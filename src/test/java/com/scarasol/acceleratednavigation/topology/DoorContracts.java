package com.scarasol.acceleratednavigation.topology;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.mojang.brigadier.CommandDispatcher;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Direct production-type controls; no world, performance probe or synthetic production API. */
final class DoorContracts {
    private static final BaseClusterTopology.Channel GROUND = BaseClusterTopology.Channel.GROUND;
    private static final BaseClusterTopology.TraversalProfile CLOSED = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND;
    private static final BaseClusterTopology.TraversalProfile OPEN =
            new BaseClusterTopology.TraversalProfile(.6F, 1.95F, 1, 0, 3, false, true);
    private static final Method SAMPLE;
    static {
        try {
            SAMPLE = TopologyService.class.getDeclaredMethod("collisionSample", BlockState.class, BlockGetter.class, BlockPos.class);
            SAMPLE.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static int controls;

    static void run() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        nativeFactsAndCodec();
        localRules();
        boundaryAndParent();
        runtimeIdentityAndCleanup();
        commandArguments();
        System.out.println("Door production controls: " + controls + " passed");
    }

    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        controls++;
        System.out.println("Door control: " + name + " passed");
    }

    private static BlockState door(boolean open, boolean iron, boolean upper) {
        return (iron ? Blocks.IRON_DOOR : Blocks.OAK_DOOR).defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }

    private static Object sample(BlockState state, BlockGetter getter, BlockPos position) throws Exception {
        return SAMPLE.invoke(null, state, getter, position);
    }

    private static SectionGeometry.Cell geometry(BlockState state) throws Exception {
        Object value = sample(state, new Grid(), BlockPos.ZERO);
        Method accessor = value.getClass().getDeclaredMethod("geometry"); accessor.setAccessible(true);
        return (SectionGeometry.Cell) accessor.invoke(value);
    }

    private static byte[] encode(SectionGeometry geometry) throws Exception {
        var bytes = new ByteArrayOutputStream();
        geometry.write(new DataOutputStream(bytes), new byte[8192]);
        return bytes.toByteArray();
    }

