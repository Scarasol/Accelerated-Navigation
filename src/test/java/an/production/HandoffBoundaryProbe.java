package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeQueryProof;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Disrupts a naturally captured handoff in an isolated copy of the real stairs. */
public final class HandoffBoundaryProbe {
    private final java.nio.file.Path output =
            java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final JsonArray checks = new JsonArray();
    private final List<String> scenarios =
            List.of("native-stop", "native-input-change", "queued-input-change",
                    "cancel", "replace", "recompute", "window-close");
    private final BlockPos marker = new BlockPos(-2, 80, -103);
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private Husk actor;
    private NavigationRequestPath request;
    private Object work, capturedEntry;
    private BlockState originalMarker;
    private int tick, scenarioIndex = -1, spawnedTick, movementTick = -1, disruptedTick = -1;
    private int originalCursor;
    private long nativeBefore, recoverBefore;
    private boolean closing, queuedInputChanged, heldSettleBudget;
    private Throwable failure;

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @SuppressWarnings("unchecked")
    private Object work() throws Exception {
        return ((Map<NavigationRequestPath, ?>) field(service, "records")).get(request);
    }

    private boolean arrival() throws Exception {
        Method method = SegmentedNavigationService.class.getDeclaredMethod("intermediateArrival", work.getClass());
        method.setAccessible(true);
        return (boolean) method.invoke(service, work);
    }

