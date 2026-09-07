package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TopologyValidationAccessTest {
    @TempDir Path temporary;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void coldOwnerCleanupRemovesGraphsAndCorridorsButKeepsCanonicalFacts() throws Exception {
        try (Owner owner = new Owner()) {
            SectionPos section = SectionPos.of(0, 0, 0);
            var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
            var facts = BaseClusterTopology.PackedFacts.allAir();
            owner.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(key, 1, 1, 1,
                    TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of()));
            var request = owner.runtime.requestMacroQuery(Level.OVERWORLD, new BlockPos(2, 2, 2), new BlockPos(3, 2, 2),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            var result = request.future().get(5, TimeUnit.SECONDS);
            assertNotNull(result.corridor());
            assertFalse(result.completedFromCache());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            TopologyValidationAccess.Snapshot before;
            do {
                owner.runtime.endServerTick();
                Thread.sleep(2);
                before = TopologyValidationAccess.snapshot(owner, Level.OVERWORLD);
            } while ((!before.idle() || !before.handoffsReleased()) && System.nanoTime() < deadline);
            assertTrue(before.idle()); assertTrue(before.handoffsReleased());
            assertTrue(before.objects().keySet().stream().anyMatch(name -> name.startsWith("base/")));
            var capture = TopologyValidationAccess.beginCapture();
            TopologyValidationAccess.endCapture(capture);
            assertTrue(capture.used().isEmpty());
            try (var lease = TopologyValidationAccess.factsLease(owner)) {
                assertTrue(TopologyValidationAccess.prepareFacts(owner, Level.OVERWORLD, lease, Set.of(section)).isEmpty());
                assertEquals(1, lease.sections());
                assertEquals(facts.retainedBytes(), lease.bytes());
                assertEquals(1, owner.runtime.metrics().activeReferences());
                assertTrue(TopologyValidationAccess.clearDerived(owner, Level.OVERWORLD));
                var after = TopologyValidationAccess.snapshot(owner, Level.OVERWORLD);
                assertEquals(1, after.objects().size());
                assertTrue(after.objects().keySet().stream().allMatch(name -> name.startsWith("facts/")));
                assertEquals(0, owner.runtime.metrics().corridorCache().entries());
                assertEquals(facts.retainedBytes(), owner.runtime.metrics().facts().activeFactsBytes());
            }
            assertEquals(0, owner.runtime.metrics().activeReferences());
            assertEquals(64 + facts.retainedBytes(), owner.runtime.metrics().baseCache().retainedBytes());
            var again = owner.runtime.requestMacroQuery(Level.OVERWORLD, new BlockPos(2, 2, 2), new BlockPos(3, 2, 2),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            assertNotNull(again.future().get(5, TimeUnit.SECONDS).corridor());
        }
    }

    @Test void tokensUseReferenceIdentityEvenForEqualValuesAndCaptureDoesNotOverlap() {
        String first = new String("same"), second = new String("same");
        assertEquals(TopologyValidationAccess.token(first), TopologyValidationAccess.token(first));
        assertNotEquals(TopologyValidationAccess.token(first), TopologyValidationAccess.token(second));
        var capture = TopologyValidationAccess.beginCapture();
        try { assertThrows(IllegalStateException.class, TopologyValidationAccess::beginCapture); }
        finally { TopologyValidationAccess.endCapture(capture); }
    }

    @Test void cleanupRequiresTheTargetReceiptAndPinsToSettleButIgnoresAnotherChunk() throws Exception {
        var decisions = new LinkedBlockingQueue<TopologyWorkerRuntime.FactDecision>();
        try (Owner owner = new Owner(new TopologyStore(temporary), decisions)) {
            var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 0, 0));
            var facts = BaseClusterTopology.PackedFacts.allAir();
            owner.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(key, 1, 1, 1,
                    TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of(), true));
            var decision = decisions.poll(5, TimeUnit.SECONDS);
            assertNotNull(decision);
            var receipt = owner.store.accept(decision);
            assertTrue(owner.runtime.acceptFacts(decision));
            assertTrue(owner.runtime.factsFormed(decision, receipt.formed.get(5, TimeUnit.SECONDS).facts())
                    .get(5, TimeUnit.SECONDS));
            Map<String, Object> waiting = TopologyValidationAccess.chunkSettlement(owner, Level.OVERWORLD, Set.of(0L));
            assertEquals(false, waiting.get("settled"));
            assertEquals(1L, waiting.get("holds")); assertEquals(1L, waiting.get("receipts"));
            assertEquals(1L, waiting.get("factPins"));
            owner.store.continueWrite(receipt);
            assertTrue(receipt.completed.get(5, TimeUnit.SECONDS).accepted());
            owner.runtime.completeFacts(decision, true, true).get(5, TimeUnit.SECONDS);
            var unrelated = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(4, 0, 0));
            owner.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(unrelated, 2, 1, 1,
                    TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of(), true));
            assertNotNull(decisions.poll(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Map<String, Object> settled;
            do {
                settled = TopologyValidationAccess.chunkSettlement(owner, Level.OVERWORLD, Set.of(0L));
                if (Boolean.TRUE.equals(settled.get("settled"))) break;
                Thread.sleep(1);
            } while (System.nanoTime() < deadline);
            assertEquals(true, settled.get("settled"));
            assertEquals(false, TopologyValidationAccess.chunkSettlement(owner, Level.OVERWORLD,
                    Set.of(unrelated.section().chunk().toLong())).get("settled"));
        }
    }

    private static final class Owner implements AutoCloseable {
        final TopologyWorkerRuntime runtime;
        final TopologyStore store;
        Owner() { this(null, new LinkedBlockingQueue<>()); }
        Owner(TopologyStore store, LinkedBlockingQueue<TopologyWorkerRuntime.FactDecision> decisions) {
            this.store = store;
            runtime = new TopologyWorkerRuntime((key, load, count) -> {}, decisions::add);
        }
        @Override public void close() {
            runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS)); runtime.finishFactClosing();
            if (store != null) store.close();
        }
    }
}
