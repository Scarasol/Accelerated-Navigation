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
    public static final boolean UNRESOLVED = CAUSALITY && "qn-unresolved".equals(System.getProperty("acceleratedNavigation.validation.selection"));
    public static final boolean ENABLED = CAUSALITY || Boolean.getBoolean("acceleratedNavigation.terrainProbe");
    private static final String PLAN = System.getProperty("acceleratedNavigation.validation.plan");
    private static final List<String> PACKAGES = PLAN == null ? null : com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan.DiagnosticPlan.parse(PLAN).observers();
    private static final String FAMILY = System.getProperty("acceleratedNavigation.validation.family");
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    private static final Map<String, Long> STARTED = new LinkedHashMap<>(), COMPLETED = new LinkedHashMap<>();
    private static final Gson GSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private static final Map<Object, Long> QUERY_IDS = new WeakHashMap<>();
    private static final Map<Object, Map<String, Object>> SEARCHES = new WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    private static long bytes;
    private static long errors;
    private static BufferedWriter writer;
    private static boolean closed, truncated;

    private TopologyCausalityObservation() {}

    public record Span(String family, String operation, long id, long owner, long startNanos, long cpuNanos, long thread) { }

    public static boolean enabled(String family) { return ENABLED && (PACKAGES == null ? FAMILY == null || FAMILY.equals(family) : PACKAGES.contains(family)); }

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

    public static void buildAttempt(Object runtime, String phase) {
        if (!enabled("build")) return;
        observe(() -> {
            Object executor = field(runtime, "taskExecutor");
            Object task = ((ThreadLocal<?>) field(executor, "currentTask")).get();
            if (task == null) throw new IllegalStateException("Build did not run in its executor task");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phase", phase); row.put("runtimeToken", TopologyValidationAccess.token(runtime));
            row.put("taskToken", TopologyValidationAccess.token(task));
            Object command;
            synchronized (field(executor, "monitor")) {
                for (String name : List.of("sequence", "kind", "priority", "enqueuedNanos")) row.put(name.equals("kind") ? "taskKind" : name, field(task, name));
                command = field(task, "command");
            }
            List<Object> inputs = new ArrayList<>();
            synchronized (field(runtime, "runtimeLock")) {
                for (Field capture : command.getClass().getDeclaredFields()) {
                    capture.setAccessible(true); Object input = capture.get(command);
                    if (input == runtime) continue;
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("type", capture.getType().getSimpleName()); value.put("token", TopologyValidationAccess.token(input));
                    if (capture.getType().getSimpleName().equals("TopologyDemand")) {
                        for (String name : List.of("key", "geometry", "generation", "fingerprint", "prewarmSlot", "priority")) value.put(name, field(input, name));
                        value.put("waiters", ((Set<?>) field(input, "waiters")).stream().map(TopologyValidationAccess::token).toList());
                        value.put("waitingFacts", ((Set<?>) field(input, "waitingFacts")).size());
                    } else if (capture.getType().isRecord() || input instanceof Number) value.put("value", input);
                    inputs.add(value);
                }
            }
            row.put("inputs", inputs); emit("build_attempt", row);
        });
    }

    public static void buildSubmission(Object runtime, Object handle) {
        if (!enabled("build") || handle == null) return;
        observe(() -> {
            Object executor = field(runtime, "taskExecutor");
            var row = new LinkedHashMap<String, Object>();
            synchronized (field(executor, "monitor")) {
                row.put("taskToken", TopologyValidationAccess.token(handle)); row.put("runtimeToken", TopologyValidationAccess.token(runtime));
                for (String name : List.of("sequence", "kind", "priority", "enqueuedNanos", "state")) row.put(name, field(handle, name));
            }
            emit("build_submission", row);
        });
    }

    public static void invalidated(Object source, Object state) {
        if (!enabled("build")) return;
        emit("derived_invalidation", Map.of("source", source, "nextState", state,
                "caller", StackWalker.getInstance().walk(stream -> stream.filter(frame -> frame.getClassName().contains("TopologyWorkerRuntime"))
                        .limit(6).map(Object::toString).toList())));
    }

    public static void queryDecision(Object query, String point) {
        if (!enabled("search")) return;
        observe(() -> {
            var row = queryIdentity(query); row.put("point", point);
            for (String name : List.of("status", "failure", "blockedEndpoint", "preparedResultCurrent")) row.put(name, field(query, name));
            row.put("usedSections", stampSnapshots(((Map<?, ?>) field(query, "usedSections")).values()));
            row.put("preparedResultStamps", stampSnapshots((Iterable<?>) field(query, "preparedResultStamps")));
            for (String name : List.of("baseGraph", "superGraph")) {
                Object graph = field(query, name);
                if (graph != null) row.put(name, capturedStamps(graph));
            }
            Object search = field(query, "search");
            if (search != null) { row.put("searchStatus", ((MacroSearch) search).status()); row.put("searchFailure", ((MacroSearch) search).failure()); }
            emit("query_decision", row);
        });
    }

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

    /** Observe handoff identity without retaining or changing any production ownership. */
    public static void boundaryHandoff(Object query, String point, MacroSearch.DependencyKey dependency,
                                       Object waiter, Object resolved) {
        if (!enabled("search")) return;
        observe(() -> {
            MacroSearch.DependencyKey actual = dependency != null ? dependency : resolved == null ? null
                    : (MacroSearch.DependencyKey) field(resolved, "dependency");
            var row = queryIdentity(query);
            row.put("point", point); row.put("dependency", actual);
            Object graph = field(query, "baseGraph");
            row.put("baseGraphToken", TopologyValidationAccess.token(graph));
            row.put("searchToken", TopologyValidationAccess.token(field(query, "search")));
            row.put("waiterToken", TopologyValidationAccess.token(waiter));
            row.put("resolvedToken", TopologyValidationAccess.token(resolved));
            if (resolved != null) {
                Object key = field(resolved, "cacheKey");
                row.put("unavailability", field(resolved, "unavailability"));
                if (key != null && actual != null && actual.kind() == MacroSearch.DependencyKind.BASE_BOUNDARY) {
                    for (String side : List.of("source", "target")) {
                        BaseClusterTopology topology = (BaseClusterTopology) field(key, side);
                        row.put(side, topologyIdentity(topology));
                        // Only inspect the mutable graph on its worker immediately before attach.
                        if (point.equals("attach") && graph != null) {
                            Object capture = ((Map<?, ?>) field(graph, "topologySnapshot")).get(topology.section().asLong());
                            row.put(side + "InGraph", capture == null ? null : topologyIdentity((BaseClusterTopology) field(capture, "topology")));
                        }
                    }
                }
            }
            emit("boundary_handoff", row);
        });
    }

    /** Live world access is restricted to the server thread before the query timer. */
    public static void endpointWorld(Object service, ServerLevel level, String route, String phase, int sample,
                                     BlockPos start, BlockPos goal) {
        if (!enabled("endpoint")) return;
        observe(() -> {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("endpoint world snapshot outside server thread");
            Object runtime = field(service, "runtime");
            var row = new LinkedHashMap<String, Object>();
            row.put("route", route); row.put("phase", phase); row.put("sample", sample);
            row.put("dimension", level.dimension()); row.put("startPosition", start); row.put("goalPosition", goal);
            var positions = new java.util.LinkedHashSet<BlockPos>();
            for (BlockPos endpoint : List.of(start, goal)) for (BlockPos anchor : observedAnchors(endpoint, true)) {
                positions.add(anchor.below()); positions.add(anchor); positions.add(anchor.above());
            }
            List<Object> cells = new ArrayList<>();
            for (BlockPos position : positions) {
                var cell = new LinkedHashMap<String, Object>();
                cell.put("position", position);
                cell.put("block", level.getBlockState(position).toString());
                cell.put("pathType", net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getBlockPathTypeStatic(level,
                        position.mutable()));
                cell.put("independentFlags", TopologyGenerationProbe.independentFlags(level, position));
                synchronized (field(runtime, "runtimeLock")) {
                    Object entry = ((Map<?, ?>) field(runtime, "clusters")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), SectionPos.of(position)));
                    cell.put("entryPresent", entry != null);
                    if (entry != null) {
                        cell.put("revision", field(entry, "revision")); cell.put("factState", field(entry, "factState"));
                        cell.put("latest", invoke(field(entry, "latest"), "get"));
                        BaseClusterTopology.PackedFacts facts = (BaseClusterTopology.PackedFacts) field(entry, "facts");
                        cell.put("factsAvailable", facts != null);
                        if (facts != null) cell.put("publishedFlags", facts.flags(BaseClusterTopology.cellIndex(
                                position.getX() & 15, position.getY() & 15, position.getZ() & 15)));
                    }
                }
                cells.add(cell);
            }
            row.put("cells", cells); emit("endpoint_world", row);
        });
    }

    /** Records the actual immutable ResolveFlight input, never reading a live world on the worker. */
    public static void endpointInput(Object resolver, List<?> input, Map<?, ?> failures) {
        if (!enabled("endpoint")) return;
        observe(() -> {
            var row = resolverIdentity(resolver);
            var topologies = new HashMap<SectionPos, BaseClusterTopology>();
            List<Object> captures = new ArrayList<>();
            for (Object capture : input) {
                BaseClusterTopology topology = (BaseClusterTopology) field(capture, "topology");
                topologies.put(topology.section(), topology);
                var entry = topologyIdentity(topology); entry.put("validity", field(capture, "validity"));
                entry.put("ownerToken", TopologyValidationAccess.token(field(capture, "owner")));
                captures.add(entry);
            }
            row.put("input", captures); row.put("factFailures", failures);
            Object key = field(resolver, "key");
            for (String side : List.of("start", "goal")) {
                boolean fallback = (Boolean) field(resolver, side.equals("start") ? "fallbackStart" : "fallbackGoal");
                List<Object> anchors = new ArrayList<>();
                for (BlockPos position : observedAnchors((BlockPos) field(key, side), fallback)) {
                    BaseClusterTopology topology = topologies.get(SectionPos.of(position));
                    var anchor = new LinkedHashMap<String, Object>();
                    anchor.put("position", position); anchor.put("topologyToken", TopologyValidationAccess.token(topology));
                    if (topology != null) anchor.put("component", topology.geometry().channel() == field(key, "channel")
                            ? component(topology, position) : -1);
                    anchors.add(anchor);
                }
                row.put(side + "Anchors", anchors);
            }
            emit("endpoint_input", row);
        });
    }

    public static void endpointResolved(Object resolver, Object starts, Object goals) {
        if (!enabled("endpoint")) return;
        observe(() -> {
            var row = resolverIdentity(resolver); row.put("starts", starts); row.put("goals", goals);
            emit("endpoint_resolved", row);
        });
    }

    private static Map<String, Object> resolverIdentity(Object resolver) throws ReflectiveOperationException {
        var row = new LinkedHashMap<String, Object>();
        row.put("resolverToken", TopologyValidationAccess.token(resolver)); row.put("key", field(resolver, "key"));
        row.put("fallbackStart", field(resolver, "fallbackStart")); row.put("fallbackGoal", field(resolver, "fallbackGoal"));
        return row;
    }

    private static Map<String, Object> topologyIdentity(BaseClusterTopology topology) {
        var row = new LinkedHashMap<String, Object>();
        row.put("topologyToken", TopologyValidationAccess.token(topology)); row.put("section", topology.section());
        row.put("revision", topology.revision()); row.put("fingerprint", topology.sourceFingerprint());
        row.put("componentCount", topology.componentCount()); row.put("geometry", topology.geometry());
        return row;
    }

    private static List<BlockPos> observedAnchors(BlockPos center, boolean fallback) {
        if (!fallback) return List.of(center);
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= 2) positions.add(center.offset(x, y, z));
        }
        return positions;
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
                entry.put("stamps", stampSnapshots((Iterable<?>) field(capture, "stamps")));
                entry.put("validity", field(capture, "validity"));
                entry.put("ownerValidity", field(field(capture, "owner"), "validity"));
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
            data.put("progress", request == null ? null : request.progress());
            data.put("requestToken", TopologyValidationAccess.token(request));
            if (request != null) data.putAll(TopologyValidationAccess.requestIdentity(request));
            emit("logical_terminal", data);
        });
    }

    /** Rebuilds a detached view from captured immutable facts, never publishing it to the runtime. */
    public static void referencePath(Object service, ServerLevel level, String route,
                                     List<BlockPos> path, Map<String, Object> reference) {
        if (!enabled("search")) return;
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
        data.put("queryToken", TopologyValidationAccess.token(query));
        for (String name : List.of("dimension", "startPosition", "goalPosition", "hierarchical",
                "refining", "witnessConnectionIndex", "startCandidates", "goalCandidates")) {
            data.put(name, field(query, name));
        }
        return data;
    }

    // Resolve suppliers before emit acquires the writer monitor: absent stamps take runtimeLock.
    // Each row compares the same single source read with the immutable expected identity.
    private static List<Object> stampSnapshots(Iterable<?> stamps) throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();
        for (Object stamp : stamps) {
            Object expected = field(stamp, "expected");
            Object current = ((java.util.function.Supplier<?>) field(stamp, "source")).get();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", field(stamp, "key"));
            row.put("expected", expected); row.put("current", current);
            row.put("expectedToken", TopologyValidationAccess.token(expected));
            row.put("currentToken", TopologyValidationAccess.token(current));
            row.put("sameIdentity", current == expected);
            result.add(simple(row));
        }
        return result;
    }

    private static List<Object> capturedStamps(Object graph) throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();
        for (Object capture : ((Map<?, ?>) field(graph, "topologySnapshot")).values()) {
            result.add(Map.of("key", field(capture, "key"),
                    "stamps", stampSnapshots((Iterable<?>) field(capture, "stamps")),
                    "validity", field(capture, "validity"),
                    "ownerValidity", field(field(capture, "owner"), "validity")));
        }
        return result;
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
                "family", PACKAGES == null ? FAMILY == null ? "all" : FAMILY : String.join(",", PACKAGES), "started", Map.copyOf(STARTED), "completed", Map.copyOf(COMPLETED)));
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
