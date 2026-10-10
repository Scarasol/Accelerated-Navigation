package com.scarasol.acceleratednavigation.topology;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, section-local collision geometry.
 *
 * <p>Shapes are stored once in a small palette and each cell keeps a palette
 * index. Coordinates are quantized to sixteenths, which is the resolution
 * used by Minecraft's block voxel shapes. Unknown geometry is deliberately a
 * distinct palette entry and is treated as occupied by the derived graph.</p>
 */
final class SectionGeometry {

    static final int EMPTY = 0;
    static final int FULL_BLOCK = 1;
    static final int UNKNOWN = 2;
    private static final int MAX_PALETTE = 4096;
    private static final int MAX_BOXES = 64;

    private static final Cell EMPTY_CELL = new Cell(Shape.EMPTY);
    private static final Cell FULL_CELL = new Cell(Shape.FULL);
    private static final Cell UNKNOWN_CELL = new Cell(Shape.UNKNOWN);

    private final short[] cells;
    private final Shape[] palette;
    private final long fingerprint;
    private final boolean hasDoors;

    private SectionGeometry(short[] cells, Shape[] palette) {
        this.cells = cells;
        this.palette = palette;
        this.fingerprint = fingerprint(cells, palette);
        this.hasDoors = Arrays.stream(palette).anyMatch(Shape::isDoor);
        validate();
    }

    static SectionGeometry allAir() {
        return new SectionGeometry(new short[BaseClusterTopology.CELL_COUNT],
                new Shape[]{Shape.EMPTY, Shape.FULL, Shape.UNKNOWN});
    }

    static SectionGeometry fromCells(Cell[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length != BaseClusterTopology.CELL_COUNT) {
            throw new IllegalArgumentException("geometry must contain exactly 4096 cells");
        }
        List<Shape> shapes = new ArrayList<>();
        shapes.add(Shape.EMPTY);
        shapes.add(Shape.FULL);
        shapes.add(Shape.UNKNOWN);
        Map<Shape, Short> ids = new HashMap<>();
        short[] cells = new short[values.length];
        for (int cell = 0; cell < values.length; cell++) {
            Cell value = Objects.requireNonNull(values[cell], "geometry cell");
            Shape shape = value.shape();
            if (shape == Shape.EMPTY || shape == Shape.FULL || shape == Shape.UNKNOWN) {
                cells[cell] = (short) (shape == Shape.EMPTY ? EMPTY
                        : shape == Shape.FULL ? FULL_BLOCK : UNKNOWN);
                continue;
            }
            Short id = ids.get(shape);
            if (id == null) {
                if (shapes.size() >= MAX_PALETTE) {
                    throw new IllegalArgumentException("too many section geometry shapes");
                }
                id = (short) shapes.size();
                shapes.add(shape);
                ids.put(shape, id);
            }
            cells[cell] = id;
        }
        return new SectionGeometry(cells, shapes.toArray(Shape[]::new));
    }

    static SectionGeometry fromFlags(byte[] flags) {
        Objects.requireNonNull(flags, "flags");
        if (flags.length != BaseClusterTopology.CELL_COUNT) {
            throw new IllegalArgumentException("facts must contain exactly 4096 cells");
        }
        Cell[] cells = new Cell[flags.length];
        for (int cell = 0; cell < flags.length; cell++) {
            int value = Byte.toUnsignedInt(flags[cell]);
            cells[cell] = (value & BaseClusterTopology.EXACT_REQUIRED) != 0
                    ? unknown()
                    : (value & BaseClusterTopology.VOLUME_OPEN) == 0
                    ? fullBlock() : empty();
        }
        return fromCells(cells);
    }

    static SectionGeometry read(DataInput input) throws IOException {
        int paletteSize = input.readInt();
        if (paletteSize < 3 || paletteSize > MAX_PALETTE) {
            throw new IllegalArgumentException("invalid geometry palette size");
        }
        Shape[] palette = new Shape[paletteSize];
        palette[EMPTY] = Shape.EMPTY;
        palette[FULL_BLOCK] = Shape.FULL;
        palette[UNKNOWN] = Shape.UNKNOWN;
        for (int index = 3; index < paletteSize; index++) {
            int header = input.readUnsignedByte();
            int kind = (header & 128) != 0 ? 4 : header > 64 ? 5 : 3;
            int boxCount = kind == 4 ? header & 127 : kind == 5 ? header & 63 : header;
            if (boxCount < 1 || boxCount > MAX_BOXES) {
                throw new IllegalArgumentException("invalid geometry box count");
            }
            short[] coordinates = new short[boxCount * 6];
            for (int coordinate = 0; coordinate < coordinates.length; coordinate++) {
                int value = input.readUnsignedByte();
                if (value > 16) throw new IllegalArgumentException("invalid geometry coordinate");
                coordinates[coordinate] = (short) value;
            }
            palette[index] = new Shape(kind, coordinates);
        }
        short[] cells = new short[BaseClusterTopology.CELL_COUNT];
        for (int cell = 0; cell < cells.length; cell++) {
            int id = input.readUnsignedShort();
            if (id >= paletteSize) throw new IllegalArgumentException("invalid geometry cell id");
            cells[cell] = (short) id;
        }
        return new SectionGeometry(cells, palette);
    }

