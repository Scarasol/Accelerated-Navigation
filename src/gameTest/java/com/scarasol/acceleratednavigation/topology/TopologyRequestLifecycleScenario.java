package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** L04 admission and L05 historical-key cleanup use public queries and existing eviction owners. */
public final class TopologyRequestLifecycleScenario implements AutoCloseable {
    private static final ChunkPos CHUNK = new ChunkPos(128, 128);
    private static final BlockPos START = new BlockPos(2052, 289, 2052), GOAL = new BlockPos(2060, 289, 2060);
    private final TopologyService service;
    private final ServerLevel level;
    private final ProductionRemediationPlan.Target target;
    private final boolean mutated;
    private final List<TopologyService.MacroRequest> requests = new ArrayList<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<BaseClusterTopology.TraversalProfile> profiles = new ArrayList<>();
    private LevelChunk chunk;
    private int stage, writtenTick, profileIndex, cycle;

    public TopologyRequestLifecycleScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target; mutated = "mutated".equals(control);
        for (int value = 0; value < 16; value++) profiles.add(new BaseClusterTopology.TraversalProfile(
                (value & 1) == 0 ? .6F : 1.4F, (value & 2) == 0 ? .9F : 1.95F,
                (value & 4) == 0 ? 0 : 1, 0, (value & 8) == 0 ? 0 : 4, false));
        require(profiles.stream().map(profile -> List.of(profile.geometry(BaseClusterTopology.Channel.GROUND),
                profile.movement(BaseClusterTopology.Channel.GROUND))).distinct().count() == 16, "sixteen distinct normalized geometry/movement keys");
    }
    public ChunkPos chunkPosition() { return CHUNK; }
    public Map<String, Object> tick() {
        if (stage == 0) {
            chunk = level.getChunk(CHUNK.x, CHUNK.z); TopologyService.onChunkLoaded(level, chunk);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 287; y <= 292; y++) {
                chunk.setBlockState(new BlockPos(2048 + x, y, 2048 + z), y == 288 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
            }
            writtenTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (level.getServer().getTickCount() <= writtenTick || !idle()) return null;
            stage = 2;
        }
        for (int advances = 0; advances < 8; advances++) {
            int previous = stage;
            Map<String, Object> result = target.kind().equals("L05") ? history() : capacity();
            if (result != null || stage == previous) return result;
        }
        return null;
    }

    private Map<String, Object> capacity() {
        if (stage == 2) {
            requests.add(request(START, GOAL, profiles.get(0)));
            require(activeRequests() == 1 && !requests.get(0).future().isDone(), "single logical request is live");
            evidence.put("one", workerMetrics(service)); requests.get(0).cancel(); stage = 3; return null;
        }
        if (stage == 3) {
            if (!idle()) return null;
            forget();
            Set<List<BlockPos>> keys = new HashSet<>();
            for (int index = 0; index < 1024; index++) {
                boolean same = target.parameters().get("keys").equals("same");
                BlockPos start = same ? START : new BlockPos(2048 + (index & 15), 289, 2048 + ((index >> 4) & 15));
                BlockPos goal = same ? GOAL : new BlockPos(2052 + (index >> 8), 289, 2056);
                keys.add(List.of(start, goal)); requests.add(request(start, goal, profiles.get(0)));
            }
            require(keys.size() == (target.parameters().get("keys").equals("same") ? 1 : 1024), "raw keys match the requested capacity control");
            require(activeRequests() == 1024 && requests.stream().noneMatch(query -> query.future().isDone()), "1024 logical requests remain nonterminal");
            boolean rejected = false;
            try { requests.add(request(START, GOAL, profiles.get(0))); }
            catch (RejectedExecutionException expected) { rejected = true; }
            verifyCapacity(activeRequests(), rejected);
            evidence.put("capacity", workerMetrics(service)); evidence.put("uniqueKeys", keys.size()); evidence.put("rejected1025", rejected);
            for (var query : requests) query.cancel(); stage = 4; return null;
        }
        if (!idle()) return null;
        require(requests.stream().allMatch(query -> query.future().isCancelled()), "all accepted capacity requests cancel");
        require(activeRequests() == 0, "server request ownership is empty after cancellation");
        evidence.put("afterCancellation", workerMetrics(service)); forget();
        return finish();
    }

    private Map<String, Object> history() {
        int keyCount = Integer.parseInt(target.parameters().get("keys"));
        if (stage == 2) {
            if (!TopologyValidationAccess.clearDerived(service, level.dimension())) return null;
            Map<String, Long> clean = ownership(); verifyClean(clean);
            if (cycle == 10) {
                evidence.put("cycles", samples); evidence.put("normalizedKeys", profiles.subList(0, keyCount).stream()
                        .map(profile -> Map.of("geometry", profile.geometry(BaseClusterTopology.Channel.GROUND),
                                "movement", profile.movement(BaseClusterTopology.Channel.GROUND))).toList());
                return finish();
            }
            requests.add(request(START, GOAL, profiles.get(profileIndex))); stage = 3; return null;
        }
        if (stage == 3) {
            if (!requests.get(0).future().isDone()) return null;
            require(requests.get(0).future().join() != null, "each distinct profile consumes the constructed walkable floor");
            samples.add(Map.of("cycle", cycle, "key", profileIndex, "request", TopologyValidationAccess.requestIdentity(requests.get(0)),
                    "progress", requests.get(0).progress(), "beforeEviction", ownership(), "resources", workerMetrics(service)));
            forget();
            var cancelled = request(START, GOAL, profiles.get(profileIndex)); requests.add(cancelled); cancelled.cancel();
            stage = 4; return null;
        }
        if (!idle()) return null;
        require(requests.get(0).future().isCancelled(), "repeat request cancellation reaches its own terminal"); forget();
        if (++profileIndex == keyCount) { profileIndex = 0; cycle++; }
        stage = 2; return null;
    }

    private Map<String, Long> ownership() {
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            long views = 0, empty = 0, references = 0, links = 0;
            for (var item : ((Map<?, ?>) readField(runtime, "clusters")).entrySet()) {
                var key = (TopologyWorkerRuntime.ClusterKey) item.getKey();
                if (!key.dimension().equals(level.dimension()) || !key.section().chunk().equals(CHUNK)) continue;
                for (Object view : ((Map<?, ?>) readField(item.getValue(), "views")).values()) {
                    views++; if (readField(view, "topology") == null) empty++;
                    references += ((Map<?, ?>) readField(view, "pinnedTopologies")).size();
                    for (Object link : (Object[]) readField(view, "links")) if (link != null) links++;
                }
            }
            return Map.of("views", views, "emptyViews", empty, "references", references, "links", links);
        }
    }
    private static void verifyClean(Map<String, Long> state) {
        require(state.values().stream().allMatch(value -> value == 0), "eviction leaves no historical views, links or active topology references: " + state);
    }
    private static void verifyCapacity(int active, boolean rejected) {
        require(rejected && active == 1024, "the 1025th request is rejected without changing ownership");
    }
    private Map<String, Object> finish() {
        if (mutated) {
            boolean detected = false;
            try {
                if (target.kind().equals("L05")) verifyClean(Map.of("emptyViews", 1L));
                else verifyCapacity(1025, false);
            } catch (AssertionError expected) { detected = true; }
            require(detected, "the selected invariant detects its deliberate violation"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }
    private int activeRequests() { return ((Map<?, ?>) readField(service, "macroRequests")).size(); }
    private boolean idle() {
        return TopologyValidationAccess.snapshot(service, level.dimension()).idle()
                && Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), Set.of(CHUNK.toLong())).get("settled"));
    }
    private TopologyService.MacroRequest request(BlockPos start, BlockPos goal, BaseClusterTopology.TraversalProfile profile) {
        return service.requestMacroQuery(level, UUID.randomUUID(), start, goal, BaseClusterTopology.Channel.GROUND, profile, NavigationScheduler.Priority.ACTIVE);
    }
    private void forget() { for (var query : requests) TopologyValidationAccess.forgetRequest(query); requests.clear(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        for (var query : requests) query.cancel(); forget();
        if (chunk != null) { TopologyService.onChunkUnloaded(level, chunk); chunk = null; }
    }
}
