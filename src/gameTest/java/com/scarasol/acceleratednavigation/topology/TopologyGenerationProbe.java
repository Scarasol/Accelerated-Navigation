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
    private static final String LINEAGE_TAG = "anav_fixture_generation_lineage";
    private static final Map<Long, Saved> SAVED = new HashMap<>();
    private static long nextSave;
    private record Saved(String coordinate, Counts counts) { }
    private static final Map<String, Map<String, Object>> LOADED = new HashMap<>();
    private static final Map<String, java.util.List<Map<String, Object>>> HISTORY = new HashMap<>();
    private static final Map<String, Integer> OMITTED = new HashMap<>();
    private record LoadedSection(String key, ChunkAccess chunk, int y) { }
    private static final Map<LevelChunkSection, LoadedSection> LOADED_SECTIONS = new java.util.IdentityHashMap<>();
    private static final Map<String, java.util.List<Map<String, Object>>> POSTLOAD_WRITES = new HashMap<>();
    private static final Map<String, Integer> DROPPED_WRITES = new HashMap<>();
    private static final ThreadLocal<Invocation> FAMILY = new ThreadLocal<>();
    private static int activeStages, highestActiveStages;
    private static long stageWindows, processCpu, processWall, windowCpu, windowWall;
    private static long gcCount, gcMillis, windowGcCount, windowGcMillis;
    private static final Map<String, Long> peakPoolBytes = new LinkedHashMap<>();
    private static boolean cpuAvailable = true;
    private static ServerLevel handoffLevel;
    private static net.minecraft.world.level.ChunkPos handoffPosition;
    private static Map<String, Object> handoff;
    private TopologyGenerationProbe() { }

    private record Invocation(String family, Counts counts, long wall, long cpu, Invocation parent) { }
    private static final class Counts {
        long noiseCalls, oreCalls, noiseWrites, oreWrites, otherWrites, repeats, wall, cpu;
        long initialized = System.nanoTime();
        java.util.List<Map<String, Object>> ancestry = new java.util.ArrayList<>();
        Counts copy() {
            Counts copy = new Counts();
            copy.noiseCalls = noiseCalls; copy.oreCalls = oreCalls; copy.noiseWrites = noiseWrites; copy.oreWrites = oreWrites;
            copy.otherWrites = otherWrites; copy.repeats = repeats; copy.wall = wall; copy.cpu = cpu; copy.initialized = initialized;
            copy.ancestry.addAll(ancestry); return copy;
        }
    }

    private static boolean selected(ChunkAccess chunk) { return chunk.getPos().x >= 0 && chunk.getPos().x < 4 && chunk.getPos().z >= 0 && chunk.getPos().z < 4; }
    private static Object state(ChunkAccess chunk) {
        if (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        return ((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState();
    }
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

    public static void postloadWrite(LevelChunkSection section, int x, int y, int z,
                                     net.minecraft.world.level.block.state.BlockState before,
                                     net.minecraft.world.level.block.state.BlockState after) {
        if (!ENABLED || COST || before == after) return;
        synchronized (LOADED_SECTIONS) {
            var target = LOADED_SECTIONS.get(section);
            if (target == null) return;
            var rows = POSTLOAD_WRITES.computeIfAbsent(target.key(), ignored -> new java.util.ArrayList<>());
            if (rows.size() >= 128) { DROPPED_WRITES.merge(target.key(), 1, Integer::sum); return; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", new BlockPos(target.chunk().getPos().getMinBlockX() + x, target.y() * 16 + y, target.chunk().getPos().getMinBlockZ() + z).toString());
            row.put("before", before.toString()); row.put("after", after.toString()); row.put("nanoTime", System.nanoTime());
            row.put("chunkToken", TopologyValidationAccess.token(target.chunk())); row.put("thread", Thread.currentThread().getName());
            row.put("generationOwnerToken", TopologyValidationAccess.token(readField(section, "acceleratedNavigation$owner")));
            row.put("caller", StackWalker.getInstance().walk(stream -> stream.skip(1).limit(9).map(Object::toString).toList()));
            rows.add(row);
        }
    }

    public static Map<String, Object> postloadWrites(ServerLevel level, ChunkAccess chunk) {
        String key = level.dimension().location() + "/" + chunk.getPos().x + "/" + chunk.getPos().z;
        synchronized (LOADED_SECTIONS) {
            return Map.of("events", java.util.List.copyOf(POSTLOAD_WRITES.getOrDefault(key, java.util.List.of())),
                    "omitted", DROPPED_WRITES.getOrDefault(key, 0));
        }
    }

    public static void loaded(ServerLevel level, ChunkAccess chunk) {
        if (level == handoffLevel && chunk.getPos().equals(handoffPosition) && chunk instanceof LevelChunk) {
            if (handoff != null) throw new IllegalStateException("Handoff fixture reached a second load");
            Object owner = readField(chunk.getSection(chunk.getSectionIndex(1)), "acceleratedNavigation$owner");
            if (owner != chunk || (boolean) readField(state(chunk), "loadedFromDisk")) throw new AssertionError("Handoff requires the real newly promoted generation owner");
            var position = new BlockPos(chunk.getPos().getMinBlockX() + 1, 1, chunk.getPos().getMinBlockZ() + 1);
            chunk.setBlockState(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
            handoff = new LinkedHashMap<>(identity(chunk));
            handoff.put("generationOwnerToken", TopologyValidationAccess.token(owner));
            handoff.put("versionsAfterPreloadWrite", ((TopologyService.ChunkFactsState) state(chunk)).versions());
        }
        if (!ENABLED || !(chunk instanceof LevelChunk) || !selected(chunk)) return;
        String key = level.dimension().location() + "/" + chunk.getPos().x + "/" + chunk.getPos().z;
        if (!COST) synchronized (LOADED_SECTIONS) {
            LOADED_SECTIONS.values().removeIf(value -> value.key().equals(key));
            for (int i = 0; i < chunk.getSections().length; i++) LOADED_SECTIONS.put(chunk.getSection(i), new LoadedSection(key, chunk, chunk.getSectionYFromSectionIndex(i)));
        }
        Counts counts = counts(chunk);
        Map<String, Object> result = new LinkedHashMap<>();
        synchronized (counts) {
            result.put("noiseCalls", counts.noiseCalls); result.put("oreCalls", counts.oreCalls);
            result.put("noiseWrites", counts.noiseWrites); result.put("oreWrites", counts.oreWrites);
            result.put("otherWrites", counts.otherWrites); result.put("repeatedWrites", counts.repeats);
            result.put("noiseOreWallNanos", counts.wall); result.put("noiseOreCpuNanos", counts.cpu);
            result.put("observedGenerationResidenceNanos", System.nanoTime() - counts.initialized);
            result.put("generationAncestry", java.util.List.copyOf(counts.ancestry));
        }
        result.put("factChanges", ((TopologyService.ChunkFactsState) state(chunk)).versions().values().stream().mapToLong(Long::longValue).sum());
        result.put("suppressed", SUPPRESS);
        result.put("chunkIdentity", identity(chunk));
        synchronized (HISTORY) {
            result.put("serializationHistory", java.util.List.copyOf(HISTORY.getOrDefault(key, java.util.List.of())));
            result.put("omittedHistoryEvents", OMITTED.getOrDefault(key, 0));
        }
        if (!COST) {
            boolean resumed = (boolean) readField(state(chunk), "loadedFromDisk");
            result.put("comparisonSource", resumed ? "published-recovered-facts" : "generated-cells-before-handoff");
            if (!resumed) result.put("cellComparison", compare(chunk));
        }
        synchronized (LOADED) { LOADED.put(key, Map.copyOf(result)); }
    }

    public static Map<String, Object> loaded(String dimension, int x, int z) {
        synchronized (LOADED) { return LOADED.get(dimension + "/" + x + "/" + z); }
    }

    public static void armHandoff(ServerLevel level, net.minecraft.world.level.ChunkPos position) {
        if (handoffLevel != null) throw new IllegalStateException("Overlapping real handoff fixtures");
        handoffLevel = level; handoffPosition = position; handoff = null;
    }
    public static Map<String, Object> handoff() { return handoff; }
    public static void clearHandoff() { handoffLevel = null; handoffPosition = null; handoff = null; }

    public static synchronized void serialized(ServerLevel level, ChunkAccess chunk, String action, net.minecraft.nbt.CompoundTag tag) {
        if (!ENABLED || COST || !selected(chunk)) return;
        String key = level.dimension().location() + "/" + chunk.getPos().x + "/" + chunk.getPos().z;
        long lineage = tag.getLong(LINEAGE_TAG);
        if (action.equals("read") && lineage != 0) {
            Saved saved = SAVED.get(lineage);
            if (saved == null || !saved.coordinate().equals(key)) throw new IllegalStateException("Unbound generation serialization lineage");
            COUNTS.put(TopologyValidationAccess.token(state(chunk)), saved.counts().copy());
        }
        if (action.equals("write")) {
            Counts current = counts(chunk);
            Counts snapshot;
            synchronized (current) { snapshot = current.copy(); }
            if (snapshot.ancestry.size() >= 64 || SAVED.size() >= 2048) throw new IllegalStateException("Generation lineage observation exceeded its bound");
            Map<String, Object> ancestor = new LinkedHashMap<>(identity(chunk));
            lineage = ++nextSave; ancestor.put("serialization", lineage);
            if (!(boolean) readField(state(chunk), "loadedFromDisk")) ancestor.put("cellComparison", compare(chunk));
            snapshot.ancestry.add(ancestor);
            SAVED.put(lineage, new Saved(key, snapshot)); tag.putLong(LINEAGE_TAG, lineage);
        }
        Map<String, Object> row = new LinkedHashMap<>(identity(chunk));
        row.put("serialization", lineage);
        row.put("action", action); row.put("nanoTime", System.nanoTime());
        Counts counts = counts(chunk);
        synchronized (counts) { row.put("noiseCalls", counts.noiseCalls); row.put("oreCalls", counts.oreCalls); }
        synchronized (HISTORY) {
            var events = HISTORY.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
            if (events.size() < 64) events.add(row);
            else OMITTED.merge(key, 1, Integer::sum);
        }
    }

    private static Map<String, Object> identity(ChunkAccess chunk) {
        Object state = state(chunk);
        return Map.of("chunkToken", TopologyValidationAccess.token(chunk), "stateToken", TopologyValidationAccess.token(state),
                "status", chunk.getStatus().toString(), "loadedFromDisk", readField(state, "loadedFromDisk"),
                "generationInitialized", readField(state, "generationInitialized"));
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
            if ((boolean) readField(state, "loadedFromDisk")) throw new IllegalStateException("Resumed sparse changes are not a complete generated baseline");
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

    public static Map<String, Object> comparePublished(Object service, ServerLevel level, ChunkAccess chunk) {
        var runtime = (TopologyWorkerRuntime) readField(service, "runtime");
        Map<Integer, BaseClusterTopology.PackedFacts> facts = new LinkedHashMap<>();
        Map<Integer, Long> revisions = new LinkedHashMap<>();
        synchronized (readField(runtime, "runtimeLock")) {
            Map<?, ?> clusters = (Map<?, ?>) readField(runtime, "clusters");
            for (int y = chunk.getMinSection(); y < chunk.getMaxSection(); y++) {
                Object entry = clusters.get(new TopologyWorkerRuntime.ClusterKey(level.dimension(),
                        net.minecraft.core.SectionPos.of(chunk.getPos().x, y, chunk.getPos().z)));
                Object value = entry == null ? null : readField(entry, "facts");
                if (value == null) return null;
                facts.put(y, (BaseClusterTopology.PackedFacts) value);
                revisions.put(y, (long) readField(entry, "revision"));
            }
        }
        long equal = 0, checked = 0;
        Map<String, Object> first = null;
        for (var entry : facts.entrySet()) for (int cell = 0; cell < 4096; cell++) {
            BlockPos position = new BlockPos(chunk.getPos().getMinBlockX() + (cell & 15), entry.getKey() * 16 + (cell >> 8),
                    chunk.getPos().getMinBlockZ() + (cell >> 4 & 15));
            int expected = independentFlags(chunk, position), actual = entry.getValue().flags(cell); checked++;
            if (expected == actual) equal++;
            else if (first == null) {
                first = new LinkedHashMap<>(); first.put("position", position.toString()); first.put("expected", expected); first.put("actual", actual);
                first.put("block", chunk.getBlockState(position).toString()); first.put("below", chunk.getBlockState(position.below()).toString());
                first.put("worldShape", chunk.getBlockState(position).getCollisionShape(chunk, position).toString());
                first.put("staticShape", chunk.getBlockState(position).getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toString());
                first.put("carrierVersion", ((TopologyService.ChunkFactsState) state(chunk)).versions().get(entry.getKey()));
                first.put("runtimeVersion", revisions.get(entry.getKey()));
                Object loaded = ((Map<?, ?>) readField(service, "loadedSections")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(),
                        net.minecraft.core.SectionPos.of(chunk.getPos().x, entry.getKey(), chunk.getPos().z)));
                if (loaded != null) {
                    for (String name : java.util.List.of("version", "changedTick", "readInFlight", "recoveryAttempted", "persistencePending")) first.put(name, readField(loaded, name));
                    first.put("pendingChangeCells", ((Map<?, ?>) readField(loaded, "pendingChanges")).size());
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
