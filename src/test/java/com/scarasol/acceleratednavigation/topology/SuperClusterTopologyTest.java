package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.scarasol.acceleratednavigation.topology.SuperClusterTopology.boundaryLinks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperClusterTopologyTest {

    @Test
    void condensesEightChildrenIntoPrimitiveParentAggregates() {
        SectionPos origin = SectionPos.of(0, 0, 0);
        BaseClusterTopology[] children = openLayerChildren(origin);
        SuperClusterTopology topology = buildSuper(origin, children,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND);

        assertTrue(topology.aggregateCount() < 8);
        for (BaseClusterTopology child : children) {
            assertTrue(topology.aggregateId(child.section(), 0) >= 0);
        }
        boolean hasHorizontalExit = false;
        for (int aggregate = 0; aggregate < topology.aggregateCount(); aggregate++) {
            assertTrue(topology.aggregateAnchor(aggregate) != null);
            hasHorizontalExit |= topology.hasPotentialExit(
                    aggregate, SectionPos.of(2, 0, 0)
            );
            assertTrue(topology.outgoingStart(aggregate)
                    <= topology.outgoingEnd(aggregate));
        }
        assertTrue(hasHorizontalExit);
    }

    @Test
    void projectsPrimitiveBoundaryCrossingThroughParentWitness() {
        SectionPos sourceOrigin = SectionPos.of(0, 0, 0);
        SectionPos targetOrigin = SectionPos.of(2, 0, 0);
        BaseClusterTopology[] sourceChildren = boundaryChildren(sourceOrigin, Direction.EAST);
        BaseClusterTopology[] targetChildren = boundaryChildren(targetOrigin, Direction.WEST);
        SuperClusterTopology source = buildSuper(sourceOrigin, sourceChildren,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND);
        SuperClusterTopology target = buildSuper(targetOrigin, targetChildren,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND);

        SuperClusterTopology.CrossingIndex crossing = source.crossingIndex(
                Direction.EAST, target, sourceChildren, targetChildren
        );

        assertTrue(crossing.edgeEnd(0) > crossing.edgeStart(0));
        int edge = crossing.edgeStart(0);
        assertEquals(0, crossing.targetAggregate(edge));
        assertEquals(Direction.EAST, crossing.face(edge));
    }

    @Test
    void rejectsBoundaryThatCannotFitTheNormalizedFootprint() {
        SectionPos sourceOrigin = SectionPos.of(0, 0, 0);
        SectionPos targetOrigin = SectionPos.of(2, 0, 0);
        BaseClusterTopology.TraversalProfile wide = new BaseClusterTopology.TraversalProfile(
                1.4F, 1.95F, 1, 3, 3, false
        );
        BaseClusterTopology[] sourceChildren = boundaryChildren(sourceOrigin, Direction.EAST, wide);
        BaseClusterTopology[] targetChildren = boundaryChildren(targetOrigin, Direction.WEST, wide);
        SuperClusterTopology source = buildSuper(sourceOrigin, sourceChildren, wide);
        SuperClusterTopology target = buildSuper(targetOrigin, targetChildren, wide);

        SuperClusterTopology.CrossingIndex crossing = source.crossingIndex(
                Direction.EAST, target, sourceChildren, targetChildren
        );

        if (source.aggregateCount() == 0) {
            return;
        }
        assertEquals(0, crossing.edgeEnd(0));
    }

    @Test
    void flatMovementDoesNotCreateVerticalParentCrossings() {
        BaseClusterTopology.TraversalProfile flatOnly = new BaseClusterTopology.TraversalProfile(
                0.6F, 1.95F, 0, 0, 0, false
        );
        SectionPos lowerOrigin = SectionPos.of(0, 0, 0);
        SectionPos upperOrigin = SectionPos.of(0, 2, 0);
        BaseClusterTopology[] lowerChildren = verticalBoundaryChildren(lowerOrigin, Direction.UP, flatOnly);
        BaseClusterTopology[] upperChildren = verticalBoundaryChildren(upperOrigin, Direction.DOWN, flatOnly);
        SuperClusterTopology lower = buildSuper(lowerOrigin, lowerChildren, flatOnly);
        SuperClusterTopology upper = buildSuper(upperOrigin, upperChildren, flatOnly);

        SuperClusterTopology.CrossingIndex crossing = lower.crossingIndex(
                Direction.UP, upper, lowerChildren, upperChildren
        );

        if (lower.aggregateCount() == 0) {
            return;
        }
        assertEquals(0, crossing.edgeEnd(0));
        assertFalse(lower.hasPotentialExit(0, upperOrigin));
    }

    @Test
    void insetStepAcrossParentBoundaryIsVisibleToParentSearch() {
        SectionPos sourceOrigin = SectionPos.of(0, 0, 0);
        SectionPos targetOrigin = SectionPos.of(2, 0, 0);
        SectionPos sourceSection = SectionPos.of(1, 0, 0);
        SectionPos targetSection = SectionPos.of(2, 0, 0);
        BaseClusterTopology.TraversalProfile profile = new BaseClusterTopology.TraversalProfile(
                0.6F, 1.95F, 1, 3, 0, false);

        BaseClusterTopology[] sourceChildren = SuperClusterTopology.childSections(sourceOrigin)
                .stream()
                .map(section -> section.equals(sourceSection)
                        ? buildBase(section, insetStepSourceSnapshot(), profile)
                        : buildBase(section, emptySnapshot(), profile))
                .toArray(BaseClusterTopology[]::new);
        BaseClusterTopology[] targetChildren = SuperClusterTopology.childSections(targetOrigin)
                .stream()
                .map(section -> section.equals(targetSection)
                        ? buildBase(section, insetStepTargetSnapshot(), profile)
                        : buildBase(section, emptySnapshot(), profile))
                .toArray(BaseClusterTopology[]::new);
        SuperClusterTopology source = buildSuper(sourceOrigin, sourceChildren, profile);
        SuperClusterTopology target = buildSuper(targetOrigin, targetChildren, profile);

        BaseClusterTopology sourceBase = sourceChildren[
                (sourceSection.x() - sourceOrigin.x())
                        + (sourceSection.z() - sourceOrigin.z()) * 2
                        + (sourceSection.y() - sourceOrigin.y()) * 4];
        BaseClusterTopology targetBase = targetChildren[0];
        int sourceComponent = sourceBase.componentAt(14, 1, 4);
        int targetComponent = targetBase.componentAt(0, 2, 4);
        int sourceAggregate = source.aggregateId(sourceSection, sourceComponent);
        SuperClusterTopology.BoundaryLinks baseLinks = SuperClusterTopology.boundaryLinks(
                sourceBase, targetBase, Direction.EAST);
        SuperClusterTopology.CrossingIndex crossing = source.crossingIndex(
                Direction.EAST, target, sourceChildren, targetChildren
        );

        assertTrue(sourceComponent >= 0);
        assertTrue(targetComponent >= 0,
                "targetComponent=" + targetComponent + ", count=" + targetBase.componentCount());
        assertTrue(baseLinks.edgeEnd(sourceComponent) > baseLinks.edgeStart(sourceComponent));
        assertTrue(sourceAggregate >= 0);
        assertTrue(crossing.edgeEnd(sourceAggregate) > crossing.edgeStart(sourceAggregate));
        assertTrue(source.hasPotentialExit(sourceAggregate, targetOrigin));
    }

    @Test
    void groundCrossesYWithHorizontalMovementFromEverySupportedInset() {
        BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= 3; distance++) {
                for (int dy = -4; dy <= 1; dy++) {
                    if (dy == 0) continue;
                    for (int inset = 0; inset < Math.abs(dy); inset++) {
                        BlockPos start = new BlockPos(8, dy > 0 ? 15 : inset, 8);
                        BlockPos goal = start.offset(direction.getStepX() * distance, dy,
                                direction.getStepZ() * distance);
                        BaseClusterTopology source = pointTopology(SectionPos.of(start),
                                BaseClusterTopology.Channel.GROUND, scratch, start);
                        BaseClusterTopology target = pointTopology(SectionPos.of(goal),
                                BaseClusterTopology.Channel.GROUND, scratch, goal);
                        Direction face = dy > 0 ? Direction.UP : Direction.DOWN;
                        SuperClusterTopology.BoundaryLinks links = boundaryLinks(source, target, face);
                        BaseClusterTopology.MovementKey allowed = new BaseClusterTopology.MovementKey(
                                Math.max(0, dy), distance - 1, Math.max(0, -dy));
                        assertEquals(1, links.edgeEnd(0));
                        assertTrue(links.supports(0, allowed));
                        assertFalse(links.supports(0, new BaseClusterTopology.MovementKey(0, 0, 0)));
                        assertTrue(source.mayExit(0, target.section(), allowed));
                        assertEquals(1, links.bandEnd(0));
                        int descriptor = links.descriptor(0);
                        assertEquals(inset, SuperClusterTopology.bandInset(descriptor));
                        assertEquals(direction, SuperClusterTopology.bandDirection(descriptor));
                        assertEquals(distance, SuperClusterTopology.bandDistance(descriptor));
                        assertEquals(dy, SuperClusterTopology.bandShift(descriptor));
                        assertEquals(1L << (8 * 16 + 8), links.maskWord(0, (8 * 16 + 8) >>> 6));
                        if (dy < -1) assertEquals(0, boundaryLinks(target, source, Direction.UP).edgeEnd(0));
                    }
                }
            }
        }
    }

    @Test
    void overlappingFaceCoordinatesKeepDifferentSourceInsets() {
        BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
        BaseClusterTopology source = pointTopology(SectionPos.of(0, 0, 0),
                BaseClusterTopology.Channel.GROUND, scratch,
                new BlockPos(14, 5, 8), new BlockPos(15, 5, 8));
        BaseClusterTopology target = pointTopology(SectionPos.of(1, 0, 0),
                BaseClusterTopology.Channel.GROUND, scratch,
                new BlockPos(16, 5, 8), new BlockPos(17, 5, 8));
        SuperClusterTopology.BoundaryLinks links = boundaryLinks(source, target, Direction.EAST);
        Set<String> decoded = new HashSet<>();
        for (int band = links.bandStart(0); band < links.bandEnd(0); band++) {
            int descriptor = links.descriptor(band);
            int sourceX = 15 - SuperClusterTopology.bandInset(descriptor);
            int targetX = sourceX + SuperClusterTopology.bandDistance(descriptor);
            assertEquals(Direction.EAST, SuperClusterTopology.bandDirection(descriptor));
            assertEquals(0, SuperClusterTopology.bandShift(descriptor));
            assertEquals(1L << 88, links.maskWord(band, 1));
            assertTrue(decoded.add(sourceX + ":" + targetX));
        }
        assertEquals(Set.of("14:16", "14:17", "15:16", "15:17"), decoded);
    }

    @Test
    void directedReachabilitySurvivesChildAndParentYBoundaries() {
        BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
        BaseClusterTopology.GeometryKey geometry = new BaseClusterTopology.GeometryKey(
                BaseClusterTopology.Channel.GROUND, 1, 1, false);
        BaseClusterTopology.MovementKey movement = new BaseClusterTopology.MovementKey(1, 2, 4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy : new int[]{-4, -1, 1}) {
                for (int y : dy > 0 ? new int[]{8, 15, 31} : new int[]{8, 16, 32}) {
                    BlockPos start = new BlockPos(8, y, 8);
                    BlockPos goal = start.offset(direction.getStepX(), dy, direction.getStepZ());
                    SectionPos startOrigin = SuperClusterTopology.originOf(SectionPos.of(start));
                    SectionPos goalOrigin = SuperClusterTopology.originOf(SectionPos.of(goal));
                    BaseClusterTopology[] sourceChildren = pointChildren(startOrigin, scratch, start, goal);
                    SuperClusterTopology source = SuperClusterTopology.build(startOrigin, sourceChildren,
                            geometry, movement, scratch);
                    int sourceAggregate = source.aggregateId(SectionPos.of(start),
                            pointComponent(sourceChildren, start));
                    if (startOrigin.equals(goalOrigin)) {
                        int goalAggregate = source.aggregateId(SectionPos.of(goal),
                                pointComponent(sourceChildren, goal));
                        if (dy == -4) {
                            assertTrue(sourceAggregate != goalAggregate);
                            assertTrue(hasParentEdge(source, sourceAggregate, goalAggregate));
                            assertFalse(hasParentEdge(source, goalAggregate, sourceAggregate));
                        } else {
                            assertEquals(sourceAggregate, goalAggregate);
                        }
                    } else {
                        BaseClusterTopology[] targetChildren = pointChildren(goalOrigin, scratch, start, goal);
                        SuperClusterTopology target = SuperClusterTopology.build(goalOrigin, targetChildren,
                                geometry, movement, scratch);
                        int goalAggregate = target.aggregateId(SectionPos.of(goal),
                                pointComponent(targetChildren, goal));
                        Direction face = dy > 0 ? Direction.UP : Direction.DOWN;
                        SuperClusterTopology.CrossingIndex crossing = source.crossingIndex(
                                face, target, sourceChildren, targetChildren);
                        assertTrue(source.hasPotentialExit(sourceAggregate, goalOrigin));
                        assertEquals(crossing.edgeStart(sourceAggregate) + 1, crossing.edgeEnd(sourceAggregate));
                        assertEquals(goalAggregate, crossing.targetAggregate(crossing.edgeStart(sourceAggregate)));
                        assertEquals(face, crossing.face(crossing.edgeStart(sourceAggregate)));
                    }
                }
            }
        }
    }

    @Test
    void volumeCrossesAllSixFacesWithoutGroundMovementCapabilities() {
        BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
        for (Direction face : Direction.values()) {
            BlockPos start = new BlockPos(face.getStepX() > 0 ? 15 : face.getStepX() < 0 ? 0 : 8,
                    face.getStepY() > 0 ? 15 : face.getStepY() < 0 ? 0 : 8,
                    face.getStepZ() > 0 ? 15 : face.getStepZ() < 0 ? 0 : 8);
            BlockPos goal = start.relative(face);
            BaseClusterTopology source = pointTopology(SectionPos.of(start),
                    BaseClusterTopology.Channel.VOLUME, scratch, start, start.relative(face.getOpposite()));
            BaseClusterTopology target = pointTopology(SectionPos.of(goal),
                    BaseClusterTopology.Channel.VOLUME, scratch, goal, goal.relative(face));
            SuperClusterTopology.BoundaryLinks links = boundaryLinks(source, target, face);
            BaseClusterTopology.MovementKey movement = new BaseClusterTopology.MovementKey(0, 0, 0);
            assertEquals(1, links.edgeEnd(0));
            assertTrue(links.supports(0, movement));
            assertTrue(source.mayExit(0, target.section(), movement));
            assertEquals(1.0F, links.lowerBound(0));
        }
    }

    private static boolean hasParentEdge(SuperClusterTopology topology, int source, int target) {
        for (int edge = topology.outgoingStart(source); edge < topology.outgoingEnd(source); edge++) {
            if (topology.outgoingTarget(edge) == target) return true;
        }
        return false;
    }

    private static int pointComponent(BaseClusterTopology[] children, BlockPos point) {
        return Arrays.stream(children).filter(child -> child.section().equals(SectionPos.of(point)))
                .findFirst().orElseThrow().componentAt(point.getX() & 15, point.getY() & 15, point.getZ() & 15);
    }

    private static BaseClusterTopology[] pointChildren(SectionPos origin,
                                                       BaseClusterTopology.BuildScratch scratch,
                                                       BlockPos... points) {
        return SuperClusterTopology.childSections(origin).stream().map(section -> pointTopology(
                section, BaseClusterTopology.Channel.GROUND, scratch, points)).toArray(BaseClusterTopology[]::new);
    }

    private static BaseClusterTopology pointTopology(SectionPos section, BaseClusterTopology.Channel channel,
                                                     BaseClusterTopology.BuildScratch scratch,
                                                     BlockPos... points) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        if (channel == BaseClusterTopology.Channel.GROUND) Arrays.fill(cells, (byte) BaseClusterTopology.VOLUME_OPEN);
        for (BlockPos point : points) {
            if (SectionPos.of(point).equals(section)) cells[BaseClusterTopology.cellIndex(
                    point.getX() & 15, point.getY() & 15, point.getZ() & 15)] =
                    BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        }
        return BaseClusterTopology.build(section, 1L,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.fromCells(cells)),
                new BaseClusterTopology.GeometryKey(channel, 1, 1, false), scratch);
    }

    private static SuperClusterTopology buildSuper(SectionPos origin,
                                                    BaseClusterTopology[] children,
                                                    BaseClusterTopology.TraversalProfile profile) {
        return SuperClusterTopology.build(
                origin,
                children,
                profile.geometry(BaseClusterTopology.Channel.GROUND),
                profile.movement(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch()
        );
    }

    private static BaseClusterTopology[] openLayerChildren(SectionPos origin) {
        BaseClusterTopology.TraversalProfile profile =
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND;
        return SuperClusterTopology.childSections(origin).stream()
                .map(section -> buildBase(section, openVolumeSnapshot(), profile))
                .toArray(BaseClusterTopology[]::new);
    }

    private static BaseClusterTopology[] boundaryChildren(SectionPos origin, Direction face) {
        return boundaryChildren(origin, face,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND);
    }

    private static BaseClusterTopology[] boundaryChildren(SectionPos origin,
                                                            Direction face,
                                                            BaseClusterTopology.TraversalProfile profile) {
        SectionPos occupied = SectionPos.of(
                face == Direction.EAST ? origin.x() + 1 : origin.x(),
                origin.y(),
                origin.z()
        );
        return SuperClusterTopology.childSections(origin).stream()
                .map(section -> buildBase(section,
                        section.equals(occupied) ? boundaryCellSnapshot(face) : emptySnapshot(),
                        profile))
                .toArray(BaseClusterTopology[]::new);
    }

    private static BaseClusterTopology[] verticalBoundaryChildren(SectionPos origin,
                                                                    Direction face) {
        return verticalBoundaryChildren(origin, face,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND);
    }

    private static BaseClusterTopology[] verticalBoundaryChildren(SectionPos origin,
                                                                    Direction face,
                                                                    BaseClusterTopology.TraversalProfile profile) {
        SectionPos occupied = SectionPos.of(
                origin.x(),
                face == Direction.UP ? origin.y() + 1 : origin.y(),
                origin.z()
        );
        int y = face == Direction.UP ? 15 : 0;
        return SuperClusterTopology.childSections(origin).stream()
                .map(section -> buildBase(section,
                        section.equals(occupied) ? groundPlaneSnapshot(y) : emptySnapshot(),
                        profile))
                .toArray(BaseClusterTopology[]::new);
    }

    private static BaseClusterTopology buildBase(SectionPos section,
                                                   BaseClusterTopology.PackedFacts facts,
                                                  BaseClusterTopology.TraversalProfile profile) {
        return BaseClusterTopology.build(
                section,
                1L,
                BaseClusterTopology.BuildInput.center(facts),
                profile.geometry(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch()
        );
    }

    private static BaseClusterTopology.PackedFacts groundPlaneSnapshot(int y) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        for (int z = 0; z < BaseClusterTopology.SIDE; z++) {
            for (int x = 0; x < BaseClusterTopology.SIDE; x++) {
                cells[BaseClusterTopology.cellIndex(x, y, z)] = (byte) flags;
                if (y + 1 < BaseClusterTopology.SIDE) {
                    cells[BaseClusterTopology.cellIndex(x, y + 1, z)] =
                            BaseClusterTopology.VOLUME_OPEN;
                }
            }
        }
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    private static BaseClusterTopology.PackedFacts openVolumeSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        java.util.Arrays.fill(cells, (byte) (
                BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
        ));
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    private static BaseClusterTopology.PackedFacts boundaryCellSnapshot(Direction face) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int x = face == Direction.EAST ? 15 : 0;
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(x, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(x, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    private static BaseClusterTopology.PackedFacts insetStepSourceSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(13, 1, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 1, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(15, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        cells[BaseClusterTopology.cellIndex(15, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    private static BaseClusterTopology.PackedFacts insetStepTargetSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(0, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(0, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    private static BaseClusterTopology.PackedFacts emptySnapshot() {
        return BaseClusterTopology.PackedFacts.fromCells(new byte[BaseClusterTopology.CELL_COUNT]);
    }
}
