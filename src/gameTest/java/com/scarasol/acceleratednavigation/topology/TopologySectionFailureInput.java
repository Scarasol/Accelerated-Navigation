package com.scarasol.acceleratednavigation.topology;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Produces one real section failure and retains the original disk-edit completion through cleanup. */
final class TopologySectionFailureInput implements AutoCloseable {
    private final TopologyService service;
    private final ServerLevel level;
    private final LevelChunk chunk;
    private final TopologyWorkerRuntime.ClusterKey key;
    private final String reason;
    private TopologyHandoffFaultProbe fault;
    private CompletableFuture<Void> edit;
    private int stage, changedTick;
    private Map<String, Object> evidence;

    TopologySectionFailureInput(TopologyService service, ServerLevel level, LevelChunk chunk,
                               SectionPos section, String reason) {
        this.service = service; this.level = level; this.chunk = chunk;
        key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section); this.reason = reason;
    }

    boolean tick() {
        if (stage == 0) {
            if (reason.equals("U")) { TopologyService.onChunkUnloaded(level, chunk); stage = 2; }
            else {
                Object loaded = loaded();
                if (loaded == null) throw new AssertionError("loaded fault input lost its section");
                long load = (long) readField(readField(loaded, "chunk"), "identity");
                fault = TopologyHandoffFaultProbe.install(service, level.dimension(), key.section(), load);
                if (reason.equals("R")) {
                    TopologyValidationAccess.evictIdle(service); fault.fault("scan"); edit = fault.corrupt("missing"); stage = 1;
                } else {
                    fault.fault("write");
                    BlockPos changed = new BlockPos(key.section().minBlockX() + 3, key.section().minBlockY() + 3, key.section().minBlockZ() + 3);
                    chunk.setBlockState(changed, chunk.getBlockState(changed).isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                    changedTick = level.getServer().getTickCount(); stage = 2;
                }
            }
            return false;
        }
        if (stage == 1) {
            if (!edit.isDone()) return false;
            edit.join(); invoke(service, "readPersisted", loaded()); stage = 2; return false;
        }
        if (reason.equals("P")) {
            if (level.getServer().getTickCount() <= changedTick || ((Number) fault.snapshot().get("rejectedWrites")).intValue() != 1) return false;
            if ((boolean) readField(loaded(), "persistencePending")) return false;
            TopologyValidationAccess.evictIdle(service);
        }
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            String state = entry == null ? "UNLOADED" : readField(entry, "factState").toString();
            String wanted = reason.equals("R") ? "RECOVERY_FAILED" : reason.equals("P") ? "PERSISTENCE_UNAVAILABLE" : "UNLOADED";
            if (!wanted.equals(state)) return false;
            evidence = Map.of("section", key.section().toString(), "reason", reason, "actualState", state,
                    "fault", fault == null ? Map.of("unloadEntry", true) : fault.snapshot());
        }
        close(); return true;
    }

    private Object loaded() { return ((Map<?, ?>) readField(service, "loadedSections")).get(key); }
    Map<String, Object> evidence() { return evidence; }
    CompletableFuture<Void> pendingEdit() { return edit; }
    @Override public void close() { if (fault != null) { fault.close(); fault = null; } }
}
