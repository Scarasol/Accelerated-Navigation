package com.scarasol.acceleratednavigation.topology;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** Build-local collision checks over the immutable facts owned by the two Base views. */
final class BoundaryGeometry {
    private static final double[] NO_DOUBLES = new double[0];
    private static final int[] NO_INTS = new int[0];
    private static final boolean[] NO_BOOLEANS = new boolean[0];

    private double[] rectangles = NO_DOUBLES;
    private double[] xs = NO_DOUBLES, ys = NO_DOUBLES;
    private boolean[] visited = NO_BOOLEANS;
    private int[] queue = NO_INTS;
    private int rectangleCoordinates;

    private final BaseClusterTopology source;
    private final BaseClusterTopology target;
    private final BaseClusterTopology.BuildInput local;
    private final Long2ObjectOpenHashMap<SectionGeometry> sections = new Long2ObjectOpenHashMap<>();

    BoundaryGeometry(BaseClusterTopology source, BaseClusterTopology target) {
        this.source = source;
        this.target = target;
        this.local = null;
    }

    BoundaryGeometry(BaseClusterTopology.BuildInput input) {
        this.source = null; this.target = null; this.local = input;
    }

    @Nullable
    private SectionGeometry at(int x, int y, int z) {
        int sx = Math.floorDiv(x, 16), sy = Math.floorDiv(y, 16), sz = Math.floorDiv(z, 16);
        if (local != null) {
            var fact = local.atSection(sx, sy, sz);
            return fact == null ? null : fact.geometry();
        }
        long key = SectionPos.asLong(sx, sy, sz);
        if (sections.containsKey(key)) return sections.get(key);
        return resolveSection(key, sx, sy, sz);
    }

    @Nullable
    private SectionGeometry resolveSection(long key, int sx, int sy, int sz) {
        int ax = sx - source.section().x(), ay = sy - source.section().y(), az = sz - source.section().z();
        int bx = sx - target.section().x(), by = sy - target.section().y(), bz = sz - target.section().z();
        BaseClusterTopology.PackedFacts a = source.boundaryFacts().atSection(ax, ay, az);
        BaseClusterTopology.PackedFacts b = target.boundaryFacts().atSection(bx, by, bz);
        if (a != null && b != null && (a.fingerprint() != b.fingerprint()
                || source.boundaryFacts().revisionAtSection(ax, ay, az, source.revision())
                != target.boundaryFacts().revisionAtSection(bx, by, bz, target.revision()))) {
            throw new IllegalArgumentException("boundary geometry snapshots have different identities");
        }
        SectionGeometry geometry = a != null ? a.geometry() : b != null ? b.geometry() : null;
        sections.put(key, geometry);
        return geometry;
    }

