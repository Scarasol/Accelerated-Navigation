package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, in-memory contraction of eight adjacent base clusters. The
 * topology is built only from immutable base data and never reads the world.
 */
public final class SuperClusterTopology {

    public static final int CHILDREN_PER_AXIS = 2;

    private static final List<Direction> INTERNAL_DIRECTIONS =
            List.of(Direction.EAST, Direction.UP, Direction.SOUTH);

    private final SectionPos origin;
    private final BaseClusterTopology.Channel channel;
    private final BaseClusterTopology.TraversalProfile profile;
    private final Map<SectionPos, BaseClusterTopology> children;
    private final int[] aggregateByNode;
    private final int[][] aggregateByChildComponent;
    private final List<Aggregate> aggregates;
    private final List<List<AggregateEdge>> outgoing;
    private final int[] outgoingOffsets;
    private final int[] outgoingTargets;
    private final float[] outgoingCosts;
    private final Map<Direction, List<BoundaryPlane>> boundaryPlanes;
    private final long signature;
    private final int retainedBytes;

    private SuperClusterTopology(SectionPos origin,
                                 BaseClusterTopology.Channel channel,
                                 BaseClusterTopology.TraversalProfile profile,
                                 Map<SectionPos, BaseClusterTopology> children,
                                 Map<ComponentRef, Integer> nodeIndices,
                                 int[] aggregateByNode,
                                 List<Aggregate> aggregates,
                                 List<List<AggregateEdge>> outgoing,
                                 Map<Direction, List<BoundaryPlane>> boundaryPlanes,
                                 long signature) {
        this.origin = origin;
        this.channel = channel;
        this.profile = profile;
        this.children = Map.copyOf(children);
        this.aggregateByNode = aggregateByNode.clone();
        this.aggregateByChildComponent = buildAggregateByChildComponent(
                origin,
                this.children,
                nodeIndices,
                this.aggregateByNode
        );
        this.aggregates = List.copyOf(aggregates);
        this.outgoing = freezeNested(outgoing);
        this.outgoingOffsets = buildAggregateOffsets(this.outgoing);
        this.outgoingTargets = new int[outgoingOffsets[outgoingOffsets.length - 1]];
        this.outgoingCosts = new float[outgoingTargets.length];
        int outgoingCursor = 0;
        for (List<AggregateEdge> edges : this.outgoing) {
            for (AggregateEdge edge : edges) {
                outgoingTargets[outgoingCursor] = edge.targetAggregate();
                outgoingCosts[outgoingCursor] = edge.lowerBound();
                outgoingCursor++;
            }
        }
        EnumMap<Direction, List<BoundaryPlane>> frozenBoundary = new EnumMap<>(Direction.class);
        boundaryPlanes.forEach((face, planes) -> frozenBoundary.put(face, List.copyOf(planes)));
        this.boundaryPlanes = Map.copyOf(frozenBoundary);
        this.signature = signature;
        int edgeCount = this.outgoing.stream().mapToInt(List::size).sum();
        int boundaryBytes = this.boundaryPlanes.values().stream()
                .flatMap(List::stream)
                .mapToInt(plane -> 32 + plane.labels().length * Character.BYTES)
                .sum();
        this.retainedBytes = 160
                + this.children.size() * 24
                + this.aggregateByNode.length * Integer.BYTES
                + Arrays.stream(this.aggregateByChildComponent)
                .mapToInt(ids -> ids.length)
                .sum() * Integer.BYTES
                + this.aggregates.size() * 40
                + edgeCount * 24
                + outgoingOffsets.length * Integer.BYTES
                + outgoingTargets.length * Integer.BYTES
                + outgoingCosts.length * Float.BYTES
                + boundaryBytes;
    }

    public static SuperClusterTopology build(SectionPos origin,
                                             List<BaseClusterTopology> children,
                                             BaseClusterTopology.Channel channel,
                                             BaseClusterTopology.TraversalProfile profile) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(children, "children");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        if (!isAlignedOrigin(origin)) {
            throw new IllegalArgumentException("super-cluster origin must be aligned to two sections");
        }
        if (children.size() != CHILDREN_PER_AXIS * CHILDREN_PER_AXIS * CHILDREN_PER_AXIS) {
            throw new IllegalArgumentException("a super cluster requires exactly eight base clusters");
        }

        Map<SectionPos, BaseClusterTopology> childMap = new HashMap<>();
        for (BaseClusterTopology child : children) {
            Objects.requireNonNull(child, "child topology");
            if (!contains(origin, child.section()) || childMap.put(child.section(), child) != null) {
                throw new IllegalArgumentException("base cluster is outside or duplicated in the super cluster");
            }
        }
        for (SectionPos expected : childSections(origin)) {
            if (!childMap.containsKey(expected)) {
                throw new IllegalArgumentException("missing base cluster " + expected);
            }
        }

        List<ComponentRef> nodes = new ArrayList<>();
        Map<ComponentRef, Integer> nodeIndices = new HashMap<>();
        for (SectionPos section : childSections(origin)) {
            for (BaseClusterTopology.Component component : childMap.get(section).components(channel)) {
                ComponentRef reference = new ComponentRef(section, component.id());
                nodeIndices.put(reference, nodes.size());
                nodes.add(reference);
            }
        }

        List<List<NodeEdge>> adjacency = mutableAdjacency(nodes.size());
        addLocalEdges(childMap, profile, nodeIndices, adjacency);
        addInternalBoundaryEdges(origin, childMap, channel, profile, nodeIndices, adjacency);

