package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
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

/** Current Base topology membership and the actual native final search contract. */
public final class GroundNavigationContracts {
    private static int checks;

    private GroundNavigationContracts() {}

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
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

    private static Path nativeLine(int start, int target, float range, int accuracy, float multiplier) throws Exception {
        var finder = new PathFinder(new LineEvaluator(), 512);
        var method = PathFinder.class.getDeclaredMethod("findPath", ProfilerFiller.class,
                Node.class, Map.class, float.class, int.class, float.class);
        method.setAccessible(true);
        return (Path) method.invoke(finder, InactiveProfiler.INSTANCE, new Node(start, 1, 0),
                Map.of(new Target(target, 1, 0), new BlockPos(target, 1, 0)), range, accuracy, multiplier);
    }

    private static void nativeEndpointContract() throws Exception {
        for (int distance = 5; distance <= 15; distance++) {
            Path exact = nativeLine(0, distance, distance, 0, 1);
            require(exact != null && !exact.canReach(), "Native search rejects walkedDistance == range");
            require(nativeLine(0, 4, 16, 0, 1).canReach(), "Intermediate exact target is reachable");
            require(nativeLine(4, distance, distance, 0, 1).canReach(), "Detailed region final target is reachable");
            require(nativeLine(0, distance, Math.nextUp((float) distance), 0, 1).canReach(),
                    "Just-above range reaches the exact endpoint");
        }
        float multiplier = 6f / 512;
        require(!nativeLine(0, 8, 35, 0, multiplier).canReach(), "Low visit budget rejects long detailed search");
        require(nativeLine(0, 4, 16, 0, multiplier).canReach(), "Budget permits first detailed segment");
        require(nativeLine(4, 8, 35, 0, multiplier).canReach(), "Budget permits last detailed segment");
        for (int accuracy = 0; accuracy <= 2; accuracy++) {
            Path path = nativeLine(0, 17, 35, accuracy, 1);
            require(path != null && path.getTarget().equals(new BlockPos(17, 1, 0)), "Do not replace the original target");
            require(path.getEndNode().x == 15 && path.canReach() == (accuracy == 2),
                    "A two-block gap satisfies only a radius of two");
        }
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

    private static void componentMembership() {
        var body = new BaseClusterTopology.TraversalProfile(.6f, 1.95f, 1, 0, 3, false, false)
                .geometry(BaseClusterTopology.Channel.GROUND);
        for (var fixture : contractFixtures()) {
            var graph = BaseClusterTopology.build(Fixtures.SECTION, 1, fixture.input(), body,
                    new BaseClusterTopology.BuildScratch());
            boolean[] found = new boolean[graph.componentCount()];
            require(found.length > 0, fixture.name() + " has usable components");
            for (int cell = 0; cell < BaseClusterTopology.CELL_COUNT; cell++) {
                int component = graph.componentAt(BaseClusterTopology.x(cell), BaseClusterTopology.y(cell),
                        BaseClusterTopology.z(cell));
                require(component >= -1 && component < found.length, fixture.name() + " has valid cell membership");
                if (component >= 0) found[component] = true;
            }
            for (int component = 0; component < found.length; component++) {
                int anchor = graph.componentAnchorCell(component);
                require(found[component] && component == graph.componentAt(BaseClusterTopology.x(anchor),
                                BaseClusterTopology.y(anchor), BaseClusterTopology.z(anchor)),
                        fixture.name() + " has a member anchor for each component");
                for (int edge = graph.localEdgeStart(component); edge < graph.localEdgeEnd(component); edge++) {
                    int target = graph.localEdgeTarget(edge);
                    require(target != component && found[target] && graph.localEdgeCapabilities(edge) != 0,
                            fixture.name() + " has a usable transition between distinct components");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        nativeEndpointContract();
        componentMembership();
        System.out.println("Ground navigation contracts: " + checks + " passed");
    }
}
