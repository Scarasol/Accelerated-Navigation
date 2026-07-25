package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperClusterTopologyTest {

    @Test
    void contractsEightMutuallyReachableChildrenWithoutAllPairsEdges() {
        SectionPos origin = SectionPos.of(0, 0, 0);
        SuperClusterTopology topology = SuperClusterTopology.build(
                origin,
                openLayerChildren(origin),
                BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
        );

        assertEquals(1, topology.aggregates().size());
        SuperClusterTopology.Aggregate aggregate = topology.aggregate(0);
        assertEquals(8, aggregate.baseComponentCount());
        for (Direction face : List.of(
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        )) {
            assertTrue(aggregate.touches(face));
        }
        assertFalse(aggregate.touches(Direction.DOWN));
        assertFalse(aggregate.touches(Direction.UP));
        assertTrue(topology.outgoing(0).isEmpty());
    }

    @Test
    void preservesOneWayDropBetweenAggregateComponents() {
        SectionPos origin = SectionPos.of(0, 0, 0);
        SectionPos directedSection = origin;
        List<BaseClusterTopology> children = new ArrayList<>();
        for (SectionPos section : SuperClusterTopology.childSections(origin)) {
            children.add(BaseClusterTopology.build(
                    section,
                    1L,
                    section.equals(directedSection) ? oneWayDropSnapshot() : emptySnapshot()
            ));
        }
        SuperClusterTopology topology = SuperClusterTopology.build(
                origin,
                children,
                BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
        );
        BaseClusterTopology base = children.get(0);
        int lowerComponent = base.componentAt(BaseClusterTopology.Channel.GROUND, 2, 2, 1).id();
        int upperComponent = base.componentAt(BaseClusterTopology.Channel.GROUND, 1, 5, 1).id();
        int lowerAggregate = topology.aggregateId(directedSection, lowerComponent);
        int upperAggregate = topology.aggregateId(directedSection, upperComponent);

        assertFalse(lowerAggregate == upperAggregate);
        assertTrue(topology.outgoing(upperAggregate).stream()
                .anyMatch(edge -> edge.targetAggregate() == lowerAggregate));
        assertFalse(topology.outgoing(lowerAggregate).stream()
                .anyMatch(edge -> edge.targetAggregate() == upperAggregate));
    }

    @Test
    void derivesAdjacentCrossingsFromExactBoundaryCells() {
        SectionPos sourceOrigin = SectionPos.of(0, 0, 0);
        SectionPos targetOrigin = SectionPos.of(2, 0, 0);
        SuperClusterTopology source = SuperClusterTopology.build(
                sourceOrigin,
                boundaryChildren(sourceOrigin, Direction.EAST),
                BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
        );
        SuperClusterTopology target = SuperClusterTopology.build(
                targetOrigin,
                boundaryChildren(targetOrigin, Direction.WEST),
                BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
        );

        assertEquals(1, source.crossings(0, Direction.EAST, target).size());
        assertEquals(0, source.crossings(0, Direction.EAST, target).get(0).targetAggregate());
    }

    @Test
    void rejectsBoundaryRunNarrowerThanTraversalProfile() {
        SectionPos sourceOrigin = SectionPos.of(0, 0, 0);
        SectionPos targetOrigin = SectionPos.of(2, 0, 0);
        BaseClusterTopology.TraversalProfile wide = new BaseClusterTopology.TraversalProfile(
                1.4F,
                1.95F,
                1,
                3,
                3,
                false
        );
        SuperClusterTopology source = SuperClusterTopology.build(
                sourceOrigin,
                boundaryChildren(sourceOrigin, Direction.EAST),
                BaseClusterTopology.Channel.GROUND,
                wide
        );
        SuperClusterTopology target = SuperClusterTopology.build(
                targetOrigin,
                boundaryChildren(targetOrigin, Direction.WEST),
                BaseClusterTopology.Channel.GROUND,
                wide
        );

        assertTrue(source.crossings(0, Direction.EAST, target).isEmpty());
    }

    private static List<BaseClusterTopology> openLayerChildren(SectionPos origin) {
        return SuperClusterTopology.childSections(origin).stream()
                .map(section -> BaseClusterTopology.build(
                        section,
                        1L,
                        groundPlaneSnapshot(section.y() == origin.y() ? 15 : 0)
                ))
                .toList();
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

    private static List<BaseClusterTopology> boundaryChildren(SectionPos origin, Direction face) {
        SectionPos occupied = SectionPos.of(
                face == Direction.EAST ? origin.x() + 1 : origin.x(),
                origin.y(),
                origin.z()
        );
        return SuperClusterTopology.childSections(origin).stream()
                .map(section -> BaseClusterTopology.build(
                        section,
                        1L,
                        section.equals(occupied) ? boundaryCellSnapshot(face) : emptySnapshot()
                ))
                .toList();
    }

    private static BaseClusterTopology.Snapshot boundaryCellSnapshot(Direction face) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int x = face == Direction.EAST ? 15 : 0;
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(x, 2, 4)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(x, 3, 4)] = BaseClusterTopology.VOLUME_OPEN;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot oneWayDropSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(1, 5, 1)] = (byte) flags;
        cells[BaseClusterTopology.cellIndex(2, 2, 1)] = (byte) flags;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot emptySnapshot() {
        return new BaseClusterTopology.Snapshot(new byte[BaseClusterTopology.CELL_COUNT]);
    }
}
