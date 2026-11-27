package com.scarasol.acceleratednavigation.topology;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.SectionPos;

public final class GroundSupportContracts {
    private static final byte OPEN = BaseClusterTopology.VOLUME_OPEN;
    private static final byte GROUND = OPEN | BaseClusterTopology.GROUND_OPEN;
    private static final byte PARTIAL = GROUND | BaseClusterTopology.EXACT_REQUIRED;
    private static final SectionGeometry.Cell LADDER = SectionGeometry.boxes(
            List.of(SectionGeometry.quantized(0, 0, .8125, 1, 1, 1)));
    private static final SectionGeometry.Cell SLAB = SectionGeometry.boxes(
            List.of(SectionGeometry.quantized(0, 0, 0, 1, .5, 1)));
    private static final SectionGeometry.Cell LEDGE = SectionGeometry.boxes(
            List.of(SectionGeometry.quantized(0, 0, 0, 1, .5, .375)));

    private GroundSupportContracts() {}

    public static void main(String[] args) {
        var body = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND);
        byte[] flags = new byte[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(flags, OPEN);
        var shapes = new SectionGeometry.Cell[flags.length];
        Arrays.fill(shapes, SectionGeometry.empty());
        put(flags, shapes, 2, 4, PARTIAL, LADDER);
        put(flags, shapes, 2, 5, PARTIAL, LADDER);
        put(flags, shapes, 2, 6, GROUND, SectionGeometry.empty());
        put(flags, shapes, 4, 4, PARTIAL, SLAB);
        put(flags, shapes, 4, 5, GROUND, SectionGeometry.empty());
        put(flags, shapes, 6, 4, (byte) 0, SectionGeometry.fullBlock());
        put(flags, shapes, 6, 5, PARTIAL, LADDER);
        put(flags, shapes, 8, 4, PARTIAL, LEDGE);
        put(flags, shapes, 8, 5, GROUND, SectionGeometry.empty());
        var center = BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(shapes));

        byte[] lowerFlags = new byte[flags.length];
        Arrays.fill(lowerFlags, OPEN);
        var lowerShapes = new SectionGeometry.Cell[flags.length];
        Arrays.fill(lowerShapes, SectionGeometry.empty());
        put(lowerFlags, lowerShapes, 2, 15, PARTIAL, LADDER);
        put(lowerFlags, lowerShapes, 4, 15, PARTIAL, SLAB);
        var lower = BaseClusterTopology.PackedFacts.fromCells(lowerFlags, SectionGeometry.fromCells(lowerShapes));
        var input = new BaseClusterTopology.BuildInput(center,
                new byte[]{(byte) BaseClusterTopology.haloIndex(0, -1, 0)},
                new BaseClusterTopology.PackedFacts[]{lower}, new long[]{1}, new long[]{lower.fingerprint()});

        var graph = BaseClusterTopology.build(SectionPos.of(0, 0, 0), 1, input, body,
                new BaseClusterTopology.BuildScratch());
        check(graph, 2, 4, false);
        check(graph, 2, 5, false);
        check(graph, 2, 6, false);
        check(graph, 4, 5, true);
        check(graph, 6, 5, true);
        check(graph, 8, 5, true);
        check(graph, 2, 0, false);
        check(graph, 4, 0, true);
        System.out.println("Ground support contracts: 8 passed");
    }

    private static void put(byte[] flags, SectionGeometry.Cell[] shapes, int x, int y,
                            byte flag, SectionGeometry.Cell shape) {
        int cell = BaseClusterTopology.cellIndex(x, y, 2);
        flags[cell] = flag;
        shapes[cell] = shape;
    }

    private static void check(BaseClusterTopology graph, int x, int y, boolean expected) {
        if ((graph.componentAt(x, y, 2) >= 0) != expected) {
            throw new AssertionError("Base support at " + x + "," + y + " expected " + expected);
        }
    }
}
