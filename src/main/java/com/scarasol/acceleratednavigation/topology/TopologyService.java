package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns topology revisions and is the sole publisher of immutable cluster data. */
public final class TopologyService {

    private static final Map<MinecraftServer, TopologyService> SERVICES = new IdentityHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int MAX_QUERY_PREFETCH_SECTIONS = 4;
    private static final int MIN_SUPER_CLUSTER_DISTANCE = 2;
    private static final int MAX_SUPER_CACHE_ENTRIES = 512;
    private static final long MAX_BASE_RETAINED_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_SUPER_RETAINED_BYTES = 16L * 1024L * 1024L;
    private static final int MIN_QUERY_VISITED_NODES = 1_024;
    private static final int MIN_HIERARCHICAL_QUERY_VISITED_NODES = 2_048;
    private static final int MAX_QUERY_VISITED_NODES = 8_192;
    private static final int MAX_LOCAL_WITNESS_NODES = 512;
    private static final float QUERY_VISITED_NODES_PER_BLOCK = 8.0F;
    private static final int MAX_DEPENDENCY_DEMANDS = 64;
    private static final int MAX_PREWARM_ADMITTED = 8;
    private static final BaseClusterTopology.GeometryKey DEFAULT_GEOMETRY =
            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(
                    BaseClusterTopology.Channel.GROUND);
    private static final int MAX_MACRO_QUERY_WAITERS = 1_024;
    private static final int MAX_COMPLETED_CORRIDORS = 1_024;
    private static final long MAX_COMPLETED_CORRIDOR_BYTES = 16L * 1024L * 1024L;
    private static final int CELL_FACT_MASK = BaseClusterTopology.VOLUME_OPEN
            | BaseClusterTopology.GROUND_OPEN | BaseClusterTopology.FLUID
            | BaseClusterTopology.EXACT_REQUIRED;
    private static final int CELL_COLLIDES = 1 << 4;
    private static final int DYNAMIC_COLLISION = -1;

    private static final TopologyTaskExecutor.TaskHandle UNTRACKED_TASK =
            new TopologyTaskExecutor.TaskHandle() {
                @Override
                public void promote(NavigationScheduler.Priority priority) {
                }

                @Override
                public void reprioritize(NavigationScheduler.Priority priority) {
                }

                @Override
                public void enableAging() {
                }
                @Override
                public boolean cancel() {
                    return false;
                }
            };

    private final TopologyTaskExecutor buildWorker;
    private final Executor publisher;
    private final BooleanSupplier ownerThread;
    private final TopologyStore store;
    private final MinecraftServer server;
    private final Map<ClusterKey, ClusterEntry> clusters = new HashMap<>();
    private final LinkedHashMap<SuperCacheKey, SuperEntry> superClusters =
            new LinkedHashMap<>(32, 0.75F, true);
    private final Map<MacroOwnerKey, MacroRequest> macroRequests = new HashMap<>();
    private final Map<MacroQueryKey, MacroFlight> macroFlights = new HashMap<>();
    private final LinkedHashMap<MacroQueryKey, CachedCorridor> completedCorridors =
            new LinkedHashMap<>(32, 0.75F, true);
    private final DemandQueue requestedBuilds = new DemandQueue();
    private final LinkedHashMap<ResourceKey<Level>, ArrayDeque<PrewarmCandidate>> prewarmQueues =
            new LinkedHashMap<>();
    private final Map<PrewarmKey, PrewarmCandidate> prewarmCandidates = new HashMap<>();
    private final BaseClusterTopology.BuildScratch buildScratch =
            new BaseClusterTopology.BuildScratch();
    private final LongAdder snapshotCells = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder buildRequests = new LongAdder();
    private final LongAdder buildNanos = new LongAdder();
    private final LongAdder publishedClusters = new LongAdder();
    private final LongAdder freshBuilds = new LongAdder();
    private final LongAdder persistenceHits = new LongAdder();
    private final LongAdder staleBuilds = new LongAdder();
    private final LongAdder coalescedInvalidations = new LongAdder();
    private final LongAdder superBuildRequests = new LongAdder();
    private final LongAdder superBuildNanos = new LongAdder();
    private final LongAdder publishedSuperClusters = new LongAdder();
    private final LongAdder staleSuperBuilds = new LongAdder();
    private final LongAdder evictedSuperClusters = new LongAdder();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicLong baseRetainedBytes = new AtomicLong();
    private final AtomicLong superRetainedBytes = new AtomicLong();
    private final LongAdder baseBoundaryBuildRequests = new LongAdder();
    private final LongAdder baseBoundaryBuildNanos = new LongAdder();
    private final LongAdder baseBoundaryHits = new LongAdder();
    private final LongAdder baseBoundaryMisses = new LongAdder();
    private final AtomicLong baseBoundaryRetainedBytes = new AtomicLong();
    private final LongAdder superBoundaryBuildRequests = new LongAdder();
    private final LongAdder superBoundaryBuildNanos = new LongAdder();
    private final LongAdder superBoundaryHits = new LongAdder();
    private final LongAdder superBoundaryMisses = new LongAdder();
    private final AtomicLong superBoundaryRetainedBytes = new AtomicLong();

    private boolean closed;
    private long topologyEpoch;
    private long topologyTick;
    private long demandSequence;
    private long viewAccessSequence;
    private int dependencyPermits;
    private int dependencyPermitHighWatermark;
    private int prewarmDimensionCursor;
    private int prewarmAdmitted;
    private long prewarmAdmissions;
    private long prewarmPublished;
    private long prewarmPromoted;
    private long prewarmCancelled;
    private long macroFlightSequence;
    private long macroLogicalRequests;
    private long macroPhysicalSearches;
    private long macroInFlightJoins;
    private long macroCompletedHits;
    private long macroCompletedMisses;
    private long macroStaleEvictions;
    private long macroCacheEvictions;
    private long completedCorridorBytes;
    private int macroMaximumGroupSize;
    private long parentBuildFailures;

