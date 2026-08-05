package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

/** Independent GameTest-only scalar partition oracle for the default 1x2 dry ground view. */
public final class MacroTopologyScalarOracle {
    private static final int MAX_MISMATCHES = 32;

    private MacroTopologyScalarOracle() {}

    public static Result compareDefaultGround(ServerLevel level, SectionPos section,
                                              BaseClusterTopology actual) {
        long began = System.nanoTime();
        int[] labels = new int[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(labels, -1);
        boolean[] legal = legalAnchors(level, section);
        ArrayDeque<Integer> open = new ArrayDeque<>();
        int components = 0;
        for (int cell = 0; cell < legal.length; cell++) {
            if (!legal[cell] || labels[cell] >= 0) continue;
            labels[cell] = components;
            open.addLast(cell);
            while (!open.isEmpty()) {
                int current = open.removeFirst();
                int x = BaseClusterTopology.x(current), y = BaseClusterTopology.y(current);
                int z = BaseClusterTopology.z(current);
                if (x > 0) visit(legal, labels, open, x - 1, y, z, components);
                if (x < 15) visit(legal, labels, open, x + 1, y, z, components);
                if (z > 0) visit(legal, labels, open, x, y, z - 1, components);
                if (z < 15) visit(legal, labels, open, x, y, z + 1, components);
            }
            components++;
        }

        List<Map<String, Object>> mismatches = new ArrayList<>();
        Map<Integer, Integer> actualToOracle = new LinkedHashMap<>();
        Map<Integer, Integer> oracleToActual = new LinkedHashMap<>();
        for (int cell = 0; cell < labels.length; cell++) {
            int expected = labels[cell];
            int observed = actual.componentAt(BaseClusterTopology.x(cell),
                    BaseClusterTopology.y(cell), BaseClusterTopology.z(cell));
            if ((expected < 0) != (observed < 0)
                    || expected >= 0 && (actualToOracle.putIfAbsent(observed, expected) != null
                    && actualToOracle.get(observed) != expected)
                    || expected >= 0 && (oracleToActual.putIfAbsent(expected, observed) != null
                    && oracleToActual.get(expected) != observed)) {
                mismatch(mismatches, "PARTITION", cell, expected, observed);
            }
        }
        if (components != actual.componentCount()) {
            mismatch(mismatches, "COMPONENT_COUNT", -1, components, actual.componentCount());
        }
        for (int component = 0; component < actual.componentCount(); component++) {
            int anchor = actual.componentAnchorCell(component);
            if (anchor < 0 || anchor >= labels.length || actual.componentAt(
                    BaseClusterTopology.x(anchor), BaseClusterTopology.y(anchor),
                    BaseClusterTopology.z(anchor)) != component) {
                mismatch(mismatches, "ANCHOR", anchor, component, -1);
            }
        }
        Map<Long, EdgeFact> expectedEdges = scalarEdges(labels, legal, prismAnchors(level, section));
        Map<Long, EdgeFact> observedEdges = new HashMap<>();
        for (int source = 0; source < actual.componentCount(); source++) {
            Integer oracleSource = actualToOracle.get(source);
            if (oracleSource == null) continue;
            for (int edge = actual.localEdgeStart(source); edge < actual.localEdgeEnd(source); edge++) {
                Integer oracleTarget = actualToOracle.get(actual.localEdgeTarget(edge));
                if (oracleTarget != null) observedEdges.put(edgeKey(oracleSource, oracleTarget),
                        new EdgeFact(actual.localEdgeCapabilities(edge), actual.localEdgeLowerBound(edge)));
            }
        }
        for (Map.Entry<Long, EdgeFact> expected : expectedEdges.entrySet()) {
            EdgeFact observed = observedEdges.remove(expected.getKey());
            if (observed == null) {
                mismatch(mismatches, "LOCAL_EDGE_MISSING", -1, expected.getKey(), -1L);
            } else if (expected.getValue().capabilities != observed.capabilities) {
                mismatch(mismatches, "LOCAL_CAPABILITIES", -1,
                        expected.getValue().capabilities, observed.capabilities);
            } else if (Math.abs(expected.getValue().cost - observed.cost)
                    > Math.max(1.0e-5F, 2.0F * Math.ulp(expected.getValue().cost))) {
                mismatch(mismatches, "LOCAL_LOWER_BOUND", -1,
                        Float.floatToRawIntBits(expected.getValue().cost),
                        Float.floatToRawIntBits(observed.cost));
            }
        }
        for (long extra : observedEdges.keySet()) {
            mismatch(mismatches, "LOCAL_EDGE_EXTRA", -1, -1L, extra);
        }
        return new Result(components, actual.componentCount(), expectedEdges.size(),
                actualEdgeCount(actual), BaseClusterTopology.CELL_COUNT,
                List.copyOf(mismatches), System.nanoTime() - began);
    }

    /** Compares the published four-bit facts with an unoptimized collision oracle. */
    public static FactsResult comparePublishedFacts(TopologyService service,
                                                     ServerLevel level,
                                                     SectionPos section) {
        long began = System.nanoTime();
        BaseClusterTopology.PackedFacts actual = publishedFacts(service, level, section);
        if (actual == null) {
            return new FactsResult(false, BaseClusterTopology.CELL_COUNT, 0,
                    List.of(Map.of("code", "PUBLISHED_FACTS_UNAVAILABLE")),
                    System.nanoTime() - began);
        }
        int[] expectedFlags = rawFacts(level, section);
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (int cell = 0; cell < BaseClusterTopology.CELL_COUNT; cell++) {
            int expected = expectedFlags[cell];
            int observed = actual.flags(cell);
            if (expected != observed && mismatches.size() < MAX_MISMATCHES) {
                mismatches.add(Map.of(
                        "cell", cell,
                        "expected", expected,
                        "observed", observed
                ));
            }
        }
        return new FactsResult(
                mismatches.isEmpty(),
                BaseClusterTopology.CELL_COUNT,
                mismatches.size(),
                List.copyOf(mismatches),
                System.nanoTime() - began
        );
    }

    private static BaseClusterTopology.PackedFacts publishedFacts(
            TopologyService service, ServerLevel level, SectionPos section) {
        try {
            Map<?, ?> clusters = (Map<?, ?>) readField(service, "clusters");
            Object entry = clusters.get(new TopologyService.ClusterKey(level.dimension(), section));
            return entry == null
                    ? null
                    : (BaseClusterTopology.PackedFacts) readField(entry, "facts");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static int[] rawFacts(ServerLevel level, SectionPos section) {
        int[] flags = new int[BaseClusterTopology.CELL_COUNT];
        boolean[] collides = new boolean[BaseClusterTopology.CELL_COUNT];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int cell = 0; cell < flags.length; cell++) {
            int x = BaseClusterTopology.x(cell);
            int y = BaseClusterTopology.y(cell);
            int z = BaseClusterTopology.z(cell);
            position.set(section.minBlockX() + x, section.minBlockY() + y,
                    section.minBlockZ() + z);
            BlockState state = level.getBlockState(position);
            VoxelShape shape = state.getCollisionShape(level, position);
            boolean full = Block.isShapeFullBlock(shape);
            boolean collision = !shape.isEmpty();
            boolean fluid = !state.getFluidState().isEmpty();
            collides[cell] = collision;
            int result = fluid ? BaseClusterTopology.FLUID : 0;
            if (!full) {
                result |= BaseClusterTopology.VOLUME_OPEN;
                if (collision || fluid || y > 0 && collides[cell - 256]) {
                    result |= BaseClusterTopology.GROUND_OPEN;
                }
                boolean standardLiquid = state.getBlock().getClass() == LiquidBlock.class;
                if (collision || state.getBlock().hasDynamicShape() && !standardLiquid) {
                    result |= BaseClusterTopology.EXACT_REQUIRED;
                }
            }
            flags[cell] = result;
        }
        return flags;
    }

    private static Object readField(Object target, String name)
            throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Map<Long, EdgeFact> scalarEdges(int[] labels,
                                                   boolean[] legal,
                                                   boolean[] prism) {
        Map<Long, EdgeFact> edges = new HashMap<>();
        Direction[] directions = {Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST};
        for (int cell = 0; cell < labels.length; cell++) {
            int source = labels[cell];
            if (source < 0) continue;
            int x = BaseClusterTopology.x(cell), y = BaseClusterTopology.y(cell);
            int z = BaseClusterTopology.z(cell);
            for (Direction direction : directions) for (int horizontal = 1; horizontal <= 3; horizontal++) {
                int targetX = x + direction.getStepX() * horizontal;
                int targetZ = z + direction.getStepZ() * horizontal;
                if (targetX < 0 || targetX > 15 || targetZ < 0 || targetZ > 15) break;
                for (int dy = -4; dy <= 1; dy++) {
                    int targetY = y + dy;
                    if (targetY < 0 || targetY > 15) continue;
                    int targetCell = BaseClusterTopology.cellIndex(targetX, targetY, targetZ);
                    int target = labels[targetCell];
                    if (!legal[targetCell] || target < 0 || target == source
                            || !scalarEnvelopeOpen(x, y, z, direction, horizontal, dy, prism)) continue;
                    long key = edgeKey(source, target);
                    long capabilities = capabilityMask(Math.max(0, dy), horizontal - 1,
                            Math.max(0, -dy));
                    float cost = (float) Math.sqrt(horizontal * horizontal + dy * dy);
                    edges.merge(key, new EdgeFact(capabilities, cost),
                            (first, second) -> new EdgeFact(first.capabilities | second.capabilities,
                                    Math.min(first.cost, second.cost)));
                }
            }
        }
        return edges;
    }

    private static boolean[] prismAnchors(ServerLevel level, SectionPos section) {
        boolean[] prism = new boolean[17 * 256];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = 0; y <= 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            position.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
            CellFact lower = fact(level, position);
            position.setY(position.getY() + 1);
            CellFact upper = fact(level, position);
            prism[(y << 8) | (z << 4) | x] = !lower.fullBlock && !lower.fluid
                    && !upper.fullBlock && !upper.fluid;
        }
        return prism;
    }

    private static boolean scalarEnvelopeOpen(int x, int y, int z, Direction direction,
                                              int horizontal, int dy, boolean[] prism) {
        if (dy < 0) {
            int targetX = x + direction.getStepX() * horizontal;
            int targetZ = z + direction.getStepZ() * horizontal;
            for (int shaftY = y - 1; shaftY > y + dy; shaftY--) {
                if (!prism[(shaftY << 8) | (targetZ << 4) | targetX]) return false;
            }
        }
        for (int distance = 1; distance < horizontal; distance++) {
            int intermediateY = y + Math.floorDiv(dy * distance, horizontal);
            int intermediate = (intermediateY << 8)
                    | ((z + direction.getStepZ() * distance) << 4)
                    | x + direction.getStepX() * distance;
            if (!prism[intermediate] && (intermediateY == 16 || !prism[intermediate + 256])) return false;
        }
        return true;
    }

    private static long capabilityMask(int step, int jump, int drop) {
        long mask = 0L;
        for (int maxStep = step; maxStep <= 1; maxStep++)
            for (int maxJump = jump; maxJump <= 2; maxJump++)
                for (int maxDrop = drop; maxDrop <= 4; maxDrop++)
                    mask |= 1L << ((maxStep * 3 + maxJump) * 5 + maxDrop);
        return mask;
    }

    private static long edgeKey(int source, int target) {
        return ((long) source << 32) | Integer.toUnsignedLong(target);
    }

    private static int actualEdgeCount(BaseClusterTopology topology) {
        return topology.componentCount() == 0 ? 0 : topology.localEdgeEnd(topology.componentCount() - 1);
    }

    private static boolean[] legalAnchors(ServerLevel level, SectionPos section) {
        boolean[] legal = new boolean[BaseClusterTopology.CELL_COUNT];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int cell = 0; cell < legal.length; cell++) {
            int x = BaseClusterTopology.x(cell), y = BaseClusterTopology.y(cell);
            int z = BaseClusterTopology.z(cell);
            position.set(section.minBlockX() + x, section.minBlockY() + y,
                    section.minBlockZ() + z);
            CellFact anchor = fact(level, position);
            position.setY(position.getY() - 1);
            boolean supportBelow = !level.getBlockState(position)
                    .getCollisionShape(level, position).isEmpty();
            position.setY(position.getY() + 1);
            boolean groundOpen = !anchor.fullBlock && (anchor.hasCollision || supportBelow
                    || anchor.fluid);
            position.setY(position.getY() + 1);
            CellFact head = fact(level, position);
            legal[cell] = groundOpen && !anchor.fluid && !head.fullBlock && !head.fluid;
        }
        return legal;
    }

