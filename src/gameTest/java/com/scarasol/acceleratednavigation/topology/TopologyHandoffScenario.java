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

/** Handoff controls change actual loaded blocks and consume facts through the public query entry. */
public final class TopologyHandoffScenario implements AutoCloseable {
    private final TopologyService service;
    private final ServerLevel level;
    private final TopologyValidationAccess.ScenarioTickets tickets;
    private final ProductionRemediationPlan.Target target;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final SectionPos section = SectionPos.of(96, 12, 96);
    private final TopologyWorkerRuntime.ClusterKey key;
    private final Object runtime;
    private final boolean mutated, competing;
    private LevelChunk chunk;
    private TopologyHandoffProbe probe;
    private TopologySectionEventProbe sectionEvents;
    private TopologyService.MacroRequest query;
    private TopologyValidationAccess.Capture capture;
    private int stage, changedTick;
    private long load, baselineVersion;
    private boolean reloaded, closed;
    private int reloadStage;

    public TopologyHandoffScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; tickets = new TopologyValidationAccess.ScenarioTickets(level); this.target = target;
        key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section);
        runtime = readField(service, "runtime"); mutated = "mutated".equals(control);
        competing = target.kind().equals("H01") || "v2".equals(parameter("competition"));
        if (!List.of("H01", "H04", "H05").contains(target.kind())) throw new IllegalArgumentException("handoff executor not installed: " + target.kind());
    }

    public ChunkPos chunkPosition() { return section.chunk(); }

    boolean prepareShutdown() {
        if (stage < 2) tick();
        return stage == 2 && probe.reached();
    }
    Map<String, Object> shutdownEvidence() {
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        result.put("handoffs", probe.events()); result.put("timedOut", probe.timedOut());
        return Map.copyOf(result);
    }
    SectionPos section() { return section; }
    long loadIdentity() { return load; }
    void releaseShutdown() { if (probe != null && probe.reached()) probe.release(); }

    boolean prepareReloadShutdown(String terminal) {
        if (stage < 2) { tick(); return false; }
        if (probe.timedOut()) throw new AssertionError("old-load handoff release timed out");
        if (reloadStage == 0) {
            if (!probe.reached() || !nextTick()) return false;
            require(((Number) TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(chunk.getPos().toLong()))
                    .get("holds")).longValue() > 0, "the accepted old load still owns its pending terminal");
            evidence.put("beforeReload", canonical());
            tickets.unload(chunk); tickets.loaded(chunk);
            long nextLoad = (long) readField(readField(loaded(), "chunk"), "identity");
            require(nextLoad != load && !(boolean) readField(loaded(), "recoveryAttempted"),
                    "the new load has its own unused validation and recovery opportunity");
            evidence.put("newLoadIdentity", nextLoad);
            if (terminal.equals("coalesced")) writeSecondVersion();
            else probe.release();
            changedTick = level.getServer().getTickCount(); reloadStage = 1; return false;
        }
        if (reloadStage == 1) {
            String expected = switch (terminal) { case "written" -> "WRITTEN"; case "failed" -> "IO_FAILURE"; default -> "COALESCED"; };
            if (probe.events().stream().noneMatch(event -> "store-terminal".equals(event.get("event")))) return false;
            require(probe.events().stream().filter(event -> "store-terminal".equals(event.get("event")))
                    .allMatch(event -> expected.equals(event.get("status"))), "the old receipt reached its real " + expected + " terminal");
            if (terminal.equals("coalesced")) {
                Map<String, Object> before = canonical(); probe.release();
                require(before.equals(canonical()), "late old-load writing cannot replace the successor facts");
            }
            if (!nextTick() || !settled()) return false;
            if (currentFacts() == null) { invoke(service, "readPersisted", loaded()); return false; }
            verifyCells();
            require(((Number) canonical().get("load")).longValue() == ((Number) evidence.get("newLoadIdentity")).longValue(),
                    "only the new load is queryable after the old terminal");
            require(sectionEvents.scans().values().stream().allMatch(count -> count <= 1), "each load scans at most once");
            query = service.requestMacroQuery(level, UUID.randomUUID(), at(5, 1, 5), at(7, 1, 5),
                    BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            reloadStage = 2; return false;
        }
        if (!query.future().isDone()) return false;
        require(query.future().join() != null, "a public query consumes the reloaded facts after the old terminal");
        evidence.put("afterReload", canonical()); evidence.put("consumer", TopologyValidationAccess.requestIdentity(query));
        evidence.put("scans", sectionEvents.scans());
        return true;
    }

    public Map<String, Object> tick() {
        if (closed) throw new IllegalStateException("closed handoff scenario");
        if (stage == 0) {
            tickets.hold(Set.of(chunkPosition().toLong()));
            chunk = level.getChunk(section.x(), section.z());
            tickets.loaded(chunk);
            for (int y = 0; y < 4; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                chunk.setBlockState(at(x, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
            }
            changedTick = level.getServer().getTickCount(); stage = 1;
            return null;
        }
        if (stage == 1) {
            if (!nextTick() || !settled()) return null;
            Object loaded = loaded();
            require(loaded != null && readField(readField(loaded, "chunk"), "chunk") == chunk,
                    "handoff preparation retains the actual loaded target chunk");
            load = (long) readField(readField(loaded, "chunk"), "identity");
            baselineVersion = (long) readField(loaded, "version");
            BaseClusterTopology.PackedFacts facts = currentFacts();
            if (facts == null) { invoke(service, "readPersisted", loaded); return null; }
            evidence.put("loadIdentity", load); evidence.put("baselineVersion", baselineVersion);
            evidence.put("baselineFactsToken", TopologyValidationAccess.token(facts));
            if ("evicted".equals(parameter("path"))) {
                TopologyValidationAccess.evictIdle(service);
                require(currentFacts() == null, "real eviction removes the target's idle canonical facts");
            }
            String barrier = target.kind().equals("H05") ? "B1" : target.kind().equals("H01") ? "B5" : parameter("barrier");
            probe = TopologyHandoffProbe.install(level.dimension(), section, load, baselineVersion + 1, barrier);
            probe.observeRuntime(service);
            if (target.kind().equals("H05") || parameter("reloadTerminal") != null) sectionEvents = TopologySectionEventProbe.watch(level.dimension(), section);
            chunk.setBlockState(at(1, 1, 1), Blocks.STONE.defaultBlockState(), false);
            chunk.setBlockState(at(1, 1, 1), Blocks.AIR.defaultBlockState(), false);
            chunk.setBlockState(at(2, 1, 1), Blocks.STONE.defaultBlockState(), false);
            changedTick = level.getServer().getTickCount(); stage = 2;
            return null;
        }
        if (target.kind().equals("H05")) return invalidSequence();
        if (stage == 2) {
            if (!probe.reached() || !nextTick()) return null;
            evidence.put("paused", TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(chunk.getPos().toLong())));
            if (competing) writeSecondVersion();
            changedTick = level.getServer().getTickCount(); stage = 3;
            return null;
        }
        if (stage == 3) {
            if (!nextTick()) return null;
            if ("old-load".equals(parameter("delivery"))) {
                tickets.unload(chunk); tickets.loaded(chunk);
                long newLoad = (long) readField(readField(loaded(), "chunk"), "identity");
                require(newLoad != load, "reload establishes an independent load identity");
                evidence.put("newLoadIdentity", newLoad); reloaded = true;
            }
            probe.release(); stage = 4;
            return null;
        }
        if (stage == 4) {
            if (!settled()) return null;
            stage = 6;
        }
        if (stage == 6) {
            String delivery = parameter("delivery");
            if ("duplicate-v1".equals(delivery)) {
                require(probe.completionCount() > 0, "a real immutable completion exists for replay");
                Map<String, Object> before = canonical();
                probe.replayCompletion(0);
                require(before.equals(canonical()), "duplicate or older acknowledgements cannot change current canonical facts");
                evidence.put("duplicateReplaySequence", List.of("v1"));
            }
            verifyCells();
            evidence.put("canonical", canonical()); evidence.put("handoffs", probe.events());
            if (!reloaded) verifyReferences(probe.events());
            if (mutated && !reloaded) {
                List<Map<String, Object>> altered = new java.util.ArrayList<>(probe.events());
                boolean changed = false;
                for (int index = 0; index < altered.size(); index++) {
                    Map<String, Object> event = altered.get(index);
                    if (!"B6".equals(event.get("barrier"))) continue;
                    Map<String, Object> replacement = new LinkedHashMap<>(event);
                    replacement.put("factsToken", ((Number) event.get("factsToken")).longValue() + 1);
                    altered.set(index, Map.copyOf(replacement)); changed = true; break;
                }
                require(changed, "real write observation exists for the reference mutation");
                boolean rejected = false;
                try { verifyReferences(altered); }
                catch (AssertionError expected) { rejected = true; }
                require(rejected, "fixture rejects its deliberately wrong reference"); evidence.put("mutationDetected", true);
            }
            if (mutated && reloaded) {
                boolean rejected = false;
                try { require(load == ((Number) canonical().get("load")).longValue(), "deliberately retained old load identity"); }
                catch (AssertionError expected) { rejected = true; }
                require(rejected, "fixture rejects the deliberately stale load identity"); evidence.put("mutationDetected", true);
            }
            capture = TopologyValidationAccess.beginCapture();
            query = service.requestMacroQuery(level, UUID.randomUUID(), at(5, 1, 5), at(7, 1, 5),
                    BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 7; return null;
        }
        if (!query.future().isDone()) return null;
        require(query.future().join() != null, "the real request consumer can use the final facts");
        TopologyValidationAccess.endCapture(capture);
        evidence.put("consumerObjects", capture.used()); capture = null;
        evidence.put("request", TopologyValidationAccess.requestIdentity(query));
        TopologyValidationAccess.forgetRequest(query); query = null;
        evidence.put("outcome", "PASS"); evidence.put("reached", true);
        return Map.copyOf(evidence);
    }

    private void writeSecondVersion() {
        chunk.setBlockState(at(1, 1, 1), Blocks.STONE.defaultBlockState(), false);
        chunk.setBlockState(at(1, 2, 1), Blocks.STONE.defaultBlockState(), false);
    }

    private Map<String, Object> invalidSequence() {
        boolean gap = "version-gap".equals(parameter("fault"));
        if (stage == 2) {
            if (!probe.reached() || !nextTick()) return null;
            sectionEvents.delayNextDelta(); writeSecondVersion();
            changedTick = level.getServer().getTickCount(); stage = 3; return null;
        }
        if (stage == 3) {
            if (!nextTick() || !sectionEvents.deltaDelayed()) return null;
            evidence.put("heldInput", sectionEvents.publications());
            if (gap) {
                chunk.setBlockState(at(3, 1, 1), Blocks.STONE.defaultBlockState(), false);
                changedTick = level.getServer().getTickCount(); stage = 4; return null;
            }
            tickets.unload(chunk); tickets.loaded(chunk);
            long newLoad = (long) readField(readField(loaded(), "chunk"), "identity");
            require(newLoad != load, "the second load has an independent identity");
            evidence.put("newLoadIdentity", newLoad);
            sectionEvents.releaseDelta(); probe.release(); stage = 5; return null;
        }
        if (stage == 4) {
            if (!nextTick()) return null;
            synchronized (readField(runtime, "runtimeLock")) {
                Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
                if ((long) readField(entry, "revision") != baselineVersion + 3) return null;
                require(readField(entry, "facts") == null, "a missing intermediate version cannot publish partial complete facts");
                require(readField(entry, "decision") != null, "the predecessor still owns its executing input");
                Object tail = readField(entry, "tail");
                require(tail != null && ((TopologyStore.SectionDelta) tail).originalVersion() == baselineVersion + 2,
                        "the later input retains its real baseline instead of bridging the missing version");
                evidence.put("rejectedGap", Map.of("executingVersion", baselineVersion + 1,
                        "tailBaseline", ((TopologyStore.SectionDelta) tail).originalVersion(), "partialPublished", false));
            }
            probe.release(); sectionEvents.releaseDelta(); stage = 5; return null;
        }
        if (stage == 5) {
            if (!settled()) return null;
            if (currentFacts() == null) { invoke(service, "readPersisted", loaded()); return null; }
            verifyCells();
            long currentLoad = (long) readField(readField(loaded(), "chunk"), "identity");
            require(((Number) canonical().get("load")).longValue() == currentLoad, "old-load input never becomes current facts");
            require(sectionEvents.scans().values().stream().allMatch(count -> count <= 1), "each load recovers at most once");
            capture = TopologyValidationAccess.beginCapture();
            query = service.requestMacroQuery(level, UUID.randomUUID(), at(5, 1, 5), at(7, 1, 5),
                    BaseClusterTopology.Channel.GROUND, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 6; return null;
        }
        if (!query.future().isDone()) return null;
        require(query.future().join() != null, "complete facts after rejection serve a real request");
        TopologyValidationAccess.endCapture(capture); evidence.put("consumerObjects", capture.used()); capture = null;
        evidence.put("request", TopologyValidationAccess.requestIdentity(query));
        TopologyValidationAccess.forgetRequest(query); query = null;
        evidence.put("canonical", canonical()); evidence.put("handoffs", probe.events());
        evidence.put("publications", sectionEvents.publications()); evidence.put("scans", sectionEvents.scans());
        if (mutated) {
            var altered = new java.util.ArrayList<>(probe.events());
            for (int index = 0; index < altered.size(); index++) {
                if (!"B6".equals(altered.get(index).get("barrier"))) continue;
                Map<String, Object> replacement = new LinkedHashMap<>(altered.get(index));
                replacement.put("factsToken", ((Number) replacement.get("factsToken")).longValue() + 1);
                altered.set(index, Map.copyOf(replacement)); break;
            }
            boolean rejected = false;
            try { verifyReferences(altered); } catch (AssertionError expected) { rejected = true; }
            require(rejected, "reference validator rejects a deliberately partial handoff"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }

    private void verifyCells() {
        BaseClusterTopology.PackedFacts facts = currentFacts();
        require(facts != null, "final version has complete canonical facts");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int expected = TopologyGenerationProbe.independentFlags(chunk, at(x, y, z));
            require(facts.flags(x | z << 4 | y << 8) == expected, "final cell agrees with independent world oracle at " + x + "," + y + "," + z);
        }
    }

    static void verifyReferences(List<Map<String, Object>> events) {
        require(events.stream().anyMatch(event -> "B5".equals(event.get("barrier"))), "the actual formation observer was reached");
        for (var formed : events) {
            if (!"B5".equals(formed.get("barrier")) || Boolean.TRUE.equals(formed.get("released"))) continue;
            var write = events.stream().filter(event -> "B6".equals(event.get("barrier"))
                    && event.get("generation").equals(formed.get("generation"))).findFirst().orElseThrow(() -> new AssertionError("formation has no actual write handoff"));
            require(formed.get("factsToken").equals(write.get("factsToken")), "formation and writing share the same full facts reference");
            require(Boolean.TRUE.equals(write.get("writeFactsPinned")), "publication pins the exact write reference before actual write");
            if (write.get("version").equals(write.get("publishedVersion"))) {
                require(write.get("factsToken").equals(write.get("publishedFactsToken")), "current publication shares the writing reference");
            }
        }
    }

    private Map<String, Object> canonical() {
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            if (entry == null) return Map.of("missing", true);
            return Map.of("version", readField(entry, "revision"), "load", readField(entry, "loadIdentity"),
                    "factsToken", TopologyValidationAccess.token(readField(entry, "facts")), "state", readField(entry, "factState").toString());
        }
    }
    private BaseClusterTopology.PackedFacts currentFacts() {
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            return entry == null ? null : (BaseClusterTopology.PackedFacts) readField(entry, "facts");
        }
    }
    public Map<String, Object> observation() {
        Map<String, Object> result = sectionObservation(service, level, section, chunk, load);
        result.put("stage", stage); result.put("reloadStage", reloadStage); result.put("changedTick", changedTick);
        result.put("phase", switch (stage) {
            case 0 -> "prepare-blocks"; case 1 -> "preparation-settlement-and-baseline";
            case 2 -> "target-barrier"; case 3 -> "next-tick-before-release";
            case 4, 5 -> "released-work-settlement"; case 6 -> "canonical-verification";
            default -> "public-consumer";
        });
        result.put("evidence", new LinkedHashMap<>(evidence));
        if (probe != null) { result.put("handoffs", probe.events()); result.put("probeTimedOut", probe.timedOut()); }
        if (query != null) result.put("query", queryObservation(query));
        return result;
    }

    static Map<String, Object> sectionObservation(TopologyService service, ServerLevel level, SectionPos section,
                                                   LevelChunk written, long expectedLoad) {
        Map<String, Object> result = new LinkedHashMap<>();
        var key = new TopologyWorkerRuntime.ClusterKey(level.dimension(), section);
        LevelChunk live = level.getChunkSource().getChunkNow(section.x(), section.z());
        result.put("section", section.toString()); result.put("serverTick", level.getServer().getTickCount());
        result.put("expectedLoad", expectedLoad); result.put("writtenChunk", TopologyValidationAccess.token(written));
        result.put("worldChunk", TopologyValidationAccess.token(live)); result.put("sameWorldChunk", written != null && written == live);
        Object loaded = ((Map<?, ?>) readField(service, "loadedSections")).get(key);
        result.put("loaded", loaded != null);
        if (loaded != null) {
            Object owner = readField(loaded, "chunk");
            result.put("currentLoad", readField(owner, "identity"));
            result.put("serviceChunk", TopologyValidationAccess.token(readField(owner, "chunk")));
            for (String field : List.of("version", "changedTick", "changeOriginalVersion", "persistencePending", "persistenceUnavailable",
                    "foregroundWaiters", "readRequest", "readInFlight", "recoveryFailed", "recoveryAttempted", "implicitAllAirBase")) {
                result.put(field, readField(loaded, field));
            }
            result.put("pendingChanges", new LinkedHashMap<>((Map<?, ?>) readField(loaded, "pendingChanges")));
            result.put("recoveryQueued", invoke(service, "recoveryQueued", loaded));
        }
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            Map<String, Object> facts = new LinkedHashMap<>(); facts.put("present", entry != null);
            if (entry != null) {
                for (String field : List.of("loadIdentity", "revision", "factState", "writeRequired", "baselineFailed", "persistenceUnavailableVersion")) {
                    facts.put(field, readField(entry, field));
                }
                for (String field : List.of("facts", "pendingFactsReference", "decision", "tail")) facts.put(field + "Token", TopologyValidationAccess.token(readField(entry, field)));
                facts.put("factWaiters", ((Set<?>) readField(entry, "factWaiters")).size());
                facts.put("pinnedFacts", ((Map<?, ?>) readField(entry, "pinnedFacts")).size());
            }
            result.put("runtime", facts);
        }
        result.put("settlement", TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(section.chunk().toLong())));
        return result;
    }

    static Map<String, Object> queryObservation(TopologyService.MacroRequest query) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identity", TopologyValidationAccess.requestIdentity(query)); result.put("progress", query.progress());
        result.put("done", query.future().isDone()); result.put("cancelled", query.future().isCancelled());
        result.put("exceptional", query.future().isCompletedExceptionally());
        if (query.future().isDone() && !query.future().isCompletedExceptionally()) result.put("hasCorridor", query.future().getNow(null) != null);
        return result;
    }

    private boolean settled() { return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(section.chunk().toLong())).get("settled")); }
    private Object loaded() { return ((Map<?, ?>) readField(service, "loadedSections")).get(key); }
    private boolean nextTick() { return level.getServer().getTickCount() > changedTick; }
    private BlockPos at(int x, int y, int z) { return new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z); }
    private String parameter(String name) { return target.parameters().get(name); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @Override public void close() {
        try {
            if (closed) return;
            closed = true;
            try { if (probe != null) probe.close(); }
            finally {
                if (sectionEvents != null) { sectionEvents.close(); sectionEvents = null; }
                if (capture != null) { TopologyValidationAccess.endCapture(capture); capture = null; }
                if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); query = null; }
                if (chunk != null) tickets.unload(chunk);
            }
        } finally { tickets.close(); }
    }
}
