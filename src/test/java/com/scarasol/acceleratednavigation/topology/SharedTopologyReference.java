package com.scarasol.acceleratednavigation.topology;

/** Independent component-CSR reachability control for shared anchor queries. */
final class SharedTopologyReference {
    record Query(BaseClusterTopology.GeometryKey body, BaseClusterTopology.MovementKey movement,
                 int start, int goal) {}

    static boolean reference(BaseClusterTopology graph, Query query) {
        int start = label(graph,query.start()), goal = label(graph,query.goal());
        if (start < 0 || goal < 0) return false;
        if (start == goal) return true;
        int[] queue = new int[graph.componentCount()];
        boolean[] visited = new boolean[queue.length];
        int head = 0, tail = 0;
        queue[tail++] = start; visited[start] = true;
        while (head < tail) {
            int source = queue[head++];
            for (int edge = graph.localEdgeStart(source); edge < graph.localEdgeEnd(source); edge++) {
                int target = graph.localEdgeTarget(edge);
                if (!graph.localEdgeSupports(edge,query.movement())) continue;
                if (target == goal) return true;
                if (!visited[target]) { visited[target] = true; queue[tail++] = target; }
            }
        }
        return false;
    }

    private static int label(BaseClusterTopology graph,int cell) {
        return graph.componentAt(BaseClusterTopology.x(cell),BaseClusterTopology.y(cell),BaseClusterTopology.z(cell));
    }
}
