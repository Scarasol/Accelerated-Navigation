package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;

/** Independent shared-stage oracle and ordinary Base membership checks. */
public final class SharedStageContracts {
    private static final SectionPos SECTION = SectionPos.of(-10, 5, -10);
    private static int checks, fixtureRoutes, componentProofs;

    private SharedStageContracts() {}

    private static SharedSectionCandidate candidate(List<Integer> cells) {
        return new SharedSectionCandidate(SECTION, new RuntimeSharedTopology.Result(
                true, "CANDIDATE", List.copyOf(cells), 1, List.of(), 0, 0, new AtomicBoolean(true)));
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Runnable operation, Class<? extends RuntimeException> expected) {
        try {
            operation.run();
            throw new AssertionError("Expected " + expected.getSimpleName());
        } catch (RuntimeException error) {
            require(expected.isInstance(error), "Wrong exception: " + error);
        }
    }

    private static int oracle(List<Integer> cells, int cursor, float range) {
        boolean walking = true;
        for (int index = cursor + 1; index < cells.size(); index++) {
            int from = cells.get(index - 1), to = cells.get(index);
            int dx = Math.abs(from % 16 - to % 16);
            int dz = Math.abs(from / 16 % 16 - to / 16 % 16);
            if (from / 256 != to / 256 || dx + dz != 1) walking = false;
        }
        int last = cells.size() - 1;
        return walking && last - cursor < range ? last : Math.min(last, cursor + 4);
    }

    private static void compare(List<Integer> cells) {
        var value = candidate(cells);
        for (int cursor = 0; cursor < cells.size(); cursor++) {
            for (float range : new float[]{0, 1, 3.5f, 4, 7.5f, 8, 12, 35, 4096}) {
                require(value.stageEnd(cursor, range) == oracle(cells, cursor, range),
                        "Stage differs from full-suffix oracle");
            }
            require(value.stageEnd(cursor, 35, false) == Math.min(cells.size() - 1, cursor + 4),
                    "Rejected merge must restore original stage");
        }
        rejected(() -> value.stageEnd(-1, 35), IndexOutOfBoundsException.class);
        rejected(() -> value.stageEnd(cells.size(), 35), IndexOutOfBoundsException.class);
        for (float range : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            rejected(() -> value.stageEnd(0, range), IllegalArgumentException.class);
        }
    }

    private static void syntheticPaths() {
        var flat = new ArrayList<Integer>();
        for (int x = 3; x <= 11; x++) flat.add(Fixtures.cell(x, 1, 7));
        var value = candidate(flat);
        require(value.stageEnd(0, 35) == 8, "Eight-block walk must use one final stage");
        require(value.stageEnd(0, 4) == 4, "Small range must retain the original stage");
        require(value.stageEnd(0, 8) == 4, "Native range is strict at the endpoint");
        require(value.stageEnd(0, Math.nextUp(8f)) == 8, "Range just above the endpoint may coalesce");
        require(value.anchors().get(0).equals(new BlockPos(-157, 81, -153)), "Negative section translation");
        compare(flat);
        compare(List.of(Fixtures.cell(3, 1, 7)));
        var turn = new ArrayList<>(flat);
        for (int z = 8; z <= 12; z++) turn.add(Fixtures.cell(11, 1, z));
        compare(turn);
        require(candidate(turn).stageEnd(0, 35) == turn.size() - 1, "Flat turns may coalesce");
        for (int[] transition : new int[][]{{1, 1}, {1, -1}, {2, 0}}) {
            var interrupted = new ArrayList<>(flat);
            int x = 11 + transition[0], y = 1 + transition[1];
            interrupted.add(Fixtures.cell(x, y, 7));
            interrupted.add(Fixtures.cell(x + 1, y, 7));
            compare(interrupted);
            require(candidate(interrupted).stageEnd(0, 35) == 4, "Non-walking prefix must retain original stage");
            require(candidate(interrupted).stageEnd(9, 35) == 10, "Walking suffix after transition may coalesce");
        }
        var random = new Random(20261017L);
        for (int run = 0; run < 300; run++) {
            var cells = new ArrayList<Integer>();
            int x = 3, y = 5, z = 7;
            cells.add(Fixtures.cell(x, y, z));
            for (int index = 0, count = 1 + random.nextInt(48); index < count; index++) {
                switch (random.nextInt(8)) {
                    case 0 -> y = Math.min(13, y + 1);
                    case 1 -> y = Math.max(0, y - 3);
                    case 2 -> x = Math.min(15, x + 2);
                    case 3 -> x = Math.max(0, x - 1);
                    case 4 -> x = Math.min(15, x + 1);
                    case 5 -> z = Math.max(0, z - 1);
                    default -> z = Math.min(15, z + 1);
                }
                cells.add(Fixtures.cell(x, y, z));
            }
            compare(cells);
        }
        rejected(() -> candidate(List.of()).stageEnd(0, 35), IndexOutOfBoundsException.class);
    }

