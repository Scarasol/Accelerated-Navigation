package com.scarasol.acceleratednavigation.topology;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable 2x2x2 SCC condensation. Published state contains stamps and primitive arrays only. */
public final class SuperClusterTopology {

    public static final int CHILDREN_PER_AXIS = 2;
    private static final int CHILD_COUNT = 8;
    private static final Direction[] INTERNAL_DIRECTIONS = {
            Direction.EAST, Direction.UP, Direction.SOUTH
    };
    private static final int AGGREGATE_ANCHOR_MASK = 0x0fff;
    private static final int AGGREGATE_CHILD_SHIFT = 12;
    private static final int AGGREGATE_EXIT_SHIFT = 15;
    private static final int AGGREGATE_EXIT_MASK = 0x3fff;

    private final SectionPos origin;
    private final BaseClusterTopology.GeometryKey geometry;
    private final BaseClusterTopology.MovementKey movement;
    private final long[] childSignatures;
    private final int[] childComponentOffsets;
    private final int[] aggregateByChildComponent;
    private final int[] aggregateMetadata;
    private final int[] outgoingOffsets;
    private final int[] outgoingTargets;
    private final float[] outgoingCosts;
    private final long[] outgoingWitnesses;
    private final long signature;
    private final int retainedBytes;

    private SuperClusterTopology(SectionPos origin,
                                 BaseClusterTopology.GeometryKey geometry,
                                 BaseClusterTopology.MovementKey movement,
                                 long[] childSignatures,
                                 int[] childComponentOffsets,
                                 int[] aggregateByChildComponent,
                                 int[] aggregateMetadata,
                                 int[] outgoingOffsets,
                                 int[] outgoingTargets,
                                 float[] outgoingCosts,
                                 long[] outgoingWitnesses,
                                 long signature) {
        this.origin = origin;
        this.geometry = geometry;
        this.movement = movement;
        this.childSignatures = childSignatures;
        this.childComponentOffsets = childComponentOffsets;
        this.aggregateByChildComponent = aggregateByChildComponent;
        this.aggregateMetadata = aggregateMetadata;
        this.outgoingOffsets = outgoingOffsets;
        this.outgoingTargets = outgoingTargets;
        this.outgoingCosts = outgoingCosts;
        this.outgoingWitnesses = outgoingWitnesses;
        this.signature = signature;
        this.retainedBytes = 160
                + childSignatures.length * Long.BYTES
                + (childComponentOffsets.length + aggregateByChildComponent.length
                + aggregateMetadata.length
                + outgoingOffsets.length + outgoingTargets.length) * Integer.BYTES
                + outgoingCosts.length * Float.BYTES
                + outgoingWitnesses.length * Long.BYTES;
        validate();
    }

    public static SuperClusterTopology build(SectionPos origin,
                                             BaseClusterTopology[] children,
                                             BaseClusterTopology.GeometryKey geometry,
                                             BaseClusterTopology.MovementKey movement,
                                             BaseClusterTopology.BuildScratch scratch) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(children, "children");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(scratch, "scratch");
        if (!originOf(origin).equals(origin) || children.length != CHILD_COUNT) {
            throw new IllegalArgumentException("a parent requires one aligned 2x2x2 child set");
        }
        validateChildren(origin, children, geometry);
        int[] childOffsets = new int[CHILD_COUNT + 1];
        long[] signatures = new long[CHILD_COUNT];
        for (int child = 0; child < CHILD_COUNT; child++) {
            childOffsets[child + 1] = childOffsets[child] + children[child].componentCount();
            signatures[child] = children[child].signature();
        }

