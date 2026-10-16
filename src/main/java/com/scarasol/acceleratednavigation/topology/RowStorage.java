package com.scarasol.acceleratednavigation.topology;

import java.util.*;

/** Immutable 64-cell CSR chunks; exact basis matches retain only a reference bit. */
final class RowStorage {
    static final int SHIFT = 6, SIZE = 1 << SHIFT;
    static final class Block {
        final char[] offsets, targets;
        final long[] masks;
        final long inherited;
        Block(char[] offsets, char[] targets, long[] masks, long inherited) {
            this.offsets = offsets; this.targets = targets; this.masks = masks;
            this.inherited = inherited;
        }
    }
    static final Block EMPTY = new Block(new char[SIZE + 1], new char[0], new long[0], 0);
    final LocalKernel.Row[] objects;
    final Block[] blocks;
    final RowStorage basis;

    private RowStorage(LocalKernel.Row[] objects, Block[] blocks, RowStorage basis) {
        this.objects = objects; this.blocks = blocks; this.basis = basis;
    }
    static RowStorage create(LocalKernel.Row[] rows, boolean packed) {
        if (!packed) return new RowStorage(rows, null, null);
        var blocks = new Block[4096 / SIZE];
        var source = new RowStorage(rows, null, null);
        for (int chunk = 0; chunk < blocks.length; chunk++)
            blocks[chunk] = pack(chunk, source, Map.of(), null);
        return new RowStorage(null, blocks, null);
    }
    static RowStorage empty(boolean packed, RowStorage basis) {
        return packed ? new RowStorage(null, new Block[4096 / SIZE], basis)
                : new RowStorage(new LocalKernel.Row[4096], null, null);
    }
    Block block(int cell) {
        Block block = blocks[cell >>> SHIFT];
        if (block == null) return EMPTY;
        return (block.inherited & (1L << (cell & (SIZE - 1)))) == 0
                ? block : basis.block(cell);
    }
    int count(int cell) {
        if (objects != null) return objects[cell] == null ? 0 : objects[cell].targets().length;
        var block = block(cell); int row = cell & (SIZE - 1);
        return block.offsets[row + 1] - block.offsets[row];
    }
    int target(int cell, int edge) {
        if (objects != null) return objects[cell].targets()[edge];
        var block = block(cell);
        return block.targets[block.offsets[cell & (SIZE - 1)] + edge];
    }
    long mask(int cell, int edge) {
        if (objects != null) return objects[cell].masks()[edge];
        var block = block(cell);
        return block.masks[block.offsets[cell & (SIZE - 1)] + edge];
    }
    boolean same(int cell, LocalKernel.Row row) {
        if (count(cell) != row.targets().length) return false;
        for (int edge = 0; edge < row.targets().length; edge++)
            if (target(cell, edge) != row.targets()[edge] || mask(cell, edge) != row.masks()[edge])
                return false;
        return true;
    }
    RowStorage merge(Map<Integer, LocalKernel.Row> delta) {
        if (delta.isEmpty()) return this;
        if (objects != null) {
            var next = objects.clone();
            delta.forEach((cell, row) -> next[cell] = row);
            return new RowStorage(next, null, null);
        }
        var next = blocks.clone();
        var touched = new BitSet(next.length);
        for (int cell : delta.keySet()) touched.set(cell >>> SHIFT);
        for (int chunk = touched.nextSetBit(0); chunk >= 0; chunk = touched.nextSetBit(chunk + 1))
            next[chunk] = pack(chunk, this, delta, basis);
        return new RowStorage(null, next, basis);
    }
    private static Block pack(int chunk, RowStorage previous,
                              Map<Integer, LocalKernel.Row> delta, RowStorage basis) {
        var offsets = new char[SIZE + 1];
        long inherited = 0; int edges = 0;
        for (int row = 0; row < SIZE; row++) {
            int cell = (chunk << SHIFT) + row;
            var changed = delta.get(cell);
            var oldBlock = previous.blocks == null ? null : previous.blocks[chunk];
            boolean reuse = changed != null ? basis != null && basis.same(cell, changed)
                    : oldBlock != null && (oldBlock.inherited & (1L << row)) != 0;
            if (reuse) inherited |= 1L << row;
            else edges += changed == null ? previous.count(cell) : changed.targets().length;
            if (edges > Character.MAX_VALUE) throw new AssertionError("CSR offset overflow");
            offsets[row + 1] = (char) edges;
        }
        if (edges == 0 && inherited == 0) return null;
        var targets = new char[edges]; var masks = new long[edges];
        for (int row = 0; row < SIZE; row++) {
            if ((inherited & (1L << row)) != 0) continue;
            int cell = (chunk << SHIFT) + row;
            var changed = delta.get(cell);
            for (int edge = 0; edge < offsets[row + 1] - offsets[row]; edge++) {
                int target = changed == null ? previous.target(cell, edge) : changed.targets()[edge];
                if (target < 0 || target >= 4096) throw new AssertionError("CSR target outside section");
                targets[offsets[row] + edge] = (char) target;
                masks[offsets[row] + edge] = changed == null ? previous.mask(cell, edge) : changed.masks()[edge];
            }
        }
        return new Block(offsets, targets, masks, inherited);
    }
    long estimatedBytes() {
        if (objects != null) {
            long bytes = 48L + 16L + objects.length * 4L;
            for (var row : objects) if (row != null && row != LocalKernel.Row.EMPTY)
                bytes += 56L + row.targets().length * 12L;
            return bytes;
        }
        long bytes = 48L + 16L + blocks.length * 4L;
        for (var block : blocks) if (block != null)
            bytes += 40L + 16L + block.offsets.length * 2L
                    + 16L + block.targets.length * 2L + 16L + block.masks.length * 8L;
        return bytes;
    }
    long chunks() { return blocks == null ? 0 : Arrays.stream(blocks).filter(Objects::nonNull).count(); }
    long basisReferences() {
        if (blocks == null) return 0;
        long count = 0;
        for (var block : blocks) if (block != null) count += Long.bitCount(block.inherited);
        return count;
    }
}