    private static void nativeFactsAndCodec() throws Exception {
        var wood = geometry(door(false, false, false));
        var iron = geometry(door(false, true, false));
        var ordinary = new SectionGeometry.Cell(SectionGeometry.boxes(List.of(
                SectionGeometry.quantized(0, 0, 0, 3 / 16., 1, 1))).shape());
        check("native wooden/iron/non-door semantics", wood.shape().openableDoor()
                && iron.shape().isDoor() && !iron.shape().openableDoor()
                && !geometry(Blocks.GLASS_PANE.defaultBlockState()).shape().isDoor()
                && !geometry(Blocks.OAK_TRAPDOOR.defaultBlockState()).shape().isDoor());
        check("same boxes keep distinct semantic identity", wood.shape().coordinateCount() == iron.shape().coordinateCount()
                && wood.fingerprint() != iron.fingerprint());
        var cells = new SectionGeometry.Cell[4096]; Arrays.fill(cells, SectionGeometry.empty());
        cells[0] = wood; cells[1] = iron; cells[2] = ordinary;
        var boxes = new ArrayList<SectionGeometry.Box>();
        for (int i = 0; i < 64; i++) boxes.add(SectionGeometry.quantized(0, 0, 0, .5, 1, 1));
        cells[3] = SectionGeometry.boxes(boxes);
        var original = SectionGeometry.fromCells(cells);
        byte[] bytes = encode(original);
        var restored = SectionGeometry.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        check("palette roundtrip including normal 64 boxes", restored.contentEquals(original)
                && restored.fingerprint() == original.fingerprint() && restored.shape(3).coordinateCount() == 384);
        var unmarked = cells.clone();
        unmarked[0] = new SectionGeometry.Cell(SectionGeometry.boxes(shapeBoxes(wood.shape())).shape());
        unmarked[1] = new SectionGeometry.Cell(SectionGeometry.boxes(shapeBoxes(iron.shape())).shape());
        var oneDoor = new SectionGeometry.Cell[4096]; Arrays.fill(oneDoor, SectionGeometry.empty()); oneDoor[0] = wood;
        var oneShape = oneDoor.clone(); oneShape[0] = unmarked[0];
        check("door tag reuses existing palette header", encode(SectionGeometry.fromCells(oneDoor)).length
                == encode(SectionGeometry.fromCells(oneShape)).length);
        check("same geometry with distinct semantics remains separate", original.shape(0).openableDoor()
                && !original.shape(1).openableDoor() && !original.shape(2).isDoor());
        byte[] broken = bytes.clone(); broken[4] = (byte) 128;
        boolean rejected = false;
        try { SectionGeometry.read(new DataInputStream(new ByteArrayInputStream(broken))); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check("invalid door box count rejected", rejected);
        var grid = new Grid(); grid.set(8, 4, 8, door(false, false, false));
        long before = TopologyService.sampleColumnFacts(grid, new BlockPos(8, 4, 8));
        grid.set(8, 4, 8, door(false, true, false));
        long after = TopologyService.sampleColumnFacts(grid, new BlockPos(8, 4, 8));
        check("incremental identity observes same-shape door material change", (before & 0xffff) == (after & 0xffff) && before != after);
        var packed = BaseClusterTopology.PackedFacts.fromCells(new byte[4096], original);
        var payload = new ByteArrayOutputStream(); var output = new DataOutputStream(payload);
        output.writeInt(0x414e544f); output.writeInt(7); output.writeInt(7); output.writeInt(1);
        output.writeInt(0); output.writeLong(1); output.write(packed.bytes()); original.write(output, new byte[8192]);
        var decoder = TopologyStore.class.getDeclaredMethod("decodeChunk", DataInputStream.class); decoder.setAccessible(true);
        Object image = decoder.invoke(null, new DataInputStream(new ByteArrayInputStream(payload.toByteArray())));
        Method sections = image.getClass().getDeclaredMethod("sections"); sections.setAccessible(true);
        var record = (TopologyStore.SectionRecord) ((Map<?, ?>) sections.invoke(image)).get(0);
        check("Store current format consumes semantic geometry", record.facts().contentEquals(packed));
        byte[] obsolete = payload.toByteArray(); obsolete[7] = 6;
        rejected = false;
        try { decoder.invoke(null, new DataInputStream(new ByteArrayInputStream(obsolete))); }
        catch (InvocationTargetException failure) { rejected = failure.getCause() instanceof IllegalArgumentException; }
        check("Store obsolete format rejected", rejected);
    }

    private static List<SectionGeometry.Box> shapeBoxes(SectionGeometry.Shape shape) {
        var boxes = new ArrayList<SectionGeometry.Box>();
        for (int i = 0; i < shape.coordinateCount(); i += 6) boxes.add(new SectionGeometry.Box(
                shape.coordinate(i), shape.coordinate(i + 1), shape.coordinate(i + 2),
                shape.coordinate(i + 3), shape.coordinate(i + 4), shape.coordinate(i + 5)));
        return boxes;
    }

    private static Grid room(boolean opened, boolean iron, boolean low) {
        var grid = new Grid(); Arrays.fill(grid.cells, Blocks.STONE.defaultBlockState());
        for (int x = 1; x <= 14; x++) for (int y = 4; y <= 5; y++) grid.set(x, y, 8, Blocks.AIR.defaultBlockState());
        grid.set(8, 4, 8, door(opened, iron, false));
        grid.set(8, 5, 8, low ? Blocks.STONE.defaultBlockState() : door(opened, iron, true));
        return grid;
    }

    private static BaseClusterTopology.PackedFacts facts(Grid grid) throws Exception {
        var cells = new SectionGeometry.Cell[4096]; var flags = new byte[4096];
        for (int i = 0; i < 4096; i++) {
            var pos = new BlockPos(BaseClusterTopology.x(i), BaseClusterTopology.y(i), BaseClusterTopology.z(i));
            Object value = sample(grid.getBlockState(pos), grid, pos);
            var g = value.getClass().getDeclaredMethod("geometry"); g.setAccessible(true);
            var f = value.getClass().getDeclaredMethod("flags"); f.setAccessible(true);
            cells[i] = (SectionGeometry.Cell) g.invoke(value);
            int bits = (int) f.invoke(value) & 15;
            if (BaseClusterTopology.y(i) > 0 && (bits & 1) != 0 && cells[i - 256].shape().coordinateCount() > 0) bits |= 2;
            flags[i] = (byte) bits;
        }
        return BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(cells));
    }

    private static BaseClusterTopology graph(BaseClusterTopology.PackedFacts facts, boolean open) {
        return BaseClusterTopology.build(SectionPos.of(0, 0, 0), 1, BaseClusterTopology.BuildInput.center(facts),
                (open ? OPEN : CLOSED).geometry(GROUND), new BaseClusterTopology.BuildScratch());
    }

