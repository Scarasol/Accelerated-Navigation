package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseClusterTopologyTest {

    @Test
    void buildsOneGroundComponentFromPackedFacts() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int ground = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        for (int z = 0; z < BaseClusterTopology.SIDE; z++) {
            for (int x = 0; x < BaseClusterTopology.SIDE; x++) {
                cells[BaseClusterTopology.cellIndex(x, 3, z)] = (byte) ground;
                cells[BaseClusterTopology.cellIndex(x, 4, z)] = BaseClusterTopology.VOLUME_OPEN;
            }
        }

        BaseClusterTopology topology = build(
                SectionPos.of(2, -1, 4),
                new BaseClusterTopology.Snapshot(cells),
                BaseClusterTopology.Channel.GROUND,
                false
        );

        assertEquals(1, topology.componentCount());
        assertTrue(topology.componentAt(0, 3, 0) >= 0,
                () -> "componentCount=" + topology.componentCount()
                        + ", anchor=" + topology.componentAnchorCell(0));
        assertEquals(BaseClusterTopology.cellIndex(0, 3, 0),
                topology.componentAnchorCell(0));
        assertTrue(topology.retainedBytes() > 0);
    }

    @Test
    void sealedOneByOneAnchorsAreRemovedByTheLinearFastPath() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(8, 8, 8)] = (byte) (
                BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
        );

        BaseClusterTopology topology = build(
                SectionPos.of(0, 0, 0),
                new BaseClusterTopology.Snapshot(cells),
                BaseClusterTopology.Channel.GROUND,
                false
        );

        assertEquals(0, topology.componentCount(),
                () -> "componentAt=" + topology.componentAt(8, 8, 8));
        assertEquals(-1, topology.componentAt(8, 8, 8));
    }

    @Test
    void sealedBoundaryAnchorUsesLoadedFaceHalo() {
        byte[] center = new byte[BaseClusterTopology.CELL_COUNT];
        center[BaseClusterTopology.cellIndex(0, 8, 8)] = (byte) (
                BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
        );
        BaseClusterTopology.Snapshot centerSnapshot = new BaseClusterTopology.Snapshot(center);
        BaseClusterTopology.GeometryKey geometry = new BaseClusterTopology.GeometryKey(
                BaseClusterTopology.Channel.GROUND, 1, 1, false
        );
        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(0, 0, 0),
                1L,
                new BaseClusterTopology.BuildInput(
                        centerSnapshot.packedFacts(),
                        new byte[]{(byte) BaseClusterTopology.haloIndex(-1, 0, 0)},
                        new BaseClusterTopology.PackedFacts[]{
                                new BaseClusterTopology.Snapshot(
                                        new byte[BaseClusterTopology.CELL_COUNT]
                                ).packedFacts()
                        },
                        new long[]{1L},
                        new long[]{2L}
                ),
                geometry,
                new BaseClusterTopology.BuildScratch()
        );

        assertEquals(-1, topology.componentAt(0, 8, 8));
    }

    @Test
    void sealedBoundaryAnchorRequiresCompleteFaceHalo() {
        byte[] center = new byte[BaseClusterTopology.CELL_COUNT];
        center[BaseClusterTopology.cellIndex(0, 8, 8)] = (byte) (
                BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN
        );
        byte[] west = new byte[BaseClusterTopology.CELL_COUNT];
        west[BaseClusterTopology.cellIndex(15, 8, 8)] = BaseClusterTopology.VOLUME_OPEN;
        BaseClusterTopology.GeometryKey geometry = new BaseClusterTopology.GeometryKey(
                BaseClusterTopology.Channel.GROUND, 1, 1, false
        );
        BaseClusterTopology topology = BaseClusterTopology.build(
                SectionPos.of(0, 0, 0),
                1L,
                new BaseClusterTopology.BuildInput(
                        new BaseClusterTopology.Snapshot(center).packedFacts(),
                        new byte[]{(byte) BaseClusterTopology.haloIndex(-1, 0, 0)},
                        new BaseClusterTopology.PackedFacts[]{
                                new BaseClusterTopology.Snapshot(west).packedFacts()
                        },
                        new long[]{1L},
                        new long[]{2L}
                ),
                geometry,
                new BaseClusterTopology.BuildScratch()
        );

        assertTrue(topology.componentAt(0, 8, 8) >= 0);
    }

    @Test
    void fluidFactsAreFilteredUnlessTheGeometryAcceptsFluid() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(4, 3, 4)] = (byte) (
                BaseClusterTopology.VOLUME_OPEN
                        | BaseClusterTopology.GROUND_OPEN
                        | BaseClusterTopology.FLUID
        );
        cells[BaseClusterTopology.cellIndex(4, 4, 4)] = BaseClusterTopology.VOLUME_OPEN;
        BaseClusterTopology.Snapshot snapshot = new BaseClusterTopology.Snapshot(cells);

        assertEquals(0, build(SectionPos.of(0, 0, 0), snapshot,
                BaseClusterTopology.Channel.GROUND, false).componentCount());
        assertEquals(1, build(SectionPos.of(0, 0, 0), snapshot,
                BaseClusterTopology.Channel.GROUND, true).componentCount());
    }

    @Test
    void packedSnapshotIsCopiedAndHasStableFingerprint() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        java.util.Arrays.fill(cells, (byte) BaseClusterTopology.VOLUME_OPEN);
        BaseClusterTopology.Snapshot snapshot = new BaseClusterTopology.Snapshot(cells);
        long fingerprint = snapshot.fingerprint();
        cells[0] = 0;

        BaseClusterTopology topology = build(
                SectionPos.of(0, 0, 0),
                snapshot,
                BaseClusterTopology.Channel.VOLUME,
                false
        );

        assertEquals(fingerprint, snapshot.fingerprint());
        assertEquals(fingerprint, topology.sourceFingerprint());
        assertTrue(topology.componentAt(0, 0, 0) >= 0);
    }

    @Test
    void movementKeyRejectsUnsupportedStructuralValues() {
        assertThrowsIllegalArgument(() -> new BaseClusterTopology.MovementKey(2, 0, 0));
        assertThrowsIllegalArgument(() -> new BaseClusterTopology.MovementKey(0, 3, 0));
        assertThrowsIllegalArgument(() -> new BaseClusterTopology.MovementKey(0, 0, 5));
        assertFalse(new BaseClusterTopology.MovementKey(0, 0, 0).capabilityMask() == 0L);
    }

    private static BaseClusterTopology build(SectionPos section,
                                               BaseClusterTopology.Snapshot snapshot,
                                               BaseClusterTopology.Channel channel,
                                               boolean acceptsFluid) {
        BaseClusterTopology.GeometryKey geometry = new BaseClusterTopology.GeometryKey(
                channel, 1, 1, acceptsFluid
        );
        return BaseClusterTopology.build(
                section,
                1L,
                BaseClusterTopology.BuildInput.center(snapshot.packedFacts()),
                geometry,
                new BaseClusterTopology.BuildScratch()
        );
    }

    private static void assertThrowsIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
