package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

/** F01 direct endpoints obtain R/P/U from actual recovery, writing and unload entry points. */
public final class TopologyEndpointFailureScenario implements AutoCloseable {
    private static volatile TopologyEndpointFailureScenario active;
    private final TopologyService service;
    private final ServerLevel level;
    private final ProductionRemediationPlan.Target target;
    private final boolean mutated, alternate;
    private final Object runtime;
    private final BlockPos start, goal, failedNeighbor;
    private final List<BlockPos> anchors;
    private final List<Map<String, Object>> resolutions = new ArrayList<>();
    private final List<LevelChunk> chunks = new ArrayList<>();
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Integer> order;
    private TopologyHandoffFaultProbe fault;
    private CompletableFuture<Void> diskEdit;
    private TopologyService.MacroRequest query;
    private int stage, faultStage, next, changedTick;

    public TopologyEndpointFailureScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target; mutated = "mutated".equals(control);
        alternate = target.kind().equals("F01/alternate");
        start = new BlockPos(alternate ? 1535 : 1537, 289, 1537);
        goal = new BlockPos(alternate ? 1528 : 1569, 289, 1537);
        failedNeighbor = alternate ? new BlockPos(1537, 289, 1537) : goal;
        anchors = List.of(start, failedNeighbor);
        runtime = readField(service, "runtime");
        order = alternate ? List.of(1) : "start-first".equals(target.parameters().get("order")) ? List.of(0, 1) : List.of(1, 0);
        synchronized (TopologyEndpointFailureScenario.class) {
            if (active != null) throw new IllegalStateException("overlapping endpoint failure controls");
            active = this;
        }
    }
    public Set<Long> chunkPositions() { return Set.of(new ChunkPos(start).toLong(), new ChunkPos(failedNeighbor).toLong()); }
    public CompletableFuture<Void> pendingDiskEdit() { return diskEdit; }

    public Map<String, Object> tick() {
        if (stage == 0) {
            for (BlockPos pos : anchors) {
                LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); chunks.add(chunk);
                TopologyService.onChunkLoaded(level, chunk);
                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                chunk.setBlockState(pos.above(), Blocks.AIR.defaultBlockState(), false);
                chunk.setBlockState(pos.below(), Blocks.STONE.defaultBlockState(), false);
            }
            if (alternate) {
                for (int x = 1528; x <= 1535; x++) for (int z = 1536; z <= 1539; z++) for (int y = 286; y <= 292; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    level.getChunk(pos.getX() >> 4, pos.getZ() >> 4).setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                }
                chunks.get(0).setBlockState(goal.below(), Blocks.STONE.defaultBlockState(), false);
                if ("1".equals(target.parameters().get("validCandidates"))) {
                    for (int x = goal.getX(); x <= start.getX() - 1; x++) chunks.get(0).setBlockState(new BlockPos(x, 288, 1537), Blocks.STONE.defaultBlockState(), false);
                }
            }
            changedTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (!settled() || level.getServer().getTickCount() <= changedTick) return null;
            for (LevelChunk chunk : chunks) { TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk); }
            stage = 2; return null;
        }
        if (stage == 2) {
            if (!settled()) return null;
            if (next < order.size()) {
                int index = order.get(next); BlockPos pos = anchors.get(index);
                String reason = target.parameters().get(alternate ? "reason" : index == 0 ? "start" : "goal");
                if (!induce(index, pos, reason)) return null;
                next++; faultStage = 0; return null;
            }
            query = service.requestMacroQuery(level, UUID.randomUUID(), start, goal, BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 3; return null;
        }
        if (!query.future().isDone()) return null;
        boolean success = query.future().join() != null;
        MacroSearch.Progress actual = query.progress();
        boolean candidate = alternate && "1".equals(target.parameters().get("validCandidates"));
        if (success != candidate) throw new AssertionError("Endpoint candidate continuation disagrees with the constructed input");
        MacroSearch.Failure reason = candidate ? MacroSearch.Failure.NONE : expected(target.parameters().get(alternate ? "reason" : "start"));
        SectionPos blocked = candidate ? null : SectionPos.of(alternate ? failedNeighbor : start);
        verify(actual, reason, blocked);
        if (alternate) {
            synchronized (resolutions) {
                if (resolutions.stream().noneMatch(row -> Boolean.TRUE.equals(row.get("fallbackStart"))
                        && ((Number) row.get("startCandidates")).intValue() == (candidate ? 1 : 0))) {
                    throw new AssertionError("The real resolver did not reach the required fallback candidate count");
                }
            }
        }
        if (mutated) {
            boolean rejected = false;
            try { verify(actual.withOutcome(actual.status(), actual.failure(), candidate ? SectionPos.of(goal) : null), reason, blocked); }
            catch (AssertionError correct) { rejected = true; }
            if (!rejected) throw new AssertionError("Endpoint validator did not reject a reason/section mismatch");
            evidence.put("mutationDetected", true);
        }
        evidence.put("failures", failures); evidence.put("progress", actual);
        synchronized (resolutions) { evidence.put("resolutions", List.copyOf(resolutions)); }
        evidence.put("request", TopologyValidationAccess.requestIdentity(query));
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }

    private boolean induce(int index, BlockPos pos, String reason) {
        SectionPos section = SectionPos.of(pos); var key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section);
        if (faultStage == 0) {
            if (reason.equals("U")) {
                TopologyService.onChunkUnloaded(level, chunks.get(index)); faultStage = 2;
            } else {
                Object loaded = ((Map<?, ?>) readField(service, "loadedSections")).get(key);
                long load = (long) readField(readField(loaded, "chunk"), "identity");
                fault = TopologyHandoffFaultProbe.install(service, level.dimension(), section, load);
                if (reason.equals("R")) {
                    TopologyValidationAccess.evictIdle(service); fault.fault("scan"); diskEdit = fault.corrupt("missing"); faultStage = 1;
                } else {
                    fault.fault("write");
                    BlockPos changed = pos.offset(5, 1, 5);
                    chunks.get(index).setBlockState(changed, chunks.get(index).getBlockState(changed).isAir()
                            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                    changedTick = level.getServer().getTickCount(); faultStage = 2;
                }
            }
            return false;
        }
        if (faultStage == 1) {
            if (!diskEdit.isDone()) return false;
            diskEdit.join(); diskEdit = null;
            invoke(service, "readPersisted", ((Map<?, ?>) readField(service, "loadedSections")).get(key));
            faultStage = 2; return false;
        }
        if (reason.equals("P")) {
            if (level.getServer().getTickCount() <= changedTick || ((Number) fault.snapshot().get("rejectedWrites")).intValue() != 1) return false;
            TopologyValidationAccess.evictIdle(service);
        }
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            String state = entry == null ? "UNLOADED" : readField(entry, "factState").toString();
            String wanted = reason.equals("R") ? "RECOVERY_FAILED" : reason.equals("P") ? "PERSISTENCE_UNAVAILABLE" : "UNLOADED";
            if (!state.equals(wanted)) return false;
            failures.add(Map.of("endpoint", alternate ? "alternate-neighbor" : index == 0 ? "start" : "goal", "reason", reason, "section", section.toString(),
                    "actualState", state, "fault", fault == null ? Map.of("unloadEntry", true) : fault.snapshot()));
        }
        if (fault != null) { fault.close(); fault = null; }
        return true;
    }

    static MacroSearch.Failure expected(String reason) {
        return switch (reason) {
            case "R" -> MacroSearch.Failure.FACTS_RECOVERY_FAILED;
            case "P" -> MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE;
            case "U" -> MacroSearch.Failure.UNAVAILABLE_CHUNK;
            default -> throw new IllegalArgumentException(reason);
        };
    }
    static void verify(MacroSearch.Progress actual, MacroSearch.Failure reason, SectionPos section) {
        if (actual.failure() != reason || !java.util.Objects.equals(section, actual.blockedSection())) throw new AssertionError("Endpoint result lost expected reason/section identity: " + actual);
    }
    public static void resolved(Object flight, Object starts, Object goals) {
        var probe = active;
        if (probe == null) return;
        Object key = readField(flight, "key");
        if (!probe.level.dimension().equals(readField(key, "dimension")) || !probe.start.equals(readField(key, "start"))
                || !probe.goal.equals(readField(key, "goal"))) return;
        synchronized (probe.resolutions) {
            if (probe.resolutions.size() >= 8) throw new IllegalStateException("unbounded endpoint resolution retries");
            probe.resolutions.add(Map.of("fallbackStart", readField(flight, "fallbackStart"), "fallbackGoal", readField(flight, "fallbackGoal"),
                    "startCandidates", ((List<?>) readField(starts, "candidates")).size(), "goalCandidates", ((List<?>) readField(goals, "candidates")).size()));
        }
    }
    private boolean settled() {
        if (!Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled"))) return false;
        for (BlockPos pos : anchors) {
            Object loaded = ((Map<?, ?>) readField(service, "loadedSections")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), SectionPos.of(pos)));
            if (loaded != null && ((boolean) readField(loaded, "readInFlight") || (boolean) readField(loaded, "persistencePending"))) return false;
        }
        return true;
    }
    @Override public void close() {
        if (fault != null) { fault.close(); fault = null; }
        if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); query = null; }
        for (LevelChunk chunk : chunks) TopologyService.onChunkUnloaded(level, chunk);
        synchronized (TopologyEndpointFailureScenario.class) { if (active == this) active = null; }
    }
}
