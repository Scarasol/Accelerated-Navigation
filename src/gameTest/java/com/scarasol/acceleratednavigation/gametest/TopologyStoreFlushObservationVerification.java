package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess.FlushObservation;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess.FlushOutcome;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess.FlushStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, opt-in verification of the test-only persistence flush observation. */
@Mod.EventBusSubscriber(modid = AcceleratedNavigation.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TopologyStoreFlushObservationVerification {

    private static final String ENABLED_PROPERTY =
            "acceleratedNavigation.terrainFlushVerification";
    private static final String REPORT_KIND = "topology_flush_observation_verification";
    private static final long PHASE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int IDLE_TICKS = 2;
    private static final List<FlushOutcome> OUTCOMES = List.of(
            FlushOutcome.SUCCESS,
            FlushOutcome.IO_FAILURE,
            FlushOutcome.UNCAUGHT_ERROR);

    private static Verifier verifier;

    private TopologyStoreFlushObservationVerification() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!enabled()) {
            return;
        }
        if (verifier != null) {
            throw new IllegalStateException("topology flush verification started twice");
        }
        verifier = new Verifier(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (verifier == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            verifier.tick();
        } catch (RuntimeException failure) {
            verifier.failAndStop(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        if (verifier == null) {
            return;
        }
        try {
            verifier.serverStopped();
        } catch (RuntimeException failure) {
            verifier.failAfterServerStopped(failure);
        }
    }

    private static final class Verifier {
        private final MinecraftServer server;
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final TopologyObservationAccess activity;
        private final TopologyStoreFlushAccess store;
        private final Path reportPath;
        private final Map<String, Object> report = new LinkedHashMap<>();
        private final List<Map<String, Object>> probes = new ArrayList<>();
        private final Map<String, Object> scenarioIsolation = new LinkedHashMap<>();
        private final Map<String, Object> abortScenario = new LinkedHashMap<>();
        private final Map<String, Object> shutdownScenario = new LinkedHashMap<>();

        private Phase phase = Phase.WAIT_IDLE;
        private long phaseDeadlineNanos = System.nanoTime() + PHASE_TIMEOUT_NANOS;
        private int quietTicks;
        private int probeIndex;
        private Map<String, Object> currentProbe;
        private boolean abortMode;
        private boolean shutdownMode;

        private Verifier(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
            this.overworld = server.overworld();
            this.nether = Objects.requireNonNull(server.getLevel(net.minecraft.world.level.Level.NETHER),
                    "nether");
            TopologyService topology = TopologyService.forServer(server);
            if (!((Object) topology instanceof TopologyObservationAccess observationAccess)
                    || !((Object) topology instanceof TopologyFlushDiagnosticAccess flushAccess)) {
                throw new IllegalStateException("flush diagnostic Mixin was not applied");
            }
            this.activity = observationAccess;
            this.store = flushAccess.acceleratedNavigation$flushAccess();
            String configuredReport = System.getProperty("acceleratedNavigation.terrainReport");
            if (configuredReport == null || configuredReport.isBlank()) {
                throw new IllegalStateException("terrain report path is not configured");
            }
            this.reportPath = Path.of(configuredReport);
            report.put("benchmarkKind", REPORT_KIND);
            report.put("state", "IN_PROGRESS");
            report.put("startedAtUtc", Instant.now().toString());
            report.put("probes", probes);
            report.put("sourceIdentity", TopologyTestObservation.sourceIdentity());
            report.put("fixtureIdentity", TopologyTestObservation.fixtureIdentity());
            report.put("directProductionDependencies", List.of(
                    Map.of("operation", "TopologyStore.requestSave",
                            "injection", "TopologyStoreFlushDiagnosticMixin.bindSaveRequest",
                            "thread", "server"),
                    Map.of("operation", "TopologyStore.scheduleFlushIfReadyLocked",
                            "injection", "TopologyStoreFlushDiagnosticMixin.bindScheduledFlush",
                            "thread", "topology I/O"),
                    Map.of("operation", "TopologyStore.runRequestedFlush",
                            "injection", "TopologyStoreFlushDiagnosticMixin.begin/finishRequestedFlush",
                            "thread", "topology I/O"),
                    Map.of("operation", "TopologyStore.flush",
                            "injection", "TopologyStoreFlushDiagnosticMixin.applyProbeOutcome",
                            "thread", "topology I/O")));
            report.put("fixtureSelfChecks", fixtureSelfChecks());
            report.put("scenarioIsolation", scenarioIsolation);
            report.put("abortScenario", abortScenario);
            report.put("shutdownScenario", shutdownScenario);
            checkpoint();
        }

        private void tick() {
            if (System.nanoTime() >= phaseDeadlineNanos) {
                throw new IllegalStateException("topology flush verification timed out in " + phase);
            }
            switch (phase) {
                case WAIT_IDLE -> waitForIdle();
                case WAIT_AFTER_DEQUEUE -> waitAfterDequeue();
                case WAIT_BEFORE_FLUSH -> waitBeforeFlush();
                case WAIT_TERMINAL -> waitForTerminal();
                case WAIT_ABORT_TERMINAL -> waitForAbortTerminal();
                case WAIT_SERVER_STOP -> waitForServerStop();
            }
        }

        private void waitForIdle() {
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            quietTicks = TopologyTestObservation.factsAndWorkersIdle(metrics)
                    ? quietTicks + 1 : 0;
            if (quietTicks < IDLE_TICKS) {
                return;
            }
            boolean shutdownProbe = shutdownMode;
            abortMode = probeIndex >= OUTCOMES.size() && !shutdownProbe;
            FlushOutcome outcome = abortMode || shutdownProbe
                    ? FlushOutcome.SUCCESS : OUTCOMES.get(probeIndex);
            long generation = store.acceleratedNavigation$tryPrepareFlushProbe(
                    outcome, overworld.dimension());
            if (generation == 0L) {
                quietTicks = 0;
                return;
            }
            currentProbe = shutdownProbe
                    ? shutdownScenario : abortMode ? abortScenario : new LinkedHashMap<>();
            currentProbe.put("outcome", outcome.name());
            currentProbe.put("generation", generation);
            if (!abortMode && !shutdownProbe) {
                probes.add(currentProbe);
            }
            scenarioIsolation.put("lastPreparedGeneration", generation);
            scenarioIsolation.put("probeCount", probes.size());
            overworld.save(null, false, false);
            phase = Phase.WAIT_AFTER_DEQUEUE;
            resetDeadline();
        }

        private void waitAfterDequeue() {
            FlushObservation observation = store.acceleratedNavigation$flushObservation();
            requireCurrentGeneration(observation);
            requireExpectedStage(observation, FlushStage.BLOCKED_AFTER_DEQUEUE);
            if (observation.stage() != FlushStage.BLOCKED_AFTER_DEQUEUE) {
                return;
            }
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            require(observation.activeFlushes() == 0,
                    "flush became active before runRequestedFlush entry");
            require(observation.targetRequested() && observation.targetQueued(),
                    "dequeued target flush lost its ownership markers");
            require(!TopologyTestObservation.factsAndWorkersIdle(metrics),
                    "idle predicate accepted a dequeued flush before method entry");
            if (shutdownMode) {
                shutdownScenario.put("generation", observation.generation());
                shutdownScenario.put("beforeShutdown", observation(observation, metrics));
                shutdownScenario.put("serverStopRequested", true);
                server.halt(false);
                phase = Phase.WAIT_SERVER_STOP;
                resetDeadline();
                return;
            }
            if (abortMode) {
                abortScenario.put("generation", observation.generation());
                abortScenario.put("beforeAbort", observation(observation, metrics));
                store.acceleratedNavigation$abortFlushProbe();
                phase = Phase.WAIT_ABORT_TERMINAL;
                resetDeadline();
                return;
            }
            nether.save(null, false, false);
            FlushObservation afterUnrelatedSave = store.acceleratedNavigation$flushObservation();
            requireCurrentGeneration(afterUnrelatedSave);
            require(afterUnrelatedSave.stage() == FlushStage.BLOCKED_AFTER_DEQUEUE,
                    "unrelated save changed the target barrier stage");
            currentProbe.put("unrelatedSave", Map.of(
                    "dimension", nether.dimension().location().toString(),
                    "issuedWhileTargetBlocked", true,
                    "targetGenerationPreserved",
                    afterUnrelatedSave.generation() == observation.generation(),
                    "targetStagePreserved",
                    afterUnrelatedSave.stage() == FlushStage.BLOCKED_AFTER_DEQUEUE));
            currentProbe.put("afterDequeue", observation(observation, metrics));
            store.acceleratedNavigation$releaseFlushProbe();
            phase = Phase.WAIT_BEFORE_FLUSH;
            resetDeadline();
        }

        private void waitBeforeFlush() {
            FlushObservation observation = store.acceleratedNavigation$flushObservation();
            requireCurrentGeneration(observation);
            requireExpectedStage(observation, FlushStage.BLOCKED_BEFORE_FLUSH);
            if (observation.stage() != FlushStage.BLOCKED_BEFORE_FLUSH) {
                return;
            }
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            require(!observation.targetRequested() && !observation.targetQueued(),
                    "target flush collections were not cleared before the physical flush window");
            require(observation.activeFlushes() == 1,
                    "active flush observation did not bridge the cleared collections");
            require(!TopologyTestObservation.factsAndWorkersIdle(metrics),
                    "idle predicate accepted an active physical flush");
            currentProbe.put("beforePhysicalFlush", observation(observation, metrics));
            store.acceleratedNavigation$releaseFlushProbe();
            phase = Phase.WAIT_TERMINAL;
            resetDeadline();
        }

        private void waitForTerminal() {
            FlushOutcome outcome = OUTCOMES.get(probeIndex);
            FlushObservation observation = store.acceleratedNavigation$flushObservation();
            requireCurrentGeneration(observation);
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            if (outcome == FlushOutcome.UNCAUGHT_ERROR) {
                if (observation.stage() != FlushStage.UNCAUGHT_ERROR_CAUGHT) {
                    return;
                }
                long activeFlushes = metric(metrics, "persistence.activeFlushes");
                require(activeFlushes >= 1,
                        "uncaught error silently cleared the active flush observation");
                require(observation.targetFlushes() == 0
                                && observation.targetFlushFailures() == 0,
                        "uncaught error incorrectly published a normal flush terminal");
                if (activeFlushes > 1
                        || !TopologyTestObservation.factsAndWorkersIdle(metrics, 1L)) {
                    return;
                }
                currentProbe.put("terminal", observation(observation, metrics));
                store.acceleratedNavigation$abortFlushProbe();
                FlushObservation released = store.acceleratedNavigation$flushObservation();
                require(released.stage() == FlushStage.ABORTED
                                && released.activeFlushes() == 0,
                        "uncaught-error observation did not release its test-only activity");
                store.acceleratedNavigation$resetAbortedFlushProbe();
                FlushObservation reset = store.acceleratedNavigation$flushObservation();
                require(reset.stage() == FlushStage.IDLE && reset.activeFlushes() == 0,
                        "uncaught-error observation did not reset after release");
                currentProbe.put("uncaughtCleanup", Map.of(
                        "stage", reset.stage().name(),
                        "activeFlushes", reset.activeFlushes(),
                        "idle", true));
                probeIndex++;
                quietTicks = 0;
                phase = Phase.WAIT_IDLE;
                resetDeadline();
                return;
            }
            if (observation.stage() != FlushStage.COMPLETE) {
                requireExpectedStage(observation, FlushStage.COMPLETE);
                return;
            }
            require(observation.targetFlushes() == 1,
                    "target flush did not publish exactly one normal terminal");
            if (outcome == FlushOutcome.IO_FAILURE) {
                require(observation.targetFlushFailures() >= 1,
                        "simulated I/O failure was not recorded for the target flush");
            } else {
                require(observation.targetFlushFailures() == 0,
                        "successful target flush recorded an I/O failure");
            }
            if (observation.activeFlushes() != 0
                    || !TopologyTestObservation.factsAndWorkersIdle(metrics)) {
                return;
            }
            currentProbe.put("terminal", observation(observation, metrics));
            probeIndex++;
            quietTicks = 0;
            phase = Phase.WAIT_IDLE;
            resetDeadline();
        }

        private void waitForAbortTerminal() {
            FlushObservation observation = store.acceleratedNavigation$flushObservation();
            requireCurrentGeneration(observation);
            require(observation.stage() == FlushStage.ABORTED,
                    "aborted flush probe did not remain in its abort terminal");
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            long activeFlushes = metric(metrics, "persistence.activeFlushes");
            if (activeFlushes != 0L
                    || !TopologyTestObservation.factsAndWorkersIdle(metrics)) {
                return;
            }
            abortScenario.put("afterAbort", observation(observation, metrics));
            store.acceleratedNavigation$resetAbortedFlushProbe();
            FlushObservation reset = store.acceleratedNavigation$flushObservation();
            require(reset.stage() == FlushStage.IDLE,
                    "aborted flush probe did not reset after worker release");
            abortScenario.put("reset", Map.of(
                    "stage", reset.stage().name(),
                    "activeFlushes", reset.activeFlushes(),
                    "idle", true));
            scenarioIsolation.put("generationsMonotonic", generationsMonotonic());
            scenarioIsolation.put("stateResetAfterAbort", true);
            shutdownMode = true;
            phase = Phase.WAIT_IDLE;
            quietTicks = 0;
            resetDeadline();
        }

        private void waitForServerStop() {
            // ServerStoppedEvent owns the terminal observation for this scenario.
        }

        private void serverStopped() {
            if (!shutdownMode || phase != Phase.WAIT_SERVER_STOP) {
                return;
            }
            FlushObservation observation = store.acceleratedNavigation$flushObservation();
            Map<String, Long> metrics = activity.acceleratedNavigation$snapshotMetrics();
            require(observation.stage() == FlushStage.ABORTED,
                    "server close did not release the flush barrier");
            require(observation.activeFlushes() == 0,
                    "server close left an active test flush observation");
            shutdownScenario.put("afterShutdown", observation(observation, metrics));
            shutdownScenario.put("serverStopped", true);
            finishReport(metrics);
        }

        private void finishReport(Map<String, Long> finalMetrics) {
            scenarioIsolation.put("generationsMonotonic", generationsMonotonic());
            scenarioIsolation.put("generations", probes.stream()
                    .map(probe -> ((Number) probe.get("generation")).longValue())
                    .toList());
            report.put("state", "COMPLETE");
            report.put("completedAtUtc", Instant.now().toString());
            report.put("finalMetrics", finalMetrics);
            checkpoint();
            verifier = null;
        }

        private void failAfterServerStopped(RuntimeException failure) {
            report.put("state", "HARNESS_FAILED");
            report.put("completedAtUtc", Instant.now().toString());
            report.put("failure", TerrainTestSupport.failureSummary(failure));
            try {
                report.put("flushObservation", store.acceleratedNavigation$flushObservation());
                report.put("metrics", activity.acceleratedNavigation$snapshotMetrics());
                checkpoint();
            } catch (RuntimeException reportFailure) {
                AcceleratedNavigation.LOGGER.error(
                        "Could not write topology flush verification failure", reportFailure);
            }
            verifier = null;
            AcceleratedNavigation.LOGGER.error(
                    "Topology flush observation verification failed after server stop", failure);
        }

        private void failAndStop(RuntimeException failure) {
            try {
                store.acceleratedNavigation$abortFlushProbe();
            } catch (RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            report.put("state", "HARNESS_FAILED");
            report.put("completedAtUtc", Instant.now().toString());
            report.put("failure", TerrainTestSupport.failureSummary(failure));
            try {
                report.put("flushObservation",
                        store.acceleratedNavigation$flushObservation());
                report.put("metrics", activity.acceleratedNavigation$snapshotMetrics());
                checkpoint();
            } catch (RuntimeException reportFailure) {
                AcceleratedNavigation.LOGGER.error(
                        "Could not write topology flush verification failure", reportFailure);
            }
            verifier = null;
            AcceleratedNavigation.LOGGER.error(
                    "Topology flush observation verification failed", failure);
            server.halt(false);
        }

        private void checkpoint() {
            report.put("updatedAtUtc", Instant.now().toString());
            TerrainTestSupport.writeReport(reportPath, report);
        }

        private boolean generationsMonotonic() {
            long previous = 0L;
            for (Map<String, Object> probe : probes) {
                long generation = ((Number) probe.get("generation")).longValue();
                if (generation <= previous) {
                    return false;
                }
                previous = generation;
            }
            return !probes.isEmpty();
        }

        private void resetDeadline() {
            phaseDeadlineNanos = System.nanoTime() + PHASE_TIMEOUT_NANOS;
        }

        private static void requireExpectedStage(FlushObservation observation,
                                                 FlushStage expected) {
            if (observation.stage() == FlushStage.COMPLETE
                    || observation.stage() == FlushStage.UNCAUGHT_ERROR_CAUGHT
                    || observation.stage() == FlushStage.ABORTED) {
                throw new IllegalStateException(
                        "flush probe reached " + observation.stage() + " before " + expected);
            }
        }

        private static Map<String, Object> observation(
                FlushObservation observation,
                Map<String, Long> metrics) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generation", observation.generation());
            result.put("stage", observation.stage().name());
            result.put("requestedFlushes", observation.requestedFlushes());
            result.put("queuedFlushes", observation.queuedFlushes());
            result.put("targetRequested", observation.targetRequested());
            result.put("targetQueued", observation.targetQueued());
            result.put("activeFlushes", metric(metrics, "persistence.activeFlushes"));
            result.put("targetFlushes", observation.targetFlushes());
            result.put("targetFlushFailures", observation.targetFlushFailures());
            result.put("idle", TopologyTestObservation.factsAndWorkersIdle(metrics));
            result.put("queuedWriteOrFlushTasks",
                    metric(metrics, "persistence.queuedWriteOrFlushTasks"));
            result.put("flushes", metric(metrics, "persistence.flushes"));
            result.put("flushFailures", metric(metrics, "persistence.flushFailures"));
            return result;
        }

        private void requireCurrentGeneration(FlushObservation observation) {
            require(observation.generation()
                            == ((Number) currentProbe.get("generation")).longValue(),
                    "flush observation belongs to a different probe generation");
        }

        private static long metric(Map<String, Long> metrics, String key) {
            return metrics.getOrDefault(key, 0L);
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }

        private static Map<String, Object> fixtureSelfChecks() {
            Map<String, Object> result = new LinkedHashMap<>();
            Path temporaryRoot;
            Path invalidTarget;
            try {
                temporaryRoot = Files.createTempDirectory("accelerated-navigation-fixture-");
                invalidTarget = temporaryRoot.resolve("report-target");
                Files.createDirectory(invalidTarget);
            } catch (IOException failure) {
                throw new IllegalStateException("could not prepare fixture self-check", failure);
            }
            boolean propagated = false;
            try {
                TerrainTestSupport.writeReport(invalidTarget, Map.of("probe", true));
            } catch (RuntimeException expected) {
                propagated = true;
                result.put("failureType", expected.getClass().getName());
            } finally {
                try {
                    Files.deleteIfExists(invalidTarget.resolveSibling("report-target.tmp"));
                    Files.deleteIfExists(invalidTarget);
                    Files.deleteIfExists(temporaryRoot);
                } catch (IOException failure) {
                    throw new IllegalStateException("could not clean fixture self-check", failure);
                }
            }
            require(propagated, "fixture report-write failure was not propagated");
            result.put("scenario", "invalid-report-target");
            result.put("reportWriteFailurePropagated", true);
            result.put("temporaryStateCleaned", true);
            return result;
        }
    }

    private enum Phase {
        WAIT_IDLE,
        WAIT_AFTER_DEQUEUE,
        WAIT_BEFORE_FLUSH,
        WAIT_TERMINAL,
        WAIT_ABORT_TERMINAL,
        WAIT_SERVER_STOP
    }
}
