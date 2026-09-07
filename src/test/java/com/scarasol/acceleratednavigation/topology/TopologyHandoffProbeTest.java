package com.scarasol.acceleratednavigation.topology;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TopologyHandoffProbeTest {
    @TempDir Path temporary;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void selectedOperationIsDeferredWithoutBlockingAndReleasedExactlyOnce() {
        var decision = decision(7, 1, 2);
        AtomicInteger calls = new AtomicInteger();
        try (var probe = TopologyHandoffProbe.install(Level.OVERWORLD, decision.key().section(), 7, 2, "B1")) {
            assertThrows(IllegalStateException.class, probe::release);
            assertFalse(TopologyHandoffProbe.pause("B1", decision(8, 2, 2), calls::incrementAndGet, null));
            assertFalse(probe.reached());
            assertTrue(TopologyHandoffProbe.pause("B1", decision, calls::incrementAndGet, decision.facts()));
            assertTrue(probe.reached()); assertEquals(0, calls.get());
            assertTrue(TopologyHandoffProbe.pause("B1", decision, () -> fail("duplicate continuation"), decision.facts()));
            probe.release(); probe.release();
            assertEquals(1, calls.get());
            assertFalse(TopologyHandoffProbe.pause("B1", decision, calls::incrementAndGet, decision.facts()));
            assertEquals(2, probe.events().size());
            assertEquals(true, probe.events().get(1).get("released"));
        }
    }

    @Test void queueBarrierKeepsTheActualMessageInItsOriginalQueueUntilExplicitRelease() {
        var decision = decision(7, 1, 2);
        Mailbox owner = new Mailbox(); AtomicInteger delivered = new AtomicInteger();
        Runnable message = delivered::incrementAndGet;
        try (var probe = TopologyHandoffProbe.install(Level.OVERWORLD, decision.key().section(), 7, 2, "B2")) {
            assertSame(message, TopologyHandoffProbe.queued(owner, message, decision));
            assertFalse(TopologyHandoffProbe.beforeDrain(owner));
            owner.persistenceCallbacks.add(message);
            assertTrue(TopologyHandoffProbe.beforeDrain(owner));
            assertSame(message, owner.persistenceCallbacks.peek()); assertEquals(0, delivered.get());
            probe.release();
            assertEquals(1, delivered.get()); assertTrue(owner.persistenceCallbacks.isEmpty());
        }
    }

    @Test void formationBarrierRetainsARealStoreReceiptAndDeliversTheOriginalFacts() throws Exception {
        var decision = decision(7, 1, 2);
        try (var store = new TopologyStore(temporary);
             var probe = TopologyHandoffProbe.install(Level.OVERWORLD, decision.key().section(), 7, 2, "B5")) {
            var receipt = store.accept(decision);
            AtomicInteger callbacks = new AtomicInteger();
            var callback = TopologyHandoffProbe.formation(decision, (value, failure) -> {
                assertNull(failure);
                assertSame(decision.facts(), ((TopologyStore.Formation) value).facts());
                callbacks.incrementAndGet(); store.continueWrite(receipt);
            });
            receipt.formed.whenComplete(callback::accept);
            assertTrue(probe.reached()); assertEquals(0, callbacks.get());
            assertFalse(receipt.completed.isDone());
            probe.release();
            assertTrue(receipt.completed.get(5, TimeUnit.SECONDS).accepted());
            assertEquals(1, callbacks.get());
            assertSame(decision.facts(), store.read(Level.OVERWORLD, decision.key().section()).get(5, TimeUnit.SECONDS).record().facts());
        }
    }

    @Test void abandoningAControlReleasesTheCapturedNativeContinuation() {
        var decision = decision(7, 1, 2); AtomicInteger released = new AtomicInteger();
        var probe = TopologyHandoffProbe.install(Level.OVERWORLD, decision.key().section(), 7, 2, "B6");
        assertTrue(TopologyHandoffProbe.pause("B6", decision, released::incrementAndGet, decision.facts()));
        probe.close(); probe.close();
        assertEquals(1, released.get());
        try (var next = TopologyHandoffProbe.install(Level.OVERWORLD, decision.key().section(), 8, 3, null)) {
            assertFalse(next.reached());
        }
    }

    private static TopologyWorkerRuntime.FactDecision decision(long load, long generation, long version) {
        return new TopologyWorkerRuntime.FactDecision(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 0, 0)),
                load, generation, version - 1, version, BaseClusterTopology.PackedFacts.allAir(), Map.of());
    }

    private static final class Mailbox {
        final ConcurrentLinkedQueue<Runnable> persistenceCallbacks = new ConcurrentLinkedQueue<>();
        @SuppressWarnings("unused") private void drainPersistence() {
            Runnable action;
            while ((action = persistenceCallbacks.poll()) != null) action.run();
        }
    }
}
