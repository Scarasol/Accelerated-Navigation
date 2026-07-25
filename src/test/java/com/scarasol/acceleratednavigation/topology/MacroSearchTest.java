package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroSearchTest {

    @Test
    void returnsStructuralCorridorWithoutBackendCertification() {
        TestGraph graph = new TestGraph(0, 2);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 1, 2, 1.0F);

        MacroSearch search = new MacroSearch(graph, 1.25F);
        runToCompletion(search);

        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertEquals(List.of(10L, 11L), search.result().connections().stream()
                .map(MacroSearch.Connection::id)
                .toList());
        assertEquals(2.0F, search.result().cost());
        assertEquals(3, search.metrics().expandedNodes());
    }

    @Test
    void preservesOpenSetAcrossSmallExpansionSlices() {
        TestGraph graph = new TestGraph(0, 20);
        for (int node = 0; node < 20; node++) {
            graph.edge(100 + node, node, node + 1, 1.0F);
        }
        MacroSearch search = new MacroSearch(graph, 1.0F);

        int slices = 0;
        while (search.status() == MacroSearch.Status.RUNNING) {
            search.step(1, Long.MAX_VALUE);
            slices++;
        }

        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertEquals(20, search.result().connections().size());
        assertEquals(21, slices);
    }

    @Test
    void parksAtAnIncompleteBestFrontierAndResumesTheSameSearch() {
        SectionPos missing = SectionPos.of(1, 0, 0);
        TestGraph graph = new TestGraph(0, 3);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 0, 2, 4.0F);
        graph.edge(12, 2, 3, 1.0F);
        graph.pending(1, missing);
        MacroSearch search = new MacroSearch(graph, 1.25F);

        search.step(64, Long.MAX_VALUE);

        assertEquals(MacroSearch.Status.RUNNING, search.status());
        assertTrue(search.waitingForTopology());
        assertEquals(List.of(missing), search.pendingSections(4));
        assertNull(search.result());

        graph.clearDependencies(1);
        graph.edge(13, 1, 3, 1.0F);
        search.topologyAvailable(missing);
        runToCompletion(search);

        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertEquals(List.of(10L, 13L), search.result().connections().stream()
                .map(MacroSearch.Connection::id)
                .toList());
        assertEquals(2.0F, search.result().cost());
        assertTrue(search.metrics().reexpandedBlockedNodes() > 0L);
    }

    @Test
    void discoversACompetitiveDependencyBatchBeforeParking() {
        SectionPos first = SectionPos.of(1, 0, 0);
        SectionPos second = SectionPos.of(0, 0, 1);
        TestGraph graph = new TestGraph(0, 4);
        graph.prefetchSlack = 1.0F;
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 0, 2, 2.0F);
        graph.edge(12, 0, 3, 8.0F);
        graph.pending(1, first);
        graph.pending(2, second);
        graph.edge(13, 3, 4, 1.0F);
        MacroSearch search = new MacroSearch(graph, 1.0F);

        search.step(64, Long.MAX_VALUE);

        assertTrue(search.waitingForTopology());
        assertEquals(Set.of(first, second), Set.copyOf(search.pendingSections(4)));
        assertEquals(3, search.metrics().expandedNodes());
    }

    @Test
    void unavailableBoundaryDoesNotHideAReadyAlternative() {
        SectionPos unavailable = SectionPos.of(1, 0, 0);
        TestGraph graph = new TestGraph(0, 3);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 0, 2, 2.0F);
        graph.edge(12, 2, 3, 1.0F);
        graph.unavailable(1, unavailable);
        MacroSearch search = new MacroSearch(graph, 1.0F);

        runToCompletion(search);

        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertFalse(search.waitingForTopology());
        assertEquals(List.of(11L, 12L), search.result().connections().stream()
                .map(MacroSearch.Connection::id)
                .toList());
    }

    @Test
    void reportsUnavailableOnlyAfterReadyGraphExhaustion() {
        SectionPos unavailable = SectionPos.of(1, 0, 0);
        TestGraph graph = new TestGraph(0, 2);
        graph.edge(10, 0, 1, 1.0F);
        graph.unavailable(1, unavailable);
        MacroSearch search = new MacroSearch(graph, 1.0F);

        runToCompletion(search);

        assertEquals(MacroSearch.Status.FAILED, search.status());
        assertEquals(MacroSearch.Failure.UNAVAILABLE_CHUNK, search.failure());
        assertEquals(unavailable, search.blockedSection());
    }

    @Test
    void doesNotRegenerateKnownEdgesWhenAnIncompleteNodeReopens() {
        SectionPos missing = SectionPos.of(1, 0, 0);
        TestGraph graph = new TestGraph(0, 3);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 1, 2, 5.0F);
        graph.pending(1, missing);
        MacroSearch search = new MacroSearch(graph, 1.0F);

        search.step(64, Long.MAX_VALUE);
        graph.clearDependencies(1);
        graph.edge(12, 1, 3, 1.0F);
        search.topologyAvailable(missing);
        runToCompletion(search);

        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertEquals(3L, search.metrics().generatedConnections());
        assertEquals(List.of(10L, 12L), search.result().connections().stream()
                .map(MacroSearch.Connection::id)
                .toList());
    }

    @Test
    void failsWhenCapturedWorldRevisionChanges() {
        TestGraph graph = new TestGraph(0, 1);
        graph.edge(10, 0, 1, 1.0F);
        MacroSearch search = new MacroSearch(graph, 1.25F);
        graph.revisionsValid = false;

        search.step(64, Long.MAX_VALUE);

        assertEquals(MacroSearch.Status.FAILED, search.status());
        assertEquals(MacroSearch.Failure.STALE_WORLD, search.failure());
        assertNull(search.result());
    }

    @Test
    void stopsAfterTheConfiguredTotalExpansionLimit() {
        TestGraph graph = new TestGraph(0, 6);
        for (int node = 0; node < 6; node++) {
            graph.edge(100 + node, node, node + 1, 1.0F);
        }
        MacroSearch search = new MacroSearch(graph, 1.0F, 3);

        runToCompletion(search);

        assertEquals(MacroSearch.Status.FAILED, search.status());
        assertEquals(MacroSearch.Failure.SEARCH_LIMIT_REACHED, search.failure());
        assertEquals(3L, search.metrics().expandedNodes());
        assertNull(search.result());
    }

    private static void runToCompletion(MacroSearch search) {
        for (int iteration = 0;
             iteration < 1_000 && search.status() == MacroSearch.Status.RUNNING;
             iteration++) {
            search.step(64, Long.MAX_VALUE);
        }
        assertNotNull(search.status());
    }

    private static final class TestGraph implements MacroSearch.Graph {
        private final Map<Long, MacroSearch.Endpoint> nodes = new HashMap<>();
        private final Map<Long, List<MacroSearch.Connection>> edges = new HashMap<>();
        private final Map<Long, List<MacroSearch.Dependency>> dependencies = new HashMap<>();
        private final long startId;
        private final long goalId;
        private boolean revisionsValid = true;
        private float prefetchSlack;

        private TestGraph(long startId, long goalId) {
            this.startId = startId;
            this.goalId = goalId;
            node(startId);
            node(goalId);
        }

        private void edge(long edgeId, long from, long to, float cost) {
            edges.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new MacroSearch.Connection(
                    edgeId,
                    node(from),
                    node(to),
                    cost,
                    new MacroSearch.MembershipTransition()
            ));
        }

        private void pending(long from, SectionPos section) {
            dependency(from, section, MacroSearch.Availability.PENDING);
        }

        private void unavailable(long from, SectionPos section) {
            dependency(from, section, MacroSearch.Availability.UNAVAILABLE);
        }

        private void dependency(long from,
                                SectionPos section,
                                MacroSearch.Availability availability) {
            dependencies.computeIfAbsent(from, ignored -> new ArrayList<>())
                    .add(new MacroSearch.Dependency(section, availability));
        }

        private void clearDependencies(long from) {
            dependencies.remove(from);
        }

        private MacroSearch.Endpoint node(long id) {
            return nodes.computeIfAbsent(id, key -> new MacroSearch.ExactEndpoint(
                    key,
                    new BlockPos(key.intValue(), 0, 0),
                    1L
            ));
        }

        @Override
        public MacroSearch.Endpoint start() {
            return node(startId);
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return node(goalId);
        }

        @Override
        public MacroSearch.Expansion expand(MacroSearch.Endpoint from) {
            return new MacroSearch.Expansion(
                    edges.getOrDefault(from.id(), List.of()),
                    dependencies.getOrDefault(from.id(), List.of())
            );
        }

        @Override
        public boolean revisionsValid() {
            return revisionsValid;
        }

        @Override
        public float prefetchSlack() {
            return prefetchSlack;
        }
    }
}
