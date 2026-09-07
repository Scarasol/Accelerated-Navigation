package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch.Status;
import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MacroSearchFailureMatrixTest {
    private static final List<String> REASONS = List.of("R", "P", "U");
    private static final List<SectionPos> SECTIONS = List.of(SectionPos.of(0, 0, 0),
            SectionPos.of(1, 0, 0), SectionPos.of(0, 0, 1));

    @Test void everyFrozenMixedFailureAndAlternativeInputKeepsItsExpectedTerminal() {
        int checked = 0;
        for (var target : ProductionRemediationPlan.fixedFailureTargets()) {
            if (!List.of("F02", "F03/alternative").contains(target.kind())) continue;
            List<String> placement = Arrays.asList(target.parameters().get("placement").split(","));
            List<String> ranked = placement.stream().sorted(Comparator.comparingInt(REASONS::indexOf)).toList();
            List<Blocked> blocked = new ArrayList<>();
            for (int index = 0; index < placement.size(); index++) {
                String reason = placement.get(index);
                int rank = ranked.indexOf(reason);
                int f = switch (target.parameters().get("f")) {
                    case "equal" -> 10;
                    case "ascending" -> (rank + 1) * 10;
                    case "descending" -> (placement.size() - rank) * 10;
                    default -> throw new AssertionError(target.id());
                };
                blocked.add(new Blocked(reason, SECTIONS.get(index), f, index + 1));
            }
            Graph graph = new Graph(blocked, target.kind().equals("F03/alternative"));
            MacroSearch search = new MacroSearch(graph, 1, 100);
            search.step(blocked.size() + 1);
            assertEquals(blocked.size(), search.pendingDependencies(16).size(), target.id());
            for (String reason : target.parameters().get("notification").split(",")) {
                Blocked failure = blocked.get(placement.indexOf(reason));
                for (int repeat = 0; repeat < (target.parameters().get("repeat").equals("twice") ? 2 : 1); repeat++) {
                    search.dependencyUnavailable(failure.dependency(), failure.cause());
                }
            }
            search.step(100);
            if (graph.alternative) {
                assertEquals(Status.SUCCEEDED, search.status(), target.id());
                assertEquals(MacroSearch.Failure.NONE, search.failure(), target.id());
                assertNull(search.blockedSection(), target.id());
                assertEquals(List.of(1000L), search.result().connections().stream().map(MacroSearch.Connection::id).toList());
            } else {
                int lowestF = blocked.stream().mapToInt(Blocked::f).min().orElseThrow();
                String firstReason = REASONS.stream().filter(reason -> blocked.stream()
                        .anyMatch(failure -> failure.f == lowestF && failure.reason.equals(reason))).findFirst().orElseThrow();
                Blocked expected = blocked.stream().filter(failure -> failure.f == lowestF && failure.reason.equals(firstReason)).findFirst().orElseThrow();
                assertEquals(Status.FAILED, search.status(), target.id());
                assertEquals(expected.cause().reason(), search.failure(), target.id());
                assertEquals(expected.section, search.blockedSection(), target.id());
            }
            checked++;
        }
        assertEquals(576, checked);
    }

    @Test void sameReasonSectionsAndStructuralSourcesIgnoreInsertionAndNotificationOrder() {
        for (String reason : REASONS) {
            for (List<String> order : ProductionRemediationPlan.permutations(List.of("0", "1", "2"))) {
                List<Blocked> blocked = new ArrayList<>();
                for (String value : order) {
                    int index = Integer.parseInt(value);
                    blocked.add(new Blocked(reason, SECTIONS.get(index), 10, index + 1));
                }
                MacroSearch search = new MacroSearch(new Graph(blocked, false), 1, 100);
                search.step(4);
                for (Blocked failure : blocked) search.dependencyUnavailable(failure.dependency(), failure.cause());
                search.step(100);
                assertEquals(SECTIONS.get(0), search.blockedSection());
                assertEquals(blocked.get(0).cause().reason(), search.failure());
            }
            for (boolean reverse : List.of(false, true)) {
                Blocked low = new Blocked(reason, SECTIONS.get(0), 10, reverse ? 91 : 1);
                Blocked high = new Blocked(reason, SECTIONS.get(0), 10, reverse ? 1 : 91);
                MacroSearch.Endpoint lowSource = new MacroSearch.ExactEndpoint(low.id, new BlockPos(-5, 0, 0), 1);
                MacroSearch.Endpoint highSource = new MacroSearch.ExactEndpoint(high.id, new BlockPos(5, 0, 0), 1);
                var start = new MacroSearch.ExactEndpoint(100, BlockPos.ZERO, 1);
                var goal = new MacroSearch.ExactEndpoint(101, new BlockPos(100, 0, 0), 1);
                MacroSearch search = new MacroSearch(new MacroSearch.Graph() {
                    @Override public MacroSearch.Endpoint start() { return start; }
                    @Override public MacroSearch.Endpoint goal() { return goal; }
                    @Override public boolean revisionsValid() { return true; }
                    @Override public float heuristic(MacroSearch.Endpoint node) { return 0; }
                    @Override public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
                        if (from == start) {
                            output.addMembership(1, reverse ? highSource : lowSource, 10);
                            output.addMembership(2, reverse ? lowSource : highSource, 10);
                        } else output.addDependency(0, low.dependency());
                    }
                }, 1, 100);
                search.step(3);
                search.dependencyUnavailable(low.dependency(), low.cause());
                Object selected = TopologyTestBridge.invoke(search, "bestBlockedFailure");
                assertSame(lowSource, TopologyTestBridge.readField(selected, "source"));
                search.step(100);
                assertEquals(SECTIONS.get(0), search.blockedSection());
            }
        }
    }

    private record Blocked(String reason, SectionPos section, int f, long id) {
        MacroSearch.DependencyKey dependency() { return new MacroSearch.DependencyKey(MacroSearch.DependencyKind.BASE_CLUSTER, section); }
        MacroSearch.Unavailability cause() {
            return new MacroSearch.Unavailability(switch (reason) {
                case "R" -> MacroSearch.Failure.FACTS_RECOVERY_FAILED;
                case "P" -> MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE;
                case "U" -> MacroSearch.Failure.UNAVAILABLE_CHUNK;
                default -> throw new AssertionError(reason);
            }, section);
        }
    }

    private record Graph(List<Blocked> blocked, boolean alternative) implements MacroSearch.Graph {
        @Override public MacroSearch.Endpoint start() { return node(0); }
        @Override public MacroSearch.Endpoint goal() { return node(100); }
        @Override public boolean revisionsValid() { return true; }
        @Override public float heuristic(MacroSearch.Endpoint node) { return 0; }
        @Override public float prefetchSlack() { return 100; }
        private MacroSearch.Endpoint node(long id) { return new MacroSearch.ExactEndpoint(id, new BlockPos((int) id, 0, 0), 1); }
        @Override public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            if (from.id() == 0) {
                for (Blocked value : blocked) output.addMembership(value.id, node(value.id), value.f);
                if (alternative) output.addMembership(1000, goal(), 1000);
            } else {
                Blocked value = blocked.stream().filter(candidate -> candidate.id == from.id()).findFirst().orElseThrow();
                output.addDependency(0, value.dependency());
            }
        }
    }
}
