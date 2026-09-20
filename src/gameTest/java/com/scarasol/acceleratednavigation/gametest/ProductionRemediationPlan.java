package com.scarasol.acceleratednavigation.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/** Minimal mechanism representatives and frozen performance samples, shared by preparation, execution and readers. */
public final class ProductionRemediationPlan {
    public static final String SCHEMA = "PRM-0805-2";
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

    public record Options(String profile, String scenario, String run, String batch, String variant, String control, String family, String selection, String plan) {
        public Options(String profile, String scenario, String run, String batch, String variant, String control, String family, String selection) {
            this(profile, scenario, run, batch, variant, control, family, selection, null);
        }
        public Options(String profile, String scenario, String run, String batch, String variant, String control, String family) {
            this(profile, scenario, run, batch, variant, control, family, null);
        }
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
                int count = shutdownPlan(run).size();
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
            if (plan != null) {
                if (!profile.equals("diagnostic") || !scenario.equals("causality") || selection != null || family != null) fail("diagnostic plan combination");
                plan = DiagnosticPlan.parse(plan).json();
            }
            if (selection != null && !(List.of("formal", "fixture").contains(profile) &&
                    (scenario.equals("terrain") && selection.equals("prm03-performance-gaps")
                    || scenario.equals("L") && selection.equals("prm03-functional-gaps"))
                    || List.of("formal", "fixture").contains(profile) && scenario.equals("L") && selection.equals("after-qn-build")
                    || profile.equals("fixture") && (
                    scenario.equals("G") && selection.equals("qn-generation") || scenario.equals("L") && selection.equals("qn-build")
                    || scenario.equals("H") && List.of("qh-repairs", "qh-recovery").contains(selection)
                    || scenario.equals("F") && List.of("qf-remaining", "qf-preparation", "qf-exception-cleanup").contains(selection)
                    || scenario.equals("L") && selection.equals("ql-parent-replaced")
                    || scenario.equals("terrain") && List.of("qn-terminal", "qn-unresolved").contains(selection))
                    || profile.equals("diagnostic") && scenario.equals("causality") && List.of("qn-hot", "qn-failures", "qn-unresolved").contains(selection))) fail("target selection");
        }

        public static Options from(Map<String, String> values) {
            return new Options(values.get("profile"), values.get("case"), values.get("run"),
                    values.get("batch"), values.get("variant"), values.get("control"), values.get("family"), values.get("selection"), values.get("plan"));
        }

