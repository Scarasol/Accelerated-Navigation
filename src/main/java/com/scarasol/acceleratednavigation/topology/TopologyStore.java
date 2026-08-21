package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Versioned section facts backed by Minecraft's crash-resistant RegionFile. */
final class TopologyStore implements AutoCloseable {

    private static final int MAGIC = 0x414E544F;
    private static final int SCHEMA_VERSION = 4;
    private static final int MAX_SECTIONS_PER_CHUNK = 1_024;
    private static final int MAX_DECODED_CHUNKS = 256;
    private static final int MAX_OPEN_REGIONS = 256;
    private static final int MAX_FOREGROUND_BURST = 16;
    private static final long CLOSE_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    private final Object monitor = new Object();
    private final Path root;
    private final LinkedHashMap<ChunkKey, DecodedChunk> decoded =
            new LinkedHashMap<>(64, 0.75F, true);
    private final Map<ChunkKey, CompletableFuture<ChunkLoad>> loads = new HashMap<>();
    private final Map<ChunkKey, PendingChunk> pending = new HashMap<>();
    private final Map<ResourceKey<Level>, Integer> dirtyChunksByDimension = new HashMap<>();
    private final Set<ResourceKey<Level>> requestedFlushes = new HashSet<>();
    private final Set<ResourceKey<Level>> queuedFlushes = new HashSet<>();
    private final ArrayDeque<IoTask> foreground = new ArrayDeque<>();
    private final ArrayDeque<IoTask> background = new ArrayDeque<>();
    private final LinkedHashMap<RegionKey, RegionFile> regions =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Thread worker;

    private boolean accepting = true;
    private boolean closing;
    private int foregroundBurst;
    private int highestPendingChunks;
    private int highestQueuedTasks;
    private long readRequests;
    private long recordsFound;
    private long recordsMissing;
    private long readFailures;
    private long writeRequests;
    private long recordsWritten;
    private long recordsCoalesced;
    private long writeFailures;
    private long saveRequests;
    private long flushes;
    private long flushFailures;

    TopologyStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root");
        Files.createDirectories(root);
        worker = new Thread(this::runWorker, "accelerated-navigation-topology-io");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    CompletableFuture<ReadResult> read(ResourceKey<Level> dimension, SectionPos section) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        ChunkKey key = new ChunkKey(dimension, new ChunkPos(section.x(), section.z()));
        int sectionY = section.y();
        CompletableFuture<ChunkLoad> load;
        synchronized (monitor) {
            ensureAccepting();
            readRequests++;
            ChunkLoad cached = decodedLoadLocked(key);
            if (cached != null) {
                ReadResult result = latestLocked(key, sectionY, cached);
                recordReadLocked(result);
                return CompletableFuture.completedFuture(result);
            }
            load = loads.get(key);
            if (load == null) {
                load = new CompletableFuture<>();
                loads.put(key, load);
                CompletableFuture<ChunkLoad> expected = load;
                enqueueLocked(foreground, () -> loadChunk(key, expected));
            }
        }
        return load.thenApply(image -> {
            synchronized (monitor) {
                ReadResult result = latestLocked(key, sectionY, image);
                recordReadLocked(result);
                return result;
            }
        });
    }

    CompletableFuture<UpdateResult> writeFull(ResourceKey<Level> dimension,
                                              SectionPos section,
                                              long version,
                                              BaseClusterTopology.PackedFacts facts) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        SectionRecord record = new SectionRecord(version, facts);
        CompletableFuture<UpdateResult> result = new CompletableFuture<>();
        CompletableFuture<UpdateResult> superseded = null;
        synchronized (monitor) {
            ensureAccepting();
            writeRequests++;
            ChunkKey key = new ChunkKey(dimension, new ChunkPos(section.x(), section.z()));
            PendingChunk chunk = pendingChunkLocked(key);
            PendingSection previous = chunk.sections.get(section.y());
            if (previous != null) {
                superseded = previous.completion;
                recordsCoalesced++;
            }
            chunk.sections.put(section.y(), new PendingSection(
                    new FullMutation(record), result));
            scheduleWriteLocked(key, chunk);
        }
        if (superseded != null) {
            superseded.complete(UpdateResult.coalesced());
        }
        return result;
    }

    CompletableFuture<UpdateResult> mergeDelta(ResourceKey<Level> dimension,
                                               SectionPos section,
                                               SectionDelta delta) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(delta, "delta");
        CompletableFuture<UpdateResult> result = new CompletableFuture<>();
        CompletableFuture<UpdateResult> superseded = null;
        synchronized (monitor) {
            ensureAccepting();
            writeRequests++;
            ChunkKey key = new ChunkKey(dimension, new ChunkPos(section.x(), section.z()));
            PendingChunk chunk = pendingChunkLocked(key);
            PendingSection previous = chunk.sections.get(section.y());
            Mutation mutation;
            if (previous == null) {
                long expected = chunk.inFlightTargetVersion(section.y());
                if (expected >= 0L && expected != delta.originalVersion) {
                    removePendingChunkIfIdleLocked(key, chunk);
                    writeFailures++;
                    return CompletableFuture.completedFuture(UpdateResult.versionMismatch());
                }
                mutation = new DeltaMutation(delta);
            } else {
                mutation = appendDelta(previous.mutation, delta);
                if (mutation == null) {
                    writeFailures++;
                    return CompletableFuture.completedFuture(UpdateResult.versionMismatch());
                }
            }
            if (previous != null) {
                superseded = previous.completion;
                recordsCoalesced++;
            }
            chunk.sections.put(section.y(), new PendingSection(mutation, result));
            scheduleWriteLocked(key, chunk);
        }
        if (superseded != null) {
            superseded.complete(UpdateResult.coalesced());
        }
        return result;
    }

    private static Mutation appendDelta(Mutation previous, SectionDelta next) {
        if (previous.targetVersion() != next.originalVersion) {
            return null;
        }
        if (previous instanceof FullMutation full) {
            return new FullMutation(new SectionRecord(
                    next.newVersion,
                    full.record.facts.withChanges(next.cells)));
        }
        DeltaMutation delta = (DeltaMutation) previous;
        Map<Integer, Byte> merged = new HashMap<>(delta.delta.cells);
        merged.putAll(next.cells);
        return new DeltaMutation(new SectionDelta(
                delta.delta.originalVersion, next.newVersion, merged));
    }

    void requestSave(ResourceKey<Level> dimension) {
        Objects.requireNonNull(dimension, "dimension");
        synchronized (monitor) {
            ensureAccepting();
            saveRequests++;
            requestedFlushes.add(dimension);
            scheduleFlushIfReadyLocked(dimension);
        }
    }

    void unload(ResourceKey<Level> dimension, ChunkPos chunk) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        synchronized (monitor) {
            if (!accepting) {
                return;
            }
            ChunkKey key = new ChunkKey(dimension, chunk);
            PendingChunk dirty = pending.get(key);
            if (dirty == null) {
                decoded.remove(key);
                return;
            }
            dirty.unloaded = true;
            scheduleWriteLocked(key, dirty);
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (!closing) {
                accepting = false;
                closing = true;
                monitor.notifyAll();
            }
        }
        try {
            worker.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            AcceleratedNavigation.LOGGER.error(
                    "Timed out while closing macro topology persistence after {} ms",
                    CLOSE_WAIT_MILLIS);
        }
    }

    TopologyService.PersistenceMetrics metrics() {
        synchronized (monitor) {
            return new TopologyService.PersistenceMetrics(
                    pending.size(),
                    highestPendingChunks,
                    loads.size(),
                    foreground.size(),
                    background.size(),
                    highestQueuedTasks,
                    decoded.size(),
                    readRequests,
                    recordsFound,
                    recordsMissing,
                    readFailures,
                    writeRequests,
                    recordsWritten,
                    recordsCoalesced,
                    writeFailures,
                    saveRequests,
                    flushes,
                    flushFailures,
                    accepting,
                    closing
            );
        }
    }

    private PendingChunk pendingChunkLocked(ChunkKey key) {
        PendingChunk chunk = pending.get(key);
        if (chunk != null) {
            chunk.unloaded = false;
            return chunk;
        }
        chunk = new PendingChunk();
        pending.put(key, chunk);
        highestPendingChunks = Math.max(highestPendingChunks, pending.size());
        dirtyChunksByDimension.merge(key.dimension, 1, Integer::sum);
        return chunk;
    }

    private void scheduleWriteLocked(ChunkKey key, PendingChunk chunk) {
        if (chunk.inFlight != null || chunk.sections.isEmpty()) {
            return;
        }
        Map<Integer, PendingSection> batch = Map.copyOf(chunk.sections);
        chunk.sections.clear();
        chunk.inFlight = batch;
        enqueueLocked(background, () -> writeChunk(key, chunk, batch));
    }

    private void writeChunk(ChunkKey key,
                            PendingChunk expected,
                            Map<Integer, PendingSection> batch) {
        ChunkLoad base;
        synchronized (monitor) {
            base = decodedLoadLocked(key);
        }
        if (base == null) {
            try {
                base = readChunk(key);
            } catch (IOException | RuntimeException failure) {
                completeWrite(key, expected, batch, null,
                        uniformStatuses(batch, UpdateStatus.IO_FAILURE), failure);
                return;
            }
        }
        Map<Integer, UpdateStatus> statuses = new HashMap<>();
        ChunkImage image = null;
        Throwable writeFailure = null;
        try {
            Map<Integer, SectionRecord> merged = base.status == ChunkStatus.PRESENT
                    ? new LinkedHashMap<>(base.image.sections) : new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<Integer, PendingSection> entry : batch.entrySet()) {
                Applied applied = apply(entry.getValue().mutation,
                        merged.get(entry.getKey()), base.status);
                statuses.put(entry.getKey(), applied.status);
                if (applied.record != null) {
                    merged.put(entry.getKey(), applied.record);
                    changed = true;
                }
            }
            if (changed) {
                image = new ChunkImage(Map.copyOf(merged));
                writeChunkRecord(key, image);
                statuses.replaceAll((ignored, status) -> status == null
                        ? UpdateStatus.WRITTEN : status);
            }
        } catch (IOException | RuntimeException failure) {
            image = null;
            // Keep semantic mismatches already discovered; every unfinished mutation failed persistence.
            for (Integer sectionY : batch.keySet()) {
                statuses.compute(sectionY, (ignored, status) -> status == null
                        ? UpdateStatus.IO_FAILURE : status);
            }
            writeFailure = failure;
        }
        completeWrite(key, expected, batch, image, statuses, writeFailure);
    }

    private static Applied apply(Mutation mutation,
                                 SectionRecord base,
                                 ChunkStatus chunkStatus) {
        if (mutation instanceof FullMutation full) {
            return new Applied(full.record, null);
        }
        DeltaMutation pending = (DeltaMutation) mutation;
        if (base == null) {
            return new Applied(null, chunkStatus == ChunkStatus.CORRUPT
                    ? UpdateStatus.CORRUPT : UpdateStatus.BASE_MISSING);
        }
        if (base.version != pending.delta.originalVersion) {
            return new Applied(null, UpdateStatus.VERSION_MISMATCH);
        }
        return new Applied(new SectionRecord(
                pending.delta.newVersion,
                base.facts.withChanges(pending.delta.cells)), null);
    }

    private static Map<Integer, UpdateStatus> uniformStatuses(
            Map<Integer, PendingSection> batch,
            UpdateStatus status) {
        Map<Integer, UpdateStatus> statuses = new HashMap<>();
        batch.keySet().forEach(sectionY -> statuses.put(sectionY, status));
        return statuses;
    }

    private void completeWrite(ChunkKey key,
                               PendingChunk expected,
                               Map<Integer, PendingSection> batch,
                               ChunkImage image,
                               Map<Integer, UpdateStatus> statuses,
                               Throwable failure) {
        List<Completion> completions = new ArrayList<>();
        synchronized (monitor) {
            expected.inFlight = null;
            if (image != null) {
                if (!expected.unloaded) {
                    cacheDecodedLocked(key, ChunkLoad.present(image));
                }
            }
            if (failure != null) {
                AcceleratedNavigation.LOGGER.warn(
                        "Could not persist macro topology chunk {}", key, failure);
            }
            for (Map.Entry<Integer, PendingSection> entry : batch.entrySet()) {
                UpdateStatus status = statuses.get(entry.getKey());
                UpdateResult result = new UpdateResult(status, failure);
                if (status == UpdateStatus.WRITTEN) recordsWritten++;
                else writeFailures++;
                completions.add(new Completion(entry.getValue().completion, result));
            }
            scheduleWriteLocked(key, expected);
            removePendingChunkIfIdleLocked(key, expected);
        }
        completions.forEach(Completion::complete);
    }

    private void removePendingChunkIfIdleLocked(ChunkKey key, PendingChunk chunk) {
        if (chunk.inFlight != null || !chunk.sections.isEmpty()
                || !pending.remove(key, chunk)) {
            return;
        }
        dirtyChunksByDimension.computeIfPresent(key.dimension, (ignored, count) ->
                count == 1 ? null : count - 1);
        if (chunk.unloaded) {
            decoded.remove(key);
        }
        scheduleFlushIfReadyLocked(key.dimension);
    }

    private void scheduleFlushIfReadyLocked(ResourceKey<Level> dimension) {
        if (!requestedFlushes.contains(dimension)
                || dirtyChunksByDimension.containsKey(dimension)
                || !queuedFlushes.add(dimension)) {
            return;
        }
        enqueueLocked(background, () -> runRequestedFlush(dimension));
    }

    private void runRequestedFlush(ResourceKey<Level> dimension) {
        synchronized (monitor) {
            queuedFlushes.remove(dimension);
            if (dirtyChunksByDimension.containsKey(dimension)
                    || !requestedFlushes.remove(dimension)) {
                return;
            }
        }
        int failures = flush(dimension);
        synchronized (monitor) {
            flushes++;
            flushFailures += failures;
        }
    }

    private void loadChunk(ChunkKey key, CompletableFuture<ChunkLoad> result) {
        ChunkLoad load;
        try {
            load = readChunk(key);
        } catch (IOException | RuntimeException failure) {
            synchronized (monitor) {
                loads.remove(key, result);
            }
            result.complete(ChunkLoad.ioFailure(failure));
            return;
        }
        synchronized (monitor) {
            loads.remove(key, result);
            cacheDecodedLocked(key, load);
        }
        result.complete(load);
    }

    private ReadResult latestLocked(ChunkKey key, int sectionY, ChunkLoad load) {
        if (load.status == ChunkStatus.IO_FAILURE) {
            return ReadResult.ioFailure(load.failure);
        }
        SectionRecord record = load.status == ChunkStatus.PRESENT
                ? load.image.sections.get(sectionY) : null;
        PendingChunk dirty = pending.get(key);
        if (dirty != null) {
            record = applyForRead(record, load.status,
                    dirty.inFlight == null ? null : dirty.inFlight.get(sectionY));
            record = applyForRead(record, load.status, dirty.sections.get(sectionY));
        }
        if (record != null) {
            return ReadResult.found(record);
        }
        return load.status == ChunkStatus.CORRUPT
                ? ReadResult.corrupt() : ReadResult.missing();
    }

    private static SectionRecord applyForRead(SectionRecord base,
                                              ChunkStatus status,
                                              PendingSection pending) {
        if (pending == null) {
            return base;
        }
        Applied applied = apply(pending.mutation, base, status);
        return applied.record;
    }

    private ChunkLoad readChunk(ChunkKey key) throws IOException {
        RegionFile region = region(key);
        try (DataInputStream input = region.getChunkDataInputStream(key.chunk)) {
            if (input == null) {
                return ChunkLoad.missing();
            }
            try {
                return ChunkLoad.present(decodeChunk(input));
            } catch (EOFException | IllegalArgumentException corrupt) {
                return ChunkLoad.corrupt();
            }
        }
    }

    private static ChunkImage decodeChunk(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC
                || input.readInt() != SCHEMA_VERSION
                || input.readInt() != BaseClusterTopology.FACTS_ALGORITHM_VERSION) {
            throw new IllegalArgumentException("unsupported topology facts format");
        }
        int sectionCount = input.readInt();
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS_PER_CHUNK) {
            throw new IllegalArgumentException("invalid topology section count");
        }
        Map<Integer, SectionRecord> sections = new LinkedHashMap<>();
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = input.readInt();
            long version = input.readLong();
            byte[] packed = new byte[BaseClusterTopology.PACKED_FACT_BYTES];
            input.readFully(packed);
            SectionRecord record = new SectionRecord(
                    version, BaseClusterTopology.PackedFacts.fromBytes(packed));
            if (sections.put(sectionY, record) != null) {
                throw new IllegalArgumentException("duplicate topology section");
            }
        }
        if (input.read() != -1) {
            throw new IllegalArgumentException("trailing topology record data");
        }
        return new ChunkImage(Map.copyOf(sections));
    }

    private void writeChunkRecord(ChunkKey key, ChunkImage image) throws IOException {
        if (image.sections.size() > MAX_SECTIONS_PER_CHUNK) {
            throw new IOException("too many topology sections in one chunk");
        }
        RegionFile region = region(key);
        try (DataOutputStream output = region.getChunkDataOutputStream(key.chunk)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(BaseClusterTopology.FACTS_ALGORITHM_VERSION);
            output.writeInt(image.sections.size());
            for (Map.Entry<Integer, SectionRecord> section : image.sections.entrySet()) {
                output.writeInt(section.getKey());
                output.writeLong(section.getValue().version);
                output.write(section.getValue().facts.bytes());
            }
        }
    }

    private int flush(ResourceKey<Level> dimension) {
        int failures = 0;
        for (Map.Entry<RegionKey, RegionFile> entry : regions.entrySet()) {
            if (!entry.getKey().dimension.equals(dimension.location())) {
                continue;
            }
            try {
                entry.getValue().flush();
            } catch (IOException failure) {
                failures++;
                AcceleratedNavigation.LOGGER.warn(
                        "Could not flush macro topology for {}",
                        dimension.location(), failure);
            }
        }
        return failures;
    }

    private int flushAndCloseRegions() {
        int failures = 0;
        for (Map.Entry<RegionKey, RegionFile> entry : regions.entrySet()) {
            try {
                entry.getValue().flush();
            } catch (IOException failure) {
                failures++;
                AcceleratedNavigation.LOGGER.warn(
                        "Could not flush macro topology region {}", entry.getKey(), failure);
            }
            try {
                entry.getValue().close();
            } catch (IOException failure) {
                failures++;
                AcceleratedNavigation.LOGGER.warn(
                        "Could not close macro topology region {}", entry.getKey(), failure);
            }
        }
        regions.clear();
        return failures;
    }

    private RegionFile region(ChunkKey chunk) throws IOException {
        RegionKey key = new RegionKey(chunk.dimension.location(),
                chunk.chunk.getRegionX(), chunk.chunk.getRegionZ());
        RegionFile open = regions.get(key);
        if (open != null) {
            return open;
        }
        if (regions.size() >= MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<RegionKey, RegionFile>> iterator = regions.entrySet().iterator();
            Map.Entry<RegionKey, RegionFile> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
        Path directory = dimensionDirectory(key.dimension);
        Files.createDirectories(directory);
        RegionFile created = new RegionFile(
                directory.resolve("r." + key.regionX + "." + key.regionZ + ".mca"),
                directory, false);
        regions.put(key, created);
        return created;
    }

    private Path dimensionDirectory(ResourceLocation dimension) {
        Path directory = root.resolve(dimension.getNamespace());
        for (String segment : dimension.getPath().split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid dimension path");
            }
            directory = directory.resolve(segment);
        }
        return directory;
    }

    private void cacheDecodedLocked(ChunkKey key, ChunkLoad load) {
        if (load.status == ChunkStatus.IO_FAILURE) {
            return;
        }
        decoded.put(key, DecodedChunk.capture(load));
        while (decoded.size() > MAX_DECODED_CHUNKS) {
            decoded.remove(decoded.entrySet().iterator().next().getKey());
        }
    }

    private ChunkLoad decodedLoadLocked(ChunkKey key) {
        DecodedChunk cached = decoded.get(key);
        if (cached == null) return null;
        ChunkLoad load = cached.resolve();
        if (load == null) decoded.remove(key);
        return load;
    }

    private void enqueueLocked(ArrayDeque<IoTask> queue, Runnable command) {
        queue.addLast(new IoTask(command));
        highestQueuedTasks = Math.max(highestQueuedTasks,
                foreground.size() + background.size());
        monitor.notifyAll();
    }

    private void runWorker() {
        try {
            while (true) {
                IoTask task;
                synchronized (monitor) {
                    while (foreground.isEmpty() && background.isEmpty() && !closing) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException ignored) {
                            if (closing) {
                                break;
                            }
                        }
                    }
                    if (foreground.isEmpty() && background.isEmpty() && closing) {
                        break;
                    }
                    if (!foreground.isEmpty()
                            && (background.isEmpty()
                            || foregroundBurst < MAX_FOREGROUND_BURST)) {
                        task = foreground.removeFirst();
                        foregroundBurst++;
                    } else {
                        task = background.removeFirst();
                        foregroundBurst = 0;
                    }
                }
                try {
                    task.command.run();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    AcceleratedNavigation.LOGGER.error(
                            "Unhandled macro topology I/O task failure", failure);
                }
            }
        } finally {
            int failures = flushAndCloseRegions();
            synchronized (monitor) {
                flushes++;
                flushFailures += failures;
            }
        }
    }

    private void recordReadLocked(ReadResult result) {
        switch (result.status) {
            case FOUND -> recordsFound++;
            case MISSING -> recordsMissing++;
            case CORRUPT, IO_FAILURE -> readFailures++;
        }
    }

    private void ensureAccepting() {
        if (!accepting) {
            throw new IllegalStateException("topology store is closed");
        }
    }

    enum ReadStatus {
        FOUND,
        MISSING,
        CORRUPT,
        IO_FAILURE
    }

    record SectionRecord(long version, BaseClusterTopology.PackedFacts facts) {
        SectionRecord {
            if (version < 0L) {
                throw new IllegalArgumentException("facts version cannot be negative");
            }
            Objects.requireNonNull(facts, "facts");
        }
    }

    record SectionDelta(long originalVersion,
                        long newVersion,
                        Map<Integer, Byte> cells) {
        SectionDelta {
            if (originalVersion < 0L || newVersion <= originalVersion) {
                throw new IllegalArgumentException("facts delta versions are not increasing");
            }
            Objects.requireNonNull(cells, "cells");
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("facts delta cannot be empty");
            }
            cells.forEach((cell, flags) -> {
                if (cell < 0 || cell >= BaseClusterTopology.CELL_COUNT) {
                    throw new IllegalArgumentException("facts delta cell is outside the section");
                }
                Objects.requireNonNull(flags, "facts delta flags");
                int value = Byte.toUnsignedInt(flags);
                int valid = BaseClusterTopology.VOLUME_OPEN
                        | BaseClusterTopology.GROUND_OPEN
                        | BaseClusterTopology.FLUID
                        | BaseClusterTopology.EXACT_REQUIRED;
                if ((value & ~valid) != 0) {
                    throw new IllegalArgumentException("facts delta contains unknown flags");
                }
            });
            cells = Map.copyOf(cells);
        }
    }

    record ReadResult(ReadStatus status, SectionRecord record, Throwable failure) {
        private static ReadResult found(SectionRecord record) {
            return new ReadResult(ReadStatus.FOUND, record, null);
        }

        private static ReadResult missing() {
            return new ReadResult(ReadStatus.MISSING, null, null);
        }

        private static ReadResult corrupt() {
            return new ReadResult(ReadStatus.CORRUPT, null, null);
        }

        private static ReadResult ioFailure(Throwable failure) {
            return new ReadResult(ReadStatus.IO_FAILURE, null,
                    Objects.requireNonNull(failure, "failure"));
        }
    }

    enum UpdateStatus {
        WRITTEN,
        COALESCED,
        BASE_MISSING,
        VERSION_MISMATCH,
        CORRUPT,
        IO_FAILURE
    }

    record UpdateResult(UpdateStatus status, Throwable failure) {
        private static UpdateResult versionMismatch() {
            return new UpdateResult(UpdateStatus.VERSION_MISMATCH, null);
        }

        private static UpdateResult coalesced() {
            return new UpdateResult(UpdateStatus.COALESCED, null);
        }

        boolean accepted() {
            return status == UpdateStatus.WRITTEN || status == UpdateStatus.COALESCED;
        }
    }

    private enum ChunkStatus {
        PRESENT,
        MISSING,
        CORRUPT,
        IO_FAILURE
    }

    private sealed interface Mutation permits FullMutation, DeltaMutation {
        long targetVersion();
    }

    private record FullMutation(SectionRecord record) implements Mutation {
        @Override
        public long targetVersion() {
            return record.version;
        }
    }

    private record DeltaMutation(SectionDelta delta) implements Mutation {
        @Override
        public long targetVersion() {
            return delta.newVersion;
        }
    }

    private static final class PendingChunk {
        private final Map<Integer, PendingSection> sections = new HashMap<>();
        private Map<Integer, PendingSection> inFlight;
        private boolean unloaded;

        private long inFlightTargetVersion(int sectionY) {
            PendingSection section = inFlight == null ? null : inFlight.get(sectionY);
            return section == null ? -1L : section.mutation.targetVersion();
        }
    }

    private static final class PendingSection {
        private final Mutation mutation;
        private final CompletableFuture<UpdateResult> completion;

        private PendingSection(Mutation mutation,
                               CompletableFuture<UpdateResult> completion) {
            this.mutation = mutation;
            this.completion = completion;
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos chunk) {
        private ChunkKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    private record RegionKey(ResourceLocation dimension, int regionX, int regionZ) {
    }

    private record ChunkImage(Map<Integer, SectionRecord> sections) {
        private static final ChunkImage EMPTY = new ChunkImage(Map.of());
    }

    private record ChunkLoad(ChunkStatus status, ChunkImage image, Throwable failure) {
        private static ChunkLoad present(ChunkImage image) {
            return new ChunkLoad(ChunkStatus.PRESENT, image, null);
        }

        private static ChunkLoad missing() {
            return new ChunkLoad(ChunkStatus.MISSING, ChunkImage.EMPTY, null);
        }

        private static ChunkLoad corrupt() {
            return new ChunkLoad(ChunkStatus.CORRUPT, ChunkImage.EMPTY, null);
        }

        private static ChunkLoad ioFailure(Throwable failure) {
            return new ChunkLoad(ChunkStatus.IO_FAILURE, ChunkImage.EMPTY, failure);
        }
    }

    /** The I/O cache does not become a second strong owner of runtime facts. */
    private static final class DecodedChunk {
        private final ChunkStatus status;
        private final Map<Integer, WeakSectionRecord> sections;

        private DecodedChunk(ChunkStatus status, Map<Integer, WeakSectionRecord> sections) {
            this.status = status;
            this.sections = sections;
        }

        private static DecodedChunk capture(ChunkLoad load) {
            if (load.status != ChunkStatus.PRESENT) {
                return new DecodedChunk(load.status, Map.of());
            }
            Map<Integer, WeakSectionRecord> records = new LinkedHashMap<>();
            load.image.sections.forEach((sectionY, record) -> records.put(
                    sectionY, new WeakSectionRecord(
                            record.version, new WeakReference<>(record.facts))));
            return new DecodedChunk(load.status, Map.copyOf(records));
        }

        private ChunkLoad resolve() {
            if (status == ChunkStatus.MISSING) return ChunkLoad.missing();
            if (status == ChunkStatus.CORRUPT) return ChunkLoad.corrupt();
            if (status != ChunkStatus.PRESENT) return null;
            Map<Integer, SectionRecord> records = new LinkedHashMap<>();
            for (Map.Entry<Integer, WeakSectionRecord> entry : sections.entrySet()) {
                BaseClusterTopology.PackedFacts facts = entry.getValue().facts.get();
                if (facts == null) return null;
                records.put(entry.getKey(), new SectionRecord(
                        entry.getValue().version, facts));
            }
            return ChunkLoad.present(new ChunkImage(Map.copyOf(records)));
        }
    }

    private record WeakSectionRecord(long version,
                                     WeakReference<BaseClusterTopology.PackedFacts> facts) {
    }

    private record Applied(SectionRecord record, UpdateStatus status) {
    }

    private record Completion(CompletableFuture<UpdateResult> future,
                              UpdateResult result) {
        private void complete() {
            future.complete(result);
        }
    }

    private record IoTask(Runnable command) {
    }
}