    private static boolean route(BaseClusterTopology graph) {
        int from = graph.componentAt(4, 4, 8), to = graph.componentAt(12, 4, 8);
        if (from < 0 || to < 0) return false;
        boolean[] seen = new boolean[graph.componentCount()]; var queue = new ArrayDeque<Integer>(); queue.add(from); seen[from] = true;
        while (!queue.isEmpty()) {
            int current = queue.remove(); if (current == to) return true;
            for (int edge = graph.localEdgeStart(current); edge < graph.localEdgeEnd(current); edge++) {
                int next = graph.localEdgeTarget(edge);
                if (!seen[next] && graph.localEdgeSupports(edge, CLOSED.movement(GROUND))) { seen[next] = true; queue.add(next); }
            }
        }
        return false;
    }

    private static void localRules() throws Exception {
        var wood = facts(room(false, false, false));
        check("closed wooden door capability off/on", !route(graph(wood, false)) && route(graph(wood, true)));
        check("already open wooden door with capability off", route(graph(facts(room(true, false, false)), false)));
        var iron = facts(room(false, true, false));
        check("iron door rejected with either capability", !route(graph(iron, false)) && !route(graph(iron, true)));
        check("door frame still blocks", !route(graph(facts(room(false, false, true)), true)));
        var support = new Grid(); support.set(8, 4, 8, door(false, false, false)); support.set(8, 5, 8, door(false, false, true));
        var unsupported = graph(facts(support), true);
        check("ignored door is not floor", unsupported.componentAt(8, 4, 8) < 0 && unsupported.componentAt(8, 6, 8) < 0);
        support.set(8, 3, 8, Blocks.STONE.defaultBlockState());
        var supported = graph(facts(support), true);
        check("real floor remains", supported.componentAt(8, 4, 8) >= 0 && supported.componentAt(8, 5, 8) < 0);
        var writer = new StringWriter(); try (var json = new JsonWriter(writer)) { supported.writeSnapshot(json); }
        check("export records enabled body capability", JsonParser.parseString(writer.toString()).getAsJsonObject()
                .getAsJsonObject("geometry").get("canOpenDoors").getAsBoolean());
    }

    private static void boundaryAndParent() throws Exception {
        var left = new Grid(); var right = new Grid();
        Arrays.fill(left.cells, Blocks.STONE.defaultBlockState()); Arrays.fill(right.cells, Blocks.STONE.defaultBlockState());
        for (int y = 4; y <= 5; y++) {
            for (int x = 1; x <= 15; x++) left.set(x, y, 8, Blocks.AIR.defaultBlockState());
            for (int x = 0; x <= 14; x++) right.set(x, y, 8, Blocks.AIR.defaultBlockState());
            left.set(15, y, 8, door(false, false, y == 5).setValue(DoorBlock.FACING, Direction.WEST));
        }
        var a = facts(left); var b = facts(right); var west = SectionPos.of(0, 0, 0); var east = SectionPos.of(1, 0, 0);
        for (boolean ability : new boolean[]{false, true}) {
            var body = (ability ? OPEN : CLOSED).geometry(GROUND);
            var from = BaseClusterTopology.build(west, 1, neighbor(a, b, 1), body, new BaseClusterTopology.BuildScratch());
            var to = BaseClusterTopology.build(east, 1, neighbor(b, a, -1), body, new BaseClusterTopology.BuildScratch());
            var links = SuperClusterTopology.boundaryLinks(from, to, Direction.EAST);
            int c = from.componentAt(15, 4, 8), t = to.componentAt(0, 4, 8); boolean found = false;
            if (c >= 0 && t >= 0) for (int edge = links.edgeStart(c); edge < links.edgeEnd(c); edge++)
                if (links.targetComponent(edge) == t && links.supports(edge, CLOSED.movement(GROUND))) found = true;
            check("cross-section doorway capability " + ability, found == ability);
            var children = new BaseClusterTopology[8]; var sections = SuperClusterTopology.childSections(west);
            var sealed = new Grid(); Arrays.fill(sealed.cells, Blocks.STONE.defaultBlockState()); var sealedFacts = facts(sealed);
            for (int i = 0; i < 8; i++) children[i] = BaseClusterTopology.build(sections.get(i), 1,
                    BaseClusterTopology.BuildInput.center(sealedFacts), body, new BaseClusterTopology.BuildScratch());
            children[sections.indexOf(west)] = from; children[sections.indexOf(east)] = to;
            var parent = SuperClusterTopology.build(west, children, body, CLOSED.movement(GROUND), new BaseClusterTopology.BuildScratch());
            check("parent internal doorway capability " + ability, ability
                    ? parent.aggregateId(west, c) == parent.aggregateId(east, t)
                    : parent.aggregateId(west, from.componentAt(14, 4, 8)) != parent.aggregateId(east, t));
        }
    }

