package com.scarasol.acceleratednavigation.gametest;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns one independent, server-thread qualification operation. */
final class TerrainQualificationController implements TerrainQualificationEntrypoint.ServerLifecycle {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_LOAD_BATCH_SIZE = 4;
    private static final long QUALIFICATION_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long DISCOVERY_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final String FIXTURE_SCENARIO = "FQS-01";
    private static final String NORMAL_CONTROL = "FQC-01";
    private static final String HANDOFF_FAILURE_CONTROL = "FQC-02";
    private static final String EARLY_STOP_CONTROL = "FQC-03";

    private final MinecraftServer server;
    private final ServerLevel overworld;
    private final ServerLevel nether;
    private final Path reportPath;
    private final Path failureReportPath;
    private final String fixtureControl;
    private final long startedNanos = System.nanoTime();
    private final ArrayDeque<ChunkLoad> chunksToLoad = new ArrayDeque<>();
    private final List<ChunkLoad> heldChunks = new ArrayList<>();
    private final List<Map<String, Object>> chunkFailures = new ArrayList<>();

    private final TerrainRouteManifest.Manifest manifest = new TerrainRouteManifest.Manifest();
    private long discoveryStartedNanos;
    private long qualificationDeadlineNanos;
    private int terrainIndex;
    private int attemptedChunkLoads;
    private int successfulChunkLoads;
    private long chunkLoadNanos;
    private long classificationsUsedByBatch;
    private Phase phase = Phase.LOAD_CHUNKS;
    private boolean stopped;
    private boolean publishPending;
    private boolean failureReportAttempted;
    private long lastDiscoveryProgressLogNanos;
    @Nullable
    private TerrainRouteManifest.Discovery activeDiscovery;

    TerrainQualificationController(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.overworld = server.overworld();
        this.nether = Objects.requireNonNull(server.getLevel(Level.NETHER), "nether");
        this.reportPath = configuredPath("acceleratedNavigation.terrainReport",
                "terrain qualification result path is not configured");
        this.failureReportPath = configuredPath("acceleratedNavigation.terrainQualificationFailureReport",
                "terrain qualification failure report path is not configured");
        String scenario = System.getProperty("acceleratedNavigation.fixtureScenario");
        if (!FIXTURE_SCENARIO.equals(scenario)) {
            throw new IllegalStateException("terrain qualification requires fixtureScenario="
                    + FIXTURE_SCENARIO);
        }
        this.fixtureControl = System.getProperty(
                "acceleratedNavigation.fixtureControl", NORMAL_CONTROL);
        if (!List.of(NORMAL_CONTROL, HANDOFF_FAILURE_CONTROL, EARLY_STOP_CONTROL)
                .contains(fixtureControl)) {
            throw new IllegalStateException("unknown terrain qualification fixture control: "
                    + fixtureControl);
        }
        prepareChunkQueue();
        LOGGER.info("Starting independent terrain qualification: scenario={}, control={}, seed={}, chunks={}, report={}, startedAt={}",
                FIXTURE_SCENARIO, fixtureControl,
                server.getWorldData().worldGenOptions().seed(), chunksToLoad.size(),
                reportPath.toAbsolutePath(), Instant.now());
    }

    @Override
    public void tick(boolean haveTime) {
        if (stopped) {
            return;
        }
        if (phase == Phase.DISCOVER && System.nanoTime() >= qualificationDeadlineNanos) {
            finishAtDeadline();
            return;
        }
        if (!haveTime) {
            return;
        }
        try {
            if (phase == Phase.LOAD_CHUNKS) {
                loadChunkBatch();
            } else if (phase == Phase.DISCOVER) {
                discoverOneTerrain();
            }
        } catch (RuntimeException failure) {
            failAndStop(failure);
        }
    }

    private void prepareChunkQueue() {
        enqueuePlan(overworld, TerrainRouteManifest.plannedChunks(overworld, "surface"));
        enqueuePlan(nether, TerrainRouteManifest.plannedChunks(nether, "nether"));
    }

    private void enqueuePlan(ServerLevel level, List<ChunkPos> plan) {
        for (ChunkPos chunk : plan) {
            chunksToLoad.addLast(new ChunkLoad(level, chunk));
        }
    }

