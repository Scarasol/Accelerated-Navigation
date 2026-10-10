package com.scarasol.acceleratednavigation.topology;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Build-local collision checks over the immutable facts owned by the two Base views. */
final class BoundaryGeometry {
    private record Rect(double minS, double minY, double maxS, double maxY) {}

    private final BaseClusterTopology source;
    private final BaseClusterTopology target;
    private final Long2ObjectOpenHashMap<SectionGeometry> sections = new Long2ObjectOpenHashMap<>();

    BoundaryGeometry(BaseClusterTopology source, BaseClusterTopology target) {
        this.source = source;
        this.target = target;
    }

    @Nullable
    private SectionGeometry at(int x, int y, int z) {
        int sx = Math.floorDiv(x, 16), sy = Math.floorDiv(y, 16), sz = Math.floorDiv(z, 16);
        long key = SectionPos.asLong(sx, sy, sz);
        if (sections.containsKey(key)) return sections.get(key);
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
        List<Rect> obstacles = new ArrayList<>();
        for (int cy = minY; cy <= maxY; cy++) for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                SectionGeometry geometry = at(cx, cy, cz);
                if (geometry == null) return false;
                SectionGeometry.Shape shape = geometry.shape(BaseClusterTopology.cellIndex(
                        Math.floorMod(cx, 16), Math.floorMod(cy, 16), Math.floorMod(cz, 16)));
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
                    obstacles.add(new Rect(a, aY, b, bY));
                }
            }
        }
        if (!pointClear(0, 0, obstacles) || !pointClear(distance, dy, obstacles)) return false;
        double up = Math.max(0, dy), down = Math.min(0, dy);
        if (segmentClear(0, 0, 0, up, obstacles)
                && segmentClear(0, up, distance, up, obstacles)
                && segmentClear(distance, up, distance, dy, obstacles)) return true;
        if (segmentClear(0, 0, 0, down, obstacles)
                && segmentClear(0, down, distance, down, obstacles)
                && segmentClear(distance, down, distance, dy, obstacles)) return true;
        for (Rect r : obstacles) {
            if (r.minY < lowY && r.maxY > highY && r.minS < distance && r.maxS > 0) return false;
        }
        if (blocksAtCut(obstacles, distance * .5, lowY, highY)) return false;

        TreeSet<Double> scuts = new TreeSet<>(), ycuts = new TreeSet<>();
        scuts.add(0.); scuts.add((double) distance);
        ycuts.add(0.); ycuts.add((double) dy); ycuts.add(lowY); ycuts.add(highY);
        for (Rect r : obstacles) {
            if (r.minS > 0 && r.minS < distance) scuts.add(r.minS);
            if (r.maxS > 0 && r.maxS < distance) scuts.add(r.maxS);
            if (r.minY > lowY && r.minY < highY) ycuts.add(r.minY);
            if (r.maxY > lowY && r.maxY < highY) ycuts.add(r.maxY);
        }
        double[] xs = scuts.stream().mapToDouble(Double::doubleValue).toArray();
        double[] ys = ycuts.stream().mapToDouble(Double::doubleValue).toArray();
        int count = xs.length * ys.length;
        int begin = Arrays.binarySearch(ys, 0.) * xs.length;
        int goal = Arrays.binarySearch(ys, (double) dy) * xs.length + xs.length - 1;
        boolean[] visited = new boolean[count];
        int[] queue = new int[count];
        visited[begin] = true; queue[0] = begin;
        int head = 0, tail = 1;
        while (head < tail) {
            int current = queue[head++];
            if (current == goal) return true;
            int ix = current % xs.length, iy = current / xs.length;
            for (int edge = 0; edge < 4; edge++) {
                int next = switch (edge) {
                    case 0 -> ix > 0 ? current - 1 : -1;
                    case 1 -> ix + 1 < xs.length ? current + 1 : -1;
                    case 2 -> iy > 0 ? current - xs.length : -1;
                    default -> iy + 1 < ys.length ? current + xs.length : -1;
                };
                if (next < 0 || visited[next]) continue;
                if (segmentClear(xs[ix], ys[iy], xs[next % xs.length], ys[next / xs.length], obstacles)) {
                    visited[next] = true; queue[tail++] = next;
                }
            }
        }
        return false;
    }

    private static boolean blocksAtCut(List<Rect> obstacles, double cut, double low, double high) {
        double covered = low;
        boolean changed;
        do {
            changed = false;
            for (Rect r : obstacles) {
                if (r.minS < cut && r.maxS > cut && r.minY < covered && r.maxY > covered) {
                    covered = r.maxY; changed = true;
                    if (covered > high) return true;
                }
            }
        } while (changed);
        return false;
    }

    private static boolean pointClear(double s, double y, List<Rect> obstacles) {
        for (Rect r : obstacles) if (s > r.minS && s < r.maxS && y > r.minY && y < r.maxY) return false;
        return true;
    }

    private static boolean segmentClear(double as, double ay, double bs, double by, List<Rect> obstacles) {
        if (!pointClear(as, ay, obstacles) || !pointClear(bs, by, obstacles)) return false;
        for (Rect r : obstacles) {
            if (as == bs) {
                if (as > r.minS && as < r.maxS && Math.max(ay, by) > r.minY
                        && Math.min(ay, by) < r.maxY) return false;
            } else if (ay > r.minY && ay < r.maxY && Math.max(as, bs) > r.minS
                    && Math.min(as, bs) < r.maxS) return false;
        }
        return true;
    }
}
