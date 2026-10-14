package an.production;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** A burst of unique routes; only the entity navigation backend differs between runs. */
public final class PerformanceComparisonProbe {
    private record Scenario(int distance, int concurrency, int repeat, boolean warmup) {}
    private record Route(BlockPos start, BlockPos goal, String kind, int tile, int slot, int rotation) {}
    private static final class Actor {
        final BenchmarkHusk mob;
        final Route route;
        final Vec3 initialPosition;
        final PerformanceSearchTelemetry.Stats search = new PerformanceSearchTelemetry.Stats();
        long submitStart, submitEnd, ready, firstMove, physicalArrival, finish;
        int maxNodes;
        int terminalTick = -1;
        boolean accepted, success, reachedNativeEnd, evaluated;
        String state = "PENDING";
        NavigationRequestPath request;
        Actor(BenchmarkHusk mob, Route route) {
            this.mob = mob; this.route = route; this.initialPosition = mob.position();
        }
    }
    public static final class BenchmarkHusk extends Husk {
        public BenchmarkHusk(ServerLevel level) {
            super(EntityType.HUSK, level);
            setBaby(false);
            setPersistenceRequired();
            setInvulnerable(true);
            setCanPickUpLoot(false);
            addEffect(new MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, MobEffectInstance.INFINITE_DURATION,
                    Integer.getInteger("an.production.performanceSpeedAmplifier", 1), false, false));
        }
        @Override protected void registerGoals() {}
        @Override protected boolean shouldDespawnInPeaceful() { return false; }
        @Override protected boolean convertsInWater() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
        @Override public boolean isPushable() { return false; }
    }

    private static final String[] KINDS = {
            "flat", "diagonal", "single-wall", "staggered-walls",
            "two-step-ramp", "pillars", "doorway", "corner"
    };
    private final java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<Actor> actors = new ArrayList<>();
    private final JsonArray trials = new JsonArray();
    private final JsonArray samples = new JsonArray();
    private final List<Long> work = new ArrayList<>();
    private final List<Long> intervals = new ArrayList<>();
    private final boolean vanilla = Boolean.getBoolean("an.production.performanceVanilla");
    private final int timeout = Integer.getInteger("an.production.performanceTimeoutTicks", 1200);
    private final int repeats = Integer.getInteger("an.production.performanceRepeats", 2);
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private Method topologyMetrics;
    private int tick, index = -1, nextTrial = Integer.getInteger("an.production.performanceStartupTicks", 180),
            submitTick, peakActive, peakPending, peakNodes, nextActor;
    private long tickStart, previousStart, currentInterval, batchStart, batchEnd, heapStart, peakHeap;
    private long gcCountStart, gcTimeStart, allocationStart, chargedStart;
    private long nativeStart, segmentStart, wholeStart, failureStart, recoveriesStart;
    private long submissionWork, submissionAllocated;
    private JsonObject topologyStart;
    private boolean submitted, closing, timedOut;
    private Throwable failure;
    private final long addedLoadNanos = Long.getLong("an.production.performanceAddedLoadNanos", 0L);
    private final int submissionSpanTicks = Integer.getInteger("an.production.performanceSubmissionSpanTicks", 1);

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void load(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.START || addedLoadNanos <= 0 || tick < 150) return;
        long deadline = System.nanoTime() + addedLoadNanos;
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    public PerformanceComparisonProbe() {
        int[] distances = ints(System.getProperty("an.production.performanceDistances", "8,18,28"));
        int[] concurrencies = ints(System.getProperty("an.production.performanceConcurrency", "128,256,512"));
        if (Boolean.parseBoolean(System.getProperty("an.production.performanceWarmup", "true"))) {
            scenarios.add(new Scenario(8, 64, -2, true));
            scenarios.add(new Scenario(28, 64, -1, true));
        }
        for (int r = 0; r < repeats; r++) {
            for (int di = 0; di < distances.length; di++)
                for (int ci = 0; ci < concurrencies.length; ci++) {
                    int d = distances[(r & 1) == 0 ? di : distances.length - 1 - di];
                    int c = concurrencies[(r & 1) == 0 ? ci : concurrencies.length - 1 - ci];
                    scenarios.add(new Scenario(d, c, r, false));
                }
        }
    }
    private static int[] ints(String values) {
        return java.util.Arrays.stream(values.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }
    private static long heap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }
    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob && !(event.getEntity() instanceof BenchmarkHusk)) event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            Field bypass = NavigationPolicy.class.getDeclaredField("bypassEntityIds");
            bypass.setAccessible(true);
            NavigationPolicy.reloadBypassIds();
            bypass.set(null, vanilla ? Set.of(new net.minecraft.resources.ResourceLocation("minecraft", "husk")) : Set.of());
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.setDayTime(6000);
            prepareArena(server, level);
            service = SegmentedNavigationService.forServer(server);
            scheduler = NavigationScheduler.forServer(server);
            topologyMetrics = TopologyService.class.getDeclaredMethod("metrics");
            topologyMetrics.setAccessible(true);
            System.out.println("PERF_READY v2 variant=" + variant() + " scenarios=" + scenarios.size());
        } catch (Throwable problem) { abort(problem); }
    }
    static void prepareArena(MinecraftServer server, ServerLevel level) {
        // Search regions extend beyond each tile; load the arena and its margin.
        for (int cx = -12; cx <= 11; cx++) for (int cz = -12; cz <= 11; cz++) {
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
        }
        for (int x = -160; x < 160; x++) for (int z = -160; z < 160; z++)
            level.setBlock(new BlockPos(x, 80, z), Blocks.STONE.defaultBlockState(), 2);
        for (int tile = 0; tile < 64; tile++) buildTile(level, tile);
    }
    private String variant() { return vanilla ? "vanilla-same-runtime" : "accelerated"; }
    private static BlockPos point(int tile, int x, int y, int z) {
        int rotation = ((tile / 8) + (tile % 8) / 2) % 4;
        int rx = x, rz = z;
        if (rotation == 1) { rx = 39 - z; rz = x; }
        else if (rotation == 2) { rx = 39 - x; rz = 39 - z; }
        else if (rotation == 3) { rx = z; rz = 39 - x; }
        return new BlockPos(-160 + (tile % 8) * 40 + rx, y, -160 + (tile / 8) * 40 + rz);
    }
    private static void obstacle(ServerLevel level, int tile, int x, int z, int height) {
        for (int y = 81; y < 81 + height; y++)
            level.setBlock(point(tile, x, y, z), Blocks.STONE.defaultBlockState(), 2);
    }
    private static void buildTile(ServerLevel level, int tile) {
        switch (tile % 8) {
            case 2 -> { for (int z = 16; z <= 22; z++) obstacle(level, tile, 11, z, 3); }
            case 3 -> {
                for (int z = 14; z <= 20; z++) obstacle(level, tile, 11, z, 3);
                for (int z = 19; z <= 25; z++) obstacle(level, tile, 21, z, 3);
            }
            case 4 -> {
                for (int x = 10; x <= 18; x++) for (int z = 6; z <= 32; z++)
                    obstacle(level, tile, x, z, rampHeight(x));
            }
            case 5 -> {
                for (int x : new int[] {10, 14, 18, 26, 30}) for (int z = 12; z <= 26; z += 4)
                    obstacle(level, tile, x, z, 3);
            }
            case 6 -> {
                for (int z = 10; z <= 28; z++) if (z < 18 || z > 19) obstacle(level, tile, 11, z, 3);
            }
            case 7 -> {
                for (int z = 16; z <= 22; z++) obstacle(level, tile, 11, z, 3);
                for (int x = 11; x <= 15; x++) obstacle(level, tile, x, 22, 3);
            }
            default -> { }
        }
    }
    private static int rampHeight(int x) { return x >= 13 && x <= 15 ? 2 : x >= 10 && x <= 18 ? 1 : 0; }
    private static Route route(int distance, int actor) {
        int tile = actor / 8;
        int slot = actor % 8;
        int x = 4 + slot / 4;
        int z = new int[] {13, 16, 19, 25}[slot % 4];
        int gx = x + distance;
        int gz = tile % 8 == 1 ? z + 3 : z;
        int y = 81, gy = 81;
        if (tile % 8 == 4) { y += rampHeight(x); gy += rampHeight(gx); }
        BlockPos a = point(tile, x, y, z);
        BlockPos b = point(tile, gx, gy, gz);
        if ((slot & 1) == 1) { BlockPos swap = a; a = b; b = swap; }
        return new Route(a, b, KINDS[tile % 8], tile, slot,
                ((tile / 8) + (tile % 8) / 2) % 4);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void start(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.START || closing) return;
        tick++;
        tickStart = System.nanoTime();
        currentInterval = previousStart == 0 ? 0 : tickStart - previousStart;
        if (submitted && previousStart != 0) intervals.add(currentInterval);
        previousStart = tickStart;
        try { if (!actors.isEmpty() && nextActor < actors.size() && tick >= submitTick) submit(); }
        catch (Throwable problem) { abort(problem); }
    }
    private void beginTrial() {
        index++;
        if (index == scenarios.size()) { stop(); return; }
        Scenario scenario = scenarios.get(index);
        actors.clear();
        work.clear();
        intervals.clear();
        submitted = false;
        nextActor = 0;
        timedOut = false;
        submissionWork = 0;
        peakActive = peakPending = peakNodes = 0;
        submitTick = tick + 20;
        for (int i = 0; i < scenario.concurrency(); i++) {
            Route route = route(scenario.distance(), i);
            BenchmarkHusk mob = new BenchmarkHusk(level);
            double offset = i == Integer.getInteger("an.production.performancePositionOffsetActor", -1)
                    ? Double.parseDouble(System.getProperty("an.production.performancePositionOffsetZ", "0")) : 0;
            mob.setPos(route.start().getX() + .5, route.start().getY() + .01, route.start().getZ() + .5 + offset);
            if (!level.addFreshEntity(mob)) throw new IllegalStateException("benchmark actor insertion rejected");
            Actor actor = new Actor(mob, route);
            actors.add(actor);
            PerformanceSearchTelemetry.ACTORS.put(mob, actor.search);
        }
        System.out.println("PERF_BEGIN index=" + index + " distance=" + scenario.distance()
                + " concurrency=" + scenario.concurrency() + " repeat=" + scenario.repeat());
    }
    private void submit() throws Exception {
        if (!submitted) {
            for (Actor a : actors) {
                if (!a.mob.onGround() || a.mob.blockPosition().distManhattan(a.route.start()) > 0)
                    throw new IllegalStateException("actor has not settled at route start: " + a.route);
                if (AutomaticNavigationBridge.enabled(a.mob.getNavigation()) == vanilla)
                    throw new IllegalStateException("navigation backend does not match benchmark variant");
            }
            heapStart = heap();
            peakHeap = heapStart;
            allocationStart = PerformanceSearchTelemetry.allocated();
            gcCountStart = gcCount(); gcTimeStart = gcTime();
            chargedStart = scheduler.metrics().chargedNanos();
            topologyStart = topology();
            var m = service.metrics();
            nativeStart = m.nativeCalls(); segmentStart = m.segmentCalls(); wholeStart = m.nativeWholeCalls();
            failureStart = m.failures(); recoveriesStart = m.recoveries();
            submitted = true;
            submissionAllocated = 0;
            batchStart = System.nanoTime();
        }
        long allocatedBefore = PerformanceSearchTelemetry.allocated();
        int limit = Math.min(actors.size(), nextActor + (actors.size() + submissionSpanTicks - 1) / submissionSpanTicks);
        while (nextActor < limit) {
            Actor a = actors.get(nextActor++);
            a.submitStart = System.nanoTime();
            a.accepted = a.mob.getNavigation().moveTo(a.route.goal().getX() + .5,
                    a.route.goal().getY(), a.route.goal().getZ() + .5, 1.0);
            a.submitEnd = System.nanoTime();
            a.request = service.active(a.mob.getNavigation());
            HandoffDiagnostics.register(a.mob, nextActor - 1, scenarios.get(index).distance(),
                    scenarios.get(index).repeat(), a.request);
            Path path = a.mob.getNavigation().getPath();
            if (path != null && path.getNodeCount() > 0) a.ready = a.submitEnd;
            if (!a.accepted) { a.state = "REJECTED"; a.finish = a.submitEnd; a.evaluated = true; }
        }
        batchEnd = System.nanoTime();
        submissionAllocated += PerformanceSearchTelemetry.allocated() - allocatedBefore;
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        long end = System.nanoTime();
        try {
            if (actors.isEmpty()) {
                if (tick >= nextTrial) beginTrial();
                return;
            }
            if (!submitted) return;
            long elapsed = end - tickStart;
            work.add(elapsed);
            if (tick == submitTick) submissionWork = elapsed;
            peakHeap = Math.max(peakHeap, heap());
            peakActive = Math.max(peakActive, service.metrics().active());
            peakPending = Math.max(peakPending, scheduler.metrics().pendingRequests());
            JsonObject sample = new JsonObject();
            sample.addProperty("tick", tick);
            sample.addProperty("relativeTick", tick - submitTick);
            sample.addProperty("trial", index);
            sample.addProperty("workNanos", elapsed);
            sample.addProperty("intervalNanos", currentInterval);
            sample.addProperty("active", service.metrics().active());
            sample.addProperty("pending", scheduler.metrics().pendingRequests());
            sample.addProperty("heap", heap());
            samples.add(sample);
            int nodes = 0;
            for (Actor a : actors) {
                Path path = a.mob.getNavigation().getPath();
                if (path != null) {
                    a.maxNodes = Math.max(a.maxNodes, path.getNodeCount());
                    nodes += path.getNodeCount();
                    a.reachedNativeEnd |= path.canReach();
                }
                if (a.ready == 0 && path != null && path.getNodeCount() > 0) a.ready = System.nanoTime();
                if (a.firstMove == 0 && a.mob.position().distanceTo(new Vec3(a.route.start().getX() + .5,
                        a.route.start().getY(), a.route.start().getZ() + .5)) > .05) a.firstMove = System.nanoTime();
                boolean within = a.mob.blockPosition().distManhattan(a.route.goal()) <= 1;
                if (within && a.physicalArrival == 0) a.physicalArrival = System.nanoTime();
                if (a.finish == 0 && a.request != null && a.request.terminal()) {
                    a.state = a.request.state.name();
                    a.success = a.request.state == NavigationRequestPath.State.SUCCEEDED && within;
                    a.finish = System.nanoTime();
                    a.terminalTick = tick;
                } else if (a.finish == 0 && vanilla && a.mob.getNavigation().isDone()) {
                    a.state = "NATIVE_DONE";
                    a.finish = System.nanoTime();
                    a.terminalTick = tick;
                }
                // Vanilla can consume its last node before residual velocity carries the mob into accuracy.
                if (!a.evaluated && a.finish != 0 && tick - a.terminalTick >= 10) {
                    if (vanilla) {
                        a.success = a.physicalArrival != 0 && a.reachedNativeEnd;
                        a.state = a.success ? "SUCCEEDED" : "EXHAUSTED";
                    }
                    a.evaluated = true;
                }
            }
            peakNodes = Math.max(peakNodes, nodes);
            if (actors.stream().allMatch(a -> a.evaluated)) finishTrial();
            else if (tick - submitTick >= timeout || end - batchStart >= 90_000_000_000L) {
                timedOut = true;
                for (Actor a : actors) if (a.finish == 0) {
                    a.state = "TIMEOUT"; a.finish = System.nanoTime(); a.evaluated = true;
                }
                finishTrial();
            }
        } catch (Throwable problem) { abort(problem); }
    }
    private JsonObject topology() throws Exception {
        return gson.toJsonTree(topologyMetrics.invoke(TopologyService.forServer(server))).getAsJsonObject();
    }
    private void finishTrial() throws Exception {
        long now = System.nanoTime();
        Scenario scenario = scenarios.get(index);
        JsonObject row = new JsonObject();
        row.addProperty("variant", variant());
        row.addProperty("distance", scenario.distance());
        row.addProperty("concurrency", scenario.concurrency());
        row.addProperty("repeat", scenario.repeat());
        row.addProperty("warmup", scenario.warmup());
        row.addProperty("timedOut", timedOut);
        row.addProperty("submitTick", submitTick);
        row.addProperty("endTick", tick);
        row.addProperty("totalActors", actors.size());
        row.addProperty("successfulActors", actors.stream().filter(a -> a.success).count());
        row.addProperty("physicalArrivals", actors.stream().filter(a -> a.physicalArrival != 0).count());
        row.addProperty("pathReadyActors", actors.stream().filter(a -> a.ready != 0).count());
        row.addProperty("rejectedActors", actors.stream().filter(a -> !a.accepted).count());
        row.addProperty("batchSubmitNanos", batchEnd - batchStart);
        row.addProperty("batchFinishNanos", now - batchStart);
        boolean allSuccess = actors.stream().allMatch(a -> a.success);
        boolean allReady = actors.stream().allMatch(a -> a.ready != 0);
        row.addProperty("batchAllSuccessNanos", allSuccess ? actors.stream()
                .mapToLong(a -> Math.max(a.physicalArrival, a.finish)).max().orElse(now) - batchStart : -1);
        row.addProperty("batchAllReadyNanos", allReady ? actors.stream().mapToLong(a -> a.ready).max().orElse(now) - batchStart : -1);
        row.addProperty("observedTps", (tick - submitTick) * 1e9 / Math.max(1, now - batchStart));
        row.addProperty("workP50Nanos", percentile(work, .5));
        row.addProperty("workP95Nanos", percentile(work, .95));
        row.addProperty("workP99Nanos", percentile(work, .99));
        row.addProperty("workMaxNanos", work.stream().mapToLong(Long::longValue).max().orElse(0));
        row.addProperty("over50msTicks", work.stream().filter(n -> n > 50_000_000).count());
        row.addProperty("measuredTicks", work.size());
        row.addProperty("submissionTickWorkNanos", submissionWork);
        row.addProperty("intervalP95Nanos", percentile(intervals, .95));
        row.addProperty("intervalMaxNanos", intervals.stream().mapToLong(Long::longValue).max().orElse(0));
        row.addProperty("heapStartBytes", heapStart);
        row.addProperty("peakHeapBytes", peakHeap);
        row.addProperty("peakHeapDeltaBytes", Math.max(0, peakHeap - heapStart));
        row.addProperty("mainThreadAllocatedBytes", PerformanceSearchTelemetry.allocated() - allocationStart);
        row.addProperty("submissionAllocatedBytes", submissionAllocated);
        row.addProperty("gcCount", gcCount() - gcCountStart);
        row.addProperty("gcTimeMillis", gcTime() - gcTimeStart);
        row.addProperty("peakActive", peakActive);
        row.addProperty("peakPending", peakPending);
        row.addProperty("peakPublishedNodes", peakNodes);
        var m = service.metrics();
        row.addProperty("nativeCallsDelta", m.nativeCalls() - nativeStart);
        row.addProperty("segmentCallsDelta", m.segmentCalls() - segmentStart);
        row.addProperty("wholeCallsDelta", m.nativeWholeCalls() - wholeStart);
        row.addProperty("serviceFailuresDelta", m.failures() - failureStart);
        row.addProperty("recoveriesDelta", m.recoveries() - recoveriesStart);
        row.addProperty("chargedNanosDelta", scheduler.metrics().chargedNanos() - chargedStart);
        row.add("topologyStart", topologyStart);
        row.add("topologyEnd", topology());
        JsonArray actorRows = new JsonArray();
        for (Actor a : actors) {
            JsonObject item = new JsonObject();
            item.addProperty("routeKind", a.route.kind());
            item.addProperty("tile", a.route.tile());
            item.addProperty("slot", a.route.slot());
            item.addProperty("rotation", a.route.rotation());
            item.add("start", gson.toJsonTree(List.of(a.route.start().getX(), a.route.start().getY(), a.route.start().getZ())));
            item.add("initialPosition", gson.toJsonTree(List.of(a.initialPosition.x, a.initialPosition.y, a.initialPosition.z)));
            item.addProperty("queryId", a.request == null ? null : a.request.queryId.toString());
            item.add("goal", gson.toJsonTree(List.of(a.route.goal().getX(), a.route.goal().getY(), a.route.goal().getZ())));
            item.add("finalPosition", gson.toJsonTree(List.of(a.mob.getX(), a.mob.getY(), a.mob.getZ())));
            item.addProperty("straightDistance", Math.sqrt(a.route.start().distSqr(a.route.goal())));
            item.addProperty("accepted", a.accepted);
            item.addProperty("success", a.success);
            item.addProperty("state", a.state);
            item.addProperty("nativeBackend", a.request != null && a.request.nativeBackend);
            item.addProperty("submitCallNanos", a.submitEnd - a.submitStart);
            item.addProperty("submitToReadyNanos", a.ready == 0 ? -1 : a.ready - a.submitStart);
            item.addProperty("batchToReadyNanos", a.ready == 0 ? -1 : a.ready - batchStart);
            item.addProperty("submitToPhysicalArrivalNanos", a.physicalArrival == 0 ? -1 : a.physicalArrival - a.submitStart);
            item.addProperty("firstMoveToPhysicalArrivalNanos", a.firstMove == 0 || a.physicalArrival == 0 ? -1 : a.physicalArrival - a.firstMove);
            item.addProperty("submitToFinishNanos", a.finish - a.submitStart);
            item.addProperty("maxPathNodes", a.maxNodes);
            item.add("nativeSearch", gson.toJsonTree(a.search));
            actorRows.add(item);
        }
        row.add("actors", actorRows);
        trials.add(row);
        System.out.println("PERF_FINISH index=" + index + " success=" + row.get("successfulActors")
                + "/" + actors.size() + " ms=" + (now - batchStart) / 1e6);
        for (Actor a : actors) {
            a.mob.getNavigation().stop(); a.mob.discard();
            PerformanceSearchTelemetry.ACTORS.remove(a.mob);
            HandoffDiagnostics.forget(a.mob);
        }
        actors.clear();
        nextTrial = tick + 20;
        writeResult();
        if (index + 1 == scenarios.size()) stop();
    }
    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int)Math.ceil(sorted.size() * p) - 1));
    }
    private void writeResult() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "high-concurrency-v2");
        root.addProperty("failed", failure != null);
        root.addProperty("failure", failure == null ? "" : failure.toString());
        root.addProperty("complete", index + 1 == scenarios.size() && actors.isEmpty());
        root.addProperty("variant", variant());
        root.addProperty("repeats", repeats);
        root.addProperty("timeoutTicks", timeout);
        root.addProperty("postTerminalObservationTicks", 10);
        root.addProperty("speedAmplifier", Integer.getInteger("an.production.performanceSpeedAmplifier", 1));
        root.addProperty("addedLoadNanos", addedLoadNanos);
        root.addProperty("submissionSpanTicks", submissionSpanTicks);
        root.addProperty("entity", "adult husk; infinite speed and glowing; no autonomous goals; entity pushing disabled");
        root.addProperty("seed", 20261013);
        root.add("routeKinds", gson.toJsonTree(KINDS));
        root.add("trials", trials);
        root.add("tickSamples", samples);
        Files.writeString(output.resolve("performance-comparison.json"), gson.toJson(root));
    }
    private void abort(Throwable problem) { failure = problem; problem.printStackTrace(); stop(); }
    private void stop() {
        if (closing) return;
        closing = true;
        try {
            for (Actor a : actors) { a.mob.getNavigation().stop(); a.mob.discard(); }
            writeResult();
        } catch (Throwable problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        try {
            if (!closing) { failure = new IllegalStateException("server stopped before completing benchmark"); writeResult(); }
            JsonObject shutdown = new JsonObject();
            shutdown.addProperty("passed", failure == null && closing && service.metrics().records() == 0
                    && service.metrics().active() == 0 && scheduler.metrics().pendingRequests() == 0);
            shutdown.add("navigation", gson.toJsonTree(service.metrics()));
            shutdown.add("scheduler", gson.toJsonTree(scheduler.metrics()));
            Files.writeString(output.resolve("shutdown.json"), gson.toJson(shutdown));
            HandoffDiagnostics.write();
        } catch (Throwable problem) { problem.printStackTrace(); }
    }
}
