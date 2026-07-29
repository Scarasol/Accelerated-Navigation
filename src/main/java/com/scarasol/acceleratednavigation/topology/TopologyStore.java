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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Chunk-coalesced topology facts backed by Minecraft's crash-resistant RegionFile. */
public final class TopologyStore implements AutoCloseable {

    private static final int MAGIC = 0x414E544F;
    private static final int SCHEMA_VERSION = 3;
    private static final int ALGORITHM_VERSION = 2;
    private static final int MAX_SECTIONS_PER_CHUNK = 1_024;
    private static final int MAX_DECODED_CHUNKS = 256;
    private static final int MAX_OPEN_REGIONS = 256;
    private static final int MAX_FOREGROUND_BURST = 16;

    private final Object monitor = new Object();
    private final Path root;
    private final LinkedHashMap<ChunkKey, ChunkImage> decoded =
            new LinkedHashMap<>(64, 0.75F, true);
    private final Map<ChunkKey, CompletableFuture<ChunkImage>> loads = new HashMap<>();
    private final Map<ChunkKey, PendingChunk> pending = new HashMap<>();
    private final ArrayDeque<IoTask> foreground = new ArrayDeque<>();
    private final ArrayDeque<IoTask> background = new ArrayDeque<>();
    private final LinkedHashMap<RegionKey, RegionFile> regions =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Thread worker;

    private boolean accepting = true;
    private boolean stopWorker;
    private int foregroundBurst;
    private int pendingHighWatermark;
    private volatile int openRegionCount;
    private long generation;
    private long submittedTasks;
    private long completedTasks;
    private long totalQueueWaitNanos;
    private long maximumQueueWaitNanos;
    private long physicalReads;
    private long coalescedReads;
    private long physicalWrites;
    private long flushes;
    private long writeFailures;
    private long droppedChunks;