        EdgeBuffer edges = new EdgeBuffer(Math.max(16, childOffsets[CHILD_COUNT]));
        addLocalEdges(children, childOffsets, movement, edges);
        addInternalBoundaryEdges(origin, children, childOffsets, movement, edges);
        PrimitiveGraph graph = edges.freeze(childOffsets[CHILD_COUNT]);
        int[] aggregateByNode = stronglyConnectedComponents(graph, scratch);
        int aggregateCount = Arrays.stream(aggregateByNode).max().orElse(-1) + 1;
        int[] aggregateMetadata = buildAggregates(origin, children, childOffsets,
                aggregateByNode, aggregateCount, movement);
        PrimitiveGraph condensed = condense(graph, aggregateByNode, aggregateCount);
        long signature = signature(origin, geometry, movement, signatures);
        return new SuperClusterTopology(
                origin,
                geometry,
                movement,
                signatures,
                childOffsets,
                aggregateByNode,
                aggregateMetadata,
                condensed.offsets,
                condensed.targets,
                condensed.costs,
                condensed.witnesses,
                signature
        );
    }

    public SectionPos origin() {
        return origin;
    }

    public BaseClusterTopology.GeometryKey geometry() {
        return geometry;
    }

    public BaseClusterTopology.MovementKey movement() {
        return movement;
    }

    public long signature() {
        return signature;
    }

    public int retainedBytes() {
        return retainedBytes;
    }

    public int aggregateCount() {
        return aggregateMetadata.length;
    }

    BlockPos aggregateAnchor(int id) {
        checkAggregate(id);
        int packed = aggregateMetadata[id];
        SectionPos section = childSection(origin, (packed >>> AGGREGATE_CHILD_SHIFT) & 7);
        int cell = packed & AGGREGATE_ANCHOR_MASK;
        return new BlockPos(section.minBlockX() + BaseClusterTopology.x(cell),
                section.minBlockY() + BaseClusterTopology.y(cell),
                section.minBlockZ() + BaseClusterTopology.z(cell));
    }

    boolean hasPotentialExit(int aggregateId, SectionPos targetOrigin) {
        checkAggregate(aggregateId);
        int slot = parentSlot(origin, targetOrigin);
        return slot >= 0 && ((aggregateMetadata[aggregateId] >>> AGGREGATE_EXIT_SHIFT)
                & AGGREGATE_EXIT_MASK & (1 << slot)) != 0;
    }

    private void checkAggregate(int id) {
        if (id < 0 || id >= aggregateMetadata.length) {
            throw new IndexOutOfBoundsException("unknown aggregate " + id);
        }
    }

    public int aggregateId(SectionPos section, int componentId) {
        if (!contains(origin, section)) return -1;
        int child = childIndex(origin, section);
        int start = childComponentOffsets[child];
        int end = childComponentOffsets[child + 1];
        return componentId < 0 || start + componentId >= end
                ? -1 : aggregateByChildComponent[start + componentId];
    }

    int outgoingStart(int aggregateId) { checkAggregate(aggregateId); return outgoingOffsets[aggregateId]; }

    int outgoingEnd(int aggregateId) { checkAggregate(aggregateId); return outgoingOffsets[aggregateId + 1]; }

    int outgoingTarget(int edgeIndex) { return outgoingTargets[edgeIndex]; }

    float outgoingCost(int edgeIndex) { return outgoingCosts[edgeIndex]; }

    long outgoingWitness(int edgeIndex) { return outgoingWitnesses[edgeIndex]; }

    SectionPos nodeSection(int node) { return childSection(origin, childForNode(node)); }

    int nodeComponent(int node) { int child = childForNode(node); return node - childComponentOffsets[child]; }

    long nodeSignature(int node) { return childSignatures[childForNode(node)]; }

    private int childForNode(int node) {
        if (node < 0 || node >= childComponentOffsets[CHILD_COUNT])
            throw new IndexOutOfBoundsException("unknown parent node " + node);
        for (int child = 0; child < CHILD_COUNT; child++)
            if (node < childComponentOffsets[child + 1]) return child;
        throw new IllegalStateException("parent node is outside child offsets");
    }

    CrossingIndex crossingIndex(Direction face,
                                SuperClusterTopology neighbor,
                                BaseClusterTopology[] sourceChildren,
                                BaseClusterTopology[] targetChildren) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(neighbor, "neighbor");
        if (!compatibleNeighbor(face, neighbor)) {
            throw new IllegalArgumentException("incompatible adjacent parent");
        }
        validateChildren(origin, sourceChildren, geometry);
        validateChildren(neighbor.origin, targetChildren, geometry);
        Long2LongOpenHashMap merged = new Long2LongOpenHashMap();
        Long2LongOpenHashMap witnesses = new Long2LongOpenHashMap();
        Long2LongOpenHashMap primitiveFaces = new Long2LongOpenHashMap();
        for (BaseClusterTopology source : sourceChildren) {
            for (BaseClusterTopology target : targetChildren) {
                Direction primitiveFace = primitiveFace(source.section(), target.section());
                if (primitiveFace == null) continue;
                BoundaryLinks links = boundaryLinks(source, target, primitiveFace);
                for (int component = 0; component < source.componentCount(); component++) {
                    int sourceAggregate = aggregateId(source.section(), component);
                    for (int edge = links.edgeStart(component); edge < links.edgeEnd(component); edge++) {
                        if (!links.supports(edge, movement)) continue;
                        int targetAggregate = neighbor.aggregateId(
                                target.section(), links.targetComponent(edge));
                        int sourceChild = childIndex(origin, source.section());
                        int targetChild = childIndex(neighbor.origin, target.section());
                        int sourceNode = childComponentOffsets[sourceChild] + component;
                        int targetNode = neighbor.childComponentOffsets[targetChild]
                                + links.targetComponent(edge);
                        mergeCrossing(merged, witnesses, primitiveFaces, sourceAggregate,
                                targetAggregate, links.lowerBound(edge), sourceNode, targetNode,
                                primitiveFace);
                    }
                }
            }
        }
        return CrossingIndex.from(aggregateCount(), merged, witnesses, primitiveFaces);
    }

    public boolean matchesChildren(BaseClusterTopology[] current) {
        if (current.length != CHILD_COUNT) return false;
        for (int child = 0; child < CHILD_COUNT; child++) {
            BaseClusterTopology topology = current[child];
            if (topology == null || !topology.section().equals(childSection(origin, child))
                    || !topology.geometry().equals(geometry)
                    || topology.signature() != childSignatures[child]) {
                return false;
            }
        }
        return true;
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
        List<SectionPos> result = new ArrayList<>(CHILD_COUNT);
        for (int child = 0; child < CHILD_COUNT; child++) result.add(childSection(origin, child));
        return List.copyOf(result);
    }

    static BoundaryLinks boundaryLinks(BaseClusterTopology source,
                                       BaseClusterTopology target,
                                       Direction face) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(face, "face");
        if (!source.geometry().equals(target.geometry())
                || !adjacentWithVerticalBand(source.section(), target.section(), face)) {
            throw new IllegalArgumentException("base boundary views are not compatible");
        }

        Long2ObjectOpenHashMap<long[]> masks = new Long2ObjectOpenHashMap<>();
        int sectionYDelta = (target.section().y() - source.section().y()) * BaseClusterTopology.SIDE;
        int maximumDistance = face.getAxis().isVertical()
                || source.geometry().channel() == BaseClusterTopology.Channel.VOLUME
                ? 1 : BaseClusterTopology.MAX_STRUCTURAL_JUMP;
        for (int distance = 1; distance <= maximumDistance; distance++) {
            for (int sourceInset = 0; sourceInset < distance; sourceInset++) {
                int targetInset = distance - sourceInset - 1;
                for (int v = 0; v < BaseClusterTopology.SIDE; v++) {
                    for (int u = 0; u < BaseClusterTopology.SIDE; u++) {
                        int sourceCell = faceCell(face, u, v, sourceInset);
                        int sourceComponent = source.componentAt(BaseClusterTopology.x(sourceCell),
                                BaseClusterTopology.y(sourceCell), BaseClusterTopology.z(sourceCell));
                        if (sourceComponent < 0) continue;
                        if (face.getAxis().isVertical()) {
                            addBoundaryMask(masks, sourceComponent, target, face, u, v, v,
                                    targetInset, 0, face.getStepY());
                        } else if (source.geometry().channel() == BaseClusterTopology.Channel.VOLUME) {
                            int targetV = v - sectionYDelta;
                            if (targetV >= 0 && targetV < BaseClusterTopology.SIDE) {
                                addBoundaryMask(masks, sourceComponent, target, face, u, v, targetV,
                                        targetInset, distance, 0);
                            }
                        } else {
                            for (int targetV = 0; targetV < BaseClusterTopology.SIDE; targetV++) {
                                int dy = sectionYDelta + targetV - v;
                                if (dy < -BaseClusterTopology.MAX_STRUCTURAL_DROP
                                        || dy > BaseClusterTopology.MAX_STRUCTURAL_STEP) continue;
                                addBoundaryMask(masks, sourceComponent, target, face, u, v, targetV,
                                        targetInset, distance, dy);
                            }
                        }
                    }
                }
            }
        }
        return BoundaryLinks.from(face, source.componentCount(), masks);
    }

    private static void addBoundaryMask(Long2ObjectOpenHashMap<long[]> masks,
                                        int sourceComponent,
                                        BaseClusterTopology target,
                                        Direction face,
                                         int u,
                                         int sourceV,
                                         int targetV,
                                         int targetInset,
                                         int horizontalDistance,
                                         int dy) {
        int targetCell = faceCell(face.getOpposite(), u, targetV, targetInset);
        int targetComponent = target.componentAt(
                BaseClusterTopology.x(targetCell),
                BaseClusterTopology.y(targetCell),
                BaseClusterTopology.z(targetCell)
        );
        if (targetComponent < 0) return;
        long key = boundaryBandKey(sourceComponent, targetComponent, horizontalDistance, dy);
        long[] mask = masks.computeIfAbsent(key, ignored -> new long[4]);
        int bit = faceIndex(u, sourceV);
        mask[bit >>> 6] |= 1L << bit;
    }

    private static long boundaryBandKey(int source, int target, int horizontal, int shift) {
        return ((long) source << 32) | ((long) target << 16)
                | ((long) horizontal << 8) | (shift + 128L);
    }

    private static int boundarySource(long key) {
        return (int) (key >>> 32);
    }

    private static int boundaryTarget(long key) {
        return (int) ((key >>> 16) & 0xffffL);
    }

    private static int boundaryShift(long key) {
        return (int) (key & 0xffL) - 128;
    }

    private static int boundaryHorizontal(long key) {
        return (int) (key >>> 8) & 0xff;
    }

    private static void validateChildren(SectionPos origin,
                                         BaseClusterTopology[] children,
                                         BaseClusterTopology.GeometryKey geometry) {
        for (int child = 0; child < CHILD_COUNT; child++) {
            BaseClusterTopology topology = Objects.requireNonNull(children[child], "child");
            if (!topology.section().equals(childSection(origin, child))
                    || !topology.geometry().equals(geometry)) {
                throw new IllegalArgumentException("parent children are not in canonical order");
            }
        }
    }

    private static void addLocalEdges(BaseClusterTopology[] children,
                                      int[] childOffsets,
                                      BaseClusterTopology.MovementKey movement,
                                      EdgeBuffer output) {
        for (int child = 0; child < CHILD_COUNT; child++) {
            BaseClusterTopology topology = children[child];
            int nodeBase = childOffsets[child];
            for (int component = 0; component < topology.componentCount(); component++) {
                for (int edge = topology.localEdgeStart(component);
                     edge < topology.localEdgeEnd(component); edge++) {
                    if (topology.localEdgeSupports(edge, movement)) {
                        output.add(nodeBase + component,
                                nodeBase + topology.localEdgeTarget(edge),
                                topology.localEdgeLowerBound(edge));
                    }
                }
            }
        }
    }

    private static void addInternalBoundaryEdges(SectionPos origin,
                                                 BaseClusterTopology[] children,
                                                 int[] childOffsets,
                                                 BaseClusterTopology.MovementKey movement,
                                                 EdgeBuffer output) {
        for (int sourceChild = 0; sourceChild < CHILD_COUNT; sourceChild++) {
            SectionPos sourceSection = childSection(origin, sourceChild);
            for (Direction face : INTERNAL_DIRECTIONS) {
                if (face.getAxis().isVertical()) {
                    addInternalDirection(origin, children, childOffsets, sourceChild,
                            offset(sourceSection, face, 1), face, movement, output);
                    continue;
                }
                SectionPos horizontal = offset(sourceSection, face, 1);
                for (int yShift = -1; yShift <= 1; yShift++) {
                    SectionPos target = SectionPos.of(horizontal.x(), horizontal.y() + yShift,
                            horizontal.z());
                    addInternalDirection(origin, children, childOffsets, sourceChild,
                            target, face, movement, output);
                }
            }
        }
    }

    private static void addInternalDirection(SectionPos origin,
                                             BaseClusterTopology[] children,
                                             int[] childOffsets,
                                             int sourceChild,
                                             SectionPos targetSection,
                                             Direction face,
                                             BaseClusterTopology.MovementKey movement,
                                             EdgeBuffer output) {
        if (!contains(origin, targetSection)) return;
        int targetChild = childIndex(origin, targetSection);
        addDirectedBoundary(children[sourceChild], children[targetChild], childOffsets[sourceChild],
                childOffsets[targetChild], face, movement, output);
        addDirectedBoundary(children[targetChild], children[sourceChild], childOffsets[targetChild],
                childOffsets[sourceChild], face.getOpposite(), movement, output);
    }

    private static void addDirectedBoundary(BaseClusterTopology source,
                                            BaseClusterTopology target,
                                            int sourceBase,
                                            int targetBase,
                                            Direction face,
                                            BaseClusterTopology.MovementKey movement,
                                            EdgeBuffer output) {
        BoundaryLinks links = boundaryLinks(source, target, face);
        for (int component = 0; component < source.componentCount(); component++) {
            for (int edge = links.edgeStart(component); edge < links.edgeEnd(component); edge++) {
                if (links.supports(edge, movement)) {
                    output.add(sourceBase + component, targetBase + links.targetComponent(edge),
                            links.lowerBound(edge));
                }
            }
        }
    }

    private static int[] buildAggregates(SectionPos origin,
                                         BaseClusterTopology[] children,
                                         int[] childOffsets,
                                         int[] aggregateByNode,
                                         int aggregateCount,
                                         BaseClusterTopology.MovementKey movement) {
        int[] metadata = new int[aggregateCount];
        int[] exitMasks = new int[aggregateCount];
        Arrays.fill(metadata, Integer.MAX_VALUE);
        for (int child = 0; child < CHILD_COUNT; child++) {
            BaseClusterTopology topology = children[child];
            for (int component = 0; component < topology.componentCount(); component++) {
                int aggregate = aggregateByNode[childOffsets[child] + component];
                int anchor = topology.componentAnchorCell(component);
                int packedAnchor = anchor | (child << 12);
                metadata[aggregate] = Math.min(metadata[aggregate], packedAnchor);
            }
            for (Direction face : Direction.values()) {
                int slots = potentialParentSlots(origin, topology.section(), face);
                if (slots == 0) continue;
                int maximumDistance = face.getAxis().isVertical()
                        || topology.geometry().channel() == BaseClusterTopology.Channel.VOLUME
                        ? 1 : BaseClusterTopology.MAX_STRUCTURAL_JUMP;
                for (int distance = 1; distance <= maximumDistance; distance++) {
                    for (int inset = 0; inset < distance; inset++) {
                        for (int v = 0; v < BaseClusterTopology.SIDE; v++) {
                            for (int u = 0; u < BaseClusterTopology.SIDE; u++) {
                                int cell = faceCell(face, u, v, inset);
                                int component = topology.componentAt(
                                        BaseClusterTopology.x(cell),
                                        BaseClusterTopology.y(cell),
                                        BaseClusterTopology.z(cell)
                                );
                                if (component < 0) continue;
                                int aggregate = aggregateByNode[childOffsets[child] + component];
                                exitMasks[aggregate] |= slots;
                            }
                        }
                    }
                }
            }
        }
        for (int aggregate = 0; aggregate < aggregateCount; aggregate++) {
            metadata[aggregate] |= (exitMasks[aggregate] & AGGREGATE_EXIT_MASK)
                    << AGGREGATE_EXIT_SHIFT;
        }
        return metadata;
    }

    private static int potentialParentSlots(SectionPos parentOrigin,
                                            SectionPos sourceSection,
                                            Direction face) {
        int slots = 0;
        int firstShift = face.getAxis().isVertical() ? 0 : -1;
        int lastShift = face.getAxis().isVertical() ? 0 : 1;
        for (int yShift = firstShift; yShift <= lastShift; yShift++) {
            SectionPos targetSection = SectionPos.of(
                    sourceSection.x() + face.getStepX(),
                    sourceSection.y() + face.getStepY() + yShift,
                    sourceSection.z() + face.getStepZ()
            );
            int slot = parentSlot(parentOrigin, originOf(targetSection));
            if (slot >= 0) slots |= 1 << slot;
        }
        return slots;
    }

    private static int parentSlot(SectionPos sourceOrigin, SectionPos targetOrigin) {
        int dx = targetOrigin.x() - sourceOrigin.x();
        int dy = targetOrigin.y() - sourceOrigin.y();
        int dz = targetOrigin.z() - sourceOrigin.z();
        if (dx == 0 && dz == 0 && dy == -CHILDREN_PER_AXIS) return 12;
        if (dx == 0 && dz == 0 && dy == CHILDREN_PER_AXIS) return 13;
        if (dy % CHILDREN_PER_AXIS != 0) return -1;
        int yShift = dy / CHILDREN_PER_AXIS;
        int direction;
        if (dx == 0 && dz == -CHILDREN_PER_AXIS) direction = 0;
        else if (dx == CHILDREN_PER_AXIS && dz == 0) direction = 1;
        else if (dx == 0 && dz == CHILDREN_PER_AXIS) direction = 2;
        else if (dx == -CHILDREN_PER_AXIS && dz == 0) direction = 3;
        else return -1;
        return yShift < -1 || yShift > 1 ? -1 : direction * 3 + yShift + 1;
    }

    private static Direction primitiveFace(SectionPos source, SectionPos target) {
        int dx = target.x() - source.x();
        int dy = target.y() - source.y();
        int dz = target.z() - source.z();
        if (dx != 0 && dz == 0 && Math.abs(dx) == 1 && Math.abs(dy) <= 1) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0 && dx == 0 && Math.abs(dz) == 1 && Math.abs(dy) <= 1) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        if (dx == 0 && dz == 0 && Math.abs(dy) == 1) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return null;
    }

    private static PrimitiveGraph condense(PrimitiveGraph graph,
                                           int[] aggregateByNode,
                                           int aggregateCount) {
        int[] nodeOffsets = new int[aggregateCount + 1];
        for (int aggregate : aggregateByNode) nodeOffsets[aggregate + 1]++;
        for (int index = 1; index < nodeOffsets.length; index++) {
            nodeOffsets[index] += nodeOffsets[index - 1];
        }
        int[] groupedNodes = new int[aggregateByNode.length];
        int[] nodeCursors = nodeOffsets.clone();
        for (int node = 0; node < aggregateByNode.length; node++) {
            groupedNodes[nodeCursors[aggregateByNode[node]]++] = node;
        }

        int[] seen = new int[aggregateCount];
        Arrays.fill(seen, -1);
        int[] offsets = new int[aggregateCount + 1];
        for (int source = 0; source < aggregateCount; source++) {
            for (int grouped = nodeOffsets[source]; grouped < nodeOffsets[source + 1]; grouped++) {
                int node = groupedNodes[grouped];
                for (int edge = graph.offsets[node]; edge < graph.offsets[node + 1]; edge++) {
                    int target = aggregateByNode[graph.targets[edge]];
                    if (target != source && seen[target] != source) {
                        seen[target] = source;
                        offsets[source + 1]++;
                    }
                }
            }
        }
        for (int index = 1; index < offsets.length; index++) offsets[index] += offsets[index - 1];

        Arrays.fill(seen, -1);
        int[] touched = new int[aggregateCount];
        float[] best = new float[aggregateCount];
        long[] bestWitnesses = new long[aggregateCount];
        int[] targets = new int[offsets[aggregateCount]];
        float[] costs = new float[targets.length];
        long[] witnesses = new long[targets.length];
        for (int source = 0; source < aggregateCount; source++) {
            int touchedCount = 0;
            for (int grouped = nodeOffsets[source]; grouped < nodeOffsets[source + 1]; grouped++) {
                int node = groupedNodes[grouped];
                for (int edge = graph.offsets[node]; edge < graph.offsets[node + 1]; edge++) {
                    int target = aggregateByNode[graph.targets[edge]];
                    if (target == source) continue;
                    if (seen[target] != source) {
                        seen[target] = source;
                        best[target] = graph.costs[edge];
                        bestWitnesses[target] = (long) node << 32
                                | Integer.toUnsignedLong(graph.targets[edge]);
                        touched[touchedCount++] = target;
                    } else if (graph.costs[edge] < best[target]
                            || (graph.costs[edge] == best[target]
                            && Long.compareUnsigned((long) node << 32
                            | Integer.toUnsignedLong(graph.targets[edge]), bestWitnesses[target]) < 0)) {
                        best[target] = Math.min(best[target], graph.costs[edge]);
                        bestWitnesses[target] = (long) node << 32
                                | Integer.toUnsignedLong(graph.targets[edge]);
                    }
                }
            }
            int cursor = offsets[source];
            for (int index = 0; index < touchedCount; index++) {
                int target = touched[index];
                targets[cursor] = target;
                costs[cursor++] = best[target];
                int edge = cursor - 1;
                witnesses[edge] = bestWitnesses[target];
            }
        }
        return new PrimitiveGraph(offsets, targets, costs, witnesses);
    }

    private static int[] stronglyConnectedComponents(PrimitiveGraph graph,
                                                      BaseClusterTopology.BuildScratch scratch) {
        int nodes = graph.offsets.length - 1;
        int[] reverseCounts = scratch.parentCounts;
        Arrays.fill(reverseCounts, 0, nodes + 1, 0);
        for (int target : graph.targets) reverseCounts[target + 1]++;
        for (int node = 1; node <= nodes; node++) reverseCounts[node] += reverseCounts[node - 1];
        int[] reverse = new int[graph.targets.length];
        int[] cursors = scratch.parentCursors;
        System.arraycopy(reverseCounts, 0, cursors, 0, nodes + 1);
        for (int source = 0; source < nodes; source++) {
            for (int edge = graph.offsets[source]; edge < graph.offsets[source + 1]; edge++) {
                reverse[cursors[graph.targets[edge]]++] = source;
            }
        }

        boolean[] visited = scratch.parentVisited;
        Arrays.fill(visited, 0, nodes, false);
        int[] order = scratch.parentOrder;
        int orderSize = 0;
        int[] stackNodes = scratch.parentStackNodes;
        int[] stackEdges = scratch.parentStackEdges;
        for (int root = 0; root < nodes; root++) {
            if (visited[root]) continue;
            int depth = 0;
            stackNodes[0] = root;
            stackEdges[0] = graph.offsets[root];
            visited[root] = true;
            while (depth >= 0) {
                int node = stackNodes[depth];
                int edge = stackEdges[depth];
                if (edge < graph.offsets[node + 1]) {
                    int target = graph.targets[edge];
                    stackEdges[depth] = edge + 1;
                    if (!visited[target]) {
                        visited[target] = true;
                        depth++;
                        stackNodes[depth] = target;
                        stackEdges[depth] = graph.offsets[target];
                    }
                } else {
                    order[orderSize++] = node;
                    depth--;
                }
            }
        }

        int[] component = scratch.parentComponents;
        Arrays.fill(component, 0, nodes, -1);
        int componentCount = 0;
        for (int orderIndex = orderSize - 1; orderIndex >= 0; orderIndex--) {
            int root = order[orderIndex];
            if (component[root] >= 0) continue;
            int size = 0;
            stackNodes[size++] = root;
            component[root] = componentCount;
            while (size > 0) {
                int node = stackNodes[--size];
                for (int edge = reverseCounts[node]; edge < reverseCounts[node + 1]; edge++) {
                    int target = reverse[edge];
                    if (component[target] < 0) {
                        component[target] = componentCount;
                        stackNodes[size++] = target;
                    }
                }
            }
            componentCount++;
        }
        return Arrays.copyOf(component, nodes);
    }

    private boolean compatibleNeighbor(Direction face, SuperClusterTopology neighbor) {
        if (!neighbor.geometry.equals(geometry) || !neighbor.movement.equals(movement)) return false;
        SectionPos horizontal = offset(origin, face, CHILDREN_PER_AXIS);
        if (face.getAxis().isVertical()) return neighbor.origin.equals(horizontal);
        return neighbor.origin.x() == horizontal.x() && neighbor.origin.z() == horizontal.z()
                && Math.abs(neighbor.origin.y() - origin.y()) <= CHILDREN_PER_AXIS;
    }

    private static void mergeCrossing(Long2LongOpenHashMap merged,
                                      Long2LongOpenHashMap witnesses,
                                      Long2LongOpenHashMap primitiveFaces,
                                      int source,
                                      int target,
                                      float cost,
                                      int witnessSource,
                                      int witnessTarget,
                                      Direction primitiveFace) {
        long key = ((long) source << 32) | Integer.toUnsignedLong(target);
        long old = merged.getOrDefault(key, 0L);
        long witness = ((long) witnessSource << 32) | Integer.toUnsignedLong(witnessTarget);
        if (!merged.containsKey(key)
                || cost < Float.intBitsToFloat((int) old)
                || (cost == Float.intBitsToFloat((int) old)
                && Long.compareUnsigned(witness, witnesses.get(key)) < 0)) {
            merged.put(key, Integer.toUnsignedLong(Float.floatToRawIntBits(cost)));
            witnesses.put(key, witness);
            primitiveFaces.put(key, primitiveFace.ordinal());
        }
    }

    private static int faceCell(Direction face, int u, int v) {
        return faceCell(face, u, v, 0);
    }

    private static int faceCell(Direction face, int u, int v, int inset) {
        return switch (face) {
            case DOWN -> BaseClusterTopology.cellIndex(u, inset, v);
            case UP -> BaseClusterTopology.cellIndex(u, 15 - inset, v);
            case NORTH -> BaseClusterTopology.cellIndex(u, v, inset);
            case SOUTH -> BaseClusterTopology.cellIndex(u, v, 15 - inset);
            case WEST -> BaseClusterTopology.cellIndex(inset, v, u);
            case EAST -> BaseClusterTopology.cellIndex(15 - inset, v, u);
        };
    }

    private static int faceIndex(int u, int v) {
        return (v << 4) | u;
    }

    private static boolean adjacentWithVerticalBand(SectionPos source,
                                                    SectionPos target,
                                                    Direction face) {
        SectionPos direct = offset(source, face, 1);
        if (face.getAxis().isVertical()) return target.equals(direct);
        return target.x() == direct.x() && target.z() == direct.z()
                && Math.abs(target.y() - source.y()) <= 1;
    }

    private static boolean contains(SectionPos origin, SectionPos section) {
        return section.x() >= origin.x() && section.x() < origin.x() + CHILDREN_PER_AXIS
                && section.y() >= origin.y() && section.y() < origin.y() + CHILDREN_PER_AXIS
                && section.z() >= origin.z() && section.z() < origin.z() + CHILDREN_PER_AXIS;
    }

    private static int childIndex(SectionPos origin, SectionPos section) {
        int x = section.x() - origin.x();
        int y = section.y() - origin.y();
        int z = section.z() - origin.z();
        return x + z * CHILDREN_PER_AXIS + y * CHILDREN_PER_AXIS * CHILDREN_PER_AXIS;
    }

    private static SectionPos childSection(SectionPos origin, int child) {
        int x = child & 1;
        int z = (child >>> 1) & 1;
        int y = (child >>> 2) & 1;
        return SectionPos.of(origin.x() + x, origin.y() + y, origin.z() + z);
    }

    private static long signature(SectionPos origin,
                                  BaseClusterTopology.GeometryKey geometry,
                                  BaseClusterTopology.MovementKey movement,
                                  long[] signatures) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, origin.asLong());
        hash = mix(hash, geometry.hashCode());
        hash = mix(hash, movement.hashCode());
        for (int child = 0; child < CHILD_COUNT; child++) {
            hash = mix(hash, signatures[child]);
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private void validate() {
        if (childSignatures.length != CHILD_COUNT
                || childComponentOffsets.length != CHILD_COUNT + 1
                || childComponentOffsets[CHILD_COUNT] != aggregateByChildComponent.length
                || outgoingOffsets.length != aggregateMetadata.length + 1
                || outgoingOffsets[aggregateMetadata.length] != outgoingTargets.length
                || outgoingTargets.length != outgoingCosts.length
                || outgoingWitnesses.length != outgoingTargets.length) {
            throw new IllegalArgumentException("inconsistent primitive parent arrays");
        }
    }

    static final class CrossingIndex {
        private final int[] offsets;
        private final int[] targets;
        private final float[] costs;
        private final long[] witnesses;
        private final byte[] primitiveFaces;

        private CrossingIndex(int[] offsets,
                              int[] targets,
                              float[] costs,
                              long[] witnesses,
                              byte[] primitiveFaces) {
            this.offsets = offsets;
            this.targets = targets;
            this.costs = costs;
            this.witnesses = witnesses;
            this.primitiveFaces = primitiveFaces;
        }

        private static CrossingIndex empty(int sourceCount) {
            return new CrossingIndex(new int[sourceCount + 1], new int[0], new float[0],
                    new long[0], new byte[0]);
        }

        private static CrossingIndex from(int sourceCount,
                                          Long2LongOpenHashMap merged,
                                          Long2LongOpenHashMap witnessMap,
                                          Long2LongOpenHashMap primitiveFaceMap) {
            long[] keys = merged.keySet().toLongArray();
            Arrays.sort(keys);
            int[] offsets = new int[sourceCount + 1];
            int[] targets = new int[keys.length];
            float[] costs = new float[keys.length];
            long[] witnesses = new long[keys.length];
            byte[] primitiveFaces = new byte[keys.length];
            for (long key : keys) offsets[(int) (key >>> 32) + 1]++;
            for (int index = 1; index < offsets.length; index++) offsets[index] += offsets[index - 1];
            for (int index = 0; index < keys.length; index++) {
                targets[index] = (int) keys[index];
                costs[index] = Float.intBitsToFloat((int) merged.get(keys[index]));
                witnesses[index] = witnessMap.get(keys[index]);
                primitiveFaces[index] = (byte) primitiveFaceMap.getOrDefault(keys[index], -1L);
            }
            return new CrossingIndex(offsets, targets, costs, witnesses, primitiveFaces);
        }

        int edgeStart(int source) { return offsets[source]; }
        int edgeEnd(int source) { return offsets[source + 1]; }
        int targetAggregate(int edge) { return targets[edge]; }
        float lowerBound(int edge) { return costs[edge]; }
        long witness(int edge) { return witnesses[edge]; }
        Direction face(int edge) {
            int ordinal = primitiveFaces[edge];
            return ordinal < 0 ? null : Direction.values()[ordinal];
        }
        int retainedBytes() {
            return 48 + (offsets.length + targets.length) * Integer.BYTES
                    + costs.length * Float.BYTES
                    + witnesses.length * Long.BYTES
                    + primitiveFaces.length;
        }
    }

    static final class BoundaryLinks {
        private final Direction face;
        private final int[] offsets;
        private final int[] targets;
        private final int[] bandOffsets;
        private final byte[] verticalShifts;
        private final long[] capabilities;
        private final long[] masks;
        private final float[] lowerBounds;

        private BoundaryLinks(Direction face,
                              int[] offsets,
                              int[] targets,
                              int[] bandOffsets,
                              byte[] verticalShifts,
                              long[] capabilities,
                              long[] masks,
                              float[] lowerBounds) {
            this.face = face;
            this.offsets = offsets;
            this.targets = targets;
            this.bandOffsets = bandOffsets;
            this.verticalShifts = verticalShifts;
            this.capabilities = capabilities;
            this.masks = masks;
            this.lowerBounds = lowerBounds;
        }

        private static BoundaryLinks from(Direction face,
                                          int sourceCount,
                                          Long2ObjectOpenHashMap<long[]> sourceMasks) {
            long[] bands = sourceMasks.keySet().toLongArray();
            Arrays.sort(bands);
            List<Long> edgeKeys = new ArrayList<>();
            for (long band : bands) {
                long edge = ((long) boundarySource(band) << 32)
                        | Integer.toUnsignedLong(boundaryTarget(band));
                if (edgeKeys.isEmpty() || edgeKeys.get(edgeKeys.size() - 1) != edge) edgeKeys.add(edge);
            }
            int[] offsets = new int[sourceCount + 1];
            int[] targets = new int[edgeKeys.size()];
            int[] bandOffsets = new int[edgeKeys.size() + 1];
            for (long edge : edgeKeys) offsets[(int) (edge >>> 32) + 1]++;
            for (int index = 1; index < offsets.length; index++) offsets[index] += offsets[index - 1];
            int bandCursor = 0;
            for (int edge = 0; edge < edgeKeys.size(); edge++) {
                long edgeKey = edgeKeys.get(edge);
                targets[edge] = (int) edgeKey;
                bandOffsets[edge] = bandCursor;
                while (bandCursor < bands.length) {
                    long bandEdge = ((long) boundarySource(bands[bandCursor]) << 32)
                            | Integer.toUnsignedLong(boundaryTarget(bands[bandCursor]));
                    if (bandEdge != edgeKey) break;
                    bandCursor++;
                }
            }
            bandOffsets[edgeKeys.size()] = bands.length;
            byte[] shifts = new byte[bands.length];
            long[] capabilities = new long[bands.length];
            long[] masks = new long[bands.length * 4];
            float[] lowerBounds = new float[edgeKeys.size()];
            Arrays.fill(lowerBounds, Float.POSITIVE_INFINITY);
            for (int band = 0; band < bands.length; band++) {
                int shift = boundaryShift(bands[band]);
                shifts[band] = (byte) shift;
                capabilities[band] = BaseClusterTopology.supportingCapabilities(
                        Math.max(0, shift), Math.max(0, boundaryHorizontal(bands[band]) - 1),
                        Math.max(0, -shift));
                System.arraycopy(sourceMasks.get(bands[band]), 0, masks, band * 4, 4);
                int source = boundarySource(bands[band]);
                int edge = offsets[source];
                while (targets[edge] != boundaryTarget(bands[band])) edge++;
                int horizontal = boundaryHorizontal(bands[band]);
                lowerBounds[edge] = Math.min(lowerBounds[edge],
                        (float) Math.sqrt(horizontal * horizontal + shift * shift));
            }
            return new BoundaryLinks(face, offsets, targets, bandOffsets, shifts, capabilities, masks,
                    lowerBounds);
        }

        Direction face() { return face; }
        int edgeStart(int source) { return offsets[source]; }
        int edgeEnd(int source) {
            return source + 1 < offsets.length ? offsets[source + 1] : offsets[source];
        }
        int targetComponent(int edge) { return targets[edge]; }
        int bandStart(int edge) { return bandOffsets[edge]; }
        int bandEnd(int edge) { return bandOffsets[edge + 1]; }
        int verticalShift(int band) { return verticalShifts[band]; }
        long capabilityMask(int band) { return capabilities[band]; }
        boolean supports(int edge, BaseClusterTopology.MovementKey movement) {
            for (int band = bandStart(edge); band < bandEnd(edge); band++) {
                if ((capabilities[band] & movement.capabilityMask()) != 0L) return true;
            }
            return false;
        }
        long maskWord(int band, int word) { return masks[band * 4 + word]; }
        float lowerBound(int edge) { return lowerBounds[edge]; }
        int retainedBytes() {
            return 72 + (offsets.length + targets.length + bandOffsets.length) * Integer.BYTES
                    + verticalShifts.length + capabilities.length * Long.BYTES + masks.length * Long.BYTES
                    + lowerBounds.length * Float.BYTES;
        }
    }

    private static final class EdgeBuffer {
        private int[] sources;
        private int[] targets;
        private float[] costs;
        private int size;

        private EdgeBuffer(int capacity) {
            sources = new int[capacity];
            targets = new int[capacity];
            costs = new float[capacity];
        }

        private void add(int source, int target, float cost) {
            if (size == sources.length) {
                int capacity = Math.max(16, size << 1);
                sources = Arrays.copyOf(sources, capacity);
                targets = Arrays.copyOf(targets, capacity);
                costs = Arrays.copyOf(costs, capacity);
            }
            sources[size] = source;
            targets[size] = target;
            costs[size++] = cost;
        }

        private PrimitiveGraph freeze(int nodeCount) {
            int[] offsets = new int[nodeCount + 1];
            for (int index = 0; index < size; index++) offsets[sources[index] + 1]++;
            for (int node = 1; node < offsets.length; node++) offsets[node] += offsets[node - 1];
            int[] cursors = offsets.clone();
            int[] graphTargets = new int[size];
            float[] graphCosts = new float[size];
            for (int index = 0; index < size; index++) {
                int edge = cursors[sources[index]]++;
                graphTargets[edge] = targets[index];
                graphCosts[edge] = costs[index];
            }
            return new PrimitiveGraph(offsets, graphTargets, graphCosts, null);
        }
    }

    private record PrimitiveGraph(int[] offsets, int[] targets, float[] costs, long[] witnesses) {
    }

}
