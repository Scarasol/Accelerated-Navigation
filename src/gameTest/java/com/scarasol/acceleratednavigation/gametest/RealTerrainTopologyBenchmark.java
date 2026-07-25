package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyGraphAudit;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Test-source-only benchmark that runs against a fixed-seed normal world. */
@Mod.EventBusSubscriber(modid = AcceleratedNavigation.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RealTerrainTopologyBenchmark {

    private static final int OVERWORLD_ROUTE_CHUNKS = 32;
    private static final int OVERWORLD_LOAD_MARGIN_CHUNKS = 16;
    private static final int OVERWORLD_LOAD_HALF_WIDTH_CHUNKS = 24;
    private static final int NETHER_MEDIUM_CHUNKS = 8;
    private static final int NETHER_LOAD_MARGIN_CHUNKS = 16;
    private static final int NETHER_LOAD_HALF_WIDTH_CHUNKS = 16;
    private static final int CORRIDOR_HALF_WIDTH_CHUNKS = 3;
    private static final int SURFACE_GRAPH_MARGIN_CHUNKS = 8;
    private static final int SURFACE_GRAPH_HALF_WIDTH_CHUNKS = 8;
    private static final int SURFACE_SECTION_PADDING = 2;
    private static final int LOAD_HALO_CHUNKS = 1;
    private static final long SEARCH_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long QUERY_PROGRESS_LOG_NANOS = 5_000_000_000L;
    private static final long BENCHMARK_TIMEOUT_NANOS = 600_000_000_000L;
    private static final int COLD_ROUTE_RUNS = 1;
    private static final int WARMUP_ROUTE_RUNS = 5;
    private static final int MEASURED_ROUTE_RUNS = 100;
    private static final int TOTAL_ROUTE_RUNS =
            COLD_ROUTE_RUNS + WARMUP_ROUTE_RUNS + MEASURED_ROUTE_RUNS;
    private static final int CHUNK_LOAD_BATCH_SIZE = 32;
    private static final long CHUNK_LOAD_TICK_BUDGET_NANOS = 4_000_000L;
    private static final int BUILD_BATCH_SIZE = 256;
    private static final int AUDIT_NODE_LIMIT = 16_384;
    private static final int AUDIT_EXPANSIONS_PER_TICK = 256;
    private static final long AUDIT_TICK_BUDGET_NANOS = 2_000_000L;
    private static final long AUDIT_TIMEOUT_NANOS = 30_000_000_000L;

    private static Controller controller;

    private RealTerrainTopologyBenchmark() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("acceleratedNavigation.terrainBenchmark")) {
            return;
        }
        if (controller != null) {
            throw new IllegalStateException("real terrain benchmark started twice");
        }
        controller = new Controller(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (controller != null && event.phase == TickEvent.Phase.END) {
            controller.tick();
        }
    }

    private static final class Controller {
        private final MinecraftServer server;
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final TopologyService topologyService;
        private final ArrayDeque<ChunkLoad> chunksToLoad = new ArrayDeque<>();
        private final List<ChunkLoad> heldChunks = new ArrayList<>();
        private final ArrayDeque<BuildGroup> groupsToBuild = new ArrayDeque<>();
        private final List<Map<String, Object>> buildReports = new ArrayList<>();
        private final List<Map<String, Object>> connectivityReferenceReports = new ArrayList<>();
        private final List<Map<String, Object>> routeReports = new ArrayList<>();
        private final ArrayDeque<RouteCase> routesToRun = new ArrayDeque<>();
        private final List<RouteRun> completedRoutes = new ArrayList<>();
        private final ArrayDeque<RouteRun> routesToAudit = new ArrayDeque<>();
        private final long startedNanos = System.nanoTime();
        private final long seed;
        private final boolean reusedWorld;
        private final com.sun.management.ThreadMXBean allocationBean;

        private Phase phase = Phase.LOAD_CHUNKS;
        private long chunkLoadNanos;
        private BuildGroup activeGroup;
        private TopologyService.Metrics metricsBeforeGroup;
        private List<CompletableFuture<BaseClusterTopology>> activeBuilds = List.of();
        private List<SectionRef> activeBuildSections = List.of();
        private int activeGroupCursor;
        private int activeGroupRetries;
        private BlockPos surfaceOrigin;
        private RouteRun activeRoute;
        private TopologyService.MacroQuery activeQuery;
        private CompletableFuture<MacroSearch.Corridor> activeQueryFuture;
        private long activeQueryStartedNanos;
        private long activeQueryNextProgressLogNanos;
        private long activeQueryWallNanos;
        private MacroSearch.Corridor activeQueryResult;
        private boolean activeQueryCompleted;
        private TopologyService.Metrics metricsBeforeQuery;
        private UUID activeQueryOwner;
        private long activeQueryAllocatedBytes;
        private long activeQueryGcCount;
        private long activeQueryGcMillis;
        private long activeQueryEndAllocatedBytes;
        private long activeQueryEndGcCount;
        private long activeQueryEndGcMillis;
        private RouteRun activeAuditRoute;
        private TopologyGraphAudit activeGraphAudit;
        private long activeAuditStartedNanos;

        private Controller(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
            this.overworld = server.overworld();
            this.nether = Objects.requireNonNull(server.getLevel(Level.NETHER), "nether");
            this.topologyService = TopologyService.forServer(server);
            this.seed = server.getWorldData().worldGenOptions().seed();
            this.reusedWorld = overworld.getGameTime() > 100L;
            this.allocationBean = allocationBean();
            prepareChunkQueue();
            AcceleratedNavigation.LOGGER.info(
                    "Starting real terrain topology benchmark: seed={}, chunks={}",
                    seed,
                    chunksToLoad.size()
            );
        }

        private void tick() {
            if (System.nanoTime() - startedNanos > BENCHMARK_TIMEOUT_NANOS) {
                throw new IllegalStateException("real terrain benchmark exceeded ten minutes");
            }
            switch (phase) {
                case LOAD_CHUNKS -> loadChunkBatch();
                case PREPARE_GROUPS -> prepareBuildGroups();
                case START_GROUP -> startNextGroup();
                case WAIT_GROUP -> waitForGroup();
                case PREPARE_ROUTES -> prepareRoutes();
                case START_ROUTE -> startRoute();
                case WAIT_ROUTE -> waitForRoute();
                case START_AUDIT -> startAudit();
                case WAIT_AUDIT -> waitForAudit();
                case WRITE_REPORT -> writeReportAndStop();
                case COMPLETE -> {
                }
            }
        }

        private void prepareChunkQueue() {
            ChunkPos overworldOrigin = new ChunkPos(overworld.getSharedSpawnPos());
            for (int x = -OVERWORLD_LOAD_MARGIN_CHUNKS - LOAD_HALO_CHUNKS;
                 x <= OVERWORLD_ROUTE_CHUNKS + OVERWORLD_LOAD_MARGIN_CHUNKS
                         + LOAD_HALO_CHUNKS; x++) {
                for (int z = -OVERWORLD_LOAD_HALF_WIDTH_CHUNKS - LOAD_HALO_CHUNKS;
                     z <= OVERWORLD_LOAD_HALF_WIDTH_CHUNKS + LOAD_HALO_CHUNKS; z++) {
                    chunksToLoad.addLast(new ChunkLoad(
                            overworld,
                            new ChunkPos(overworldOrigin.x + x, overworldOrigin.z + z)
                    ));
                }
            }
            for (int x = -NETHER_LOAD_MARGIN_CHUNKS - LOAD_HALO_CHUNKS;
                 x <= NETHER_MEDIUM_CHUNKS + NETHER_LOAD_MARGIN_CHUNKS
                         + LOAD_HALO_CHUNKS; x++) {
                for (int z = -NETHER_LOAD_HALF_WIDTH_CHUNKS - LOAD_HALO_CHUNKS;
                     z <= NETHER_LOAD_HALF_WIDTH_CHUNKS + LOAD_HALO_CHUNKS; z++) {
                    chunksToLoad.addLast(new ChunkLoad(nether, new ChunkPos(x, z)));
                }
            }
        }

        private void loadChunkBatch() {
            long deadline = System.nanoTime() + CHUNK_LOAD_TICK_BUDGET_NANOS;
            int loaded = 0;
            while (loaded < CHUNK_LOAD_BATCH_SIZE && System.nanoTime() < deadline) {
                ChunkLoad load = chunksToLoad.pollFirst();
                if (load == null) {
                    phase = Phase.PREPARE_GROUPS;
                    return;
                }
                long started = System.nanoTime();
                load.level.getChunkSource().addRegionTicket(
                        TicketType.FORCED,
                        load.chunk,
                        2,
                        load.chunk
                );
                load.level.getChunkSource().getChunk(
                        load.chunk.x,
                        load.chunk.z,
                        ChunkStatus.FULL,
                        true
                );
                chunkLoadNanos += System.nanoTime() - started;
                heldChunks.add(load);
                loaded++;
            }
        }

        private void prepareBuildGroups() {
            ChunkPos originChunk = new ChunkPos(overworld.getSharedSpawnPos());
            int originX = originChunk.getMinBlockX() + 8;
            int originZ = originChunk.getMinBlockZ() + 8;
            surfaceOrigin = requireSurfacePosition(overworld, originX, originZ);

            Set<TopologyService.ClusterKey> assigned = new LinkedHashSet<>();
            List<SectionRef> surface = new ArrayList<>();
            for (int chunkX = originChunk.x - SURFACE_GRAPH_MARGIN_CHUNKS;
                 chunkX <= originChunk.x + OVERWORLD_ROUTE_CHUNKS
                         + SURFACE_GRAPH_MARGIN_CHUNKS; chunkX++) {
                for (int chunkZ = originChunk.z - SURFACE_GRAPH_HALF_WIDTH_CHUNKS;
                     chunkZ <= originChunk.z + SURFACE_GRAPH_HALF_WIDTH_CHUNKS; chunkZ++) {
                    addSurfaceChunkSections(surface, assigned, chunkX, chunkZ);
                }
            }

            List<SectionRef> caves = new ArrayList<>();
            int caveMaxSection = Math.min(6, overworld.getMaxSection() - 1);
            for (int chunkX = originChunk.x; chunkX <= originChunk.x + 6; chunkX++) {
                for (int chunkZ = originChunk.z - CORRIDOR_HALF_WIDTH_CHUNKS;
                     chunkZ <= originChunk.z + CORRIDOR_HALF_WIDTH_CHUNKS; chunkZ++) {
                    for (int sectionY = overworld.getMinSection();
                         sectionY <= caveMaxSection; sectionY++) {
                        addUnique(caves, assigned, overworld, chunkX, sectionY, chunkZ);
                    }
                }
            }

            List<SectionRef> netherSections = new ArrayList<>();
            for (int chunkX = 0; chunkX <= NETHER_MEDIUM_CHUNKS; chunkX++) {
                for (int chunkZ = -CORRIDOR_HALF_WIDTH_CHUNKS;
                     chunkZ <= CORRIDOR_HALF_WIDTH_CHUNKS; chunkZ++) {
                    for (int sectionY = nether.getMinSection();
                         sectionY < nether.getMaxSection(); sectionY++) {
                        addUnique(netherSections, assigned, nether, chunkX, sectionY, chunkZ);
                    }
                }
            }

            groupsToBuild.addLast(new BuildGroup("overworld_surface", surface));
            groupsToBuild.addLast(new BuildGroup("overworld_caves", caves));
            groupsToBuild.addLast(new BuildGroup("nether", netherSections));
            phase = Phase.START_GROUP;
        }

        private void startNextGroup() {
            activeGroup = groupsToBuild.pollFirst();
            if (activeGroup == null) {
                phase = Phase.PREPARE_ROUTES;
                return;
            }
            metricsBeforeGroup = topologyService.metrics();
            activeGroupCursor = 0;
            activeGroupRetries = 0;
            submitNextBuildBatch();
            phase = Phase.WAIT_GROUP;
        }

        private void submitNextBuildBatch() {
            int end = Math.min(activeGroup.sections.size(), activeGroupCursor + BUILD_BATCH_SIZE);
            submitBuildSections(activeGroup.sections.subList(activeGroupCursor, end));
            activeGroupCursor = end;
        }

        private void submitBuildSections(List<SectionRef> sections) {
            activeBuildSections = List.copyOf(sections);
            List<CompletableFuture<BaseClusterTopology>> requests = new ArrayList<>();
            for (SectionRef section : activeBuildSections) {
                requests.add(topologyService.requestCluster(
                        section.level,
                        section.section,
                        NavigationScheduler.Priority.BACKGROUND
                ));
            }
            activeBuilds = List.copyOf(requests);
        }

        private void waitForGroup() {
            if (activeBuilds.stream().anyMatch(future -> !future.isDone())) {
                return;
            }
            List<CompletableFuture<BaseClusterTopology>> retried = new ArrayList<>(activeBuilds);
            boolean retrying = false;
            for (int index = 0; index < activeBuilds.size(); index++) {
                CompletableFuture<BaseClusterTopology> future = activeBuilds.get(index);
                try {
                    future.join();
                } catch (RuntimeException failure) {
                    if (++activeGroupRetries > activeGroup.sections.size() * 20) {
                        throw new IllegalStateException(
                                "terrain remained unstable while building " + activeGroup.name,
                                failure
                        );
                    }
                    SectionRef section = activeBuildSections.get(index);
                    retried.set(index, topologyService.requestCluster(
                            section.level,
                            section.section,
                            NavigationScheduler.Priority.BACKGROUND
                    ));
                    retrying = true;
                }
            }
            if (retrying) {
                activeBuilds = List.copyOf(retried);
                return;
            }
            if (activeGroupCursor < activeGroup.sections.size()) {
                submitNextBuildBatch();
                return;
            }
            List<SectionRef> invalidated = activeGroup.sections.stream()
                    .filter(section -> topologyService.topology(section.key()) == null)
                    .limit(BUILD_BATCH_SIZE)
                    .toList();
            if (!invalidated.isEmpty()) {
                activeGroupRetries += invalidated.size();
                if (activeGroupRetries > activeGroup.sections.size() * 20) {
                    throw new IllegalStateException(
                            "terrain did not stabilize while publishing " + activeGroup.name
                    );
                }
                submitBuildSections(invalidated);
                return;
            }
            TopologyService.Metrics after = topologyService.metrics();
            buildReports.add(buildReport(
                    activeGroup,
                    metricsBeforeGroup,
                    after,
                    activeGroupRetries
            ));
            completedGroups.add(activeGroup);
            activeBuilds = List.of();
            activeBuildSections = List.of();
            activeGroup = null;
            phase = Phase.START_GROUP;
        }

        private Map<String, Object> buildReport(BuildGroup group,
                                                TopologyService.Metrics before,
                                                TopologyService.Metrics after,
                                                int retries) {
            int[] components = new int[group.sections.size()];
            int[] connections = new int[group.sections.size()];
            long retained = 0L;
            int maximumLocalDegree = 0;
            for (int index = 0; index < group.sections.size(); index++) {
                SectionRef section = group.sections.get(index);
                BaseClusterTopology topology = topologyService.topology(section.key());
                if (topology == null) {
                    throw new IllegalStateException("topology was not published for " + section.key());
                }
                components[index] = topology.components().size();
                connections[index] = topology.localConnections().size();
                for (BaseClusterTopology.Component component : topology.components()) {
                    maximumLocalDegree = Math.max(
                            maximumLocalDegree,
                            topology.outgoingConnections(component.id()).size()
                    );
                }
                retained += topology.retainedBytes();
            }
            Arrays.sort(components);
            Arrays.sort(connections);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("name", group.name);
            report.put("sections", group.sections.size());
            report.put("coveredChunks", group.sections.stream()
                    .map(section -> new ChunkPos(section.section.x(), section.section.z()).toLong())
                    .distinct()
                    .count());
            report.put("sectionEnvelope", sectionEnvelope(group.sections));
            report.put("staleSnapshotRetries", retries);
            report.put("staleWorkerAttempts", after.staleBuilds() - before.staleBuilds());
            report.put("coalescedInvalidations",
                    after.coalescedInvalidations() - before.coalescedInvalidations());
            report.put("snapshotMillis", nanosToMillis(after.snapshotNanos() - before.snapshotNanos()));
            report.put("workerBuildMillis", nanosToMillis(after.buildNanos() - before.buildNanos()));
            report.put("averageSnapshotMicrosPerSection",
                    nanosToMicros(after.snapshotNanos() - before.snapshotNanos()) / group.sections.size());
            report.put("averageWorkerBuildMicrosPerSection",
                    nanosToMicros(after.buildNanos() - before.buildNanos()) / group.sections.size());
            report.put("componentsMin", components.length == 0 ? 0 : components[0]);
            report.put("componentsP50", percentile(components, 0.50D));
            report.put("componentsP95", percentile(components, 0.95D));
            report.put("componentsMax", components.length == 0 ? 0 : components[components.length - 1]);
            report.put("localConnectionsP50", percentile(connections, 0.50D));
            report.put("localConnectionsP95", percentile(connections, 0.95D));
            report.put("localConnectionsMax", connections.length == 0 ? 0 : connections[connections.length - 1]);
            report.put("maximumLocalDegree", maximumLocalDegree);
            report.put("retainedBytes", retained);
            report.put("buildWorkerQueue", workerDelta(
                    before.buildWorker(),
                    after.buildWorker()
            ));
            report.put("persistenceWorkerQueue", workerDelta(
                    before.persistenceWorker(),
                    after.persistenceWorker()
            ));
            return report;
        }

        private void prepareRoutes() {
            verifyHeldChunksLoaded();
            TopologyGraphAudit.PublishedConnectivity overworldConnectivity =
                    TopologyGraphAudit.indexPublished(
                            topologyService,
                            overworld.dimension(),
                            indexedSections(overworld),
                            BaseClusterTopology.Channel.GROUND,
                            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
                    );
            TopologyGraphAudit.PublishedConnectivity netherConnectivity =
                    TopologyGraphAudit.indexPublished(
                            topologyService,
                            nether.dimension(),
                            indexedSections(nether),
                            BaseClusterTopology.Channel.GROUND,
                            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
                    );
            connectivityReferenceReports.add(overworldConnectivity.report());
            connectivityReferenceReports.add(netherConnectivity.report());

            List<RouteCase> cases = new ArrayList<>();
            List<BlockPos> surfaceCandidates = representativeSurfacePositions();
            for (int distance : List.of(8, 96, 512)) {
                addConnectedPair(
                        cases,
                        "surface_" + distance,
                        overworld,
                        overworldConnectivity,
                        surfaceCandidates,
                        distance
                );
            }

            List<BlockPos> caveCandidates = representativeWalkablePositions(
                    "overworld_caves",
                    true
            );
            List<BlockPos> netherCandidates = representativeWalkablePositions("nether", false);
            if (caveCandidates.isEmpty() || netherCandidates.isEmpty()) {
                throw new IllegalStateException(
                        "fixed-seed terrain did not expose cave and nether walkable samples"
                );
            }
            addConnectedPair(
                    cases,
                    "cave_8",
                    overworld,
                    overworldConnectivity,
                    caveCandidates,
                    8
            );
            addConnectedPair(
                    cases,
                    "cave_96",
                    overworld,
                    overworldConnectivity,
                    caveCandidates,
                    96
            );
            addConnectedPair(
                    cases,
                    "nether_8",
                    nether,
                    netherConnectivity,
                    netherCandidates,
                    8
            );
            addConnectedPair(
                    cases,
                    "nether_96",
                    nether,
                    netherConnectivity,
                    netherCandidates,
                    96
            );

            for (BuildGroup group : completedGroups) {
                for (SectionRef section : group.sections) {
                    topologyService.invalidate(section.key());
                }
            }
            for (RouteCase routeCase : cases) {
                AcceleratedNavigation.LOGGER.info(
                        "Selected structurally connected real terrain route: case={}, dimension={}, "
                                + "start={}, goal={}, directDistance={}, sourceSccNodes={}, "
                                + "targetSccNodes={}, sameScc={}",
                        routeCase.name,
                        routeCase.level.dimension().location(),
                        routeCase.start,
                        routeCase.goal,
                        distance(routeCase.start, routeCase.goal),
                        routeCase.selection.sourceStrongComponentNodes(),
                        routeCase.selection.targetStrongComponentNodes(),
                        routeCase.selection.sourceStrongComponentId()
                                == routeCase.selection.targetStrongComponentId()
                );
            }
            routesToRun.addAll(cases);
            phase = Phase.START_ROUTE;
        }

        private List<SectionPos> indexedSections(ServerLevel level) {
            return completedGroups.stream()
                    .flatMap(group -> group.sections.stream())
                    .filter(section -> section.level == level)
                    .map(SectionRef::section)
                    .distinct()
                    .toList();
        }

        private void verifyHeldChunksLoaded() {
            for (ChunkLoad held : heldChunks) {
                if (held.level.getChunkSource().getChunkNow(held.chunk.x, held.chunk.z) == null) {
                    throw new IllegalStateException("benchmark chunk unloaded before route timing: "
                            + held.level.dimension().location() + " " + held.chunk);
                }
            }
        }

        private List<BlockPos> representativeWalkablePositions(String groupName, boolean caveOnly) {
            BuildGroup group = findCompletedGroup(groupName);
            List<BlockPos> result = new ArrayList<>();
            for (SectionRef section : group.sections) {
                BlockPos position = representativeWalkablePosition(section, caveOnly);
                if (position != null) {
                    result.add(position);
                }
            }
            return result;
        }

        private List<BlockPos> representativeSurfacePositions() {
            ChunkPos origin = new ChunkPos(overworld.getSharedSpawnPos());
            Set<BlockPos> result = new LinkedHashSet<>();
            result.add(surfaceOrigin);
            for (int chunkX = origin.x; chunkX <= origin.x + OVERWORLD_ROUTE_CHUNKS; chunkX++) {
                for (int chunkZ = origin.z - CORRIDOR_HALF_WIDTH_CHUNKS;
                     chunkZ <= origin.z + CORRIDOR_HALF_WIDTH_CHUNKS; chunkZ++) {
                    for (int localZ = 2; localZ < 16; localZ += 4) {
                        for (int localX = 2; localX < 16; localX += 4) {
                            BlockPos position = findSurfacePosition(
                                    overworld,
                                    (chunkX << 4) + localX,
                                    (chunkZ << 4) + localZ
                            );
                            if (position != null) {
                                result.add(position);
                            }
                        }
                    }
                }
            }
            return List.copyOf(result);
        }

        private BuildGroup findCompletedGroup(String groupName) {
            return completedGroups.stream()
                    .filter(group -> group.name.equals(groupName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("missing completed group " + groupName));
        }

        private BlockPos representativeWalkablePosition(SectionRef section, boolean caveOnly) {
            int minX = section.section.minBlockX();
            int minY = section.section.minBlockY();
            int minZ = section.section.minBlockZ();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = 1; y < 15; y++) {
                for (int z = 2; z < 16; z += 4) {
                    for (int x = 2; x < 16; x += 4) {
                        cursor.set(minX + x, minY + y, minZ + z);
                        if (pathType(section.level, cursor) != BlockPathTypes.WALKABLE) {
                            continue;
                        }
                        if (caveOnly) {
                            int surface = section.level.getHeight(
                                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                    cursor.getX(),
                                    cursor.getZ()
                            );
                            if (cursor.getY() >= surface - 8 || section.level.canSeeSky(cursor)) {
                                continue;
                            }
                        }
                        return cursor.immutable();
                    }
                }
            }
            return null;
        }

        private void startRoute() {
            if (activeRoute == null) {
                RouteCase routeCase = routesToRun.pollFirst();
                if (routeCase == null) {
                    routesToAudit.addAll(completedRoutes);
                    phase = Phase.START_AUDIT;
                    return;
                }
                activeRoute = new RouteRun(routeCase);
            }
            if (activeRoute.run >= TOTAL_ROUTE_RUNS) {
                completedRoutes.add(activeRoute);
                activeRoute = null;
                phase = Phase.START_ROUTE;
                return;
            }

            RouteCase routeCase = activeRoute.routeCase;
            activeQuery = topologyService.macroQuery(
                    routeCase.level,
                    routeCase.start,
                    routeCase.goal,
                    BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.ACTIVE
            );
            activeQueryOwner = UUID.nameUUIDFromBytes(("terrain-route:" + routeCase.name + ":" + activeRoute.run)
                    .getBytes(StandardCharsets.UTF_8));
            metricsBeforeQuery = topologyService.metrics();
            activeQueryAllocatedBytes = allocatedBytes(allocationBean);
            activeQueryGcCount = gcCollectionCount();
            activeQueryGcMillis = gcCollectionMillis();
            activeQueryStartedNanos = System.nanoTime();
            activeQueryNextProgressLogNanos = activeQueryStartedNanos + QUERY_PROGRESS_LOG_NANOS;
            activeQueryWallNanos = 0L;
            activeQueryResult = null;
            activeQueryCompleted = false;
            activeQueryFuture = NavigationScheduler.forServer(server).submitStrict(
                    routeCase.level.dimension(),
                    activeQueryOwner,
                    NavigationScheduler.Priority.ACTIVE,
                    activeQuery
            );
            phase = Phase.WAIT_ROUTE;
        }

        private void waitForRoute() {
            long now = System.nanoTime();
            if (now >= activeQueryNextProgressLogNanos) {
                logActiveQueryState("progress", now);
                activeQueryNextProgressLogNanos = now + QUERY_PROGRESS_LOG_NANOS;
            }
            if (now - activeQueryStartedNanos > SEARCH_TIMEOUT_NANOS) {
                recordQueryTimeout(now);
                return;
            }
            if (!activeQueryCompleted) {
                if (!activeQueryFuture.isDone()) {
                    return;
                }
                try {
                    activeQueryResult = activeQueryFuture.join();
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("macro query failed exceptionally for "
                            + activeRoute.routeCase.name, failure);
                }
                activeQueryWallNanos = System.nanoTime() - activeQueryStartedNanos;
                activeQueryEndAllocatedBytes = allocatedBytes(allocationBean);
                activeQueryEndGcCount = gcCollectionCount();
                activeQueryEndGcMillis = gcCollectionMillis();
                activeQueryCompleted = true;
            }
            TopologyService.QueryMetrics queryMetrics = activeQuery.metrics();
            if (queryMetrics.completedSections() + queryMetrics.failedSections()
                    < queryMetrics.requestedSections()
                    || queryMetrics.completedSuperClusters() + queryMetrics.failedSuperClusters()
                    < queryMetrics.requestedSuperClusters()
                    || queryMetrics.completedBaseBoundaryLinks()
                    + queryMetrics.failedBaseBoundaryLinks()
                    < queryMetrics.requestedBaseBoundaryLinks()
                    || queryMetrics.completedSuperBoundaryLinks()
                    + queryMetrics.failedSuperBoundaryLinks()
                    < queryMetrics.requestedSuperBoundaryLinks()) {
                return;
            }
            TopologyService.Metrics after = topologyService.metrics();
            MacroSearch.Metrics searchMetrics = queryMetrics.searchMetrics();
            if (activeQuery.failure() == MacroSearch.Failure.UNAVAILABLE_CHUNK
                    || (searchMetrics != null && searchMetrics.unavailableSections() != 0)) {
                    throw new IllegalStateException(
                            "benchmark encountered an unknown/unloaded boundary for "
                                    + activeRoute.routeCase.name
                                + " start=" + activeRoute.routeCase.start
                                + " goal=" + activeRoute.routeCase.goal
                                + " failure=" + activeQuery.failure()
                                + " blocked=" + activeQuery.blockedSection()
                                    + " metrics=" + searchMetrics
                                    + ", unavailableDependencies="
                                    + unavailableDependencyDiagnostics(activeQuery)
                                    + " loadedEnvelope=" + loadedEnvelope(activeRoute.routeCase.level)
                    );
                }
            activeRoute.record(
                    activeQuery,
                    activeQueryResult,
                    activeQueryWallNanos,
                    queryMetrics,
                    metricsBeforeQuery,
                    after,
                    metricDelta(activeQueryAllocatedBytes, activeQueryEndAllocatedBytes),
                    metricDelta(activeQueryGcCount, activeQueryEndGcCount),
                    metricDelta(activeQueryGcMillis, activeQueryEndGcMillis)
            );
            activeQuery = null;
            activeQueryFuture = null;
            activeQueryOwner = null;
            activeQueryResult = null;
            activeRoute.run++;
            phase = Phase.START_ROUTE;
        }

        private void recordQueryTimeout(long now) {
            long endAllocatedBytes = allocatedBytes(allocationBean);
            long endGcCount = gcCollectionCount();
            long endGcMillis = gcCollectionMillis();
            String state = activeQueryState("timeout", now);
            TopologyService.QueryMetrics queryMetrics = activeQuery.metrics();
            NavigationScheduler.forServer(server).cancel(
                    activeRoute.routeCase.level.dimension(),
                    activeQueryOwner
            );
            TopologyService.Metrics after = topologyService.metrics();
            activeRoute.recordTimeout(
                    activeQuery,
                    now - activeQueryStartedNanos,
                    queryMetrics,
                    metricsBeforeQuery,
                    after,
                    metricDelta(activeQueryAllocatedBytes, endAllocatedBytes),
                    metricDelta(activeQueryGcCount, endGcCount),
                    metricDelta(activeQueryGcMillis, endGcMillis)
            );
            AcceleratedNavigation.LOGGER.warn("{}; recorded as an inconclusive harness timeout", state);
            activeQuery = null;
            activeQueryFuture = null;
            activeQueryOwner = null;
            activeQueryResult = null;
            activeRoute.run++;
            phase = Phase.START_ROUTE;
        }

        private void startAudit() {
            activeAuditRoute = routesToAudit.pollFirst();
            if (activeAuditRoute == null) {
                phase = Phase.WRITE_REPORT;
                return;
            }
            activeAuditStartedNanos = System.nanoTime();
            if (activeAuditRoute.sampleCorridor != null) {
                activeAuditRoute.corridorAudit = TopologyGraphAudit.auditCorridor(
                        topologyService,
                        activeAuditRoute.routeCase.level.dimension(),
                        activeAuditRoute.sampleCorridor,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
                );
                runVanillaCorridorProbe(activeAuditRoute);
                completeAudit(activeAuditRoute);
                return;
            }

            activeGraphAudit = new TopologyGraphAudit(
                    topologyService,
                    activeAuditRoute.routeCase.level,
                    activeAuditRoute.routeCase.start,
                    activeAuditRoute.routeCase.goal,
                    BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    AUDIT_NODE_LIMIT
            );
            phase = Phase.WAIT_AUDIT;
        }

        private void waitForAudit() {
            if (System.nanoTime() - activeAuditStartedNanos > AUDIT_TIMEOUT_NANOS) {
                activeGraphAudit.stop("REFERENCE_WALL_TIMEOUT");
            }
            long deadline = System.nanoTime() + AUDIT_TICK_BUDGET_NANOS;
            TopologyGraphAudit.Status status = activeGraphAudit.step(
                    AUDIT_EXPANSIONS_PER_TICK,
                    deadline
            );
            if (status == TopologyGraphAudit.Status.RUNNING) {
                return;
            }
            activeAuditRoute.baseGraphAudit = activeGraphAudit.report();
            if (status == TopologyGraphAudit.Status.FOUND) {
                runVanillaWitnessProbe(activeAuditRoute, activeGraphAudit.witnessAnchors());
            }
            completeAudit(activeAuditRoute);
        }

        private void runVanillaCorridorProbe(RouteRun route) {
            try (VanillaGroundProbe probe = new VanillaGroundProbe(route.routeCase.level)) {
                route.vanillaProbe = probe.firstWindow(route.sampleCorridor);
            }
        }

        private void runVanillaWitnessProbe(RouteRun route, List<BlockPos> anchors) {
            if (anchors.size() < 2) {
                return;
            }
            try (VanillaGroundProbe probe = new VanillaGroundProbe(route.routeCase.level)) {
                route.vanillaProbe = probe.firstWitnessWindow(anchors);
            }
        }

        private void completeAudit(RouteRun route) {
            route.referenceWallNanos = System.nanoTime() - activeAuditStartedNanos;
            route.assessAnswer();
            routeReports.add(finishRoute(route));
            AcceleratedNavigation.LOGGER.info(
                    "Route answer audit complete: case={}, macroFailure={}, assessment={}, referenceMs={}",
                    route.routeCase.name,
                    route.failure,
                    route.answerAssessment,
                    nanosToMillis(route.referenceWallNanos)
            );
            activeAuditRoute = null;
            activeGraphAudit = null;
            phase = Phase.START_AUDIT;
        }

        private void logActiveQueryState(String reason, long now) {
            AcceleratedNavigation.LOGGER.info(activeQueryState(reason, now));
        }

        private String activeQueryState(String reason, long now) {
            NavigationScheduler scheduler = NavigationScheduler.forServer(server);
            return "Macro query " + reason
                    + ": case=" + activeRoute.routeCase.name
                    + ", run=" + activeRoute.run
                    + ", elapsedMs=" + nanosToMillis(now - activeQueryStartedNanos)
                    + ", futureDone=" + activeQueryFuture.isDone()
                    + ", failure=" + activeQuery.failure()
                    + ", blocked=" + activeQuery.blockedSection()
                    + ", query=" + activeQuery.metrics()
                    + ", topology=" + topologyService.metrics()
                    + ", schedulerCapacity=" + scheduler.admissionCapacity();
        }

        private Map<String, Object> finishRoute(RouteRun route) {
            long[] macroNanos = route.macroNanos.clone();
            long[] superNanos = route.superNanos.clone();
            long[] refinementNanos = route.refinementNanos.clone();
            long[] queryCpuNanos = route.queryCpuNanos.clone();
            long[] wallNanos = route.wallNanos.clone();
            long[] allocatedBytes = route.allocatedBytes.clone();
            long[] buildQueueWaitNanos = route.buildQueueWaitNanos.clone();
            long[] persistenceQueueWaitNanos = route.persistenceQueueWaitNanos.clone();
            Arrays.sort(macroNanos);
            Arrays.sort(superNanos);
            Arrays.sort(refinementNanos);
            Arrays.sort(queryCpuNanos);
            Arrays.sort(wallNanos);
            Arrays.sort(allocatedBytes);
            Arrays.sort(buildQueueWaitNanos);
            Arrays.sort(persistenceQueueWaitNanos);
            RouteCase routeCase = route.routeCase;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("name", routeCase.name);
            report.put("dimension", routeCase.level.dimension().location().toString());
            report.put("start", positionMap(routeCase.start));
            report.put("goal", positionMap(routeCase.goal));
            report.put("requestedStraightLineBlocks", routeCase.requestedDistance);
            report.put("straightLineBlocks", distance(routeCase.start, routeCase.goal));
            report.put("startBiome", biome(routeCase.level, routeCase.start));
            report.put("goalBiome", biome(routeCase.level, routeCase.goal));
            report.put("coldRuns", COLD_ROUTE_RUNS);
            report.put("warmupRuns", WARMUP_ROUTE_RUNS);
            report.put("measuredWarmRuns", MEASURED_ROUTE_RUNS);
            report.put("runs", MEASURED_ROUTE_RUNS);
            report.put("totalInvocations", TOTAL_ROUTE_RUNS);
            report.put("succeededRuns", route.succeeded);
            report.put("timedOutRuns", route.timedOut);
            report.put("lastFailure", route.failure);
            report.put("failureCounts", route.failureCounts);
            report.put("firstBlockedSection", route.blockedSection == null
                    ? null
                    : Map.of("x", route.blockedSection.x(), "y", route.blockedSection.y(),
                    "z", route.blockedSection.z()));
            report.put("failureStage", route.succeeded > 0 ? "NONE" : "MACRO_TOPOLOGY");
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("assessment", route.answerAssessment);
            validation.put("answerCheck", route.macroAnswerCorrect == null
                    ? "INCONCLUSIVE"
                    : route.macroAnswerCorrect ? "PASS" : "FAIL");
            validation.put("macroAnswerCorrect", route.macroAnswerCorrect);
            validation.put("reachabilityConclusion", route.reachabilityConclusion);
            validation.put("outcomesConsistent", route.resolvedOutcomesConsistent());
            validation.put("referenceTimingExcludedFromMacroTiming", true);
            validation.put("selectionReference", routeCase.selection.report());
            validation.put("referenceWallMillis", nanosToMillis(route.referenceWallNanos));
            validation.put("corridorAudit", route.corridorAudit);
            validation.put("baseGraphAudit", route.baseGraphAudit);
            validation.put("vanillaPositiveWitness",
                    route.vanillaProbe == null ? null : route.vanillaProbe.report());
            report.put("answerValidation", validation);
            report.put("p50MacroMillis", nanosToMillis(percentile(macroNanos, 0.50D)));
            report.put("p95MacroMillis", nanosToMillis(percentile(macroNanos, 0.95D)));
            report.put("p99MacroMillis", nanosToMillis(percentile(macroNanos, 0.99D)));
            report.put("maxMacroMillis", nanosToMillis(macroNanos[macroNanos.length - 1]));
            report.put("p50SuperSearchMillis", nanosToMillis(percentile(superNanos, 0.50D)));
            report.put("p95SuperSearchMillis", nanosToMillis(percentile(superNanos, 0.95D)));
            report.put("p99SuperSearchMillis", nanosToMillis(percentile(superNanos, 0.99D)));
            report.put("maxSuperSearchMillis", nanosToMillis(superNanos[superNanos.length - 1]));
            report.put("p50BaseRefinementMillis",
                    nanosToMillis(percentile(refinementNanos, 0.50D)));
            report.put("p95BaseRefinementMillis",
                    nanosToMillis(percentile(refinementNanos, 0.95D)));
            report.put("p99BaseRefinementMillis",
                    nanosToMillis(percentile(refinementNanos, 0.99D)));
            report.put("maxBaseRefinementMillis",
                    nanosToMillis(refinementNanos[refinementNanos.length - 1]));
            report.put("p50QueryCpuMillis", nanosToMillis(percentile(queryCpuNanos, 0.50D)));
            report.put("p95QueryCpuMillis", nanosToMillis(percentile(queryCpuNanos, 0.95D)));
            report.put("p99QueryCpuMillis", nanosToMillis(percentile(queryCpuNanos, 0.99D)));
            report.put("maxQueryCpuMillis", nanosToMillis(queryCpuNanos[queryCpuNanos.length - 1]));
            report.put("coldMacroMillis", nanosToMillis(route.coldMacroNanos));
            report.put("coldSuperSearchMillis", nanosToMillis(route.coldSuperNanos));
            report.put("coldBaseRefinementMillis", nanosToMillis(route.coldRefinementNanos));
            report.put("coldQueryCpuMillis", nanosToMillis(route.coldQueryCpuNanos));
            report.put("coldWallLatencyMillis", nanosToMillis(route.coldWallNanos));
            report.put("coldObservedServerThreadAllocatedBytes", route.coldAllocatedBytes);
            report.put("coldObservedGcCollectionCountDelta", route.coldGcCount);
            report.put("coldObservedGcPauseMillisDelta", route.coldGcMillis);
            report.put("p50WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.50D)));
            report.put("p95WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.95D)));
            report.put("p99WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.99D)));
            report.put("maxWallLatencyMillis", nanosToMillis(wallNanos[wallNanos.length - 1]));
            report.put("coldSnapshotMillis", nanosToMillis(route.coldSnapshotNanos));
            report.put("coldWorkerBuildMillis", nanosToMillis(route.coldWorkerNanos));
            report.put("coldSuperBuildMillis", nanosToMillis(route.coldSuperWorkerNanos));
            report.put("coldBaseBoundaryBuildMillis",
                    nanosToMillis(route.coldBaseBoundaryBuildNanos));
            report.put("coldSuperBoundaryBuildMillis",
                    nanosToMillis(route.coldSuperBoundaryBuildNanos));
            report.put("coldBuildWorkerQueueWaitMillis",
                    nanosToMillis(route.coldBuildQueueWaitNanos));
            report.put("p50BuildWorkerQueueWaitMillis",
                    nanosToMillis(percentile(buildQueueWaitNanos, 0.50D)));
            report.put("p95BuildWorkerQueueWaitMillis",
                    nanosToMillis(percentile(buildQueueWaitNanos, 0.95D)));
            report.put("coldPersistenceWorkerQueueWaitMillis",
                    nanosToMillis(route.coldPersistenceQueueWaitNanos));
            report.put("p50PersistenceWorkerQueueWaitMillis",
                    nanosToMillis(percentile(persistenceQueueWaitNanos, 0.50D)));
            report.put("p95PersistenceWorkerQueueWaitMillis",
                    nanosToMillis(percentile(persistenceQueueWaitNanos, 0.95D)));
            report.put("buildWorkerTasksSubmitted", route.buildTasksSubmitted);
            report.put("buildWorkerTasksPromoted", route.buildTasksPromoted);
            report.put("buildWorkerTasksCancelled", route.buildTasksCancelled);
            report.put("persistenceWorkerTasksSubmitted", route.persistenceTasksSubmitted);
            report.put("persistenceWorkerTasksPromoted", route.persistenceTasksPromoted);
            report.put("persistenceWorkerTasksCancelled", route.persistenceTasksCancelled);
            report.put("workerQueueMetricScope",
                    "scheduler-wide deltas observed during each query window");
            report.put("macroNavigationCalls", 0);
            report.put("hierarchicalRuns", route.hierarchicalRuns);
            report.put("averageExpandedNodes", (double) route.expanded / MEASURED_ROUTE_RUNS);
            report.put("averageGeneratedConnections", (double) route.generated / MEASURED_ROUTE_RUNS);
            report.put("averageSuperExpandedNodes", (double) route.superExpanded / MEASURED_ROUTE_RUNS);
            report.put("averageSuperGeneratedConnections", (double) route.superGenerated / MEASURED_ROUTE_RUNS);
            report.put("averageRefinementExpandedNodes",
                    (double) route.refinementExpanded / MEASURED_ROUTE_RUNS);
            report.put("averageRefinementGeneratedConnections",
                    (double) route.refinementGenerated / MEASURED_ROUTE_RUNS);
            report.put("averageReopenedNodes", (double) route.reopened / MEASURED_ROUTE_RUNS);
            report.put("averageReexpandedBlockedNodes",
                    (double) route.reexpandedBlocked / MEASURED_ROUTE_RUNS);
            report.put("maximumExpandedDegree", route.maximumDegree);
            report.put("maximumBlockedNodes", route.maximumBlockedNodes);
            report.put("requestedTopologySections", route.requestedSections);
            report.put("completedTopologySections", route.completedSections);
            report.put("failedTopologySections", route.failedSections);
            report.put("requestedSuperClusters", route.requestedSuperClusters);
            report.put("completedSuperClusters", route.completedSuperClusters);
            report.put("failedSuperClusters", route.failedSuperClusters);
            report.put("requestedBaseBoundaryLinks", route.requestedBaseBoundaryLinks);
            report.put("completedBaseBoundaryLinks", route.completedBaseBoundaryLinks);
            report.put("failedBaseBoundaryLinks", route.failedBaseBoundaryLinks);
            report.put("requestedSuperBoundaryLinks", route.requestedSuperBoundaryLinks);
            report.put("completedSuperBoundaryLinks", route.completedSuperBoundaryLinks);
            report.put("failedSuperBoundaryLinks", route.failedSuperBoundaryLinks);
            report.put("parkCount", route.parkCount);
            report.put("wakeCount", route.wakeCount);
            report.put("staleSearchRestarts", route.staleRestarts);
            report.put("p50ObservedServerThreadAllocatedBytes",
                    percentile(allocatedBytes, 0.50D));
            report.put("p95ObservedServerThreadAllocatedBytes",
                    percentile(allocatedBytes, 0.95D));
            report.put("p99ObservedServerThreadAllocatedBytes",
                    percentile(allocatedBytes, 0.99D));
            report.put("maxObservedServerThreadAllocatedBytes",
                    allocatedBytes[allocatedBytes.length - 1]);
            report.put("observedGcCollectionCountDelta", route.gcCount);
            report.put("observedGcPauseMillisDelta", route.gcMillis);
            report.put("allocationAndGcScope",
                    "server-thread/process deltas across the scheduled query wall window");
            report.put("baseBoundaryCache", route.baseBoundaryCacheReport());
            report.put("superBoundaryCache", route.superBoundaryCacheReport());
            report.put("unknownBoundarySections", 0);
            report.put("fineProbeRuns", route.vanillaProbe == null ? 0 : 1);
            report.put("fineProbeSucceededRuns",
                    route.vanillaProbe != null && route.vanillaProbe.reachable() ? 1 : 0);
            report.put("averageFirstWindowFineMillis",
                    route.vanillaProbe == null ? 0.0D
                            : nanosToMillis(route.vanillaProbe.spentNanos()));
            report.put("corridorConnections", route.corridorConnections);
            return report;
        }

        private void writeReportAndStop() {
            TopologyService.Metrics topologyMetrics = topologyService.metrics();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("benchmarkKind", "real_generated_terrain");
            report.put("seed", seed);
            report.put("worldPreset", "minecraft:normal");
            report.put("reusedWorld", reusedWorld);
            report.put("chunkGenerationAndLoadMillis", nanosToMillis(chunkLoadNanos));
            report.put("chunkGenerationExcludedFromTopologyTiming", true);
            report.put("loadedChunks", heldChunks.size());
            report.put("answerValidationProtocol", Map.of(
                    "macroResult", "system under test",
                    "routeSelection",
                    "ordered endpoints are preselected by directed reachability in the published base-component graph",
                    "hierarchyDifferential",
                    "failed routes are searched independently on the published base-component graph",
                    "vanillaReference",
                    "positive local executability witness only; bounded failure never proves unreachable",
                    "negativeAnswerRule",
                    "NO_STRUCTURAL_ROUTE may only be corroborated as base-graph disconnection, never physical unreachability",
                    "timingIsolation",
                    "all reference checks run after all measured macro queries"
            ));
            report.put("connectivityReferences", connectivityReferenceReports);
            report.put("topologyWorkers", Map.of(
                    "build", workerReport(topologyMetrics.buildWorker()),
                    "persistence", workerReport(topologyMetrics.persistenceWorker())
            ));
            report.put("superTopology", Map.of(
                    "buildRequests", topologyMetrics.superBuildRequests(),
                    "workerBuildMillis", nanosToMillis(topologyMetrics.superBuildNanos()),
                    "published", topologyMetrics.publishedSuperClusters(),
                    "staleBuilds", topologyMetrics.staleSuperBuilds(),
                    "evicted", topologyMetrics.evictedSuperClusters(),
                    "cached", topologyMetrics.cachedSuperClusters(),
                    "retainedBytes", topologyMetrics.superRetainedBytes()
            ));
            report.put("boundaryLinkCaches", Map.of(
                    "base", serviceLinkCacheReport(topologyMetrics.baseBoundaryLinks()),
                    "super", serviceLinkCacheReport(topologyMetrics.superBoundaryLinks())
            ));
            report.put("topologyBuilds", buildReports);
            report.put("routes", routeReports);
            report.put("limitations", List.of(
                    "Macro timing performs zero PathNavigation calls.",
                    "Each route reports 100 measured warm samples after one cold run and five warm-up runs.",
                    "Routes spanning at least one complete intervening 32-cubed cluster use the in-memory super graph.",
                    "Every returned corridor is refined back to base components before it is reported.",
                    "Answer validation runs only after every timed route has completed.",
                    "Endpoint selection and the base-graph reachability index run before invalidation and outside every macro timing sample.",
                    "The selection reference proves base-graph structural reachability, not complete Minecraft physical execution.",
                    "A hierarchy-independent BFS over the published base-component graph audits failed macro answers.",
                    "Vanilla Zombie PathNavigation is a positive local witness only; null or canReach=false is inconclusive because vanilla search is bounded.",
                    "Neither base-graph exhaustion nor a vanilla failure proves physical Minecraft terrain unreachable.",
                    "Cold macro queries build loaded missing topology across scheduler ticks.",
                    "Every timed route rejects unloaded or unknown boundary observations.",
                    "Route endpoints are screened with Minecraft path types and selected by directed start-to-goal base-graph reachability.",
                    "Published build samples are invalidated before route timing so queries exercise TOPOLOGY_PENDING.",
                    "The 512-block surface route has " + OVERWORLD_LOAD_MARGIN_CHUNKS
                            + " longitudinal and " + OVERWORLD_LOAD_HALF_WIDTH_CHUNKS
                            + " lateral loaded chunks of margin.",
                    "Cave and Nether pairs are selected from generated walkable samples.",
                    "Synthetic microbenchmark results are stored in a separate report."
            ));
            String configured = System.getProperty("acceleratedNavigation.terrainReport");
            java.nio.file.Path output = configured == null
                    ? java.nio.file.Path.of("build", "reports", "real-terrain-topology.json")
                    : java.nio.file.Path.of(configured);
            try {
                Files.createDirectories(output.getParent());
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                Files.writeString(output, gson.toJson(report), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("could not write real terrain benchmark report", exception);
            }
            releaseTickets();
            AcceleratedNavigation.LOGGER.info(
                    "Real terrain topology benchmark complete: report={}",
                    output.toAbsolutePath()
            );
            phase = Phase.COMPLETE;
            controller = null;
            server.halt(false);
        }

        private final List<BuildGroup> completedGroups = new ArrayList<>();

        private void releaseTickets() {
            for (ChunkLoad load : heldChunks) {
                load.level.getChunkSource().removeRegionTicket(
                        TicketType.FORCED,
                        load.chunk,
                        2,
                        load.chunk
                );
            }
        }

        private BlockPos requireSurfacePosition(ServerLevel level, int desiredX, int desiredZ) {
            BlockPos position = findSurfacePosition(level, desiredX, desiredZ);
            if (position != null) {
                return position;
            }
            throw new IllegalStateException(
                    "no surface walkable position near " + desiredX + "," + desiredZ
            );
        }

        @Nullable
        private BlockPos findSurfacePosition(ServerLevel level, int desiredX, int desiredZ) {
            for (int radius = 0; radius <= 8; radius++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        int x = desiredX + dx;
                        int z = desiredZ + dz;
                        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                        for (int y = top + 2; y >= top - 4; y--) {
                            cursor.set(x, y, z);
                            if (pathType(level, cursor) == BlockPathTypes.WALKABLE) {
                                return cursor.immutable();
                            }
                        }
                    }
                }
            }
            return null;
        }

        private void addConnectedPair(
                List<RouteCase> cases,
                String name,
                ServerLevel level,
                TopologyGraphAudit.PublishedConnectivity connectivity,
                List<BlockPos> candidates,
                int desiredDistance) {
            TopologyGraphAudit.PairSelection selection = connectivity.selectPair(
                    candidates,
                    desiredDistance
            );
            double allowedDifference = Math.max(4.0D, desiredDistance * 0.10D);
            if (Math.abs(selection.directDistance() - desiredDistance) > allowedDifference) {
                throw new IllegalStateException(
                        "no structurally connected " + name + " pair within distance tolerance: "
                                + "requested=" + desiredDistance
                                + ", selected=" + selection.directDistance()
                                + ", tolerance=" + allowedDifference
                                + ", selection=" + selection.report()
                                + ", connectivity=" + connectivity.report()
                );
            }
            cases.add(new RouteCase(
                    name,
                    level,
                    selection.start(),
                    selection.goal(),
                    desiredDistance,
                    selection
            ));
        }

        private static void addUnique(List<SectionRef> target,
                                      Set<TopologyService.ClusterKey> assigned,
                                      ServerLevel level,
                                      int sectionX,
                                      int sectionY,
                                      int sectionZ) {
            if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
                return;
            }
            SectionRef reference = new SectionRef(level, SectionPos.of(sectionX, sectionY, sectionZ));
            if (assigned.add(reference.key())) {
                target.add(reference);
            }
        }

        private void addSurfaceChunkSections(List<SectionRef> target,
                                             Set<TopologyService.ClusterKey> assigned,
                                             int chunkX,
                                             int chunkZ) {
            int minimumSection = Integer.MAX_VALUE;
            int maximumSection = Integer.MIN_VALUE;
            int minimumX = chunkX << 4;
            int minimumZ = chunkZ << 4;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int surfaceY = overworld.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            minimumX + localX,
                            minimumZ + localZ
                    );
                    int sectionY = SectionPos.blockToSectionCoord(surfaceY);
                    minimumSection = Math.min(minimumSection, sectionY);
                    maximumSection = Math.max(maximumSection, sectionY);
                }
            }
            for (int sectionY = minimumSection - SURFACE_SECTION_PADDING;
                 sectionY <= maximumSection + SURFACE_SECTION_PADDING; sectionY++) {
                addUnique(target, assigned, overworld, chunkX, sectionY, chunkZ);
            }
        }

        private static Map<String, Integer> sectionEnvelope(List<SectionRef> sections) {
            if (sections.isEmpty()) {
                return Map.of();
            }
            int minimumX = Integer.MAX_VALUE;
            int minimumY = Integer.MAX_VALUE;
            int minimumZ = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            int maximumY = Integer.MIN_VALUE;
            int maximumZ = Integer.MIN_VALUE;
            for (SectionRef reference : sections) {
                SectionPos section = reference.section;
                minimumX = Math.min(minimumX, section.x());
                minimumY = Math.min(minimumY, section.y());
                minimumZ = Math.min(minimumZ, section.z());
                maximumX = Math.max(maximumX, section.x());
                maximumY = Math.max(maximumY, section.y());
                maximumZ = Math.max(maximumZ, section.z());
            }
            Map<String, Integer> result = new LinkedHashMap<>();
            result.put("minimumSectionX", minimumX);
            result.put("minimumSectionY", minimumY);
            result.put("minimumSectionZ", minimumZ);
            result.put("maximumSectionX", maximumX);
            result.put("maximumSectionY", maximumY);
            result.put("maximumSectionZ", maximumZ);
            return result;
        }

        private static BlockPathTypes pathType(ServerLevel level, BlockPos position) {
            return WalkNodeEvaluator.getBlockPathTypeStatic(
                    level,
                    new BlockPos.MutableBlockPos(position.getX(), position.getY(), position.getZ())
            );
        }

        private static List<Map<String, Object>> unavailableDependencyDiagnostics(
                TopologyService.MacroQuery query) {
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                Field unavailable = MacroSearch.class.getDeclaredField(
                        "encounteredUnavailableDependencies"
                );
                unavailable.setAccessible(true);
                appendUnavailableDependencies(query, "activeSearch", "search", unavailable, result);
                appendUnavailableDependencies(query, "superSearch", "superSearch", unavailable, result);
                result.sort((first, second) -> {
                    int source = ((String) first.get("source")).compareTo((String) second.get("source"));
                    if (source != 0) {
                        return source;
                    }
                    int kind = ((String) first.get("kind")).compareTo((String) second.get("kind"));
                    if (kind != 0) {
                        return kind;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> left = (Map<String, Integer>) first.get("section");
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> right = (Map<String, Integer>) second.get("section");
                    int x = Integer.compare(left.get("x"), right.get("x"));
                    if (x != 0) {
                        return x;
                    }
                    int y = Integer.compare(left.get("y"), right.get("y"));
                    return y != 0 ? y : Integer.compare(left.get("z"), right.get("z"));
                });
                return List.copyOf(result);
            } catch (ReflectiveOperationException exception) {
                return List.of(Map.of(
                        "diagnosticFailure", exception.getClass().getSimpleName(),
                        "message", String.valueOf(exception.getMessage())
                ));
            }
        }

        private static void appendUnavailableDependencies(TopologyService.MacroQuery query,
                                                          String source,
                                                          String searchFieldName,
                                                          Field unavailableField,
                                                          List<Map<String, Object>> target)
                throws ReflectiveOperationException {
            Field searchField = query.getClass().getDeclaredField(searchFieldName);
            searchField.setAccessible(true);
            Object search = searchField.get(query);
            if (!(search instanceof MacroSearch macroSearch)) {
                return;
            }
            Object dependencies = unavailableField.get(macroSearch);
            if (!(dependencies instanceof Set<?> entries)) {
                return;
            }
            for (Object entry : entries) {
                if (!(entry instanceof MacroSearch.DependencyKey dependency)) {
                    continue;
                }
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("source", source);
                report.put("kind", dependency.kind().name());
                SectionPos section = dependency.position();
                report.put("section", Map.of(
                        "x", section.x(),
                        "y", section.y(),
                        "z", section.z()
                ));
                target.add(report);
            }
        }

        private String biome(ServerLevel level, BlockPos position) {
            ResourceLocation key = level.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getKey(level.getBiome(position).value());
            return key == null ? "unknown" : key.toString();
        }

        private String loadedEnvelope(ServerLevel level) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int count = 0;
            for (ChunkLoad held : heldChunks) {
                if (held.level != level) {
                    continue;
                }
                minX = Math.min(minX, held.chunk.x);
                maxX = Math.max(maxX, held.chunk.x);
                minZ = Math.min(minZ, held.chunk.z);
                maxZ = Math.max(maxZ, held.chunk.z);
                count++;
            }
            return count == 0
                    ? "none"
                    : "chunks[x=" + minX + ".." + maxX + ",z=" + minZ + ".." + maxZ
                            + ",count=" + count + "]";
        }

        private static Map<String, Integer> positionMap(BlockPos position) {
            return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
        }
    }

    private static Map<String, Object> workerDelta(TopologyService.WorkerMetrics before,
                                                    TopologyService.WorkerMetrics after) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("managed", after.managed());
        report.put("queuedAtStart", before.queuedTasks());
        report.put("queuedAtEnd", after.queuedTasks());
        report.put("submitted", Math.max(0L, after.submittedTasks() - before.submittedTasks()));
        report.put("completed", Math.max(0L, after.completedTasks() - before.completedTasks()));
        report.put("promoted", Math.max(0L, after.promotedTasks() - before.promotedTasks()));
        report.put("cancelled", Math.max(0L, after.cancelledTasks() - before.cancelledTasks()));
        report.put("totalQueueWaitMillis", nanosToMillis(Math.max(
                0L,
                after.totalQueueWaitNanos() - before.totalQueueWaitNanos()
        )));
        report.put("maximumQueueWaitMillisObserved",
                nanosToMillis(after.maximumQueueWaitNanos()));
        return report;
    }

    private static Map<String, Object> workerReport(TopologyService.WorkerMetrics metrics) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("managed", metrics.managed());
        report.put("queuedTasks", metrics.queuedTasks());
        report.put("submittedTasks", metrics.submittedTasks());
        report.put("completedTasks", metrics.completedTasks());
        report.put("promotedTasks", metrics.promotedTasks());
        report.put("cancelledTasks", metrics.cancelledTasks());
        report.put("totalQueueWaitMillis", nanosToMillis(metrics.totalQueueWaitNanos()));
        report.put("maximumQueueWaitMillis", nanosToMillis(metrics.maximumQueueWaitNanos()));
        return report;
    }

    private static Map<String, Object> serviceLinkCacheReport(
            TopologyService.LinkCacheMetrics metrics) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("buildRequests", metrics.buildRequests());
        report.put("workerBuildMillis", nanosToMillis(metrics.buildNanos()));
        report.put("hits", metrics.hits());
        report.put("misses", metrics.misses());
        report.put("evictions", metrics.evictions());
        report.put("cachedEntries", metrics.cachedEntries());
        report.put("retainedBytes", metrics.retainedBytes());
        return report;
    }

    private static final class VanillaGroundProbe implements AutoCloseable {
        private final Zombie zombie;

        private VanillaGroundProbe(ServerLevel level) {
            zombie = Objects.requireNonNull(EntityType.ZOMBIE.create(level), "zombie");
            if (zombie.getAttribute(Attributes.FOLLOW_RANGE) != null) {
                zombie.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64.0D);
            }
        }

        private ProbeResult firstWindow(MacroSearch.Corridor corridor) {
            BlockPos from = corridor.endpoints().get(0).anchor();
            BlockPos target = corridor.endpoints().get(corridor.endpoints().size() - 1).anchor();
            for (MacroSearch.Connection connection : corridor.connections()) {
                if (connection.transition() instanceof MacroSearch.BoundaryTransition boundary
                        && connection.from() instanceof MacroSearch.ComponentEndpoint component) {
                    target = boundaryPosition(component.section(), boundary);
                    break;
                }
            }
            return probe(from, target, "MACRO_FIRST_WINDOW");
        }

        private ProbeResult firstWitnessWindow(List<BlockPos> anchors) {
            BlockPos from = anchors.get(0);
            BlockPos target = anchors.get(1);
            for (int index = 2; index < anchors.size(); index++) {
                BlockPos candidate = anchors.get(index);
                if (distance(from, candidate) > 24.0D) {
                    break;
                }
                target = candidate;
            }
            return probe(from, target, "BASE_GRAPH_FIRST_WINDOW");
        }

        private ProbeResult probe(BlockPos from, BlockPos target, String kind) {
            long started = System.nanoTime();
            zombie.getNavigation().stop();
            zombie.moveTo(from.getX() + 0.5D, from.getY(), from.getZ() + 0.5D, 0.0F, 0.0F);
            zombie.setOnGround(true);
            Path path = zombie.getNavigation().createPath(target, 0);
            long spent = System.nanoTime() - started;
            BlockPos actualEnd = path == null || path.getEndNode() == null
                    ? null
                    : path.getEndNode().asBlockPos();
            return new ProbeResult(
                    kind,
                    from,
                    target,
                    path != null,
                    path != null && path.canReach(),
                    path == null ? 0 : path.getNodeCount(),
                    path == null ? Float.POSITIVE_INFINITY : path.getDistToTarget(),
                    actualEnd,
                    spent
            );
        }

        @Override
        public void close() {
            zombie.discard();
        }
    }

    private static BlockPos boundaryPosition(SectionPos section,
                                             MacroSearch.BoundaryTransition transition) {
        int index = -1;
        MacroSearch.BoundaryBand band = transition.bands().get(0);
        for (int word = 0; word < 4 && index < 0; word++) {
            long mask = band.maskWord(word);
            if (mask != 0L) {
                index = word * Long.SIZE + Long.numberOfTrailingZeros(mask);
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("boundary transition has no passage cell");
        }
        int u = index & 15;
        int v = index >>> 4;
        int x;
        int y;
        int z;
        switch (transition.face()) {
            case DOWN -> {
                x = u;
                y = 0;
                z = v;
            }
            case UP -> {
                x = u;
                y = 15;
                z = v;
            }
            case NORTH -> {
                x = u;
                y = v;
                z = 0;
            }
            case SOUTH -> {
                x = u;
                y = v;
                z = 15;
            }
            case WEST -> {
                x = 0;
                y = v;
                z = u;
            }
            case EAST -> {
                x = 15;
                y = v;
                z = u;
            }
            default -> throw new IllegalStateException("unknown boundary face");
        }
        return new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
    }

    private static double distance(BlockPos first, BlockPos second) {
        double dx = second.getX() - first.getX();
        double dy = second.getY() - first.getY();
        double dz = second.getZ() - first.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Nullable
    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
        if (!(standard instanceof com.sun.management.ThreadMXBean extended)
                || !extended.isThreadAllocatedMemorySupported()) {
            return null;
        }
        try {
            if (!extended.isThreadAllocatedMemoryEnabled()) {
                extended.setThreadAllocatedMemoryEnabled(true);
            }
            return extended.isThreadAllocatedMemoryEnabled() ? extended : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static long allocatedBytes(@Nullable com.sun.management.ThreadMXBean bean) {
        return bean == null ? -1L : bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static long gcCollectionCount() {
        long total = 0L;
        boolean supported = false;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            if (count >= 0L) {
                total += count;
                supported = true;
            }
        }
        return supported ? total : -1L;
    }

    private static long gcCollectionMillis() {
        long total = 0L;
        boolean supported = false;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long millis = collector.getCollectionTime();
            if (millis >= 0L) {
                total += millis;
                supported = true;
            }
        }
        return supported ? total : -1L;
    }

    private static long metricDelta(long before, long after) {
        return before < 0L || after < 0L ? -1L : Math.max(0L, after - before);
    }

    private static long accumulateMetric(long current, long delta) {
        if (delta < 0L) {
            return current;
        }
        return current < 0L ? delta : current + delta;
    }

    private static Map<String, Object> cacheReport(long buildRequests,
                                                    long buildNanos,
                                                    long hits,
                                                    long misses,
                                                    long evictions) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("buildRequests", buildRequests);
        report.put("workerBuildMillis", nanosToMillis(buildNanos));
        report.put("hits", hits);
        report.put("misses", misses);
        report.put("evictions", evictions);
        return report;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static double nanosToMicros(long nanos) {
        return nanos / 1_000.0D;
    }

    private static int percentile(int[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
    }

    private static final class RouteRun {
        private final RouteCase routeCase;
        private final long[] macroNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] superNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] refinementNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] queryCpuNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] wallNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] allocatedBytes = new long[MEASURED_ROUTE_RUNS];
        private final long[] buildQueueWaitNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] persistenceQueueWaitNanos = new long[MEASURED_ROUTE_RUNS];
        private long coldMacroNanos;
        private long coldSuperNanos;
        private long coldRefinementNanos;
        private long coldQueryCpuNanos;
        private long coldAllocatedBytes;
        private long coldGcCount;
        private long coldGcMillis;
        private long coldWallNanos;
        private long coldSnapshotNanos;
        private long coldWorkerNanos;
        private long coldSuperWorkerNanos;
        private long coldBaseBoundaryBuildNanos;
        private long coldSuperBoundaryBuildNanos;
        private long coldBuildQueueWaitNanos;
        private long coldPersistenceQueueWaitNanos;
        private int run;
        private int succeeded;
        private int timedOut;
        private String failure = "NONE";
        private SectionPos blockedSection;
        private long expanded;
        private long generated;
        private long superExpanded;
        private long superGenerated;
        private long refinementExpanded;
        private long refinementGenerated;
        private long reopened;
        private long reexpandedBlocked;
        private int maximumDegree;
        private int maximumBlockedNodes;
        private int requestedSections;
        private int completedSections;
        private int failedSections;
        private int hierarchicalRuns;
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
        private int corridorConnections;
        private long buildTasksSubmitted;
        private long buildTasksPromoted;
        private long buildTasksCancelled;
        private long persistenceTasksSubmitted;
        private long persistenceTasksPromoted;
        private long persistenceTasksCancelled;
        private long gcCount = -1L;
        private long gcMillis = -1L;
        private long baseBoundaryBuildRequests;
        private long baseBoundaryBuildNanos;
        private long baseBoundaryHits;
        private long baseBoundaryMisses;
        private long baseBoundaryEvictions;
        private long superBoundaryBuildRequests;
        private long superBoundaryBuildNanos;
        private long superBoundaryHits;
        private long superBoundaryMisses;
        private long superBoundaryEvictions;
        private final Map<String, Integer> failureCounts = new LinkedHashMap<>();
        private MacroSearch.Corridor sampleCorridor;
        private Map<String, Object> corridorAudit;
        private Map<String, Object> baseGraphAudit;
        private ProbeResult vanillaProbe;
        private long referenceWallNanos;
        private String answerAssessment = "NOT_AUDITED";
        private Boolean macroAnswerCorrect;
        private String reachabilityConclusion = "UNRESOLVED";

        private RouteRun(RouteCase routeCase) {
            this.routeCase = routeCase;
        }

        private void record(TopologyService.MacroQuery query,
                            MacroSearch.Corridor corridor,
                            long wall,
                            TopologyService.QueryMetrics queryMetrics,
                            TopologyService.Metrics before,
                            TopologyService.Metrics after,
                            long allocatedBytes,
                            long gcCount,
                            long gcMillis) {
            recordMetrics(
                    wall,
                    queryMetrics,
                    before,
                    after,
                    allocatedBytes,
                    gcCount,
                    gcMillis
            );
            if (corridor == null) {
                if (isMeasuredRun()) {
                    failure = query.failure().name();
                    failureCounts.merge(failure, 1, Integer::sum);
                    blockedSection = query.blockedSection();
                }
                return;
            }
            if (isMeasuredRun()) {
                succeeded++;
            }
            corridorConnections = corridor.connections().size();
            sampleCorridor = corridor;
        }

        private void recordTimeout(TopologyService.MacroQuery query,
                                   long wall,
                                   TopologyService.QueryMetrics queryMetrics,
                                   TopologyService.Metrics before,
                                   TopologyService.Metrics after,
                                   long allocatedBytes,
                                   long gcCount,
                                   long gcMillis) {
            recordMetrics(
                    wall,
                    queryMetrics,
                    before,
                    after,
                    allocatedBytes,
                    gcCount,
                    gcMillis
            );
            if (isMeasuredRun()) {
                timedOut++;
                failure = "HARNESS_TIMEOUT";
                failureCounts.merge(failure, 1, Integer::sum);
                blockedSection = query.blockedSection();
            }
        }

        private void recordMetrics(long wall,
                                   TopologyService.QueryMetrics queryMetrics,
                                   TopologyService.Metrics before,
                                   TopologyService.Metrics after,
                                   long observedAllocatedBytes,
                                   long observedGcCount,
                                   long observedGcMillis) {
            TopologyService.WorkerMetrics beforeBuild = before.buildWorker();
            TopologyService.WorkerMetrics afterBuild = after.buildWorker();
            TopologyService.WorkerMetrics beforePersistence = before.persistenceWorker();
            TopologyService.WorkerMetrics afterPersistence = after.persistenceWorker();
            long buildQueueWait = Math.max(
                    0L,
                    afterBuild.totalQueueWaitNanos() - beforeBuild.totalQueueWaitNanos()
            );
            long persistenceQueueWait = Math.max(
                    0L,
                    afterPersistence.totalQueueWaitNanos()
                            - beforePersistence.totalQueueWaitNanos()
            );
            if (run == 0) {
                coldMacroNanos = queryMetrics.macroSearchNanos();
                coldSuperNanos = queryMetrics.superSearchNanos();
                coldRefinementNanos = queryMetrics.refinementSearchNanos();
                coldQueryCpuNanos = queryMetrics.queryCpuNanos();
                coldWallNanos = wall;
                coldAllocatedBytes = observedAllocatedBytes;
                coldGcCount = observedGcCount;
                coldGcMillis = observedGcMillis;
                coldSnapshotNanos = after.snapshotNanos() - before.snapshotNanos();
                coldWorkerNanos = after.buildNanos() - before.buildNanos();
                coldSuperWorkerNanos = after.superBuildNanos() - before.superBuildNanos();
                coldBaseBoundaryBuildNanos = after.baseBoundaryLinks().buildNanos()
                        - before.baseBoundaryLinks().buildNanos();
                coldSuperBoundaryBuildNanos = after.superBoundaryLinks().buildNanos()
                        - before.superBoundaryLinks().buildNanos();
                coldBuildQueueWaitNanos = buildQueueWait;
                coldPersistenceQueueWaitNanos = persistenceQueueWait;
                return;
            }
            if (!isMeasuredRun()) {
                return;
            }

            int measured = run - COLD_ROUTE_RUNS - WARMUP_ROUTE_RUNS;
            macroNanos[measured] = queryMetrics.macroSearchNanos();
            superNanos[measured] = queryMetrics.superSearchNanos();
            refinementNanos[measured] = queryMetrics.refinementSearchNanos();
            queryCpuNanos[measured] = queryMetrics.queryCpuNanos();
            wallNanos[measured] = wall;
            allocatedBytes[measured] = observedAllocatedBytes;
            buildQueueWaitNanos[measured] = buildQueueWait;
            persistenceQueueWaitNanos[measured] = persistenceQueueWait;
            gcCount = accumulateMetric(gcCount, observedGcCount);
            gcMillis = accumulateMetric(gcMillis, observedGcMillis);
            buildTasksSubmitted += Math.max(
                    0L,
                    afterBuild.submittedTasks() - beforeBuild.submittedTasks()
            );
            buildTasksPromoted += Math.max(
                    0L,
                    afterBuild.promotedTasks() - beforeBuild.promotedTasks()
            );
            buildTasksCancelled += Math.max(
                    0L,
                    afterBuild.cancelledTasks() - beforeBuild.cancelledTasks()
            );
            persistenceTasksSubmitted += Math.max(
                    0L,
                    afterPersistence.submittedTasks() - beforePersistence.submittedTasks()
            );
            persistenceTasksPromoted += Math.max(
                    0L,
                    afterPersistence.promotedTasks() - beforePersistence.promotedTasks()
            );
            persistenceTasksCancelled += Math.max(
                    0L,
                    afterPersistence.cancelledTasks() - beforePersistence.cancelledTasks()
            );
            requestedSections += queryMetrics.requestedSections();
            completedSections += queryMetrics.completedSections();
            failedSections += queryMetrics.failedSections();
            if (queryMetrics.hierarchical()) {
                hierarchicalRuns++;
            }
            requestedSuperClusters += queryMetrics.requestedSuperClusters();
            completedSuperClusters += queryMetrics.completedSuperClusters();
            failedSuperClusters += queryMetrics.failedSuperClusters();
            requestedBaseBoundaryLinks += queryMetrics.requestedBaseBoundaryLinks();
            completedBaseBoundaryLinks += queryMetrics.completedBaseBoundaryLinks();
            failedBaseBoundaryLinks += queryMetrics.failedBaseBoundaryLinks();
            requestedSuperBoundaryLinks += queryMetrics.requestedSuperBoundaryLinks();
            completedSuperBoundaryLinks += queryMetrics.completedSuperBoundaryLinks();
            failedSuperBoundaryLinks += queryMetrics.failedSuperBoundaryLinks();
            accumulateCacheMetrics(before.baseBoundaryLinks(), after.baseBoundaryLinks(), true);
            accumulateCacheMetrics(before.superBoundaryLinks(), after.superBoundaryLinks(), false);
            parkCount += queryMetrics.parkCount();
            wakeCount += queryMetrics.wakeCount();
            staleRestarts += queryMetrics.staleRestarts();
            MacroSearch.Metrics searchMetrics = queryMetrics.searchMetrics();
            if (searchMetrics != null) {
                expanded += searchMetrics.expandedNodes();
                generated += searchMetrics.generatedConnections();
                reopened += searchMetrics.reopenedNodes();
                reexpandedBlocked += searchMetrics.reexpandedBlockedNodes();
                maximumDegree = Math.max(maximumDegree, searchMetrics.maximumDegree());
                maximumBlockedNodes = Math.max(
                        maximumBlockedNodes,
                        searchMetrics.maximumBlockedNodes()
                );
            }
            MacroSearch.Metrics superMetrics = queryMetrics.superSearchMetrics();
            if (superMetrics != null) {
                superExpanded += superMetrics.expandedNodes();
                superGenerated += superMetrics.generatedConnections();
            }
            MacroSearch.Metrics refinementMetrics = queryMetrics.refinementMetrics();
            if (refinementMetrics != null) {
                refinementExpanded += refinementMetrics.expandedNodes();
                refinementGenerated += refinementMetrics.generatedConnections();
            }
        }

        private boolean isMeasuredRun() {
            return run >= COLD_ROUTE_RUNS + WARMUP_ROUTE_RUNS;
        }

        private void accumulateCacheMetrics(TopologyService.LinkCacheMetrics before,
                                            TopologyService.LinkCacheMetrics after,
                                            boolean base) {
            long requests = Math.max(0L, after.buildRequests() - before.buildRequests());
            long nanos = Math.max(0L, after.buildNanos() - before.buildNanos());
            long hits = Math.max(0L, after.hits() - before.hits());
            long misses = Math.max(0L, after.misses() - before.misses());
            long evictions = Math.max(0L, after.evictions() - before.evictions());
            if (base) {
                baseBoundaryBuildRequests += requests;
                baseBoundaryBuildNanos += nanos;
                baseBoundaryHits += hits;
                baseBoundaryMisses += misses;
                baseBoundaryEvictions += evictions;
            } else {
                superBoundaryBuildRequests += requests;
                superBoundaryBuildNanos += nanos;
                superBoundaryHits += hits;
                superBoundaryMisses += misses;
                superBoundaryEvictions += evictions;
            }
        }

        private Map<String, Object> baseBoundaryCacheReport() {
            return cacheReport(
                    baseBoundaryBuildRequests,
                    baseBoundaryBuildNanos,
                    baseBoundaryHits,
                    baseBoundaryMisses,
                    baseBoundaryEvictions
            );
        }

        private Map<String, Object> superBoundaryCacheReport() {
            return cacheReport(
                    superBoundaryBuildRequests,
                    superBoundaryBuildNanos,
                    superBoundaryHits,
                    superBoundaryMisses,
                    superBoundaryEvictions
            );
        }

        private void assessAnswer() {
            if (!resolvedOutcomesConsistent()) {
                answerAssessment = "NONDETERMINISTIC_MACRO_OUTCOME";
                macroAnswerCorrect = false;
                reachabilityConclusion = "UNRESOLVED";
                return;
            }
            if (sampleCorridor != null) {
                boolean referenceCurrent = corridorAudit != null
                        && Boolean.TRUE.equals(corridorAudit.get("referenceCurrent"));
                if (!referenceCurrent) {
                    answerAssessment = "CORRIDOR_AUDIT_STALE_WORLD";
                    macroAnswerCorrect = null;
                    reachabilityConclusion = "STRUCTURAL_ROUTE_FOUND_AT_QUERY_REVISION";
                    return;
                }
                boolean valid = corridorAudit != null
                        && Boolean.TRUE.equals(corridorAudit.get("valid"));
                answerAssessment = valid
                        ? "CORRIDOR_STRUCTURALLY_VALID"
                        : "INVALID_REFINED_CORRIDOR";
                macroAnswerCorrect = valid;
                reachabilityConclusion = valid
                        ? "STRUCTURAL_ROUTE_FOUND"
                        : "UNRESOLVED";
                return;
            }

            String auditStatus = baseGraphAudit == null
                    ? "NOT_RUN"
                    : String.valueOf(baseGraphAudit.get("status"));
            String resolvedFailure = resolvedFailure();
            if (MacroSearch.Failure.NO_STRUCTURAL_ROUTE.name().equals(resolvedFailure)) {
                switch (auditStatus) {
                    case "FOUND" -> {
                        answerAssessment = "HIERARCHY_FALSE_NEGATIVE";
                        macroAnswerCorrect = false;
                        reachabilityConclusion = "BASE_GRAPH_ROUTE_FOUND";
                    }
                    case "EXHAUSTED" -> {
                        answerAssessment = "BASE_STRUCTURAL_DISCONNECT_CORROBORATED";
                        macroAnswerCorrect = true;
                        reachabilityConclusion = "BASE_GRAPH_DISCONNECTED_NOT_PHYSICAL_PROOF";
                    }
                    case "ENDPOINT_UNBOUND" -> {
                        answerAssessment = "ENDPOINT_BINDING_FAILURE";
                        macroAnswerCorrect = false;
                        reachabilityConclusion = "UNRESOLVED";
                    }
                    default -> {
                        answerAssessment = "REFERENCE_AUDIT_INCONCLUSIVE";
                        macroAnswerCorrect = null;
                        reachabilityConclusion = "UNRESOLVED";
                    }
                }
                return;
            }
            if (MacroSearch.Failure.SEARCH_LIMIT_REACHED.name().equals(resolvedFailure)) {
                answerAssessment = "MACRO_SEARCH_BUDGET_INCONCLUSIVE"
                        + ("FOUND".equals(auditStatus) ? "_BASE_ROUTE_FOUND" : "");
                macroAnswerCorrect = null;
                reachabilityConclusion = "FOUND".equals(auditStatus)
                        ? "BASE_GRAPH_ROUTE_FOUND"
                        : "UNRESOLVED";
                return;
            }
            answerAssessment = "MACRO_FAILURE_INCONCLUSIVE";
            macroAnswerCorrect = null;
            reachabilityConclusion = "UNRESOLVED";
        }

        private boolean resolvedOutcomesConsistent() {
            return succeeded == 0 || resolvedFailureCount() == 0;
        }

        private int resolvedFailureCount() {
            return failureCounts.entrySet().stream()
                    .filter(entry -> !"HARNESS_TIMEOUT".equals(entry.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        }

        private String resolvedFailure() {
            return failureCounts.keySet().stream()
                    .filter(value -> !"HARNESS_TIMEOUT".equals(value))
                    .findFirst()
                    .orElse(failure);
        }
    }

    private enum Phase {
        LOAD_CHUNKS,
        PREPARE_GROUPS,
        START_GROUP,
        WAIT_GROUP,
        PREPARE_ROUTES,
        START_ROUTE,
        WAIT_ROUTE,
        START_AUDIT,
        WAIT_AUDIT,
        WRITE_REPORT,
        COMPLETE
    }

    private record ChunkLoad(ServerLevel level, ChunkPos chunk) {
    }

    private record SectionRef(ServerLevel level, SectionPos section) {
        private TopologyService.ClusterKey key() {
            return new TopologyService.ClusterKey(level.dimension(), section);
        }
    }

    private record BuildGroup(String name, List<SectionRef> sections) {
        private BuildGroup {
            sections = List.copyOf(sections);
        }
    }

    private record RouteCase(String name,
                             ServerLevel level,
                             BlockPos start,
                             BlockPos goal,
                             int requestedDistance,
                             TopologyGraphAudit.PairSelection selection) {
    }

    private record ProbeResult(String kind,
                               BlockPos from,
                               BlockPos requestedTarget,
                               boolean pathReturned,
                               boolean reachable,
                               int nodeCount,
                               float distanceToTarget,
                               @Nullable BlockPos actualEnd,
                               long spentNanos) {

        private Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("kind", kind);
            report.put("from", Controller.positionMap(from));
            report.put("requestedTarget", Controller.positionMap(requestedTarget));
            report.put("pathReturned", pathReturned);
            report.put("canReach", reachable);
            report.put("nodeCount", nodeCount);
            report.put("distanceToTarget", Float.isFinite(distanceToTarget)
                    ? distanceToTarget
                    : null);
            report.put("actualEnd", actualEnd == null
                    ? null
                    : Controller.positionMap(actualEnd));
            report.put("spentMillis", nanosToMillis(spentNanos));
            report.put("interpretation", reachable
                    ? "POSITIVE_LOCAL_EXECUTABILITY_WITNESS"
                    : "INCONCLUSIVE_BOUNDED_VANILLA_SEARCH");
            return report;
        }
    }
}
