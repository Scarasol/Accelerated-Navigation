package com.scarasol.acceleratednavigation.topology;

import com.google.gson.Gson;
import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/** Diagnostic-only snapshots. No production state is written or returned differently. */
public final class TopologyCausalityObservation {
    public static final boolean CAUSALITY = Boolean.getBoolean("acceleratedNavigation.terrainCausality");
    public static final boolean ENABLED = CAUSALITY || Boolean.getBoolean("acceleratedNavigation.terrainProbe");
    private static final String FAMILY = System.getProperty("acceleratedNavigation.validation.family");
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    private static final Map<String, Long> STARTED = new LinkedHashMap<>(), COMPLETED = new LinkedHashMap<>();
    private static final Gson GSON = new Gson();
    private static final Map<Object, Long> QUERY_IDS = new WeakHashMap<>();
    private static final Map<Object, Map<String, Object>> SEARCHES = new WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    private static long bytes;
    private static long errors;
    private static BufferedWriter writer;
    private static boolean closed, truncated;

    private TopologyCausalityObservation() {}

    public record Span(String family, String operation, long id, long owner, long startNanos, long cpuNanos, long thread) { }

    public static boolean enabled(String family) { return ENABLED && (FAMILY == null || FAMILY.equals(family)); }

    public static Span begin(String family, String operation, Object owner) {
        if (!enabled(family)) return null;
        synchronized (TopologyCausalityObservation.class) {
            if (closed) return null;
            STARTED.merge(family, 1L, Long::sum);
        }
        return new Span(family, operation, nextId(), TopologyValidationAccess.token(owner), System.nanoTime(), cpu(), Thread.currentThread().getId());
    }

    public static void end(Span span, Map<String, ?> details) {
        if (span == null) return;
        long ended = System.nanoTime(), cpu = cpu();
        synchronized (TopologyCausalityObservation.class) { COMPLETED.merge(span.family(), 1L, Long::sum); }
        Map<String, Object> row = new LinkedHashMap<>(details);
        row.put("family", span.family()); row.put("operation", span.operation()); row.put("spanId", span.id()); row.put("ownerToken", span.owner());
        row.put("startNanos", span.startNanos()); row.put("endNanos", ended); row.put("wallNanos", ended - span.startNanos());
        row.put("cpuNanos", span.thread() == Thread.currentThread().getId() && span.cpuNanos() >= 0 && cpu >= span.cpuNanos() ? cpu - span.cpuNanos() : null);
        emit("operation_terminal", row);
    }