    private static BaseClusterTopology.BuildInput neighbor(BaseClusterTopology.PackedFacts center,
                                                            BaseClusterTopology.PackedFacts neighbor, int dx) {
        return new BaseClusterTopology.BuildInput(center, new byte[]{(byte) BaseClusterTopology.haloIndex(dx, 0, 0)},
                new BaseClusterTopology.PackedFacts[]{neighbor}, new long[]{1}, new long[]{neighbor.fingerprint()});
    }

    private static void runtimeIdentityAndCleanup() throws Exception {
        var factory = net.minecraft.resources.ResourceKey.class.getDeclaredMethod("create",
                net.minecraft.resources.ResourceLocation.class, net.minecraft.resources.ResourceLocation.class); factory.setAccessible(true);
        @SuppressWarnings("unchecked") var dimension = (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>) factory.invoke(null,
                new net.minecraft.resources.ResourceLocation("minecraft:dimension"), new net.minecraft.resources.ResourceLocation("navigation:door-integration"));
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {}, decision -> { throw new AssertionError("unexpected persistence"); });
        try {
            var wood = facts(room(false, false, false));
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
                publish(runtime, dimension, SectionPos.of(x, y, z), 1, (x | y | z) == 0 ? wood : BaseClusterTopology.PackedFacts.allAir());
            for (boolean ability : new boolean[]{false, true, false, true}) check("runtime alternating capability " + ability,
                    (query(runtime, dimension, ability).corridor() != null) == ability);
            publish(runtime, dimension, SectionPos.of(0, 0, 0), 2, facts(room(false, true, false)));
            check("semantic update invalidates enabled query", query(runtime, dimension, true).corridor() == null);
        } finally { check("workers stop", runtime.awaitStopped(10, TimeUnit.SECONDS)); }
        var metrics = runtime.metrics();
        check("all door query resources released", metrics.activeReferences() == 0 && metrics.liveSearchDependencies() == 0
                && metrics.logicalRequests() == 0 && metrics.physicalSearches() == 0 && metrics.baseCache().retainedBytes() == 0);
    }

    private static TopologyWorkerRuntime.WorkerResult query(TopologyWorkerRuntime runtime,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, boolean open) throws Exception {
        return runtime.requestMacroQuery(dimension, new BlockPos(4, 4, 8), new BlockPos(12, 4, 8), GROUND,
                open ? OPEN : CLOSED, NavigationScheduler.Priority.PLAYER_PURSUIT).future().get(10, TimeUnit.SECONDS);
    }

    private static void publish(TopologyWorkerRuntime runtime,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, SectionPos section,
            long revision, BaseClusterTopology.PackedFacts facts) {
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(new TopologyWorkerRuntime.ClusterKey(dimension, section),
                1, 0, revision, TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of()));
    }

    private static void commandArguments() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var event = new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null);
        var type = Class.forName("com.scarasol.acceleratednavigation.NavigationCommand");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var register = type.getDeclaredMethod("register", RegisterCommandsEvent.class); register.setAccessible(true);
        register.invoke(constructor.newInstance(), event);
        var detailed = dispatcher.getRoot().getChild("navigation").getChild("start").getChild("goal").getChild("detailed");
        check("detailed and door command arguments remain independent", detailed.getCommand() != null
                && detailed.getChild("canOpenDoors") != null && detailed.getChild("canOpenDoors").getCommand() != null);
    }

    private static final class Grid implements BlockGetter {
        private final BlockState[] cells = new BlockState[4096];
        private Grid() { Arrays.fill(cells, Blocks.AIR.defaultBlockState()); }
        private void set(int x, int y, int z, BlockState state) { cells[BaseClusterTopology.cellIndex(x, y, z)] = state; }
        @Override public BlockState getBlockState(BlockPos p) {
            if (p.getX() < 0 || p.getX() > 15 || p.getY() < 0 || p.getY() > 15 || p.getZ() < 0 || p.getZ() > 15)
                return Blocks.AIR.defaultBlockState();
            return cells[BaseClusterTopology.cellIndex(p.getX(), p.getY(), p.getZ())];
        }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
        @Override public int getHeight() { return 16; }
        @Override public int getMinBuildHeight() { return 0; }
    }
}
