package com.scarasol.acceleratednavigation.topology;

import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.Direction;

/** Geometry-normalized anchor rows; component identity remains in the topology. */
final class BoundaryPortBits {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final long FULL_BOUNDS = (15L << 24) | (15L << 28) | (15L << 32);
    private static final long EMPTY_BOUNDS = -1L;
    private final long[] xRows;
    private final short[] zRows;
    private final long legalBounds;

    BoundaryPortBits(long[] legalMask) {
        xRows = Arrays.copyOf(legalMask, 64);
        zRows = new short[256];
        int xMask = 0, yMask = 0, zMask = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            int bits = rowX(y, z);
            if (bits != 0) {
                xMask |= bits;
                yMask |= 1 << y;
                zMask |= 1 << z;
            }
            while (bits != 0) {
                int x = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                zRows[(y << 4) | x] |= (short) (1 << z);
            }
        }
        legalBounds = occupiedBounds(xMask, yMask, zMask);
    }

    BoundaryPortBits(BoundaryPortBits donor, BitSet changedCells, long bounds) {
        xRows = donor.xRows.clone();
        zRows = donor.zRows.clone();
        legalBounds = bounds;
        for (int cell = changedCells.nextSetBit(0); cell >= 0; cell = changedCells.nextSetBit(cell + 1)) {
            int x = BaseClusterTopology.x(cell), y = BaseClusterTopology.y(cell), z = BaseClusterTopology.z(cell);
            xRows[cell >>> 6] ^= 1L << cell;
            zRows[(y << 4) | x] ^= (short) (1 << z);
        }
    }

    int retainedBytes() { return 40 + 16 + 64 * Long.BYTES + 16 + 256 * Short.BYTES; }

    private static long occupiedBounds(int x, int y, int z) {
        if (x == 0) return EMPTY_BOUNDS;
        return (long) Integer.numberOfTrailingZeros(x) << 12
                | (long) Integer.numberOfTrailingZeros(y) << 16
                | (long) Integer.numberOfTrailingZeros(z) << 20
                | (long) (31 - Integer.numberOfLeadingZeros(x)) << 24
                | (long) (31 - Integer.numberOfLeadingZeros(y)) << 28
                | (long) (31 - Integer.numberOfLeadingZeros(z)) << 32;
    }

    int rowX(int y, int z) {
        if ((y | z) < 0 || y >= 16 || z >= 16) return 0;
        return (int) (xRows[(y << 2) | (z >>> 2)] >>> ((z & 3) << 4)) & 0xffff;
    }

    int faceRow(Direction face, int inset, int row) {
        if (inset < 0 || inset >= 16 || row < 0 || row >= 16) return 0;
        return switch (face) {
            case DOWN -> rowX(inset, row);
            case UP -> rowX(15 - inset, row);
            case NORTH -> rowX(row, inset);
            case SOUTH -> rowX(row, 15 - inset);
            case WEST -> zRows[(row << 4) | inset] & 0xffff;
            case EAST -> zRows[(row << 4) | (15 - inset)] & 0xffff;
        };
    }

    int candidates(BoundaryPortBits target, Direction face, int inset, int row,
                   Direction direction, int distance, int dy, int sectionYDelta) {
        int source = faceRow(face, inset, row);
        if (source == 0) return 0;
        if (!face.getAxis().isVertical()) {
            int targetInset = distance - inset - 1;
            return source & target.faceRow(face.getOpposite(), targetInset, row + dy - sectionYDelta);
        }
        int sourceY = face == Direction.UP ? 15 - inset : inset;
        int targetBits = target.rowX(sourceY + dy - sectionYDelta,
                row + direction.getStepZ() * distance);
        int dx = direction.getStepX() * distance;
        int aligned = dx >= 0 ? targetBits >>> dx : targetBits << -dx;
        return source & aligned;
    }

    static int depth(Direction face, boolean volume) {
        return volume ? 1 : face.getAxis().isVertical()
                ? (face == Direction.UP ? BaseClusterTopology.MAX_STRUCTURAL_STEP
                : BaseClusterTopology.MAX_STRUCTURAL_DROP) : BaseClusterTopology.MAX_STRUCTURAL_JUMP;
    }

    private static int minimum(long bounds, int axis) {
        return (int) (bounds >>> (12 + 4 * axis)) & 15;
    }

    private static int maximum(long bounds, int axis) {
        return (int) (bounds >>> (24 + 4 * axis)) & 15;
    }

    private static int rangeMask(int min, int max) {
        min = Math.max(0, min);
        max = Math.min(15, max);
        return min > max ? 0 : ((1 << (max + 1)) - 1) & ~((1 << min) - 1);
    }

    static boolean mayLink(BaseClusterTopology source, int sourceComponent,
                           BaseClusterTopology target, int targetComponent, Direction face) {
        if (source.portBits() == null || target.portBits() == null) return true;
        if (sourceComponent >= source.componentCount() || targetComponent >= target.componentCount()) return false;
        boolean volume = source.geometry().channel() == BaseClusterTopology.Channel.VOLUME;
        boolean vertical = face.getAxis().isVertical();
        int sectionYDelta = (target.section().y() - source.section().y()) * 16;
        int maximumDistance = volume ? (vertical ? 0 : 1) : BaseClusterTopology.MAX_STRUCTURAL_JUMP;
        int minimumDy = volume ? face.getStepY() : -BaseClusterTopology.MAX_STRUCTURAL_DROP;
        int maximumDy = volume ? face.getStepY() : BaseClusterTopology.MAX_STRUCTURAL_STEP;
        Direction[] directions = vertical && !volume ? HORIZONTAL : new Direction[]{face};

        long sourceBounds = sourceComponent < 0 ? FULL_BOUNDS : source.metadata(sourceComponent);
        long targetBounds = targetComponent < 0 ? target.portBits().legalBounds : target.metadata(targetComponent);
        if (targetBounds == EMPTY_BOUNDS) return false;
        int normalAxis = vertical ? 1 : face.getAxis() == Direction.Axis.X ? 0 : 2;
        int rowAxis = vertical ? 2 : 1;
        int uAxis = normalAxis == 0 ? 2 : 0;
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int firstInset = positive ? 15 - maximum(sourceBounds, normalAxis) : minimum(sourceBounds, normalAxis);
        int lastInset = Math.min(depth(face, volume) - 1,
                positive ? 15 - minimum(sourceBounds, normalAxis) : maximum(sourceBounds, normalAxis));
        int targetNormalMin = minimum(targetBounds, normalAxis), targetNormalMax = maximum(targetBounds, normalAxis);
        int targetRowMin = minimum(targetBounds, rowAxis), targetRowMax = maximum(targetBounds, rowAxis);
        int targetUMin = minimum(targetBounds, uAxis), targetUMax = maximum(targetBounds, uAxis);
        int firstRow = Math.max(minimum(sourceBounds, rowAxis),
                vertical ? targetRowMin - maximumDistance : targetRowMin - maximumDy + sectionYDelta);
        int lastRow = Math.min(maximum(sourceBounds, rowAxis),
                vertical ? targetRowMax + maximumDistance : targetRowMax - minimumDy + sectionYDelta);
        int sourceUMask = rangeMask(minimum(sourceBounds, uAxis), maximum(sourceBounds, uAxis));
        int fixedUMask = sourceUMask & rangeMask(targetUMin, targetUMax);
        if (firstInset > lastInset || firstRow > lastRow || !vertical && fixedUMask == 0) return false;

        for (int inset = firstInset; inset <= lastInset; inset++) {
            int firstDistance = volume && vertical ? 0 : vertical ? 1 : inset + 1;
            int lastDistance = maximumDistance;
            int firstDy = minimumDy, lastDy = maximumDy;
            if (vertical) {
                int sourceY = face == Direction.UP ? 15 - inset : inset;
                firstDy = Math.max(firstDy, targetNormalMin - sourceY + sectionYDelta);
                lastDy = Math.min(lastDy, targetNormalMax - sourceY + sectionYDelta);
            } else {
                // The target inset is measured from the opposite face: distance - inset - 1.
                firstDistance = Math.max(firstDistance,
                        (positive ? targetNormalMin : 15 - targetNormalMax) + inset + 1);
                lastDistance = Math.min(lastDistance,
                        (positive ? targetNormalMax : 15 - targetNormalMin) + inset + 1);
            }
            if (firstDistance > lastDistance || firstDy > lastDy) continue;
            for (int row = firstRow; row <= lastRow; row++) for (Direction direction : directions) {
                int rowFirstDy = vertical ? firstDy : Math.max(firstDy, targetRowMin - row + sectionYDelta);
                int rowLastDy = vertical ? lastDy : Math.min(lastDy, targetRowMax - row + sectionYDelta);
                for (int distance = firstDistance; distance <= lastDistance; distance++) {
                    int uMask = fixedUMask;
                    if (vertical) {
                        int targetRow = row + direction.getStepZ() * distance;
                        if (targetRow < targetRowMin || targetRow > targetRowMax) continue;
                        int offset = direction.getStepX() * distance;
                        uMask = sourceUMask & rangeMask(targetUMin - offset, targetUMax - offset);
                        if (uMask == 0) continue;
                    }
                    for (int dy = rowFirstDy; dy <= rowLastDy; dy++) {
                        int bits = source.portBits().candidates(target.portBits(), face, inset, row,
                                direction, distance, dy, sectionYDelta) & uMask;
                        while (bits != 0) {
                            int u = Integer.numberOfTrailingZeros(bits);
                            bits &= bits - 1;
                            if (sourceComponent < 0 && targetComponent < 0) return true;
                            int cell = SuperClusterTopology.faceCell(face, u, row, inset);
                            int x = BaseClusterTopology.x(cell), y = BaseClusterTopology.y(cell),
                                    z = BaseClusterTopology.z(cell);
                            if (sourceComponent >= 0 && source.componentAt(x, y, z) != sourceComponent) continue;
                            if (targetComponent >= 0 && target.componentAt(
                                    x + direction.getStepX() * distance - face.getStepX() * 16,
                                    y + dy - sectionYDelta,
                                    z + direction.getStepZ() * distance - face.getStepZ() * 16)
                                    != targetComponent) continue;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