    private void loadChunkBatch() {
        int attempted = 0;
        while (attempted++ < CHUNK_LOAD_BATCH_SIZE) {
            ChunkLoad load = chunksToLoad.pollFirst();
            if (load == null) {
                phase = Phase.DISCOVER;
                discoveryStartedNanos = System.nanoTime();
                qualificationDeadlineNanos = discoveryStartedNanos + QUALIFICATION_TIMEOUT_NANOS;
                LOGGER.info("Qualification chunks loaded: attempted={}, loaded={}, millis={}",
                        attemptedChunkLoads, successfulChunkLoads, millis(chunkLoadNanos));
                return;
            }
            attemptedChunkLoads++;
            long started = System.nanoTime();
            load.level().getChunkSource().addRegionTicket(
                    TicketType.FORCED, load.chunk(), 2, load.chunk());
            heldChunks.add(load);
            try {
                ChunkAccess chunk = load.level().getChunkSource().getChunk(
                        load.chunk().x, load.chunk().z, ChunkStatus.FULL, true);
                if (chunk == null) {
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
        }
        if ((attemptedChunkLoads & 63) == 0) {
            LOGGER.info("Qualification chunk progress: {}", chunkLoadingReport());
        }
    }

    private void discoverOneTerrain() {
        if (terrainIndex >= 3) {
            if (EARLY_STOP_CONTROL.equals(fixtureControl)) {
                failAndStop(new IllegalStateException(
                        "FQC-03 was blocked because qualification completed without an unfinished discovery step"));
                return;
            }
            finishQualification();
            return;
        }
        String terrain = terrainName(terrainIndex);
        ServerLevel level = "nether".equals(terrain) ? nether : overworld;
        if (activeDiscovery == null) {
            activeDiscovery = TerrainRouteManifest.beginDiscovery(
                    level, terrain, classificationsUsedByBatch);
        }
        logDiscoveryProgress(terrain, "before");
        long stepStartedNanos = System.nanoTime();
        TerrainRouteManifest.QualificationDiscovery result;
        try {
            result = activeDiscovery.step(Math.min(
                    System.nanoTime() + 4_000_000L, qualificationDeadlineNanos));
        } finally {
            activeDiscovery.recordStepTiming(System.nanoTime() - stepStartedNanos);
        }
        logDiscoveryProgress(terrain, "after");
        if (result == null) {
            if (EARLY_STOP_CONTROL.equals(fixtureControl)) {
                stopAfterFirstIncompleteDiscovery(terrain);
            }
            return;
        }
        appendDiscovery(result);
        LOGGER.info("Qualification terrain complete: terrain={}, routes={}, selections={}, classifications={}, elapsedMillis={}",
                terrain, result.routes.size(), result.selectionCoverage.size(), result.classifications,
                millis(System.nanoTime() - discoveryStartedNanos));
        terrainIndex++;
        activeDiscovery = null;
    }

    private void finishAtDeadline() {
        if (activeDiscovery != null) {
            appendDiscovery(activeDiscovery.stopAtDeadline());
            activeDiscovery = null;
            terrainIndex++;
        }
        while (terrainIndex < 3) {
            String terrain = terrainName(terrainIndex);
            appendDiscovery(TerrainRouteManifest.deadlineCoverage(terrain));
            terrainIndex++;
        }
        LOGGER.info("Qualification discovery reached its 300 second deadline; preserving complete routes "
                        + "and publishing coverage gaps: routes={}, selections={}, classifications={}",
                manifest.routes.size(), manifest.selectionCoverage.size(), classificationsUsedByBatch);
        finishQualification();
    }

    private void appendDiscovery(TerrainRouteManifest.QualificationDiscovery result) {
        classificationsUsedByBatch += result.classifications;
        manifest.routes.addAll(result.routes);
        manifest.selectionCoverage.addAll(result.selectionCoverage);
    }

    private static String terrainName(int index) {
        return switch (index) {
            case 0 -> "surface";
            case 1 -> "cave";
            default -> "nether";
        };
    }

    private void logDiscoveryProgress(String terrain, String phase) {
        long now = System.nanoTime();
        if (now - lastDiscoveryProgressLogNanos < DISCOVERY_LOG_INTERVAL_NANOS) {
            return;
        }
        lastDiscoveryProgressLogNanos = now;
        LOGGER.info("Qualification discovery progress: terrain={}, phase={}, thread={}, elapsedMillis={}, {}",
                terrain, phase, Thread.currentThread().getName(),
                millis(now - discoveryStartedNanos), activeDiscovery.progressSummary());
    }

    private void finishQualification() {
        boolean covered = !manifest.routes.isEmpty()
                && !manifest.selectionCoverage.isEmpty()
                && manifest.selectionCoverage.stream()
                .allMatch(selection -> "COVERED".equals(selection.status));
        TerrainRouteManifest.write(reportPath, manifest);
        publishPending = true;
        LOGGER.info("Qualification result written: state={}, routes={}, selections={}, coverageComplete={}, discoveryMillis={}, totalMillis={}",
                covered ? "COMPLETE" : "COVERAGE_GAP", manifest.routes.size(),
                manifest.selectionCoverage.size(), covered,
                millis(System.nanoTime() - discoveryStartedNanos), millis(System.nanoTime() - startedNanos));
        releaseAndHalt(Phase.COMPLETE);
    }

    @Override
    public void failAndStop(RuntimeException failure) {
        if (stopped) {
            return;
        }
        stopped = true;
        publishPending = false;
        releaseActiveDiscovery("qualification_runtime");
        publishFailureReportOnce("qualification_runtime", failure);
        LOGGER.error("Terrain qualification harness failed", failure);
        releaseAndHalt(Phase.COMPLETE);
    }

    @Override
    public void serverStopped() {
        RuntimeException failure = null;
        try {
            try {
                releaseActiveDiscovery("qualification_server_stopped");
                releaseTickets();
            } catch (RuntimeException releaseFailure) {
                publishPending = false;
                failure = releaseFailure;
                publishFailureReportOnce("qualification_ticket_release", releaseFailure);
            }
            if (failure == null && !publishPending && !stopped && phase != Phase.COMPLETE) {
                failure = new IllegalStateException(
                        "qualification server stopped before reaching a terminal phase");
                publishFailureReportOnce("qualification_server_stopped_early", failure);
            }
            if (failure == null && publishPending) {
                publishPending = false;
                LOGGER.info("Qualification report and saved world are ready for external atomic handoff");
            }
        } catch (RuntimeException stageFailure) {
            publishPending = false;
            failure = stageFailure;
            publishFailureReportOnce("qualification_server_stop", stageFailure);
        } finally {
            TerrainQualificationEntrypoint.clearController();
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void recordStoppedFailure(RuntimeException failure) {
        stopped = true;
        publishPending = false;
        releaseActiveDiscovery("qualification_server_stopped");
        publishFailureReportOnce("qualification_server_stopped", failure);
        LOGGER.error("Terrain qualification stopped with failure", failure);
        try {
            releaseTickets();
        } finally {
            TerrainQualificationEntrypoint.clearController();
        }
    }

    private Map<String, Object> chunkLoadingReport() {
        return new LinkedHashMap<>(Map.of(
                "plannedChunks", chunksToLoad.size() + heldChunks.size(),
                "attemptedChunks", attemptedChunkLoads,
                "loadedChunks", successfulChunkLoads,
                "failedChunks", chunkFailures.size(),
                "remainingChunks", chunksToLoad.size(),
                "loadMillis", millis(chunkLoadNanos),
                "failures", chunkFailures,
                "complete", chunksToLoad.isEmpty()));
    }

    private void releaseTickets() {
        RuntimeException firstFailure = null;
        for (ChunkLoad load : heldChunks) {
            try {
                load.level().getChunkSource().removeRegionTicket(
                        TicketType.FORCED, load.chunk(), 2, load.chunk());
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        heldChunks.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void releaseAndHalt(Phase terminalPhase) {
        RuntimeException failure = null;
        try {
            releaseActiveDiscovery("qualification_terminal");
            releaseTickets();
        } catch (RuntimeException releaseFailure) {
            publishPending = false;
            failure = releaseFailure;
            publishFailureReportOnce("qualification_ticket_release", releaseFailure);
            LOGGER.error("Could not release qualification chunk tickets before stop", releaseFailure);
        } finally {
            stopped = true;
            phase = terminalPhase;
            server.halt(false);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void stopAfterFirstIncompleteDiscovery(String terrain) {
        String before = activeDiscovery == null ? "none" : activeDiscovery.progressSummary();
        int ticketsBefore = heldChunks.size();
        releaseActiveDiscovery(EARLY_STOP_CONTROL);
        RuntimeException failure = new IllegalStateException(
                "FQC-03 stopped qualification after the first unfinished discovery step");
        publishPending = false;
        publishFailureReportOnce("qualification_server_stopped_early", failure);
        LOGGER.info("Fixture control FQC-03 requested early stop: scenario={}, terrain={}, terrainIndex={}, "
                        + "activeBefore={}, activeAfter={}, ticketsBefore={}",
                FIXTURE_SCENARIO, terrain, terrainIndex, before,
                activeDiscovery == null ? "released" : "present", ticketsBefore);
        releaseAndHalt(Phase.COMPLETE);
    }

    private void releaseActiveDiscovery(String reason) {
        TerrainRouteManifest.Discovery discovery = activeDiscovery;
        if (discovery == null) {
            return;
        }
        discovery.abort();
        if (discovery.hasActiveState()) {
            throw new IllegalStateException(
                    "qualification discovery retained active state after release: " + reason);
        }
        activeDiscovery = null;
        LOGGER.info("Released terrain qualification discovery state: reason={}, terrainIndex={}",
                reason, terrainIndex);
    }

    private void publishFailureReportOnce(String stage, RuntimeException failure) {
        if (failureReportAttempted) {
            return;
        }
        failureReportAttempted = true;
        try {
            TerrainQualificationStore.writeFailureReport(failureReportPath, stage, failure);
            LOGGER.info("Published terrain qualification failure report once: {}",
                    failureReportPath.toAbsolutePath());
        } catch (RuntimeException reportFailure) {
            LOGGER.error("Could not publish terrain qualification failure report", reportFailure);
        }
    }

    private static Path configuredPath(String property, String message) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private enum Phase {
        LOAD_CHUNKS,
        DISCOVER,
        COMPLETE
    }

    private record ChunkLoad(ServerLevel level, ChunkPos chunk) {
    }
}
