package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** L03 keeps the original computation and references alive across each controlled world transition. */
public final class TopologyDerivedLifecycleScenario implements AutoCloseable {
    private final TopologyService service;
    private final ServerLevel level;
    private final String window, change;
    private final boolean mutated, replaced;
    private final BlockPos start, goal;
    private final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final TopologyFinalResultScenario finalResult;
    private TopologyBuildWindowProbe probe;
    private TopologyService.MacroRequest query, replacement;
    private int stage, changedTick;
    private boolean shutdownMode, shutdownReady;

    public TopologyDerivedLifecycleScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; window = target.parameters().get("window"); change = target.parameters().get("change");
        mutated = "mutated".equals(control); replaced = window.endsWith("/replaced");
        start = new BlockPos(2494, 289, 2497); goal = new BlockPos(window.startsWith("parent") ? 2625 : 2501, 289, 2497);
        if (window.equals("corridor/final-validation")) {
            String terminal = switch (change) { case "version" -> "stale-once"; case "cancel" -> "cancel"; case "unload" -> "endpoint-unload"; default -> throw new IllegalArgumentException(change); };
            finalResult = new TopologyFinalResultScenario(service, level,
                    new ProductionRemediationPlan.Target(target.id(), "F04", Map.of("terminal", terminal)), control);
        } else finalResult = null;
    }
    public Set<Long> chunkPositions() {
        if (finalResult != null) return finalResult.chunkPositions();
        Set<Long> positions = new java.util.HashSet<>();
        for (int x = 154; x <= (window.startsWith("parent") ? 165 : 157); x++) for (int z = 156; z <= 157; z++) positions.add(ChunkPos.asLong(x, z));
        return Set.copyOf(positions);
    }
    public CompletableFuture<Void> pendingDiskEdit() { return finalResult == null ? null : finalResult.pendingDiskEdit(); }
    boolean prepareShutdown() {
        shutdownMode = true;
        if (!shutdownReady) tick();
        return shutdownReady;
    }
    Map<String, Object> shutdownEvidence() {
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        result.put("events", probe.events()); result.put("oldReference", probe.oldReferenceState());
        result.put("timedOut", probe.timedOut()); result.put("request", TopologyValidationAccess.requestIdentity(query));
        result.put("cancelled", query.future().isCancelled()); result.put("latePublished", probe.resultPublished());
        result.put("executor", probe.executorState());
        return Map.copyOf(result);
    }
    void releaseShutdown() { if (probe != null) probe.release(); }
    public Map<String, Object> tick() {
        if (finalResult != null) return finalResult.tick();
        if (stage == 0) {
            for (long position : chunkPositions()) {
                ChunkPos pos = new ChunkPos(position); LevelChunk chunk = level.getChunk(pos.x, pos.z);
                chunks.put(position, chunk); TopologyService.onChunkLoaded(level, chunk);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 288; y <= 292; y++) {
                    chunk.setBlockState(new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z),
                            y == 288 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                }
            }
            changedTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (level.getServer().getTickCount() <= changedTick || !settled()) return null;
            query = request(); stage = 2; return null;
        }
        if (stage == 2) {
            if (!query.future().isDone()) return null;
            require(query.future().join() != null, "unmodified lifecycle-control terrain has a real public route");
            if (!TopologyValidationAccess.clearDerived(service, level.dimension())) return null;
            TopologyValidationAccess.forgetRequest(query);
            probe = TopologyBuildWindowProbe.watch(service, window, chunkPositions()); query = request(); stage = 3; return null;
        }
        if (probe.timedOut()) throw new AssertionError("original worker timed out at " + window);
        if (stage == 3) {
            if (!probe.reached()) {
                if (query.future().isDone()) throw new AssertionError("required worker window was not entered: " + window);
                return null;
            }
            evidence.put("beforeTransition", probe.events());
            if (shutdownMode && !replaced) {
                if (window.startsWith("executor/")) {
                    Map<String, Object> executor = probe.executorState();
                    boolean queued = window.endsWith("/queued");
                    require((queued ? "QUEUED" : "RUNNING").equals(executor.get("state"))
                                    && Boolean.valueOf(queued).equals(executor.get("queued")),
                            "the original executor task is in its requested ownership window");
                    if (!queued) require(Boolean.TRUE.equals(executor.get("buildPermit")), "the claimed build retains the sole permit");
                    evidence.put("executorBeforeStop", executor);
                }
                shutdownReady = true; return null;
            }
            if (replaced || change.equals("version")) changeVersion();
            if (!replaced) applyTerminalChange();
            changedTick = level.getServer().getTickCount(); stage = 4; return null;
        }
        if (stage == 4) {
            if (level.getServer().getTickCount() <= changedTick) return null;
            if (replaced) {
                replacement = request(); stage = 5; return null;
            }
            probe.release(); stage = 6; return null;
        }
        if (stage == 5) {
            if (!replacement.future().isDone()) return null;
            require(replacement.future().join() != null, "a new public consumer uses the replacement world while the old reference stays active");
            Map<String, Object> old = probe.oldReferenceState();
            require(Boolean.TRUE.equals(old.get("retiredOrReplaced")) && ((Number) old.get("pins")).intValue() > 0,
                    "replacement retires the old cache value without releasing its active consumer");
            evidence.put("oldReferenceDuringReplacement", old); evidence.put("replacement", TopologyValidationAccess.requestIdentity(replacement));
            if (shutdownMode) { shutdownReady = true; return null; }
            applyTerminalChange(); probe.release(); stage = 6; return null;
        }
        if (!query.future().isDone() || !settled() || !TopologyValidationAccess.snapshot(service, level.dimension()).idle()) return null;
        if (change.equals("cancel")) require(query.future().isCancelled(), "the controlled request remains cancelled");
        else if (!query.future().isCancelled()) query.future().join();
        verifyNoLatePublication(probe.resultPublished());
        if (replaced) require(((Number) probe.oldReferenceState().get("pins")).intValue() == 0, "the last old consumer releases its reference");
        evidence.put("events", probe.events()); evidence.put("afterTransition", probe.oldReferenceState()); evidence.put("request", TopologyValidationAccess.requestIdentity(query));
        evidence.put("progress", query.progress()); evidence.put("resources", workerMetrics(service));
        if (mutated) {
            boolean detected = false;
            try { verifyNoLatePublication(true); } catch (AssertionError expected) { detected = true; }
            require(detected, "qualification detects an installed stale result"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }
    private void changeVersion() {
        SectionPos section = probe.section(); BlockPos point = new BlockPos(section.minBlockX() + 8, section.minBlockY() + 4, section.minBlockZ() + 8);
        LevelChunk chunk = chunks.get(section.chunk().toLong());
        chunk.setBlockState(point, chunk.getBlockState(point).isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
    }
    private void applyTerminalChange() {
        if (change.equals("cancel")) query.cancel();
        else if (change.equals("unload")) TopologyService.onChunkUnloaded(level, chunks.get(probe.section().chunk().toLong()));
    }
    private TopologyService.MacroRequest request() {
        return service.requestMacroQuery(level, UUID.randomUUID(), start, goal, BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
    }
    private boolean settled() { return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled")); }
    private static void verifyNoLatePublication(boolean published) { require(!published, "the rejected original object is not installed as a current cache value"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        if (finalResult != null) { finalResult.close(); return; }
        if (probe != null) { probe.close(); probe = null; }
        for (var request : new TopologyService.MacroRequest[]{query, replacement}) if (request != null) { request.cancel(); TopologyValidationAccess.forgetRequest(request); }
        for (LevelChunk chunk : chunks.values()) TopologyService.onChunkUnloaded(level, chunk);
    }
}
