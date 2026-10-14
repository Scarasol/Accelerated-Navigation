package an.production;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/** Exact branch counters for a few fixed-input replays; never changes the search. */
public final class NativeReplayTelemetry {
    public static final class Stats {
        public String label;
        public int visitedLimit, popped, rangePrunedNodes, rangeRejectedNeighbors, neighborCalls, zeroNeighborCalls, openRemaining;
        public float range, multiplier;
        public boolean reachable;
        public final List<List<Integer>> nodes = new ArrayList<>();
        private transient Node start;
    }
    private static Stats active;
    public static Stats expect(String label) {
        if (active != null) throw new IllegalStateException("nested replay");
        active = new Stats(); active.label = label; return active;
    }
    public static void started(Node start, float range, float multiplier, int limit) {
        if (active == null) return;
        active.start = start; active.range = range; active.multiplier = multiplier;
        active.visitedLimit = (int) (limit * multiplier);
    }
    public static void popped(Node node) {
        if (active == null) return;
        active.popped++;
        if (node.distanceTo(active.start) >= active.range) active.rangePrunedNodes++;
    }
    public static void neighbors(Node source, Node[] nodes, int count) {
        if (active == null) return;
        active.neighborCalls++;
        if (count == 0) active.zeroNeighborCalls++;
        for (int i = 0; i < count; i++)
            if (source.walkedDistance + source.distanceTo(nodes[i]) >= active.range)
                active.rangeRejectedNeighbors++;
    }
    public static void finished(BinaryHeap heap, Path path) {
        if (active == null) return;
        active.openRemaining = heap.size();
        active.reachable = path != null && path.canReach();
        if (path != null) for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i); active.nodes.add(List.of(node.x, node.y, node.z));
        }
    }
    public static void clear() { active = null; }
}
