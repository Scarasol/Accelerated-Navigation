package com.scarasol.acceleratednavigation.topology;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Generation-only observations are active before spawn preparation starts. */
public final class TopologyGenerationProbe {
    public static final boolean ENABLED = java.util.List.of("G", "generation-cost")
            .contains(System.getProperty("acceleratedNavigation.validation.case", ""));
    public static final boolean COST = "generation-cost".equals(System.getProperty("acceleratedNavigation.validation.case"));
    public static final boolean SUPPRESS = COST
            && "off".equals(System.getProperty("acceleratedNavigation.validation.variant"));
    private static final Map<Long, Counts> COUNTS = new HashMap<>();
    private static final Map<String, Map<String, Object>> LOADED = new HashMap<>();
    private static final ThreadLocal<Invocation> FAMILY = new ThreadLocal<>();
    private static int activeStages, highestActiveStages;
    private static long stageWindows, processCpu, processWall, windowCpu, windowWall;
    private static long gcCount, gcMillis, windowGcCount, windowGcMillis;
    private static final Map<String, Long> peakPoolBytes = new LinkedHashMap<>();
    private static boolean cpuAvailable = true;
    private TopologyGenerationProbe() { }

    private record Invocation(String family, Counts counts, long wall, long cpu, Invocation parent) { }
    private static final class Counts {
        long noiseCalls, oreCalls, noiseWrites, oreWrites, otherWrites, repeats, wall, cpu;
        long initialized = System.nanoTime();
    }

    private static boolean selected(ChunkAccess chunk) { return chunk.getPos().x >= 0 && chunk.getPos().x < 4 && chunk.getPos().z >= 0 && chunk.getPos().z < 4; }
    private static Object state(ChunkAccess chunk) { return ((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState(); }
    private static synchronized Counts counts(ChunkAccess chunk) { return COUNTS.computeIfAbsent(TopologyValidationAccess.token(state(chunk)), ignored -> new Counts()); }

    public static void enter(String family, ChunkAccess chunk) {
        if (!ENABLED) return;
        Counts counts = selected(chunk) ? counts(chunk) : null;
        if (counts != null) synchronized (counts) { if (family.equals("Noise")) counts.noiseCalls++; else counts.oreCalls++; }
        FAMILY.set(new Invocation(family, counts, System.nanoTime(), cpu(), FAMILY.get()));
    }

    public static void exit() {
        if (!ENABLED) return;
        Invocation invocation = FAMILY.get();
        if (invocation == null) throw new IllegalStateException("generation observation stack is empty");
        FAMILY.set(invocation.parent);
        if (invocation.counts != null) synchronized (invocation.counts) {
            invocation.counts.wall += System.nanoTime() - invocation.wall;
            long end = cpu();
            if (end >= 0 && invocation.cpu >= 0) invocation.counts.cpu += end - invocation.cpu;
        }
    }

    public static void write(LevelChunkSection section, boolean repeated) {
        if (!ENABLED) return;
        ChunkAccess owner = (ChunkAccess) readField(section, "acceleratedNavigation$owner");
        if (owner == null || !selected(owner)) return;
        Counts counts = counts(owner);
        Invocation invocation = FAMILY.get();
        synchronized (counts) {
            if (invocation != null && invocation.family.equals("Noise")) counts.noiseWrites++;
            else if (invocation != null && invocation.family.equals("Ore")) counts.oreWrites++;
            else counts.otherWrites++;
            if (repeated) counts.repeats++;
        }
    }

    public static void loaded(ServerLevel level, ChunkAccess chunk) {
        if (!ENABLED || !(chunk instanceof LevelChunk) || !selected(chunk)) return;
        String key = level.dimension().location() + "/" + chunk.getPos().x + "/" + chunk.getPos().z;
        synchronized (LOADED) { if (LOADED.containsKey(key)) return; }
        Counts counts = counts(chunk);
        Map<String, Object> result = new LinkedHashMap<>();
        synchronized (counts) {
            result.put("noiseCalls", counts.noiseCalls); result.put("oreCalls", counts.oreCalls);
            result.put("noiseWrites", counts.noiseWrites); result.put("oreWrites", counts.oreWrites);
            result.put("otherWrites", counts.otherWrites); result.put("repeatedWrites", counts.repeats);
            result.put("noiseOreWallNanos", counts.wall); result.put("noiseOreCpuNanos", counts.cpu);
            result.put("observedGenerationResidenceNanos", System.nanoTime() - counts.initialized);
        }
        result.put("factChanges", ((TopologyService.ChunkFactsState) state(chunk)).versions().values().stream().mapToLong(Long::longValue).sum());
        result.put("suppressed", SUPPRESS);
        if (!COST) result.put("cellComparison", compare(chunk));
        synchronized (LOADED) { LOADED.put(key, Map.copyOf(result)); }
    }

    public static Map<String, Object> loaded(String dimension, int x, int z) {
        synchronized (LOADED) { return LOADED.get(dimension + "/" + x + "/" + z); }
    }

    /** Union intervals avoid counting concurrent generation stages more than once. */
    public static synchronized void beginStage() {
        if (!COST) return;
        if (activeStages++ == 0) {
            windowCpu = processCpuTime(); windowWall = System.nanoTime(); stageWindows++;
            windowGcCount = gc(false); windowGcMillis = gc(true);
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.isValid()) pool.resetPeakUsage();
        }
        highestActiveStages = Math.max(highestActiveStages, activeStages);
    }

    public static synchronized void endStage() {
        if (!COST) return;
        if (activeStages <= 0) throw new IllegalStateException("generation stage settled twice");
        if (--activeStages == 0) {
            long end = processCpuTime();
            cpuAvailable &= windowCpu >= 0 && end >= windowCpu;
            if (cpuAvailable) processCpu += end - windowCpu;
            processWall += System.nanoTime() - windowWall;
            gcCount += Math.max(0, gc(false) - windowGcCount);
            gcMillis += Math.max(0, gc(true) - windowGcMillis);
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                var peak = pool.isValid() ? pool.getPeakUsage() : null;
                if (peak != null) peakPoolBytes.merge(pool.getType() + "/" + pool.getName(), peak.getUsed(), Math::max);
            }
        }
    }

