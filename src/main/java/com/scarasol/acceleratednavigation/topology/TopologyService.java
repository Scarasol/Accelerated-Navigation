package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final int MAX_BASE_BOUNDARY_LINK_ENTRIES = 4_096;
    private static final int MAX_SUPER_BOUNDARY_LINK_ENTRIES = 1_024;
    private static final int MIN_QUERY_VISITED_NODES = 1_024;
    private static final int MIN_HIERARCHICAL_QUERY_VISITED_NODES = 2_048;
    private static final int MAX_QUERY_VISITED_NODES = 8_192;
    private static final float QUERY_VISITED_NODES_PER_BLOCK = 8.0F;
    private static final int MAX_DEPENDENCY_DEMANDS = 64;
    private static final int MAX_MACRO_QUERY_WAITERS = 1_024;
    private static final int MAX_COMPLETED_CORRIDORS = 1_024;
    private static final long MAX_COMPLETED_CORRIDOR_BYTES = 16L * 1024L * 1024L;

    private static final TopologyTaskExecutor.TaskHandle UNTRACKED_TASK =
            new TopologyTaskExecutor.TaskHandle() {
                @Override
                public void promote(NavigationScheduler.Priority priority) {
                }

                @Override
                public void reprioritize(NavigationScheduler.Priority priority) {
                }

                @Override
                public boolean cancel() {
                    return false;
                }
            };

    private final WorkDispatcher buildWorker;
    private final Executor publisher;
    private final BooleanSupplier ownerThread;
    private final TopologyTaskExecutor ownedBuildWorker;
    private final TopologyStore store;
    private final MinecraftServer server;
    private final Map<ClusterKey, ClusterEntry> clusters = new HashMap<>();
    private final LinkedHashMap<SuperCacheKey, SuperEntry> superClusters =
            new LinkedHashMap<>(32, 0.75F, true);
    private final LinkedHashMap<BaseBoundaryCacheKey, LinkEntry<SuperClusterTopology.BoundaryLinks>>
            baseBoundaryLinks = new LinkedHashMap<>(64, 0.75F, true);
    private final LinkedHashMap<SuperBoundaryCacheKey, LinkEntry<SuperClusterTopology.CrossingIndex>>
            superBoundaryLinks = new LinkedHashMap<>(32, 0.75F, true);
    private final Map<MacroOwnerKey, MacroRequest> macroRequests = new HashMap<>();
    private final Map<MacroQueryKey, MacroFlight> macroFlights = new HashMap<>();
    private final LinkedHashMap<MacroQueryKey, CachedCorridor> completedCorridors =
            new LinkedHashMap<>(32, 0.75F, true);
    private final DemandQueue requestedBuilds = new DemandQueue();
    private final LongAdder snapshotCells = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder buildRequests = new LongAdder();
    private final LongAdder buildNanos = new LongAdder();
    private final LongAdder publishedClusters = new LongAdder();
    private final LongAdder freshBuilds = new LongAdder();
    private final LongAdder persistenceHits = new LongAdder();
    private final LongAdder staleBuilds = new LongAdder();
    private final LongAdder coalescedInvalidations = new LongAdder();
    private final LongAdder dependencyStartsAtFullOrdinaryAdmission = new LongAdder();
    private final LongAdder superBuildRequests = new LongAdder();
    private final LongAdder superBuildNanos = new LongAdder();
    private final LongAdder publishedSuperClusters = new LongAdder();
    private final LongAdder staleSuperBuilds = new LongAdder();
    private final LongAdder evictedSuperClusters = new LongAdder();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicLong superRetainedBytes = new AtomicLong();
    private final LongAdder baseBoundaryBuildRequests = new LongAdder();
    private final LongAdder baseBoundaryBuildNanos = new LongAdder();
    private final LongAdder baseBoundaryHits = new LongAdder();
    private final LongAdder baseBoundaryMisses = new LongAdder();
    private final LongAdder baseBoundaryEvictions = new LongAdder();
    private final AtomicLong baseBoundaryRetainedBytes = new AtomicLong();
    private final LongAdder superBoundaryBuildRequests = new LongAdder();
    private final LongAdder superBoundaryBuildNanos = new LongAdder();
    private final LongAdder superBoundaryHits = new LongAdder();
    private final LongAdder superBoundaryMisses = new LongAdder();
    private final LongAdder superBoundaryEvictions = new LongAdder();
    private final AtomicLong superBoundaryRetainedBytes = new AtomicLong();

    private boolean closed;
    private long topologyEpoch;
    private long demandSequence;
    private int dependencyPermits;
    private int dependencyPermitHighWatermark;
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

    TopologyService(Executor worker, Executor publisher, BooleanSupplier ownerThread) {
        Executor injectedWorker = Objects.requireNonNull(worker, "worker");
        this.buildWorker = (dimension, priority, command) -> {
            injectedWorker.execute(command);
            return UNTRACKED_TASK;
        };
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.ownedBuildWorker = null;
        this.store = null;
        this.server = null;
    }

    private TopologyService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.ownedBuildWorker = new TopologyTaskExecutor(
                "accelerated-navigation-topology",
                Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
        );
        this.buildWorker = ownedBuildWorker::submit;
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
            service.startRequestedBuilds();
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
                newShape,
                !newState.getFluidState().isEmpty()
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
        int x = position.getX() & 15;
        int y = position.getY() & 15;
        int z = position.getZ() & 15;
        if (x == 0) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.WEST));
        } else if (x == 15) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.EAST));
        }
        if (y == 0) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.DOWN));
        } else if (y == 15) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.UP));
        }
        if (z == 0) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.NORTH));
        } else if (z == 15) {
            service.invalidate(service.offsetKey(serverLevel.dimension(), section, Direction.SOUTH));
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
                                             VoxelShape newShape,
                                             boolean newContainsFluid) {
        Objects.requireNonNull(oldShape, "oldShape");
        Objects.requireNonNull(newShape, "newShape");
        if (oldContainsFluid != newContainsFluid) {
            return true;
        }
        return Shapes.joinIsNotEmpty(oldShape, newShape, BooleanOp.NOT_SAME);
    }

    static int classifyCell(BlockState state, VoxelShape collisionShape, boolean supportBelow) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(collisionShape, "collisionShape");
        boolean fluid = !state.getFluidState().isEmpty();
        int flags = fluid ? BaseClusterTopology.FLUID : 0;
        if (Block.isShapeFullBlock(collisionShape)) {
            return flags;
        }

        flags |= BaseClusterTopology.VOLUME_OPEN;
        if (!collisionShape.isEmpty() || supportBelow || fluid) {
            flags |= BaseClusterTopology.GROUND_OPEN;
        }
        if (state.getBlock().hasDynamicShape() || !collisionShape.isEmpty()) {
            flags |= BaseClusterTopology.EXACT_REQUIRED;
        }
        return flags;
    }

    TopologySubscription<BaseClusterTopology> subscribeClusterDependency(
            ServerLevel level,
            SectionPos section,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(section, "section");
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
        if (entry.topology != null && entry.topology.revision() == entry.revision) {
            return completedSubscription(entry.topology, priority);
        }
        TopologyDemand demand = entry.demand;
        if (demand == null) {
            demand = new TopologyDemand(
                    key,
                    entry.revision,
                    ++demandSequence,
                    System.nanoTime()
            );
            entry.demand = demand;
        }
        TopologySubscription<BaseClusterTopology> subscription =
                new TopologySubscription<>(priority);
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
        if (demand.snapshotFuture != null && previous != demand.priority) {
            NavigationScheduler.forServer(level.getServer()).promote(
                    level.dimension(),
                    snapshotOwner(key),
                    demand.priority
            );
        }
        if (demand.buildTask != UNTRACKED_TASK && previous != demand.priority) {
            demand.buildTask.promote(demand.priority);
        }
        if (!demand.queued && !demand.dependencyPermit) {
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
        SuperClusterTopology ready = currentSuperTopology(key, entry);
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
        Map<SectionPos, BaseClusterTopology> childSnapshot = currentChildTopologies(key);
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
                                   Map<SectionPos, BaseClusterTopology> childSnapshot) {
        long started = System.nanoTime();
        SuperClusterTopology topology;
        try {
            topology = SuperClusterTopology.build(
                    key.origin(),
                    SuperClusterTopology.childSections(key.origin()).stream()
                            .map(childSnapshot::get)
                            .toList(),
                    key.channel(),
                    key.profile()
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
                                     Map<SectionPos, BaseClusterTopology> childSnapshot,
                                     SuperClusterTopology topology) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt || closed
                || entry.waiters.isEmpty()) {
            staleSuperBuilds.increment();
            return;
        }
        Map<SectionPos, BaseClusterTopology> current = currentChildTopologies(key);
        if (current == null || !sameChildTopologies(childSnapshot, current)
                || !topology.matchesChildren(current)) {
            staleSuperBuilds.increment();
            entry.attemptRunning = false;
            entry.buildTask = null;
            ServerLevel level = server == null ? null : server.getLevel(key.dimension());
            if (level == null || !superClusterAvailable(level, key.origin())) {
                failSuperRequest(key, entry, new StaleTopologyException(
                        new ClusterKey(key.dimension(), key.origin())
                ));
            } else {
                beginSuperRequest(level, key, entry);
            }
            return;
        }

        removeSuperTopology(entry);
        entry.topology = topology;
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
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        BaseBoundaryCacheKey key = new BaseBoundaryCacheKey(
                dimension,
                source,
                target,
                face,
                channel,
                profile
        );
        LinkEntry<SuperClusterTopology.BoundaryLinks> existing = baseBoundaryLinks.get(key);
        if (existing != null) {
            existing.promote(priority);
            return existing.future;
        }

        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = new LinkEntry<>(priority);
        baseBoundaryLinks.put(key, entry);
        baseBoundaryBuildRequests.increment();
        try {
            entry.track(buildWorker.submit(
                    key.dimension(),
                    priority,
                    () -> buildBaseBoundaryLinks(key, entry)
            ));
        } catch (RejectedExecutionException failure) {
            baseBoundaryLinks.remove(key, entry);
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
                    key.face(),
                    key.channel(),
                    key.profile()
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
        if (closed || baseBoundaryLinks.get(key) != expected || !baseBoundaryKeyCurrent(key)) {
            baseBoundaryLinks.remove(key, expected);
            expected.future.completeExceptionally(new StaleTopologyException(
                    "base boundary topology changed while links were building"
            ));
            return;
        }
        expected.value = links;
        expected.task = UNTRACKED_TASK;
        baseBoundaryRetainedBytes.addAndGet(links.retainedBytes());
        expected.future.complete(links);
        evictBaseBoundaryLinks();
    }

    private void failBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected,
            RuntimeException failure) {
        requireOwnerThread();
        baseBoundaryLinks.remove(key, expected);
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
        LinkEntry<SuperClusterTopology.CrossingIndex> existing = superBoundaryLinks.get(key);
        if (existing != null) {
            existing.promote(priority);
            return existing.future;
        }

        LinkEntry<SuperClusterTopology.CrossingIndex> entry = new LinkEntry<>(priority);
        superBoundaryLinks.put(key, entry);
        superBoundaryBuildRequests.increment();
        try {
            entry.track(buildWorker.submit(
                    key.dimension(),
                    priority,
                    () -> buildSuperBoundaryLinks(key, entry)
            ));
        } catch (RejectedExecutionException failure) {
            superBoundaryLinks.remove(key, entry);
            entry.future.completeExceptionally(failure);
        }
        return entry.future;
    }

    private void buildSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected) {
        long started = System.nanoTime();
        SuperClusterTopology.CrossingIndex links;
        try {
            links = key.source().crossingIndex(key.face(), key.target());
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
        if (closed || superBoundaryLinks.get(key) != expected || !superBoundaryKeyCurrent(key)) {
            superBoundaryLinks.remove(key, expected);
            expected.future.completeExceptionally(new StaleTopologyException(
                    "super boundary topology changed while links were building"
            ));
            return;
        }
        expected.value = links;
        expected.task = UNTRACKED_TASK;
        superBoundaryRetainedBytes.addAndGet(links.retainedBytes());
        expected.future.complete(links);
        evictSuperBoundaryLinks();
    }

    private void failSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            RuntimeException failure) {
        requireOwnerThread();
        superBoundaryLinks.remove(key, expected);
        expected.future.completeExceptionally(failure);
    }

    @Nullable
    private SuperClusterTopology.BoundaryLinks readyBaseBoundaryLinks(BaseBoundaryCacheKey key) {
        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = baseBoundaryLinks.get(key);
        if (entry == null || entry.value == null) {
            baseBoundaryMisses.increment();
            return null;
        }
        baseBoundaryHits.increment();
        return entry.value;
    }

    @Nullable
    private SuperClusterTopology.CrossingIndex readySuperBoundaryLinks(
            SuperBoundaryCacheKey key) {
        LinkEntry<SuperClusterTopology.CrossingIndex> entry = superBoundaryLinks.get(key);
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
        return source != null && source.topology == key.source()
                && target != null && target.topology == key.target();
    }

    private boolean superBoundaryKeyCurrent(SuperBoundaryCacheKey key) {
        SuperEntry source = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.source().origin(),
                key.source().channel(),
                key.source().profile()
        ));
        SuperEntry target = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.target().origin(),
                key.target().channel(),
                key.target().profile()
        ));
        return source != null && source.topology == key.source()
                && target != null && target.topology == key.target();
    }

    private void evictBaseBoundaryLinks() {
        Iterator<Map.Entry<BaseBoundaryCacheKey,
                LinkEntry<SuperClusterTopology.BoundaryLinks>>> iterator =
                baseBoundaryLinks.entrySet().iterator();
        while (baseBoundaryLinks.size() > MAX_BASE_BOUNDARY_LINK_ENTRIES
                && iterator.hasNext()) {
            LinkEntry<SuperClusterTopology.BoundaryLinks> entry = iterator.next().getValue();
            if (entry.value == null) {
                continue;
            }
            iterator.remove();
            baseBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            baseBoundaryEvictions.increment();
        }
    }

    private void evictSuperBoundaryLinks() {
        Iterator<Map.Entry<SuperBoundaryCacheKey,
                LinkEntry<SuperClusterTopology.CrossingIndex>>> iterator =
                superBoundaryLinks.entrySet().iterator();
        while (superBoundaryLinks.size() > MAX_SUPER_BOUNDARY_LINK_ENTRIES
                && iterator.hasNext()) {
            LinkEntry<SuperClusterTopology.CrossingIndex> entry = iterator.next().getValue();
            if (entry.value == null) {
                continue;
            }
            iterator.remove();
            superBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            superBoundaryEvictions.increment();
        }
    }

    private void startRequestedBuilds() {
        requireOwnerThread();
        ensureOpen();
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        int queuedAtTickEnd = requestedBuilds.size();
        for (int index = 0; index < queuedAtTickEnd; index++) {
            TopologyDemand demand = requestedBuilds.poll(
                    System.nanoTime(),
                    candidate -> candidate.dependencyPermit
                            || dependencyPermits < MAX_DEPENDENCY_DEMANDS
            );
            if (demand == null) {
                break;
            }
            ClusterKey key = demand.key;
            ClusterEntry entry = clusters.get(key);
            if (entry == null || entry.demand != demand || demand.waiters.isEmpty()) {
                continue;
            }
            if (entry.topology != null && entry.topology.revision() == entry.revision) {
                completeDemand(entry, demand, entry.topology);
                continue;
            }
            if (demand.snapshotFuture != null || demand.buildTask != UNTRACKED_TASK) {
                continue;
            }
            if (!demand.dependencyPermit && !acquireDependencyPermit(demand)) {
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
            startSnapshotAttempt(level, entry, demand, chunk);
            if (scheduler.admissionCapacity().freeSlots() == 0) {
                dependencyStartsAtFullOrdinaryAdmission.increment();
            }
        }
    }

    private void startSnapshotAttempt(ServerLevel level,
                                      ClusterEntry entry,
                                      TopologyDemand demand,
                                      LevelChunk chunk) {
        entry.dirty = false;
        ClusterKey key = demand.key;
        SnapshotSearch snapshotSearch = worldSnapshot(level, key.section(), chunk);
        NavigationScheduler scheduler = NavigationScheduler.forServer(level.getServer());
        CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture = scheduler.submitDependency(
                level.dimension(), snapshotOwner(key), demand.priority, snapshotSearch);
        demand.snapshotFuture = snapshotFuture;
        snapshotFuture.whenComplete((snapshot, failure) -> publisher.execute(() -> completeSnapshotAttempt(
                entry,
                demand,
                snapshotSearch,
                snapshotFuture,
                snapshot,
                failure
        )));
    }

    private void completeSnapshotAttempt(ClusterEntry entry,
                                         TopologyDemand demand,
                                         SnapshotSearch snapshotSearch,
                                         CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture,
                                         @Nullable BaseClusterTopology.Snapshot snapshot,
                                         @Nullable Throwable failure) {
        requireOwnerThread();
        snapshotCells.add(snapshotSearch.sampledCells());
        snapshotNanos.add(snapshotSearch.spentNanos());
        if (clusters.get(demand.key) != entry || entry.demand != demand
                || demand.snapshotFuture != snapshotFuture) {
            return;
        }
        demand.snapshotFuture = null;
        if (closed || demand.waiters.isEmpty()) {
            return;
        }
        if (failure != null || snapshot == null || entry.revision != demand.generation) {
            if (entry.revision != demand.generation || retryableAttemptFailure(failure)) {
                entry.dirty = true;
                enqueueRequestedBuild(demand);
            } else {
                failDemand(entry, demand, failure != null
                        ? failure
                        : new IllegalStateException("snapshot search failed"));
            }
            return;
        }
        demand.fingerprint = snapshot.fingerprint();
        buildRequests.increment();
        try {
            if (store == null) {
                submitDemandBuild(demand, snapshot, null, BuildOrigin.FRESH_BUILD);
            } else {
                demand.loadPending = true;
                store.read(demand.key.dimension(), demand.key.section())
                        .whenComplete((stored, readFailure) -> publisher.execute(
                                () -> completeStoredTopologyRead(
                                        entry,
                                        demand,
                                        snapshot,
                                        stored == null ? null : stored.orElse(null),
                                        readFailure
                                )
                        ));
            }
        } catch (RuntimeException exception) {
            failDemand(entry, demand, exception);
        }
    }

    private void completeStoredTopologyRead(ClusterEntry entry,
                                            TopologyDemand demand,
                                            BaseClusterTopology.Snapshot snapshot,
                                            @Nullable BaseClusterTopology.PackedFacts stored,
                                            @Nullable Throwable readFailure) {
        requireOwnerThread();
        if (closed || clusters.get(demand.key) != entry || entry.demand != demand
                || entry.revision != demand.generation) {
            return;
        }
        demand.loadPending = false;
        if (readFailure != null) {
            AcceleratedNavigation.LOGGER.warn(
                    "Could not read macro topology for {}",
                    demand.key,
                    readFailure
            );
        }
        boolean persistenceHit = stored != null
                && stored.fingerprint() == snapshot.fingerprint();
        submitDemandBuild(
                demand,
                snapshot,
                persistenceHit ? stored : null,
                persistenceHit ? BuildOrigin.PERSISTENCE_HIT : BuildOrigin.FRESH_BUILD
        );
    }

    private void submitDemandBuild(TopologyDemand demand,
                                   BaseClusterTopology.Snapshot snapshot,
                                   @Nullable BaseClusterTopology.PackedFacts stored,
                                   BuildOrigin origin) {
        demand.loadPending = false;
        try {
            demand.buildTask = buildWorker.submit(
                    demand.key.dimension(),
                    demand.priority,
                    () -> build(demand, snapshot, stored, origin)
            );
        } catch (RejectedExecutionException exception) {
            ClusterEntry entry = clusters.get(demand.key);
            if (entry != null && entry.demand == demand) {
                failDemand(entry, demand, exception);
            }
        }
    }

    public void invalidate(ClusterKey key) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        ClusterEntry entry = clusters.get(key);
        if (entry == null) {
            return;
        }
        if (entry.dirty && entry.topology == null
                && (entry.demand == null || entry.demand.queued)) {
            coalescedInvalidations.increment();
            return;
        }
        entry.revision++;
        entry.dirty = true;
        if (entry.topology != null) {
            invalidateBaseBoundaryLinks(entry.topology);
            retainedBytes.addAndGet(-entry.topology.retainedBytes());
            entry.topology = null;
            topologyChanged();
        }
        if (entry.demand != null) {
            TopologyDemand stale = entry.demand;
            cancelDemandWork(stale);
            TopologyDemand replacement = new TopologyDemand(
                    key,
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
            entry.demand = replacement;
            enqueueRequestedBuild(replacement);
        }
        invalidateSuperParent(key, false);
    }

    @Nullable
    public BaseClusterTopology topology(ClusterKey key) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(Objects.requireNonNull(key, "key"));
        return entry == null ? null : entry.topology;
    }

    public long revision(ClusterKey key) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(Objects.requireNonNull(key, "key"));
        return entry == null ? 0L : entry.revision;
    }

    MacroSearch.Graph graph(ResourceKey<Level> dimension,
                            BlockPos start,
                            BlockPos goal,
                            BaseClusterTopology.Channel channel) {
        return graph(
                dimension,
                start,
                goal,
                channel,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
        );
    }

    public boolean isCurrent(ResourceKey<Level> dimension, MacroSearch.Corridor corridor) {
        requireOwnerThread();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(corridor, "corridor");
        for (MacroSearch.Endpoint endpoint : corridor.endpoints()) {
            SectionPos section = endpoint instanceof MacroSearch.ComponentEndpoint component
                    ? component.section()
                    : SectionPos.of(endpoint.anchor());
            ClusterEntry entry = clusters.get(new ClusterKey(dimension, section));
            if (entry == null || entry.topology == null
                    || entry.topology.revision() != endpoint.revision()) {
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
        if (server == null || level.getServer() != server) {
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

    private static boolean shouldUseSuperGraph(BlockPos start, BlockPos goal) {
        SectionPos startOrigin = SuperClusterTopology.originOf(SectionPos.of(start));
        SectionPos goalOrigin = SuperClusterTopology.originOf(SectionPos.of(goal));
        int dx = Math.abs(goalOrigin.x() - startOrigin.x())
                / SuperClusterTopology.CHILDREN_PER_AXIS;
        int dy = Math.abs(goalOrigin.y() - startOrigin.y())
                / SuperClusterTopology.CHILDREN_PER_AXIS;
        int dz = Math.abs(goalOrigin.z() - startOrigin.z())
                / SuperClusterTopology.CHILDREN_PER_AXIS;
        return Math.max(dx, Math.max(dy, dz)) >= MIN_SUPER_CLUSTER_DISTANCE;
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

    MacroSearch.Graph graph(ResourceKey<Level> dimension,
                            BlockPos start,
                            BlockPos goal,
                            BaseClusterTopology.Channel channel,
                            BaseClusterTopology.TraversalProfile profile) {
        requireOwnerThread();
        ensureOpen();
        return new TopologyGraph(
                Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(start, "start").immutable(),
                Objects.requireNonNull(goal, "goal").immutable(),
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(profile, "profile")
        );
    }

    public Metrics metrics() {
        int dependencyDemands = 0;
        int queuedDependencyDemands = 0;
        int topologyWaiters = 0;
        for (ClusterEntry entry : clusters.values()) {
            if (entry.demand == null) {
                continue;
            }
            topologyWaiters += entry.demand.waiters.size();
            dependencyDemands++;
            if (entry.demand.queued) {
                queuedDependencyDemands++;
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
                superBuildRequests.sum(),
                superBuildNanos.sum(),
                publishedSuperClusters.sum(),
                staleSuperBuilds.sum(),
                evictedSuperClusters.sum(),
                superClusters.size(),
                superRetainedBytes.get(),
                workerMetrics(ownedBuildWorker),
                persistenceWorkerMetrics(persistence),
                persistenceMetrics(persistence),
                dependencyPermits,
                dependencyPermitHighWatermark,
                dependencyDemands,
                queuedDependencyDemands,
                topologyWaiters,
                dependencyStartsAtFullOrdinaryAdmission.sum(),
                topologyEpoch,
                new LinkCacheMetrics(
                        baseBoundaryBuildRequests.sum(),
                        baseBoundaryBuildNanos.sum(),
                        baseBoundaryHits.sum(),
                        baseBoundaryMisses.sum(),
                        baseBoundaryEvictions.sum(),
                        baseBoundaryLinks.size(),
                        baseBoundaryRetainedBytes.get()
                ),
                new LinkCacheMetrics(
                        superBoundaryBuildRequests.sum(),
                        superBoundaryBuildNanos.sum(),
                        superBoundaryHits.sum(),
                        superBoundaryMisses.sum(),
                        superBoundaryEvictions.sum(),
                        superBoundaryLinks.size(),
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
                )
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
        for (LinkEntry<SuperClusterTopology.BoundaryLinks> entry : baseBoundaryLinks.values()) {
            entry.task.cancel();
            entry.future.completeExceptionally(stopped);
        }
        for (LinkEntry<SuperClusterTopology.CrossingIndex> entry : superBoundaryLinks.values()) {
            entry.task.cancel();
            entry.future.completeExceptionally(stopped);
        }
        baseBoundaryLinks.clear();
        superBoundaryLinks.clear();
        for (Map.Entry<ClusterKey, ClusterEntry> cluster : clusters.entrySet()) {
            ClusterEntry entry = cluster.getValue();
            if (entry.demand != null) {
                failDemand(entry, entry.demand, stopped);
            }
            entry.topology = null;
        }
        for (SuperEntry entry : superClusters.values()) {
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
        requestedBuilds.clear();
        dependencyPermits = 0;
        retainedBytes.set(0L);
        superRetainedBytes.set(0L);
        baseBoundaryRetainedBytes.set(0L);
        superBoundaryRetainedBytes.set(0L);
        if (ownedBuildWorker != null) {
            ownedBuildWorker.shutdown();
        }
        if (store != null) {
            store.close();
        }
    }

    private void build(TopologyDemand demand,
                       BaseClusterTopology.Snapshot freshSnapshot,
                       @Nullable BaseClusterTopology.PackedFacts stored,
                       BuildOrigin origin) {
        long started = System.nanoTime();
        BaseClusterTopology topology;
        BaseClusterTopology.Snapshot snapshot;
        try {
            snapshot = stored == null ? freshSnapshot : stored.snapshot();
            topology = BaseClusterTopology.build(
                    demand.key.section(),
                    demand.generation,
                    snapshot
            );
        } catch (RuntimeException exception) {
            buildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failBuild(demand, exception));
            return;
        }
        buildNanos.add(System.nanoTime() - started);
        publisher.execute(() -> publish(demand, topology, freshSnapshot, origin));
    }

    private void publish(TopologyDemand demand,
                         BaseClusterTopology topology,
                         BaseClusterTopology.Snapshot freshSnapshot,
                         BuildOrigin origin) {
        requireOwnerThread();
        ClusterKey key = demand.key;
        ClusterEntry entry = clusters.get(key);
        demand.buildTask = UNTRACKED_TASK;
        if (closed || entry == null || entry.revision != demand.generation
                || entry.demand != demand
                || topology.sourceFingerprint() != demand.fingerprint) {
            staleBuilds.increment();
            return;
        }

        BaseClusterTopology replaced = entry.topology;
        if (replaced != null) {
            invalidateBaseBoundaryLinks(replaced);
            retainedBytes.addAndGet(-replaced.retainedBytes());
        }
        entry.topology = topology;
        entry.dirty = false;
        retainedBytes.addAndGet(topology.retainedBytes());
        publishedClusters.increment();
        if (origin == BuildOrigin.PERSISTENCE_HIT) {
            persistenceHits.increment();
        } else {
            freshBuilds.increment();
        }
        topologyChanged();
        if (replaced != null) {
            invalidateSuperParent(key, false);
        }
        if (origin == BuildOrigin.FRESH_BUILD && store != null && !closed) {
            store.markDirty(key.dimension(), key.section(), freshSnapshot.packedFacts());
        }
        completeDemand(entry, demand, topology);
    }

    private void failBuild(TopologyDemand demand, RuntimeException exception) {
        requireOwnerThread();
        demand.buildTask = UNTRACKED_TASK;
        ClusterEntry entry = clusters.get(demand.key);
        if (entry != null && entry.demand == demand) {
            failDemand(entry, demand, exception);
        }
    }

    private void requireOwnerThread() {
        if (!ownerThread.getAsBoolean()) {
            throw new IllegalStateException("topology state must be accessed by its owner thread");
        }
    }

    private void enqueueRequestedBuild(TopologyDemand demand) {
        if (demand.waiters.isEmpty() || demand.queued || demand.snapshotFuture != null
                || demand.loadPending || demand.buildTask != UNTRACKED_TASK) {
            return;
        }
        requestedBuilds.add(demand);
    }

    private void completeDemand(ClusterEntry entry,
                                TopologyDemand demand,
                                BaseClusterTopology topology) {
        entry.demand = null;
        requestedBuilds.remove(demand);
        releaseDependencyPermit(demand);
        for (TopologySubscription<BaseClusterTopology> waiter : List.copyOf(demand.waiters)) {
            waiter.active = false;
            waiter.demand = null;
            waiter.future().complete(topology);
        }
        demand.waiters.clear();
    }

    private void failDemand(ClusterEntry entry, TopologyDemand demand, Throwable failure) {
        if (entry.demand == demand) {
            entry.demand = null;
        }
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
            if (entry != null && entry.demand == demand) {
                entry.demand = null;
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
        if (previous != demand.priority && demand.snapshotFuture != null && server != null) {
            NavigationScheduler.forServer(server).reprioritize(
                    demand.key.dimension(),
                    snapshotOwner(demand.key),
                    demand.priority
            );
        }
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
        if (demand.snapshotFuture != null && server != null && previous != demand.priority) {
            NavigationScheduler.forServer(server).reprioritize(
                    demand.key.dimension(),
                    snapshotOwner(demand.key),
                    demand.priority
            );
        }
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
        if (demand.snapshotFuture != null && server != null) {
            NavigationScheduler.forServer(server).qualifyDependency(
                    demand.key.dimension(),
                    snapshotOwner(demand.key),
                    demand.priority
            );
        }
        return true;
    }

    private void releaseDependencyPermit(TopologyDemand demand) {
        if (!demand.dependencyPermit) {
            return;
        }
        demand.dependencyPermit = false;
        dependencyPermits--;
        if (dependencyPermits < 0) {
            throw new IllegalStateException("topology dependency permit count became negative");
        }
    }

    private void cancelDemandWork(TopologyDemand demand) {
        requestedBuilds.remove(demand);
        if (demand.snapshotFuture != null && server != null) {
            CompletableFuture<BaseClusterTopology.Snapshot> snapshot = demand.snapshotFuture;
            demand.snapshotFuture = null;
            NavigationScheduler.forServer(server).cancel(
                    demand.key.dimension(),
                    snapshotOwner(demand.key)
            );
            snapshot.cancel(false);
        }
        demand.loadPending = false;
        demand.buildTask.cancel();
        demand.buildTask = UNTRACKED_TASK;
        releaseDependencyPermit(demand);
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

    private ClusterKey offsetKey(ResourceKey<Level> dimension,
                                 SectionPos section,
                                 Direction direction) {
        return new ClusterKey(
                dimension,
                SectionPos.of(
                        section.x() + direction.getStepX(),
                        section.y() + direction.getStepY(),
                        section.z() + direction.getStepZ()
                )
        );
    }

    private static SnapshotSearch worldSnapshot(ServerLevel level,
                                                SectionPos section,
                                                LevelChunk centerChunk) {
        LoadedBlockGetter getter = new LoadedBlockGetter(level);
        Map<BlockState, VoxelShape> stableShapes = new IdentityHashMap<>();
        boolean[] collisions = new boolean[BaseClusterTopology.CELL_COUNT];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        return new SnapshotSearch(index -> {
            int x = index & 15;
            int z = index >>> 4 & 15;
            int y = index >>> 8;
            position.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
            BlockState state = centerChunk.getBlockState(position);
            VoxelShape shape = collisionShape(state, getter, position, stableShapes);
            boolean supportBelow;
            if (y > 0) {
                supportBelow = collisions[index - 256];
            } else {
                BlockPos below = position.below();
                BlockState support = getter.getBlockState(below);
                VoxelShape supportShape = collisionShape(support, getter, below, stableShapes);
                supportBelow = !supportShape.isEmpty();
            }
            collisions[index] = !shape.isEmpty();
            return classifyCell(state, shape, supportBelow);
        });
    }

    private static VoxelShape collisionShape(BlockState state,
                                             BlockGetter getter,
                                             BlockPos position,
                                             Map<BlockState, VoxelShape> stableShapes) {
        if (state.getBlock().hasDynamicShape()) {
            return state.getCollisionShape(getter, position);
        }
        return stableShapes.computeIfAbsent(
                state,
                key -> key.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        );
    }

    private void evictChunk(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireOwnerThread();
        if (store != null && !closed) {
            store.unload(dimension, chunk);
        }
        evictSuperChunkParents(dimension, chunk);
        Iterator<Map.Entry<ClusterKey, ClusterEntry>> iterator = clusters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ClusterKey, ClusterEntry> cluster = iterator.next();
            ClusterKey key = cluster.getKey();
            if (!key.dimension().equals(dimension)
                    || key.section().x() != chunk.x || key.section().z() != chunk.z) {
                continue;
            }
            ClusterEntry entry = cluster.getValue();
            if (entry.topology != null) {
                invalidateBaseBoundaryLinks(entry.topology);
                retainedBytes.addAndGet(-entry.topology.retainedBytes());
                topologyChanged();
            }
            if (entry.demand != null) {
                failDemand(entry, entry.demand, new StaleTopologyException(key));
            }
            iterator.remove();
        }
    }

    @Nullable
    private SuperClusterTopology currentSuperTopology(SuperCacheKey key, SuperEntry entry) {
        return entry.topology;
    }

    @Nullable
    private SuperClusterTopology superTopology(SuperCacheKey key) {
        requireOwnerThread();
        SuperEntry entry = superClusters.get(key);
        return entry == null ? null : currentSuperTopology(key, entry);
    }

    @Nullable
    private Map<SectionPos, BaseClusterTopology> currentChildTopologies(SuperCacheKey key) {
        Map<SectionPos, BaseClusterTopology> result = new HashMap<>();
        for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
            ClusterEntry entry = clusters.get(new ClusterKey(key.dimension(), child));
            if (entry == null || entry.topology == null
                    || entry.topology.revision() != entry.revision) {
                return null;
            }
            result.put(child, entry.topology);
        }
        return Map.copyOf(result);
    }

    private static boolean sameChildTopologies(Map<SectionPos, BaseClusterTopology> first,
                                               Map<SectionPos, BaseClusterTopology> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (Map.Entry<SectionPos, BaseClusterTopology> child : first.entrySet()) {
            BaseClusterTopology other = second.get(child.getKey());
            if (other != child.getValue()) {
                return false;
            }
        }
        return true;
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
        Iterator<Map.Entry<SuperCacheKey, SuperEntry>> iterator = superClusters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SuperCacheKey, SuperEntry> cached = iterator.next();
            SuperCacheKey key = cached.getKey();
            if (!key.dimension().equals(child.dimension()) || !key.origin().equals(origin)) {
                continue;
            }
            SuperEntry entry = cached.getValue();
            removeSuperTopology(entry);
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
                iterator.remove();
            } else if (!entry.waiters.isEmpty()) {
                restart.add(Map.entry(key, entry));
            } else {
                iterator.remove();
            }
        }
        if (server == null) {
            return;
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
        Iterator<Map.Entry<SuperCacheKey, SuperEntry>> iterator = superClusters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SuperCacheKey, SuperEntry> cached = iterator.next();
            SuperCacheKey key = cached.getKey();
            if (!key.dimension().equals(dimension)
                    || chunk.x < key.origin().x()
                    || chunk.x >= key.origin().x() + SuperClusterTopology.CHILDREN_PER_AXIS
                    || chunk.z < key.origin().z()
                    || chunk.z >= key.origin().z() + SuperClusterTopology.CHILDREN_PER_AXIS) {
                continue;
            }
            SuperEntry entry = cached.getValue();
            removeSuperTopology(entry);
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
            iterator.remove();
        }
    }

    private void evictSuperCache() {
        if (superClusters.size() <= MAX_SUPER_CACHE_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<SuperCacheKey, SuperEntry>> iterator = superClusters.entrySet().iterator();
        while (superClusters.size() > MAX_SUPER_CACHE_ENTRIES && iterator.hasNext()) {
            SuperEntry entry = iterator.next().getValue();
            if (!entry.waiters.isEmpty() || entry.attemptRunning) {
                continue;
            }
            removeSuperTopology(entry);
            iterator.remove();
            evictedSuperClusters.increment();
        }
    }

    private void removeSuperTopology(SuperEntry entry) {
        if (entry.topology == null) {
            return;
        }
        SuperClusterTopology removed = entry.topology;
        int bytes = removed.retainedBytes();
        invalidateSuperBoundaryLinks(removed);
        entry.topology = null;
        retainedBytes.addAndGet(-bytes);
        superRetainedBytes.addAndGet(-bytes);
        topologyChanged();
    }

    private void invalidateBaseBoundaryLinks(BaseClusterTopology topology) {
        Iterator<Map.Entry<BaseBoundaryCacheKey,
                LinkEntry<SuperClusterTopology.BoundaryLinks>>> iterator =
                baseBoundaryLinks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BaseBoundaryCacheKey,
                    LinkEntry<SuperClusterTopology.BoundaryLinks>> cached = iterator.next();
            if (cached.getKey().source() != topology && cached.getKey().target() != topology) {
                continue;
            }
            iterator.remove();
            LinkEntry<SuperClusterTopology.BoundaryLinks> entry = cached.getValue();
            entry.task.cancel();
            if (entry.value != null) {
                baseBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            }
            entry.future.completeExceptionally(new StaleTopologyException(
                    "base topology changed while boundary links were retained"
            ));
        }
    }

    private void invalidateSuperBoundaryLinks(SuperClusterTopology topology) {
        Iterator<Map.Entry<SuperBoundaryCacheKey,
                LinkEntry<SuperClusterTopology.CrossingIndex>>> iterator =
                superBoundaryLinks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SuperBoundaryCacheKey,
                    LinkEntry<SuperClusterTopology.CrossingIndex>> cached = iterator.next();
            if (cached.getKey().source() != topology && cached.getKey().target() != topology) {
                continue;
            }
            iterator.remove();
            LinkEntry<SuperClusterTopology.CrossingIndex> entry = cached.getValue();
            entry.task.cancel();
            if (entry.value != null) {
                superBoundaryRetainedBytes.addAndGet(-entry.value.retainedBytes());
            }
            entry.future.completeExceptionally(new StaleTopologyException(
                    "super topology changed while boundary links were retained"
            ));
        }
    }

    private void topologyChanged() {
        topologyEpoch++;
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
                           long dependencyStartsAtFullOrdinaryAdmission,
                           long topologyEpoch,
                           LinkCacheMetrics baseBoundaryLinks,
                           LinkCacheMetrics superBoundaryLinks,
                           MacroQueryReuseMetrics macroQueries) {
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
        private BaseClusterTopology topology;
        private TopologyDemand demand;
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
        private final long generation;
        private final long sequence;
        private final long enqueuedNanos;
        private final Set<TopologySubscription<BaseClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private NavigationScheduler.Priority priority = NavigationScheduler.Priority.BACKGROUND;
        private CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture;
        private TopologyTaskExecutor.TaskHandle buildTask = UNTRACKED_TASK;
        private long fingerprint;
        private boolean dependencyPermit;
        private boolean loadPending;
        private boolean queued;

        private TopologyDemand(ClusterKey key,
                               long generation,
                               long sequence,
                               long enqueuedNanos) {
            this.key = key;
            this.generation = generation;
            this.sequence = sequence;
            this.enqueuedNanos = enqueuedNanos;
        }

    }

    final class TopologySubscription<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private NavigationScheduler.Priority priority;
        private TopologyDemand demand;
        private Runnable cancellation = () -> {
        };
        private Consumer<NavigationScheduler.Priority> reprioritization = ignored -> {
        };
        private boolean active = true;

        private TopologySubscription(NavigationScheduler.Priority priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
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
                    TopologyDemand candidate = dimension.peekFirst();
                    if (candidate == null || !eligible.test(candidate)) {
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
                    TopologyDemand candidate = queue == null ? null : queue.peekFirst();
                    if (candidate != null && eligible.test(candidate)
                            && effectiveRank(candidate, now) == rank
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
        private final Set<TopologySubscription<SuperClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private List<TopologySubscription<BaseClusterTopology>> children = List.of();
        private NavigationScheduler.Priority requestPriority;
        private TopologyTaskExecutor.TaskHandle buildTask;
    }

    private record SuperCacheKey(ResourceKey<Level> dimension,
                                 SectionPos origin,
                                 BaseClusterTopology.Channel channel,
                                 BaseClusterTopology.TraversalProfile profile) {
        private SuperCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(profile, "profile");
        }
    }

    private record BaseBoundaryCacheKey(
            ResourceKey<Level> dimension,
            BaseClusterTopology source,
            BaseClusterTopology target,
            Direction face,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile) {
        private BaseBoundaryCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(profile, "profile");
            if (!target.section().equals(SuperClusterTopology.offset(source.section(), face, 1))) {
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
            if (!target.origin().equals(SuperClusterTopology.offset(
                    source.origin(),
                    face,
                    SuperClusterTopology.CHILDREN_PER_AXIS
            )) || target.channel() != source.channel()
                    || !target.profile().equals(source.profile())) {
                throw new IllegalArgumentException("super boundary cache key is not compatible");
            }
        }
    }

    private static final class LinkEntry<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile NavigationScheduler.Priority priority;
        private TopologyTaskExecutor.TaskHandle task = UNTRACKED_TASK;
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
            }
        }

    }

    @FunctionalInterface
    private interface WorkDispatcher {
        TopologyTaskExecutor.TaskHandle submit(ResourceKey<Level> dimension,
                                                NavigationScheduler.Priority priority,
                                                Runnable command);
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
        private final boolean hierarchical;
        private final CompletableFuture<MacroSearch.Corridor> future = new CompletableFuture<>();
        private List<TopologySubscription<BaseClusterTopology>> endpointSubscriptions = List.of();

        private boolean active = true;
        private boolean completedFromCache;
        private long resolveAttempt;
        private MacroFlight flight;
        private MacroSearch.Failure failure = MacroSearch.Failure.NONE;
        private SectionPos blockedSection;
        private QueryMetrics metrics;

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
            this.hierarchical = shouldUseSuperGraph(startPosition, goalPosition)
                    && superClusterHeightAvailable(
                            level,
                            SuperClusterTopology.originOf(SectionPos.of(startPosition))
                    )
                    && superClusterHeightAvailable(
                            level,
                            SuperClusterTopology.originOf(SectionPos.of(goalPosition))
                    );
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
                subscriptions.add(subscribeClusterDependency(level, section, priority));
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
            BaseClusterTopology startTopology = topology(startKey);
            BaseClusterTopology goalTopology = topology(goalKey);
            if (startTopology == null || goalTopology == null) {
                beginResolve();
                return;
            }
            BaseClusterTopology.Component startComponent = bindComponent(
                    startTopology,
                    startPosition,
                    channel
            );
            BaseClusterTopology.Component goalComponent = bindComponent(
                    goalTopology,
                    goalPosition,
                    channel
            );
            if (startComponent == null || goalComponent == null) {
                failure = MacroSearch.Failure.NO_STRUCTURAL_ROUTE;
                blockedSection = startComponent == null ? startKey.section() : goalKey.section();
                finish(null, null, emptyQueryMetrics(hierarchical));
                return;
            }
            MacroQueryKey key = new MacroQueryKey(
                    level.dimension(),
                    channel,
                    profile,
                    hierarchical,
                    Float.floatToRawIntBits(weight),
                    queryNodeBudget(startPosition, goalPosition, hierarchical),
                    new MacroComponentKey(
                            startKey.section(),
                            startComponent.id(),
                            startTopology.revision()
                    ),
                    new MacroComponentKey(
                            goalKey.section(),
                            goalComponent.id(),
                            goalTopology.revision()
                    )
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
                    representative.weight
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
                bytes += 32L + boundary.bands().size() * 48L;
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
                && SectionPos.of(start.anchor()).equals(key.start.section)
                && start.revision() == key.start.revision
                && SectionPos.of(goal.anchor()).equals(key.goal.section)
                && goal.revision() == key.goal.revision;
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

    @Nullable
    private static BaseClusterTopology.Component bindComponent(
            BaseClusterTopology topology,
            BlockPos position,
            BaseClusterTopology.Channel channel) {
        return topology.nearestComponent(
                channel,
                Math.floorMod(position.getX(), BaseClusterTopology.SIDE),
                Math.floorMod(position.getY(), BaseClusterTopology.SIDE),
                Math.floorMod(position.getZ(), BaseClusterTopology.SIDE),
                2
        );
    }

    private static boolean staleTopologyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StaleTopologyException) {
                return true;
            }
        }
        return false;
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

    private record MacroComponentKey(SectionPos section, int componentId, long revision) {
    }

    private record MacroQueryKey(ResourceKey<Level> dimension,
                                 BaseClusterTopology.Channel channel,
                                 BaseClusterTopology.TraversalProfile profile,
                                 boolean hierarchical,
                                 int weightBits,
                                 int nodeBudget,
                                 MacroComponentKey start,
                                 MacroComponentKey goal) {
    }

    private record CachedCorridor(MacroSearch.Corridor corridor, long retainedBytes) {
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
        private final boolean hierarchical;
        private final Map<MacroSearch.DependencyKey, DependencyRequest> requests = new HashMap<>();

        private Status status = Status.RUNNING;
        private MacroSearch.Failure failure = MacroSearch.Failure.NONE;
        private MacroSearch search;
        private MacroSearch superSearch;
        private SuperTopologyGraph superGraph;
        private boolean refining;
        private MacroSearch.Corridor result;
        private SectionPos blockedEndpoint;
        private Runnable wakeup;
        private Throwable buildFailure;
        private boolean waitingForBuild;
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
                           float weight) {
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
            this.hierarchical = shouldUseSuperGraph(startPosition, goalPosition)
                    && superClusterHeightAvailable(
                            level,
                            SuperClusterTopology.originOf(SectionPos.of(startPosition))
                    )
                    && superClusterHeightAvailable(
                            level,
                            SuperClusterTopology.originOf(SectionPos.of(goalPosition))
                    );
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
                    throw new IllegalStateException("macro topology build failed", buildFailure);
                }
                waitingForBuild = false;
                if (search == null && !prepareEndpoints()) {
                    return status;
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
                                profile
                        );
                        search = new MacroSearch(superGraph, weight, maxVisitedNodes);
                    } else {
                        search = new MacroSearch(
                                graph(level.dimension(), startPosition, goalPosition, channel, profile),
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
                            TopologyGraph baseGraph = new TopologyGraph(
                                    level.dimension(),
                                    startPosition,
                                    goalPosition,
                                    channel,
                                    profile
                            );
                            search = new MacroSearch(
                                    new CorridorTopologyGraph(baseGraph, superGraph, candidate),
                                    weight,
                                    queryNodeBudget(
                                            startPosition,
                                            goalPosition,
                                            hierarchical
                                    )
                            );
                            refining = true;
                        }
                    } else if (candidate != null && isCurrent(level.dimension(), candidate)) {
                        result = candidate;
                        status = Status.SUCCEEDED;
                        clearRequests();
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

        private void restartStaleSearch() {
            clearRequests();
            search = null;
            superSearch = null;
            superGraph = null;
            refining = false;
            result = null;
            failure = MacroSearch.Failure.NONE;
            blockedEndpoint = null;
            waitingForBuild = false;
            staleRestarts++;
        }

        private boolean prepareEndpoints() {
            boolean ready = true;
            if (hierarchical && !refining) {
                SectionPos startOrigin = SuperClusterTopology.originOf(SectionPos.of(startPosition));
                SectionPos goalOrigin = SuperClusterTopology.originOf(SectionPos.of(goalPosition));
                for (SectionPos origin : startOrigin.equals(goalOrigin)
                        ? List.of(startOrigin)
                        : List.of(startOrigin, goalOrigin)) {
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
                        failure = MacroSearch.Failure.UNAVAILABLE_CHUNK;
                        blockedEndpoint = origin;
                        status = Status.FAILED;
                        clearRequests();
                        return false;
                    }
                    requestDependency(MacroSearch.Dependency.superCluster(
                            origin,
                            MacroSearch.Availability.PENDING
                    ));
                }
                waitingForBuild = !ready && !requests.isEmpty();
                return ready;
            }

            SectionPos startSection = SectionPos.of(startPosition);
            SectionPos goalSection = SectionPos.of(goalPosition);
            for (SectionPos section : startSection.equals(goalSection)
                    ? List.of(startSection)
                    : List.of(startSection, goalSection)) {
                ClusterKey key = new ClusterKey(level.dimension(), section);
                if (topology(key) != null) {
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
                )) != null;
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
            ));
            BaseClusterTopology target = topology(new ClusterKey(
                    level.dimension(),
                    dependency.target()
            ));
            return source == null || target == null
                    ? null
                    : new BaseBoundaryCacheKey(
                            level.dimension(),
                            source,
                            target,
                            dependency.face(),
                            channel,
                            profile
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
                            subscribeClusterDependency(level, key.position(), priority);
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
                                    boundary.channel(),
                                    boundary.profile(),
                                    priority
                            );
                    request = new DependencyRequest(future, () -> {
                    }, requested -> {
                        LinkEntry<SuperClusterTopology.BoundaryLinks> entry =
                                boundary == null ? null : baseBoundaryLinks.get(boundary);
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
                                boundary == null ? null : superBoundaryLinks.get(boundary);
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
                if (!isStaleFailure(requestFailure)
                        && dependencyAvailableInWorld(dependency)) {
                    buildFailure = requestFailure;
                }
            }
            if (search != null) {
                search.dependencyAvailable(dependency);
            }
            waitingForBuild = false;
            signalWakeup();
        }

        private void clearRequests() {
            if (requests.isEmpty()) {
                return;
            }
            List<DependencyRequest> active = List.copyOf(requests.values());
            requests.clear();
            active.forEach(DependencyRequest::cancel);
            waitingForBuild = false;
        }

        private boolean isStaleFailure(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof StaleTopologyException) {
                    return true;
                }
            }
            return false;
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
            MacroSearch.Metrics refinementMetrics = refining && search != null
                    ? search.metrics()
                    : !hierarchical && search != null ? search.metrics() : null;
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
        if (server == null) {
            return true;
        }
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
        private final SuperCacheKey startCluster;
        private final SuperCacheKey goalCluster;
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
        private final AggregateBinding startBinding;
        private final AggregateBinding goalBinding;
        private long nextEndpointId = 2L;
        private long validatedEpoch;

        private SuperTopologyGraph(ResourceKey<Level> dimension,
                                   BlockPos startPosition,
                                   BlockPos goalPosition,
                                   BaseClusterTopology.Channel channel,
                                   BaseClusterTopology.TraversalProfile profile) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.startCluster = key(SuperClusterTopology.originOf(SectionPos.of(startPosition)));
            this.goalCluster = key(SuperClusterTopology.originOf(SectionPos.of(goalPosition)));

            CapturedSuper startTopology = requireTopology(startCluster);
            CapturedSuper goalTopology = requireTopology(goalCluster);
            BaseClusterTopology startBase = requireBaseTopology(
                    new ClusterKey(dimension, SectionPos.of(startPosition))
            );
            BaseClusterTopology goalBase = requireBaseTopology(
                    new ClusterKey(dimension, SectionPos.of(goalPosition))
            );
            this.start = new MacroSearch.ExactEndpoint(0L, startPosition, startBase.revision());
            this.goal = new MacroSearch.ExactEndpoint(1L, goalPosition, goalBase.revision());
            this.startBinding = bindPosition(startTopology, startBase, startPosition);
            this.goalBinding = bindPosition(goalTopology, goalBase, goalPosition);
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
        public MacroSearch.Expansion expand(MacroSearch.Endpoint from) {
            MacroSearch.ExpansionBuffer output = new MacroSearch.ExpansionBuffer();
            output.reset(from);
            expandInto(from, output);
            return output.snapshot();
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            requireOwnerThread();
            if (from.id() == goal.id()) {
                return;
            }
            if (from.id() == start.id()) {
                if (startBinding != null) {
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
            if (sameAggregate(source, goalBinding)) {
                output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
            }

            CapturedSuper sourceTopology = requireTopology(source.cluster());
            SuperClusterTopology topology = sourceTopology.topology;
            int aggregateId = source.aggregate().id();
            for (int edge = topology.outgoingStart(aggregateId);
                 edge < topology.outgoingEnd(aggregateId); edge++) {
                AggregateBinding target = binding(
                        sourceTopology,
                        topology.outgoingTarget(edge)
                );
                output.addAggregate(
                        edgeId(source.endpoint(), target.endpoint()),
                        target.endpoint(),
                        topology.outgoingCost(edge)
                );
            }

            for (Direction face : DIRECTIONS) {
                if (!source.aggregate().touches(face)) {
                    continue;
                }
                SectionPos neighborOrigin = sourceTopology.neighbor(face);
                if (!isSuperHeightAvailable(neighborOrigin)) {
                    continue;
                }
                CapturedSuper neighbor = captureTopology(neighborOrigin);
                if (neighbor == null) {
                    output.addDependency(MacroSearch.Dependency.superCluster(
                            neighborOrigin,
                            isSuperLoaded(neighborOrigin)
                                    ? MacroSearch.Availability.PENDING
                                    : MacroSearch.Availability.UNAVAILABLE
                    ));
                    continue;
                }
                SuperClusterTopology.CrossingIndex links =
                        sourceTopology.boundaryLinks(face, neighbor);
                if (links == null) {
                    output.addDependency(MacroSearch.Dependency.superBoundary(
                            source.cluster().origin(),
                            neighbor.key.origin(),
                            face,
                            MacroSearch.Availability.PENDING
                    ));
                    continue;
                }
                for (int edge = links.edgeStart(aggregateId);
                     edge < links.edgeEnd(aggregateId); edge++) {
                    AggregateBinding target = binding(
                            neighbor,
                            links.targetAggregate(edge)
                    );
                    output.addAggregate(
                            edgeId(source.endpoint(), target.endpoint()),
                            target.endpoint(),
                            links.lowerBound(edge)
                    );
                }
            }
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
            int dx = Math.abs(goalCluster.origin().x() - sourceOrigin.x())
                    / SuperClusterTopology.CHILDREN_PER_AXIS;
            int dy = Math.abs(goalCluster.origin().y() - sourceOrigin.y())
                    / SuperClusterTopology.CHILDREN_PER_AXIS;
            int dz = Math.abs(goalCluster.origin().z() - sourceOrigin.z())
                    / SuperClusterTopology.CHILDREN_PER_AXIS;
            return Math.max(dx, Math.max(dy, dz));
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
            BaseClusterTopology topology = TopologyService.this.topology(key);
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
            SuperClusterTopology topology = superTopology(key);
            if (topology != null) {
                captured = new CapturedSuper(key, topology);
                topologySnapshot.put(packed, captured);
            }
            return captured;
        }

        @Nullable
        private AggregateBinding bindPosition(CapturedSuper topology,
                                              BaseClusterTopology base,
                                              BlockPos position) {
            BaseClusterTopology.Component component = base.nearestComponent(
                    channel,
                    Math.floorMod(position.getX(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getY(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getZ(), BaseClusterTopology.SIDE),
                    2
            );
            if (component == null) {
                return null;
            }
            int aggregateId = topology.topology.aggregateId(base.section(), component.id());
            return aggregateId < 0 ? null : binding(topology, aggregateId);
        }

        private AggregateBinding binding(CapturedSuper captured, int aggregateId) {
            SuperClusterTopology topology = captured.topology;
            SuperClusterTopology.Aggregate aggregate = topology.aggregate(aggregateId);
            long packed = captured.key.origin().asLong();
            AggregateBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new AggregateBinding[topology.aggregates().size()];
                bindingsByCluster.put(packed, bindings);
            }
            AggregateBinding binding = bindings[aggregateId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.AggregateEndpoint endpoint = new MacroSearch.AggregateEndpoint(
                    nextEndpointId++,
                    aggregate.anchor(),
                    topology.signature(),
                    captured.key.origin(),
                    channel,
                    aggregate.id()
            );
            binding = new AggregateBinding(captured.key, aggregate, endpoint);
            bindings[aggregateId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private boolean isSuperLoaded(SectionPos origin) {
            if (server == null) {
                return true;
            }
            ServerLevel level = server.getLevel(dimension);
            return level != null && superClusterAvailable(level, origin);
        }

        private boolean isSuperHeightAvailable(SectionPos origin) {
            if (server == null) {
                return true;
            }
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return false;
            }
            for (SectionPos child : SuperClusterTopology.childSections(origin)) {
                if (child.y() < level.getMinSection() || child.y() >= level.getMaxSection()) {
                    return false;
                }
            }
            return true;
        }

        private SuperClusterTopology capturedTopology(SectionPos origin) {
            CapturedSuper captured = topologySnapshot.get(origin.asLong());
            return captured == null ? null : captured.topology;
        }

        private final class CapturedSuper {
            private final SuperCacheKey key;
            private final SuperClusterTopology topology;
            private final SuperBoundaryCacheKey[] boundaries =
                    new SuperBoundaryCacheKey[DIRECTIONS.length];
            private final SectionPos[] neighbors = new SectionPos[DIRECTIONS.length];
            private final SuperClusterTopology[] boundaryTargets =
                    new SuperClusterTopology[DIRECTIONS.length];
            private final SuperClusterTopology.CrossingIndex[] readyBoundaries =
                    new SuperClusterTopology.CrossingIndex[DIRECTIONS.length];

            private CapturedSuper(SuperCacheKey key, SuperClusterTopology topology) {
                this.key = key;
                this.topology = topology;
            }

            private SectionPos neighbor(Direction face) {
                int index = face.ordinal();
                SectionPos origin = neighbors[index];
                if (origin == null) {
                    origin = SuperClusterTopology.offset(
                            key.origin(),
                            face,
                            SuperClusterTopology.CHILDREN_PER_AXIS
                    );
                    neighbors[index] = origin;
                }
                return origin;
            }

            @Nullable
            private SuperClusterTopology.CrossingIndex boundaryLinks(
                    Direction face,
                    CapturedSuper target) {
                int index = face.ordinal();
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

    /** Restricts base-component refinement to the selected aggregate corridor. */
    private final class CorridorTopologyGraph implements MacroSearch.Graph, ComponentAdmission {
        private final TopologyGraph delegate;
        private final SuperTopologyGraph parent;
        private final Long2ObjectOpenHashMap<IntOpenHashSet> allowed =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<AdmissionView> admissionBySection =
                new Long2ObjectOpenHashMap<>();

        private CorridorTopologyGraph(TopologyGraph delegate,
                                      SuperTopologyGraph parent,
                                      MacroSearch.Corridor corridor) {
            this.delegate = delegate;
            this.parent = parent;
            for (MacroSearch.Endpoint endpoint : corridor.endpoints()) {
                if (!(endpoint instanceof MacroSearch.AggregateEndpoint aggregate)) {
                    continue;
                }
                SuperClusterTopology topology = parent.capturedTopology(aggregate.origin());
                if (topology == null || topology.signature() != aggregate.revision()) {
                    throw new IllegalStateException("aggregate corridor references stale topology");
                }
                allowed.computeIfAbsent(
                        aggregate.origin().asLong(),
                        ignored -> new IntOpenHashSet()
                ).add(aggregate.aggregateId());
            }
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("aggregate corridor contains no aggregate nodes");
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
        public MacroSearch.Expansion expand(MacroSearch.Endpoint from) {
            MacroSearch.ExpansionBuffer output = new MacroSearch.ExpansionBuffer();
            output.reset(from);
            expandInto(from, output);
            return output.snapshot();
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
            return admission(section) != null;
        }

        @Override
        public boolean allowsComponent(SectionPos section, int componentId) {
            AdmissionView admission = admission(section);
            if (admission == null) {
                return false;
            }
            int aggregateId = admission.topology.aggregateId(section, componentId);
            return aggregateId >= 0 && admission.allowedAggregates.contains(aggregateId);
        }

        @Nullable
        private AdmissionView admission(SectionPos section) {
            long packed = section.asLong();
            AdmissionView existing = admissionBySection.get(packed);
            if (existing != null) {
                return existing;
            }
            SectionPos origin = SuperClusterTopology.originOf(section);
            IntOpenHashSet allowedAggregates = allowed.get(origin.asLong());
            SuperClusterTopology topology = parent.capturedTopology(origin);
            if (allowedAggregates == null || topology == null) {
                return null;
            }
            AdmissionView created = new AdmissionView(topology, allowedAggregates);
            admissionBySection.put(packed, created);
            return created;
        }

        private record AdmissionView(SuperClusterTopology topology,
                                     IntOpenHashSet allowedAggregates) {
        }
    }

    private final class TopologyGraph implements MacroSearch.Graph {
        private final ResourceKey<Level> dimension;
        private final ClusterKey startCluster;
        private final ClusterKey goalCluster;
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
        private final ComponentBinding startBinding;
        private final ComponentBinding goalBinding;
        private long nextEndpointId = 2L;
        private long validatedEpoch;

        private TopologyGraph(ResourceKey<Level> dimension,
                              BlockPos startPosition,
                              BlockPos goalPosition,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.startCluster = new ClusterKey(dimension, SectionPos.of(startPosition));
            this.goalCluster = new ClusterKey(dimension, SectionPos.of(goalPosition));

            CapturedBase startTopology = requireTopology(startCluster);
            CapturedBase goalTopology = requireTopology(goalCluster);
            this.start = new MacroSearch.ExactEndpoint(
                    0L,
                    startPosition,
                    startTopology.topology.revision()
            );
            this.goal = new MacroSearch.ExactEndpoint(
                    1L,
                    goalPosition,
                    goalTopology.topology.revision()
            );
            this.startBinding = bindPosition(startTopology, startPosition);
            this.goalBinding = bindPosition(goalTopology, goalPosition);
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
        public MacroSearch.Expansion expand(MacroSearch.Endpoint from) {
            MacroSearch.ExpansionBuffer output = new MacroSearch.ExpansionBuffer();
            output.reset(from);
            expandInto(from, output, null);
            return output.snapshot();
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
                if (startBinding != null && (admission == null || admission.allowsComponent(
                        startBinding.cluster().section(),
                        startBinding.component().id()
                ))) {
                    output.addMembership(
                            edgeId(start, startBinding.endpoint()),
                            startBinding.endpoint(),
                            0.0F
                    );
                }
                return;
            }

            ComponentBinding source = bindingsByEndpoint.get(from.id());
            if (source == null) {
                throw new IllegalArgumentException("endpoint does not belong to this graph");
            }
            if (sameComponent(source, goalBinding)) {
                output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
            }

            CapturedBase sourceTopology = requireTopology(source.cluster());
            BaseClusterTopology topology = sourceTopology.topology;
            int sourceComponentId = source.component().id();
            for (int edge = topology.localOutgoingStart(sourceComponentId);
                 edge < topology.localOutgoingEnd(sourceComponentId); edge++) {
                BaseClusterTopology.LocalConnection local = topology.localOutgoing(edge);
                if (!profile.supports(local)) {
                    continue;
                }
                if (admission != null && !admission.allowsComponent(
                        source.cluster().section(),
                        local.toComponent()
                )) {
                    continue;
                }
                ComponentBinding target = binding(sourceTopology, local.toComponent());
                output.addLocal(
                        edgeId(source.endpoint(), target.endpoint()),
                        target.endpoint(),
                        local.lowerBound(),
                        local
                );
            }

            for (Direction face : DIRECTIONS) {
                if (!source.component().touches(face)) {
                    continue;
                }
                SectionPos neighborSection = sourceTopology.neighbor(face);
                if (admission != null && !admission.allowsSection(neighborSection)) {
                    continue;
                }
                if (!isSectionHeightAvailable(neighborSection)) {
                    continue;
                }
                CapturedBase neighbor = captureTopology(neighborSection);
                if (neighbor == null) {
                    output.addDependency(new MacroSearch.Dependency(
                            neighborSection,
                            isClusterLoaded(neighborSection)
                                    ? MacroSearch.Availability.PENDING
                                    : MacroSearch.Availability.UNAVAILABLE
                    ));
                    continue;
                }
                SuperClusterTopology.BoundaryLinks links =
                        sourceTopology.boundaryLinks(face, neighbor);
                if (links == null) {
                    output.addDependency(MacroSearch.Dependency.baseBoundary(
                            source.cluster().section(),
                            neighborSection,
                            face,
                            MacroSearch.Availability.PENDING
                    ));
                    continue;
                }
                for (int edge = links.edgeStart(sourceComponentId);
                     edge < links.edgeEnd(sourceComponentId); edge++) {
                    int targetComponentId = links.targetComponent(edge);
                    if (admission != null && !admission.allowsComponent(
                            neighborSection,
                            targetComponentId
                    )) {
                        continue;
                    }
                    ComponentBinding target = binding(neighbor, targetComponentId);
                    output.addBoundary(
                            edgeId(source.endpoint(), target.endpoint()),
                            target.endpoint(),
                            1.0F,
                            links,
                            edge
                    );
                }
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
            SectionPos sourceSection = endpoint instanceof MacroSearch.ComponentEndpoint component
                    ? component.section()
                    : SectionPos.of(endpoint.anchor());
            int dx = Math.abs(goalCluster.section().x() - sourceSection.x());
            int dy = Math.abs(goalCluster.section().y() - sourceSection.y());
            int dz = Math.abs(goalCluster.section().z() - sourceSection.z());
            return Math.max(dx, Math.max(dy, dz));
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
            if (entry == null || entry.topology == null) {
                return null;
            }
            captured = new CapturedBase(key, entry.topology);
            topologySnapshot.put(packed, captured);
            return captured;
        }

        @Nullable
        private ComponentBinding bindPosition(CapturedBase topology,
                                              BlockPos position) {
            BaseClusterTopology.Component component = topology.topology.nearestComponent(
                    channel,
                    Math.floorMod(position.getX(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getY(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getZ(), BaseClusterTopology.SIDE),
                    2
            );
            return component == null ? null : binding(topology, component.id());
        }

        private ComponentBinding binding(CapturedBase captured, int componentId) {
            BaseClusterTopology topology = captured.topology;
            BaseClusterTopology.Component component = topology.component(componentId);
            if (component.channel() != channel) {
                throw new IllegalArgumentException(
                        "component " + componentId + " does not use channel " + channel
                );
            }
            long packed = captured.key.section().asLong();
            ComponentBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new ComponentBinding[topology.components().size()];
                bindingsByCluster.put(packed, bindings);
            }
            ComponentBinding binding = bindings[componentId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.ComponentEndpoint endpoint = new MacroSearch.ComponentEndpoint(
                    nextEndpointId++,
                    componentAnchor(captured.key.section(), component),
                    topology.revision(),
                    captured.key.section(),
                    channel,
                    component.id()
            );
            binding = new ComponentBinding(captured.key, component, endpoint);
            bindings[componentId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private boolean isClusterLoaded(SectionPos section) {
            return clusterLoaded(new ClusterKey(dimension, section));
        }

        private boolean isSectionHeightAvailable(SectionPos section) {
            if (server == null) {
                return true;
            }
            ServerLevel level = server.getLevel(dimension);
            return level != null
                    && section.y() >= level.getMinSection()
                    && section.y() < level.getMaxSection();
        }

        private final class CapturedBase {
            private final ClusterKey key;
            private final BaseClusterTopology topology;
            private final SectionPos[] neighbors = new SectionPos[DIRECTIONS.length];
            private final BaseBoundaryCacheKey[] boundaries =
                    new BaseBoundaryCacheKey[DIRECTIONS.length];
            private final BaseClusterTopology[] boundaryTargets =
                    new BaseClusterTopology[DIRECTIONS.length];
            private final SuperClusterTopology.BoundaryLinks[] readyBoundaries =
                    new SuperClusterTopology.BoundaryLinks[DIRECTIONS.length];

            private CapturedBase(ClusterKey key, BaseClusterTopology topology) {
                this.key = key;
                this.topology = topology;
            }

            private SectionPos neighbor(Direction face) {
                int index = face.ordinal();
                SectionPos section = neighbors[index];
                if (section == null) {
                    section = SuperClusterTopology.offset(key.section(), face, 1);
                    neighbors[index] = section;
                }
                return section;
            }

            @Nullable
            private SuperClusterTopology.BoundaryLinks boundaryLinks(
                    Direction face,
                    CapturedBase target) {
                int index = face.ordinal();
                if (boundaryTargets[index] != target.topology) {
                    boundaryTargets[index] = target.topology;
                    boundaries[index] = new BaseBoundaryCacheKey(
                            dimension,
                            topology,
                            target.topology,
                            face,
                            channel,
                            profile
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
                                            BaseClusterTopology.Component component) {
        return new BlockPos(
                section.minBlockX() + component.anchorX(),
                section.minBlockY() + component.anchorY(),
                section.minBlockZ() + component.anchorZ()
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
                && first.component().id() == second.component().id();
    }

    private static boolean sameAggregate(@Nullable AggregateBinding first,
                                         @Nullable AggregateBinding second) {
        return first != null && second != null
                && first.cluster().equals(second.cluster())
                && first.aggregate().id() == second.aggregate().id();
    }

    private record AggregateBinding(SuperCacheKey cluster,
                                    SuperClusterTopology.Aggregate aggregate,
                                    MacroSearch.AggregateEndpoint endpoint) {
    }

    private record ComponentBinding(ClusterKey cluster,
                                    BaseClusterTopology.Component component,
                                    MacroSearch.ComponentEndpoint endpoint) {
    }

    private interface ComponentAdmission {
        boolean allowsSection(SectionPos section);

        boolean allowsComponent(SectionPos section, int componentId);
    }

    @FunctionalInterface
    interface CellSampler {
        int sample(int cellIndex);
    }

    static final class SnapshotSearch implements ResumableSearch<BaseClusterTopology.Snapshot> {
        private final CellSampler sampler;
        private final byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        private Status status = Status.RUNNING;
        private BaseClusterTopology.Snapshot result;
        private int cursor;
        private long spentNanos;

        SnapshotSearch(CellSampler sampler) {
            this.sampler = Objects.requireNonNull(sampler, "sampler");
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
