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
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** F04 changes the real world after worker completion and before the server validates it. */
public final class TopologyFinalResultScenario implements AutoCloseable {
    private static final BlockPos START = new BlockPos(1793, 289, 1793), GOAL = new BlockPos(1837, 289, 1793);
    private static final BlockPos CHANGED = new BlockPos(1813, 292, 1799);
    private final TopologyService service;
    private final ServerLevel level;
    private final String terminal;
    private final boolean mutated;
    private final List<LevelChunk> chunks = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final RuntimeException injectedFailure = new IllegalStateException("F04 final-result callback fault");
    private TopologyResultValidationProbe resultProbe;
    private TopologySectionEventProbe failureProbe;
    private TopologyHandoffFaultProbe fault;
    private CompletableFuture<Void> diskEdit;
    private TopologyService.MacroRequest query;
    private Terminal beforeLate;
    private int stage, changedTick;

    private record Terminal(boolean cancelled, Throwable exception, MacroSearch.Corridor corridor,
                            MacroSearch.Progress progress, long attempt, int retries) { }

    public TopologyFinalResultScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; terminal = target.parameters().get("terminal"); mutated = "mutated".equals(control);
    }
    public Set<Long> chunkPositions() { return Set.of(ChunkPos.asLong(112, 112), ChunkPos.asLong(113, 112), ChunkPos.asLong(114, 112)); }
    public CompletableFuture<Void> pendingDiskEdit() { return diskEdit; }
    boolean prepareShutdown() {
        if (!terminal.equals("closing")) throw new IllegalStateException("not a closing control");
        tick(); return stage == 10 && failureProbe.deltaDelayed();
    }
    void releaseShutdown() {
        if (query != null && query.future().isDone() && beforeLate == null) beforeLate = currentTerminal();
        if (failureProbe != null) failureProbe.releaseDelta();
        if (resultProbe != null) resultProbe.release(null);
    }
    Map<String, Object> shutdownEvidence(boolean finished) {
        if (finished) {
            require(beforeLate != null && beforeLate.cancelled, "ServerStopping cancels the pending final result");
            for (int index = 0; index < resultProbe.count(); index++) resultProbe.replay(index);
            require(beforeLate.equals(currentTerminal()), "late failure and old result cannot revive the cancelled query after ServerStopped");
        }
        return Map.of("validationMessages", resultProbe.evidence(), "lateFailure", failureProbe.publications(),
                "fault", fault.snapshot(), "cancelled", query.future().isCancelled(), "request", TopologyValidationAccess.requestIdentity(query));
    }

    public Map<String, Object> tick() {
        if (stage == 0) {
            for (int x = 112; x <= 114; x++) {
                LevelChunk chunk = level.getChunk(x, 112); chunks.add(chunk); TopologyService.onChunkLoaded(level, chunk);
                for (int localX = 0; localX < 16; localX++) for (int z = 1792; z <= 1799; z++) for (int y = 287; y <= 294; y++) {
                    BlockPos pos = new BlockPos(x * 16 + localX, y, z);
                    chunk.setBlockState(pos, y == 288 && z == 1793 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                }
            }
            changedTick = tickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (tickCount() <= changedTick || !settled()) return null;
            for (LevelChunk chunk : chunks) { TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk); }
            stage = 2; return null;
        }
        if (stage == 2) {
            if (!settled()) return null;
            resultProbe = TopologyResultValidationProbe.watch(level.dimension(), START, GOAL, terminal.equals("stale-twice") ? 2 : 1);
            query = service.requestMacroQuery(level, UUID.randomUUID(), START, GOAL, BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 3; return null;
        }
        if (stage == 3) {
            if (!resultProbe.reached()) {
                if (query.future().isDone()) throw new AssertionError("F04 did not reach a real successful result before final validation");
                return null;
            }
            require(resultProbe.holdsSection(SectionPos.of(CHANGED)), "corridor carries the non-endpoint section stamp");
            if (terminal.equals("closing")) { prepareLateFailure(); stage = 9; return null; }
            switch (terminal) {
                case "cancel" -> query.cancel();
                case "endpoint-unload" -> TopologyService.onChunkUnloaded(level, chunks.get(0));
                case "stale-once", "stale-twice" -> chunks.get(1).setBlockState(CHANGED,
                        chunks.get(1).getBlockState(CHANGED).isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                case "exception" -> { }
                default -> throw new IllegalArgumentException(terminal);
            }
            changedTick = tickCount(); stage = 4; return null;
        }
        if (stage == 4) {
            if (tickCount() <= changedTick || !settled()) return null;
            resultProbe.release(terminal.equals("exception") ? injectedFailure : null);
            stage = terminal.equals("stale-twice") && resultProbe.count() == 1 ? 3 : 5;
            return null;
        }
        if (stage == 5) {
            if (!query.future().isDone() || !settled()) return null;
            beforeLate = currentTerminal();
            verify(beforeLate, terminal, injectedFailure);
            evidence.put("validationMessages", resultProbe.evidence()); evidence.put("request", TopologyValidationAccess.requestIdentity(query));
            prepareLateFailure();
            stage = 6; return null;
        }
        if (stage == 9) {
            if (!diskEdit.isDone()) return null;
            diskEdit.join(); diskEdit = null; invoke(service, "readPersisted", loadedMiddle()); stage = 10; return null;
        }
        if (stage == 10) return null;
        if (stage == 6) {
            if (!diskEdit.isDone()) return null;
            diskEdit.join(); diskEdit = null; invoke(service, "readPersisted", loadedMiddle()); stage = 7; return null;
        }
        if (stage == 7) {
            if (!failureProbe.deltaDelayed()) return null;
            require(((Number) fault.snapshot().get("scans")).intValue() == 1, "late notification originates from one actual failed recovery");
            evidence.put("lateFailure", failureProbe.publications()); evidence.put("fault", fault.snapshot());
            failureProbe.releaseDelta();
            for (int index = 0; index < resultProbe.count(); index++) resultProbe.replay(index);
            changedTick = tickCount(); stage = 8; return null;
        }
        if (tickCount() <= changedTick || !settled()) return null;
        require(beforeLate.equals(currentTerminal()), "late facts and repeated results cannot change the terminal request");
        if (mutated) {
            boolean rejected = false;
            Terminal corrupt = new Terminal(beforeLate.cancelled, beforeLate.exception, beforeLate.corridor,
                    beforeLate.progress.withOutcome(beforeLate.progress.status(), MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE, SectionPos.of(CHANGED)),
                    beforeLate.attempt, beforeLate.retries);
            try { require(beforeLate.equals(corrupt), "terminal identity must remain unchanged"); }
            catch (AssertionError expected) { rejected = true; }
            require(rejected, "qualification detects a substituted late-failure terminal"); evidence.put("mutationDetected", true);
        }
        evidence.put("terminal", terminal); evidence.put("progress", beforeLate.progress);
        evidence.put("cancelled", beforeLate.cancelled); evidence.put("exception", beforeLate.exception == null ? "" : beforeLate.exception.toString());
        evidence.put("staleRetries", beforeLate.retries); evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }

    private void prepareLateFailure() {
        Object loaded = loadedMiddle();
        require(loaded != null && !(boolean) readField(loaded, "recoveryAttempted"), "late-failure control has an unused recovery opportunity");
        failureProbe = TopologySectionEventProbe.watch(level.dimension(), SectionPos.of(CHANGED));
        failureProbe.delayNextFailure();
        fault = TopologyHandoffFaultProbe.install(service, level.dimension(), SectionPos.of(CHANGED),
                (long) readField(readField(loaded, "chunk"), "identity"));
        fault.fault("scan"); TopologyValidationAccess.evictIdle(service); diskEdit = fault.corrupt("missing");
    }

    private Terminal currentTerminal() {
        MacroSearch.Corridor corridor = null; Throwable exception = null;
        if (!query.future().isCancelled()) {
            try { corridor = query.future().join(); } catch (CompletionException failure) { exception = failure.getCause(); }
        }
        Object worker = readField(query, "workerRequest");
        return new Terminal(query.future().isCancelled(), exception, corridor, query.progress(),
                (long) readField(worker, "attempt"), (int) readField(worker, "staleRetries"));
    }
    private static void verify(Terminal actual, String expected, Throwable injected) {
        if (expected.equals("cancel")) { require(actual.cancelled, "cancellation remains cancellation"); return; }
        require(!actual.cancelled, "non-cancellation case must reach its own terminal");
        if (expected.equals("exception")) { require(actual.exception == injected, "program failure preserves the exact exception"); return; }
        require(actual.exception == null, "business terminal must not be a program exception");
        if (expected.equals("stale-once")) {
            require(actual.corridor != null && actual.progress.failure() == MacroSearch.Failure.NONE && actual.retries == 1,
                    "one stale final validation retries once and succeeds");
        } else {
            MacroSearch.Failure reason = expected.equals("stale-twice") ? MacroSearch.Failure.STALE_WORLD : MacroSearch.Failure.UNAVAILABLE_CHUNK;
            require(actual.corridor == null && actual.progress.failure() == reason, "failure preserves its terminal reason");
            if (expected.equals("stale-twice")) require(actual.retries == 1, "a second stale result cannot obtain another retry");
            else require(SectionPos.of(START).equals(actual.progress.blockedSection()), "unloaded endpoint owns the blocked section");
        }
    }
    private Object loadedMiddle() {
        return ((Map<?, ?>) readField(service, "loadedSections")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), SectionPos.of(CHANGED)));
    }
    private int tickCount() { return level.getServer().getTickCount(); }
    private boolean settled() {
        if (!Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled"))) return false;
        for (var item : ((Map<?, ?>) readField(service, "loadedSections")).entrySet()) {
            var key = (TopologyWorkerRuntime.ClusterKey) item.getKey();
            if (key.dimension().equals(level.dimension()) && chunkPositions().contains(key.section().chunk().toLong())
                    && ((boolean) readField(item.getValue(), "readInFlight") || (boolean) readField(item.getValue(), "persistencePending"))) return false;
        }
        return true;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        if (fault != null) { fault.close(); fault = null; }
        if (failureProbe != null) { failureProbe.close(); failureProbe = null; }
        if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); query = null; }
        if (resultProbe != null) { resultProbe.close(); resultProbe = null; }
        for (LevelChunk chunk : chunks) TopologyService.onChunkUnloaded(level, chunk);
    }
}
