package com.scarasol.acceleratednavigation.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Frozen PRM-0805-1 input expansion, shared by preparation, execution and readers. */
public final class ProductionRemediationPlan {
    public static final String SCHEMA = "PRM-0805-1";
    public static final String MANIFEST = "terrain-qualification-input/real-terrain-qualification.json.slots/slot-a/manifest.json";
    public static final String MANIFEST_HASH = "16EB5BBD262BDF6D10B69535787E270A0A097F6ADD45FEEBC3B8CA4B8F40B4FF";
    public static final String BASELINE = "build/reports/rqf28-current-reference-diagnostic-20260905/real-terrain-topology-current.json";
    public static final String BASELINE_HASH = "4151507584AC0B51B19989DAC99A72AB9046AA4EA3D9B099621D55D92F6FCF0E";
    public static final List<String> GROUPS = List.of("surface_8", "surface_64", "surface_96", "surface_512",
            "cave_8", "cave_64", "cave_96", "nether_8", "nether_64", "nether_96");
    public static final List<String> STAGES = List.of("high_coalescing", "completed_corridor_replay", "distributed_routes");
    public static final List<String> PATHS = List.of("memory", "evicted");
    public static final List<String> LINK_WINDOWS = List.of("base/captured", "base/publishing", "base/replaced",
            "parent/captured", "parent/publishing", "parent/replaced", "base-boundary/captured",
            "base-boundary/publishing", "base-boundary/replaced", "parent-boundary/captured",
            "parent-boundary/publishing", "parent-boundary/replaced", "corridor/final-validation");

    private ProductionRemediationPlan() { }

    public record Options(String profile, String scenario, String run, String batch, String variant, String control, String family) {
        public Options(String profile, String scenario, String run, String batch, String variant, String control) {
            this(profile, scenario, run, batch, variant, control, null);
        }
        public Options {
            if (profile == null || !List.of("formal", "fixture", "diagnostic").contains(profile)) fail("profile");
            List<String> cases = profile.equals("diagnostic") ? List.of("causality", "generation-cost", "probe")
                    : profile.equals("fixture") ? List.of("terrain", "G", "H", "F", "L", "W", "M", "D06")
                    : List.of("terrain", "G", "H", "F", "L", "W", "M");
            if (scenario == null || !cases.contains(scenario)) fail("case");
            if (scenario.equals("W")) {
                if (run == null || !run.matches("W0[1-9]") || variant == null
                        || !variant.matches("[1-9][0-9]*")) fail("W run/variant");
                int count = List.of(4, 4, 2, 12, 5, 3, 15, 5, 3).get(Integer.parseInt(run.substring(1)) - 1);
                if (Long.parseLong(variant) > count) fail("W variant");
            } else {
                List<String> runs = List.of("terrain", "M", "probe", "generation-cost").contains(scenario)
                        ? List.of("R1", "R2", "R3") : List.of("R1");
                if (run == null || !runs.contains(run)) fail("run");
                if (scenario.equals("D06")) {
                    if (variant == null || !List.of("chunk-write-idle", "chunk-write-pending", "api-pending").contains(variant)) fail("D06 variant");
                } else if (List.of("M", "probe", "generation-cost").contains(scenario)) {
                    if (variant == null || !List.of("on", "off").contains(variant)) fail("variant");
                } else if (variant != null) fail("unexpected variant");
            }
            String prefix = profile.equals("formal") ? "PRM" : profile.equals("fixture") ? "PRMQ" : "PRMD";
            if (batch == null || !batch.matches(prefix + "[0-9]{2,}")) fail("batch");
            if (profile.equals("fixture")) {
                control = control == null ? "normal" : control;
                if (!List.of("normal", "mutated", "missed").contains(control)) fail("control");
            } else if (control != null) fail("fixture-only control");
            if (family != null && (!profile.equals("diagnostic") || !scenario.equals("probe")
                    || !List.of("endpoint", "build", "search", "final-validation").contains(family))) fail("diagnostic probe family");
        }

        public static Options from(Map<String, String> values) {
            return new Options(values.get("profile"), values.get("case"), values.get("run"),
                    values.get("batch"), values.get("variant"), values.get("control"), values.get("family"));
        }