        public static Options configured() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : List.of("profile", "case", "run", "batch", "variant", "control", "family", "selection", "plan")) {
                values.put(key, System.getProperty("acceleratedNavigation.validation." + key));
            }
            return from(values);
        }

        public String suffix() { return batch + "/" + run + "/" + scenario + (variant == null ? "" : "/" + variant)
                + (family == null ? "" : "/" + family) + (selection == null ? "" : "/" + selection); }
        public boolean usesFrozenWorld() { return List.of("terrain", "M", "probe", "causality").contains(scenario); }
        public int round() { return Integer.parseInt(run.substring(1)); }
        public Map<String, String> parameters() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("profile", profile); result.put("case", scenario); result.put("run", run); result.put("batch", batch);
            if (variant != null) result.put("variant", variant);
            if (control != null) result.put("control", control);
            if (family != null) result.put("family", family);
            if (selection != null) result.put("selection", selection);
            if (plan != null) result.put("plan", plan);
            return result;
        }
        public List<String> command() {
            List<String> args = new ArrayList<>(List.of(".\\gradlew.bat", "runTerrainBenchmarkServer", "-PterrainTestMode=benchmark"));
            parameters().forEach((key, value) -> { if (key.equals("plan")) value = "build/reports/production-remediation/" + suffix() + "/plan.json"; args.add("-PterrainValidation" + Character.toUpperCase(key.charAt(0)) + key.substring(1) + "=" + value); });
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
        if ("qn-generation".equals(options.selection)) result.removeIf(target -> !List.of("G02", "G04", "G05").contains(target.kind));
        if ("qn-build".equals(options.selection)) result.removeIf(target -> !qnBuildTarget(target));
        if ("prm03-functional-gaps".equals(options.selection)) result.removeIf(target -> !functionalGap(target));
        if ("after-qn-build".equals(options.selection)) result.removeIf(ProductionRemediationPlan::qnBuildTarget);
        if ("qh-repairs".equals(options.selection)) result.removeIf(target -> !List.of("H01", "H02/baseline", "H02/recovery", "H03").contains(target.kind)
                && !target.id.equals("H04/barrier=B1/competition=none/delivery=normal/path=memory"));
        if ("qh-recovery".equals(options.selection)) result.removeIf(target -> !target.kind.equals("H02/recovery"));
        if ("qf-remaining".equals(options.selection)) result.removeIf(target -> Set.of(
                "F01/direct/goal=P/order=goal-first/start=R",
                "F01/alternate/reason=R/validCandidates=0",
                "F01/alternate/reason=R/validCandidates=1").contains(target.id));
        if ("ql-parent-replaced".equals(options.selection)) result.removeIf(target ->
                !target.id.equals("L03/change=version/window=parent-boundary/replaced"));
        if ("qf-preparation".equals(options.selection)) result.removeIf(target ->
                !target.id.equals("F02/f=actual/notification=P,R/placement=R,P/repeat=twice"));
        if ("qf-exception-cleanup".equals(options.selection)) {
            Target target = result.stream().filter(t -> t.kind.equals("F02")).findFirst().orElseThrow();
            var parameters = new LinkedHashMap<>(target.parameters); parameters.put("qualification", "harness-exception");
            result.clear(); add(result, target.kind, parameters);
        }
        return List.copyOf(result);
    }

    public static List<String> performanceRoutes() { return routes().stream().filter(route -> !List.of("nether_8_02", "nether_8_03").contains(route)).toList(); }
    public static List<String> performanceColdGroups() { return List.of("surface_8", "surface_64", "cave_64"); }

    private static boolean functionalGap(Target t) {
        return java.util.Set.of(
                "L01/ground/capability=allowed/direction=south/distance=1/dy=1/placement=inside",
                "L01/ground/capability=forbidden/direction=east/distance=3/dy=0/placement=section-boundary",
                "L01/ground/capability=allowed/direction=south/distance=1/dy=1/placement=section-boundary",
                "L01/volume/direction=south",
                "L03/change=version/window=parent-boundary/replaced",
                "L03/change=version/window=corridor/final-validation",
                "L04/dependencies/live=1",
                "L04/dependencies/live=2",
                "L04/dependencies/live=16",
                "L04/dependencies/live=17",
                "L04/prewarm/prewarm=0/promote=true",
                "L04/prewarm/prewarm=8/promote=true",
                "L05/cycles=10/keys=1",
                "L05/cycles=10/keys=4",
                "L05/cycles=10/keys=16"
        ).contains(t.id);
    }

    private static boolean qnBuildTarget(Target target) {
        return target.kind.equals("L03") && "version".equals(target.parameters.get("change"))
                && List.of("parent/captured", "parent/publishing", "base-boundary/captured", "base-boundary/publishing",
                "parent-boundary/captured", "parent-boundary/publishing").contains(target.parameters.get("window"));
    }

    private static void queryPlan(List<Target> out, Options options) {
        if (options.plan != null) { DiagnosticPlan.parse(options.plan).expand(out); return; }
        if ("prm03-performance-gaps".equals(options.selection)) {
            for (String route : performanceRoutes()) {
                addQuery(out, route, "first_observed", 1); addQuery(out, route, "warmup", 5); addQuery(out, route, "hot", 100);
                if (performanceColdGroups().contains(route.substring(0, route.lastIndexOf('_')))) addQuery(out, route, "cold", coldCount(options.round(), routes().indexOf(route)));
            }
            return;
        }
        if ("qn-unresolved".equals(options.selection)) {
            addQuery(out, "surface_512_01", "first_observed", 1); addQuery(out, "surface_512_01", "cold", 2);
            addQuery(out, "surface_96_23", "first_observed", 1); addQuery(out, "surface_96_23", "cold", 1);
            addQuery(out, "nether_96_10", "first_observed", 1); addQuery(out, "nether_96_51", "first_observed", 1);
            return;
        }
        if ("qn-terminal".equals(options.selection)) {
            addQuery(out, "nether_64_04", "first_observed", 1); addQuery(out, "nether_64_04", "warmup", 5);
            addQuery(out, "nether_64_04", "hot", 2); return;
        }
        if ("qn-hot".equals(options.selection)) {
            for (String route : List.of("nether_64_01", "nether_64_02", "nether_64_03", "nether_64_04")) {
                addQuery(out, route, "first_observed", 1); addQuery(out, route, "warmup", 5); addQuery(out, route, "hot", 100);
            }
            return;
        }
        boolean pair = List.of("M", "probe").contains(options.scenario);
        boolean causal = options.scenario.equals("causality");
        List<String> routes = pair ? routes().subList(0, 4) : causal ? List.of("surface_512_01", "surface_512_02",
                "surface_512_03", "surface_512_04", "nether_96_06", "surface_96_01", "cave_96_01", "nether_96_01") : routes();
        for (int r = 0; r < routes.size(); r++) {
            String route = routes.get(r);
            addQuery(out, route, "first_observed", 1);
            addQuery(out, route, "warmup", causal ? 0 : 5);
            addQuery(out, route, "hot", causal ? 0 : pair ? 1000 : 100);
            if ("qn-failures".equals(options.selection)) addQuery(out, route, "cold", 2);
            else if (!pair && !causal) addQuery(out, route, "cold", coldCount(options.round(), r));
        }
        if (pair) {
            if (options.scenario.equals("M") && options.variant.equals("on")) {
                product(out, "M/snapshot", dims("scale", List.of("idle", "1024", "cache-pressure")), Map.of("count", "10000"));
            }
            return;
        }
        pressurePlan(out);
    }

    private static void pressurePlan(List<Target> out) {
        for (String stage : STAGES) for (String dimension : List.of("overworld", "nether")) {
            for (int i = 0; i < 512; i++) {
                String route = (dimension.equals("overworld") ? "surface" : "nether") + "_96_"
                        + String.format(java.util.Locale.ROOT, "%02d", stage.equals("distributed_routes") ? i / 8 + 1 : 1);
                add(out, "pressure", Map.of("route", route, "phase", stage, "dimension", dimension, "index", Integer.toString(i)));
            }
        }
    }

    /** Finite startup recipe. The archived JSON is the input for planning, execution and reading. */
    public record DiagnosticPlan(com.google.gson.JsonObject value) {
        private static final List<String> OBSERVERS = List.of("endpoint", "build", "search", "final-validation");
        public static DiagnosticPlan parse(String json) {
            var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            fields(obj, List.of("schema", "recipe", "routes", "comparison", "observers", "pressure"));
            if (!"PRM-DIAGNOSTIC-PLAN-1".equals(text(obj, "schema"))) fail("plan schema");
            String recipe = text(obj, "recipe");
            if (!List.of("sequence", "observation-comparison").contains(recipe)) fail("recipe");
            if (!List.of("none", "three-stages").contains(text(obj, "pressure"))) fail("pressure recipe");
            var routes = obj.getAsJsonArray("routes");
            if (routes == null || routes.isEmpty() || routes.size() > 200) fail("plan routes");
            var seen = new java.util.HashSet<String>();
            for (var item : routes) {
                var route = item.getAsJsonObject(); fields(route, List.of("id", "first", "warmup", "hot", "cold"));
                String id = text(route, "id");
                if (id == null || !id.matches("(surface|cave|nether)_[0-9]+_[0-9]{2}") || !seen.add(id)) fail("route id");
                for (String key : List.of("first", "warmup")) count(route, key, 1000);
                if (count(route, "first", 1) != 1) fail("first position");
                if (recipe.equals("sequence")) { count(route, "hot", 1000); count(route, "cold", 1000); }
                else if (route.has("hot") || route.has("cold")) fail("comparison route counts");
            }
            var observers = obj.getAsJsonArray("observers");
            if (observers == null) fail("observers");
            seen.clear();
            for (var item : observers) if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !OBSERVERS.contains(item.getAsString()) || !seen.add(item.getAsString())) fail("observer package");
            if (recipe.equals("observation-comparison")) {
                var expected = com.google.gson.JsonParser.parseString("{\"modes\":[\"A\",\"A\",\"B\",\"B\",\"A\",\"A\"],\"requestsPerBlock\":20,\"factor\":\"measured-window-derived-dependency-capture\",\"A\":\"enabled\",\"B\":\"disabled\",\"beforeSnapshot\":\"full-in-both-modes\",\"BQualification\":\"diagnostic-only\"}");
                var complementary = expected.deepCopy().getAsJsonObject();
                complementary.add("modes", com.google.gson.JsonParser.parseString("[\"B\",\"B\",\"A\",\"A\",\"B\",\"B\"]"));
                if ((!expected.equals(obj.get("comparison")) && !complementary.equals(obj.get("comparison")))
                        || !observers.isEmpty() || !text(obj, "pressure").equals("none")) fail("comparison factors");
            } else if (obj.has("comparison")) fail("unexpected comparison");
            var plan = new DiagnosticPlan(obj); var targets = new ArrayList<Target>(); plan.expand(targets);
            if (targets.size() > 10000) fail("diagnostic position budget");
            return plan;
        }
        public String json() { return value.toString(); }
        public List<String> observers() { return java.util.stream.StreamSupport.stream(value.getAsJsonArray("observers").spliterator(), false).map(com.google.gson.JsonElement::getAsString).toList(); }
        public void validateRoutes(com.google.gson.JsonObject manifest) {
            var available = new java.util.HashMap<String, TerrainRouteManifest.Route>();
            var gson = new com.google.gson.Gson();
            for (var item : manifest.getAsJsonArray("routes")) {
                var route = gson.fromJson(item, TerrainRouteManifest.Route.class);
                if (available.putIfAbsent(route.name, route) != null) fail("duplicate manifest route");
            }
            for (var route : value.getAsJsonArray("routes")) {
                String id = text(route.getAsJsonObject(), "id");
                String issue = TerrainRouteManifest.routeIssue(available.get(id));
                if (issue != null) fail("invalid manifest route " + id + ": " + issue);
            }
        }
        void expand(List<Target> out) {
            for (var item : value.getAsJsonArray("routes")) {
                var r = item.getAsJsonObject(); String route = text(r, "id");
                addQuery(out, route, "first_observed", count(r, "first", 1)); addQuery(out, route, "warmup", count(r, "warmup", 1000));
                if (text(value, "recipe").equals("sequence")) {
                    addQuery(out, route, "hot", count(r, "hot", 1000)); addQuery(out, route, "cold", count(r, "cold", 1000));
                } else for (int block = 0; block < 6; block++) for (int i = 0; i < 20; i++)
                    add(out, "query", Map.of("route", route, "phase", "hot", "index", Integer.toString(block * 20 + i), "block", Integer.toString(block), "mode", value.getAsJsonObject("comparison").getAsJsonArray("modes").get(block).getAsString()));
            }
            if (text(value, "pressure").equals("three-stages")) pressurePlan(out);
        }
        private static int count(com.google.gson.JsonObject obj, String key, int maximum) {
            var v = obj.getAsJsonPrimitive(key);
            if (v == null || !v.isNumber()) { fail("missing integral count " + key); return 0; }
            int result = v.getAsBigDecimal().intValueExact();
            if (result < 0 || result > maximum) fail("count " + key); return result;
        }
        private static String text(com.google.gson.JsonObject obj, String key) {
            var value = obj.getAsJsonPrimitive(key); return value != null && value.isString() ? value.getAsString() : null;
        }
        private static void fields(com.google.gson.JsonObject obj, List<String> keys) { if (!keys.containsAll(obj.keySet())) fail("unknown plan field"); }
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
        // Each ownership boundary once; replay and old-load rejection have separate representatives.
        for (String barrier : List.of("B1", "B2", "B3", "B4", "B5", "B6", "B7")) {
            add(out, "H04", Map.of("path", List.of("B4", "B5").contains(barrier) ? "evicted" : "memory",
                    "barrier", barrier, "competition", "none", "delivery", "normal"));
        }
        for (String path : PATHS) {
            add(out, "H04", Map.of("path", path, "barrier", "B6", "competition", "v2", "delivery", "duplicate-v1"));
        }
        add(out, "H04", Map.of("path", "evicted", "barrier", "B7", "competition", "none", "delivery", "old-load"));
        product(out, "H05", dims("fault", List.of("version-gap", "different-load")), Map.of());
    }

    public static List<Target> fixedFailureTargets() {
        List<Target> out = new ArrayList<>();
        failurePlan(out, true);
        return List.copyOf(out);
    }

    private static void failurePlan(List<Target> out) {
        List<String> reasons = List.of("R", "P", "U");
        for (int i = 0; i < reasons.size(); i++) {
            String reason = reasons.get(i), next = reasons.get((i + 1) % reasons.size());
            add(out, "F01/direct", Map.of("start", reason, "goal", next, "order", "goal-first"));
            product(out, "F01/alternate", dims("validCandidates", List.of("0", "1")), Map.of("reason", reason));
            for (String kind : List.of("F02", "F03/alternative")) {
                add(out, kind, Map.of("placement", reason + "," + next, "notification", next + "," + reason,
                        "f", "actual", "repeat", "twice"));
            }
            add(out, "F03/rejected", Map.of("reason", reason));
        }
        for (String kind : List.of("F02", "F03/alternative")) {
            add(out, kind, Map.of("placement", "R,P,U", "notification", "U,P,R", "f", "actual", "repeat", "twice"));
        }
        add(out, "F02/node-tie", Map.of("reason", "R", "order", "reverse"));
        add(out, "F02/section-tie", Map.of("reason", "P", "order", "0,0,1;1,0,0;0,0,0"));
        product(out, "F04", dims("terminal", List.of("cancel", "endpoint-unload", "stale-once", "stale-twice", "exception")), Map.of("lateFactFailure", "true"));
    }

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
        // Geometry permutations belong to the existing core tests; live cases verify each integration path.
        for (String placement : List.of("inside", "section-boundary", "parent-boundary")) {
            for (int dy : List.of(-4, 0, 1)) for (String capability : List.of("allowed", "forbidden")) {
                add(out, "L01/ground", Map.of("direction", dy == -4 ? "north" : dy == 0 ? "east" : "south",
                        "distance", dy == 0 ? "3" : "1", "dy", Integer.toString(dy), "capability", capability, "placement", placement));
            }
        }
        product(out, "L01/volume", dims("direction", List.of("north", "south", "west", "east", "up", "down")), Map.of());
        for (int inset = 0; inset <= 2; inset++) {
            add(out, "L02", Map.of("inset", Integer.toString(inset), "direction", inset == 1 ? "west" : "east", "dy", "0"));
            add(out, "L02/vertical", Map.of("direction", "down", "inset", Integer.toString(inset), "dy", Integer.toString(-inset - 1)));
        }
        add(out, "L02/vertical", Map.of("direction", "up", "inset", "0", "dy", "1"));
        product(out, "L03", dims("window", LINK_WINDOWS), Map.of("change", "version"));
        add(out, "L03", Map.of("window", "base/captured", "change", "cancel"));
        add(out, "L03", Map.of("window", "parent-boundary/publishing", "change", "unload"));
        product(out, "L04/capacity", dims("keys", List.of("same", "different")), Map.of("counts", "1,1024,1025"));
        product(out, "L04/dependencies", dims("live", List.of("1", "2", "16", "17")), Map.of());
        product(out, "L04/prewarm", dims("prewarm", List.of("0", "8")), Map.of("promote", "true"));
        product(out, "L05", dims("keys", List.of("1", "4", "16")), Map.of("cycles", "10"));
    }

    public static List<Target> shutdownPlan(String run) {
        List<Target> out = new ArrayList<>();
        switch (run) {
            case "W01" -> {
                add(out, run, Map.of("path", "memory", "barrier", "B1"));
                add(out, run, Map.of("path", "evicted", "barrier", "B2"));
            }
            case "W02" -> {
                add(out, run, Map.of("path", "memory", "submission", "accept-race", "barrier", "B3"));
                add(out, run, Map.of("path", "evicted", "submission", "reject", "barrier", "B3"));
            }
            case "W03" -> product(out, run, dims("barrier", List.of("B4", "B5")), Map.of("path", "evicted"));
            case "W04" -> {
                add(out, run, Map.of("path", "memory", "window", "B6", "write", "success"));
                add(out, run, Map.of("path", "evicted", "window", "writing", "write", "io"));
                add(out, run, Map.of("path", "evicted", "window", "B7", "write", "success"));
            }
            case "W05" -> product(out, run, dims("window", List.of("queued", "queued-cancel", "queued-unload")), Map.of());
            case "W06" -> product(out, run, dims("completion", List.of("written", "failed", "coalesced")), Map.of());
            case "W07" -> {
                List<String> windows = new ArrayList<>(List.of("base/captured", "parent/replaced", "base-boundary/captured",
                        "parent-boundary/publishing", "corridor/final-validation"));
                windows.addAll(List.of("executor/queued", "executor/claimed"));
                product(out, run, dims("window", windows), Map.of());
            }
            case "W08" -> product(out, run, dims("window", List.of("request", "queued", "dequeued", "removed-before-flush")), Map.of("unrelatedSave", "true"));
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
