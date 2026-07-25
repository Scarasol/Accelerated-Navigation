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
import java.util.UUID;

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
    private static final int MAX_QUERY_VISITED_NODES = 8_192;
    private static final float QUERY_VISITED_NODES_PER_BLOCK = 8.0F;

    private static final TopologyTaskExecutor.TaskHandle UNTRACKED_TASK =
            new TopologyTaskExecutor.TaskHandle() {
                @Override
                public void promote(NavigationScheduler.Priority priority) {
                }

                @Override
                public boolean cancel() {
                    return false;
                }
            };

    private final WorkDispatcher buildWorker;
    private final WorkDispatcher persistenceWorker;
    private final Executor publisher;
    private final BooleanSupplier ownerThread;
    private final TopologyTaskExecutor ownedBuildWorker;
    private final TopologyTaskExecutor ownedPersistenceWorker;
    private final TopologyStore store;
    private final MinecraftServer server;
    private final Map<ClusterKey, ClusterEntry> clusters = new HashMap<>();
    private final LinkedHashMap<SuperCacheKey, SuperEntry> superClusters =
            new LinkedHashMap<>(32, 0.75F, true);
    private final LinkedHashMap<BaseBoundaryCacheKey, LinkEntry<SuperClusterTopology.BoundaryLinks>>
            baseBoundaryLinks = new LinkedHashMap<>(64, 0.75F, true);
    private final LinkedHashMap<SuperBoundaryCacheKey, LinkEntry<SuperClusterTopology.CrossingIndex>>
            superBoundaryLinks = new LinkedHashMap<>(32, 0.75F, true);
    private final ArrayDeque<ClusterKey> requestedBuilds = new ArrayDeque<>();
    private final Set<ClusterKey> queuedBuilds = new HashSet<>();
    private final LongAdder snapshotCells = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder buildRequests = new LongAdder();
    private final LongAdder buildNanos = new LongAdder();
    private final LongAdder publishedClusters = new LongAdder();
    private final LongAdder staleBuilds = new LongAdder();
    private final LongAdder coalescedInvalidations = new LongAdder();
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

    TopologyService(Executor worker, Executor publisher, BooleanSupplier ownerThread) {
        Executor injectedWorker = Objects.requireNonNull(worker, "worker");
        this.buildWorker = (priority, command) -> {
            injectedWorker.execute(command);
            return UNTRACKED_TASK;
        };
        this.persistenceWorker = this.buildWorker;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.ownedBuildWorker = null;
        this.ownedPersistenceWorker = null;
        this.store = null;
        this.server = null;
    }

    private TopologyService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.ownedBuildWorker = new TopologyTaskExecutor(
                "accelerated-navigation-topology",
                Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
        );
        this.ownedPersistenceWorker = new TopologyTaskExecutor(
                "accelerated-navigation-topology-io",
                Thread.MIN_PRIORITY
        );
        this.buildWorker = ownedBuildWorker::submit;
        this.persistenceWorker = ownedPersistenceWorker::submit;
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

    public CompletableFuture<BaseClusterTopology> requestCluster(ServerLevel level,
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
            return CompletableFuture.failedFuture(
                    new IllegalStateException("topology request cannot load an unavailable chunk")
            );
        }

        ClusterKey key = new ClusterKey(level.dimension(), section);
        ClusterEntry entry = clusters.computeIfAbsent(key, ignored -> new ClusterEntry());
        if (entry.topology != null && entry.topology.revision() == entry.revision) {
            return CompletableFuture.completedFuture(entry.topology);
        }
        if (entry.request == null) {
            entry.request = new CompletableFuture<>();
        }
        entry.requestPriority = higherPriority(entry.requestPriority, priority);
        if (entry.snapshotFuture != null) {
            NavigationScheduler.forServer(level.getServer()).promote(
                    level.dimension(),
                    snapshotOwner(key),
                    entry.requestPriority
            );
        }
        if (entry.pending != null) {
            entry.pending.promote(entry.requestPriority);
        }
        enqueueRequestedBuild(key, entry);
        return entry.request;
    }

    private CompletableFuture<SuperClusterTopology> requestSuperCluster(
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
            return CompletableFuture.failedFuture(
                    new IllegalStateException("super topology cannot use unavailable sections")
            );
        }

        SuperEntry entry = superClusters.computeIfAbsent(key, ignored -> new SuperEntry());
        SuperClusterTopology ready = currentSuperTopology(key, entry);
        if (ready != null) {
            return CompletableFuture.completedFuture(ready);
        }
        if (entry.request == null) {
            entry.request = new CompletableFuture<>();
        }
        NavigationScheduler.Priority previousPriority = entry.requestPriority;
        entry.requestPriority = higherPriority(previousPriority, priority);
        if (entry.buildTask != null) {
            entry.buildTask.promote(entry.requestPriority);
        }
        if (entry.attemptRunning && previousPriority != entry.requestPriority) {
            for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
                requestCluster(level, child, entry.requestPriority);
            }
        }
        if (!entry.attemptRunning) {
            beginSuperRequest(level, key, entry);
        }
        return entry.request;
    }

    private void beginSuperRequest(ServerLevel level,
                                   SuperCacheKey key,
                                   SuperEntry entry) {
        requireOwnerThread();
        if (closed || entry.request == null || entry.request.isDone()) {
            return;
        }
        entry.attemptRunning = true;
        long attempt = ++entry.attempt;
        List<CompletableFuture<BaseClusterTopology>> children = new ArrayList<>(8);
        try {
            for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
                children.add(requestCluster(level, child, entry.requestPriority));
            }
        } catch (RuntimeException failure) {
            entry.attemptRunning = false;
            failSuperRequest(key, entry, failure);
            return;
        }
        CompletableFuture.allOf(children.toArray(CompletableFuture[]::new))
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
                || entry.request == null || entry.request.isDone()) {
            return;
        }
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
                || entry.request == null || entry.request.isDone()) {
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
        CompletableFuture<SuperClusterTopology> request = entry.request;
        entry.request = null;
        entry.requestPriority = null;
        request.complete(topology);
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
        CompletableFuture<SuperClusterTopology> request = entry.request;
        entry.request = null;
        entry.requestPriority = null;
        if (entry.buildTask != null) {
            entry.buildTask.cancel();
            entry.buildTask = null;
        }
        entry.attemptRunning = false;
        if (request != null) {
            request.completeExceptionally(failure);
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
            entry.track(buildWorker.submit(priority, () -> buildBaseBoundaryLinks(key, entry)));
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
            entry.track(buildWorker.submit(priority, () -> buildSuperBoundaryLinks(key, entry)));
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
        NavigationScheduler.AdmissionCapacity capacity = scheduler.admissionCapacity();
        int freeSlots = capacity.freeSlots();
        int evictableBackgroundSlots = capacity.evictableBackgroundSlots();
        int queuedAtTickEnd = requestedBuilds.size();
        for (int index = 0; index < queuedAtTickEnd; index++) {
            ClusterKey key = requestedBuilds.removeFirst();
            queuedBuilds.remove(key);
            ClusterEntry entry = clusters.get(key);
            if (entry == null || entry.request == null || entry.request.isDone()) {
                continue;
            }
            if (entry.topology != null && entry.topology.revision() == entry.revision) {
                completeRequest(key, entry, entry.topology);
                continue;
            }
            if (entry.snapshotFuture != null || entry.pending != null) {
                continue;
            }
            boolean useFreeSlot = freeSlots > 0;
            boolean replaceBackground = !useFreeSlot
                    && entry.requestPriority != NavigationScheduler.Priority.BACKGROUND
                    && evictableBackgroundSlots > 0;
            if (!useFreeSlot && !replaceBackground) {
                enqueueRequestedBuild(key, entry);
                continue;
            }
            ServerLevel level = server.getLevel(key.dimension());
            LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(
                    key.section().x(),
                    key.section().z()
            );
            if (level == null || chunk == null) {
                failRequest(key, entry, new IllegalStateException(
                        "topology request cannot use an unavailable chunk"
                ));
                continue;
            }
            startSnapshotAttempt(level, key, entry, chunk);
            if (useFreeSlot) {
                freeSlots--;
            } else {
                evictableBackgroundSlots--;
            }
        }
    }

    private void startSnapshotAttempt(ServerLevel level,
                                      ClusterKey key,
                                      ClusterEntry entry,
                                      LevelChunk chunk) {
        long expectedRevision = entry.revision;
        entry.dirty = false;
        SnapshotSearch snapshotSearch = worldSnapshot(level, key.section(), chunk);
        CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture =
                NavigationScheduler.forServer(level.getServer()).submitStrict(
                        level.dimension(),
                        snapshotOwner(key),
                        entry.requestPriority,
                        snapshotSearch
                );
        entry.snapshotFuture = snapshotFuture;
        snapshotFuture.whenComplete((snapshot, failure) -> publisher.execute(() -> completeSnapshotAttempt(
                key,
                expectedRevision,
                snapshotSearch,
                snapshotFuture,
                snapshot,
                failure
        )));
    }

    private void completeSnapshotAttempt(ClusterKey key,
                                         long expectedRevision,
                                         SnapshotSearch snapshotSearch,
                                         CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture,
                                         @Nullable BaseClusterTopology.Snapshot snapshot,
                                         @Nullable Throwable failure) {
        requireOwnerThread();
        snapshotCells.add(snapshotSearch.sampledCells());
        snapshotNanos.add(snapshotSearch.spentNanos());
        ClusterEntry entry = clusters.get(key);
        if (entry == null || entry.snapshotFuture != snapshotFuture) {
            return;
        }
        entry.snapshotFuture = null;
        if (closed || entry.request == null) {
            return;
        }
        if (failure != null || snapshot == null || entry.revision != expectedRevision) {
            if (entry.revision != expectedRevision || retryableAttemptFailure(failure)) {
                entry.dirty = true;
                enqueueRequestedBuild(key, entry);
            } else {
                failRequest(key, entry, failure != null
                        ? failure
                        : new IllegalStateException("snapshot search failed"));
            }
            return;
        }

        CompletableFuture<BaseClusterTopology> buildFuture;
        try {
            buildFuture = submitSnapshot(key, snapshot, entry.requestPriority);
        } catch (RuntimeException exception) {
            failRequest(key, entry, exception);
            return;
        }
        buildFuture.whenComplete((topology, buildFailure) -> publisher.execute(
                () -> completeBuildRequest(key, expectedRevision, topology, buildFailure)
        ));
    }

    private void completeBuildRequest(ClusterKey key,
                                      long expectedRevision,
                                      @Nullable BaseClusterTopology topology,
                                      @Nullable Throwable failure) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(key);
        if (entry == null || entry.request == null || closed) {
            return;
        }
        if (failure != null || topology == null || entry.revision != expectedRevision
                || entry.topology != topology) {
            if (entry.revision != expectedRevision || retryableAttemptFailure(failure)) {
                entry.dirty = true;
                enqueueRequestedBuild(key, entry);
            } else {
                failRequest(key, entry, failure != null
                        ? failure
                        : new IllegalStateException("topology build did not publish its result"));
            }
            return;
        }
        completeRequest(key, entry, topology);
    }

    public CompletableFuture<BaseClusterTopology> submitSnapshot(ClusterKey key,
                                                                   BaseClusterTopology.Snapshot snapshot) {
        return submitSnapshot(key, snapshot, NavigationScheduler.Priority.BACKGROUND);
    }

    private CompletableFuture<BaseClusterTopology> submitSnapshot(
            ClusterKey key,
            BaseClusterTopology.Snapshot snapshot,
            NavigationScheduler.Priority priority) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(priority, "priority");

        ClusterEntry entry = clusters.computeIfAbsent(key, ignored -> new ClusterEntry());
        if (entry.topology != null
                && entry.topology.revision() == entry.revision
                && entry.topology.sourceFingerprint() == snapshot.fingerprint()) {
            return CompletableFuture.completedFuture(entry.topology);
        }
        if (entry.topology != null
                && entry.topology.sourceFingerprint() != snapshot.fingerprint()) {
            BaseClusterTopology stale = entry.topology;
            entry.revision++;
            entry.topology = null;
            entry.dirty = true;
            invalidateBaseBoundaryLinks(stale);
            retainedBytes.addAndGet(-stale.retainedBytes());
            topologyChanged();
            invalidateSuperParent(key, false);
        }
        if (entry.pending != null) {
            if (entry.pending.revision != entry.revision
                    || entry.pending.fingerprint != snapshot.fingerprint()) {
                throw new IllegalStateException("different snapshots submitted for one topology revision");
            }
            entry.pending.promote(priority);
            return entry.pending.future;
        }

        PendingBuild pending = new PendingBuild(
                entry.revision,
                snapshot.fingerprint(),
                new CompletableFuture<>(),
                priority
        );
        entry.pending = pending;
        entry.dirty = false;
        buildRequests.increment();
        startPendingBuild(key, snapshot, pending);
        return pending.future;
    }

    private void startPendingBuild(ClusterKey key,
                                   BaseClusterTopology.Snapshot snapshot,
                                   PendingBuild pending) {
        try {
            if (store == null) {
                pending.track(buildWorker.submit(
                        pending.priority,
                        () -> build(key, snapshot, pending)
                ));
            } else {
                pending.track(persistenceWorker.submit(
                        pending.priority,
                        () -> readStoredTopology(key, snapshot, pending)
                ));
            }
        } catch (RejectedExecutionException exception) {
            ClusterEntry entry = clusters.get(key);
            if (entry != null && entry.pending == pending) {
                entry.pending = null;
            }
            pending.future.completeExceptionally(exception);
        }
    }

    private void readStoredTopology(ClusterKey key,
                                    BaseClusterTopology.Snapshot snapshot,
                                    PendingBuild pending) {
        BaseClusterTopology stored = null;
        Throwable readFailure = null;
        try {
            stored = store.read(key.dimension(), key.section())
                    .filter(value -> value.sourceFingerprint() == snapshot.fingerprint())
                    .orElse(null);
        } catch (IOException | RuntimeException exception) {
            readFailure = exception;
        }
        BaseClusterTopology result = stored;
        Throwable failure = readFailure;
        publisher.execute(() -> completeStoredTopologyRead(
                key,
                snapshot,
                pending,
                result,
                failure
        ));
    }

    private void completeStoredTopologyRead(ClusterKey key,
                                            BaseClusterTopology.Snapshot snapshot,
                                            PendingBuild pending,
                                            @Nullable BaseClusterTopology stored,
                                            @Nullable Throwable readFailure) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(key);
        if (closed || entry == null || entry.pending != pending
                || entry.revision != pending.revision) {
            return;
        }
        if (readFailure != null) {
            AcceleratedNavigation.LOGGER.warn("Could not read macro topology for {}", key, readFailure);
        }
        if (stored != null) {
            publish(key, pending, stored.withRevision(pending.revision));
            return;
        }
        try {
            pending.track(buildWorker.submit(
                    pending.priority,
                    () -> build(key, snapshot, pending)
            ));
        } catch (RejectedExecutionException exception) {
            entry.pending = null;
            pending.future.completeExceptionally(exception);
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
                && entry.pending == null && entry.snapshotFuture == null) {
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
        if (entry.pending != null) {
            PendingBuild stale = entry.pending;
            entry.pending = null;
            stale.cancel();
            stale.future.completeExceptionally(new StaleTopologyException(key));
        }
        if (entry.snapshotFuture != null) {
            entry.snapshotFuture = null;
            if (server != null) {
                NavigationScheduler.forServer(server).cancel(key.dimension(), snapshotOwner(key));
            }
        }
        if (entry.request != null && !entry.request.isDone()) {
            enqueueRequestedBuild(key, entry);
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

    public MacroQuery macroQuery(ServerLevel level,
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
        return new MacroQuery(
                level,
                Objects.requireNonNull(start, "start").immutable(),
                Objects.requireNonNull(goal, "goal").immutable(),
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(profile, "profile"),
                Objects.requireNonNull(priority, "priority"),
                MacroSearch.DEFAULT_WEIGHT
        );
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

    private static int queryNodeBudget(BlockPos start, BlockPos goal) {
        double directDistance = Math.sqrt(start.distSqr(goal));
        return Math.max(
                MIN_QUERY_VISITED_NODES,
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
        return new Metrics(
                snapshotCells.sum(),
                snapshotNanos.sum(),
                buildRequests.sum(),
                buildNanos.sum(),
                publishedClusters.sum(),
                staleBuilds.sum(),
                coalescedInvalidations.sum(),
                queuedBuilds.size(),
                retainedBytes.get(),
                superBuildRequests.sum(),
                superBuildNanos.sum(),
                publishedSuperClusters.sum(),
                staleSuperBuilds.sum(),
                evictedSuperClusters.sum(),
                superClusters.size(),
                superRetainedBytes.get(),
                workerMetrics(ownedBuildWorker),
                workerMetrics(ownedPersistenceWorker),
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

    public void shutdown() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        IllegalStateException stopped = new IllegalStateException("topology service stopped");
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
            if (entry.pending != null) {
                entry.pending.cancel();
                entry.pending.future.completeExceptionally(
                        new IllegalStateException("topology service stopped")
                );
                entry.pending = null;
            }
            if (entry.snapshotFuture != null && server != null) {
                entry.snapshotFuture = null;
                NavigationScheduler.forServer(server).cancel(
                        cluster.getKey().dimension(),
                        snapshotOwner(cluster.getKey())
                );
            }
            if (entry.request != null) {
                entry.request.completeExceptionally(
                        new IllegalStateException("topology service stopped")
                );
                entry.request = null;
            }
            entry.topology = null;
        }
        for (SuperEntry entry : superClusters.values()) {
            if (entry.buildTask != null) {
                entry.buildTask.cancel();
                entry.buildTask = null;
            }
            if (entry.request != null) {
                entry.request.completeExceptionally(
                        new IllegalStateException("topology service stopped")
                );
            }
            entry.topology = null;
            entry.request = null;
        }
        superClusters.clear();
        requestedBuilds.clear();
        queuedBuilds.clear();
        retainedBytes.set(0L);
        superRetainedBytes.set(0L);
        baseBoundaryRetainedBytes.set(0L);
        superBoundaryRetainedBytes.set(0L);
        if (ownedBuildWorker != null) {
            ownedBuildWorker.shutdown();
        }
        if (ownedPersistenceWorker != null) {
            if (store == null) {
                ownedPersistenceWorker.shutdown();
            } else {
                ownedPersistenceWorker.shutdown(() -> {
                    try {
                        store.close();
                    } catch (IOException exception) {
                        AcceleratedNavigation.LOGGER.error("Could not close macro topology store", exception);
                    }
                });
            }
        }
    }

    private void build(ClusterKey key,
                       BaseClusterTopology.Snapshot snapshot,
                       PendingBuild pending) {
        long started = System.nanoTime();
        BaseClusterTopology topology;
        try {
            topology = BaseClusterTopology.build(key.section(), pending.revision, snapshot);
        } catch (RuntimeException exception) {
            buildNanos.add(System.nanoTime() - started);
            publisher.execute(() -> failBuild(key, pending, exception));
            return;
        }
        buildNanos.add(System.nanoTime() - started);
        publisher.execute(() -> publish(key, pending, topology));
    }

    private void publish(ClusterKey key,
                         PendingBuild pending,
                         BaseClusterTopology topology) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(key);
        if (closed || entry == null || entry.revision != pending.revision
                || entry.pending != pending
                || topology.sourceFingerprint() != pending.fingerprint) {
            staleBuilds.increment();
            pending.future.completeExceptionally(new StaleTopologyException(key));
            return;
        }

        BaseClusterTopology replaced = entry.topology;
        if (replaced != null) {
            invalidateBaseBoundaryLinks(replaced);
            retainedBytes.addAndGet(-replaced.retainedBytes());
        }
        entry.topology = topology;
        entry.pending = null;
        entry.dirty = false;
        retainedBytes.addAndGet(topology.retainedBytes());
        publishedClusters.increment();
        topologyChanged();
        if (replaced != null) {
            invalidateSuperParent(key, false);
        }
        pending.future.complete(topology);
        if (store != null && !closed) {
            try {
                persistenceWorker.submit(
                        NavigationScheduler.Priority.BACKGROUND,
                        () -> persist(key, topology)
                );
            } catch (RejectedExecutionException exception) {
                AcceleratedNavigation.LOGGER.warn("Macro topology persistence queue rejected {}", key);
            }
        }
    }

    private void failBuild(ClusterKey key, PendingBuild pending, RuntimeException exception) {
        requireOwnerThread();
        ClusterEntry entry = clusters.get(key);
        if (entry != null && entry.pending == pending) {
            entry.pending = null;
        }
        pending.future.completeExceptionally(exception);
    }

    private void requireOwnerThread() {
        if (!ownerThread.getAsBoolean()) {
            throw new IllegalStateException("topology state must be accessed by its owner thread");
        }
    }

    private void persist(ClusterKey key, BaseClusterTopology topology) {
        try {
            store.write(key.dimension(), topology);
        } catch (IOException exception) {
            AcceleratedNavigation.LOGGER.warn("Could not persist macro topology for {}", key, exception);
        }
    }

    private void enqueueRequestedBuild(ClusterKey key, ClusterEntry entry) {
        if (entry.request == null || entry.request.isDone() || entry.snapshotFuture != null
                || entry.pending != null) {
            return;
        }
        if (queuedBuilds.add(key)) {
            requestedBuilds.addLast(key);
        }
    }

    private void completeRequest(ClusterKey key,
                                 ClusterEntry entry,
                                 BaseClusterTopology topology) {
        CompletableFuture<BaseClusterTopology> request = entry.request;
        entry.request = null;
        entry.requestPriority = null;
        queuedBuilds.remove(key);
        if (request != null) {
            request.complete(topology);
        }
    }

    private void failRequest(ClusterKey key, ClusterEntry entry, Throwable failure) {
        CompletableFuture<BaseClusterTopology> request = entry.request;
        entry.request = null;
        entry.requestPriority = null;
        queuedBuilds.remove(key);
        if (request != null) {
            request.completeExceptionally(failure);
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
            if (entry.pending != null) {
                entry.pending.cancel();
                entry.pending.future.completeExceptionally(new StaleTopologyException(key));
            }
            if (entry.snapshotFuture != null && server != null) {
                entry.snapshotFuture = null;
                NavigationScheduler.forServer(server).cancel(key.dimension(), snapshotOwner(key));
            }
            if (entry.request != null) {
                entry.request.completeExceptionally(new StaleTopologyException(key));
            }
            queuedBuilds.remove(key);
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
            if (unavailable) {
                if (entry.request != null) {
                    entry.request.completeExceptionally(new StaleTopologyException(child));
                }
                entry.request = null;
                entry.requestPriority = null;
                iterator.remove();
            } else if (entry.request != null && !entry.request.isDone()) {
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
            if (entry.request != null) {
                entry.request.completeExceptionally(new IllegalStateException(
                        "super topology lost a loaded child chunk"
                ));
            }
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
            if (entry.request != null || entry.attemptRunning) {
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
                           long topologyEpoch,
                           LinkCacheMetrics baseBoundaryLinks,
                           LinkCacheMetrics superBoundaryLinks) {
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

    private static final class ClusterEntry {
        private long revision;
        private boolean dirty;
        private BaseClusterTopology topology;
        private PendingBuild pending;
        private CompletableFuture<BaseClusterTopology.Snapshot> snapshotFuture;
        private CompletableFuture<BaseClusterTopology> request;
        private NavigationScheduler.Priority requestPriority;
    }

    private static final class SuperEntry {
        private long attempt;
        private boolean attemptRunning;
        private SuperClusterTopology topology;
        private CompletableFuture<SuperClusterTopology> request;
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
        TopologyTaskExecutor.TaskHandle submit(NavigationScheduler.Priority priority,
                                               Runnable command);
    }

    private static final class PendingBuild {
        private final long revision;
        private final long fingerprint;
        private final CompletableFuture<BaseClusterTopology> future;
        private volatile NavigationScheduler.Priority priority;
        private TopologyTaskExecutor.TaskHandle task = UNTRACKED_TASK;
        private boolean cancelled;

        private PendingBuild(long revision,
                             long fingerprint,
                             CompletableFuture<BaseClusterTopology> future,
                             NavigationScheduler.Priority priority) {
            this.revision = revision;
            this.fingerprint = fingerprint;
            this.future = future;
            this.priority = priority;
        }

        private synchronized void track(TopologyTaskExecutor.TaskHandle handle) {
            Objects.requireNonNull(handle, "handle");
            if (cancelled) {
                handle.cancel();
                return;
            }
            task = handle;
            task.promote(priority);
        }

        private synchronized void promote(NavigationScheduler.Priority requested) {
            if (requested.higherThan(priority)) {
                priority = requested;
                task.promote(requested);
            }
        }

        private synchronized void cancel() {
            cancelled = true;
            task.cancel();
        }
    }

    /** Coordinates section availability around one pure, resumable macro search. */
    public final class MacroQuery implements ResumableSearch<MacroSearch.Corridor> {
        private final ServerLevel level;
        private final BlockPos startPosition;
        private final BlockPos goalPosition;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final NavigationScheduler.Priority priority;
        private final float weight;
        private final boolean hierarchical;
        private final Map<MacroSearch.DependencyKey, CompletableFuture<?>> requests = new HashMap<>();

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
                    throw new IllegalStateException("macro topology build failed", buildFailure);
                }
                waitingForBuild = false;
                if (search == null && !prepareEndpoints()) {
                    return status;
                }
                if (search == null) {
                    int maxVisitedNodes = queryNodeBudget(startPosition, goalPosition);
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
                                    queryNodeBudget(startPosition, goalPosition)
                            );
                            refining = true;
                        }
                    } else if (candidate != null && isCurrent(level.dimension(), candidate)) {
                        result = candidate;
                        status = Status.SUCCEEDED;
                    } else {
                        restartStaleSearch();
                    }
                } else if (searchStatus == Status.FAILED) {
                    if (search.failure() == MacroSearch.Failure.STALE_WORLD) {
                        restartStaleSearch();
                    } else {
                        failure = search.failure();
                        status = Status.FAILED;
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
            CompletableFuture<?> request;
            switch (key.kind()) {
                case BASE_CLUSTER -> {
                    request = requestCluster(level, key.position(), priority);
                    requestedSections++;
                }
                case SUPER_CLUSTER -> {
                    request = requestSuperCluster(level, key.position(), channel, profile, priority);
                    requestedSuperClusters++;
                }
                case BASE_BOUNDARY -> {
                    BaseBoundaryCacheKey boundary = baseBoundaryKey(key);
                    request = boundary == null
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
                    requestedBaseBoundaryLinks++;
                }
                case SUPER_BOUNDARY -> {
                    SuperBoundaryCacheKey boundary = superBoundaryKey(key);
                    request = boundary == null
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
                    requestedSuperBoundaryLinks++;
                }
                default -> throw new IllegalStateException("unknown topology dependency " + key.kind());
            }
            requests.put(key, request);
            request.whenComplete((topology, requestFailure) -> publisher.execute(
                    () -> completeDependencyRequest(key, request, requestFailure)
            ));
        }

        private void completeDependencyRequest(MacroSearch.DependencyKey dependency,
                                               CompletableFuture<?> request,
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

        @Override
        public void cancel() {
            requireOwnerThread();
            if (status != Status.RUNNING) {
                return;
            }
            status = Status.FAILED;
            failure = MacroSearch.Failure.CANCELLED;
            waitingForBuild = false;
            wakeup = null;
            if (search != null) {
                search.cancel();
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
