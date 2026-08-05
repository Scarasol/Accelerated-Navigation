package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Test-only directed reachability and corridor audit over the published primitive base graph. */
public final class TopologyGraphAudit {
    private final TopologyService service;
    private final ServerLevel level;
    private final BlockPos start;
    private final BlockPos goal;
    private final BaseClusterTopology.Channel channel;
    private final BaseClusterTopology.TraversalProfile profile;
    private final int nodeLimit;
    private final ArrayDeque<NodeRef> open = new ArrayDeque<>();
    private final Set<NodeRef> discovered = new HashSet<>();
    private final Map<NodeRef, NodeRef> previous = new HashMap<>();
    private final Set<NodeRef> goals = new HashSet<>();
    private final Set<SectionPos> unavailable = new LinkedHashSet<>();
    private Status status = Status.RUNNING;
    private NodeRef reached;
    private String detail = "RUNNING";
    private long cpuNanos;
    private int expanded;
    private int generated;
    private boolean initialized;

    public static CompletableFuture<BaseClusterTopology> requestClusterDependency(
            TopologyService service, ServerLevel level, SectionPos section,
            NavigationScheduler.Priority priority) {
        return service.subscribeClusterDependency(level, section, priority).future();
    }

    public TopologyGraphAudit(TopologyService service, ServerLevel level, BlockPos start,
                              BlockPos goal, BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile, int nodeLimit) {
        this.service = Objects.requireNonNull(service, "service");
        this.level = Objects.requireNonNull(level, "level");
        this.start = start.immutable();
        this.goal = goal.immutable();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (nodeLimit <= 0) throw new IllegalArgumentException("nodeLimit must be positive");
        this.nodeLimit = nodeLimit;
    }

    public static Map<String, Object> diagnoseHierarchyProjection(
            TopologyService service,
            ServerLevel level,
            BlockPos start,
            BlockPos goal,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile,
            int nodeLimit) {
        long began = System.nanoTime();
        TopologyGraphAudit audit = new TopologyGraphAudit(
                service, level, start, goal, channel, profile, nodeLimit
        );
        while (audit.status() == Status.RUNNING) {
            audit.step(1_024, Long.MAX_VALUE);
        }
        Map<String, Object> report = new LinkedHashMap<>(audit.report());
        report.put("diagnosticWallMillis", millis(System.nanoTime() - began));
        report.put("timingExcludedFromMacroQuery", true);
        report.put("hierarchyInspectionMoment", "POST_QUERY_CURRENT_CACHE");
        report.put("hierarchyInspectionLimit",
                "Does not claim that an idle cache entry was captured by the completed query");
        return report;
    }

