package com.scarasol.acceleratednavigation.topology;

import java.util.*;
import net.minecraft.core.Direction;

/** Evaluates individual anchors and moves using the frozen Base rules. */
final class LocalKernel {
    record Row(int[] targets, long[] masks) {
        static final Row EMPTY = new Row(new int[0], new long[0]);
        long mask(int target) {
            int at = Arrays.binarySearch(targets, target);
            return at < 0 ? 0 : masks[at];
        }
    }
    static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    final BaseClusterTopology.BuildInput input;
    final BaseClusterTopology.GeometryKey body;
    final BaseClusterTopology.BuildScratch scratch;
    final BoundaryGeometry collision;
    final boolean hasDoors;
    final byte[] legal = new byte[4096], prism = new byte[4352];
    long anchorEvaluations, prismEvaluations, rowEvaluations, moveCandidates, factChecks;

    LocalKernel(BaseClusterTopology.BuildInput input, BaseClusterTopology.GeometryKey body,
                BaseClusterTopology.BuildScratch scratch) {
        if (body.channel() != BaseClusterTopology.Channel.GROUND || body.heightCells() < 2)
            throw new IllegalArgumentException("local kernel supports GROUND heightCells>=2");
        this.input = input; this.body = body; this.scratch = scratch;
        this.collision = new BoundaryGeometry(input);
        boolean[] doors = {false};
        input.visitFacts(facts -> doors[0] |= facts.geometry().hasDoors());
        this.hasDoors = doors[0];
    }
    boolean legal(int cell) {
        if (legal[cell] == 0) {
            anchorEvaluations++;
            legal[cell] = (byte) (anchor(cell, true) ? 2 : 1);
        }
        return legal[cell] == 2;
    }
    boolean prism(int x, int y, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= 17) return false;
        int cell = (y << 8) | (z << 4) | x;
        if (prism[cell] == 0) {
            prismEvaluations++;
            prism[cell] = (byte) (anchor(cell, false) ? 2 : 1);
        }
        return prism[cell] == 2;
    }
    private boolean anchor(int cell, boolean ground) {
        int x = cell & 15, y = cell >>> 8, z = cell >>> 4 & 15;
        int width = body.widthCells(), alignments = (width & 1) == 0 ? 4 : 1;
        for (int alignment = 0; alignment < alignments; alignment++) {
            int minX = -(width >>> 1) + ((width & 1) == 0 ? alignment & 1 : 0);
            int minZ = -(width >>> 1) + ((width & 1) == 0 ? alignment >>> 1 : 0);
            boolean open = true;
            for (int dz = 0; dz < width && open; dz++) for (int dx = 0; dx < width && open; dx++) {
                int cx = x + minX + dx, cz = z + minZ + dz;
                for (int dy = 0; dy < body.heightCells(); dy++) {
                    int cy = y + dy, flags = input.flags(cx, cy, cz, scratch);
                    factChecks++;
                    if ((!body.acceptsFluid() && (flags & BaseClusterTopology.FLUID) != 0)
                            || !input.volumeOpen(cx, cy, cz, body, scratch)
                            || ground && dy == 0 && !input.groundOpen(cx, cy, cz, flags, body, scratch)) {
                        open = false; break;
                    }
                }
            }
            if (open) return true;
        }
        return false;
    }
    private void preparePrism(int x, int y, int z, Direction direction, int horizontal, int dy) throws Exception {
        long[] mask = scratch.localPrismMask();
        Arrays.fill(mask, 0L);
        if (dy < 0) for (int yy = y - 1; yy > y + dy; yy--)
            putPrism(mask, x + direction.getStepX() * horizontal, yy, z + direction.getStepZ() * horizontal);
        for (int distance = 1; distance < horizontal; distance++) {
            int yy = y + Math.floorDiv(dy * distance, horizontal);
            int xx = x + direction.getStepX() * distance, zz = z + direction.getStepZ() * distance;
            putPrism(mask, xx, yy, zz); putPrism(mask, xx, yy + 1, zz);
        }
    }
    private void putPrism(long[] mask, int x, int y, int z) {
        if (prism(x, y, z)) {
            int cell = (y << 8) | (z << 4) | x;
            mask[cell >>> 6] |= 1L << cell;
        }
    }
    Row outgoing(int cell) throws Exception {
        rowEvaluations++;
        if (!legal(cell)) return Row.EMPTY;
        int x = cell & 15, y = cell >>> 8, z = cell >>> 4 & 15;
        TreeMap<Integer, Long> merged = new TreeMap<>();
        for (Direction direction : DIRECTIONS) for (int horizontal = 1; horizontal <= 3; horizontal++) {
            int nx = x + direction.getStepX() * horizontal, nz = z + direction.getStepZ() * horizontal;
            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) break;
            for (int dy = -4; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= 16) continue;
                moveCandidates++;
                int target = BaseClusterTopology.cellIndex(nx, ny, nz);
                if (!legal(target)) continue;
                preparePrism(x, y, z, direction, horizontal, dy);
                if (!BaseClusterTopology.movementEnvelopeOpen(x, y, z, direction, horizontal, dy, scratch)
                        || hasDoors && !input.doorMoveOpen(x, y, z, direction, horizontal, dy, body, collision)) continue;
                long mask = BaseClusterTopology.supportingCapabilities(Math.max(0, dy), horizontal - 1, Math.max(0, -dy));
                merged.merge(target, mask, (a, b) -> a | b);
            }
        }
        int[] targets = new int[merged.size()]; long[] masks = new long[targets.length]; int at = 0;
        for (var entry : merged.entrySet()) { targets[at] = entry.getKey(); masks[at++] = entry.getValue(); }
        return new Row(targets, masks);
    }
    Map<String, Long> work() {
        return Map.of("anchorEvaluations", anchorEvaluations, "prismEvaluations", prismEvaluations,
                "rowEvaluations", rowEvaluations, "moveCandidates", moveCandidates, "factChecks", factChecks);
    }
}
