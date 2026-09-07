package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Only the explicit L04 prewarm control enables admission in the benchmark image. */
public final class TopologyPrewarmScenario implements AutoCloseable {
    private static volatile TopologyPrewarmScenario active;
    private final TopologyService service;
    private final ServerLevel level;
    private final Object runtime;
    private final int desired;
    private final boolean mutated;
    private final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final BlockPos readyStart = new BlockPos(2820, 289, 2564), readyGoal = new BlockPos(2824, 289, 2564);
    private volatile boolean admission;
    private TopologyBuildWindowProbe probe;
    private TopologyService.MacroRequest ready, foreground;
    private int stage, changedTick;

    public TopologyPrewarmScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; runtime = readField(service, "runtime");
        desired = Integer.parseInt(target.parameters().get("prewarm")); mutated = "mutated".equals(control);
        synchronized (TopologyPrewarmScenario.class) {
            if (active != null) throw new IllegalStateException("overlapping prewarm controls"); active = this;
        }
    }
    public static boolean allows(Object runtime) {
        var control = active; return control != null && control.runtime == runtime && control.admission;
    }
    public Set<Long> chunkPositions() {
        Set<Long> result = new java.util.HashSet<>(); for (int x = 168; x <= 176; x++) result.add(ChunkPos.asLong(x, 160)); return Set.copyOf(result);
    }
    public Map<String, Object> tick() {
        if (stage == 0) {
            for (long position : chunkPositions()) {
                ChunkPos pos = new ChunkPos(position); LevelChunk chunk = level.getChunk(pos.x, pos.z); chunks.put(position, chunk);
                TopologyService.onChunkLoaded(level, chunk);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 288; y <= 291; y++) {
                    chunk.setBlockState(new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z),
                            y == 288 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                }
            }
            changedTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (level.getServer().getTickCount() <= changedTick || !settled()) return null;
            for (LevelChunk chunk : chunks.values()) { TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk); }
            stage = 2; return null;
        }
        if (stage == 2) {
            if (!settled() || !TopologyValidationAccess.clearDerived(service, level.dimension())) return null;
            ready = request(readyStart, readyGoal); stage = 3; return null;
        }
        if (stage == 3) {
            if (!ready.future().isDone() || !settled()) return null;
            require(ready.future().join() != null, "independent ready-search input is built before prewarm control");
            TopologyValidationAccess.forgetRequest(ready);
            ((TopologyRuntimeFormalControlAccess) runtime).acceleratedNavigation$clearCompletedCorridors();
            Set<Long> selected = new java.util.HashSet<>(chunkPositions()); selected.remove(ChunkPos.asLong(176, 160));
            synchronized (readField(runtime, "runtimeLock")) {
                for (var entry : List.copyOf(((Map<?, ?>) readField(runtime, "prewarmCandidates")).entrySet())) {
                    Object key = entry.getKey(), candidate = entry.getValue();
                    long chunk = (long) readField(key, "chunkLong");
                    invoke(runtime, "removePrewarm", readField(key, "dimension"), chunk, readField(candidate, "loadIdentity"));
                }
            }
            if (desired == 8) {
                for (long position : selected) {
                    ChunkPos pos = new ChunkPos(position);
                    Object loaded = ((Map<?, ?>) readField(service, "loadedSections")).get(
                            new TopologyWorkerRuntime.ClusterKey(level.dimension(), SectionPos.of(pos.x, 18, pos.z)));
                    require(loaded != null && !chunks.get(position).getSection(chunks.get(position).getSectionIndexFromSectionY(18)).hasOnlyAir(),
                            "the selected prewarm section is a real loaded non-air input");
                    long load = (long) readField(readField(loaded, "chunk"), "identity");
                    ((TopologyWorkerRuntime) runtime).enqueuePrewarm(level.dimension(), pos, load, List.of(18));
                }
                evidence.put("selectedSections", selected.stream().map(position -> new ChunkPos(position).toString() + "/18").toList());
            }
            if (desired == 8) probe = TopologyBuildWindowProbe.watch(service, "base/captured", selected);
            admission = true; stage = 4; return null;
        }
        if (stage == 4) {
            if (desired == 8 && (!probe.reached() || admitted() != 8)) return null;
            verifyAdmission(admitted(), desired); evidence.put("beforeForeground", workerMetrics(service));
            BlockPos start = desired == 8 ? new BlockPos(probe.section().minBlockX() + 4, 289, 2564) : new BlockPos(2692, 289, 2564);
            foreground = request(start, start.offset(4, 0, 0));
            ready = request(readyStart, readyGoal); stage = 5; return null;
        }
        if (stage == 5) {
            if (probe != null && probe.timedOut()) throw new AssertionError("prewarm worker control timed out");
            if (!ready.future().isDone()) return null;
            require(ready.future().join() != null, "the second worker progresses ready search while the serial build is paused");
            evidence.put("readySearch", TopologyValidationAccess.requestIdentity(ready));
            if (desired == 8) {
                require(!foreground.future().isDone(), "foreground consumer is still waiting on the original prewarm build");
                synchronized (readField(runtime, "runtimeLock")) {
                    Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), probe.section()));
                    Object view = ((Map<?, ?>) readField(entry, "views")).get(BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND));
                    Object demand = readField(view, "demand");
                    require(demand != null && !(boolean) readField(demand, "prewarmSlot"), "foreground takes over the original shared demand");
                }
                require(admitted() == 7, "promotion releases exactly one of eight admission slots");
                Map<String, Long> metrics = workerMetrics(service);
                require(metrics.get("worker.tasks.running.builds") + metrics.get("worker.tasks.running.prewarms") == 1,
                        "the original serial build remains the only running build");
                evidence.put("duringPromotion", metrics); probe.release();
            }
            stage = 6; return null;
        }
        if (!foreground.future().isDone()) return null;
        require(foreground.future().join() != null, "promoted or ordinary foreground query completes");
        admission = false;
        if (!settled() || !TopologyValidationAccess.snapshot(service, level.dimension()).idle()) return null;
        verifyAdmission(admitted(), 0);
        evidence.put("afterForeground", workerMetrics(service)); evidence.put("foreground", TopologyValidationAccess.requestIdentity(foreground));
        if (mutated) {
            boolean detected = false;
            try { verifyAdmission(9, 8); } catch (AssertionError expected) { detected = true; }
            require(detected, "qualification rejects a ninth admitted prewarm"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }
    private int admitted() { synchronized (readField(runtime, "runtimeLock")) { return (int) readField(runtime, "prewarmAdmitted"); } }
    private boolean settled() { return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled")); }
    private TopologyService.MacroRequest request(BlockPos start, BlockPos goal) {
        return service.requestMacroQuery(level, UUID.randomUUID(), start, goal, BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
    }
    private static void verifyAdmission(int actual, int expected) { require(actual == expected && actual <= 8, "bounded prewarm admission equals the requested workload"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        admission = false; if (probe != null) { probe.close(); probe = null; }
        for (var query : new TopologyService.MacroRequest[]{ready, foreground}) if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); }
        for (LevelChunk chunk : chunks.values()) TopologyService.onChunkUnloaded(level, chunk);
        synchronized (TopologyPrewarmScenario.class) { if (active == this) active = null; }
    }
}