        int[] aggregateByNode = stronglyConnectedComponents(adjacency);
        int aggregateCount = Arrays.stream(aggregateByNode).max().orElse(-1) + 1;
        List<Aggregate> aggregates = buildAggregates(
                origin,
                childMap,
                nodes,
                aggregateByNode,
                aggregateCount
        );
        List<List<AggregateEdge>> outgoing = buildAggregateEdges(
                adjacency,
                aggregateByNode,
                aggregateCount
        );
        Map<Direction, List<BoundaryPlane>> boundaryPlanes = buildBoundaryPlanes(
                origin,
                childMap,
                nodeIndices,
                channel,
                profile
        );
        long signature = signature(origin, childMap, channel, profile);
        return new SuperClusterTopology(
                origin,
                channel,
                profile,
                childMap,
                nodeIndices,
                aggregateByNode,
                aggregates,
                outgoing,
                boundaryPlanes,
                signature
        );
    }

    public SectionPos origin() {
        return origin;
    }

    public BaseClusterTopology.Channel channel() {
        return channel;
    }

    public BaseClusterTopology.TraversalProfile profile() {
        return profile;
    }

    public long signature() {
        return signature;
    }

    public int retainedBytes() {
        return retainedBytes;
    }

    public List<Aggregate> aggregates() {
        return aggregates;
    }

    public Aggregate aggregate(int id) {
        if (id < 0 || id >= aggregates.size()) {
            throw new IndexOutOfBoundsException("unknown aggregate component " + id);
        }
        return aggregates.get(id);
    }

    public int aggregateId(SectionPos section, int componentId) {
        if (!contains(origin, section)) {
            return -1;
        }
        int[] aggregates = aggregateByChildComponent[childIndex(origin, section)];
        return componentId < 0 || componentId >= aggregates.length
                ? -1
                : aggregates[componentId];
    }

    private static int[][] buildAggregateByChildComponent(
            SectionPos origin,
            Map<SectionPos, BaseClusterTopology> children,
            Map<ComponentRef, Integer> nodeIndices,
            int[] aggregateByNode) {
        int childCount = CHILDREN_PER_AXIS * CHILDREN_PER_AXIS * CHILDREN_PER_AXIS;
        int[][] result = new int[childCount][];
        for (SectionPos section : childSections(origin)) {
            int[] aggregates = new int[children.get(section).components().size()];
            Arrays.fill(aggregates, -1);
            result[childIndex(origin, section)] = aggregates;
        }
        for (Map.Entry<ComponentRef, Integer> node : nodeIndices.entrySet()) {
            ComponentRef component = node.getKey();
            result[childIndex(origin, component.section())][component.componentId()] =
                    aggregateByNode[node.getValue()];
        }
        return result;
    }

    private static int childIndex(SectionPos origin, SectionPos section) {
        int x = section.x() - origin.x();
        int y = section.y() - origin.y();
        int z = section.z() - origin.z();
        return x + z * CHILDREN_PER_AXIS
                + y * CHILDREN_PER_AXIS * CHILDREN_PER_AXIS;
    }

    public List<AggregateEdge> outgoing(int aggregateId) {
        aggregate(aggregateId);
        return outgoing.get(aggregateId);
    }

    int outgoingStart(int aggregateId) {
        aggregate(aggregateId);
        return outgoingOffsets[aggregateId];
    }

    int outgoingEnd(int aggregateId) {
        aggregate(aggregateId);
        return outgoingOffsets[aggregateId + 1];
    }

    int outgoingTarget(int edgeIndex) {
        return outgoingTargets[edgeIndex];
    }

    float outgoingCost(int edgeIndex) {
        return outgoingCosts[edgeIndex];
    }

    public List<Crossing> crossings(int aggregateId,
                                    Direction face,
                                    SuperClusterTopology neighbor) {
        Aggregate sourceAggregate = aggregate(aggregateId);
        if (!sourceAggregate.touches(face)) {
            return List.of();
        }
        CrossingIndex index = crossingIndex(face, neighbor);
        List<Crossing> result = new ArrayList<>();
        for (int edge = index.edgeStart(aggregateId); edge < index.edgeEnd(aggregateId); edge++) {
            result.add(new Crossing(index.targetAggregate(edge), index.lowerBound(edge)));
        }
        return List.copyOf(result);
    }

    public Map<Integer, List<Crossing>> crossings(Direction face,
                                                  SuperClusterTopology neighbor) {
        CrossingIndex index = crossingIndex(face, neighbor);
        Map<Integer, List<Crossing>> result = new HashMap<>();
        for (int source = 0; source < aggregates.size(); source++) {
            List<Crossing> crossings = new ArrayList<>();
            for (int edge = index.edgeStart(source); edge < index.edgeEnd(source); edge++) {
                crossings.add(new Crossing(index.targetAggregate(edge), index.lowerBound(edge)));
            }
            if (!crossings.isEmpty()) {
                result.put(source, List.copyOf(crossings));
            }
        }
        return Map.copyOf(result);
    }

    CrossingIndex crossingIndex(Direction face, SuperClusterTopology neighbor) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(neighbor, "neighbor");
        if (!neighbor.origin.equals(offset(origin, face, CHILDREN_PER_AXIS))
                || neighbor.channel != channel
                || !neighbor.profile.equals(profile)) {
            throw new IllegalArgumentException("incompatible adjacent super cluster");
        }
        Direction opposite = face.getOpposite();
        Map<Integer, Map<Integer, Float>> bestBySource = new HashMap<>();
        List<BoundaryPlane> targetPlanes = neighbor.boundaryPlanes.getOrDefault(opposite, List.of());
        for (BoundaryPlane sourcePlane : boundaryPlanes.getOrDefault(face, List.of())) {
            SectionPos targetSection = offset(sourcePlane.section(), face, 1);
            BoundaryPlane targetPlane = plane(targetPlanes, targetSection);
            if (targetPlane == null) {
                continue;
            }
            collectPlaneCrossings(sourcePlane, targetPlane, face, neighbor, bestBySource);
        }
        int[] offsets = new int[aggregates.size() + 1];
        for (Map.Entry<Integer, Map<Integer, Float>> source : bestBySource.entrySet()) {
            offsets[source.getKey() + 1] = source.getValue().size();
        }
        for (int source = 1; source < offsets.length; source++) {
            offsets[source] += offsets[source - 1];
        }
        int[] targets = new int[offsets[offsets.length - 1]];
        float[] costs = new float[targets.length];
        for (int source = 0; source < aggregates.size(); source++) {
            Map<Integer, Float> outgoing = bestBySource.get(source);
            if (outgoing == null) {
                continue;
            }
            List<Map.Entry<Integer, Float>> sorted = new ArrayList<>(outgoing.entrySet());
            sorted.sort(Map.Entry.comparingByKey());
            int cursor = offsets[source];
            for (Map.Entry<Integer, Float> target : sorted) {
                targets[cursor] = target.getKey();
                costs[cursor] = target.getValue();
                cursor++;
            }
        }
        return new CrossingIndex(face, offsets, targets, costs);
    }

    public boolean matchesChildren(Map<SectionPos, BaseClusterTopology> current) {
        if (current.size() != children.size()) {
            return false;
        }
        for (Map.Entry<SectionPos, BaseClusterTopology> child : children.entrySet()) {
            BaseClusterTopology value = current.get(child.getKey());
            if (value == null
                    || value.revision() != child.getValue().revision()
                    || value.sourceFingerprint() != child.getValue().sourceFingerprint()) {
                return false;
            }
        }
        return true;
    }

    public List<SectionPos> childSections() {
        return childSections(origin);
    }

    static SectionPos originOf(SectionPos section) {
        Objects.requireNonNull(section, "section");
        return SectionPos.of(
                Math.floorDiv(section.x(), CHILDREN_PER_AXIS) * CHILDREN_PER_AXIS,
                Math.floorDiv(section.y(), CHILDREN_PER_AXIS) * CHILDREN_PER_AXIS,
                Math.floorDiv(section.z(), CHILDREN_PER_AXIS) * CHILDREN_PER_AXIS
        );
    }

    static SectionPos offset(SectionPos section, Direction direction, int distance) {
        return SectionPos.of(
                section.x() + direction.getStepX() * distance,
                section.y() + direction.getStepY() * distance,
                section.z() + direction.getStepZ() * distance
        );
    }

    static List<SectionPos> childSections(SectionPos origin) {
        List<SectionPos> result = new ArrayList<>(8);
        for (int y = 0; y < CHILDREN_PER_AXIS; y++) {
            for (int z = 0; z < CHILDREN_PER_AXIS; z++) {
                for (int x = 0; x < CHILDREN_PER_AXIS; x++) {
                    result.add(SectionPos.of(origin.x() + x, origin.y() + y, origin.z() + z));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<BoundaryBand> boundaryBands(BaseClusterTopology sourceTopology,
                                            BaseClusterTopology.Component source,
                                            BaseClusterTopology targetTopology,
                                            BaseClusterTopology.Component target,
                                            Direction face,
                                            BaseClusterTopology.Channel channel,
                                            BaseClusterTopology.TraversalProfile profile) {
        Objects.requireNonNull(sourceTopology, "sourceTopology");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetTopology, "targetTopology");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        if (source.channel() != channel || target.channel() != channel) {
            return List.of();
        }

        Direction opposite = face.getOpposite();
        int minimumWidth = Math.max(1, (int) Math.ceil(profile.width()));
        if (channel == BaseClusterTopology.Channel.VOLUME || face.getAxis().isVertical()) {
            if (!allowsVerticalBoundary(face, channel, profile)) {
                return List.of();
            }
            BoundaryBand band = overlap(
                    sourceTopology,
                    source,
                    targetTopology,
                    target,
                    face,
                    opposite,
                    0,
                    minimumWidth,
                    channel,
                    profile
            );
            return band == null ? List.of() : List.of(band);
        }

        List<BoundaryBand> result = new ArrayList<>();
        int maximum = Math.max(profile.maxStep(), profile.maxDrop());
        for (int magnitude = 0; magnitude <= maximum; magnitude++) {
            if (magnitude <= profile.maxStep()) {
                addOverlap(
                        result,
                        sourceTopology,
                        source,
                        targetTopology,
                        target,
                        face,
                        opposite,
                        magnitude,
                        minimumWidth,
                        channel,
                        profile
                );
            }
            if (magnitude > 0 && magnitude <= profile.maxDrop()) {
                addOverlap(
                        result,
                        sourceTopology,
                        source,
                        targetTopology,
                        target,
                        face,
                        opposite,
                        -magnitude,
                        minimumWidth,
                        channel,
                        profile
                );
            }
        }
        return List.copyOf(result);
    }

    static BoundaryLinks boundaryLinks(BaseClusterTopology sourceTopology,
                                       BaseClusterTopology targetTopology,
                                       Direction face,
                                       BaseClusterTopology.Channel channel,
                                       BaseClusterTopology.TraversalProfile profile) {
        Objects.requireNonNull(sourceTopology, "sourceTopology");
        Objects.requireNonNull(targetTopology, "targetTopology");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        if (!targetTopology.section().equals(offset(sourceTopology.section(), face, 1))) {
            throw new IllegalArgumentException("base boundary topologies are not adjacent");
        }

        List<BoundaryEdgeBuild> edges = new ArrayList<>();
        int[] offsets = new int[sourceTopology.components().size() + 1];
        Direction opposite = face.getOpposite();
        int sourceCount = sourceTopology.boundaryComponentCount(face, channel);
        int targetCount = targetTopology.boundaryComponentCount(opposite, channel);
        for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
            int sourceId = sourceTopology.boundaryComponentId(face, channel, sourceIndex);
            BaseClusterTopology.Component source = sourceTopology.component(sourceId);
            for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
                int targetId = targetTopology.boundaryComponentId(opposite, channel, targetIndex);
                List<BoundaryBand> bands = boundaryBands(
                        sourceTopology,
                        source,
                        targetTopology,
                        targetTopology.component(targetId),
                        face,
                        channel,
                        profile
                );
                if (!bands.isEmpty()) {
                    edges.add(new BoundaryEdgeBuild(sourceId, targetId, bands));
                    offsets[sourceId + 1]++;
                }
            }
        }
        for (int component = 1; component < offsets.length; component++) {
            offsets[component] += offsets[component - 1];
        }

        int[] targets = new int[edges.size()];
        int[] bandOffsets = new int[edges.size() + 1];
        int bandCount = edges.stream().mapToInt(edge -> edge.bands().size()).sum();
        byte[] shifts = new byte[bandCount];
        long[] masks = new long[bandCount * 4];
        int edgeCursor = 0;
        int bandCursor = 0;
        for (BoundaryEdgeBuild edge : edges) {
            targets[edgeCursor] = edge.targetComponent();
            bandOffsets[edgeCursor] = bandCursor;
            for (BoundaryBand band : edge.bands()) {
                if (band.verticalShift() < Byte.MIN_VALUE || band.verticalShift() > Byte.MAX_VALUE) {
                    throw new IllegalArgumentException("boundary shift cannot be encoded");
                }
                shifts[bandCursor] = (byte) band.verticalShift();
                masks[bandCursor * 4] = band.mask0();
                masks[bandCursor * 4 + 1] = band.mask1();
                masks[bandCursor * 4 + 2] = band.mask2();
                masks[bandCursor * 4 + 3] = band.mask3();
                bandCursor++;
            }
            edgeCursor++;
        }
        bandOffsets[edges.size()] = bandCursor;
        return new BoundaryLinks(face, offsets, targets, bandOffsets, shifts, masks);
    }

    private static void addLocalEdges(Map<SectionPos, BaseClusterTopology> children,
                                      BaseClusterTopology.TraversalProfile profile,
                                      Map<ComponentRef, Integer> nodeIndices,
                                      List<List<NodeEdge>> adjacency) {
        for (BaseClusterTopology topology : children.values()) {
            for (BaseClusterTopology.LocalConnection local : topology.localConnections()) {
                if (!profile.supports(local)) {
                    continue;
                }
                Integer from = nodeIndices.get(new ComponentRef(topology.section(), local.fromComponent()));
                Integer to = nodeIndices.get(new ComponentRef(topology.section(), local.toComponent()));
                if (from != null && to != null) {
                    adjacency.get(from).add(new NodeEdge(to, local.lowerBound()));
                }
            }
        }
    }

    private static void addInternalBoundaryEdges(SectionPos origin,
                                                 Map<SectionPos, BaseClusterTopology> children,
                                                 BaseClusterTopology.Channel channel,
                                                 BaseClusterTopology.TraversalProfile profile,
                                                 Map<ComponentRef, Integer> nodeIndices,
                                                 List<List<NodeEdge>> adjacency) {
        for (SectionPos sourceSection : childSections(origin)) {
            for (Direction face : INTERNAL_DIRECTIONS) {
                SectionPos targetSection = offset(sourceSection, face, 1);
                if (!contains(origin, targetSection)) {
                    continue;
                }
                addDirectedBoundaryEdges(
                        children.get(sourceSection),
                        children.get(targetSection),
                        face,
                        channel,
                        profile,
                        nodeIndices,
                        adjacency
                );
                addDirectedBoundaryEdges(
                        children.get(targetSection),
                        children.get(sourceSection),
                        face.getOpposite(),
                        channel,
                        profile,
                        nodeIndices,
                        adjacency
                );
            }
        }
    }

    private static void addDirectedBoundaryEdges(BaseClusterTopology sourceTopology,
                                                 BaseClusterTopology targetTopology,
                                                 Direction face,
                                                 BaseClusterTopology.Channel channel,
                                                 BaseClusterTopology.TraversalProfile profile,
                                                 Map<ComponentRef, Integer> nodeIndices,
                                                 List<List<NodeEdge>> adjacency) {
        Direction opposite = face.getOpposite();
        for (BaseClusterTopology.Component source : sourceTopology.boundaryComponents(face, channel)) {
            int from = nodeIndices.get(new ComponentRef(sourceTopology.section(), source.id()));
            for (BaseClusterTopology.Component target
                    : targetTopology.boundaryComponents(opposite, channel)) {
                if (boundaryBands(
                        sourceTopology,
                        source,
                        targetTopology,
                        target,
                        face,
                        channel,
                        profile
                ).isEmpty()) {
                    continue;
                }
                int to = nodeIndices.get(new ComponentRef(targetTopology.section(), target.id()));
                adjacency.get(from).add(new NodeEdge(to, 1.0F));
            }
        }
    }

    private static List<Aggregate> buildAggregates(SectionPos origin,
                                                   Map<SectionPos, BaseClusterTopology> children,
                                                   List<ComponentRef> nodes,
                                                   int[] aggregateByNode,
                                                   int aggregateCount) {
        int[] counts = new int[aggregateCount];
        int[] faceMasks = new int[aggregateCount];
        BlockPos[] anchors = new BlockPos[aggregateCount];
        for (int node = 0; node < nodes.size(); node++) {
            int aggregate = aggregateByNode[node];
            ComponentRef reference = nodes.get(node);
            BaseClusterTopology.Component component = children.get(reference.section())
                    .component(reference.componentId());
            counts[aggregate]++;
            faceMasks[aggregate] |= outerFaceMask(origin, reference.section(), component);
            if (anchors[aggregate] == null) {
                anchors[aggregate] = componentAnchor(reference.section(), component);
            }
        }
        List<Aggregate> result = new ArrayList<>(aggregateCount);
        for (int id = 0; id < aggregateCount; id++) {
            result.add(new Aggregate(id, anchors[id], counts[id], faceMasks[id]));
        }
        return List.copyOf(result);
    }

    private static List<List<AggregateEdge>> buildAggregateEdges(List<List<NodeEdge>> adjacency,
                                                                 int[] aggregateByNode,
                                                                 int aggregateCount) {
        List<Map<Integer, Float>> best = new ArrayList<>(aggregateCount);
        for (int id = 0; id < aggregateCount; id++) {
            best.add(new HashMap<>());
        }
        for (int from = 0; from < adjacency.size(); from++) {
            int sourceAggregate = aggregateByNode[from];
            for (NodeEdge edge : adjacency.get(from)) {
                int targetAggregate = aggregateByNode[edge.to()];
                if (sourceAggregate != targetAggregate) {
                    best.get(sourceAggregate).merge(targetAggregate, edge.lowerBound(), Math::min);
                }
            }
        }
        List<List<AggregateEdge>> result = new ArrayList<>(aggregateCount);
        for (Map<Integer, Float> targets : best) {
            result.add(targets.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new AggregateEdge(entry.getKey(), entry.getValue()))
                    .toList());
        }
        return List.copyOf(result);
    }

    private static Map<Direction, List<BoundaryPlane>> buildBoundaryPlanes(
            SectionPos origin,
            Map<SectionPos, BaseClusterTopology> children,
            Map<ComponentRef, Integer> nodeIndices,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile) {
        EnumMap<Direction, List<BoundaryPlane>> result = new EnumMap<>(Direction.class);
        for (SectionPos section : childSections(origin)) {
            BaseClusterTopology topology = children.get(section);
            for (Direction face : Direction.values()) {
                if (!isOuterChild(origin, section, face)) {
                    continue;
                }
                char[] labels = new char[BaseClusterTopology.SIDE * BaseClusterTopology.SIDE];
                boolean nonEmpty = false;
                for (BaseClusterTopology.Component component : topology.boundaryComponents(face, channel)) {
                    Integer node = nodeIndices.get(new ComponentRef(section, component.id()));
                    if (node == null || node >= Character.MAX_VALUE) {
                        throw new IllegalStateException("super-cluster boundary component is not indexed");
                    }
                    char encoded = (char) (node + 1);
                    for (int word = 0; word < 4; word++) {
                        long remaining = component.boundaryMaskWord(face, word);
                        while (remaining != 0L) {
                            int index = word * Long.SIZE + Long.numberOfTrailingZeros(remaining);
                            int u = index & 15;
                            int v = index >>> 4;
                            if (hasBoundaryHeadroom(topology, face, u, v, channel, profile)) {
                                labels[index] = encoded;
                                nonEmpty = true;
                            }
                            remaining &= remaining - 1L;
                        }
                    }
                }
                if (nonEmpty) {
                    result.computeIfAbsent(face, ignored -> new ArrayList<>())
                            .add(new BoundaryPlane(section, labels));
                }
            }
        }
        return result;
    }

    @Nullable
    private static BoundaryPlane plane(List<BoundaryPlane> planes, SectionPos section) {
        for (BoundaryPlane plane : planes) {
            if (plane.section().equals(section)) {
                return plane;
            }
        }
        return null;
    }

    private void collectPlaneCrossings(BoundaryPlane source,
                                       BoundaryPlane target,
                                       Direction face,
                                       SuperClusterTopology neighbor,
                                       Map<Integer, Map<Integer, Float>> output) {
        int minimumWidth = Math.max(1, (int) Math.ceil(profile.width()));
        if (channel == BaseClusterTopology.Channel.VOLUME || face.getAxis().isVertical()) {
            if (allowsVerticalBoundary(face, channel, profile)) {
                collectPlaneCrossings(source, target, 0, minimumWidth, neighbor, output);
            }
            return;
        }
        int maximum = Math.max(profile.maxStep(), profile.maxDrop());
        for (int magnitude = 0; magnitude <= maximum; magnitude++) {
            if (magnitude <= profile.maxStep()) {
                collectPlaneCrossings(source, target, magnitude, minimumWidth, neighbor, output);
            }
            if (magnitude > 0 && magnitude <= profile.maxDrop()) {
                collectPlaneCrossings(source, target, -magnitude, minimumWidth, neighbor, output);
            }
        }
    }

    private void collectPlaneCrossings(BoundaryPlane source,
                                       BoundaryPlane target,
                                       int verticalShift,
                                       int minimumWidth,
                                       SuperClusterTopology neighbor,
                                       Map<Integer, Map<Integer, Float>> output) {
        char[] sourceLabels = source.labels();
        char[] targetLabels = target.labels();
        for (int v = 0; v < BaseClusterTopology.SIDE; v++) {
            int targetV = v + verticalShift;
            if (targetV < 0 || targetV >= BaseClusterTopology.SIDE) {
                continue;
            }
            long previousPair = -1L;
            int run = 0;
            for (int u = 0; u < BaseClusterTopology.SIDE; u++) {
                int sourceNode = sourceLabels[(v << 4) | u] - 1;
                int targetNode = targetLabels[(targetV << 4) | u] - 1;
                if (sourceNode < 0 || targetNode < 0) {
                    previousPair = -1L;
                    run = 0;
                    continue;
                }
                long pair = ((long) sourceNode << 32) | Integer.toUnsignedLong(targetNode);
                run = pair == previousPair ? run + 1 : 1;
                previousPair = pair;
                if (run == minimumWidth) {
                    int sourceAggregate = aggregateByNode[sourceNode];
                    int targetAggregate = neighbor.aggregateByNode[targetNode];
                    output.computeIfAbsent(sourceAggregate, ignored -> new HashMap<>())
                            .putIfAbsent(targetAggregate, 1.0F);
                }
            }
        }
    }

    private static boolean allowsVerticalBoundary(
            Direction face,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile) {
        if (channel != BaseClusterTopology.Channel.GROUND || !face.getAxis().isVertical()) {
            return true;
        }
        return face == Direction.UP ? profile.maxStep() >= 1 : profile.maxDrop() >= 1;
    }

    private static int[] stronglyConnectedComponents(List<List<NodeEdge>> adjacency) {
        int size = adjacency.size();
        if (size == 0) {
            return new int[0];
        }
        List<List<Integer>> reverse = new ArrayList<>(size);
        for (int node = 0; node < size; node++) {
            reverse.add(new ArrayList<>());
        }
        for (int from = 0; from < size; from++) {
            for (NodeEdge edge : adjacency.get(from)) {
                reverse.get(edge.to()).add(from);
            }
        }

        boolean[] seen = new boolean[size];
        int[] cursor = new int[size];
        int[] stack = new int[size];
        int[] finishOrder = new int[size];
        int finished = 0;
        for (int root = 0; root < size; root++) {
            if (seen[root]) {
                continue;
            }
            int depth = 0;
            stack[depth++] = root;
            seen[root] = true;
            while (depth > 0) {
                int node = stack[depth - 1];
                if (cursor[node] < adjacency.get(node).size()) {
                    int next = adjacency.get(node).get(cursor[node]++).to();
                    if (!seen[next]) {
                        seen[next] = true;
                        stack[depth++] = next;
                    }
                } else {
                    depth--;
                    finishOrder[finished++] = node;
                }
            }
        }

        int[] aggregateByNode = new int[size];
        Arrays.fill(aggregateByNode, -1);
        int aggregate = 0;
        for (int order = finished - 1; order >= 0; order--) {
            int root = finishOrder[order];
            if (aggregateByNode[root] >= 0) {
                continue;
            }
            int depth = 0;
            stack[depth++] = root;
            aggregateByNode[root] = aggregate;
            while (depth > 0) {
                int node = stack[--depth];
                for (int next : reverse.get(node)) {
                    if (aggregateByNode[next] < 0) {
                        aggregateByNode[next] = aggregate;
                        stack[depth++] = next;
                    }
                }
            }
            aggregate++;
        }
        return aggregateByNode;
    }

    private static void addOverlap(List<BoundaryBand> output,
                                   BaseClusterTopology sourceTopology,
                                   BaseClusterTopology.Component source,
                                   BaseClusterTopology targetTopology,
                                   BaseClusterTopology.Component target,
                                   Direction sourceFace,
                                   Direction targetFace,
                                   int shift,
                                   int minimumWidth,
                                   BaseClusterTopology.Channel channel,
                                   BaseClusterTopology.TraversalProfile profile) {
        BoundaryBand band = overlap(
                sourceTopology,
                source,
                targetTopology,
                target,
                sourceFace,
                targetFace,
                shift,
                minimumWidth,
                channel,
                profile
        );
        if (band != null) {
            output.add(band);
        }
    }

    @Nullable
    private static BoundaryBand overlap(BaseClusterTopology sourceTopology,
                                        BaseClusterTopology.Component source,
                                        BaseClusterTopology targetTopology,
                                        BaseClusterTopology.Component target,
                                        Direction sourceFace,
                                        Direction targetFace,
                                        int verticalShift,
                                        int minimumWidth,
                                        BaseClusterTopology.Channel channel,
                                        BaseClusterTopology.TraversalProfile profile) {
        long[] overlapMask = new long[4];
        boolean wideEnough = false;
        for (int v = 0; v < BaseClusterTopology.SIDE; v++) {
            int targetV = v + verticalShift;
            if (targetV < 0 || targetV >= BaseClusterTopology.SIDE) {
                continue;
            }
            int run = 0;
            for (int u = 0; u < BaseClusterTopology.SIDE; u++) {
                int sourceIndex = (v << 4) | u;
                int targetIndex = (targetV << 4) | u;
                boolean matches = bitSet(source, sourceFace, sourceIndex)
                        && bitSet(target, targetFace, targetIndex)
                        && hasBoundaryHeadroom(sourceTopology, sourceFace, u, v, channel, profile)
                        && hasBoundaryHeadroom(
                                targetTopology,
                                targetFace,
                                u,
                                targetV,
                                channel,
                                profile
                        );
                if (matches) {
                    overlapMask[sourceIndex >>> 6] |= 1L << (sourceIndex & 63);
                }
                run = matches ? run + 1 : 0;
                if (run >= minimumWidth) {
                    wideEnough = true;
                }
            }
        }
        return wideEnough
                ? new BoundaryBand(
                        verticalShift,
                        overlapMask[0],
                        overlapMask[1],
                        overlapMask[2],
                        overlapMask[3]
                )
                : null;
    }

    private static boolean hasBoundaryHeadroom(BaseClusterTopology topology,
                                               Direction face,
                                               int u,
                                               int v,
                                               BaseClusterTopology.Channel channel,
                                               BaseClusterTopology.TraversalProfile profile) {
        if (channel != BaseClusterTopology.Channel.GROUND || face.getAxis().isVertical()) {
            return true;
        }
        int heightCells = Math.max(1, (int) Math.ceil(profile.height()));
        for (int offset = 0; offset < heightCells; offset++) {
            int y = v + offset;
            if (y >= BaseClusterTopology.SIDE) {
                continue;
            }
            int x = face == Direction.WEST || face == Direction.EAST
                    ? (face == Direction.WEST ? 0 : 15)
                    : u;
            int z = face == Direction.NORTH || face == Direction.SOUTH
                    ? (face == Direction.NORTH ? 0 : 15)
                    : u;
            if (topology.componentAt(BaseClusterTopology.Channel.VOLUME, x, y, z) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean bitSet(BaseClusterTopology.Component component,
                                  Direction face,
                                  int index) {
        return (component.boundaryMaskWord(face, index >>> 6)
                & (1L << (index & 63))) != 0L;
    }

    private static int outerFaceMask(SectionPos origin,
                                     SectionPos section,
                                     BaseClusterTopology.Component component) {
        int result = 0;
        for (Direction face : Direction.values()) {
            if (isOuterChild(origin, section, face) && component.touches(face)) {
                result |= 1 << face.ordinal();
            }
        }
        return result;
    }

    private static boolean isOuterChild(SectionPos origin,
                                        SectionPos section,
                                        Direction face) {
        return switch (face) {
            case DOWN -> section.y() == origin.y();
            case UP -> section.y() == origin.y() + CHILDREN_PER_AXIS - 1;
            case NORTH -> section.z() == origin.z();
            case SOUTH -> section.z() == origin.z() + CHILDREN_PER_AXIS - 1;
            case WEST -> section.x() == origin.x();
            case EAST -> section.x() == origin.x() + CHILDREN_PER_AXIS - 1;
        };
    }

    private static boolean contains(SectionPos origin, SectionPos section) {
        return section.x() >= origin.x() && section.x() < origin.x() + CHILDREN_PER_AXIS
                && section.y() >= origin.y() && section.y() < origin.y() + CHILDREN_PER_AXIS
                && section.z() >= origin.z() && section.z() < origin.z() + CHILDREN_PER_AXIS;
    }

    private static boolean isAlignedOrigin(SectionPos origin) {
        return Math.floorMod(origin.x(), CHILDREN_PER_AXIS) == 0
                && Math.floorMod(origin.y(), CHILDREN_PER_AXIS) == 0
                && Math.floorMod(origin.z(), CHILDREN_PER_AXIS) == 0;
    }

    private static BlockPos componentAnchor(SectionPos section,
                                            BaseClusterTopology.Component component) {
        return new BlockPos(
                section.minBlockX() + component.anchorX(),
                section.minBlockY() + component.anchorY(),
                section.minBlockZ() + component.anchorZ()
        );
    }

    private static long signature(SectionPos origin,
                                  Map<SectionPos, BaseClusterTopology> children,
                                  BaseClusterTopology.Channel channel,
                                  BaseClusterTopology.TraversalProfile profile) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, origin.x());
        hash = mix(hash, origin.y());
        hash = mix(hash, origin.z());
        hash = mix(hash, channel.ordinal());
        hash = mix(hash, Float.floatToRawIntBits(profile.width()));
        hash = mix(hash, Float.floatToRawIntBits(profile.height()));
        hash = mix(hash, profile.maxStep());
        hash = mix(hash, profile.maxJump());
        hash = mix(hash, profile.maxDrop());
        hash = mix(hash, profile.acceptsFluid() ? 1 : 0);
        for (SectionPos section : childSections(origin)) {
            BaseClusterTopology child = children.get(section);
            hash = mix(hash, child.revision());
            hash = mix(hash, child.sourceFingerprint());
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static List<List<NodeEdge>> mutableAdjacency(int size) {
        List<List<NodeEdge>> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new ArrayList<>());
        }
        return result;
    }

    private static int[] buildAggregateOffsets(List<List<AggregateEdge>> outgoing) {
        int[] offsets = new int[outgoing.size() + 1];
        for (int aggregate = 0; aggregate < outgoing.size(); aggregate++) {
            offsets[aggregate + 1] = offsets[aggregate] + outgoing.get(aggregate).size();
        }
        return offsets;
    }

    private static <T> List<List<T>> freezeNested(List<List<T>> source) {
        return source.stream().map(List::copyOf).toList();
    }

    public record Aggregate(int id,
                            BlockPos anchor,
                            int baseComponentCount,
                            int boundaryFaceMask) {
        public Aggregate {
            if (id < 0 || baseComponentCount <= 0) {
                throw new IllegalArgumentException("invalid aggregate component");
            }
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        }

        public boolean touches(Direction face) {
            return (boundaryFaceMask & (1 << Objects.requireNonNull(face, "face").ordinal())) != 0;
        }
    }

    public record AggregateEdge(int targetAggregate, float lowerBound) {
        public AggregateEdge {
            if (targetAggregate < 0 || !Float.isFinite(lowerBound) || lowerBound < 0.0F) {
                throw new IllegalArgumentException("invalid aggregate edge");
            }
        }
    }

    public record Crossing(int targetAggregate, float lowerBound) {
        public Crossing {
            if (targetAggregate < 0 || !Float.isFinite(lowerBound) || lowerBound < 0.0F) {
                throw new IllegalArgumentException("invalid super-cluster crossing");
            }
        }
    }

    static final class CrossingIndex {
        private final Direction face;
        private final int[] offsets;
        private final int[] targets;
        private final float[] costs;

        private CrossingIndex(Direction face, int[] offsets, int[] targets, float[] costs) {
            this.face = Objects.requireNonNull(face, "face");
            this.offsets = offsets.clone();
            this.targets = targets.clone();
            this.costs = costs.clone();
            if (this.offsets.length == 0
                    || this.offsets[this.offsets.length - 1] != this.targets.length
                    || this.targets.length != this.costs.length) {
                throw new IllegalArgumentException("invalid crossing index");
            }
        }

        Direction face() {
            return face;
        }

        int edgeStart(int aggregateId) {
            return offsets[aggregateId];
        }

        int edgeEnd(int aggregateId) {
            return offsets[aggregateId + 1];
        }

        int targetAggregate(int edgeIndex) {
            return targets[edgeIndex];
        }

        float lowerBound(int edgeIndex) {
            return costs[edgeIndex];
        }

        int retainedBytes() {
            return 48
                    + offsets.length * Integer.BYTES
                    + targets.length * Integer.BYTES
                    + costs.length * Float.BYTES;
        }
    }

    static final class BoundaryLinks {
        private final Direction face;
        private final int[] offsets;
        private final int[] targets;
        private final int[] bandOffsets;
        private final byte[] verticalShifts;
        private final long[] masks;

        private BoundaryLinks(Direction face,
                              int[] offsets,
                              int[] targets,
                              int[] bandOffsets,
                              byte[] verticalShifts,
                              long[] masks) {
            this.face = Objects.requireNonNull(face, "face");
            this.offsets = offsets.clone();
            this.targets = targets.clone();
            this.bandOffsets = bandOffsets.clone();
            this.verticalShifts = verticalShifts.clone();
            this.masks = masks.clone();
            if (this.offsets.length == 0
                    || this.offsets[this.offsets.length - 1] != this.targets.length
                    || this.bandOffsets.length != this.targets.length + 1
                    || this.bandOffsets[this.bandOffsets.length - 1] != this.verticalShifts.length
                    || this.masks.length != this.verticalShifts.length * 4) {
                throw new IllegalArgumentException("invalid base boundary link index");
            }
        }

        Direction face() {
            return face;
        }

        int edgeStart(int componentId) {
            return offsets[componentId];
        }

        int edgeEnd(int componentId) {
            return offsets[componentId + 1];
        }

        int targetComponent(int edgeIndex) {
            return targets[edgeIndex];
        }

        int bandStart(int edgeIndex) {
            return bandOffsets[edgeIndex];
        }

        int bandEnd(int edgeIndex) {
            return bandOffsets[edgeIndex + 1];
        }

        int verticalShift(int bandIndex) {
            return verticalShifts[bandIndex];
        }

        long maskWord(int bandIndex, int word) {
            if (word < 0 || word >= 4) {
                throw new IndexOutOfBoundsException("boundary mask word must be in [0, 3]");
            }
            return masks[bandIndex * 4 + word];
        }

        int retainedBytes() {
            return 64
                    + offsets.length * Integer.BYTES
                    + targets.length * Integer.BYTES
                    + bandOffsets.length * Integer.BYTES
                    + verticalShifts.length
                    + masks.length * Long.BYTES;
        }
    }

    record BoundaryBand(int verticalShift,
                        long mask0,
                        long mask1,
                        long mask2,
                        long mask3) {
        BoundaryBand {
            if ((mask0 | mask1 | mask2 | mask3) == 0L) {
                throw new IllegalArgumentException("boundary band mask cannot be empty");
            }
        }
    }

    private record BoundaryEdgeBuild(int sourceComponent,
                                     int targetComponent,
                                     List<BoundaryBand> bands) {
    }

    private record ComponentRef(SectionPos section, int componentId) {
        private ComponentRef {
            Objects.requireNonNull(section, "section");
            if (componentId < 0) {
                throw new IllegalArgumentException("component ID must be non-negative");
            }
        }
    }

    private record BoundaryPlane(SectionPos section, char[] labels) {
        private BoundaryPlane {
            Objects.requireNonNull(section, "section");
            labels = Objects.requireNonNull(labels, "labels").clone();
            if (labels.length != BaseClusterTopology.SIDE * BaseClusterTopology.SIDE) {
                throw new IllegalArgumentException("boundary plane has the wrong size");
            }
        }
    }

    private record NodeEdge(int to, float lowerBound) {
    }
}
