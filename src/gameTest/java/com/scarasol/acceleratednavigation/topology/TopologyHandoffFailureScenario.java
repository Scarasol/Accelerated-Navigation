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

/** H02/H03 exercise physical faults, loaded-block mutations and public query consumers. */
public final class TopologyHandoffFailureScenario implements AutoCloseable {
    private final TopologyService service;
    private final ServerLevel level;
    private final ProductionRemediationPlan.Target target;
    private final boolean mutated, writeFailure, recoveryFailure;
    private final SectionPos section = SectionPos.of(100, 12, 100);
    private final TopologyWorkerRuntime.ClusterKey key;
    private final Object runtime;
    private final List<TopologyService.MacroRequest> queries = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private LevelChunk chunk;
    private TopologyHandoffFaultProbe fault;
    private TopologyHandoffProbe handoffs;
    private CompletableFuture<Void> diskEdit;
    private int stage, changedTick;
    private long load, version;
    private boolean closed;
    private boolean shutdownIntervened;

    public TopologyHandoffFailureScenario(TopologyService service, ServerLevel level,
                                          ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target;
        mutated = "mutated".equals(control); writeFailure = target.kind().equals("H03");
        recoveryFailure = target.kind().equals("H02/recovery") && !"collision".equals(parameter("fault"));
        key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section); runtime = readField(service, "runtime");
    }

    public ChunkPos chunkPosition() { return section.chunk(); }
    public CompletableFuture<Void> pendingDiskEdit() { return diskEdit; }

    boolean holdRecoveryForShutdown() { return stage >= 2 && !closed; }
    boolean recoveryQueuedForShutdown() {
        Object section = loaded();
        return section != null && (boolean) invoke(service, "recoveryQueued", section);
    }
    SectionPos shutdownSection() { return section; }
    boolean prepareRecoveryShutdown(String window) {
        if (stage < 5) { tick(); return false; }
        if (!window.startsWith("queued")) {
            if (!queriesDone() || !settled()) return false;
            verifyQueries(MacroSearch.Failure.NONE);
            require(count(fault.snapshot(), "scans") == 1 && count(fault.snapshot(), "publications") == 1,
                    "one synchronous recovery reaches scan and publication before shutdown");
            return true;
        }
        if (!shutdownIntervened) {
            if (!recoveryQueuedForShutdown() || (int) readField(loaded(), "foregroundWaiters") == 0) return false;
            require(count(fault.snapshot(), "scans") == 0, "the actual recovery remains queued and has not scanned");
            evidence.put("queuedRecovery", Map.of("load", load, "version", version,
                    "foregroundWaiters", readField(loaded(), "foregroundWaiters"), "fault", fault.snapshot()));
            if (window.equals("queued-cancel")) queries.forEach(TopologyService.MacroRequest::cancel);
            if (window.equals("queued-unload")) TopologyService.onChunkUnloaded(level, chunk);
            shutdownIntervened = true;
        }
        if (window.equals("queued-cancel")) {
            if ((int) readField(loaded(), "foregroundWaiters") != 0) return false;
            require(recoveryQueuedForShutdown(), "cancellation removes waiters while the loaded recovery stays queued");
            require(queries.stream().allMatch(query -> query.future().isCancelled()), "both query cancellations are terminal");
        }
        if (window.equals("queued-unload")) {
            require(loaded() == null && !recoveryQueuedForShutdown(), "unload removes the original load and its recovery");
            if (!queriesDone()) return false;
            verifyQueries(MacroSearch.Failure.UNAVAILABLE_CHUNK);
        }
        return true;
    }
    Map<String, Object> recoveryShutdownEvidence() {
        var result = new LinkedHashMap<String, Object>(evidence);
        result.put("faultObservations", fault.snapshot()); result.put("loadIdentity", load);
        result.put("section", section.toString()); result.put("pendingDiskEdit", diskEdit != null && !diskEdit.isDone());
        return Map.copyOf(result);
    }

    public Map<String, Object> tick() {
        if (closed) throw new IllegalStateException("closed fault scenario");
        if (stage == 0) {
            chunk = level.getChunk(section.x(), section.z());
            TopologyService.onChunkLoaded(level, chunk);
            for (int y = 0; y < 4; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                chunk.setBlockState(at(x, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
            }
            // A definite real change gives preparation a persisted complete baseline even on reused terrain.
            chunk.setBlockState(at(1, 1, 1), Blocks.STONE.defaultBlockState(), false);
            changedTick = tickNumber(); stage = 1; return null;
        }
        if (stage == 1) {
            if (!nextTick() || !settled()) return null;
            TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk);
            stage = 2; return null;
        }
        if (stage == 2) {
            if (!settled() || facts() == null) return null;
            Object loaded = loaded();
            require(!(boolean) readField(loaded, "recoveryAttempted"), "fresh load reads the valid preparation baseline");
            load = (long) readField(readField(loaded, "chunk"), "identity"); version = (long) readField(loaded, "version");
            evidence.put("loadIdentity", load); evidence.put("baselineVersion", version);
            fault = TopologyHandoffFaultProbe.install(service, level.dimension(), section, load);
            handoffs = TopologyHandoffProbe.install(level.dimension(), section, load, version + 1, null);
            handoffs.observeRuntime(service);
            if (!writeFailure || "evicted".equals(parameter("path"))) evict();
            if (writeFailure) fault.fault("write");
            else if (target.kind().equals("H02/recovery")) {
                diskEdit = fault.corrupt("missing"); fault.fault(parameter("fault"));
            } else prepareReadFault();
            stage = 3; return null;
        }
        if (stage == 3) {
            if (diskEdit != null && !diskEdit.isDone()) return null;
            if (diskEdit != null) diskEdit.join();
            chunk.setBlockState(at(1, 1, 1), Blocks.AIR.defaultBlockState(), false);
            changedTick = tickNumber(); stage = 4; return null;
        }
        if (stage == 4) {
            if (!nextTick()) return null;
            requestPair(); stage = 5; return null;
        }
        if (stage == 5) {
            if (!queriesDone() || !settled()) return null;
            verifyQueries(recoveryFailure ? MacroSearch.Failure.FACTS_RECOVERY_FAILED : MacroSearch.Failure.NONE);
            Map<String, Object> first = fault.snapshot(); evidence.put("firstFailureCycle", first);
            require(count(first, "scans") == (writeFailure ? 0 : 1), "one scan for rejected baselines, none after formed write failure");
            if (writeFailure) {
                require(count(first, "rejectedWrites") == 1, "one actual write attempt failed");
                require((boolean) readField(loaded(), "persistenceUnavailable"), "write failure retains a compact terminal marker");
            } else {
                String expected = switch (parameter("fault")) {
                    case "truncated" -> "CORRUPT";
                    case "version" -> "VERSION_MISMATCH";
                    case "io" -> "IO_FAILURE";
                    default -> "BASE_MISSING";
                };
                require(((List<?>) first.get("formations")).stream().filter(expected::equals).count() == 1,
                        "the actual delta formation rejected the selected baseline once");
            }
            if ("collision".equals(parameter("fault"))) {
                require(count(first, "collisionFailures") == 1, "one real collision classification failure was reached");
                require(facts().flags(4 | 4 << 4 | 1 << 8) == 11, "collision failure preserves the approved conservative four bits");
                require((facts().flags(4 | 4 << 4 | 2 << 8) & 2) != 0, "degraded collision supports the cell above");
            }
            if (recoveryFailure) {
                require(facts() == null, "failed recovery never publishes partial facts");
                fault.fault(null);
                chunk.setBlockState(at(2, 1, 1), Blocks.STONE.defaultBlockState(), false);
                changedTick = tickNumber(); stage = 11; return null;
            }
            evidence.put("formedReferenceEvents", handoffs.events());
            TopologyHandoffScenario.verifyReferences(handoffs.events());
            if (writeFailure && "evicted".equals(parameter("afterWriteFailure"))) {
                evict(); requestPair(); stage = 10; return null;
            }
            fault.fault(null);
            chunk.setBlockState(at(2, 1, 1), Blocks.STONE.defaultBlockState(), false);
            changedTick = tickNumber(); stage = 6; return null;
        }
        if (stage == 6) {
            if (!nextTick() || !settled()) return null;
            require(!(boolean) readField(loaded(), "persistenceUnavailable"), "a new version with complete facts can persist again");
            require(facts() != null, "new version has a complete baseline");
            if (writeFailure) {
                require(count(fault.snapshot(), "writes") == 2, "retained complete baseline permits exactly one new-version write");
                reload(); stage = 12; return null;
            }
            if (target.kind().equals("H02/recovery")) return finish();
            evidence.put("beforeExhaustedRead", fault.snapshot());
            evict(); prepareReadFault(); stage = 7; return null;
        }
        if (stage == 7) {
            if (diskEdit != null && !diskEdit.isDone()) return null;
            if (diskEdit != null) diskEdit.join();
            requestPair(); stage = 8; return null;
        }
        if (stage == 8) {
            if (!queriesDone() || !settled()) return null;
            verifyQueries(MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE);
            require(count(fault.snapshot(), "scans") == 1, "exhausted recovery does not rescan after eviction and another rejected read");
            require((boolean) readField(loaded(), "persistenceUnavailable"), "ordinary reread exhaustion is published to the service and worker");
            fault.fault(null);
            chunk.setBlockState(at(3, 1, 1), Blocks.STONE.defaultBlockState(), false);
            changedTick = tickNumber(); stage = 9; return null;
        }
        if (stage == 9 || stage == 11) {
            if (!nextTick() || !settled()) return null;
            requestPair(); stage = stage == 9 ? 10 : 13; return null;
        }
        if (stage == 10 || stage == 13) {
            if (!queriesDone() || !settled()) return null;
            verifyQueries(stage == 13 ? MacroSearch.Failure.FACTS_RECOVERY_FAILED : MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE);
            require(count(fault.snapshot(), "scans") == (writeFailure ? 0 : 1), "ordinary changes and repeated queries cannot rescan");
            if (writeFailure) {
                fault.fault(null);
                chunk.setBlockState(at(2, 1, 1), Blocks.STONE.defaultBlockState(), false);
                changedTick = tickNumber(); stage = 14; return null;
            }
            reload(); stage = 12; return null;
        }
        if (stage == 14) {
            if (!nextTick() || !settled()) return null;
            require(count(fault.snapshot(), "writes") == 1, "evicted unavailable facts cannot start a write from only a delta");
            reload(); stage = 12; return null;
        }
        if (stage == 12) {
            if (!settled()) return null;
            long currentLoad = (long) readField(readField(loaded(), "chunk"), "identity");
            require(currentLoad != load, "reload replaces the failed load identity");
            evidence.put("reloadedIdentity", currentLoad);
            requestPair(); stage = 15; return null;
        }
        if (!queriesDone() || !settled()) return null;
        verifyQueries(MacroSearch.Failure.NONE);
        return finish();
    }

    private void prepareReadFault() {
        diskEdit = null;
        if ("io".equals(parameter("fault"))) fault.fault("io");
        else diskEdit = fault.corrupt(parameter("fault"));
    }
    private void evict() {
        TopologyValidationAccess.evictIdle(service);
        require(facts() == null, "the original cache owner evicted complete target facts");
        ((TopologyStore) readField(service, "store")).unload(level.dimension(), section.chunk());
    }
    private void reload() { fault.fault(null); TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk); }
    private void requestPair() {
        queries.clear();
        for (int i = 0; i < 2; i++) queries.add(service.requestMacroQuery(level, UUID.randomUUID(), at(6, 1, 6), at(8, 1, 6),
                BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE));
    }
    private boolean queriesDone() { return queries.stream().allMatch(query -> query.future().isDone()); }
    private void verifyQueries(MacroSearch.Failure expected) {
        for (var query : queries) {
            MacroSearch.Corridor result = query.future().join();
            require(query.progress().failure() == expected, "query failure agrees with its actual failure stage: " + expected);
            require(expected == MacroSearch.Failure.NONE ? result != null : result == null, "query result has the expected terminal shape");
            if (expected != MacroSearch.Failure.NONE) require(section.equals(query.progress().blockedSection()), "reason and failed section belong to the same dependency");
            TopologyValidationAccess.forgetRequest(query);
        }
    }
    private Map<String, Object> finish() {
        Map<String, Object> observed = fault.snapshot();
        evidence.put("faultObservations", observed);
        require(count(observed, "scans") == (writeFailure ? 0 : 1), "fault-run scan total is bounded by the load identity");
        if (mutated) {
            Map<String, Object> altered = new LinkedHashMap<>(observed); altered.put("scans", count(observed, "scans") + 1);
            boolean rejected = false;
            try { require(count(altered, "scans") == (writeFailure ? 0 : 1), "fault-run scan total is bounded by the load identity"); }
            catch (AssertionError expected) { rejected = true; }
            require(rejected, "qualification detects a deliberately extra recovery scan"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }
    private Object loaded() { return ((Map<?, ?>) readField(service, "loadedSections")).get(key); }
    private BaseClusterTopology.PackedFacts facts() {
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            return entry == null ? null : (BaseClusterTopology.PackedFacts) readField(entry, "facts");
        }
    }
    private boolean settled() {
        Object loaded = loaded();
        if (loaded != null && ((boolean) readField(loaded, "readInFlight")
                || !((Map<?, ?>) readField(loaded, "pendingChanges")).isEmpty()
                || (boolean) invoke(service, "recoveryQueued", loaded))) return false;
        return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(section.chunk().toLong())).get("settled"));
    }
    private int tickNumber() { return level.getServer().getTickCount(); }
    private boolean nextTick() { return tickNumber() > changedTick; }
    private BlockPos at(int x, int y, int z) { return new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z); }
    private String parameter(String name) { return target.parameters().get(name); }
    private static int count(Map<String, Object> values, String key) { return ((Number) values.get(key)).intValue(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var query : queries) { if (!query.future().isDone()) query.cancel(); TopologyValidationAccess.forgetRequest(query); }
        if (fault != null) fault.close();
        if (handoffs != null) handoffs.close();
        if (chunk != null) TopologyService.onChunkUnloaded(level, chunk);
    }
}
