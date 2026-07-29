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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void roundTripsChunkCoalescedFactsAcrossReopen() throws Exception {
        SectionPos lower = SectionPos.of(3, -2, 7);
        SectionPos upper = SectionPos.of(3, 5, 7);
        BaseClusterTopology.PackedFacts lowerFacts = facts(1);
        BaseClusterTopology.PackedFacts upperFacts = facts(11);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            store.markDirty(Level.OVERWORLD, lower, lowerFacts);
            store.markDirty(Level.OVERWORLD, upper, upperFacts);
            store.save(Level.OVERWORLD).join();
            assertEquals(1L, store.metrics().physicalWrites());
        }
        try (TopologyStore reopened = new TopologyStore(temporaryDirectory)) {
            assertFacts(lowerFacts, reopened.read(Level.OVERWORLD, lower).join().orElseThrow());
            assertFacts(upperFacts, reopened.read(Level.OVERWORLD, upper).join().orElseThrow());
            assertEquals(1L, reopened.metrics().physicalReads());
        }
    }

    @Test
    void pendingOverlayKeepsLatestSectionAndSibling() throws Exception {
        SectionPos first = SectionPos.of(0, 0, 0);
        SectionPos sibling = SectionPos.of(0, 1, 0);
        BaseClusterTopology.PackedFacts replacement = facts(13);
        BaseClusterTopology.PackedFacts siblingFacts = facts(4);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            store.markDirty(Level.OVERWORLD, first, facts(2));
            store.markDirty(Level.OVERWORLD, sibling, siblingFacts);
            store.markDirty(Level.OVERWORLD, first, replacement);
            assertFacts(replacement, store.read(Level.OVERWORLD, first).join().orElseThrow());
            assertFacts(siblingFacts, store.read(Level.OVERWORLD, sibling).join().orElseThrow());
            assertEquals(0L, store.metrics().physicalWrites());
            store.unload(Level.OVERWORLD, new ChunkPos(0, 0));
        }
    }

    @Test
    void corruptRecordReadsAsEmptyAndCanBeReplaced() throws Exception {
        Path dimensionDirectory = temporaryDirectory.resolve("minecraft").resolve("overworld");
        Files.createDirectories(dimensionDirectory);
        try (RegionFile region = new RegionFile(
                dimensionDirectory.resolve("r.0.0.mca"),
                dimensionDirectory,
                false
        ); DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(0, 0))) {
            output.writeInt(0x12345678);
            output.writeInt(Integer.MAX_VALUE);
        }
        SectionPos section = SectionPos.of(0, 0, 0);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            assertFalse(store.read(Level.OVERWORLD, section).join().isPresent());
            store.markDirty(Level.OVERWORLD, section, facts(1));
            assertTrue(store.read(Level.OVERWORLD, section).join().isPresent());
        }
    }

    private static BaseClusterTopology.PackedFacts facts(int z) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(15, 2, z)] =
                (byte) (BaseClusterTopology.VOLUME_OPEN
                        | BaseClusterTopology.GROUND_OPEN
                        | BaseClusterTopology.FLUID
                        | BaseClusterTopology.EXACT_REQUIRED);
        return new BaseClusterTopology.Snapshot(cells).packedFacts();
    }

    private static void assertFacts(BaseClusterTopology.PackedFacts expected,
                                    BaseClusterTopology.PackedFacts actual) {
        assertEquals(expected.fingerprint(), actual.fingerprint());
        assertArrayEquals(expected.bytes(), actual.bytes());
    }
}