    private static CellFact fact(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        VoxelShape shape = state.getBlock().hasDynamicShape()
                ? state.getCollisionShape(level, position)
                : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return new CellFact(Block.isShapeFullBlock(shape), !shape.isEmpty(),
                !state.getFluidState().isEmpty());
    }

    private static void visit(boolean[] legal, int[] labels, ArrayDeque<Integer> open,
                              int x, int y, int z, int component) {
        int cell = BaseClusterTopology.cellIndex(x, y, z);
        if (legal[cell] && labels[cell] < 0) {
            labels[cell] = component;
            open.addLast(cell);
        }
    }

    private static void mismatch(List<Map<String, Object>> output, String category,
                                 int cell, long expected, long observed) {
        if (output.size() >= MAX_MISMATCHES) return;
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("category", category);
        mismatch.put("cell", cell);
        mismatch.put("expected", expected);
        mismatch.put("observed", observed);
        output.add(mismatch);
    }

    private record CellFact(boolean fullBlock, boolean hasCollision, boolean fluid) {}
    private record EdgeFact(long capabilities, float cost) {}

    public record Result(int oracleComponents, int productionComponents,
                         int oracleEdges, int productionEdges, int checkedCells,
                         List<Map<String, Object>> mismatches, long elapsedNanos) {
        public boolean passed() { return mismatches.isEmpty(); }

        public Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("passed", passed());
            report.put("oracleComponents", oracleComponents);
            report.put("productionComponents", productionComponents);
            report.put("oracleEdges", oracleEdges);
            report.put("productionEdges", productionEdges);
            report.put("checkedCells", checkedCells);
            report.put("mismatches", mismatches);
            report.put("elapsedMillis", elapsedNanos / 1_000_000.0D);
            return report;
        }
    }

    public record FactsResult(boolean passed,
                              int checkedCells,
                              int mismatchCount,
                              List<Map<String, Object>> mismatches,
                              long elapsedNanos) {
        public Map<String, Object> report() {
            return Map.of(
                    "passed", passed,
                    "checkedCells", checkedCells,
                    "mismatchCount", mismatchCount,
                    "mismatches", mismatches,
                    "elapsedMillis", elapsedNanos / 1_000_000.0D
            );
        }
    }
}