    public static synchronized Map<String, Object> generationCost() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "Process CPU over the union of real ChunkStatus.generate futures, including generation dependencies");
        result.put("activeStages", activeStages); result.put("highestActiveStages", highestActiveStages);
        result.put("windows", stageWindows); result.put("wallNanos", processWall);
        result.put("processCpuNanos", cpuAvailable && activeStages == 0 ? processCpu : null);
        result.put("gcCount", gcCount); result.put("gcMillis", gcMillis);
        result.put("peakPoolBytes", Map.copyOf(peakPoolBytes));
        result.put("peakScope", "Each JVM memory pool peak over generation windows; peaks are not simultaneous and must not be added as a measured process peak");
        result.put("postGenerationFactsServiceSuppressed", true);
        return result;
    }

    private static long gc(boolean time) {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean ->
                Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount())).sum();
    }

    private static long processCpuTime() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean operating ? operating.getProcessCpuTime() : -1L;
    }

    public static Map<String, Object> compare(ChunkAccess chunk) {
        TopologyService.ChunkFactsState state = (TopologyService.ChunkFactsState) state(chunk);
        long checked = 0, equal = 0;
        Map<String, Object> first = null;
        synchronized (state) {
            @SuppressWarnings("unchecked") Map<Integer, byte[]> actual = (Map<Integer, byte[]>) readField(state, "generatedCells");
            for (int section = chunk.getMinSection(); section < chunk.getMaxSection(); section++) {
                byte[] cells = actual.get(section);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockPos position = new BlockPos(chunk.getPos().getMinBlockX() + x, section * 16 + y, chunk.getPos().getMinBlockZ() + z);
                    int expected = independentFlags(chunk, position);
                    int observed = cells == null ? BaseClusterTopology.VOLUME_OPEN : cells[x | z << 4 | y << 8] & 15;
                    checked++;
                    if (expected == observed) equal++;
                    else if (first == null) first = Map.of("position", java.util.List.of(position.getX(), position.getY(), position.getZ()), "expected", expected, "actual", observed);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked); result.put("equal", equal); result.put("firstDifference", first);
        return result;
    }

    /** Independent oracle for the approved static/dynamic precision boundary. */
    public static int independentFlags(BlockGetter world, BlockPos position) {
        var block = world.getBlockState(position);
        boolean fluid = !block.getFluidState().isEmpty();
        if (block.getBlock().getClass() == LiquidBlock.class) return 3 | (fluid ? 4 : 0);
        if (block.getBlock().hasDynamicShape()) return 11 | (fluid ? 4 : 0);
        var shape = block.getCollisionShape(world, position);
        if (Block.isShapeFullBlock(shape)) return fluid ? 4 : 0;
        int flags = 1 | (fluid ? 4 : 0);
        if (!shape.isEmpty() || fluid) flags |= 2;
        if (!shape.isEmpty()) flags |= 8;
        if ((position.getY() & 15) != 0) {
            var below = world.getBlockState(position.below());
            if (below.getBlock().getClass() != LiquidBlock.class
                    && (below.getBlock().hasDynamicShape()
                    || !below.getCollisionShape(world, position.below()).isEmpty())) flags |= 2;
        }
        return flags;
    }

    private static long cpu() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1;
    }
}
