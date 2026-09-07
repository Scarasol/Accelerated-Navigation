package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** PRM controls attached to the existing terrain runner, outside each timed request. */
final class ProductionRemediationTerrain {
    enum Preparation { WAIT, READY, SKIP }
    private final TopologyService topology;
    private final TopologyObservationAccess observation;
    private final ProductionRemediationPlan.Options options;
    private final ProductionRemediationReport.Journal journal;
    private final ProductionRemediationMetrics metrics;
    private final Map<String, List<ProductionRemediationPlan.Target>> ordinary = new HashMap<>();
    private final Map<String, ProductionRemediationPlan.Target> pressure = new HashMap<>();
    private final Map<String, Map<String, TopologyValidationAccess.Stamp>> prepared = new HashMap<>();
    private final Set<ResourceKey<Level>> preparedDimensions = new HashSet<>();
    private TopologyValidationAccess.FactsLease factsLease;
    private TopologyValidationAccess.Snapshot before;
    private Map<String, Long> beforeMetrics;
    private TopologyValidationAccess.Capture capture;
    private String preparationId;
    private long preparationStarted;
    private TopologyService.MacroRequest preparationQuery;
    private int preparationQueries;
    private boolean coldCleared;
    private long startedGcCount, startedGcMillis;
    private Map<String, Long> observationCost = Map.of();