    void write(DataOutput output, byte[] scratch) throws IOException {
        if (scratch.length < BaseClusterTopology.CELL_COUNT * Short.BYTES)
            throw new IllegalArgumentException("geometry scratch buffer too small");
        output.writeInt(palette.length);
        for (int index = 3; index < palette.length; index++) {
            Shape shape = palette[index];
            int boxes = shape.coordinates.length / 6;
            output.writeByte(boxes | (shape.kind == 4 ? 128 : shape.kind == 5 ? 64 : 0));
            for (short coordinate : shape.coordinates) output.writeByte(coordinate);
        }
        int offset = 0;
        for (short cell : cells) {
            scratch[offset++] = (byte) (Short.toUnsignedInt(cell) >>> 8);
            scratch[offset++] = (byte) cell;
        }
        output.write(scratch, 0, BaseClusterTopology.CELL_COUNT * Short.BYTES);
    }

    Shape shape(int cell) {
        if (cell < 0 || cell >= cells.length) throw new IndexOutOfBoundsException("geometry cell");
        return palette[Short.toUnsignedInt(cells[cell])];
    }

    boolean hasDoors() { return hasDoors; }

    boolean occupied(int cell, float width, float height, boolean canOpenDoors) {
        Shape shape = shape(cell);
        if (shape == Shape.EMPTY || canOpenDoors && shape.openableDoor()) return false;
        if (shape == Shape.FULL || shape == Shape.UNKNOWN) return true;
        // The coarse graph anchor is at the center of the cell.  The exact
        // palette still preserves the shape boundary, so a thin plate outside
        // the requested body footprint remains traversable.
        double halfWidth = width * 0.5;
        double minX = 0.5 - halfWidth;
        double maxX = 0.5 + halfWidth;
        double minY = 0.0;
        double maxY = height;
        double minZ = minX;
        double maxZ = maxX;
        return shape.intersects(minX, maxX, minY, maxY, minZ, maxZ);
    }

    int retainedBytes() {
        int total = 16 + cells.length * Short.BYTES;
        for (Shape shape : palette) total += shape.coordinates.length * Short.BYTES;
        return total;
    }

    long fingerprint() {
        return fingerprint;
    }

    boolean contentEquals(SectionGeometry other) {
        if (this == other) return true;
        if (other == null || !Arrays.equals(cells, other.cells)
                || palette.length != other.palette.length) return false;
        for (int index = 0; index < palette.length; index++) {
            if (!palette[index].equals(other.palette[index])) return false;
        }
        return true;
    }

    private void validate() {
        if (cells.length != BaseClusterTopology.CELL_COUNT
                || palette.length < 3 || palette.length > MAX_PALETTE
                || palette[EMPTY] != Shape.EMPTY
                || palette[FULL_BLOCK] != Shape.FULL
                || palette[UNKNOWN] != Shape.UNKNOWN) {
            throw new IllegalArgumentException("invalid section geometry");
        }
        for (short id : cells) {
            if (Short.toUnsignedInt(id) >= palette.length) {
                throw new IllegalArgumentException("geometry cell id outside palette");
            }
        }
    }

    static Cell empty() {
        return EMPTY_CELL;
    }

    static Cell fullBlock() {
        return FULL_CELL;
    }

    static Cell unknown() {
        return UNKNOWN_CELL;
    }

    static Cell door(Cell cell, boolean openable) {
        if (cell.shape.kind != 3 || cell.shape.coordinates.length / 6 > 63)
            throw new IllegalArgumentException("door requires bounded partial geometry");
        return new Cell(new Shape(openable ? 4 : 5, cell.shape.coordinates));
    }

