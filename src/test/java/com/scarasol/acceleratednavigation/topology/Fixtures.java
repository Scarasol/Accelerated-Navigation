package com.scarasol.acceleratednavigation.topology;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.SectionPos;

final class Fixtures {
    record Fixture(String name, BaseClusterTopology.BuildInput input) {}
    static final SectionPos SECTION = SectionPos.of(0, 0, 0);
    static int cell(int x, int y, int z) { return BaseClusterTopology.cellIndex(x, y, z); }
    static BaseClusterTopology.GeometryKey body(float width, float height, boolean water, boolean doors) {
        return new BaseClusterTopology.TraversalProfile(width, height, 1, 3, 4, water, doors)
                .geometry(BaseClusterTopology.Channel.GROUND);
    }
    static BaseClusterTopology.BuildInput input(BaseClusterTopology.PackedFacts center,
                                                Map<Integer, BaseClusterTopology.PackedFacts> halo) {
        var entries = new TreeMap<>(halo);
        byte[] offsets = new byte[entries.size()];
        var facts = new BaseClusterTopology.PackedFacts[entries.size()];
        long[] revisions = new long[entries.size()], fingerprints = new long[entries.size()];
        int at = 0;
        for (var entry : entries.entrySet()) {
            offsets[at] = entry.getKey().byteValue(); facts[at] = entry.getValue();
            revisions[at] = 1; fingerprints[at++] = entry.getValue().fingerprint();
        }
        return new BaseClusterTopology.BuildInput(center, offsets, facts, revisions, fingerprints);
    }
    static byte[] flat() {
        byte[] flags = new byte[4096];
        Arrays.fill(flags, (byte) BaseClusterTopology.VOLUME_OPEN);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            flags[cell(x, 0, z)] = 0;
            flags[cell(x, 1, z)] |= BaseClusterTopology.GROUND_OPEN;
        }
        return flags;
    }
    static Fixture fromFlags(String name, byte[] flags) {
        return new Fixture(name, BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.fromCells(flags)));
    }
    static Fixture wall(String name, int gap, boolean water) {
        byte[] flags = flat();
        for (int y = 1; y < 16; y++) for (int z = 0; z < 16; z++) {
            if (z >= 7 && z < 7 + gap) continue;
            flags[cell(8, y, z)] = 0;
        }
        if (water) for (int y = 1; y < 3; y++) for (int z = 7; z < 7 + gap; z++)
            flags[cell(8, y, z)] |= BaseClusterTopology.FLUID;
        return fromFlags(name, flags);
    }
    static Fixture door(String name, boolean openable, boolean support) {
        return door(name, openable, support, false);
    }
    static Fixture door(String name, boolean openable, boolean support, boolean fullSpan) {
        byte[] flags = support ? flat() : wall("unused", 1, false).input().center().cells();
        SectionGeometry.Cell[] shapes = new SectionGeometry.Cell[4096];
        for (int i = 0; i < 4096; i++) shapes[i] =
                (flags[i] & BaseClusterTopology.VOLUME_OPEN) == 0 ? SectionGeometry.fullBlock() : SectionGeometry.empty();
        var panel = SectionGeometry.door(SectionGeometry.boxes(List.of(
                SectionGeometry.quantized(0, 0, 0, fullSpan ? 1 : .1875, 1, 1))), openable);
        for (int y = support ? 0 : 1; y <= (support ? 0 : 2); y++) {
            int at = cell(8, y, 7);
            flags[at] = (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.EXACT_REQUIRED
                    | (support ? 0 : BaseClusterTopology.GROUND_OPEN));
            shapes[at] = panel;
        }
        var facts = BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(shapes));
        return new Fixture(name, BaseClusterTopology.BuildInput.center(facts));
    }
    static List<Fixture> all(Path city) throws Exception {
        List<Fixture> rows = new ArrayList<>();
        rows.add(fromFlags("flat", flat()));
        rows.add(wall("narrow-gap", 1, false));
        rows.add(wall("wide-gap", 4, false));
        rows.add(wall("water-only-link", 1, true));
        rows.add(door("openable-door", true, false));
        rows.add(door("locked-door", false, false));
        rows.add(door("standing-above-openable-door", true, true));
        rows.add(door("full-span-openable-door", true, false, true));
        rows.add(door("full-span-locked-door", false, false, true));
        byte[] ceiling = flat();
        for (int x = 7; x <= 9; x++) for (int z = 0; z < 16; z++) ceiling[cell(x, 3, z)] = 0;
        rows.add(fromFlags("low-ceiling", ceiling));
        byte[] step = flat();
        for (int x = 8; x < 16; x++) for (int z = 0; z < 16; z++) {
            step[cell(x, 1, z)] = 0; step[cell(x, 2, z)] |= BaseClusterTopology.GROUND_OPEN;
        }
        rows.add(fromFlags("one-step", step));
        byte[] drop = flat();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 16; z++) {
            for (int y = 1; y <= 3; y++) drop[cell(x, y, z)] = 0;
            drop[cell(x, 4, z)] |= BaseClusterTopology.GROUND_OPEN;
        }
        rows.add(fromFlags("three-drop", drop));
        byte[] jump = flat();
        for (int x = 7; x <= 8; x++) for (int z = 0; z < 16; z++) jump[cell(x, 1, z)] &=
                ~BaseClusterTopology.GROUND_OPEN;
        rows.add(fromFlags("two-cell-jump", jump));
        byte[] support = new byte[4096];
        rows.add(new Fixture("loaded-support-halo", input(BaseClusterTopology.PackedFacts.allAir(),
                Map.of(BaseClusterTopology.haloIndex(0, -1, 0), BaseClusterTopology.PackedFacts.fromCells(support)))));
        rows.add(fromFlags("absent-support-halo", new byte[4096]));
        for (int seed = 0; seed < 16; seed++) {
            Random random = new Random(20261014L + seed);
            byte[] flags = flat();
            for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
                if (random.nextInt(6) == 0) for (int y = 1; y <= 4; y++) flags[cell(x, y, z)] = 0;
                else if (random.nextInt(8) == 0) flags[cell(x, 1, z)] |= BaseClusterTopology.FLUID;
            }
            rows.add(fromFlags("seeded-" + seed, flags));
        }
        record Section(long revision, BaseClusterTopology.PackedFacts facts) {}
        Map<Integer, Section> sections = new TreeMap<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(city))) {
            if (in.readInt() != 0x414e544f || in.readInt() != 7
                    || in.readInt() != BaseClusterTopology.FACTS_ALGORITHM_VERSION) throw new IOException("city format");
            int count = in.readInt();
            if (count < 1 || count > 1024) throw new IOException("city count");
            for (int i = 0; i < count; i++) {
                int y = in.readInt(); long revision = in.readLong();
                byte[] data = in.readNBytes(2048);
                if (data.length != 2048) throw new IOException("city flags");
                sections.put(y, new Section(revision, BaseClusterTopology.PackedFacts.fromBytes(data, SectionGeometry.read(in))));
            }
            if (in.read() != -1) throw new IOException("city tail");
        }
        for (var entry : sections.entrySet()) {
            Map<Integer, BaseClusterTopology.PackedFacts> halo = new TreeMap<>();
            for (int dy : new int[]{-1, 1}) if (sections.containsKey(entry.getKey() + dy))
                halo.put(BaseClusterTopology.haloIndex(0, dy, 0), sections.get(entry.getKey() + dy).facts());
            rows.add(new Fixture("city-column-" + entry.getKey(), input(entry.getValue().facts(), halo)));
        }
        return rows;
    }
}
