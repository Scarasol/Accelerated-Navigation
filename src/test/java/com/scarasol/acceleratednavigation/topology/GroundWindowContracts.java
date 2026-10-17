package com.scarasol.acceleratednavigation.topology;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

/** Runs unchanged against both frozen implementations and exports ordered, complete results. */
public final class GroundWindowContracts {
    private record Fixture(String name, MacroSearch.Corridor corridor,
                           MacroSearch.ComponentEndpoint destination,
                           BaseClusterTopology.TraversalProfile profile, BlockPos start,
                           List<MacroSearch.Connection> edges, BlockPos reach, int accuracy) {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("Preserve existing checks");
        var fixtures = fixtures();
        var results = new JsonArray();
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {},
                decision -> { throw new AssertionError("Window fixture requested facts"); });
        try {
            for (Fixture fixture : fixtures) {
                try (var window = capture(runtime, fixture).get(10, TimeUnit.SECONDS)) {
                    results.add(check(fixture, window));
                }
            }
            var concurrent = new ArrayList<CompletableFuture<GroundSegmentWindow>>();
            for (Fixture fixture : fixtures) concurrent.add(capture(runtime, fixture));
            for (int i = 0; i < fixtures.size(); i++) {
                try (var window = concurrent.get(i).get(10, TimeUnit.SECONDS)) {
                    require(results.get(i).equals(check(fixtures.get(i), window)),
                            "Concurrent window changed another window's coordinates");
                }
            }
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "Window workers did not stop");
        }
        var metrics = runtime.metrics();
        require(metrics.activeReferences() == 0 && metrics.logicalRequests() == 0
                && metrics.physicalSearches() == 0, "Window fixture leaked runtime ownership");
        var result = new JsonObject();
        result.addProperty("schema", "GROUND_WINDOW_CONTRACTS_V1");
        result.addProperty("passed", true);
        result.addProperty("fixtures", fixtures.size());
        result.addProperty("concurrentWindows", fixtures.size());
        result.add("results", results);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        System.out.println("GROUND_WINDOW_CHECKS_OK fixtures=" + fixtures.size()
                + " concurrent=" + fixtures.size());
    }

    private static CompletableFuture<GroundSegmentWindow> capture(TopologyWorkerRuntime runtime, Fixture fixture) {
        return GroundSegmentWindow.capture(runtime, fixture.corridor(), fixture.profile(),
                NavigationScheduler.Priority.ACTIVE, fixture.start(), fixture.edges(),
                fixture.destination(), fixture.reach(), fixture.accuracy());
    }

    private static JsonObject check(Fixture fixture, GroundSegmentWindow window) {
        require(window.current(), "Published window already closed");
        JsonObject result = new JsonObject();
        result.addProperty("name", fixture.name());
        result.add("candidates", coordinates(window.candidates));
        result.add("recovery", coordinates(window.nearbyCandidates()));
        var expectedSections = new LinkedHashSet<SectionPos>();
        expectedSections.add(SectionPos.of(fixture.start()));
        expectedSections.add(fixture.destination().section());
        for (var edge : fixture.edges()) {
            expectedSections.add(((MacroSearch.ComponentEndpoint) edge.from()).section());
            expectedSections.add(((MacroSearch.ComponentEndpoint) edge.to()).section());
        }
        require(window.sections().equals(expectedSections), "Window lost a section");
        require(new LinkedHashSet<>(window.candidates).size() == window.candidates.size(),
                "Duplicate published candidates");
        require(new LinkedHashSet<>(window.nearbyCandidates()).size() == window.nearbyCandidates().size(),
                "Duplicate recovery candidates");
        require(window.nearbyCandidates().containsAll(window.candidates), "Recovery lost original candidates");
        for (BlockPos point : window.candidates) {
            require(window.contains(point), "Candidate outside completed membership");
            if (fixture.reach() != null)
                require(point.distManhattan(fixture.reach()) <= fixture.accuracy(), "Reach target accuracy changed");
        }
        for (BlockPos point : window.nearbyCandidates())
            require(window.contains(point), "Recovery candidate outside completed membership");
        var membership = fixture.corridor().component(fixture.destination());
        SectionPos section = fixture.destination().section();
        int members = 0;
        for (int x = -1; x <= 16; x++) for (int y = -1; y <= 16; y++) for (int z = -1; z <= 16; z++) {
            BlockPos point = new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
            require(window.contains(point) == membership.contains(point), "Window membership changed");
            if (window.contains(point)) members++;
        }
        result.addProperty("members", members);
        immutable(window.candidates);
        immutable(window.nearbyCandidates());
        window.close();
        window.close();
        require(!window.current() && !window.contains(fixture.destination().anchor()),
                "Closed window retained usable membership");
        require(result.get("candidates").equals(coordinates(window.candidates))
                && result.get("recovery").equals(coordinates(window.nearbyCandidates())),
                "Closing changed an immutable result");
        return result;
    }

    private static JsonArray coordinates(List<BlockPos> points) {
        JsonArray result = new JsonArray();
        for (BlockPos point : points) {
            require(!(point instanceof BlockPos.MutableBlockPos), "Mutable cursor escaped into published results");
            JsonArray row = new JsonArray();
            row.add(point.getX()); row.add(point.getY()); row.add(point.getZ());
            result.add(row);
        }
        return result;
    }

    private static void immutable(List<BlockPos> points) {
        try {
            points.add(BlockPos.ZERO);
            throw new AssertionError("Published candidate list is mutable");
        } catch (UnsupportedOperationException expected) {}
    }

    private static List<Fixture> fixtures() throws Exception {
        var result = new ArrayList<Fixture>();
        var section = SectionPos.of(-2, -1, -3);
        for (boolean sparse : new boolean[]{false, true}) {
            var source = endpoint(section, sparse, 1);
            for (int drop : new int[]{0, 3, 4}) {
                var profile = new BaseClusterTopology.TraversalProfile(.6F, 1.95F, 1, 0, drop, false, false);
                var edge = new MacroSearch.Connection(1, source.getKey(), source.getKey(), 0,
                        new MacroSearch.LocalTransition());
                var corridor = new MacroSearch.Corridor(List.of(source.getKey(), source.getKey()), List.of(edge), 0,
                        Map.of(source.getKey(), source.getValue()));
                result.add(new Fixture("local-" + sparse + "-" + drop, corridor, source.getKey(), profile,
                        source.getKey().anchor(), List.of(edge), null, 0));
            }
            for (int accuracy : new int[]{0, 1, 3}) {
                var corridor = new MacroSearch.Corridor(List.of(source.getKey()), List.of(), 0,
                        Map.of(source.getKey(), source.getValue()));
                result.add(new Fixture("reach-" + sparse + "-" + accuracy, corridor, source.getKey(),
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, source.getKey().anchor(),
                        List.of(), source.getKey().anchor().offset(1, 1, 1), accuracy));
            }
            var empty = new MacroSearch.Corridor(List.of(source.getKey()), List.of(), 0,
                    Map.of(source.getKey(), source.getValue()));
            result.add(new Fixture("empty-" + sparse, empty, source.getKey(),
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, source.getKey().anchor(), List.of(), null, 0));
            for (Direction face : Direction.values()) {
                var target = endpoint(SectionPos.of(section.x() + face.getStepX(),
                        section.y() + face.getStepY(), section.z() + face.getStepZ()), sparse, 2);
                var transition = MacroSearch.BoundaryTransition.class.getDeclaredConstructor(
                        Direction.class, short[].class, long[].class);
                transition.setAccessible(true);
                short[] descriptors = new short[3];
                long[] masks = new long[12];
                for (int band = 0; band < 3; band++) {
                    int distance = face.getAxis().isHorizontal() ? 1 : 0;
                    int direction = distance == 0 ? 0 : face.get2DDataValue();
                    int shift = face == Direction.UP ? 1 : face == Direction.DOWN ? -1 : band - 1;
                    descriptors[band] = (short) (direction << 2 | distance << 4 | (shift + 4) << 6);
                    for (int bit = 0; bit < 256; bit++)
                        if (band != 1 || bit % 3 != 0) masks[band * 4 + (bit >>> 6)] |= 1L << bit;
                }
                var edge = new MacroSearch.Connection(2, source.getKey(), target.getKey(), 1,
                        transition.newInstance(face, descriptors, masks));
                var corridor = new MacroSearch.Corridor(List.of(source.getKey(), target.getKey()), List.of(edge), 1,
                        Map.of(source.getKey(), source.getValue(), target.getKey(), target.getValue()));
                result.add(new Fixture("boundary-" + sparse + "-" + face, corridor, target.getKey(),
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, source.getKey().anchor(),
                        List.of(edge), null, 0));
            }
        }
        return result;
    }

    private static Map.Entry<MacroSearch.ComponentEndpoint, MacroSearch.ComponentSnapshot> endpoint(
            SectionPos section, boolean sparse, long id) {
        byte[] flags = new byte[4096];
        Arrays.fill(flags, (byte) BaseClusterTopology.VOLUME_OPEN);
        if (sparse) for (int cell = 0; cell < 4096; cell++)
            if (BaseClusterTopology.y(cell) == 7 || (cell % 31 == 1)) flags[cell] = 0;
        var geometry = new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.VOLUME,
                1, 1, .3F, .4F, false, false);
        var topology = BaseClusterTopology.build(section, 1, BaseClusterTopology.BuildInput.center(
                BaseClusterTopology.PackedFacts.fromCells(flags)), geometry, new BaseClusterTopology.BuildScratch());
        var anchor = new BlockPos(section.minBlockX(), section.minBlockY(), section.minBlockZ());
        var endpoint = new MacroSearch.ComponentEndpoint(id, anchor, topology.signature(), section,
                BaseClusterTopology.Channel.VOLUME, topology.componentAt(0, 0, 0));
        return Map.entry(endpoint, new MacroSearch.ComponentSnapshot(endpoint, topology));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
