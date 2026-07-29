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
    private static final int COMPLETED_CACHE_WARMUP_RUNS = 5;
    private static final int MEASURED_COMPLETED_CACHE_RUNS = 100;
    private static final int READY_TOPOLOGY_ROUTE_RUNS =
            COLD_ROUTE_RUNS + WARMUP_ROUTE_RUNS + MEASURED_ROUTE_RUNS;
    private static final int TOTAL_ROUTE_RUNS =
            READY_TOPOLOGY_ROUTE_RUNS
                    + COMPLETED_CACHE_WARMUP_RUNS
                    + MEASURED_COMPLETED_CACHE_RUNS;
    private static final int CHUNK_LOAD_BATCH_SIZE = 32;
    private static final long CHUNK_LOAD_TICK_BUDGET_NANOS = 4_000_000L;
    private static final int BUILD_BATCH_SIZE = 256;
    private static final int AUDIT_NODE_LIMIT = 16_384;
    private static final int AUDIT_EXPANSIONS_PER_TICK = 256;
    private static final long AUDIT_TICK_BUDGET_NANOS = 2_000_000L;
    private static final long AUDIT_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int PRESSURE_QUERIES = 1_024;
    private static final int PRESSURE_STARTS_PER_DIMENSION = 64;
    private static final int COALESCED_REPETITIONS = 512;
    private static final int DISTRIBUTED_REPETITIONS = 8;
    private static final long PRESSURE_TIMEOUT_NANOS = 120_000_000_000L;
    private static final int[] DIAGNOSTIC_NODE_BUDGETS = {2_048, 4_096, 8_192};
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
        private TopologyService topologyService;
        private final ArrayDeque<ChunkLoad> chunksToLoad = new ArrayDeque<>();
        private final List<ChunkLoad> heldChunks = new ArrayList<>();
        private final ArrayDeque<BuildGroup> groupsToBuild = new ArrayDeque<>();
        private final List<Map<String, Object>> buildReports = new ArrayList<>();
        private final List<Map<String, Object>> connectivityReferenceReports = new ArrayList<>();
        private final List<Map<String, Object>> routeReports = new ArrayList<>();
        private final ArrayDeque<RouteCase> routesToRun = new ArrayDeque<>();
        private final List<RouteRun> completedRoutes = new ArrayList<>();
        private final ArrayDeque<RouteRun> routesToAudit = new ArrayDeque<>();
        private final ArrayDeque<PressureStage> pressureStages = new ArrayDeque<>();
        private final List<Map<String, Object>> pressureReports = new ArrayList<>();
        private final List<Map<String, Object>> budgetProbeReports = new ArrayList<>();
        private final Set<SectionRef> persistenceSections = new LinkedHashSet<>();
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
        private TopologyService.MacroRequest activeQuery;
        private CompletableFuture<MacroSearch.Corridor> activeQueryFuture;
        private long activeQueryStartedNanos;
        private long activeQueryNextProgressLogNanos;
        private long activeQueryWallNanos;
        private MacroSearch.Corridor activeQueryResult;
        private boolean activeQueryCompleted;
        private boolean activeQueryCompletedSynchronously;
        private TopologyService.Metrics metricsBeforeQuery;
        private long activeQueryAllocatedBytes;
        private long activeQueryGcCount;
        private long activeQueryGcMillis;
        private long activeQueryEndAllocatedBytes;
        private long activeQueryEndGcCount;
        private long activeQueryEndGcMillis;
        private RouteRun activeAuditRoute;
        private TopologyGraphAudit activeGraphAudit;
        private long activeAuditStartedNanos;
        private StressRun activeStress;
        private TopologyService.Metrics pressureMetricsBefore;
        private TopologyService.Metrics preRestartMetrics;
        private List<CompletableFuture<BaseClusterTopology>> restoreRequests = List.of();
        private long restoreStartedNanos;
        private Map<String, Object> persistenceReport = Map.of();

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
                case START_PRESSURE -> startPressure();
                case WAIT_PRESSURE -> waitForPressure();
                case SAVE_AND_RESTART -> saveAndRestart();
                case WAIT_RESTORE -> waitForRestore();
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
                requests.add(TopologyGraphAudit.requestClusterDependency(
                        topologyService,
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
                    retried.set(index, TopologyGraphAudit.requestClusterDependency(
                            topologyService,
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

            List<TopologyGraphAudit.PairSelection> overworldPressure =
                    overworldConnectivity.selectDistinctStartPairs(
                            surfaceCandidates,
                            96,
                            PRESSURE_STARTS_PER_DIMENSION
                    );
            List<TopologyGraphAudit.PairSelection> netherPressure =
                    netherConnectivity.selectDistinctStartPairs(
                            netherCandidates,
                            96,
                            PRESSURE_STARTS_PER_DIMENSION
                    );
            requirePressureDistance(overworldPressure);
            requirePressureDistance(netherPressure);
            pressureStages.addLast(new PressureStage(
                    "high_coalescing",
                    List.of(
                            new PressureRoute(overworld, overworldPressure.get(0)),
                            new PressureRoute(nether, netherPressure.get(0))
                    ),
                    COALESCED_REPETITIONS,
                    PressureKind.IN_FLIGHT,
                    0
            ));
            pressureStages.addLast(new PressureStage(
                    "completed_corridor_replay",
                    List.of(
                            new PressureRoute(overworld, overworldPressure.get(0)),
                            new PressureRoute(nether, netherPressure.get(0))
                    ),
                    COALESCED_REPETITIONS,
                    PressureKind.COMPLETED_REUSE,
                    0
            ));
            List<PressureRoute> distributed = new ArrayList<>(
                    PRESSURE_STARTS_PER_DIMENSION * 2
            );
            for (int index = 0; index < PRESSURE_STARTS_PER_DIMENSION; index++) {
                distributed.add(new PressureRoute(overworld, overworldPressure.get(index)));
                distributed.add(new PressureRoute(nether, netherPressure.get(index)));
            }
            pressureStages.addLast(new PressureStage(
                    "distributed_64_per_dimension",
                    distributed,
                    DISTRIBUTED_REPETITIONS,
                    PressureKind.DISTRIBUTED,
                    0
            ));
            pressureStages.stream()
                    .flatMap(stage -> stage.routes().stream())
                    .map(route -> new SectionRef(
                            route.level(),
                            SectionPos.of(route.selection().start())
                    ))
                    .forEach(persistenceSections::add);

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

        private static void requirePressureDistance(
                List<TopologyGraphAudit.PairSelection> selections) {
            for (TopologyGraphAudit.PairSelection selection : selections) {
                if (Math.abs(selection.directDistance() - 96.0D) > 9.6D) {
                    throw new IllegalStateException(
                            "pressure endpoint is outside the 96 block tolerance: "
                                    + selection.report()
                    );
                }
            }
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
            if (activeRoute.requiresCompletedCacheBypass()) {
                clearCompletedCorridorCache(topologyService);
            }
            metricsBeforeQuery = topologyService.metrics();
            activeQueryAllocatedBytes = allocatedBytes(allocationBean);
            activeQueryGcCount = gcCollectionCount();
            activeQueryGcMillis = gcCollectionMillis();
            activeQueryStartedNanos = System.nanoTime();
            activeQueryNextProgressLogNanos = activeQueryStartedNanos + QUERY_PROGRESS_LOG_NANOS;
            activeQuery = topologyService.requestMacroQuery(
                    routeCase.level,
                    UUID.nameUUIDFromBytes(("terrain-route:" + routeCase.name + ":"
                            + activeRoute.run).getBytes(StandardCharsets.UTF_8)),
                    routeCase.start,
                    routeCase.goal,
                    BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.ACTIVE
            );
            activeQueryWallNanos = 0L;
            activeQueryResult = null;
            activeQueryCompleted = false;
            activeQueryFuture = activeQuery.future();
            activeQueryCompletedSynchronously = activeQueryFuture.isDone();
            phase = Phase.WAIT_ROUTE;
            if (activeQueryCompletedSynchronously) {
                waitForRoute();
            }
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
                    metricDelta(activeQueryGcMillis, activeQueryEndGcMillis),
                    activeQueryCompletedSynchronously
            );
            activeQuery = null;
            activeQueryFuture = null;
            activeQueryResult = null;
            activeQueryCompletedSynchronously = false;
            activeRoute.run++;
            phase = Phase.START_ROUTE;
        }

        private void recordQueryTimeout(long now) {
            long endAllocatedBytes = allocatedBytes(allocationBean);
            long endGcCount = gcCollectionCount();
            long endGcMillis = gcCollectionMillis();
            String state = activeQueryState("timeout", now);
            TopologyService.QueryMetrics queryMetrics = activeQuery.metrics();
            activeQuery.cancel();
            TopologyService.Metrics after = topologyService.metrics();
            activeRoute.recordTimeout(
                    activeQuery,
                    now - activeQueryStartedNanos,
                    queryMetrics,
                    metricsBeforeQuery,
                    after,
                    metricDelta(activeQueryAllocatedBytes, endAllocatedBytes),
                    metricDelta(activeQueryGcCount, endGcCount),
                    metricDelta(activeQueryGcMillis, endGcMillis),
                    activeQueryCompletedSynchronously
            );
            AcceleratedNavigation.LOGGER.warn("{}; recorded as an inconclusive harness timeout", state);
            activeQuery = null;
            activeQueryFuture = null;
            activeQueryResult = null;
            activeQueryCompletedSynchronously = false;
            activeRoute.run++;
            phase = Phase.START_ROUTE;
        }

        private static void clearCompletedCorridorCache(TopologyService service) {
            try {
                Field corridorsField = TopologyService.class.getDeclaredField("completedCorridors");
                corridorsField.setAccessible(true);
                Object corridors = corridorsField.get(service);
                if (!(corridors instanceof Map<?, ?> cache)) {
                    throw new IllegalStateException("completed corridor cache has an unexpected type");
                }
                cache.clear();
                Field bytesField = TopologyService.class.getDeclaredField("completedCorridorBytes");
                bytesField.setAccessible(true);
                bytesField.setLong(service, 0L);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot isolate ready-topology search timing", failure);
            }
        }

        private void startAudit() {
            activeAuditRoute = routesToAudit.pollFirst();
            if (activeAuditRoute == null) {
                pressureMetricsBefore = topologyService.metrics();
                phase = Phase.START_PRESSURE;
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

        private void startPressure() {
            PressureStage stage = pressureStages.pollFirst();
            if (stage == null) {
                MacroSearchMetricsProbe.endStage();
                phase = Phase.SAVE_AND_RESTART;
                return;
            }
            MacroSearchMetricsProbe.beginStage(stage.nodeBudget());
            if (stage.kind() != PressureKind.COMPLETED_REUSE
                    && stage.kind() != PressureKind.BUDGET_PROBE) {
                stage.routes().stream()
                        .map(route -> new TopologyService.ClusterKey(
                                route.level().dimension(),
                                SectionPos.of(route.selection().start())
                        ))
                        .distinct()
                        .forEach(topologyService::invalidate);
            }
            TopologyService.Metrics before = topologyService.metrics();
            activeStress = new StressRun(stage, before, System.nanoTime());
            NavigationScheduler scheduler = NavigationScheduler.forServer(server);
            int index = 0;
            for (int repetition = 0; repetition < stage.repetitions(); repetition++) {
                for (PressureRoute route : stage.routes()) {
                    UUID owner = UUID.nameUUIDFromBytes(("terrain-pressure:" + stage.name()
                            + ":" + index).getBytes(StandardCharsets.UTF_8));
                    long submittedNanos = System.nanoTime();
                    TopologyService.MacroRequest request = topologyService.requestMacroQuery(
                            route.level(),
                            owner,
                            route.selection().start(),
                            route.selection().goal(),
                            BaseClusterTopology.Channel.GROUND,
                            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                            NavigationScheduler.Priority.ACTIVE
                    );
                    activeStress.add(new StressQuery(
                            route,
                            request,
                            request.future(),
                            submittedNanos
                    ));
                    index++;
                }
            }
            if (index != stage.queryCount()) {
                throw new IllegalStateException(
                        "pressure fixture submitted " + index + " queries instead of "
                                + stage.queryCount()
                );
            }
            AcceleratedNavigation.LOGGER.info(
                    "Started topology pressure stage: name={}, queries={}, starts={}, nodeBudget={}",
                    stage.name(),
                    index,
                    stage.routes().size(),
                    stage.nodeBudget() == 0 ? "production" : stage.nodeBudget()
            );
            phase = Phase.WAIT_PRESSURE;
        }

        private void waitForPressure() {
            long now = System.nanoTime();
            NavigationScheduler scheduler = NavigationScheduler.forServer(server);
            NavigationScheduler.AdmissionCapacity capacity = scheduler.admissionCapacity();
            TopologyService.Metrics metrics = topologyService.metrics();
            activeStress.observe(metrics);
            if (!activeStress.complete()
                    && now - activeStress.startedNanos > PRESSURE_TIMEOUT_NANOS) {
                activeStress.timedOut = activeStress.queries.size() - activeStress.completed;
                for (StressQuery query : activeStress.queries) {
                    if (!query.future().isDone()) {
                        query.request().cancel();
                    }
                }
            }
            if (!activeStress.complete()) {
                return;
            }
            TopologyService.Metrics after = topologyService.metrics();
            NavigationScheduler.AdmissionCapacity afterCapacity = scheduler.admissionCapacity();
            Map<String, Object> stageReport = finishPressure(
                    activeStress,
                    after,
                    afterCapacity
            );
            if (activeStress.stage.kind() == PressureKind.BUDGET_PROBE) {
                budgetProbeReports.add(stageReport);
            } else {
                pressureReports.add(stageReport);
            }
            enqueueNextBudgetProbe(activeStress);
            MacroSearchMetricsProbe.endStage();
            AcceleratedNavigation.LOGGER.info(
                    "Completed topology pressure stage: name={}, succeeded={}, failed={}, wallMs={}",
                    activeStress.stage.name(),
                    activeStress.succeeded,
                    activeStress.failed,
                    nanosToMillis(System.nanoTime() - activeStress.startedNanos)
            );
            activeStress = null;
            phase = Phase.START_PRESSURE;
        }

        private void enqueueNextBudgetProbe(StressRun run) {
            if (run.stage.kind() != PressureKind.DISTRIBUTED
                    && run.stage.kind() != PressureKind.BUDGET_PROBE) {
                return;
            }
            List<PressureRoute> failedRoutes = run.searchLimitRoutes();
            if (failedRoutes.isEmpty()) {
                return;
            }
            int nextBudget = nextDiagnosticBudget(run.stage.nodeBudget());
            if (nextBudget == 0) {
                return;
            }
            pressureStages.addFirst(new PressureStage(
                    "search_budget_probe_" + nextBudget,
                    failedRoutes,
                    1,
                    PressureKind.BUDGET_PROBE,
                    nextBudget
            ));
        }

        private static int nextDiagnosticBudget(int currentBudget) {
            for (int candidate : DIAGNOSTIC_NODE_BUDGETS) {
                if (candidate > currentBudget) {
                    return candidate;
                }
            }
            return 0;
        }

        private Map<String, Object> finishPressure(
                StressRun run,
                TopologyService.Metrics after,
                NavigationScheduler.AdmissionCapacity capacity) {
            long[] latency = run.latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            long wallNanos = Math.max(1L, System.nanoTime() - run.startedNanos);
            long buildNanos = Math.max(0L, after.buildNanos() - run.before.buildNanos());
            Map<String, Long> completedByDimension = new LinkedHashMap<>();
            run.completionDimensions.forEach(dimension -> completedByDimension.merge(
                    dimension,
                    1L,
                    Long::sum
            ));
            double minimumDistance = run.stage.routes().stream()
                    .mapToDouble(route -> route.selection().directDistance())
                    .min().orElse(0.0D);
            double maximumDistance = run.stage.routes().stream()
                    .mapToDouble(route -> route.selection().directDistance())
                    .max().orElse(0.0D);
            boolean cleanup = after.dependencyPermits() == 0
                    && after.dependencyDemands() == 0
                    && after.queuedDependencyDemands() == 0
                    && after.topologyWaiters() == 0
                    && after.macroQueries().activeWaiters() == 0
                    && after.macroQueries().activeFlights() == 0
                    && capacity.parkedRequests() == 0;
            TopologyService.MacroQueryReuseMetrics beforeReuse = run.before.macroQueries();
            TopologyService.MacroQueryReuseMetrics afterReuse = after.macroQueries();
            long logicalRequests = afterReuse.logicalRequests() - beforeReuse.logicalRequests();
            long physicalSearches = afterReuse.physicalSearches() - beforeReuse.physicalSearches();
            long inFlightJoins = afterReuse.inFlightJoins() - beforeReuse.inFlightJoins();
            long completedHits = afterReuse.completedHits() - beforeReuse.completedHits();
            long completedMisses = afterReuse.completedMisses() - beforeReuse.completedMisses();
            long staleEvictions = afterReuse.staleEvictions() - beforeReuse.staleEvictions();
            long cacheEvictions = afterReuse.cacheEvictions() - beforeReuse.cacheEvictions();
            boolean expectedReuse = switch (run.stage.kind()) {
                case IN_FLIGHT -> physicalSearches == run.stage.routes().size()
                        && inFlightJoins == run.queries.size() - run.stage.routes().size()
                        && completedHits == 0;
                case COMPLETED_REUSE -> physicalSearches == 0
                        && inFlightJoins == 0
                        && completedHits == run.queries.size();
                case DISTRIBUTED -> physicalSearches == run.stage.routes().size()
                        && inFlightJoins == run.queries.size() - run.stage.routes().size();
                case BUDGET_PROBE -> physicalSearches == run.stage.routes().size()
                        && inFlightJoins == 0
                        && completedHits == 0;
            };
            boolean expectedFailures = run.failureCounts.keySet().stream()
                    .allMatch(reason -> (run.stage.kind() == PressureKind.DISTRIBUTED
                            || run.stage.kind() == PressureKind.BUDGET_PROBE)
                            && reason.equals(MacroSearch.Failure.SEARCH_LIMIT_REACHED.name()));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("name", run.stage.name());
            report.put("kind", run.stage.kind().name());
            report.put("configuredNodeBudget", run.stage.nodeBudget() == 0
                    ? "production"
                    : run.stage.nodeBudget());
            report.put("submitted", run.queries.size());
            report.put("succeeded", run.succeeded);
            report.put("failed", run.failed);
            report.put("timedOut", run.timedOut);
            report.put("failureCounts", run.failureCounts);
            report.put("p50WallLatencyMillis", latency.length == 0
                    ? 0.0D : nanosToMillis(percentile(latency, 0.50D)));
            report.put("p95WallLatencyMillis", latency.length == 0
                    ? 0.0D : nanosToMillis(percentile(latency, 0.95D)));
            report.put("p99WallLatencyMillis", latency.length == 0
                    ? 0.0D : nanosToMillis(percentile(latency, 0.99D)));
            report.put("lastCompletionMillis", latency.length == 0
                    ? 0.0D : nanosToMillis(latency[latency.length - 1]));
            report.put("completedByDimension", completedByDimension);
            report.put("dimensionCompletionOrder", runLengthOrder(run.completionDimensions));
            report.put("minimumStraightLineBlocks", minimumDistance);
            report.put("maximumStraightLineBlocks", maximumDistance);
            report.put("averageCorridorConnections", run.succeeded == 0
                    ? 0.0D : (double) run.corridorConnections / run.succeeded);
            report.put("ordinaryFreeZeroDependencyProgressObserved",
                    run.zeroOrdinarySlotsProgress);
            report.put("maximumDependencyPermits", run.maximumDependencyPermits);
            report.put("maximumDependencyDemands", run.maximumDependencyDemands);
            report.put("maximumQueuedDependencyDemands", run.maximumQueuedDependencyDemands);
            report.put("dependencyPermitHighWatermark", after.dependencyPermitHighWatermark());
            report.put("endDependencyPermits", after.dependencyPermits());
            report.put("endDependencyDemands", after.dependencyDemands());
            report.put("endQueuedDependencyDemands", after.queuedDependencyDemands());
            report.put("endTopologyWaiters", after.topologyWaiters());
            report.put("endSchedulerPending", capacity.pendingRequests());
            report.put("endSchedulerParked", capacity.parkedRequests());
            report.put("workerBuildMillis", nanosToMillis(buildNanos));
            report.put("workerUtilizationPercent", buildNanos * 100.0D / wallNanos);
            report.put("physicalWritesDuringStage",
                    after.persistence().physicalWrites() - run.before.persistence().physicalWrites());
            report.put("logicalRequests", logicalRequests);
            report.put("physicalSearches", physicalSearches);
            report.put("inFlightJoins", inFlightJoins);
            report.put("completedHits", completedHits);
            report.put("completedMisses", completedMisses);
            report.put("staleEvictions", staleEvictions);
            report.put("cacheEvictions", cacheEvictions);
            report.put("maximumGroupSize", afterReuse.maximumGroupSize());
            report.put("endMacroWaiters", afterReuse.activeWaiters());
            report.put("endMacroFlights", afterReuse.activeFlights());
            report.put("cachedCorridors", afterReuse.cachedEntries());
            report.put("cachedCorridorBytes", afterReuse.cachedBytes());
            report.put("canonicalQueries", List.copyOf(run.canonicalReports.values()));
            report.put("searchLimitCanonicalRoutes", run.searchLimitRoutes.size());
            report.put("reuseExpectationPassed", expectedReuse);
            report.put("onlyExpectedSearchFailures", expectedFailures);
            report.put("cleanupPassed", cleanup);
            report.put("passed", run.queries.size() == run.stage.queryCount()
                    && run.completed == run.stage.queryCount()
                    && run.timedOut == 0
                    && expectedFailures
                    && logicalRequests == run.stage.queryCount()
                    && expectedReuse
                    && run.maximumDependencyPermits <= 64
                    && cleanup);
            return report;
        }

        private void saveAndRestart() {
            TopologyService.Metrics beforeSave = topologyService.metrics();
            boolean saved = server.saveEverything(false, true, true);
            TopologyService.Metrics afterSave = topologyService.metrics();
            preRestartMetrics = afterSave;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("serverSaveReturned", saved);
            report.put("ordinaryTickPhysicalWrites",
                    beforeSave.persistence().physicalWrites()
                            - pressureMetricsBefore.persistence().physicalWrites());
            report.put("savePhysicalWrites",
                    afterSave.persistence().physicalWrites()
                            - beforeSave.persistence().physicalWrites());
            report.put("saveFlushes",
                    afterSave.persistence().flushes() - beforeSave.persistence().flushes());
            report.put("afterSave", persistenceMetricsMap(afterSave.persistence()));
            TopologyService.shutdown(server);
            topologyService = TopologyService.forServer(server);
            restoreStartedNanos = System.nanoTime();
            restoreRequests = persistenceSections.stream()
                    .map(section -> TopologyGraphAudit.requestClusterDependency(
                            topologyService,
                            section.level(),
                            section.section(),
                            NavigationScheduler.Priority.BACKGROUND
                    ))
                    .toList();
            report.put("requestedRestoreSections", restoreRequests.size());
            report.put("requestedRestoreChunks", persistenceSections.stream()
                    .map(section -> new DimensionChunk(
                            section.level().dimension().location().toString(),
                            new ChunkPos(section.section().x(), section.section().z()).toLong()
                    ))
                    .distinct()
                    .count());
            persistenceReport = report;
            phase = Phase.WAIT_RESTORE;
        }

        private void waitForRestore() {
            boolean timedOut = System.nanoTime() - restoreStartedNanos > PRESSURE_TIMEOUT_NANOS;
            if (!timedOut && restoreRequests.stream().anyMatch(future -> !future.isDone())) {
                return;
            }
            if (timedOut) {
                restoreRequests.stream()
                        .filter(future -> !future.isDone())
                        .forEach(future -> future.cancel(false));
            }
            int restored = 0;
            for (CompletableFuture<BaseClusterTopology> request : restoreRequests) {
                if (!request.isCompletedExceptionally() && !request.isCancelled()
                        && request.join() != null) {
                    restored++;
                }
            }
            TopologyService.Metrics after = topologyService.metrics();
            Map<String, Object> report = new LinkedHashMap<>(persistenceReport);
            long requestedChunks = ((Number) report.get("requestedRestoreChunks")).longValue();
            report.put("restoredSections", restored);
            report.put("restoreTimedOut", timedOut);
            report.put("persistenceHits", after.persistenceHits());
            report.put("freshBuilds", after.freshBuilds());
            report.put("afterRestore", persistenceMetricsMap(after.persistence()));
            report.put("passed", !timedOut
                    && restored == restoreRequests.size()
                    && after.persistenceHits() == restoreRequests.size()
                    && after.freshBuilds() == 0
                    && after.persistence().physicalReads() <= requestedChunks
                    && after.persistence().physicalWrites() == 0
                    && after.persistence().pendingChunks() == 0
                    && after.persistence().inFlightLoads() == 0);
            persistenceReport = report;
            restoreRequests = List.of();
            phase = Phase.WRITE_REPORT;
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
            long[] completedCacheWallNanos = route.completedCacheWallNanos.clone();
            long[] completedCacheAllocatedBytes = route.completedCacheAllocatedBytes.clone();
            long[] buildQueueWaitNanos = route.buildQueueWaitNanos.clone();
            long[] persistenceQueueWaitNanos = route.persistenceQueueWaitNanos.clone();
            Arrays.sort(macroNanos);
            Arrays.sort(superNanos);
            Arrays.sort(refinementNanos);
            Arrays.sort(queryCpuNanos);
            Arrays.sort(wallNanos);
            Arrays.sort(allocatedBytes);
            Arrays.sort(completedCacheWallNanos);
            Arrays.sort(completedCacheAllocatedBytes);
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
            report.put("completedCacheWarmupRuns", COMPLETED_CACHE_WARMUP_RUNS);
            report.put("measuredCompletedCacheRuns", MEASURED_COMPLETED_CACHE_RUNS);
            report.put("totalInvocations", TOTAL_ROUTE_RUNS);
            report.put("succeededRuns", route.succeeded);
            report.put("timedOutRuns", route.timedOut);
            report.put("completedCacheSucceededRuns", route.completedCacheSucceeded);
            report.put("completedCacheTimedOutRuns", route.completedCacheTimedOut);
            report.put("completedCacheFailureCounts", route.completedCacheFailureCounts);
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
            report.put("coldCompletedCorridorCacheHit", route.coldCompletedCacheHit);
            report.put("readyTopologyPhysicalSearches", route.readyTopologyPhysicalSearches);
            report.put("readyTopologyCompletedCorridorCacheHits",
                    route.readyTopologyCompletedCacheHits);
            report.put("measuredCompletedCorridorCacheHits", route.measuredCompletedCacheHits);
            report.put("synchronousCompletedCorridorCacheHits",
                    route.synchronousCompletedCacheHits);
            report.put("coldObservedServerThreadAllocatedBytes", route.coldAllocatedBytes);
            report.put("coldObservedGcCollectionCountDelta", route.coldGcCount);
            report.put("coldObservedGcPauseMillisDelta", route.coldGcMillis);
            report.put("p50WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.50D)));
            report.put("p95WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.95D)));
            report.put("p99WallLatencyMillis", nanosToMillis(percentile(wallNanos, 0.99D)));
            report.put("maxWallLatencyMillis", nanosToMillis(wallNanos[wallNanos.length - 1]));
            report.put("p50CompletedCacheHitWallMillis",
                    nanosToMillis(percentile(completedCacheWallNanos, 0.50D)));
            report.put("p95CompletedCacheHitWallMillis",
                    nanosToMillis(percentile(completedCacheWallNanos, 0.95D)));
            report.put("p99CompletedCacheHitWallMillis",
                    nanosToMillis(percentile(completedCacheWallNanos, 0.99D)));
            report.put("maxCompletedCacheHitWallMillis",
                    nanosToMillis(completedCacheWallNanos[completedCacheWallNanos.length - 1]));
            report.put("completedCachePhysicalSearches", route.completedCachePhysicalSearches);
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
            report.put("p50CompletedCacheHitAllocatedBytes",
                    percentile(completedCacheAllocatedBytes, 0.50D));
            report.put("p95CompletedCacheHitAllocatedBytes",
                    percentile(completedCacheAllocatedBytes, 0.95D));
            report.put("p99CompletedCacheHitAllocatedBytes",
                    percentile(completedCacheAllocatedBytes, 0.99D));
            report.put("maxCompletedCacheHitAllocatedBytes",
                    completedCacheAllocatedBytes[completedCacheAllocatedBytes.length - 1]);
            report.put("observedGcCollectionCountDelta", route.gcCount);
            report.put("observedGcPauseMillisDelta", route.gcMillis);
            report.put("allocationAndGcScope",
                    "server-thread/process deltas across the scheduled query wall window");
            report.put("hotMetricProtocol", Map.of(
                    "readyTopologySearch",
                    "completed corridor cache cleared before timing; topology and link caches retained",
                    "completedCorridorCache",
                    "normal production path timed before requestMacroQuery with same-tick completion observed"
            ));
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
            TopologyService.Metrics topologyMetrics = preRestartMetrics == null
                    ? topologyService.metrics()
                    : preRestartMetrics;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("benchmarkKind", "real_generated_terrain");
            report.put("sourceBaselineCommit", "fc55ced0a6282b44183e802e51c110c4954bb55b");
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
            report.put("pressureStages", pressureReports);
            report.put("searchBudgetDiagnostics", Map.of(
                    "productionBudgetSource", "distributed_64_per_dimension",
                    "probeBudgets", Arrays.stream(DIAGNOSTIC_NODE_BUDGETS).boxed().toList(),
                    "probeStages", budgetProbeReports,
                    "productionDefaultsChanged", false,
                    "probeScope", "terrain benchmark GameTest mixin only"
            ));
            report.put("persistenceLifecycle", persistenceReport);
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
                TopologyService.MacroRequest request) {
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                Field flightField = request.getClass().getDeclaredField("flight");
                flightField.setAccessible(true);
                Object flight = flightField.get(request);
                if (flight == null) {
                    return List.of();
                }
                Field queryField = flight.getClass().getDeclaredField("query");
                queryField.setAccessible(true);
                Object value = queryField.get(flight);
                if (!(value instanceof TopologyService.MacroQuery query)) {
                    return List.of();
                }
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

    @Nullable
    private static Map<String, Object> searchMetricsReport(
            @Nullable MacroSearch.Metrics metrics,
            @Nullable MacroSearchMetricsProbe.Snapshot snapshot) {
        if (metrics == null) {
            return null;
        }
        if (snapshot == null) {
            throw new IllegalStateException("GameTest macro-search metrics snapshot is missing");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("visitedNodeLimit", snapshot.visitedNodeLimit());
        report.put("expandedNodes", metrics.expandedNodes());
        report.put("discoveredNodes", snapshot.discoveredNodes());
        report.put("openNodesAtCompletion", snapshot.openNodes());
        report.put("blockedNodesAtCompletion", snapshot.blockedNodes());
        report.put("generatedConnections", metrics.generatedConnections());
        report.put("reopenedNodes", metrics.reopenedNodes());
        report.put("reexpandedBlockedNodes", metrics.reexpandedBlockedNodes());
        report.put("maximumDegree", metrics.maximumDegree());
        report.put("maximumBlockedNodes", metrics.maximumBlockedNodes());
        report.put("pendingDependencies", metrics.pendingSections());
        report.put("unavailableDependencies", metrics.unavailableSections());
        return report;
    }

    private static String completedSearchPhase(TopologyService.QueryMetrics metrics,
                                               String outcome,
                                               @Nullable MacroSearchMetricsProbe.Snapshot superSnapshot,
                                               @Nullable MacroSearchMetricsProbe.Snapshot refinementSnapshot) {
        if (!MacroSearch.Failure.SEARCH_LIMIT_REACHED.name().equals(outcome)) {
            return metrics.hierarchical() ? "COMPLETE" : "DIRECT";
        }
        MacroSearch.Metrics refinement = metrics.refinementMetrics();
        if (atVisitedNodeLimit(refinement, refinementSnapshot)) {
            return metrics.hierarchical() ? "REFINEMENT" : "DIRECT";
        }
        if (atVisitedNodeLimit(metrics.superSearchMetrics(), superSnapshot)) {
            return "SUPER";
        }
        return "UNRESOLVED";
    }

    private static boolean atVisitedNodeLimit(
            @Nullable MacroSearch.Metrics metrics,
            @Nullable MacroSearchMetricsProbe.Snapshot snapshot) {
        return metrics != null && snapshot != null
                && metrics.expandedNodes() >= snapshot.visitedNodeLimit();
    }

    private static Map<String, Object> persistenceMetricsMap(
            TopologyService.PersistenceMetrics metrics) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("physicalReads", metrics.physicalReads());
        report.put("coalescedReads", metrics.coalescedReads());
        report.put("physicalWrites", metrics.physicalWrites());
        report.put("flushes", metrics.flushes());
        report.put("pendingChunks", metrics.pendingChunks());
        report.put("pendingHighWatermark", metrics.pendingHighWatermark());
        report.put("oldestPendingMillis", nanosToMillis(metrics.oldestPendingNanos()));
        report.put("writeFailures", metrics.writeFailures());
        report.put("droppedChunks", metrics.droppedChunks());
        report.put("decodedChunks", metrics.decodedChunks());
        report.put("inFlightLoads", metrics.inFlightLoads());
        report.put("openRegions", metrics.openRegions());
        return report;
    }

    private static List<Map<String, Object>> runLengthOrder(List<String> dimensions) {
        List<Map<String, Object>> result = new ArrayList<>();
        String active = null;
        int count = 0;
        for (String dimension : dimensions) {
            if (Objects.equals(active, dimension)) {
                count++;
                continue;
            }
            if (active != null) {
                result.add(Map.of("dimension", active, "count", count));
            }
            active = dimension;
            count = 1;
        }
        if (active != null) {
            result.add(Map.of("dimension", active, "count", count));
        }
        return result;
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

    private static final class StressRun {
        private final PressureStage stage;
        private final TopologyService.Metrics before;
        private final long startedNanos;
        private final List<StressQuery> queries = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>();
        private final List<String> completionDimensions = new ArrayList<>();
        private final Map<String, Integer> failureCounts = new LinkedHashMap<>();
        private final Map<PressureRoute, Map<String, Object>> canonicalReports =
                new LinkedHashMap<>();
        private final Set<PressureRoute> searchLimitRoutes = new LinkedHashSet<>();
        private int completed;
        private int succeeded;
        private int failed;
        private int timedOut;
        private long corridorConnections;
        private boolean zeroOrdinarySlotsProgress;
        private int maximumDependencyPermits;
        private int maximumDependencyDemands;
        private int maximumQueuedDependencyDemands;

        private StressRun(PressureStage stage,
                          TopologyService.Metrics before,
                          long startedNanos) {
            this.stage = stage;
            this.before = before;
            this.startedNanos = startedNanos;
        }

        private void add(StressQuery query) {
            queries.add(query);
            query.future().whenComplete((corridor, failure) -> {
                completed++;
                latencies.add(Math.max(0L, System.nanoTime() - startedNanos));
                completionDimensions.add(
                        query.route().level().dimension().location().toString()
                );
                String outcome = failure == null
                        ? query.request().failure().name()
                        : failure.getClass().getSimpleName();
                recordCanonical(query, corridor, outcome);
                if (failure == null && corridor != null) {
                    succeeded++;
                    corridorConnections += corridor.connections().size();
                    return;
                }
                failed++;
                failureCounts.merge(outcome, 1, Integer::sum);
            });
        }

        private void recordCanonical(StressQuery query,
                                     @Nullable MacroSearch.Corridor corridor,
                                     String outcome) {
            if (canonicalReports.containsKey(query.route())) {
                return;
            }
            TopologyService.QueryMetrics metrics = query.request().metrics();
            MacroSearchMetricsProbe.Snapshot superSnapshot =
                    MacroSearchMetricsProbe.snapshot(metrics.superSearchMetrics());
            MacroSearchMetricsProbe.Snapshot refinementSnapshot =
                    MacroSearchMetricsProbe.snapshot(metrics.refinementMetrics());
            MacroSearchMetricsProbe.Snapshot combinedSnapshot =
                    MacroSearchMetricsProbe.combine(superSnapshot, refinementSnapshot);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dimension",
                    query.route().level().dimension().location().toString());
            report.put("start", Controller.positionMap(query.route().selection().start()));
            report.put("goal", Controller.positionMap(query.route().selection().goal()));
            report.put("straightLineBlocks", query.route().selection().directDistance());
            report.put("outcome", corridor == null ? outcome : "SUCCEEDED");
            report.put("failurePhase", completedSearchPhase(
                    metrics,
                    outcome,
                    superSnapshot,
                    refinementSnapshot
            ));
            report.put("completedFromCache", query.request().completedFromCache());
            report.put("wallMillis", nanosToMillis(
                    Math.max(0L, System.nanoTime() - query.submittedNanos())
            ));
            report.put("queryCpuMillis", nanosToMillis(metrics.queryCpuNanos()));
            report.put("macroSearchMillis", nanosToMillis(metrics.macroSearchNanos()));
            report.put("superSearchMillis", nanosToMillis(metrics.superSearchNanos()));
            report.put("refinementSearchMillis", nanosToMillis(
                    metrics.refinementSearchNanos()
            ));
            report.put("hierarchical", metrics.hierarchical());
            report.put("combinedSearch", searchMetricsReport(
                    metrics.searchMetrics(),
                    combinedSnapshot
            ));
            report.put("superSearch", searchMetricsReport(
                    metrics.superSearchMetrics(),
                    superSnapshot
            ));
            report.put("refinementSearch", searchMetricsReport(
                    metrics.refinementMetrics(),
                    refinementSnapshot
            ));
            report.put("corridorConnections",
                    corridor == null ? 0 : corridor.connections().size());
            canonicalReports.put(query.route(), report);
            if (MacroSearch.Failure.SEARCH_LIMIT_REACHED.name().equals(outcome)) {
                searchLimitRoutes.add(query.route());
            }
        }

        private List<PressureRoute> searchLimitRoutes() {
            return List.copyOf(searchLimitRoutes);
        }

        private void observe(TopologyService.Metrics metrics) {
            if (metrics.dependencyStartsAtFullOrdinaryAdmission()
                    > before.dependencyStartsAtFullOrdinaryAdmission()) {
                zeroOrdinarySlotsProgress = true;
            }
            maximumDependencyPermits = Math.max(
                    maximumDependencyPermits,
                    metrics.dependencyPermits()
            );
            maximumDependencyDemands = Math.max(
                    maximumDependencyDemands,
                    metrics.dependencyDemands()
            );
            maximumQueuedDependencyDemands = Math.max(
                    maximumQueuedDependencyDemands,
                    metrics.queuedDependencyDemands()
            );
        }

        private boolean complete() {
            return completed == queries.size();
        }
    }

    private static final class RouteRun {
        private final RouteCase routeCase;
        private final long[] macroNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] superNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] refinementNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] queryCpuNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] wallNanos = new long[MEASURED_ROUTE_RUNS];
        private final long[] allocatedBytes = new long[MEASURED_ROUTE_RUNS];
        private final long[] completedCacheWallNanos =
                new long[MEASURED_COMPLETED_CACHE_RUNS];
        private final long[] completedCacheAllocatedBytes =
                new long[MEASURED_COMPLETED_CACHE_RUNS];
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
        private boolean coldCompletedCacheHit;
        private long readyTopologyPhysicalSearches;
        private int readyTopologyCompletedCacheHits;
        private int measuredCompletedCacheHits;
        private int synchronousCompletedCacheHits;
        private long completedCachePhysicalSearches;
        private int completedCacheSucceeded;
        private int completedCacheTimedOut;
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
        private final Map<String, Integer> completedCacheFailureCounts = new LinkedHashMap<>();
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

        private void record(TopologyService.MacroRequest query,
                            MacroSearch.Corridor corridor,
                            long wall,
                            TopologyService.QueryMetrics queryMetrics,
                            TopologyService.Metrics before,
                            TopologyService.Metrics after,
                            long allocatedBytes,
                            long gcCount,
                            long gcMillis,
                            boolean completedSynchronously) {
            if (run == 0) {
                coldCompletedCacheHit = query.completedFromCache();
            } else if (isMeasuredReadyTopologyRun() && query.completedFromCache()) {
                readyTopologyCompletedCacheHits++;
            } else if (isMeasuredCompletedCacheRun()) {
                if (query.completedFromCache()) {
                    measuredCompletedCacheHits++;
                    if (completedSynchronously) {
                        synchronousCompletedCacheHits++;
                    }
                }
            }
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
                if (isMeasuredReadyTopologyRun()) {
                    failure = query.failure().name();
                    failureCounts.merge(failure, 1, Integer::sum);
                    blockedSection = query.blockedSection();
                } else if (isMeasuredCompletedCacheRun()) {
                    completedCacheFailureCounts.merge(query.failure().name(), 1, Integer::sum);
                }
                return;
            }
            if (isMeasuredReadyTopologyRun()) {
                succeeded++;
            } else if (isMeasuredCompletedCacheRun()) {
                completedCacheSucceeded++;
            }
            corridorConnections = corridor.connections().size();
            sampleCorridor = corridor;
        }

        private void recordTimeout(TopologyService.MacroRequest query,
                                   long wall,
                                   TopologyService.QueryMetrics queryMetrics,
                                   TopologyService.Metrics before,
                                   TopologyService.Metrics after,
                                   long allocatedBytes,
                                   long gcCount,
                                   long gcMillis,
                                   boolean completedSynchronously) {
            recordMetrics(
                    wall,
                    queryMetrics,
                    before,
                    after,
                    allocatedBytes,
                    gcCount,
                    gcMillis
            );
            if (isMeasuredReadyTopologyRun()) {
                timedOut++;
                failure = "HARNESS_TIMEOUT";
                failureCounts.merge(failure, 1, Integer::sum);
                blockedSection = query.blockedSection();
            } else if (isMeasuredCompletedCacheRun()) {
                completedCacheTimedOut++;
                completedCacheFailureCounts.merge("HARNESS_TIMEOUT", 1, Integer::sum);
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
            if (isMeasuredCompletedCacheRun()) {
                int measured = run - READY_TOPOLOGY_ROUTE_RUNS
                        - COMPLETED_CACHE_WARMUP_RUNS;
                completedCacheWallNanos[measured] = wall;
                completedCacheAllocatedBytes[measured] = observedAllocatedBytes;
                completedCachePhysicalSearches += Math.max(
                        0L,
                        after.macroQueries().physicalSearches()
                                - before.macroQueries().physicalSearches()
                );
                return;
            }
            if (!isMeasuredReadyTopologyRun()) {
                return;
            }

            int measured = run - COLD_ROUTE_RUNS - WARMUP_ROUTE_RUNS;
            macroNanos[measured] = queryMetrics.macroSearchNanos();
            superNanos[measured] = queryMetrics.superSearchNanos();
            refinementNanos[measured] = queryMetrics.refinementSearchNanos();
            queryCpuNanos[measured] = queryMetrics.queryCpuNanos();
            wallNanos[measured] = wall;
            allocatedBytes[measured] = observedAllocatedBytes;
            readyTopologyPhysicalSearches += Math.max(
                    0L,
                    after.macroQueries().physicalSearches()
                            - before.macroQueries().physicalSearches()
            );
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

        private boolean requiresCompletedCacheBypass() {
            return run < READY_TOPOLOGY_ROUTE_RUNS;
        }

        private boolean isMeasuredReadyTopologyRun() {
            return run >= COLD_ROUTE_RUNS + WARMUP_ROUTE_RUNS
                    && run < READY_TOPOLOGY_ROUTE_RUNS;
        }

        private boolean isMeasuredCompletedCacheRun() {
            return run >= READY_TOPOLOGY_ROUTE_RUNS + COMPLETED_CACHE_WARMUP_RUNS;
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
        START_PRESSURE,
        WAIT_PRESSURE,
        SAVE_AND_RESTART,
        WAIT_RESTORE,
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

    private record PressureStage(String name,
                                 List<PressureRoute> routes,
                                 int repetitions,
                                 PressureKind kind,
                                 int nodeBudget) {
        private PressureStage {
            routes = List.copyOf(routes);
            Objects.requireNonNull(kind, "kind");
            if (routes.isEmpty() || repetitions <= 0 || nodeBudget < 0) {
                throw new IllegalArgumentException("pressure stage is not executable");
            }
            if (kind == PressureKind.BUDGET_PROBE && nodeBudget <= 0) {
                throw new IllegalArgumentException("budget probe requires an explicit budget");
            }
            if (kind != PressureKind.BUDGET_PROBE
                    && routes.size() * repetitions != PRESSURE_QUERIES) {
                throw new IllegalArgumentException("pressure stage must contain 1024 queries");
            }
        }

        private int queryCount() {
            return routes.size() * repetitions;
        }
    }

    private enum PressureKind {
        IN_FLIGHT,
        COMPLETED_REUSE,
        DISTRIBUTED,
        BUDGET_PROBE
    }

    private record PressureRoute(ServerLevel level,
                                 TopologyGraphAudit.PairSelection selection) {
    }

    private record StressQuery(PressureRoute route,
                               TopologyService.MacroRequest request,
                               CompletableFuture<MacroSearch.Corridor> future,
                               long submittedNanos) {
    }

    private record DimensionChunk(String dimension, long chunk) {
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
