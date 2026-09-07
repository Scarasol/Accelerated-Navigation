package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologyEndpointFailureScenarioTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void requiredHaloFailureWaitsForPendingHaloAndKeepsItsOwnSection() throws Exception {
        var pendingObserved = new java.util.concurrent.CountDownLatch(1);
        SectionPos lower = SectionPos.of(0, -1, 0), upper = SectionPos.of(0, 1, 0);
        var runtime = new TopologyWorkerRuntime((key, load, count) -> {
            if (key.section().equals(lower) && count > 0) pendingObserved.countDown();
        }, decision -> fail("non-persisting query facts cannot create a write"));
        try {
            byte[] cells = new byte[4096]; java.util.Arrays.fill(cells, (byte) 3);
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 0, 0)),
                    1, 0, 0, TopologyWorkerRuntime.FactState.AVAILABLE, BaseClusterTopology.PackedFacts.fromCells(cells), Map.of(), false));
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, lower),
                    1, 0, 0, TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, upper),
                    1, 0, 0, TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, null, Map.of()));
            var request = runtime.requestMacroQuery(Level.OVERWORLD, new BlockPos(4, 1, 4), new BlockPos(6, 1, 4),
                    BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            assertTrue(pendingObserved.await(5, TimeUnit.SECONDS));
            assertFalse(request.future().isDone(), "a pending required halo is not a terminal failure");
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, lower),
                    1, 0, 0, TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of()));
            var result = request.future().get(5, TimeUnit.SECONDS);
            assertNull(result.corridor());
            TopologyEndpointFailureScenario.verify(result.progress(), MacroSearch.Failure.FACTS_RECOVERY_FAILED, lower);
        } finally { runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS)); runtime.finishFactClosing(); }
    }

    @Test void allDirectFailurePairsKeepStartPriorityAcrossBothPublicationOrders() throws Exception {
        var states = List.of(TopologyWorkerRuntime.FactState.RECOVERY_FAILED,
                TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, TopologyWorkerRuntime.FactState.UNLOADED);
        var reasons = List.of(MacroSearch.Failure.FACTS_RECOVERY_FAILED,
                MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE, MacroSearch.Failure.UNAVAILABLE_CHUNK);
        BlockPos start = new BlockPos(1, 1, 1), goal = new BlockPos(33, 1, 1);
        for (int from = 0; from < 3; from++) for (int to = 0; to < 3; to++) for (boolean reverse : List.of(false, true)) {
            var runtime = new TopologyWorkerRuntime((key, load, count) -> { }, decision -> fail("No persistence decision is expected for unavailable facts"));
            try {
                var events = List.of(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(start)),
                                1, 0, 0, states.get(from), null, Map.of()),
                        new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(goal)),
                                1, 0, 0, states.get(to), null, Map.of()));
                runtime.publishSection(events.get(reverse ? 1 : 0)); runtime.publishSection(events.get(reverse ? 0 : 1));
                var request = runtime.requestMacroQuery(Level.OVERWORLD, start, goal, BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
                var result = request.future().get(5, TimeUnit.SECONDS);
                assertNull(result.corridor());
                var expected = reasons.get(from);
                TopologyEndpointFailureScenario.verify(result.progress(), expected, SectionPos.of(start));
                assertThrows(AssertionError.class, () -> TopologyEndpointFailureScenario.verify(result.progress(), expected, SectionPos.of(goal)));
            } finally {
                runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS)); runtime.finishFactClosing();
            }
        }
    }

    @Test void validFallbackCandidateContinuesDespiteUnavailableAlternates() throws Exception {
        var states = List.of(TopologyWorkerRuntime.FactState.RECOVERY_FAILED,
                TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, TopologyWorkerRuntime.FactState.UNLOADED);
        var reasons = List.of(MacroSearch.Failure.FACTS_RECOVERY_FAILED,
                MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE, MacroSearch.Failure.UNAVAILABLE_CHUNK);
        for (int reason = 0; reason < 3; reason++) for (boolean valid : List.of(false, true)) {
            var runtime = new TopologyWorkerRuntime((key, load, count) -> { }, decision -> fail("Non-persisting facts must not create write decisions"));
            try {
                byte[] cells = new byte[4096]; java.util.Arrays.fill(cells, (byte) 1);
                for (int x = 8; x <= (valid ? 14 : 8); x++) cells[x | 1 << 4 | 1 << 8] = 3;
                runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(0, 0, 0)),
                        1, 0, 0, TopologyWorkerRuntime.FactState.AVAILABLE, BaseClusterTopology.PackedFacts.fromCells(cells), Map.of(), false));
                runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(1, 0, 0)),
                        1, 0, 0, states.get(reason), null, Map.of()));
                var request = runtime.requestMacroQuery(Level.OVERWORLD, new BlockPos(15, 1, 1), new BlockPos(8, 1, 1),
                        BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
                var result = request.future().get(5, TimeUnit.SECONDS);
                if (valid) {
                    assertNotNull(result.corridor()); TopologyEndpointFailureScenario.verify(result.progress(), MacroSearch.Failure.NONE, null);
                } else {
                    assertNull(result.corridor()); TopologyEndpointFailureScenario.verify(result.progress(), reasons.get(reason), SectionPos.of(1, 0, 0));
                }
            } finally {
                runtime.beginStopping(); assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS)); runtime.finishFactClosing();
            }
        }
    }
}
