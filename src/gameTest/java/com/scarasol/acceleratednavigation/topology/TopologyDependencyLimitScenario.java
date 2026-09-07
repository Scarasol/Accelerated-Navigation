package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Supplies explicit real-section dependencies to the original public query's worker-owned admission. */
public final class TopologyDependencyLimitScenario implements AutoCloseable {
    private static volatile TopologyDependencyLimitScenario active;
    private static final BlockPos START = new BlockPos(3076, 289, 3076), GOAL = new BlockPos(3080, 289, 3076);
    private final TopologyService service;
    private final ServerLevel level;
    private final Object runtime;
    private final int desired;
    private final boolean mutated;
    private final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private final List<TopologyService.MacroRequest> queries = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final CountDownLatch advance = new CountDownLatch(1), release = new CountDownLatch(1);
    private final List<MacroSearch.DependencyKey> dependencies = new ArrayList<>();
    private TopologyBuildWindowProbe build;
    private volatile boolean armed, seeded, advanced, timedOut, closed;
    private Object physicalQuery;
    private int stage, changedTick;

    public TopologyDependencyLimitScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; runtime = readField(service, "runtime");
        desired = Integer.parseInt(target.parameters().get("live")); mutated = control.equals("mutated");
        for (int index = 0; index < desired; index++) dependencies.add(new MacroSearch.DependencyKey(
                MacroSearch.DependencyKind.BASE_CLUSTER, SectionPos.of(196 + index, 18, 192)));
        synchronized (TopologyDependencyLimitScenario.class) {
            if (active != null) throw new IllegalStateException("overlapping dependency controls"); active = this;
        }
    }
    public Set<Long> chunkPositions() {
        Set<Long> result = new java.util.HashSet<>(); result.add(new ChunkPos(START).toLong());
        dependencies.forEach(key -> result.add(key.position().chunk().toLong())); return Set.copyOf(result);
    }
    public Map<String, Object> tick() {
        if (timedOut || build != null && build.timedOut()) throw new AssertionError("dependency ownership window timed out");
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
            queries.add(request()); stage = 2; return null;
        }
        if (stage == 2) {
            if (!queries.get(0).future().isDone() || !settled()) return null;
            require(queries.get(0).future().join() != null, "the real public query has valid ready endpoints");
            TopologyValidationAccess.forgetRequest(queries.remove(0));
            ((TopologyRuntimeFormalControlAccess) runtime).acceleratedNavigation$clearCompletedCorridors();
            Set<Long> selected = new java.util.HashSet<>(chunkPositions()); selected.remove(new ChunkPos(START).toLong());
            build = TopologyBuildWindowProbe.watch(service, "base/captured", selected);
            armed = true; queries.add(request()); queries.add(request()); stage = 3; return null;
        }
        if (stage == 3) {
            if (!seeded || !build.reached()) return null;
            synchronized (readField(runtime, "runtimeLock")) {
                Map<String, Object> actual = admission(); verify(actual, Math.min(desired, 16), desired == 17 ? 1 : 0);
                Object first = readField(readField(queries.get(0), "workerRequest"), "flight");
                Object second = readField(readField(queries.get(1), "workerRequest"), "flight");
                require(first != null && first == second && readField(first, "query") == physicalQuery,
                        "both logical requests share one original physical query");
                evidence.put("admission", actual); evidence.put("physicalQuery", TopologyValidationAccess.token(physicalQuery));
                evidence.put("requests", queries.stream().map(TopologyValidationAccess::requestIdentity).toList());
            }
            if (desired == 17) { build.release(); stage = 4; return null; }
            cancelAndRelease(); stage = 6; return null;
        }
        if (stage == 4) {
            synchronized (readField(runtime, "runtimeLock")) {
                if (((Map<?, ?>) readField(physicalQuery, "resolvedDependencies")).isEmpty()) return null;
            }
            advance.countDown(); stage = 5; return null;
        }
        if (stage == 5) {
            if (!advanced) return null;
            cancelAndRelease(); stage = 6; return null;
        }
        if (!settled() || !TopologyValidationAccess.snapshot(service, level.dimension()).idle()) return null;
        require(queries.stream().allMatch(query -> query.future().isCancelled()), "all logical consumers cancel without a fabricated result");
        if (!TopologyValidationAccess.clearDerived(service, level.dimension())) return null;
        if (mutated) {
            boolean rejected = false;
            try { verify(Map.of("live", 17, "pending", 0), 16, 1); } catch (AssertionError expected) { rejected = true; }
            require(rejected, "the admission validator detects an extra live dependency"); evidence.put("mutationDetected", true);
        }
        evidence.put("inputMethod", "explicit known loaded section dependencies supplied on the original MacroQuery worker after search creation; real completed dependencies consumed before admitting the seventeenth");
        evidence.put("afterCancellation", workerMetrics(service)); evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }
    static void beforeSearch(Object query) {
        var scenario = active;
        if (scenario == null || !scenario.armed || scenario.closed || scenario.seeded
                || readField(query, "this$0") != scenario.runtime || !START.equals(readField(query, "startPosition"))) return;
        require((boolean) readField(query, "workerRunning"), "dependency control stays on the original running worker");
        scenario.physicalQuery = query;
        invoke(scenario.runtime, "runRuntimeTransition", (Runnable) () -> {
            require(((Map<?, ?>) readField(query, "requests")).isEmpty(), "endpoint preparation has no leftover dependency owner");
            for (var dependency : scenario.dependencies) {
                Object entry = ((Map<?, ?>) readField(scenario.runtime, "clusters")).get(
                        new TopologyWorkerRuntime.ClusterKey(scenario.level.dimension(), dependency.position()));
                require(entry != null && readField(entry, "facts") != null, "every supplied dependency has real published complete facts");
            }
            writeField(query, "pendingDependencies", List.copyOf(scenario.dependencies));
            invoke(query, "requestPendingSections");
        });
        scenario.seeded = true;
        if (scenario.desired == 17) {
            scenario.await(scenario.advance);
            if (!scenario.closed) {
                invoke(query, "applyResolvedDependencies", invoke(query, "peekResolvedDependencies"));
                invoke(scenario.runtime, "runRuntimeTransition", (Runnable) () -> {
                    invoke(query, "requestPendingSections");
                    require(((List<?>) readField(query, "pendingDependencies")).isEmpty(), "the seventeenth dependency leaves the original wait after capacity is released");
                    var last = scenario.dependencies.get(16);
                    require(((Map<?, ?>) readField(query, "requests")).containsKey(last)
                            || ((Map<?, ?>) readField(query, "resolvedDependencies")).containsKey(last),
                            "the original admission owner now owns the seventeenth dependency");
                    scenario.evidence.put("seventeenthAdmitted", scenario.admission());
                });
            }
            scenario.advanced = true;
        }
        scenario.await(scenario.release);
    }
    private Map<String, Object> admission() {
        int live = ((Map<?, ?>) readField(physicalQuery, "requests")).size() + ((Map<?, ?>) readField(physicalQuery, "resolvedDependencies")).size();
        return Map.of("live", live, "pending", ((List<?>) readField(physicalQuery, "pendingDependencies")).size());
    }
    private static void verify(Map<String, Object> actual, int live, int pending) {
        require(((Number) actual.get("live")).intValue() == live && ((Number) actual.get("pending")).intValue() == pending,
                "live dependency admission and the original pending list match their bound");
    }
    private void await(CountDownLatch latch) {
        require(!Thread.holdsLock(readField(runtime, "runtimeLock")), "the paused search holds no runtime lock");
        try { if (!latch.await(30, TimeUnit.SECONDS)) { timedOut = true; throw new IllegalStateException("dependency control was not released"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private TopologyService.MacroRequest request() {
        return service.requestMacroQuery(level, UUID.randomUUID(), START, GOAL, BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
    }
    private boolean settled() { return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled")); }
    private void cancelAndRelease() { queries.forEach(TopologyService.MacroRequest::cancel); advance.countDown(); release.countDown(); if (build != null) build.release(); }
    @Override public void close() {
        closed = true; armed = false; cancelAndRelease();
        queries.forEach(TopologyValidationAccess::forgetRequest); if (build != null) build.close();
        chunks.values().forEach(chunk -> TopologyService.onChunkUnloaded(level, chunk));
        synchronized (TopologyDependencyLimitScenario.class) { if (active == this) active = null; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