    static Cell boxes(List<Box> boxes) {
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.isEmpty()) return empty();
        if (boxes.size() > MAX_BOXES) throw new IllegalArgumentException("too many geometry boxes");
        short[] coordinates = new short[boxes.size() * 6];
        int cursor = 0;
        for (Box box : boxes) {
            coordinates[cursor++] = box.minX;
            coordinates[cursor++] = box.minY;
            coordinates[cursor++] = box.minZ;
            coordinates[cursor++] = box.maxX;
            coordinates[cursor++] = box.maxY;
            coordinates[cursor++] = box.maxZ;
        }
        return new Cell(new Shape(coordinates));
    }

    static Box quantized(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
        return new Box(
                quantize(minX, false), quantize(minY, false), quantize(minZ, false),
                quantize(maxX, true), quantize(maxY, true), quantize(maxZ, true)
        );
    }

    private static short quantize(double value, boolean upper) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        int result = upper ? (int) Math.ceil(clamped * 16.0) : (int) Math.floor(clamped * 16.0);
        return (short) Math.max(0, Math.min(16, result));
    }

    private static long fingerprint(short[] cells, Shape[] palette) {
        long hash = 0xcbf29ce484222325L;
        for (short cell : cells) {
            hash = (hash ^ Short.toUnsignedInt(cell)) * 0x100000001b3L;
        }
        for (Shape shape : palette) {
            for (short coordinate : shape.coordinates) {
                hash = (hash ^ Short.toUnsignedInt(coordinate)) * 0x100000001b3L;
            }
            hash = (hash ^ shape.coordinates.length) * 0x100000001b3L;
            if (shape.isDoor()) hash = (hash ^ (256 + shape.kind)) * 0x100000001b3L;
        }
        return hash;
    }

    record Cell(Shape shape) {
        Cell {
            Objects.requireNonNull(shape, "shape");
        }

        long fingerprint() {
            return shape.fingerprint();
        }
    }

    record Box(short minX, short minY, short minZ,
               short maxX, short maxY, short maxZ) {
        Box {
            if (minX < 0 || minY < 0 || minZ < 0
                    || maxX > 16 || maxY > 16 || maxZ > 16
                    || minX >= maxX || minY >= maxY || minZ >= maxZ) {
                throw new IllegalArgumentException("invalid quantized geometry box");
            }
        }
    }

    static final class Shape {
        private static final Shape EMPTY = new Shape(0, new short[0]);
        private static final Shape FULL = new Shape(1, new short[]{0, 0, 0, 16, 16, 16});
        private static final Shape UNKNOWN = new Shape(2, new short[]{0, 0, 0, 16, 16, 16});

        private final int kind;
        private final short[] coordinates;

        private Shape(short[] coordinates) {
            this(3, coordinates);
        }

        private Shape(int kind, short[] coordinates) {
            this.kind = kind;
            this.coordinates = coordinates.clone();
        }

        boolean isDoor() { return kind == 4 || kind == 5; }
        boolean openableDoor() { return kind == 4; }

        int coordinateCount() {
            return coordinates.length;
        }

        short coordinate(int index) {
            return coordinates[index];
        }

        private boolean intersects(double minX, double maxX,
                                   double minY, double maxY,
                                   double minZ, double maxZ) {
            for (int offset = 0; offset < coordinates.length; offset += 6) {
                double boxMinX = coordinates[offset] / 16.0;
                double boxMinY = coordinates[offset + 1] / 16.0;
                double boxMinZ = coordinates[offset + 2] / 16.0;
                double boxMaxX = coordinates[offset + 3] / 16.0;
                double boxMaxY = coordinates[offset + 4] / 16.0;
                double boxMaxZ = coordinates[offset + 5] / 16.0;
                if (boxMaxX > minX && boxMinX < maxX
                        && boxMaxY > minY && boxMinY < maxY
                        && boxMaxZ > minZ && boxMinZ < maxZ) {
                    return true;
                }
            }
            return false;
        }

        private long fingerprint() {
            long hash = 0xcbf29ce484222325L;
            for (short coordinate : coordinates) {
                hash = (hash ^ Short.toUnsignedInt(coordinate)) * 0x100000001b3L;
            }
            hash = (hash ^ coordinates.length) * 0x100000001b3L;
            return isDoor() ? (hash ^ (256 + kind)) * 0x100000001b3L : hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Shape shape && kind == shape.kind
                    && Arrays.equals(coordinates, shape.coordinates);
        }

        @Override
        public int hashCode() {
            return 31 * kind + Arrays.hashCode(coordinates);
        }
    }
}
