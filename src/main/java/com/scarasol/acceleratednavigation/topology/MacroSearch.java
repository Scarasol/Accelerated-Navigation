package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resumable Weighted A* over an already-built structural component graph. */
public final class MacroSearch implements ResumableSearch<MacroSearch.Corridor> {

    public static final float DEFAULT_WEIGHT = 1.25F;

    private final Graph graph;
    private final float weight;
    private final int maxVisitedNodes;
    private final BinaryHeap openSet = new BinaryHeap();
    private final Long2ObjectOpenHashMap<SearchNode> nodes = new Long2ObjectOpenHashMap<>();
    private final Set<SearchNode> blockedNodes = new HashSet<>();
    private final Map<DependencyKey, Set<SearchNode>> waitingByDependency = new HashMap<>();
    private final Set<DependencyKey> encounteredPendingDependencies = new HashSet<>();
    private final Set<DependencyKey> encounteredUnavailableDependencies = new HashSet<>();
    private final ExpansionBuffer expansionBuffer = new ExpansionBuffer();

    private Status status = Status.RUNNING;
    private Failure failure = Failure.NONE;
    private SectionPos blockedSection;
    private Corridor result;
    private boolean initialized;
    private boolean waitingForTopology;
    private long expandedNodes;
    private long generatedConnections;
    private long reopenedNodes;
    private long reexpandedBlockedNodes;
    private int maximumDegree;
    private int maximumBlockedNodes;

    public MacroSearch(Graph graph, float weight) {
        this(graph, weight, Integer.MAX_VALUE);
    }

    public MacroSearch(Graph graph,
                       float weight,
                       int maxVisitedNodes) {
        this.graph = Objects.requireNonNull(graph, "graph");
        if (!Float.isFinite(weight) || weight < 1.0F) {
            throw new IllegalArgumentException("weight must be finite and at least 1.0");
        }
        if (maxVisitedNodes <= 0) {
            throw new IllegalArgumentException("maxVisitedNodes must be positive");
        }
        this.weight = weight;
        this.maxVisitedNodes = maxVisitedNodes;
    }

    @Override
    public Status step(int expansionBudget, long deadlineNanos) {
        if (status != Status.RUNNING) {
            return status;
        }
        if (expansionBudget <= 0) {
            throw new IllegalArgumentException("expansionBudget must be positive");
        }
        if (!graph.revisionsValid()) {
            return fail(Failure.STALE_WORLD);
        }
        if (!initialized) {
            initialize();
        }

        waitingForTopology = false;
        int expandedThisStep = 0;
        while (expandedThisStep < expansionBudget && System.nanoTime() < deadlineNanos) {
            if (expandedNodes >= maxVisitedNodes) {
                return fail(Failure.SEARCH_LIMIT_REACHED);
            }
            if (shouldWaitForTopology()) {
                waitingForTopology = true;
                blockedSection = bestBlockedSection(Availability.PENDING);
                return status;
            }
            if (openSet.isEmpty()) {
                if (hasBlockedAvailability(Availability.PENDING)) {
                    waitingForTopology = true;
                    blockedSection = bestBlockedSection(Availability.PENDING);
                    return status;
                }
                return fail(hasBlockedAvailability(Availability.UNAVAILABLE)
                        ? Failure.UNAVAILABLE_CHUNK
                        : Failure.NO_STRUCTURAL_ROUTE);
            }
            SearchNode current = popOpenNode();
            if (current.closed) {
                continue;
            }
            expandedThisStep++;
            expandedNodes++;

            if (current.endpoint.id() == graph.goal().id()) {
                result = trace(current);
                status = Status.SUCCEEDED;
                return status;
            }
            expand(current);
        }
        return status;
    }

