package com.scarasol.acceleratednavigation.topology;

/** Immutable transpose retaining source components and original outgoing-edge indexes. */
final class IncomingEdges {
    private static final IncomingEdges EMPTY =
            new IncomingEdges(new int[1], new int[0], new int[0]);

    private final int[] offsets;
    private final int[] sources;
    private final int[] edges;

    private IncomingEdges(int[] offsets, int[] sources, int[] edges) {
        this.offsets = offsets;
        this.sources = sources;
        this.edges = edges;
    }

    static IncomingEdges build(int[] outgoingOffsets, int[] targets, int targetCount) {
        if (targets.length == 0) return EMPTY;
        int[] offsets = new int[targetCount + 1];
        for (int target : targets) offsets[target + 1]++;
        for (int i = 1; i < offsets.length; i++) offsets[i] += offsets[i - 1];
        int[] cursor = offsets.clone();
        int[] sources = new int[targets.length];
        int[] edges = new int[targets.length];
        for (int source = 0; source + 1 < outgoingOffsets.length; source++) {
            for (int edge = outgoingOffsets[source]; edge < outgoingOffsets[source + 1]; edge++) {
                int incoming = cursor[targets[edge]]++;
                sources[incoming] = source;
                edges[incoming] = edge;
            }
        }
        return new IncomingEdges(offsets, sources, edges);
    }

    // Reserve before lazy construction so cache accounting cannot grow behind its owner.
    static int reservedBytes(int targetCount, int edgeCount) {
        if (edgeCount == 0) return 0;
        return Math.addExact(128, Math.multiplyExact(Integer.BYTES,
                Math.addExact(targetCount + 1, Math.multiplyExact(edgeCount, 2))));
    }

    int start(int target) {
        if (target < 0) throw new IllegalArgumentException("negative target component");
        return target + 1 < offsets.length ? offsets[target] : 0;
    }

    int end(int target) {
        if (target < 0) throw new IllegalArgumentException("negative target component");
        return target + 1 < offsets.length ? offsets[target + 1] : 0;
    }

    int source(int incoming) { return sources[incoming]; }
    int edge(int incoming) { return edges[incoming]; }
}
