package com.scarasol.acceleratednavigation.topology;

import java.util.Arrays;
import net.minecraft.core.Direction;

/** Geometry-normalized anchor rows; component identity remains in the topology. */
final class BoundaryPortBits {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private final long[] xRows;
    private final short[] zRows = new short[256];

    BoundaryPortBits(long[] legalMask) {
        xRows = Arrays.copyOf(legalMask, 64);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            int bits = rowX(y, z);
            while (bits != 0) {
                int x = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                zRows[(y << 4) | x] |= (short) (1 << z);
            }
        }
    }

    int retainedBytes() { return 32 + 16 + 64 * Long.BYTES + 16 + 256 * Short.BYTES; }

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

    static boolean mayLink(BaseClusterTopology source, int sourceComponent,
                           BaseClusterTopology target, int targetComponent, Direction face) {
        if (source.portBits() == null || target.portBits() == null) return true;
        boolean volume = source.geometry().channel() == BaseClusterTopology.Channel.VOLUME;
        boolean vertical = face.getAxis().isVertical();
        int sectionYDelta = (target.section().y() - source.section().y()) * 16;
        int maximumDistance = volume ? (vertical ? 0 : 1) : BaseClusterTopology.MAX_STRUCTURAL_JUMP;
        int minimumDy = volume ? face.getStepY() : -BaseClusterTopology.MAX_STRUCTURAL_DROP;
        int maximumDy = volume ? face.getStepY() : BaseClusterTopology.MAX_STRUCTURAL_STEP;
        Direction[] directions = vertical && !volume ? HORIZONTAL : new Direction[]{face};
        for (int inset = 0; inset < depth(face, volume); inset++) {
            int firstDistance = volume && vertical ? 0 : vertical ? 1 : inset + 1;
            for (int row = 0; row < 16; row++) for (Direction direction : directions) {
                for (int distance = firstDistance; distance <= maximumDistance; distance++) {
                    for (int dy = minimumDy; dy <= maximumDy; dy++) {
                        int bits = source.portBits().candidates(target.portBits(), face, inset, row,
                                direction, distance, dy, sectionYDelta);
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
