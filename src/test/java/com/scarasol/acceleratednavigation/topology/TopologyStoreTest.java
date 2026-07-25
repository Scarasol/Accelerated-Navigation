package com.scarasol.acceleratednavigation.topology;

import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyStoreTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsMultipleSectionsInOneChunkAcrossReopen() throws Exception {
        BaseClusterTopology lower = topology(SectionPos.of(3, -2, 7), 4L, 1);
        BaseClusterTopology upper = topology(SectionPos.of(3, 5, 7), 9L, 11);

        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            store.write(Level.OVERWORLD, lower);
            store.write(Level.OVERWORLD, upper);
        }

        try (TopologyStore reopened = new TopologyStore(temporaryDirectory)) {
            assertEquivalent(lower, reopened.read(Level.OVERWORLD, lower.section()).orElseThrow());
            assertEquivalent(upper, reopened.read(Level.OVERWORLD, upper.section()).orElseThrow());
        }
    }

    @Test
    void replacingOneSectionPreservesItsChunkSiblings() throws Exception {
        SectionPos firstSection = SectionPos.of(0, 0, 0);
        SectionPos siblingSection = SectionPos.of(0, 1, 0);
        BaseClusterTopology first = topology(firstSection, 1L, 2);
        BaseClusterTopology sibling = topology(siblingSection, 1L, 4);
        BaseClusterTopology replacement = topology(firstSection, 2L, 13);

        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            store.write(Level.OVERWORLD, first);
            store.write(Level.OVERWORLD, sibling);
            store.write(Level.OVERWORLD, replacement);

            assertEquivalent(replacement, store.read(Level.OVERWORLD, firstSection).orElseThrow());
            assertEquivalent(sibling, store.read(Level.OVERWORLD, siblingSection).orElseThrow());
        }
    }

    @Test
    void corruptOrUnknownRecordIsRejectedWithoutEscapingDecoder() throws Exception {
        Path dimensionDirectory = temporaryDirectory
                .resolve("minecraft")
                .resolve("overworld");
        Files.createDirectories(dimensionDirectory);
        Path regionPath = dimensionDirectory.resolve("r.0.0.mca");
        try (RegionFile region = new RegionFile(regionPath, dimensionDirectory, false);
             DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(0, 0))) {
            output.writeInt(0x12345678);
            output.writeInt(Integer.MAX_VALUE);
        }

        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            assertFalse(store.read(Level.OVERWORLD, SectionPos.of(0, 0, 0)).isPresent());
            store.write(Level.OVERWORLD, topology(SectionPos.of(0, 0, 0), 1L, 1));
            assertTrue(store.read(Level.OVERWORLD, SectionPos.of(0, 0, 0)).isPresent());
        }
    }

    private static BaseClusterTopology topology(SectionPos section, long revision, int z) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(15, 2, z)] =
                (byte) (BaseClusterTopology.VOLUME_OPEN
                        | BaseClusterTopology.GROUND_OPEN
                        | BaseClusterTopology.FLUID
                        | BaseClusterTopology.EXACT_REQUIRED);
        return BaseClusterTopology.build(section, revision, new BaseClusterTopology.Snapshot(cells));
    }

    private static void assertEquivalent(BaseClusterTopology expected,
                                         BaseClusterTopology actual) {
        assertEquals(expected.section(), actual.section());
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.sourceFingerprint(), actual.sourceFingerprint());
        assertEquals(expected.nonEmptyFluidFaceMask(), actual.nonEmptyFluidFaceMask());
        assertEquals(expected.components().size(), actual.components().size());
        assertEquals(expected.localConnections().size(), actual.localConnections().size());
        assertEquals(expected.retainedBytes(), actual.retainedBytes());
    }
}
