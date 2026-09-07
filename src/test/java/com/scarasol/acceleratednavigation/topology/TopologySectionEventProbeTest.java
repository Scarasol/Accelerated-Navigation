package com.scarasol.acceleratednavigation.topology;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologySectionEventProbeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void observationsKeepImmutableFinalCellsAndDistinguishLoadIdentities() {
        SectionPos section = SectionPos.of(1, 2, 3);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        try (var probe = TopologySectionEventProbe.watch(Level.OVERWORLD, section)) {
            Map<Integer, Byte> cells = new HashMap<>(Map.of(1, (byte) 3));
            var event = new TopologyWorkerRuntime.SectionEvent(key, 7, 1, 2,
                    TopologyWorkerRuntime.FactState.AVAILABLE, null, cells);
            TopologySectionEventProbe.event(event); cells.put(1, (byte) 0);
            assertEquals(Map.of(1, (byte) 3), probe.publications().get(0).changes());
            assertThrows(UnsupportedOperationException.class, () -> probe.publications().get(0).changes().clear());
            TopologySectionEventProbe.scan(new Loaded(key, new Chunk(7)));
            TopologySectionEventProbe.scan(new Loaded(key, new Chunk(8)));
            TopologySectionEventProbe.scan(new Loaded(new TopologyWorkerRuntime.ClusterKey(Level.NETHER, section), new Chunk(7)));
            assertEquals(Map.of(7L, 1, 8L, 1), probe.scans());
            assertThrows(IllegalStateException.class, () -> TopologySectionEventProbe.watch(Level.OVERWORLD, section));
        }
        try (var next = TopologySectionEventProbe.watch(Level.OVERWORLD, section)) {
            assertTrue(next.publications().isEmpty()); assertTrue(next.scans().isEmpty());
        }
    }
    @Test void delayedActualFailureStaysPendingUntilReleaseAndClosesWithoutLosingTheMessage() throws Exception {
        var section = SectionPos.of(0, 0, 0);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        var demanded = new java.util.concurrent.CountDownLatch(1);
        var runtime = new TopologyWorkerRuntime((ignored, load, count) -> { if (count > 0) demanded.countDown(); },
                decision -> fail("A facts failure cannot create a persistence input"));
        try {
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(key, 1, 0, 0,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            var query = runtime.requestMacroQuery(Level.OVERWORLD, new net.minecraft.core.BlockPos(1, 1, 1),
                    new net.minecraft.core.BlockPos(2, 1, 1), BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    com.scarasol.acceleratednavigation.scheduler.NavigationScheduler.Priority.ACTIVE);
            assertTrue(demanded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try (var probe = TopologySectionEventProbe.watch(Level.OVERWORLD, section)) {
                probe.delayNextFailure();
                var failure = new TopologyWorkerRuntime.SectionEvent(key, 1, 0, 0,
                        TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of());
                assertTrue(TopologySectionEventProbe.defer(runtime, failure));
                assertTrue(probe.deltaDelayed()); assertFalse(query.future().isDone());
                assertThrows(IllegalStateException.class, probe::delayNextDelta);
            }
            var result = query.future().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(MacroSearch.Failure.FACTS_RECOVERY_FAILED, result.progress().failure());
            assertEquals(section, result.progress().blockedSection());
        } finally {
            runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, java.util.concurrent.TimeUnit.SECONDS)); runtime.finishFactClosing();
        }
    }
    private record Loaded(TopologyWorkerRuntime.ClusterKey key, Chunk chunk) { }
    private record Chunk(long identity) { }
}
