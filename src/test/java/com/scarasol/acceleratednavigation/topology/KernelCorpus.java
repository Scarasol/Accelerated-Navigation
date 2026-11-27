package com.scarasol.acceleratednavigation.topology;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.minecraft.core.Direction;

final class KernelCorpus {
    record Query(int x, int y, int z, Direction direction, int distance, int dy) {
        boolean run(BoundaryGeometry collision, BaseClusterTopology.GeometryKey body) {
            return collision.open(body, x, y, z, direction, distance, dy);
        }
    }
    record BoundarySet(String name, BaseClusterTopology.BuildInput input,
                       BaseClusterTopology.GeometryKey body, Query[] queries) {}
    static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    static BaseClusterTopology.GeometryKey body(String name) {
        return switch (name) {
            case "small" -> Fixtures.body(.6F, 1.95F, false, false);
            case "wide" -> Fixtures.body(1.4F, 2.9F, false, true);
            case "tiny" -> Fixtures.body(.125F, .5F, false, false);
            case "volume" -> new BaseClusterTopology.TraversalProfile(.9F, 1.4F, 1, 3, 4, true, true)
                    .geometry(BaseClusterTopology.Channel.VOLUME);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static List<BaseClusterTopology.GeometryKey> bodies() {
        return List.of(body("small"), body("wide"), body("volume"), body("tiny"),
                Fixtures.body(.6F, 1.95F, true, true),
                Fixtures.body(1.F, 1.F, false, false),
                Fixtures.body(2.F, 2.F, true, false),
                Fixtures.body(3.F, 3.F, false, true));
    }

    static BaseClusterTopology.BuildInput fullHalo(BaseClusterTopology.PackedFacts facts) {
        var halo = new TreeMap<Integer, BaseClusterTopology.PackedFacts>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) if ((dx | dy | dz) != 0)
                halo.put(BaseClusterTopology.haloIndex(dx, dy, dz), facts);
        return Fixtures.input(facts, halo);
    }

    static List<Fixtures.Fixture> fixtures(Path city) throws Exception {
        var rows = new ArrayList<>(Fixtures.all(city));
        byte[] unknown = new byte[4096];
        Arrays.fill(unknown, (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.EXACT_REQUIRED));
        rows.add(Fixtures.fromFlags("all-unknown", unknown));
        rows.add(Fixtures.fromFlags("all-solid", new byte[4096]));
        rows.add(new Fixtures.Fixture("all-air", BaseClusterTopology.BuildInput.center(
                BaseClusterTopology.PackedFacts.allAir())));
        for (int seed = 0; seed < 12; seed++) {
            var facts = randomFacts(2026101500L + seed);
            rows.add(new Fixtures.Fixture("partial-" + seed,
                    seed % 2 == 0 ? fullHalo(facts) : Fixtures.input(facts, Map.of(
                            BaseClusterTopology.haloIndex(-1, 0, 0), facts,
                            BaseClusterTopology.haloIndex(0, -1, 0), facts))));
        }
        rows.add(new Fixtures.Fixture("flat-full-halo", fullHalo(
                BaseClusterTopology.PackedFacts.fromCells(Fixtures.flat()))));
        rows.add(new Fixtures.Fixture("door-full-halo", fullHalo(
                Fixtures.door("door", true, false).input().center())));
        return rows;
    }

    static BaseClusterTopology.PackedFacts randomFacts(long seed) {
        var random = new Random(seed);
        byte[] flags = Fixtures.flat();
        var shapes = new SectionGeometry.Cell[4096];
        for (int i = 0; i < 4096; i++) {
            shapes[i] = flags[i] == 0 ? SectionGeometry.fullBlock() : SectionGeometry.empty();
        }
        var slab = SectionGeometry.boxes(List.of(SectionGeometry.quantized(0, 0, 0, 1, .5, 1)));
        var post = SectionGeometry.boxes(List.of(SectionGeometry.quantized(.375, 0, .375, .625, 1, .625)));
        var panel = SectionGeometry.boxes(List.of(SectionGeometry.quantized(0, 0, 0, .1875, 1, 1)));
        var multi = SectionGeometry.boxes(List.of(
                SectionGeometry.quantized(0, 0, 0, .25, 1, .25),
                SectionGeometry.quantized(.75, 0, .75, 1, 1, 1)));
        var options = new SectionGeometry.Cell[]{slab, post, multi, SectionGeometry.unknown(),
                SectionGeometry.door(panel, true), SectionGeometry.door(panel, false)};
        for (int i = 0; i < 320; i++) {
            int cell = random.nextInt(4096);
            shapes[cell] = options[random.nextInt(options.length)];
            flags[cell] = (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.EXACT_REQUIRED
                    | (random.nextBoolean() ? BaseClusterTopology.GROUND_OPEN : 0)
                    | (random.nextInt(8) == 0 ? BaseClusterTopology.FLUID : 0));
        }
        return BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(shapes));
    }

