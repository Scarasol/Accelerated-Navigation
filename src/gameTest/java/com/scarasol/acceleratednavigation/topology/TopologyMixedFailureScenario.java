package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.Comparator;
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

/** Real tunnels, original public queries and original dependency owners; only notification delivery is held. */
public final class TopologyMixedFailureScenario implements AutoCloseable {
    private static volatile TopologyMixedFailureScenario active;
    private static final BlockPos START = new BlockPos(8, 6, -8), GOAL = new BlockPos(-14, 6, 24);
    private static final List<SectionPos> SECTIONS = List.of(SectionPos.of(0, 0, 0), SectionPos.of(1, 0, 0), SectionPos.of(0, 0, 1));
    private final TopologyService service;
    private final ServerLevel level;
    private final Object runtime;
    private final ProductionRemediationPlan.Target target;
    private final boolean alternative, rejected, nodeControl, mutated;
    private final Map<SectionPos, String> reasons = new LinkedHashMap<>();
    private final List<SectionPos> delivery = new ArrayList<>();
    private final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private final List<TopologySectionFailureInput> inputs = new ArrayList<>();
    private final List<Map<String, Object>> faults = new ArrayList<>(), notifications = new ArrayList<>(), rejections = new ArrayList<>();
    private final List<MacroSearch.DependencyKey> requested = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private TopologyService.MacroRequest query;
    private MacroSearch search;
    private boolean armed, closed, released;
    private int stage, changedTick, next;

    public TopologyMixedFailureScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target; runtime = readField(service, "runtime");
        alternative = target.kind().equals("F03/alternative"); rejected = target.kind().equals("F03/rejected");
        nodeControl = target.kind().equals("F02/node-tie"); mutated = control.equals("mutated");
        if (target.parameters().containsKey("placement")) {
            String[] placement = target.parameters().get("placement").split(",");
            for (int index = 0; index < placement.length; index++) reasons.put(SECTIONS.get(index), placement[index]);
            for (String reason : target.parameters().get("notification").split(",")) {
                delivery.add(reasons.entrySet().stream().filter(entry -> entry.getValue().equals(reason)).findFirst().orElseThrow().getKey());
            }
        } else if (target.kind().equals("F02/section-tie")) {
            SECTIONS.forEach(section -> reasons.put(section, target.parameters().get("reason")));
            for (String position : target.parameters().get("order").split(";")) {
                String[] xyz = position.split(","); delivery.add(SectionPos.of(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
            }
        } else { reasons.put(SECTIONS.get(0), target.parameters().get("reason")); delivery.add(SECTIONS.get(0)); }
        synchronized (TopologyMixedFailureScenario.class) {
            if (active != null) throw new IllegalStateException("overlapping mixed-failure controls"); active = this;
        }
    }

    public Set<Long> chunkPositions() {
        Set<Long> positions = new java.util.HashSet<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 2; z++) positions.add(ChunkPos.asLong(x, z));
        return Set.copyOf(positions);
    }
    public CompletableFuture<Void> pendingDiskEdit() {
        return CompletableFuture.allOf(inputs.stream().map(TopologySectionFailureInput::pendingEdit)
                .filter(java.util.Objects::nonNull).toArray(CompletableFuture[]::new));
    }