    private static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled() ? THREADS.getCurrentThreadCpuTime() : -1; }

    public static void bindSearch(Object query) {
        if (!enabled("search")) return;
        observe(() -> {
            Map<String, Object> identity = queryIdentity(query);
            identity.put("searchId", nextId());
            identity.put("stage", Boolean.TRUE.equals(field(query, "refining")) ? "WITNESS"
                    : Boolean.TRUE.equals(field(query, "hierarchical")) ? "SUPER" : "BASE");
            identity.put("recoveryAggregateOrigin", field(query, "recoveryAggregateOrigin"));
            identity.put("recoveryAggregate", field(query, "recoveryAggregate"));
            Object search = field(query, "search");
            identity.put("maxVisitedNodes", field(search, "maxVisitedNodes"));
            synchronized (SEARCHES) { SEARCHES.put(search, identity); }
            emit("search_start", identity);
        });
    }

    public static void searchTerminal(MacroSearch search) {
        if (!enabled("search") || search.status() == ResumableSearch.Status.RUNNING) return;
        observe(() -> {
            Map<String, Object> data;
            synchronized (SEARCHES) { data = SEARCHES.remove(search); }
            if (data == null) return;
            data = new LinkedHashMap<>(data);
            data.put("status", search.status());
            data.put("failure", search.failure());
            data.put("metrics", search.metrics());
            data.put("blockedSection", search.blockedSection());
            MacroSearch.Graph graph = (MacroSearch.Graph) field(search, "graph");
            data.put("start", graph.start());
            data.put("goal", graph.goal());
            data.put("graphClass", graph.getClass().getSimpleName());
            List<Object> nodes = new ArrayList<>();
            for (Object node : ((Map<?, ?>) field(search, "nodes")).values()) {
                Map<String, Object> state = new LinkedHashMap<>();
                var minecraftNode = (net.minecraft.world.level.pathfinder.Node) node;
                state.put("g", minecraftNode.g);
                state.put("h", minecraftNode.h);
                state.put("f", minecraftNode.f);
                state.put("closed", minecraftNode.closed);
                for (String name : List.of("endpoint", "expansionStarted",
                        "completedExpansionParts", "pendingParts", "unavailableDependencies")) {
                    state.put(name, field(node, name));
                }
                nodes.add(state);
            }
            data.put("nodes", nodes);
            Object view = graph.getClass().getSimpleName().equals("AggregateTopologyGraph")
                    ? field(graph, "delegate") : graph;
            List<Object> captured = new ArrayList<>();
            for (Object capture : ((Map<?, ?>) field(view, "topologySnapshot")).values()) {
                Object topology = field(capture, "topology");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", field(capture, "key"));
                entry.put("stamps", field(capture, "stamps"));
                entry.put("validity", field(capture, "validity"));
                if (topology instanceof SuperClusterTopology parent) {
                    entry.put("signature", parent.signature());
                    for (String name : List.of("childComponentOffsets", "aggregateByChildComponent",
                            "aggregateMetadata", "outgoingOffsets", "outgoingTargets")) {
                        entry.put(name, field(parent, name));
                    }
                } else {
                    BaseClusterTopology base = (BaseClusterTopology) topology;
                    entry.put("revision", base.revision());
                    entry.put("sourceFingerprint", base.sourceFingerprint());
                    entry.put("componentCount", base.componentCount());
                }
                captured.add(entry);
            }
            data.put("capturedTopologies", captured);
            emit("search_terminal", data);
        });
    }

    public static void unavailable(Object query, MacroSearch.DependencyKey dependency) {
        if (!enabled("search")) return;
        observe(() -> {
            Map<String, Object> data = queryIdentity(query);
            data.put("dependency", dependency);
            Object runtime = field(query, "this$0");
            Map<?, ?> clusters = (Map<?, ?>) field(runtime, "clusters");
            List<SectionPos> sections = new ArrayList<>();
            boolean parent = dependency.kind() == MacroSearch.DependencyKind.SUPER_CLUSTER
                    || dependency.kind() == MacroSearch.DependencyKind.SUPER_BOUNDARY;
            sections.addAll(parent ? SuperClusterTopology.childSections(dependency.position())
                    : List.of(dependency.position()));
            if (dependency.target() != null) sections.addAll(parent
                    ? SuperClusterTopology.childSections(dependency.target()) : List.of(dependency.target()));
            List<Object> states = new ArrayList<>();
            synchronized (field(runtime, "runtimeLock")) {
                for (SectionPos section : sections) {
                    Object entry = clusters.get(new TopologyWorkerRuntime.ClusterKey(
                            (ResourceKey) field(query, "dimension"), section));
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("section", section);
                    state.put("present", entry != null);
                    if (entry != null) {
                        state.put("latest", invoke(field(entry, "latest"), "get"));
                        state.put("factState", field(entry, "factState"));
                        state.put("revision", field(entry, "revision"));
                    }
                    states.add(state);
                }
            }
            data.put("sections", states);
            emit("unavailable_dependency", data);
        });
    }

    public static void logical(String route, String stage, int sample, ServerLevel level,
                               BlockPos start, BlockPos goal, TopologyService.MacroRequest request,
                               boolean success, Throwable failure) {
        if (!ENABLED) return;
        observe(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("route", route);
            data.put("stage", stage);
            data.put("sample", sample);
            data.put("dimension", level.dimension());
            data.put("startPosition", start);
            data.put("goalPosition", goal);
            data.put("success", success);
            data.put("exception", failure == null ? null : failure.toString());
            data.put("progress", request.progress());
            data.put("requestToken", TopologyValidationAccess.token(request));
            data.putAll(TopologyValidationAccess.requestIdentity(request));
            emit("logical_terminal", data);
        });
    }

    /** Rebuilds a detached view from captured immutable facts, never publishing it to the runtime. */
    public static void referencePath(Object service, ServerLevel level, String route,
                                     List<BlockPos> path, Map<String, Object> reference) {
        if (!ENABLED) return;
        observe(() -> {
            Object runtime = field(service, "runtime");
            Map<?, ?> clusters = (Map<?, ?>) field(runtime, "clusters");
            Set<SectionPos> sections = path.stream().map(SectionPos::of).collect(Collectors.toSet());
            Map<SectionPos, BaseClusterTopology.BuildInput> inputs = new HashMap<>();
            Map<SectionPos, Long> revisions = new HashMap<>();
            Map<SectionPos, BaseClusterTopology> cached = new HashMap<>();
            var geometry = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND);
            Method inputMethod = runtime.getClass().getDeclaredMethod("buildInput",
                    TopologyWorkerRuntime.ClusterKey.class, BaseClusterTopology.PackedFacts.class);
            inputMethod.setAccessible(true);
            synchronized (field(runtime, "runtimeLock")) {
                for (SectionPos section : sections) {
                    var key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section);
                    Object entry = clusters.get(key);
                    if (entry == null || !Boolean.TRUE.equals(invoke(entry, "current"))) continue;
                    BaseClusterTopology.PackedFacts facts = (BaseClusterTopology.PackedFacts) field(entry, "facts");
                    if (facts == null) continue;
                    inputs.put(section, (BaseClusterTopology.BuildInput) inputMethod.invoke(runtime, key, facts));
                    revisions.put(section, (Long) field(entry, "revision"));
                    Object view = ((Map<?, ?>) field(entry, "views")).get(geometry);
                    if (view != null && field(view, "validity").equals(field(view, "topologyValidity"))) {
                        cached.put(section, (BaseClusterTopology) field(view, "topology"));
                    }
                }
            }
            Map<SectionPos, BaseClusterTopology> topologies = new HashMap<>();
            List<Object> inputRecords = new ArrayList<>();
            for (var item : inputs.entrySet()) {
                SectionPos section = item.getKey();
                var input = item.getValue();
                var topology = BaseClusterTopology.build(section, revisions.get(section), input,
                        geometry, new BaseClusterTopology.BuildScratch());
                topologies.put(section, topology);
                BaseClusterTopology existing = cached.get(section);
                int differentLabels = 0;
                if (existing != null) for (int cell = 0; cell < 4096; cell++) {
                    if (component(existing, cell) != component(topology, cell)) differentLabels++;
                }
                inputRecords.add(Map.of("section", section, "revision", revisions.get(section),
                        "fingerprint", topology.sourceFingerprint(), "cached", existing != null,
                        "cachedLabelDifferences", differentLabels, "components", topology.componentCount(),
                        "centerFactsBase64", java.util.Base64.getEncoder().encodeToString(input.center().bytes())));
            }
            List<Object> steps = new ArrayList<>();
            var factScratch = new BaseClusterTopology.BuildScratch();
            var movement = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.movement(BaseClusterTopology.Channel.GROUND);
            for (int i = 0; i < path.size(); i++) {
                BlockPos position = path.get(i);
                SectionPos section = SectionPos.of(position);
                BaseClusterTopology topology = topologies.get(section);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("position", position);
                step.put("section", section);
                step.put("component", topology == null ? -1 : component(topology, position));
                step.put("worldBlocks", List.of(level.getBlockState(position.below()).toString(),
                        level.getBlockState(position).toString(), level.getBlockState(position.above()).toString()));
                if (inputs.containsKey(section)) step.put("facts", inputs.get(section).flags(
                        position.getX() & 15, position.getY() & 15, position.getZ() & 15, factScratch));
                if (i > 0) {
                    BlockPos previous = path.get(i - 1);
                    BaseClusterTopology source = topologies.get(SectionPos.of(previous));
                    step.put("edge", edge(source, topology, previous, position, movement));
                }
                steps.add(step);
            }
            Map<String, Object> data = new LinkedHashMap<>(reference);
            data.put("route", route);
            data.put("dimension", level.dimension());
            data.put("tick", level.getServer().getTickCount());
            data.put("inputSections", inputRecords);
            data.put("steps", steps);
            emit("reference_path", data);
        });
    }

    private static String edge(BaseClusterTopology source, BaseClusterTopology target,
                               BlockPos from, BlockPos to, BaseClusterTopology.MovementKey movement) {
        if (source == null || target == null) return "MISSING_FACTS";
        int a = component(source, from), b = component(target, to);
        if (a < 0 || b < 0) return "ILLEGAL_ANCHOR";
        if (source.section().equals(target.section())) {
            if (a == b) return "SAME_COMPONENT";
            for (int e = source.localEdgeStart(a); e < source.localEdgeEnd(a); e++) {
                if (source.localEdgeTarget(e) == b && source.localEdgeSupports(e, movement)) return "LOCAL";
            }
            return "MISSING_LOCAL";
        }
        int dx = target.section().x() - source.section().x();
        int dz = target.section().z() - source.section().z();
        Direction face = dx != 0 ? dx > 0 ? Direction.EAST : Direction.WEST
                : dz != 0 ? dz > 0 ? Direction.SOUTH : Direction.NORTH
                : to.getY() > from.getY() ? Direction.UP : Direction.DOWN;
        var links = SuperClusterTopology.boundaryLinks(source, target, face);
        for (int e = links.edgeStart(a); e < links.edgeEnd(a); e++) {
            if (links.targetComponent(e) == b && links.supports(e, movement)) return "BOUNDARY";
        }
        return "MISSING_BOUNDARY_" + face;
    }

    private static int component(BaseClusterTopology topology, BlockPos point) {
        return topology.componentAt(point.getX() & 15, point.getY() & 15, point.getZ() & 15);
    }

    private static int component(BaseClusterTopology topology, int cell) {
        return topology.componentAt(BaseClusterTopology.x(cell), BaseClusterTopology.y(cell), BaseClusterTopology.z(cell));
    }

    private static Map<String, Object> queryIdentity(Object query) throws ReflectiveOperationException {
        Map<String, Object> data = new LinkedHashMap<>();
        synchronized (QUERY_IDS) { data.put("queryId", QUERY_IDS.computeIfAbsent(query, ignored -> nextId())); }
        for (String name : List.of("dimension", "startPosition", "goalPosition", "hierarchical",
                "refining", "witnessConnectionIndex", "startCandidates", "goalCandidates")) {
            data.put(name, field(query, name));
        }
        return data;
    }

    private static long nextId() { return SEQUENCE.incrementAndGet(); }

    public static synchronized void emit(String kind, Map<String, ?> values) {
        if (!ENABLED || closed || truncated && !kind.equals("observation_end")) return;
        try {
            if (writer == null) {
                Path path = Path.of(System.getProperty("acceleratedNavigation.terrainCausalityOutput"));
                Files.createDirectories(path.getParent());
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind);
            data.put("observedNanos", System.nanoTime());
            data.put("thread", Thread.currentThread().getName());
            data.putAll(values);
            String line = GSON.toJson(simple(data));
            long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (!kind.equals("observation_end") && bytes + size > 512L * 1024 * 1024 - 8192) {
                truncated = true; errors++; return;
            }
            bytes += size;
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (Exception failure) {
            errors++;
            AcceleratedNavigation.LOGGER.error("Causality observation failed: {}", kind, failure);
        }
    }

    public static synchronized void close() {
        if (!ENABLED || closed) return;
        emit("observation_end", Map.of("errors", errors, "bytesBeforeEnd", bytes, "truncated", truncated,
                "family", FAMILY == null ? "all" : FAMILY, "started", Map.copyOf(STARTED), "completed", Map.copyOf(COMPLETED)));
        closed = true;
        try { if (writer != null) writer.close(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        writer = null;
        synchronized (SEARCHES) { SEARCHES.clear(); }
        synchronized (QUERY_IDS) { QUERY_IDS.clear(); }
    }

    private static Object simple(Object value) throws ReflectiveOperationException {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? number : number.toString();
        if (value instanceof Enum<?> item) return item.name();
        if (value instanceof Vec3i point) return List.of(point.getX(), point.getY(), point.getZ());
        if (value instanceof ResourceKey<?> key) return key.location().toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), simple(entry.getValue()));
            return result;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> result = new ArrayList<>();
            for (Object item : items) result.add(simple(item));
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) result.add(simple(Array.get(value, i)));
            return result;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (RecordComponent part : value.getClass().getRecordComponents()) {
                Method method = part.getAccessor();
                method.setAccessible(true);
                result.put(part.getName(), simple(method.invoke(value)));
            }
            return result;
        }
        return value.toString();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) { /* Search inherited Minecraft node fields. */ }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void observe(Observation action) {
        try { action.run(); }
        catch (Exception failure) {
            synchronized (TopologyCausalityObservation.class) { errors++; }
            emit("observation_error", Map.of("error", failure.toString()));
            AcceleratedNavigation.LOGGER.error("Causality snapshot failed", failure);
        }
    }

    @FunctionalInterface
    private interface Observation { void run() throws Exception; }
}
