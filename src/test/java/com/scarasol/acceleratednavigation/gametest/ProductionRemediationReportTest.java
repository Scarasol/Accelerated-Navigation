package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRemediationReportTest {
    @TempDir Path temporary;

    @Test void fixtureRepairSelectionKeepsOnlyUnverifiedTargets() {
        var remaining = new ProductionRemediationPlan.Options("fixture", "F", "R1", "PRMQ01", null, "normal", null, "qf-remaining");
        var targets = ProductionRemediationPlan.targets(remaining);
        assertEquals(24, targets.size());
        assertEquals("F02/f=actual/notification=P,R/placement=R,P/repeat=twice", targets.get(0).id());
        var preparation = ProductionRemediationPlan.targets(new ProductionRemediationPlan.Options("fixture", "F", "R1", "PRMQ01", null, "normal", null, "qf-preparation"));
        assertEquals(List.of(targets.get(0)), preparation);
        assertTrue(targets.stream().noneMatch(t -> List.of("F01/direct/goal=P/order=goal-first/start=R",
                "F01/alternate/reason=R/validCandidates=0", "F01/alternate/reason=R/validCandidates=1").contains(t.id())));
        var parent = new ProductionRemediationPlan.Options("fixture", "L", "R1", "PRMQ01", null, "normal", null, "ql-parent-replaced");
        assertEquals(List.of("L03/change=version/window=parent-boundary/replaced"),
                ProductionRemediationPlan.targets(parent).stream().map(ProductionRemediationPlan.Target::id).toList());
        assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationPlan.Options("fixture", "H", "R1", "PRMQ01", null, "normal", null, "qf-remaining"));
        var aborted = ProductionRemediationPlan.targets(new ProductionRemediationPlan.Options("fixture", "F", "R1", "PRMQ01", null, "normal", null, "qf-exception-cleanup"));
        assertEquals(1, aborted.size());
        assertEquals("harness-exception", aborted.get(0).parameters().get("qualification"));
    }

    @Test void familyAggregationDoesNotDemandUnrelatedAcceptedControls() throws Exception {
        var aggregate = ProductionRemediationReport.class.getDeclaredMethod("aggregate", Path.class, ProductionRemediationPlan.Options.class);
        aggregate.setAccessible(true);
        Path batch = temporary.resolve("build/reports/production-remediation/PRM01");
        Files.createDirectories(batch);
        aggregate.invoke(null, temporary, options("F", "R1", null));
        var report = ProductionRemediationReport.readObject(batch.resolve("aggregate-F.json"));
        assertEquals("F", report.get("scope").getAsString());
        assertEquals(List.of("R1/F"), ProductionRemediationReport.JSON.fromJson(report.get("missing"), List.class));
        assertFalse(report.has("performance"));
        assertFalse(report.has("metricsComparison"));
        assertFalse(Files.exists(batch.resolve("aggregate.json")));
    }

    @Test void shutdownAssertionRejectsAStillOwnedResourceWithoutAnotherServer() throws Exception {
        var verify = Class.forName("com.scarasol.acceleratednavigation.topology.TopologyShutdownScenario")
                .getDeclaredMethod("verifyClosed", Map.class);
        verify.setAccessible(true);
        Map<String, Object> state = new LinkedHashMap<>(Map.of("stopping", true, "stopped", true, "runtimeClosed", true,
                "storeAccepting", false, "ioAlive", false, "buildPermit", false, "workersAlive", 0, "receipts", 0));
        verify.invoke(null, state);
        state.put("workersAlive", 1);
        var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> verify.invoke(null, state));
        assertInstanceOf(AssertionError.class, rejected.getCause());
        state.put("workersAlive", 0); state.put("receipts", 1);
        assertInstanceOf(AssertionError.class,
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> verify.invoke(null, state)).getCause());
    }

    @Test void mutationFailurePreservesExecutedTerminalAndHarnessReason() throws Exception {
        var options = new ProductionRemediationPlan.Options("fixture", "terrain", "R1", "PRMQ01", null, "mutated", null, "qn-terminal");
        var identity = identity(options);
        ProductionRemediationReport.writeNew(temporary.resolve("identity.json"), identity, 1 << 20);
        var target = ProductionRemediationPlan.targets(options).get(0);
        try (var journal = new ProductionRemediationReport.Journal(temporary, options)) {
            Map<String, Object> row = new LinkedHashMap<>(Map.of("outcome", "BUSINESS_FAILURE", "failure", "STALE_WORLD"));
            assertThrows(IllegalStateException.class, () -> journal.checkedSample(target, row, null, true));
            Map<String, Object> result = new LinkedHashMap<>(compact());
            result.put("state", "HARNESS_FAILED"); result.put("failureStage", "sample"); result.put("failureReason", "mutation precondition");
            journal.finish(result, "STOPPED_EARLY");
        }
        var first = com.google.gson.JsonParser.parseString(Files.readAllLines(temporary.resolve("samples.jsonl")).get(0)).getAsJsonObject();
        assertEquals("BUSINESS_FAILURE", first.get("outcome").getAsString());
        assertEquals("STALE_WORLD", first.get("failure").getAsString());
        assertTrue(first.has("fixtureError"));
        assertTrue(((List<?>) ProductionRemediationReport.assess(temporary, identity).get("failures")).contains("Harness or shutdown failure recorded"));
    }

    @Test void fixedPositionsAndColdAllocationMatchAllThreeRounds() {
        Map<String, Integer> counts = new HashMap<>(), coldRoutes = new HashMap<>();
        for (int round = 1; round <= 3; round++) {
            var plan = ProductionRemediationPlan.targets(options("terrain", "R" + round, null));
            assertEquals(plan.size(), plan.stream().map(ProductionRemediationPlan.Target::id).distinct().count());
            for (var target : plan) {
                counts.merge(target.parameters().get("phase"), 1, Integer::sum);
                if (target.parameters().get("phase").equals("cold")) coldRoutes.merge(target.parameters().get("route"), 1, Integer::sum);
            }
        }
        assertEquals(12000, counts.get("hot"));
        assertEquals(200, counts.get("cold"));
        assertEquals(720, counts.get("first_observed") + counts.get("warmup"));
        assertEquals(22136, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(40, coldRoutes.size());
        assertTrue(coldRoutes.values().stream().allMatch(count -> count == 5));
        for (String phase : ProductionRemediationPlan.STAGES) assertEquals(3072, counts.get(phase));
    }

    @Test void shutdownVariantsAndDiagnosticPlansAreFiniteAndDistinct() {
        List<Integer> counts = new ArrayList<>();
        for (int row = 1; row <= 9; row++) {
            String run = "W0" + row;
            var expected = ProductionRemediationPlan.shutdownPlan(run);
            counts.add(expected.size());
            for (int variant = 1; variant <= expected.size(); variant++) {
                var actual = ProductionRemediationPlan.targets(options("W", run, Integer.toString(variant)));
                assertEquals(List.of(expected.get(variant - 1)), actual);
            }
        }
        assertEquals(List.of(2, 2, 2, 3, 3, 3, 7, 4, 3), counts);
        assertEquals(29, counts.stream().mapToInt(Integer::intValue).sum());
        var diagnostic = new ProductionRemediationPlan.Options("diagnostic", "causality", "R1", "PRMD01", null, null);
        assertEquals(3080, ProductionRemediationPlan.targets(diagnostic).size());
        for (String scenario : List.of("G", "H", "F", "L")) {
            var plan = ProductionRemediationPlan.targets(options(scenario, "R1", null));
            assertFalse(plan.isEmpty());
            assertEquals(plan.size(), plan.stream().map(ProductionRemediationPlan.Target::id).distinct().count());
        }
        var h = ProductionRemediationPlan.targets(options("H", "R1", null));
        assertEquals(10, h.stream().filter(target -> target.kind().equals("H04")).count());
        var g = ProductionRemediationPlan.targets(options("G", "R1", null));
        assertEquals(32, g.stream().filter(target -> target.kind().equals("G02")).count());
    }

    @Test void unsupportedAndDestructiveParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> options("W", "W07", "16"));
        assertThrows(IllegalArgumentException.class, () -> options("terrain", "R1", "on"));
        assertThrows(IllegalArgumentException.class, () -> options("M", "R1", null));
        assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationPlan.Options("formal", "G", "R1", "PRM01", null, "mutated"));
        assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationPlan.Options("formal", "G", "R1", "../PRM01", null, null));
        assertThrows(IllegalArgumentException.class, () -> ProductionRemediationPlan.Options.from(Map.of()));
    }

    @Test void nearestRankUsesUnroundedValuesAndEmptyIsNotZero() {
        var result = ProductionRemediationReport.distribution(List.of(.001, 1.0000001, 2.5, 3.25, 9.1));
        assertEquals(2.5, result.get("p50"));
        assertEquals(9.1, result.get("p95"));
        assertEquals(9.1, result.get("p99"));
        var empty = ProductionRemediationReport.distribution(List.of());
        assertEquals("NOT_APPLICABLE", empty.get("status"));
        assertFalse(empty.containsKey("p95"));
        assertThrows(IllegalArgumentException.class, () -> ProductionRemediationReport.distribution(List.of(Double.NaN)));
        assertEquals(1.32726, ProductionRemediationReport.hotLimit("surface_64_04", 1.2066), 1e-10);
        assertEquals(5, ProductionRemediationReport.hotLimit("surface_512_01", null));
    }

    @Test void archivedBaselineHasExactly35ValidReferencesAndCannotFallback() throws Exception {
        Path path = Path.of(ProductionRemediationPlan.BASELINE);
        ProductionRemediationReport.requireHash(path, ProductionRemediationPlan.BASELINE_HASH);
        JsonObject baseline = ProductionRemediationReport.readObject(path);
        var references = ProductionRemediationReport.validateBaseline(baseline);
        assertEquals(35, references.size());
        assertEquals(1.2066, references.get("surface_64_04"));
        assertFalse(references.containsKey("nether_96_06"));
        JsonObject wall = baseline.getAsJsonArray("routes").get(0).getAsJsonObject().getAsJsonObject("summary")
                .getAsJsonObject("phases").getAsJsonObject("hot").getAsJsonObject("successfulPerformance").getAsJsonObject("wallMillis");
        wall.remove("p95");
        assertThrows(java.io.IOException.class, () -> ProductionRemediationReport.validateBaseline(baseline));
        assertThrows(java.io.IOException.class, () -> ProductionRemediationReport.requireHash(path, "00"));
    }

    @Test void generationPairsPreserveRatiosAndRejectUnequalWorkOrZeroDenominators() {
        Map<String, Object> instances = new LinkedHashMap<>();
        for (int round = 1; round <= 3; round++) for (String variant : List.of("on", "off")) {
            boolean on = variant.equals("on");
            int time = (round == 2 ? !on : on) ? 1 : 2;
            instances.put("R" + round + "/generation-cost/" + variant, Map.of("status", "PASS",
                    "preparedAt", "2026-09-06T00:0" + time + ":00Z", "identitySHA256", "identity-" + round + variant,
                    "generation", Map.of("cost", Map.of("processCpuNanos", on ? 110 : 100, "wallNanos", on ? 190 : 200),
                            "workload", Map.of("chunk", Map.of("writes", 100)))));
        }
        List<String> missing = new ArrayList<>();
        var result = ProductionRemediationReport.compareGeneration(instances, missing);
        assertTrue(missing.isEmpty());
        assertEquals("MEASURED_NO_THRESHOLD", result.get("status"));
        Map<?, ?> cpu = (Map<?, ?>) ((Map<?, ?>) result.get("ratios")).get("processCpuNanos");
        assertEquals(.1, cpu.get("p50"));
        assertEquals(-.05, ((Map<?, ?>) ((Map<?, ?>) result.get("ratios")).get("wallNanos")).get("p50"));
        JsonObject changed = ProductionRemediationReport.JSON.toJsonTree(instances.get("R1/generation-cost/off")).getAsJsonObject();
        changed.getAsJsonObject("generation").getAsJsonObject("cost").addProperty("processCpuNanos", 0);
        instances.put("R1/generation-cost/off", changed);
        assertEquals("INCOMPLETE", ProductionRemediationReport.compareGeneration(instances, missing).get("status"));
        assertTrue(missing.stream().anyMatch(issue -> issue.contains("zero denominator")));
        changed.getAsJsonObject("generation").getAsJsonObject("workload").addProperty("extraChunk", 1);
        missing.clear();
        ProductionRemediationReport.compareGeneration(instances, missing);
        assertTrue(missing.stream().anyMatch(issue -> issue.contains("work differs")));
    }

    @Test void pairedQueriesEnforceCpuAndLatencyLimitsAndRetainIncompleteRounds() {
        Map<String, Object> instances = new LinkedHashMap<>();
        for (int round = 1; round <= 3; round++) for (String variant : List.of("on", "off")) {
            boolean on = variant.equals("on");
            Map<String, Object> routes = new LinkedHashMap<>();
            for (int route = 1; route <= 4; route++) routes.put("surface_8_0" + route,
                    Map.of("count", 1000, "wallMillis", Map.of("p95", on ? .22 : .2), "processCpuNanos", on ? 10200 : 10000,
                            "workAndResultSHA256", "same-work-" + route));
            instances.put("R" + round + "/M/" + variant, Map.of("preparedAt", "2026-09-06T00:0" + ((round == 2 ? !on : on) ? 1 : 2) + ":00Z",
                    "identitySHA256", variant + round, "paired", Map.of("status", "AVAILABLE", "routes", routes)));
        }
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        assertEquals("MEASURED", ProductionRemediationReport.compareQueries(instances, "M", missing, failures).get("status"));
        assertTrue(missing.isEmpty());
        assertTrue(failures.isEmpty(), "retired M limits must not create current failures");
        JsonObject changed = ProductionRemediationReport.JSON.toJsonTree(instances.get("R2/M/off")).getAsJsonObject();
        changed.getAsJsonObject("paired").getAsJsonObject("routes").getAsJsonObject("surface_8_04").addProperty("workAndResultSHA256", "different");
        instances.put("R2/M/off", changed); missing.clear(); failures.clear();
        var incomplete = ProductionRemediationReport.compareQueries(instances, "M", missing, failures);
        assertEquals("INCOMPLETE", incomplete.get("status"));
        assertTrue(missing.stream().anyMatch(value -> value.contains("work or result differs")));
        assertEquals(2, ((Map<?, ?>) incomplete.get("cpuIncrement")).get("count"));
    }

    @Test void hostCollectionCanCompleteWithTheFullProcessInventory() throws Exception {
        var collector = ProductionRemediationReport.class.getDeclaredMethod("hostEnvironment");
        collector.setAccessible(true);
        JsonObject host = (JsonObject) collector.invoke(null);
        assertFalse(host.get("processor").getAsString().isBlank());
        assertFalse(host.getAsJsonArray("processes").isEmpty());
    }

    @Test void missingControlAndObservedFailureAreBothPreserved() throws Exception {
        var options = options("H", "R1", null);
        JsonObject identity = identity(options);
        ProductionRemediationReport.writeNew(temporary.resolve("identity.json"), identity, 1 << 20);
        var targets = ProductionRemediationPlan.targets(options);
        try (var journal = new ProductionRemediationReport.Journal(temporary, options)) {
            journal.control(targets.get(0), Map.of("outcome", "FAIL", "reached", true, "cleaned", true));
            journal.control(targets.get(1), Map.of("outcome", "NOT_ENTERED", "originalOutcome", "FAIL",
                    "reason", "CLEANUP_NOT_SETTLED", "reached", true, "cleaned", false));
            assertThrows(IllegalStateException.class, () -> journal.control(targets.get(0), Map.of("outcome", "PASS")));
            journal.finish(compact(), "STOPPED_EARLY");
        }
        var assessment = ProductionRemediationReport.assess(temporary, identity);
        assertEquals("INCOMPLETE", assessment.get("status"));
        assertEquals(targets.size(), assessment.get("terminals"));
        assertEquals(2, ((List<?>) assessment.get("failures")).size());
        assertFalse(((List<?>) assessment.get("missing")).isEmpty());
    }

    @Test void passRequiresActualReachAndCleanupAndUntamperedRawReference() throws Exception {
        var options = options("W", "W01", "1");
        JsonObject identity = identity(options);
        ProductionRemediationReport.writeNew(temporary.resolve("identity.json"), identity, 1 << 20);
        try (var journal = new ProductionRemediationReport.Journal(temporary, options)) {
            journal.control(ProductionRemediationPlan.targets(options).get(0), Map.of("outcome", "PASS", "reached", false, "cleaned", true));
            journal.finish(compact(), "unused");
        }
        var assessment = ProductionRemediationReport.assess(temporary, identity);
        assertEquals("INCOMPLETE", assessment.get("status"));
        appendOrInsertBrokenRecord(temporary, -1);
        var damaged = ProductionRemediationReport.assess(temporary, identity);
        assertEquals("INCOMPLETE", damaged.get("status"));
        assertTrue(((List<?>) damaged.get("missing")).stream().anyMatch(value -> value.toString().contains("Invalid raw record")));
    }

    @Test void boundedStreamMarksTruncationWithoutWritingPartialJson() throws Exception {
        var path = temporary.resolve("bounded.jsonl");
        var stream = new ProductionRemediationReport.Stream(path, 64);
        stream.append(Map.of("event", "small"));
        stream.append(Map.of("event", "x".repeat(100)));
        stream.close();
        var reference = stream.reference();
        assertEquals(true, reference.get("truncated"));
        assertEquals(1L, reference.get("records"));
        assertTrue((long) reference.get("appendNanos") > 0);
        List<JsonObject> rows = new ArrayList<>();
        ProductionRemediationReport.readLines(path, rows::add);
        assertEquals(1, rows.size());
        assertTrue(Files.size(path) <= 64);
    }

    @Test void timingMustRecomputeAndFailedSamplesMustKeepTheirProductionReason() {
        JsonObject sample = new JsonObject();
        sample.addProperty("id", "query/fixture"); sample.addProperty("outcome", "BUSINESS_FAILURE");
        sample.addProperty("owner", "test-owner"); sample.addProperty("dimension", "minecraft:overworld");
        sample.addProperty("channel", "GROUND"); sample.addProperty("profile", "DEFAULT_GROUND"); sample.addProperty("priority", "ACTIVE");
        sample.add("start", ProductionRemediationReport.JSON.toJsonTree(List.of(0, 1, 0)));
        sample.add("goal", ProductionRemediationReport.JSON.toJsonTree(List.of(8, 1, 0)));
        sample.addProperty("startNanos", 100); sample.addProperty("endNanos", 300); sample.addProperty("wallNanos", 200);
        sample.addProperty("startTick", 12); sample.addProperty("endTick", 14); sample.addProperty("completionTicks", 2);
        sample.addProperty("attempt", 1); sample.add("physicalSearchIds", ProductionRemediationReport.JSON.toJsonTree(List.of()));
        sample.addProperty("failure", "NO_STRUCTURAL_ROUTE"); sample.add("blockedSection", com.google.gson.JsonNull.INSTANCE);
        sample.add("processCpuNanos", com.google.gson.JsonNull.INSTANCE);
        sample.add("completionBoundary", ProductionRemediationReport.JSON.toJsonTree(Map.of("status", "UNPROVEN", "reason", "NON_SUCCESS")));
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        ProductionRemediationReport.validateSample(sample, missing, failures);
        assertTrue(missing.isEmpty()); assertTrue(failures.isEmpty());
        sample.addProperty("wallNanos", 199); sample.addProperty("failure", "NONE");
        ProductionRemediationReport.validateSample(sample, missing, failures);
        assertTrue(failures.stream().anyMatch(value -> value.contains("Inconsistent timing")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("no production reason")));
    }

    @Test void missingOuterExitCannotEraseAnObservedServerFailure() throws Exception {
        Map<String, Object> exit = new java.util.LinkedHashMap<>();
        exit.put("serverProcessExitCode", 1); exit.put("gradleProcessExitCode", 0); exit.put("outerProcessExitCode", null);
        exit.put("cleanupComplete", true);
        ProductionRemediationReport.writeNew(temporary.resolve("exit.json"), exit, 4096);
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        ProductionRemediationReport.validateExit(temporary, missing, failures);
        assertTrue(missing.contains("Uncaptured outerProcessExitCode"));
        assertTrue(failures.stream().anyMatch(value -> value.contains("Nonzero serverProcessExitCode")));
        Files.writeString(temporary.resolve("observer-timeout.json"), "{}");
        ProductionRemediationReport.validateExit(temporary, missing, failures);
        assertTrue(failures.contains("External observer deadline expired"));
    }

    @Test void fixtureMutationsRejectColdStateHotCountsAndSampleTimingWithoutChangingRawEvidence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.putAll(Map.of("id", "query/fixture", "outcome", "SUCCESS", "owner", "owner", "dimension", "minecraft:overworld",
                "channel", "GROUND", "profile", "DEFAULT_GROUND", "priority", "ACTIVE", "start", List.of(0, 1, 0), "goal", List.of(8, 1, 0)));
        row.putAll(Map.of("startNanos", 100L, "endNanos", 300L, "wallNanos", 200L, "startTick", 1, "endTick", 2,
                "completionTicks", 1, "attempt", 1, "physicalSearchIds", List.of(), "failure", "NONE"));
        row.put("blockedSection", null); row.put("processCpuNanos", 100L); row.put("controlReference", "query/fixture");
        row.put("observerCost", Map.of("directWallNanos", 10, "calls", 1));
        row.put("completionBoundary", Map.of("status", "BOUNDED", "callBeforeNanos", 290L, "callAfterNanos", 305L, "callbackNanos", 300L, "lowerNanos", 190L, "upperNanos", 200L, "lowerTicks", 1, "upperTicks", 1));
        Map<String, Object> counters = Map.of("persistence.readRequests", 0L, "service.recoveredSections", 0L,
                "service.failedRecoveries", 0L, "worker.tasks.completed.builds", 0L, "worker.tasks.completed.prewarms", 0L);
        Map<String, Object> proof = Map.of("before", Map.of("facts/test", 1), "used", Map.of("facts/test", 1),
                "counterBefore", counters, "counterAfter", counters, "coldDerivedEmpty", true, "fullBefore", true);
        String original = ProductionRemediationReport.JSON.toJson(row), originalProof = ProductionRemediationReport.JSON.toJson(proof);
        for (String phase : List.of("first_observed", "hot", "cold")) {
            var target = new ProductionRemediationPlan.Target("query/fixture", "query", Map.of("phase", phase));
            assertEquals(true, ProductionRemediationReport.rejectSampleMutation(target, row, proof).get("mutationDetected"));
            assertEquals(original, ProductionRemediationReport.JSON.toJson(row));
            assertEquals(originalProof, ProductionRemediationReport.JSON.toJson(proof));
        }
        var snapshot = ProductionRemediationReport.JSON.toJsonTree(Map.of("snapshotNanos", java.util.Collections.nCopies(10000, 20L),
                "productionState", Map.of("activeMacroRequests", 1024))).getAsJsonObject();
        assertEquals(10000, ProductionRemediationReport.snapshotTimes(snapshot, "1024").size());
        snapshot.getAsJsonArray("snapshotNanos").remove(0);
        assertThrows(IllegalArgumentException.class, () -> ProductionRemediationReport.snapshotTimes(snapshot, "1024"));
    }

    @Test void stateDeltasPreserveDependenciesAndRejectMissingPredecessors() {
        var encoder = new ProductionRemediationReport.StateProofs();
        var decoder = new ProductionRemediationReport.StateProofs();
        Map<String, Object> objects = new LinkedHashMap<>();
        for (int index = 0; index < 512; index++) objects.put("facts/section/" + index,
                Map.of("token", index, "load", 1, "version", 1, "validity", 0));
        objects.put("base/section/0", Map.of("token", 999, "load", 0, "version", 1, "validity", 1));
        JsonObject first = null, last = null;
        long encodedBytes = 0, fullBytes = 0;
        for (int index = 0; index < 100; index++) {
            if (index == 99) { objects.remove("base/section/0"); objects.put("facts/section/0", Map.of("token", 1000, "load", 2, "version", 2, "validity", 0)); }
            JsonObject proof = ProductionRemediationReport.JSON.toJsonTree(Map.of("id", "query/" + index,
                    "event", "sample-state", "before", objects, "used", objects)).getAsJsonObject();
            JsonObject encoded = encoder.encode(proof), decoded = decoder.decode(encoded);
            for (String field : List.of("before", "used")) assertEquals(proof.get(field), decoded.get(field));
            if (index == 0) first = decoded;
            last = encoded;
            encodedBytes += encoded.toString().length(); fullBytes += proof.toString().length();
        }
        assertTrue(encodedBytes < fullBytes / 20);
        assertTrue(first.getAsJsonObject("used").has("base/section/0"));
        assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationReport.StateProofs().decode(new JsonObject()));
        JsonObject skipped = last;
        assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationReport.StateProofs().decode(skipped));
    }

    @Test void damagedMiddleRecordPreservesFailuresFromBothSides() throws Exception {
        var options = options("H", "R1", null); JsonObject identity = identity(options);
        ProductionRemediationReport.writeNew(temporary.resolve("identity.json"), identity, 1 << 20);
        var targets = ProductionRemediationPlan.targets(options);
        try (var journal = new ProductionRemediationReport.Journal(temporary, options)) {
            journal.control(targets.get(0), Map.of("outcome", "FAIL", "reached", true, "cleaned", true));
            journal.control(targets.get(1), Map.of("outcome", "FAIL", "reached", true, "cleaned", true));
            journal.finish(compact(), "remaining targets were not run");
        }
        appendOrInsertBrokenRecord(temporary, 2);
        var assessment = ProductionRemediationReport.assess(temporary, identity);
        assertEquals("INCOMPLETE", assessment.get("status"));
        assertEquals(2, ((List<?>) assessment.get("failures")).size());
    }

    @Test void outerExitSummaryCannotMaskRawNonzeroGradleExit() throws Exception {
        var exit = Map.of("observerId", "observer", "serverProcessExitCode", 0, "gradleProcessExitCode", 0,
                "outerProcessExitCode", 0, "cleanupComplete", true);
        ProductionRemediationReport.writeNew(temporary.resolve("exit.json"), exit, 4096);
        ProductionRemediationReport.writeNew(temporary.resolve("server-exit.json"), exit, 4096);
        ProductionRemediationReport.writeNew(temporary.resolve("gradle-exit.json"), Map.of("observerId", "observer", "exitCode", 7), 4096);
        ProductionRemediationReport.writeNew(temporary.resolve("outer-exit.json"), Map.of("observerId", "observer", "exitCode", 0), 4096);
        Files.writeString(temporary.resolve("console.log"), "raw console"); Files.writeString(temporary.resolve("server.log"), "raw server");
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        ProductionRemediationReport.validateExit(temporary, missing, failures);
        assertTrue(missing.isEmpty());
        assertTrue(failures.contains("Raw nonzero exit masked: gradle"));
    }

    @Test void adoptionRuleNeverAdoptsCrossingRelationsAndRequiresPreRegisteredReview() {
        assertEquals("ADOPTED", ProductionRemediationReport.adoptionFor("WITHIN", true));
        assertEquals("ADOPTED", ProductionRemediationReport.adoptionFor("EXCEEDS", true));
        assertEquals("REVIEW_REQUIRED", ProductionRemediationReport.adoptionFor("WITHIN", false));
        assertEquals("REVIEW_REQUIRED", ProductionRemediationReport.adoptionFor("EXCEEDS", false));
        assertEquals("REVIEW_REQUIRED", ProductionRemediationReport.adoptionFor("CROSSES_LIMIT", true));
        assertEquals("REVIEW_REQUIRED", ProductionRemediationReport.adoptionFor(null, true));
        assertEquals("CROSSES_LIMIT", ProductionRemediationReport.combinedColdRelation("WITHIN", "CROSSES_LIMIT"));
        assertEquals("EXCEEDS", ProductionRemediationReport.combinedColdRelation("WITHIN", "EXCEEDS"));
        assertEquals("WITHIN", ProductionRemediationReport.combinedColdRelation("WITHIN", "WITHIN"));
    }

    @Test void adoptionReviewMustBindBatchSelectionAndBeFrozenBeforeRun() throws Exception {
        Path batch = temporary.resolve("PRM04"); Files.createDirectories(batch);
        List<Path> identities = adoptionIdentities(batch);
        JsonObject review = new JsonObject();
        review.addProperty("schemaVersion", "PRM-ADOPTION-REVIEW-1"); review.addProperty("batch", "PRM04");
        review.addProperty("selection", "prm03-performance-gaps"); review.addProperty("frozenAt", "2026-09-19T00:00:00Z");
        JsonObject route = new JsonObject(); route.addProperty("conclusion", "CANNOT_CHANGE_SIDE");
        route.add("evidence", ProductionRemediationReport.JSON.toJsonTree(List.of("observer-cost-bounded")).getAsJsonArray());
        JsonObject routes = new JsonObject(); routes.add("cave_8_01", route); review.add("routes", routes);
        JsonObject groups = new JsonObject(); groups.add("surface_8", route.deepCopy()); review.add("coldGroups", groups);
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        List<String> issues = new ArrayList<>();
        var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, issues);
        assertTrue(issues.isEmpty(), issues.toString());
        assertTrue(parsed.routes().contains("cave_8_01"));
        assertTrue(parsed.coldGroups().contains("surface_8"));
        review.addProperty("frozenAt", "2026-09-21T00:00:00Z");
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        issues.clear();
        var late = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, issues);
        assertTrue(late.routes().isEmpty()); assertTrue(late.coldGroups().isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.contains("not pre-registered")), issues.toString());
        review.addProperty("frozenAt", "2026-09-19T00:00:00Z"); review.addProperty("selection", "other");
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        issues.clear();
        var wrongSelection = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, issues);
        assertTrue(wrongSelection.routes().isEmpty()); assertTrue(wrongSelection.coldGroups().isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.contains("batch/selection")), issues.toString());
        Files.delete(batch.resolve("adoption-review.json"));
        issues.clear();
        var missingReview = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, issues);
        assertTrue(missingReview.routes().isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.contains("missing")), issues.toString());
    }

    private List<Path> adoptionIdentities(Path batch) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            var opts = new ProductionRemediationPlan.Options("formal", "terrain", "R" + round, "PRM04", null, null, null, "prm03-performance-gaps");
            Path path = batch.resolve("R" + round + "/terrain/prm03-performance-gaps/identity.json");
            Files.createDirectories(path.getParent());
            JsonObject value = ProductionRemediationReport.JSON.toJsonTree(opts.parameters()).getAsJsonObject();
            value.addProperty("preparedAt", "2026-09-20T00:00:00Z");
            Files.writeString(path, value.toString()); paths.add(path);
        }
        return paths;
    }

    private JsonObject completeAdoptionReview() {
        JsonObject review = new JsonObject();
        review.addProperty("schemaVersion", "PRM-ADOPTION-REVIEW-1"); review.addProperty("batch", "PRM04");
        review.addProperty("selection", "prm03-performance-gaps"); review.addProperty("frozenAt", "2026-09-19T00:00:00Z");
        JsonObject entry = new JsonObject(); entry.addProperty("conclusion", "CANNOT_CHANGE_SIDE");
        entry.add("evidence", ProductionRemediationReport.JSON.toJsonTree(List.of("synthetic test evidence, not production acceptance")));
        JsonObject routes = new JsonObject(), cold = new JsonObject();
        for (String route : ProductionRemediationPlan.performanceRoutes()) routes.add(route, entry.deepCopy());
        for (String group : ProductionRemediationPlan.performanceColdGroups()) cold.add(group, entry.deepCopy());
        review.add("routes", routes); review.add("coldGroups", cold);
        return review;
    }

    @Test void adoptionRejectsInvalidRoundIdentityAndTimestampWithoutLeakingCoverage() throws Exception {
        Path batch = temporary.resolve("PRM04");
        var identities = adoptionIdentities(batch); var review = completeAdoptionReview();
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        for (var invalid : List.of(List.<Path>of(), identities.subList(0, 2), List.of(identities.get(0), identities.get(0), identities.get(2)))) {
            var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", invalid, new ArrayList<>());
            assertTrue(parsed.routes().isEmpty()); assertTrue(parsed.coldGroups().isEmpty()); assertFalse(parsed.issues().isEmpty());
        }
        Path identity = identities.get(1); String original = Files.readString(identity);
        for (String key : List.of("batch", "profile", "case", "selection", "run", "preparedAt")) {
            JsonObject invalid = com.google.gson.JsonParser.parseString(original).getAsJsonObject(); invalid.addProperty(key, "invalid");
            Files.writeString(identity, invalid.toString());
            var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, new ArrayList<>());
            assertTrue(parsed.routes().isEmpty(), key); assertTrue(parsed.coldGroups().isEmpty(), key);
        }
        Files.writeString(identity, original);
        for (String time : List.of("2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z", "invalid")) {
            review.addProperty("frozenAt", time); Files.writeString(batch.resolve("adoption-review.json"), review.toString());
            var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, new ArrayList<>());
            assertTrue(parsed.routes().isEmpty(), time); assertTrue(parsed.coldGroups().isEmpty(), time);
        }
    }

    @Test void adoptionMalformedEntriesStayLocalAndCannotLeavePartialCoverageForTheirTarget() throws Exception {
        Path batch = temporary.resolve("PRM04"); var identities = adoptionIdentities(batch);
        for (String bad : List.of("null", "true", "{}", "{\"conclusion\":[],\"evidence\":[\"x\"]}",
                "{\"conclusion\":\"CANNOT_CHANGE_SIDE\",\"evidence\":{}}",
                "{\"conclusion\":\"CANNOT_CHANGE_SIDE\",\"evidence\":[true]}",
                "{\"conclusion\":\"CANNOT_CHANGE_SIDE\",\"evidence\":[42]}",
                "{\"conclusion\":\"CANNOT_CHANGE_SIDE\",\"evidence\":[\" \" ]}")) {
            var review = completeAdoptionReview();
            review.getAsJsonObject("routes").add("cave_8_01", com.google.gson.JsonParser.parseString(bad));
            review.getAsJsonObject("coldGroups").add("surface_8", com.google.gson.JsonParser.parseString(bad));
            Files.writeString(batch.resolve("adoption-review.json"), review.toString());
            var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, new ArrayList<>());
            assertFalse(parsed.routes().contains("cave_8_01"), bad); assertFalse(parsed.coldGroups().contains("surface_8"), bad);
            assertTrue(parsed.routes().contains("cave_8_02"), bad); assertTrue(parsed.coldGroups().contains("surface_64"), bad);
        }
        var review = completeAdoptionReview(); review.addProperty("coldGroups", false);
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        var parsed = ProductionRemediationReport.parseAdoptionReview(batch, "PRM04", "prm03-performance-gaps", identities, new ArrayList<>());
        assertTrue(parsed.coldGroups().isEmpty()); assertTrue(parsed.routes().contains("cave_8_01"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.routes().clear());
    }

    @Test void adoptionAggregationPropagatesInvalidReviewsAndAcceptedFailures() throws Exception {
        Path batch = syntheticPerformanceBatch();
        var review = completeAdoptionReview();
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        JsonObject valid = aggregateSyntheticPerformance(batch, missing, failures);
        assertEquals("ADOPTED", valid.get("adoption").getAsString()); assertTrue(missing.isEmpty(), missing.toString());
        assertTrue(failures.contains("Hot performance exceeds limit: cave_8_01"));
        assertTrue(failures.contains("Cold performance exceeds limit: surface_8"));
        assertEquals("ADOPTED", valid.getAsJsonObject("routes").getAsJsonObject("cave_8_02").get("adoption").getAsString());
        for (String time : List.of("2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z")) {
            review.addProperty("frozenAt", time); Files.writeString(batch.resolve("adoption-review.json"), review.toString());
            missing.clear(); failures.clear(); var invalid = aggregateSyntheticPerformance(batch, missing, failures);
            assertEquals("REVIEW_REQUIRED", invalid.get("adoption").getAsString());
            for (String section : List.of("routes", "coldGroups")) for (var item : invalid.getAsJsonObject(section).entrySet())
                assertEquals("REVIEW_REQUIRED", item.getValue().getAsJsonObject().get("adoption").getAsString());
            assertFalse(missing.isEmpty()); assertTrue(failures.isEmpty(), failures.toString());
        }
        Files.delete(batch.resolve("adoption-review.json")); missing.clear(); failures.clear();
        assertEquals("REVIEW_REQUIRED", aggregateSyntheticPerformance(batch, missing, failures).get("adoption").getAsString());
        assertFalse(missing.isEmpty()); assertTrue(failures.isEmpty());
    }

    @Test void adoptionAggregationKeepsCrossingsPendingAndValidWithinResultsAdoptable() throws Exception {
        Path batch = syntheticPerformanceBatch(); var review = completeAdoptionReview();
        Files.writeString(batch.resolve("adoption-review.json"), review.toString());
        // Keep the same fixed targets; only the offline synthetic observations change.
        for (int round = 1; round <= 3; round++) {
            Path folder = batch.resolve("R" + round + "/terrain/prm03-performance-gaps");
            List<String> rows = new ArrayList<>();
            for (String line : Files.readAllLines(folder.resolve("samples.jsonl"))) {
                JsonObject row = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                var boundary = row.getAsJsonObject("completionBoundary");
                row.addProperty("wallNanos", 100_000L); boundary.addProperty("lowerNanos", 99_000L); boundary.addProperty("upperNanos", 100_000L);
                boundary.addProperty("lowerTicks", 1); boundary.addProperty("upperTicks", 1);
                rows.add(row.toString());
            }
            Files.write(folder.resolve("samples.jsonl"), rows);
            refreshSyntheticSampleReference(folder);
        }
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        assertEquals("ADOPTED", aggregateSyntheticPerformance(batch, missing, failures).get("adoption").getAsString());
        assertTrue(missing.isEmpty()); assertTrue(failures.isEmpty());
        for (int round = 1; round <= 3; round++) {
            Path folder = batch.resolve("R" + round + "/terrain/prm03-performance-gaps");
            List<String> rows = new ArrayList<>();
            for (String line : Files.readAllLines(folder.resolve("samples.jsonl"))) {
                JsonObject row = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                var parameters = row.getAsJsonObject("parameters");
                var boundary = row.getAsJsonObject("completionBoundary");
                if (parameters.get("route").getAsString().equals("cave_8_01")) {
                    boundary.addProperty("upperNanos", 501_000L); row.addProperty("wallNanos", 501_000L);
                }
                if (parameters.get("phase").getAsString().equals("cold") && parameters.get("route").getAsString().startsWith("surface_8_")) boundary.addProperty("upperTicks", 3);
                rows.add(row.toString());
            }
            Files.write(folder.resolve("samples.jsonl"), rows); refreshSyntheticSampleReference(folder);
        }
        var crossing = aggregateSyntheticPerformance(batch, missing, failures);
        assertEquals("REVIEW_REQUIRED", crossing.getAsJsonObject("routes").getAsJsonObject("cave_8_01").get("adoption").getAsString());
        assertEquals("REVIEW_REQUIRED", crossing.getAsJsonObject("coldGroups").getAsJsonObject("surface_8").get("adoption").getAsString());
        assertEquals("ADOPTED", crossing.getAsJsonObject("routes").getAsJsonObject("cave_8_02").get("adoption").getAsString());
        assertEquals("REVIEW_REQUIRED", crossing.get("adoption").getAsString());
        assertFalse(missing.isEmpty()); assertTrue(failures.isEmpty());
    }

    private void refreshSyntheticSampleReference(Path folder) throws Exception {
        var result = ProductionRemediationReport.readObject(folder.resolve("result.json"));
        var ref = result.getAsJsonObject("rawEvidence").getAsJsonObject("samples");
        ref.addProperty("bytes", Files.size(folder.resolve("samples.jsonl")));
        ref.addProperty("sha256", ProductionRemediationReport.hash(folder.resolve("samples.jsonl")));
        Files.writeString(folder.resolve("result.json"), result.toString());
    }

    private Path syntheticPerformanceBatch() throws Exception {
        Path baseline = temporary.resolve(ProductionRemediationPlan.BASELINE); Files.createDirectories(baseline.getParent());
        Files.copy(Path.of(ProductionRemediationPlan.BASELINE), baseline);
        Path batch = temporary.resolve("PRM04"); var identities = adoptionIdentities(batch);
        for (int round = 1; round <= 3; round++) {
            var opts = new ProductionRemediationPlan.Options("formal", "terrain", "R" + round, "PRM04", null, null, null, "prm03-performance-gaps");
            Path folder = identities.get(round - 1).getParent(); StringBuilder lines = new StringBuilder();
            for (var target : ProductionRemediationPlan.targets(opts)) {
                boolean hotFailure = target.parameters().get("route").equals("cave_8_01");
                long duration = hotFailure ? 2_000_000L : 100_000L;
                int ticks = target.parameters().get("phase").equals("cold") && target.parameters().get("route").startsWith("surface_8_") ? 3 : 1;
                lines.append(ProductionRemediationReport.JSON.toJson(Map.of("id", target.id(), "kind", target.kind(), "parameters", target.parameters(),
                        "outcome", "SUCCESS", "qualified", true, "wallNanos", duration, "completionBoundary",
                        Map.of("status", "BOUNDED", "lowerNanos", duration - 1000, "upperNanos", duration, "lowerTicks", ticks, "upperTicks", ticks)))).append('\n');
            }
            Files.writeString(folder.resolve("samples.jsonl"), lines); Files.writeString(folder.resolve("controls.jsonl"), "{}");
            Map<String, Object> refs = new LinkedHashMap<>();
            for (String stream : List.of("samples", "controls")) {
                Path file = folder.resolve(stream + ".jsonl");
                refs.put(stream, Map.of("path", file.getFileName().toString(), "bytes", Files.size(file), "records", stream.equals("samples") ? ProductionRemediationPlan.targets(opts).size() : 1,
                        "sha256", ProductionRemediationReport.hash(file), "truncated", false));
            }
            Files.writeString(folder.resolve("result.json"), ProductionRemediationReport.JSON.toJson(Map.of("rawEvidence", refs)));
        }
        return batch;
    }

    private JsonObject aggregateSyntheticPerformance(Path batch, List<String> missing, List<String> failures) throws Exception {
        var method = ProductionRemediationReport.class.getDeclaredMethod("aggregatePerformance", Path.class, Path.class, String.class, List.class, List.class);
        method.setAccessible(true);
        return ProductionRemediationReport.JSON.toJsonTree(method.invoke(null, temporary, batch, "prm03-performance-gaps", missing, failures)).getAsJsonObject();
    }

    private static void appendOrInsertBrokenRecord(Path output, int index) throws Exception {
        Path gz = output.resolve("controls.jsonl.gz");
        List<String> lines = new ArrayList<>();
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(gz));
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) lines.add(line);
        }
        if (index < 0 || index >= lines.size()) lines.add("broken record"); else lines.add(index, "broken record");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            for (String line : lines) out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void compressedControlsStreamStaysUnderBudgetAndRoundTrips() throws Exception {
        Path file = temporary.resolve("controls.jsonl.gz");
        try (var stream = new ProductionRemediationReport.Stream(file, 128L << 20, true)) {
            for (int i = 0; i < 5000; i++) stream.append(Map.of("event", "sample-state", "id", "x" + i));
            var reference = stream.reference();
            assertEquals("controls.jsonl.gz", reference.get("path"));
            assertEquals(5000L, reference.get("records"));
            assertEquals(false, reference.get("truncated"));
        }
        assertTrue(Files.size(file) < (128L << 20));
        long[] count = {0};
        ProductionRemediationReport.readLines(file, row -> count[0]++);
        assertEquals(5000, count[0]);
    }

    @Test void rawStreamFilePrefersCompressedControlsAndReaderDetectsGzip() throws Exception {
        Path gz = temporary.resolve("controls.jsonl.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("{\"event\":\"sample-state\",\"id\":\"x\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(gz, ProductionRemediationReport.rawStreamFile(temporary, "controls"));
        long[] count = {0};
        ProductionRemediationReport.readLines(gz, row -> count[0]++);
        assertEquals(1, count[0]);
    }

    private static ProductionRemediationPlan.Options options(String scenario, String run, String variant) {
        return new ProductionRemediationPlan.Options("formal", scenario, run, "PRM01", variant, null);
    }

    @Test void frozenD05D07D08PositionsMatchTheApprovedInventories() throws Exception {
        Path approved = Path.of("build/reports/prm03-consolidated-remediation-20260910");
        for (String name : List.of("measurement", "causes")) {
            String json = Files.readString(approved.resolve("revision-09/configs/" + name + ".json"));
            var opts = new ProductionRemediationPlan.Options("diagnostic", "causality", "R1", "PRMD1500", null, null, null, null, json);
            var targets = ProductionRemediationPlan.targets(opts);
            assertEquals(name.equals("measurement") ? 252 : 3083, targets.size());
            assertEquals(targets.size(), targets.stream().map(ProductionRemediationPlan.Target::id).distinct().count());
            assertEquals(opts, ProductionRemediationReport.options(identity(opts)));
            var plan = ProductionRemediationPlan.DiagnosticPlan.parse(json);
            plan.validateRoutes(ProductionRemediationReport.readObject(Path.of(ProductionRemediationPlan.MANIFEST)));
        }
        var reference = ProductionRemediationReport.readObject(approved.resolve("revision-11/performance-reference-plan.json"));
        for (int round = 1; round <= 3; round++) {
            var opts = new ProductionRemediationPlan.Options("formal", "terrain", "R" + round, "PRM04", null, null, null, "prm03-performance-gaps");
            assertEquals(reference.getAsJsonArray("rounds").get(round - 1).getAsJsonObject().get("targets"), ProductionRemediationReport.JSON.toJsonTree(ProductionRemediationPlan.targets(opts)));
        }
        var functional = ProductionRemediationReport.readObject(approved.resolve("revision-12/functional-recovery-plan.json"));
        var opts = new ProductionRemediationPlan.Options("formal", "L", "R1", "PRM05", null, null, null, "prm03-functional-gaps");
        var actual = ProductionRemediationPlan.targets(opts);
        assertEquals(15, actual.size());
        var original = functional.getAsJsonArray("instances").get(1).getAsJsonObject().getAsJsonArray("targets");
        assertEquals(java.util.stream.StreamSupport.stream(original.spliterator(), false).map(t -> t.getAsJsonObject().get("id").getAsString()).toList(), actual.stream().map(ProductionRemediationPlan.Target::id).toList());
    }

    @Test void complementaryObservationPlanPreservesFrozenPositionsAndRejectsOtherFactors() throws Exception {
        Path original = Path.of("build/reports/prm04-acceptance-20260915/d05/frozen-plan.json");
        var frozen = com.google.gson.JsonParser.parseString(Files.readString(original)).getAsJsonArray().get(0).getAsJsonObject();
        var expected = frozen.getAsJsonArray("targets").deepCopy();
        String oldInput = Files.readString(Path.of("build/reports/prm03-consolidated-remediation-20260910/revision-09/configs/measurement.json"));
        String newInput = Files.readString(Path.of("build/reports/prm04-complementary-20260917/measurement-complementary.json"));
        for (String input : List.of(oldInput, newInput)) {
            boolean complement = input.equals(newInput);
            var opts = new ProductionRemediationPlan.Options("diagnostic", "causality", "R1", "PRMD1502", null, null, null, null, input);
            if (complement) for (var item : expected) {
                var target = item.getAsJsonObject(); var params = target.getAsJsonObject("parameters");
                if (!params.has("mode")) continue;
                String before = params.get("mode").getAsString(), after = before.equals("A") ? "B" : "A";
                params.addProperty("mode", after);
                target.addProperty("id", target.get("id").getAsString().replace("/mode=" + before + "/", "/mode=" + after + "/"));
            }
            var targets = ProductionRemediationPlan.targets(opts);
            assertEquals(expected, ProductionRemediationReport.JSON.toJsonTree(targets));
            assertEquals(252, targets.size()); assertEquals(252, targets.stream().map(ProductionRemediationPlan.Target::id).distinct().count());
            assertEquals(opts, ProductionRemediationReport.options(identity(opts)));
            ProductionRemediationPlan.DiagnosticPlan.parse(input).validateRoutes(ProductionRemediationReport.readObject(Path.of(ProductionRemediationPlan.MANIFEST)));
            assertThrows(IllegalArgumentException.class, () -> new ProductionRemediationPlan.Options("formal", "terrain", "R1", "PRM04", null, null, null, null, input));
        }
        var valid = com.google.gson.JsonParser.parseString(newInput).getAsJsonObject();
        for (String mutation : List.of("modes", "requestsPerBlock", "factor", "beforeSnapshot", "BQualification", "observers", "pressure")) {
            var invalid = valid.deepCopy(); var comparison = invalid.getAsJsonObject("comparison");
            switch (mutation) {
                case "modes" -> comparison.add("modes", com.google.gson.JsonParser.parseString("[\"A\",\"B\",\"A\",\"B\",\"A\",\"B\"]"));
                case "requestsPerBlock" -> comparison.addProperty(mutation, 21);
                case "observers" -> invalid.getAsJsonArray(mutation).add("search");
                case "pressure" -> invalid.addProperty(mutation, "three-stages");
                default -> comparison.addProperty(mutation, "changed");
            }
            assertThrows(IllegalArgumentException.class, () -> ProductionRemediationPlan.DiagnosticPlan.parse(invalid.toString()), mutation);
        }
    }

    @Test void diagnosticConfigRejectsUnknownRoutesFieldsCountsAndFactors() throws Exception {
        String input = Files.readString(Path.of("build/reports/prm03-consolidated-remediation-20260910/revision-09/configs/causes.json"));
        var json = com.google.gson.JsonParser.parseString(input).getAsJsonObject();
        json.addProperty("unknown", true);
        assertThrows(RuntimeException.class, () -> ProductionRemediationPlan.DiagnosticPlan.parse(json.toString())); json.remove("unknown");
        json.getAsJsonArray("routes").get(0).getAsJsonObject().addProperty("hot", .5);
        assertThrows(RuntimeException.class, () -> ProductionRemediationPlan.DiagnosticPlan.parse(json.toString()));
        json.getAsJsonArray("routes").get(0).getAsJsonObject().addProperty("hot", 2);
        json.getAsJsonArray("routes").get(0).getAsJsonObject().addProperty("id", "surface_64_99");
        var plan = ProductionRemediationPlan.DiagnosticPlan.parse(json.toString());
        assertThrows(RuntimeException.class, () -> plan.validateRoutes(ProductionRemediationReport.readObject(Path.of(ProductionRemediationPlan.MANIFEST))));
        String duplicate = input.replace("surface_512_01", "surface_64_01");
        assertThrows(RuntimeException.class, () -> ProductionRemediationPlan.DiagnosticPlan.parse(duplicate));
        assertThrows(RuntimeException.class, () -> new ProductionRemediationPlan.Options("formal", "terrain", "R1", "PRM04", null, null, null, null, input));
    }

    @Test void diagnosticConfigRejectsAnExistingButUnqualifiedRoute() throws Exception {
        String input = Files.readString(Path.of("build/reports/prm03-consolidated-remediation-20260910/revision-09/configs/causes.json"));
        var plan = ProductionRemediationPlan.DiagnosticPlan.parse(input);
        var manifest = ProductionRemediationReport.readObject(Path.of(ProductionRemediationPlan.MANIFEST));
        for (var route : manifest.getAsJsonArray("routes")) if ("surface_64_01".equals(route.getAsJsonObject().get("name").getAsString()))
            route.getAsJsonObject().addProperty("status", "UNREACHABLE");
        assertThrows(RuntimeException.class, () -> plan.validateRoutes(manifest));
    }

    @Test void reducedObservationStillRequiresCompleteBeforeAndPreparationEvidence() {
        var target = new ProductionRemediationPlan.Target("comparison", "query", Map.of("phase", "hot", "mode", "B"));
        var sample = ProductionRemediationReport.JSON.toJsonTree(Map.of("controlReference", "comparison", "outcome", "SUCCESS",
                "qualified", false, "qualificationMode", "DIAGNOSTIC_ONLY", "observerCost", Map.of("directWallNanos", 0, "calls", 0))).getAsJsonObject();
        var proof = ProductionRemediationReport.JSON.toJsonTree(Map.of("fullBefore", true, "before", Map.of("base/a", "stamp"),
                "prepared", Map.of("base/a", "stamp"), "used", Map.of(), "counterBefore", Map.of(), "counterAfter", Map.of())).getAsJsonObject();
        var missing = new ArrayList<String>(); var failures = new ArrayList<String>();
        ProductionRemediationReport.validateStateProof(target, sample, proof, missing, failures);
        assertTrue(missing.isEmpty()); assertTrue(failures.isEmpty());
        proof.remove("fullBefore");
        ProductionRemediationReport.validateStateProof(target, sample, proof, missing, failures);
        assertFalse(missing.isEmpty());
        missing.clear(); proof.addProperty("fullBefore", true); proof.getAsJsonObject("prepared").addProperty("base/a", "old-stamp");
        ProductionRemediationReport.validateStateProof(target, sample, proof, missing, failures);
        assertFalse(failures.isEmpty());
    }

    @Test void completionIntervalsUseUnroundedLimitsAndRejectMissingSuccessProof() {
        assertEquals("CROSSES_LIMIT", ProductionRemediationReport.interval(.49, .52, .5).get("observedRelation"));
        assertEquals("EXCEEDS", ProductionRemediationReport.interval(.50001, .50002, .5).get("observedRelation"));
        assertEquals("WITHIN", ProductionRemediationReport.interval(.499, .5, .5).get("observedRelation"));
        var row = new JsonObject(); row.addProperty("id", "target"); row.addProperty("outcome", "SUCCESS");
        var missing = new ArrayList<String>(); var failures = new ArrayList<String>();
        ProductionRemediationReport.validateBoundary(row, missing, failures); assertFalse(missing.isEmpty());
    }

    @Test void platformClockCapabilityIsRecordedWithoutClaimingPerRequestCpuPrecision() throws Exception {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        boolean cpu = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        long cpuStart = cpu ? bean.getCurrentThreadCpuTime() : -1, start = System.nanoTime(), previous = start, step = Long.MAX_VALUE;
        for (int i = 0; i < 10000; i++) { long now = System.nanoTime(); if (now > previous) step = Math.min(step, now - previous); previous = now; }
        long wall = System.nanoTime() - start, cpuEnd = cpu ? bean.getCurrentThreadCpuTime() : -1;
        var data = Map.of("javaRuntime", System.getProperty("java.runtime.version"), "calls", 10000, "wallNanos", wall,
                "minimumObservedStepNanos", step, "threadCpuSupportedEnabled", cpu, "threadCpuBatchDelta", cpu ? cpuEnd - cpuStart : -1,
                "interpretation", "Observed clock capability only; numerical units do not establish absolute accuracy or exclusive module CPU");
        Path output = Path.of("build/reports/prm03-test-implementation-20260910/clock-capability.json");
        Files.writeString(output, ProductionRemediationReport.JSON.toJson(data));
        assertTrue(wall > 0); assertTrue(step > 0 && step < 1000000, "millisecond distinctions require a finer observed step");
    }

    private static JsonObject identity(ProductionRemediationPlan.Options options) {
        JsonObject result = ProductionRemediationReport.JSON.toJsonTree(options.parameters()).getAsJsonObject();
        result.addProperty("schemaVersion", ProductionRemediationPlan.SCHEMA);
        result.add("targets", ProductionRemediationReport.JSON.toJsonTree(ProductionRemediationPlan.targets(options)));
        return result;
    }

    private static Map<String, Object> compact() {
        return Map.of("state", "DATA_COMPLETE", "runScope", Map.of(), "routes", List.of(), "pressureStages", List.of(), "historyBaseline", Map.of());
    }
}