    ProductionRemediationTerrain(TopologyService topology, TopologyObservationAccess observation) {
        this.topology = topology; this.observation = observation;
        options = ProductionRemediationPlan.Options.configured();
        if (!options.usesFrozenWorld()) throw new IllegalArgumentException("Terrain controls require a frozen-world case");
        try { journal = new ProductionRemediationReport.Journal(Path.of(System.getProperty("acceleratedNavigation.validation.output")), options); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        metrics = options.scenario().equals("M") ? new ProductionRemediationMetrics(topology, journal, options) : null;
        List<ProductionRemediationPlan.Target> targets = ProductionRemediationPlan.targets(options);
        for (String route : ProductionRemediationPlan.routes()) {
            ordinary.put(route, targets.stream().filter(target -> target.kind().equals("query") && target.parameters().get("route").equals(route)).toList());
        }
        for (var target : targets) if (target.kind().equals("pressure")) {
            var p = target.parameters(); pressure.put(p.get("phase") + "/" + p.get("dimension") + "/" + p.get("index"), target);
        }
    }

    int count(String route) { return ordinary.getOrDefault(route, List.of()).size(); }
    String phase(String route, int index) { return target(route, index).parameters().get("phase"); }
    boolean pressureEnabled() { return !pressure.isEmpty(); }
    boolean skipPressure(String phase) {
        if (!"missed".equals(options.control())) return false;
        for (var target : pressure.values()) if (phase.equals(target.parameters().get("phase"))) {
            journal.sample(target, Map.of("outcome", "NOT_ENTERED", "reason", "FIXTURE_TARGET_SKIPPED"));
        }
        return true;
    }
    boolean selected(String route) { return count(route) != 0; }

    private ProductionRemediationPlan.Target target(String route, int index) { return ordinary.get(route).get(index); }

    Preparation prepare(String route, int index, ServerLevel level, BlockPos start, BlockPos goal, Set<Long> chunks) {
        var target = target(route, index);
        if ("missed".equals(options.control())) {
            journal.sample(target, Map.of("outcome", "NOT_ENTERED", "reason", "FIXTURE_TARGET_SKIPPED", "qualified", false));
            return Preparation.SKIP;
        }
        long now = System.nanoTime();
        if (!target.id().equals(preparationId)) {
            preparationId = target.id(); preparationStarted = now; preparationQueries = 0; coldCleared = false;
        }
        boolean initial = !preparedDimensions.contains(level.dimension());
        long timeout = initial ? 600_000_000_000L : 30_000_000_000L;
        if (now - preparationStarted >= timeout) {
            cleanupPreparation();
            journal.sample(target, Map.of("outcome", "NOT_ENTERED", "reason", "PREPARATION_FAILED", "qualified", false));
            return Preparation.SKIP;
        }
        String phase = target.parameters().get("phase");
        boolean cold = phase.equals("cold");
        if (initial || cold) {
            if (factsLease == null) factsLease = TopologyValidationAccess.factsLease(topology);
            var sections = TopologyValidationAccess.loadedSections(topology, level.dimension(), chunks);
            if (sections.isEmpty()) throw new IllegalStateException("Frozen chunk plan has no loaded sections");
            if (!TopologyValidationAccess.prepareFacts(topology, level.dimension(), factsLease, sections).isEmpty()) return Preparation.WAIT;
        }
        var snapshot = TopologyValidationAccess.snapshot(topology, level.dimension());
        if (!snapshot.idle()) return Preparation.WAIT;
        if (initial) {
            preparedDimensions.add(level.dimension());
            journal.controlEvent(Map.of("event", "facts-ready", "dimension", level.dimension().location().toString(),
                    "sections", factsLease.sections(), "heldFactsBytes", factsLease.bytes()));
            if (!cold) releaseFacts();
        }
        if (preparationQuery != null) {
            if (!preparationQuery.future().isDone()) return Preparation.WAIT;
            var used = finishCapture();
            prepared.computeIfAbsent(route, ignored -> new LinkedHashMap<>()).putAll(used);
            journal.controlEvent(Map.of("event", "preparation-query-terminal", "for", target.id(), "ordinal", preparationQueries,
                    "progress", preparationQuery.progress(), "request", TopologyValidationAccess.requestIdentity(preparationQuery), "used", used));
            TopologyValidationAccess.forgetRequest(preparationQuery);
            preparationQuery = null;
            snapshot = TopologyValidationAccess.snapshot(topology, level.dimension());
        }
        ((TopologyFormalControlAccess) (Object) topology).acceleratedNavigation$clearCompletedCorridors();
        if (cold && !coldCleared) {
            if (!TopologyValidationAccess.clearDerived(topology, level.dimension())) return Preparation.WAIT;
            coldCleared = true;
            snapshot = TopologyValidationAccess.snapshot(topology, level.dimension());
        }
        if (phase.equals("hot")) {
            Map<String, TopologyValidationAccess.Stamp> expected = prepared.getOrDefault(route, Map.of());
            if (expected.isEmpty() || !snapshot.objects().keySet().containsAll(expected.keySet())) {
                if (preparationQueries == 0) {
                    capture = TopologyValidationAccess.beginCapture();
                    preparationQueries++;
                    try {
                        preparationQuery = topology.requestMacroQuery(level, UUID.nameUUIDFromBytes((target.id() + "/preparation")
                                .getBytes(StandardCharsets.UTF_8)), start, goal, BaseClusterTopology.Channel.GROUND,
                                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
                    } catch (RuntimeException failure) { finishCapture(); throw failure; }
                    journal.controlEvent(Map.of("event", "preparation-query-submitted", "for", target.id(), "ordinal", preparationQueries));
                }
                return Preparation.WAIT;
            }
        }
        if (metrics != null && !metrics.prepare(level, start, goal)) return Preparation.WAIT;
        before = TopologyValidationAccess.snapshot(topology, level.dimension());
        beforeMetrics = observation.acceleratedNavigation$snapshotMetrics();
        capture = TopologyValidationAccess.beginCapture();
        startedGcCount = gc(false); startedGcMillis = gc(true);
        return Preparation.READY;
    }

    void sample(String route, int index, ServerLevel level, BlockPos start, BlockPos goal, UUID owner,
                TopologyService.MacroRequest request, String outcome, Throwable failure,
                long started, long ended, long cpu, int startTick, int endTick) {
        var target = target(route, index);
        var used = finishCapture();
        Map<String, Long> after = observation.acceleratedNavigation$snapshotMetrics();
        String phase = target.parameters().get("phase");
        List<String> issues = new java.util.ArrayList<>();
        if (phase.equals("hot")) {
            for (var entry : used.entrySet()) if (!entry.getValue().equals(before.objects().get(entry.getKey()))) issues.add("NOT_READY_BEFORE:" + entry.getKey());
            if (used.isEmpty()) issues.add("NO_GRAPH_ACQUISITION");
            for (String counter : List.of("worker.tasks.completed.builds", "worker.tasks.completed.prewarms")) {
                if (delta(beforeMetrics, after, counter) != 0) issues.add(counter);
            }
        }
        if (phase.equals("hot") || phase.equals("cold")) {
            for (String counter : List.of("persistence.readRequests", "service.recoveredSections", "service.failedRecoveries")) {
                if (delta(beforeMetrics, after, counter) != 0) issues.add(counter);
            }
            if (request != null && request.completedFromCache()) issues.add("COMPLETED_CACHE_HIT");
            var current = TopologyValidationAccess.snapshot(topology, level.dimension());
            for (var entry : used.entrySet()) if (entry.getKey().startsWith("facts/") && !entry.getValue().equals(current.objects().get(entry.getKey()))) issues.add("FACTS_CHANGED:" + entry.getKey());
        } else prepared.computeIfAbsent(route, ignored -> new LinkedHashMap<>()).putAll(used);
        Map<String, Object> row = requestRow(level, start, goal, owner, request, outcome, failure, started, ended, cpu, startTick, endTick);
        row.put("qualified", issues.isEmpty()); row.put("qualificationIssues", issues);
        row.put("gcCollections", Math.max(0, gc(false) - startedGcCount));
        row.put("gcMillis", Math.max(0, gc(true) - startedGcMillis));
        if (request != null && (options.scenario().equals("M") || options.scenario().equals("probe"))) {
            var progress = request.progress();
            Map<String, Object> work = new LinkedHashMap<>();
            work.put("metrics", progress.metrics()); work.put("hierarchical", progress.hierarchical());
            work.put("witnessSegments", progress.witnessSegments());
            MacroSearch.Corridor corridor = outcome.equals("SUCCESS") ? request.future().getNow(null) : null;
            work.put("corridorCost", corridor == null ? null : corridor.cost());
            work.put("corridorAnchors", corridor == null ? List.of() : corridor.endpoints().stream().map(endpoint ->
                    List.of(endpoint.anchor().getX(), endpoint.anchor().getY(), endpoint.anchor().getZ())).toList());
            row.put("pairedWork", work);
        }
        row.put("controlReference", target.id());
        row.put("observerCost", observationCost);
        Map<String, TopologyValidationAccess.Stamp> relevantBefore = new LinkedHashMap<>();
        for (String key : used.keySet()) if (before.objects().containsKey(key)) relevantBefore.put(key, before.objects().get(key));
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("event", "sample-state"); control.put("id", target.id()); control.put("before", relevantBefore); control.put("used", used);
        control.put("counterBefore", beforeMetrics); control.put("counterAfter", after); control.put("coldDerivedEmpty", coldCleared);
        control.put("testHeldFactsBytes", factsLease == null ? 0L : factsLease.bytes());
        if ("mutated".equals(options.control())) row.putAll(ProductionRemediationReport.rejectSampleMutation(target, row, control));
        journal.controlEvent(control);
        journal.sample(target, row);
        if (request != null) TopologyValidationAccess.forgetRequest(request);
        releaseFacts(); preparationId = null;
    }

    void pressure(String stage, boolean nether, int index, ServerLevel level, BlockPos start, BlockPos goal, UUID owner,
                  TopologyService.MacroRequest request, String outcome, Throwable failure,
                  long started, long ended, int startTick, int endTick) {
        var target = pressure.get(stage + "/" + (nether ? "nether" : "overworld") + "/" + index);
        if (target == null) throw new IllegalStateException("Unknown pressure position");
        var row = requestRow(level, start, goal, owner, request, outcome, failure, started, ended, -1, startTick, endTick);
        if ("mutated".equals(options.control())) row.putAll(ProductionRemediationReport.rejectSampleMutation(target, row, null));
        journal.sample(target, row);
        if (request != null) TopologyValidationAccess.forgetRequest(request);
    }

    private static Map<String, Object> requestRow(ServerLevel level, BlockPos start, BlockPos goal, UUID owner,
                                                 TopologyService.MacroRequest request, String outcome, Throwable failure,
                                                 long started, long ended, long cpu, int startTick, int endTick) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owner", owner.toString()); row.put("dimension", level.dimension().location().toString());
        row.put("start", List.of(start.getX(), start.getY(), start.getZ())); row.put("goal", List.of(goal.getX(), goal.getY(), goal.getZ()));
        row.put("channel", "GROUND"); row.put("profile", "DEFAULT_GROUND"); row.put("priority", "ACTIVE");
        row.put("outcome", outcome); row.put("exception", failure == null ? null : TerrainTestSupport.failureSummary(failure));
        row.put("startNanos", started); row.put("endNanos", ended); row.put("wallNanos", ended - started);
        row.put("processCpuNanos", cpu < 0 ? null : cpu); row.put("startTick", startTick); row.put("endTick", endTick); row.put("completionTicks", endTick - startTick);
        row.put("failure", request == null ? null : request.progress().failure().name());
        row.put("blockedSection", request == null ? null : request.progress().blockedSection());
        row.put("progress", request == null ? null : request.progress());
        row.putAll(request == null ? Map.of("attempt", 0L, "physicalSearchIds", List.of()) : TopologyValidationAccess.requestIdentity(request));
        return row;
    }

    void finish(Map<String, Object> result, String missingReason) {
        cleanupPreparation();
        if (metrics != null) { result.put("metricsControl", metrics.finish()); metrics.close(); }
        try { journal.finish(result, "missed".equals(options.control()) ? "FIXTURE_TARGET_SKIPPED" : missingReason); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private Map<String, TopologyValidationAccess.Stamp> finishCapture() {
        if (capture == null) return Map.of();
        TopologyValidationAccess.endCapture(capture);
        observationCost = capture.cost();
        var used = capture.used(); capture = null; return used;
    }

    private void cleanupPreparation() {
        if (preparationQuery != null) { preparationQuery.cancel(); TopologyValidationAccess.forgetRequest(preparationQuery); preparationQuery = null; }
        finishCapture(); releaseFacts();
    }

    private void releaseFacts() { if (factsLease != null) { factsLease.close(); factsLease = null; } }
    private static long delta(Map<String, Long> before, Map<String, Long> after, String key) {
        if (!before.containsKey(key) || !after.containsKey(key)) throw new IllegalStateException("Missing observation counter " + key);
        return after.get(key) - before.get(key);
    }
    private static long gc(boolean time) {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean ->
                Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount())).sum();
    }
}
