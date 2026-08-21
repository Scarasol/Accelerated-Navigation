package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroSearchTest {

    @Test
    void returnsAWeightedCorridor() {
        TestGraph graph = new TestGraph(0, 2);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 1, 2, 1.0F);

        MacroSearch search = new MacroSearch(graph, 1.25F, 100);
        runToCompletion(search);

        assertEquals(Status.SUCCEEDED, search.status());
        assertEquals(List.of(10L, 11L), search.result().connections().stream()
                .map(MacroSearch.Connection::id).toList());
        assertEquals(2.0F, search.result().cost());
    }

    @Test
    void resumesAcrossSmallExpansionSlices() {
        TestGraph graph = new TestGraph(0, 20);
        for (int node = 0; node < 20; node++) {
            graph.edge(100 + node, node, node + 1, 1.0F);
        }

        MacroSearch search = new MacroSearch(graph, 1.0F, 100);
        int slices = 0;
        while (search.status() == Status.RUNNING && slices < 100) {
            search.step(1);
            slices++;
        }

        assertEquals(Status.SUCCEEDED, search.status());
        assertEquals(20, search.result().connections().size());
        assertTrue(slices > 1);
    }

    @Test
    void waitsForAndResumesAfterAWorkerDependency() {
        SectionPos missing = SectionPos.of(1, 0, 0);
        MacroSearch.DependencyKey dependency = new MacroSearch.DependencyKey(
                MacroSearch.DependencyKind.BASE_CLUSTER, missing);
        TestGraph graph = new TestGraph(0, 2);
        graph.edge(10, 0, 1, 1.0F);
        graph.edge(11, 1, 2, 1.0F);
        graph.dependency(1, dependency);

        MacroSearch search = new MacroSearch(graph, 1.0F, 100);
        search.step(32);

        assertTrue(search.waitingForTopology());
        assertEquals(List.of(dependency), search.pendingDependencies(4));

        graph.clearDependencies(1);
        search.dependenciesAvailable(List.of(dependency));
        runToCompletion(search);
        assertEquals(Status.SUCCEEDED, search.status());
    }

    @Test
    void reportsUnavailableDependencyAfterReadyFrontierIsExhausted() {
        SectionPos unavailable = SectionPos.of(1, 0, 0);
        MacroSearch.DependencyKey dependency = new MacroSearch.DependencyKey(
                MacroSearch.DependencyKind.BASE_CLUSTER, unavailable);
        TestGraph graph = new TestGraph(0, 1);
        graph.dependency(0, dependency);

        MacroSearch search = new MacroSearch(graph, 1.0F, 100);
        search.step(32);
        search.dependencyUnavailable(dependency, MacroSearch.Failure.UNAVAILABLE_CHUNK);
        runToCompletion(search);

        assertEquals(Status.FAILED, search.status());
        assertEquals(MacroSearch.Failure.UNAVAILABLE_CHUNK, search.failure());
        assertEquals(unavailable, search.blockedSection());
    }

    @Test
    void rejectsAStaleCapturedWorldAndHonoursVisitLimit() {
        TestGraph staleGraph = new TestGraph(0, 1);
        staleGraph.revisionsValid = false;
        MacroSearch stale = new MacroSearch(staleGraph, 1.0F, 100);
        stale.step(1);
        assertEquals(Status.FAILED, stale.status());
        assertEquals(MacroSearch.Failure.STALE_WORLD, stale.failure());
        assertNull(stale.result());

        TestGraph limitedGraph = new TestGraph(0, 4);
        for (int node = 0; node < 4; node++) {
            limitedGraph.edge(200 + node, node, node + 1, 1.0F);
        }
        MacroSearch limited = new MacroSearch(limitedGraph, 1.0F, 2);
        runToCompletion(limited);
        assertEquals(Status.FAILED, limited.status());
        assertEquals(MacroSearch.Failure.SEARCH_LIMIT_REACHED, limited.failure());
    }

    private static void runToCompletion(MacroSearch search) {
        for (int iteration = 0;
             iteration < 1_000 && search.status() == Status.RUNNING;
             iteration++) {
            search.step(64);
        }
        assertTrue(search.status() != Status.RUNNING,
                "search did not finish within the test bound");
    }

    private static final class TestGraph implements MacroSearch.Graph {
        private final Map<Long, MacroSearch.Endpoint> nodes = new HashMap<>();
        private final Map<Long, List<MacroSearch.Connection>> edges = new HashMap<>();
        private final Map<Long, List<MacroSearch.DependencyKey>> dependencies = new HashMap<>();
        private final long startId;
        private final long goalId;
        private boolean revisionsValid = true;

        private TestGraph(long startId, long goalId) {
            this.startId = startId;
            this.goalId = goalId;
            node(startId);
            node(goalId);
        }

        private void edge(long edgeId, long from, long to, float cost) {
            edges.computeIfAbsent(from, ignored -> new ArrayList<>()).add(
                    new MacroSearch.Connection(edgeId, node(from), node(to), cost,
                            new MacroSearch.MembershipTransition()));
        }

        private void dependency(long from, MacroSearch.DependencyKey key) {
            dependencies.computeIfAbsent(from, ignored -> new ArrayList<>()).add(key);
        }

        private void clearDependencies(long from) {
            dependencies.remove(from);
        }

        private MacroSearch.Endpoint node(long id) {
            return nodes.computeIfAbsent(id, key -> new MacroSearch.ExactEndpoint(
                    key, new BlockPos(key.intValue(), 0, 0), 1L));
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
        public void expandInto(MacroSearch.Endpoint from,
                               MacroSearch.ExpansionBuffer output) {
            List<MacroSearch.DependencyKey> blocked = dependencies.get(from.id());
            if (blocked != null) {
                for (MacroSearch.DependencyKey dependency : blocked) {
                    output.addDependency(0, dependency);
                }
                return;
            }
            for (MacroSearch.Connection connection : edges.getOrDefault(from.id(), List.of())) {
                output.add(connection);
            }
        }

        @Override
        public boolean revisionsValid() {
            return revisionsValid;
        }
    }
}
