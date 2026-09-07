package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.TopologyMetricsControl;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Preparatory M workloads and direct production snapshot timing, outside query timing. */
final class ProductionRemediationMetrics implements AutoCloseable {
    private final TopologyService service;
    private final ProductionRemediationReport.Journal journal;
    private final ProductionRemediationPlan.Options options;
    private final MethodHandle snapshot;
    private final List<TopologyService.MacroRequest> pending = new ArrayList<>();
    private final List<Map<String, Object>> preparation = new ArrayList<>();
    private final Map<String, Object> snapshotEvidence = new LinkedHashMap<>();
    private Object lastSnapshot;
    private int stage, profileIndex;
    private boolean finished;

    ProductionRemediationMetrics(TopologyService service, ProductionRemediationReport.Journal journal, ProductionRemediationPlan.Options options) {
        this.service = service; this.journal = journal; this.options = options;
        try {
            var method = TopologyService.class.getDeclaredMethod("metrics"); method.setAccessible(true);
            snapshot = MethodHandles.lookup().unreflect(method).bindTo(service).asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Production snapshot entry unavailable", failure); }
    }

    boolean prepare(ServerLevel level, BlockPos start, BlockPos goal) {
        if (finished) return true;
        if (stage == 0) {
            if (!TopologyValidationAccess.snapshot(service, level.dimension()).idle()) return false;
            measure("idle", level); stage = 1;
            for (int index = 0; index < 1024; index++) pending.add(request(level, start, goal, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND));
            if (((Map<?, ?>) TopologyTestBridge.readField(service, "macroRequests")).size() != 1024
                    || pending.stream().anyMatch(request -> request.future().isDone())) throw new IllegalStateException("1024 live server requests were not reached");
            measure("1024", level);
            pending.forEach(TopologyService.MacroRequest::cancel);
            return false;
        }
        if (!pending.isEmpty()) {
            if (pending.stream().anyMatch(request -> !request.future().isDone())) return false;
            for (var request : pending) {
                preparation.add(Map.of("cancelled", request.future().isCancelled(), "progress", request.progress(),
                        "request", TopologyValidationAccess.requestIdentity(request)));
                TopologyValidationAccess.forgetRequest(request);
            }
            pending.clear();
        }
        if (!TopologyValidationAccess.snapshot(service, level.dimension()).idle()) return false;
        if (profileIndex < 16) {
            int value = profileIndex++;
            var profile = new BaseClusterTopology.TraversalProfile((value & 1) == 0 ? .6F : 1.4F,
                    (value & 2) == 0 ? .9F : 1.95F, (value & 4) == 0 ? 0 : 1, 3, (value & 8) == 0 ? 0 : 4, false);
            ((TopologyFormalControlAccess) (Object) service).acceleratedNavigation$clearCompletedCorridors();
            pending.add(request(level, start, goal, profile)); return false;
        }
        var state = TopologyValidationAccess.snapshot(service, level.dimension());
        long derived = state.objects().keySet().stream().filter(key -> !key.startsWith("facts/")).count();
        if (derived == 0) throw new IllegalStateException("Cache workload produced no retained derived objects");
        measure("cache-pressure", level);
        journal.controlEvent(Map.of("event", "metrics-preparation", "requests", preparation, "geometryRequests", 16,
                "retainedDerivedObjects", derived));
        finished = true; return true;
    }

    private TopologyService.MacroRequest request(ServerLevel level, BlockPos start, BlockPos goal, BaseClusterTopology.TraversalProfile profile) {
        return service.requestMacroQuery(level, UUID.randomUUID(), start, goal, BaseClusterTopology.Channel.GROUND,
                profile, NavigationScheduler.Priority.ACTIVE);
    }

    private void measure(String scale, ServerLevel level) {
        if (!options.variant().equals("on")) return;
        var target = ProductionRemediationPlan.targets(options).stream().filter(item -> item.kind().equals("M/snapshot")
                && scale.equals(item.parameters().get("scale"))).findFirst().orElseThrow();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scale", scale); evidence.put("state", TopologyValidationAccess.snapshot(service, level.dimension()));
        List<Long> elapsed = new ArrayList<>(10000);
        try {
            for (int index = 0; index < 1000; index++) lastSnapshot = (Object) snapshot.invokeExact();
            for (int index = 0; index < 10000; index++) {
                long start = System.nanoTime();
                Object value = (Object) snapshot.invokeExact();
                long end = System.nanoTime(); lastSnapshot = value;
                elapsed.add(end - start);
            }
        } catch (Throwable failure) { throw new IllegalStateException("Production snapshot measurement failed", failure); }
        evidence.put("snapshotNanos", elapsed); evidence.put("reached", true);
        evidence.put("productionState", lastSnapshot);
        if ("mutated".equals(options.control())) {
            var altered = ProductionRemediationReport.JSON.toJsonTree(evidence).getAsJsonObject();
            ProductionRemediationReport.snapshotTimes(altered, scale);
            altered.getAsJsonArray("snapshotNanos").remove(9999);
            boolean rejected = false;
            try { ProductionRemediationReport.snapshotTimes(altered, scale); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Snapshot reader accepted a missing observation");
            evidence.put("mutationDetected", true); evidence.put("mutatedRelationship", "snapshot-count");
        }
        evidence.put("outcome", "PASS");
        snapshotEvidence.put(scale, Map.of("target", target, "evidence", evidence));
    }

    Map<String, Object> finish() {
        boolean clean = pending.isEmpty() && finished;
        for (Object item : snapshotEvidence.values()) {
            @SuppressWarnings("unchecked") var entry = (Map<String, Object>) item;
            @SuppressWarnings("unchecked") var evidence = (Map<String, Object>) entry.get("evidence");
            evidence.put("cleaned", clean);
            journal.control((ProductionRemediationPlan.Target) entry.get("target"), evidence);
        }
        snapshotEvidence.clear();
        return Map.of("prepared", finished, "cleaned", clean, "suppressedWrites", TopologyMetricsControl.appliedWrites(),
                "statisticsEnabled", options.variant().equals("on"));
    }
    @Override public void close() {
        for (var request : pending) { request.cancel(); TopologyValidationAccess.forgetRequest(request); }
        pending.clear(); lastSnapshot = null;
    }
}