    /**
     * Verifies every directed edge in a published base graph, rather than only
     * the witness selected by a route query.
     */
    public static Map<String, Object> auditPublishedProjection(
            TopologyService service,
            ServerLevel level,
            PublishedConnectivity connectivity) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(connectivity, "connectivity");
        TopologyGraphAudit owner = new TopologyGraphAudit(
                service,
                level,
                BlockPos.ZERO,
                BlockPos.ZERO,
                connectivity.channel,
                connectivity.profile,
                Integer.MAX_VALUE
        );
        ProjectionContext context = owner.new ProjectionContext();
        int checked = 0;
        int crossParent = 0;
        int mapped = 0;
        int inconclusive = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (int sourceIndex = 0; sourceIndex < connectivity.nodes.size(); sourceIndex++) {
            NodeRef source = connectivity.nodes.get(sourceIndex);
            for (int targetIndex : connectivity.edges.get(sourceIndex)) {
                NodeRef target = connectivity.nodes.get(targetIndex);
                checked++;
                if (!SuperClusterTopology.originOf(source.section)
                        .equals(SuperClusterTopology.originOf(target.section))) {
                    crossParent++;
                }
                ProjectionResult result = context.edge(source, target);
                if (result.mapped()) {
                    mapped++;
                } else {
                    if (!result.conclusive()) inconclusive++;
                    if (failures.size() < 32) {
                        failures.add(projectionFailure(
                                checked - 1, "PUBLISHED_BASE_EDGE", source, target, result
                        ));
                    }
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "ALL_PUBLISHED_DIRECTED_BASE_EDGES");
        report.put("checkedBaseEdges", checked);
        report.put("crossParentEdges", crossParent);
        report.put("mappedEdges", mapped);
        report.put("directEdgeOmissions", checked - mapped);
        report.put("inconclusiveEdges", inconclusive);
        report.put("passed", checked == mapped && inconclusive == 0);
        report.put("failures", failures);
        report.put("context", context.report());
        report.put("projectionMillis", context.projectionMillis());
        return report;
    }

    public Status step(int expansionBudget, long deadlineNanos) {
        if (status != Status.RUNNING) return status;
        long began = System.nanoTime();
        try {
            if (!initialized && !initialize()) return status;
            int budget = expansionBudget;
            while (budget-- > 0 && System.nanoTime() < deadlineNanos) {
                if (expanded >= nodeLimit) return finish(Status.INCOMPLETE, "REFERENCE_NODE_LIMIT");
                NodeRef node = open.pollFirst();
                if (node == null) return finish(unavailable.isEmpty() ? Status.EXHAUSTED : Status.INCOMPLETE,
                        unavailable.isEmpty() ? "BASE_COMPONENT_GRAPH_EXHAUSTED"
                                : "UNAVAILABLE_TOPOLOGY_BOUNDARY");
                if (goals.contains(node)) {
                    reached = node;
                    return finish(Status.FOUND, "BASE_COMPONENT_ROUTE_FOUND");
                }
                expanded++;
                expand(node);
            }
            return status;
        } finally {
            cpuNanos += System.nanoTime() - began;
        }
    }

    public Status status() { return status; }

    public void stop(String reason) {
        if (status == Status.RUNNING) finish(Status.INCOMPLETE, Objects.requireNonNull(reason));
    }

    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status.name());
        report.put("detail", detail);
        report.put("nodeLimit", nodeLimit);
        report.put("visitedComponents", discovered.size());
        report.put("expandedComponents", expanded);
        report.put("generatedConnections", generated);
        report.put("cpuMillis", millis(cpuNanos));
        report.put("unavailableSections", unavailable.stream().limit(32).map(TopologyGraphAudit::sectionMap).toList());
        report.put("witnessComponentCount", witness().size());
        if (status == Status.FOUND) {
            report.put("hierarchyProjection", hierarchyProjectionReport());
        }
        return report;
    }

    public List<BlockPos> witnessAnchors() {
        return witness().stream().map(this::anchor).toList();
    }

    private boolean initialize() {
        List<NodeRef> starts = bindCandidates(start);
        List<NodeRef> targets = bindCandidates(goal);
        if (starts.isEmpty() || targets.isEmpty()) {
            finish(Status.EXHAUSTED, "ENDPOINT_HAS_NO_COMPONENT");
            return false;
        }
        for (NodeRef node : starts) if (discovered.add(node)) open.addLast(node);
        goals.addAll(targets);
        initialized = true;
        return true;
    }

    private void expand(NodeRef source) {
        BaseClusterTopology topology = topology(source.section);
        if (topology == null || source.componentId >= topology.componentCount()) return;
        BaseClusterTopology.MovementKey movement = profile.movement(channel);
        for (int edge = topology.localEdgeStart(source.componentId);
             edge < topology.localEdgeEnd(source.componentId); edge++) {
            if (topology.localEdgeSupports(edge, movement)) {
                visit(source, new NodeRef(source.section, topology.localEdgeTarget(edge)));
            }
        }
        for (Direction face : Direction.values()) {
            int minimum = face.getAxis().isVertical() ? 0 : -1;
            int maximum = face.getAxis().isVertical() ? 0 : 1;
            for (int yShift = minimum; yShift <= maximum; yShift++) {
                SectionPos targetSection = neighbor(source.section, face, yShift);
                BaseClusterTopology target = topology(targetSection);
                if (target == null) {
                    if (level.getChunkSource().getChunkNow(targetSection.x(), targetSection.z()) == null) {
                        unavailable.add(targetSection);
                    }
                    continue;
                }
                SuperClusterTopology.BoundaryLinks links =
                        SuperClusterTopology.boundaryLinks(topology, target, face);
                for (int edge = links.edgeStart(source.componentId);
                     edge < links.edgeEnd(source.componentId); edge++) {
                    if (links.supports(edge, movement)) {
                        visit(source, new NodeRef(targetSection, links.targetComponent(edge)));
                    }
                }
            }
        }
    }

    private void visit(NodeRef source, NodeRef target) {
        generated++;
        if (discovered.add(target)) {
            previous.put(target, source);
            open.addLast(target);
        }
    }

    private List<NodeRef> bindCandidates(BlockPos position) {
        Set<NodeRef> result = new LinkedHashSet<>();
        for (BlockPos candidate : candidateAnchors(position)) {
            BaseClusterTopology topology = topology(SectionPos.of(candidate));
            if (topology == null || topology.geometry().channel() != channel) continue;
            int component = topology.componentAt(Math.floorMod(candidate.getX(), 16),
                    Math.floorMod(candidate.getY(), 16), Math.floorMod(candidate.getZ(), 16));
            if (component >= 0) {
                result.add(new NodeRef(topology.section(), component));
                if (candidate.equals(position)) break;
            }
        }
        return List.copyOf(result);
    }

    private List<NodeRef> witness() {
        if (reached == null) return List.of();
        List<NodeRef> reverse = new ArrayList<>();
        for (NodeRef node = reached; node != null; node = previous.get(node)) reverse.add(node);
        java.util.Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private Map<String, Object> hierarchyProjectionReport() {
        long began = System.nanoTime();
        List<NodeRef> path = witness();
        ProjectionContext context = new ProjectionContext();
        int sameAggregate = 0;
        int internalEdges = 0;
        int crossingEdges = 0;
        int mappedEdges = 0;
        int inconclusiveEdges = 0;
        Map<String, Object> firstFailure = null;

        ProjectionResult startProjection = context.endpoint(path.get(0));
        ProjectionResult goalProjection = context.endpoint(path.get(path.size() - 1));
        if (!startProjection.mapped()) {
            firstFailure = projectionFailure(0, "START_ENDPOINT", path.get(0), path.get(0),
                    startProjection);
        } else if (!goalProjection.mapped()) {
            firstFailure = projectionFailure(path.size() - 1, "GOAL_ENDPOINT",
                    path.get(path.size() - 1), path.get(path.size() - 1), goalProjection);
        }

        for (int index = 0; index + 1 < path.size(); index++) {
            NodeRef source = path.get(index);
            NodeRef target = path.get(index + 1);
            ProjectionResult result = context.edge(source, target);
            switch (result.kind()) {
                case "SAME_AGGREGATE" -> sameAggregate++;
                case "INTERNAL_PARENT_EDGE" -> internalEdges++;
                case "PARENT_CROSSING_EDGE" -> crossingEdges++;
                default -> {
                }
            }
            if (result.mapped()) {
                mappedEdges++;
            } else {
                if (!result.conclusive()) {
                    inconclusiveEdges++;
                }
                if (firstFailure == null) {
                    firstFailure = projectionFailure(index, "BASE_EDGE", source, target, result);
                }
            }
        }

        boolean endpointsMapped = startProjection.mapped() && goalProjection.mapped();
        int baseEdges = Math.max(0, path.size() - 1);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "ONE_DIRECTED_BASE_WITNESS_PROJECTED_AFTER_QUERY");
        report.put("baseWitnessComponents", path.size());
        report.put("baseWitnessEdges", baseEdges);
        report.put("startProjection", startProjection.report());
        report.put("goalProjection", goalProjection.report());
        report.put("sameAggregateEdges", sameAggregate);
        report.put("internalParentEdges", internalEdges);
        report.put("parentCrossingEdges", crossingEdges);
        report.put("mappedEdges", mappedEdges);
        report.put("directEdgeOmissions", baseEdges - mappedEdges);
        report.put("inconclusiveEdges", inconclusiveEdges);
        report.put("firstFailure", firstFailure == null ? Map.of() : firstFailure);
        report.put("context", context.report());
        report.put("alternativeHierarchyRouteChecked", false);
        report.put("causalityLimit",
                "A missing direct aggregate edge does not by itself exclude an alternate parent-graph route");
        report.put("projectionMillis", millis(System.nanoTime() - began));
        if (endpointsMapped && mappedEdges == baseEdges) {
            report.put("conclusion", context.usedFallback()
                    ? "BASE_WITNESS_PRESERVED_BY_CURRENT_HIERARCHY_SEMANTICS"
                    : "BASE_WITNESS_PRESENT_IN_POST_QUERY_CACHED_HIERARCHY");
        } else if (firstFailure != null
                && Boolean.TRUE.equals(firstFailure.get("conclusive"))) {
            report.put("conclusion", "DIRECT_PARENT_EDGE_OMISSION_OBSERVED");
        } else {
            report.put("conclusion", "HIERARCHY_PROJECTION_AUDIT_INCONCLUSIVE");
        }
        return report;
    }

    private static Map<String, Object> projectionFailure(
            int edgeIndex,
            String stage,
            NodeRef source,
            NodeRef target,
            ProjectionResult result) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("edgeIndex", edgeIndex);
        failure.put("stage", stage);
        failure.put("code", result.code());
        failure.put("conclusive", result.conclusive());
        failure.put("source", nodeMap(source));
        failure.put("target", nodeMap(target));
        failure.put("detail", result.detail());
        return failure;
    }

    private final class ProjectionContext {
        private final Map<SectionPos, CachedParent> parents = new HashMap<>();
        private final BaseClusterTopology.BuildScratch scratch =
                new BaseClusterTopology.BuildScratch();
        private String cacheInspectionFailure = "NONE";
        private int cachedParentsScanned;
        private int cachedParentsUsed;
        private int rebuiltParents;
        private int cachedCrossingsUsed;
        private int recomputedCrossings;
        private int uninspectableCrossings;
        private int parentMaskCandidateSlots;
        private int parentMaskSetSlots;
        private int parentMaskExcludedSlots;
        private int generatedParentEdges;
        private final Set<SectionPos> countedMaskOrigins = new HashSet<>();
        private final Set<String> countedCrossings = new HashSet<>();
        private long projectionStartedNanos;

        private ProjectionContext() {
            projectionStartedNanos = System.nanoTime();
            inspectCachedParents();
        }

        private ProjectionResult endpoint(NodeRef node) {
            CachedParent parent = parent(node);
            if (parent == null) {
                return ProjectionResult.missing("ENDPOINT_PARENT_UNAVAILABLE", false,
                        endpointDetail(node, null, -1, "No cached parent and not all children are published"));
            }
            int aggregate = parent.topology().aggregateId(node.section(), node.componentId());
            if (aggregate < 0) {
                return ProjectionResult.missing("ENDPOINT_COMPONENT_UNMAPPED", true,
                        endpointDetail(node, parent, aggregate,
                                "Parent does not map the base component"));
            }
            return ProjectionResult.mapped("ENDPOINT_MEMBERSHIP",
                    endpointDetail(node, parent, aggregate, "Mapped"));
        }

        private ProjectionResult edge(NodeRef source, NodeRef target) {
            CachedParent sourceParent = parent(source);
            CachedParent targetParent = parent(target);
            if (sourceParent == null || targetParent == null) {
                return ProjectionResult.missing("PARENT_UNAVAILABLE", false, Map.of(
                        "sourceParent", sourceParent == null ? "UNAVAILABLE" : sourceParent.source(),
                        "targetParent", targetParent == null ? "UNAVAILABLE" : targetParent.source()
                ));
            }
            int sourceAggregate = sourceParent.topology().aggregateId(
                    source.section(), source.componentId());
            int targetAggregate = targetParent.topology().aggregateId(
                    target.section(), target.componentId());
            Map<String, Object> identity = edgeIdentity(
                    source, target, sourceParent, targetParent,
                    sourceAggregate, targetAggregate
            );
            if (sourceAggregate < 0 || targetAggregate < 0) {
                return ProjectionResult.missing("BASE_COMPONENT_UNMAPPED", true, identity);
            }
            if (sourceParent.topology().origin().equals(targetParent.topology().origin())) {
                if (sourceAggregate == targetAggregate) {
                    return ProjectionResult.mapped("SAME_AGGREGATE", identity);
                }
                for (int edge = sourceParent.topology().outgoingStart(sourceAggregate);
                     edge < sourceParent.topology().outgoingEnd(sourceAggregate); edge++) {
                    if (sourceParent.topology().outgoingTarget(edge) == targetAggregate) {
                        return ProjectionResult.mapped("INTERNAL_PARENT_EDGE", identity);
                    }
                }
                return ProjectionResult.missing(
                        "MISSING_INTERNAL_AGGREGATE_EDGE", true, identity
                );
            }

            Direction face = parentFace(sourceParent.topology().origin(),
                    targetParent.topology().origin());
            if (face == null) {
                return ProjectionResult.missing("NON_ADJACENT_PARENT_TRANSITION", true, identity);
            }
            boolean potentialExit = sourceParent.topology().hasPotentialExit(
                    sourceAggregate, targetParent.topology().origin());
            identity.put("potentialExit", potentialExit);
            if (!potentialExit) {
                return ProjectionResult.missing("MISSING_PARENT_EXIT_MASK", true, identity);
            }
            LinkResolution resolution = crossing(sourceParent, targetParent, face);
            identity.put("face", face.getName());
            identity.put("crossingSource", resolution.source());
            identity.put("cachedStatus", resolution.cachedStatus());
            if (resolution.links() == null) {
                uninspectableCrossings++;
                return ProjectionResult.missing(
                        "PARENT_CROSSING_UNAVAILABLE", false, identity
                );
            }
            for (int edge = resolution.links().edgeStart(sourceAggregate);
                 edge < resolution.links().edgeEnd(sourceAggregate); edge++) {
                if (resolution.links().targetAggregate(edge) == targetAggregate) {
                    return ProjectionResult.mapped("PARENT_CROSSING_EDGE", identity);
                }
            }
            return ProjectionResult.missing(
                    "MISSING_PARENT_CROSSING_EDGE", true, identity
            );
        }

        @Nullable
        private CachedParent parent(NodeRef node) {
            SectionPos origin = SuperClusterTopology.originOf(node.section());
            CachedParent cached = parents.get(origin);
            if (cached != null) {
                if (!cached.counted()) {
                    cachedParentsUsed++;
                    cached = cached.countedCopy();
                    parents.put(origin, cached);
                }
                return cached;
            }
            BaseClusterTopology[] children = children(origin);
            if (children == null) {
                return null;
            }
            SuperClusterTopology rebuilt = SuperClusterTopology.build(
                    origin,
                    children,
                    profile.geometry(channel),
                    profile.movement(channel),
                    scratch
            );
            CachedParent parent = new CachedParent(rebuilt, null, "REBUILT_FROM_CURRENT_BASE",
                    true);
            parents.put(origin, parent);
            recordExitMask(rebuilt);
            rebuiltParents++;
            return parent;
        }

        private LinkResolution crossing(CachedParent source,
                                        CachedParent target,
                                        Direction face) {
            String cachedStatus = "NO_SOURCE_CACHE_ENTRY";
            if (source.cacheEntry() != null) {
                try {
                    int slot = superLinkSlot(source.topology().origin(),
                            target.topology().origin(), face);
                    long[] signatures = (long[]) readField(
                            source.cacheEntry(), "linkTargetSignatures");
                    Object[] links = (Object[]) readField(source.cacheEntry(), "links");
                    if (signatures[slot] != target.topology().signature()) {
                        cachedStatus = "TARGET_SIGNATURE_MISMATCH";
                    } else if (links[slot] == null) {
                        cachedStatus = "CACHE_SLOT_EMPTY";
                    } else {
                        Object value = readField(links[slot], "value");
                        if (value instanceof SuperClusterTopology.CrossingIndex crossing) {
                            cachedCrossingsUsed++;
                            countCrossingEdges(source, target, crossing);
                            return new LinkResolution(crossing, "POST_QUERY_CACHE", "READY");
                        }
                        cachedStatus = "CACHE_VALUE_NOT_READY";
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    cachedStatus = "CACHE_INSPECTION_FAILED:" + failure.getClass().getSimpleName();
                }
            }

            BaseClusterTopology[] sourceChildren = children(source.topology().origin());
            BaseClusterTopology[] targetChildren = children(target.topology().origin());
            if (sourceChildren != null && targetChildren != null
                    && source.topology().matchesChildren(sourceChildren)
                    && target.topology().matchesChildren(targetChildren)) {
                recomputedCrossings++;
                SuperClusterTopology.CrossingIndex crossing = source.topology().crossingIndex(
                        face, target.topology(), sourceChildren, targetChildren);
                countCrossingEdges(source, target, crossing);
                return new LinkResolution(crossing, "RECOMPUTED_FROM_CURRENT_BASE", cachedStatus);
            }
            return new LinkResolution(null, "UNAVAILABLE", cachedStatus);
        }

        private void countCrossingEdges(CachedParent source,
                                        CachedParent target,
                                        SuperClusterTopology.CrossingIndex crossing) {
            String key = source.topology().origin() + "->" + target.topology().origin();
            if (!countedCrossings.add(key)) return;
            for (int aggregate = 0; aggregate < source.topology().aggregateCount(); aggregate++) {
                generatedParentEdges += crossing.edgeEnd(aggregate) - crossing.edgeStart(aggregate);
            }
        }

        @Nullable
        private BaseClusterTopology[] children(SectionPos origin) {
            BaseClusterTopology[] children = new BaseClusterTopology[8];
            int index = 0;
            for (SectionPos child : SuperClusterTopology.childSections(origin)) {
                BaseClusterTopology topology = TopologyGraphAudit.this.topology(child);
                if (topology == null
                        || !topology.geometry().equals(profile.geometry(channel))) {
                    return null;
                }
                children[index++] = topology;
            }
            return children;
        }

        private void inspectCachedParents() {
            try {
                Object value = readField(service, "superClusters");
                if (!(value instanceof Map<?, ?> cache)) {
                    cacheInspectionFailure = "SUPER_CACHE_HAS_UNEXPECTED_TYPE";
                    return;
                }
                for (Map.Entry<?, ?> entry : cache.entrySet()) {
                    Object key = entry.getKey();
                    if (!level.dimension().equals(readField(key, "dimension"))
                            || !profile.geometry(channel).equals(readField(key, "geometry"))
                            || !profile.movement(channel).equals(readField(key, "movement"))) {
                        continue;
                    }
                    Object topology = readField(entry.getValue(), "topology");
                    if (topology instanceof SuperClusterTopology parent) {
                        parents.put(parent.origin(), new CachedParent(
                                parent, entry.getValue(), "POST_QUERY_CACHE", false
                        ));
                        recordExitMask(parent);
                        cachedParentsScanned++;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                cacheInspectionFailure = failure.getClass().getSimpleName()
                        + ":" + String.valueOf(failure.getMessage());
            }
        }

        private boolean usedFallback() {
            return rebuiltParents != 0 || recomputedCrossings != 0;
        }

        private Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("cacheInspectionFailure", cacheInspectionFailure);
            report.put("cachedParentsScanned", cachedParentsScanned);
            report.put("cachedParentsUsed", cachedParentsUsed);
            report.put("rebuiltParents", rebuiltParents);
            report.put("cachedCrossingsUsed", cachedCrossingsUsed);
            report.put("recomputedCrossings", recomputedCrossings);
            report.put("uninspectableCrossings", uninspectableCrossings);
            report.put("parentMaskCandidateSlots", parentMaskCandidateSlots);
            report.put("parentMaskSetSlots", parentMaskSetSlots);
            report.put("parentMaskExcludedSlots", parentMaskExcludedSlots);
            report.put("generatedParentEdges", generatedParentEdges);
            return report;
        }

        private void recordExitMask(SuperClusterTopology parent) {
            if (!countedMaskOrigins.add(parent.origin())) return;
            try {
                int[] metadata = (int[]) readField(parent, "aggregateMetadata");
                for (int value : metadata) {
                    int set = Integer.bitCount((value >>> 15) & 0x3fff);
                    parentMaskCandidateSlots += 14;
                    parentMaskSetSlots += set;
                    parentMaskExcludedSlots += 14 - set;
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                cacheInspectionFailure = "EXIT_MASK_INSPECTION_FAILED:"
                        + failure.getClass().getSimpleName();
            }
        }

        private double projectionMillis() {
            return millis(System.nanoTime() - projectionStartedNanos);
        }
    }

    private static Object readField(Object target, String name)
            throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    @Nullable
    private static Direction parentFace(SectionPos source, SectionPos target) {
        int dx = target.x() - source.x();
        int dy = target.y() - source.y();
        int dz = target.z() - source.z();
        int stride = SuperClusterTopology.CHILDREN_PER_AXIS;
        if (Math.abs(dx) == stride && dz == 0 && Math.abs(dy) <= stride) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        if (Math.abs(dz) == stride && dx == 0 && Math.abs(dy) <= stride) {
            return dz < 0 ? Direction.NORTH : Direction.SOUTH;
        }
        if (dx == 0 && dz == 0 && Math.abs(dy) == stride) {
            return dy < 0 ? Direction.DOWN : Direction.UP;
        }
        return null;
    }

    private static int superLinkSlot(SectionPos source, SectionPos target, Direction face) {
        if (face.getAxis().isVertical()) {
            return face == Direction.DOWN ? 12 : 13;
        }
        int direction = switch (face) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("horizontal face required");
        };
        int yShift = (target.y() - source.y()) / SuperClusterTopology.CHILDREN_PER_AXIS;
        if (yShift < -1 || yShift > 1) {
            throw new IllegalArgumentException("parent Y shift is outside -1..1");
        }
        return direction * 3 + yShift + 1;
    }

    private static Map<String, Object> endpointDetail(
            NodeRef node,
            @Nullable CachedParent parent,
            int aggregate,
            String result) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("node", nodeMap(node));
        report.put("parent", parent == null ? Map.of()
                : sectionMap(parent.topology().origin()));
        report.put("parentSource", parent == null ? "UNAVAILABLE" : parent.source());
        report.put("aggregate", aggregate);
        report.put("result", result);
        return report;
    }

    private static Map<String, Object> edgeIdentity(
            NodeRef source,
            NodeRef target,
            CachedParent sourceParent,
            CachedParent targetParent,
            int sourceAggregate,
            int targetAggregate) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", nodeMap(source));
        report.put("target", nodeMap(target));
        report.put("sourceParent", sectionMap(sourceParent.topology().origin()));
        report.put("targetParent", sectionMap(targetParent.topology().origin()));
        report.put("sourceParentSource", sourceParent.source());
        report.put("targetParentSource", targetParent.source());
        report.put("sourceAggregate", sourceAggregate);
        report.put("targetAggregate", targetAggregate);
        return report;
    }

    private static Map<String, Object> nodeMap(NodeRef node) {
        return Map.of(
                "section", sectionMap(node.section()),
                "component", node.componentId()
        );
    }

    private BlockPos anchor(NodeRef node) {
        BaseClusterTopology topology = topology(node.section);
        if (topology == null) return BlockPos.ZERO;
        int cell = topology.componentAnchorCell(node.componentId);
        return new BlockPos(node.section.minBlockX() + BaseClusterTopology.x(cell),
                node.section.minBlockY() + BaseClusterTopology.y(cell),
                node.section.minBlockZ() + BaseClusterTopology.z(cell));
    }

    @Nullable
    private BaseClusterTopology topology(SectionPos section) {
        return service.topology(new TopologyService.ClusterKey(level.dimension(), section));
    }

    private Status finish(Status result, String resultDetail) {
        status = result;
        detail = resultDetail;
        return result;
    }

    public static Map<String, Object> auditCorridor(TopologyService service,
                                                     ResourceKey<Level> dimension,
                                                     MacroSearch.Corridor corridor,
                                                     BaseClusterTopology.TraversalProfile profile) {
        List<String> errors = new ArrayList<>();
        int aggregateEndpoints = 0;
        List<MacroSearch.Endpoint> endpoints = corridor.endpoints();
        if (corridor.connections().size() + 1 != endpoints.size()) {
            errors.add("connection count does not match endpoint count");
        }
        for (MacroSearch.Endpoint endpoint : endpoints) {
            if (endpoint instanceof MacroSearch.AggregateEndpoint) aggregateEndpoints++;
            if (endpoint instanceof MacroSearch.ComponentEndpoint component) {
                BaseClusterTopology topology = service.topology(
                        new TopologyService.ClusterKey(dimension, component.section()));
                if (topology == null || topology.signature() != component.revision()
                        || component.componentId() < 0
                        || component.componentId() >= topology.componentCount()) {
                    errors.add("stale or unavailable component endpoint " + component.id());
                }
            }
        }
        if (aggregateEndpoints != 0) errors.add("refined corridor leaked aggregate endpoints");
        for (int index = 0; index < corridor.connections().size(); index++) {
            MacroSearch.Connection edge = corridor.connections().get(index);
            if (index + 1 >= endpoints.size() || edge.from().id() != endpoints.get(index).id()
                    || edge.to().id() != endpoints.get(index + 1).id()) {
                errors.add("connection " + index + " does not match endpoint order");
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("valid", errors.isEmpty());
        report.put("referenceCurrent", errors.stream().noneMatch(error -> error.contains("stale")));
        report.put("checkedConnections", corridor.connections().size());
        report.put("aggregateEndpoints", aggregateEndpoints);
        report.put("errors", errors);
        return report;
    }

    public static PublishedConnectivity indexPublished(
            TopologyService service, ResourceKey<Level> dimension,
            Collection<SectionPos> sections, BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile) {
        long began = System.nanoTime();
        Map<SectionPos, BaseClusterTopology> topologies = new LinkedHashMap<>();
        for (SectionPos section : sections) {
            BaseClusterTopology topology = service.topology(new TopologyService.ClusterKey(dimension, section));
            if (topology == null) throw new IllegalStateException("missing topology for " + section);
            topologies.putIfAbsent(section, topology);
        }
        List<NodeRef> nodes = new ArrayList<>();
        Map<NodeRef, Integer> indexes = new HashMap<>();
        for (Map.Entry<SectionPos, BaseClusterTopology> entry : topologies.entrySet()) {
            for (int component = 0; component < entry.getValue().componentCount(); component++) {
                NodeRef node = new NodeRef(entry.getKey(), component);
                indexes.put(node, nodes.size());
                nodes.add(node);
            }
        }
        List<Set<Integer>> edges = new ArrayList<>(nodes.size());
        for (int ignored = 0; ignored < nodes.size(); ignored++) edges.add(new LinkedHashSet<>());
        BaseClusterTopology.MovementKey movement = profile.movement(channel);
        for (int sourceIndex = 0; sourceIndex < nodes.size(); sourceIndex++) {
            NodeRef source = nodes.get(sourceIndex);
            BaseClusterTopology topology = topologies.get(source.section);
            for (int edge = topology.localEdgeStart(source.componentId);
                 edge < topology.localEdgeEnd(source.componentId); edge++) {
                if (topology.localEdgeSupports(edge, movement)) add(edges, indexes, sourceIndex,
                        new NodeRef(source.section, topology.localEdgeTarget(edge)));
            }
            for (Direction face : Direction.values()) {
                int minimum = face.getAxis().isVertical() ? 0 : -1;
                int maximum = face.getAxis().isVertical() ? 0 : 1;
                for (int yShift = minimum; yShift <= maximum; yShift++) {
                    SectionPos targetSection = neighbor(source.section, face, yShift);
                    BaseClusterTopology target = topologies.get(targetSection);
                    if (target == null) continue;
                    SuperClusterTopology.BoundaryLinks links =
                            SuperClusterTopology.boundaryLinks(topology, target, face);
                    for (int edge = links.edgeStart(source.componentId);
                         edge < links.edgeEnd(source.componentId); edge++) {
                        if (links.supports(edge, movement)) add(edges, indexes, sourceIndex,
                                new NodeRef(targetSection, links.targetComponent(edge)));
                    }
                }
            }
        }
        return new PublishedConnectivity(dimension, channel, profile, Map.copyOf(topologies),
                List.copyOf(nodes), indexes, edges.stream().map(Set::stream)
                .map(stream -> stream.sorted().toList()).toList(), System.nanoTime() - began);
    }

    private static void add(List<Set<Integer>> edges, Map<NodeRef, Integer> indexes,
                            int source, NodeRef target) {
        Integer targetIndex = indexes.get(target);
        if (targetIndex != null) edges.get(source).add(targetIndex);
    }

    public static final class PublishedConnectivity {
        private final ResourceKey<Level> dimension;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Map<SectionPos, BaseClusterTopology> topologies;
        private final List<NodeRef> nodes;
        private final Map<NodeRef, Integer> indexes;
        private final List<List<Integer>> edges;
        private final Map<Integer, BitSet> reachability = new HashMap<>();
        private final long buildNanos;

        private PublishedConnectivity(ResourceKey<Level> dimension,
                                      BaseClusterTopology.Channel channel,
                                      BaseClusterTopology.TraversalProfile profile,
                                      Map<SectionPos, BaseClusterTopology> topologies,
                                      List<NodeRef> nodes, Map<NodeRef, Integer> indexes,
                                      List<List<Integer>> edges, long buildNanos) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.topologies = topologies;
            this.nodes = nodes;
            this.indexes = Map.copyOf(indexes);
            this.edges = edges;
            this.buildNanos = buildNanos;
        }

        public PairSelection selectPair(Collection<BlockPos> candidates, double desiredDistance) {
            List<BoundCandidate> bound = bind(candidates);
            PairCandidate best = null;
            long examined = 0, reachable = 0, began = System.nanoTime();
            for (int first = 0; first < bound.size(); first++) for (int second = first + 1;
                 second < bound.size(); second++) {
                examined++;
                BoundCandidate a = bound.get(first), b = bound.get(second);
                double distance = distance(a.position, b.position);
                double difference = Math.abs(distance - desiredDistance);
                if (canReach(a.node, b.node)) {
                    reachable++;
                    if (best == null || difference < best.difference) best =
                            new PairCandidate(a, b, distance, difference);
                }
                if (canReach(b.node, a.node)) {
                    reachable++;
                    if (best == null || difference < best.difference) best =
                            new PairCandidate(b, a, distance, difference);
                }
            }
            if (best == null) throw new IllegalStateException("no reachable candidate pair for "
                    + dimension.location() + " at " + desiredDistance);
            return selection(best, candidates.size(), bound.size(), desiredDistance,
                    examined, reachable, System.nanoTime() - began);
        }

        public List<PairSelection> selectDistinctStartPairs(Collection<BlockPos> candidates,
                                                            double desiredDistance, int count) {
            List<BoundCandidate> bound = bind(candidates);
            Map<SectionPos, PairCandidate> bestBySection = new LinkedHashMap<>();
            long examined = 0, reachable = 0, began = System.nanoTime();
            for (BoundCandidate start : bound) for (BoundCandidate goal : bound) {
                if (start == goal) continue;
                examined++;
                if (!canReach(start.node, goal.node)) continue;
                reachable++;
                double distance = distance(start.position, goal.position);
                PairCandidate offered = new PairCandidate(start, goal, distance,
                        Math.abs(distance - desiredDistance));
                bestBySection.merge(SectionPos.of(start.position), offered,
                        (old, next) -> old.difference <= next.difference ? old : next);
            }
            List<PairCandidate> selected = bestBySection.values().stream()
                    .sorted(Comparator.comparingDouble(PairCandidate::difference)).limit(count).toList();
            if (selected.size() != count) throw new IllegalStateException("insufficient distinct starts");
            long elapsed = System.nanoTime() - began, finalExamined = examined, finalReachable = reachable;
            return selected.stream().map(pair -> selection(pair, candidates.size(), bound.size(),
                    desiredDistance, finalExamined, finalReachable, elapsed)).toList();
        }

        private List<BoundCandidate> bind(Collection<BlockPos> positions) {
            Set<BlockPos> unique = new LinkedHashSet<>();
            List<BoundCandidate> result = new ArrayList<>();
            for (BlockPos position : positions) if (unique.add(position.immutable())) {
                BoundCandidate bound = bind(position);
                if (bound != null) result.add(bound);
            }
            return List.copyOf(result);
        }

        @Nullable
        private BoundCandidate bind(BlockPos position) {
            for (BlockPos candidate : candidateAnchors(position)) {
                BaseClusterTopology topology = topologies.get(SectionPos.of(candidate));
                if (topology == null || topology.geometry().channel() != channel) continue;
                int component = topology.componentAt(Math.floorMod(candidate.getX(), 16),
                        Math.floorMod(candidate.getY(), 16), Math.floorMod(candidate.getZ(), 16));
                Integer node = component < 0 ? null : indexes.get(new NodeRef(topology.section(), component));
                if (node != null) return new BoundCandidate(position.immutable(), node);
            }
            return null;
        }

        private boolean canReach(int source, int target) { return reachable(source).get(target); }

        private BitSet reachable(int source) {
            return reachability.computeIfAbsent(source, ignored -> {
                BitSet reached = new BitSet(nodes.size());
                ArrayDeque<Integer> open = new ArrayDeque<>();
                reached.set(source);
                open.add(source);
                while (!open.isEmpty()) for (int target : edges.get(open.removeFirst())) {
                    if (!reached.get(target)) { reached.set(target); open.addLast(target); }
                }
                return reached;
            });
        }

        private PairSelection selection(PairCandidate pair, int candidates, int bound,
                                        double requested, long examined, long reachable,
                                        long elapsed) {
            return new PairSelection(pair.start.position, pair.goal.position, requested,
                    pair.distance, candidates, bound, candidates - bound, pair.start.node,
                    pair.goal.node, 1, 1, reachable(pair.start.node).cardinality(),
                    examined, reachable, elapsed);
        }

        public Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dimension", dimension.location().toString());
            report.put("channel", channel.name());
            report.put("profile", profile.toString());
            report.put("sections", topologies.size());
            report.put("directedNodes", nodes.size());
            report.put("directedEdges", edges.stream().mapToInt(List::size).sum());
            report.put("buildMillis", millis(buildNanos));
            report.put("timingExcludedFromMacroTiming", true);
            report.put("proofScope", "PUBLISHED_PRIMITIVE_BASE_GRAPH_REACHABILITY");
            return report;
        }
    }

    public record PairSelection(BlockPos start, BlockPos goal, double requestedDistance,
                                double directDistance, int candidateCount, int boundCandidateCount,
                                int unboundCandidateCount, int sourceStrongComponentId,
                                int targetStrongComponentId, int sourceStrongComponentNodes,
                                int targetStrongComponentNodes, int reachableStrongComponentsFromSource,
                                long examinedCandidatePairs, long reachableCandidatePairs,
                                long selectionNanos) {
        public PairSelection { start = start.immutable(); goal = goal.immutable(); }

        public Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("method", "PUBLISHED_PRIMITIVE_BASE_GRAPH_REACHABILITY");
            report.put("requestedDistance", requestedDistance);
            report.put("directDistance", directDistance);
            report.put("candidateCount", candidateCount);
            report.put("boundCandidateCount", boundCandidateCount);
            report.put("unboundCandidateCount", unboundCandidateCount);
            report.put("sourceNodeId", sourceStrongComponentId);
            report.put("targetNodeId", targetStrongComponentId);
            report.put("reachableNodesFromSource", reachableStrongComponentsFromSource);
            report.put("examinedCandidatePairs", examinedCandidatePairs);
            report.put("reachableCandidatePairs", reachableCandidatePairs);
            report.put("selectionMillis", millis(selectionNanos));
            return report;
        }
    }

    private static List<BlockPos> candidateAnchors(BlockPos center) {
        List<BlockPos> result = new ArrayList<>(25);
        for (int distance = 0; distance <= 2; distance++) for (int dy = -distance; dy <= distance; dy++) {
            for (int dz = -distance; dz <= distance; dz++) {
                int dx = distance - Math.abs(dy) - Math.abs(dz);
                if (dx < 0) continue;
                result.add(center.offset(dx == 0 ? 0 : -dx, dy, dz));
                if (dx != 0) result.add(center.offset(dx, dy, dz));
            }
        }
        return List.copyOf(result);
    }

    private static SectionPos neighbor(SectionPos source, Direction face, int yShift) {
        SectionPos direct = SuperClusterTopology.offset(source, face, 1);
        return face.getAxis().isVertical() ? direct
                : SectionPos.of(direct.x(), direct.y() + yShift, direct.z());
    }

    private static double distance(BlockPos first, BlockPos second) {
        return Math.sqrt(first.distSqr(second));
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0D; }

    private static Map<String, Integer> sectionMap(SectionPos section) {
        return Map.of("x", section.x(), "y", section.y(), "z", section.z());
    }

    private record NodeRef(SectionPos section, int componentId) {}
    private record BoundCandidate(BlockPos position, int node) {}
    private record PairCandidate(BoundCandidate start, BoundCandidate goal,
                                 double distance, double difference) {}
    private record CachedParent(SuperClusterTopology topology,
                                @Nullable Object cacheEntry,
                                String source,
                                boolean counted) {
        private CachedParent countedCopy() {
            return counted ? this : new CachedParent(topology, cacheEntry, source, true);
        }
    }
    private record LinkResolution(@Nullable SuperClusterTopology.CrossingIndex links,
                                  String source,
                                  String cachedStatus) {}
    private record ProjectionResult(boolean mapped,
                                    boolean conclusive,
                                    String kind,
                                    String code,
                                    Map<String, Object> detail) {
        private static ProjectionResult mapped(String kind, Map<String, Object> detail) {
            return new ProjectionResult(true, true, kind, "MAPPED", Map.copyOf(detail));
        }

        private static ProjectionResult missing(String code,
                                                boolean conclusive,
                                                Map<String, Object> detail) {
            return new ProjectionResult(false, conclusive, "MISSING", code,
                    Map.copyOf(detail));
        }

        private Map<String, Object> report() {
            return Map.of(
                    "mapped", mapped,
                    "conclusive", conclusive,
                    "kind", kind,
                    "code", code,
                    "detail", detail
            );
        }
    }

    public enum Status { RUNNING, FOUND, EXHAUSTED, INCOMPLETE }
}
