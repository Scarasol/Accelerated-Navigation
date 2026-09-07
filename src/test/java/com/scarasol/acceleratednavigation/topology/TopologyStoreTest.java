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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            writeFull(store, lower, 1L, lowerFacts).join();
            writeFull(store, upper, 1L, upperFacts).join();
        }
        try (TopologyStore reopened = new TopologyStore(temporaryDirectory)) {
            assertEquals(TopologyStore.ReadStatus.FOUND,
                    reopened.read(Level.OVERWORLD, lower).join().status());
            assertEquals(TopologyStore.ReadStatus.FOUND,
                    reopened.read(Level.OVERWORLD, upper).join().status());
            assertFacts(lowerFacts, reopened.read(Level.OVERWORLD, lower).join().record().facts());
            assertFacts(upperFacts, reopened.read(Level.OVERWORLD, upper).join().record().facts());
        }
    }

    @Test
    void pendingOverlayKeepsLatestSectionAndSibling() throws Exception {
        SectionPos first = SectionPos.of(0, 0, 0);
        SectionPos sibling = SectionPos.of(0, 1, 0);
        BaseClusterTopology.PackedFacts replacement = facts(13);
        BaseClusterTopology.PackedFacts siblingFacts = facts(4);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            writeFull(store, first, 1L, facts(2));
            writeFull(store, sibling, 1L, siblingFacts);
            writeFull(store, first, 2L, replacement);
            assertFacts(replacement, store.read(Level.OVERWORLD, first).join().record().facts());
            assertFacts(siblingFacts, store.read(Level.OVERWORLD, sibling).join().record().facts());
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
            assertEquals(TopologyStore.ReadStatus.CORRUPT,
                    store.read(Level.OVERWORLD, section).join().status());
            writeFull(store, section, 1L, facts(1));
            assertEquals(TopologyStore.ReadStatus.FOUND,
                    store.read(Level.OVERWORLD, section).join().status());
        }
    }

    private static BaseClusterTopology.PackedFacts facts(int z) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(15, 2, z)] =
                (byte) (BaseClusterTopology.VOLUME_OPEN
                        | BaseClusterTopology.GROUND_OPEN
                        | BaseClusterTopology.FLUID
                        | BaseClusterTopology.EXACT_REQUIRED);
        return BaseClusterTopology.PackedFacts.fromCells(cells);
    }

    static CompletableFuture<TopologyStore.UpdateResult> writeFull(TopologyStore store,
            SectionPos section, long version, BaseClusterTopology.PackedFacts facts) {
        var receipt = store.accept(new TopologyWorkerRuntime.FactDecision(
                new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section),
                1, version, version, version, facts, Map.of()));
        store.continueWrite(receipt);
        return receipt.completed;
    }

    @Test
    void deltaFormationPrecedesWriteAndDoesNotBlockTheIoWorker() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0);
        var baseline = facts(2);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            writeFull(store, section, 10, baseline).get(5, TimeUnit.SECONDS);
            var receipt = store.accept(new TopologyWorkerRuntime.FactDecision(
                    new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section), 1, 1,
                    10, 12, null, Map.of(0, (byte) 7, 1, (byte) 3)));
            var formed = receipt.formed.get(5, TimeUnit.SECONDS);
            assertFacts(baseline.withChanges(Map.of(0, (byte) 7, 1, (byte) 3)), formed.facts());
            assertFalse(receipt.completed.isDone());
            assertEquals(10, store.read(Level.OVERWORLD, section).get(5, TimeUnit.SECONDS).record().version());
            store.continueWrite(receipt);
            assertEquals(TopologyStore.UpdateStatus.WRITTEN,
                    receipt.completed.get(5, TimeUnit.SECONDS).status());
            assertSame(formed.facts(), store.read(Level.OVERWORLD, section).join().record().facts());
        }
    }

    @Test
    void absentAndWrongVersionBaselinesFailBeforeFormation() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            var missing = store.accept(new TopologyWorkerRuntime.FactDecision(
                    key, 1, 1, 0, 1, null, Map.of(0, (byte) 0)));
            assertNull(missing.formed.get(5, TimeUnit.SECONDS).facts());
            assertEquals(TopologyStore.UpdateStatus.BASE_MISSING,
                    missing.completed.get(5, TimeUnit.SECONDS).status());
            writeFull(store, section, 3, facts(1)).get(5, TimeUnit.SECONDS);
            var wrong = store.accept(new TopologyWorkerRuntime.FactDecision(
                    key, 1, 2, 4, 5, null, Map.of(0, (byte) 0)));
            assertNull(wrong.formed.get(5, TimeUnit.SECONDS).facts());
            assertEquals(TopologyStore.UpdateStatus.VERSION_MISMATCH,
                    wrong.completed.get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void closingSettlesAcceptedFormationWithoutWaitingForServerContinuation() throws Exception {
        var store = new TopologyStore(temporaryDirectory);
        var receipt = store.accept(new TopologyWorkerRuntime.FactDecision(
                new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 0, 0)),
                1, 1, 1, 1, facts(1), Map.of()));
        assertSame(receipt.decision.facts(), receipt.formed.get(5, TimeUnit.SECONDS).facts());
        store.close();
        assertEquals(TopologyStore.UpdateStatus.CLOSED, receipt.completed.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void reloadedFullImageSettlesDelayedOldWriteWithoutOverwritingNewDiskFacts() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        var original = facts(1);
        var replacement = original.withChanges(Map.of(0, (byte) 3));
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            var old = store.accept(new TopologyWorkerRuntime.FactDecision(key, 1, 1, 1, 1, original, Map.of()));
            var sibling = store.accept(new TopologyWorkerRuntime.FactDecision(
                    new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 1, 0)),
                    1, 2, 1, 1, facts(2), Map.of()));
            var newer = store.accept(new TopologyWorkerRuntime.FactDecision(key, 2, 3, 2, 2, replacement, Map.of()));
            assertFalse(old.completed.isDone());
            store.continueWrite(newer);
            assertEquals(TopologyStore.UpdateStatus.COALESCED, old.completed.get(5, TimeUnit.SECONDS).status());
            assertFalse(sibling.completed.isDone(), "the new section must not settle a sibling's responsibility");
            assertEquals(TopologyStore.UpdateStatus.WRITTEN, newer.completed.get(5, TimeUnit.SECONDS).status());
            store.continueWrite(old);
            store.continueWrite(old);
            assertEquals(2, store.read(Level.OVERWORLD, section).get(5, TimeUnit.SECONDS).record().version());
            assertFacts(replacement, store.read(Level.OVERWORLD, section).join().record().facts());
        }
        try (TopologyStore reopened = new TopologyStore(temporaryDirectory)) {
            var stored = reopened.read(Level.OVERWORLD, section).get(5, TimeUnit.SECONDS).record();
            assertEquals(2, stored.version()); assertFacts(replacement, stored.facts());
        }
    }

    @Test
    void newLoadCannotOvertakeAWritePausedInItsRetirementCallback() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        var newestFacts = facts(3);
        try (TopologyStore store = new TopologyStore(temporaryDirectory)) {
            var oldest = store.accept(new TopologyWorkerRuntime.FactDecision(
                    key, 1, 1, 1, 1, facts(1), Map.of()));
            var older = store.accept(new TopologyWorkerRuntime.FactDecision(
                    key, 2, 2, 2, 2, facts(2), Map.of()));
            var newest = store.accept(new TopologyWorkerRuntime.FactDecision(
                    key, 3, 3, 3, 3, newestFacts, Map.of()));
            var callbackEntered = new CountDownLatch(1);
            var resumeCallback = new CountDownLatch(1);
            var monitorField = TopologyStore.class.getDeclaredField("monitor");
            monitorField.setAccessible(true);
            Object monitor = monitorField.get(store);
            var callback = oldest.completed.thenRun(() -> {
                assertFalse(Thread.holdsLock(monitor), "retirement must notify outside the store lock");
                callbackEntered.countDown();
                try {
                    assertTrue(resumeCallback.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var olderSubmission = CompletableFuture.runAsync(() -> store.continueWrite(older));
            CompletableFuture<Void> newestSubmission = null;
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                newestSubmission = CompletableFuture.runAsync(() -> store.continueWrite(newest));
                newestSubmission.get(5, TimeUnit.SECONDS);
            } finally {
                resumeCallback.countDown();
                olderSubmission.get(5, TimeUnit.SECONDS);
                if (newestSubmission != null) newestSubmission.get(5, TimeUnit.SECONDS);
            }
            callback.get(5, TimeUnit.SECONDS);
            assertTrue(older.completed.get(5, TimeUnit.SECONDS).accepted());
            assertTrue(newest.completed.get(5, TimeUnit.SECONDS).accepted());
        }
        try (TopologyStore reopened = new TopologyStore(temporaryDirectory)) {
            var stored = reopened.read(Level.OVERWORLD, section).get(5, TimeUnit.SECONDS).record();
            assertEquals(3, stored.version());
            assertFacts(newestFacts, stored.facts());
        }
    }

    private static void assertFacts(BaseClusterTopology.PackedFacts expected,
                                    BaseClusterTopology.PackedFacts actual) {
        assertEquals(expected.fingerprint(), actual.fingerprint());
        assertArrayEquals(expected.bytes(), actual.bytes());
    }
}