    private void check(String name, boolean passed, Object detail) {
        JsonObject row = new JsonObject();
        row.addProperty("scenario", scenarios.get(scenarioIndex));
        row.addProperty("name", name);
        row.addProperty("passed", passed);
        row.add("detail", new GsonBuilder().create().toJsonTree(detail));
        checks.add(row);
        if (!passed) throw new AssertionError(name + ": " + detail);
    }

    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob
                && !event.getEntity().getClass().getName()
                        .equals("com.scarasol.acceleratednavigation.NavigationTestMob"))
            event.setCanceled(true);
    }

    @SubscribeEvent public void start(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.setDifficulty(Difficulty.NORMAL, true);
            for (int x = -4; x <= 0; x++) for (int z = -9; z <= -5; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
            originalMarker = level.getBlockState(marker);
            level.setBlock(marker, Blocks.STONE.defaultBlockState(), 3);
            service = SegmentedNavigationService.forServer(server);
            scheduler = NavigationScheduler.forServer(server);
        } catch (Throwable problem) { abort(problem); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        try {
            tick++;
            if (tick < 40) return;
            if (actor == null) {
                if (++scenarioIndex == scenarios.size()) { stop(); return; }
                var constructor = Class.forName("com.scarasol.acceleratednavigation.NavigationTestMob")
                        .getDeclaredConstructor(ServerLevel.class, boolean.class);
                constructor.setAccessible(true);
                actor = (Husk) constructor.newInstance(level, false);
                actor.setPos(-4.5, 74.01, -102.5);
                if (!level.addFreshEntity(actor)) throw new IllegalStateException("Actor not added");
                spawnedTick = tick;
                movementTick = disruptedTick = -1;
                queuedInputChanged = heldSettleBudget = false;
                work = capturedEntry = null;
                return;
            }
            if (movementTick < 0) {
                if (tick - spawnedTick < 20) return;
                check("actual-grounded-start", actor.onGround(), actor.position().toString());
                check("actual-segmented-move", actor.getNavigation().moveTo(-4.5, 78, -98.5, 1), "north stair");
                request = service.active(actor.getNavigation());
                check("active-request", request != null, "real service request");
                movementTick = tick;
                return;
            }
            if (disruptedTick < 0) {
                work = work();
                if (work == null || request.terminal()) throw new AssertionError("Missing entry before terminal");
                capturedEntry = field(work, "segmentEntry");
                if (capturedEntry == null) {
                    if (tick - movementTick > 250) throw new AssertionError("No real handoff entry");
                    return;
                }
                check("real-intermediate-entry", !(boolean) field(work, "last")
                        && (boolean) field(work, "segmentReachable"), actor.position().toString());
                check("current-entry-consumable", arrival(), "before disruption");
                originalCursor = (int) field(work, "cursor");
                nativeBefore = service.metrics().nativeCalls();
                recoverBefore = service.metrics().recoveries();
                disrupt();
                disruptedTick = tick;
                return;
            }
            String scenario = scenarios.get(scenarioIndex);
            if (scenario.equals("queued-input-change") && !queuedInputChanged) {
                if (heldSettleBudget && (boolean) field(work, "queued") && request.isDone()) {
                    check("actual-settle-awaits-shared-budget", true, "nodes exhausted, settle queued");
                    level.setBlock(marker, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                    check("queued-input-proof-stale",
                            !((NativeQueryProof) field(work, "nativeProof")).current(), marker.toString());
                    check("queued-stale-input-rejects-handoff", !arrival(), "before END dispatch");
                    queuedInputChanged = true;
                }
                if (!queuedInputChanged) {
                    if (tick - disruptedTick > 250)
                        throw new AssertionError("Settle was not queued across the input change");
                    return;
                }
            }
            if (scenario.equals("native-stop")) {
                if (service.metrics().nativeCalls() > nativeBefore) {
                    check("native-stop-runs-real-recovery", service.metrics().recoveries() > recoverBefore,
                            service.metrics().toString());
                    check("native-stop-does-not-advance-cursor",
                            (int) field(work, "cursor") <= originalCursor, field(work, "cursor"));
                    complete();
                }
            } else if (scenario.equals("native-input-change")
                    || scenario.equals("queued-input-change") && queuedInputChanged) {
                if (service.metrics().nativeCalls() > nativeBefore) {
                    check("input-change-runs-new-native-search", field(work, "segmentEntry") != capturedEntry,
                            service.metrics().toString());
                    check("input-change-keeps-current-cursor", (int) field(work, "cursor") == originalCursor,
                            field(work, "cursor"));
                    complete();
                }
            } else if (scenario.equals("recompute") || scenario.equals("window-close")) {
                if (service.metrics().nativeCalls() > nativeBefore) {
                    check("new-search-discards-old-entry", field(work, "segmentEntry") != capturedEntry,
                            service.metrics().toString());
                    check("replan-keeps-request-identity", service.active(actor.getNavigation()) == request,
                            request.state.name());
                    complete();
                }
            } else complete();
            if (actor != null && tick - disruptedTick > 250)
                throw new AssertionError("Disruption did not reach its expected lifecycle");
        } catch (Throwable problem) { abort(problem); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void holdSettle(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing
                || disruptedTick < 0 || queuedInputChanged || heldSettleBudget
                || !scenarios.get(scenarioIndex).equals("queued-input-change"))return;
        try {
            if (request.isDone() && (int) field(work, "stable") >= 2
                    && !(boolean) field(work, "queued") && !(boolean) field(work, "preparing")) {
                // One measured delay leaves the real settle queued across an input change.
                long balance = scheduler.metrics().availableNanos();
                try (var ignored = scheduler.account()) {
                    long deadline = System.nanoTime() + Math.max(20_000_000L, balance + 1_000_000L);
                    while (System.nanoTime() < deadline) Thread.onSpinWait();
                }
                check("hold-exhausts-current-dynamic-budget", scheduler.metrics().availableNanos() < 500_000L,
                        scheduler.metrics().availableNanos());
                heldSettleBudget = true;
            }
        } catch (Throwable problem) { abort(problem); }
    }

    private void disrupt() throws Exception {
        switch (scenarios.get(scenarioIndex)) {
            case "native-stop" -> {
                AutomaticNavigationBridge.nativeStop(actor.getNavigation(), () -> actor.getNavigation().stop());
                check("native-stop-clears-entry", field(work, "segmentEntry") == null, "native stop callback");
                check("native-stop-rejects-handoff", !arrival(), "stopped path is not natural exhaustion");
            }
            case "native-input-change" -> {
                level.setBlock(marker, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                NativeQueryProof proof = (NativeQueryProof) field(work, "nativeProof");
                check("actual-native-input-became-stale", !proof.current(), marker.toString());
                check("stale-native-input-rejects-handoff", !arrival(), "same collision, different native rule");
            }
            case "queued-input-change" -> {}
            case "cancel" -> {
                actor.getNavigation().stop();
                check("cancel-removes-work", work() == null && request.state == NavigationRequestPath.State.CANCELLED,
                        request.state.name());
                check("cancel-clears-entry", field(work, "segmentEntry") == null, "terminal cleanup");
            }
            case "replace" -> {
                check("replacement-accepted", actor.getNavigation().moveTo(-4.5, 80, -96.5, 1), "new target");
                check("replacement-cancels-old-request", request.state == NavigationRequestPath.State.CANCELLED
                        && service.active(actor.getNavigation()) != request, request.state.name());
                check("replacement-clears-old-entry", field(work, "segmentEntry") == null, "old work cleanup");
            }
            case "recompute" -> actor.getNavigation().recomputePath();
            case "window-close" -> {
                ((GroundSegmentWindow) field(work, "window")).close();
                check("closed-window-rejects-handoff", !arrival(), "closed topology view");
            }
            default -> throw new IllegalStateException("Unknown scenario");
        }
    }

    private void complete() throws Exception {
        actor.getNavigation().stop();
        actor.discard();
        actor = null;
        level.setBlock(marker, Blocks.STONE.defaultBlockState(), 3);
        writeResult();
    }

    private void writeResult() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("failed", failure != null);
        result.addProperty("failure", failure == null ? "" : failure.toString());
        result.addProperty("tick", tick);
        result.add("checks", checks);
        if (service != null) result.add("navigation", new GsonBuilder().create().toJsonTree(service.metrics()));
        Files.writeString(output.resolve("diagnosis.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private void abort(Throwable problem) {
        failure = problem;
        problem.printStackTrace();
        stop();
    }

    private void stop() {
        if (closing) return;
        closing = true;
        try {
            if (actor != null) { actor.getNavigation().stop(); actor.discard(); }
            if (originalMarker != null) level.setBlock(marker, originalMarker, 3);
            writeResult();
        } catch (Throwable problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("failed", failure != null);
            result.addProperty("navigationClosed", SegmentedNavigationService.isClosed(server));
            result.add("navigation", new GsonBuilder().create().toJsonTree(service.metrics()));
            result.add("scheduler", new GsonBuilder().create().toJsonTree(scheduler.metrics()));
            Files.writeString(output.resolve("shutdown.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Throwable problem) { problem.printStackTrace(); }
    }
}
