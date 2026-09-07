package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Fixed-seed, test-source-only benchmark of the production macro request boundary. */
@Mod.EventBusSubscriber(modid = AcceleratedNavigation.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RealTerrainTopologyBenchmark {

    private static final String BENCHMARK_KIND = "real_generated_terrain_qualification_manifest";
    private static final Gson GSON = new Gson();
    private static final int CHUNK_LOAD_BATCH_SIZE = 4;
    private static final long CHUNK_LOAD_TICK_BUDGET_NANOS = 8_000_000L;
    private static final long QUERY_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long CANCELLATION_SETTLE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long BENCHMARK_TIMEOUT_NANOS = 2_700_000_000_000L;
    private static final int WARMUP_SAMPLES = TopologyCausalityObservation.CAUSALITY ? 0 : 5;
    private static final int HOT_SAMPLES = TopologyCausalityObservation.CAUSALITY ? 0
            : List.of("M", "probe").contains(System.getProperty("acceleratedNavigation.validation.case", "")) ? 1000 : 100;
    private static final int TOTAL_SAMPLES = 1 + WARMUP_SAMPLES + HOT_SAMPLES;
    private static final int PRESSURE_REQUESTS_PER_DIMENSION = 512;
    private static final long PRESSURE_TIMEOUT_NANOS = 120_000_000_000L;
    private static final long PRESSURE_SETTLE_TIMEOUT_NANOS = 30_000_000_000L;
    private static ServerLifecycle controller;

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
        if (!"benchmark".equals(System.getProperty("acceleratedNavigation.terrainTestMode"))) {
            throw new IllegalStateException(
                    "production terrain benchmark requires terrainTestMode=benchmark");
        }
        boolean independent = System.getProperty("acceleratedNavigation.validation.profile") != null
                && !ProductionRemediationPlan.Options.configured().usesFrozenWorld();
        controller = independent ? new ProductionRemediationControls(event.getServer()) : new Controller(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (controller == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            controller.tick();
        } catch (RuntimeException failure) {
            controller.failAndStop(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void observeShutdownStart(net.minecraftforge.event.server.ServerStoppingEvent event) {
        if (System.getProperty("acceleratedNavigation.validation.profile") != null) ProductionRemediationReport.shutdownStarted();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        ServerLifecycle current = controller;
        if (current != null) current.serverStopping();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        ServerLifecycle current = controller;
        if (current == null) {
            return;
        }
        try {
            current.serverStopped();
        } catch (RuntimeException failure) {
            current.recordStoppedFailure(failure);
        }
    }

    interface ServerLifecycle {
        void tick();

        default void serverStopping() { }

        void failAndStop(RuntimeException failure);

        void serverStopped();

        void recordStoppedFailure(RuntimeException failure);
    }

    static void clearController() {
        controller = null;
    }

    private static final class Controller implements ServerLifecycle {
        private final MinecraftServer server;
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final TopologyService topology;
        private final TopologyObservationAccess observation;
        private final TopologyFormalControlAccess formalControl;
        @Nullable private final ProductionRemediationTerrain validation;
        private final Path reportPath;
        private final HistoricalBaseline baseline;
        private final long startedNanos = System.nanoTime();
        private final ArrayDeque<ChunkLoad> chunksToLoad = new ArrayDeque<>();
        private final List<ChunkLoad> heldChunks = new ArrayList<>();
        private final List<Map<String, Object>> chunkFailures = new ArrayList<>();
        private final ArrayDeque<RouteSpec> routesToPrepare = new ArrayDeque<>();
        private final List<RouteRun> routes = new ArrayList<>();
        private final List<Map<String, Object>> routeReports = new ArrayList<>();
        private final Map<String, Object> report = new LinkedHashMap<>();
        private final Map<String, Object> runScope = new LinkedHashMap<>();

        private Phase phase = Phase.LOAD_CHUNKS;
        private long chunkLoadNanos;
        private int attemptedChunkLoads;
        private int successfulChunkLoads;
        private int routeCursor;
        private TerrainRouteManifest.Manifest routeManifest;
        private RouteRun activeRoute;
        private ActiveSample activeSample;
        private int pressureStageIndex;
        @Nullable private PressureStageRun activePressure;
        @Nullable private PressureDimensionRun activePressureDimension;
        private boolean stopped;
        private boolean shutdownRequested;
        @Nullable private String pendingFinalState;
        private int diagnosticRouteCursor;
        private boolean coverageGap;
        private boolean reportWriteAttempted;

        private Controller(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
            this.overworld = server.overworld();
            this.nether = Objects.requireNonNull(server.getLevel(Level.NETHER), "nether");
            this.topology = TopologyService.forServer(server);
            if (!((Object) topology instanceof TopologyObservationAccess observationAccess)
                    || !((Object) topology instanceof TopologyFormalControlAccess formalAccess)) {
                throw new IllegalStateException("formal terrain benchmark Mixin was not applied");
            }
            this.observation = observationAccess;
            this.formalControl = formalAccess;
            this.validation = System.getProperty("acceleratedNavigation.validation.profile") == null ? null
                    : new ProductionRemediationTerrain(topology, observation);
            String configuredReport = System.getProperty("acceleratedNavigation.terrainReport");
            if (configuredReport == null || configuredReport.isBlank()) {
                throw new IllegalStateException("terrain report path is not configured");
            }
            this.reportPath = Path.of(configuredReport);
            this.baseline = HistoricalBaseline.load(
                    System.getProperty("acceleratedNavigation.terrainBaselineReport"));
            initializeReport();
            prepareChunkQueue();
            AcceleratedNavigation.LOGGER.info(
                    "Starting qualified real terrain benchmark: seed={}, chunks={}, report={}",
                    server.getWorldData().worldGenOptions().seed(),
                    chunksToLoad.size(),
                    reportPath.toAbsolutePath());
        }

        private void initializeReport() {
            report.put("benchmarkKind", BENCHMARK_KIND);
            report.put("runScope", runScope);
            report.put("routes", routeReports);
            report.put("pressureStages", new ArrayList<Map<String, Object>>());
            report.put("historyBaseline", baseline.identity());
        }

        public void tick() {
            if (stopped) {
                return;
            }
            if (System.nanoTime() - startedNanos > BENCHMARK_TIMEOUT_NANOS) {
                failAndStop(new IllegalStateException("terrain benchmark exceeded 45 minutes"));
                return;
            }
            switch (phase) {
                case LOAD_CHUNKS -> loadChunkBatch();
                case PREPARE_ROUTES -> prepareOneRoute();
                case START_SAMPLE -> startSample();
                case WAIT_SAMPLE -> waitForSample();
                case WAIT_CANCELLATION_SETTLE -> waitForCancellationSettle();
                case START_PRESSURE -> startPressureStage();
                case WAIT_PRESSURE -> waitForPressureStage();
                case WRITE_REPORT -> finishAndStop();
                case COMPLETE -> {
                }
            }
        }

        private void prepareChunkQueue() {
            enqueueRegion(overworld, 13, 56, -18, -6);
            enqueueRegion(nether, -2, 38, -6, 6);
            AcceleratedNavigation.LOGGER.info(
                    "Formal terrain chunk plan prepared: plannedChunks={}", chunksToLoad.size());
        }

        private void enqueueRegion(ServerLevel level,
                                   int minimumX,
                                   int maximumX,
                                   int minimumZ,
                                   int maximumZ) {
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    chunksToLoad.addLast(new ChunkLoad(level, new ChunkPos(x, z)));
                }
            }
        }

        private void loadChunkBatch() {
            long deadline = System.nanoTime() + CHUNK_LOAD_TICK_BUDGET_NANOS;
            int attempted = 0;
            while (attempted < CHUNK_LOAD_BATCH_SIZE && System.nanoTime() < deadline) {
                ChunkLoad load = chunksToLoad.pollFirst();
                if (load == null) {
                    routeManifest = loadAndValidateManifest();
                    List<RouteSpec> plannedRoutes = routeSpecsFromManifest(routeManifest);
                    if (validation != null) plannedRoutes = plannedRoutes.stream().filter(route -> validation.selected(route.route().name)).toList();
                    if (TopologyCausalityObservation.CAUSALITY) {
                        Set<String> selected = Set.of("surface_512_01", "surface_512_02",
                                "surface_512_03", "surface_512_04", "nether_96_06",
                                "surface_96_01", "cave_96_01", "nether_96_01");
                        plannedRoutes = plannedRoutes.stream()
                                .filter(route -> selected.contains(route.route().name)).toList();
                    }
                    routesToPrepare.addAll(plannedRoutes);
                    recordRunScope(routeManifest, plannedRoutes);
                    AcceleratedNavigation.LOGGER.info(
                            "Formal terrain chunks loaded: attempted={}, loaded={}, failures={}, millis={}",
                            attemptedChunkLoads, successfulChunkLoads, chunkFailures.size(),
                            nanosToMillis(chunkLoadNanos));
                    phase = Phase.PREPARE_ROUTES;
                    return;
                }
                attempted++;
                attemptedChunkLoads++;
                long started = System.nanoTime();
                load.level().getChunkSource().addRegionTicket(
                        TicketType.FORCED, load.chunk(), 2, load.chunk());
                heldChunks.add(load);
                try {
                    if (load.level().getChunkSource().getChunk(
                            load.chunk().x, load.chunk().z, ChunkStatus.FULL, true) == null) {
                        throw new IllegalStateException("FULL chunk request returned no chunk");
                    }
                    successfulChunkLoads++;
                } catch (RuntimeException failure) {
                    chunkFailures.add(Map.of(
                            "dimension", dimension(load.level()),
                            "x", load.chunk().x,
                            "z", load.chunk().z,
                            "failure", TerrainTestSupport.failureSummary(failure)));
                    throw failure;
                } finally {
                    chunkLoadNanos += System.nanoTime() - started;
                }
                if ((attemptedChunkLoads & 63) == 0) {
                    AcceleratedNavigation.LOGGER.info(
                            "Formal terrain chunk progress: attempted={}, loaded={}, remaining={}, millis={}",
                            attemptedChunkLoads, successfulChunkLoads, chunksToLoad.size(),
                            nanosToMillis(chunkLoadNanos));
                }
            }
        }

        private TerrainRouteManifest.Manifest loadAndValidateManifest() {
            String configured = System.getProperty("acceleratedNavigation.terrainQualificationReport");
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException("prepared terrain qualification manifest is not configured");
            }
            Path qualificationReportPath = Path.of(configured).toAbsolutePath().normalize();
            TerrainRouteManifest.Manifest loaded =
                    TerrainQualificationStore.readPreparedManifest(qualificationReportPath);
            if (loaded.routes == null || loaded.selectionCoverage == null) {
                throw new IllegalStateException("terrain qualification manifest is incomplete");
            }

            Map<String, Integer> nameCounts = new HashMap<>();
            Map<String, Integer> endpointPairCounts = new HashMap<>();
            for (TerrainRouteManifest.Route route : loaded.routes) {
                if (route != null && route.name != null && !route.name.isBlank()) {
                    nameCounts.merge(route.name, 1, Integer::sum);
                }
                if (TerrainRouteManifest.routeIssue(route) == null) {
                    endpointPairCounts.merge(endpointPair(route), 1, Integer::sum);
                }
            }
            Map<String, TerrainRouteManifest.Route> routesByName = new LinkedHashMap<>();
            List<TerrainRouteManifest.Route> validRoutes = new ArrayList<>();
            for (int index = 0; index < loaded.routes.size(); index++) {
                TerrainRouteManifest.Route entry = loaded.routes.get(index);
                String issue = TerrainRouteManifest.routeIssue(entry);
                if (issue == null && nameCounts.getOrDefault(entry.name, 0) > 1) {
                    issue = "duplicate name=" + entry.name;
                } else if (issue == null
                        && endpointPairCounts.getOrDefault(endpointPair(entry), 0) > 1) {
                    issue = "duplicate endpoint pair=" + endpointPair(entry);
                }
                if (issue != null) {
                    AcceleratedNavigation.LOGGER.warn(
                            "Ignoring invalid terrain qualification route index {}: {}", index, issue);
                    continue;
                }
                routesByName.put(entry.name, entry);
                validRoutes.add(entry);
            }

            List<TerrainRouteManifest.SelectionCoverage> validCoverage = new ArrayList<>();
            Set<String> coverageIdentities = new HashSet<>();
            for (int index = 0; index < loaded.selectionCoverage.size(); index++) {
                TerrainRouteManifest.SelectionCoverage selection = loaded.selectionCoverage.get(index);
                String issue = TerrainRouteManifest.coverageIssue(selection);
                String identity = issue == null
                        ? selection.selection + ":" + selection.terrain + ":"
                        + selection.requestedDistance : "";
                if (issue != null || !coverageIdentities.add(identity)) {
                    AcceleratedNavigation.LOGGER.warn(
                            "Ignoring invalid terrain qualification coverage index {}: {}",
                            index, issue == null ? "duplicate identity" : issue);
                    continue;
                }
                Set<String> retainedSet = new HashSet<>();
                List<TerrainRouteManifest.Route> retainedRoutes = new ArrayList<>();
                for (String name : selection.routeNames) {
                    TerrainRouteManifest.Route route = routesByName.get(name);
                    if (route == null
                            || !TerrainRouteManifest.coverageMatchesRoute(selection, route)
                            || selection.requestedDistance != route.requestedDistance
                            || !retainedSet.add(name)) {
                        AcceleratedNavigation.LOGGER.warn(
                                "Ignoring invalid route reference in terrain qualification coverage {}: {}",
                                index, name);
                        continue;
                    }
                    retainedRoutes.add(route);
                }
                TerrainRouteManifest.SelectionCoverage sanitized =
                        TerrainRouteManifest.recalculateCoverage(selection, retainedRoutes);
                if (!TerrainRouteManifest.sameCoverage(selection, sanitized)) {
                    AcceleratedNavigation.LOGGER.warn(
                            "Terrain qualification coverage was recalculated: index={}, selection={}, "
                                    + "terrain={}, distance={}, status={}->{}, routes={}->{}, "
                                    + "crossHeight={}->{}, sources={}->{}, maxSourceContribution={}->{}, "
                                    + "startChunks={}->{}, goalChunks={}->{}, chunkPairs={}->{}",
                            index, selection.selection, selection.terrain, selection.requestedDistance,
                            selection.status, sanitized.status,
                            selection.foundCount, sanitized.foundCount,
                            selection.crossHeightFound, sanitized.crossHeightFound,
                            selection.sourceCount, sanitized.sourceCount,
                            selection.maximumSourceContribution, sanitized.maximumSourceContribution,
                            selection.startChunkCount, sanitized.startChunkCount,
                            selection.goalChunkCount, sanitized.goalChunkCount,
                            selection.chunkPairCount, sanitized.chunkPairCount);
                }
                if (retainedRoutes.size() != selection.foundCount) {
                    AcceleratedNavigation.LOGGER.warn(
                            "Terrain qualification coverage {} lost invalid route references: retained={}/declared={}",
                            index, sanitized.foundCount, selection.foundCount);
                }
                coverageGap |= "COVERAGE_GAP".equals(sanitized.status);
                validCoverage.add(sanitized);
            }
            if (coverageIdentities.size() != 12) {
                coverageGap = true;
                AcceleratedNavigation.LOGGER.warn(
                        "Terrain qualification manifest has {}/12 distinct selection records",
                        coverageIdentities.size());
            }
            TerrainRouteManifest.Manifest sanitizedManifest = new TerrainRouteManifest.Manifest();
            sanitizedManifest.routes = validRoutes;
            sanitizedManifest.selectionCoverage = validCoverage;
            return sanitizedManifest;
        }

        private void recordRunScope(TerrainRouteManifest.Manifest manifest,
                                    List<RouteSpec> plannedRoutes) {
            List<Map<String, Object>> usedSelections = new ArrayList<>();
            List<Map<String, Object>> unrunSelections = new ArrayList<>();
            for (TerrainRouteManifest.SelectionCoverage selection : manifest.selectionCoverage) {
                Map<String, Object> scope = new LinkedHashMap<>();
                scope.put("selection", selection.selection);
                scope.put("terrain", selection.terrain);
                scope.put("requestedDistance", selection.requestedDistance);
                scope.put("plannedRoutes", selection.requestedCount);
                scope.put("selectedRoutes", selection.foundCount);
                scope.put("coverage", selection.status);
                ("COVERED".equals(selection.status) ? usedSelections : unrunSelections).add(scope);
            }
            runScope.put("qualificationSelectionsUsed", usedSelections);
            runScope.put("unrunQualificationSelections", unrunSelections);
            runScope.put("plannedOrdinaryRoutes", plannedRoutes.size());
            runScope.put("plannedOrdinarySamples", plannedRoutes.stream().mapToInt(route -> validation == null
                    ? TOTAL_SAMPLES : validation.count(route.route().name)).sum());
            runScope.put("ordinarySamplesPerRoute", Map.of(
                    "firstObserved", 1,
                    "warmup", WARMUP_SAMPLES,
                    "hot", HOT_SAMPLES));
            runScope.put("pressureStages", List.of(
                    "high_coalescing", "completed_corridor_replay", "distributed_routes"));
            runScope.put("plannedPressureRequestsPerStage", Map.of(
                    "minecraft:overworld", PRESSURE_REQUESTS_PER_DIMENSION,
                    "minecraft:the_nether", PRESSURE_REQUESTS_PER_DIMENSION));
        }

        private static String endpointPair(TerrainRouteManifest.Route route) {
            return route.start.x + "," + route.start.y + "," + route.start.z + ":"
                    + route.goal.x + "," + route.goal.y + "," + route.goal.z;
        }

        private void prepareOneRoute() {
            RouteSpec spec = routesToPrepare.pollFirst();
            if (spec == null) {
                phase = Phase.START_SAMPLE;
                return;
            }
            RouteRun route = selectRoute(spec);
            routes.add(route);
            routeReports.add(route.report);
        }

        private RouteRun selectRoute(RouteSpec spec) {
            ServerLevel level = spec.terrain() == Terrain.NETHER ? nether : overworld;
            TerrainRouteManifest.Route entry = spec.route();
            BlockPos start = entry.start.toBlockPos();
            BlockPos goal = entry.goal.toBlockPos();
            return new RouteRun(spec, level, start, goal, entry, validation == null ? TOTAL_SAMPLES : validation.count(entry.name));
        }

        private void startSample() {
            if (activeRoute == null) {
                if (routeCursor >= routes.size()) {
                    phase = Phase.START_PRESSURE;
                    return;
                }
                activeRoute = routes.get(routeCursor++);
            }
            if (activeRoute.terminal || activeRoute.sampleIndex >= activeRoute.plannedSamples) {
                activeRoute.finish(baseline);
                activeRoute = null;
                return;
            }
            if (validation != null) {
                Set<Long> chunks = new HashSet<>();
                for (ChunkLoad held : heldChunks) if (held.level == activeRoute.level) chunks.add(held.chunk.toLong());
                var preparation = validation.prepare(activeRoute.spec.route().name, activeRoute.sampleIndex,
                        activeRoute.level, activeRoute.start, activeRoute.goal, chunks);
                if (preparation == ProductionRemediationTerrain.Preparation.WAIT) return;
                if (preparation == ProductionRemediationTerrain.Preparation.SKIP) {
                    activeRoute.samples.add(new SampleResult(currentSamplePhase(), "NOT_ENTERED", -1, -1, -1));
                    activeRoute.sampleIndex++;
                    return;
                }
            }
            formalControl.acceleratedNavigation$clearCompletedCorridors();
            long startedNanos = System.nanoTime();
            long startedCpuNanos = processCpuNanos();
            int startedTick = server.getTickCount();
            try {
                TopologyService.MacroRequest request = topology.requestMacroQuery(
                        activeRoute.level,
                        UUID.nameUUIDFromBytes(("terrain-benchmark:" + activeRoute.spec.route().name
                                + ":" + activeRoute.sampleIndex).getBytes(StandardCharsets.UTF_8)),
                        activeRoute.start,
                        activeRoute.goal,
                        BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                        NavigationScheduler.Priority.ACTIVE);
                CompletableFuture<MacroSearch.Corridor> future = request.future();
                ActiveSample sample = new ActiveSample(
                        request,
                        startedNanos,
                        startedCpuNanos,
                        startedTick,
                        future.isDone());
                activeSample = sample;
                String diagnosticRoute = activeRoute.spec.route().name;
                ServerLevel diagnosticLevel = activeRoute.level;
                BlockPos diagnosticStart = activeRoute.start, diagnosticGoal = activeRoute.goal;
                int diagnosticSample = activeRoute.sampleIndex;
                future.whenComplete((corridor, failure) -> {
                    TopologyCausalityObservation.logical(diagnosticRoute, "ordinary", diagnosticSample,
                            diagnosticLevel, diagnosticStart, diagnosticGoal, request, corridor != null, failure);
                    SampleTerminal terminal = SampleTerminal.completed(
                            System.nanoTime(), processCpuNanos(), corridor,
                            unwrapCompletionFailure(failure)).withObservedTick(server.getTickCount());
                    server.execute(() -> deliverSampleTerminal(sample, terminal));
                });
                phase = Phase.WAIT_SAMPLE;
                if (sample.terminal != null) {
                    waitForSample();
                }
            } catch (RuntimeException failure) {
                if (validation != null) validation.sample(activeRoute.spec.route().name, activeRoute.sampleIndex, activeRoute.level,
                        activeRoute.start, activeRoute.goal, sampleOwner(), null, "EXCEPTION", failure, startedNanos,
                        System.nanoTime(), cpuDelta(startedCpuNanos, processCpuNanos()), startedTick, server.getTickCount());
                activeRoute.recordSubmissionFailure(currentSamplePhase(), failure);
                activeRoute.sampleIndex++;
            }
        }

        private UUID sampleOwner() {
            return UUID.nameUUIDFromBytes(("terrain-benchmark:" + activeRoute.spec.route().name + ":" + activeRoute.sampleIndex).getBytes(StandardCharsets.UTF_8));
        }

        private String currentSamplePhase() {
            return validation == null ? samplePhase(activeRoute.sampleIndex) : validation.phase(activeRoute.spec.route().name, activeRoute.sampleIndex);
        }

        private void recordValidationSample(ActiveSample sample, SampleTerminal terminal, String forcedOutcome) {
            if (validation == null) return;
            String outcome = forcedOutcome != null ? forcedOutcome : terminal.failure instanceof java.util.concurrent.CancellationException ? "CANCELLED"
                    : terminal.failure != null ? "EXCEPTION" : terminal.corridor != null ? "SUCCESS" : "BUSINESS_FAILURE";
            validation.sample(activeRoute.spec.route().name, activeRoute.sampleIndex, activeRoute.level, activeRoute.start, activeRoute.goal,
                    sampleOwner(), sample.request, outcome, terminal.failure, sample.startedNanos, terminal.observedNanos,
                    cpuDelta(sample.startedCpuNanos, terminal.observedCpuNanos), sample.startedTick, terminal.observedTick);
        }

        private void waitForSample() {
            ActiveSample sample = requireActiveSample();
            SampleTerminal terminal = sample.terminal;
            long now = System.nanoTime();
            if (terminal == null) {
                if (now - sample.startedNanos < QUERY_TIMEOUT_NANOS) {
                    return;
                }
                SampleTerminal timeout = SampleTerminal.timeout(
                        now,
                        processCpuNanos(),
                        server.getTickCount(),
                        sample.request.progress());
                sample.terminal = timeout;
                sample.cancellationStartedNanos = now;
                sample.request.cancel();
                phase = Phase.WAIT_CANCELLATION_SETTLE;
                return;
            }
            if (terminal.timeout) {
                phase = Phase.WAIT_CANCELLATION_SETTLE;
                return;
            }
            recordValidationSample(sample, terminal, null);
            activeRoute.recordCompleted(
                    currentSamplePhase(),
                    terminal.observedNanos - sample.startedNanos,
                    cpuDelta(sample.startedCpuNanos, terminal.observedCpuNanos),
                    Math.max(0, terminal.observedTick - sample.startedTick),
                    sample.completedSynchronously,
                    sample.request,
                    terminal.corridor,
                    terminal.failure);
            completeSample();
        }

        private void waitForCancellationSettle() {
            ActiveSample sample = requireActiveSample();
            SampleTerminal timeout = sample.terminal;
            if (timeout == null || !timeout.timeout) {
                throw new IllegalStateException("cancellation settle phase has no timeout terminal");
            }
            long now = System.nanoTime();
            Map<String, Long> after = observation.acceleratedNavigation$snapshotMetrics();
            boolean settled = TopologyTestObservation.factsAndWorkersIdle(after);
            long settleNanos = now - sample.cancellationStartedNanos;
            if (!settled && settleNanos < CANCELLATION_SETTLE_TIMEOUT_NANOS) {
                return;
            }
            recordValidationSample(sample, timeout, "TIMEOUT");
            activeRoute.recordTimeout(
                    currentSamplePhase(),
                    timeout.observedNanos - sample.startedNanos,
                    cpuDelta(sample.startedCpuNanos, timeout.observedCpuNanos),
                    Math.max(0, timeout.observedTick - sample.startedTick),
                    timeout.progress,
                    settleNanos,
                    settled);
            if (settled) {
                completeSample();
                return;
            }

            activeRoute.sampleIndex++;
            activeRoute.finish(baseline);
            String reason = "timed-out request did not converge after cancellation";
            for (int index = routeCursor; index < routes.size(); index++) {
                routes.get(index).markIncomparable(reason, baseline);
            }
            AcceleratedNavigation.LOGGER.error(
                    "Formal terrain cancellation did not settle: route={}, settleMillis={}",
                    activeRoute.spec.route().name, nanosToMillis(settleNanos));
            activeSample = null;
            failAndStop(new IllegalStateException(reason));
        }

        private ActiveSample requireActiveSample() {
            if (activeSample == null) {
                throw new IllegalStateException("sample phase has no active sample");
            }
            return activeSample;
        }

        private void deliverSampleTerminal(ActiveSample sample, SampleTerminal terminal) {
            if (stopped || activeSample != sample || sample.terminal != null) {
                return;
            }
            sample.terminal = terminal;
        }

        private void completeSample() {
            activeRoute.sampleIndex++;
            activeSample = null;
            phase = Phase.START_SAMPLE;
        }

        private void startPressureStage() {
            if (pressureStageIndex >= 3 || validation != null && !validation.pressureEnabled()) {
                phase = Phase.WRITE_REPORT;
                return;
            }
            String stageName = switch (pressureStageIndex) {
                case 0 -> "high_coalescing";
                case 1 -> "completed_corridor_replay";
                default -> "distributed_routes";
            };
            if (validation != null && validation.skipPressure(stageName)) { pressureStageIndex++; return; }
            if (pressureStageIndex == 0) {
                formalControl.acceleratedNavigation$clearCompletedCorridors();
            }
            activePressure = new PressureStageRun(stageName, System.nanoTime());
            startNextPressureDimension();
        }

        private void startNextPressureDimension() {
            PressureStageRun stage = activePressure;
            if (stage == null) {
                throw new IllegalStateException("pressure stage has no active state");
            }
            while (stage.nextDimensionIndex < 2) {
                boolean netherDimension = stage.nextDimensionIndex++ == 1;
                String selectionTerrain = netherDimension ? "nether" : "overworld";
                ServerLevel level = netherDimension ? nether : overworld;
                List<TerrainRouteManifest.Route> selectedRoutes =
                        pressureRoutes(routeManifest, selectionTerrain);
                Map<String, Long> before = observation.acceleratedNavigation$snapshotMetrics();
                PressureDimensionRun dimension = new PressureDimensionRun(
                        dimension(level), before, System.nanoTime());
                if (selectedRoutes.isEmpty()) {
                    coverageGap = true;
                    dimension.coverageGap = true;
                    dimension.coverageReason = "qualified pressure selection is unavailable";
                    stage.addDimension(dimension.finish(
                            before, System.nanoTime(), baseline, stage.name));
                    continue;
                }
                if (pressureStageIndex == 0) {
                    formalControl.acceleratedNavigation$invalidateSections(
                            level.dimension(),
                            List.of(SectionPos.of(selectedRoutes.get(0).start.toBlockPos())));
                }
                activePressureDimension = dimension;
                if (pressureStageIndex < 2) {
                    submitRepeatedPressureRequests(
                            stage, dimension, selectedRoutes.get(0), PRESSURE_REQUESTS_PER_DIMENSION);
                } else {
                    submitDistributedPressureRequests(stage, dimension, selectedRoutes);
                }
                dimension.updatePeaks(observation.acceleratedNavigation$snapshotMetrics());
                phase = Phase.WAIT_PRESSURE;
                return;
            }
            finishPressureStage(stage);
        }

        private void submitRepeatedPressureRequests(PressureStageRun stage,
                                                     PressureDimensionRun dimension,
                                                     TerrainRouteManifest.Route route,
                                                     int count) {
            for (int index = 0; index < count; index++) {
                submitPressureRequest(stage, dimension, route, index);
            }
        }

        private void submitDistributedPressureRequests(PressureStageRun stage,
                                                       PressureDimensionRun dimension,
                                                       List<TerrainRouteManifest.Route> routes) {
            int routeCount = Math.min(TerrainRouteManifest.PRESSURE_ROUTES_PER_DIMENSION,
                    routes.size());
            for (int index = 0; index < routeCount; index++) {
                for (int repetition = 0; repetition < 8; repetition++) {
                    submitPressureRequest(
                            stage, dimension, routes.get(index), index * 8 + repetition);
                }
            }
        }

        private void submitPressureRequest(PressureStageRun stage,
                                           PressureDimensionRun dimension,
                                           TerrainRouteManifest.Route route,
                                           int ownerIndex) {
            Terrain terrain = "nether".equals(route.terrain)
                    ? Terrain.NETHER : Terrain.SURFACE;
            ServerLevel level = terrain == Terrain.NETHER ? nether : overworld;
            BlockPos start = route.start.toBlockPos();
            BlockPos goal = route.goal.toBlockPos();
            UUID owner = UUID.nameUUIDFromBytes(("terrain-pressure:" + stage.name + ":" + dimension.dimension + ":" + ownerIndex).getBytes(StandardCharsets.UTF_8));
            long started = System.nanoTime();
            int startTick = server.getTickCount();
            try {
                TopologyService.MacroRequest request = topology.requestMacroQuery(
                        level,
                        owner,
                        start,
                        goal,
                        BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                        NavigationScheduler.Priority.ACTIVE);
                PressureRequest pressureRequest = new PressureRequest(route, request, ownerIndex, started, startTick);
                dimension.requests.add(pressureRequest);
                request.future().whenComplete((corridor, failure) -> {
                    TopologyCausalityObservation.logical(route.name, stage.name, ownerIndex,
                            level, start, goal, request, corridor != null, failure);
                    PressureTerminal terminal = PressureTerminal.completed(
                            System.nanoTime(), corridor, unwrapCompletionFailure(failure),
                            request.progress().failure().name(), server.getTickCount());
                    server.execute(() -> deliverPressureTerminal(
                            stage, dimension, pressureRequest, terminal));
                });
            } catch (RuntimeException failure) {
                if (validation != null) validation.pressure(stage.name, level == nether, ownerIndex, level, start, goal, owner,
                        null, "EXCEPTION", failure, started, System.nanoTime(), startTick, server.getTickCount());
                dimension.submissionFailures.merge(
                        failure.getClass().getSimpleName(), 1, Integer::sum);
            }
        }

        private void waitForPressureStage() {
            PressureStageRun stage = activePressure;
            PressureDimensionRun dimension = activePressureDimension;
            if (stage == null || dimension == null) {
                throw new IllegalStateException("pressure stage has no active state");
            }
            long now = System.nanoTime();
            Map<String, Long> after = observation.acceleratedNavigation$snapshotMetrics();
            dimension.updatePeaks(after);
            boolean complete = dimension.requests.stream()
                    .allMatch(request -> request.terminal != null);
            if (!complete && now - dimension.startedNanos < PRESSURE_TIMEOUT_NANOS) {
                return;
            }
            if (!complete) {
                if (!dimension.timedOut) {
                    dimension.timedOut = true;
                    dimension.settlementDeadlineNanos = now + PRESSURE_SETTLE_TIMEOUT_NANOS;
                    for (PressureRequest request : dimension.requests) {
                        if (request.terminal == null) {
                            request.timeoutRequested = true;
                            recordValidationPressure(stage, dimension, request,
                                    new PressureTerminal(now, false, "TIMEOUT", server.getTickCount()));
                            request.request.cancel();
                            dimension.cancelledRequests++;
                        }
                    }
                    return;
                }
                if (now >= dimension.settlementDeadlineNanos) {
                    failAndStop(new IllegalStateException(
                            "pressure dimension cancellation did not settle"));
                }
                return;
            }
            if (dimension.timedOut && dimension.settlementDeadlineNanos > 0L
                    && now >= dimension.settlementDeadlineNanos) {
                failAndStop(new IllegalStateException(
                        "pressure dimension cancellation settled after its deadline"));
                return;
            }
            if (!TopologyTestObservation.factsAndWorkersIdle(after)) {
                if (dimension.settlementDeadlineNanos == 0L) {
                    dimension.settlementDeadlineNanos = now + PRESSURE_SETTLE_TIMEOUT_NANOS;
                }
                if (now < dimension.settlementDeadlineNanos) {
                    return;
                }
                failAndStop(new IllegalStateException(
                        "pressure dimension worker state did not settle"));
                return;
            }
            stage.addDimension(dimension.finish(after, now, baseline, stage.name));
            activePressureDimension = null;
            startNextPressureDimension();
        }

        private void deliverPressureTerminal(PressureStageRun stage,
                                             PressureDimensionRun dimension,
                                             PressureRequest request,
                                             PressureTerminal terminal) {
            if (stopped || activePressure != stage || activePressureDimension != dimension
                    || request.terminal != null) {
                return;
            }
            request.terminal = terminal;
            recordValidationPressure(stage, dimension, request, terminal);
        }

        private void recordValidationPressure(PressureStageRun stage, PressureDimensionRun dimension,
                                              PressureRequest request, PressureTerminal terminal) {
            if (validation == null || request.evidenceRecorded) return;
            request.evidenceRecorded = true;
            ServerLevel level = request.route.terrain.equals("nether") ? nether : overworld;
            UUID owner = UUID.nameUUIDFromBytes(("terrain-pressure:" + stage.name + ":" + dimension.dimension + ":" + request.ownerIndex).getBytes(StandardCharsets.UTF_8));
            String outcome = request.timeoutRequested ? "TIMEOUT" : terminal.success ? "SUCCESS"
                    : terminal.outcome.startsWith("EXCEPTION:") ? "EXCEPTION" : terminal.outcome.equals("CANCELLED") ? "CANCELLED" : "BUSINESS_FAILURE";
            validation.pressure(stage.name, level == nether, request.ownerIndex, level,
                    request.route.start.toBlockPos(), request.route.goal.toBlockPos(), owner, request.request, outcome, null,
                    request.submittedNanos, terminal.completedNanos, request.startTick, terminal.completedTick);
        }

        private void finishPressureStage(PressureStageRun stage) {
            Map<String, Object> stageReport = stage.finish(System.nanoTime(), baseline);
            pressureReports().add(stageReport);
            activePressure = null;
            pressureStageIndex++;
            phase = Phase.START_PRESSURE;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> pressureReports() {
            return (List<Map<String, Object>>) report.get("pressureStages");
        }

        private void finishAndStop() {
            if (TopologyCausalityObservation.CAUSALITY && diagnosticRouteCursor < routes.size()) {
                RouteRun route = routes.get(diagnosticRouteCursor++);
                var reference = TerrainRouteManifest.diagnosticReference(route.level, route.spec.route());
                TopologyCausalityObservation.referencePath(topology, route.level, route.spec.route().name,
                        reference.path(), reference.result());
                return;
            }
            for (RouteRun route : routes) {
                route.finish(baseline);
            }
            pendingFinalState = coverageGap ? "DATA_PARTIAL" : "DATA_COMPLETE";
            runScope.put("actualOrdinaryRoutes", routes.size());
            runScope.put("completedPressureStages", pressureStageIndex);
            runScope.put("resultCoverage", pendingFinalState);
            stopped = true;
            shutdownRequested = true;
            phase = Phase.COMPLETE;
            AcceleratedNavigation.LOGGER.info(
                    "Current-source real terrain benchmark reached terminal work; waiting for server stop: {}",
                    reportPath.toAbsolutePath());
            server.halt(false);
        }

        public void failAndStop(RuntimeException failure) {
            if (stopped) {
                throw failure;
            }
            stopped = true;
            shutdownRequested = true;
            phase = Phase.COMPLETE;
            prepareFailureReport("runtime", failure);
            try {
                try {
                    releaseTickets();
                } catch (RuntimeException cleanupFailure) {
                    AcceleratedNavigation.LOGGER.error(
                            "Could not release terrain benchmark tickets before stop", cleanupFailure);
                }
                publishReportOnce("failed terrain benchmark");
                AcceleratedNavigation.LOGGER.error(
                        "Current-source real terrain benchmark stopped after one failure", failure);
            } finally {
                server.halt(false);
            }
        }

        private void releaseTickets() {
            for (ChunkLoad load : heldChunks) {
                load.level().getChunkSource().removeRegionTicket(
                        TicketType.FORCED, load.chunk(), 2, load.chunk());
            }
            heldChunks.clear();
        }

        public void serverStopped() {
            if (shutdownRequested) {
                try {
                    RuntimeException cleanupFailure = null;
                    try {
                        releaseTickets();
                    } catch (RuntimeException failure) {
                        cleanupFailure = failure;
                        prepareFailureReport("server_stop_cleanup", failure);
                    }
                    if (!reportWriteAttempted) {
                        if (cleanupFailure != null) {
                            publishReportOnce("failed terrain benchmark cleanup");
                        } else if (pendingFinalState == null) {
                            prepareFailureReport(
                                    "shutdown_without_terminal",
                                    new IllegalStateException(
                                            "benchmark stopped without a final result state"));
                        } else {
                            report.put("state", pendingFinalState);
                        }
                        publishReportOnce("final terrain benchmark");
                    }
                } finally {
                    controller = null;
                }
                return;
            }
            if (stopped) {
                controller = null;
                return;
            }
            stopped = true;
            shutdownRequested = true;
            prepareFailureReport(
                    "server_stopped_early",
                    new IllegalStateException("server stopped before benchmark reached a terminal phase"));
            try {
                releaseTickets();
            } catch (RuntimeException cleanupFailure) {
                AcceleratedNavigation.LOGGER.error(
                        "Could not release terrain benchmark tickets after early stop",
                        cleanupFailure);
            }
            publishReportOnce("stopped terrain benchmark");
            controller = null;
        }

        public void recordStoppedFailure(RuntimeException failure) {
            if (reportWriteAttempted) {
                controller = null;
                throw failure;
            }
            stopped = true;
            shutdownRequested = true;
            prepareFailureReport("server_stopped_callback", failure);
            try {
                releaseTickets();
            } catch (RuntimeException cleanupFailure) {
                AcceleratedNavigation.LOGGER.error(
                        "Could not release terrain benchmark tickets after stop", cleanupFailure);
            }
            publishReportOnce("stopped terrain benchmark failure");
            controller = null;
        }

        private void prepareFailureReport(String stage, RuntimeException failure) {
            if (reportWriteAttempted) {
                return;
            }
            report.put("state", "HARNESS_FAILED");
            report.put("failureStage", stage);
            report.put("failureReason", TerrainTestSupport.failureSummary(failure));
            report.put("formalDataResult", "NOT_PUBLISHED");
        }

        private void publishReportOnce(String description) {
            if (reportWriteAttempted) {
                return;
            }
            reportWriteAttempted = true;
            if (validation != null) validation.finish(report, pendingFinalState == null ? "HARNESS_STOPPED" : "NOT_EXECUTED");
            else TerrainTestSupport.writeReport(reportPath, report);
            TopologyCausalityObservation.close();
            AcceleratedNavigation.LOGGER.info(
                    "Published {} report once: {}", description, reportPath.toAbsolutePath());
        }
    }

    private static final class ActiveSample {
        private final TopologyService.MacroRequest request;
        private final long startedNanos;
        private final long startedCpuNanos;
        private final int startedTick;
        private final boolean completedSynchronously;
        @Nullable private SampleTerminal terminal;
        private long cancellationStartedNanos;

        private ActiveSample(TopologyService.MacroRequest request,
                             long startedNanos,
                             long startedCpuNanos,
                             int startedTick,
                             boolean completedSynchronously) {
            this.request = request;
            this.startedNanos = startedNanos;
            this.startedCpuNanos = startedCpuNanos;
            this.startedTick = startedTick;
            this.completedSynchronously = completedSynchronously;
        }
    }

    private static final class SampleTerminal {
        private final boolean timeout;
        private final long observedNanos;
        private final long observedCpuNanos;
        private final int observedTick;
        @Nullable private final MacroSearch.Corridor corridor;
        @Nullable private final Throwable failure;
        @Nullable private final MacroSearch.Progress progress;

        private SampleTerminal(boolean timeout,
                               long observedNanos,
                               long observedCpuNanos,
                               int observedTick,
                               @Nullable MacroSearch.Corridor corridor,
                               @Nullable Throwable failure,
                               @Nullable MacroSearch.Progress progress) {
            this.timeout = timeout;
            this.observedNanos = observedNanos;
            this.observedCpuNanos = observedCpuNanos;
            this.observedTick = observedTick;
            this.corridor = corridor;
            this.failure = failure;
            this.progress = progress;
        }

        private static SampleTerminal completed(long observedNanos,
                                                 long observedCpuNanos,
                                                 @Nullable MacroSearch.Corridor corridor,
                                                 @Nullable Throwable failure) {
            return new SampleTerminal(false, observedNanos, observedCpuNanos, -1,
                    corridor, failure, null);
        }

        private static SampleTerminal timeout(long observedNanos,
                                               long observedCpuNanos,
                                               int observedTick,
                                               MacroSearch.Progress progress) {
            return new SampleTerminal(true, observedNanos, observedCpuNanos, observedTick,
                    null, null, progress);
        }

        private SampleTerminal withObservedTick(int tick) {
            return new SampleTerminal(timeout, observedNanos, observedCpuNanos, tick,
                    corridor, failure, progress);
        }
    }

    private static final class PressureTerminal {
        private final long completedNanos;
        private final boolean success;
        private final String outcome;
        private final int completedTick;

        private PressureTerminal(long completedNanos, boolean success, String outcome, int completedTick) {
            this.completedNanos = completedNanos;
            this.success = success;
            this.outcome = outcome;
            this.completedTick = completedTick;
        }

        private static PressureTerminal completed(long completedNanos,
                                                  @Nullable MacroSearch.Corridor corridor,
                                                  @Nullable Throwable failure,
                                                  String productionFailure, int completedTick) {
            if (failure != null) {
                return new PressureTerminal(completedNanos, false,
                        "EXCEPTION:" + failure.getClass().getSimpleName(), completedTick);
            }
            if (corridor != null) {
                return new PressureTerminal(completedNanos, true, "SUCCEEDED", completedTick);
            }
            String outcome = productionFailure == null || "NONE".equals(productionFailure)
                    ? "NO_RESULT" : productionFailure;
            return new PressureTerminal(completedNanos, false, outcome, completedTick);
        }
    }

    private static final class PressureRequest {
        private final TerrainRouteManifest.Route route;
        private final TopologyService.MacroRequest request;
        private final long submittedNanos;
        private final int ownerIndex;
        private final int startTick;
        private boolean evidenceRecorded;
        @Nullable private PressureTerminal terminal;
        private boolean timeoutRequested;

        private PressureRequest(TerrainRouteManifest.Route route,
                                TopologyService.MacroRequest request, int ownerIndex, long submittedNanos, int startTick) {
            this.route = route;
            this.request = request;
            this.ownerIndex = ownerIndex;
            this.submittedNanos = submittedNanos;
            this.startTick = startTick;
        }
    }

    private static final class PressureDimensionRun {
        private final String dimension;
        private final Map<String, Long> metricsBefore;
        private final long startedNanos;
        private final List<PressureRequest> requests = new ArrayList<>();
        private final Map<String, Integer> submissionFailures = new TreeMap<>();
        private final Map<String, Long> peakDependencies = new LinkedHashMap<>();
        private final Map<String, Long> peakQueues = new LinkedHashMap<>();
        private final int expectedLogicalRequests = PRESSURE_REQUESTS_PER_DIMENSION;
        private boolean timedOut;
        private int cancelledRequests;
        private long settlementDeadlineNanos;
        private boolean coverageGap;
        @Nullable private String coverageReason;

        private PressureDimensionRun(String dimension,
                                     Map<String, Long> metricsBefore,
                                     long startedNanos) {
            this.dimension = dimension;
            this.metricsBefore = metricsBefore;
            this.startedNanos = startedNanos;
            updatePeaks(metricsBefore);
        }

        private void updatePeaks(Map<String, Long> metrics) {
            updatePeak(peakDependencies, "dependencyConsumers",
                    metric(metrics, "worker.dependencyConsumers"));
            updatePeak(peakDependencies, "liveSearchDependencies",
                    metric(metrics, "worker.liveSearchDependencies"));
            updatePeak(peakDependencies, "buildDemands",
                    metric(metrics, "worker.buildDemands"));
            long totalQueued = 0L;
            for (String kind : List.of(
                    "control", "builds", "quickSearches", "longSearches", "prewarms")) {
                long queued = metric(metrics, "worker.tasks.queued." + kind);
                updatePeak(peakQueues, kind, queued);
                totalQueued += queued;
            }
            updatePeak(peakQueues, "total", totalQueued);
        }

        private Map<String, Object> finish(Map<String, Long> after,
                                           long completedNanos,
                                           HistoricalBaseline baseline,
                                           String stageName) {
            updatePeaks(after);
            long physicalSearches = counterDelta(
                    metricsBefore, after, "worker.physicalSearchesStarted");
            long completedCacheHits = counterDelta(
                    metricsBefore, after, "worker.completedCacheHits");
            long coalescedRequests = Math.max(
                    0L, requests.size() - physicalSearches - completedCacheHits);
            long successes = 0L;
            long timeouts = 0L;
            int stableTerminals = 0;
            Map<String, Long> businessFailures = new TreeMap<>();
            Map<String, Long> exceptionCounts = new TreeMap<>();
            List<Long> successfulLatencies = new ArrayList<>();
            Set<String> routesUsed = new HashSet<>();
            for (PressureRequest request : requests) {
                routesUsed.add(request.route.name);
                PressureTerminal terminal = request.terminal;
                if (terminal == null) {
                    continue;
                }
                stableTerminals++;
                if (terminal.success) {
                    successes++;
                    successfulLatencies.add(terminal.completedNanos - request.submittedNanos);
                } else if (request.timeoutRequested
                        || "TIMEOUT".equals(terminal.outcome)) {
                    timeouts++;
                } else if (terminal.outcome.startsWith("EXCEPTION:")) {
                    exceptionCounts.merge(terminal.outcome.substring("EXCEPTION:".length()),
                            1L, Long::sum);
                } else {
                    businessFailures.merge(terminal.outcome, 1L, Long::sum);
                }
            }
            submissionFailures.forEach((type, count) ->
                    exceptionCounts.merge("submission:" + type, count.longValue(), Long::sum));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dimension", dimension);
            report.put("plannedLogicalRequests", expectedLogicalRequests);
            report.put("attemptedLogicalRequests", requests.size()
                    + submissionFailures.values().stream().mapToInt(Integer::intValue).sum());
            report.put("submittedRequests", requests.size());
            report.put("stableTerminals", stableTerminals);
            report.put("physicalSearches", physicalSearches);
            report.put("coalescedRequests", coalescedRequests);
            report.put("completedCacheHits", completedCacheHits);
            report.put("successes", successes);
            report.put("businessFailureCounts", businessFailures);
            report.put("exceptionCounts", exceptionCounts);
            report.put("timeouts", timeouts);
            report.put("cancelledRequests", cancelledRequests);
            report.put("coverageGap", coverageGap);
            report.put("coverageReason", coverageReason);
            report.put("wallMillis", nanosToMillis(completedNanos - startedNanos));
            report.put("successfulLatencyMillis", distribution(successfulLatencies, true));
            report.put("peakDependencyCounts", new LinkedHashMap<>(peakDependencies));
            report.put("peakQueueCounts", new LinkedHashMap<>(peakQueues));
            report.put("routesUsed", routesUsed.size());
            report.put("complete", !coverageGap
                    && stableTerminals + submissionFailures.values().stream()
                    .mapToInt(Integer::intValue).sum() == expectedLogicalRequests);
            report.put("historyComparison",
                    baseline.pressureDimensionComparison(stageName, dimension, report));
            return report;
        }
    }

    private static final class PressureStageRun {
        private final String name;
        private final long startedNanos;
        private final List<Map<String, Object>> dimensions = new ArrayList<>();
        private int nextDimensionIndex;

        private PressureStageRun(String name, long startedNanos) {
            this.name = name;
            this.startedNanos = startedNanos;
        }

        private void addDimension(Map<String, Object> dimension) {
            dimensions.add(dimension);
        }

        private Map<String, Object> finish(long completedNanos,
                                           HistoricalBaseline baseline) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("plannedLogicalRequests",
                    sum(dimensions, "plannedLogicalRequests"));
            summary.put("attemptedLogicalRequests",
                    sum(dimensions, "attemptedLogicalRequests"));
            summary.put("submittedRequests", sum(dimensions, "submittedRequests"));
            summary.put("stableTerminals", sum(dimensions, "stableTerminals"));
            summary.put("physicalSearches", sum(dimensions, "physicalSearches"));
            summary.put("coalescedRequests", sum(dimensions, "coalescedRequests"));
            summary.put("completedCacheHits", sum(dimensions, "completedCacheHits"));
            summary.put("successes", sum(dimensions, "successes"));
            summary.put("businessFailureCounts",
                    mergeCounts(dimensions, "businessFailureCounts"));
            summary.put("exceptionCounts", mergeCounts(dimensions, "exceptionCounts"));
            summary.put("timeouts", sum(dimensions, "timeouts"));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("name", name);
            report.put("wallMillis", nanosToMillis(completedNanos - startedNanos));
            report.put("dimensions", dimensions);
            report.put("summary", summary);
            report.put("historyComparison", baseline.pressureStageComparison(name, summary));
            return report;
        }
    }

    private static final class RouteRun {
        private final RouteSpec spec;
        private final ServerLevel level;
        private final BlockPos start;
        private final BlockPos goal;
        private final Map<String, Object> report = new LinkedHashMap<>();
        private final List<SampleResult> samples = new ArrayList<>();
        private int sampleIndex;
        private final int plannedSamples;
        private boolean terminal;

        private RouteRun(RouteSpec spec,
                         ServerLevel level,
                         BlockPos start,
                         BlockPos goal,
                         TerrainRouteManifest.Route qualification, int plannedSamples) {
            this.spec = spec;
            this.level = level;
            this.start = start;
            this.goal = goal;
            this.plannedSamples = plannedSamples;
            report.put("name", qualification.name);
            report.put("terrain", spec.terrain().reportName);
            report.put("dimension", dimension(level));
            report.put("requestedDistance", qualification.requestedDistance);
            report.put("plannedSamples", plannedSamples);
        }

        private void recordSubmissionFailure(String phase, RuntimeException failure) {
            samples.add(new SampleResult(
                    phase, "SUBMISSION_EXCEPTION:" + failure.getClass().getSimpleName(),
                    0L, -1L, 0));
            AcceleratedNavigation.LOGGER.warn(
                    "Formal terrain request submission failed: route={}, phase={}, failure={}",
                    spec.route().name, phase, TerrainTestSupport.failureSummary(failure));
        }

        private void recordTimeout(String phase,
                                   long wallNanos,
                                   long processCpuNanos,
                                   int completionTicks,
                                   MacroSearch.Progress progress,
                                   long cancellationSettleNanos,
                                   boolean cancellationSettled) {
            samples.add(new SampleResult(
                    phase, "HARNESS_TIMEOUT", wallNanos, processCpuNanos, completionTicks));
            AcceleratedNavigation.LOGGER.warn(
                    "Formal terrain request timed out: route={}, phase={}, productionFailure={}, "
                            + "settled={}, settleMillis={}",
                    spec.route().name, phase, progress.failure().name(), cancellationSettled,
                    nanosToMillis(cancellationSettleNanos));
        }

        private void recordCompleted(String phase,
                                     long wallNanos,
                                     long processCpuNanos,
                                     int completionTicks,
                                     boolean completedSynchronously,
                                     TopologyService.MacroRequest request,
                                     @Nullable MacroSearch.Corridor corridor,
                                     @Nullable Throwable exceptionalFailure) {
            MacroSearch.Progress progress = request.progress();
            String outcome = exceptionalFailure != null
                    ? "EXCEPTION:" + exceptionalFailure.getClass().getSimpleName()
                    : corridor == null
                    ? (progress.failure() == MacroSearch.Failure.NONE
                    ? "NO_RESULT" : progress.failure().name())
                    : "SUCCEEDED";
            samples.add(new SampleResult(
                    phase, outcome, wallNanos, processCpuNanos, completionTicks));
            if (!"SUCCEEDED".equals(outcome)) {
                AcceleratedNavigation.LOGGER.warn(
                        "Formal terrain request completed without a corridor: route={}, phase={}, "
                                + "outcome={}, synchronous={}",
                        spec.route().name, phase, outcome, completedSynchronously);
            }
        }

        private void finish(HistoricalBaseline baseline) {
            if (report.containsKey("summary")) {
                return;
            }
            Map<String, Object> summary = sampleSummary(samples);
            report.put("recordedSamples", samples.size());
            report.put("complete", !terminal && sampleIndex >= plannedSamples);
            report.put("summary", summary);
            report.put("historyComparison",
                    baseline.routeComparison(spec.route().name, summary));
            AcceleratedNavigation.LOGGER.info(
                    "Formal terrain route complete: route={}, recorded={}/{}, complete={}",
                    spec.route().name, samples.size(), plannedSamples,
                    !terminal && sampleIndex >= plannedSamples);
        }

        private void markIncomparable(String reason, HistoricalBaseline baseline) {
            if (report.containsKey("summary")) {
                return;
            }
            terminal = true;
            report.put("notRunReason", reason);
            finish(baseline);
        }
    }

    private record SampleResult(String phase,
                                String outcome,
                                long wallNanos,
                                long processCpuNanos,
                                int completionTicks) {
    }

    private static List<RouteSpec> routeSpecsFromManifest(
            TerrainRouteManifest.Manifest manifest) {
        List<RouteSpec> specs = new ArrayList<>();
        Map<String, TerrainRouteManifest.Route> routesByName = routeIndex(manifest);
        for (TerrainRouteManifest.SelectionCoverage selection : manifest.selectionCoverage) {
            if (!TerrainRouteManifest.NORMAL_SELECTION.equals(selection.selection)
                    || !"COVERED".equals(selection.status)) {
                continue;
            }
            for (String name : selection.routeNames) {
                TerrainRouteManifest.Route route = routesByName.get(name);
                Terrain terrain = switch (route.terrain) {
                    case "surface" -> Terrain.SURFACE;
                    case "cave" -> Terrain.CAVE;
                    case "nether" -> Terrain.NETHER;
                    default -> throw new IllegalStateException("unknown route terrain " + route.terrain);
                };
                specs.add(new RouteSpec(terrain, route));
            }
        }
        return specs;
    }

    private static List<TerrainRouteManifest.Route> pressureRoutes(
            TerrainRouteManifest.Manifest manifest,
            String terrain) {
        Map<String, TerrainRouteManifest.Route> routesByName = routeIndex(manifest);
        for (TerrainRouteManifest.SelectionCoverage selection : manifest.selectionCoverage) {
            if (TerrainRouteManifest.PRESSURE_SELECTION.equals(selection.selection)
                    && terrain.equals(selection.terrain)
                    && "COVERED".equals(selection.status)) {
                return selection.routeNames.stream().map(routesByName::get).toList();
            }
        }
        return List.of();
    }

    private static Map<String, TerrainRouteManifest.Route> routeIndex(
            TerrainRouteManifest.Manifest manifest) {
        Map<String, TerrainRouteManifest.Route> routesByName = new LinkedHashMap<>();
        for (TerrainRouteManifest.Route route : manifest.routes) {
            routesByName.put(route.name, route);
        }
        return routesByName;
    }

    private static Map<String, Object> sampleSummary(List<SampleResult> samples) {
        Map<String, Integer> outcomes = new TreeMap<>();
        for (SampleResult sample : samples) {
            outcomes.merge(sample.outcome(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordedSamples", samples.size());
        summary.put("outcomes", outcomes);
        Map<String, Object> phases = new LinkedHashMap<>();
        for (String phase : List.of("first_observed", "warmup", "hot", "cold")) {
            phases.put(phase, phaseSummary(samples, phase));
        }
        summary.put("phases", phases);
        return summary;
    }

    private static Map<String, Object> phaseSummary(List<SampleResult> samples,
                                                    String phase) {
        int recorded = 0;
        int successes = 0;
        int timeouts = 0;
        Map<String, Integer> businessFailures = new TreeMap<>();
        Map<String, Integer> exceptions = new TreeMap<>();
        List<Long> successfulWall = new ArrayList<>();
        List<Long> successfulCpu = new ArrayList<>();
        List<Long> successfulTicks = new ArrayList<>();
        for (SampleResult sample : samples) {
            if (!phase.equals(sample.phase())) {
                continue;
            }
            recorded++;
            if ("SUCCEEDED".equals(sample.outcome())) {
                successes++;
                successfulWall.add(sample.wallNanos());
                successfulTicks.add((long) sample.completionTicks());
                if (sample.processCpuNanos() >= 0L) {
                    successfulCpu.add(sample.processCpuNanos());
                }
            } else if ("HARNESS_TIMEOUT".equals(sample.outcome())) {
                timeouts++;
            } else if (sample.outcome().startsWith("EXCEPTION:")) {
                exceptions.merge(sample.outcome().substring("EXCEPTION:".length()),
                        1, Integer::sum);
            } else if (sample.outcome().startsWith("SUBMISSION_EXCEPTION:")) {
                exceptions.merge("submission:"
                                + sample.outcome().substring("SUBMISSION_EXCEPTION:".length()),
                        1, Integer::sum);
            } else {
                businessFailures.merge(sample.outcome(), 1, Integer::sum);
            }
        }

        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("wallMillis", distribution(successfulWall, true));
        performance.put("processCpuMillis", distribution(successfulCpu, true));
        performance.put("completionTicks", distribution(successfulTicks, false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordedSamples", recorded);
        result.put("successes", successes);
        result.put("businessFailureCounts", businessFailures);
        result.put("exceptionCounts", exceptions);
        result.put("timeouts", timeouts);
        result.put("successfulPerformance", performance);
        return result;
    }

    private static Map<String, Object> distribution(List<Long> samples, boolean nanos) {
        if (samples.isEmpty()) {
            return Map.of(
                    "status", "NOT_APPLICABLE",
                    "reason", "no eligible successful samples",
                    "count", 0);
        }
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        double average = 0.0D;
        for (long sample : sorted) {
            average += sample;
        }
        average /= sorted.length;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "AVAILABLE");
        result.put("count", sorted.length);
        if (nanos) {
            result.put("average", nanosToMillis((long) average));
            result.put("median", nanosToMillis(percentile(sorted, 0.50D)));
            result.put("p95", nanosToMillis(percentile(sorted, 0.95D)));
            result.put("p99", nanosToMillis(percentile(sorted, 0.99D)));
            result.put("max", nanosToMillis(sorted[sorted.length - 1]));
        } else {
            result.put("average", average);
            result.put("median", percentile(sorted, 0.50D));
            result.put("p95", percentile(sorted, 0.95D));
            result.put("p99", percentile(sorted, 0.99D));
            result.put("max", sorted[sorted.length - 1]);
        }
        return result;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static void updatePeak(Map<String, Long> peaks, String key, long value) {
        peaks.merge(key, value, Math::max);
    }

    private static long counterDelta(Map<String, Long> before,
                                     Map<String, Long> after,
                                     String key) {
        return Math.max(0L, metric(after, key) - metric(before, key));
    }

    private static long sum(List<Map<String, Object>> entries, String key) {
        long total = 0L;
        for (Map<String, Object> entry : entries) {
            Object value = entry.get(key);
            if (value instanceof Number number) {
                total += number.longValue();
            }
        }
        return total;
    }

    private static Map<String, Long> mergeCounts(List<Map<String, Object>> entries,
                                                 String key) {
        Map<String, Long> result = new TreeMap<>();
        for (Map<String, Object> entry : entries) {
            Object counts = entry.get(key);
            if (!(counts instanceof Map<?, ?> countMap)) {
                continue;
            }
            for (Map.Entry<?, ?> count : countMap.entrySet()) {
                if (count.getValue() instanceof Number number) {
                    result.merge(String.valueOf(count.getKey()),
                            number.longValue(), Long::sum);
                }
            }
        }
        return result;
    }

    private static final class HistoricalBaseline {
        @Nullable private final Path path;
        @Nullable private final Map<String, Object> root;
        @Nullable private final String unavailableReason;

        private HistoricalBaseline(@Nullable Path path,
                                   @Nullable Map<String, Object> root,
                                   @Nullable String unavailableReason) {
            this.path = path;
            this.root = root;
            this.unavailableReason = unavailableReason;
        }

        private static HistoricalBaseline load(@Nullable String configuredPath) {
            if (configuredPath == null || configuredPath.isBlank()) {
                return new HistoricalBaseline(
                        null, null, "historical baseline path is not configured");
            }
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            boolean validation = System.getProperty("acceleratedNavigation.validation.profile") != null;
            if (validation) {
                Path required = Path.of(System.getProperty("acceleratedNavigation.projectRoot"))
                        .resolve(ProductionRemediationPlan.BASELINE).toAbsolutePath().normalize();
                if (!path.equals(required)) throw new IllegalStateException("Validation baseline path differs from PRM-0805-1");
                try { ProductionRemediationReport.requireHash(path, ProductionRemediationPlan.BASELINE_HASH); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            if (!Files.isRegularFile(path)) {
                return new HistoricalBaseline(path, null, "historical baseline is missing");
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Object parsed = GSON.fromJson(reader, Object.class);
                if (!(parsed instanceof Map<?, ?> parsedMap)) {
                    return new HistoricalBaseline(
                            path, null, "historical baseline has no object root");
                }
                Map<String, Object> root = new LinkedHashMap<>();
                parsedMap.forEach((key, value) -> root.put(String.valueOf(key), value));
                if (validation) ProductionRemediationReport.validateBaseline(GSON.toJsonTree(root).getAsJsonObject());
                return new HistoricalBaseline(path, root, null);
            } catch (IOException | RuntimeException failure) {
                if (validation) throw new IllegalStateException("Validation baseline is invalid", failure);
                AcceleratedNavigation.LOGGER.warn(
                        "Could not read optional terrain benchmark baseline {}",
                        path, failure);
                return new HistoricalBaseline(
                        path, null, "historical baseline could not be read");
            }
        }

        private Map<String, Object> identity() {
            if (root == null) {
                return notApplicable(unavailableReason == null
                        ? "historical baseline is unavailable" : unavailableReason);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "AVAILABLE");
            if (path != null) {
                result.put("path", normalize(path));
            }
            copyIdentity(result, "benchmarkKind");
            copyIdentity(result, "sourceBaselineCommit");
            copyIdentity(result, "seed");
            return result;
        }

        private void copyIdentity(Map<String, Object> target, String key) {
            Object value = root == null ? null : root.get(key);
            if (value != null) {
                target.put(key, value);
            }
        }

        private Map<String, Object> routeComparison(String routeName,
                                                    Map<String, Object> currentSummary) {
            if (root == null) {
                return notApplicable("historical baseline is unavailable");
            }
            Map<?, ?> baselineRoute = findNamed(root.get("routes"), routeName);
            if (baselineRoute == null) {
                return notApplicable("historical baseline has no route with the same name");
            }
            Map<?, ?> currentPerformance = nestedMap(
                    currentSummary, "phases", "hot", "successfulPerformance");
            Map<?, ?> currentWall = childMap(currentPerformance, "wallMillis");
            Map<?, ?> currentCpu = childMap(currentPerformance, "processCpuMillis");
            Map<?, ?> baselinePerformance = nestedMap(
                    baselineRoute, "summary", "phases", "hot", "successfulPerformance");
            Map<?, ?> baselineWall = childMap(baselinePerformance, "wallMillis");
            Map<?, ?> baselineCpu = childMap(baselinePerformance, "processCpuMillis");
            Map<String, Object> metrics = new LinkedHashMap<>();
            addComparison(metrics, "wall.averageMillis", number(currentWall, "average"),
                    firstNumber(number(baselineWall, "average"),
                            number(baselineRoute, "averageWallLatencyMillis")));
            addComparison(metrics, "wall.medianMillis", number(currentWall, "median"),
                    firstNumber(number(baselineWall, "median"),
                            number(baselineRoute, "p50WallLatencyMillis")));
            addComparison(metrics, "wall.p95Millis", number(currentWall, "p95"),
                    firstNumber(number(baselineWall, "p95"),
                            number(baselineRoute, "p95WallLatencyMillis")));
            addComparison(metrics, "wall.maxMillis", number(currentWall, "max"),
                    firstNumber(number(baselineWall, "max"),
                            number(baselineRoute, "maxWallLatencyMillis")));
            addComparison(metrics, "cpu.averageMillis", number(currentCpu, "average"),
                    firstNumber(number(baselineCpu, "average"),
                            number(baselineRoute, "averageQueryCpuMillis")));
            addComparison(metrics, "cpu.medianMillis", number(currentCpu, "median"),
                    firstNumber(number(baselineCpu, "median"),
                            number(baselineRoute, "p50QueryCpuMillis")));
            addComparison(metrics, "cpu.p95Millis", number(currentCpu, "p95"),
                    firstNumber(number(baselineCpu, "p95"),
                            number(baselineRoute, "p95QueryCpuMillis")));
            addComparison(metrics, "cpu.maxMillis", number(currentCpu, "max"),
                    firstNumber(number(baselineCpu, "max"),
                            number(baselineRoute, "maxQueryCpuMillis")));
            return comparable(routeName, metrics,
                    "historical route has no comparable successful hot-sample metrics");
        }

        private Map<String, Object> pressureDimensionComparison(
                String stageName,
                String dimension,
                Map<String, Object> current) {
            if (root == null) {
                return notApplicable("historical baseline is unavailable");
            }
            Map<?, ?> stage = findNamed(root.get("pressureStages"), stageName);
            Map<?, ?> baseline = stage == null
                    ? null : findIdentified(stage.get("dimensions"), "dimension", dimension);
            if (baseline == null) {
                return notApplicable(
                        "historical baseline has no same-stage, same-dimension summary");
            }
            Number currentPlanned = number(current, "plannedLogicalRequests");
            Number baselinePlanned = number(baseline, "plannedLogicalRequests");
            if (!sameCount(currentPlanned, baselinePlanned)) {
                return notApplicable("historical pressure dimension uses a different plan");
            }
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (String key : List.of(
                    "wallMillis", "physicalSearches", "coalescedRequests",
                    "completedCacheHits", "successes", "timeouts")) {
                addComparison(metrics, key, number(current, key), number(baseline, key));
            }
            return comparable(stageName + ":" + dimension, metrics,
                    "historical pressure dimension has no comparable metrics");
        }

        private Map<String, Object> pressureStageComparison(
                String stageName,
                Map<String, Object> current) {
            if (root == null) {
                return notApplicable("historical baseline is unavailable");
            }
            Map<?, ?> stage = findNamed(root.get("pressureStages"), stageName);
            if (stage == null) {
                return notApplicable("historical baseline has no pressure stage with the same name");
            }
            Map<?, ?> baselineSummary = childMap(stage, "summary");
            if (baselineSummary == null) {
                baselineSummary = stage;
            }
            Number currentPlanned = number(current, "plannedLogicalRequests");
            Number baselinePlanned = firstNumber(
                    number(baselineSummary, "plannedLogicalRequests"),
                    number(baselineSummary, "logicalRequests"));
            if (!sameCount(currentPlanned, baselinePlanned)) {
                return notApplicable("historical pressure stage uses a different request plan");
            }
            Map<String, Object> metrics = new LinkedHashMap<>();
            addComparison(metrics, "logicalRequests",
                    number(current, "attemptedLogicalRequests"),
                    firstNumber(number(baselineSummary, "attemptedLogicalRequests"),
                            number(baselineSummary, "logicalRequests")));
            addComparison(metrics, "physicalSearches",
                    number(current, "physicalSearches"),
                    number(baselineSummary, "physicalSearches"));
            addComparison(metrics, "coalescedRequests",
                    number(current, "coalescedRequests"),
                    baselineCoalesced(baselineSummary));
            addComparison(metrics, "successes",
                    number(current, "successes"),
                    firstNumber(number(baselineSummary, "successes"),
                            number(baselineSummary, "succeeded")));
            addComparison(metrics, "timeouts",
                    number(current, "timeouts"),
                    number(baselineSummary, "timedOut"));
            return comparable(stageName, metrics,
                    "historical pressure stage has no comparable metrics");
        }

        @Nullable
        private static Number baselineCoalesced(Map<?, ?> baseline) {
            Number direct = number(baseline, "coalescedRequests");
            if (direct != null) {
                return direct;
            }
            Number logical = number(baseline, "logicalRequests");
            Number physical = number(baseline, "physicalSearches");
            Number hits = firstNumber(number(baseline, "completedCacheHits"),
                    number(baseline, "completedHits"));
            if (logical == null || physical == null) {
                return null;
            }
            return Math.max(0L, logical.longValue() - physical.longValue()
                    - (hits == null ? 0L : hits.longValue()));
        }

        @Nullable
        private static Map<?, ?> findNamed(@Nullable Object collection, String name) {
            return findIdentified(collection, "name", name);
        }

        @Nullable
        private static Map<?, ?> findIdentified(@Nullable Object collection,
                                                 String key,
                                                 String identity) {
            if (!(collection instanceof List<?> entries)) {
                return null;
            }
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map
                        && identity.equals(String.valueOf(map.get(key)))) {
                    return map;
                }
            }
            return null;
        }

        @Nullable
        private static Map<?, ?> nestedMap(@Nullable Object value, String... path) {
            Object current = value;
            for (String key : path) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(key);
            }
            return current instanceof Map<?, ?> map ? map : null;
        }

        @Nullable
        private static Map<?, ?> childMap(@Nullable Map<?, ?> map, String key) {
            Object value = map == null ? null : map.get(key);
            return value instanceof Map<?, ?> child ? child : null;
        }

        @Nullable
        private static Number number(@Nullable Map<?, ?> map, String key) {
            Object value = map == null ? null : map.get(key);
            return value instanceof Number number ? number : null;
        }

        @Nullable
        private static Number firstNumber(@Nullable Number first,
                                          @Nullable Number second) {
            return first == null ? second : first;
        }

        private static boolean sameCount(@Nullable Number first,
                                         @Nullable Number second) {
            return first != null && second != null
                    && first.longValue() == second.longValue();
        }

        private static void addComparison(Map<String, Object> target,
                                          String key,
                                          @Nullable Number current,
                                          @Nullable Number baseline) {
            if (current == null || baseline == null) {
                return;
            }
            target.put(key, Map.of(
                    "current", current,
                    "baseline", baseline,
                    "delta", current.doubleValue() - baseline.doubleValue()));
        }

        private static Map<String, Object> comparable(
                String identity,
                Map<String, Object> metrics,
                String emptyReason) {
            if (metrics.isEmpty()) {
                return notApplicable(emptyReason);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "COMPARABLE");
            result.put("identity", identity);
            result.put("metrics", metrics);
            return result;
        }
    }

    private static Map<String, Object> notApplicable(String reason) {
        return Map.of("status", "NOT_APPLICABLE", "reason", reason);
    }


    @Nullable
    private static Throwable unwrapCompletionFailure(@Nullable Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String samplePhase(int index) {
        if (index == 0) {
            return "first_observed";
        }
        return index <= WARMUP_SAMPLES ? "warmup" : "hot";
    }

    private static long metric(Map<String, Long> metrics, String key) {
        return metrics.getOrDefault(key, 0L);
    }

    private static long processCpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getProcessCpuTime();
        }
        return -1L;
    }

    private static long cpuDelta(long startedCpuNanos, long completedCpuNanos) {
        if (startedCpuNanos < 0L || completedCpuNanos < startedCpuNanos) {
            return -1L;
        }
        return completedCpuNanos - startedCpuNanos;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private enum Phase {
        LOAD_CHUNKS,
        PREPARE_ROUTES,
        START_SAMPLE,
        WAIT_SAMPLE,
        WAIT_CANCELLATION_SETTLE,
        START_PRESSURE,
        WAIT_PRESSURE,
        WRITE_REPORT,
        COMPLETE
    }

    private enum Terrain {
        SURFACE("surface"),
        CAVE("cave"),
        NETHER("nether");

        private final String reportName;

        Terrain(String reportName) {
            this.reportName = reportName;
        }
    }

    private record ChunkLoad(ServerLevel level, ChunkPos chunk) {
    }

    private record RouteSpec(Terrain terrain,
                             TerrainRouteManifest.Route route) {
    }
}
