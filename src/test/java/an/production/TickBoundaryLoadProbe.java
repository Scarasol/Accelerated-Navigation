package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Actual server event-loop tasks, load, an atomic overrun, and an unknown END tail. */
public final class TickBoundaryLoadProbe {
    private final Path output = Path.of(System.getProperty("an.production.output"));
    private final JsonArray samples = new JsonArray(), tasks = new JsonArray(), checks = new JsonArray();
    private MinecraftServer server;
    private NavigationScheduler scheduler;
    private SegmentedNavigationService service;
    private Field envelope;
    private int tick;
    private boolean closing;
    private Throwable failure;
    private CompletableFuture<Integer> atom;
    private static int stage(int tick) { return Math.min(9, Math.max(0, (tick - 1) / 50)); }
    private static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
    private void check(String name, boolean passed, Object detail) {
        JsonObject row = new JsonObject(); row.addProperty("name", name); row.addProperty("passed", passed);
        row.add("detail", new GsonBuilder().create().toJsonTree(detail)); checks.add(row);
        if (!passed) throw new AssertionError(name + ": " + detail);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        server = event.getServer();
        try {
            ProductionBudgetChecks.run(server);
            scheduler = NavigationScheduler.forServer(server);
            service = SegmentedNavigationService.forServer(server);
            envelope = NavigationScheduler.class.getDeclaredField("inEnvelope"); envelope.setAccessible(true);
        } catch (Throwable problem) { abort(problem); }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void start(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.START || closing) return;
        try {
            tick++;
            var previous = scheduler.budgetMetrics().lastTick();
            if (previous != null) {
                JsonObject sample = new GsonBuilder().create().toJsonTree(previous).getAsJsonObject();
                sample.addProperty("stage", stage(tick - 1)); samples.add(sample);
            }
            int stage = stage(tick);
            long load = switch (stage) { case 1 -> 10; case 2 -> 30; case 3 -> 40; case 4 -> 48; default -> 0; };
            if (load > 0) busy(load * 1_000_000);
            if (tick == 20)
                atom = scheduler.submitSoft(Level.OVERWORLD, UUID.randomUUID(), NavigationScheduler.Priority.ACTIVE,
                        500_000, () -> { busy(60_000_000); return 1; });
            if (scheduler.metrics().pendingRequests() < 8)
                for (int i = 0; i < 8; i++)
                    scheduler.submitSoft(Level.OVERWORLD, UUID.randomUUID(), NavigationScheduler.Priority.ACTIVE,
                            500_000, () -> { busy(250_000); return 1; });
        } catch (Throwable problem) { abort(problem); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        try {
            int stage = stage(tick);
            if (stage == 5) busy(12_000_000);
            if (stage >= 6 && stage <= 8) {
                long load = stage == 6 ? 10_000_000 : stage == 7 ? 40_000_000 : 60_000_000;
                Thread submitter = new Thread(() -> server.execute(() -> {
                    if (closing) return;
                    try {
                        long start = System.nanoTime();
                        boolean inside = envelope.getBoolean(scheduler);
                        busy(load);
                        long before = scheduler.metrics().chargedNanos();
                        try (var ignored = scheduler.account()) { busy(2_000_000); }
                        JsonObject task = new JsonObject();
                        task.addProperty("stage", stage); task.addProperty("tick", server.getTickCount());
                        task.addProperty("inEnvelope", inside); task.addProperty("busyNanos", load);
                        task.addProperty("workNanos", System.nanoTime() - start);
                        task.addProperty("navigationNanos", scheduler.metrics().chargedNanos() - before);
                        tasks.add(task);
                    } catch (Throwable problem) { abort(problem); }
                }), "navigation-boundary-test-submitter");
                submitter.setDaemon(true); submitter.start(); submitter.join(2000);
                if (submitter.isAlive()) throw new AssertionError("event-loop submission did not finish");
            }
            if (tick >= 530) {
                check("real-long-atomic-call-completes", atom != null && atom.isDone() && atom.join() == 1, "60ms atomic call");
                boolean zero = false, tail = false, debt = false;
                long outside = 0;
                for (var row : samples) {
                    var sample = row.getAsJsonObject();
                    if (sample.get("stage").getAsInt() == 4 && sample.get("grantNanos").getAsLong() == 0) zero = true;
                    if (sample.get("stage").getAsInt() == 5 && sample.get("tailReserveNanos").getAsLong() >= 12_000_000) tail = true;
                    if (sample.get("finalBalanceNanos").getAsLong() < 0) debt = true;
                    outside += sample.get("outsideNavigationNanos").getAsLong();
                }
                check("48ms-base-load-selects-zero", zero, "no forced minimum");
                check("unknown-end-tail-is-learned", tail, "measured >=12ms tail");
                check("atomic-overrun-keeps-debt", debt, "actual negative balances");
                Set<Integer> outsideStages = new HashSet<>();
                for (var row : tasks) if (!row.getAsJsonObject().get("inEnvelope").getAsBoolean())
                    outsideStages.add(row.getAsJsonObject().get("stage").getAsInt());
                check("real-event-loop-outside-tasks", outsideStages.containsAll(Set.of(6, 7, 8)), outsideStages);
                check("outside-navigation-remains-attributed-outside", outside >= 200_000_000, outside);
                check("post-load-queued-work-drains", scheduler.metrics().pendingRequests() == 0, scheduler.metrics());
                stop();
            }
        } catch (Throwable problem) { abort(problem); }
    }
    private void abort(Throwable problem) { failure = problem; problem.printStackTrace(); stop(); }
    private void stop() {
        if (closing) return; closing = true;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("failed", failure != null); result.addProperty("failure", failure == null ? "" : failure.toString());
            result.addProperty("actualIntegratedPauseTested", false);
            result.addProperty("missingStartEndAndResume", "synthetic scheduler controls; actual dedicated event loop for tasks and catch-up");
            result.add("samples", samples); result.add("tasks", tasks); result.add("checks", checks);
            Files.writeString(output.resolve("tick-boundary-load.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Throwable problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("passed", failure == null && closing && service.metrics().records() == 0
                    && scheduler.metrics().pendingRequests() == 0);
            Files.writeString(output.resolve("shutdown.json"), new GsonBuilder().create().toJson(result));
        } catch (Throwable problem) { problem.printStackTrace(); }
    }
}
