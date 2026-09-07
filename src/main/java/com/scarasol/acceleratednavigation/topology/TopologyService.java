package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Server-thread owner of basic facts, persistence, recovery and caller publication. */
public final class TopologyService {

    private static final Map<MinecraftServer, TopologyService> SERVICES = new IdentityHashMap<>();
    private static final int FACT_MASK = BaseClusterTopology.VOLUME_OPEN
            | BaseClusterTopology.GROUND_OPEN | BaseClusterTopology.FLUID
            | BaseClusterTopology.EXACT_REQUIRED;
    private static final int COLLIDES = 1 << 4;
    private static final int HAS_ABOVE = 1 << 16;
    private static final int MAX_MACRO_REQUESTS = 1_024;
    private static final long WORKER_CLOSE_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    private final MinecraftServer server;
    private final TopologyWorkerRuntime runtime;
    @Nullable
    private final TopologyStore store;
    private final Map<ChunkKey, LoadedChunk> loadedChunks = new HashMap<>();
    private final Map<TopologyWorkerRuntime.ClusterKey, LoadedSection> loadedSections =
            new HashMap<>();
    private final LinkedHashSet<TopologyWorkerRuntime.ClusterKey> foregroundRecovery =
            new LinkedHashSet<>();
    private final LinkedHashSet<TopologyWorkerRuntime.ClusterKey> ordinaryRecovery =
            new LinkedHashSet<>();
    private final LinkedHashSet<LoadedSection> changedSections = new LinkedHashSet<>();
    private final Map<RequestKey, MacroRequest> macroRequests = new HashMap<>();
    private final Object factDemandLock = new Object();
    private Map<FactDemandKey, Integer> factDemandChanges = new HashMap<>();
    private boolean factDemandNoticeQueued;
    private final ConcurrentLinkedQueue<Runnable> persistenceCallbacks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean persistenceNoticeQueued = new AtomicBoolean();
    private final AtomicBoolean unsupportedThreading = new AtomicBoolean();
    private long nextLoadIdentity;
    private int highestMacroRequests;
    private int highestQueuedRecoveries;
    private long recoveredSections;
    private long failedRecoveries;
    private long degradedRecoveryCells;
    private long successfulMacroQueries;
    private long failedMacroQueries;
    private long cancelledMacroQueries;
    private long exceptionalMacroQueries;
    private long finalValidationRejections;
    private long successfulStaleRetries;
    private boolean recoveryNoticeQueued;
    private volatile boolean stopping;
    private volatile boolean stopped;