        public static Options configured() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : List.of("profile", "case", "run", "batch", "variant", "control", "family")) {
                values.put(key, System.getProperty("acceleratedNavigation.validation." + key));
            }
            return from(values);
        }

        public String suffix() { return batch + "/" + run + "/" + scenario + (variant == null ? "" : "/" + variant)
                + (family == null ? "" : "/" + family); }
        public boolean usesFrozenWorld() { return List.of("terrain", "M", "probe", "causality").contains(scenario); }
        public int round() { return Integer.parseInt(run.substring(1)); }
        public Map<String, String> parameters() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("profile", profile); result.put("case", scenario); result.put("run", run); result.put("batch", batch);
            if (variant != null) result.put("variant", variant);
            if (control != null) result.put("control", control);
            if (family != null) result.put("family", family);
            return result;
        }
        public List<String> command() {
            List<String> args = new ArrayList<>(List.of(".\\gradlew.bat", "runTerrainBenchmarkServer", "-PterrainTestMode=benchmark"));
            parameters().forEach((key, value) -> args.add("-PterrainValidation" + Character.toUpperCase(key.charAt(0)) + key.substring(1) + "=" + value));
            args.addAll(List.of("--offline", "--no-daemon", "--console=plain"));
            return List.copyOf(args);
        }
    }

    public record Target(String id, String kind, Map<String, String> parameters) {
        public Target { parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters)); }
    }

    public static List<String> routes() {
        List<String> result = new ArrayList<>();
        for (String group : GROUPS) {
            List<Integer> suffixes = group.equals("surface_96") ? List.of(1, 2, 9, 10)
                    : group.equals("nether_96") ? List.of(1, 3, 4, 6) : List.of(1, 2, 3, 4);
            for (int suffix : suffixes) result.add(group + "_" + String.format(java.util.Locale.ROOT, "%02d", suffix));
        }
        return List.copyOf(result);
    }

    public static int coldCount(int round, int routeIndex) {
        if (round < 1 || round > 3 || routeIndex < 0 || routeIndex >= 40) fail("cold allocation");
        return new int[][]{{2, 2, 2, 1}, {2, 2, 1, 2}, {1, 1, 2, 2}}[round - 1][routeIndex % 4];
    }

    public static List<Target> targets(Options options) {
        List<Target> result = new ArrayList<>();
        switch (options.scenario) {
            case "terrain", "M", "probe", "causality" -> queryPlan(result, options);
            case "G", "generation-cost" -> generationPlan(result, options.scenario.equals("generation-cost"));
            case "H" -> handoffPlan(result);
            case "F" -> failurePlan(result);
            case "L" -> linksPlan(result);
            case "W" -> {
                List<Target> row = shutdownPlan(options.run);
                result.add(row.get(Integer.parseInt(options.variant) - 1));
            }
            case "D06" -> add(result, "D06", Map.of("entry", options.variant));
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        if (result.stream().map(Target::id).distinct().count() != result.size()) fail("duplicate target");
        return List.copyOf(result);
    }

    private static void queryPlan(List<Target> out, Options options) {
        boolean pair = List.of("M", "probe").contains(options.scenario);
        boolean causal = options.scenario.equals("causality");
        List<String> routes = pair ? routes().subList(0, 4) : causal ? List.of("surface_512_01", "surface_512_02",
                "surface_512_03", "surface_512_04", "nether_96_06", "surface_96_01", "cave_96_01", "nether_96_01") : routes();
        for (int r = 0; r < routes.size(); r++) {
            String route = routes.get(r);
            addQuery(out, route, "first_observed", 1);
            addQuery(out, route, "warmup", causal ? 0 : 5);
            addQuery(out, route, "hot", causal ? 0 : pair ? 1000 : 100);
            if (!pair && !causal) addQuery(out, route, "cold", coldCount(options.round(), r));
        }
        if (pair) {
            if (options.scenario.equals("M") && options.variant.equals("on")) {
                product(out, "M/snapshot", dims("scale", List.of("idle", "1024", "cache-pressure")), Map.of("count", "10000"));
            }
            return;
        }
        for (String stage : STAGES) for (String dimension : List.of("overworld", "nether")) {
            for (int i = 0; i < 512; i++) {
                String route = (dimension.equals("overworld") ? "surface" : "nether") + "_96_"
                        + String.format(java.util.Locale.ROOT, "%02d", stage.equals("distributed_routes") ? i / 8 + 1 : 1);
                add(out, "pressure", Map.of("route", route, "phase", stage, "dimension", dimension, "index", Integer.toString(i)));
            }
        }
    }

    private static void addQuery(List<Target> out, String route, String phase, int count) {
        for (int index = 0; index < count; index++) add(out, "query", Map.of("route", route, "phase", phase, "index", Integer.toString(index)));
    }

    private static void generationPlan(List<Target> out, boolean costOnly) {
        if (!costOnly) product(out, "G01", dims("constructor", List.of("normal", "delegating", "imposter", "promotion", "load")), Map.of());
        product(out, "G02", dims("dimension", List.of("overworld", "nether"), "x", integers(0, 3), "z", integers(0, 3)), Map.of());
        if (costOnly) return;
        product(out, "G03", dims("section", List.of("0", "1"), "x", List.of("0", "7", "15"), "y", List.of("0", "7", "15"),
                "z", List.of("0", "7", "15"), "setter", List.of("four", "five")), Map.of("sequence", "air,stone,stone,water,air"));
        product(out, "G03/shape", dims("block", List.of("bottom_stone_slab", "oak_fence")), Map.of());
        add(out, "G04", Map.of());
        product(out, "G05", dims("version", List.of("normal", "missing", "corrupt"), "section", List.of("air", "sparse")), Map.of("writesY", "0,15"));
    }

    private static void handoffPlan(List<Target> out) {
        product(out, "H01", dims("path", PATHS), Map.of());
        product(out, "H02/baseline", dims("fault", List.of("missing", "truncated", "version", "io")), Map.of("requests", "2"));
        product(out, "H02/recovery", dims("fault", List.of("scan", "pack", "publish", "collision")), Map.of());
        product(out, "H03", dims("path", PATHS, "afterWriteFailure", List.of("retained", "evicted")), Map.of());
        product(out, "H04", dims("path", PATHS, "barrier", List.of("B1", "B2", "B3", "B4", "B5", "B6", "B7"),
                "competition", List.of("none", "v2"), "delivery", List.of("normal", "duplicate-v1", "v2-before-v1", "old-load")), Map.of());
        product(out, "H05", dims("fault", List.of("version-gap", "different-load")), Map.of());
    }

    public static List<Target> fixedFailureTargets() {
        List<Target> out = new ArrayList<>();
        failurePlan(out, true);
        return List.copyOf(out);
    }

    private static void failurePlan(List<Target> out) { failurePlan(out, false); }

    private static void failurePlan(List<Target> out, boolean fixedPriorities) {
        List<String> reasons = List.of("R", "P", "U");
        product(out, "F01/direct", dims("start", reasons, "goal", reasons, "order", List.of("start-first", "goal-first")), Map.of());
        product(out, "F01/alternate", dims("reason", reasons, "validCandidates", List.of("0", "1")), Map.of());
        for (List<String> group : List.of(List.of("R", "P"), List.of("R", "U"), List.of("P", "U"), reasons)) {
            for (List<String> placement : permutations(group)) for (List<String> notification : permutations(group)) {
                Map<String, String> fixed = Map.of("placement", String.join(",", placement), "notification", String.join(",", notification));
                List<String> priorities = fixedPriorities ? List.of("equal", "ascending", "descending") : List.of("actual");
                product(out, "F02", dims("f", priorities, "repeat", List.of("once", "twice")), fixed);
                product(out, "F03/alternative", dims("f", priorities, "repeat", List.of("once", "twice")), fixed);
            }
        }
        product(out, "F02/node-tie", dims("reason", reasons, "order", List.of("forward", "reverse")), Map.of());
        for (List<String> order : permutations(List.of("0,0,0", "1,0,0", "0,0,1"))) {
            product(out, "F02/section-tie", dims("reason", reasons), Map.of("order", String.join(";", order)));
        }
        product(out, "F03/rejected", dims("reason", reasons), Map.of());
        product(out, "F04", dims("terminal", List.of("cancel", "endpoint-unload", "stale-once", "stale-twice", "exception")), Map.of("lateFactFailure", "true"));
    }

    private static void linksPlan(List<Target> out) {
        product(out, "L01/ground", dims("direction", List.of("north", "south", "west", "east"), "distance", integers(1, 3),
                "dy", integers(-4, 1), "capability", List.of("allowed", "forbidden"),
                "placement", List.of("inside", "section-boundary", "parent-boundary")), Map.of());
        product(out, "L01/volume", dims("direction", List.of("north", "south", "west", "east", "up", "down")), Map.of());
        product(out, "L02", dims("inset", integers(0, 2), "direction", List.of("north", "south", "west", "east"), "dy", integers(-4, 1)), Map.of());
        add(out, "L02/vertical", Map.of("direction", "up", "inset", "0", "dy", "1"));
        for (int inset = 0; inset <= 2; inset++) {
            product(out, "L02/vertical", dims("dy", integers(-4, -inset - 1)),
                    Map.of("direction", "down", "inset", Integer.toString(inset)));
        }
        product(out, "L03", dims("window", LINK_WINDOWS, "change", List.of("version", "cancel", "unload")), Map.of());
        product(out, "L04/capacity", dims("keys", List.of("same", "different")), Map.of("counts", "1,1024,1025"));
        product(out, "L04/dependencies", dims("live", List.of("1", "2", "16", "17")), Map.of());
        product(out, "L04/prewarm", dims("prewarm", List.of("0", "8")), Map.of("promote", "true"));
        product(out, "L05", dims("keys", List.of("1", "4", "16")), Map.of("cycles", "10"));
    }

    public static List<Target> shutdownPlan(String run) {
        List<Target> out = new ArrayList<>();
        switch (run) {
            case "W01" -> product(out, run, dims("path", PATHS, "barrier", List.of("B1", "B2")), Map.of());
            case "W02" -> product(out, run, dims("path", PATHS, "submission", List.of("accept-race", "reject")), Map.of("barrier", "B3"));
            case "W03" -> product(out, run, dims("barrier", List.of("B4", "B5")), Map.of("path", "evicted"));
            case "W04" -> product(out, run, dims("path", PATHS, "window", List.of("B6", "writing", "B7"), "write", List.of("success", "io")), Map.of());
            case "W05" -> product(out, run, dims("window", List.of("queued", "scanning", "publishing", "queued-cancel", "queued-unload")), Map.of());
            case "W06" -> product(out, run, dims("completion", List.of("written", "failed", "coalesced")), Map.of());
            case "W07" -> {
                List<String> windows = new ArrayList<>(LINK_WINDOWS);
                windows.addAll(List.of("executor/queued", "executor/claimed"));
                product(out, run, dims("window", windows), Map.of());
            }
            case "W08" -> product(out, run, dims("window", List.of("request", "queued", "dequeued", "removed-before-flush", "completion-before-callback")), Map.of("unrelatedSave", "true"));
            case "W09" -> product(out, run, dims("fault", List.of("direct-stopped", "queue-rejection", "cleanup-exception")), Map.of());
            default -> fail("shutdown run");
        }
        return List.copyOf(out);
    }

    public static List<List<String>> permutations(List<String> values) {
        if (values.isEmpty()) return List.of(List.of());
        List<List<String>> result = new ArrayList<>();
        for (String first : values) {
            List<String> rest = new ArrayList<>(values); rest.remove(first);
            for (List<String> tail : permutations(rest)) {
                List<String> row = new ArrayList<>(List.of(first)); row.addAll(tail); result.add(List.copyOf(row));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> integers(int start, int end) {
        return IntStream.rangeClosed(start, end).mapToObj(Integer::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> dims(Object... pairs) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (List<String>) pairs[i + 1]);
        return result;
    }

    private static void product(List<Target> out, String kind, Map<String, List<String>> dimensions, Map<String, String> fixed) {
        List<Map<String, String>> rows = new ArrayList<>(List.of(new LinkedHashMap<>(fixed)));
        for (var dimension : dimensions.entrySet()) {
            List<Map<String, String>> next = new ArrayList<>();
            for (var row : rows) for (String value : dimension.getValue()) {
                Map<String, String> copy = new LinkedHashMap<>(row); copy.put(dimension.getKey(), value); next.add(copy);
            }
            rows = next;
        }
        for (Map<String, String> row : rows) add(out, kind, row);
    }

    private static void add(List<Target> out, String kind, Map<String, String> parameters) {
        StringBuilder id = new StringBuilder(kind);
        parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> id.append('/').append(entry.getKey()).append('=').append(entry.getValue()));
        out.add(new Target(id.toString(), kind, parameters));
    }

    private static void fail(String field) { throw new IllegalArgumentException("Invalid PRM-0805-1 " + field); }
}