    boolean open(BaseClusterTopology.GeometryKey body, int x, int y, int z,
                 Direction direction, int distance, int dy) {
        boolean xAxis = direction.getAxis() == Direction.Axis.X || distance == 0;
        int sign = xAxis ? direction.getStepX() : direction.getStepZ();
        if (sign == 0) sign = 1;
        double half = body.width() * .5, height = body.height();
        double originS = (xAxis ? x : z) + .5, lateral = (xAxis ? z : x) + .5;
        double lowY = Math.min(0, dy), highY = Math.max(0, dy) + (distance > 1 ? 1 : 0);
        int minX = (int) Math.floor(Math.min(x + .5, x + .5 + direction.getStepX() * distance) - half);
        int maxX = (int) Math.ceil(Math.max(x + .5, x + .5 + direction.getStepX() * distance) + half) - 1;
        int minZ = (int) Math.floor(Math.min(z + .5, z + .5 + direction.getStepZ() * distance) - half);
        int maxZ = (int) Math.ceil(Math.max(z + .5, z + .5 + direction.getStepZ() * distance) + half) - 1;
        int minY = (int) Math.floor(y + lowY), maxY = (int) Math.ceil(y + highY + height) - 1;
        rectangleCoordinates = 0;
        for (int cy = minY; cy <= maxY; cy++) for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                SectionGeometry geometry = at(cx, cy, cz);
                if (geometry == null) return false;
                SectionGeometry.Shape shape = geometry.shape(BaseClusterTopology.cellIndex(
                        Math.floorMod(cx, 16), Math.floorMod(cy, 16), Math.floorMod(cz, 16)));
                if (body.canOpenDoors() && shape.openableDoor()) continue;
                for (int box = 0; box < shape.coordinateCount(); box += 6) {
                    double aS = (xAxis ? cx : cz) + shape.coordinate(box + (xAxis ? 0 : 2)) / 16.;
                    double bS = (xAxis ? cx : cz) + shape.coordinate(box + (xAxis ? 3 : 5)) / 16.;
                    double aL = (xAxis ? cz : cx) + shape.coordinate(box + (xAxis ? 2 : 0)) / 16.;
                    double bL = (xAxis ? cz : cx) + shape.coordinate(box + (xAxis ? 5 : 3)) / 16.;
                    if (aL >= lateral + half || bL <= lateral - half) continue;
                    double aY = cy + shape.coordinate(box + 1) / 16. - height - y;
                    double bY = cy + shape.coordinate(box + 4) / 16. - y;
                    double a = sign > 0 ? aS - originS - half : originS - bS - half;
                    double b = sign > 0 ? bS - originS + half : originS - aS + half;
                    if (a >= distance || b <= 0 || aY >= highY || bY <= lowY) continue;
                    if (rectangleCoordinates + 4 > rectangles.length) {
                        rectangles = Arrays.copyOf(rectangles, Math.max(32, rectangles.length * 2));
                    }
                    rectangles[rectangleCoordinates++] = a;
                    rectangles[rectangleCoordinates++] = aY;
                    rectangles[rectangleCoordinates++] = b;
                    rectangles[rectangleCoordinates++] = bY;
                }
            }
        }
        if (!pointClear(0, 0) || !pointClear(distance, dy)) return false;
        double up = Math.max(0, dy), down = Math.min(0, dy);
        if (segmentClear(0, 0, 0, up)
                && segmentClear(0, up, distance, up)
                && segmentClear(distance, up, distance, dy)) return true;
        if (segmentClear(0, 0, 0, down)
                && segmentClear(0, down, distance, down)
                && segmentClear(distance, down, distance, dy)) return true;
        for (int i = 0; i < rectangleCoordinates; i += 4) {
            if (rectangles[i + 1] < lowY && rectangles[i + 3] > highY
                    && rectangles[i] < distance && rectangles[i + 2] > 0) return false;
        }
        if (blocksAtCut(distance * .5, lowY, highY)) return false;

        int capacity = rectangleCoordinates / 2 + 4;
        if (xs.length < capacity) {
            xs = new double[capacity];
            ys = new double[capacity];
        }
        int nx = 0, ny = 0;
        xs[nx++] = 0.; xs[nx++] = distance;
        ys[ny++] = 0.; ys[ny++] = dy; ys[ny++] = lowY; ys[ny++] = highY;
        for (int i = 0; i < rectangleCoordinates; i += 4) {
            if (rectangles[i] > 0 && rectangles[i] < distance) xs[nx++] = rectangles[i];
            if (rectangles[i + 2] > 0 && rectangles[i + 2] < distance) xs[nx++] = rectangles[i + 2];
            if (rectangles[i + 1] > lowY && rectangles[i + 1] < highY) ys[ny++] = rectangles[i + 1];
            if (rectangles[i + 3] > lowY && rectangles[i + 3] < highY) ys[ny++] = rectangles[i + 3];
        }
        nx = sortUnique(xs, nx);
        ny = sortUnique(ys, ny);
        int count = nx * ny;
        int begin = Arrays.binarySearch(ys, 0, ny, 0.) * nx;
        int goal = Arrays.binarySearch(ys, 0, ny, (double) dy) * nx + nx - 1;
        if (visited.length < count) {
            visited = new boolean[count];
            queue = new int[count];
        } else Arrays.fill(visited, 0, count, false);
        visited[begin] = true; queue[0] = begin;
        int head = 0, tail = 1;
        while (head < tail) {
            int current = queue[head++];
            if (current == goal) return true;
            int ix = current % nx, iy = current / nx;
            for (int edge = 0; edge < 4; edge++) {
                int next = switch (edge) {
                    case 0 -> ix > 0 ? current - 1 : -1;
                    case 1 -> ix + 1 < nx ? current + 1 : -1;
                    case 2 -> iy > 0 ? current - nx : -1;
                    default -> iy + 1 < ny ? current + nx : -1;
                };
                if (next < 0 || visited[next]) continue;
                if (segmentClear(xs[ix], ys[iy], xs[next % nx], ys[next / nx])) {
                    visited[next] = true; queue[tail++] = next;
                }
            }
        }
        return false;
    }

    private static int sortUnique(double[] values, int count) {
        Arrays.sort(values, 0, count);
        int unique = 0;
        for (int i = 0; i < count; i++) {
            if (unique == 0 || Double.compare(values[i], values[unique - 1]) != 0) {
                values[unique++] = values[i];
            }
        }
        return unique;
    }

    private boolean blocksAtCut(double cut, double low, double high) {
        double covered = low;
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < rectangleCoordinates; i += 4) {
                if (rectangles[i] < cut && rectangles[i + 2] > cut
                        && rectangles[i + 1] < covered && rectangles[i + 3] > covered) {
                    covered = rectangles[i + 3]; changed = true;
                    if (covered > high) return true;
                }
            }
        } while (changed);
        return false;
    }

    private boolean pointClear(double s, double y) {
        for (int i = 0; i < rectangleCoordinates; i += 4) {
            if (s > rectangles[i] && s < rectangles[i + 2]
                    && y > rectangles[i + 1] && y < rectangles[i + 3]) return false;
        }
        return true;
    }

    private boolean segmentClear(double as, double ay, double bs, double by) {
        if (!pointClear(as, ay) || !pointClear(bs, by)) return false;
        for (int i = 0; i < rectangleCoordinates; i += 4) {
            if (as == bs) {
                if (as > rectangles[i] && as < rectangles[i + 2]
                        && Math.max(ay, by) > rectangles[i + 1]
                        && Math.min(ay, by) < rectangles[i + 3]) return false;
            } else if (ay > rectangles[i + 1] && ay < rectangles[i + 3]
                    && Math.max(as, bs) > rectangles[i]
                    && Math.min(as, bs) < rectangles[i + 2]) return false;
        }
        return true;
    }
}