    static Fixtures.Fixture select(List<Fixtures.Fixture> fixtures, String name) {
        if (name.equals("city")) {
            return fixtures.stream().filter(f -> f.name().startsWith("city-column-"))
                    .max(java.util.Comparator.comparingInt(f -> mixedCells(f.input().center())))
                    .orElseThrow();
        }
        String key = switch (name) {
            case "flat" -> "flat";
            case "halo" -> "flat-full-halo";
            case "doors" -> "door-full-halo";
            case "partial" -> "partial-0";
            default -> throw new IllegalArgumentException(name);
        };
        return fixtures.stream().filter(f -> f.name().equals(key)).findFirst().orElseThrow();
    }

    private static int mixedCells(BaseClusterTopology.PackedFacts facts) {
        int count = 0;
        for (int cell = 0; cell < 4096; cell++) if (facts.flags(cell) != 0
                && facts.flags(cell) != BaseClusterTopology.VOLUME_OPEN) count++;
        return count;
    }

    static Query[] queries(long seed, int count) {
        var random = new Random(seed);
        var rows = new Query[count];
        for (int i = 0; i < count; i++) {
            int x = random.nextInt(20) - 2, z = random.nextInt(20) - 2;
            int y = random.nextInt(20) - 2;
            if (i % 3 == 0) { x = 5 + random.nextInt(6); y = 1; z = 5 + random.nextInt(6); }
            rows[i] = new Query(x, y, z, HORIZONTAL[i & 3], i % 4,
                    new int[]{0, 1, -1, -3, -4}[i % 5]);
        }
        return rows;
    }

    static BoundarySet boundarySet(String name) {
        var shapes = new SectionGeometry.Cell[4096];
        Arrays.fill(shapes, SectionGeometry.empty());
        byte[] flags = new byte[4096];
        Arrays.fill(flags, (byte) BaseClusterTopology.VOLUME_OPEN);
        var body = body("tiny");
        Query[] queries = new Query[64];
        if (name.equals("zigzag") || name.equals("grid-blocked")) {
            if (name.equals("zigzag")) {
                shapes[Fixtures.cell(6, 1, 7)] = SectionGeometry.boxes(List.of(
                        SectionGeometry.quantized(0, 0, 0, .25, .375, 1)));
                shapes[Fixtures.cell(7, 2, 7)] = SectionGeometry.boxes(List.of(
                        SectionGeometry.quantized(.5, .125, 0, .75, .5, 1)));
            } else {
                shapes[Fixtures.cell(6, 1, 7)] = SectionGeometry.boxes(List.of(
                        SectionGeometry.quantized(0, 0, 0, .5, .75, 1)));
                shapes[Fixtures.cell(6, 2, 7)] = SectionGeometry.boxes(List.of(
                        SectionGeometry.quantized(.375, 0, 0, .75, .5, 1)));
            }
            Arrays.fill(queries, new Query(5, 1, 7, Direction.EAST, 3, 0));
        } else if (name.equals("door")) {
            shapes[Fixtures.cell(6, 1, 7)] = SectionGeometry.door(SectionGeometry.boxes(List.of(
                    SectionGeometry.quantized(0, 0, 0, .1875, 1, 1))), true);
            shapes[Fixtures.cell(6, 2, 7)] = shapes[Fixtures.cell(6, 1, 7)];
            body = Fixtures.body(.6F, 1.95F, false, false);
            for (int i = 0; i < queries.length; i++)
                queries[i] = new Query(5, 1, i % 2 == 0 ? 7 : 8, Direction.EAST, 3, 0);
        } else if (name.equals("mixed")) {
            var facts = randomFacts(2026101500L);
            return new BoundarySet(name, fullHalo(facts), body("small"), queries(1001, 64));
        } else if (name.equals("open")) {
            for (int i = 0; i < queries.length; i++)
                queries[i] = new Query(5, 1 + i % 3, 5 + i % 5, HORIZONTAL[i & 3], 1 + i % 3, i % 2);
        } else throw new IllegalArgumentException(name);
        for (int i = 0; i < shapes.length; i++) if (shapes[i] != SectionGeometry.empty())
            flags[i] = (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.EXACT_REQUIRED);
        var facts = BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(shapes));
        return new BoundarySet(name, fullHalo(facts), body, queries);
    }
}
