package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/** Bounded raw evidence and strict derived readers; contains no production behavior. */
public final class ProductionRemediationReport {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    static final long COMPACT_LIMIT = 32L << 20;
    static final long STREAM_LIMIT = 128L << 20;
    private static final Set<String> SAMPLE_OUTCOMES = Set.of("SUCCESS", "BUSINESS_FAILURE", "EXCEPTION", "TIMEOUT", "CANCELLED", "NOT_ENTERED");
    private static final Set<String> CONTROL_OUTCOMES = Set.of("PASS", "FAIL", "NOT_ENTERED", "NOT_APPLICABLE");
    private ProductionRemediationReport() { }

    public static void shutdownStarted() {
        var process = ProcessHandle.current();
        Path output = Path.of(System.getProperty("acceleratedNavigation.validation.output"));
        Map<String, Object> marker = Map.of("observerId", System.getProperty("acceleratedNavigation.validation.observer"),
                "processId", process.pid(), "processStartedAt", process.info().startInstant().orElseThrow().toEpochMilli(),
                "shutdownStartedAt", System.currentTimeMillis());
        try {
            Path temporary = output.resolve("shutdown-start.json.tmp");
            writeNew(temporary, marker, COMPACT_LIMIT);
            Files.move(temporary, output.resolve("shutdown-start.json"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("prepare|finish projectRoot outputDirectory [workDirectory optionsJson]");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "prepare" -> {
                if (args.length != 5) throw new IllegalArgumentException("prepare arguments");
                Map<String, String> options = new LinkedHashMap<>();
                JsonParser.parseString(args[4]).getAsJsonObject().entrySet().forEach(entry -> {
                    if (!entry.getValue().isJsonNull()) options.put(entry.getKey(), entry.getValue().getAsString());
                });
                prepare(root, output, Path.of(args[3]), ProductionRemediationPlan.Options.from(options));
            }
            case "finish" -> finish(root, output);
            default -> throw new IllegalArgumentException("unknown report operation");
        }
    }

    static void prepare(Path root, Path output, Path work, ProductionRemediationPlan.Options options) throws IOException {
        Path expected = root.resolve("build/reports/production-remediation").resolve(options.suffix()).normalize();
        if (!expected.equals(output) || !work.toAbsolutePath().normalize().equals(root.resolve("run-production-remediation").resolve(options.suffix()))) {
            throw new IOException("Validation output or working path does not match the frozen parameters");
        }
        if (Files.exists(output)) throw new IOException("Validation output already exists: " + output);
        requireHash(root.resolve(ProductionRemediationPlan.MANIFEST), ProductionRemediationPlan.MANIFEST_HASH);
        requireHash(root.resolve(ProductionRemediationPlan.BASELINE), ProductionRemediationPlan.BASELINE_HASH);
        if (!Files.readString(root.resolve("terrain-qualification-input/real-terrain-qualification.json.current")).trim().equals("a")) {
            throw new IOException("Qualification pointer is not a");
        }
        validateBaseline(readObject(root.resolve(ProductionRemediationPlan.BASELINE)));
        List<Map<String, Object>> frozen = frozenInputs(root);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schemaVersion", ProductionRemediationPlan.SCHEMA);
        identity.putAll(options.parameters());
        identity.put("command", options.command());
        identity.put("preparedAt", Instant.now().toString());
        identity.put("inputs", inputIdentity(root));
        identity.put("frozenInputs", frozen);
        identity.put("workingDirectory", work.toAbsolutePath().normalize().toString());
        identity.put("targets", ProductionRemediationPlan.targets(options));
        Map<String, Object> preparedEnvironment = environment(true);
        List<String> environmentIssues = environmentIssues(JSON.toJsonTree(preparedEnvironment).getAsJsonObject());
        identity.put("preparationEnvironment", preparedEnvironment);
        identity.put("environmentIssues", environmentIssues);
        List<Map<String, Object>> binaries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path file = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isRegularFile(file)) binaries.add(Map.of("path", file.toString(), "bytes", Files.size(file), "sha256", hash(file)));
        }
        identity.put("resolvedBinaryInputs", binaries);
        Path skill = Path.of("C:/Users/Administrator/.codex/skills/grilling/SKILL.md");
        identity.put("skillInputs", List.of(Map.of("path", skill.toString(), "sha256", hash(skill))));
        identity.put("historyEnvironmentDifference", "0905 JAVA_TOOL_OPTIONS was empty; this instance records the actual value");
        Files.createDirectories(output);
        writeNew(output.resolve("identity.json"), identity, COMPACT_LIMIT);
        if (!environmentIssues.isEmpty()) throw new IOException("Frozen environment mismatch: " + environmentIssues);
    }

