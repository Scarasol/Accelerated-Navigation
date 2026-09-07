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
        assertEquals(List.of(4, 4, 2, 12, 5, 3, 15, 5, 3), counts);
        assertEquals(53, counts.stream().mapToInt(Integer::intValue).sum());
        var diagnostic = new ProductionRemediationPlan.Options("diagnostic", "causality", "R1", "PRMD01", null, null);
        assertEquals(3080, ProductionRemediationPlan.targets(diagnostic).size());
        for (String scenario : List.of("G", "H", "F", "L")) {
            var plan = ProductionRemediationPlan.targets(options(scenario, "R1", null));
            assertFalse(plan.isEmpty());
            assertEquals(plan.size(), plan.stream().map(ProductionRemediationPlan.Target::id).distinct().count());
        }
        var h = ProductionRemediationPlan.targets(options("H", "R1", null));
        assertEquals(112, h.stream().filter(target -> target.kind().equals("H04")).count());
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
        assertTrue(failures.stream().anyMatch(value -> value.contains("CPU increment exceeds 1%")));
        assertEquals(4, failures.stream().filter(value -> value.contains("hot P95 increment")).count());
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
        Files.writeString(temporary.resolve("controls.jsonl"), "\n", java.nio.file.StandardOpenOption.APPEND);
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
        Map<String, Object> counters = Map.of("persistence.readRequests", 0L, "service.recoveredSections", 0L,
                "service.failedRecoveries", 0L, "worker.tasks.completed.builds", 0L, "worker.tasks.completed.prewarms", 0L);
        Map<String, Object> proof = Map.of("before", Map.of("facts/test", 1), "used", Map.of("facts/test", 1),
                "counterBefore", counters, "counterAfter", counters, "coldDerivedEmpty", true);
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

    @Test void damagedMiddleRecordPreservesFailuresFromBothSides() throws Exception {
        var options = options("H", "R1", null); JsonObject identity = identity(options);
        ProductionRemediationReport.writeNew(temporary.resolve("identity.json"), identity, 1 << 20);
        var targets = ProductionRemediationPlan.targets(options);
        try (var journal = new ProductionRemediationReport.Journal(temporary, options)) {
            journal.control(targets.get(0), Map.of("outcome", "FAIL", "reached", true, "cleaned", true));
            journal.control(targets.get(1), Map.of("outcome", "FAIL", "reached", true, "cleaned", true));
            journal.finish(compact(), "remaining targets were not run");
        }
        var path = temporary.resolve("controls.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(path)); lines.add(2, "broken record"); Files.write(path, lines);
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

    private static ProductionRemediationPlan.Options options(String scenario, String run, String variant) {
        return new ProductionRemediationPlan.Options("formal", scenario, run, "PRM01", variant, null);
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
