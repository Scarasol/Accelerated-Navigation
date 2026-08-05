package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

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
                                                  BaseClusterTopology.Snapshot snapshot,
                                                  BaseClusterTopology.TraversalProfile profile) {
        return BaseClusterTopology.build(
                section,
                1L,
                BaseClusterTopology.BuildInput.center(snapshot.packedFacts()),
                profile.geometry(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch()
        );
    }

    private static BaseClusterTopology.Snapshot groundPlaneSnapshot(int y) {
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
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot openVolumeSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        java.util.Arrays.fill(cells, (byte) (
                BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
        ));
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot boundaryCellSnapshot(Direction face) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int x = face == Direction.EAST ? 15 : 0;
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(x, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(x, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot insetStepSourceSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(13, 1, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 1, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(15, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(14, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        cells[BaseClusterTopology.cellIndex(15, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot insetStepTargetSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(0, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(0, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot emptySnapshot() {
        return new BaseClusterTopology.Snapshot(new byte[BaseClusterTopology.CELL_COUNT]);
    }
}