    public Map<String, Object> tick() {
        if (stage == 0) {
            List<Long> positions = new ArrayList<>(chunkPositions()); positions.sort(Long::compare);
            if ("reverse".equals(target.parameters().get("order"))) java.util.Collections.reverse(positions);
            for (long packed : positions) {
                ChunkPos pos = new ChunkPos(packed); LevelChunk chunk = level.getChunk(pos.x, pos.z); chunks.put(packed, chunk);
                TopologyService.onChunkLoaded(level, chunk);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    chunk.setBlockState(new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z), Blocks.STONE.defaultBlockState(), false);
                }
            }
            carve(START); carve(GOAL);
            if (!rejected) {
                tunnel(8, -8, 8, 8);
                tunnel(-8, -8, 24, -8);
                tunnel(24, -8, 24, reasons.size() > 1 ? 8 : -8);
                tunnel(-8, -8, -8, 24);
                if (reasons.size() == 3) tunnel(-8, 24, 8, 24);
                if (nodeControl) tunnel(-8, 8, 8, 8);
                if (alternative) {
                    tunnel(-8, 24, -8, 40); tunnel(-14, 40, -8, 40); tunnel(-14, 24, -14, 40);
                }
            }
            changedTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (level.getServer().getTickCount() <= changedTick || !settled()) return null;
            verifyCells();
            for (LevelChunk chunk : chunks.values()) { TopologyService.onChunkUnloaded(level, chunk); TopologyService.onChunkLoaded(level, chunk); }
            stage = 2; return null;
        }
        if (stage == 2) {
            if (!settled()) return null;
            if (next < reasons.size()) {
                SectionPos section = new ArrayList<>(reasons.keySet()).get(next);
                if (inputs.size() == next) inputs.add(new TopologySectionFailureInput(service, level, chunks.get(section.chunk().toLong()), section, reasons.get(section)));
                if (!inputs.get(next).tick()) return null;
                faults.add(inputs.get(next).evidence()); next++; return null;
            }
            synchronized (this) { armed = true; }
            query = service.requestMacroQuery(level, UUID.randomUUID(), START, GOAL, BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 3; return null;
        }
        if (!query.future().isDone()) return null;
        boolean success = query.future().join() != null;
        require(success == alternative, "the public query matches the real tunnel's alternative reachability");
        synchronized (this) {
            require(search != null, "the real public query reached its search worker");
            if (rejected) {
                require(rejections.stream().anyMatch(row -> row.get("section").equals(SECTIONS.get(0).toString())), "the actual R02 source rejection was reached");
                require(requested.stream().noneMatch(key -> reasons.containsKey(key.position())), "R02 never requested the unrelated failed section");
                require(candidates.isEmpty(), "an unrelated failure never entered the candidate set");
                TopologyEndpointFailureScenario.verify(query.progress(), MacroSearch.Failure.NO_STRUCTURAL_ROUTE, null);
            } else {
                require(released && notifications.size() == reasons.size(), "every planned original failure notification was delivered");
                require(candidates.stream().map(Candidate::section).collect(java.util.stream.Collectors.toSet()).equals(reasons.keySet()), "all planned sections contributed actual blocked candidates");
                if (nodeControl) require(candidates.stream().map(Candidate::source).distinct().count() >= 2, "two original graph nodes waited for the same section");
                Candidate expected = candidates.stream().min(CANDIDATE_ORDER).orElseThrow();
                TopologyEndpointFailureScenario.verify(query.progress(), alternative ? MacroSearch.Failure.NONE : expected.reason(), alternative ? null : expected.section());
                if (!alternative) {
                    Object selected = invoke(search, "bestBlockedFailure");
                    require(selected != null && expected.source().equals(source((MacroSearch.Endpoint) readField(selected, "source"))), "the selected source agrees with the independent stable-key comparison");
                }
                evidence.put("expected", expected); evidence.put("actualFTie", candidates.stream().map(Candidate::f).distinct().count() < candidates.size());
            }
            if (mutated) {
                boolean detected = false;
                try { TopologyEndpointFailureScenario.verify(query.progress(), MacroSearch.Failure.FACTS_RECOVERY_FAILED, SectionPos.of(99, 0, 99)); }
                catch (AssertionError expected) { detected = true; }
                require(detected, "the validator rejects a foreign failure section"); evidence.put("mutationDetected", true);
            }
            evidence.put("fCoverage", "actual production f; fixed numeric priorities and forced ties remain in the separate core matrix");
            evidence.put("fMode", "actual");
            evidence.put("notificationOwner", "original MacroQuery.resolvedDependencies; no copied completion queue");
            evidence.put("registrationOrder", target.parameters().getOrDefault("order", "forward"));
            evidence.put("faults", List.copyOf(faults)); evidence.put("notifications", List.copyOf(notifications));
            evidence.put("candidates", List.copyOf(candidates)); evidence.put("sourceRejections", List.copyOf(rejections));
            evidence.put("requestedDependencies", List.copyOf(requested)); evidence.put("progress", query.progress());
            evidence.put("request", TopologyValidationAccess.requestIdentity(query)); evidence.put("outcome", "PASS");
            return Map.copyOf(evidence);
        }
    }

    private void tunnel(int x1, int z1, int x2, int z2) {
        require(x1 == x2 || z1 == z2, "test tunnels use axis-aligned unit steps");
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) carve(new BlockPos(x, 6, z));
    }
    private void carve(BlockPos pos) {
        LevelChunk chunk = chunks.get(new ChunkPos(pos).toLong());
        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false); chunk.setBlockState(pos.above(), Blocks.AIR.defaultBlockState(), false);
    }
    private void verifyCells() {
        int checked = 0;
        for (LevelChunk chunk : chunks.values()) {
            BaseClusterTopology.PackedFacts facts;
            synchronized (readField(runtime, "runtimeLock")) {
                Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), SectionPos.of(chunk.getPos().x, 0, chunk.getPos().z)));
                facts = entry == null ? null : (BaseClusterTopology.PackedFacts) readField(entry, "facts");
            }
            require(facts != null, "the prepared tunnel has complete published facts");
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                require(facts.flags(x | z << 4 | y << 8) == TopologyGenerationProbe.independentFlags(chunk, pos), "prepared facts agree with independent world classification at " + pos);
                checked++;
            }
        }
        evidence.put("independentCellsChecked", checked);
    }
    private boolean settled() {
        if (!Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled"))) return false;
        for (Object section : ((Map<?, ?>) readField(service, "loadedSections")).values()) {
            var key = (TopologyWorkerRuntime.ClusterKey) readField(section, "key");
            if (key.dimension().equals(level.dimension()) && chunks.containsKey(key.section().chunk().toLong())
                    && ((boolean) readField(section, "readInFlight") || (boolean) readField(section, "persistencePending"))) return false;
        }
        return true;
    }
    private boolean matches(Object query) {
        return armed && !closed && readField(query, "this$0") == runtime && START.equals(readField(query, "startPosition")) && GOAL.equals(readField(query, "goalPosition"));
    }
    static void created(Object query) {
        var probe = active; if (probe == null) return;
        synchronized (probe) {
            if (!probe.matches(query)) return;
            require(probe.search == null, "the stable fault input does not restart the physical search");
            probe.search = (MacroSearch) readField(query, "search");
        }
    }
    static List<?> resolutions(Object query, List<?> original) {
        var probe = active; if (probe == null) return original;
        synchronized (probe) {
            if (!probe.matches(query) || probe.search == null || probe.rejected || probe.released) return original;
            Map<SectionPos, Object> failed = new LinkedHashMap<>(); List<Object> available = new ArrayList<>();
            for (Object resolution : original) {
                var cause = (MacroSearch.Unavailability) readField(resolution, "unavailability");
                if (cause == null || !probe.reasons.containsKey(cause.section())) available.add(resolution);
                else {
                    require(cause.reason() == TopologyEndpointFailureScenario.expected(probe.reasons.get(cause.section())), "the captured real failure has the planned reason");
                    require(failed.put(cause.section(), resolution) == null, "one original dependency completion per failed section");
                }
            }
            boolean all = failed.keySet().equals(probe.reasons.keySet());
            if (all && probe.nodeControl) {
                @SuppressWarnings("unchecked") Map<MacroSearch.DependencyKey, Set<?>> waiting = (Map<MacroSearch.DependencyKey, Set<?>>) readField(probe.search, "waitingByDependency");
                all = waiting.entrySet().stream().anyMatch(entry -> entry.getKey().position().equals(SECTIONS.get(0)) && entry.getValue().size() >= 2);
            }
            if (all) { probe.delivery.forEach(section -> available.add(failed.get(section))); probe.released = true; }
            return List.copyOf(available);
        }
    }
    static void unavailable(MacroSearch search, MacroSearch.DependencyKey key, MacroSearch.Unavailability reason) {
        var probe = active;
        int repeat = 1;
        if (probe != null) synchronized (probe) {
            if (!probe.closed && probe.search == search && probe.reasons.containsKey(reason.section())) {
                @SuppressWarnings("unchecked") Map<MacroSearch.DependencyKey, Set<?>> waiting = (Map<MacroSearch.DependencyKey, Set<?>>) readField(search, "waitingByDependency");
                Set<?> nodes = waiting.get(key); require(nodes != null && !nodes.isEmpty(), "the real notification reaches an actual blocked dependency");
                for (Object node : nodes) {
                    float f = (float) readField(node, "f"), g = (float) readField(node, "g"), h = (float) readField(node, "h");
                    require(Float.isFinite(f) && Float.compare(f, g + (float) readField(search, "weight") * h) == 0, "observed f preserves the production node's g/weight/h relationship");
                    require(probe.candidates.size() < 64, "blocked candidate observation is bounded");
                    probe.candidates.add(new Candidate(f, g, h, reason.reason(), reason.section(), source((MacroSearch.Endpoint) readField(node, "endpoint"))));
                }
                repeat = "twice".equals(probe.target.parameters().get("repeat")) ? 2 : 1;
                require(probe.notifications.size() < probe.reasons.size(), "original failure notifications are delivered once per section");
                probe.notifications.add(Map.of("section", reason.section().toString(), "reason", reason.reason().name(), "deliveries", repeat));
            }
        }
        for (int index = 0; index < repeat; index++) search.dependencyUnavailable(key, reason);
    }
    static void requested(Object query, MacroSearch.DependencyKey key) {
        var probe = active; if (probe == null) return;
        synchronized (probe) {
            if (!probe.matches(query)) return;
            require(probe.requested.size() < 256, "dependency observation is bounded"); probe.requested.add(key);
        }
    }
    static void sourceExit(Object graph, SectionPos neighbor, boolean allowed) {
        var probe = active; if (probe == null || allowed) return;
        synchronized (probe) {
            if (!probe.armed || probe.closed || readField(graph, "this$0") != probe.runtime || !probe.reasons.containsKey(neighbor)) return;
            MacroSearch.Endpoint start = (MacroSearch.Endpoint) readField(graph, "start");
            if (!START.equals(start.anchor())) return;
            require(probe.rejections.size() < 128, "source-rejection observation is bounded");
            probe.rejections.add(Map.of("section", neighbor.toString(), "mayExit", false));
        }
    }

    record Source(int layer, int x, int y, int z, int component, int anchorX, int anchorY, int anchorZ) { }
    record Candidate(float f, float g, float h, MacroSearch.Failure reason, SectionPos section, Source source) { }
    private static Source source(MacroSearch.Endpoint endpoint) {
        int layer = endpoint instanceof MacroSearch.AggregateEndpoint ? 2 : endpoint instanceof MacroSearch.ComponentEndpoint ? 1 : 0;
        SectionPos pos = endpoint instanceof MacroSearch.AggregateEndpoint value ? value.origin()
                : endpoint instanceof MacroSearch.ComponentEndpoint value ? value.section() : SectionPos.of(endpoint.anchor());
        int component = endpoint instanceof MacroSearch.AggregateEndpoint value ? value.aggregateId()
                : endpoint instanceof MacroSearch.ComponentEndpoint value ? value.componentId() : -1;
        return new Source(layer, pos.x(), pos.y(), pos.z(), component, endpoint.anchor().getX(), endpoint.anchor().getY(), endpoint.anchor().getZ());
    }
    private static final Comparator<Source> SOURCE_ORDER = Comparator.comparingInt(Source::layer).thenComparingInt(Source::x)
            .thenComparingInt(Source::y).thenComparingInt(Source::z).thenComparingInt(Source::component)
            .thenComparingInt(Source::anchorX).thenComparingInt(Source::anchorY).thenComparingInt(Source::anchorZ);
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator.comparingDouble(Candidate::f)
            .thenComparingInt(value -> List.of(MacroSearch.Failure.FACTS_RECOVERY_FAILED, MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE,
                    MacroSearch.Failure.UNAVAILABLE_CHUNK).indexOf(value.reason()))
            .thenComparingInt(value -> value.section().x()).thenComparingInt(value -> value.section().y()).thenComparingInt(value -> value.section().z())
            .thenComparing(Candidate::source, SOURCE_ORDER);

    @Override public void close() {
        synchronized (this) { closed = true; armed = false; }
        inputs.forEach(TopologySectionFailureInput::close);
        if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); }
        chunks.values().forEach(chunk -> TopologyService.onChunkUnloaded(level, chunk));
        synchronized (TopologyMixedFailureScenario.class) { if (active == this) active = null; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
