package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;

/** Deterministic production regressions without external corpora or frozen class overlays. */
public final class WorkerAlgorithmContracts {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private WorkerAlgorithmContracts() {}

    public static void main(String[] args) throws Exception {
        pendingDependenciesMatchStableSort();
        portRowsMatchIndependentCoordinates();
        boundaryOutputsMatchScalarEnumeration();
        collisionRowsSurviveSerialization();
        callbacksPreserveOwnershipAndReentrancy();
        queueHeadsMatchFullScan();
        queuedTasksCompleteUnderConcurrentMutation();
        CoreContracts.main(new String[]{"boundary-recovery"});
        CoreContracts.main(new String[]{"macro-commit"});
        CoreContracts.main(new String[]{"door"});
        System.out.println("Worker algorithm contracts: 7 groups passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pendingDependenciesMatchStableSort() throws Exception {
        var endpoint = new MacroSearch.ExactEndpoint(0, BlockPos.ZERO, 1);
        var nodeType = Class.forName(MacroSearch.class.getName() + "$SearchNode");
        var constructor = nodeType.getDeclaredConstructor(MacroSearch.class, MacroSearch.Endpoint.class);
        constructor.setAccessible(true);
        var random = new Random(2026101701L);
        float[] special = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0F, 0.0F, 1, 2};
        for (int sample = 0; sample < 80; sample++) {
            var search = new MacroSearch(new MacroSearch.Graph() {
                public MacroSearch.Endpoint start() { return endpoint; }
                public MacroSearch.Endpoint goal() { return endpoint; }
                public boolean revisionsValid() { return true; }
                public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {}
            }, 1, 10000);
            Map<MacroSearch.DependencyKey, Set<Node>> waiting =
                    (Map) field(MacroSearch.class, "waitingByDependency").get(search);
            int count = sample < 8 ? new int[]{0, 1, 15, 16, 17, 32, 64, 512}[sample] : random.nextInt(200);
            long nextNode = 1;
            for (int i = 0; i < count; i++) {
                var section = SectionPos.of(i / 24 - 3, i / 72 - 2, i / 216 - 1);
                var face = Direction.values()[(i / 4) % 6];
                int distance = i % 4 == 3 ? SuperClusterTopology.CHILDREN_PER_AXIS : 1;
                var target = SectionPos.of(section.x() + face.getStepX() * distance,
                        section.y() + face.getStepY() * distance, section.z() + face.getStepZ() * distance);
                var key = switch (i % 4) {
                    case 0 -> new MacroSearch.DependencyKey(MacroSearch.DependencyKind.BASE_CLUSTER, section);
                    case 1 -> MacroSearch.DependencyKey.superCluster(section);
                    case 2 -> MacroSearch.DependencyKey.baseBoundary(section, target, face);
                    default -> MacroSearch.DependencyKey.superBoundary(section, target, face);
                };
                Set<Node> nodes = new HashSet<>();
                for (int j = random.nextInt(8); j > 0; j--) {
                    var node = (Node) constructor.newInstance(search,
                            new MacroSearch.ExactEndpoint(nextNode++, BlockPos.ZERO, 1));
                    node.f = special[random.nextInt(special.length)];
                    nodes.add(node);
                }
                waiting.put(key, nodes);
            }
            var expected = waiting.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().stream()
                            .map(node -> node.f).min(Float::compare).orElse(Float.POSITIVE_INFINITY)))
                    .sorted(Comparator.comparingDouble((Map.Entry<MacroSearch.DependencyKey, Float> e) -> e.getValue())
                            .thenComparingInt(e -> e.getKey().kind().ordinal())
                            .thenComparingInt(e -> e.getKey().position().x())
                            .thenComparingInt(e -> e.getKey().position().y())
                            .thenComparingInt(e -> e.getKey().position().z()))
                    .map(Map.Entry::getKey).toList();
            for (int limit : new int[]{1, 4, 15, 16, 17, 1000}) {
                var actual = search.pendingDependencies(limit);
                require(actual.equals(expected.subList(0, Math.min(limit, expected.size()))),
                        "Top-K ordering or stable ties changed");
                try { actual.add(null); throw new AssertionError("mutable dependency list"); }
                catch (UnsupportedOperationException expectedFailure) {}
            }
            for (int limit : new int[]{0, -1}) {
                try { search.pendingDependencies(limit); throw new AssertionError("invalid Top-K limit"); }
                catch (IllegalArgumentException expectedFailure) {}
            }
        }
    }

    private static boolean bit(long[] mask, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 16 || y >= 16 || z >= 16) return false;
        int cell = y * 256 + z * 16 + x;
        return (mask[cell / 64] & (1L << (cell % 64))) != 0;
    }

    private static int scalarRow(long[] source, long[] target, Direction face, int inset, int row,
                                 Direction direction, int distance, int dy, int deltaY) {
        int result = 0;
        for (int u = 0; u < 16; u++) {
            int x = switch (face) { case WEST -> inset; case EAST -> 15 - inset; default -> u; };
            int y = switch (face) { case DOWN -> inset; case UP -> 15 - inset; default -> row; };
            int z = switch (face) {
                case NORTH -> inset; case SOUTH -> 15 - inset; case WEST, EAST -> u; default -> row;
            };
            if (bit(source, x, y, z) && bit(target,
                    x + direction.getStepX() * distance - face.getStepX() * 16,
                    y + dy - deltaY, z + direction.getStepZ() * distance - face.getStepZ() * 16))
                result |= 1 << u;
        }
        return result;
    }

    private static void portRowsMatchIndependentCoordinates() {
        var random = new Random(2026101709L);
        for (int sample = 0; sample < 12; sample++) {
            long[] a = new long[64], b = new long[64];
            for (int word = 0; word < 64; word++) {
                a[word] = sample == 0 ? 0 : sample == 1 ? -1L : random.nextLong();
                b[word] = sample == 0 ? -1L : sample == 1 ? 0 : random.nextLong();
            }
            var source = new BoundaryPortBits(a);
            var target = new BoundaryPortBits(b);
            for (Direction face : Direction.values()) {
                boolean vertical = face.getAxis().isVertical();
                int depth = vertical ? (face == Direction.UP ? 1 : 4) : 3;
                for (int shift = vertical ? 0 : -1; shift <= (vertical ? 0 : 1); shift++) {
                    int deltaY = (face.getStepY() + shift) * 16;
                    for (int inset = 0; inset < depth; inset++) for (int row = 0; row < 16; row++)
                        for (Direction direction : vertical ? HORIZONTAL : new Direction[]{face})
                            for (int distance = vertical ? 1 : inset + 1; distance <= 3; distance++)
                                for (int dy = -4; dy <= 1; dy++)
                                    require(source.candidates(target, face, inset, row, direction, distance, dy, deltaY)
                                            == scalarRow(a, b, face, inset, row, direction, distance, dy, deltaY),
                                            "port coordinate alignment or row wrapping changed");
                }
            }
            int saved = source.rowX(0, 0);
            Arrays.fill(a, 0);
            require(source.rowX(0, 0) == saved, "port bitmap retained mutable build scratch");
        }
    }

    private static BaseClusterTopology.BuildInput fullHalo(BaseClusterTopology.PackedFacts facts) {
        var halo = new java.util.TreeMap<Integer, BaseClusterTopology.PackedFacts>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if ((x | y | z) != 0) halo.put(BaseClusterTopology.haloIndex(x, y, z), facts);
        return Fixtures.input(facts, halo);
    }

    private static BaseClusterTopology groundRows(int sectionX, int... rows) {
        byte[] flags = Fixtures.flat();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            boolean accepted = false;
            for (int row : rows) accepted |= row == z;
            if (!accepted) flags[Fixtures.cell(x, 1, z)] &= ~BaseClusterTopology.GROUND_OPEN;
        }
        return BaseClusterTopology.build(SectionPos.of(sectionX, -2, -3), 1,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.fromCells(flags)),
                Fixtures.body(.6F, 1.8F, false, false), new BaseClusterTopology.BuildScratch());
    }

    private static void boundaryOutputsMatchScalarEnumeration() throws Exception {
        neighborFaceRulesArePreserved();
        boundaryValidationAndDirectedKeysArePreserved();
        var source = groundRows(-2, 2, 13);
        var target = groundRows(-1, 13);
        int isolated = source.componentAt(15, 1, 2), aligned = source.componentAt(15, 1, 13);
        int incoming = target.componentAt(0, 1, 13);
        require(isolated >= 0 && aligned >= 0 && isolated != aligned, "fixture components collapsed");
        require(!BoundaryPortBits.mayLink(source, isolated, target, -1, Direction.EAST)
                && BoundaryPortBits.mayLink(source, aligned, target, incoming, Direction.EAST),
                "section mask mixed component identities or disjoint ports");
        require(BoundaryPortBits.mayLink(target, incoming, source, -1, Direction.WEST),
                "opposite face alignment changed");
        var scalar = method(SuperClusterTopology.class, "scalarBoundaryLinks",
                BaseClusterTopology.class, BaseClusterTopology.class, Direction.class);
        var fixtures = List.of(Fixtures.fromFlags("flat", Fixtures.flat()), Fixtures.wall("gap", 1, false),
                Fixtures.door("openable", true, false), Fixtures.door("locked", false, false),
                Fixtures.door("support-door", true, true));
        var bodies = List.of(Fixtures.body(.6F, 1.8F, false, false),
                Fixtures.body(1.25F, 2.25F, true, true),
                new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.VOLUME, 1, 1, .3F, .4F, false, false));
        for (var fixture : fixtures) for (var body : bodies) {
            var input = fullHalo(fixture.input().center());
            var origin = SectionPos.of(-2, 3, -5);
            var scratch = new BaseClusterTopology.BuildScratch();
            source = BaseClusterTopology.build(origin, 1, input, body, scratch);
            require((source.portBits() != null) == (body.channel() == BaseClusterTopology.Channel.GROUND),
                    "VOLUME unexpectedly allocated a port index");
            for (int cell = 0; cell < 4096 && source.portBits() != null; cell++) {
                int x = cell % 16, y = cell / 256, z = cell / 16 % 16;
                require(((source.portBits().rowX(y, z) >>> x) & 1) == (source.componentAt(x, y, z) < 0 ? 0 : 1),
                        "port bits lost legal component anchors");
            }
            for (Direction face : Direction.values()) {
                boolean vertical = face.getAxis().isVertical();
                for (int shift = vertical ? 0 : -1; shift <= (vertical ? 0 : 1); shift++) {
                    var neighbor = SectionPos.of(origin.x() + face.getStepX(),
                            origin.y() + face.getStepY() + shift, origin.z() + face.getStepZ());
                    target = BaseClusterTopology.build(neighbor, 1, input, body, scratch);
                    var actual = SuperClusterTopology.boundaryLinks(source, target, face);
                    var expected = (SuperClusterTopology.BoundaryLinks) scalar.invoke(null, source, target, face);
                    for (int component = 0; component < source.componentCount(); component++) {
                        require(actual.edgeStart(component) == expected.edgeStart(component)
                                && actual.edgeEnd(component) == expected.edgeEnd(component), "boundary edge offsets changed");
                        if (actual.edgeStart(component) < actual.edgeEnd(component))
                            require(BoundaryPortBits.mayLink(source, component, target, -1, face),
                                    "forward prefilter rejected a real edge");
                        for (int edge = actual.edgeStart(component); edge < actual.edgeEnd(component); edge++) {
                            require(actual.targetComponent(edge) == expected.targetComponent(edge)
                                    && actual.lowerBound(edge) == expected.lowerBound(edge)
                                    && actual.bandStart(edge) == expected.bandStart(edge)
                                    && actual.bandEnd(edge) == expected.bandEnd(edge), "boundary target or bands changed");
                            require(BoundaryPortBits.mayLink(source, -1, target, actual.targetComponent(edge), face),
                                    "incoming prefilter rejected an original forward edge");
                            for (int band = actual.bandStart(edge); band < actual.bandEnd(edge); band++) {
                                if (body.channel() == BaseClusterTopology.Channel.GROUND)
                                    require(SuperClusterTopology.bandDistance(actual.descriptor(band)) >= 1,
                                            "GROUND gained pure vertical motion absent from the scalar contract");
                                require(actual.descriptor(band) == expected.descriptor(band)
                                        && actual.capabilityMask(band) == expected.capabilityMask(band),
                                        "boundary descriptor or capabilities changed");
                                for (int word = 0; word < 4; word++)
                                    require(actual.maskWord(band, word) == expected.maskWord(band, word),
                                            "boundary position mask changed");
                            }
                        }
                        if (body.channel() == BaseClusterTopology.Channel.VOLUME)
                            require(BoundaryPortBits.mayLink(source, component, target, -1, face),
                                    "VOLUME must conservatively retain scalar candidates");
                    }
                }
            }
        }
    }

    private static void neighborFaceRulesArePreserved() {
        var origin = SectionPos.of(-2, -2, -3);
        var northAbove = SectionPos.of(-2, -1, -4);
        try {
            MacroSearch.DependencyKey.baseBoundary(origin, northAbove, Direction.UP);
            throw new AssertionError("vertical face accepted a different x/z section");
        } catch (IllegalArgumentException expected) {}
        MacroSearch.DependencyKey.baseBoundary(origin, northAbove, Direction.NORTH);
        for (Direction direction : HORIZONTAL) {
            int x = direction == Direction.WEST ? 0 : direction == Direction.EAST ? 15 : 8;
            int z = direction == Direction.NORTH ? 0 : direction == Direction.SOUTH ? 15 : 8;
            int targetX = x + direction.getStepX() - direction.getStepX() * 16;
            int targetZ = z + direction.getStepZ() - direction.getStepZ() * 16;
            long[] sourceMask = new long[64], targetMask = new long[64];
            int sourceCell = Fixtures.cell(x, 15, z), targetCell = Fixtures.cell(targetX, 0, targetZ);
            sourceMask[sourceCell >>> 6] |= 1L << sourceCell;
            targetMask[targetCell >>> 6] |= 1L << targetCell;
            var source = new BoundaryPortBits(sourceMask);
            var target = new BoundaryPortBits(targetMask);
            require(source.candidates(target, Direction.UP, 0, z, direction, 1, 1, 16) == 0,
                    "diagonal endpoint incorrectly assigned to the UP neighbor");
            int u = direction.getAxis() == Direction.Axis.X ? z : x;
            require(source.candidates(target, direction, 0, 15, direction, 1, 1, 16) == 1 << u,
                    "horizontal face with yShift=1 lost the diagonal upward endpoint");
        }
    }

    private static void boundaryValidationAndDirectedKeysArePreserved() throws Exception {
        var sourceSection = SectionPos.of(-2, -2, -3);
        var targetSection = SectionPos.of(-1, -2, -3);
        var forward = MacroSearch.DependencyKey.baseBoundary(sourceSection, targetSection, Direction.EAST);
        Direction neighborFaceFromTarget = Direction.WEST;
        var incoming = MacroSearch.DependencyKey.baseBoundary(sourceSection, targetSection,
                neighborFaceFromTarget.getOpposite());
        require(forward.equals(incoming), "incoming search failed to request the original directed boundary");
        require(!forward.equals(MacroSearch.DependencyKey.baseBoundary(targetSection, sourceSection, Direction.WEST)),
                "physical reverse boundary must retain its independent direction");
        var scalar = method(SuperClusterTopology.class, "scalarBoundaryLinks",
                BaseClusterTopology.class, BaseClusterTopology.class, Direction.class);
        var input = BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.allAir());
        for (var channel : BaseClusterTopology.Channel.values()) {
            var profile = new BaseClusterTopology.TraversalProfile(.6F, 1.8F, 1, 3, 4, false, false);
            var body = profile.geometry(channel);
            var scratch = new BaseClusterTopology.BuildScratch();
            var source = BaseClusterTopology.build(sourceSection, 1, input, body, scratch);
            var target = BaseClusterTopology.build(targetSection, 1, input, body, scratch);
            var distant = BaseClusterTopology.build(SectionPos.of(0, -2, -3), 1, input, body, scratch);
            var wrongGeometry = BaseClusterTopology.build(targetSection, 1, input,
                    new BaseClusterTopology.TraversalProfile(1.25F, 2.25F, 1, 3, 4, false, false).geometry(channel), scratch);
            var otherChannel = BaseClusterTopology.build(targetSection, 1, input,
                    profile.geometry(channel == BaseClusterTopology.Channel.GROUND
                            ? BaseClusterTopology.Channel.VOLUME : BaseClusterTopology.Channel.GROUND), scratch);
            rejectBoundary(scalar, null, target, Direction.EAST, NullPointerException.class);
            rejectBoundary(scalar, source, null, Direction.EAST, NullPointerException.class);
            rejectBoundary(scalar, source, target, null, NullPointerException.class);
            rejectBoundary(scalar, source, distant, Direction.EAST, IllegalArgumentException.class);
            rejectBoundary(scalar, source, wrongGeometry, Direction.EAST, IllegalArgumentException.class);
            rejectBoundary(scalar, source, otherChannel, Direction.EAST, IllegalArgumentException.class);
            rejectBoundary(scalar, source, target, Direction.UP, IllegalArgumentException.class);
        }
    }

    private static void rejectBoundary(Method scalar, BaseClusterTopology source, BaseClusterTopology target,
                                        Direction face, Class<? extends Throwable> expected) throws Exception {
        for (boolean indexed : new boolean[]{false, true}) {
            Throwable failure = null;
            try {
                if (indexed) SuperClusterTopology.boundaryLinks(source, target, face);
                else scalar.invoke(null, source, target, face);
            } catch (java.lang.reflect.InvocationTargetException error) {
                failure = error.getCause();
            } catch (RuntimeException error) {
                failure = error;
            }
            require(expected.isInstance(failure), "indexed/scalar boundary validation diverged");
        }
    }

    private static void collisionRowsSurviveSerialization() throws Exception {
        strictAxisSegmentsRetainContacts();
        var cells = new SectionGeometry.Cell[4096];
        Arrays.fill(cells, SectionGeometry.empty());
        var partial = SectionGeometry.boxes(List.of(SectionGeometry.quantized(0, 0, 0, .1875, 1, 1)));
        for (int i = 0; i < cells.length; i += 7)
            cells[i] = switch (i % 4) {
                case 0 -> SectionGeometry.unknown(); case 1 -> SectionGeometry.fullBlock();
                case 2 -> partial; default -> SectionGeometry.door(partial, true);
            };
        var original = SectionGeometry.fromCells(cells);
        var bytes = new ByteArrayOutputStream();
        original.write(new DataOutputStream(bytes), new byte[8192]);
        var decoded = SectionGeometry.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        require(original.contentEquals(decoded) && original.fingerprint() == decoded.fingerprint(),
                "derived collision index changed persistent identity");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            int expected = 0;
            for (int x = 0; x < 16; x++)
                if (cells[Fixtures.cell(x, y, z)].shape().coordinateCount() > 0) expected |= 1 << x;
            require(original.nonEmptyRow(y, z) == expected && decoded.nonEmptyRow(y, z) == expected,
                    "collision row dropped UNKNOWN, door, full or partial geometry");
        }
    }

    private static void strictAxisSegmentsRetainContacts() throws Exception {
        var collision = new BoundaryGeometry(
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.allAir()));
        field(BoundaryGeometry.class, "rectangles").set(collision, new double[]{-1, -1, 1, 1});
        field(BoundaryGeometry.class, "rectangleCoordinates").setInt(collision, 4);
        var segment = method(BoundaryGeometry.class, "segmentClear",
                double.class, double.class, double.class, double.class);
        double[][] blocked = {{0, 0, 0, 0}, {-2, 0, 0, 0}, {0, -2, 0, 0}, {0, -.5, 0, .5}};
        double[][] clear = {{-2, 1, 2, 1}, {1, -2, 1, 2}, {1, 0, 1, 0},
                {2, 0, 1, 0}, {-2, -2, -2, -2}, {-2, -1, 2, -1}};
        for (double[] points : blocked)
            require(!(boolean) segment.invoke(collision, points[0], points[1], points[2], points[3]),
                    "axis segment lost interior endpoint or degenerate collision");
        for (double[] points : clear)
            require((boolean) segment.invoke(collision, points[0], points[1], points[2], points[3]),
                    "strict tangency or exterior degenerate point became blocked");
    }

    private static void callbacksPreserveOwnershipAndReentrancy() throws Exception {
        var runtime = new TopologyWorkerRuntime((key, load, delta) -> {}, ignored -> {});
        try {
            var order = new ArrayList<Integer>();
            runtime.sharedTransition(() -> {
                require(runtime.sharedLockHeld(), "command outside runtime monitor");
                runtime.sharedAfter(() -> {
                    require(!runtime.sharedLockHeld(), "callback retained runtime monitor");
                    order.add(1);
                    runtime.sharedTransition(() -> { runtime.sharedAfter(() -> order.add(2)); return null; });
                });
                runtime.sharedTransition(() -> { runtime.sharedAfter(() -> order.add(3)); return null; });
                runtime.sharedAfter(() -> order.add(4));
                require(order.isEmpty(), "nested callback ran before transition committed");
                return null;
            });
            require(order.equals(List.of(1, 2, 3, 4)), "nested/reentrant callback ordering changed");
            var repeated = new AtomicInteger();
            Runnable sameAction = repeated::incrementAndGet;
            runtime.sharedTransition(() -> {
                runtime.sharedAfter(sameAction);
                runtime.sharedAfter(sameAction);
                return null;
            });
            require(repeated.get() == 2, "original callback List semantics unexpectedly deduplicated an action");
            for (Throwable failure : List.of(new IllegalStateException("command"), new AssertionError("command"))) {
                int before = order.size();
                try {
                    runtime.sharedTransition(() -> {
                        runtime.sharedAfter(() -> order.add(5));
                        if (failure instanceof RuntimeException exception) throw exception;
                        throw (Error) failure;
                    });
                    throw new AssertionError("command exception swallowed");
                } catch (Throwable actual) {
                    require(actual == failure && order.size() == before + 1, "command failure lost callback ownership");
                }
            }
            var callbackFailure = new IllegalArgumentException("callback");
            try {
                runtime.sharedTransition(() -> {
                    runtime.sharedAfter(() -> { throw callbackFailure; });
                    runtime.sharedAfter(() -> order.add(99));
                    return null;
                });
                throw new AssertionError("callback exception swallowed");
            } catch (IllegalArgumentException actual) {
                require(actual == callbackFailure && !order.contains(99), "callback failure behavior changed");
            }
            runtime.sharedTransition(() -> { runtime.sharedAfter(() -> order.add(6)); return null; });
            require(order.get(order.size() - 1) == 6, "failed callback contaminated later transition");
            var completed = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();
            var start = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int t = 0; t < 4; t++) {
                var thread = new Thread(() -> {
                    try {
                        require(start.await(5, TimeUnit.SECONDS), "callback start timeout");
                        for (int i = 0; i < 500; i++) {
                            var future = new CompletableFuture<Integer>();
                            var dependent = future.thenRun(() -> runtime.sharedTransition(() -> {
                                runtime.sharedAfter(completed::incrementAndGet); return null;
                            }));
                            runtime.sharedTransition(() -> {
                                runtime.sharedAfter(() -> {
                                    require(!runtime.sharedLockHeld(), "concurrent callback under monitor");
                                    future.complete(1);
                                });
                                return null;
                            });
                            dependent.join();
                        }
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(10000);
                require(!thread.isAlive(), "callback completion deadlock");
            }
            if (failure.get() != null) throw new AssertionError("concurrent callback failure", failure.get());
            require(completed.get() == 2000, "lost or duplicate future completions");
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "runtime worker leak");
        }
    }

    private static void queueHeadsMatchFullScan() throws Exception {
        var executor = new TopologyTaskExecutor(() -> false);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable block = () -> {
            started.countDown();
            try { require(release.await(20, TimeUnit.SECONDS), "queue blocker timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        try {
            for (int i = 0; i < 2; i++)
                executor.submitSearch(NavigationScheduler.Priority.PLAYER_PURSUIT,
                        TopologyTaskExecutor.WorkKind.QUICK_SEARCH, block);
            require(started.await(5, TimeUnit.SECONDS), "queue workers did not start");
            Object monitor = field(TopologyTaskExecutor.class, "monitor").get(executor);
            Field foreground = field(TopologyTaskExecutor.class, "foreground");
            Field heads = field(TopologyTaskExecutor.class, "foregroundHeads");
            Field occupied = field(TopologyTaskExecutor.class, "buildRunning");
            Method best = method(TopologyTaskExecutor.class, "bestForeground", long.class);
            var taskType = Class.forName(TopologyTaskExecutor.class.getName() + "$Task");
            Field priority = field(taskType, "priority"), kind = field(taskType, "kind");
            Field sequence = field(taskType, "sequence"), enqueued = field(taskType, "enqueuedNanos");
            Field state = field(taskType, "state");
            var priorities = NavigationScheduler.Priority.values();
            var handles = new ArrayList<TopologyTaskExecutor.TaskHandle>();
            var random = new Random(2026101703L);
            synchronized (monitor) {
                for (int step = 0; step < 4000; step++) {
                    if (handles.isEmpty() || handles.size() < 96 && random.nextBoolean()) {
                        var selected = priorities[random.nextInt(priorities.length)];
                        handles.add(switch (random.nextInt(4)) {
                            case 0 -> executor.submit(selected, () -> {});
                            case 1 -> executor.submitPrewarm(() -> {});
                            default -> executor.submitSearch(selected, random.nextBoolean()
                                    ? TopologyTaskExecutor.WorkKind.QUICK_SEARCH : TopologyTaskExecutor.WorkKind.LONG_SEARCH, () -> {});
                        });
                    } else {
                        int index = random.nextInt(handles.size());
                        var handle = handles.get(index);
                        switch (random.nextInt(3)) {
                            case 0 -> handle.reprioritize(priorities[random.nextInt(priorities.length)]);
                            case 1 -> handle.promoteBuild();
                            default -> { handle.cancelWhenQueued(ignored -> {}); handles.remove(index); }
                        }
                    }
                    boolean buildRunning = random.nextBoolean();
                    occupied.setBoolean(executor, buildRunning);
                    long now = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(random.nextInt(2000));
                    Object expected = null;
                    Object[][] expectedHeads = new Object[priorities.length][3];
                    int bestRank = Integer.MAX_VALUE, bestKind = Integer.MAX_VALUE;
                    long bestSequence = Long.MAX_VALUE;
                    for (Object task : ((Map<?, ?>) foreground.get(executor)).values()) {
                        var workKind = (TopologyTaskExecutor.WorkKind) kind.get(task);
                        int group = ((NavigationScheduler.Priority) priority.get(task)).ordinal();
                        int column = workKind.ordinal() - 1;
                        Object head = expectedHeads[group][column];
                        if (head == null || sequence.getLong(task) < sequence.getLong(head))
                            expectedHeads[group][column] = task;
                        if (buildRunning && workKind == TopologyTaskExecutor.WorkKind.BUILD) continue;
                        int rank = ((NavigationScheduler.Priority) priority.get(task)).ordinal();
                        rank -= (int) Math.min(rank,
                                Math.max(0, now - enqueued.getLong(task)) / TimeUnit.MILLISECONDS.toNanos(250));
                        int kindOrder = workKind.ordinal();
                        long seq = sequence.getLong(task);
                        if (expected == null || rank < bestRank || rank == bestRank
                                && (kindOrder < bestKind || kindOrder == bestKind && seq < bestSequence)) {
                            expected = task; bestRank = rank; bestKind = kindOrder; bestSequence = seq;
                        }
                    }
                    Object[][] actualHeads = (Object[][]) heads.get(executor);
                    for (int group = 0; group < priorities.length; group++) for (int column = 0; column < 3; column++) {
                        Object head = actualHeads[group][column];
                        require(head == expectedHeads[group][column], "stale or incorrect cached group head");
                        if (head != null)
                            require(((Enum<?>) state.get(head)).name().equals("QUEUED"), "cached head is not queued");
                    }
                    require(best.invoke(executor, now) == expected, "queue head differs from full-scan oracle");
                }
                occupied.setBoolean(executor, false);
                executor.shutdown();
                require(best.invoke(executor, System.nanoTime()) == null, "shutdown retained cached heads");
            }
        } finally {
            executor.shutdown();
            release.countDown();
            require(executor.awaitTermination(5, TimeUnit.SECONDS), "queue worker leak");
        }
    }

    private static void queuedTasksCompleteUnderConcurrentMutation() throws Exception {
        var executor = new TopologyTaskExecutor(() -> true);
        var done = new CountDownLatch(600);
        var failure = new AtomicReference<Throwable>();
        var runningBuilds = new AtomicInteger();
        var completions = new AtomicIntegerArray(600);
        var producers = new ArrayList<Thread>();
        try {
            for (int producer = 0; producer < 4; producer++) {
                final int seed = producer;
                var thread = new Thread(() -> {
                    var random = new Random(2026101720L + seed);
                    try {
                        for (int i = 0; i < 150; i++) {
                            int index = seed * 150 + i;
                            boolean build = i % 3 != 2;
                            Runnable command = () -> {
                                boolean enteredBuild = false;
                                try {
                                    require(completions.incrementAndGet(index) == 1, "duplicate task execution");
                                    if (build) {
                                        enteredBuild = true;
                                        require(runningBuilds.incrementAndGet() == 1, "overlapping serial builds");
                                    }
                                    Thread.yield();
                                } catch (Throwable error) { failure.compareAndSet(null, error); }
                                finally {
                                    if (enteredBuild) runningBuilds.decrementAndGet();
                                    done.countDown();
                                }
                            };
                            var handle = switch (i % 3) {
                                case 0 -> executor.submit(NavigationScheduler.Priority.ACTIVE, command);
                                case 1 -> executor.submitPrewarm(command);
                                default -> executor.submitSearch(NavigationScheduler.Priority.BACKGROUND,
                                        TopologyTaskExecutor.WorkKind.LONG_SEARCH, command);
                            };
                            handle.reprioritize(NavigationScheduler.Priority.PLAYER_PURSUIT);
                            if (random.nextBoolean()) handle.promoteBuild();
                            if (random.nextInt(4) == 0)
                                handle.cancelWhenQueued(cancelled -> {
                                    if (cancelled) {
                                        if (completions.incrementAndGet(index) != 1)
                                            failure.compareAndSet(null, new AssertionError("task ran after cancellation"));
                                        done.countDown();
                                    }
                                });
                        }
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                producers.add(thread);
                thread.start();
            }
            for (Thread producer : producers) {
                producer.join(10000);
                require(!producer.isAlive(), "queue producer deadlock");
            }
            require(done.await(10, TimeUnit.SECONDS), "queued task or cancellation lost");
            if (failure.get() != null) throw new AssertionError("queue execution failure", failure.get());
            for (int i = 0; i < completions.length(); i++)
                require(completions.get(i) == 1, "missing task completion or cancellation");
            require(runningBuilds.get() == 0, "serial build permit leaked");
            var resumed = new AtomicInteger();
            var continuationDone = new CountDownLatch(1);
            Runnable continuation = new Runnable() {
                @Override public void run() {
                    try {
                        int count = resumed.incrementAndGet();
                        if (count < 64) {
                            require(executor.requeueCurrent(count % 2 == 0 ? NavigationScheduler.Priority.ACTIVE
                                            : NavigationScheduler.Priority.PLAYER_PURSUIT,
                                    count % 2 == 0 ? TopologyTaskExecutor.WorkKind.QUICK_SEARCH
                                            : TopologyTaskExecutor.WorkKind.LONG_SEARCH, this),
                                    "running search failed to requeue a continuation");
                        } else continuationDone.countDown();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        continuationDone.countDown();
                    }
                }
            };
            executor.submitSearch(NavigationScheduler.Priority.ACTIVE,
                    TopologyTaskExecutor.WorkKind.QUICK_SEARCH, continuation);
            require(continuationDone.await(10, TimeUnit.SECONDS), "continuation lost or cached head stalled");
            if (failure.get() != null) throw new AssertionError("continuation failure", failure.get());
            require(resumed.get() == 64, "continuation executed incorrectly");
        } finally {
            executor.shutdown();
            require(executor.awaitTermination(5, TimeUnit.SECONDS), "executing queue worker leak");
        }
    }
}