    private TopologyService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        TopologyStore opened = null;
        try {
            opened = new TopologyStore(server.getWorldPath(LevelResource.ROOT)
                    .resolve("data").resolve("accelerated_navigation").resolve("topology"));
        } catch (IOException failure) {
            AcceleratedNavigation.LOGGER.error("Could not open macro topology store", failure);
        }
        store = opened;
        runtime = new TopologyWorkerRuntime(this::factDemandChanged,
                this::queueFactDecision);
    }

    public static TopologyService forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            rejectUnsupportedThread(server);
            throw unsupportedThreadFailure();
        }
        synchronized (SERVICES) {
            if (SERVICES.containsKey(server) && SERVICES.get(server) == null) {
                throw unsupportedThreadFailure();
            }
            return SERVICES.computeIfAbsent(server, TopologyService::new);
        }
    }

    private static void rejectUnsupportedThread(MinecraftServer server) {
        synchronized (SERVICES) {
            TopologyService service = SERVICES.get(server);
            if (service != null) service.disableUnsupportedThreading();
            else if (!SERVICES.containsKey(server)) {
                // A null entry rejects creation until shutdown, without starting workers off-thread.
                SERVICES.put(server, null);
                AcceleratedNavigation.LOGGER.warn(
                        "Macro topology disabled until server restart: unsupported calling thread");
            }
        }
    }

    private void disableUnsupportedThreading() {
        if (!unsupportedThreading.compareAndSet(false, true)) return;
        AcceleratedNavigation.LOGGER.warn(
                "Macro topology disabled until server restart: unsupported calling thread");
        queueServer(this::beginStopping);
    }

    private static UnsupportedOperationException unsupportedThreadFailure() {
        return new UnsupportedOperationException(
                "Macro topology requires the server thread and is disabled until server restart");
    }

    public boolean isAvailable() {
        return !unsupportedThreading.get() && !stopping && !stopped;
    }

    public static boolean canReuseFactVersions(MinecraftServer server) {
        synchronized (SERVICES) {
            if (!SERVICES.containsKey(server)) return true;
            TopologyService service = SERVICES.get(server);
            return service != null && !service.unsupportedThreading.get();
        }
    }

    public static void beginStopping(MinecraftServer server) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(server);
        }
        if (service != null) service.beginStopping();
    }

    public static void shutdown(MinecraftServer server) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.remove(server);
        }
        if (service != null) service.finishStopping();
    }

    public static void endServerTick(MinecraftServer server) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(server);
        }
        if (service != null) service.finishTick();
    }

    public static void onChunkLoaded(ServerLevel level, ChunkAccess chunk) {
        if (!level.getServer().isSameThread()) {
            rejectUnsupportedThread(level.getServer());
            return;
        }
        if (!canReuseFactVersions(level.getServer())) return;
        forServer(level.getServer()).loadChunk(level, chunk);
    }

    public static void onChunkUnloaded(ServerLevel level, ChunkAccess chunk) {
        if (!level.getServer().isSameThread()) {
            rejectUnsupportedThread(level.getServer());
            return;
        }
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(level.getServer());
        }
        if (service != null) service.unloadChunk(level, chunk);
    }

    public static void onLevelSave(ServerLevel level) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(level.getServer());
        }
        if (service != null && service.store != null && !service.stopped) {
            service.store.requestSave(level.dimension());
        }
    }

    public static void onColumnFactsChanged(ServerLevel level,
                                            LevelChunk chunk,
                                            BlockPos position,
                                            long before,
                                            long after) {
        if (!(chunk instanceof ChunkFactsCarrier carrier)) return;
        TopologyService service = carrier.acceleratedNavigation$factsState().consumer;
        if (service == null || !service.acceptsFactsThread()) return;
        service.recordColumnChange(level, chunk, position.immutable(), before, after);
    }

    /** Returns whether this loaded chunk currently has a live facts consumer. */
    public static boolean tracksFacts(LevelChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!(chunk instanceof ChunkFactsCarrier carrier)) return false;
        TopologyService service = carrier.acceleratedNavigation$factsState().consumer;
        return service != null && service.acceptsFactsThread();
    }

    private boolean acceptsFactsThread() {
        if (!isAvailable()) return false;
        if (server.isSameThread()) return true;
        disableUnsupportedThreading();
        return false;
    }

    public MacroRequest requestMacroQuery(ServerLevel level,
                                          UUID owner,
                                          BlockPos start,
                                          BlockPos goal,
                                          BaseClusterTopology.Channel channel,
                                          BaseClusterTopology.TraversalProfile profile,
                                          NavigationScheduler.Priority priority) {
        if (!server.isSameThread()) disableUnsupportedThreading();
        if (unsupportedThreading.get()) throw unsupportedThreadFailure();
        requireServerThread();
        if (stopping || level.getServer() != server) {
            throw new IllegalStateException("topology service is stopping or belongs to another server");
        }
        RequestKey key = new RequestKey(level.dimension(), Objects.requireNonNull(owner, "owner"));
        if (!macroRequests.containsKey(key) && macroRequests.size() >= MAX_MACRO_REQUESTS) {
            throw new RejectedExecutionException(
                    "accelerated macro query request limit reached");
        }
        MacroRequest request = new MacroRequest(key, start, goal, channel, profile, priority);
        MacroRequest replaced = macroRequests.put(key, request);
        highestMacroRequests = Math.max(highestMacroRequests, macroRequests.size());
        if (replaced != null) replaced.cancelOnServer();
        request.submit();
        return request;
    }

    private void loadChunk(ServerLevel level, ChunkAccess access) {
        requireServerThread();
        if (!isAvailable() || !(access instanceof LevelChunk chunk)
                || !(access instanceof ChunkFactsCarrier carrier)) return;
        ChunkKey key = new ChunkKey(level.dimension(), chunk.getPos().toLong());
        LoadedChunk previous = loadedChunks.remove(key);
        if (previous != null) unloadChunk(previous);
        LoadedChunk loaded = new LoadedChunk(key, ++nextLoadIdentity, chunk, carrier);
        loadedChunks.put(key, loaded);

        ChunkFactsState carrierState = carrier.acceleratedNavigation$factsState();
        carrierState.beginTracking(this);
        Map<Integer, TopologyStore.SectionRecord> generated = carrierState.drainGeneratedFacts();
        for (LevelChunkSection section : chunk.getSections()) {
            ((GenerationSection) section).acceleratedNavigation$bind(null, 0);
        }
        Map<Integer, Long> versions = carrierState.versions();
        List<Integer> nonAir = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            if (!sections[index].hasOnlyAir()) {
                nonAir.add(chunk.getSectionYFromSectionIndex(index));
            }
        }
        runtime.enqueuePrewarm(level.dimension(), chunk.getPos(), loaded.identity, nonAir);
        for (int index = 0; index < sections.length; index++) {
            int sectionY = chunk.getSectionYFromSectionIndex(index);
            boolean allAir = sections[index].hasOnlyAir();
            TopologyStore.SectionRecord initial = generated.get(sectionY);
            long version = initial == null ? versions.getOrDefault(sectionY, 0L)
                    : initial.version();
            carrierState.setVersion(sectionY, version);
            TopologyWorkerRuntime.ClusterKey sectionKey = new TopologyWorkerRuntime.ClusterKey(
                    level.dimension(), SectionPos.of(chunk.getPos().x, sectionY, chunk.getPos().z));
            LoadedSection section = new LoadedSection(
                    sectionKey, loaded, sectionY, version, allAir && initial == null);
            loadedSections.put(sectionKey, section);
            loaded.sections.add(section);
            if (initial != null) {
                writeFull(section, initial.facts());
            } else if (allAir) {
                publishFull(section, BaseClusterTopology.PackedFacts.allAir());
            } else {
                publishPending(section);
                if (!carrierState.versionTagValid() || !versions.containsKey(sectionY)
                        || store == null) registerRecovery(section);
                else readPersisted(section);
            }
        }
    }

    private void unloadChunk(ServerLevel level, ChunkAccess access) {
        requireServerThread();
        LoadedChunk loaded = loadedChunks.remove(
                new ChunkKey(level.dimension(), access.getPos().toLong()));
        if (loaded != null) unloadChunk(loaded);
    }

    private void unloadChunk(LoadedChunk loaded) {
        loaded.carrier.acceleratedNavigation$factsState().endTracking();
        for (LoadedSection section : loaded.sections) {
            foregroundRecovery.remove(section.key);
            ordinaryRecovery.remove(section.key);
            changedSections.remove(section);
            loadedSections.remove(section.key, section);
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    section.key, loaded.identity, section.version, section.version,
                    TopologyWorkerRuntime.FactState.UNLOADED, null, Map.of()));
        }
        ChunkPos position = new ChunkPos(loaded.key.chunkLong());
        runtime.publishChunkUnload(loaded.key.dimension(), position, loaded.identity);
        if (store != null) store.unload(loaded.key.dimension(), position);
    }

    private void readPersisted(LoadedSection section) {
        if (!isAvailable() || section.readInFlight || recoveryQueued(section)
                || section.recoveryFailed) return;
        if (section.persistenceUnavailable) return;
        if (hasUncommittedChanges(section) || section.persistencePending) return;
        if (store == null) {
            registerRecovery(section);
            return;
        }
        section.readInFlight = true;
        long identity = section.chunk.identity;
        long requestedVersion = section.version;
        long readRequest = ++section.readRequest;
        try {
            store.read(section.key.dimension(), section.key.section())
                    .whenComplete((result, failure) -> queueServer(() -> {
                        LoadedSection current = current(section.key, identity);
                        if (current == null || !isAvailable()
                                || current.readRequest != readRequest) return;
                        current.readInFlight = false;
                        if (current.version != requestedVersion
                                || hasUncommittedChanges(current)) return;
                        if (failure == null && result != null
                                && result.status() == TopologyStore.ReadStatus.FOUND
                                && result.record().version() == requestedVersion) {
                            publishFull(current, result.record().facts());
                        } else registerRecovery(current);
                    }));
        } catch (RuntimeException failure) {
            queueServer(() -> {
                LoadedSection current = current(section.key, identity);
                if (current == null || !isAvailable()
                        || current.readRequest != readRequest) return;
                current.readInFlight = false;
                if (current.version == requestedVersion
                        && !hasUncommittedChanges(current)) registerRecovery(current);
            });
        }
    }

    private boolean hasUncommittedChanges(LoadedSection section) {
        return section.changedTick == server.getTickCount()
                || !section.pendingChanges.isEmpty();
    }

    private void registerRecovery(LoadedSection section) {
        requireServerThread();
        if (!isAvailable() || section.recoveryFailed || section.persistenceUnavailable
                || recoveryQueued(section)) return;
        if (section.recoveryAttempted) {
            section.persistenceUnavailable = true;
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    section.key, section.chunk.identity, section.version, section.version,
                    TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, null, Map.of()));
            return;
        }
        if (recoverySet(section).add(section.key)) {
            highestQueuedRecoveries = Math.max(highestQueuedRecoveries,
                    foregroundRecovery.size() + ordinaryRecovery.size());
        }
        queueRecoveryNotice();
    }

    private boolean recoveryQueued(LoadedSection section) {
        return foregroundRecovery.contains(section.key)
                || ordinaryRecovery.contains(section.key);
    }

    private Set<TopologyWorkerRuntime.ClusterKey> recoverySet(LoadedSection section) {
        return section.foregroundWaiters > 0 ? foregroundRecovery : ordinaryRecovery;
    }

    private void factDemandChanged(TopologyWorkerRuntime.ClusterKey key,
                                   long loadIdentity,
                                   int delta) {
        boolean schedule = false;
        synchronized (factDemandLock) {
            if (!isAvailable()) return;
            FactDemandKey demand = new FactDemandKey(key, loadIdentity);
            factDemandChanges.merge(demand, delta, Integer::sum);
            if (factDemandChanges.get(demand) == 0) factDemandChanges.remove(demand);
            if (!factDemandChanges.isEmpty() && !factDemandNoticeQueued) {
                factDemandNoticeQueued = true;
                schedule = true;
            }
        }
        if (schedule) queueServer(this::drainFactDemandChanges);
    }

    private void drainFactDemandChanges() {
        requireServerThread();
        Map<FactDemandKey, Integer> batch;
        synchronized (factDemandLock) {
            batch = factDemandChanges;
            factDemandChanges = new HashMap<>();
            factDemandNoticeQueued = false;
        }
        batch.forEach((demand, delta) -> {
            LoadedSection section = loadedSections.get(demand.key);
            if (section == null || section.chunk.identity != demand.loadIdentity || !isAvailable()) return;
            int previous = section.foregroundWaiters;
            section.foregroundWaiters = previous + delta;
            if (recoveryQueued(section)
                    && (previous == 0) != (section.foregroundWaiters == 0)) {
                foregroundRecovery.remove(demand.key);
                ordinaryRecovery.remove(demand.key);
                recoverySet(section).add(demand.key);
            }
            if (delta > 0 && !recoveryQueued(section) && !section.recoveryFailed
                    && !section.readInFlight) {
                if (section.implicitAllAirBase) {
                    publishFull(section, BaseClusterTopology.PackedFacts.allAir());
                } else readPersisted(section);
            }
        });
        boolean schedule;
        synchronized (factDemandLock) {
            schedule = isAvailable() && !factDemandChanges.isEmpty() && !factDemandNoticeQueued;
            if (schedule) factDemandNoticeQueued = true;
        }
        if (schedule) queueServer(this::drainFactDemandChanges);
    }

    private void queueRecoveryNotice() {
        if (!isAvailable() || recoveryNoticeQueued
                || foregroundRecovery.isEmpty() && ordinaryRecovery.isEmpty()) return;
        recoveryNoticeQueued = true;
        queueServer(this::runOneRecovery);
    }

    private void runOneRecovery() {
        requireServerThread();
        if (!isAvailable()) return;
        // A worker demand can reach the server mailbox after the recovery notice
        // was queued. Apply that mailbox before choosing ordinary recovery so a
        // waiting query is visible to the strict foreground ordering.
        boolean hasFactDemand;
        synchronized (factDemandLock) {
            hasFactDemand = !factDemandChanges.isEmpty();
        }
        recoveryNoticeQueued = true;
        try {
            if (hasFactDemand) drainFactDemandChanges();
        } finally {
            recoveryNoticeQueued = false;
        }
        TopologyWorkerRuntime.ClusterKey key = pollFirst(foregroundRecovery);
        if (key == null) key = pollFirst(ordinaryRecovery);
        if (key == null) return;
        LoadedSection section = loadedSections.get(key);
        if (section == null) {
            queueRecoveryNotice();
            return;
        }
        ServerLevel level = server.getLevel(key.dimension());
        LevelChunk currentChunk = level == null ? null : level.getChunkSource().getChunkNow(
                key.section().x(), key.section().z());
        if (currentChunk != section.chunk.chunk) {
            queueRecoveryNotice();
            return;
        }
        try {
            section.recoveryAttempted = true;
            RecoveryResult recovered = scanSection(section);
            if (recovered.firstFailure != null) {
                AcceleratedNavigation.LOGGER.warn(
                        "Recovered macro facts for {} with {} degraded collision cells; first at {} state={} ({})",
                        key, recovered.degradedCount, recovered.firstPosition,
                        recovered.firstState, recovered.firstFailure.toString());
            }
            section.pendingChanges.clear();
            changedSections.remove(section);
            section.changeOriginalVersion = -1L;
            section.changedTick = Long.MIN_VALUE;
            writeFull(section, recovered.facts);
            recoveredSections++;
            degradedRecoveryCells += recovered.degradedCount;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            section.recoveryFailed = true;
            failedRecoveries++;
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    section.key, section.chunk.identity, section.version, section.version,
                    TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of()));
            AcceleratedNavigation.LOGGER.error("Could not recover macro facts for {}", key, failure);
        }
        queueRecoveryNotice();
    }

    @Nullable
    private static <T> T pollFirst(LinkedHashSet<T> values) {
        var iterator = values.iterator();
        if (!iterator.hasNext()) return null;
        T value = iterator.next();
        iterator.remove();
        return value;
    }

    private RecoveryResult scanSection(LoadedSection section) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        long[] fullCells = new long[BaseClusterTopology.CELL_COUNT / Long.SIZE];
        long[] collidingCells = new long[BaseClusterTopology.CELL_COUNT / Long.SIZE];
        long[] failedCells = new long[BaseClusterTopology.CELL_COUNT / Long.SIZE];
        BlockState[] states = new BlockState[BaseClusterTopology.CELL_COUNT];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        SectionPos origin = section.key.section();
        int degraded = 0;
        BlockPos firstPosition = null;
        String firstState = null;
        RuntimeException firstFailure = null;
        for (int y = 0; y < BaseClusterTopology.SIDE; y++) {
            for (int z = 0; z < BaseClusterTopology.SIDE; z++) {
                for (int x = 0; x < BaseClusterTopology.SIDE; x++) {
                    int cell = BaseClusterTopology.cellIndex(x, y, z);
                    cursor.set(origin.minBlockX() + x, origin.minBlockY() + y,
                            origin.minBlockZ() + z);
                    BlockState state = section.chunk.chunk.getBlockState(cursor);
                    states[cell] = state;
                    if (state.getBlock().getClass() != LiquidBlock.class
                            && !state.getBlock().hasDynamicShape()) {
                        try {
                            if (state.isCollisionShapeFullBlock(
                                    EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                                setCollisionBit(fullCells, cell);
                                setCollisionBit(collidingCells, cell);
                            }
                        } catch (RuntimeException failure) {
                            setCollisionBit(failedCells, cell);
                            degraded++;
                            if (firstFailure == null) {
                                firstFailure = failure;
                                firstPosition = cursor.immutable();
                                firstState = state.toString();
                            }
                        }
                    }
                }
            }
        }
        for (int y = 0; y < BaseClusterTopology.SIDE; y++) {
            for (int z = 0; z < BaseClusterTopology.SIDE; z++) {
                for (int x = 0; x < BaseClusterTopology.SIDE; x++) {
                    int cell = BaseClusterTopology.cellIndex(x, y, z);
                    BlockState state = states[cell];
                    int flags;
                    boolean collides;
                    boolean fluid = !state.getFluidState().isEmpty();
                    cursor.set(origin.minBlockX() + x, origin.minBlockY() + y,
                            origin.minBlockZ() + z);
                    if (collisionBit(failedCells, cell)) {
                        flags = BaseClusterTopology.VOLUME_OPEN
                                | BaseClusterTopology.GROUND_OPEN
                                | BaseClusterTopology.EXACT_REQUIRED
                                | (fluid ? BaseClusterTopology.FLUID : 0);
                        collides = true;
                    } else try {
                        int classified = collisionBit(fullCells, cell)
                                || !state.getBlock().hasDynamicShape()
                                && enclosedByFullCells(x, y, z, fullCells)
                                ? (fluid ? BaseClusterTopology.FLUID : 0) | COLLIDES
                                : collisionClassification(state, true);
                        flags = classified & FACT_MASK;
                        collides = (classified & COLLIDES) != 0;
                    } catch (RuntimeException failure) {
                        flags = BaseClusterTopology.VOLUME_OPEN
                                | BaseClusterTopology.GROUND_OPEN
                                | BaseClusterTopology.EXACT_REQUIRED
                                | (fluid ? BaseClusterTopology.FLUID : 0);
                        collides = true;
                        degraded++;
                        if (firstFailure == null) {
                            firstFailure = failure;
                            firstPosition = cursor.immutable();
                            firstState = state.toString();
                        }
                    }
                    if (y > 0 && collisionBit(collidingCells, cell - 256)
                            && (flags & BaseClusterTopology.VOLUME_OPEN) != 0) {
                        flags |= BaseClusterTopology.GROUND_OPEN;
                    }
                    cells[cell] = (byte) flags;
                    if (collides) setCollisionBit(collidingCells, cell);
                }
            }
        }
        return new RecoveryResult(BaseClusterTopology.PackedFacts.fromCells(cells),
                degraded, firstPosition, firstState, firstFailure);
    }

    private static boolean enclosedByFullCells(int x, int y, int z, long[] full) {
        if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) return false;
        int cell = BaseClusterTopology.cellIndex(x, y, z);
        return collisionBit(full, cell - 1) && collisionBit(full, cell + 1)
                && collisionBit(full, cell - 16) && collisionBit(full, cell + 16)
                && collisionBit(full, cell - 256) && collisionBit(full, cell + 256);
    }

    private static void setCollisionBit(long[] bits, int cell) {
        bits[cell >>> 6] |= 1L << (cell & 63);
    }

    private static boolean collisionBit(long[] bits, int cell) {
        return (bits[cell >>> 6] & 1L << (cell & 63)) != 0L;
    }

    private void recordColumnChange(ServerLevel level,
                                    LevelChunk chunk,
                                    BlockPos position,
                                    long before,
                                    long after) {
        requireServerThread();
        if (!isAvailable() || before == after) return;
        recordCellChange(level, chunk, position,
                (byte) (before & FACT_MASK), (byte) (after & FACT_MASK));
        if ((before & HAS_ABOVE) != 0L && (after & HAS_ABOVE) != 0L) {
            recordCellChange(level, chunk, position.above(),
                    (byte) (before >>> 8 & FACT_MASK),
                    (byte) (after >>> 8 & FACT_MASK));
        }
    }

    private void recordCellChange(ServerLevel level,
                                  LevelChunk chunk,
                                  BlockPos position,
                                  byte before,
                                  byte after) {
        if (!isAvailable() || before == after) return;
        TopologyWorkerRuntime.ClusterKey key = new TopologyWorkerRuntime.ClusterKey(
                level.dimension(), SectionPos.of(position));
        LoadedSection section = loadedSections.get(key);
        LoadedChunk loaded = loadedChunks.get(
                new ChunkKey(level.dimension(), chunk.getPos().toLong()));
        if (section == null || loaded == null || section.chunk != loaded
                || loaded.chunk != chunk) return;
        long tick = server.getTickCount();
        if (section.changedTick != tick) {
            section.changedTick = tick;
            section.changeOriginalVersion = section.version;
            section.version++;
            section.chunk.carrier.acceleratedNavigation$factsState()
                    .setVersion(section.sectionY, section.version);
        }
        int cell = (position.getX() & 15) | (position.getZ() & 15) << 4
                | (position.getY() & 15) << 8;
        section.pendingChanges.put(cell, after);
        changedSections.add(section);
    }

    private void finishTick() {
        requireServerThread();
        if (stopped) return;
        if (unsupportedThreading.get()) {
            beginStopping();
            drainPersistence();
            if (runtime.factsSettled() && persistenceCallbacks.isEmpty()) runtime.finishFactClosing();
            return;
        }
        drainPersistence();
        List<LoadedSection> changed = List.copyOf(changedSections);
        changedSections.clear();
        for (LoadedSection section : changed) {
            if (section.pendingChanges.isEmpty()) continue;
            Map<Integer, Byte> changes = Map.copyOf(section.pendingChanges);
            section.pendingChanges.clear();
            long original = section.changeOriginalVersion;
            long targetVersion = section.version;
            section.changeOriginalVersion = -1L;
            section.changedTick = Long.MIN_VALUE;
            section.implicitAllAirBase = false;
            section.readRequest++;
            section.readInFlight = false;
            section.persistencePending = true;
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    section.key, section.chunk.identity, original, targetVersion,
                    TopologyWorkerRuntime.FactState.AVAILABLE, null, changes));
        }
        runtime.endServerTick();
    }

    private void publishPending(LoadedSection section) {
        if (!isAvailable()) return;
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                section.key, section.chunk.identity, section.version, section.version,
                TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
    }

    private void publishFull(LoadedSection section, BaseClusterTopology.PackedFacts facts) {
        if (!isAvailable()) return;
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                section.key, section.chunk.identity, section.version, section.version,
                TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of()));
    }

    private void writeFull(LoadedSection section, BaseClusterTopology.PackedFacts facts) {
        if (!isAvailable()) return;
        section.persistencePending = true;
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                section.key, section.chunk.identity, section.version, section.version,
                TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of(), true));
    }

    private void persistDecision(TopologyWorkerRuntime.FactDecision decision) {
        requireServerThread();
        LoadedSection section = current(decision.key(), decision.loadIdentity());
        if (section == null || stopped || unsupportedThreading.get()) {
            runtime.completeFacts(decision, decision.facts() != null, false);
            return;
        }
        // A lower target may still own the baseline for the current merged tail.
        if (decision.version() > section.version) {
            throw new IllegalStateException("facts decision is ahead of its world version");
        }
        if (store == null) {
            completePersistence(decision, decision.facts() != null, false);
            return;
        }
        try {
            TopologyStore.WriteReceipt receipt = store.accept(decision);
            runtime.acceptFacts(decision);
            receipt.formed.whenComplete((formation, failure) -> {
                if (formation == null || formation.facts() == null) return;
                runtime.factsFormed(decision, formation.facts()).whenComplete(
                        (pinned, pinFailure) -> {
                            if (pinFailure == null && Boolean.TRUE.equals(pinned)) store.continueWrite(receipt);
                            else store.rejectWrite(receipt);
                        });
            });
            receipt.completed.whenComplete((result, failure) -> {
                TopologyStore.Formation formation = receipt.formed.getNow(null);
                boolean formed = formation != null && formation.facts() != null;
                boolean written = failure == null && result != null && result.accepted();
                if (!queuePersistence(() -> completePersistence(decision, formed, written))) {
                    runtime.completeFacts(decision, formed, written);
                }
            });
        } catch (RuntimeException failure) {
            AcceleratedNavigation.LOGGER.warn("Could not accept macro facts for {}", decision.key(), failure);
            completePersistence(decision, decision.facts() != null, false);
        }
    }

    private void completePersistence(TopologyWorkerRuntime.FactDecision decision,
                                     boolean formed, boolean written) {
        requireServerThread();
        runtime.completeFacts(decision, formed, written).thenAccept(applied -> {
            if (applied) queuePersistence(() -> persistenceApplied(decision, formed, written));
        });
    }

    private void persistenceApplied(TopologyWorkerRuntime.FactDecision decision,
                                    boolean formed, boolean written) {
        requireServerThread();
        LoadedSection section = current(decision.key(), decision.loadIdentity());
        if (section != null && !unsupportedThreading.get()) {
            if (section.version == decision.version()) section.persistencePending = false;
            if (formed && !written) section.persistenceUnavailable = true;
            if (written && section.version == decision.version()) section.persistenceUnavailable = false;
            if (!formed && !written) registerRecovery(section);
        }
    }

    private void queueFactDecision(TopologyWorkerRuntime.FactDecision decision) {
        if (!queuePersistence(() -> persistDecision(decision))) {
            runtime.completeFacts(decision, decision.facts() != null, false);
        }
    }

    private boolean queuePersistence(Runnable action) {
        synchronized (persistenceCallbacks) {
            if (stopped) return false;
            persistenceCallbacks.add(action);
        }
        if (persistenceNoticeQueued.compareAndSet(false, true)) queueServer(this::drainPersistence);
        return true;
    }

    private void drainPersistence() {
        requireServerThread();
        Runnable action;
        while ((action = persistenceCallbacks.poll()) != null) action.run();
        persistenceNoticeQueued.set(false);
        if (!persistenceCallbacks.isEmpty() && persistenceNoticeQueued.compareAndSet(false, true)) {
            queueServer(this::drainPersistence);
        }
    }

    @Nullable
    private LoadedSection current(TopologyWorkerRuntime.ClusterKey key, long identity) {
        LoadedSection current = loadedSections.get(key);
        return current != null && current.chunk.identity == identity ? current : null;
    }

    private void beginStopping() {
        requireServerThread();
        if (stopping) return;
        if (!unsupportedThreading.get()) finishTick();
        stopping = true;
        foregroundRecovery.clear();
        ordinaryRecovery.clear();
        changedSections.clear();
        recoveryNoticeQueued = false;
        synchronized (factDemandLock) {
            factDemandChanges.clear();
            factDemandNoticeQueued = false;
        }
        for (MacroRequest request : List.copyOf(macroRequests.values())) {
            if (unsupportedThreading.get()) request.rejectUnsupportedThreading();
            else request.cancelOnServer();
        }
        macroRequests.clear();
        loadedChunks.values().forEach(loaded -> {
            ChunkFactsState state = loaded.carrier.acceleratedNavigation$factsState();
            if (unsupportedThreading.get()) state.loadedVersions(Map.of(), false);
            state.endTracking();
        });
        runtime.beginStopping();
        if (unsupportedThreading.get()) {
            loadedSections.clear();
            loadedChunks.clear();
        }
    }

    private void finishStopping() {
        requireServerThread();
        beginStopping();
        if (stopped) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WORKER_CLOSE_WAIT_MILLIS);
        while (true) {
            drainPersistence();
            if (runtime.factsSettled() && persistenceCallbacks.isEmpty()) break;
            if (System.nanoTime() >= deadline) {
                AcceleratedNavigation.LOGGER.error("Timed out settling final macro facts transfers");
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        if (store != null) store.close();
        drainPersistence();
        synchronized (persistenceCallbacks) { stopped = true; }
        drainPersistence();
        if (!runtime.awaitStopped(WORKER_CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            AcceleratedNavigation.LOGGER.error(
                    "Timed out while closing macro topology workers after {} ms",
                    WORKER_CLOSE_WAIT_MILLIS);
        }
        loadedSections.clear();
        loadedChunks.clear();
        AcceleratedNavigation.LOGGER.info("Accelerated navigation topology summary: {}", metrics());
    }

    /** Fixed-size production diagnostics; no cache, task or waiter collection is traversed. */
    private Metrics metrics() {
        requireServerThread();
        return new Metrics(
                loadedChunks.size(),
                loadedSections.size(),
                macroRequests.size(),
                highestMacroRequests,
                foregroundRecovery.size(),
                ordinaryRecovery.size(),
                highestQueuedRecoveries,
                recoveredSections,
                failedRecoveries,
                degradedRecoveryCells,
                successfulMacroQueries,
                failedMacroQueries,
                cancelledMacroQueries,
                exceptionalMacroQueries,
                finalValidationRejections,
                successfulStaleRetries,
                runtime.metrics(),
                store == null ? null : store.metrics()
        );
    }

    private void queueServer(Runnable action) {
        if (stopped) return;
        try {
            server.tell(new TickTask(server.getTickCount(), action));
        } catch (RejectedExecutionException ignored) {
            // Shutdown owns final cleanup; queued load/recovery callbacks may be dropped.
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("basic facts state belongs to the server thread");
        }
    }

    private boolean resultCurrent(List<TopologyWorkerRuntime.SectionStamp> stamps) {
        if (stamps.isEmpty()) return false;
        for (TopologyWorkerRuntime.SectionStamp stamp : stamps) {
            LoadedSection section = loadedSections.get(stamp.key());
            if (!stamp.current()) return false;
            if (stamp.absent()) {
                if (section != null) return false;
                continue;
            }
            if (section == null
                    || section.chunk.identity != stamp.loadIdentity()
                    || section.version != stamp.version()) return false;
        }
        return true;
    }

    public final class MacroRequest {
        private final RequestKey key;
        private final BlockPos start;
        private final BlockPos goal;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final NavigationScheduler.Priority priority;
        private final CompletableFuture<MacroSearch.Corridor> future = new CompletableFuture<>();
        private TopologyWorkerRuntime.MacroRequest workerRequest;
        private boolean active = true;
        private volatile MacroSearch.Progress progress = MacroSearch.Progress.PENDING;
        private volatile boolean completedFromCache;

        private MacroRequest(RequestKey key,
                             BlockPos start,
                             BlockPos goal,
                             BaseClusterTopology.Channel channel,
                             BaseClusterTopology.TraversalProfile profile,
                             NavigationScheduler.Priority priority) {
            this.key = key;
            this.start = Objects.requireNonNull(start, "start").immutable();
            this.goal = Objects.requireNonNull(goal, "goal").immutable();
            this.channel = Objects.requireNonNull(channel, "channel");
            this.profile = Objects.requireNonNull(profile, "profile");
            this.priority = Objects.requireNonNull(priority, "priority");
            future.whenComplete((ignored, requestFailure) -> {
                if (!future.isCancelled() || !active) return;
                if (server.isSameThread()) cancelOnServer();
                else queueServer(this::cancelOnServer);
            });
        }

        private void submit() {
            requireServerThread();
            if (unsupportedThreading.get()) {
                rejectUnsupportedThreading();
                return;
            }
            try {
                workerRequest = runtime.requestMacroQuery(key.dimension(), start, goal,
                        channel, profile, priority);
                workerRequest.future().whenComplete((result, requestFailure) -> queueServer(
                        () -> completeWorker(result, requestFailure)));
            } catch (RuntimeException requestFailure) {
                finishExceptionally(requestFailure);
            }
        }

        private void completeWorker(@Nullable TopologyWorkerRuntime.WorkerResult result,
                                    @Nullable Throwable requestFailure) {
            requireServerThread();
            if (!active) return;
            if (unsupportedThreading.get()) {
                rejectUnsupportedThreading();
                return;
            }
            if (requestFailure != null) {
                finishExceptionally(requestFailure);
                return;
            }
            if (result == null) {
                finishExceptionally(new IllegalStateException("topology worker returned no result"));
                return;
            }
            boolean rejectedByFinalValidation = (result.corridor() != null || !result.stamps().isEmpty())
                    && result.progress().failure() != MacroSearch.Failure.STALE_WORLD
                    && result.progress().failure() != MacroSearch.Failure.CANCELLED
                    && !resultCurrent(result.stamps());
            if (unsupportedThreading.get()) {
                rejectUnsupportedThreading();
                return;
            }
            if (rejectedByFinalValidation) {
                finalValidationRejections++;
                progress = result.progress().withOutcome(ResumableSearch.Status.FAILED,
                        MacroSearch.Failure.STALE_WORLD, result.progress().blockedSection());
                try {
                    workerRequest.rejectFinalStaleResult(result.attempt()).whenComplete(
                            (retry, retryFailure) -> queueServer(
                                    () -> completeWorker(retry, retryFailure)));
                } catch (RuntimeException retryFailure) {
                    finishExceptionally(retryFailure);
                }
                return;
            }
            boolean stale = result.progress().failure() == MacroSearch.Failure.STALE_WORLD;
            progress = stale ? result.progress().withOutcome(ResumableSearch.Status.FAILED,
                    MacroSearch.Failure.STALE_WORLD, result.progress().blockedSection())
                    : result.progress();
            completedFromCache = result.completedFromCache();
            active = false;
            macroRequests.remove(key, this);
            if (!stale && result.corridor() != null) {
                successfulMacroQueries++;
                if (result.staleRetries() > 0) successfulStaleRetries++;
            } else failedMacroQueries++;
            future.complete(stale ? null : result.corridor());
        }

        private void finishExceptionally(Throwable requestFailure) {
            if (!active) return;
            active = false;
            macroRequests.remove(key, this);
            exceptionalMacroQueries++;
            future.completeExceptionally(requestFailure);
        }

        private void rejectUnsupportedThreading() {
            if (!active) return;
            if (workerRequest != null) workerRequest.cancel();
            finishExceptionally(unsupportedThreadFailure());
        }

        private void cancelOnServer() {
            requireServerThread();
            if (!active) return;
            active = false;
            macroRequests.remove(key, this);
            cancelledMacroQueries++;
            if (workerRequest != null) workerRequest.cancel();
            future.cancel(false);
        }

        public CompletableFuture<MacroSearch.Corridor> future() { return future; }
        public MacroSearch.Progress progress() {
            TopologyWorkerRuntime.MacroRequest current = workerRequest;
            return active && current != null ? current.progress() : progress;
        }
        public boolean completedFromCache() { return completedFromCache; }
        public void cancel() { future.cancel(false); }
    }

    /** Returns current-cell flags, optional above-cell flags, and an above-present marker. */
    public static long sampleColumnFacts(BlockGetter getter, BlockPos position) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(position, "position");
        BlockState current = getter.getBlockState(position);
        int currentCollision = collisionClassification(current, false);
        int belowCollision = (position.getY() & 15) == 0 ? 0 : collisionClassification(
                getter.getBlockState(position.below()), false);
        int currentFlags = addSupport(
                currentCollision, (belowCollision & COLLIDES) != 0);
        if (position.getY() + 1 >= getter.getMaxBuildHeight()) {
            return currentFlags & FACT_MASK;
        }
        BlockPos abovePosition = position.above();
        int aboveCollision = collisionClassification(
                getter.getBlockState(abovePosition), false);
        int aboveFlags = addSupport(aboveCollision,
                (abovePosition.getY() & 15) != 0 && (currentCollision & COLLIDES) != 0);
        return (currentFlags & FACT_MASK)
                | (long) (aboveFlags & FACT_MASK) << 8 | HAS_ABOVE;
    }

    private static int addSupport(int classification, boolean supportBelow) {
        int flags = classification & FACT_MASK;
        if (supportBelow && (flags & BaseClusterTopology.VOLUME_OPEN) != 0) {
            flags |= BaseClusterTopology.GROUND_OPEN;
        }
        return flags;
    }

    private static int collisionClassification(BlockState state,
                                                boolean staticFullChecked) {
        boolean fluid = !state.getFluidState().isEmpty();
        int flags = fluid ? BaseClusterTopology.FLUID : 0;
        if (state.getBlock().getClass() == LiquidBlock.class) {
            return flags | BaseClusterTopology.VOLUME_OPEN
                    | BaseClusterTopology.GROUND_OPEN;
        }
        if (state.getBlock().hasDynamicShape()) {
            return flags | BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
                    | BaseClusterTopology.EXACT_REQUIRED | COLLIDES;
        }
        if (!staticFullChecked && state.isCollisionShapeFullBlock(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return flags | COLLIDES;
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (Block.isShapeFullBlock(shape)) return flags | COLLIDES;
        flags |= BaseClusterTopology.VOLUME_OPEN;
        if (!shape.isEmpty() || fluid) flags |= BaseClusterTopology.GROUND_OPEN;
        if (!shape.isEmpty()) flags |= BaseClusterTopology.EXACT_REQUIRED;
        return flags | (shape.isEmpty() ? 0 : COLLIDES);
    }

    public interface ChunkFactsCarrier {
        ChunkFactsState acceleratedNavigation$factsState();
    }

    public interface GenerationSection {
        void acceleratedNavigation$bind(@Nullable ChunkAccess owner, int sectionY);
    }

    public static void bindGeneration(ChunkAccess chunk) {
        ChunkFactsState state = ((ChunkFactsCarrier) chunk).acceleratedNavigation$factsState();
        state.initializeGeneration(chunk);
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            ((GenerationSection) sections[index]).acceleratedNavigation$bind(
                    state.consumer != null ? null : chunk, chunk.getSectionYFromSectionIndex(index));
        }
    }

    /** Per-chunk generation/version state; it never mirrors the global facts cache. */
    public static final class ChunkFactsState {
        private final Map<Integer, Long> versions = new HashMap<>();
        private final Map<Integer, byte[]> generatedCells = new HashMap<>();
        private final Set<Integer> incompleteSections = new java.util.HashSet<>();
        private boolean generationInitialized;
        private boolean loadedFromDisk;
        private boolean versionTagValid = true;
        @Nullable
        private volatile TopologyService consumer;

        private synchronized void initializeGeneration(ChunkAccess chunk) {
            if (generationInitialized) return;
            generationInitialized = true;
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                if (!sections[index].hasOnlyAir()) {
                    incompleteSections.add(chunk.getSectionYFromSectionIndex(index));
                }
            }
        }

        synchronized void beginTracking(TopologyService service) {
            consumer = service;
        }

        synchronized void endTracking() {
            consumer = null;
        }

        public synchronized void recordGeneratedWrite(BlockGetter chunk, BlockPos position) {
            if (consumer != null) return;
            int sectionY = position.getY() >> 4;
            if (loadedFromDisk || incompleteSections.contains(sectionY)) {
                versions.remove(sectionY);
                return;
            }
            long sampled = sampleColumnFacts(chunk, position);
            recordGeneratedCell(position, (byte) (sampled & FACT_MASK));
            if ((sampled & HAS_ABOVE) != 0L) {
                recordGeneratedCell(position.above(),
                        (byte) (sampled >>> 8 & FACT_MASK));
            }
        }

        private void recordGeneratedCell(BlockPos position, byte flags) {
            int sectionY = position.getY() >> 4;
            if (incompleteSections.contains(sectionY)) return;
            byte[] cells = generatedCells.computeIfAbsent(sectionY, ignored -> {
                byte[] created = new byte[BaseClusterTopology.CELL_COUNT];
                Arrays.fill(created, (byte) BaseClusterTopology.VOLUME_OPEN);
                return created;
            });
            int cell = (position.getX() & 15) | (position.getZ() & 15) << 4
                    | (position.getY() & 15) << 8;
            if (cells[cell] == flags) return;
            cells[cell] = flags;
            versions.merge(sectionY, 1L, Long::sum);
        }

        public synchronized void loadedVersions(Map<Integer, Long> loaded, boolean valid) {
            versions.clear();
            versions.putAll(Objects.requireNonNull(loaded, "loaded"));
            generatedCells.clear();
            incompleteSections.clear();
            loadedFromDisk = true;
            versionTagValid = valid;
        }

        public synchronized Map<Integer, Long> versions() {
            return Map.copyOf(versions);
        }

        synchronized Map<Integer, TopologyStore.SectionRecord> drainGeneratedFacts() {
            Map<Integer, TopologyStore.SectionRecord> result = new HashMap<>();
            generatedCells.forEach((sectionY, cells) -> result.put(sectionY,
                    new TopologyStore.SectionRecord(versions.getOrDefault(sectionY, 0L),
                            BaseClusterTopology.PackedFacts.fromCells(cells))));
            generatedCells.clear();
            loadedFromDisk = true;
            return Map.copyOf(result);
        }

        synchronized boolean versionTagValid() {
            return versionTagValid;
        }

        synchronized void setVersion(int sectionY, long version) {
            versions.put(sectionY, version);
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, long chunkLong) {
    }

    private record RequestKey(ResourceKey<Level> dimension, UUID owner) {
    }

    private static final class LoadedChunk {
        private final ChunkKey key;
        private final long identity;
        private final LevelChunk chunk;
        private final ChunkFactsCarrier carrier;
        private final List<LoadedSection> sections = new ArrayList<>();

        private LoadedChunk(ChunkKey key,
                            long identity,
                            LevelChunk chunk,
                            ChunkFactsCarrier carrier) {
            this.key = key;
            this.identity = identity;
            this.chunk = chunk;
            this.carrier = carrier;
        }
    }

    private static final class LoadedSection {
        private final TopologyWorkerRuntime.ClusterKey key;
        private final LoadedChunk chunk;
        private final int sectionY;
        private final Map<Integer, Byte> pendingChanges = new HashMap<>();
        private long version;
        private long changedTick = Long.MIN_VALUE;
        private long changeOriginalVersion = -1L;
        private boolean persistencePending;
        private boolean persistenceUnavailable;
        private int foregroundWaiters;
        private long readRequest;
        private boolean readInFlight;
        private boolean recoveryFailed;
        private boolean recoveryAttempted;
        private boolean implicitAllAirBase;

        private LoadedSection(TopologyWorkerRuntime.ClusterKey key,
                              LoadedChunk chunk,
                              int sectionY,
                              long version,
                              boolean implicitAllAirBase) {
            this.key = key;
            this.chunk = chunk;
            this.sectionY = sectionY;
            this.version = version;
            this.implicitAllAirBase = implicitAllAirBase;
        }
    }

    private record RecoveryResult(BaseClusterTopology.PackedFacts facts,
                                   int degradedCount,
                                   @Nullable BlockPos firstPosition,
                                   @Nullable String firstState,
                                   @Nullable RuntimeException firstFailure) {
    }

    private record FactDemandKey(TopologyWorkerRuntime.ClusterKey key, long loadIdentity) {
    }

    record CacheMetrics(int entries,
                        int highestEntries,
                        long retainedBytes,
                        long highestRetainedBytes) {
    }

    record TaskCounts(long control,
                      long builds,
                      long quickSearches,
                      long longSearches,
                      long prewarms) {
    }

    record TaskMetrics(TaskCounts queued,
                       TaskCounts running,
                       TaskCounts highestQueued,
                       TaskCounts highestRunning,
                       TaskCounts completed,
                       TaskCounts failed) {
        TaskMetrics {
            Objects.requireNonNull(queued, "queued");
            Objects.requireNonNull(running, "running");
            Objects.requireNonNull(highestQueued, "highestQueued");
            Objects.requireNonNull(highestRunning, "highestRunning");
            Objects.requireNonNull(completed, "completed");
            Objects.requireNonNull(failed, "failed");
        }
    }

    record WorkerMetrics(int logicalRequests,
                         int highestLogicalRequests,
                         int endpointResolutions,
                         int highestEndpointResolutions,
                         int physicalSearches,
                         int highestPhysicalSearches,
                         int buildDemands,
                         int highestBuildDemands,
                         int dependencyConsumers,
                         int highestDependencyConsumers,
                         int liveSearchDependencies,
                         int highestLiveSearchDependencies,
                         int prewarmCandidates,
                         int highestPrewarmCandidates,
                         int admittedPrewarms,
                         int highestAdmittedPrewarms,
                         int activeReferences,
                         int highestActiveReferences,
                         CacheMetrics baseCache,
                         CacheMetrics parentCache,
                         CacheMetrics corridorCache,
                         long completedCacheHits,
                         long physicalSearchesStarted,
                         long physicalSearchesSucceeded,
                         long physicalSearchesFailed,
                         long workerStaleResults,
                         long automaticStaleRetries,
                         long staleRetryExhaustions,
                         EventMetrics events,
                          TaskMetrics tasks,
                          FactRetention facts) {
        WorkerMetrics {
            Objects.requireNonNull(baseCache, "baseCache");
            Objects.requireNonNull(parentCache, "parentCache");
            Objects.requireNonNull(corridorCache, "corridorCache");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(tasks, "tasks");
            Objects.requireNonNull(facts, "facts");
        }
    }

    record FactRetention(int transfers, int mergedSections, int mergedCells,
                         int executingDeltaCells, long activeFactsBytes, long sectionIndexBytes) {
        long mergedEstimatedBytes() { return mergedSections * 64L + mergedCells * 32L; }
        long executingInputEstimatedBytes() { return transfers * 96L + executingDeltaCells * 32L; }
    }

    record EventMetrics(int pendingKeys,
                        int activeBatchKeys,
                        int highestPendingKeys,
                        int highestBatchKeys,
                        long pendingEstimatedBytes,
                        long activeBatchEstimatedBytes,
                        long highestPendingEstimatedBytes,
                        long highestBatchEstimatedBytes,
                        long completedBatches,
                        long failedBatches,
                        long longestWaitNanos,
                        long longestBatchNanos,
                        boolean taskOutstanding,
                        boolean batchActive) {
    }

    record PersistenceMetrics(int pendingChunks,
                              int highestPendingChunks,
                              int readsInFlight,
                              int queuedReadTasks,
                              int queuedWriteOrFlushTasks,
                              int highestQueuedTasks,
                              int decodedChunks,
                              long readRequests,
                              long recordsFound,
                              long recordsMissing,
                              long readFailures,
                              long writeRequests,
                              long recordsWritten,
                              long recordsCoalesced,
                              long writeFailures,
                              long saveRequests,
                              long flushes,
                              long flushFailures,
                              boolean accepting,
                              boolean closing,
                              int acceptedWrites) {
    }

    private record Metrics(int loadedChunks,
                           int loadedSections,
                           int activeMacroRequests,
                           int highestMacroRequests,
                           int foregroundRecoveries,
                           int ordinaryRecoveries,
                           int highestQueuedRecoveries,
                           long recoveredSections,
                           long failedRecoveries,
                           long degradedRecoveryCells,
                           long successfulMacroQueries,
                           long failedMacroQueries,
                           long cancelledMacroQueries,
                           long exceptionalMacroQueries,
                           long finalValidationRejections,
                           long successfulStaleRetries,
                           WorkerMetrics worker,
                           @Nullable PersistenceMetrics persistence) {
        Metrics {
            Objects.requireNonNull(worker, "worker");
        }
    }

}
