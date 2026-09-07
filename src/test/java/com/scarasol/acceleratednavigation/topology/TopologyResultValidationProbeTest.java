package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologyResultValidationProbeTest {
    private static final BlockPos START = new BlockPos(1, 1, 1), GOAL = new BlockPos(5, 1, 1);
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void realWorkerResultIsHeldOnceAndReleasedWithItsOriginalIdentity() throws Exception {
        var runtime = runtime();
        try {
            publish(runtime, 0, TopologyWorkerRuntime.FactState.AVAILABLE);
            var result = request(runtime).future().get(5, TimeUnit.SECONDS);
            assertNotNull(result.corridor());
            var recipient = new Recipient(Level.OVERWORLD);
            try (var probe = TopologyResultValidationProbe.watch(Level.OVERWORLD, START, GOAL, 1)) {
                new Recipient(Level.NETHER).completeWorker(result, null);
                assertFalse(probe.reached());
                recipient.completeWorker(result, null);
                assertTrue(recipient.received.isEmpty()); assertTrue(probe.reached());
                assertTrue(probe.holdsSection(SectionPos.of(START))); assertEquals(1, probe.count());
                probe.release(null); probe.release(null);
                assertEquals(List.of(result), recipient.received);
                assertSame(result, recipient.received.get(0));
                probe.replay(0); assertEquals(2, recipient.received.size());
            }
            RuntimeException failure = new IllegalStateException("controlled callback failure");
            try (var probe = TopologyResultValidationProbe.watch(Level.OVERWORLD, START, GOAL, 1)) {
                recipient.completeWorker(result, null); probe.release(failure); assertSame(failure, recipient.failure);
            }
            try (var probe = TopologyResultValidationProbe.watch(Level.OVERWORLD, START, GOAL, 1)) {
                recipient.completeWorker(result, null); assertTrue(probe.reached());
            }
            assertEquals(4, recipient.received.size());
        } finally { stop(runtime); }
    }

    @Test void finalValidationRetriesOnceAndPreservesEndpointUnloadAfterRejection() throws Exception {
        for (boolean unload : List.of(false, true)) {
            var runtime = runtime();
            try {
                publish(runtime, 0, TopologyWorkerRuntime.FactState.AVAILABLE);
                var request = request(runtime);
                var first = request.future().get(5, TimeUnit.SECONDS);
                assertNotNull(first.corridor());
                publish(runtime, 1, unload ? TopologyWorkerRuntime.FactState.UNLOADED : TopologyWorkerRuntime.FactState.AVAILABLE);
                var second = request.rejectFinalStaleResult(first.attempt()).get(5, TimeUnit.SECONDS);
                assertEquals(1, second.staleRetries()); assertEquals(first.attempt() + 1, second.attempt());
                if (unload) {
                    assertNull(second.corridor()); assertEquals(MacroSearch.Failure.UNAVAILABLE_CHUNK, second.progress().failure());
                    assertEquals(SectionPos.of(START), second.progress().blockedSection());
                } else {
                    assertNotNull(second.corridor());
                    publish(runtime, 2, TopologyWorkerRuntime.FactState.AVAILABLE);
                    var exhausted = request.rejectFinalStaleResult(second.attempt()).get(5, TimeUnit.SECONDS);
                    assertNull(exhausted.corridor()); assertEquals(MacroSearch.Failure.STALE_WORLD, exhausted.progress().failure());
                    assertEquals(second.attempt(), exhausted.attempt()); assertEquals(1, exhausted.staleRetries());
                    publish(runtime, 2, TopologyWorkerRuntime.FactState.RECOVERY_FAILED);
                    assertEquals(MacroSearch.Failure.STALE_WORLD, exhausted.progress().failure());
                }
            } finally { stop(runtime); }
        }
    }

    private static TopologyWorkerRuntime runtime() {
        return new TopologyWorkerRuntime((key, load, count) -> { }, decision -> fail("Non-persisting test facts cannot submit a write"));
    }
    private static void publish(TopologyWorkerRuntime runtime, long version, TopologyWorkerRuntime.FactState state) {
        byte[] cells = new byte[4096]; java.util.Arrays.fill(cells, (byte) 1);
        for (int x = 1; x <= 5; x++) cells[x | 1 << 4 | 1 << 8] = 3;
        cells[10 | 5 << 4 | 5 << 8] = (byte) (version % 2);
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(START)),
                1, version, version, state, state == TopologyWorkerRuntime.FactState.AVAILABLE ? BaseClusterTopology.PackedFacts.fromCells(cells) : null, Map.of(), false));
    }
    private static TopologyWorkerRuntime.MacroRequest request(TopologyWorkerRuntime runtime) {
        return runtime.requestMacroQuery(Level.OVERWORLD, START, GOAL, BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
    }
    private static void stop(TopologyWorkerRuntime runtime) throws Exception {
        runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS)); runtime.finishFactClosing();
    }
    private record Key(ResourceKey<Level> dimension) { }
    private static final class Recipient {
        private final Key key;
        private final BlockPos start = START, goal = GOAL;
        private final List<TopologyWorkerRuntime.WorkerResult> received = new ArrayList<>();
        private Throwable failure;
        private Recipient(ResourceKey<Level> dimension) { key = new Key(dimension); }
        private void completeWorker(TopologyWorkerRuntime.WorkerResult result, Throwable failure) {
            if (TopologyResultValidationProbe.pause(this, result, failure)) return;
            received.add(result); this.failure = failure;
        }
    }
}