    private static List<Map<String, Object>> frozenInputs(Path root) throws IOException {
        JsonArray previous = JsonParser.parseString(Files.readString(root.resolve(
                "build/reports/production-causality-diagnostic-20260906/pre-state.json"))).getAsJsonArray();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement item : previous) {
            JsonObject row = item.getAsJsonObject();
            String relative = row.get("path").getAsString().replace('\\', '/');
            if (!relative.startsWith("terrain-qualification-input/")) continue;
            Path file = within(root, relative);
            requireHash(file, row.get("sha256").getAsString());
            result.add(fileIdentity(root, file));
        }
        long worlds = result.stream().filter(row -> row.get("path").toString().contains("/world/")).count();
        if (worlds != 36) throw new IOException("Frozen world input count is " + worlds + ", expected 36");
        result.add(fileIdentity(root, root.resolve(ProductionRemediationPlan.BASELINE)));
        return result;
    }

    static List<Map<String, Object>> inputIdentity(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String directory : List.of("src/main", "src/test", "src/gameTest", "docs/agent-process/audit", "docs/agent-process/testing")) {
            try (var files = Files.walk(root.resolve(directory))) { files.filter(Files::isRegularFile).forEach(paths::add); }
        }
        for (String file : List.of("AGENTS.md", "build.gradle", "settings.gradle", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
                "docs/agent-process/00-evidence-contract.md", "docs/agent-process/01-requirements.md", "docs/agent-process/02-implementation.md",
                "docs/agent-process/03-audit.md", "docs/agent-process/04-testing.md",
                "docs/specs/2026-08-05-hot-query-and-short-cold-remediation-design-zh.md",
                "docs/specs/2026-09-05-production-remediation-exit-objects-zh.md",
                "docs/specs/2026-09-06-production-remediation-formal-test-inputs-zh.md")) paths.add(root.resolve(file));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path path : paths.stream().distinct().sorted().toList()) result.add(fileIdentity(root, path));
        return result;
    }

    public static Map<String, Object> environment() throws IOException {
        return environment(System.getProperty("acceleratedNavigation.validation.profile") != null);
    }

    private static Map<String, Object> environment(boolean hostRequired) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("java.version", "java.runtime.version", "java.vendor", "java.vm.name", "java.home", "os.name", "os.version", "os.arch")) {
            result.put(name, System.getProperty(name));
        }
        result.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("pid", ProcessHandle.current().pid());
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("collectors", ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean -> bean.getName()).toList());
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : List.of("GRADLE_USER_HOME", "TEMP", "TMP", "JAVA_TOOL_OPTIONS")) env.put(key, System.getenv(key));
        result.put("environment", env);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (Files.isRegularFile(java)) result.put("javaBinarySHA256", hash(java));
        if (hostRequired) result.put("host", hostEnvironment());
        return result;
    }

    private static JsonObject hostEnvironment() throws IOException {
        String script = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                + "$validationCpu=Get-CimInstance Win32_Processor | Select-Object -First 1; "
                + "$validationOs=Get-CimInstance Win32_OperatingSystem; "
                + "[ordered]@{processor=$validationCpu.Name.Trim(); cores=$validationCpu.NumberOfCores; "
                + "logicalProcessors=$validationCpu.NumberOfLogicalProcessors; osVersion=$validationOs.Version; "
                + "visibleMemoryKiB=$validationOs.TotalVisibleMemorySize; "
                + "powerScheme=(& powercfg.exe /GETACTIVESCHEME | Out-String).Trim(); "
                + "processes=@(Get-Process | Select-Object Id,ProcessName)} | ConvertTo-Json -Depth 4 -Compress";
        Path capture = Files.createTempFile("topology-host-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).redirectOutput(capture.toFile()).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Host identity collection timed out"); }
            String output = Files.readString(capture, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException("Host identity collection failed: " + output);
            return JsonParser.parseString(output).getAsJsonObject();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Host identity collection interrupted", interrupted);
        } catch (RuntimeException malformed) { throw new IOException("Malformed host identity", malformed); }
        finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.onExit().orTimeout(5, TimeUnit.SECONDS).join(); }
                catch (java.util.concurrent.CompletionException failure) { throw new IOException("Host identity process did not stop", failure); }
            }
            Files.deleteIfExists(capture);
        }
    }

    static List<String> environmentIssues(JsonObject environment) {
        List<String> issues = new ArrayList<>();
        if (environment == null) return List.of("Runtime environment missing");
        String runtime = string(environment, "java.runtime.version"), vendor = string(environment, "java.vendor");
        if (runtime == null || !(runtime.equals("17.0.18+8") || runtime.startsWith("17.0.18+8-"))
                || !"Eclipse Adoptium".equals(vendor)) issues.add("Temurin 17.0.18+8 required");
        if (string(environment, "javaBinarySHA256") == null) issues.add("Java executable hash missing");
        try {
            JsonObject host = environment.getAsJsonObject("host"), env = environment.getAsJsonObject("environment");
            if (!string(host, "processor").contains("AMD Ryzen 9 8945HX") || exactLong(host, "cores") != 16
                    || exactLong(host, "logicalProcessors") != 32) issues.add("Frozen processor mismatch");
            if (!"10.0.26200".equals(string(host, "osVersion")) || exactLong(host, "visibleMemoryKiB") != 16040484L) issues.add("Frozen OS or visible memory mismatch");
            if (string(host, "powerScheme") == null || string(host, "powerScheme").isBlank() || !host.has("processes")) issues.add("Power scheme or process inventory missing");
            for (var expected : Map.of("GRADLE_USER_HOME", "D:/Library/.gradle", "TEMP", "C:/Temp", "TMP", "C:/Temp").entrySet()) {
                String value = string(env, expected.getKey());
                if (value == null || !value.replace('\\', '/').equalsIgnoreCase(expected.getValue())) issues.add("Environment mismatch: " + expected.getKey());
            }
            if (!"-Djdk.net.unixdomain.tmpdir=C:/Temp".equals(string(env, "JAVA_TOOL_OPTIONS"))) issues.add("JAVA_TOOL_OPTIONS mismatch");
        } catch (RuntimeException missing) { issues.add("Host or environment identity incomplete"); }
        return issues;
    }

    public static final class Journal implements AutoCloseable {
        private final Path output;
        private final ProductionRemediationPlan.Options options;
        private final Map<String, ProductionRemediationPlan.Target> plan = new LinkedHashMap<>();
        private final Set<String> terminal = new HashSet<>();
        private final Stream samples;
        private final Stream controls;
        private boolean closed;

        public Journal(Path output, ProductionRemediationPlan.Options options) throws IOException {
            this.output = output; this.options = options;
            if (!options.profile().equals("diagnostic") && (Boolean.getBoolean("acceleratedNavigation.terrainCausality")
                    || Boolean.getBoolean("acceleratedNavigation.terrainProbe"))) throw new IOException("Detailed diagnostics cannot enter a formal or fixture run");
            JsonObject identity = readObject(output.resolve("identity.json"));
            if (!options.equals(options(identity))) throw new IOException("Prepared identity differs from JVM parameters");
            for (var target : ProductionRemediationPlan.targets(options)) plan.put(target.id(), target);
            samples = new Stream(output.resolve("samples.jsonl"), STREAM_LIMIT);
            controls = new Stream(output.resolve("controls.jsonl"), STREAM_LIMIT);
            controlEvent(Map.of("event", "runtime-environment", "environment", environment()));
        }

        public synchronized void sample(ProductionRemediationPlan.Target target, Map<String, Object> evidence) {
            emitTerminal(target, evidence, true);
        }

        public synchronized void control(ProductionRemediationPlan.Target target, Map<String, Object> evidence) {
            emitTerminal(target, evidence, false);
        }

        private void emitTerminal(ProductionRemediationPlan.Target target, Map<String, Object> evidence, boolean sample) {
            if (closed || !target.equals(plan.get(target.id()))) throw new IllegalStateException("Unknown or closed target: " + target.id());
            String outcome = String.valueOf(evidence.get("outcome"));
            if (!(sample ? SAMPLE_OUTCOMES : CONTROL_OUTCOMES).contains(outcome)) throw new IllegalArgumentException("Invalid terminal " + outcome);
            if (!terminal.add(target.id())) throw new IllegalStateException("Duplicate terminal " + target.id());
            Map<String, Object> row = new LinkedHashMap<>(evidence);
            row.put("id", target.id()); row.put("kind", target.kind()); row.put("parameters", target.parameters()); row.put("event", "terminal");
            (sample ? samples : controls).append(row);
        }

        public synchronized void controlEvent(Map<String, Object> evidence) {
            if (closed) throw new IllegalStateException("Journal is closed");
            controls.append(evidence);
        }

        public synchronized void finish(Map<String, Object> compact, String missingReason) throws IOException {
            if (closed) return;
            for (var target : plan.values()) if (!terminal.contains(target.id())) {
                boolean sample = target.kind().equals("query") || target.kind().equals("pressure");
                emitTerminal(target, Map.of("outcome", "NOT_ENTERED", "reason", missingReason), sample);
            }
            close();
            Map<String, Object> result = new LinkedHashMap<>(compact);
            result.put("schemaVersion", ProductionRemediationPlan.SCHEMA);
            result.put("profile", options.profile());
            result.put("case", options.scenario());
            result.put("observationPolicy", Map.of("detailedDiagnostics", options.profile().equals("diagnostic"),
                    "directObserverCost", "reported separately; never subtracted from request latency",
                    "unmeasuredEffects", "GC, cache and scheduling perturbation are not bounded by direct observer timing"));
            result.put("rawEvidence", Map.of("samples", samples.reference(), "controls", controls.reference()));
            result.put("streamTruncated", samples.truncated || controls.truncated);
            long remaining = COMPACT_LIMIT - Files.size(output.resolve("identity.json"));
            writeNew(output.resolve("result.json"), result, remaining);
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { samples.close(); } finally { controls.close(); }
        }
    }

    static final class Stream implements AutoCloseable {
        private final Path path;
        private final long limit;
        private final BufferedWriter writer;
        private long bytes, records;
        private boolean truncated;
        Stream(Path path, long limit) throws IOException {
            this.path = path; this.limit = limit;
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        synchronized void append(Map<String, Object> row) {
            String line = JSON.toJson(row) + "\n";
            long size = line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > limit) { truncated = true; return; }
            try { writer.write(line); writer.flush(); bytes += size; records++; }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        Map<String, Object> reference() throws IOException {
            return Map.of("path", path.getFileName().toString(), "bytes", bytes, "records", records, "sha256", hash(path), "truncated", truncated);
        }
        @Override public void close() throws IOException { writer.close(); }
    }

    static void finish(Path root, Path output) throws IOException {
        JsonObject identity = readObject(output.resolve("identity.json"));
        List<String> identityIssues = new ArrayList<>();
        checkFiles(root, identity.getAsJsonArray("inputs"), identityIssues);
        checkFiles(root, identity.getAsJsonArray("frozenInputs"), identityIssues);
        checkAbsoluteFiles(identity.getAsJsonArray("resolvedBinaryInputs"), identityIssues);
        checkAbsoluteFiles(identity.getAsJsonArray("skillInputs"), identityIssues);
        if (!JSON.toJsonTree(inputIdentity(root)).equals(identity.get("inputs"))) identityIssues.add("Input membership changed");
        Map<String, Object> assessment = assess(output, identity);
        assessment.put("identityIssues", identityIssues);
        if (!identityIssues.isEmpty()) assessment.put("status", "INCOMPLETE");
        replaceDerived(output.resolve("assessment.json"), assessment);
        aggregate(root, options(identity));
    }

    static Map<String, Object> assess(Path output, JsonObject identity) throws IOException {
        var options = options(identity);
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        List<String> expectedMisses = new ArrayList<>();
        if (!ProductionRemediationPlan.SCHEMA.equals(string(identity, "schemaVersion"))) missing.add("Identity schema mismatch");
        Map<String, ProductionRemediationPlan.Target> expected = new LinkedHashMap<>();
        for (var target : ProductionRemediationPlan.targets(options)) expected.put(target.id(), target);
        if (!JSON.toJsonTree(expected.values()).equals(identity.get("targets"))) missing.add("Frozen target inventory missing or changed");
        Map<String, JsonObject> terminals = new LinkedHashMap<>();
        Map<String, JsonObject> stateProofs = new HashMap<>();
        List<JsonObject> runtimeEnvironments = new ArrayList<>();
        for (String stream : List.of("samples", "controls")) {
            Path file = output.resolve(stream + ".jsonl");
            if (!Files.isRegularFile(file)) { missing.add(stream + " missing"); continue; }
            readEvidence(file, missing, row -> {
                if (stream.equals("controls") && "runtime-environment".equals(string(row, "event")) && row.has("environment")) runtimeEnvironments.add(row.getAsJsonObject("environment"));
                if (stream.equals("controls") && "sample-state".equals(string(row, "event"))) {
                    String id = string(row, "id");
                    if (!expected.containsKey(id)) failures.add("Unknown state proof: " + id);
                    else if (stateProofs.putIfAbsent(id, row) != null) failures.add("Duplicate state proof: " + id);
                }
                if (!"terminal".equals(string(row, "event"))) return;
                String id = string(row, "id");
                var target = expected.get(id);
                if (target == null) { failures.add("Unknown target: " + id); return; }
                boolean sample = target.kind().equals("query") || target.kind().equals("pressure");
                if (sample != stream.equals("samples")) failures.add("Wrong terminal stream: " + id);
                if (!target.kind().equals(string(row, "kind"))) failures.add("Target kind changed: " + id);
                if (!JSON.toJsonTree(target.parameters()).equals(row.get("parameters"))) failures.add("Target parameters changed: " + id);
                if (terminals.putIfAbsent(id, row) != null) failures.add("Duplicate terminal: " + id);
                String outcome = string(row, "outcome");
                if ("missed".equals(options.control()) && (!"NOT_ENTERED".equals(outcome)
                        || !"FIXTURE_TARGET_SKIPPED".equals(string(row, "reason")))) failures.add("Missed fixture executed a target: " + id);
                if (!(sample ? SAMPLE_OUTCOMES : CONTROL_OUTCOMES).contains(outcome)) missing.add("Invalid terminal: " + id);
                if ("NOT_ENTERED".equals(outcome)) {
                    if ("missed".equals(options.control()) && "FIXTURE_TARGET_SKIPPED".equals(string(row, "reason"))) expectedMisses.add(id);
                    else missing.add(id + ":" + string(row, "reason"));
                }
                else if (sample && !"SUCCESS".equals(outcome) || !sample && "FAIL".equals(outcome)) failures.add(id + ":" + outcome);
                if (!sample && "NOT_ENTERED".equals(outcome) && "FAIL".equals(string(row, "originalOutcome"))) failures.add(id + ":FAIL before cleanup");
                if (sample && !"NOT_ENTERED".equals(outcome)) {
                    validateSample(row, missing, failures);
                    if ("mutated".equals(options.control()) && !bool(row, "mutationDetected")) missing.add("No sample mutation rejection evidence: " + id);
                    String phase = target.parameters().get("phase");
                    if (("hot".equals(phase) || "cold".equals(phase)) && !bool(row, "qualified")) missing.add("Unqualified " + id);
                }
                if (!sample && "PASS".equals(outcome) && (!bool(row, "reached") || !bool(row, "cleaned"))) missing.add("Control reach/cleanup evidence: " + id);
                if ("PASS".equals(outcome) && (target.kind().startsWith("F02") || target.kind().startsWith("F03"))) validateMixedFailureControl(target, row, missing);
                if (!sample && "PASS".equals(outcome) && "mutated".equals(options.control()) && !bool(row, "mutationDetected")) missing.add("No mutation rejection evidence: " + id);
                if (!sample && "NOT_APPLICABLE".equals(outcome) && (!row.has("staticEvidence") || row.get("staticEvidence").isJsonNull())) missing.add("No inapplicability proof: " + id);
            });
        }
        for (String id : expected.keySet()) if (!terminals.containsKey(id)) missing.add("No terminal: " + id);
        if (runtimeEnvironments.size() != 1) missing.add("Expected exactly one runtime environment");
        else missing.addAll(environmentIssues(runtimeEnvironments.get(0)));
        for (var item : terminals.entrySet()) {
            var target = expected.get(item.getKey());
            if (target == null || !target.kind().equals("query") || "NOT_ENTERED".equals(string(item.getValue(), "outcome"))) continue;
            String phase = target.parameters().get("phase");
            if ("hot".equals(phase) || "cold".equals(phase)) validateStateProof(target, item.getValue(), stateProofs.get(item.getKey()), missing, failures);
        }
        JsonObject result = null;
        try { if (Files.isRegularFile(output.resolve("result.json"))) result = readObject(output.resolve("result.json")); }
        catch (IOException malformed) { missing.add(malformed.getMessage()); }
        if (result == null) missing.add("result.json missing");
        else {
            if (!ProductionRemediationPlan.SCHEMA.equals(string(result, "schemaVersion"))) missing.add("Result schema mismatch");
            if (!options.profile().equals(string(result, "profile")) || !options.scenario().equals(string(result, "case"))) missing.add("Result profile/case mismatch");
            if (!"DATA_COMPLETE".equals(string(result, "state")) && !("missed".equals(options.control())
                    && "DATA_PARTIAL".equals(string(result, "state")))) missing.add("Result collection did not complete");
            if (result.has("failure") || result.has("shutdownFailure")) failures.add("Harness or shutdown failure recorded");
            if (Files.size(output.resolve("result.json")) + Files.size(output.resolve("identity.json")) > COMPACT_LIMIT) missing.add("Combined compact budget exceeded");
            if (bool(result, "streamTruncated")) missing.add("Raw stream truncated");
            for (String key : List.of("runScope", "routes", "pressureStages", "historyBaseline", "rawEvidence")) if (!result.has(key)) missing.add("Result field missing: " + key);
            if (result.has("rawEvidence")) {
                try { verifyReferences(output, result.getAsJsonObject("rawEvidence"), missing); }
                catch (IOException | RuntimeException malformed) { missing.add("Raw references could not be verified: " + malformed.getMessage()); }
            }
        }
        try { validateExit(output, missing, failures); }
        catch (IOException | RuntimeException malformed) { missing.add("Exit evidence could not be read: " + malformed.getMessage()); }
        Map<String, Integer> counts = new TreeMap<>();
        terminals.values().forEach(row -> counts.merge(String.valueOf(string(row, "outcome")), 1, Integer::sum));
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("schemaVersion", ProductionRemediationPlan.SCHEMA);
        assessment.put("status", missing.isEmpty() ? failures.isEmpty() ? "PASS" : "FAIL" : "INCOMPLETE");
        assessment.put("planned", expected.size()); assessment.put("terminals", terminals.size());
        assessment.put("terminalCounts", counts);
        if (options.scenario().equals("F")) assessment.put("failureCoverage", Map.of(
                "worldPriorityMode", "actual", "worldMixedTargets", expected.values().stream().filter(value -> List.of("F02", "F03/alternative").contains(value.kind())).count(),
                "fixedCoreMixedInputs", 576, "coreEvidence", "separate source-bound core test result; not established by this server report"));
        assessment.put("runtimeEnvironment", runtimeEnvironments.size() == 1 ? runtimeEnvironments.get(0) : null);
        assessment.put("identitySHA256", hash(output.resolve("identity.json")));
        assessment.put("preparedAt", string(identity, "preparedAt"));
        if (options.scenario().equals("generation-cost")) {
            assessment.put("generation", generationEvidence(result, terminals, missing));
            if (!missing.isEmpty()) assessment.put("status", "INCOMPLETE");
        }
        if (List.of("M", "probe").contains(options.scenario()) && !"missed".equals(options.control())) {
            assessment.put("paired", pairedEvidence(result, terminals, options, missing, failures));
            assessment.put("status", missing.isEmpty() ? failures.isEmpty() ? "PASS" : "FAIL" : "INCOMPLETE");
        }
        if (options.scenario().equals("causality") || options.scenario().equals("probe")) {
            assessment.put("observations", diagnosticEvidence(output, options, missing));
            assessment.put("status", missing.isEmpty() ? failures.isEmpty() ? "PASS" : "FAIL" : "INCOMPLETE");
        }
        assessment.put("missing", missing); assessment.put("failures", failures);
        if (options.profile().equals("fixture")) {
            boolean missedComplete = !"missed".equals(options.control()) || expectedMisses.size() == expected.size();
            assessment.put("fixtureStatus", missing.isEmpty() && missedComplete ? failures.isEmpty() ? "PASS" : "FAIL" : "INCOMPLETE");
            assessment.put("expectedNotEntered", expectedMisses);
            if (!expectedMisses.isEmpty()) assessment.put("status", "INCOMPLETE");
        }
        assessment.put("routes", summarizeRoutes(terminals.values().stream().toList()));
        return assessment;
    }

    private static void validateMixedFailureControl(ProductionRemediationPlan.Target target, JsonObject row, List<String> missing) {
        String id = target.id();
        if (!"actual".equals(string(row, "fMode"))) missing.add("Actual-f coverage identity missing: " + id);
        if (!nonNegative(row, "independentCellsChecked") || row.get("independentCellsChecked").getAsInt() != 12 * 4096) missing.add("Independent tunnel facts evidence missing: " + id);
        for (String field : List.of("faults", "notifications", "candidates", "requestedDependencies", "sourceRejections")) {
            if (!row.has(field) || !row.get(field).isJsonArray()) { missing.add("Mixed failure evidence missing: " + id + "/" + field); return; }
        }
        if (target.kind().equals("F03/rejected")) {
            if (row.getAsJsonArray("sourceRejections").isEmpty() || !row.getAsJsonArray("candidates").isEmpty()) missing.add("R02 rejection evidence disagrees: " + id);
            return;
        }
        int sections = target.parameters().containsKey("placement") ? target.parameters().get("placement").split(",").length
                : target.kind().equals("F02/section-tie") ? 3 : 1;
        if (row.getAsJsonArray("faults").size() != sections || row.getAsJsonArray("notifications").size() != sections
                || row.getAsJsonArray("candidates").size() < sections || !row.has("expected")) missing.add("Real failure combination incomplete: " + id);
        int deliveries = "twice".equals(target.parameters().get("repeat")) ? 2 : 1;
        for (JsonElement notification : row.getAsJsonArray("notifications")) {
            JsonObject value = notification.getAsJsonObject();
            if (!nonNegative(value, "deliveries") || value.get("deliveries").getAsInt() != deliveries) missing.add("Notification repetition incomplete: " + id);
        }
        for (JsonElement candidate : row.getAsJsonArray("candidates")) {
            JsonObject value = candidate.getAsJsonObject();
            if (!nonNegative(value, "f") || !nonNegative(value, "g") || !nonNegative(value, "h")
                    || !value.has("source") || !value.has("section") || !value.has("reason")) missing.add("Actual blocked-node identity incomplete: " + id);
        }
    }

    private static Map<String, Object> diagnosticEvidence(Path output, ProductionRemediationPlan.Options options, List<String> missing) throws IOException {
        Path file = output.resolve("observations.jsonl");
        if (options.scenario().equals("probe") && options.variant().equals("off")) {
            if (Files.exists(file)) missing.add("Probe-off instance produced detailed observations");
            return Map.of("enabled", false);
        }
        if (!Files.isRegularFile(file)) { missing.add("Diagnostic observations missing"); return Map.of("enabled", true, "complete", false); }
        if (Files.size(file) > 512L * 1024 * 1024) { missing.add("Diagnostic observation budget exceeded"); return Map.of("enabled", true, "complete", false); }
        Map<String, Long> counts = new LinkedHashMap<>();
        JsonObject ending = null;
        int errors = 0; long records = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                records++;
                try {
                    JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                    if (ending != null) { errors++; continue; }
                    String kind = string(row, "kind");
                    if ("observation_end".equals(kind)) { ending = row; continue; }
                    if ("observation_error".equals(kind)) errors++;
                    if (!"operation_terminal".equals(kind)) continue;
                    String family = string(row, "family");
                    if (!List.of("endpoint", "build", "search", "final-validation").contains(family)
                            || options.family() != null && !options.family().equals(family)
                            || exactLong(row, "endNanos") - exactLong(row, "startNanos") != exactLong(row, "wallNanos")
                            || exactLong(row, "wallNanos") < 0 || exactLong(row, "cpuNanos") < 0) { errors++; continue; }
                    counts.merge(family, 1L, Long::sum);
                } catch (RuntimeException malformed) { errors++; }
            }
        }
        if (ending == null) missing.add("Diagnostic ending record missing");
        else {
            try {
                if (exactLong(ending, "errors") != 0 || bool(ending, "truncated")) errors++;
                if (!(options.family() == null ? "all" : options.family()).equals(string(ending, "family"))) errors++;
                List<String> families = options.family() == null ? List.of("endpoint", "build", "search", "final-validation") : List.of(options.family());
                for (String family : families) {
                    long count = counts.getOrDefault(family, 0L);
                    if (count == 0 || count != exactLong(ending.getAsJsonObject("started"), family)
                            || count != exactLong(ending.getAsJsonObject("completed"), family)) missing.add("Incomplete diagnostic family: " + family);
                }
            } catch (RuntimeException malformed) { errors++; }
        }
        if (errors != 0) missing.add("Invalid or failed diagnostic observations: " + errors);
        return Map.of("enabled", true, "family", options.family() == null ? "all" : options.family(), "records", records,
                "operations", counts, "errors", errors, "bytes", Files.size(file), "sha256", hash(file));
    }

    static void validateSample(JsonObject row, List<String> missing, List<String> failures) {
        String id = string(row, "id");
        for (String key : List.of("owner", "dimension", "channel", "profile", "priority")) {
            if (string(row, key) == null || string(row, key).isBlank()) missing.add("Missing request " + key + ": " + id);
        }
        for (String key : List.of("start", "goal")) {
            if (!row.has(key) || !row.get(key).isJsonArray() || row.getAsJsonArray(key).size() != 3) missing.add("Missing endpoint " + key + ": " + id);
        }
        if (!"GROUND".equals(string(row, "channel")) || !"DEFAULT_GROUND".equals(string(row, "profile")) || !"ACTIVE".equals(string(row, "priority"))) {
            failures.add("Request parameters differ from frozen input: " + id);
        }
        for (String[] clock : List.of(new String[]{"startNanos", "endNanos", "wallNanos"}, new String[]{"startTick", "endTick", "completionTicks"})) {
            try {
                long start = exactLong(row, clock[0]), end = exactLong(row, clock[1]), elapsed = exactLong(row, clock[2]);
                if (elapsed < 0 || Math.subtractExact(end, start) != elapsed) failures.add("Inconsistent timing " + clock[2] + ": " + id);
            } catch (RuntimeException absent) { missing.add("Missing integral timing " + clock[2] + ": " + id); }
        }
        if (!row.has("physicalSearchIds") || !row.get("physicalSearchIds").isJsonArray() || !nonNegative(row, "attempt")) {
            missing.add("Missing request-to-search binding: " + id);
        } else {
            Set<String> bindings = new HashSet<>();
            for (var item : row.getAsJsonArray("physicalSearchIds")) {
                if (!item.isJsonObject() || !nonNegative(item.getAsJsonObject(), "attempt")
                        || !nonNegative(item.getAsJsonObject(), "physicalSearchId")) missing.add("Malformed physical search binding: " + id);
                else if (!bindings.add(item.toString())) failures.add("Duplicate physical search binding: " + id);
            }
        }
        if (!row.has("failure") || !row.has("blockedSection") || !row.has("processCpuNanos")) missing.add("Missing failure/CPU fields: " + id);
        if ("BUSINESS_FAILURE".equals(string(row, "outcome")) && (string(row, "failure") == null || "NONE".equals(string(row, "failure")))) failures.add("Business failure has no production reason: " + id);
        if ("SUCCESS".equals(string(row, "outcome")) && (!"NONE".equals(string(row, "failure"))
                || row.has("blockedSection") && !row.get("blockedSection").isJsonNull())) failures.add("Successful sample retains a business failure: " + id);
    }

    static void validateStateProof(ProductionRemediationPlan.Target target, JsonObject sample, JsonObject proof,
                                           List<String> missing, List<String> failures) {
        String id = target.id(), phase = target.parameters().get("phase");
        if (!sample.has("observerCost") || !sample.get("observerCost").isJsonObject()
                || !nonNegative(sample.getAsJsonObject("observerCost"), "directWallNanos")
                || !nonNegative(sample.getAsJsonObject("observerCost"), "calls")) missing.add("Direct observer cost missing: " + id);
        if (!id.equals(string(sample, "controlReference")) || proof == null) { missing.add("Missing sample state reference: " + id); return; }
        try {
            JsonObject before = proof.getAsJsonObject("before"), used = proof.getAsJsonObject("used");
            JsonObject countersBefore = proof.getAsJsonObject("counterBefore"), countersAfter = proof.getAsJsonObject("counterAfter");
            if (before == null || used == null || countersBefore == null || countersAfter == null) throw new IllegalArgumentException("state fields");
            if ("SUCCESS".equals(string(sample, "outcome")) && used.size() == 0) missing.add("Successful sample captured no dependencies: " + id);
            if (phase.equals("hot")) for (var entry : used.entrySet()) {
                if (!entry.getValue().equals(before.get(entry.getKey()))) failures.add("Hot dependency was not ready: " + id + ":" + entry.getKey());
            }
            if (phase.equals("cold") && (!bool(proof, "coldDerivedEmpty") || before.keySet().stream().anyMatch(key -> !key.startsWith("facts/")))) failures.add("Cold derived state was not empty: " + id);
            List<String> fixed = new ArrayList<>(List.of("persistence.readRequests", "service.recoveredSections", "service.failedRecoveries"));
            if (phase.equals("hot")) fixed.addAll(List.of("worker.tasks.completed.builds", "worker.tasks.completed.prewarms"));
            for (String key : fixed) if (exactLong(countersBefore, key) != exactLong(countersAfter, key)) failures.add("Timed state contamination: " + id + ":" + key);
        } catch (RuntimeException malformed) { missing.add("Malformed sample state proof: " + id); }
    }

    static Map<String, Object> rejectSampleMutation(ProductionRemediationPlan.Target target, Map<String, Object> sample, Map<String, Object> proof) {
        JsonObject row = JSON.toJsonTree(sample).getAsJsonObject();
        JsonObject state = proof == null ? null : JSON.toJsonTree(proof).getAsJsonObject();
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        String phase = target.parameters().get("phase");
        boolean stateful = "hot".equals(phase) || "cold".equals(phase);
        validateSample(row, missing, failures);
        if (stateful) validateStateProof(target, row, state, missing, failures);
        if (!missing.isEmpty() || !failures.isEmpty()) throw new IllegalStateException("Cannot qualify a mutation of invalid evidence: " + missing + failures);
        String relationship;
        if ("cold".equals(phase)) {
            state.addProperty("coldDerivedEmpty", false); relationship = "cold-derived-state";
        } else if (stateful) {
            JsonObject counters = state.getAsJsonObject("counterAfter");
            counters.addProperty("service.recoveredSections", exactLong(counters, "service.recoveredSections") + 1);
            relationship = "hot-recovery-count";
        } else {
            row.addProperty("wallNanos", exactLong(row, "wallNanos") + 1); relationship = "sample-clock";
        }
        validateSample(row, missing, failures);
        if (stateful) validateStateProof(target, row, state, missing, failures);
        if (missing.isEmpty() && failures.isEmpty()) throw new AssertionError("Sample reader accepted deliberate " + relationship + " mutation");
        return Map.of("mutationDetected", true, "mutatedRelationship", relationship, "mutationRejections", List.copyOf(failures));
    }

    static List<Double> snapshotTimes(JsonObject row, String scale) {
        JsonArray raw = row.getAsJsonArray("snapshotNanos");
        if (raw == null || raw.size() != 10000) throw new IllegalArgumentException("10000 raw snapshots required");
        if (exactLong(row.getAsJsonObject("productionState"), "activeMacroRequests") != (scale.equals("1024") ? 1024 : 0)) {
            throw new IllegalArgumentException("Snapshot scale not reached: " + scale);
        }
        List<Double> times = new ArrayList<>();
        for (var value : raw) {
            long nanos = value.getAsBigDecimal().longValueExact();
            if (nanos < 0) throw new IllegalArgumentException("Negative snapshot time");
            times.add(nanos / 1_000_000.0);
        }
        return times;
    }

    static void validateExit(Path output, List<String> missing, List<String> failures) throws IOException {
        if (Files.exists(output.resolve("observer-timeout.json")) || Files.exists(output.resolve("outer-timeout.json"))) failures.add("External observer deadline expired");
        Path file = output.resolve("exit.json");
        if (!Files.isRegularFile(file)) { missing.add("exit.json missing"); return; }
        JsonObject exit = readObject(file);
        for (String key : List.of("serverProcessExitCode", "gradleProcessExitCode", "outerProcessExitCode")) {
            try { if (exactLong(exit, key) != 0) failures.add("Nonzero " + key + ": " + exit.get(key)); }
            catch (RuntimeException absent) { missing.add("Uncaptured " + key); }
        }
        if (!bool(exit, "cleanupComplete")) failures.add("Working copy cleanup did not complete");
        for (String log : List.of("console.log", "server.log")) if (!Files.isRegularFile(output.resolve(log)) || Files.size(output.resolve(log)) == 0) missing.add("Missing raw " + log);
        String observer = string(exit, "observerId");
        if (observer == null || observer.isBlank()) missing.add("Exit observer identity missing");
        for (String layer : List.of("server", "gradle", "outer")) {
            Path raw = output.resolve(layer + "-exit.json");
            if (!Files.isRegularFile(raw)) { missing.add("Missing raw exit layer: " + layer); continue; }
            try {
                JsonObject observed = readObject(raw);
                String key = layer + "ProcessExitCode";
                if (observer == null || !observer.equals(string(observed, "observerId"))) missing.add("Exit layer identity mismatch: " + layer);
                long code = exactLong(observed, layer.equals("server") ? key : "exitCode");
                if (code != 0) failures.add("Nonzero raw " + layer + " exit: " + code);
                if (code != exactLong(exit, key)) failures.add("Exit layer value mismatch: " + layer);
                if (code != 0 && exactLong(exit, key) == 0) failures.add("Raw nonzero exit masked: " + layer);
                if (layer.equals("server") && bool(observed, "cleanupComplete") != bool(exit, "cleanupComplete")) failures.add("Cleanup result changed in outer aggregation");
            } catch (IOException | RuntimeException malformed) { missing.add("Unreadable exit layer " + layer + ":" + malformed.getMessage()); }
        }
    }

    private static long exactLong(JsonObject object, String key) {
        var value = object.getAsJsonPrimitive(key);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException("missing number: " + key);
        return value.getAsBigDecimal().longValueExact();
    }

    static Map<String, Object> summarizeRoutes(List<JsonObject> samples) {
        Map<String, List<JsonObject>> grouped = new TreeMap<>();
        for (var row : samples) if ("query".equals(string(row, "kind"))) {
            JsonObject parameters = row.getAsJsonObject("parameters");
            grouped.computeIfAbsent(string(parameters, "route") + "/" + string(parameters, "phase"), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        grouped.forEach((key, rows) -> {
            List<Double> success = new ArrayList<>(), all = new ArrayList<>(), ticks = new ArrayList<>(), observer = new ArrayList<>();
            int qualified = 0;
            for (JsonObject row : rows) {
                if (row.has("observerCost") && nonNegative(row.getAsJsonObject("observerCost"), "directWallNanos")) {
                    observer.add(row.getAsJsonObject("observerCost").get("directWallNanos").getAsDouble() / 1_000_000.0);
                }
                if (!"NOT_ENTERED".equals(string(row, "outcome")) && nonNegative(row, "wallNanos")) all.add(row.get("wallNanos").getAsDouble() / 1_000_000.0);
                if ("SUCCESS".equals(string(row, "outcome")) && nonNegative(row, "wallNanos")) {
                    success.add(row.get("wallNanos").getAsDouble() / 1_000_000.0);
                    if (bool(row, "qualified")) qualified++;
                    if (nonNegative(row, "completionTicks")) ticks.add(row.get("completionTicks").getAsDouble());
                }
            }
            result.put(key, Map.of("positions", rows.size(), "qualifiedSuccesses", qualified, "successfulWallMillis", distribution(success),
                    "allSubmittedWallMillis", distribution(all), "successfulCompletionTicks", distribution(ticks), "directObserverWallMillis", distribution(observer)));
        });
        return result;
    }

    public static Map<String, Object> distribution(List<Double> samples) {
        return distribution(samples, false);
    }

    private static Map<String, Object> distribution(List<Double> samples, boolean signed) {
        if (samples.isEmpty()) return Map.of("status", "NOT_APPLICABLE", "count", 0);
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        for (double value : sorted) if (!Double.isFinite(value) || !signed && value < 0) throw new IllegalArgumentException("Invalid observation");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "AVAILABLE"); result.put("count", sorted.length);
        result.put("p50", percentile(sorted, .50)); result.put("p95", percentile(sorted, .95)); result.put("p99", percentile(sorted, .99));
        result.put("min", sorted[0]); result.put("max", sorted[sorted.length - 1]);
        result.put("average", java.util.Arrays.stream(sorted).average().orElseThrow());
        return result;
    }

    private static double percentile(double[] sorted, double p) { return sorted[(int) Math.ceil(p * sorted.length) - 1]; }

    static Map<String, Double> validateBaseline(JsonObject baseline) throws IOException {
        Map<String, Double> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        JsonArray routes = baseline.getAsJsonArray("routes");
        if (routes == null) throw new IOException("Baseline routes missing");
        for (JsonElement element : routes) {
            JsonObject route = element.getAsJsonObject(); String name = string(route, "name");
            if (!seen.add(name)) throw new IOException("Duplicate baseline route " + name);
            if (!ProductionRemediationPlan.routes().contains(name)) throw new IOException("Unknown baseline route " + name);
            JsonObject wall;
            try { wall = route.getAsJsonObject("summary").getAsJsonObject("phases").getAsJsonObject("hot").getAsJsonObject("successfulPerformance").getAsJsonObject("wallMillis"); }
            catch (RuntimeException malformed) { throw new IOException("Malformed baseline hot metric " + name, malformed); }
            if (wall == null || !wall.has("count")) throw new IOException("Missing baseline count " + name);
            int count = wall.get("count").getAsInt();
            boolean noReference = name.startsWith("surface_512_") || name.equals("nether_96_06");
            if (count != (noReference ? 0 : 100)) throw new IOException("Unexpected baseline hot count " + name);
            if (!noReference) {
                if (!nonNegative(wall, "p95")) throw new IOException("Missing baseline p95 " + name);
                result.put(name, wall.get("p95").getAsDouble());
            }
        }
        if (!seen.equals(new HashSet<>(ProductionRemediationPlan.routes()))) throw new IOException("Baseline route membership mismatch");
        return result;
    }

    static double hotLimit(String route, Double baseline) {
        int distance = Integer.parseInt(route.split("_")[1]);
        double absolute = distance == 8 ? .5 : distance == 512 ? 5 : 1.5;
        return baseline == null ? absolute : Math.min(absolute, baseline + Math.max(baseline * .1, .02));
    }

    private static void aggregate(Path root, ProductionRemediationPlan.Options options) throws IOException {
        Path batch = root.resolve("build/reports/production-remediation").resolve(options.batch());
        Map<String, Object> instances = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>(), failures = new ArrayList<>();
        List<String> required = new ArrayList<>();
        if (options.profile().equals("formal")) {
            for (int round = 1; round <= 3; round++) {
                required.add("R" + round + "/terrain");
                required.add("R" + round + "/M/on"); required.add("R" + round + "/M/off");
            }
            for (String scenario : List.of("G", "H", "F", "L")) required.add("R1/" + scenario);
            for (int row = 1; row <= 9; row++) {
                String run = "W0" + row;
                for (int variant = 1; variant <= ProductionRemediationPlan.shutdownPlan(run).size(); variant++) required.add(run + "/W/" + variant);
            }
        } else if (options.profile().equals("diagnostic") && List.of("generation-cost", "probe").contains(options.scenario())) {
            for (int round = 1; round <= 3; round++) for (String variant : List.of("on", "off")) {
                required.add("R" + round + "/" + options.scenario() + "/" + variant);
                if (options.scenario().equals("probe")) for (String family : List.of("endpoint", "build", "search", "final-validation")) {
                    String narrow = "R" + round + "/probe/" + variant + "/" + family;
                    if (Files.isRegularFile(batch.resolve(narrow).resolve("identity.json"))) required.add(narrow);
                }
            }
        } else {
            // Fixture batches intentionally vary the destructive control in separate directories.
            required.add(options.run() + "/" + options.scenario() + (options.variant() == null ? "" : "/" + options.variant()));
        }
        JsonElement commonInputs = null, commonBinaries = null, commonEnvironment = null;
        for (String instance : required) {
            Path directory = batch.resolve(instance), file = directory.resolve("identity.json");
            if (!Files.isRegularFile(file)) { missing.add(instance); continue; }
            try {
                JsonObject identity = readObject(file);
                var actual = options(identity);
                if (!actual.profile().equals(options.profile()) || !actual.batch().equals(options.batch())
                        || !actual.suffix().equals(options.batch() + "/" + instance)) {
                    missing.add("Instance identity/path mismatch: " + instance); continue;
                }
                if (commonInputs == null) { commonInputs = identity.get("inputs"); commonBinaries = identity.get("resolvedBinaryInputs"); }
                else if (!commonInputs.equals(identity.get("inputs")) || commonBinaries == null || !commonBinaries.equals(identity.get("resolvedBinaryInputs"))) missing.add("Instance source or dependency mismatch: " + instance);
                Map<String, Object> assessed = assess(directory, identity);
                JsonObject assessment = JSON.toJsonTree(assessed).getAsJsonObject();
                instances.put(instance, assessment);
                if ("INCOMPLETE".equals(string(assessment, "status"))
                        && !(options.profile().equals("fixture") && "PASS".equals(string(assessment, "fixtureStatus")))) missing.add(instance);
                if (assessment.getAsJsonArray("failures").size() != 0) failures.add(instance);
                JsonObject environment = comparisonEnvironment(assessment.getAsJsonObject("runtimeEnvironment"));
                if (commonEnvironment == null) commonEnvironment = environment;
                else if (!commonEnvironment.equals(environment)) missing.add("Runtime configuration mismatch: " + instance);
                List<String> identityIssues = new ArrayList<>();
                checkFiles(root, identity.getAsJsonArray("inputs"), identityIssues);
                checkFiles(root, identity.getAsJsonArray("frozenInputs"), identityIssues);
                checkAbsoluteFiles(identity.getAsJsonArray("resolvedBinaryInputs"), identityIssues);
                checkAbsoluteFiles(identity.getAsJsonArray("skillInputs"), identityIssues);
                if (!identityIssues.isEmpty()) missing.add("Archived input changed: " + instance + ":" + identityIssues);
            } catch (IOException | RuntimeException invalid) { missing.add("Invalid archived instance " + instance + ": " + invalid.getMessage()); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", ProductionRemediationPlan.SCHEMA); result.put("profile", options.profile());
        result.put("batch", options.batch()); result.put("instances", instances); result.put("missing", missing); result.put("failures", failures);
        if (options.profile().equals("formal")) result.put("performance", aggregatePerformance(root, batch, missing, failures));
        if (options.scenario().equals("generation-cost")) result.put("generationComparison", compareGeneration(instances, missing));
        if (options.profile().equals("formal")) result.put("metricsComparison", compareQueries(instances, "M", missing, failures));
        if (options.scenario().equals("probe")) {
            Map<String, Object> comparison = compareQueries(instances, "probe", missing, failures);
            result.put("probeComparison", comparison);
            if (Boolean.TRUE.equals(comparison.get("narrowDiagnosticsRequired")) || options.family() != null) {
                Map<String, Object> narrow = new LinkedHashMap<>();
                for (String family : List.of("endpoint", "build", "search", "final-validation")) {
                    narrow.put(family, compareQueries(instances, "probe", family, missing, failures));
                }
                result.put("narrowProbeComparisons", narrow);
                boolean complete = narrow.values().stream().allMatch(value -> "MEASURED".equals(((Map<?, ?>) value).get("status")));
                if (complete) missing.remove("Probe overhead exceeds 5%; endpoint/build/search/final-validation narrow diagnostics are required");
            }
        }
        result.put("status", missing.isEmpty() ? failures.isEmpty() ? "PASS" : "FAIL" : "INCOMPLETE");
        if (options.profile().equals("fixture")) result.put("fixtureStatus", result.get("status"));
        replaceDerived(batch.resolve("aggregate.json"), result);
    }

    private static Map<String, Object> generationEvidence(JsonObject result, Map<String, JsonObject> terminals, List<String> missing) {
        JsonObject cost = result == null || !result.has("generationCost") ? null : result.getAsJsonObject("generationCost");
        if (cost == null) { missing.add("Generation cost missing"); return Map.of(); }
        for (String field : List.of("processCpuNanos", "wallNanos", "windows", "activeStages", "highestActiveStages", "gcCount", "gcMillis")) {
            if (!nonNegative(cost, field)) missing.add("Generation metric missing: " + field);
        }
        if (!nonNegative(cost, "activeStages") || exactLong(cost, "activeStages") != 0) missing.add("Generation stages not settled");
        if (!cost.has("peakPoolBytes") || !cost.get("peakPoolBytes").isJsonObject() || cost.getAsJsonObject("peakPoolBytes").size() == 0) missing.add("Generation pool peaks missing");
        if (!bool(cost, "postGenerationFactsServiceSuppressed")) missing.add("Generation cost included post-load facts service");
        Map<String, Object> workload = new TreeMap<>();
        for (var entry : terminals.entrySet()) {
            JsonObject row = entry.getValue();
            if (!"G02".equals(string(row, "kind"))) continue;
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String field : List.of("noiseCalls", "oreCalls", "noiseWrites", "oreWrites", "otherWrites", "repeatedWrites")) {
                if (!nonNegative(row, field)) missing.add("Generation work count missing: " + entry.getKey() + "/" + field);
                else counts.put(field, exactLong(row, field));
            }
            workload.put(entry.getKey(), counts);
        }
        if (workload.size() != 32) missing.add("Generation workload requires 32 chunks");
        return Map.of("cost", cost, "workload", workload);
    }

    static Map<String, Object> compareGeneration(Map<String, Object> instances, List<String> missing) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        for (int round = 1; round <= 3; round++) {
            String prefix = "R" + round + "/generation-cost/";
            try {
                JsonObject on = JSON.toJsonTree(instances.get(prefix + "on")).getAsJsonObject();
                JsonObject off = JSON.toJsonTree(instances.get(prefix + "off")).getAsJsonObject();
                if (!"PASS".equals(string(on, "status")) || !"PASS".equals(string(off, "status"))) throw new IllegalArgumentException("one pair member lacks complete successful controls");
                Instant onTime = Instant.parse(string(on, "preparedAt")), offTime = Instant.parse(string(off, "preparedAt"));
                if (round == 2 ? !offTime.isBefore(onTime) : !onTime.isBefore(offTime)) throw new IllegalArgumentException("paired execution order differs from the frozen plan");
                JsonObject onGeneration = on.getAsJsonObject("generation"), offGeneration = off.getAsJsonObject("generation");
                if (!onGeneration.get("workload").equals(offGeneration.get("workload"))) throw new IllegalArgumentException("generation work differs between on/off");
                JsonObject onCost = onGeneration.getAsJsonObject("cost"), offCost = offGeneration.getAsJsonObject("cost");
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("round", round); values.put("onIdentity", string(on, "identitySHA256")); values.put("offIdentity", string(off, "identitySHA256"));
                values.put("on", onCost); values.put("off", offCost);
                for (String field : List.of("processCpuNanos", "wallNanos")) {
                    long enabled = exactLong(onCost, field), disabled = exactLong(offCost, field);
                    if (enabled < 0 || disabled <= 0) throw new IllegalArgumentException("missing measurement or zero denominator: " + field);
                    double ratio = ((double) enabled - disabled) / disabled;
                    values.put(field + "Ratio", ratio); ratios.computeIfAbsent(field, ignored -> new ArrayList<>()).add(ratio);
                }
                rounds.add(values);
            } catch (RuntimeException invalid) { missing.add(prefix + "pair unavailable: " + invalid.getMessage()); }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        ratios.forEach((field, values) -> summary.put(field, distribution(values, true)));
        return Map.of("status", rounds.size() == 3 ? "MEASURED_NO_THRESHOLD" : "INCOMPLETE",
                "rounds", rounds, "ratios", summary, "acceptanceLimit", "No generation overhead threshold was approved");
    }

    private static JsonObject comparisonEnvironment(JsonObject environment) {
        JsonObject result = new JsonObject();
        if (environment == null) return result;
        for (String key : List.of("java.runtime.version", "java.vendor", "java.vm.name", "javaBinarySHA256", "os.name", "os.version", "os.arch",
                "availableProcessors", "maximumHeapBytes", "collectors", "environment")) result.add(key, environment.get(key));
        JsonObject host = environment.getAsJsonObject("host");
        if (host != null) for (String key : List.of("processor", "cores", "logicalProcessors", "osVersion", "visibleMemoryKiB", "powerScheme")) result.add(key, host.get(key));
        JsonArray arguments = new JsonArray();
        if (environment.has("jvmArguments")) for (var item : environment.getAsJsonArray("jvmArguments")) {
            String argument = item.getAsString();
            if (!argument.startsWith("-DacceleratedNavigation.validation.") && !argument.startsWith("-DacceleratedNavigation.terrainReport=")
                    && !argument.startsWith("-DacceleratedNavigation.terrainQualificationReport=")
                    && !argument.startsWith("-DacceleratedNavigation.terrainProbe=")
                    && !argument.startsWith("-DacceleratedNavigation.terrainCausalityOutput=")) arguments.add(item);
        }
        result.add("jvmArguments", arguments);
        return result;
    }

    private static Map<String, Object> pairedEvidence(JsonObject result, Map<String, JsonObject> terminals,
                                                      ProductionRemediationPlan.Options options, List<String> missing, List<String> failures) {
        int initialMissing = missing.size();
        Map<String, Object> routes = new LinkedHashMap<>(), snapshots = new LinkedHashMap<>();
        for (int route = 1; route <= 4; route++) {
            String name = "surface_8_0" + route;
            List<Double> walls = new ArrayList<>(); long cpu = 0, gcCount = 0, gcTime = 0;
            StringBuilder work = new StringBuilder();
            try {
                for (var entry : terminals.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    JsonObject row = entry.getValue(), parameters = row.getAsJsonObject("parameters");
                    if (!"query".equals(string(row, "kind")) || !name.equals(string(parameters, "route")) || !"hot".equals(string(parameters, "phase"))) continue;
                    if (!"SUCCESS".equals(string(row, "outcome")) || !bool(row, "qualified")) throw new IllegalArgumentException("unsuccessful or unqualified hot sample");
                    if (!row.has("pairedWork") || !row.get("pairedWork").isJsonObject()) throw new IllegalArgumentException("work/result evidence missing");
                    long elapsed = exactLong(row, "wallNanos"), spent = exactLong(row, "processCpuNanos");
                    if (elapsed < 0 || spent < 0) throw new IllegalArgumentException("negative query measurement");
                    cpu = Math.addExact(cpu, spent); walls.add(elapsed / 1_000_000.0);
                    gcCount = Math.addExact(gcCount, exactLong(row, "gcCollections")); gcTime = Math.addExact(gcTime, exactLong(row, "gcMillis"));
                    work.append(entry.getKey()).append(':').append(row.get("pairedWork")).append('\n');
                }
                if (walls.size() != 1000) throw new IllegalArgumentException("expected 1000 successful qualified hot positions, got " + walls.size());
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(work.toString().getBytes(StandardCharsets.UTF_8)));
                routes.put(name, Map.of("count", walls.size(), "wallMillis", distribution(walls), "processCpuNanos", cpu,
                        "gcCollections", gcCount, "gcMillis", gcTime, "workAndResultSHA256", digest));
            } catch (RuntimeException | NoSuchAlgorithmException invalid) { missing.add(options.scenario() + "/" + name + ":" + invalid.getMessage()); }
        }
        if (options.scenario().equals("M")) {
            JsonObject control = result == null || !result.has("metricsControl") ? null : result.getAsJsonObject("metricsControl");
            if (control == null || !bool(control, "prepared") || !bool(control, "cleaned")) missing.add("Metrics control preparation/cleanup missing");
            else if (bool(control, "statisticsEnabled") != options.variant().equals("on")) failures.add("Metrics variant did not select the required statistics switch");
            if (options.variant().equals("off") && (control == null || !control.has("suppressedWrites")
                    || control.getAsJsonObject("suppressedWrites").size() != 7)) missing.add("Metrics suppression target coverage missing");
            if (options.variant().equals("on")) for (JsonObject row : terminals.values()) {
                if (!"M/snapshot".equals(string(row, "kind"))) continue;
                String scale = string(row.getAsJsonObject("parameters"), "scale");
                try {
                    var distribution = distribution(snapshotTimes(row, scale)); snapshots.put(scale, distribution);
                    if (options.profile().equals("formal") && (double) distribution.get("p95") > .05) failures.add("Production snapshot P95 exceeds 0.05ms: " + scale);
                } catch (RuntimeException invalid) { missing.add("Snapshot " + scale + ":" + invalid.getMessage()); }
            }
        }
        return Map.of("status", missing.size() == initialMissing && routes.size() == 4 ? "AVAILABLE" : "INCOMPLETE", "routes", routes, "snapshots", snapshots);
    }

    static Map<String, Object> compareQueries(Map<String, Object> instances, String scenario, List<String> missing, List<String> failures) {
        return compareQueries(instances, scenario, null, missing, failures);
    }

    private static Map<String, Object> compareQueries(Map<String, Object> instances, String scenario, String family, List<String> missing, List<String> failures) {
        Map<String, List<Double>> differences = new LinkedHashMap<>(), relative = new LinkedHashMap<>();
        List<Double> cpuRatios = new ArrayList<>(); List<Map<String, Object>> rounds = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            String prefix = "R" + round + "/" + scenario + "/";
            try {
                String suffix = family == null ? "" : "/" + family;
                JsonObject on = JSON.toJsonTree(instances.get(prefix + "on" + suffix)).getAsJsonObject(), off = JSON.toJsonTree(instances.get(prefix + "off" + suffix)).getAsJsonObject();
                Instant onTime = Instant.parse(string(on, "preparedAt")), offTime = Instant.parse(string(off, "preparedAt"));
                if (round == 2 ? !offTime.isBefore(onTime) : !onTime.isBefore(offTime)) throw new IllegalArgumentException("paired order mismatch");
                JsonObject enabled = on.getAsJsonObject("paired"), disabled = off.getAsJsonObject("paired");
                if (!"AVAILABLE".equals(string(enabled, "status")) || !"AVAILABLE".equals(string(disabled, "status"))) throw new IllegalArgumentException("paired work/metrics incomplete");
                long onCpu = 0, offCpu = 0;
                Map<String, Object> values = new LinkedHashMap<>();
                Map<String, Double> roundDifferences = new LinkedHashMap<>(), roundRatios = new LinkedHashMap<>();
                for (int route = 1; route <= 4; route++) {
                    String name = "surface_8_0" + route;
                    JsonObject a = enabled.getAsJsonObject("routes").getAsJsonObject(name), b = disabled.getAsJsonObject("routes").getAsJsonObject(name);
                    if (!a.get("workAndResultSHA256").equals(b.get("workAndResultSHA256")) || exactLong(a, "count") != 1000 || exactLong(b, "count") != 1000) throw new IllegalArgumentException("work or result differs: " + name);
                    double p95on = a.getAsJsonObject("wallMillis").get("p95").getAsDouble(), p95off = b.getAsJsonObject("wallMillis").get("p95").getAsDouble();
                    if (p95off <= 0) throw new IllegalArgumentException("zero P95 denominator: " + name);
                    roundDifferences.put(name, p95on - p95off);
                    roundRatios.put(name, (p95on - p95off) / p95off);
                    onCpu = Math.addExact(onCpu, exactLong(a, "processCpuNanos")); offCpu = Math.addExact(offCpu, exactLong(b, "processCpuNanos"));
                    values.put(name, Map.of("on", a, "off", b));
                }
                if (offCpu <= 0) throw new IllegalArgumentException("zero CPU denominator");
                roundDifferences.forEach((name, value) -> differences.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value));
                roundRatios.forEach((name, value) -> relative.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value));
                cpuRatios.add(((double) onCpu - offCpu) / offCpu);
                rounds.add(Map.of("round", round, "onIdentity", string(on, "identitySHA256"), "offIdentity", string(off, "identitySHA256"), "routes", values));
            } catch (RuntimeException invalid) { missing.add(prefix + "pair unavailable: " + invalid.getMessage()); }
        }
        Map<String, Object> routes = new LinkedHashMap<>(); boolean narrow = false;
        for (String route : differences.keySet()) {
            var absolute = distribution(differences.get(route), true); var ratio = distribution(relative.get(route), true);
            routes.put(route, Map.of("p95DifferenceMillis", absolute, "p95RelativeOverhead", ratio));
            if (rounds.size() == 3 && scenario.equals("M") && (double) absolute.get("p50") > .01) failures.add("Metrics hot P95 increment exceeds 0.01ms: " + route);
            if (rounds.size() == 3 && scenario.equals("probe") && family == null && (double) ratio.get("p50") > .05) narrow = true;
        }
        var cpu = distribution(cpuRatios, true);
        if (rounds.size() == 3 && scenario.equals("M") && (double) cpu.get("p50") > .01) failures.add("Production statistics CPU increment exceeds 1%");
        if (narrow) missing.add("Probe overhead exceeds 5%; endpoint/build/search/final-validation narrow diagnostics are required");
        return Map.of("status", rounds.size() == 3 ? "MEASURED" : "INCOMPLETE", "rounds", rounds, "routes", routes, "cpuIncrement", cpu, "narrowDiagnosticsRequired", narrow);
    }

    private static Map<String, Object> aggregatePerformance(Path root, Path batch, List<String> missing, List<String> failures) throws IOException {
        requireHash(root.resolve(ProductionRemediationPlan.BASELINE), ProductionRemediationPlan.BASELINE_HASH);
        Map<String, Double> baseline = validateBaseline(readObject(root.resolve(ProductionRemediationPlan.BASELINE)));
        Map<String, Object> routes = new LinkedHashMap<>();
        Map<String, List<Double>> coldWall = new LinkedHashMap<>(), coldTicks = new LinkedHashMap<>();
        List<RoundSamples> samplesByRound = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            Path output = batch.resolve("R" + round + "/terrain");
            try { samplesByRound.add(roundSamples(output, batch.getFileName().toString(), "R" + round)); }
            catch (IOException | RuntimeException invalid) { missing.add("Terrain R" + round + " raw evidence unavailable: " + invalid.getMessage()); }
        }
        for (String route : ProductionRemediationPlan.routes()) {
            List<Double> rounds = new ArrayList<>();
            List<String> inputHashes = new ArrayList<>();
            for (RoundSamples round : samplesByRound) {
                inputHashes.add(round.identityHash);
                List<Double> hot = new ArrayList<>();
                String group = route.substring(0, route.lastIndexOf('_'));
                for (JsonObject row : round.routes.getOrDefault(route, List.of())) {
                    JsonObject p = row.getAsJsonObject("parameters");
                    if (!"SUCCESS".equals(string(row, "outcome")) || !bool(row, "qualified") || !nonNegative(row, "wallNanos")) continue;
                    double wall = row.get("wallNanos").getAsDouble() / 1_000_000.0;
                    if ("hot".equals(string(p, "phase"))) hot.add(wall);
                    else if ("cold".equals(string(p, "phase"))) {
                        coldWall.computeIfAbsent(group, ignored -> new ArrayList<>()).add(wall);
                        if (nonNegative(row, "completionTicks")) coldTicks.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row.get("completionTicks").getAsDouble());
                    }
                }
                if (hot.size() == 100) rounds.add((double) distribution(hot).get("p95"));
            }
            Double previous = baseline.get(route);
            double limit = hotLimit(route, previous);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("hotP95ByRound", rounds); comparison.put("inputHashes", inputHashes); comparison.put("baseline", previous); comparison.put("limit", limit);
            if (rounds.size() != 3) missing.add("Hot samples incomplete: " + route);
            else {
                double median = (double) distribution(rounds).get("p50"), worst = (double) distribution(rounds).get("max");
                comparison.put("median", median); comparison.put("worst", worst); comparison.put("distanceToLimit", median - limit);
                comparison.put("absoluteChange", previous == null ? null : median - previous);
                comparison.put("percentChange", previous == null || previous == 0 ? null : (median - previous) / previous * 100);
                if (median > limit) failures.add("Hot P95 exceeds limit: " + route);
            }
            routes.put(route, comparison);
        }
        Map<String, Object> cold = new LinkedHashMap<>();
        for (String group : ProductionRemediationPlan.GROUPS) {
            List<Double> wall = coldWall.getOrDefault(group, List.of()), ticks = coldTicks.getOrDefault(group, List.of());
            int distance = Integer.parseInt(group.substring(group.lastIndexOf('_') + 1));
            int millisLimit = distance == 8 ? 100 : distance == 64 ? 150 : distance == 96 ? 300 : 1000;
            int tickLimit = distance == 8 ? 2 : distance == 64 ? 3 : distance == 96 ? 6 : 20;
            cold.put(group, Map.of("wallMillis", distribution(wall), "completionTicks", distribution(ticks), "wallLimit", millisLimit, "tickLimit", tickLimit,
                    "historicalRelativeComparison", "NOT_APPLICABLE: no qualified cold reference"));
            if (wall.size() != 20 || ticks.size() != 20) missing.add("Cold samples incomplete: " + group);
            else if ((double) distribution(wall).get("p95") > millisLimit || (double) distribution(ticks).get("p95") > tickLimit) failures.add("Cold P95 exceeds limit: " + group);
        }
        return Map.of("routes", routes, "coldGroups", cold);
    }

    private record RoundSamples(String identityHash, Map<String, List<JsonObject>> routes) { }

    private static RoundSamples roundSamples(Path output, String batch, String run) throws IOException {
        JsonObject identity = readObject(output.resolve("identity.json")), result = readObject(output.resolve("result.json"));
        var expectedOptions = new ProductionRemediationPlan.Options("formal", "terrain", run, batch, null, null);
        if (!expectedOptions.equals(options(identity))) throw new IOException("Terrain instance has wrong identity");
        List<String> referenceIssues = new ArrayList<>();
        verifyReferences(output, result.getAsJsonObject("rawEvidence"), referenceIssues);
        if (!referenceIssues.isEmpty()) throw new IOException("Raw identity mismatch: " + referenceIssues);
        Map<String, ProductionRemediationPlan.Target> plan = new HashMap<>();
        ProductionRemediationPlan.targets(expectedOptions).forEach(target -> plan.put(target.id(), target));
        Set<String> seen = new HashSet<>(); Map<String, List<JsonObject>> routes = new LinkedHashMap<>();
        readLines(output.resolve("samples.jsonl"), row -> {
            String id = string(row, "id"); var target = plan.get(id);
            if (target == null || !seen.add(id) || !target.kind().equals(string(row, "kind"))
                    || !JSON.toJsonTree(target.parameters()).equals(row.get("parameters"))) throw new IllegalArgumentException("changed, unknown or duplicate sample");
            if (target.kind().equals("query")) routes.computeIfAbsent(target.parameters().get("route"), ignored -> new ArrayList<>()).add(row);
        });
        return new RoundSamples(hash(output.resolve("identity.json")), routes);
    }

    private static void verifyReferences(Path output, JsonObject references, List<String> issues) throws IOException {
        for (String name : List.of("samples", "controls")) {
            if (!references.has(name)) { issues.add("Missing raw reference " + name); continue; }
            JsonObject reference = references.getAsJsonObject(name);
            if (bool(reference, "truncated")) issues.add("Truncated raw reference " + name);
            if (!(name + ".jsonl").equals(string(reference, "path"))) { issues.add("Invalid raw reference " + name); continue; }
            Path file = output.resolve(name + ".jsonl");
            if (!Files.isRegularFile(file) || Files.size(file) != reference.get("bytes").getAsLong()
                    || !hash(file).equalsIgnoreCase(string(reference, "sha256"))) issues.add("Raw identity mismatch " + name);
            else {
                long[] count = {0}; readLines(file, row -> count[0]++);
                if (count[0] != reference.get("records").getAsLong()) issues.add("Raw record count mismatch " + name);
            }
        }
    }

    private static void checkFiles(Path root, JsonArray files, List<String> issues) throws IOException {
        for (JsonElement item : files) {
            JsonObject row = item.getAsJsonObject(); Path file = within(root, string(row, "path"));
            if (!Files.isRegularFile(file) || !hash(file).equalsIgnoreCase(string(row, "sha256"))) issues.add("Input changed: " + string(row, "path"));
        }
    }

    private static void checkAbsoluteFiles(JsonArray files, List<String> issues) throws IOException {
        if (files == null || files.isEmpty()) { issues.add("Resolved binaries or skill identities missing"); return; }
        for (var item : files) {
            JsonObject row = item.getAsJsonObject(); Path file = Path.of(string(row, "path"));
            if (!file.isAbsolute() || !Files.isRegularFile(file) || !hash(file).equalsIgnoreCase(string(row, "sha256"))) issues.add("Binary or skill input changed: " + file);
        }
    }

    static ProductionRemediationPlan.Options options(JsonObject identity) {
        return new ProductionRemediationPlan.Options(string(identity, "profile"), string(identity, "case"), string(identity, "run"),
                string(identity, "batch"), string(identity, "variant"), string(identity, "control"), string(identity, "family"));
    }

    static JsonObject readObject(Path path) throws IOException {
        if (Files.size(path) > COMPACT_LIMIT) throw new IOException("Compact report exceeds budget: " + path);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (RuntimeException malformed) { throw new IOException("Invalid JSON object: " + path, malformed); }
    }

    static void readLines(Path path, Consumer<JsonObject> consumer) throws IOException {
        if (Files.size(path) > STREAM_LIMIT) throw new IOException("Raw stream exceeds budget: " + path);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line; int count = 0;
            while ((line = reader.readLine()) != null) {
                count++;
                try { consumer.accept(JsonParser.parseString(line).getAsJsonObject()); }
                catch (RuntimeException malformed) { throw new IOException("Invalid raw record " + path + ":" + count, malformed); }
            }
        }
    }

    private static void readEvidence(Path path, List<String> missing, Consumer<JsonObject> consumer) throws IOException {
        if (Files.size(path) > STREAM_LIMIT) { missing.add("Raw stream exceeds budget: " + path); return; }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line; int count = 0;
            while ((line = reader.readLine()) != null) {
                count++;
                try { consumer.accept(JsonParser.parseString(line).getAsJsonObject()); }
                catch (RuntimeException malformed) { missing.add("Invalid raw record " + path + ":" + count); }
            }
        }
    }

    public static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static void requireHash(Path path, String expected) throws IOException {
        if (!hash(path).equalsIgnoreCase(expected)) throw new IOException("Frozen input hash mismatch: " + path);
    }

    private static Map<String, Object> fileIdentity(Path root, Path file) throws IOException {
        return Map.of("path", root.relativize(file).toString().replace('\\', '/'), "bytes", Files.size(file), "sha256", hash(file));
    }

    private static Path within(Path root, String relative) throws IOException {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) throw new IOException("Input path escapes project: " + relative);
        return result;
    }

    static void writeNew(Path path, Object value, long budget) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > budget) throw new IOException("Compact report exceeds remaining budget: " + path);
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }

    private static void replaceDerived(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > COMPACT_LIMIT) throw new IOException("Derived report exceeds budget");
        Files.write(temporary, bytes);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    static String string(JsonObject object, String key) { return !object.has(key) || object.get(key).isJsonNull() ? null : object.get(key).getAsString(); }
    static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
    private static boolean nonNegative(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber()) return false;
        double number = object.get(key).getAsDouble(); return Double.isFinite(number) && number >= 0;
    }
}