    private TopologyService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.buildWorker = new TopologyTaskExecutor(
                "accelerated-navigation-topology",
                Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
        );
        this.publisher = server::execute;
        this.ownerThread = server::isSameThread;
        TopologyStore openedStore;
        try {
            openedStore = new TopologyStore(
                    server.getWorldPath(LevelResource.ROOT)
                            .resolve("data")
                            .resolve("accelerated_navigation")
                            .resolve("topology")
            );
        } catch (IOException exception) {
            AcceleratedNavigation.LOGGER.error("Could not open macro topology store", exception);
            openedStore = null;
        }
        this.store = openedStore;
    }

    public static TopologyService forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, TopologyService::new);
        }
    }

    public static void shutdown(MinecraftServer server) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.remove(server);
        }
        if (service != null) {
            service.shutdown();
        }
    }

    public static void endServerTick(MinecraftServer server) {
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(server);
        }
        if (service != null) {
            service.topologyTick++;
            service.startRequestedBuilds();
            service.admitPrewarm();
            service.evictBaseCache();
            service.evictSuperCache();
        }
    }

    public static void onBlockChanged(Level level,
                                      BlockPos position,
                                      BlockState oldState,
                                      VoxelShape oldShape,
                                      BlockState newState,
                                      VoxelShape newShape) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.getServer().isSameThread()) {
            BlockPos immutablePosition = position.immutable();
            serverLevel.getServer().execute(
                    () -> onBlockChanged(
                            serverLevel,
                            immutablePosition,
                            oldState,
                            oldShape,
                            newState,
                            newShape
                    )
            );
            return;
        }
        if (!navigationGeometryChanged(
                oldShape,
                !oldState.getFluidState().isEmpty(),
                requiresExactCheck(oldState, oldShape),
                newShape,
                !newState.getFluidState().isEmpty(),
                requiresExactCheck(newState, newShape)
        )) {
            return;
        }
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(serverLevel.getServer());
        }
        if (service == null) {
            return;
        }
        SectionPos section = SectionPos.of(position);
        service.invalidate(new ClusterKey(serverLevel.dimension(), section));
        if ((position.getY() & 15) == 15 && oldShape.isEmpty() != newShape.isEmpty()) {
            service.invalidate(new ClusterKey(serverLevel.dimension(), SectionPos.of(
                    section.x(), section.y() + 1, section.z())));
        }
    }

    public static void onChunkUnloaded(ServerLevel level, ChunkPos chunk) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> onChunkUnloaded(level, chunk));
            return;
        }
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(level.getServer());
        }
        if (service != null) {
            service.evictChunk(level.dimension(), chunk);
        }
    }

    public static void onChunkLoaded(ServerLevel level, ChunkPos chunk) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> onChunkLoaded(level, chunk));
            return;
        }
        forServer(level.getServer()).enqueuePrewarm(level.dimension(), chunk);
    }

    public static void onLevelSave(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> onLevelSave(level));
            return;
        }
        TopologyService service;
        synchronized (SERVICES) {
            service = SERVICES.get(level.getServer());
        }
        if (service != null && service.store != null && !service.closed) {
            service.store.save(level.dimension()).join();
        }
    }

    static boolean navigationGeometryChanged(VoxelShape oldShape,
                                             boolean oldContainsFluid,
                                             boolean oldExactRequired,
                                             VoxelShape newShape,
                                             boolean newContainsFluid,
                                             boolean newExactRequired) {
        Objects.requireNonNull(oldShape, "oldShape");
        Objects.requireNonNull(newShape, "newShape");
        if (oldContainsFluid != newContainsFluid || oldExactRequired != newExactRequired) {
            return true;
        }
        return Shapes.joinIsNotEmpty(oldShape, newShape, BooleanOp.NOT_SAME);
    }

    private static boolean requiresExactCheck(BlockState state, VoxelShape collisionShape) {
        return state.getBlock().hasDynamicShape() || !collisionShape.isEmpty();
    }

    private static int classifyCell(BlockState state,
                                    boolean fullCollision,
                                    boolean collides,
                                    boolean supportBelow,
                                    boolean exactRequired) {
        boolean fluid = !state.getFluidState().isEmpty();
        int flags = fluid ? BaseClusterTopology.FLUID : 0;
        if (fullCollision) {
            return flags;
        }

        flags |= BaseClusterTopology.VOLUME_OPEN;
        if (collides || supportBelow || fluid) {
            flags |= BaseClusterTopology.GROUND_OPEN;
        }
        if (exactRequired) {
            flags |= BaseClusterTopology.EXACT_REQUIRED;
        }
        return flags;
    }

    private static int staticCellClassification(BlockState state) {
        if (state.getBlock().getClass() == LiquidBlock.class) {
            return classifyCell(state, false, false, false, false);
        }
        if (state.getBlock().hasDynamicShape()) {
            return DYNAMIC_COLLISION;
        }
        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return classifyCell(state, true, true, false, false) | CELL_COLLIDES;
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean collides = !shape.isEmpty();
        return classifyCell(state, false, collides, false, collides)
                | (collides ? CELL_COLLIDES : 0);
    }

    TopologySubscription<BaseClusterTopology> subscribeClusterDependency(
            ServerLevel level,
            SectionPos section,
            NavigationScheduler.Priority priority) {
        return subscribeClusterDependency(level, section, DEFAULT_GEOMETRY, priority);
    }

    private TopologySubscription<BaseClusterTopology> subscribeClusterDependency(
            ServerLevel level,
            SectionPos section,
            BaseClusterTopology.GeometryKey geometry,
            NavigationScheduler.Priority priority) {
        return subscribeClusterDependency(level, section, geometry, priority, false);
    }

    private TopologySubscription<BaseClusterTopology> subscribeClusterDependency(
            ServerLevel level,
            SectionPos section,
            BaseClusterTopology.GeometryKey geometry,
            NavigationScheduler.Priority priority,
            boolean prewarm) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(priority, "priority");
        if (level.getServer() == null || forServer(level.getServer()) != this) {
            throw new IllegalArgumentException("level belongs to a different topology service");
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(section.x(), section.z());
        if (chunk == null) {
            return failedSubscription(
                    priority,
                    new IllegalStateException("topology request cannot load an unavailable chunk")
            );
        }

        ClusterKey key = new ClusterKey(level.dimension(), section);
        ClusterEntry entry = clusters.computeIfAbsent(key, ignored -> new ClusterEntry());
        ViewEntry view = entry.view(geometry);
        view.lastAccess = ++viewAccessSequence;
        if (view.topology != null && view.topology.revision() == entry.revision) {
            return completedSubscription(view.topology, priority);
        }
        TopologyDemand demand = view.demand;
        if (demand == null) {
            demand = new TopologyDemand(
                    key,
                    geometry,
                    entry.revision,
                    ++demandSequence,
                    System.nanoTime()
            );
            view.demand = demand;
            demand.prewarmSlot = prewarm;
        } else if (!prewarm && demand.prewarmSlot) {
            prewarmPromoted++;
            releasePrewarmSlot(demand);
            if (demand.buildTask != UNTRACKED_TASK) demand.buildTask.enableAging();
        }
        TopologySubscription<BaseClusterTopology> subscription =
                new TopologySubscription<>(priority, prewarm);
        subscription.demand = demand;
        subscription.cancellation = () -> cancelClusterSubscription(subscription);
        subscription.reprioritization = requested -> reconcileClusterSubscription(
                subscription,
                requested
        );
        demand.waiters.add(subscription);
        NavigationScheduler.Priority previous = demand.priority;
        demand.priority = higherPriority(previous, priority);
        if (demand.queued && previous != demand.priority) {
            requestedBuilds.reprioritize(demand, previous);
        }
        if (previous != demand.priority) {
            promoteDemandFacts(level, demand);
        }
        if (demand.buildTask != UNTRACKED_TASK && previous != demand.priority) {
            demand.buildTask.enableAging();
            demand.buildTask.promote(demand.priority);
        }
        if (!demand.prewarmSlot && !demand.queued && !demand.dependencyPermit) {
            acquireDependencyPermit(demand);
        }
        enqueueRequestedBuild(demand);
        return subscription;
    }

    private TopologySubscription<SuperClusterTopology> requestSuperCluster(
            ServerLevel level,
            SectionPos origin,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(priority, "priority");
        if (!SuperClusterTopology.originOf(origin).equals(origin)) {
            throw new IllegalArgumentException("super-cluster origin is not aligned");
        }
        SuperCacheKey key = new SuperCacheKey(level.dimension(), origin, channel, profile);
        if (!superClusterAvailable(level, origin)) {
            return failedSubscription(
                    priority,
                    new IllegalStateException("super topology cannot use unavailable sections")
            );
        }

        SuperEntry entry = superClusters.computeIfAbsent(key, ignored -> new SuperEntry());
        SuperClusterTopology ready = entry.topology;
        if (ready != null) {
            return completedSubscription(ready, priority);
        }
        TopologySubscription<SuperClusterTopology> subscription =
                new TopologySubscription<>(priority);
        subscription.cancellation = () -> cancelSuperSubscription(key, entry, subscription);
        subscription.reprioritization = requested -> reconcileSuperSubscription(
                entry,
                subscription,
                requested
        );
        entry.waiters.add(subscription);
        NavigationScheduler.Priority previousPriority = entry.requestPriority;
        entry.requestPriority = higherPriority(previousPriority, priority);
        if (entry.buildTask != null && previousPriority != entry.requestPriority) {
            entry.buildTask.reprioritize(entry.requestPriority);
        }
        if (entry.attemptRunning) {
            reconcileSuperChildren(entry);
        }
        if (!entry.attemptRunning) {
            beginSuperRequest(level, key, entry);
        }
        return subscription;
    }

    private void beginSuperRequest(ServerLevel level,
                                   SuperCacheKey key,
                                   SuperEntry entry) {
        requireOwnerThread();
        if (closed || entry.waiters.isEmpty()) {
            return;
        }
        entry.attemptRunning = true;
        long attempt = ++entry.attempt;
        List<TopologySubscription<BaseClusterTopology>> children = new ArrayList<>(8);
        try {
            for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
                children.add(subscribeClusterDependency(
                        level,
                        child,
                        key.geometry(),
                        entry.requestPriority
                ));
            }
            entry.children = List.copyOf(children);
        } catch (RuntimeException failure) {
            children.forEach(TopologySubscription::cancel);
            entry.attemptRunning = false;
            failSuperRequest(key, entry, failure);
            return;
        }
        CompletableFuture.allOf(children.stream()
                        .map(TopologySubscription::future)
                        .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> publisher.execute(
                        () -> completeSuperChildren(level, key, entry, attempt, failure)
                ));
    }

    private void completeSuperChildren(ServerLevel level,
                                       SuperCacheKey key,
                                       SuperEntry expected,
                                       long attempt,
                                       @Nullable Throwable failure) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt || closed
                || entry.waiters.isEmpty()) {
            return;
        }
        entry.children = List.of();
        if (failure != null) {
            entry.attemptRunning = false;
            if (retryableAttemptFailure(failure) && superClusterAvailable(level, key.origin())) {
                beginSuperRequest(level, key, entry);
            } else {
                failSuperRequest(key, entry, failure);
            }
            return;
        }
        BaseClusterTopology[] childSnapshot = currentChildTopologies(key);
        if (childSnapshot == null) {
            entry.attemptRunning = false;
            beginSuperRequest(level, key, entry);
            return;
        }

        superBuildRequests.increment();
        try {
            entry.buildTask = buildWorker.submit(
                    key.dimension(),
                    entry.requestPriority,
                    () -> buildSuperCluster(key, entry, attempt, childSnapshot)
            );
        } catch (RejectedExecutionException exception) {
            entry.attemptRunning = false;
            failSuperRequest(key, entry, exception);
        }
    }

    private void buildSuperCluster(SuperCacheKey key,
                                   SuperEntry expected,
                                   long attempt,
                                   BaseClusterTopology[] childSnapshot) {
        long started = System.nanoTime();
        SuperClusterTopology topology;
        try {
            topology = SuperClusterTopology.build(
                    key.origin(),
                    childSnapshot,
                    key.geometry(),
                    key.movement(),
                    buildScratch
            );
        } catch (RuntimeException failure) {
            superBuildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failSuperBuild(key, expected, attempt, failure));
            return;
        }
        superBuildNanos.add(System.nanoTime() - started);
        publisher.execute(() -> publishSuperCluster(
                key,
                expected,
                attempt,
                childSnapshot,
                topology
        ));
    }

    private void publishSuperCluster(SuperCacheKey key,
                                     SuperEntry expected,
                                     long attempt,
                                     BaseClusterTopology[] childSnapshot,
                                     SuperClusterTopology topology) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt || closed
                || entry.waiters.isEmpty()) {
            staleSuperBuilds.increment();
            return;
        }
        BaseClusterTopology[] current = currentChildTopologies(key);
        if (current == null || !Arrays.equals(childSnapshot, current)
                || !topology.matchesChildren(current)) {
            staleSuperBuilds.increment();
            entry.attemptRunning = false;
            entry.buildTask = null;
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !superClusterAvailable(level, key.origin())) {
                failSuperRequest(key, entry, new StaleTopologyException(
                        new ClusterKey(key.dimension(), key.origin())
                ));
            } else {
                beginSuperRequest(level, key, entry);
            }
            return;
        }

        removeSuperTopology(key, entry, true);
        entry.topology = topology;
        entry.handoffUntilTick = topologyTick + 1L;
        entry.attemptRunning = false;
        entry.buildTask = null;
        retainedBytes.addAndGet(topology.retainedBytes());
        superRetainedBytes.addAndGet(topology.retainedBytes());
        publishedSuperClusters.increment();
        topologyChanged();
        List<TopologySubscription<SuperClusterTopology>> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        entry.requestPriority = null;
        for (TopologySubscription<SuperClusterTopology> waiter : waiters) {
            waiter.active = false;
            waiter.future().complete(topology);
        }
        evictSuperCache();
    }

    private void failSuperBuild(SuperCacheKey key,
                                SuperEntry expected,
                                long attempt,
                                RuntimeException failure) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt) {
            return;
        }
        entry.attemptRunning = false;
        failSuperRequest(key, entry, failure);
    }

    private void failSuperRequest(SuperCacheKey key,
                                  SuperEntry entry,
                                  Throwable failure) {
        List<TopologySubscription<SuperClusterTopology>> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        entry.requestPriority = null;
        if (entry.buildTask != null) {
            entry.buildTask.cancel();
            entry.buildTask = null;
        }
        cancelSuperChildren(entry);
        entry.attemptRunning = false;
        for (TopologySubscription<SuperClusterTopology> waiter : waiters) {
            waiter.active = false;
            waiter.future().completeExceptionally(failure);
        }
        if (entry.topology == null) {
            superClusters.remove(key, entry);
        }
    }

    private CompletableFuture<SuperClusterTopology.BoundaryLinks> requestBaseBoundaryLinks(
            ResourceKey<Level> dimension,
            BaseClusterTopology source,
            BaseClusterTopology target,
            Direction face,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        BaseBoundaryCacheKey key = new BaseBoundaryCacheKey(
                dimension,
                source,
                target,
                face
        );
        ViewEntry owner = baseView(key.dimension(), key.source());
        if (owner == null) {
            return CompletableFuture.failedFuture(new StaleTopologyException(
                    "base boundary source is no longer current"
            ));
        }
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        if (owner.linkTargetSignatures[slot] != key.target().signature()) {
            clearBaseLink(owner, slot, "base boundary target changed");
            owner.linkTargetSignatures[slot] = key.target().signature();
        }
        LinkEntry<SuperClusterTopology.BoundaryLinks> existing = owner.links[slot];
        if (existing != null) {
            existing.promote(priority);
            return existing.future;
        }

        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = new LinkEntry<>(priority);
        owner.links[slot] = entry;
        baseBoundaryBuildRequests.increment();
        try {
            entry.track(buildWorker.submit(
                    key.dimension(),
                    priority,
                    () -> buildBaseBoundaryLinks(key, entry)
            ));
        } catch (RejectedExecutionException failure) {
            if (owner.links[slot] == entry) owner.links[slot] = null;
            entry.future.completeExceptionally(failure);
        }
        return entry.future;
    }

    private void buildBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected) {
        long started = System.nanoTime();
        SuperClusterTopology.BoundaryLinks links;
        try {
            links = SuperClusterTopology.boundaryLinks(
                    key.source(),
                    key.target(),
                    key.face()
            );
        } catch (RuntimeException failure) {
            baseBoundaryBuildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failBaseBoundaryLinks(key, expected, failure));
            return;
        }
        baseBoundaryBuildNanos.add(System.nanoTime() - started);
        publisher.execute(() -> publishBaseBoundaryLinks(key, expected, links));
    }

    private void publishBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected,
            SuperClusterTopology.BoundaryLinks links) {
        requireOwnerThread();
        ViewEntry owner = baseView(key.dimension(), key.source());
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        if (closed || owner == null || owner.links[slot] != expected
                || owner.linkTargetSignatures[slot] != key.target().signature()
                || !baseBoundaryKeyCurrent(key)) {
            if (owner != null && owner.links[slot] == expected) owner.links[slot] = null;
            expected.future.completeExceptionally(new StaleTopologyException(
                    "base boundary topology changed while links were building"
            ));
            return;
        }
        expected.value = links;
        expected.task = UNTRACKED_TASK;
        baseBoundaryRetainedBytes.addAndGet(links.retainedBytes());
        baseRetainedBytes.addAndGet(links.retainedBytes());
        retainedBytes.addAndGet(links.retainedBytes());
        expected.future.complete(links);
        evictBaseCache();
    }

    private void failBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected,
            RuntimeException failure) {
        requireOwnerThread();
        ViewEntry owner = baseView(key.dimension(), key.source());
        if (owner != null) {
            int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
            if (owner.links[slot] == expected) owner.links[slot] = null;
        }
        expected.future.completeExceptionally(failure);
    }

    private CompletableFuture<SuperClusterTopology.CrossingIndex> requestSuperBoundaryLinks(
            ResourceKey<Level> dimension,
            SuperClusterTopology source,
            SuperClusterTopology target,
            Direction face,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        SuperBoundaryCacheKey key = new SuperBoundaryCacheKey(
                dimension,
                source,
                target,
                face
        );
        SuperEntry owner = superView(key.dimension(), key.source());
        if (owner == null) {
            return CompletableFuture.failedFuture(new StaleTopologyException(
                    "parent boundary source is no longer current"
            ));
        }
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        if (owner.linkTargetSignatures[slot] != key.target().signature()) {
            clearSuperLink(owner, slot, "parent boundary target changed");
            owner.linkTargetSignatures[slot] = key.target().signature();
        }
        LinkEntry<SuperClusterTopology.CrossingIndex> existing = owner.links[slot];
        if (existing != null) {
            existing.promote(priority);
            return existing.future;
        }

        LinkEntry<SuperClusterTopology.CrossingIndex> entry = new LinkEntry<>(priority);
        owner.links[slot] = entry;
        superBoundaryBuildRequests.increment();
        SuperCacheKey sourceKey = new SuperCacheKey(dimension, source.origin(),
                source.geometry(), source.movement());
        SuperCacheKey targetKey = new SuperCacheKey(dimension, target.origin(),
                target.geometry(), target.movement());
        BaseClusterTopology[] sourceChildren = currentChildTopologies(sourceKey);
        BaseClusterTopology[] targetChildren = currentChildTopologies(targetKey);
        if (sourceChildren == null || targetChildren == null) {
            beginSuperBoundaryChildren(key, entry, sourceKey, targetKey);
            return entry.future;
        }
        submitSuperBoundaryBuild(key, entry, sourceChildren, targetChildren);
        return entry.future;
    }

    private void beginSuperBoundaryChildren(SuperBoundaryCacheKey key,
                                            LinkEntry<SuperClusterTopology.CrossingIndex> entry,
                                            SuperCacheKey sourceKey,
                                            SuperCacheKey targetKey) {
        ServerLevel level = server.getLevel(key.dimension());
        if (level == null) {
            failSuperBoundaryLinks(key, entry, new StaleTopologyException("parent level unloaded"));
            return;
        }
        List<TopologySubscription<BaseClusterTopology>> children = new ArrayList<>(16);
        try {
            for (SectionPos child : SuperClusterTopology.childSections(sourceKey.origin())) {
                children.add(subscribeClusterDependency(level, child, sourceKey.geometry(), entry.priority));
            }
            for (SectionPos child : SuperClusterTopology.childSections(targetKey.origin())) {
                children.add(subscribeClusterDependency(level, child, targetKey.geometry(), entry.priority));
            }
            entry.children = List.copyOf(children);
        } catch (RuntimeException failure) {
            children.forEach(TopologySubscription::cancel);
            failSuperBoundaryLinks(key, entry, failure);
            return;
        }
        CompletableFuture.allOf(children.stream().map(TopologySubscription::future)
                        .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> publisher.execute(() -> {
                    entry.children = List.of();
                    if (failure != null) {
                        failSuperBoundaryLinks(key, entry, failure);
                        return;
                    }
                    BaseClusterTopology[] source = currentChildTopologies(sourceKey);
                    BaseClusterTopology[] target = currentChildTopologies(targetKey);
                    if (source == null || target == null) {
                        failSuperBoundaryLinks(key, entry,
                                new StaleTopologyException("parent boundary children changed"));
                    } else {
                        submitSuperBoundaryBuild(key, entry, source, target);
                    }
                }));
    }

    private void submitSuperBoundaryBuild(SuperBoundaryCacheKey key,
                                          LinkEntry<SuperClusterTopology.CrossingIndex> entry,
                                          BaseClusterTopology[] sourceChildren,
                                          BaseClusterTopology[] targetChildren) {
        try {
            entry.track(buildWorker.submit(key.dimension(), entry.priority,
                    () -> buildSuperBoundaryLinks(key, entry, sourceChildren, targetChildren)));
        } catch (RejectedExecutionException failure) {
            failSuperBoundaryLinks(key, entry, failure);
        }
    }

    private void buildSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            BaseClusterTopology[] sourceChildren,
            BaseClusterTopology[] targetChildren) {
        long started = System.nanoTime();
        SuperClusterTopology.CrossingIndex links;
        try {
            links = key.source().crossingIndex(
                    key.face(), key.target(), sourceChildren, targetChildren);
        } catch (RuntimeException failure) {
            superBoundaryBuildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failSuperBoundaryLinks(key, expected, failure));
            return;
        }
        superBoundaryBuildNanos.add(System.nanoTime() - started);
        publisher.execute(() -> publishSuperBoundaryLinks(key, expected, links));
    }

    private void publishSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            SuperClusterTopology.CrossingIndex links) {
        requireOwnerThread();
        SuperEntry owner = superView(key.dimension(), key.source());
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        if (closed || owner == null || owner.links[slot] != expected
                || owner.linkTargetSignatures[slot] != key.target().signature()
                || !superBoundaryKeyCurrent(key)) {
            if (owner != null && owner.links[slot] == expected) owner.links[slot] = null;
            expected.future.completeExceptionally(new StaleTopologyException(
                    "super boundary topology changed while links were building"
            ));
            return;
        }
        expected.value = links;
        expected.task = UNTRACKED_TASK;
        superBoundaryRetainedBytes.addAndGet(links.retainedBytes());
        superRetainedBytes.addAndGet(links.retainedBytes());
        retainedBytes.addAndGet(links.retainedBytes());
        expected.future.complete(links);
        evictSuperCache();
    }

    private void failSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            Throwable failure) {
        requireOwnerThread();
        SuperEntry owner = superView(key.dimension(), key.source());
        if (owner != null) {
            int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
            if (owner.links[slot] == expected) owner.links[slot] = null;
        }
        expected.future.completeExceptionally(failure);
    }

    @Nullable
    private SuperClusterTopology.BoundaryLinks readyBaseBoundaryLinks(BaseBoundaryCacheKey key) {
        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = baseLinkEntry(key);
        if (entry == null || entry.value == null) {
            baseBoundaryMisses.increment();
            return null;
        }
        baseBoundaryHits.increment();
        return entry.value;
    }

    @Nullable
    private LinkEntry<SuperClusterTopology.BoundaryLinks> baseLinkEntry(BaseBoundaryCacheKey key) {
        ViewEntry owner = baseView(key.dimension(), key.source());
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        return owner == null || owner.linkTargetSignatures[slot] != key.target().signature()
                ? null : owner.links[slot];
    }

    private int baseLinkCount() {
        int count = 0;
        for (ClusterEntry entry : clusters.values()) {
            for (ViewEntry view : entry.views.values()) {
                for (LinkEntry<SuperClusterTopology.BoundaryLinks> link : view.links) {
                    if (link != null && link.value != null) count++;
                }
            }
        }
        return count;
    }

    @Nullable
    private SuperClusterTopology.CrossingIndex readySuperBoundaryLinks(
            SuperBoundaryCacheKey key) {
        LinkEntry<SuperClusterTopology.CrossingIndex> entry = superLinkEntry(key);
        if (entry == null || entry.value == null) {
            superBoundaryMisses.increment();
            return null;
        }
        superBoundaryHits.increment();
        return entry.value;
    }

    private boolean baseBoundaryKeyCurrent(BaseBoundaryCacheKey key) {
        ClusterEntry source = clusters.get(new ClusterKey(key.dimension(), key.source().section()));
        ClusterEntry target = clusters.get(new ClusterKey(key.dimension(), key.target().section()));
        BaseClusterTopology currentTarget = target == null
                ? null : target.topology(key.target().geometry());
        return source != null && source.topology(key.source().geometry()) == key.source()
                && currentTarget != null && currentTarget.signature() == key.target().signature();
    }

    @Nullable
    private ViewEntry baseView(ResourceKey<Level> dimension, BaseClusterTopology topology) {
        ClusterEntry entry = clusters.get(new ClusterKey(dimension, topology.section()));
        ViewEntry view = entry == null ? null : entry.views.get(topology.geometry());
        return view != null && view.topology == topology ? view : null;
    }

    private static int baseLinkSlot(SectionPos source, SectionPos target, Direction face) {
        if (face.getAxis().isVertical()) return face == Direction.DOWN ? 12 : 13;
        int direction = switch (face) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("horizontal face required");
        };
        int yShift = target.y() - source.y();
        if (yShift < -1 || yShift > 1) {
            throw new IllegalArgumentException("horizontal boundary Y shift is outside -1..1");
        }
        return direction * 3 + yShift + 1;
    }

    private void clearBaseLink(ViewEntry owner, int slot, String reason) {
        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = owner.links[slot];
        owner.links[slot] = null;
        owner.linkTargetSignatures[slot] = 0L;
        if (entry == null) return;
        entry.task.cancel();
        entry.children.forEach(TopologySubscription::cancel);
        entry.children = List.of();
        if (entry.value != null) {
            baseBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            baseRetainedBytes.addAndGet(-entry.value.retainedBytes());
            retainedBytes.addAndGet(-entry.value.retainedBytes());
        }
        entry.future.completeExceptionally(new StaleTopologyException(reason));
    }

    private boolean superBoundaryKeyCurrent(SuperBoundaryCacheKey key) {
        SuperEntry source = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.source().origin(),
                key.source().geometry(),
                key.source().movement()
        ));
        SuperEntry target = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.target().origin(),
                key.target().geometry(),
                key.target().movement()
        ));
        return source != null && source.topology == key.source()
                && target != null && target.topology != null
                && target.topology.signature() == key.target().signature();
    }

    @Nullable
    private SuperEntry superView(ResourceKey<Level> dimension,
                                 SuperClusterTopology topology) {
        SuperEntry entry = superClusters.get(new SuperCacheKey(
                dimension, topology.origin(), topology.geometry(), topology.movement()
        ));
        return entry != null && entry.topology == topology ? entry : null;
    }

    private static int superLinkSlot(SectionPos source, SectionPos target, Direction face) {
        if (face.getAxis().isVertical()) return face == Direction.DOWN ? 12 : 13;
        int ySections = target.y() - source.y();
        if (ySections % SuperClusterTopology.CHILDREN_PER_AXIS != 0) {
            throw new IllegalArgumentException("parent boundary is not parent-grid aligned");
        }
        SectionPos normalizedTarget = SectionPos.of(
                target.x(), source.y() + ySections / SuperClusterTopology.CHILDREN_PER_AXIS,
                target.z()
        );
        SectionPos normalizedSource = SectionPos.of(source.x(), source.y(), source.z());
        return baseLinkSlot(normalizedSource, normalizedTarget, face);
    }

    @Nullable
    private LinkEntry<SuperClusterTopology.CrossingIndex> superLinkEntry(
            SuperBoundaryCacheKey key) {
        SuperEntry owner = superView(key.dimension(), key.source());
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        return owner == null || owner.linkTargetSignatures[slot] != key.target().signature()
                ? null : owner.links[slot];
    }

    private void clearSuperLink(SuperEntry owner, int slot, String reason) {
        LinkEntry<SuperClusterTopology.CrossingIndex> entry = owner.links[slot];
        owner.links[slot] = null;
        owner.linkTargetSignatures[slot] = 0L;
        if (entry == null) return;
        entry.task.cancel();
        entry.children.forEach(TopologySubscription::cancel);
        entry.children = List.of();
        if (entry.value != null) {
            superBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            superRetainedBytes.addAndGet(-entry.value.retainedBytes());
            retainedBytes.addAndGet(-entry.value.retainedBytes());
        }
        entry.future.completeExceptionally(new StaleTopologyException(reason));
    }

    private int superLinkCount() {
        int count = 0;
        for (SuperEntry entry : superClusters.values()) {
            for (LinkEntry<SuperClusterTopology.CrossingIndex> link : entry.links) {
                if (link != null && link.value != null) count++;
            }
        }
        return count;
    }

    private void startRequestedBuilds() {
        requireOwnerThread();
        ensureOpen();
        int queuedAtTickEnd = requestedBuilds.size();
        for (int index = 0; index < queuedAtTickEnd; index++) {
            TopologyDemand demand = requestedBuilds.poll(
                    System.nanoTime(),
                    candidate -> candidate.prewarmSlot || candidate.dependencyPermit
                            || dependencyPermits < MAX_DEPENDENCY_DEMANDS
            );
            if (demand == null) {
                break;
            }
            ClusterKey key = demand.key;
            ClusterEntry entry = clusters.get(key);
            ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
            if (view == null || view.demand != demand || demand.waiters.isEmpty()) {
                continue;
            }
            if (view.topology != null && view.topology.revision() == entry.revision) {
                completeDemand(entry, demand, view.topology);
                continue;
            }
            if (demand.buildTask != UNTRACKED_TASK) {
                continue;
            }
            if (!demand.prewarmSlot && !demand.dependencyPermit
                    && !acquireDependencyPermit(demand)) {
                enqueueRequestedBuild(demand);
                break;
            }
            ServerLevel level = server.getLevel(key.dimension());
            LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(
                    key.section().x(),
                    key.section().z()
            );
            if (level == null || chunk == null) {
                failDemand(entry, demand, new IllegalStateException(
                        "topology request cannot use an unavailable chunk"
                ));
                continue;
            }
            prepareDemand(level, entry, demand, chunk);
        }
    }

    private void prepareDemand(ServerLevel level,
                               ClusterEntry entry,
                               TopologyDemand demand,
                               LevelChunk chunk) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view == null || view.demand != demand || demand.waiters.isEmpty()
                || demand.buildTask != UNTRACKED_TASK) return;
        if (entry.facts == null) {
            joinFactsAttempt(level, demand.key, entry, demand, chunk);
            return;
        }
        ViewEntry defaultView = entry.views.get(DEFAULT_GEOMETRY);
        boolean eagerDefault = !demand.prewarmSlot && !demand.geometry.equals(DEFAULT_GEOMETRY)
                && (defaultView == null || defaultView.topology == null);
        int horizontal = demand.geometry.widthCells() == 1 ? 0 : 1;
        int lower = demand.geometry.channel() == BaseClusterTopology.Channel.GROUND || eagerDefault ? -1 : 0;
        int upper = demand.geometry.heightCells() == 1 && !eagerDefault ? 0 : 1;
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dy = lower; dy <= upper; dy++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    SectionPos section = SectionPos.of(demand.key.section().x() + dx,
                            demand.key.section().y() + dy, demand.key.section().z() + dz);
                    if (section.y() < level.getMinSection() || section.y() >= level.getMaxSection()) {
                        continue;
                    }
                    LevelChunk neighborChunk = level.getChunkSource().getChunkNow(
                            section.x(), section.z());
                    if (neighborChunk == null) continue;
                    ClusterKey neighborKey = new ClusterKey(demand.key.dimension(), section);
                    ClusterEntry neighbor = clusters.computeIfAbsent(
                            neighborKey, ignored -> new ClusterEntry());
                    if (neighbor.facts == null) {
                        joinFactsAttempt(level, neighborKey, neighbor, demand, neighborChunk);
                        return;
                    }
                    if (demand.heldFacts.add(neighbor)) neighbor.factPins++;
                }
            }
        }
        demand.fingerprint = entry.facts.fingerprint();
        buildRequests.increment();
        BaseClusterTopology.BuildInput input = buildInput(demand.key, entry.facts);
        releaseHeldFacts(demand);
        submitDemandBuild(demand, input, eagerDefault);
    }

    private void joinFactsAttempt(ServerLevel level,
                                  ClusterKey factsKey,
                                  ClusterEntry entry,
                                  TopologyDemand demand,
                                  LevelChunk chunk) {
        entry.dirty = false;
        FactsAttempt attempt = entry.factsAttempt;
        if (attempt != null) {
            if (attempt.demands.add(demand)) demand.factAttempts.add(attempt);
            promoteFactsAttempt(level, attempt, demand.priority);
            return;
        }
        attempt = new FactsAttempt(factsKey, entry.revision);
        attempt.demands.add(demand);
        demand.factAttempts.add(attempt);
        attempt.priority = demand.priority;
        entry.factsAttempt = attempt;
        if (chunk.getSection(chunk.getSectionIndexFromSectionY(factsKey.section().y()))
                .hasOnlyAir()) {
            completeFacts(entry, attempt, BaseClusterTopology.PackedFacts.allAir(),
                    BuildOrigin.FRESH_BUILD);
            return;
        }
        SnapshotSearch snapshotSearch = worldSnapshot(level, factsKey.section(), chunk);
        attempt.search = snapshotSearch;
        NavigationScheduler scheduler = NavigationScheduler.forServer(level.getServer());
        CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture = demand.prewarmSlot
                ? scheduler.submitPrewarm(level.dimension(), snapshotOwner(factsKey), snapshotSearch)
                : scheduler.submitDependency(level.dimension(), snapshotOwner(factsKey),
                attempt.priority, snapshotSearch);
        attempt.snapshotFuture = snapshotFuture;
        FactsAttempt expected = attempt;
        snapshotFuture.whenComplete((snapshot, failure) -> publisher.execute(() -> completeSnapshotAttempt(
                entry,
                expected,
                snapshot,
                failure
        )));
    }

    private void completeSnapshotAttempt(ClusterEntry entry,
                                         FactsAttempt attempt,
                                         @Nullable BaseClusterTopology.Snapshot snapshot,
                                         @Nullable Throwable failure) {
        requireOwnerThread();
        snapshotCells.add(attempt.search.sampledCells());
        snapshotNanos.add(attempt.search.spentNanos());
        if (clusters.get(attempt.key) != entry || entry.factsAttempt != attempt) {
            return;
        }
        attempt.snapshotFuture = null;
        if (closed || attempt.demands.isEmpty()) {
            entry.factsAttempt = null;
            return;
        }
        if (failure != null || snapshot == null || entry.revision != attempt.generation) {
            if (entry.revision != attempt.generation || retryableAttemptFailure(failure)) {
                entry.dirty = true;
                retryFactsAttempt(entry, attempt);
            } else {
                failFactsAttempt(entry, attempt, failure != null ? failure
                        : new IllegalStateException("snapshot search failed"));
            }
            return;
        }
        try {
            if (store == null) {
                completeFacts(entry, attempt, snapshot.packedFacts(), BuildOrigin.FRESH_BUILD);
            } else {
                store.read(attempt.key.dimension(), attempt.key.section())
                        .whenComplete((stored, readFailure) -> publisher.execute(
                                () -> completeStoredTopologyRead(
                                        entry,
                                        attempt,
                                        snapshot,
                                        stored == null ? null : stored.orElse(null),
                                        readFailure
                                )
                        ));
            }
        } catch (RuntimeException exception) {
            failFactsAttempt(entry, attempt, exception);
        }
    }

    private void completeStoredTopologyRead(ClusterEntry entry,
                                            FactsAttempt attempt,
                                            BaseClusterTopology.Snapshot snapshot,
                                            @Nullable BaseClusterTopology.PackedFacts stored,
                                            @Nullable Throwable readFailure) {
        requireOwnerThread();
        if (closed || clusters.get(attempt.key) != entry || entry.factsAttempt != attempt) {
            return;
        }
        if (entry.revision != attempt.generation) {
            entry.dirty = true;
            retryFactsAttempt(entry, attempt);
            return;
        }
        if (readFailure != null) {
            AcceleratedNavigation.LOGGER.warn(
                    "Could not read macro topology for {}",
                    attempt.key,
                    readFailure
            );
        }
        boolean persistenceHit = stored != null
                && stored.fingerprint() == snapshot.fingerprint();
        completeFacts(entry, attempt,
                persistenceHit ? stored : snapshot.packedFacts(),
                persistenceHit ? BuildOrigin.PERSISTENCE_HIT : BuildOrigin.FRESH_BUILD);
    }

    private void submitDemandBuild(TopologyDemand demand,
                                    BaseClusterTopology.BuildInput input,
                                    boolean eagerDefault) {
        try {
            demand.buildTask = buildWorker.submit(
                    demand.key.dimension(),
                    demand.priority,
                    () -> build(demand, input, eagerDefault),
                    !demand.prewarmSlot
            );
        } catch (RejectedExecutionException exception) {
            ClusterEntry entry = clusters.get(demand.key);
            ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
            if (view != null && view.demand == demand) {
                failDemand(entry, demand, exception);
            }
        }
    }

    private void completeFacts(ClusterEntry entry,
                               FactsAttempt attempt,
                               BaseClusterTopology.PackedFacts facts,
                               BuildOrigin origin) {
        requireOwnerThread();
        if (closed || clusters.get(attempt.key) != entry || entry.factsAttempt != attempt
                || entry.revision != attempt.generation) {
            return;
        }
        entry.factsAttempt = null;
        if (entry.facts != null) {
            retainedBytes.addAndGet(-entry.facts.retainedBytes());
            baseRetainedBytes.addAndGet(-entry.facts.retainedBytes());
        }
        boolean newHaloIdentity = entry.factsRevision != entry.revision
                || entry.factsFingerprint != facts.fingerprint();
        entry.facts = facts;
        entry.factsRevision = entry.revision;
        entry.factsFingerprint = facts.fingerprint();
        retainedBytes.addAndGet(facts.retainedBytes());
        baseRetainedBytes.addAndGet(facts.retainedBytes());
        entry.dirty = false;
        if (origin == BuildOrigin.PERSISTENCE_HIT) {
            persistenceHits.increment();
        } else {
            freshBuilds.increment();
            if (store != null) store.markDirty(attempt.key.dimension(), attempt.key.section(), facts);
        }
        if (newHaloIdentity) invalidateHaloDependents(attempt.key, true);
        for (TopologyDemand demand : List.copyOf(attempt.demands)) {
            demand.factAttempts.remove(attempt);
            resumeDemand(demand);
        }
        attempt.demands.clear();
        releaseUnusedFacts(entry);
        evictBaseCache();
    }

    private void retryFactsAttempt(ClusterEntry entry, FactsAttempt attempt) {
        entry.factsAttempt = null;
        for (TopologyDemand demand : List.copyOf(attempt.demands)) {
            demand.factAttempts.remove(attempt);
            resumeDemand(demand);
        }
        attempt.demands.clear();
    }

    private void failFactsAttempt(ClusterEntry entry,
                                  FactsAttempt attempt,
                                  Throwable failure) {
        entry.factsAttempt = null;
        for (TopologyDemand demand : List.copyOf(attempt.demands)) {
            demand.factAttempts.remove(attempt);
            ClusterEntry owner = clusters.get(demand.key);
            ViewEntry view = owner == null ? null : owner.views.get(demand.geometry);
            if (view != null && view.demand == demand) failDemand(owner, demand, failure);
        }
        attempt.demands.clear();
        releaseUnusedFacts(entry);
    }

    private void resumeDemand(TopologyDemand demand) {
        ClusterEntry entry = clusters.get(demand.key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        ServerLevel level = server.getLevel(demand.key.dimension());
        LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(
                demand.key.section().x(), demand.key.section().z());
        if (view == null || view.demand != demand || demand.waiters.isEmpty()) return;
        if (level == null || chunk == null) {
            failDemand(entry, demand, new StaleTopologyException(demand.key));
        } else {
            prepareDemand(level, entry, demand, chunk);
        }
    }

    private void releaseUnusedFacts(ClusterEntry entry) {
        if (entry.facts == null || entry.factsAttempt != null || entry.factPins != 0) return;
        for (ViewEntry view : entry.views.values()) {
            if (view.topology != null || view.demand != null) return;
        }
        retainedBytes.addAndGet(-entry.facts.retainedBytes());
        baseRetainedBytes.addAndGet(-entry.facts.retainedBytes());
        entry.facts = null;
    }

    private void promoteFactsAttempt(ServerLevel level,
                                     FactsAttempt attempt,
                                     NavigationScheduler.Priority requested) {
        boolean promoted = requested.higherThan(attempt.priority);
        if (promoted) attempt.priority = requested;
        if (attempt.snapshotFuture != null) {
            NavigationScheduler scheduler = NavigationScheduler.forServer(level.getServer());
            boolean dependency = attempt.demands.stream()
                    .anyMatch(demand -> demand.dependencyPermit);
            if (dependency) {
                scheduler.qualifyDependency(
                        level.dimension(), snapshotOwner(attempt.key), attempt.priority
                );
            } else if (promoted) {
                scheduler.promote(
                        level.dimension(), snapshotOwner(attempt.key), attempt.priority
                );
            }
        }
    }

    private BaseClusterTopology.BuildInput buildInput(ClusterKey key,
                                                      BaseClusterTopology.PackedFacts center) {
        byte[] offsets = new byte[26];
        BaseClusterTopology.PackedFacts[] facts = new BaseClusterTopology.PackedFacts[26];
        long[] revisions = new long[26];
        long[] fingerprints = new long[26];
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    SectionPos section = SectionPos.of(
                            key.section().x() + dx,
                            key.section().y() + dy,
                            key.section().z() + dz
                    );
                    ClusterEntry neighbor = clusters.get(new ClusterKey(key.dimension(), section));
                    if (neighbor == null || neighbor.facts == null) continue;
                    offsets[count] = (byte) BaseClusterTopology.haloIndex(dx, dy, dz);
                    facts[count] = neighbor.facts;
                    revisions[count] = neighbor.revision;
                    fingerprints[count] = neighbor.facts.fingerprint();
                    count++;
                }
            }
        }
        return new BaseClusterTopology.BuildInput(
                center,
                Arrays.copyOf(offsets, count),
                Arrays.copyOf(facts, count),
                Arrays.copyOf(revisions, count),
                Arrays.copyOf(fingerprints, count)
        );
    }

    private void invalidateHaloDependents(ClusterKey source, boolean newlyAvailable) {
        boolean changed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    ClusterKey key = new ClusterKey(source.dimension(), SectionPos.of(
                            source.section().x() + dx,
                            source.section().y() + dy,
                            source.section().z() + dz
                    ));
                    ClusterEntry entry = clusters.get(key);
                    if (entry == null) continue;
                    boolean entryChanged = false;
                    for (Map.Entry<BaseClusterTopology.GeometryKey, ViewEntry> cached
                            : entry.views.entrySet()) {
                        ViewEntry view = cached.getValue();
                        boolean affected = view.topology == null
                                ? view.lastSignature != 0L
                                && requiresHalo(cached.getKey(), -dx, -dy, -dz)
                                : newlyAvailable
                                ? requiresHalo(cached.getKey(), -dx, -dy, -dz)
                                : referencesHalo(view.topology, -dx, -dy, -dz);
                        if (!affected) continue;
                        if (view.topology == null) {
                            view.lastSignature = 0L;
                            changed = true;
                            entryChanged = true;
                            continue;
                        }
                        BaseClusterTopology stale = view.topology;
                        view.topology = null;
                        retireBaseTopology(view, stale);
                        changed = true;
                        entryChanged = true;
                    }
                    if (entryChanged) invalidateSuperParent(key, false);
                }
            }
        }
        if (changed) topologyChanged();
    }

    private static boolean requiresHalo(BaseClusterTopology.GeometryKey geometry,
                                        int dx,
                                        int dy,
                                        int dz) {
        int horizontal = geometry.widthCells() == 1 ? 0 : 1;
        int lower = geometry.channel() == BaseClusterTopology.Channel.GROUND ? -1 : 0;
        int upper = geometry.heightCells() == 1 ? 0 : 1;
        return (dx | dy | dz) != 0 && Math.abs(dx) <= horizontal
                && Math.abs(dz) <= horizontal && dy >= lower && dy <= upper;
    }

    private static boolean referencesHalo(BaseClusterTopology topology,
                                          int dx,
                                          int dy,
                                          int dz) {
        int encoded = BaseClusterTopology.haloIndex(dx, dy, dz);
        for (int index = 0; index < topology.haloStampCount(); index++) {
            if (Byte.toUnsignedInt(topology.haloOffset(index)) == encoded) return true;
        }
        return false;
    }

    private boolean haloCurrent(ClusterKey key, BaseClusterTopology topology) {
        for (int index = 0; index < topology.haloStampCount(); index++) {
            int encoded = Byte.toUnsignedInt(topology.haloOffset(index));
            SectionPos section = SectionPos.of(
                    key.section().x() + BaseClusterTopology.haloX(encoded),
                    key.section().y() + BaseClusterTopology.haloY(encoded),
                    key.section().z() + BaseClusterTopology.haloZ(encoded)
            );
            ClusterEntry neighbor = clusters.get(new ClusterKey(key.dimension(), section));
            if (neighbor == null
                    || neighbor.revision != topology.haloRevision(index)
                    || neighbor.factsRevision != neighbor.revision
                    || neighbor.factsFingerprint != topology.haloFingerprint(index)) {
                return false;
            }
        }
        return true;
    }

    public void invalidate(ClusterKey key) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        ClusterEntry entry = clusters.get(key);
        if (entry == null) {
            return;
        }
        if (entry.dirty && entry.views.values().stream().allMatch(view -> view.topology == null)
                && entry.views.values().stream().allMatch(
                        view -> view.demand == null || view.demand.queued
                )) {
            coalescedInvalidations.increment();
            return;
        }
        entry.revision++;
        entry.dirty = true;
        invalidateHaloDependents(key, false);
        if (entry.facts != null) {
            retainedBytes.addAndGet(-entry.facts.retainedBytes());
            baseRetainedBytes.addAndGet(-entry.facts.retainedBytes());
            entry.facts = null;
        }
        boolean removedTopology = false;
        for (ViewEntry view : entry.views.values()) {
            if (view.topology != null) {
                BaseClusterTopology stale = view.topology;
                view.topology = null;
                retireBaseTopology(view, stale);
                removedTopology = true;
            }
            view.lastSignature = 0L;
            if (view.demand == null) continue;
            TopologyDemand stale = view.demand;
            cancelDemandWork(stale);
            TopologyDemand replacement = new TopologyDemand(
                    key,
                    stale.geometry,
                    entry.revision,
                    stale.sequence,
                    stale.enqueuedNanos
            );
            replacement.priority = stale.priority;
            replacement.waiters.addAll(stale.waiters);
            for (TopologySubscription<BaseClusterTopology> waiter : replacement.waiters) {
                waiter.demand = replacement;
            }
            stale.waiters.clear();
            view.demand = replacement;
            enqueueRequestedBuild(replacement);
        }
        if (removedTopology) topologyChanged();
        invalidateSuperParent(key, false);
    }

    @Nullable
    public BaseClusterTopology topology(ClusterKey key) {
        return topology(key, DEFAULT_GEOMETRY);
    }

    @Nullable
    private BaseClusterTopology topology(ClusterKey key,
                                         BaseClusterTopology.GeometryKey geometry) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(Objects.requireNonNull(key, "key"));
        ViewEntry view = entry == null ? null : entry.views.get(geometry);
        if (view == null) return null;
        view.lastAccess = ++viewAccessSequence;
        return view.topology;
    }

    public long revision(ClusterKey key) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(Objects.requireNonNull(key, "key"));
        return entry == null ? 0L : entry.revision;
    }

    public boolean isCurrent(ResourceKey<Level> dimension, MacroSearch.Corridor corridor) {
        requireOwnerThread();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(corridor, "corridor");
        for (MacroSearch.Endpoint endpoint : corridor.endpoints()) {
            if (endpoint instanceof MacroSearch.ExactEndpoint) {
                continue;
            }
            SectionPos section = endpoint instanceof MacroSearch.ComponentEndpoint component
                    ? component.section()
                    : SectionPos.of(endpoint.anchor());
            ClusterEntry entry = clusters.get(new ClusterKey(dimension, section));
            if (entry == null || entry.views.values().stream()
                    .noneMatch(view -> view.lastSignature == endpoint.revision())) {
                return false;
            }
        }
        return true;
    }

    public MacroRequest requestMacroQuery(ServerLevel level,
                                          UUID owner,
                                          BlockPos start,
                                          BlockPos goal,
                                          BaseClusterTopology.Channel channel,
                                          BaseClusterTopology.TraversalProfile profile,
                                          NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(level, "level");
        if (level.getServer() != server) {
            throw new IllegalArgumentException("level belongs to a different topology service");
        }
        MacroOwnerKey ownerKey = new MacroOwnerKey(
                level.dimension(),
                Objects.requireNonNull(owner, "owner")
        );
        MacroRequest replaced = macroRequests.remove(ownerKey);
        if (replaced != null) {
            replaced.cancelInternal();
        }
        MacroRequest request = new MacroRequest(
                level,
                ownerKey,
                Objects.requireNonNull(start, "start").immutable(),
                Objects.requireNonNull(goal, "goal").immutable(),
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(profile, "profile"),
                Objects.requireNonNull(priority, "priority"),
                MacroSearch.DEFAULT_WEIGHT
        );
        macroLogicalRequests++;
        if (macroRequests.size() >= MAX_MACRO_QUERY_WAITERS) {
            request.reject(new RejectedExecutionException(
                    "accelerated macro query waiter limit reached"
            ));
            return request;
        }
        macroRequests.put(ownerKey, request);
        request.beginResolve();
        return request;
    }

    private static int queryNodeBudget(BlockPos start,
                                       BlockPos goal,
                                       boolean hierarchical) {
        double directDistance = Math.sqrt(start.distSqr(goal));
        return Math.max(
                hierarchical
                        ? MIN_HIERARCHICAL_QUERY_VISITED_NODES
                        : MIN_QUERY_VISITED_NODES,
                Math.min(
                        MAX_QUERY_VISITED_NODES,
                        (int) Math.ceil(directDistance * QUERY_VISITED_NODES_PER_BLOCK)
                )
        );
    }

    public Metrics metrics() {
        int dependencyDemands = 0;
        int queuedDependencyDemands = 0;
        int topologyWaiters = 0;
        for (ClusterEntry entry : clusters.values()) {
            for (ViewEntry view : entry.views.values()) {
                if (view.demand == null) continue;
                topologyWaiters += view.demand.waiters.size();
                dependencyDemands++;
                if (view.demand.queued) queuedDependencyDemands++;
            }
        }
        for (SuperEntry entry : superClusters.values()) {
            topologyWaiters += entry.waiters.size();
        }
        TopologyStore.Metrics persistence = store == null ? null : store.metrics();
        return new Metrics(
                snapshotCells.sum(),
                snapshotNanos.sum(),
                buildRequests.sum(),
                buildNanos.sum(),
                publishedClusters.sum(),
                freshBuilds.sum(),
                persistenceHits.sum(),
                staleBuilds.sum(),
                coalescedInvalidations.sum(),
                requestedBuilds.size(),
                retainedBytes.get(),
                baseRetainedBytes.get(),
                buildScratch.retainedBytes(),
                superBuildRequests.sum(),
                superBuildNanos.sum(),
                publishedSuperClusters.sum(),
                staleSuperBuilds.sum(),
                evictedSuperClusters.sum(),
                superClusters.size(),
                superRetainedBytes.get(),
                workerMetrics(buildWorker),
                persistenceWorkerMetrics(persistence),
                persistenceMetrics(persistence),
                dependencyPermits,
                dependencyPermitHighWatermark,
                dependencyDemands,
                queuedDependencyDemands,
                topologyWaiters,
                topologyEpoch,
                new LinkCacheMetrics(
                        baseBoundaryBuildRequests.sum(),
                        baseBoundaryBuildNanos.sum(),
                        baseBoundaryHits.sum(),
                        baseBoundaryMisses.sum(),
                        0L,
                        baseLinkCount(),
                        baseBoundaryRetainedBytes.get()
                ),
                new LinkCacheMetrics(
                        superBoundaryBuildRequests.sum(),
                        superBoundaryBuildNanos.sum(),
                        superBoundaryHits.sum(),
                        superBoundaryMisses.sum(),
                        0L,
                        superLinkCount(),
                        superBoundaryRetainedBytes.get()
                ),
                new MacroQueryReuseMetrics(
                        macroLogicalRequests,
                        macroPhysicalSearches,
                        macroInFlightJoins,
                        macroCompletedHits,
                        macroCompletedMisses,
                        macroStaleEvictions,
                        macroCacheEvictions,
                        macroRequests.size(),
                        macroFlights.size(),
                        macroMaximumGroupSize,
                        completedCorridors.size(),
                        completedCorridorBytes
                ),
                prewarmCandidates.size(), prewarmAdmitted, prewarmAdmissions,
                prewarmPublished, prewarmPromoted, prewarmCancelled, parentBuildFailures
        );
    }

    private static WorkerMetrics workerMetrics(@Nullable TopologyTaskExecutor worker) {
        if (worker == null) {
            return new WorkerMetrics(false, 0, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        TopologyTaskExecutor.Metrics metrics = worker.metrics();
        return new WorkerMetrics(
                true,
                metrics.queuedTasks(),
                metrics.submittedTasks(),
                metrics.completedTasks(),
                metrics.promotedTasks(),
                metrics.cancelledTasks(),
                metrics.totalQueueWaitNanos(),
                metrics.maximumQueueWaitNanos()
        );
    }

    private static WorkerMetrics persistenceWorkerMetrics(@Nullable TopologyStore.Metrics metrics) {
        if (metrics == null) {
            return new WorkerMetrics(false, 0, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        return new WorkerMetrics(
                true,
                metrics.queuedTasks(),
                metrics.submittedTasks(),
                metrics.completedTasks(),
                0L,
                0L,
                metrics.totalQueueWaitNanos(),
                metrics.maximumQueueWaitNanos()
        );
    }

    private static PersistenceMetrics persistenceMetrics(@Nullable TopologyStore.Metrics metrics) {
        if (metrics == null) {
            return new PersistenceMetrics(0L, 0L, 0L, 0L, 0, 0, 0L, 0L, 0L, 0, 0, 0);
        }
        return new PersistenceMetrics(
                metrics.physicalReads(),
                metrics.coalescedReads(),
                metrics.physicalWrites(),
                metrics.flushes(),
                metrics.pendingChunks(),
                metrics.pendingHighWatermark(),
                metrics.oldestPendingNanos(),
                metrics.writeFailures(),
                metrics.droppedChunks(),
                metrics.decodedChunks(),
                metrics.inFlightLoads(),
                metrics.openRegions()
        );
    }

    public void shutdown() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        IllegalStateException stopped = new IllegalStateException("topology service stopped");
        for (MacroRequest request : List.copyOf(macroRequests.values())) {
            request.cancelInternal();
        }
        for (MacroFlight flight : List.copyOf(macroFlights.values())) {
            NavigationScheduler.forServer(server).cancel(
                    flight.key.dimension,
                    flight.schedulerOwner
            );
        }
        macroRequests.clear();
        macroFlights.clear();
        completedCorridors.clear();
        completedCorridorBytes = 0L;
        for (Map.Entry<ClusterKey, ClusterEntry> cluster : clusters.entrySet()) {
            ClusterEntry entry = cluster.getValue();
            for (ViewEntry view : entry.views.values()) {
                for (int slot = 0; slot < view.links.length; slot++) {
                    clearBaseLink(view, slot, "topology service stopped");
                }
                if (view.demand != null) failDemand(entry, view.demand, stopped);
                view.topology = null;
            }
            entry.facts = null;
        }
        for (SuperEntry entry : superClusters.values()) {
            for (int slot = 0; slot < entry.links.length; slot++) {
                clearSuperLink(entry, slot, "topology service stopped");
            }
            if (entry.buildTask != null) {
                entry.buildTask.cancel();
                entry.buildTask = null;
            }
            cancelSuperChildren(entry);
            for (TopologySubscription<SuperClusterTopology> waiter : List.copyOf(entry.waiters)) {
                waiter.active = false;
                waiter.future().completeExceptionally(stopped);
            }
            entry.waiters.clear();
            entry.requestPriority = null;
            entry.topology = null;
        }
        superClusters.clear();
        prewarmCandidates.clear();
        prewarmQueues.clear();
        requestedBuilds.clear();
        dependencyPermits = 0;
        prewarmAdmitted = 0;
        retainedBytes.set(0L);
        baseRetainedBytes.set(0L);
        superRetainedBytes.set(0L);
        baseBoundaryRetainedBytes.set(0L);
        superBoundaryRetainedBytes.set(0L);
        buildWorker.shutdown();
        if (store != null) {
            store.close();
        }
    }

    private void build(TopologyDemand demand,
                       BaseClusterTopology.BuildInput input,
                       boolean eagerDefault) {
        long started = System.nanoTime();
        BaseClusterTopology topology;
        BaseClusterTopology defaultTopology = null;
        try {
            topology = BaseClusterTopology.build(
                    demand.key.section(),
                    demand.generation,
                    input,
                    demand.geometry,
                    buildScratch
            );
            if (eagerDefault) {
                defaultTopology = BaseClusterTopology.build(demand.key.section(), demand.generation,
                        input, DEFAULT_GEOMETRY, buildScratch);
            }
        } catch (RuntimeException exception) {
            buildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failBuild(demand, exception));
            return;
        }
        buildNanos.add(System.nanoTime() - started);
        BaseClusterTopology eager = defaultTopology;
        publisher.execute(() -> publish(demand, topology, eager));
    }

    private void publish(TopologyDemand demand,
                         BaseClusterTopology topology,
                         @Nullable BaseClusterTopology eagerDefault) {
        requireOwnerThread();
        ClusterKey key = demand.key;
        ClusterEntry entry = clusters.get(key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        demand.buildTask = UNTRACKED_TASK;
        if (closed || entry == null || entry.revision != demand.generation
                || view == null || view.demand != demand) {
            staleBuilds.increment();
            return;
        }
        if (topology.sourceFingerprint() != demand.fingerprint || !haloCurrent(key, topology)) {
            staleBuilds.increment();
            enqueueRequestedBuild(demand);
            return;
        }

        BaseClusterTopology replaced = view.topology;
        if (replaced != null) {
            view.topology = null;
            retireBaseTopology(view, replaced);
        }
        view.topology = topology;
        view.handoffUntilTick = topologyTick + 1L;
        view.lastSignature = topology.signature();
        entry.dirty = false;
        retainedBytes.addAndGet(topology.retainedBytes());
        baseRetainedBytes.addAndGet(topology.retainedBytes());
        publishedClusters.increment();
        topologyChanged();
        if (replaced != null) {
            invalidateSuperParent(key, false);
        }
        if (eagerDefault != null && haloCurrent(key, eagerDefault)) {
            ViewEntry eagerView = entry.view(DEFAULT_GEOMETRY);
            if (eagerView.topology == null) {
                eagerView.topology = eagerDefault;
                eagerView.handoffUntilTick = topologyTick + 1L;
                eagerView.lastSignature = eagerDefault.signature();
                eagerView.lastAccess = ++viewAccessSequence;
                retainedBytes.addAndGet(eagerDefault.retainedBytes());
                baseRetainedBytes.addAndGet(eagerDefault.retainedBytes());
                publishedClusters.increment();
                if (eagerView.demand != null) {
                    boolean prewarm = eagerView.demand.prewarmSlot;
                    stopDemandWork(eagerView.demand);
                    if (prewarm) prewarmPublished++;
                    completeDemand(entry, eagerView.demand, eagerDefault);
                }
            }
        }
        completeDemand(entry, demand, topology);
        evictBaseCache();
    }

    private void failBuild(TopologyDemand demand, RuntimeException exception) {
        requireOwnerThread();
        demand.buildTask = UNTRACKED_TASK;
        ClusterEntry entry = clusters.get(demand.key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) {
            failDemand(entry, demand, exception);
        }
    }

    private void requireOwnerThread() {
        if (!ownerThread.getAsBoolean()) {
            throw new IllegalStateException("topology state must be accessed by its owner thread");
        }
    }

    private void enqueueRequestedBuild(TopologyDemand demand) {
        if (demand.waiters.isEmpty() || demand.queued || demand.buildTask != UNTRACKED_TASK) {
            return;
        }
        requestedBuilds.add(demand);
    }

    private void completeDemand(ClusterEntry entry,
                                TopologyDemand demand,
                                BaseClusterTopology topology) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) view.demand = null;
        requestedBuilds.remove(demand);
        if (demand.prewarmSlot) prewarmPublished++;
        releasePrewarmSlot(demand);
        releaseDependencyPermit(demand);
        for (TopologySubscription<BaseClusterTopology> waiter : List.copyOf(demand.waiters)) {
            waiter.active = false;
            waiter.demand = null;
            waiter.future().complete(topology);
        }
        demand.waiters.clear();
    }

    private void failDemand(ClusterEntry entry, TopologyDemand demand, Throwable failure) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) view.demand = null;
        if (demand.prewarmSlot) prewarmCancelled++;
        releasePrewarmSlot(demand);
        cancelDemandWork(demand);
        for (TopologySubscription<BaseClusterTopology> waiter : List.copyOf(demand.waiters)) {
            waiter.active = false;
            waiter.demand = null;
            waiter.future().completeExceptionally(failure);
        }
        demand.waiters.clear();
    }

    private <T> TopologySubscription<T> completedSubscription(
            T value,
            NavigationScheduler.Priority priority) {
        TopologySubscription<T> subscription = new TopologySubscription<>(priority);
        subscription.active = false;
        subscription.future().complete(value);
        return subscription;
    }

    private <T> TopologySubscription<T> failedSubscription(
            NavigationScheduler.Priority priority,
            Throwable failure) {
        TopologySubscription<T> subscription = new TopologySubscription<>(priority);
        subscription.active = false;
        subscription.future().completeExceptionally(failure);
        return subscription;
    }

    private void cancelClusterSubscription(
            TopologySubscription<BaseClusterTopology> subscription) {
        requireOwnerThread();
        if (!subscription.active) {
            return;
        }
        subscription.active = false;
        TopologyDemand demand = subscription.demand;
        subscription.demand = null;
        if (demand == null || !demand.waiters.remove(subscription)) {
            return;
        }
        if (demand.waiters.isEmpty()) {
            ClusterEntry entry = clusters.get(demand.key);
            ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
            if (view != null && view.demand == demand) {
                view.demand = null;
            }
            cancelDemandWork(demand);
            return;
        }
        NavigationScheduler.Priority previous = demand.priority;
        demand.priority = demand.waiters.stream()
                .map(waiter -> waiter.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyService::higherPriority);
        if (demand.queued && demand.priority != previous) {
            requestedBuilds.reprioritize(demand, previous);
        }
        if (previous != demand.priority) {
            ServerLevel level = server.getLevel(demand.key.dimension());
            if (level != null) promoteDemandFacts(level, demand);
        }
        if (demand.dependencyPermit && demand.waiters.stream()
                .noneMatch(waiter -> !waiter.prewarmWaiter)) {
            releaseDependencyPermit(demand);
        }
        if (!demand.prewarmSlot) demand.buildTask.enableAging();
        demand.buildTask.reprioritize(demand.priority);
    }

    private void reconcileClusterSubscription(
            TopologySubscription<BaseClusterTopology> subscription,
            NavigationScheduler.Priority requested) {
        if (!subscription.active) {
            return;
        }
        TopologyDemand demand = subscription.demand;
        subscription.priority = requested;
        if (demand == null) {
            return;
        }
        NavigationScheduler.Priority previous = demand.priority;
        demand.priority = demand.waiters.stream()
                .map(waiter -> waiter.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyService::higherPriority);
        if (demand.queued) {
            requestedBuilds.reprioritize(demand, previous);
        }
        if (previous != demand.priority) {
            ServerLevel level = server.getLevel(demand.key.dimension());
            if (level != null) promoteDemandFacts(level, demand);
        }
        if (!demand.prewarmSlot) demand.buildTask.enableAging();
        demand.buildTask.reprioritize(demand.priority);
    }

    private void cancelSuperSubscription(
            SuperCacheKey key,
            SuperEntry entry,
            TopologySubscription<SuperClusterTopology> subscription) {
        requireOwnerThread();
        if (!subscription.active || !entry.waiters.remove(subscription)) {
            return;
        }
        subscription.active = false;
        if (entry.waiters.isEmpty()) {
            entry.attempt++;
            entry.attemptRunning = false;
            if (entry.buildTask != null) {
                entry.buildTask.cancel();
                entry.buildTask = null;
            }
            cancelSuperChildren(entry);
            entry.requestPriority = null;
            if (entry.topology == null) {
                superClusters.remove(key, entry);
            }
            return;
        }
        entry.requestPriority = entry.waiters.stream()
                .map(waiter -> waiter.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyService::higherPriority);
        if (entry.buildTask != null) {
            entry.buildTask.reprioritize(entry.requestPriority);
        }
        reconcileSuperChildren(entry);
    }

    private void reconcileSuperSubscription(
            SuperEntry entry,
            TopologySubscription<SuperClusterTopology> subscription,
            NavigationScheduler.Priority requested) {
        requireOwnerThread();
        if (!subscription.active || !entry.waiters.contains(subscription)) {
            return;
        }
        subscription.priority = requested;
        NavigationScheduler.Priority previous = entry.requestPriority;
        entry.requestPriority = entry.waiters.stream()
                .map(waiter -> waiter.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND,
                        TopologyService::higherPriority);
        if (entry.buildTask != null && previous != entry.requestPriority) {
            entry.buildTask.reprioritize(entry.requestPriority);
        }
        if (previous != entry.requestPriority) {
            reconcileSuperChildren(entry);
        }
    }

    private void cancelSuperChildren(SuperEntry entry) {
        entry.children.forEach(TopologySubscription::cancel);
        entry.children = List.of();
    }

    private void reconcileSuperChildren(SuperEntry entry) {
        entry.children.forEach(child -> reconcileClusterSubscription(
                child,
                entry.requestPriority
        ));
    }

    private boolean acquireDependencyPermit(TopologyDemand demand) {
        if (demand.dependencyPermit) {
            return true;
        }
        if (demand.waiters.isEmpty() || dependencyPermits >= MAX_DEPENDENCY_DEMANDS) {
            return false;
        }
        demand.dependencyPermit = true;
        dependencyPermits++;
        dependencyPermitHighWatermark = Math.max(dependencyPermitHighWatermark, dependencyPermits);
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        for (FactsAttempt attempt : demand.factAttempts) {
            scheduler.qualifyDependency(attempt.key.dimension(),
                    snapshotOwner(attempt.key), demand.priority);
        }
        return true;
    }

    private void releaseDependencyPermit(TopologyDemand demand) {
        if (!demand.dependencyPermit) {
            return;
        }
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        for (FactsAttempt attempt : demand.factAttempts) {
            boolean retainedByOtherDemand = attempt.demands.stream()
                    .anyMatch(other -> other != demand && other.dependencyPermit);
            if (!retainedByOtherDemand) {
                scheduler.releaseDependency(attempt.key.dimension(), snapshotOwner(attempt.key));
            }
        }
        demand.dependencyPermit = false;
        dependencyPermits--;
        if (dependencyPermits < 0) {
            throw new IllegalStateException("topology dependency permit count became negative");
        }
    }

    private void releasePrewarmSlot(TopologyDemand demand) {
        if (!demand.prewarmSlot) return;
        demand.prewarmSlot = false;
        prewarmAdmitted--;
        if (prewarmAdmitted < 0) {
            throw new IllegalStateException("prewarm admission count became negative");
        }
    }

    private void cancelDemandWork(TopologyDemand demand) {
        if (demand.prewarmSlot) prewarmCancelled++;
        stopDemandWork(demand);
    }

    private void stopDemandWork(TopologyDemand demand) {
        requestedBuilds.remove(demand);
        // Release the demand's dependency qualification before detaching its
        // shared facts attempts. A remaining ordinary waiter must be able to
        // downgrade the same scheduler request instead of leaving it marked
        // as a dependency after this demand is removed.
        releaseDependencyPermit(demand);
        for (FactsAttempt attempt : List.copyOf(demand.factAttempts)) {
            ClusterEntry entry = clusters.get(attempt.key);
            attempt.demands.remove(demand);
            if (attempt.demands.isEmpty() && entry != null && entry.factsAttempt == attempt) {
                entry.factsAttempt = null;
                if (attempt.snapshotFuture != null) {
                    NavigationScheduler.forServer(server).cancel(
                            attempt.key.dimension(), snapshotOwner(attempt.key));
                    attempt.snapshotFuture.cancel(false);
                    attempt.snapshotFuture = null;
                }
                releaseUnusedFacts(entry);
            }
        }
        demand.factAttempts.clear();
        releaseHeldFacts(demand);
        demand.buildTask.cancel();
        demand.buildTask = UNTRACKED_TASK;
        releasePrewarmSlot(demand);
    }

    private void releaseHeldFacts(TopologyDemand demand) {
        for (ClusterEntry entry : demand.heldFacts) {
            if (--entry.factPins < 0) throw new IllegalStateException("fact pin count became negative");
            releaseUnusedFacts(entry);
        }
        demand.heldFacts.clear();
    }

    private void promoteDemandFacts(ServerLevel level, TopologyDemand demand) {
        for (FactsAttempt attempt : demand.factAttempts) {
            promoteFactsAttempt(level, attempt, demand.priority);
        }
    }

    private static boolean retryableAttemptFailure(@Nullable Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StaleTopologyException
                    || current instanceof CancellationException
                    || current instanceof RejectedExecutionException) {
                return true;
            }
        }
        return false;
    }

    private static NavigationScheduler.Priority higherPriority(
            @Nullable NavigationScheduler.Priority current,
            NavigationScheduler.Priority requested) {
        return current == null || requested.higherThan(current) ? requested : current;
    }

    private static UUID snapshotOwner(ClusterKey key) {
        SectionPos section = key.section();
        return UUID.nameUUIDFromBytes(
                (key.dimension().location() + ":" + section.x() + ":" + section.y() + ":" + section.z())
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static SnapshotSearch worldSnapshot(ServerLevel level,
                                                SectionPos section,
                                                LevelChunk centerChunk) {
        LoadedBlockGetter getter = new LoadedBlockGetter(level);
        Map<BlockState, Integer> staticClassifications = new IdentityHashMap<>();
        LevelChunkSection chunkSection = centerChunk.getSection(
                centerChunk.getSectionIndexFromSectionY(section.y())
        );
        int[] paletteFlags = {-1};
        boolean[] paletteFast = {true};
        chunkSection.getStates().getAll(state -> {
            int classification = staticCellClassification(state);
            staticClassifications.put(state, classification);
            if (classification == DYNAMIC_COLLISION) {
                paletteFast[0] = false;
                return;
            }
            int flags = classification & CELL_FACT_MASK;
            if (paletteFlags[0] < 0) {
                paletteFlags[0] = flags;
            } else if (paletteFlags[0] != flags) {
                paletteFast[0] = false;
            }
        });
        if (paletteFast[0] && paletteFlags[0] >= 0) {
            return SnapshotSearch.uniform(paletteFlags[0]);
        }
        boolean[] collisions = new boolean[BaseClusterTopology.CELL_COUNT];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        return new SnapshotSearch(index -> {
            int x = index & 15;
            int z = index >>> 4 & 15;
            int y = index >>> 8;
            BlockState state = chunkSection.getBlockState(x, y, z);
            int classification = staticClassifications.computeIfAbsent(
                    state, TopologyService::staticCellClassification
            );
            if (classification == DYNAMIC_COLLISION) {
                position.set(section.minBlockX() + x, section.minBlockY() + y,
                        section.minBlockZ() + z);
                VoxelShape shape = state.getCollisionShape(getter, position);
                boolean collides = !shape.isEmpty();
                classification = classifyCell(
                        state,
                        Block.isShapeFullBlock(shape),
                        collides,
                        false,
                        true
                ) | (collides ? CELL_COLLIDES : 0);
            }
            boolean supportBelow = y > 0 && collisions[index - 256];
            collisions[index] = (classification & CELL_COLLIDES) != 0;
            int flags = classification & CELL_FACT_MASK;
            if (supportBelow && (flags & BaseClusterTopology.VOLUME_OPEN) != 0) {
                flags |= BaseClusterTopology.GROUND_OPEN;
            }
            return flags;
        });
    }

    private void enqueuePrewarm(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireOwnerThread();
        if (closed) return;
        PrewarmKey key = new PrewarmKey(dimension, chunk.toLong());
        if (prewarmCandidates.containsKey(key)) return;
        PrewarmCandidate candidate = new PrewarmCandidate(key);
        prewarmCandidates.put(key, candidate);
        prewarmQueues.computeIfAbsent(dimension, ignored -> new ArrayDeque<>()).addLast(candidate);
    }

    private void admitPrewarm() {
        requireOwnerThread();
        if (closed || prewarmAdmitted >= MAX_PREWARM_ADMITTED || prewarmCandidates.isEmpty()) {
            return;
        }
        int turns = prewarmCandidates.size();
        while (prewarmAdmitted < MAX_PREWARM_ADMITTED && turns-- > 0
                && !prewarmQueues.isEmpty()) {
            List<ResourceKey<Level>> dimensions = new ArrayList<>(prewarmQueues.keySet());
            if (prewarmDimensionCursor >= dimensions.size()) prewarmDimensionCursor = 0;
            ResourceKey<Level> dimension = dimensions.get(prewarmDimensionCursor++);
            ArrayDeque<PrewarmCandidate> queue = prewarmQueues.get(dimension);
            PrewarmCandidate candidate = queue == null ? null : queue.pollFirst();
            if (queue != null && queue.isEmpty()) prewarmQueues.remove(dimension);
            if (candidate == null || prewarmCandidates.get(candidate.key) != candidate) continue;
            ServerLevel level = server.getLevel(dimension);
            ChunkPos chunkPos = new ChunkPos(candidate.key.chunkLong);
            LevelChunk chunk = level == null ? null
                    : level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (level == null || chunk == null) {
                prewarmCandidates.remove(candidate.key);
                continue;
            }
            if (candidate.nextSectionY == Integer.MAX_VALUE) {
                candidate.nextSectionY = level.getMaxSection() - 1;
            }
            while (candidate.nextSectionY >= level.getMinSection()
                    && chunk.getSection(chunk.getSectionIndexFromSectionY(candidate.nextSectionY))
                    .hasOnlyAir()) {
                candidate.nextSectionY--;
            }
            if (candidate.nextSectionY < level.getMinSection()) {
                prewarmCandidates.remove(candidate.key);
                continue;
            }
            SectionPos section = SectionPos.of(chunkPos.x, candidate.nextSectionY--, chunkPos.z);
            prewarmQueues.computeIfAbsent(dimension, ignored -> new ArrayDeque<>()).addLast(candidate);
            ClusterEntry cluster = clusters.get(new ClusterKey(dimension, section));
            ViewEntry existing = cluster == null ? null : cluster.views.get(DEFAULT_GEOMETRY);
            if (existing != null && (existing.topology != null || existing.demand != null)) continue;
            prewarmAdmitted++;
            prewarmAdmissions++;
            TopologySubscription<BaseClusterTopology> subscription = subscribeClusterDependency(
                    level, section, DEFAULT_GEOMETRY, NavigationScheduler.Priority.BACKGROUND, true
            );
            if (subscription.demand == null || !subscription.demand.prewarmSlot) {
                prewarmAdmitted--;
            }
        }
    }

    private void removePrewarm(ResourceKey<Level> dimension, ChunkPos chunk) {
        PrewarmKey key = new PrewarmKey(dimension, chunk.toLong());
        PrewarmCandidate removed = prewarmCandidates.remove(key);
        ArrayDeque<PrewarmCandidate> queue = prewarmQueues.get(dimension);
        if (removed != null && queue != null) {
            queue.remove(removed);
            if (queue.isEmpty()) prewarmQueues.remove(dimension);
        }
    }

    private void evictChunk(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireOwnerThread();
        removePrewarm(dimension, chunk);
        if (store != null && !closed) {
            store.unload(dimension, chunk);
        }
        evictSuperChunkParents(dimension, chunk);
        Set<TopologyDemand> resume = Collections.newSetFromMap(new IdentityHashMap<>());
        Iterator<Map.Entry<ClusterKey, ClusterEntry>> iterator = clusters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ClusterKey, ClusterEntry> cluster = iterator.next();
            ClusterKey key = cluster.getKey();
            if (!key.dimension().equals(dimension)
                    || key.section().x() != chunk.x || key.section().z() != chunk.z) {
                continue;
            }
            ClusterEntry entry = cluster.getValue();
            invalidateHaloDependents(key, false);
            FactsAttempt factsAttempt = entry.factsAttempt;
            if (factsAttempt != null) {
                entry.factsAttempt = null;
                if (factsAttempt.snapshotFuture != null) {
                    NavigationScheduler.forServer(server).cancel(
                            factsAttempt.key.dimension(), snapshotOwner(factsAttempt.key));
                    factsAttempt.snapshotFuture.cancel(false);
                }
                for (TopologyDemand demand : factsAttempt.demands) {
                    demand.factAttempts.remove(factsAttempt);
                    resume.add(demand);
                }
                factsAttempt.demands.clear();
            }
            if (entry.facts != null) {
                retainedBytes.addAndGet(-entry.facts.retainedBytes());
                baseRetainedBytes.addAndGet(-entry.facts.retainedBytes());
            }
            boolean removed = false;
            for (ViewEntry view : entry.views.values()) {
                if (view.topology != null) {
                    BaseClusterTopology stale = view.topology;
                    view.topology = null;
                    retireBaseTopology(view, stale);
                    removed = true;
                }
                if (view.demand != null) {
                    failDemand(entry, view.demand, new StaleTopologyException(key));
                }
            }
            if (removed) topologyChanged();
            iterator.remove();
        }
        resume.forEach(this::resumeDemand);
    }

    @Nullable
    private SuperClusterTopology superTopology(SuperCacheKey key) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        return entry == null ? null : entry.topology;
    }

    @Nullable
    private BaseClusterTopology[] currentChildTopologies(SuperCacheKey key) {
        BaseClusterTopology[] result = new BaseClusterTopology[8];
        BaseClusterTopology.GeometryKey geometry = key.geometry();
        int index = 0;
        for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
            ClusterEntry entry = clusters.get(new ClusterKey(key.dimension(), child));
            BaseClusterTopology topology = entry == null ? null : entry.topology(geometry);
            if (topology == null || topology.revision() != entry.revision) {
                return null;
            }
            result[index++] = topology;
        }
        return result;
    }

    private boolean superClusterAvailable(ServerLevel level, SectionPos origin) {
        if (!superClusterHeightAvailable(level, origin)) {
            return false;
        }
        for (SectionPos child : SuperClusterTopology.childSections(origin)) {
            if (level.getChunkSource().getChunkNow(child.x(), child.z()) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean superClusterHeightAvailable(ServerLevel level, SectionPos origin) {
        for (SectionPos child : SuperClusterTopology.childSections(origin)) {
            if (child.y() < level.getMinSection() || child.y() >= level.getMaxSection()) {
                return false;
            }
        }
        return true;
    }

    private void invalidateSuperParent(ClusterKey child, boolean unavailable) {
        SectionPos origin = SuperClusterTopology.originOf(child.section());
        List<Map.Entry<SuperCacheKey, SuperEntry>> restart = new ArrayList<>();
        for (Map.Entry<SuperCacheKey, SuperEntry> cached
                : List.copyOf(superClusters.entrySet())) {
            SuperCacheKey key = cached.getKey();
            if (!key.dimension().equals(child.dimension()) || !key.origin().equals(origin)) continue;
            SuperEntry entry = cached.getValue();
            removeSuperTopology(key, entry, true);
            if (entry.buildTask != null) {
                entry.buildTask.cancel();
                entry.buildTask = null;
            }
            entry.attempt++;
            entry.attemptRunning = false;
            cancelSuperChildren(entry);
            if (unavailable) {
                StaleTopologyException failure = new StaleTopologyException(child);
                for (TopologySubscription<SuperClusterTopology> waiter
                        : List.copyOf(entry.waiters)) {
                    waiter.active = false;
                    waiter.future().completeExceptionally(failure);
                }
                entry.waiters.clear();
                entry.requestPriority = null;
                superClusters.remove(key, entry);
            } else if (!entry.waiters.isEmpty()) {
                restart.add(Map.entry(key, entry));
            } else {
                superClusters.remove(key, entry);
            }
        }
        for (Map.Entry<SuperCacheKey, SuperEntry> cached : restart) {
            ServerLevel level = server.getLevel(cached.getKey().dimension());
            if (level != null && superClusterAvailable(level, cached.getKey().origin())) {
                beginSuperRequest(level, cached.getKey(), cached.getValue());
            } else {
                failSuperRequest(
                        cached.getKey(),
                        cached.getValue(),
                        new StaleTopologyException(child)
                );
            }
        }
    }

    private void evictSuperChunkParents(ResourceKey<Level> dimension, ChunkPos chunk) {
        for (Map.Entry<SuperCacheKey, SuperEntry> cached
                : List.copyOf(superClusters.entrySet())) {
            SuperCacheKey key = cached.getKey();
            if (!key.dimension().equals(dimension)
                    || chunk.x < key.origin().x()
                    || chunk.x >= key.origin().x() + SuperClusterTopology.CHILDREN_PER_AXIS
                    || chunk.z < key.origin().z()
                    || chunk.z >= key.origin().z() + SuperClusterTopology.CHILDREN_PER_AXIS) {
                continue;
            }
            SuperEntry entry = cached.getValue();
            removeSuperTopology(key, entry, true);
            if (entry.buildTask != null) {
                entry.buildTask.cancel();
                entry.buildTask = null;
            }
            entry.attempt++;
            entry.attemptRunning = false;
            cancelSuperChildren(entry);
            IllegalStateException failure = new IllegalStateException(
                    "super topology lost a loaded child chunk"
            );
            for (TopologySubscription<SuperClusterTopology> waiter : List.copyOf(entry.waiters)) {
                waiter.active = false;
                waiter.future().completeExceptionally(failure);
            }
            entry.waiters.clear();
            entry.requestPriority = null;
            superClusters.remove(key, entry);
        }
    }

    private void evictBaseCache() {
        while (baseRetainedBytes.get() > MAX_BASE_RETAINED_BYTES) {
            ClusterEntry oldestEntry = null;
            ViewEntry oldestView = null;
            long oldestAccess = Long.MAX_VALUE;
            for (ClusterEntry cached : clusters.values()) {
                for (ViewEntry view : cached.views.values()) {
                    if (view.topology != null && view.demand == null && view.pins == 0
                            && topologyTick > view.handoffUntilTick
                            && view.lastAccess < oldestAccess) {
                        oldestEntry = cached;
                        oldestView = view;
                        oldestAccess = view.lastAccess;
                    }
                }
            }
            if (oldestView == null) return;
            BaseClusterTopology removed = oldestView.topology;
            clearOwnedBaseLinks(oldestView, "base boundary source evicted");
            oldestView.topology = null;
            retireBaseTopology(oldestView, removed);
            boolean hasState = oldestEntry.factsAttempt != null;
            for (ViewEntry view : oldestEntry.views.values()) {
                hasState |= view.topology != null || view.demand != null;
            }
            if (!hasState && oldestEntry.facts != null) {
                retainedBytes.addAndGet(-oldestEntry.facts.retainedBytes());
                baseRetainedBytes.addAndGet(-oldestEntry.facts.retainedBytes());
                oldestEntry.facts = null;
            }
        }
    }

    private void evictSuperCache() {
        if (superClusters.size() <= MAX_SUPER_CACHE_ENTRIES
                && superRetainedBytes.get() <= MAX_SUPER_RETAINED_BYTES) {
            return;
        }
        for (Map.Entry<SuperCacheKey, SuperEntry> cached
                : List.copyOf(superClusters.entrySet())) {
            if (superClusters.size() <= MAX_SUPER_CACHE_ENTRIES
                    && superRetainedBytes.get() <= MAX_SUPER_RETAINED_BYTES) {
                break;
            }
            SuperEntry entry = cached.getValue();
            if (!entry.waiters.isEmpty() || entry.attemptRunning || entry.pins != 0
                    || topologyTick <= entry.handoffUntilTick) {
                continue;
            }
            removeSuperTopology(cached.getKey(), entry, false);
            if (superClusters.remove(cached.getKey(), entry)) {
                evictedSuperClusters.increment();
            }
        }
    }

    private void removeSuperTopology(SuperCacheKey key,
                                     SuperEntry entry,
                                     boolean targetChanged) {
        if (entry.topology == null) {
            return;
        }
        SuperClusterTopology removed = entry.topology;
        if (targetChanged) {
            invalidateSuperBoundaryLinks(key, removed);
        } else {
            clearOwnedSuperLinks(entry, "parent boundary source evicted");
        }
        entry.topology = null;
        retireSuperTopology(entry, removed);
        topologyChanged();
    }

    private void invalidateBaseBoundaryLinks(ClusterKey key, BaseClusterTopology topology) {
        ViewEntry owner = baseView(key.dimension(), topology);
        if (owner != null) clearOwnedBaseLinks(owner, "base boundary source changed");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ClusterEntry entry = clusters.get(new ClusterKey(key.dimension(), SectionPos.of(
                            key.section().x() + dx,
                            key.section().y() + dy,
                            key.section().z() + dz
                    )));
                    ViewEntry view = entry == null ? null : entry.views.get(topology.geometry());
                    if (view == null) continue;
                    for (int slot = 0; slot < view.linkTargetSignatures.length; slot++) {
                        if (view.linkTargetSignatures[slot] == topology.signature()) {
                            clearBaseLink(view, slot, "base boundary target changed");
                        }
                    }
                }
            }
        }
    }

    private void invalidateSuperBoundaryLinks(SuperCacheKey key,
                                              SuperClusterTopology topology) {
        SuperEntry owner = superView(key.dimension(), topology);
        if (owner != null) clearOwnedSuperLinks(owner, "parent boundary source changed");
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dy = -2; dy <= 2; dy += 2) {
                for (int dz = -2; dz <= 2; dz += 2) {
                    SuperEntry entry = superClusters.get(new SuperCacheKey(
                            key.dimension(),
                            SectionPos.of(key.origin().x() + dx, key.origin().y() + dy,
                                    key.origin().z() + dz),
                            key.geometry(), key.movement()
                    ));
                    if (entry == null) continue;
                    for (int slot = 0; slot < entry.linkTargetSignatures.length; slot++) {
                        if (entry.linkTargetSignatures[slot] == topology.signature()) {
                            clearSuperLink(entry, slot, "parent boundary target changed");
                        }
                    }
                }
            }
        }
    }

    private void topologyChanged() {
        topologyEpoch++;
    }

    private void pinBase(ViewEntry view, BaseClusterTopology topology) {
        view.pins++;
        view.pinnedTopologies.merge(topology, 1, Integer::sum);
    }

    private void releaseBasePin(ViewEntry view, BaseClusterTopology topology) {
        Integer count = view.pinnedTopologies.remove(topology);
        if (count == null) throw new IllegalStateException("base topology pin is not owned");
        if (count > 1) view.pinnedTopologies.put(topology, count - 1);
        if (--view.pins < 0) throw new IllegalStateException("negative base topology pins");
        if (count == 1 && view.topology != topology) releaseBaseBytes(topology);
    }

    private void retireBaseTopology(ViewEntry view, BaseClusterTopology topology) {
        if (topology != null && !view.pinnedTopologies.containsKey(topology)) releaseBaseBytes(topology);
    }

    private void releaseBaseBytes(BaseClusterTopology topology) {
        retainedBytes.addAndGet(-topology.retainedBytes());
        baseRetainedBytes.addAndGet(-topology.retainedBytes());
    }

    private void pinSuper(SuperEntry entry, SuperClusterTopology topology) {
        entry.pins++;
        entry.pinnedTopologies.merge(topology, 1, Integer::sum);
    }

    private void releaseSuperPin(SuperEntry entry, SuperClusterTopology topology) {
        Integer count = entry.pinnedTopologies.remove(topology);
        if (count == null) throw new IllegalStateException("super topology pin is not owned");
        if (count > 1) entry.pinnedTopologies.put(topology, count - 1);
        if (--entry.pins < 0) throw new IllegalStateException("negative super topology pins");
        if (count == 1 && entry.topology != topology) releaseSuperBytes(topology);
    }

    private void retireSuperTopology(SuperEntry entry, SuperClusterTopology topology) {
        if (topology != null && !entry.pinnedTopologies.containsKey(topology)) releaseSuperBytes(topology);
    }

    private void releaseSuperBytes(SuperClusterTopology topology) {
        retainedBytes.addAndGet(-topology.retainedBytes());
        superRetainedBytes.addAndGet(-topology.retainedBytes());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("topology service is stopped");
        }
    }

    public record ClusterKey(ResourceKey<Level> dimension, SectionPos section) {
        public ClusterKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(section, "section");
        }
    }

    public record Metrics(long snapshotCells,
                          long snapshotNanos,
                          long buildRequests,
                          long buildNanos,
                          long publishedClusters,
                          long freshBuilds,
                          long persistenceHits,
                          long staleBuilds,
                          long coalescedInvalidations,
                          int queuedBuilds,
                          long retainedBytes,
                          long baseRetainedBytes,
                          long fixedScratchBytes,
                          long superBuildRequests,
                          long superBuildNanos,
                          long publishedSuperClusters,
                           long staleSuperBuilds,
                           long evictedSuperClusters,
                           int cachedSuperClusters,
                           long superRetainedBytes,
                           WorkerMetrics buildWorker,
                           WorkerMetrics persistenceWorker,
                           PersistenceMetrics persistence,
                           int dependencyPermits,
                           int dependencyPermitHighWatermark,
                           int dependencyDemands,
                           int queuedDependencyDemands,
                           int topologyWaiters,
                           long topologyEpoch,
                           LinkCacheMetrics baseBoundaryLinks,
                           LinkCacheMetrics superBoundaryLinks,
                           MacroQueryReuseMetrics macroQueries,
                           int prewarmCandidates,
                           int prewarmAdmitted,
                           long prewarmAdmissions,
                           long prewarmPublished,
                           long prewarmPromoted,
                           long prewarmCancelled,
                           long parentBuildFailures) {
    }

    public record MacroQueryReuseMetrics(long logicalRequests,
                                         long physicalSearches,
                                         long inFlightJoins,
                                         long completedHits,
                                         long completedMisses,
                                         long staleEvictions,
                                         long cacheEvictions,
                                         int activeWaiters,
                                         int activeFlights,
                                         int maximumGroupSize,
                                         int cachedEntries,
                                         long cachedBytes) {
    }

    public record LinkCacheMetrics(long buildRequests,
                                   long buildNanos,
                                   long hits,
                                   long misses,
                                   long evictions,
                                   int cachedEntries,
                                   long retainedBytes) {
    }

    public record WorkerMetrics(boolean managed,
                                int queuedTasks,
                                long submittedTasks,
                                long completedTasks,
                                long promotedTasks,
                                long cancelledTasks,
                                long totalQueueWaitNanos,
                                long maximumQueueWaitNanos) {
    }

    private final class ClusterEntry {
        private long revision;
        private boolean dirty;
        private BaseClusterTopology.PackedFacts facts;
        private long factsRevision = Long.MIN_VALUE;
        private long factsFingerprint;
        private int factPins;
        private FactsAttempt factsAttempt;
        private final Map<BaseClusterTopology.GeometryKey, ViewEntry> views = new HashMap<>();

        private ViewEntry view(BaseClusterTopology.GeometryKey geometry) {
            return views.computeIfAbsent(geometry, ignored -> new ViewEntry());
        }

        @Nullable
        private BaseClusterTopology topology(BaseClusterTopology.GeometryKey geometry) {
            ViewEntry view = views.get(geometry);
            return view == null ? null : view.topology;
        }
    }

    private void clearOwnedBaseLinks(ViewEntry owner, String reason) {
        for (int slot = 0; slot < owner.links.length; slot++) clearBaseLink(owner, slot, reason);
    }

    private void clearOwnedSuperLinks(SuperEntry owner, String reason) {
        for (int slot = 0; slot < owner.links.length; slot++) clearSuperLink(owner, slot, reason);
    }

    private final class ViewEntry {
        private BaseClusterTopology topology;
        private long lastSignature;
        private TopologyDemand demand;
        private long lastAccess;
        private int pins;
        private long handoffUntilTick = Long.MIN_VALUE;
        private final IdentityHashMap<BaseClusterTopology, Integer> pinnedTopologies =
                new IdentityHashMap<>();
        private final long[] linkTargetSignatures = new long[14];
        @SuppressWarnings("unchecked")
        private final LinkEntry<SuperClusterTopology.BoundaryLinks>[] links =
                (LinkEntry<SuperClusterTopology.BoundaryLinks>[]) new LinkEntry<?>[14];
    }

    private final class FactsAttempt {
        private final ClusterKey key;
        private final long generation;
        private final Set<TopologyDemand> demands =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private NavigationScheduler.Priority priority = NavigationScheduler.Priority.BACKGROUND;
        private SnapshotSearch search;
        private CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture;

        private FactsAttempt(ClusterKey key, long generation) {
            this.key = key;
            this.generation = generation;
        }
    }

    public record PersistenceMetrics(long physicalReads,
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

    private final class TopologyDemand {
        private final ClusterKey key;
        private final BaseClusterTopology.GeometryKey geometry;
        private final long generation;
        private final long sequence;
        private final long enqueuedNanos;
        private final Set<TopologySubscription<BaseClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<FactsAttempt> factAttempts =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<ClusterEntry> heldFacts =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private NavigationScheduler.Priority priority = NavigationScheduler.Priority.BACKGROUND;
        private TopologyTaskExecutor.TaskHandle buildTask = UNTRACKED_TASK;
        private long fingerprint;
        private boolean dependencyPermit;
        private boolean prewarmSlot;
        private boolean queued;

        private TopologyDemand(ClusterKey key,
                               BaseClusterTopology.GeometryKey geometry,
                               long generation,
                               long sequence,
                               long enqueuedNanos) {
            this.key = key;
            this.geometry = geometry;
            this.generation = generation;
            this.sequence = sequence;
            this.enqueuedNanos = enqueuedNanos;
        }

    }

    final class TopologySubscription<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final boolean prewarmWaiter;
        private NavigationScheduler.Priority priority;
        private TopologyDemand demand;
        private Runnable cancellation = () -> {
        };
        private Consumer<NavigationScheduler.Priority> reprioritization = ignored -> {
        };
        private boolean active = true;

        private TopologySubscription(NavigationScheduler.Priority priority) {
            this(priority, false);
        }

        private TopologySubscription(NavigationScheduler.Priority priority,
                                     boolean prewarmWaiter) {
            this.priority = Objects.requireNonNull(priority, "priority");
            this.prewarmWaiter = prewarmWaiter;
            future.whenComplete((ignored, failure) -> {
                if (!future.isCancelled() || !active) {
                    return;
                }
                if (ownerThread.getAsBoolean()) {
                    cancellation.run();
                } else {
                    publisher.execute(cancellation);
                }
            });
        }

        private void cancel() {
            if (!active) {
                return;
            }
            cancellation.run();
            future.cancel(false);
        }

        private void reprioritize(NavigationScheduler.Priority requested) {
            if (active && priority != requested) {
                reprioritization.accept(requested);
            }
        }

        CompletableFuture<T> future() {
            return future;
        }
    }

    private final class DemandQueue {
        private static final long AGING_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

        private final EnumMap<NavigationScheduler.Priority,
                LinkedHashMap<ResourceKey<Level>, ArrayDeque<TopologyDemand>>> bands =
                new EnumMap<>(NavigationScheduler.Priority.class);
        private final List<ResourceKey<Level>> dimensions = new ArrayList<>();
        private final int[] cursors = new int[NavigationScheduler.Priority.values().length];
        private int size;

        private DemandQueue() {
            for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                bands.put(priority, new LinkedHashMap<>());
            }
        }

        private void add(TopologyDemand demand) {
            if (demand.queued) {
                return;
            }
            if (!dimensions.contains(demand.key.dimension())) {
                dimensions.add(demand.key.dimension());
            }
            insertStable(queue(demand.priority, demand.key.dimension()), demand);
            demand.queued = true;
            size++;
        }

        private void reprioritize(TopologyDemand demand,
                                  NavigationScheduler.Priority previous) {
            if (!demand.queued || previous == demand.priority) {
                return;
            }
            removeFromBand(demand, previous);
            insertStable(queue(demand.priority, demand.key.dimension()), demand);
        }

        private TopologyDemand poll(long now,
                                    java.util.function.Predicate<TopologyDemand> eligible) {
            int rank = Integer.MAX_VALUE;
            for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                for (ArrayDeque<TopologyDemand> dimension : bands.get(priority).values()) {
                    TopologyDemand candidate = firstEligible(dimension, eligible);
                    if (candidate == null) {
                        continue;
                    }
                    rank = Math.min(rank, effectiveRank(candidate, now));
                }
            }
            if (rank == Integer.MAX_VALUE || dimensions.isEmpty()) {
                return null;
            }
            int cursor = cursors[rank];
            for (int offset = 0; offset < dimensions.size(); offset++) {
                int index = (cursor + offset) % dimensions.size();
                ResourceKey<Level> dimension = dimensions.get(index);
                TopologyDemand selected = null;
                for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                    ArrayDeque<TopologyDemand> queue = bands.get(priority).get(dimension);
                    TopologyDemand candidate = queue == null
                            ? null : firstEligible(queue, eligible);
                    if (candidate != null && effectiveRank(candidate, now) == rank
                            && (selected == null || candidate.sequence < selected.sequence)) {
                        selected = candidate;
                    }
                }
                if (selected != null) {
                    cursors[rank] = (index + 1) % dimensions.size();
                    remove(selected);
                    return selected;
                }
            }
            throw new IllegalStateException("topology demand queue is inconsistent");
        }

        private static <T> T firstEligible(Iterable<T> candidates,
                                           java.util.function.Predicate<T> eligible) {
            // A blocked head must not hide a runnable dependency/prewarm demand behind it.
            for (T candidate : candidates) {
                if (eligible.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private void remove(TopologyDemand demand) {
            if (!demand.queued) {
                return;
            }
            removeFromBand(demand, demand.priority);
            demand.queued = false;
            size--;
        }

        private void clear() {
            for (LinkedHashMap<ResourceKey<Level>, ArrayDeque<TopologyDemand>> band : bands.values()) {
                for (ArrayDeque<TopologyDemand> queue : band.values()) {
                    queue.forEach(demand -> demand.queued = false);
                }
                band.clear();
            }
            size = 0;
        }

        private int size() {
            return size;
        }

        private ArrayDeque<TopologyDemand> queue(NavigationScheduler.Priority priority,
                                                 ResourceKey<Level> dimension) {
            return bands.get(priority).computeIfAbsent(dimension, ignored -> new ArrayDeque<>());
        }

        private void removeFromBand(TopologyDemand demand,
                                    NavigationScheduler.Priority priority) {
            LinkedHashMap<ResourceKey<Level>, ArrayDeque<TopologyDemand>> band = bands.get(priority);
            ArrayDeque<TopologyDemand> queue = band.get(demand.key.dimension());
            if (queue == null || !queue.remove(demand)) {
                throw new IllegalStateException("queued topology demand is missing from its band");
            }
            if (queue.isEmpty()) {
                band.remove(demand.key.dimension());
            }
        }

        private int effectiveRank(TopologyDemand demand, long now) {
            int rank = demand.priority.ordinal();
            if (demand.prewarmSlot) {
                return NavigationScheduler.Priority.BACKGROUND.ordinal();
            }
            long waited = Math.max(0L, now - demand.enqueuedNanos);
            return rank - (int) Math.min(rank, waited / AGING_NANOS);
        }

        private void insertStable(ArrayDeque<TopologyDemand> queue, TopologyDemand inserted) {
            if (queue.isEmpty() || queue.peekLast().sequence < inserted.sequence) {
                queue.addLast(inserted);
                return;
            }
            ArrayDeque<TopologyDemand> reordered = new ArrayDeque<>(queue.size() + 1);
            boolean added = false;
            for (TopologyDemand demand : queue) {
                if (!added && inserted.sequence < demand.sequence) {
                    reordered.addLast(inserted);
                    added = true;
                }
                reordered.addLast(demand);
            }
            if (!added) {
                reordered.addLast(inserted);
            }
            queue.clear();
            queue.addAll(reordered);
        }
    }

    private final class SuperEntry {
        private long attempt;
        private boolean attemptRunning;
        private SuperClusterTopology topology;
        private long handoffUntilTick = Long.MIN_VALUE;
        private final IdentityHashMap<SuperClusterTopology, Integer> pinnedTopologies =
                new IdentityHashMap<>();
        private final Set<TopologySubscription<SuperClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private List<TopologySubscription<BaseClusterTopology>> children = List.of();
        private NavigationScheduler.Priority requestPriority;
        private TopologyTaskExecutor.TaskHandle buildTask;
        private int pins;
        private final long[] linkTargetSignatures = new long[14];
        @SuppressWarnings("unchecked")
        private final LinkEntry<SuperClusterTopology.CrossingIndex>[] links =
                (LinkEntry<SuperClusterTopology.CrossingIndex>[]) new LinkEntry<?>[14];
    }

    private record SuperCacheKey(ResourceKey<Level> dimension,
                                 SectionPos origin,
                                 BaseClusterTopology.GeometryKey geometry,
                                 BaseClusterTopology.MovementKey movement) {
        private SuperCacheKey(ResourceKey<Level> dimension,
                              SectionPos origin,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile) {
            this(dimension, origin, profile.geometry(channel), profile.movement(channel));
        }

        private SuperCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(movement, "movement");
        }

    }

    private record BaseBoundaryCacheKey(
            ResourceKey<Level> dimension,
            BaseClusterTopology source,
            BaseClusterTopology target,
            Direction face) {

        private BaseBoundaryCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            if (!source.geometry().equals(target.geometry())) {
                throw new IllegalArgumentException("base boundary geometries differ");
            }
            SectionPos direct = SuperClusterTopology.offset(source.section(), face, 1);
            if (target.section().x() != direct.x() || target.section().z() != direct.z()
                    || (face.getAxis().isVertical() && target.section().y() != direct.y())
                    || (!face.getAxis().isVertical()
                    && Math.abs(target.section().y() - source.section().y()) > 1)) {
                throw new IllegalArgumentException("base boundary cache key is not adjacent");
            }
        }

    }

    private record SuperBoundaryCacheKey(ResourceKey<Level> dimension,
                                         SuperClusterTopology source,
                                         SuperClusterTopology target,
                                         Direction face) {
        private SuperBoundaryCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            SectionPos direct = SuperClusterTopology.offset(
                    source.origin(), face, SuperClusterTopology.CHILDREN_PER_AXIS
            );
            boolean adjacent = face.getAxis().isVertical()
                    ? target.origin().equals(direct)
                    : target.origin().x() == direct.x() && target.origin().z() == direct.z()
                    && Math.abs(target.origin().y() - source.origin().y())
                    <= SuperClusterTopology.CHILDREN_PER_AXIS;
            if (!adjacent || !target.geometry().equals(source.geometry())
                    || !target.movement().equals(source.movement())) {
                throw new IllegalArgumentException("super boundary cache key is not compatible");
            }
        }
    }

    private static final class LinkEntry<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile NavigationScheduler.Priority priority;
        private TopologyTaskExecutor.TaskHandle task = UNTRACKED_TASK;
        private List<TopologySubscription<BaseClusterTopology>> children = List.of();
        private T value;

        private LinkEntry(NavigationScheduler.Priority priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
        }

        private void track(TopologyTaskExecutor.TaskHandle task) {
            this.task = Objects.requireNonNull(task, "task");
            task.promote(priority);
        }

        private void promote(NavigationScheduler.Priority requested) {
            if (requested.higherThan(priority)) {
                priority = requested;
                task.promote(requested);
                children.forEach(child -> child.reprioritize(requested));
            }
        }

    }

    public final class MacroRequest {
        private final ServerLevel level;
        private final MacroOwnerKey ownerKey;
        private final BlockPos startPosition;
        private final BlockPos goalPosition;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final NavigationScheduler.Priority priority;
        private final float weight;
        private boolean hierarchical;
        private final CompletableFuture<MacroSearch.Corridor> future = new CompletableFuture<>();
        private List<TopologySubscription<BaseClusterTopology>> endpointSubscriptions = List.of();

        private boolean active = true;
        private boolean completedFromCache;
        private long resolveAttempt;
        private MacroFlight flight;
        private MacroSearch.Failure failure = MacroSearch.Failure.NONE;
        private SectionPos blockedSection;
        private QueryMetrics metrics;
        private boolean fallbackResolve;
        private boolean fallbackStart;
        private boolean fallbackGoal;
        private List<MacroComponentKey> startCandidates = List.of();
        private List<MacroComponentKey> goalCandidates = List.of();

        private MacroRequest(ServerLevel level,
                             MacroOwnerKey ownerKey,
                             BlockPos startPosition,
                             BlockPos goalPosition,
                             BaseClusterTopology.Channel channel,
                             BaseClusterTopology.TraversalProfile profile,
                             NavigationScheduler.Priority priority,
                             float weight) {
            this.level = level;
            this.ownerKey = ownerKey;
            this.startPosition = startPosition;
            this.goalPosition = goalPosition;
            this.channel = channel;
            this.profile = profile;
            this.priority = priority;
            this.weight = weight;
            future.whenComplete((ignored, requestFailure) -> {
                if (!future.isCancelled() || !active) {
                    return;
                }
                if (ownerThread.getAsBoolean()) {
                    cancelInternal();
                } else {
                    publisher.execute(this::cancelInternal);
                }
            });
        }

        public CompletableFuture<MacroSearch.Corridor> future() {
            return future;
        }

        public MacroSearch.Failure failure() {
            return failure;
        }

        @Nullable
        public SectionPos blockedSection() {
            MacroFlight current = flight;
            return current == null || current.query == null
                    ? blockedSection
                    : current.query.blockedSection();
        }

        public QueryMetrics metrics() {
            if (metrics != null) {
                return metrics;
            }
            MacroFlight current = flight;
            return current == null || current.query == null
                    ? emptyQueryMetrics(hierarchical)
                    : current.query.metrics();
        }

        public boolean completedFromCache() {
            return completedFromCache;
        }

        public void cancel() {
            if (ownerThread.getAsBoolean()) {
                cancelInternal();
            } else {
                publisher.execute(this::cancelInternal);
            }
        }

        private void beginResolve() {
            requireOwnerThread();
            if (!active) {
                return;
            }
            clearEndpointSubscriptions();
            fallbackResolve = false;
            fallbackStart = false;
            fallbackGoal = false;
            long attempt = ++resolveAttempt;
            SectionPos startSection = SectionPos.of(startPosition);
            SectionPos goalSection = SectionPos.of(goalPosition);
            List<SectionPos> sections = startSection.equals(goalSection)
                    ? List.of(startSection)
                    : List.of(startSection, goalSection);
            List<TopologySubscription<BaseClusterTopology>> subscriptions = new ArrayList<>(2);
            for (SectionPos section : sections) {
                ClusterKey key = new ClusterKey(level.dimension(), section);
                if (!clusterLoaded(key)) {
                    failure = MacroSearch.Failure.UNAVAILABLE_CHUNK;
                    blockedSection = section;
                    finish(null, null, emptyQueryMetrics(hierarchical));
                    return;
                }
                subscriptions.add(subscribeClusterDependency(
                        level, section, profile.geometry(channel), priority
                ));
            }
            endpointSubscriptions = List.copyOf(subscriptions);
            CompletableFuture.allOf(subscriptions.stream()
                            .map(TopologySubscription::future)
                            .toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, resolveFailure) -> publisher.execute(
                            () -> completeResolve(attempt, resolveFailure)
                    ));
        }

        private void completeResolve(long attempt, @Nullable Throwable resolveFailure) {
            requireOwnerThread();
            if (!active || attempt != resolveAttempt) {
                return;
            }
            endpointSubscriptions = List.of();
            if (resolveFailure != null) {
                if (staleTopologyFailure(resolveFailure)) {
                    beginResolve();
                } else {
                    finishExceptionally(resolveFailure);
                }
                return;
            }
            ClusterKey startKey = new ClusterKey(level.dimension(), SectionPos.of(startPosition));
            ClusterKey goalKey = new ClusterKey(level.dimension(), SectionPos.of(goalPosition));
            BaseClusterTopology.GeometryKey geometry = profile.geometry(channel);
            BaseClusterTopology startTopology = topology(startKey, geometry);
            BaseClusterTopology goalTopology = topology(goalKey, geometry);
            if (startTopology == null || goalTopology == null) {
                beginResolve();
                return;
            }
            int startComponent = directComponent(startTopology, startPosition, channel);
            int goalComponent = directComponent(goalTopology, goalPosition, channel);
            if (!fallbackResolve && (startComponent < 0 || goalComponent < 0)) {
                beginFallbackResolve(startComponent < 0, goalComponent < 0);
                return;
            }
            CandidateResolution starts = fallbackStart
                    ? resolveCandidates(startPosition, geometry)
                    : CandidateResolution.direct(startKey.section(), startComponent,
                    startTopology.signature());
            CandidateResolution goals = fallbackGoal
                    ? resolveCandidates(goalPosition, geometry)
                    : CandidateResolution.direct(goalKey.section(), goalComponent,
                    goalTopology.signature());
            if (starts.candidates.isEmpty() || goals.candidates.isEmpty()) {
                CandidateResolution missing = starts.candidates.isEmpty() ? starts : goals;
                failure = missing.unavailable == null
                        ? MacroSearch.Failure.NO_STRUCTURAL_ROUTE
                        : MacroSearch.Failure.UNAVAILABLE_CHUNK;
                blockedSection = missing.unavailable == null
                        ? (starts.candidates.isEmpty() ? startKey.section() : goalKey.section())
                        : missing.unavailable;
                finish(null, null, emptyQueryMetrics(false));
                return;
            }
            startCandidates = starts.candidates;
            goalCandidates = goals.candidates;
            hierarchical = shouldUseSuperGraph(startCandidates, goalCandidates)
                    && endpointParentsInBuildHeight(level, startCandidates)
                    && endpointParentsInBuildHeight(level, goalCandidates);
            MacroQueryKey key = new MacroQueryKey(
                    level.dimension(),
                    channel,
                    profile,
                    hierarchical,
                    Float.floatToRawIntBits(weight),
                    queryNodeBudget(startPosition, goalPosition, hierarchical),
                    startCandidates,
                    goalCandidates
            );
            CachedCorridor cached = completedCorridor(key);
            if (cached != null) {
                completedFromCache = true;
                finish(
                        rematerializeCorridor(cached.corridor, startPosition, goalPosition),
                        null,
                        emptyQueryMetrics(hierarchical)
                );
                return;
            }
            MacroFlight current = macroFlights.get(key);
            if (current != null) {
                macroInFlightJoins++;
                current.add(this);
                return;
            }
            MacroFlight created = new MacroFlight(key, this);
            macroFlights.put(key, created);
            created.start();
        }

        private void beginFallbackResolve(boolean start, boolean goal) {
            fallbackResolve = true;
            fallbackStart = start;
            fallbackGoal = goal;
            clearEndpointSubscriptions();
            long attempt = ++resolveAttempt;
            Set<SectionPos> sections = new HashSet<>();
            if (start) sections.addAll(candidateSections(startPosition));
            if (goal) sections.addAll(candidateSections(goalPosition));
            List<TopologySubscription<BaseClusterTopology>> subscriptions = new ArrayList<>();
            for (SectionPos section : sections.stream().sorted(
                    Comparator.comparingLong(SectionPos::asLong)).toList()) {
                ClusterKey key = new ClusterKey(level.dimension(), section);
                if (clusterLoaded(key)) {
                    subscriptions.add(subscribeClusterDependency(
                            level, section, profile.geometry(channel), priority
                    ));
                }
            }
            endpointSubscriptions = List.copyOf(subscriptions);
            CompletableFuture.allOf(subscriptions.stream()
                            .map(TopologySubscription::future).toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> publisher.execute(
                            () -> completeResolve(attempt, failure)
                    ));
        }

        private CandidateResolution resolveCandidates(BlockPos position,
                                                      BaseClusterTopology.GeometryKey geometry) {
            Set<MacroComponentKey> candidates = new HashSet<>();
            SectionPos unavailable = null;
            for (BlockPos anchor : candidateAnchors(position)) {
                SectionPos section = SectionPos.of(anchor);
                ClusterKey key = new ClusterKey(level.dimension(), section);
                if (!clusterLoaded(key)) {
                    if (unavailable == null) unavailable = section;
                    continue;
                }
                BaseClusterTopology topology = TopologyService.this.topology(key, geometry);
                if (topology == null) continue;
                int component = directComponent(topology, anchor, channel);
                if (component >= 0) {
                    candidates.add(new MacroComponentKey(section, component, topology.signature()));
                }
            }
            List<MacroComponentKey> ordered = candidates.stream()
                    .sorted(MacroComponentKey.ORDER).toList();
            return new CandidateResolution(ordered, unavailable);
        }

        private void finish(@Nullable MacroSearch.Corridor result,
                            @Nullable MacroSearch.Failure resultFailure,
                            QueryMetrics resultMetrics) {
            if (!active) {
                return;
            }
            active = false;
            clearEndpointSubscriptions();
            flight = null;
            metrics = resultMetrics;
            if (resultFailure != null) {
                failure = resultFailure;
            }
            macroRequests.remove(ownerKey, this);
            future.complete(result);
        }

        private void finishExceptionally(Throwable resultFailure) {
            if (!active) {
                return;
            }
            active = false;
            clearEndpointSubscriptions();
            flight = null;
            macroRequests.remove(ownerKey, this);
            future.completeExceptionally(resultFailure);
        }

        private void reject(Throwable rejection) {
            active = false;
            future.completeExceptionally(rejection);
        }

        private void cancelInternal() {
            requireOwnerThread();
            if (!active) {
                return;
            }
            active = false;
            failure = MacroSearch.Failure.CANCELLED;
            clearEndpointSubscriptions();
            MacroFlight current = flight;
            flight = null;
            if (current != null) {
                current.remove(this);
            }
            macroRequests.remove(ownerKey, this);
            future.cancel(false);
        }

        private void clearEndpointSubscriptions() {
            if (endpointSubscriptions.isEmpty()) {
                return;
            }
            List<TopologySubscription<BaseClusterTopology>> activeSubscriptions =
                    endpointSubscriptions;
            endpointSubscriptions = List.of();
            activeSubscriptions.forEach(TopologySubscription::cancel);
        }
    }

    private final class MacroFlight {
        private final MacroQueryKey key;
        private final UUID schedulerOwner = new UUID(
                0x414e41564d414352L,
                ++macroFlightSequence
        );
        private final Set<MacroRequest> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private NavigationScheduler.Priority priority;
        private MacroQuery query;

        private MacroFlight(MacroQueryKey key, MacroRequest first) {
            this.key = key;
            add(first);
        }

        private void start() {
            MacroRequest representative = waiters.iterator().next();
            query = new MacroQuery(
                    representative.level,
                    representative.startPosition,
                    representative.goalPosition,
                    representative.channel,
                    representative.profile,
                    priority,
                    representative.weight,
                    key
            );
            macroPhysicalSearches++;
            NavigationScheduler.forServer(server).submitStrict(
                    key.dimension,
                    schedulerOwner,
                    priority,
                    query
            ).whenComplete((corridor, searchFailure) -> publisher.execute(
                    () -> complete(corridor, searchFailure)
            ));
        }

        private void add(MacroRequest waiter) {
            waiters.add(waiter);
            waiter.flight = this;
            macroMaximumGroupSize = Math.max(macroMaximumGroupSize, waiters.size());
            updatePriority();
        }

        private void remove(MacroRequest waiter) {
            if (!waiters.remove(waiter)) {
                return;
            }
            if (waiters.isEmpty()) {
                macroFlights.remove(key, this);
                if (query != null) {
                    NavigationScheduler.forServer(server).cancel(key.dimension, schedulerOwner);
                }
            } else {
                updatePriority();
            }
        }

        private void updatePriority() {
            NavigationScheduler.Priority requested = waiters.stream()
                    .map(waiter -> waiter.priority)
                    .reduce(NavigationScheduler.Priority.BACKGROUND,
                            TopologyService::higherPriority);
            if (requested == priority) {
                return;
            }
            priority = requested;
            if (query != null) {
                query.reprioritize(priority);
                NavigationScheduler.forServer(server).reprioritize(
                        key.dimension,
                        schedulerOwner,
                        priority
                );
            }
        }

        private void complete(@Nullable MacroSearch.Corridor corridor,
                              @Nullable Throwable searchFailure) {
            requireOwnerThread();
            if (!macroFlights.remove(key, this)) {
                return;
            }
            QueryMetrics queryMetrics = query.metrics();
            List<MacroRequest> completing = List.copyOf(waiters);
            waiters.clear();
            if (searchFailure == null && corridor != null && !isCurrent(key.dimension, corridor)) {
                completing.forEach(waiter -> {
                    waiter.flight = null;
                    waiter.beginResolve();
                });
                return;
            }
            if (searchFailure == null && corridor != null
                    && endpointIdentityMatches(key, corridor)) {
                cacheCompletedCorridor(key, corridor);
            }
            for (MacroRequest waiter : completing) {
                waiter.flight = null;
                if (searchFailure != null) {
                    waiter.metrics = queryMetrics;
                    waiter.finishExceptionally(searchFailure);
                } else if (corridor == null) {
                    waiter.finish(null, query.failure(), queryMetrics);
                } else {
                    waiter.finish(
                            rematerializeCorridor(
                                    corridor,
                                    waiter.startPosition,
                                    waiter.goalPosition
                            ),
                            null,
                            queryMetrics
                    );
                }
            }
        }
    }

    @Nullable
    private CachedCorridor completedCorridor(MacroQueryKey key) {
        CachedCorridor cached = completedCorridors.get(key);
        if (cached == null) {
            macroCompletedMisses++;
            return null;
        }
        if (!isCurrent(key.dimension, cached.corridor)) {
            completedCorridors.remove(key);
            completedCorridorBytes -= cached.retainedBytes;
            macroStaleEvictions++;
            macroCompletedMisses++;
            return null;
        }
        macroCompletedHits++;
        return cached;
    }

    private void cacheCompletedCorridor(MacroQueryKey key, MacroSearch.Corridor corridor) {
        long bytes = estimateCorridorBytes(corridor);
        if (bytes > MAX_COMPLETED_CORRIDOR_BYTES) {
            return;
        }
        CachedCorridor previous = completedCorridors.put(
                key,
                new CachedCorridor(corridor, bytes)
        );
        if (previous != null) {
            completedCorridorBytes -= previous.retainedBytes;
        }
        completedCorridorBytes += bytes;
        while (completedCorridors.size() > MAX_COMPLETED_CORRIDORS
                || completedCorridorBytes > MAX_COMPLETED_CORRIDOR_BYTES) {
            Iterator<Map.Entry<MacroQueryKey, CachedCorridor>> iterator =
                    completedCorridors.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            CachedCorridor evicted = iterator.next().getValue();
            iterator.remove();
            completedCorridorBytes -= evicted.retainedBytes;
            macroCacheEvictions++;
        }
    }

    private static long estimateCorridorBytes(MacroSearch.Corridor corridor) {
        long bytes = 96L + corridor.endpoints().size() * 56L
                + corridor.connections().size() * 72L;
        for (MacroSearch.Connection connection : corridor.connections()) {
            if (connection.transition() instanceof MacroSearch.BoundaryTransition boundary) {
                bytes += boundary.retainedBytes();
            }
        }
        return bytes;
    }

    private static boolean endpointIdentityMatches(
            MacroQueryKey key,
            MacroSearch.Corridor corridor) {
        List<MacroSearch.Endpoint> endpoints = corridor.endpoints();
        if (endpoints.isEmpty()) {
            return false;
        }
        MacroSearch.Endpoint start = endpoints.get(0);
        MacroSearch.Endpoint goal = endpoints.get(endpoints.size() - 1);
        return start instanceof MacroSearch.ExactEndpoint
                && goal instanceof MacroSearch.ExactEndpoint
                && start.revision() == candidateSignature(key.starts)
                && goal.revision() == candidateSignature(key.goals);
    }

    private static long candidateSignature(List<MacroComponentKey> candidates) {
        long hash = 0xcbf29ce484222325L;
        for (MacroComponentKey candidate : candidates) {
            hash = (hash ^ candidate.section.asLong()) * 0x100000001b3L;
            hash = (hash ^ candidate.componentId) * 0x100000001b3L;
            hash = (hash ^ candidate.signature) * 0x100000001b3L;
        }
        return hash;
    }

    private static MacroSearch.Corridor rematerializeCorridor(
            MacroSearch.Corridor corridor,
            BlockPos startPosition,
            BlockPos goalPosition) {
        List<MacroSearch.Endpoint> endpoints = new ArrayList<>(corridor.endpoints());
        MacroSearch.ExactEndpoint oldStart = (MacroSearch.ExactEndpoint) endpoints.get(0);
        MacroSearch.ExactEndpoint oldGoal =
                (MacroSearch.ExactEndpoint) endpoints.get(endpoints.size() - 1);
        endpoints.set(0, new MacroSearch.ExactEndpoint(
                oldStart.id(),
                startPosition,
                oldStart.revision()
        ));
        endpoints.set(endpoints.size() - 1, new MacroSearch.ExactEndpoint(
                oldGoal.id(),
                goalPosition,
                oldGoal.revision()
        ));
        List<MacroSearch.Connection> connections = new ArrayList<>(corridor.connections());
        for (int index : connections.size() == 1
                ? new int[]{0}
                : new int[]{0, connections.size() - 1}) {
            MacroSearch.Connection connection = connections.get(index);
            connections.set(index, new MacroSearch.Connection(
                    connection.id(),
                    endpoints.get(index),
                    endpoints.get(index + 1),
                    connection.lowerBound(),
                    connection.transition()
            ));
        }
        return new MacroSearch.Corridor(endpoints, connections, corridor.cost());
    }

    private static int directComponent(
            BaseClusterTopology topology,
            BlockPos position,
            BaseClusterTopology.Channel channel) {
        if (topology.geometry().channel() != channel) return -1;
        int x = Math.floorMod(position.getX(), BaseClusterTopology.SIDE);
        int y = Math.floorMod(position.getY(), BaseClusterTopology.SIDE);
        int z = Math.floorMod(position.getZ(), BaseClusterTopology.SIDE);
        return topology.componentAt(x, y, z);
    }

    private static List<BlockPos> candidateAnchors(BlockPos center) {
        List<BlockPos> result = new ArrayList<>(25);
        for (int distance = 0; distance <= 2; distance++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    int dx = distance - Math.abs(dy) - Math.abs(dz);
                    if (dx < 0) continue;
                    if (dx == 0) {
                        result.add(center.offset(0, dy, dz));
                    } else {
                        result.add(center.offset(-dx, dy, dz));
                        result.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<SectionPos> candidateSections(BlockPos center) {
        Set<SectionPos> sections = new HashSet<>();
        candidateAnchors(center).forEach(anchor -> sections.add(SectionPos.of(anchor)));
        return sections.stream().sorted(Comparator.comparingLong(SectionPos::asLong)).toList();
    }

    private static boolean shouldUseSuperGraph(List<MacroComponentKey> starts,
                                               List<MacroComponentKey> goals) {
        int maximum = 0;
        for (MacroComponentKey start : starts) {
            SectionPos startParent = SuperClusterTopology.originOf(start.section);
            for (MacroComponentKey goal : goals) {
                SectionPos goalParent = SuperClusterTopology.originOf(goal.section);
                maximum = Math.max(maximum, Math.max(
                        Math.abs(startParent.x() - goalParent.x()),
                        Math.max(Math.abs(startParent.y() - goalParent.y()),
                                Math.abs(startParent.z() - goalParent.z()))
                ) / SuperClusterTopology.CHILDREN_PER_AXIS);
            }
        }
        return maximum >= MIN_SUPER_CLUSTER_DISTANCE;
    }

    private static boolean endpointParentsInBuildHeight(ServerLevel level,
                                                        List<MacroComponentKey> candidates) {
        for (MacroComponentKey candidate : candidates) {
            if (!superClusterHeightAvailable(level,
                    SuperClusterTopology.originOf(candidate.section))) return false;
        }
        return true;
    }

    private static boolean staleTopologyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StaleTopologyException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static QueryMetrics emptyQueryMetrics(boolean hierarchical) {
        return new QueryMetrics(
                0L, 0L, 0L, 0L,
                0, 0, 0, 0, 0, 0,
                null, hierarchical,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, null
        );
    }

    private record MacroOwnerKey(ResourceKey<Level> dimension, UUID owner) {
    }

    private record MacroComponentKey(SectionPos section, int componentId, long signature) {
        private static final Comparator<MacroComponentKey> ORDER =
                Comparator.comparingLong((MacroComponentKey key) -> key.section.asLong())
                        .thenComparingInt(MacroComponentKey::componentId)
                        .thenComparingLong(MacroComponentKey::signature);
    }

    private record CandidateResolution(List<MacroComponentKey> candidates,
                                       @Nullable SectionPos unavailable) {
        private CandidateResolution {
            candidates = List.copyOf(candidates);
        }

        private static CandidateResolution direct(SectionPos section,
                                                  int component,
                                                  long signature) {
            return component < 0
                    ? new CandidateResolution(List.of(), null)
                    : new CandidateResolution(
                    List.of(new MacroComponentKey(section, component, signature)), null
            );
        }
    }

    private record MacroQueryKey(ResourceKey<Level> dimension,
                                 BaseClusterTopology.GeometryKey geometry,
                                 BaseClusterTopology.MovementKey movement,
                                 boolean hierarchical,
                                 int weightBits,
                                 int nodeBudget,
                                 List<MacroComponentKey> starts,
                                 List<MacroComponentKey> goals) {
        private MacroQueryKey {
            starts = List.copyOf(starts);
            goals = List.copyOf(goals);
        }

        private MacroQueryKey(ResourceKey<Level> dimension,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              boolean hierarchical,
                              int weightBits,
                              int nodeBudget,
                              List<MacroComponentKey> starts,
                              List<MacroComponentKey> goals) {
            this(dimension, profile.geometry(channel), profile.movement(channel), hierarchical,
                    weightBits, nodeBudget, List.copyOf(starts), List.copyOf(goals));
        }
    }

    private record CachedCorridor(MacroSearch.Corridor corridor, long retainedBytes) {
    }

    private record PrewarmKey(ResourceKey<Level> dimension, long chunkLong) {
    }

    private static final class PrewarmCandidate {
        private final PrewarmKey key;
        private int nextSectionY = Integer.MAX_VALUE;

        private PrewarmCandidate(PrewarmKey key) {
            this.key = key;
        }
    }

    private enum BuildOrigin {
        FRESH_BUILD,
        PERSISTENCE_HIT
    }

    /** Coordinates section availability around one pure, resumable macro search. */
    public final class MacroQuery implements ResumableSearch<MacroSearch.Corridor> {
        private final ServerLevel level;
        private final BlockPos startPosition;
        private final BlockPos goalPosition;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private NavigationScheduler.Priority priority;
        private final float weight;
        private boolean hierarchical;
        private final List<MacroComponentKey> startCandidates;
        private final List<MacroComponentKey> goalCandidates;
        private final Map<MacroSearch.DependencyKey, DependencyRequest> requests = new HashMap<>();
        // Completions are merged on the server task queue, but never form a query-wide barrier.
        private final Set<MacroSearch.DependencyKey> pendingDependencyNotifications = new HashSet<>();
        private boolean dependencyDrainScheduled;
        private long dependencyGeneration;

        private Status status = Status.RUNNING;
        private MacroSearch.Failure failure = MacroSearch.Failure.NONE;
        private MacroSearch search;
        private MacroSearch superSearch;
        private SuperTopologyGraph superGraph;
        private TopologyGraph baseGraph;
        private boolean refining;
        private MacroSearch.Corridor result;
        private MacroSearch.Corridor aggregateCorridor;
        private int witnessConnectionIndex;
        private MacroSearch.Endpoint recoveryCurrent;
        private AggregateWitness pendingWitness;
        private MacroSearch.Connection pendingAggregateConnection;
        private long recoveryEndpointSequence = 0x1000_0000L;
        private final List<MacroSearch.Endpoint> recoveredEndpoints = new ArrayList<>();
        private final List<MacroSearch.Connection> recoveredConnections = new ArrayList<>();
        private float recoveredCost;
        private MacroSearch.Metrics completedRefinementMetrics;
        private SectionPos blockedEndpoint;
        private Runnable wakeup;
        private Throwable buildFailure;
        private boolean waitingForBuild;
        private boolean parentFallbackUsed;
        private long queryCpuNanos;
        private long macroSearchNanos;
        private long superSearchNanos;
        private long refinementSearchNanos;
        private int requestedSections;
        private int completedSections;
        private int failedSections;
        private int requestedSuperClusters;
        private int completedSuperClusters;
        private int failedSuperClusters;
        private int requestedBaseBoundaryLinks;
        private int completedBaseBoundaryLinks;
        private int failedBaseBoundaryLinks;
        private int requestedSuperBoundaryLinks;
        private int completedSuperBoundaryLinks;
        private int failedSuperBoundaryLinks;
        private int parkCount;
        private int wakeCount;
        private int staleRestarts;

        private MacroQuery(ServerLevel level,
                           BlockPos startPosition,
                           BlockPos goalPosition,
                           BaseClusterTopology.Channel channel,
                           BaseClusterTopology.TraversalProfile profile,
                           NavigationScheduler.Priority priority,
                           float weight,
                           MacroQueryKey key) {
            if (!Float.isFinite(weight) || weight < 1.0F) {
                throw new IllegalArgumentException("weight must be finite and at least 1.0");
            }
            this.level = level;
            this.startPosition = startPosition;
            this.goalPosition = goalPosition;
            this.channel = channel;
            this.profile = profile;
            this.priority = priority;
            this.weight = weight;
            this.hierarchical = key.hierarchical;
            this.startCandidates = key.starts;
            this.goalCandidates = key.goals;
        }

        @Override
        public Status step(int expansionBudget, long deadlineNanos) {
            requireOwnerThread();
            long queryStarted = System.nanoTime();
            try {
                if (status != Status.RUNNING) {
                    return status;
                }
                if (buildFailure != null) {
                    status = Status.FAILED;
                    clearRequests();
                    releaseGraphs();
                    Throwable root = rootFailure(buildFailure);
                    if (root instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("macro topology build failed", buildFailure);
                }
                waitingForBuild = false;
                if (search == null && !refining && !prepareEndpoints()) {
                    return status;
                }
                if (refining) {
                    return stepWitnessRecovery(expansionBudget, deadlineNanos);
                }
                if (search == null) {
                    int maxVisitedNodes = queryNodeBudget(
                            startPosition,
                            goalPosition,
                            hierarchical
                    );
                    if (hierarchical && !refining) {
                        superGraph = new SuperTopologyGraph(
                                level.dimension(),
                                startPosition,
                                goalPosition,
                                channel,
                                profile,
                                startCandidates,
                                goalCandidates
                        );
                        search = new MacroSearch(superGraph, weight, maxVisitedNodes);
                    } else {
                        baseGraph = new TopologyGraph(
                                level.dimension(), startPosition, goalPosition,
                                channel, profile, startCandidates, goalCandidates);
                        search = new MacroSearch(
                                baseGraph,
                                weight,
                                maxVisitedNodes
                        );
                    }
                }

                boolean superPhase = hierarchical && !refining;
                long started = System.nanoTime();
                Status searchStatus = search.step(expansionBudget, deadlineNanos);
                long searchNanos = System.nanoTime() - started;
                macroSearchNanos += searchNanos;
                if (superPhase) {
                    superSearchNanos += searchNanos;
                } else {
                    refinementSearchNanos += searchNanos;
                }
                if (searchStatus == Status.SUCCEEDED) {
                    MacroSearch.Corridor candidate = search.result();
                    if (hierarchical && !refining) {
                        if (candidate == null || superGraph == null || !superGraph.revisionsValid()) {
                            restartStaleSearch();
                        } else {
                            superSearch = search;
                            search = null;
                            beginWitnessRecovery(candidate);
                            refining = true;
                            return stepWitnessRecovery(expansionBudget, deadlineNanos);
                        }
                    } else if (candidate != null && isCurrent(level.dimension(), candidate)) {
                        result = candidate;
                        status = Status.SUCCEEDED;
                        clearRequests();
                        releaseGraphs();
                    } else {
                        restartStaleSearch();
                    }
                } else if (searchStatus == Status.FAILED) {
                    if (search.failure() == MacroSearch.Failure.STALE_WORLD) {
                        restartStaleSearch();
                    } else {
                        failure = search.failure();
                        status = Status.FAILED;
                        clearRequests();
                        releaseGraphs();
                    }
                } else {
                    requestPendingSections();
                    waitingForBuild = search.waitingForTopology() && !requests.isEmpty();
                }
                return status;
            } finally {
                queryCpuNanos += System.nanoTime() - queryStarted;
            }
        }

        private void beginWitnessRecovery(MacroSearch.Corridor corridor) {
            aggregateCorridor = corridor;
            witnessConnectionIndex = 0;
            recoveryCurrent = corridor.endpoints().get(0);
            pendingWitness = null;
            pendingAggregateConnection = null;
            recoveredEndpoints.clear();
            recoveredConnections.clear();
            recoveredCost = 0.0F;
        }

        private Status stepWitnessRecovery(int expansionBudget, long deadlineNanos) {
            while (true) {
                if (search != null) {
                    long started = System.nanoTime();
                    Status searchStatus;
                    try {
                        searchStatus = search.step(expansionBudget, deadlineNanos);
                    } catch (StaleTopologyException stale) {
                        restartStaleSearch();
                        return status;
                    }
                    long spent = System.nanoTime() - started;
                    macroSearchNanos += spent;
                    refinementSearchNanos += spent;
                    if (searchStatus == Status.RUNNING) {
                        requestPendingSections();
                        waitingForBuild = search.waitingForTopology() && !requests.isEmpty();
                        return status;
                    }
                    if (searchStatus == Status.FAILED) {
                        if (search.failure() == MacroSearch.Failure.STALE_WORLD) {
                            restartStaleSearch();
                        } else {
                            return failWitness(search.failure());
                        }
                        return status;
                    }
                    MacroSearch.Corridor segment = search.result();
                    if (segment == null) {
                        return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                    }
                    completedRefinementMetrics = completedRefinementMetrics == null
                            ? search.metrics()
                            : completedRefinementMetrics.plus(search.metrics());
                    appendSegment(segment);
                    baseGraph.close();
                    baseGraph = null;
                    search = null;
                    if (pendingWitness != null) {
                        appendWitness(pendingWitness, pendingAggregateConnection);
                        pendingWitness = null;
                        pendingAggregateConnection = null;
                    }
                    continue;
                }
                if (aggregateCorridor == null
                        || witnessConnectionIndex >= aggregateCorridor.connections().size()) {
                    result = new MacroSearch.Corridor(
                            recoveredEndpoints,
                            recoveredConnections,
                            recoveredCost
                    );
                    status = Status.SUCCEEDED;
                    clearRequests();
                    releaseGraphs();
                    refining = false;
                    aggregateCorridor = null;
                    return status;
                }
                MacroSearch.Connection connection = aggregateCorridor.connections()
                        .get(witnessConnectionIndex);
                if (connection.transition() instanceof MacroSearch.MembershipTransition) {
                    if (connection.to() instanceof MacroSearch.ExactEndpoint) {
                        MacroSearch.AggregateEndpoint aggregate =
                                connection.from() instanceof MacroSearch.AggregateEndpoint endpoint
                                        ? endpoint : null;
                        if (aggregate == null) {
                            return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                        }
                        beginLocalRecovery(
                                recoveryCurrent,
                                aggregate.origin(),
                                aggregate.aggregateId(),
                                null,
                                true
                        );
                    }
                    witnessConnectionIndex++;
                    continue;
                }
                if (!(connection.transition() instanceof MacroSearch.AggregateTransition)) {
                    return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                }
                AggregateWitness witness = superGraph == null
                        ? null : superGraph.witness(connection.id());
                if (witness == null) {
                    return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                }
                beginLocalRecovery(
                        recoveryCurrent,
                        SuperClusterTopology.originOf(witness.source().section()),
                        aggregateId(witness.source()),
                        witness.source(),
                        false
                );
                pendingWitness = witness;
                pendingAggregateConnection = connection;
                witnessConnectionIndex++;
            }
        }
        private Status failWitness(MacroSearch.Failure reason) {
            failure = reason;
            status = Status.FAILED;
            clearRequests();
            releaseGraphs();
            return status;
        }
        private int aggregateId(MacroComponentKey component) {
            if (superGraph == null) return -1;
            SuperClusterTopology topology = superGraph.capturedTopology(
                    SuperClusterTopology.originOf(component.section()));
            return topology == null ? -1 : topology.aggregateId(component.section(), component.componentId());
        }
        private void beginLocalRecovery(MacroSearch.Endpoint from, SectionPos aggregateOrigin,
                                        int aggregate, @Nullable MacroComponentKey target,
                                        boolean finalGoal) {
            if (aggregate < 0) throw new StaleTopologyException("aggregate witness mapping changed");
            List<MacroComponentKey> starts = from instanceof MacroSearch.ComponentEndpoint component
                    ? List.of(new MacroComponentKey(component.section(), component.componentId(),
                    component.revision()))
                    : startCandidates;
            List<MacroComponentKey> goals = finalGoal ? goalCandidates
                    : List.of(Objects.requireNonNull(target));
            BlockPos goal = finalGoal ? goalPosition : componentAnchorFor(target);
            baseGraph = new TopologyGraph(level.dimension(), from.anchor(), goal, channel, profile,
                    starts, goals);
            search = new MacroSearch(new AggregateTopologyGraph(
                    baseGraph, superGraph, aggregateOrigin, aggregate), weight, MAX_LOCAL_WITNESS_NODES);
        }

        private BlockPos componentAnchorFor(MacroComponentKey component) {
            BaseClusterTopology topology = TopologyService.this.topology(
                    new ClusterKey(level.dimension(), component.section()), profile.geometry(channel));
            if (topology == null)
                throw new StaleTopologyException("witness component topology is unavailable");
            return componentAnchor(component.section(), topology, component.componentId());
        }

        private void appendSegment(MacroSearch.Corridor segment) {
            if (recoveredEndpoints.isEmpty()) {
                recoveredEndpoints.addAll(segment.endpoints());
                recoveredConnections.addAll(segment.connections());
                recoveredCost += segment.cost();
                recoveryCurrent = recoveredEndpoints.get(recoveredEndpoints.size() - 1);
                return;
            }
            List<MacroSearch.Endpoint> endpoints = segment.endpoints();
            for (int index = 1; index < endpoints.size(); index++) {
                recoveredEndpoints.add(endpoints.get(index));
            }
            for (int index = 0; index < segment.connections().size(); index++) {
                MacroSearch.Connection connection = segment.connections().get(index);
                MacroSearch.Endpoint from = index == 0
                        ? recoveredEndpoints.get(recoveredEndpoints.size()
                        - segment.connections().size() - 1)
                        : connection.from();
                recoveredConnections.add(new MacroSearch.Connection(connection.id(), from,
                        connection.to(), connection.lowerBound(), connection.transition()));
            }
            recoveredCost += segment.cost();
            recoveryCurrent = recoveredEndpoints.get(recoveredEndpoints.size() - 1);
        }

        private void appendWitness(AggregateWitness witness,
                                   MacroSearch.Connection aggregateConnection) {
            MacroSearch.Endpoint source = recoveryCurrent;
            MacroSearch.ComponentEndpoint target = componentEndpoint(witness.target());
            if (!source.anchor().equals(componentAnchorFor(witness.source())))
                throw new IllegalStateException("local witness recovery ended at wrong component");
            recoveredConnections.add(new MacroSearch.Connection(edgeId(source, target), source,
                    target, aggregateConnection.lowerBound(), new MacroSearch.LocalTransition()));
            recoveredEndpoints.add(target);
            recoveredCost += aggregateConnection.lowerBound();
            recoveryCurrent = target;
        }

        private MacroSearch.ComponentEndpoint componentEndpoint(MacroComponentKey component) {
            return new MacroSearch.ComponentEndpoint(recoveryEndpointSequence++,
                    componentAnchorFor(component), component.signature(), component.section(),
                    channel, component.componentId());
        }

        private void restartStaleSearch() {
            clearRequests();
            releaseGraphs();
            search = null;
            superSearch = null;
            superGraph = null;
            refining = false;
            aggregateCorridor = null; pendingWitness = null; pendingAggregateConnection = null;
            recoveredEndpoints.clear();
            recoveredConnections.clear();
            completedRefinementMetrics = null;
            result = null;
            failure = MacroSearch.Failure.NONE;
            blockedEndpoint = null;
            waitingForBuild = false;
            staleRestarts++;
        }

        private void releaseGraphs() {
            if (baseGraph != null) {
                baseGraph.close();
                baseGraph = null;
            }
            if (superGraph != null) {
                superGraph.close();
                superGraph = null;
            }
        }

        private boolean prepareEndpoints() {
            boolean ready = true;
            if (hierarchical && !refining) {
                Set<SectionPos> endpointParents = new HashSet<>();
                startCandidates.forEach(candidate -> endpointParents.add(
                        SuperClusterTopology.originOf(candidate.section)));
                goalCandidates.forEach(candidate -> endpointParents.add(
                        SuperClusterTopology.originOf(candidate.section)));
                for (SectionPos origin : endpointParents.stream()
                        .sorted(Comparator.comparingLong(SectionPos::asLong)).toList()) {
                    SuperCacheKey key = new SuperCacheKey(
                            level.dimension(),
                            origin,
                            channel,
                            profile
                    );
                    if (superTopology(key) != null) {
                        continue;
                    }
                    ready = false;
                    if (!superClusterAvailable(level, origin)) {
                        clearRequests();
                        releaseGraphs();
                        hierarchical = false;
                        return prepareEndpoints();
                    }
                    requestDependency(MacroSearch.Dependency.superCluster(
                            origin,
                            MacroSearch.Availability.PENDING
                    ));
                }
                waitingForBuild = !ready && !requests.isEmpty();
                return ready;
            }

            Set<SectionPos> endpointSections = new HashSet<>();
            startCandidates.forEach(candidate -> endpointSections.add(candidate.section));
            goalCandidates.forEach(candidate -> endpointSections.add(candidate.section));
            for (SectionPos section : endpointSections.stream()
                    .sorted(Comparator.comparingLong(SectionPos::asLong)).toList()) {
                ClusterKey key = new ClusterKey(level.dimension(), section);
                if (topology(key, profile.geometry(channel)) != null) {
                    continue;
                }
                ready = false;
                if (!clusterLoaded(key)) {
                    failure = MacroSearch.Failure.UNAVAILABLE_CHUNK;
                    blockedEndpoint = section;
                    status = Status.FAILED;
                    clearRequests();
                    return false;
                }
                requestDependency(new MacroSearch.Dependency(
                        section,
                        MacroSearch.Availability.PENDING
                ));
            }
            waitingForBuild = !ready && !requests.isEmpty();
            return ready;
        }

        private void requestPendingSections() {
            for (MacroSearch.Dependency dependency
                    : search.pendingDependencies(MAX_QUERY_PREFETCH_SECTIONS)) {
                MacroSearch.DependencyKey key = dependency.key();
                if (dependencyReady(key)) {
                    search.dependencyAvailable(key);
                } else if (dependencyAvailableInWorld(key)) {
                    requestDependency(dependency);
                } else {
                    // Re-expansion will reclassify this dependency as unavailable.
                    search.dependencyAvailable(key);
                }
            }
        }

        private boolean dependencyReady(MacroSearch.DependencyKey dependency) {
            return switch (dependency.kind()) {
                case BASE_CLUSTER -> topology(new ClusterKey(
                        level.dimension(),
                        dependency.position()
                ), profile.geometry(channel)) != null;
                case SUPER_CLUSTER -> superTopology(new SuperCacheKey(
                        level.dimension(),
                        dependency.position(),
                        channel,
                        profile
                )) != null;
                case BASE_BOUNDARY -> {
                    BaseBoundaryCacheKey key = baseBoundaryKey(dependency);
                    yield key != null && readyBaseBoundaryLinks(key) != null;
                }
                case SUPER_BOUNDARY -> {
                    SuperBoundaryCacheKey key = superBoundaryKey(dependency);
                    yield key != null && readySuperBoundaryLinks(key) != null;
                }
            };
        }

        private boolean dependencyAvailableInWorld(MacroSearch.DependencyKey dependency) {
            return switch (dependency.kind()) {
                case BASE_CLUSTER -> clusterLoaded(new ClusterKey(
                        level.dimension(),
                        dependency.position()
                ));
                case SUPER_CLUSTER -> superClusterAvailable(level, dependency.position());
                case BASE_BOUNDARY -> dependency.target() != null
                        && clusterLoaded(new ClusterKey(level.dimension(), dependency.position()))
                        && clusterLoaded(new ClusterKey(level.dimension(), dependency.target()));
                case SUPER_BOUNDARY -> dependency.target() != null
                        && superClusterAvailable(level, dependency.position())
                        && superClusterAvailable(level, dependency.target());
            };
        }

        @Nullable
        private BaseBoundaryCacheKey baseBoundaryKey(MacroSearch.DependencyKey dependency) {
            if (dependency.kind() != MacroSearch.DependencyKind.BASE_BOUNDARY
                    || dependency.target() == null || dependency.face() == null) {
                return null;
            }
            BaseClusterTopology source = topology(new ClusterKey(
                    level.dimension(),
                    dependency.position()
            ), profile.geometry(channel));
            BaseClusterTopology target = topology(new ClusterKey(
                    level.dimension(),
                    dependency.target()
            ), profile.geometry(channel));
            return source == null || target == null
                    ? null
                    : new BaseBoundaryCacheKey(
                            level.dimension(),
                            source,
                            target,
                            dependency.face()
                    );
        }

        @Nullable
        private SuperBoundaryCacheKey superBoundaryKey(MacroSearch.DependencyKey dependency) {
            if (dependency.kind() != MacroSearch.DependencyKind.SUPER_BOUNDARY
                    || dependency.target() == null || dependency.face() == null) {
                return null;
            }
            SuperClusterTopology source = superTopology(new SuperCacheKey(
                    level.dimension(),
                    dependency.position(),
                    channel,
                    profile
            ));
            SuperClusterTopology target = superTopology(new SuperCacheKey(
                    level.dimension(),
                    dependency.target(),
                    channel,
                    profile
            ));
            return source == null || target == null
                    ? null
                    : new SuperBoundaryCacheKey(
                            level.dimension(),
                            source,
                            target,
                            dependency.face()
                    );
        }

        private void requestDependency(MacroSearch.Dependency dependency) {
            MacroSearch.DependencyKey key = dependency.key();
            if (requests.containsKey(key)) {
                return;
            }
            DependencyRequest request;
            switch (key.kind()) {
                case BASE_CLUSTER -> {
                    TopologySubscription<BaseClusterTopology> subscription =
                            subscribeClusterDependency(
                                    level, key.position(), profile.geometry(channel), priority
                            );
                    request = new DependencyRequest(
                            subscription.future(),
                            subscription::cancel,
                            subscription::reprioritize
                    );
                    requestedSections++;
                }
                case SUPER_CLUSTER -> {
                    TopologySubscription<SuperClusterTopology> subscription = requestSuperCluster(
                            level,
                            key.position(),
                            channel,
                            profile,
                            priority
                    );
                    request = new DependencyRequest(
                            subscription.future(),
                            subscription::cancel,
                            subscription::reprioritize
                    );
                    requestedSuperClusters++;
                }
                case BASE_BOUNDARY -> {
                    BaseBoundaryCacheKey boundary = baseBoundaryKey(key);
                    CompletableFuture<?> future = boundary == null
                            ? CompletableFuture.failedFuture(new StaleTopologyException(
                                    "base boundary dependency lost a topology identity"
                            ))
                            : requestBaseBoundaryLinks(
                                    level.dimension(),
                                    boundary.source(),
                                    boundary.target(),
                                    boundary.face(),
                                    priority
                            );
                    request = new DependencyRequest(future, () -> {
                    }, requested -> {
                        LinkEntry<SuperClusterTopology.BoundaryLinks> entry =
                                boundary == null ? null : baseLinkEntry(boundary);
                        if (entry != null) {
                            entry.promote(requested);
                        }
                    });
                    requestedBaseBoundaryLinks++;
                }
                case SUPER_BOUNDARY -> {
                    SuperBoundaryCacheKey boundary = superBoundaryKey(key);
                    CompletableFuture<?> future = boundary == null
                            ? CompletableFuture.failedFuture(new StaleTopologyException(
                                    "super boundary dependency lost a topology identity"
                            ))
                            : requestSuperBoundaryLinks(
                                    level.dimension(),
                                    boundary.source(),
                                    boundary.target(),
                                    boundary.face(),
                                    priority
                            );
                    request = new DependencyRequest(future, () -> {
                    }, requested -> {
                        LinkEntry<SuperClusterTopology.CrossingIndex> entry =
                                boundary == null ? null : superLinkEntry(boundary);
                        if (entry != null) {
                            entry.promote(requested);
                        }
                    });
                    requestedSuperBoundaryLinks++;
                }
                default -> throw new IllegalStateException("unknown topology dependency " + key.kind());
            }
            requests.put(key, request);
            request.future.whenComplete((topology, requestFailure) -> publisher.execute(
                    () -> completeDependencyRequest(key, request, requestFailure)
            ));
        }

        private void completeDependencyRequest(MacroSearch.DependencyKey dependency,
                                               DependencyRequest request,
                                               @Nullable Throwable requestFailure) {
            requireOwnerThread();
            if (!requests.remove(dependency, request)) {
                return;
            }
            switch (dependency.kind()) {
                case BASE_CLUSTER -> {
                    if (requestFailure == null) {
                        completedSections++;
                    } else {
                        failedSections++;
                    }
                }
                case SUPER_CLUSTER -> {
                    if (requestFailure == null) {
                        completedSuperClusters++;
                    } else {
                        failedSuperClusters++;
                    }
                }
                case BASE_BOUNDARY -> {
                    if (requestFailure == null) {
                        completedBaseBoundaryLinks++;
                    } else {
                        failedBaseBoundaryLinks++;
                    }
                }
                case SUPER_BOUNDARY -> {
                    if (requestFailure == null) {
                        completedSuperBoundaryLinks++;
                    } else {
                        failedSuperBoundaryLinks++;
                    }
                }
            }
            if (status != Status.RUNNING) {
                return;
            }
            if (requestFailure != null) {
                if (!staleTopologyFailure(requestFailure) && dependencyAvailableInWorld(dependency)) {
                    Throwable root = rootFailure(requestFailure);
                    if (dependency.kind() == MacroSearch.DependencyKind.SUPER_CLUSTER
                            && root instanceof RuntimeException && !parentFallbackUsed) {
                        parentFallbackUsed = true;
                        parentBuildFailures++;
                        clearRequests();
                        releaseGraphs();
                        hierarchical = false;
                        search = null;
                        superSearch = null;
                        superGraph = null;
                        refining = false;
                        failure = MacroSearch.Failure.NONE;
                    } else {
                        buildFailure = root;
                    }
                }
            }
            if (buildFailure != null) {
                pendingDependencyNotifications.clear();
                if (wakeup != null) {
                    scheduleDependencyDrain();
                }
            } else {
                if (search != null) {
                    pendingDependencyNotifications.add(dependency);
                }
                if (search != null || wakeup != null) {
                    scheduleDependencyDrain();
                }
            }
        }

        private void clearRequests() {
            dependencyGeneration++;
            dependencyDrainScheduled = false;
            pendingDependencyNotifications.clear();
            waitingForBuild = false;
            if (requests.isEmpty()) {
                return;
            }
            List<DependencyRequest> active = List.copyOf(requests.values());
            requests.clear();
            active.forEach(DependencyRequest::cancel);
        }

        private void scheduleDependencyDrain() {
            if (dependencyDrainScheduled) {
                return;
            }
            dependencyDrainScheduled = true;
            long scheduledGeneration = dependencyGeneration;
            publisher.execute(() -> drainDependencyNotifications(scheduledGeneration));
        }

        private void drainDependencyNotifications(long scheduledGeneration) {
            requireOwnerThread();
            if (scheduledGeneration != dependencyGeneration) {
                return;
            }
            dependencyDrainScheduled = false;
            if (status != Status.RUNNING) {
                pendingDependencyNotifications.clear();
                return;
            }
            Set<MacroSearch.DependencyKey> completed = Set.copyOf(pendingDependencyNotifications);
            pendingDependencyNotifications.clear();
            if (search != null && !completed.isEmpty()) {
                search.dependenciesAvailable(completed);
            }
            if (wakeup != null) {
                waitingForBuild = false;
                signalWakeup();
            }
            if (!pendingDependencyNotifications.isEmpty()) {
                scheduleDependencyDrain();
            }
        }

        private void signalWakeup() {
            Runnable callback = wakeup;
            wakeup = null;
            if (callback != null) {
                wakeCount++;
                callback.run();
            }
        }

        @Override
        public boolean park(Runnable callback) {
            requireOwnerThread();
            Objects.requireNonNull(callback, "callback");
            if (status != Status.RUNNING || !waitingForBuild || requests.isEmpty()) {
                return false;
            }
            if (wakeup != null) {
                throw new IllegalStateException("macro query is already parked");
            }
            wakeup = callback;
            parkCount++;
            return true;
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        @Nullable
        public MacroSearch.Corridor result() {
            return result;
        }

        public MacroSearch.Failure failure() {
            return failure;
        }

        @Nullable
        public SectionPos blockedSection() {
            if (search != null) {
                return search.blockedSection();
            }
            return blockedEndpoint;
        }

        public QueryMetrics metrics() {
            MacroSearch.Metrics superMetrics = superSearch == null
                    ? hierarchical && !refining && search != null ? search.metrics() : null
                    : superSearch.metrics();
            MacroSearch.Metrics refinementMetrics = completedRefinementMetrics;
            if (search != null && (refining || !hierarchical)) {
                refinementMetrics = refinementMetrics == null
                        ? search.metrics() : refinementMetrics.plus(search.metrics());
            }
            MacroSearch.Metrics combined = superMetrics == null
                    ? refinementMetrics
                    : superMetrics.plus(refinementMetrics);
            return new QueryMetrics(
                    queryCpuNanos,
                    macroSearchNanos,
                    superSearchNanos,
                    refinementSearchNanos,
                    requestedSections,
                    completedSections,
                    failedSections,
                    parkCount,
                    wakeCount,
                    staleRestarts,
                    combined,
                    hierarchical,
                    requestedSuperClusters,
                    completedSuperClusters,
                    failedSuperClusters,
                    requestedBaseBoundaryLinks,
                    completedBaseBoundaryLinks,
                    failedBaseBoundaryLinks,
                    requestedSuperBoundaryLinks,
                    completedSuperBoundaryLinks,
                    failedSuperBoundaryLinks,
                    superMetrics,
                    refinementMetrics
            );
        }

        private void reprioritize(NavigationScheduler.Priority requested) {
            requireOwnerThread();
            if (status != Status.RUNNING || priority == requested) {
                return;
            }
            priority = requested;
            requests.values().forEach(request -> request.reprioritize(requested));
        }

        @Override
        public void cancel() {
            requireOwnerThread();
            if (status != Status.RUNNING) {
                clearRequests();
                releaseGraphs();
                return;
            }
            status = Status.FAILED;
            failure = MacroSearch.Failure.CANCELLED;
            waitingForBuild = false;
            wakeup = null;
            if (search != null) {
                search.cancel();
            }
            clearRequests();
            releaseGraphs();
        }

        private record DependencyRequest(
                CompletableFuture<?> future,
                Runnable cancellation,
                Consumer<NavigationScheduler.Priority> reprioritization) {
            private void cancel() {
                cancellation.run();
            }

            private void reprioritize(NavigationScheduler.Priority priority) {
                reprioritization.accept(priority);
            }
        }
    }

    public record QueryMetrics(long queryCpuNanos,
                               long macroSearchNanos,
                               long superSearchNanos,
                               long refinementSearchNanos,
                               int requestedSections,
                               int completedSections,
                               int failedSections,
                               int parkCount,
                               int wakeCount,
                               int staleRestarts,
                               @Nullable MacroSearch.Metrics searchMetrics,
                               boolean hierarchical,
                               int requestedSuperClusters,
                               int completedSuperClusters,
                               int failedSuperClusters,
                               int requestedBaseBoundaryLinks,
                               int completedBaseBoundaryLinks,
                               int failedBaseBoundaryLinks,
                               int requestedSuperBoundaryLinks,
                               int completedSuperBoundaryLinks,
                               int failedSuperBoundaryLinks,
                               @Nullable MacroSearch.Metrics superSearchMetrics,
                               @Nullable MacroSearch.Metrics refinementMetrics) {
    }

    private boolean clusterLoaded(ClusterKey key) {
        ServerLevel level = server.getLevel(key.dimension());
        return level != null && level.getChunkSource().getChunkNow(
                key.section().x(),
                key.section().z()
        ) != null;
    }

    private static final class StaleTopologyException extends RuntimeException {
        private StaleTopologyException(ClusterKey key) {
            super("topology build became stale for " + key);
        }

        private StaleTopologyException(String message) {
            super(message);
        }
    }

    /** Search view over profile-specific 32-cubed contractions. */
    private final class SuperTopologyGraph implements MacroSearch.Graph {
        private final ResourceKey<Level> dimension;
        private final MacroSearch.ExactEndpoint start;
        private final MacroSearch.ExactEndpoint goal;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Long2ObjectOpenHashMap<CapturedSuper> topologySnapshot =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<AggregateBinding[]> bindingsByCluster =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<AggregateBinding> bindingsByEndpoint =
                new Long2ObjectOpenHashMap<>();
        private final Map<Long, AggregateWitness> witnesses = new HashMap<>();
        private final List<AggregateBinding> startBindings;
        private final List<AggregateBinding> goalBindings;
        private long nextEndpointId = 2L;
        private long validatedEpoch;
        private boolean closed;

        private SuperTopologyGraph(ResourceKey<Level> dimension,
                                   BlockPos startPosition,
                                   BlockPos goalPosition,
                                   BaseClusterTopology.Channel channel,
                                   BaseClusterTopology.TraversalProfile profile,
                                   List<MacroComponentKey> startCandidates,
                                   List<MacroComponentKey> goalCandidates) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.start = new MacroSearch.ExactEndpoint(
                    0L, startPosition, candidateSignature(startCandidates));
            this.goal = new MacroSearch.ExactEndpoint(
                    1L, goalPosition, candidateSignature(goalCandidates));
            this.startBindings = bindCandidates(startCandidates);
            this.goalBindings = bindCandidates(goalCandidates);
            this.validatedEpoch = topologyEpoch;
        }

        @Override
        public MacroSearch.Endpoint start() {
            return start;
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return goal;
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            requireOwnerThread();
            if (from.id() == goal.id()) {
                return;
            }
            if (from.id() == start.id()) {
                for (AggregateBinding startBinding : startBindings) {
                    output.addMembership(
                            edgeId(start, startBinding.endpoint()),
                            startBinding.endpoint(),
                            0.0F
                    );
                }
                return;
            }

            AggregateBinding source = bindingsByEndpoint.get(from.id());
            if (source == null) {
                throw new IllegalArgumentException("endpoint does not belong to this super graph");
            }
            if (goalBindings.stream().anyMatch(goalBinding -> sameAggregate(source, goalBinding))) {
                output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
            }

            CapturedSuper sourceTopology = requireTopology(source.cluster());
            SuperClusterTopology topology = sourceTopology.topology;
            int aggregateId = source.aggregateId();
            for (int edge = topology.outgoingStart(aggregateId);
                 edge < topology.outgoingEnd(aggregateId); edge++) {
                AggregateBinding target = binding(
                        sourceTopology,
                        topology.outgoingTarget(edge)
                );
                long id = edgeId(source.endpoint(), target.endpoint());
                rememberWitness(id, sourceTopology, topology.outgoingWitness(edge),
                        sourceTopology, null);
                output.addAggregate(
                        id,
                        target.endpoint(),
                        topology.outgoingCost(edge)
                );
            }

            for (Direction face : DIRECTIONS) {
                if (face.getAxis().isVertical()) {
                    expandParentBoundary(source, sourceTopology, aggregateId, face, 0, output);
                } else {
                    for (int yShift = -1; yShift <= 1; yShift++) {
                        expandParentBoundary(source, sourceTopology, aggregateId, face, yShift,
                                output);
                    }
                }
            }
        }

        private void expandParentBoundary(AggregateBinding source,
                                          CapturedSuper sourceTopology,
                                          int aggregateId,
                                          Direction face,
                                          int yShift,
                                          MacroSearch.ExpansionBuffer output) {
            SectionPos neighborOrigin = sourceTopology.neighbor(face, yShift);
            if (!sourceTopology.topology.hasPotentialExit(aggregateId, neighborOrigin)) {
                return;
            }
            if (!isSuperHeightAvailable(neighborOrigin)) return;
            CapturedSuper neighbor = captureTopology(neighborOrigin);
            if (neighbor == null) {
                output.addDependency(MacroSearch.Dependency.superCluster(
                        neighborOrigin, isSuperLoaded(neighborOrigin)
                        ? MacroSearch.Availability.PENDING : MacroSearch.Availability.UNAVAILABLE
                ));
                return;
            }
            SuperClusterTopology.CrossingIndex links =
                    sourceTopology.boundaryLinks(face, neighbor);
            if (links == null) {
                output.addDependency(MacroSearch.Dependency.superBoundary(
                        source.cluster().origin(), neighbor.key.origin(), face,
                        MacroSearch.Availability.PENDING
                ));
                return;
            }
            for (int edge = links.edgeStart(aggregateId);
                 edge < links.edgeEnd(aggregateId); edge++) {
                AggregateBinding target = binding(neighbor, links.targetAggregate(edge));
                long id = edgeId(source.endpoint(), target.endpoint());
                rememberWitness(id, sourceTopology, links.witness(edge), neighbor, links.face(edge));
                output.addAggregate(id, target.endpoint(),
                        links.lowerBound(edge));
            }
        }

        private void rememberWitness(long id,
                                     CapturedSuper source,
                                     long packedNodes,
                                     CapturedSuper target,
                                     @Nullable Direction face) {
            int sourceNode = (int) (packedNodes >>> 32);
            int targetNode = (int) packedNodes;
            MacroComponentKey sourceComponent = new MacroComponentKey(
                    source.topology.nodeSection(sourceNode), source.topology.nodeComponent(sourceNode),
                    source.topology.nodeSignature(sourceNode));
            MacroComponentKey targetComponent = new MacroComponentKey(
                    target.topology.nodeSection(targetNode), target.topology.nodeComponent(targetNode),
                    target.topology.nodeSignature(targetNode));
            AggregateWitness witness = new AggregateWitness(sourceComponent, targetComponent, face);
            witnesses.putIfAbsent(id, witness);
        }

        @Nullable
        private AggregateWitness witness(long id) {
            return witnesses.get(id);
        }

        @Override
        public boolean revisionsValid() {
            requireOwnerThread();
            if (validatedEpoch == topologyEpoch) {
                return true;
            }
            for (CapturedSuper captured : topologySnapshot.values()) {
                SuperEntry current = superClusters.get(captured.key);
                if (current == null || current.topology != captured.topology) {
                    return false;
                }
            }
            validatedEpoch = topologyEpoch;
            return true;
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            if (endpoint.id() == goal.id()) {
                return 0.0F;
            }
            SectionPos sourceOrigin = endpoint instanceof MacroSearch.AggregateEndpoint aggregate
                    ? aggregate.origin()
                    : SuperClusterTopology.originOf(SectionPos.of(endpoint.anchor()));
            int best = Integer.MAX_VALUE;
            for (AggregateBinding binding : goalBindings) {
                SectionPos target = binding.cluster().origin();
                int dx = Math.abs(target.x() - sourceOrigin.x())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                int dy = Math.abs(target.y() - sourceOrigin.y())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                int dz = Math.abs(target.z() - sourceOrigin.z())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                best = Math.min(best, Math.max(dx, Math.max(dy, dz)));
            }
            return best;
        }

        @Override
        public float prefetchSlack() {
            return 1.0F;
        }

        private SuperCacheKey key(SectionPos origin) {
            return new SuperCacheKey(dimension, origin, channel, profile);
        }

        private CapturedSuper requireTopology(SuperCacheKey key) {
            CapturedSuper topology = captureTopology(key);
            if (topology == null) {
                throw new IllegalStateException("super topology is not ready for " + key);
            }
            return topology;
        }

        private BaseClusterTopology requireBaseTopology(ClusterKey key) {
            BaseClusterTopology topology = TopologyService.this.topology(
                    key, profile.geometry(channel)
            );
            if (topology == null) {
                throw new IllegalStateException("base topology is not ready for " + key);
            }
            return topology;
        }

        @Nullable
        private CapturedSuper captureTopology(SuperCacheKey key) {
            return captureTopology(key.origin(), key);
        }

        @Nullable
        private CapturedSuper captureTopology(SectionPos origin) {
            return captureTopology(origin, null);
        }

        @Nullable
        private CapturedSuper captureTopology(SectionPos origin,
                                              @Nullable SuperCacheKey knownKey) {
            long packed = origin.asLong();
            CapturedSuper captured = topologySnapshot.get(packed);
            if (captured != null) {
                return captured;
            }
            SuperCacheKey key = knownKey == null ? key(origin) : knownKey;
            SuperEntry entry = superClusters.get(key);
            SuperClusterTopology topology = entry == null ? null : entry.topology;
            if (topology != null) {
                pinSuper(entry, topology);
                captured = new CapturedSuper(key, topology, entry);
                topologySnapshot.put(packed, captured);
            }
            return captured;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (CapturedSuper captured : topologySnapshot.values()) {
                releaseSuperPin(captured.owner, captured.topology);
            }
            evictSuperCache();
        }

        private List<AggregateBinding> bindCandidates(List<MacroComponentKey> candidates) {
            List<AggregateBinding> bindings = new ArrayList<>(candidates.size());
            for (MacroComponentKey candidate : candidates) {
                CapturedSuper parent = requireTopology(key(
                        SuperClusterTopology.originOf(candidate.section)));
                BaseClusterTopology base = requireBaseTopology(
                        new ClusterKey(dimension, candidate.section));
                if (base.signature() != candidate.signature) {
                    throw new StaleTopologyException("candidate base topology changed");
                }
                int aggregateId = parent.topology.aggregateId(
                        candidate.section, candidate.componentId);
                if (aggregateId < 0) {
                    throw new StaleTopologyException("candidate parent mapping changed");
                }
                AggregateBinding binding = binding(parent, aggregateId);
                if (bindings.stream().noneMatch(existing -> sameAggregate(existing, binding))) {
                    bindings.add(binding);
                }
            }
            return List.copyOf(bindings);
        }

        private AggregateBinding binding(CapturedSuper captured, int aggregateId) {
            SuperClusterTopology topology = captured.topology;
            topology.outgoingStart(aggregateId);
            long packed = captured.key.origin().asLong();
            AggregateBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new AggregateBinding[topology.aggregateCount()];
                bindingsByCluster.put(packed, bindings);
            }
            AggregateBinding binding = bindings[aggregateId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.AggregateEndpoint endpoint = new MacroSearch.AggregateEndpoint(
                    nextEndpointId++,
                    topology.aggregateAnchor(aggregateId),
                    topology.signature(),
                    captured.key.origin(),
                    channel,
                    aggregateId
            );
            binding = new AggregateBinding(captured.key, aggregateId, endpoint);
            bindings[aggregateId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private boolean isSuperLoaded(SectionPos origin) {
            ServerLevel level = server.getLevel(dimension);
            return level != null && superClusterAvailable(level, origin);
        }

        private boolean isSuperHeightAvailable(SectionPos origin) {
            ServerLevel level = server.getLevel(dimension);
            return level != null && superClusterHeightAvailable(level, origin);
        }

        private SuperClusterTopology capturedTopology(SectionPos origin) {
            CapturedSuper captured = topologySnapshot.get(origin.asLong());
            return captured == null ? null : captured.topology;
        }

        private final class CapturedSuper {
            private final SuperCacheKey key;
            private final SuperClusterTopology topology;
            private final SuperEntry owner;
            private final SuperBoundaryCacheKey[] boundaries = new SuperBoundaryCacheKey[14];
            private final SectionPos[] neighbors = new SectionPos[14];
            private final SuperClusterTopology[] boundaryTargets = new SuperClusterTopology[14];
            private final SuperClusterTopology.CrossingIndex[] readyBoundaries =
                    new SuperClusterTopology.CrossingIndex[14];

            private CapturedSuper(SuperCacheKey key,
                                  SuperClusterTopology topology,
                                  SuperEntry owner) {
                this.key = key;
                this.topology = topology;
                this.owner = owner;
            }

            private SectionPos neighbor(Direction face, int yShift) {
                SectionPos direct = SuperClusterTopology.offset(
                        key.origin(), face, SuperClusterTopology.CHILDREN_PER_AXIS
                );
                SectionPos target = face.getAxis().isVertical() ? direct : SectionPos.of(
                        direct.x(), direct.y() + yShift * SuperClusterTopology.CHILDREN_PER_AXIS,
                        direct.z()
                );
                int index = superLinkSlot(key.origin(), target, face);
                SectionPos origin = neighbors[index];
                if (origin == null) {
                    origin = target;
                    neighbors[index] = origin;
                }
                return origin;
            }

            @Nullable
            private SuperClusterTopology.CrossingIndex boundaryLinks(
                    Direction face,
                    CapturedSuper target) {
                int index = superLinkSlot(key.origin(), target.key.origin(), face);
                if (boundaryTargets[index] != target.topology) {
                    boundaryTargets[index] = target.topology;
                    boundaries[index] = new SuperBoundaryCacheKey(
                            dimension,
                            topology,
                            target.topology,
                            face
                    );
                    readyBoundaries[index] = null;
                }
                SuperClusterTopology.CrossingIndex ready = readyBoundaries[index];
                if (ready == null) {
                    ready = readySuperBoundaryLinks(boundaries[index]);
                    readyBoundaries[index] = ready;
                }
                return ready;
            }
        }
    }

    /** Restricts one local recovery search to one immutable parent aggregate. */
    private final class AggregateTopologyGraph implements MacroSearch.Graph, ComponentAdmission {
        private final TopologyGraph delegate;
        private final SuperTopologyGraph parent;
        private final SectionPos aggregateOrigin;
        private final int aggregateId;

        private AggregateTopologyGraph(TopologyGraph delegate,
                                       SuperTopologyGraph parent,
                                       SectionPos aggregateOrigin,
                                       int aggregateId) {
            this.delegate = delegate;
            this.parent = parent;
            this.aggregateOrigin = Objects.requireNonNull(aggregateOrigin, "aggregateOrigin");
            this.aggregateId = aggregateId;
            if (parent.capturedTopology(aggregateOrigin) == null) {
                throw new StaleTopologyException("local recovery parent is unavailable");
            }
        }

        @Override
        public MacroSearch.Endpoint start() {
            return delegate.start();
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return delegate.goal();
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            delegate.expandInto(from, output, this);
        }

        @Override
        public boolean revisionsValid() {
            return parent.revisionsValid() && delegate.revisionsValid();
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            return delegate.heuristic(endpoint);
        }

        @Override
        public float prefetchSlack() {
            return delegate.prefetchSlack();
        }

        @Override
        public boolean allowsSection(SectionPos section) {
            return SuperClusterTopology.originOf(section).equals(aggregateOrigin);
        }

        @Override
        public boolean allowsComponent(SectionPos section, int componentId) {
            if (!allowsSection(section)) return false;
            SuperClusterTopology topology = parent.capturedTopology(aggregateOrigin);
            return topology != null && topology.aggregateId(section, componentId) == aggregateId;
        }
    }

    private final class TopologyGraph implements MacroSearch.Graph {
        private final ResourceKey<Level> dimension;
        private final MacroSearch.ExactEndpoint start;
        private final MacroSearch.ExactEndpoint goal;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Long2ObjectOpenHashMap<CapturedBase> topologySnapshot =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<ComponentBinding[]> bindingsByCluster =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<ComponentBinding> bindingsByEndpoint =
                new Long2ObjectOpenHashMap<>();
        private final List<ComponentBinding> startBindings;
        private final List<ComponentBinding> goalBindings;
        private long nextEndpointId = 2L;
        private long validatedEpoch;
        private boolean closed;

        private TopologyGraph(ResourceKey<Level> dimension,
                              BlockPos startPosition,
                              BlockPos goalPosition,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              List<MacroComponentKey> startCandidates,
                              List<MacroComponentKey> goalCandidates) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.start = new MacroSearch.ExactEndpoint(
                    0L,
                    startPosition,
                    candidateSignature(startCandidates)
            );
            this.goal = new MacroSearch.ExactEndpoint(
                    1L,
                    goalPosition,
                    candidateSignature(goalCandidates)
            );
            this.startBindings = bindCandidates(startCandidates);
            this.goalBindings = bindCandidates(goalCandidates);
            this.validatedEpoch = topologyEpoch;
        }

        @Override
        public MacroSearch.Endpoint start() {
            return start;
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return goal;
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            expandInto(from, output, null);
        }

        private void expandInto(MacroSearch.Endpoint from,
                                MacroSearch.ExpansionBuffer output,
                                @Nullable ComponentAdmission admission) {
            requireOwnerThread();
            if (from.id() == goal.id()) {
                return;
            }
            if (from.id() == start.id()) {
                for (ComponentBinding startBinding : startBindings) {
                    if (admission == null || admission.allowsComponent(
                            startBinding.cluster().section(), startBinding.componentId())) {
                        output.addMembership(edgeId(start, startBinding.endpoint()),
                                startBinding.endpoint(), 0.0F);
                    }
                }
                return;
            }

            ComponentBinding source = bindingsByEndpoint.get(from.id());
            if (source == null) {
                throw new IllegalArgumentException("endpoint does not belong to this graph");
            }
            if (goalBindings.stream().anyMatch(goalBinding -> sameComponent(source, goalBinding))) {
                output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
            }

            CapturedBase sourceTopology = requireTopology(source.cluster());
            BaseClusterTopology topology = sourceTopology.topology;
            int sourceComponentId = source.componentId();
            BaseClusterTopology.MovementKey movement = profile.movement(channel);
            for (int edge = topology.localEdgeStart(sourceComponentId);
                 edge < topology.localEdgeEnd(sourceComponentId); edge++) {
                if (!topology.localEdgeSupports(edge, movement)) {
                    continue;
                }
                int targetComponentId = topology.localEdgeTarget(edge);
                if (admission != null && !admission.allowsComponent(
                        source.cluster().section(),
                        targetComponentId
                )) {
                    continue;
                }
                if (admission != null && !admission.allowsTransition(
                        source.cluster().section(),
                        sourceComponentId,
                        source.cluster().section(),
                        targetComponentId
                )) {
                    continue;
                }
                ComponentBinding target = binding(sourceTopology, targetComponentId);
                output.addLocal(
                        edgeId(source.endpoint(), target.endpoint()),
                        target.endpoint(),
                        topology.localEdgeLowerBound(edge),
                        topology.localEdgeCapabilities(edge)
                );
            }

            for (Direction face : DIRECTIONS) {
                if (face.getAxis().isVertical()) {
                    expandBoundary(source, sourceTopology, sourceComponentId, face, 0,
                            admission, movement, output);
                } else {
                    for (int yShift = -1; yShift <= 1; yShift++) {
                        expandBoundary(source, sourceTopology, sourceComponentId, face, yShift,
                                admission, movement, output);
                    }
                }
            }
        }

        private void expandBoundary(ComponentBinding source,
                                    CapturedBase sourceTopology,
                                    int sourceComponentId,
                                    Direction face,
                                    int yShift,
                                    @Nullable ComponentAdmission admission,
                                    BaseClusterTopology.MovementKey movement,
                                    MacroSearch.ExpansionBuffer output) {
            SectionPos neighborSection = sourceTopology.neighbor(face, yShift);
            if ((admission != null && !admission.allowsSection(neighborSection))
                    || !isSectionHeightAvailable(neighborSection)) return;
            CapturedBase neighbor = captureTopology(neighborSection);
            if (neighbor == null) {
                output.addDependency(new MacroSearch.Dependency(
                        neighborSection, isClusterLoaded(neighborSection)
                        ? MacroSearch.Availability.PENDING : MacroSearch.Availability.UNAVAILABLE
                ));
                return;
            }
            SuperClusterTopology.BoundaryLinks links =
                    sourceTopology.boundaryLinks(face, yShift, neighbor);
            if (links == null) {
                output.addDependency(MacroSearch.Dependency.baseBoundary(
                        source.cluster().section(), neighborSection, face,
                        MacroSearch.Availability.PENDING
                ));
                return;
            }
            for (int edge = links.edgeStart(sourceComponentId);
                 edge < links.edgeEnd(sourceComponentId); edge++) {
                if (!links.supports(edge, movement)) continue;
                int targetComponentId = links.targetComponent(edge);
                if (admission != null && !admission.allowsComponent(
                        neighborSection, targetComponentId)) continue;
                if (admission != null && !admission.allowsTransition(
                        source.cluster().section(),
                        sourceComponentId,
                        neighborSection,
                        targetComponentId
                )) continue;
                ComponentBinding target = binding(neighbor, targetComponentId);
                output.addBoundary(edgeId(source.endpoint(), target.endpoint()), target.endpoint(),
                        links.lowerBound(edge), links, edge, movement.capabilityMask());
            }
        }

        @Override
        public boolean revisionsValid() {
            requireOwnerThread();
            if (validatedEpoch == topologyEpoch) {
                return true;
            }
            for (CapturedBase captured : topologySnapshot.values()) {
                ClusterEntry current = clusters.get(captured.key);
                if (current == null || current.topology(captured.topology.geometry())
                        != captured.topology) {
                    return false;
                }
            }
            validatedEpoch = topologyEpoch;
            return true;
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            if (endpoint.id() == goal.id()) {
                return 0.0F;
            }
            SectionPos sourceSection = endpoint instanceof MacroSearch.ComponentEndpoint component
                    ? component.section()
                    : SectionPos.of(endpoint.anchor());
            int best = Integer.MAX_VALUE;
            for (ComponentBinding binding : goalBindings) {
                SectionPos target = binding.cluster().section();
                int dx = Math.abs(target.x() - sourceSection.x());
                int dy = Math.abs(target.y() - sourceSection.y());
                int dz = Math.abs(target.z() - sourceSection.z());
                best = Math.min(best, Math.max(dx, Math.max(dy, dz)));
            }
            return best;
        }

        @Override
        public float prefetchSlack() {
            // One boundary transition is the graph's minimum cross-section cost.
            return 1.0F;
        }

        private CapturedBase requireTopology(ClusterKey key) {
            CapturedBase topology = captureTopology(key);
            if (topology == null) {
                throw new IllegalStateException("topology is not ready for " + key);
            }
            return topology;
        }

        @Nullable
        private CapturedBase captureTopology(ClusterKey key) {
            return captureTopology(key.section(), key);
        }

        @Nullable
        private CapturedBase captureTopology(SectionPos section) {
            return captureTopology(section, null);
        }

        @Nullable
        private CapturedBase captureTopology(SectionPos section, @Nullable ClusterKey knownKey) {
            long packed = section.asLong();
            CapturedBase captured = topologySnapshot.get(packed);
            if (captured != null) {
                return captured;
            }
            ClusterKey key = knownKey == null ? new ClusterKey(dimension, section) : knownKey;
            ClusterEntry entry = clusters.get(key);
            ViewEntry view = entry == null ? null : entry.views.get(profile.geometry(channel));
            BaseClusterTopology topology = view == null ? null : view.topology;
            if (topology == null) {
                return null;
            }
            pinBase(view, topology);
            captured = new CapturedBase(key, topology, view);
            topologySnapshot.put(packed, captured);
            return captured;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (CapturedBase captured : topologySnapshot.values()) {
                releaseBasePin(captured.owner, captured.topology);
            }
            evictBaseCache();
        }

        private List<ComponentBinding> bindCandidates(List<MacroComponentKey> candidates) {
            List<ComponentBinding> bindings = new ArrayList<>(candidates.size());
            for (MacroComponentKey candidate : candidates) {
                CapturedBase captured = requireTopology(
                        new ClusterKey(dimension, candidate.section));
                if (captured.topology.signature() != candidate.signature
                        || candidate.componentId < 0
                        || candidate.componentId >= captured.topology.componentCount()) {
                    throw new StaleTopologyException("candidate base topology changed");
                }
                bindings.add(binding(captured, candidate.componentId));
            }
            return List.copyOf(bindings);
        }

        private ComponentBinding binding(CapturedBase captured, int componentId) {
            BaseClusterTopology topology = captured.topology;
            if (topology.geometry().channel() != channel) {
                throw new IllegalArgumentException(
                        "component " + componentId + " does not use channel " + channel
                );
            }
            long packed = captured.key.section().asLong();
            ComponentBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new ComponentBinding[topology.componentCount()];
                bindingsByCluster.put(packed, bindings);
            }
            ComponentBinding binding = bindings[componentId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.ComponentEndpoint endpoint = new MacroSearch.ComponentEndpoint(
                    nextEndpointId++,
                    componentAnchor(captured.key.section(), topology, componentId),
                    topology.signature(),
                    captured.key.section(),
                    channel,
                    componentId
            );
            binding = new ComponentBinding(captured.key, componentId, endpoint);
            bindings[componentId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private boolean isClusterLoaded(SectionPos section) {
            return clusterLoaded(new ClusterKey(dimension, section));
        }

        private boolean isSectionHeightAvailable(SectionPos section) {
            ServerLevel level = server.getLevel(dimension);
            return level != null
                    && section.y() >= level.getMinSection()
                    && section.y() < level.getMaxSection();
        }

        private final class CapturedBase {
            private final ClusterKey key;
            private final BaseClusterTopology topology;
            private final ViewEntry owner;
            private final SectionPos[] neighbors = new SectionPos[14];
            private final BaseBoundaryCacheKey[] boundaries = new BaseBoundaryCacheKey[14];
            private final BaseClusterTopology[] boundaryTargets = new BaseClusterTopology[14];
            private final SuperClusterTopology.BoundaryLinks[] readyBoundaries =
                    new SuperClusterTopology.BoundaryLinks[14];

            private CapturedBase(ClusterKey key,
                                 BaseClusterTopology topology,
                                 ViewEntry owner) {
                this.key = key;
                this.topology = topology;
                this.owner = owner;
            }

            private SectionPos neighbor(Direction face, int yShift) {
                int index = face.getAxis().isVertical()
                        ? baseLinkSlot(key.section(), SuperClusterTopology.offset(
                        key.section(), face, 1), face)
                        : baseLinkSlot(key.section(), SectionPos.of(
                        key.section().x() + face.getStepX(), key.section().y() + yShift,
                        key.section().z() + face.getStepZ()), face);
                SectionPos section = neighbors[index];
                if (section == null) {
                    SectionPos horizontal = SuperClusterTopology.offset(key.section(), face, 1);
                    section = face.getAxis().isVertical() ? horizontal : SectionPos.of(
                            horizontal.x(), horizontal.y() + yShift, horizontal.z()
                    );
                    neighbors[index] = section;
                }
                return section;
            }

            @Nullable
            private SuperClusterTopology.BoundaryLinks boundaryLinks(
                    Direction face,
                    int yShift,
                    CapturedBase target) {
                int index = baseLinkSlot(key.section(), target.key.section(), face);
                if (boundaryTargets[index] != target.topology) {
                    boundaryTargets[index] = target.topology;
                    boundaries[index] = new BaseBoundaryCacheKey(
                            dimension,
                            topology,
                            target.topology,
                            face
                    );
                    readyBoundaries[index] = null;
                }
                SuperClusterTopology.BoundaryLinks ready = readyBoundaries[index];
                if (ready == null) {
                    ready = readyBaseBoundaryLinks(boundaries[index]);
                    readyBoundaries[index] = ready;
                }
                return ready;
            }
        }
    }

    private static BlockPos componentAnchor(SectionPos section,
                                            BaseClusterTopology topology,
                                            int componentId) {
        int anchor = topology.componentAnchorCell(componentId);
        return new BlockPos(
                section.minBlockX() + BaseClusterTopology.x(anchor),
                section.minBlockY() + BaseClusterTopology.y(anchor),
                section.minBlockZ() + BaseClusterTopology.z(anchor)
        );
    }

    private static long edgeId(MacroSearch.Endpoint from, MacroSearch.Endpoint to) {
        long fromId = from.id();
        long toId = to.id();
        if ((fromId & ~0xffff_ffffL) != 0L || (toId & ~0xffff_ffffL) != 0L) {
            throw new IllegalStateException("macro endpoint ID exceeds the collision-free edge range");
        }
        return fromId << 32 | toId;
    }

    private static boolean sameComponent(@Nullable ComponentBinding first,
                                         @Nullable ComponentBinding second) {
        return first != null && second != null
                && first.cluster().equals(second.cluster())
                && first.componentId() == second.componentId();
    }

    private static boolean sameAggregate(@Nullable AggregateBinding first,
                                         @Nullable AggregateBinding second) {
        return first != null && second != null
                && first.cluster().equals(second.cluster())
                && first.aggregateId() == second.aggregateId();
    }

    private record AggregateBinding(SuperCacheKey cluster,
                                    int aggregateId,
                                    MacroSearch.AggregateEndpoint endpoint) {
    }

    private record AggregateWitness(MacroComponentKey source, MacroComponentKey target,
                                    @Nullable Direction face) {}

    private record ComponentBinding(ClusterKey cluster,
                                    int componentId,
                                    MacroSearch.ComponentEndpoint endpoint) {
    }

    private interface ComponentAdmission {
        boolean allowsSection(SectionPos section);

        boolean allowsComponent(SectionPos section, int componentId);

        default boolean allowsTransition(SectionPos sourceSection,
                                         int sourceComponentId,
                                         SectionPos targetSection,
                                         int targetComponentId) {
            return true;
        }
    }

    @FunctionalInterface
    interface CellSampler {
        int sample(int cellIndex);
    }

    static final class SnapshotSearch implements ResumableSearch<BaseClusterTopology.Snapshot> {
        private final CellSampler sampler;
        private final int uniformFlags;
        private final byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        private Status status = Status.RUNNING;
        private BaseClusterTopology.Snapshot result;
        private int cursor;
        private long spentNanos;

        SnapshotSearch(CellSampler sampler) {
            this.sampler = Objects.requireNonNull(sampler, "sampler");
            this.uniformFlags = -1;
        }

        private SnapshotSearch(int uniformFlags) {
            int validFlags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
                    | BaseClusterTopology.FLUID | BaseClusterTopology.EXACT_REQUIRED;
            if ((uniformFlags & ~validFlags) != 0) {
                throw new IllegalArgumentException("uniform snapshot contains unknown flags");
            }
            this.sampler = null;
            this.uniformFlags = uniformFlags;
        }

        static SnapshotSearch uniform(int flags) {
            return new SnapshotSearch(flags);
        }

        @Override
        public Status step(int expansionBudget, long deadlineNanos) {
            if (status != Status.RUNNING) {
                return status;
            }
            if (expansionBudget <= 0) {
                throw new IllegalArgumentException("expansionBudget must be positive");
            }

            long started = System.nanoTime();
            if (uniformFlags >= 0) {
                Arrays.fill(cells, (byte) uniformFlags);
                cursor = BaseClusterTopology.CELL_COUNT;
                result = new BaseClusterTopology.Snapshot(cells);
                status = Status.SUCCEEDED;
                spentNanos += System.nanoTime() - started;
                return status;
            }
            int end = Math.min(BaseClusterTopology.CELL_COUNT, cursor + expansionBudget);
            try {
                while (cursor < end && System.nanoTime() < deadlineNanos) {
                    cells[cursor] = (byte) sampler.sample(cursor);
                    cursor++;
                }
                if (cursor == BaseClusterTopology.CELL_COUNT) {
                    result = new BaseClusterTopology.Snapshot(cells);
                    status = Status.SUCCEEDED;
                }
                return status;
            } catch (RuntimeException exception) {
                status = Status.FAILED;
                throw exception;
            } finally {
                spentNanos += System.nanoTime() - started;
            }
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        @Nullable
        public BaseClusterTopology.Snapshot result() {
            return result;
        }

        @Override
        public void cancel() {
            if (status == Status.RUNNING) {
                status = Status.FAILED;
            }
        }

        int sampledCells() {
            return cursor;
        }

        long spentNanos() {
            return spentNanos;
        }
    }

    private static final class LoadedBlockGetter implements BlockGetter {
        private final ServerLevel level;

        private LoadedBlockGetter(ServerLevel level) {
            this.level = level;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos position) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(position);
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(position);
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }
}