    public TopologyStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root");
        Files.createDirectories(root);
        worker = new Thread(this::runWorker, "accelerated-navigation-topology-io");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    CompletableFuture<Optional<BaseClusterTopology.PackedFacts>> read(
            ResourceKey<Level> dimension,
            SectionPos section) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        ChunkKey key = new ChunkKey(dimension, new ChunkPos(section.x(), section.z()));
        int sectionY = section.y();
        CompletableFuture<ChunkImage> load;
        synchronized (monitor) {
            ensureAccepting();
            PendingChunk dirty = pending.get(key);
            if (dirty != null) {
                dirty.unloaded = false;
                BaseClusterTopology.PackedFacts facts = dirty.latest(sectionY);
                if (facts != null) {
                    return CompletableFuture.completedFuture(Optional.of(facts));
                }
            }
            ChunkImage cached = decoded.get(key);
            if (cached != null) {
                return CompletableFuture.completedFuture(Optional.ofNullable(cached.sections.get(sectionY)));
            }
            load = loads.get(key);
            if (load == null) {
                load = new CompletableFuture<>();
                loads.put(key, load);
                CompletableFuture<ChunkImage> expected = load;
                enqueueLocked(foreground, () -> loadChunk(key, expected));
            } else {
                coalescedReads++;
            }
        }
        return load.thenApply(image -> Optional.ofNullable(latest(key, sectionY, image)));
    }

    void markDirty(ResourceKey<Level> dimension,
                   SectionPos section,
                   BaseClusterTopology.PackedFacts facts) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(facts, "facts");
        synchronized (monitor) {
            ensureAccepting();
            ChunkKey key = new ChunkKey(dimension, new ChunkPos(section.x(), section.z()));
            PendingChunk dirty = pending.computeIfAbsent(key, ignored -> new PendingChunk());
            long now = System.nanoTime();
            SectionUpdate replaced = dirty.updates.put(
                    section.y(),
                    new SectionUpdate(++generation, now, facts)
            );
            if (replaced != null && replaced.enqueuedNanos == dirty.oldestUpdateNanos) {
                dirty.refreshOldestUpdate();
            } else if (dirty.oldestUpdateNanos == 0L) {
                dirty.oldestUpdateNanos = now;
            }
            pendingHighWatermark = Math.max(pendingHighWatermark, pending.size());
            if (dirty.unloaded) {
                dirty.writeRequested = true;
                scheduleWriteLocked(key, dirty);
            }
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
            if (dirty != null) {
                dirty.unloaded = true;
                dirty.writeRequested = true;
                scheduleWriteLocked(key, dirty);
            }
        }
    }

    CompletableFuture<Void> save(ResourceKey<Level> dimension) {
        Objects.requireNonNull(dimension, "dimension");
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        synchronized (monitor) {
            ensureAccepting();
            for (Map.Entry<ChunkKey, PendingChunk> entry : pending.entrySet()) {
                if (entry.getKey().dimension.equals(dimension) && !entry.getValue().updates.isEmpty()) {
                    writes.add(requestWriteLocked(entry.getKey(), entry.getValue()));
                }
            }
        }
        return settle(writes).thenCompose(ignored -> submitForeground(() -> flush(dimension)));
    }

    Metrics metrics() {
        synchronized (monitor) {
            long now = System.nanoTime();
            long oldest = 0L;
            for (PendingChunk chunk : pending.values()) {
                if (chunk.oldestUpdateNanos != 0L) {
                    oldest = Math.max(oldest, now - chunk.oldestUpdateNanos);
                }
            }
            return new Metrics(
                    foreground.size() + background.size(),
                    submittedTasks,
                    completedTasks,
                    totalQueueWaitNanos,
                    maximumQueueWaitNanos,
                    physicalReads,
                    coalescedReads,
                    physicalWrites,
                    flushes,
                    pending.size(),
                    pendingHighWatermark,
                    oldest,
                    writeFailures,
                    droppedChunks,
                    decoded.size(),
                    loads.size(),
                    openRegionCount
            );
        }
    }

    @Override
    public void close() {
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        synchronized (monitor) {
            if (!accepting) {
                return;
            }
            accepting = false;
            for (Map.Entry<ChunkKey, PendingChunk> entry : pending.entrySet()) {
                if (!entry.getValue().updates.isEmpty()) {
                    entry.getValue().unloaded = true;
                    writes.add(requestWriteLocked(entry.getKey(), entry.getValue()));
                }
            }
        }
        CompletableFuture<Void> closeFuture = settle(writes)
                .thenCompose(ignored -> retryClosingWrites())
                .thenCompose(ignored -> submitForeground(this::flushAndCloseRegions));
        try {
            closeFuture.join();
        } catch (CompletionException failure) {
            AcceleratedNavigation.LOGGER.error("Could not close macro topology store", failure.getCause());
        } finally {
            synchronized (monitor) {
                stopWorker = true;
                monitor.notifyAll();
            }
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CompletableFuture<Void> retryClosingWrites() {
        List<CompletableFuture<Void>> retries = new ArrayList<>();
        synchronized (monitor) {
            for (Map.Entry<ChunkKey, PendingChunk> entry : pending.entrySet()) {
                if (!entry.getValue().updates.isEmpty()) {
                    retries.add(requestWriteLocked(entry.getKey(), entry.getValue()));
                }
            }
        }
        return settle(retries);
    }

    private BaseClusterTopology.PackedFacts latest(ChunkKey key, int sectionY, ChunkImage fallback) {
        synchronized (monitor) {
            PendingChunk dirty = pending.get(key);
            if (dirty != null) {
                BaseClusterTopology.PackedFacts facts = dirty.latest(sectionY);
                if (facts != null) {
                    return facts;
                }
            }
            ChunkImage cached = decoded.get(key);
            return (cached == null ? fallback : cached).sections.get(sectionY);
        }
    }

    private void loadChunk(ChunkKey key, CompletableFuture<ChunkImage> result) {
        ChunkImage image;
        try {
            synchronized (monitor) {
                image = decoded.get(key);
            }
            if (image == null) {
                image = readChunk(key);
            }
        } catch (IOException failure) {
            synchronized (monitor) {
                loads.remove(key, result);
            }
            result.completeExceptionally(failure);
            return;
        }
        synchronized (monitor) {
            loads.remove(key, result);
            cacheDecodedLocked(key, image);
        }
        result.complete(image);
    }

    private CompletableFuture<Void> requestWriteLocked(ChunkKey key, PendingChunk dirty) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        long target = dirty.latestGeneration();
        if (target <= dirty.writtenGeneration) {
            result.complete(null);
            return result;
        }
        dirty.waiters.add(new WriteWaiter(target, result));
        dirty.writeRequested = true;
        scheduleWriteLocked(key, dirty);
        return result;
    }

    private void scheduleWriteLocked(ChunkKey key, PendingChunk dirty) {
        if (dirty.writeInFlight || !dirty.writeRequested || dirty.updates.isEmpty()) {
            return;
        }
        dirty.writeRequested = false;
        dirty.writeInFlight = true;
        long target = dirty.latestGeneration();
        Map<Integer, SectionUpdate> updates = Map.copyOf(dirty.updates);
        enqueueLocked(background, () -> writeChunk(key, dirty, target, updates));
    }

    private void writeChunk(ChunkKey key,
                            PendingChunk expected,
                            long target,
                            Map<Integer, SectionUpdate> updates) {
        ChunkImage base;
        try {
            synchronized (monitor) {
                base = decoded.get(key);
            }
            if (base == null) {
                base = readChunk(key);
            }
            Map<Integer, BaseClusterTopology.PackedFacts> merged =
                    new LinkedHashMap<>(base.sections);
            updates.forEach((sectionY, update) -> merged.put(sectionY, update.facts));
            ChunkImage image = new ChunkImage(Map.copyOf(merged));
            synchronized (monitor) {
                if (pending.get(key) == expected) {
                    expected.currentImage = image;
                }
            }
            writeChunkRecord(key, image);
            completeWrite(key, expected, target, image, null);
        } catch (IOException | RuntimeException failure) {
            completeWrite(key, expected, target, null, failure);
        }
    }

    private void completeWrite(ChunkKey key,
                               PendingChunk expected,
                               long target,
                               ChunkImage image,
                               Throwable failure) {
        List<CompletableFuture<Void>> completed = new ArrayList<>();
        synchronized (monitor) {
            PendingChunk dirty = pending.get(key);
            if (dirty != expected) {
                return;
            }
            dirty.writeInFlight = false;
            dirty.currentImage = null;
            if (failure == null) {
                physicalWrites++;
                dirty.failedAttempts = 0;
                dirty.writtenGeneration = Math.max(dirty.writtenGeneration, target);
                dirty.updates.entrySet().removeIf(entry -> entry.getValue().generation <= target);
                dirty.refreshOldestUpdate();
                cacheDecodedLocked(key, image);
            } else {
                writeFailures++;
                dirty.failedAttempts++;
                AcceleratedNavigation.LOGGER.warn("Could not persist macro topology chunk {}", key, failure);
                if (dirty.unloaded && dirty.failedAttempts >= 2) {
                    dirty.updates.entrySet().removeIf(entry -> entry.getValue().generation <= target);
                    dirty.refreshOldestUpdate();
                    droppedChunks++;
                }
            }
            Iterator<WriteWaiter> iterator = dirty.waiters.iterator();
            while (iterator.hasNext()) {
                WriteWaiter waiter = iterator.next();
                if (waiter.generation <= target) {
                    iterator.remove();
                    completed.add(waiter.future);
                }
            }
            if (dirty.updates.isEmpty() && !dirty.writeInFlight) {
                pending.remove(key, dirty);
            } else if (dirty.writeRequested && (failure == null
                    || dirty.latestGeneration() > target)) {
                scheduleWriteLocked(key, dirty);
            }
        }
        for (CompletableFuture<Void> future : completed) {
            if (failure == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(failure);
            }
        }
    }

    private ChunkImage readChunk(ChunkKey key) throws IOException {
        synchronized (monitor) {
            physicalReads++;
        }
        RegionFile region = region(key);
        try (DataInputStream input = region.getChunkDataInputStream(key.chunk)) {
            if (input == null) {
                return ChunkImage.EMPTY;
            }
            try {
                return decodeChunk(input);
            } catch (EOFException | IllegalArgumentException corrupt) {
                return ChunkImage.EMPTY;
            }
        }
    }

    private static ChunkImage decodeChunk(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC
                || input.readInt() != SCHEMA_VERSION
                || input.readInt() != ALGORITHM_VERSION) {
            return ChunkImage.EMPTY;
        }
        int sectionCount = input.readInt();
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS_PER_CHUNK) {
            return ChunkImage.EMPTY;
        }
        Map<Integer, BaseClusterTopology.PackedFacts> sections = new LinkedHashMap<>();
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = input.readInt();
            byte[] packed = new byte[BaseClusterTopology.PACKED_FACT_BYTES];
            input.readFully(packed);
            if (sections.put(sectionY, BaseClusterTopology.PackedFacts.fromBytes(packed)) != null) {
                return ChunkImage.EMPTY;
            }
        }
        return input.read() == -1 ? new ChunkImage(Map.copyOf(sections)) : ChunkImage.EMPTY;
    }

    private void writeChunkRecord(ChunkKey key, ChunkImage image) throws IOException {
        if (image.sections.size() > MAX_SECTIONS_PER_CHUNK) {
            throw new IOException("too many topology sections in one chunk");
        }
        RegionFile region = region(key);
        try (DataOutputStream output = region.getChunkDataOutputStream(key.chunk)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(ALGORITHM_VERSION);
            output.writeInt(image.sections.size());
            for (Map.Entry<Integer, BaseClusterTopology.PackedFacts> section
                    : image.sections.entrySet()) {
                output.writeInt(section.getKey());
                output.write(section.getValue().bytes());
            }
        }
    }

    private void flush(ResourceKey<Level> dimension) {
        for (Map.Entry<RegionKey, RegionFile> entry : regions.entrySet()) {
            if (!entry.getKey().dimension.equals(dimension.location())) {
                continue;
            }
            try {
                entry.getValue().flush();
                synchronized (monitor) {
                    flushes++;
                }
            } catch (IOException failure) {
                AcceleratedNavigation.LOGGER.warn(
                        "Could not flush macro topology for {}",
                        dimension.location(),
                        failure
                );
            }
        }
    }

    private void flushAndCloseRegions() {
        for (Map.Entry<RegionKey, RegionFile> entry : regions.entrySet()) {
            try {
                entry.getValue().flush();
                synchronized (monitor) {
                    flushes++;
                }
            } catch (IOException failure) {
                AcceleratedNavigation.LOGGER.warn("Could not flush macro topology region {}", entry.getKey(), failure);
            }
            try {
                entry.getValue().close();
            } catch (IOException failure) {
                AcceleratedNavigation.LOGGER.warn("Could not close macro topology region {}", entry.getKey(), failure);
            }
        }
        regions.clear();
        openRegionCount = 0;
    }

    private RegionFile region(ChunkKey chunk) throws IOException {
        RegionKey key = new RegionKey(
                chunk.dimension.location(),
                chunk.chunk.getRegionX(),
                chunk.chunk.getRegionZ()
        );
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
                directory,
                false
        );
        regions.put(key, created);
        openRegionCount = regions.size();
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

    private void cacheDecodedLocked(ChunkKey key, ChunkImage image) {
        decoded.put(key, image);
        while (decoded.size() > MAX_DECODED_CHUNKS) {
            decoded.remove(decoded.entrySet().iterator().next().getKey());
        }
    }

    private CompletableFuture<Void> submitForeground(IoOperation operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (monitor) {
            enqueueLocked(foreground, () -> {
                try {
                    operation.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        }
        return result;
    }

    private void enqueueLocked(ArrayDeque<IoTask> queue, Runnable command) {
        queue.addLast(new IoTask(System.nanoTime(), command));
        submittedTasks++;
        monitor.notifyAll();
    }

    private void runWorker() {
        while (true) {
            IoTask task;
            synchronized (monitor) {
                while (foreground.isEmpty() && background.isEmpty() && !stopWorker) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException ignored) {
                        if (stopWorker) {
                            return;
                        }
                    }
                }
                if (foreground.isEmpty() && background.isEmpty() && stopWorker) {
                    return;
                }
                if (!foreground.isEmpty()
                        && (background.isEmpty() || foregroundBurst < MAX_FOREGROUND_BURST)) {
                    task = foreground.removeFirst();
                    foregroundBurst++;
                } else {
                    task = background.removeFirst();
                    foregroundBurst = 0;
                }
                long waited = Math.max(0L, System.nanoTime() - task.enqueuedNanos);
                totalQueueWaitNanos += waited;
                maximumQueueWaitNanos = Math.max(maximumQueueWaitNanos, waited);
            }
            try {
                task.command.run();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                AcceleratedNavigation.LOGGER.error("Unhandled macro topology I/O task failure", failure);
            } finally {
                synchronized (monitor) {
                    completedTasks++;
                }
            }
        }
    }

    private static CompletableFuture<Void> settle(List<CompletableFuture<Void>> futures) {
        return CompletableFuture.allOf(futures.stream()
                .map(future -> future.exceptionally(ignored -> null))
                .toArray(CompletableFuture[]::new));
    }

    private void ensureAccepting() {
        if (!accepting) {
            throw new IllegalStateException("topology store is closed");
        }
    }

    record Metrics(int queuedTasks,
                   long submittedTasks,
                   long completedTasks,
                   long totalQueueWaitNanos,
                   long maximumQueueWaitNanos,
                   long physicalReads,
                   long coalescedReads,
                   long physicalWrites,
                   long flushes,
                   int pendingChunks,
                   int pendingHighWatermark,
                   long oldestPendingNanos,
                   long writeFailures,
                   long droppedChunks,
                   int decodedChunks,
                   int inFlightLoads,
                   int openRegions) {
    }

    private static final class PendingChunk {
        private final Map<Integer, SectionUpdate> updates = new HashMap<>();
        private final List<WriteWaiter> waiters = new ArrayList<>();
        private ChunkImage currentImage;
        private boolean unloaded;
        private boolean writeRequested;
        private boolean writeInFlight;
        private int failedAttempts;
        private long writtenGeneration;
        private long oldestUpdateNanos;

        private BaseClusterTopology.PackedFacts latest(int sectionY) {
            SectionUpdate update = updates.get(sectionY);
            if (update != null) {
                return update.facts;
            }
            return currentImage == null ? null : currentImage.sections.get(sectionY);
        }

        private long latestGeneration() {
            return updates.values().stream().mapToLong(update -> update.generation).max().orElse(0L);
        }

        private void refreshOldestUpdate() {
            oldestUpdateNanos = updates.values().stream()
                    .mapToLong(update -> update.enqueuedNanos)
                    .min()
                    .orElse(0L);
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

    private record ChunkImage(Map<Integer, BaseClusterTopology.PackedFacts> sections) {
        private static final ChunkImage EMPTY = new ChunkImage(Map.of());
    }

    private record SectionUpdate(long generation,
                                 long enqueuedNanos,
                                 BaseClusterTopology.PackedFacts facts) {
    }

    private record WriteWaiter(long generation, CompletableFuture<Void> future) {
    }

    private record IoTask(long enqueuedNanos, Runnable command) {
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }
}
