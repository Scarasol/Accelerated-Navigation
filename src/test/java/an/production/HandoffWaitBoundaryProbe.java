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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Observes actual last-node protection before injecting isolated lifecycle faults. */
public final class HandoffWaitBoundaryProbe {
    private static HandoffWaitBoundaryProbe instance;
    private final List<String> scenarios = List.of("entry-29", "entry-281", "entry-281-offset0022",
            "entry-281-offset003", "entry-281-offset0035", "timeout", "native-stop", "stale",
            "cancel", "replace", "recompute", "window-close", "shutdown");
    private final JsonArray checks = new JsonArray();
    private final Path output = Path.of(System.getProperty("an.production.output"));
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private PerformanceComparisonProbe.BenchmarkHusk actor;
    private NavigationRequestPath request;
    private Object work;
    private int scenario = -1, tick, spawned, disrupted = -1, cursor;
    private long calls, recoveries;
    private BlockPos originalTarget;
    private boolean freezeMovement;
    private BlockPos marker;
    private boolean closing;
    private Throwable failure;

    public HandoffWaitBoundaryProbe() { instance = this; }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private boolean defer() {
        return service.deferSegmentAdvance(actor.getNavigation(), request, true);
    }
    public static boolean freeze(Mob mob) {
        return instance != null && instance.freezeMovement && instance.actor == mob;
    }
    public static void afterAdvance(SegmentedNavigationService service, PathNavigation navigation,
            net.minecraft.world.level.pathfinder.Path path, boolean held, boolean following) {
        var probe = instance;
        if (probe == null || probe.request == null || probe.closing || probe.disrupted >= 0
                || !held || !following || path != probe.request || navigation != probe.actor.getNavigation()) return;
        try {
            probe.work = ((Map<?, ?>) field(service, "records")).get(probe.request);
            if (probe.work == null || (boolean) field(probe.work, "last")) return;
            probe.disrupt();
        } catch (Throwable problem) { probe.abort(problem); }
    }
    private void check(String name, boolean passed, Object detail) {
        JsonObject row = new JsonObject();
        row.addProperty("scenario", scenarios.get(scenario));
        row.addProperty("name", name);
        row.addProperty("passed", passed);
        row.add("detail", new GsonBuilder().create().toJsonTree(detail));
        checks.add(row);
        if (!passed) throw new AssertionError(name + ": " + detail);
    }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob && !(event.getEntity() instanceof PerformanceComparisonProbe.BenchmarkHusk))
            event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        server = event.getServer(); level = server.overworld();
        try {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            PerformanceComparisonProbe.prepareArena(server, level);
            service = SegmentedNavigationService.forServer(server);
            scheduler = NavigationScheduler.forServer(server);
        } catch (Throwable problem) { abort(problem); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        try {
            tick++;
            if (tick < 180) return;
            if (actor == null) {
                scenario++;
                if (scenario >= scenarios.size()) { stop(); return; }
                actor = new PerformanceComparisonProbe.BenchmarkHusk(level);
                String current = scenarios.get(scenario);
                double offset = current.endsWith("0022") ? .022 : current.endsWith("0035") ? .035
                        : current.endsWith("003") ? .03 : 0;
                actor.setPos(-16.5, 81.01, current.startsWith("entry-281") ? 32.5 + offset : -136.5);
                if (!level.addFreshEntity(actor)) throw new AssertionError("actor rejected");
                spawned = tick; disrupted = -1; request = null; freezeMovement = false;
                return;
            }
            if (request == null && tick - spawned >= 20) {
                check("actual-ground-start", actor.onGround(), actor.position());
                boolean route281 = scenarios.get(scenario).startsWith("entry-281");
                check("real-route-accepted", actor.getNavigation().moveTo(-16.5, 81, route281 ? 4.5 : -154.5, 1),
                        route281 ? "28 block actor281" : "18 block actor29");
                request = service.active(actor.getNavigation());
                check("real-request", request != null, "production handle");
            }
            if (disrupted < 0) {
                if (request != null && request.terminal() || tick - spawned > 600)
                    throw new AssertionError("route did not naturally trigger last-node protection");
                return;
            }
            String current = scenarios.get(scenario);
            if (current.startsWith("entry-")) {
                if (!request.terminal()) {
                    if (tick - disrupted > 350) throw new AssertionError("protected route did not finish");
                    return;
                }
                check("real-arrival-after-last-node-protection", request.state == NavigationRequestPath.State.SUCCEEDED,
                        request.state);
                check("normal-entry-without-recovery", service.metrics().recoveries() == recoveries, service.metrics());
                check("normal-entry-only-final-search", service.metrics().nativeCalls() == calls + 1, service.metrics());
            } else if (List.of("timeout", "native-stop", "stale", "recompute", "window-close").contains(current)
                    && service.metrics().nativeCalls() == calls) {
                if (tick - disrupted > 250) throw new AssertionError("no real search after fault");
                return;
            }
            if (current.equals("native-stop") || current.equals("timeout")) {
                check("native-stop-recovery", service.metrics().recoveries() > recoveries, service.metrics());
                check("native-stop-no-cursor-advance", (int) field(work, "cursor") == cursor, field(work, "cursor"));
                check("failed-target-recorded-only-after-real-recovery",
                        ((java.util.Set<?>) field(work, "failedTargets")).contains(originalTarget), originalTarget);
            } else if (current.equals("stale")) {
                check("stale-real-search-keeps-cursor", (int) field(work, "cursor") == cursor, field(work, "cursor"));
                check("stale-publishes-current-proof", ((NativeQueryProof) field(work, "nativeProof")).current(), "fresh input");
            } else if (current.equals("recompute") || current.equals("window-close")) {
                check("replan-retains-request", service.active(actor.getNavigation()) == request, request.state);
                check("replan-replaces-window", ((GroundSegmentWindow) field(work, "window")).current(), "new window");
            }
            freezeMovement = false;
            actor.getNavigation().stop(); actor.discard(); actor = null; request = null;
            if (marker != null) { level.setBlock(marker, Blocks.AIR.defaultBlockState(), 2); marker = null; }
            write();
        } catch (Throwable problem) { abort(problem); }
    }
    private void disrupt() throws Exception {
        check("actual-production-node-protection", !request.isDone()
                && request.getNextNodeIndex() == request.getNodeCount() - 1
                && (boolean) field(work, "segmentReachable") && field(work, "segmentEntry") == null
                && !((GroundSegmentWindow) field(work, "window")).contains(actor.blockPosition()),
                "node=" + request.getNextNodeIndex() + " position=" + actor.position());
        check("no-failure-target-on-normal-publication",
                ((java.util.Set<?>) field(work, "failedTargets")).isEmpty(), field(work, "failedTargets"));
        check("no-fixed-done-wait-origin", (long) field(work, "doneSince") == -1, field(work, "doneSince"));
        originalTarget = (BlockPos) field(work, "segmentTarget");
        check("real-selected-segment-target-retained", originalTarget != null, originalTarget);
        cursor = (int) field(work, "cursor");
        calls = service.metrics().nativeCalls(); recoveries = service.metrics().recoveries(); disrupted = tick;
        switch (scenarios.get(scenario)) {
            case "entry-29", "entry-281", "entry-281-offset0022", "entry-281-offset003", "entry-281-offset0035" -> {}
            case "timeout" -> freezeMovement = true;
            case "native-stop" -> {
                AutomaticNavigationBridge.nativeStop(actor.getNavigation(), () -> actor.getNavigation().stop());
                check("native-stop-excludes-further-protection", !defer(), "actual native stop");
            }
            case "stale" -> {
                marker = actor.blockPosition().above(5);
                level.setBlock(marker, Blocks.STONE.defaultBlockState(), 2);
                check("actual-input-invalidated", !((NativeQueryProof) field(work, "nativeProof")).current(), marker);
                check("stale-excludes-further-protection", !defer() && !service.executionReady(actor.getNavigation()),
                        "changed section revision");
            }
            case "cancel" -> {
                actor.getNavigation().stop();
                check("cancel-cleans-old-work", request.state == NavigationRequestPath.State.CANCELLED
                        && service.active(actor.getNavigation()) == null && !defer(), request.state);
            }
            case "replace" -> {
                check("replacement-accepted", actor.getNavigation().moveTo(-16.5, 81, -134.5, 1), "other target");
                check("replacement-cancels-old-work", request.state == NavigationRequestPath.State.CANCELLED
                        && service.active(actor.getNavigation()) != request && !defer(), request.state);
            }
            case "recompute" -> {
                actor.getNavigation().recomputePath();
                check("recompute-queued", (boolean) field(work, "queued"), "same request replan");
            }
            case "window-close" -> {
                ((GroundSegmentWindow) field(work, "window")).close();
                check("closed-window-excludes-further-protection",
                        !defer() && !service.executionReady(actor.getNavigation()), "closed");
            }
            case "shutdown" -> {
                SegmentedNavigationService.shutdown(server);
                NavigationScheduler.shutdown(server);
                check("shutdown-cleans-waiting-work", service.metrics().records() == 0
                        && scheduler.metrics().pendingRequests() == 0 && !defer(), service.metrics());
                check("shutdown-rejects-late-callback", request.terminal(), request.state);
                stop();
            }
            default -> throw new AssertionError("unknown fault");
        }
    }
    private void write() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("failed", failure != null);
        result.addProperty("failure", failure == null ? "" : failure.toString());
        result.addProperty("complete", closing && failure == null && scenario == scenarios.size() - 1);
        result.addProperty("tick", tick);
        result.add("checks", checks);
        Files.writeString(output.resolve("wait-boundaries.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
    private void abort(Throwable problem) { failure = problem; problem.printStackTrace(); stop(); }
    private void stop() {
        if (closing) return; closing = true;
        try { if (actor != null) { actor.getNavigation().stop(); actor.discard(); } write(); }
        catch (Throwable problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        instance = null;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("passed", failure == null && closing && service.metrics().records() == 0
                    && service.metrics().active() == 0 && scheduler.metrics().pendingRequests() == 0);
            result.add("navigation", new GsonBuilder().create().toJsonTree(service.metrics()));
            result.add("scheduler", new GsonBuilder().create().toJsonTree(scheduler.metrics()));
            Files.writeString(output.resolve("shutdown.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Throwable problem) { problem.printStackTrace(); }
    }
}
