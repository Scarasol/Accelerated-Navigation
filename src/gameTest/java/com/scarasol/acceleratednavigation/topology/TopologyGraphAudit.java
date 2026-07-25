package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

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

/** Test-only, hierarchy-independent reachability and corridor consistency audit. */
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
    private final Set<SectionPos> visitedSections = new HashSet<>();
    private final Set<SectionPos> unavailableSections = new LinkedHashSet<>();
    private final Map<NodeRef, NodeRef> previous = new HashMap<>();
    private final Map<TopologyService.ClusterKey, CompletableFuture<BaseClusterTopology>> requests =
            new LinkedHashMap<>();

    private Status status = Status.RUNNING;
    private NodeRef startBinding;
    private NodeRef goalBinding;
    private NodeRef nearest;
    private NodeRef reached;
    private String detail = "RUNNING";
    private long cpuNanos;
    private int expandedComponents;
    private int generatedConnections;
    private int requestedSections;
    private int completedSections;
    private int failedSections;
    private int minSectionX = Integer.MAX_VALUE;
    private int minSectionY = Integer.MAX_VALUE;
    private int minSectionZ = Integer.MAX_VALUE;
    private int maxSectionX = Integer.MIN_VALUE;
    private int maxSectionY = Integer.MIN_VALUE;
    private int maxSectionZ = Integer.MIN_VALUE;
    private double nearestDistance = Double.POSITIVE_INFINITY;
    private boolean initialized;

    public TopologyGraphAudit(TopologyService service,
                              ServerLevel level,
                              BlockPos start,
                              BlockPos goal,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              int nodeLimit) {
        this.service = Objects.requireNonNull(service, "service");
        this.level = Objects.requireNonNull(level, "level");
        this.start = Objects.requireNonNull(start, "start").immutable();
        this.goal = Objects.requireNonNull(goal, "goal").immutable();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (nodeLimit <= 0) {
            throw new IllegalArgumentException("nodeLimit must be positive");
        }
        this.nodeLimit = nodeLimit;
    }

    public Status step(int expansionBudget, long deadlineNanos) {
        if (status != Status.RUNNING) {
            return status;
        }
        if (expansionBudget <= 0) {
            throw new IllegalArgumentException("expansionBudget must be positive");
        }
        long started = System.nanoTime();
        try {
            if (!completeRequests()) {
                return status;
            }
            if (!initialized && !initialize()) {
                return status;
            }

            int expandedThisStep = 0;
            while (expandedThisStep < expansionBudget && System.nanoTime() < deadlineNanos) {
                if (open.isEmpty()) {
                    status = unavailableSections.isEmpty() ? Status.EXHAUSTED : Status.INCOMPLETE;
                    detail = unavailableSections.isEmpty()
                            ? "BASE_COMPONENT_GRAPH_EXHAUSTED"
                            : "UNAVAILABLE_TOPOLOGY_BOUNDARY";
                    return status;
                }
                NodeRef current = open.removeFirst();
                if (current.equals(goalBinding)) {
                    reached = current;
                    status = Status.FOUND;
                    detail = "BASE_COMPONENT_ROUTE_FOUND";
                    return status;
                }
                if (!requestMissingNeighbors(current)) {
                    open.addFirst(current);
                    return status;
                }

                expandedThisStep++;
                expandedComponents++;
                expand(current);
                if (status != Status.RUNNING) {
                    return status;
                }
            }
            return status;
        } finally {
            cpuNanos += System.nanoTime() - started;
        }
    }

    public Status status() {
        return status;
    }

    public void stop(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (status == Status.RUNNING) {
            status = Status.INCOMPLETE;
            detail = reason;
        }
    }

    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status.name());
        report.put("detail", detail);
        report.put("nodeLimit", nodeLimit);
        report.put("visitedComponents", discovered.size());
        report.put("expandedComponents", expandedComponents);
        report.put("generatedConnections", generatedConnections);
        report.put("visitedSections", visitedSections.size());
        report.put("requestedSections", requestedSections);
        report.put("completedSections", completedSections);
        report.put("failedSections", failedSections);
        report.put("cpuMillis", nanosToMillis(cpuNanos));
        report.put("startBinding", bindingReport(startBinding, start));
        report.put("goalBinding", bindingReport(goalBinding, goal));
        report.put("nearestGoalDistanceBlocks",
                Double.isFinite(nearestDistance) ? nearestDistance : null);
        report.put("nearestReachableComponent", nodeReport(nearest));
        report.put("reachableSectionBounds", sectionBounds());
        report.put("unavailableSections", unavailableSections.stream()
                .limit(32)
                .map(TopologyGraphAudit::sectionMap)
                .toList());
        report.put("witnessComponentCount", witness().size());
        report.put("nearestFrontier", frontierReport(nearest));
        return report;
    }

    public List<BlockPos> witnessAnchors() {
        return witness().stream().map(this::anchor).toList();
    }

    public static Map<String, Object> auditCorridor(TopologyService service,
                                                     ResourceKey<Level> dimension,
                                                     MacroSearch.Corridor corridor,
                                                     BaseClusterTopology.TraversalProfile profile) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(profile, "profile");
        List<String> errors = new ArrayList<>();
        int checked = 0;
        int aggregateEndpoints = 0;
        for (MacroSearch.Endpoint endpoint : corridor.endpoints()) {
            if (endpoint instanceof MacroSearch.AggregateEndpoint) {
                aggregateEndpoints++;
            }
        }
        if (aggregateEndpoints != 0) {
            errors.add("refined corridor leaked " + aggregateEndpoints + " aggregate endpoints");
        }

        for (int index = 0; index < corridor.connections().size(); index++) {
            MacroSearch.Connection connection = corridor.connections().get(index);
            if (connection.from().id() != corridor.endpoints().get(index).id()
                    || connection.to().id() != corridor.endpoints().get(index + 1).id()) {
                errors.add("connection " + index + " does not match corridor endpoint order");
                continue;
            }
            checked++;
            verifyConnection(service, dimension, connection, profile, index, errors);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("valid", errors.isEmpty());
        report.put("referenceCurrent", errors.stream().noneMatch(error ->
                error.contains("stale") || error.contains("unavailable")));
        report.put("checkedConnections", checked);
        report.put("aggregateEndpoints", aggregateEndpoints);
        report.put("errors", errors);
        return report;
    }

    /**
     * Builds a test-only directed connectivity index from already published base topology.
     * It deliberately does not call the hierarchy under test or any concrete Navigation.
     */
    public static PublishedConnectivity indexPublished(
            TopologyService service,
            ResourceKey<Level> dimension,
            Collection<SectionPos> sections,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        long started = System.nanoTime();

        Map<SectionPos, BaseClusterTopology> topologies = new LinkedHashMap<>();
        for (SectionPos section : sections) {
            BaseClusterTopology topology = service.topology(
                    new TopologyService.ClusterKey(dimension, section)
            );
            if (topology == null) {
                throw new IllegalStateException(
                        "published connectivity index is missing topology for " + section
                );
            }
            topologies.putIfAbsent(section, topology);
        }

        Map<NodeRef, List<NodeRef>> outgoing = new LinkedHashMap<>();
        Map<NodeRef, List<NodeRef>> incoming = new LinkedHashMap<>();
        for (Map.Entry<SectionPos, BaseClusterTopology> entry : topologies.entrySet()) {
            for (BaseClusterTopology.Component component : entry.getValue().components(channel)) {
                NodeRef node = new NodeRef(entry.getKey(), component.id());
                outgoing.put(node, new ArrayList<>());
                incoming.put(node, new ArrayList<>());
            }
        }

        int directedEdges = 0;
        for (Map.Entry<SectionPos, BaseClusterTopology> entry : topologies.entrySet()) {
            SectionPos section = entry.getKey();
            BaseClusterTopology topology = entry.getValue();
            for (BaseClusterTopology.Component component : topology.components(channel)) {
                NodeRef source = new NodeRef(section, component.id());
                for (BaseClusterTopology.LocalConnection local
                        : topology.outgoingConnections(component.id())) {
                    if (profile.supports(local)) {
                        directedEdges += addIndexedEdge(
                                outgoing,
                                incoming,
                                source,
                                new NodeRef(section, local.toComponent())
                        );
                    }
                }
                for (Direction face : Direction.values()) {
                    if (!component.touches(face)) {
                        continue;
                    }
                    SectionPos neighborSection = offset(section, face);
                    BaseClusterTopology neighbor = topologies.get(neighborSection);
                    if (neighbor == null) {
                        continue;
                    }
                    for (BaseClusterTopology.Component target
                            : neighbor.boundaryComponents(face.getOpposite(), channel)) {
                        if (!SuperClusterTopology.boundaryBands(
                                topology,
                                component,
                                neighbor,
                                target,
                                face,
                                channel,
                                profile
                        ).isEmpty()) {
                            directedEdges += addIndexedEdge(
                                    outgoing,
                                    incoming,
                                    source,
                                    new NodeRef(neighborSection, target.id())
                            );
                        }
                    }
                }
            }
        }

        List<NodeRef> finishOrder = directedFinishOrder(outgoing);
        Map<NodeRef, Integer> stronglyConnected = new HashMap<>();
        List<Integer> componentSizes = new ArrayList<>();
        ArrayDeque<NodeRef> open = new ArrayDeque<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            NodeRef seed = finishOrder.get(index);
            if (stronglyConnected.containsKey(seed)) {
                continue;
            }
            int componentId = componentSizes.size();
            int componentSize = 0;
            stronglyConnected.put(seed, componentId);
            open.addLast(seed);
            while (!open.isEmpty()) {
                NodeRef current = open.removeFirst();
                componentSize++;
                for (NodeRef previous : incoming.getOrDefault(current, List.of())) {
                    if (stronglyConnected.putIfAbsent(previous, componentId) == null) {
                        open.addLast(previous);
                    }
                }
            }
            componentSizes.add(componentSize);
        }

        List<List<Integer>> componentOutgoing = condensationEdges(
                outgoing,
                stronglyConnected,
                componentSizes.size()
        );
        return new PublishedConnectivity(
                dimension,
                channel,
                profile,
                Map.copyOf(topologies),
                Map.copyOf(stronglyConnected),
                List.copyOf(componentSizes),
                componentOutgoing,
                directedEdges,
                System.nanoTime() - started
        );
    }

    private static List<List<Integer>> condensationEdges(
            Map<NodeRef, List<NodeRef>> outgoing,
            Map<NodeRef, Integer> stronglyConnected,
            int componentCount) {
        List<Set<Integer>> mutable = new ArrayList<>(componentCount);
        for (int component = 0; component < componentCount; component++) {
            mutable.add(new LinkedHashSet<>());
        }
        outgoing.forEach((source, targets) -> {
            int sourceComponent = stronglyConnected.get(source);
            for (NodeRef target : targets) {
                int targetComponent = stronglyConnected.get(target);
                if (sourceComponent != targetComponent) {
                    mutable.get(sourceComponent).add(targetComponent);
                }
            }
        });
        return mutable.stream()
                .map(targets -> targets.stream().sorted().toList())
                .toList();
    }

    private static int addIndexedEdge(Map<NodeRef, List<NodeRef>> outgoing,
                                      Map<NodeRef, List<NodeRef>> incoming,
                                      NodeRef source,
                                      NodeRef target) {
        List<NodeRef> sourceEdges = outgoing.get(source);
        List<NodeRef> targetEdges = incoming.get(target);
        if (sourceEdges == null || targetEdges == null) {
            return 0;
        }
        sourceEdges.add(target);
        targetEdges.add(source);
        return 1;
    }

    private static List<NodeRef> directedFinishOrder(Map<NodeRef, List<NodeRef>> outgoing) {
        List<NodeRef> result = new ArrayList<>(outgoing.size());
        Set<NodeRef> visited = new HashSet<>();
        ArrayDeque<NodeRef> nodes = new ArrayDeque<>();
        ArrayDeque<Integer> neighborIndexes = new ArrayDeque<>();
        for (NodeRef seed : outgoing.keySet()) {
            if (!visited.add(seed)) {
                continue;
            }
            nodes.addLast(seed);
            neighborIndexes.addLast(0);
            while (!nodes.isEmpty()) {
                NodeRef current = nodes.peekLast();
                int neighborIndex = neighborIndexes.removeLast();
                List<NodeRef> neighbors = outgoing.getOrDefault(current, List.of());
                if (neighborIndex < neighbors.size()) {
                    neighborIndexes.addLast(neighborIndex + 1);
                    NodeRef neighbor = neighbors.get(neighborIndex);
                    if (visited.add(neighbor)) {
                        nodes.addLast(neighbor);
                        neighborIndexes.addLast(0);
                    }
                    continue;
                }
                nodes.removeLast();
                result.add(current);
            }
        }
        return result;
    }

    public static final class PublishedConnectivity {
        private final ResourceKey<Level> dimension;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Map<SectionPos, BaseClusterTopology> topologies;
        private final Map<NodeRef, Integer> stronglyConnected;
        private final List<Integer> componentSizes;
        private final List<List<Integer>> componentOutgoing;
        private final Map<Integer, BitSet> reachabilityByComponent = new HashMap<>();
        private final int directedEdges;
        private final long buildNanos;

        private PublishedConnectivity(ResourceKey<Level> dimension,
                                      BaseClusterTopology.Channel channel,
                                      BaseClusterTopology.TraversalProfile profile,
                                      Map<SectionPos, BaseClusterTopology> topologies,
                                      Map<NodeRef, Integer> stronglyConnected,
                                      List<Integer> componentSizes,
                                      List<List<Integer>> componentOutgoing,
                                      int directedEdges,
                                      long buildNanos) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.topologies = topologies;
            this.stronglyConnected = stronglyConnected;
            this.componentSizes = componentSizes;
            this.componentOutgoing = componentOutgoing;
            this.directedEdges = directedEdges;
            this.buildNanos = buildNanos;
        }

        public PairSelection selectPair(Collection<BlockPos> candidates,
                                        double desiredDistance) {
            Objects.requireNonNull(candidates, "candidates");
            if (desiredDistance <= 0.0D) {
                throw new IllegalArgumentException("desiredDistance must be positive");
            }
            long started = System.nanoTime();
            List<BoundCandidate> boundCandidates = new ArrayList<>();
            Set<BlockPos> unique = new LinkedHashSet<>();
            int unbound = 0;
            for (BlockPos candidate : candidates) {
                BlockPos immutable = Objects.requireNonNull(candidate, "candidate").immutable();
                if (!unique.add(immutable)) {
                    continue;
                }
                BoundCandidate bound = bindCandidate(immutable);
                if (bound == null) {
                    unbound++;
                    continue;
                }
                boundCandidates.add(bound);
            }

            BoundCandidate bestStart = null;
            BoundCandidate bestGoal = null;
            double bestDistance = 0.0D;
            double bestDifference = Double.POSITIVE_INFINITY;
            long examinedPairs = 0L;
            long reachablePairs = 0L;
            search:
            for (int first = 0; first < boundCandidates.size(); first++) {
                BoundCandidate firstCandidate = boundCandidates.get(first);
                for (int second = first + 1; second < boundCandidates.size(); second++) {
                    BoundCandidate secondCandidate = boundCandidates.get(second);
                    examinedPairs++;
                    double directDistance = distance(
                            firstCandidate.position(),
                            secondCandidate.position()
                    );
                    double difference = Math.abs(directDistance - desiredDistance);
                    if (difference >= bestDifference) {
                        continue;
                    }

                    boolean forward = canReach(
                            firstCandidate.strongComponent(),
                            secondCandidate.strongComponent()
                    );
                    boolean reverse = canReach(
                            secondCandidate.strongComponent(),
                            firstCandidate.strongComponent()
                    );
                    if (!forward && !reverse) {
                        continue;
                    }
                    reachablePairs++;
                    bestStart = forward ? firstCandidate : secondCandidate;
                    bestGoal = forward ? secondCandidate : firstCandidate;
                    bestDifference = difference;
                    bestDistance = directDistance;
                    if (bestDifference == 0.0D) {
                        break search;
                    }
                }
            }
            if (bestStart == null || bestGoal == null) {
                throw new IllegalStateException(
                        "no directionally reachable candidate pair for " + dimension.location()
                                + " at distance " + desiredDistance
                );
            }
            int sourceComponent = bestStart.strongComponent();
            int targetComponent = bestGoal.strongComponent();
            return new PairSelection(
                    bestStart.position(),
                    bestGoal.position(),
                    desiredDistance,
                    bestDistance,
                    unique.size(),
                    unique.size() - unbound,
                    unbound,
                    sourceComponent,
                    targetComponent,
                    componentSizes.get(sourceComponent),
                    componentSizes.get(targetComponent),
                    reachableFrom(sourceComponent).cardinality(),
                    examinedPairs,
                    reachablePairs,
                    System.nanoTime() - started
            );
        }

        private boolean canReach(int sourceComponent, int targetComponent) {
            return reachableFrom(sourceComponent).get(targetComponent);
        }

        private BitSet reachableFrom(int sourceComponent) {
            return reachabilityByComponent.computeIfAbsent(sourceComponent, source -> {
                BitSet reached = new BitSet(componentOutgoing.size());
                ArrayDeque<Integer> open = new ArrayDeque<>();
                reached.set(source);
                open.addLast(source);
                while (!open.isEmpty()) {
                    int current = open.removeFirst();
                    for (int target : componentOutgoing.get(current)) {
                        if (!reached.get(target)) {
                            reached.set(target);
                            open.addLast(target);
                        }
                    }
                }
                return reached;
            });
        }

        public Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("dimension", dimension.location().toString());
            report.put("channel", channel.name());
            report.put("profile", profile.toString());
            report.put("sections", topologies.size());
            report.put("directedNodes", stronglyConnected.size());
            report.put("directedEdges", directedEdges);
            report.put("stronglyConnectedComponents", componentSizes.size());
            report.put("condensationEdges", componentOutgoing.stream()
                    .mapToInt(List::size)
                    .sum());
            report.put("largestStrongComponentNodes", componentSizes.stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0));
            report.put("buildMillis", nanosToMillis(buildNanos));
            report.put("timingExcludedFromMacroTiming", true);
            report.put("proofScope", "PUBLISHED_BASE_GRAPH_DIRECTED_REACHABILITY");
            return report;
        }

        @Nullable
        private BoundCandidate bindCandidate(BlockPos position) {
            SectionPos section = SectionPos.of(position);
            BaseClusterTopology topology = topologies.get(section);
            if (topology == null) {
                return null;
            }
            BaseClusterTopology.Component component = topology.nearestComponent(
                    channel,
                    Math.floorMod(position.getX(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getY(), BaseClusterTopology.SIDE),
                    Math.floorMod(position.getZ(), BaseClusterTopology.SIDE),
                    2
            );
            if (component == null) {
                return null;
            }
            Integer strongComponent = stronglyConnected.get(
                    new NodeRef(section, component.id())
            );
            return strongComponent == null
                    ? null
                    : new BoundCandidate(position, strongComponent);
        }
    }

    public record PairSelection(BlockPos start,
                                BlockPos goal,
                                double requestedDistance,
                                double directDistance,
                                int candidateCount,
                                int boundCandidateCount,
                                int unboundCandidateCount,
                                int sourceStrongComponentId,
                                int targetStrongComponentId,
                                int sourceStrongComponentNodes,
                                int targetStrongComponentNodes,
                                int reachableStrongComponentsFromSource,
                                long examinedCandidatePairs,
                                long reachableCandidatePairs,
                                long selectionNanos) {

        public PairSelection {
            start = start.immutable();
            goal = goal.immutable();
        }

        public Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("method", "PUBLISHED_BASE_GRAPH_CONDENSATION_REACHABILITY");
            report.put("proofScope", "STRUCTURAL_DIRECTED_REACHABILITY");
            report.put("requestedDistance", requestedDistance);
            report.put("directDistance", directDistance);
            report.put("candidateCount", candidateCount);
            report.put("boundCandidateCount", boundCandidateCount);
            report.put("unboundCandidateCount", unboundCandidateCount);
            report.put("sourceStrongComponentId", sourceStrongComponentId);
            report.put("targetStrongComponentId", targetStrongComponentId);
            report.put("sameStrongComponent", sourceStrongComponentId == targetStrongComponentId);
            report.put("sourceStrongComponentNodes", sourceStrongComponentNodes);
            report.put("targetStrongComponentNodes", targetStrongComponentNodes);
            report.put("reachableStrongComponentsFromSource", reachableStrongComponentsFromSource);
            report.put("examinedCandidatePairs", examinedCandidatePairs);
            report.put("reachableCandidatePairs", reachableCandidatePairs);
            report.put("selectionMillis", nanosToMillis(selectionNanos));
            report.put("timingExcludedFromMacroTiming", true);
            report.put("physicalExecutabilityClaimed", false);
            return report;
        }
    }

    private record BoundCandidate(BlockPos position, int strongComponent) {
    }

    private boolean initialize() {
        SectionPos startSection = SectionPos.of(start);
        SectionPos goalSection = SectionPos.of(goal);
        BaseClusterTopology startTopology = topology(startSection);
        BaseClusterTopology goalTopology = topology(goalSection);
        if (startTopology == null || goalTopology == null) {
            if (startTopology == null) {
                request(startSection);
            }
            if (goalTopology == null) {
                request(goalSection);
            }
            return false;
        }

        startBinding = bind(startSection, startTopology, start);
        goalBinding = bind(goalSection, goalTopology, goal);
        initialized = true;
        if (startBinding == null || goalBinding == null) {
            status = Status.ENDPOINT_UNBOUND;
            detail = startBinding == null && goalBinding == null
                    ? "START_AND_GOAL_UNBOUND"
                    : startBinding == null ? "START_UNBOUND" : "GOAL_UNBOUND";
            return false;
        }
        discover(null, startBinding);
        return true;
    }

    private boolean completeRequests() {
        if (requests.isEmpty()) {
            return true;
        }
        if (requests.values().stream().anyMatch(request -> !request.isDone())) {
            return false;
        }
        for (Map.Entry<TopologyService.ClusterKey, CompletableFuture<BaseClusterTopology>> entry
                : List.copyOf(requests.entrySet())) {
            try {
                entry.getValue().join();
                completedSections++;
            } catch (RuntimeException failure) {
                failedSections++;
                unavailableSections.add(entry.getKey().section());
            }
            requests.remove(entry.getKey());
        }
        return true;
    }

    private boolean requestMissingNeighbors(NodeRef current) {
        BaseClusterTopology topology = requireTopology(current.section());
        BaseClusterTopology.Component component = topology.component(current.componentId());
        boolean ready = true;
        for (Direction face : Direction.values()) {
            if (!component.touches(face)) {
                continue;
            }
            SectionPos neighbor = offset(current.section(), face);
            if (!validHeight(neighbor) || topology(neighbor) != null
                    || unavailableSections.contains(neighbor)) {
                continue;
            }
            if (!chunkLoaded(neighbor)) {
                unavailableSections.add(neighbor);
                continue;
            }
            request(neighbor);
            ready = false;
        }
        return ready;
    }

    private void expand(NodeRef current) {
        BaseClusterTopology topology = requireTopology(current.section());
        BaseClusterTopology.Component component = topology.component(current.componentId());
        visit(current, component);

        for (BaseClusterTopology.LocalConnection local
                : topology.outgoingConnections(component.id())) {
            if (profile.supports(local)) {
                generatedConnections++;
                discover(current, new NodeRef(current.section(), local.toComponent()));
                if (status != Status.RUNNING) {
                    return;
                }
            }
        }

        for (Direction face : Direction.values()) {
            if (!component.touches(face)) {
                continue;
            }
            SectionPos neighborSection = offset(current.section(), face);
            BaseClusterTopology neighbor = topology(neighborSection);
            if (neighbor == null) {
                continue;
            }
            for (BaseClusterTopology.Component target
                    : neighbor.boundaryComponents(face.getOpposite(), channel)) {
                if (SuperClusterTopology.boundaryBands(
                        topology,
                        component,
                        neighbor,
                        target,
                        face,
                        channel,
                        profile
                ).isEmpty()) {
                    continue;
                }
                generatedConnections++;
                discover(current, new NodeRef(neighborSection, target.id()));
                if (status != Status.RUNNING) {
                    return;
                }
            }
        }
    }

    private void discover(@Nullable NodeRef from, NodeRef target) {
        if (!discovered.add(target)) {
            return;
        }
        if (discovered.size() > nodeLimit) {
            status = Status.LIMIT_REACHED;
            detail = "REFERENCE_NODE_LIMIT_REACHED";
            return;
        }
        if (from != null) {
            previous.put(target, from);
        }
        open.addLast(target);
        if (target.equals(goalBinding)) {
            reached = target;
            status = Status.FOUND;
            detail = "BASE_COMPONENT_ROUTE_FOUND";
        }
    }

    private void visit(NodeRef node, BaseClusterTopology.Component component) {
        SectionPos section = node.section();
        visitedSections.add(section);
        minSectionX = Math.min(minSectionX, section.x());
        minSectionY = Math.min(minSectionY, section.y());
        minSectionZ = Math.min(minSectionZ, section.z());
        maxSectionX = Math.max(maxSectionX, section.x());
        maxSectionY = Math.max(maxSectionY, section.y());
        maxSectionZ = Math.max(maxSectionZ, section.z());
        double distance = distance(anchor(section, component), goal);
        if (distance < nearestDistance) {
            nearestDistance = distance;
            nearest = node;
        }
    }

    private void request(SectionPos section) {
        if (!validHeight(section) || requests.keySet().stream()
                .anyMatch(key -> key.section().equals(section))) {
            return;
        }
        if (!chunkLoaded(section)) {
            unavailableSections.add(section);
            return;
        }
        TopologyService.ClusterKey key = new TopologyService.ClusterKey(level.dimension(), section);
        requests.put(key, service.requestCluster(
                level,
                section,
                NavigationScheduler.Priority.BACKGROUND
        ));
        requestedSections++;
    }

    private Map<String, Object> bindingReport(@Nullable NodeRef binding, BlockPos requested) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("requested", positionMap(requested));
        report.put("section", sectionMap(SectionPos.of(requested)));
        report.put("bound", binding != null);
        if (binding != null) {
            report.putAll(nodeReport(binding));
            BlockPos matchedCell = matchedBindingCell(binding, requested);
            report.put("matchedCell", positionMap(matchedCell));
            report.put("bindingDistanceBlocks", distance(matchedCell, requested));
            report.put("componentAnchorDistanceBlocks", distance(anchor(binding), requested));
        }
        return report;
    }

    private BlockPos matchedBindingCell(NodeRef binding, BlockPos requested) {
        BaseClusterTopology topology = requireTopology(binding.section());
        int x = Math.floorMod(requested.getX(), BaseClusterTopology.SIDE);
        int y = Math.floorMod(requested.getY(), BaseClusterTopology.SIDE);
        int z = Math.floorMod(requested.getZ(), BaseClusterTopology.SIDE);
        for (int distance = 0; distance <= 2; distance++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    int dxMagnitude = distance - Math.abs(dy) - Math.abs(dz);
                    if (dxMagnitude < 0) {
                        continue;
                    }
                    for (int sign : dxMagnitude == 0 ? new int[]{1} : new int[]{-1, 1}) {
                        int nx = x + sign * dxMagnitude;
                        int ny = y + dy;
                        int nz = z + dz;
                        if ((nx | ny | nz) < 0 || nx >= BaseClusterTopology.SIDE
                                || ny >= BaseClusterTopology.SIDE
                                || nz >= BaseClusterTopology.SIDE) {
                            continue;
                        }
                        BaseClusterTopology.Component candidate = topology.componentAt(
                                channel,
                                nx,
                                ny,
                                nz
                        );
                        if (candidate != null && candidate.id() == binding.componentId()) {
                            return new BlockPos(
                                    binding.section().minBlockX() + nx,
                                    binding.section().minBlockY() + ny,
                                    binding.section().minBlockZ() + nz
                            );
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("could not reproduce endpoint binding for " + requested);
    }

    private Map<String, Object> nodeReport(@Nullable NodeRef node) {
        if (node == null) {
            return Map.of();
        }
        BaseClusterTopology topology = topology(node.section());
        if (topology == null) {
            return Map.of(
                    "section", sectionMap(node.section()),
                    "componentId", node.componentId(),
                    "topologyPresent", false
            );
        }
        BaseClusterTopology.Component component = topology.component(node.componentId());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("section", sectionMap(node.section()));
        report.put("componentId", node.componentId());
        report.put("anchor", positionMap(anchor(node.section(), component)));
        report.put("cells", component.cellCount());
        report.put("boundaryFaceMask", component.boundaryFaceMask());
        report.put("requiresExactCheck", component.requiresExactCheck());
        report.put("topologyRevision", topology.revision());
        return report;
    }

    private Map<String, Object> sectionBounds() {
        if (visitedSections.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                "min", Map.of("x", minSectionX, "y", minSectionY, "z", minSectionZ),
                "max", Map.of("x", maxSectionX, "y", maxSectionY, "z", maxSectionZ)
        );
    }

    private Map<String, Object> frontierReport(@Nullable NodeRef node) {
        if (node == null) {
            return Map.of();
        }
        BaseClusterTopology topology = topology(node.section());
        if (topology == null) {
            return Map.of("component", nodeReport(node));
        }
        BaseClusterTopology.Component source = topology.component(node.componentId());
        List<Map<String, Object>> boundaries = new ArrayList<>();
        int allowedLocal = 0;
        int rejectedLocal = 0;
        for (BaseClusterTopology.LocalConnection local
                : topology.outgoingConnections(source.id())) {
            if (profile.supports(local)) {
                allowedLocal++;
            } else {
                rejectedLocal++;
            }
        }
        for (Direction face : Direction.values()) {
            Map<String, Object> boundary = boundaryReport(node.section(), topology, source, face);
            boundaries.add(boundary);
        }
        boundaries.sort(Comparator.comparingDouble(boundary ->
                distance(sectionCenter(sectionFromMap(boundary.get("neighborSection"))), goal)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("component", nodeReport(node));
        report.put("allowedLocalConnections", allowedLocal);
        report.put("profileRejectedLocalConnections", rejectedLocal);
        report.put("boundaries", boundaries);
        return report;
    }

    private Map<String, Object> boundaryReport(SectionPos sourceSection,
                                                BaseClusterTopology sourceTopology,
                                                BaseClusterTopology.Component source,
                                                Direction face) {
        SectionPos neighborSection = offset(sourceSection, face);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("face", face.getName());
        report.put("neighborSection", sectionMap(neighborSection));
        report.put("sourceTouchesFace", source.touches(face));
        report.put("sourceBoundaryCells", bitCount(source.boundaryMask(face)));
        if (!source.touches(face)) {
            report.put("result", "SOURCE_NOT_ON_BOUNDARY");
            return report;
        }
        if (!validHeight(neighborSection)) {
            report.put("result", "OUTSIDE_BUILD_HEIGHT");
            return report;
        }
        BaseClusterTopology neighbor = topology(neighborSection);
        if (neighbor == null) {
            report.put("result", chunkLoaded(neighborSection)
                    ? "TOPOLOGY_NOT_PUBLISHED"
                    : "CHUNK_NOT_LOADED");
            return report;
        }

        int targets = 0;
        int rawOverlaps = 0;
        int compatibleTargets = 0;
        int compatibleBands = 0;
        for (BaseClusterTopology.Component target
                : neighbor.boundaryComponents(face.getOpposite(), channel)) {
            targets++;
            rawOverlaps += rawOverlapCells(source, target, face, profile);
            List<SuperClusterTopology.BoundaryBand> bands =
                    SuperClusterTopology.boundaryBands(
                            sourceTopology,
                            source,
                            neighbor,
                            target,
                            face,
                            channel,
                            profile
                    );
            if (!bands.isEmpty()) {
                compatibleTargets++;
                compatibleBands += bands.size();
            }
        }
        report.put("targetBoundaryComponents", targets);
        report.put("rawOverlapCells", rawOverlaps);
        report.put("profileCompatibleTargets", compatibleTargets);
        report.put("profileCompatibleBands", compatibleBands);
        report.put("result", compatibleTargets > 0
                ? "CONNECTED"
                : targets == 0
                ? "NO_TARGET_BOUNDARY_COMPONENT"
                : rawOverlaps == 0
                ? "NO_GEOMETRIC_OVERLAP"
                : "PROFILE_WIDTH_OR_HEADROOM_REJECTED");
        return report;
    }

    private int rawOverlapCells(BaseClusterTopology.Component source,
                                BaseClusterTopology.Component target,
                                Direction face,
                                BaseClusterTopology.TraversalProfile profile) {
        Direction opposite = face.getOpposite();
        int maximum = channel == BaseClusterTopology.Channel.VOLUME || face.getAxis().isVertical()
                ? 0
                : Math.max(profile.maxStep(), profile.maxDrop());
        int overlaps = 0;
        for (int shift = -maximum; shift <= maximum; shift++) {
            if (shift > profile.maxStep() || -shift > profile.maxDrop()) {
                continue;
            }
            for (int v = 0; v < BaseClusterTopology.SIDE; v++) {
                int targetV = v + shift;
                if (targetV < 0 || targetV >= BaseClusterTopology.SIDE) {
                    continue;
                }
                for (int u = 0; u < BaseClusterTopology.SIDE; u++) {
                    int sourceIndex = (v << 4) | u;
                    int targetIndex = (targetV << 4) | u;
                    if (bitSet(source, face, sourceIndex)
                            && bitSet(target, opposite, targetIndex)) {
                        overlaps++;
                    }
                }
            }
        }
        return overlaps;
    }

    private List<NodeRef> witness() {
        NodeRef end = reached != null ? reached : nearest;
        if (end == null) {
            return List.of();
        }
        ArrayDeque<NodeRef> reversed = new ArrayDeque<>();
        for (NodeRef current = end; current != null; current = previous.get(current)) {
            reversed.addFirst(current);
        }
        return List.copyOf(reversed);
    }

    @Nullable
    private NodeRef bind(SectionPos section,
                         BaseClusterTopology topology,
                         BlockPos position) {
        BaseClusterTopology.Component component = topology.nearestComponent(
                channel,
                Math.floorMod(position.getX(), BaseClusterTopology.SIDE),
                Math.floorMod(position.getY(), BaseClusterTopology.SIDE),
                Math.floorMod(position.getZ(), BaseClusterTopology.SIDE),
                2
        );
        return component == null ? null : new NodeRef(section, component.id());
    }

    @Nullable
    private BaseClusterTopology topology(SectionPos section) {
        return service.topology(new TopologyService.ClusterKey(level.dimension(), section));
    }

    private BaseClusterTopology requireTopology(SectionPos section) {
        BaseClusterTopology topology = topology(section);
        if (topology == null) {
            throw new IllegalStateException("audit topology is unavailable for " + section);
        }
        return topology;
    }

    private boolean chunkLoaded(SectionPos section) {
        return level.getChunkSource().getChunkNow(section.x(), section.z()) != null;
    }

    private boolean validHeight(SectionPos section) {
        return section.y() >= level.getMinSection() && section.y() < level.getMaxSection();
    }

    private BlockPos anchor(NodeRef node) {
        return anchor(node.section(), requireTopology(node.section()).component(node.componentId()));
    }

    private static BlockPos anchor(SectionPos section,
                                   BaseClusterTopology.Component component) {
        return new BlockPos(
                section.minBlockX() + component.anchorX(),
                section.minBlockY() + component.anchorY(),
                section.minBlockZ() + component.anchorZ()
        );
    }

    private static void verifyConnection(TopologyService service,
                                         ResourceKey<Level> dimension,
                                         MacroSearch.Connection connection,
                                         BaseClusterTopology.TraversalProfile profile,
                                         int index,
                                         List<String> errors) {
        if (connection.transition() instanceof MacroSearch.AggregateTransition) {
            errors.add("connection " + index + " leaked an aggregate transition");
            return;
        }
        if (connection.transition() instanceof MacroSearch.MembershipTransition) {
            verifyMembership(service, dimension, connection, index, errors);
            return;
        }
        if (!(connection.from() instanceof MacroSearch.ComponentEndpoint from)
                || !(connection.to() instanceof MacroSearch.ComponentEndpoint to)) {
            errors.add("connection " + index + " has non-component structural endpoints");
            return;
        }
        BaseClusterTopology source = service.topology(
                new TopologyService.ClusterKey(dimension, from.section())
        );
        BaseClusterTopology target = service.topology(
                new TopologyService.ClusterKey(dimension, to.section())
        );
        if (source == null || target == null) {
            errors.add("connection " + index + " references unavailable topology");
            return;
        }
        if (source.revision() != from.revision() || target.revision() != to.revision()) {
            errors.add("connection " + index + " references stale topology");
            return;
        }
        BaseClusterTopology.Component sourceComponent = source.component(from.componentId());
        BaseClusterTopology.Component targetComponent = target.component(to.componentId());
        if (connection.transition() instanceof MacroSearch.LocalTransition local) {
            BaseClusterTopology.LocalConnection requirement = local.requirement();
            if (!from.section().equals(to.section())
                    || requirement.fromComponent() != from.componentId()
                    || requirement.toComponent() != to.componentId()
                    || !source.outgoingConnections(from.componentId()).contains(requirement)
                    || !profile.supports(requirement)) {
                errors.add("connection " + index + " has an invalid local transition");
            }
            return;
        }
        if (connection.transition() instanceof MacroSearch.BoundaryTransition boundary) {
            if (!offset(from.section(), boundary.face()).equals(to.section())) {
                errors.add("connection " + index + " crosses a non-adjacent section boundary");
                return;
            }
            List<SuperClusterTopology.BoundaryBand> recomputed =
                    SuperClusterTopology.boundaryBands(
                            source,
                            sourceComponent,
                            target,
                            targetComponent,
                            boundary.face(),
                            from.channel(),
                            profile
                    );
            if (recomputed.isEmpty()) {
                errors.add("connection " + index + " no longer has a compatible boundary band");
            }
            return;
        }
        errors.add("connection " + index + " has an unknown transition type");
    }

    private static void verifyMembership(TopologyService service,
                                         ResourceKey<Level> dimension,
                                         MacroSearch.Connection connection,
                                         int index,
                                         List<String> errors) {
        MacroSearch.ExactEndpoint exact;
        MacroSearch.ComponentEndpoint component;
        if (connection.from() instanceof MacroSearch.ExactEndpoint from
                && connection.to() instanceof MacroSearch.ComponentEndpoint to) {
            exact = from;
            component = to;
        } else if (connection.from() instanceof MacroSearch.ComponentEndpoint from
                && connection.to() instanceof MacroSearch.ExactEndpoint to) {
            exact = to;
            component = from;
        } else {
            errors.add("connection " + index + " has invalid membership endpoints");
            return;
        }
        BaseClusterTopology topology = service.topology(
                new TopologyService.ClusterKey(dimension, component.section())
        );
        if (topology == null || topology.revision() != component.revision()) {
            errors.add("connection " + index + " membership topology is unavailable or stale");
            return;
        }
        BaseClusterTopology.Component bound = topology.nearestComponent(
                component.channel(),
                Math.floorMod(exact.anchor().getX(), BaseClusterTopology.SIDE),
                Math.floorMod(exact.anchor().getY(), BaseClusterTopology.SIDE),
                Math.floorMod(exact.anchor().getZ(), BaseClusterTopology.SIDE),
                2
        );
        if (bound == null || bound.id() != component.componentId()) {
            errors.add("connection " + index + " membership does not match endpoint binding");
        }
    }

    private static boolean bitSet(BaseClusterTopology.Component component,
                                  Direction face,
                                  int index) {
        return (component.boundaryMaskWord(face, index >>> 6)
                & (1L << (index & 63))) != 0L;
    }

    private static int bitCount(long[] words) {
        int result = 0;
        for (long word : words) {
            result += Long.bitCount(word);
        }
        return result;
    }

    private static SectionPos offset(SectionPos section, Direction face) {
        return SectionPos.of(
                section.x() + face.getStepX(),
                section.y() + face.getStepY(),
                section.z() + face.getStepZ()
        );
    }

    private static BlockPos sectionCenter(SectionPos section) {
        return new BlockPos(section.minBlockX() + 8, section.minBlockY() + 8,
                section.minBlockZ() + 8);
    }

    @SuppressWarnings("unchecked")
    private static SectionPos sectionFromMap(Object value) {
        Map<String, Integer> section = (Map<String, Integer>) value;
        return SectionPos.of(section.get("x"), section.get("y"), section.get("z"));
    }

    private static Map<String, Integer> sectionMap(SectionPos section) {
        return Map.of("x", section.x(), "y", section.y(), "z", section.z());
    }

    private static Map<String, Integer> positionMap(BlockPos position) {
        return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
    }

    private static double distance(BlockPos first, BlockPos second) {
        double dx = second.getX() - first.getX();
        double dy = second.getY() - first.getY();
        double dz = second.getZ() - first.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    public enum Status {
        RUNNING,
        FOUND,
        EXHAUSTED,
        LIMIT_REACHED,
        INCOMPLETE,
        ENDPOINT_UNBOUND
    }

    private record NodeRef(SectionPos section, int componentId) {
    }
}