    private static final class LineEvaluator extends NodeEvaluator {
        @Override public Node getStart() { return getNode(0, 1, 0); }
        @Override public Target getGoal(double x, double y, double z) {
            return new Target((int) x, (int) y, (int) z);
        }
        @Override public int getNeighbors(Node[] neighbors, Node current) {
            if (current.x >= 15) return 0;
            neighbors[0] = getNode(current.x + 1, 1, 0);
            return 1;
        }
        @Override public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
            return BlockPathTypes.WALKABLE;
        }
        @Override public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
            return BlockPathTypes.WALKABLE;
        }
    }

    private static Path nativeLine(int start, int target, float range) throws Exception {
        return nativeLine(start,target,range,1f);
    }

    private static Path nativeLine(int start, int target, float range, float multiplier) throws Exception {
        var finder = new PathFinder(new LineEvaluator(), 512);
        var method = PathFinder.class.getDeclaredMethod("findPath", ProfilerFiller.class,
                Node.class, Map.class, float.class, int.class, float.class);
        method.setAccessible(true);
        return (Path) method.invoke(finder, InactiveProfiler.INSTANCE, new Node(start, 1, 0),
                Map.of(new Target(target, 1, 0), new BlockPos(target, 1, 0)), range, 0, multiplier);
    }

    private static void nativeRangeBoundary() throws Exception {
        // Use the game's real search loop; a stage-selection oracle cannot establish range semantics.
        for (int distance = 5; distance <= 15; distance++) {
            var cells = new ArrayList<Integer>();
            for (int x = 0; x <= distance; x++) cells.add(Fixtures.cell(x, 1, 0));
            var value = candidate(cells);
            Path exact = nativeLine(0, distance, distance);
            require(exact != null && !exact.canReach(), "Native search must reject walkedDistance == range");
            require(value.stageEnd(0, distance) == 4, "Exact native range must preserve intermediate stage");
            require(nativeLine(0, 4, 16).canReach(), "Original first stage must reach");
            require(nativeLine(4, distance, distance).canReach(), "Original final stage must reach");
            require(value.stageEnd(0, Math.nextUp((float) distance)) == distance, "Just-above range may merge");
            require(nativeLine(0, distance, Math.nextUp((float) distance)).canReach(), "Native just-above range must reach");
        }
        var cells = new ArrayList<Integer>();
        for (int x = 0; x <= 8; x++) cells.add(Fixtures.cell(x, 1, 0));
        var value = candidate(cells);
        float multiplier = 6f / 512;
        require(value.stageEnd(0, 35) == 8, "Positive candidate may initially merge");
        require(!nativeLine(0, 8, 35, multiplier).canReach(), "Low native visit limit must reject merged search");
        int fallback = value.stageEnd(0, 35, false);
        require(fallback == 4, "Rejected merge must choose original intermediate target");
        require(nativeLine(0, fallback, 16, multiplier).canReach(), "Fallback first search must reach");
        require(nativeLine(fallback, 8, 35, multiplier).canReach(), "Fallback final search must reach");
    }

    private static List<Fixtures.Fixture> contractFixtures() {
        var fixtures = new ArrayList<>(List.of(
                Fixtures.fromFlags("flat", Fixtures.flat()),
                Fixtures.wall("narrow-gap", 1, false),
                Fixtures.wall("wide-gap", 4, false),
                Fixtures.wall("water-only-link", 1, true),
                Fixtures.door("openable-door", true, false),
                Fixtures.door("locked-door", false, false),
                Fixtures.door("standing-above-door", true, true)));
        byte[] step = Fixtures.flat(), drop = Fixtures.flat(), jump = Fixtures.flat();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (x >= 8) {
                step[Fixtures.cell(x, 1, z)] = 0;
                step[Fixtures.cell(x, 2, z)] |= BaseClusterTopology.GROUND_OPEN;
            } else {
                for (int y = 1; y <= 3; y++) drop[Fixtures.cell(x, y, z)] = 0;
                drop[Fixtures.cell(x, 4, z)] |= BaseClusterTopology.GROUND_OPEN;
            }
            if (x >= 7 && x <= 8) jump[Fixtures.cell(x, 1, z)] &= ~BaseClusterTopology.GROUND_OPEN;
        }
        fixtures.add(Fixtures.fromFlags("one-step", step));
        fixtures.add(Fixtures.fromFlags("three-drop", drop));
        fixtures.add(Fixtures.fromFlags("two-cell-jump", jump));
        return fixtures;
    }

    private static void componentMembership() throws Exception {
        var profile = new BaseClusterTopology.TraversalProfile(.6f, 1.95f, 1, 0, 3, false, false);
        var body = profile.geometry(BaseClusterTopology.Channel.GROUND);
        var movement = profile.movement(BaseClusterTopology.Channel.GROUND);
        for (var fixture : contractFixtures()) {
            var graph = BaseClusterTopology.build(Fixtures.SECTION, 1, fixture.input(), body,
                    new BaseClusterTopology.BuildScratch());
            var legal = new ArrayList<Integer>();
            for (int cell = 0; cell < 4096; cell++) {
                if (graph.componentAt(cell & 15, cell >>> 8, cell >>> 4 & 15) >= 0) legal.add(cell);
            }
            if (legal.isEmpty()) continue;
            var rows = SharedRows.create(fixture.input());
            for (int sample = 0; sample < 8; sample++) {
                int start = legal.get(sample % legal.size());
                int goal = legal.get(Math.max(0, legal.size() - 1 - sample));
                var outcome = rows.query(new SharedRows.Query(body, movement, start, goal, 4096));
                rows = rows.merge(body, outcome.change());
                if (!outcome.candidate()) continue;
                fixtureRoutes++;
                compare(outcome.cells());
                var value = candidate(outcome.cells());
                require(value.anchors().get(value.anchors().size() - 1).equals(new BlockPos(
                        SECTION.minBlockX() + (goal & 15), SECTION.minBlockY() + (goal >>> 8),
                        SECTION.minBlockZ() + (goal >>> 4 & 15))), "Shared route must end at the exact query goal");
                for (int cursor = 0; cursor + 4 < outcome.cells().size() - 1; cursor++) {
                    if (value.stageEnd(cursor, 4096) != outcome.cells().size() - 1) continue;
                    int first = outcome.cells().get(cursor);
                    int component = graph.componentAt(first & 15, first >>> 8, first >>> 4 & 15);
                    require(component >= 0, "Coalesced source must be legal");
                    for (int index = cursor + 1; index < outcome.cells().size(); index++) {
                        int cell = outcome.cells().get(index);
                        require(component == graph.componentAt(cell & 15, cell >>> 8, cell >>> 4 & 15),
                                "Coalesced suffix must remain in one ordinary Base component");
                    }
                    componentProofs++;
                }
            }
        }
        require(fixtureRoutes > 0 && componentProofs > 0, "Missing component proof coverage");
    }

    public static void main(String[] args) throws Exception {
        syntheticPaths();
        nativeRangeBoundary();
        componentMembership();
        System.out.println("Shared stage contracts: " + checks + " checks, " + fixtureRoutes
                + " fixture routes, " + componentProofs + " component proofs");
    }
}
