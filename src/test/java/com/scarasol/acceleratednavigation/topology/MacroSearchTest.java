package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroSearchTest {

    @Test
    void laterFailureDoesNotCloseAReopenedNodeBeforeItsNextExpansion() {
        var a = new MacroSearch.DependencyKey(MacroSearch.DependencyKind.BASE_CLUSTER, SectionPos.of(1, 0, 0));
        var b = new MacroSearch.DependencyKey(MacroSearch.DependencyKind.BASE_CLUSTER, SectionPos.of(2, 0, 0));
        class PartialGraph implements MacroSearch.Graph {
            final MacroSearch.Endpoint root = new MacroSearch.ExactEndpoint(0, BlockPos.ZERO, 1);
            final MacroSearch.Endpoint other = new MacroSearch.ExactEndpoint(1, new BlockPos(1, 0, 0), 1);
            final MacroSearch.Endpoint goal = new MacroSearch.ExactEndpoint(2, new BlockPos(2, 0, 0), 1);
            boolean ready;
            public MacroSearch.Endpoint start() { return root; }
            public MacroSearch.Endpoint goal() { return goal; }
            public boolean revisionsValid() { return true; }
            public float heuristic(MacroSearch.Endpoint node) { return node == root ? 10 : 0; }
            public void expandInto(MacroSearch.Endpoint node, MacroSearch.ExpansionBuffer out) {
                if (node == root && out.needsPart(0)) {
                    out.add(new MacroSearch.Connection(10, root, other, 1, new MacroSearch.MembershipTransition()));
                    out.completePart(0);
                }
                if (out.needsPart(1)) {
                    if (!ready) out.addDependency(1, a);
                    else {
                        if (node == root) out.add(new MacroSearch.Connection(20, root, goal, 2,
                                new MacroSearch.MembershipTransition()));
                        out.completePart(1);
                    }
                }
                if (node == root && out.needsPart(2)) out.addDependency(2, b);
            }
        }
        PartialGraph graph = new PartialGraph();
        MacroSearch search = new MacroSearch(graph, 1, 100);
        search.step(64);
        assertTrue(search.waitingForTopology());
        graph.ready = true;
        search.dependenciesAvailable(List.of(a));
        search.step(1);
        search.dependencyUnavailable(b, new MacroSearch.Unavailability(
                MacroSearch.Failure.UNAVAILABLE_CHUNK, b.position()));
        runToCompletion(search);
        assertEquals(Status.SUCCEEDED, search.status());
        assertEquals(List.of(20L), search.result().connections().stream().map(MacroSearch.Connection::id).toList());
    }

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
        search.dependencyUnavailable(dependency, new MacroSearch.Unavailability(
                MacroSearch.Failure.UNAVAILABLE_CHUNK, dependency.position()));
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

    @Test
    void mixedFailuresSelectReasonAndActualSectionIndependentOfNotificationOrder() {
        List<List<Integer>> orders = List.of(List.of(0, 1, 2), List.of(0, 2, 1),
                List.of(1, 0, 2), List.of(1, 2, 0), List.of(2, 0, 1), List.of(2, 1, 0));
        List<MacroSearch.Unavailability> causes = List.of(
                new MacroSearch.Unavailability(MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE,
                        SectionPos.of(-9, 0, 0)),
                new MacroSearch.Unavailability(MacroSearch.Failure.FACTS_RECOVERY_FAILED,
                        SectionPos.of(8, 0, 0)),
                new MacroSearch.Unavailability(MacroSearch.Failure.FACTS_RECOVERY_FAILED,
                        SectionPos.of(7, -1, 0)));
        for (List<Integer> order : orders) {
            TestGraph graph = new TestGraph(0, 1);
            List<MacroSearch.DependencyKey> dependencies = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                var dependency = new MacroSearch.DependencyKey(MacroSearch.DependencyKind.BASE_CLUSTER,
                        SectionPos.of(index, 1, 0));
                dependencies.add(dependency);
                graph.dependency(0, dependency);
            }
            MacroSearch search = new MacroSearch(graph, 1, 100);
            search.step(32);
            for (int index : order) {
                search.dependencyUnavailable(dependencies.get(index), causes.get(index));
                search.dependencyUnavailable(dependencies.get(index), causes.get(index));
            }
            runToCompletion(search);
            assertEquals(MacroSearch.Failure.FACTS_RECOVERY_FAILED, search.failure());
            assertEquals(SectionPos.of(7, -1, 0), search.blockedSection());
        }
    }

    @Test
    void materializesOnlySupportedBandsWithoutLosingTheirSourceInsets() {
        BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
        BaseClusterTopology source = boundaryPair(SectionPos.of(0, 0, 0), 14, scratch);
        BaseClusterTopology target = boundaryPair(SectionPos.of(1, 0, 0), 0, scratch);
        SuperClusterTopology.BoundaryLinks links = SuperClusterTopology.boundaryLinks(source, target, Direction.EAST);
        long movementMask = new BaseClusterTopology.MovementKey(0, 1, 0).capabilityMask();
        MacroSearch.Endpoint start = new MacroSearch.ExactEndpoint(0, new BlockPos(14, 5, 8), 1);
        MacroSearch.Endpoint goal = new MacroSearch.ExactEndpoint(1, new BlockPos(16, 5, 8), 1);
        MacroSearch search = new MacroSearch(new MacroSearch.Graph() {
            @Override public MacroSearch.Endpoint start() { return start; }
            @Override public MacroSearch.Endpoint goal() { return goal; }
            @Override public boolean revisionsValid() { return true; }
            @Override public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
                if (from.id() == start.id()) output.addBoundary(1, goal, links.lowerBound(0), links, 0, movementMask);
            }
        }, 1.0F, 100);
        runToCompletion(search);
        assertEquals(Status.SUCCEEDED, search.status());
        MacroSearch.BoundaryTransition transition = (MacroSearch.BoundaryTransition)
                search.result().connections().get(0).transition();
        assertEquals(Direction.EAST, transition.face());
        assertEquals(3, transition.bandCount());
        Set<String> decoded = new HashSet<>();
        for (int band = 0; band < transition.bandCount(); band++) {
            int sourceX = 15 - transition.sourceInset(band);
            assertEquals(Direction.EAST, transition.horizontalDirection(band));
            assertEquals(0, transition.verticalShift(band));
            assertEquals(1L << 88, transition.maskWord(band, 1));
            assertTrue(decoded.add(sourceX + ":" + (sourceX + transition.horizontalDistance(band))));
        }
        assertEquals(Set.of("14:16", "15:16", "15:17"), decoded);
        assertEquals(40 + 3 * Short.BYTES + 12 * Long.BYTES, transition.retainedBytes());
    }

    private static BaseClusterTopology boundaryPair(SectionPos section, int x,
                                                     BaseClusterTopology.BuildScratch scratch) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        java.util.Arrays.fill(cells, (byte) BaseClusterTopology.VOLUME_OPEN);
        cells[BaseClusterTopology.cellIndex(x, 5, 8)] |= BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(x + 1, 5, 8)] |= BaseClusterTopology.GROUND_OPEN;
        return BaseClusterTopology.build(section, 1,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.fromCells(cells)),
                new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.GROUND, 1, 1, false), scratch);
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
