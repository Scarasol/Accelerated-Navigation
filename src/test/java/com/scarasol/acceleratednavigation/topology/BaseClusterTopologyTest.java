package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseClusterTopologyTest {

    @Test
    void buildsSectionComponentsWithExactBoundaryMasksAndMemberAnchors() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        for (int x = 0; x < 16; x++) {
            set(cells, x, 3, 6, BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);
        }

        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(2, -1, 4),
                7L,
                new BaseClusterTopology.Snapshot(cells)
        );

        assertEquals(1, topology.components(BaseClusterTopology.Channel.GROUND).size());
        BaseClusterTopology.Component ground = topology.components(BaseClusterTopology.Channel.GROUND).get(0);
        assertTrue(ground.touches(Direction.WEST));
        assertTrue(ground.touches(Direction.EAST));
        assertEquals(16, ground.cellCount());
        assertNotNull(topology.componentAt(
                BaseClusterTopology.Channel.GROUND,
                ground.anchorX(),
                ground.anchorY(),
                ground.anchorZ()
        ));
    }

    @Test
    void storesStepAndDropAsProfileFilteredConnections() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        set(cells, 4, 3, 4, BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);
        set(cells, 5, 4, 4, BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);

        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(0, 0, 0),
                1L,
                new BaseClusterTopology.Snapshot(cells)
        );
        BaseClusterTopology.Component lower = topology.componentAt(
                BaseClusterTopology.Channel.GROUND, 4, 3, 4
        );
        BaseClusterTopology.Component upper = topology.componentAt(
                BaseClusterTopology.Channel.GROUND, 5, 4, 4
        );

        assertTrue(topology.outgoingConnections(lower.id()).stream()
                .anyMatch(connection -> connection.toComponent() == upper.id()
                        && connection.rise() == 1));
        assertTrue(topology.outgoingConnections(upper.id()).stream()
                .anyMatch(connection -> connection.toComponent() == lower.id()
                        && connection.drop() == 1));
    }

    @Test
    void storesFluidOnlyOnFacesTouchedByFluidCells() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        set(cells, 0, 7, 9, BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.FLUID);
        set(cells, 6, 15, 2, BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.FLUID);

        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(0, 0, 0),
                1L,
                new BaseClusterTopology.Snapshot(cells)
        );

        assertTrue(topology.hasFluid(Direction.WEST, 9, 7));
        assertTrue(topology.hasFluid(Direction.UP, 6, 2));
        assertFalse(topology.hasFluid(Direction.EAST, 9, 7));
        assertEquals((1 << Direction.WEST.ordinal()) | (1 << Direction.UP.ordinal()),
                topology.nonEmptyFluidFaceMask());
    }

    @Test
    void snapshotAndBoundaryMasksAreImmutable() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        set(cells, 15, 4, 6, BaseClusterTopology.VOLUME_OPEN);
        BaseClusterTopology.Snapshot snapshot = new BaseClusterTopology.Snapshot(cells);
        cells[BaseClusterTopology.cellIndex(15, 4, 6)] = 0;

        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(0, 0, 0),
                3L,
                snapshot
        );
        BaseClusterTopology.Component component = topology.components(
                BaseClusterTopology.Channel.VOLUME
        ).get(0);
        long original = component.boundaryMaskWord(Direction.EAST, 1);
        long[] exported = component.boundaryMask(Direction.EAST);
        exported[1] = 0L;

        assertEquals(original, component.boundaryMaskWord(Direction.EAST, 1));
        assertTrue(topology.retainedBytes() > 0);
        assertEquals(snapshot.fingerprint(), topology.sourceFingerprint());
    }

    private static void set(byte[] cells, int x, int y, int z, int flags) {
        cells[BaseClusterTopology.cellIndex(x, y, z)] = (byte) flags;
    }
}