    @SuppressWarnings("unchecked")
    private SearchNode popOpenNode() {
        return (SearchNode) openSet.pop();
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    @Nullable
    public Corridor result() {
        return result;
    }

    public Failure failure() {
        return failure;
    }

    @Nullable
    public SectionPos blockedSection() {
        return blockedSection;
    }

    public Metrics metrics() {
        return new Metrics(
                expandedNodes,
                generatedConnections,
                reopenedNodes,
                reexpandedBlockedNodes,
                maximumDegree,
                maximumBlockedNodes,
                encounteredPendingDependencies.size(),
                encounteredUnavailableDependencies.size()
        );
    }

    public boolean waitingForTopology() {
        return status == Status.RUNNING && waitingForTopology;
    }

    public List<SectionPos> pendingSections(int limit) {
        return pendingDependencies(limit).stream()
                .map(dependency -> dependency.key().position())
                .distinct()
                .toList();
    }

    public List<Dependency> pendingDependencies(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return waitingByDependency.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(node -> node.f)
                                .min(Float::compare)
                                .orElse(Float.POSITIVE_INFINITY)
                ))
                .sorted(Comparator
                        .comparingDouble((Map.Entry<DependencyKey, Float> entry) -> entry.getValue())
                        .thenComparingInt(entry -> entry.getKey().kind().ordinal())
                        .thenComparingInt(entry -> entry.getKey().position().x())
                        .thenComparingInt(entry -> entry.getKey().position().y())
                        .thenComparingInt(entry -> entry.getKey().position().z()))
                .limit(limit)
                .map(entry -> new Dependency(entry.getKey(), Availability.PENDING))
                .toList();
    }

    public void topologyAvailable(SectionPos section) {
        Objects.requireNonNull(section, "section");
        waitingByDependency.keySet().stream()
                .filter(key -> key.position().equals(section))
                .toList()
                .forEach(this::dependencyAvailable);
    }

    public void dependencyAvailable(DependencyKey dependency) {
        Objects.requireNonNull(dependency, "dependency");
        Set<SearchNode> affected = waitingByDependency.get(dependency);
        if (affected == null || affected.isEmpty()) {
            return;
        }
        for (SearchNode node : List.copyOf(affected)) {
            if (!blockedNodes.contains(node)) {
                continue;
            }
            unregisterBlocked(node, false);
            node.closed = false;
            if (!node.inOpenSet()) {
                openSet.insert(node);
            }
            reexpandedBlockedNodes++;
        }
        waitingForTopology = false;
        blockedSection = null;
    }

    @Override
    public void cancel() {
        if (status == Status.RUNNING) {
            fail(Failure.CANCELLED);
        }
    }

    private void initialize() {
        SearchNode start = node(graph.start());
        start.g = 0.0F;
        start.h = checkedHeuristic(start.endpoint);
        start.f = weight * start.h;
        openSet.insert(start);
        initialized = true;
    }

    private boolean shouldWaitForTopology() {
        float blockedKey = bestBlockedKey(Availability.PENDING);
        if (!Float.isFinite(blockedKey)) {
            return false;
        }
        if (openSet.isEmpty()) {
            return true;
        }
        float readyKey = ((SearchNode) openSet.peek()).f;
        return readyKey > blockedKey + graph.prefetchSlack();
    }

    private void expand(SearchNode current) {
        expansionBuffer.reset(current.endpoint);
        graph.expandInto(current.endpoint, expansionBuffer);
        boolean incomplete = expansionBuffer.dependencyCount() != 0;
        if (incomplete && current.emittedConnectionIds == null) {
            current.emittedConnectionIds = new LongOpenHashSet();
        }
        int generatedThisExpansion = 0;
        for (int index = 0; index < expansionBuffer.connectionCount(); index++) {
            long connectionId = expansionBuffer.connectionId(index);
            if (current.emittedConnectionIds != null
                    && !current.emittedConnectionIds.add(connectionId)) {
                continue;
            }
            generatedConnections++;
            generatedThisExpansion++;
            float lowerBound = expansionBuffer.lowerBound(index);
            float nextCost = current.g + lowerBound;
            SearchNode next = node(expansionBuffer.target(index));
            if (nextCost >= next.g) {
                continue;
            }
            boolean wasClosed = next.closed;
            next.g = nextCost;
            next.h = checkedHeuristic(next.endpoint);
            next.f = nextCost + weight * next.h;
            next.previous = current;
            next.viaId = connectionId;
            next.viaLowerBound = lowerBound;
            next.viaKind = expansionBuffer.transitionKind(index);
            next.viaPayload = expansionBuffer.transitionPayload(index);
            next.viaPayloadIndex = expansionBuffer.transitionPayloadIndex(index);
            boolean wasBlocked = blockedNodes.contains(next);
            if (wasBlocked) {
                unregisterBlocked(next, true);
            }
            if (next.inOpenSet()) {
                openSet.changeCost(next, next.f);
            } else {
                next.closed = false;
                openSet.insert(next);
                if (wasClosed || wasBlocked) {
                    reopenedNodes++;
                }
            }
        }
        maximumDegree = Math.max(maximumDegree, generatedThisExpansion);
        if (incomplete) {
            registerBlocked(current, expansionBuffer.dependencies());
        } else {
            current.closed = true;
            current.emittedConnectionIds = null;
        }
    }

    private void registerBlocked(SearchNode node, List<Dependency> dependencies) {
        unregisterBlocked(node, false);
        Set<DependencyKey> pendingDependencies = new HashSet<>();
        Set<DependencyKey> unavailableDependencies = new HashSet<>();
        for (Dependency dependency : dependencies) {
            if (dependency.availability() == Availability.PENDING) {
                pendingDependencies.add(dependency.key());
                encounteredPendingDependencies.add(dependency.key());
            } else {
                unavailableDependencies.add(dependency.key());
                encounteredUnavailableDependencies.add(dependency.key());
            }
        }
        node.pendingDependencies = Set.copyOf(pendingDependencies);
        node.unavailableDependencies = Set.copyOf(unavailableDependencies);
        node.closed = false;
        blockedNodes.add(node);
        for (DependencyKey dependency : pendingDependencies) {
            waitingByDependency.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(node);
        }
        maximumBlockedNodes = Math.max(maximumBlockedNodes, blockedNodes.size());
    }

    private void unregisterBlocked(SearchNode node, boolean clearEmittedConnections) {
        if (!blockedNodes.remove(node)) {
            return;
        }
        for (DependencyKey dependency : node.pendingDependencies) {
            Set<SearchNode> waiting = waitingByDependency.get(dependency);
            if (waiting == null) {
                continue;
            }
            waiting.remove(node);
            if (waiting.isEmpty()) {
                waitingByDependency.remove(dependency);
            }
        }
        node.pendingDependencies = Set.of();
        node.unavailableDependencies = Set.of();
        if (clearEmittedConnections) {
            node.emittedConnectionIds = null;
        }
    }

    private boolean hasBlockedAvailability(Availability availability) {
        return Float.isFinite(bestBlockedKey(availability));
    }

    private float bestBlockedKey(Availability availability) {
        float best = Float.POSITIVE_INFINITY;
        for (SearchNode node : blockedNodes) {
            Set<DependencyKey> dependencies = availability == Availability.PENDING
                    ? node.pendingDependencies
                    : node.unavailableDependencies;
            if (!dependencies.isEmpty()) {
                best = Math.min(best, node.f);
            }
        }
        return best;
    }

    @Nullable
    private SectionPos bestBlockedSection(Availability availability) {
        SearchNode bestNode = null;
        for (SearchNode node : blockedNodes) {
            Set<DependencyKey> dependencies = availability == Availability.PENDING
                    ? node.pendingDependencies
                    : node.unavailableDependencies;
            if (!dependencies.isEmpty() && (bestNode == null || node.f < bestNode.f)) {
                bestNode = node;
            }
        }
        if (bestNode == null) {
            return null;
        }
        Set<DependencyKey> dependencies = availability == Availability.PENDING
                ? bestNode.pendingDependencies
                : bestNode.unavailableDependencies;
        return dependencies.stream()
                .map(DependencyKey::position)
                .min(Comparator.comparingInt((SectionPos section) -> section.x())
                        .thenComparingInt(section -> section.y())
                        .thenComparingInt(section -> section.z()))
                .orElse(null);
    }

    private SearchNode node(Endpoint endpoint) {
        SearchNode existing = nodes.get(endpoint.id());
        if (existing != null) {
            if (!existing.endpoint.anchor().equals(endpoint.anchor())
                    || existing.endpoint.revision() != endpoint.revision()) {
                throw new IllegalStateException("graph reused a node ID for another endpoint");
            }
            return existing;
        }
        SearchNode created = new SearchNode(endpoint);
        nodes.put(endpoint.id(), created);
        return created;
    }

    private float checkedHeuristic(Endpoint endpoint) {
        float heuristic = graph.heuristic(endpoint);
        if (!Float.isFinite(heuristic) || heuristic < 0.0F) {
            throw new IllegalStateException("graph returned an invalid heuristic");
        }
        return heuristic;
    }

    private Corridor trace(SearchNode goal) {
        List<SearchNode> reversed = new ArrayList<>();
        for (SearchNode current = goal; current.previous != null; current = current.previous) {
            reversed.add(current);
        }
        Collections.reverse(reversed);
        List<Endpoint> endpoints = new ArrayList<>(reversed.size() + 1);
        endpoints.add(graph.start());
        List<Connection> connections = new ArrayList<>(reversed.size());
        float cost = 0.0F;
        for (SearchNode node : reversed) {
            SearchNode previous = Objects.requireNonNull(node.previous);
            Transition transition = materializeTransition(
                    node.viaKind,
                    node.viaPayload,
                    node.viaPayloadIndex
            );
            connections.add(new Connection(
                    node.viaId,
                    previous.endpoint,
                    node.endpoint,
                    node.viaLowerBound,
                    transition
            ));
            endpoints.add(node.endpoint);
            cost += node.viaLowerBound;
        }
        return new Corridor(endpoints, connections, cost);
    }

    private static Transition materializeTransition(byte kind, Object payload, int payloadIndex) {
        return switch (kind) {
            case ExpansionBuffer.DIRECT -> (Transition) Objects.requireNonNull(payload);
            case ExpansionBuffer.MEMBERSHIP -> MembershipTransition.INSTANCE;
            case ExpansionBuffer.LOCAL -> new LocalTransition(
                    (BaseClusterTopology.LocalConnection) Objects.requireNonNull(payload)
            );
            case ExpansionBuffer.BOUNDARY -> materializeBoundary(
                    (SuperClusterTopology.BoundaryLinks) Objects.requireNonNull(payload),
                    payloadIndex
            );
            case ExpansionBuffer.AGGREGATE -> AggregateTransition.INSTANCE;
            default -> throw new IllegalStateException("unknown transition descriptor " + kind);
        };
    }

    private static BoundaryTransition materializeBoundary(SuperClusterTopology.BoundaryLinks links,
                                                            int edgeIndex) {
        List<BoundaryBand> bands = new ArrayList<>(links.bandEnd(edgeIndex) - links.bandStart(edgeIndex));
        for (int band = links.bandStart(edgeIndex); band < links.bandEnd(edgeIndex); band++) {
            bands.add(new BoundaryBand(
                    links.verticalShift(band),
                    links.maskWord(band, 0),
                    links.maskWord(band, 1),
                    links.maskWord(band, 2),
                    links.maskWord(band, 3)
            ));
        }
        return new BoundaryTransition(links.face(), bands);
    }

    private Status fail(Failure reason) {
        status = Status.FAILED;
        failure = Objects.requireNonNull(reason, "reason");
        blockedSection = reason == Failure.UNAVAILABLE_CHUNK
                ? bestBlockedSection(Availability.UNAVAILABLE)
                : blockedSection;
        result = null;
        return status;
    }

    public interface Graph {
        Endpoint start();

        Endpoint goal();

        Expansion expand(Endpoint from);

        default void expandInto(Endpoint from, ExpansionBuffer output) {
            Expansion expansion = Objects.requireNonNull(expand(from), "graph returned null expansion");
            for (Connection connection : expansion.connections()) {
                output.add(connection);
            }
            for (Dependency dependency : expansion.dependencies()) {
                output.addDependency(dependency);
            }
        }

        boolean revisionsValid();

        default float heuristic(Endpoint endpoint) {
            BlockPos from = endpoint.anchor();
            BlockPos goalPosition = goal().anchor();
            double dx = goalPosition.getX() - from.getX();
            double dy = goalPosition.getY() - from.getY();
            double dz = goalPosition.getZ() - from.getZ();
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        default float prefetchSlack() {
            return 0.0F;
        }
    }

    /** Reused adjacency storage for the allocation-sensitive production search path. */
    public static final class ExpansionBuffer {
        private static final byte DIRECT = 0;
        private static final byte MEMBERSHIP = 1;
        private static final byte LOCAL = 2;
        private static final byte BOUNDARY = 3;
        private static final byte AGGREGATE = 4;

        private Endpoint source;
        private long[] ids = new long[16];
        private Endpoint[] targets = new Endpoint[16];
        private float[] costs = new float[16];
        private byte[] kinds = new byte[16];
        private Object[] payloads = new Object[16];
        private int[] payloadIndexes = new int[16];
        private int connectionCount;
        private Dependency[] dependencies = new Dependency[4];
        private int dependencyCount;

        void reset(Endpoint source) {
            this.source = Objects.requireNonNull(source, "source");
            for (int index = 0; index < connectionCount; index++) {
                targets[index] = null;
                payloads[index] = null;
            }
            for (int index = 0; index < dependencyCount; index++) {
                dependencies[index] = null;
            }
            connectionCount = 0;
            dependencyCount = 0;
        }

        public void add(Connection connection) {
            Objects.requireNonNull(connection, "connection");
            if (connection.from().id() != source.id()) {
                throw new IllegalArgumentException("connection source does not match the expansion source");
            }
            add(connection.id(), connection.to(), connection.lowerBound(), DIRECT, connection.transition(), 0);
        }

        public void addMembership(long id, Endpoint target, float lowerBound) {
            add(id, target, lowerBound, MEMBERSHIP, null, 0);
        }

        public void addLocal(long id,
                             Endpoint target,
                             float lowerBound,
                             BaseClusterTopology.LocalConnection connection) {
            add(id, target, lowerBound, LOCAL, Objects.requireNonNull(connection, "connection"), 0);
        }

        public void addBoundary(long id,
                                Endpoint target,
                                float lowerBound,
                                SuperClusterTopology.BoundaryLinks links,
                                int edgeIndex) {
            add(id, target, lowerBound, BOUNDARY, Objects.requireNonNull(links, "links"), edgeIndex);
        }

        public void addAggregate(long id, Endpoint target, float lowerBound) {
            add(id, target, lowerBound, AGGREGATE, null, 0);
        }

        public void addDependency(Dependency dependency) {
            Objects.requireNonNull(dependency, "dependency");
            ensureDependencyCapacity(dependencyCount + 1);
            dependencies[dependencyCount++] = dependency;
        }

        public Expansion snapshot() {
            List<Connection> connections = new ArrayList<>(connectionCount);
            for (int index = 0; index < connectionCount; index++) {
                connections.add(new Connection(
                        ids[index],
                        source,
                        targets[index],
                        costs[index],
                        materializeTransition(kinds[index], payloads[index], payloadIndexes[index])
                ));
            }
            return new Expansion(connections, dependencies());
        }

        private void add(long id,
                         Endpoint target,
                         float lowerBound,
                         byte kind,
                         @Nullable Object payload,
                         int payloadIndex) {
            Objects.requireNonNull(target, "target");
            if (!Float.isFinite(lowerBound) || lowerBound < 0.0F) {
                throw new IllegalArgumentException("lowerBound must be finite and non-negative");
            }
            ensureConnectionCapacity(connectionCount + 1);
            ids[connectionCount] = id;
            targets[connectionCount] = target;
            costs[connectionCount] = lowerBound;
            kinds[connectionCount] = kind;
            payloads[connectionCount] = payload;
            payloadIndexes[connectionCount] = payloadIndex;
            connectionCount++;
        }

        private int connectionCount() {
            return connectionCount;
        }

        private long connectionId(int index) {
            return ids[index];
        }

        private Endpoint target(int index) {
            return targets[index];
        }

        private float lowerBound(int index) {
            return costs[index];
        }

        private byte transitionKind(int index) {
            return kinds[index];
        }

        private Object transitionPayload(int index) {
            return payloads[index];
        }

        private int transitionPayloadIndex(int index) {
            return payloadIndexes[index];
        }

        private int dependencyCount() {
            return dependencyCount;
        }

        private List<Dependency> dependencies() {
            if (dependencyCount == 0) {
                return List.of();
            }
            List<Dependency> result = new ArrayList<>(dependencyCount);
            for (int index = 0; index < dependencyCount; index++) {
                result.add(dependencies[index]);
            }
            return result;
        }

        private void ensureConnectionCapacity(int required) {
            if (required <= ids.length) {
                return;
            }
            int capacity = Math.max(required, ids.length << 1);
            ids = java.util.Arrays.copyOf(ids, capacity);
            targets = java.util.Arrays.copyOf(targets, capacity);
            costs = java.util.Arrays.copyOf(costs, capacity);
            kinds = java.util.Arrays.copyOf(kinds, capacity);
            payloads = java.util.Arrays.copyOf(payloads, capacity);
            payloadIndexes = java.util.Arrays.copyOf(payloadIndexes, capacity);
        }

        private void ensureDependencyCapacity(int required) {
            if (required > dependencies.length) {
                dependencies = java.util.Arrays.copyOf(
                        dependencies,
                        Math.max(required, dependencies.length << 1)
                );
            }
        }
    }

    public record Expansion(List<Connection> connections, List<Dependency> dependencies) {
        public Expansion {
            connections = List.copyOf(connections);
            dependencies = List.copyOf(dependencies);
        }

        public static Expansion complete(List<Connection> connections) {
            return new Expansion(connections, List.of());
        }
    }

    public record Dependency(DependencyKey key, Availability availability) {
        public Dependency {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(availability, "availability");
        }

        public Dependency(SectionPos section, Availability availability) {
            this(new DependencyKey(DependencyKind.BASE_CLUSTER, section), availability);
        }

        public static Dependency superCluster(SectionPos origin, Availability availability) {
            return new Dependency(new DependencyKey(DependencyKind.SUPER_CLUSTER, origin), availability);
        }

        public static Dependency baseBoundary(SectionPos source,
                                              SectionPos target,
                                              Direction face,
                                              Availability availability) {
            return new Dependency(
                    new DependencyKey(DependencyKind.BASE_BOUNDARY, source, target, face),
                    availability
            );
        }

        public static Dependency superBoundary(SectionPos source,
                                               SectionPos target,
                                               Direction face,
                                               Availability availability) {
            return new Dependency(
                    new DependencyKey(DependencyKind.SUPER_BOUNDARY, source, target, face),
                    availability
            );
        }

        public SectionPos section() {
            return key.position();
        }
    }

    public record DependencyKey(DependencyKind kind,
                                SectionPos position,
                                @Nullable SectionPos target,
                                @Nullable Direction face) {
        public DependencyKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(position, "position");
            boolean boundary = kind == DependencyKind.BASE_BOUNDARY
                    || kind == DependencyKind.SUPER_BOUNDARY;
            if (boundary ? target == null || face == null : target != null || face != null) {
                throw new IllegalArgumentException(
                        "boundary dependencies require a target section and face"
                );
            }
            if (boundary) {
                int distance = kind == DependencyKind.SUPER_BOUNDARY
                        ? SuperClusterTopology.CHILDREN_PER_AXIS
                        : 1;
                if (!target.equals(SuperClusterTopology.offset(position, face, distance))) {
                    throw new IllegalArgumentException("boundary dependency is not adjacent");
                }
            }
        }

        public DependencyKey(DependencyKind kind, SectionPos position) {
            this(kind, position, null, null);
        }
    }

    public enum DependencyKind {
        BASE_CLUSTER,
        SUPER_CLUSTER,
        BASE_BOUNDARY,
        SUPER_BOUNDARY
    }

    public enum Availability {
        PENDING,
        UNAVAILABLE
    }

    public sealed interface Endpoint permits ExactEndpoint, ComponentEndpoint, AggregateEndpoint {
        long id();

        BlockPos anchor();

        long revision();
    }

    public record ExactEndpoint(long id, BlockPos anchor, long revision) implements Endpoint {
        public ExactEndpoint {
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        }
    }

    public record ComponentEndpoint(long id,
                                    BlockPos anchor,
                                    long revision,
                                    SectionPos section,
                                    BaseClusterTopology.Channel channel,
                                    int componentId) implements Endpoint {
        public ComponentEndpoint {
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(channel, "channel");
            if (componentId < 0) {
                throw new IllegalArgumentException("componentId must be non-negative");
            }
        }
    }

    public record AggregateEndpoint(long id,
                                    BlockPos anchor,
                                    long revision,
                                    SectionPos origin,
                                    BaseClusterTopology.Channel channel,
                                    int aggregateId) implements Endpoint {
        public AggregateEndpoint {
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(channel, "channel");
            if (aggregateId < 0) {
                throw new IllegalArgumentException("aggregate ID must be non-negative");
            }
        }
    }

    public record Connection(long id,
                             Endpoint from,
                             Endpoint to,
                             float lowerBound,
                             Transition transition) {
        public Connection {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(transition, "transition");
            if (!Float.isFinite(lowerBound) || lowerBound < 0.0F) {
                throw new IllegalArgumentException("lowerBound must be finite and non-negative");
            }
        }
    }

    public sealed interface Transition
            permits MembershipTransition, LocalTransition, BoundaryTransition, AggregateTransition {
    }

    public record MembershipTransition() implements Transition {
        private static final MembershipTransition INSTANCE = new MembershipTransition();
    }

    public record LocalTransition(BaseClusterTopology.LocalConnection requirement)
            implements Transition {
        public LocalTransition {
            Objects.requireNonNull(requirement, "requirement");
        }
    }

    public record BoundaryTransition(Direction face, List<BoundaryBand> bands) implements Transition {
        public BoundaryTransition {
            Objects.requireNonNull(face, "face");
            bands = List.copyOf(bands);
            if (bands.isEmpty()) {
                throw new IllegalArgumentException("boundary transition requires at least one band");
            }
        }
    }

    public record AggregateTransition() implements Transition {
        private static final AggregateTransition INSTANCE = new AggregateTransition();
    }

    public record BoundaryBand(int verticalShift,
                               long mask0,
                               long mask1,
                               long mask2,
                               long mask3) {
        public BoundaryBand {
            if ((mask0 | mask1 | mask2 | mask3) == 0L) {
                throw new IllegalArgumentException("boundary band mask cannot be empty");
            }
        }

        public long maskWord(int index) {
            return switch (index) {
                case 0 -> mask0;
                case 1 -> mask1;
                case 2 -> mask2;
                case 3 -> mask3;
                default -> throw new IndexOutOfBoundsException("boundary mask word must be in [0, 3]");
            };
        }
    }

    public record Corridor(List<Endpoint> endpoints, List<Connection> connections, float cost) {
        public Corridor {
            endpoints = List.copyOf(endpoints);
            connections = List.copyOf(connections);
            if (endpoints.size() != connections.size() + 1) {
                throw new IllegalArgumentException("corridor endpoint count must be connection count plus one");
            }
            if (!Float.isFinite(cost) || cost < 0.0F) {
                throw new IllegalArgumentException("corridor cost must be finite and non-negative");
            }
        }
    }

    public record Metrics(long expandedNodes,
                          long generatedConnections,
                          long reopenedNodes,
                          long reexpandedBlockedNodes,
                          int maximumDegree,
                          int maximumBlockedNodes,
                          int pendingSections,
                          int unavailableSections) {
        public Metrics plus(@Nullable Metrics other) {
            if (other == null) {
                return this;
            }
            return new Metrics(
                    expandedNodes + other.expandedNodes,
                    generatedConnections + other.generatedConnections,
                    reopenedNodes + other.reopenedNodes,
                    reexpandedBlockedNodes + other.reexpandedBlockedNodes,
                    Math.max(maximumDegree, other.maximumDegree),
                    Math.max(maximumBlockedNodes, other.maximumBlockedNodes),
                    pendingSections + other.pendingSections,
                    unavailableSections + other.unavailableSections
            );
        }
    }

    public enum Failure {
        NONE,
        NO_STRUCTURAL_ROUTE,
        SEARCH_LIMIT_REACHED,
        UNAVAILABLE_CHUNK,
        STALE_WORLD,
        CANCELLED
    }

    private final class SearchNode extends Node {
        private final Endpoint endpoint;
        private SearchNode previous;
        private long viaId;
        private float viaLowerBound;
        private byte viaKind;
        private Object viaPayload;
        private int viaPayloadIndex;
        private LongOpenHashSet emittedConnectionIds;
        private Set<DependencyKey> pendingDependencies = Set.of();
        private Set<DependencyKey> unavailableDependencies = Set.of();

        private SearchNode(Endpoint endpoint) {
            super((int) endpoint.id(), (int) (endpoint.id() >>> 32), 0);
            this.endpoint = endpoint;
            this.g = Float.POSITIVE_INFINITY;
        }
    }
}
