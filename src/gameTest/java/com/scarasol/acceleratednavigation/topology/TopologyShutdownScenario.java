package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** One W target per process, observed through the server's normal stopping events. */
public final class TopologyShutdownScenario implements AutoCloseable {
    private static volatile TopologyShutdownScenario active;
    private final TopologyService service;
    private final ServerLevel level;
    private final ProductionRemediationPlan.Target target;
    private final Object runtime, store;
    private final boolean mutated;
    private final TopologyHandoffScenario handoff;
    private final TopologyDerivedLifecycleScenario derived;
    private final TopologyFinalResultScenario finalResult;
    private final TopologyHandoffFailureScenario recovery;
    private final CountDownLatch ioRelease = new CountDownLatch(1);
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Map<String, Object>> lifecycle = new ArrayList<>();
    private volatile boolean writing, ioTimedOut, stopped, armed;
    private boolean stopObserved;
    private int writeFailures, acceptanceRejections, controlRejections, cleanupFailures;
    private boolean releaseWrite;
    private boolean recoveryReleased;
    private final List<Map<String, Object>> recoveryStages = new ArrayList<>();
    private TopologyFlushWindowProbe flushProbe;
    private int flushStage;

    public TopologyShutdownScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target; mutated = "mutated".equals(control);
        runtime = readField(service, "runtime"); store = readField(service, "store");
        if (target.kind().equals("W05")) {
            recovery = new TopologyHandoffFailureScenario(service, level,
                    new ProductionRemediationPlan.Target(target.id(), "H02/baseline", Map.of("fault", "missing")), control);
            handoff = null; derived = null; finalResult = null;
        } else if (target.kind().equals("W07") && target.parameters().get("window").equals("corridor/final-validation")) {
            recovery = null;
            finalResult = new TopologyFinalResultScenario(service, level,
                    new ProductionRemediationPlan.Target(target.id(), "F04", Map.of("terminal", "closing")), control);
            derived = null; handoff = null;
        } else if (target.kind().equals("W07") && !target.parameters().get("window").startsWith("corridor/")) {
            recovery = null;
            derived = new TopologyDerivedLifecycleScenario(service, level,
                    new ProductionRemediationPlan.Target(target.id(), "L03", Map.of("window", target.parameters().get("window"), "change", "version")), control);
            handoff = null; finalResult = null;
        } else if (target.kind().equals("W06")) {
            recovery = null; derived = null; finalResult = null;
            String terminal = target.parameters().get("completion");
            handoff = new TopologyHandoffScenario(service, level, new ProductionRemediationPlan.Target(target.id(), "H04",
                    Map.of("path", "memory", "barrier", terminal.equals("coalesced") ? "B6" : "B7", "competition", "none",
                            "delivery", "old-load", "reloadTerminal", terminal)), control);
        } else if (List.of("W01", "W02", "W03", "W04", "W08", "W09").contains(target.kind())) {
            recovery = null;
            String barrier = target.kind().equals("W09") ? "B1"
                    : target.parameters().getOrDefault("barrier", target.parameters().getOrDefault("window", "B3"));
            if (target.kind().equals("W08")) barrier = target.parameters().get("window").equals("request") ? "B6" : "B7";
            if (barrier.equals("writing")) barrier = "B6";
            handoff = new TopologyHandoffScenario(service, level, new ProductionRemediationPlan.Target(target.id(), "H04",
                    Map.of("path", target.parameters().getOrDefault("path", "memory"), "barrier", barrier, "competition", "none", "delivery", "normal")), control);
            derived = null; finalResult = null;
        } else throw new IllegalArgumentException("Shutdown executor not installed: " + target.id());
        synchronized (TopologyShutdownScenario.class) {
            if (active != null) throw new IllegalStateException("overlapping shutdown controls"); active = this;
        }
    }
    public Set<Long> chunkPositions() { return recovery != null ? Set.of(recovery.chunkPosition().toLong())
            : finalResult != null ? finalResult.chunkPositions() : derived == null ? Set.of(handoff.chunkPosition().toLong()) : derived.chunkPositions(); }
    public boolean prepare() {
        if (recovery != null) {
            if (!recovery.prepareRecoveryShutdown(target.parameters().get("window"))) return false;
            evidence.put("controlledWindow", recovery.recoveryShutdownEvidence());
            if (!target.parameters().get("window").startsWith("queued")) {
                require(recoveryStages.size() == 2, "both synchronous recovery stages were observed on the server thread");
                evidence.put("staticEvidence", Map.of("source", "TopologyService.runOneRecovery -> scanSection -> writeFull; beginStopping requires the same server thread",
                        "reason", "scan and publication have no queue or yield between them and cannot overlap ServerStopping",
                        "observedStages", List.copyOf(recoveryStages)));
            }
        } else if (target.kind().equals("W08")) {
            if (!prepareFlush()) return false;
        } else if (target.kind().equals("W06")) {
            boolean ready = handoff.prepareReloadShutdown(target.parameters().get("completion"));
            armed = handoff.loadIdentity() != 0;
            if (!ready) return false;
            evidence.put("controlledWindow", handoff.shutdownEvidence());
        } else if (finalResult != null) {
            if (!finalResult.prepareShutdown()) return false;
            evidence.put("controlledWindow", finalResult.shutdownEvidence(false));
        } else if (derived != null) {
            if (!derived.prepareShutdown()) return false;
            evidence.put("controlledWindow", derived.shutdownEvidence());
        } else {
            if (!releaseWrite) {
                boolean ready = handoff.prepareShutdown(); armed = handoff.loadIdentity() != 0;
                if (!ready) return false;
            }
            if ("writing".equals(target.parameters().get("window"))) {
                if (!releaseWrite) { handoff.releaseShutdown(); releaseWrite = true; }
                if (!writing) return false;
            }
            evidence.put("controlledWindow", handoff.shutdownEvidence());
            Map<String, Object> ownership = TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions());
            require(((Number) ownership.get("holds")).longValue() > 0, "the paused handoff still owns its persistence responsibility");
            evidence.put("beforeStopOwnership", ownership);
        }
        armed = true; record("before-ServerStopping"); return true;
    }
    private boolean prepareFlush() {
        String window = target.parameters().get("window");
        if (flushStage == 0) {
            if (!handoff.prepareShutdown()) return false;
            if (!window.equals("request")) handoff.releaseShutdown();
            flushStage = 1;
        }
        if (flushStage == 1) {
            if (!window.equals("request") && !Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled"))) return false;
            synchronized (readField(store, "monitor")) {
                if (((Set<?>) readField(store, "queuedFlushes")).contains(level.dimension())) return false;
                flushProbe = TopologyFlushWindowProbe.watch(store, level.dimension(), window);
                TopologyService.onLevelSave(level);
            }
            ServerLevel unrelated = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            require(unrelated != null, "the independent save dimension exists");
            TopologyService.onLevelSave(unrelated);
            evidence.put("unrelatedSave", unrelated.dimension().location().toString()); flushStage = 2;
        }
        if (!flushProbe.reached()) return false;
        Map<String, Object> before = flushProbe.evidence(); evidence.put("flushBeforeStop", before);
        if (window.equals("completion-before-callback")) {
            require(((Number) before.get("completed")).intValue() == 1, "the real flush completed");
            evidence.put("staticEvidence", Map.of("source", "TopologyService.onLevelSave -> TopologyStore.requestSave -> runRequestedFlush",
                    "reason", "requestSave and runRequestedFlush return void and register no completion or server callback; the callback window does not exist",
                    "observedFlush", before));
        } else require(((Number) before.get("completed")).intValue() == 0, "unrelated saving cannot complete the paused target");
        evidence.put("controlledWindow", handoff.shutdownEvidence()); return true;
    }
    public void serverStopping() {
        stopObserved = true;
        record("after-ServerStopping");
        if (directStopped()) {
            require(!(boolean) readField(service, "stopping"), "the test bypasses only the first production stopping handler");
            evidence.put("stoppingHandlerBypassed", true); return;
        }
        require((boolean) readField(service, "stopping") && (boolean) readField(runtime, "stopRequested"), "the production handler stops new business before releasing controls");
        release();
    }
    private void release() {
        recoveryReleased = true;
        if (flushProbe != null) flushProbe.release();
        ioRelease.countDown();
        if (handoff != null) handoff.releaseShutdown();
        if (derived != null) derived.releaseShutdown();
        if (finalResult != null) finalResult.releaseShutdown();
    }
    public Map<String, Object> serverStopped() {
        stopped = true; record("after-ServerStopped");
        Map<String, Object> finalState = finalState();
        evidence.put("lifecycle", List.copyOf(lifecycle)); evidence.put("finalState", finalState);
        evidence.put("controlledWindow", recovery != null ? recovery.recoveryShutdownEvidence() : finalResult != null ? finalResult.shutdownEvidence(true)
                : derived == null ? handoff.shutdownEvidence() : derived.shutdownEvidence());
        evidence.put("writeFailures", writeFailures); evidence.put("acceptanceRejections", acceptanceRejections);
        evidence.put("controlRejections", controlRejections); evidence.put("cleanupFailures", cleanupFailures);
        require(stopObserved, "real ServerStopping precedes real ServerStopped");
        require(!ioTimedOut, "the actual I/O window was explicitly released before its deadline");
        verifyClosed(finalState);
        if (flushProbe != null) {
            Map<String, Object> state = flushProbe.evidence(); evidence.put("flushAfterStop", state);
            require(((Number) state.get("completed")).intValue() == 1 && Boolean.FALSE.equals(state.get("timedOut"))
                    && Boolean.FALSE.equals(state.get("inOriginalQueue")) && Boolean.FALSE.equals(state.get("current")),
                    "the original flush reaches its terminal and releases all queue and execution ownership");
        }
        if (recovery != null) {
            Map<?, ?> fault = (Map<?, ?>) recovery.recoveryShutdownEvidence().get("faultObservations");
            require(((Number) fault.get("scans")).intValue() == (target.parameters().get("window").startsWith("queued") ? 0 : 1),
                    "stopping never starts a queued recovery or repeats a completed recovery");
        }
        if ("reject".equals(target.parameters().get("submission"))) require(acceptanceRejections == 1, "the actual acceptance rejection was reached once");
        if ("io".equals(target.parameters().get("write"))) require(writeFailures == 1, "the actual write failed once without retrying");
        if ("failed".equals(target.parameters().get("completion"))) require(writeFailures == 1, "the old-load write failed exactly once");
        if ("queue-rejection".equals(target.parameters().get("fault"))) require(controlRejections == 1, "the real closing control submission was rejected once");
        if ("cleanup-exception".equals(target.parameters().get("fault"))) require(cleanupFailures == 1, "the real final region flush failed once and still closed its file");
        if (derived != null) {
            Map<String, Object> window = derived.shutdownEvidence();
            require(Boolean.TRUE.equals(window.get("cancelled")), "the stopped server request is cancelled");
            require(!Boolean.TRUE.equals(window.get("latePublished")), "late build output is not published after closing");
            Map<?, ?> old = (Map<?, ?>) window.get("oldReference");
            if (!old.isEmpty()) require(((Number) old.get("pins")).longValue() == 0, "closing releases the last old graph reference");
            Map<?, ?> executor = (Map<?, ?>) window.get("executor");
            if (!executor.isEmpty()) require("DONE".equals(executor.get("state")) && Boolean.FALSE.equals(executor.get("queued")),
                    "the selected executor task releases its queue and running ownership");
        }
        if (mutated) {
            var altered = new LinkedHashMap<>(finalState); altered.put("workersAlive", 1);
            boolean detected = false;
            try { verifyClosed(altered); } catch (AssertionError expected) { detected = true; }
            require(detected, "the shutdown validator rejects a surviving worker"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", evidence.containsKey("staticEvidence") ? "NOT_APPLICABLE" : "PASS");
        evidence.put("reached", !evidence.containsKey("staticEvidence")); evidence.put("cleaned", true);
        return Map.copyOf(evidence);
    }
    public Map<String, Object> failure(Throwable failure) {
        evidence.put("outcome", "FAIL"); evidence.put("reason", "SHUTDOWN_ASSERTION_OR_EXCEPTION");
        evidence.put("failure", failure.toString()); evidence.put("reached", armed); evidence.put("cleaned", false);
        evidence.put("lifecycle", List.copyOf(lifecycle)); return Map.copyOf(evidence);
    }
    private Map<String, Object> finalState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopping", readField(service, "stopping")); result.put("stopped", readField(service, "stopped"));
        for (String name : List.of("macroRequests", "loadedChunks", "loadedSections")) result.put(name, ((Map<?, ?>) readField(service, name)).size());
        result.put("callbacks", ((Collection<?>) readField(service, "persistenceCallbacks")).size());
        synchronized (readField(runtime, "runtimeLock")) {
            result.put("runtimeClosed", readField(runtime, "closed"));
            result.put("holds", ((Map<?, ?>) readField(runtime, "persistenceHolds")).size());
        }
        Object executor = readField(runtime, "taskExecutor");
        result.put("workersAlive", java.util.Arrays.stream((Thread[]) readField(executor, "workers")).filter(Thread::isAlive).count());
        synchronized (readField(executor, "monitor")) { result.put("buildPermit", readField(executor, "buildRunning")); }
        synchronized (readField(store, "monitor")) {
            result.put("storeAccepting", readField(store, "accepting"));
            for (String name : List.of("pending", "loads", "regions")) result.put("store/" + name, ((Map<?, ?>) readField(store, name)).size());
            result.put("receipts", ((Collection<?>) readField(store, "receipts")).size());
        }
        result.put("ioAlive", ((Thread) readField(store, "worker")).isAlive());
        result.put("activeReferences", workerMetrics(service).get("worker.activeReferences"));
        return Map.copyOf(result);
    }
    private static void verifyClosed(Map<String, Object> state) {
        for (String name : List.of("stopping", "stopped", "runtimeClosed")) require(Boolean.TRUE.equals(state.get(name)), "closing reaches " + name);
        for (String name : List.of("storeAccepting", "ioAlive", "buildPermit")) require(Boolean.FALSE.equals(state.get(name)), "closing releases " + name);
        for (var item : state.entrySet()) if (item.getValue() instanceof Number number) require(number.longValue() == 0, "closing leaves no owned resource: " + item.getKey());
    }
    private void record(String event) { lifecycle.add(Map.of("event", event, "nanoTime", System.nanoTime(), "thread", Thread.currentThread().getName())); }
    private boolean directStopped() { return "direct-stopped".equals(target.parameters().get("fault")); }
    static boolean bypassStopping(net.minecraft.server.MinecraftServer server) {
        var scenario = active;
        return scenario != null && scenario.armed && scenario.directStopped() && scenario.level.getServer() == server;
    }
    static void finalStopping(Object service) {
        var scenario = active;
        if (scenario != null && scenario.armed && scenario.service == service && scenario.directStopped()) {
            scenario.record("final-stop-supplements-beginStopping"); scenario.release();
        }
    }
    static void submittingControl(Object executor) {
        var scenario = active;
        if (scenario == null || !scenario.armed || scenario.stopped || readField(scenario.runtime, "taskExecutor") != executor
                || !"queue-rejection".equals(scenario.target.parameters().get("fault"))) return;
        synchronized (readField(scenario.runtime, "runtimeLock")) {
            if (!(boolean) readField(scenario.runtime, "stopRequested") || scenario.controlRejections != 0) return;
            scenario.controlRejections++;
        }
        throw new java.util.concurrent.RejectedExecutionException("W09 actual closing control submission rejected");
    }
    static boolean holdRecovery(Object owner) {
        var scenario = active;
        return scenario != null && scenario.service == owner && scenario.recovery != null && !scenario.recoveryReleased
                && scenario.target.parameters().get("window").startsWith("queued")
                && scenario.recovery.holdRecoveryForShutdown() && scenario.recovery.recoveryQueuedForShutdown();
    }
    static void recoveryStage(Object owner, Object section, String point) {
        var scenario = active;
        if (scenario == null || scenario.service != owner || scenario.recovery == null
                || !scenario.recovery.holdRecoveryForShutdown()
                || !scenario.recovery.shutdownSection().equals(((TopologyWorkerRuntime.ClusterKey) readField(section, "key")).section())) return;
        require(scenario.level.getServer().isSameThread(), "recovery and stopping use the same server thread");
        require(!(boolean) readField(owner, "stopping"), "the synchronous recovery stage precedes stopping");
        scenario.recoveryStages.add(Map.of("point", point, "thread", Thread.currentThread().getName(),
                "nanoTime", System.nanoTime(), "loadIdentity", readField(readField(section, "chunk"), "identity")));
    }
    static void cleaning(Object store) throws IOException {
        var scenario = active;
        if (scenario == null || !scenario.armed || scenario.store != store
                || !"cleanup-exception".equals(scenario.target.parameters().get("fault")) || scenario.cleanupFailures != 0) return;
        scenario.cleanupFailures++;
        throw new IOException("W09 actual final region flush failure");
    }
    static void accepting(Object owner, TopologyWorkerRuntime.FactDecision decision) {
        var scenario = active;
        if (scenario == null || !scenario.armed || scenario.stopped || owner != scenario.store || scenario.handoff == null
                || !scenario.handoff.section().equals(decision.key().section()) || scenario.handoff.loadIdentity() != decision.loadIdentity()) return;
        if ("reject".equals(scenario.target.parameters().get("submission"))) {
            scenario.acceptanceRejections++;
            throw new java.util.concurrent.RejectedExecutionException("W02 actual store submission rejected");
        }
    }
    static void writing(Object owner, Object chunkKey) throws IOException {
        var scenario = active;
        if (scenario == null || !scenario.armed || scenario.stopped || owner != scenario.store || scenario.handoff == null
                || !scenario.level.dimension().equals(readField(chunkKey, "dimension"))
                || !scenario.handoff.chunkPosition().equals(readField(chunkKey, "chunk"))) return;
        if ("writing".equals(scenario.target.parameters().get("window")) && !scenario.writing) {
            require(!Thread.holdsLock(readField(owner, "monitor")), "actual I/O pause does not hold the storage monitor");
            scenario.writing = true;
            try { if (!scenario.ioRelease.await(30, TimeUnit.SECONDS)) { scenario.ioTimedOut = true; throw new IOException("W04 I/O release timed out"); } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        }
        if ("io".equals(scenario.target.parameters().get("write"))
                || "failed".equals(scenario.target.parameters().get("completion")) && scenario.writeFailures == 0) {
            scenario.writeFailures++; throw new IOException("W actual write fault");
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        try { release(); }
        finally {
            try { if (handoff != null) handoff.close(); if (derived != null) derived.close(); if (finalResult != null) finalResult.close();
                if (recovery != null) { recovery.close(); if (!(boolean) readField(service, "stopping")) invoke(service, "runOneRecovery"); }
                if (flushProbe != null) flushProbe.close(); }
            finally { synchronized (TopologyShutdownScenario.class) { if (active == this) active = null; } }
        }
    }
}
