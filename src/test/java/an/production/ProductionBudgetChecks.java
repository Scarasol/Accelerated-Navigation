package an.production;

import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Actual scheduler controls, isolated and restored after the real 30-second gate. */
final class ProductionBudgetChecks {
    private static final List<String> checks = new ArrayList<>();
    private static Field field(String name) throws Exception {
        Field result = NavigationScheduler.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void require(boolean passed, String name) {
        if (!passed) throw new AssertionError("production budget: " + name);
        checks.add(name);
    }
    private static void begin(MinecraftServer server, BooleanSupplier haveTime) {
        NavigationScheduler.beginTickEnvelope(server, haveTime);
        NavigationScheduler.beginServerTick(server);
    }
    private static void end(MinecraftServer server, boolean haveTime) {
        NavigationScheduler.endServerTick(server, haveTime);
        NavigationScheduler.forServer(server).finishTickEnvelope(true);
    }
    private static void idle(MinecraftServer server, int count) {
        for (int i = 0; i < count; i++) { begin(server, () -> true); end(server, true); }
    }
    private static long grant(NavigationScheduler scheduler) throws Exception {
        return NavigationScheduler.TICK_BUDGET_NANOS;
    }
    private static void reset(NavigationScheduler scheduler, AtomicLong clock) throws Exception {
        require(scheduler.metrics().pendingRequests() == 0, "reset has no pending work");
        for (String name : List.of("tokensNanos", "chargedNanos", "previousChargeAtEnd", "validTicks", "skippedTicks"))
            field(name).setLong(scheduler, 0);
        field("lastTick").set(scheduler, null);
        ((List<?>) field("dimensionOrder").get(scheduler)).clear();
        Arrays.fill((int[]) field("dimensionCursors").get(scheduler), 0);
        clock.set(0);
    }
    private static CompletableFuture<Integer> task(NavigationScheduler scheduler, AtomicLong clock,
            AtomicInteger calls, long cost, boolean nether) throws Exception {
        if (scheduler.metrics().pendingRequests() == 0)
            Arrays.fill((int[]) field("dimensionCursors").get(scheduler), 0);
        return scheduler.submitSoft(nether ? Level.NETHER : Level.OVERWORLD, UUID.randomUUID(),
                NavigationScheduler.Priority.ACTIVE, 500_000, () -> {
                    calls.incrementAndGet(); clock.addAndGet(cost); return calls.get();
                });
    }

    static void run(MinecraftServer server) throws Exception {
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        require(scheduler.metrics().pendingRequests() == 0, "controls start empty");
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (Field candidate : NavigationScheduler.class.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers()) || Modifier.isFinal(candidate.getModifiers())) continue;
            candidate.setAccessible(true); saved.put(candidate, candidate.get(scheduler));
        }
        long[] savedStops = ((long[]) field("stopCounts").get(scheduler)).clone();
        int[] savedCursors = ((int[]) field("dimensionCursors").get(scheduler)).clone();
        @SuppressWarnings("unchecked")
        List<Object> dimensions = (List<Object>) field("dimensionOrder").get(scheduler);
        List<Object> savedDimensions = new ArrayList<>(dimensions);
        AtomicLong clock = new AtomicLong();
        int firstCheck = checks.size();
        try {
            field("inEnvelope").setBoolean(scheduler, false);
            field("startSeen").setBoolean(scheduler, false);
            field("endSeen").setBoolean(scheduler, false);
            field("accountingDepth").setInt(scheduler, 0);
            field("clock").set(scheduler, (java.util.function.LongSupplier) clock::get);
            for (int pass = 0; pass < 2; pass++) controls(server, scheduler, clock);
            Files.writeString(Path.of(System.getProperty("an.production.output"), "budget-controls.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(
                            Map.of("passed", true, "passes", 2, "checks", checks.subList(firstCheck, checks.size()))));
            System.out.println("PRODUCTION_BUDGET_CONTROLS " + (checks.size() - firstCheck) + " passed; passes=2");
        } finally {
            var cancel = NavigationScheduler.class.getDeclaredMethod("cancelAll");
            cancel.setAccessible(true); cancel.invoke(scheduler);
            for (var entry : saved.entrySet()) entry.getKey().set(scheduler, entry.getValue());
            System.arraycopy(savedStops, 0, field("stopCounts").get(scheduler), 0, savedStops.length);
            System.arraycopy(savedCursors, 0, field("dimensionCursors").get(scheduler), 0, savedCursors.length);
            dimensions.clear(); dimensions.addAll(savedDimensions);
        }
    }

    private static void controls(MinecraftServer server, NavigationScheduler scheduler, AtomicLong clock) throws Exception {
        reset(scheduler, clock);
        AtomicInteger calls = new AtomicInteger();
        var first = scheduler.submitSoft(Level.OVERWORLD, UUID.randomUUID(), NavigationScheduler.Priority.ACTIVE,
                500_000, () -> {
                    calls.incrementAndGet(); clock.addAndGet(20_700_000);
                    try (var ignored = scheduler.account()) { clock.addAndGet(200_000); }
                    return 7;
                });
        first.thenAccept(value -> clock.addAndGet(800_000));
        var second = task(scheduler, clock, calls, 0, true);
        begin(server, () -> true); end(server, false);
        require(calls.get() == 0, "END haveTime prevents dispatch");
        begin(server, () -> true);
        require(scheduler.metrics().availableNanos() == 20_000_000, "fixed allowance does not accumulate");
        end(server, true);
        require(first.join() == 7 && calls.get() == 1 && !second.isDone(), "cold overshoot stops next task");
        require(scheduler.metrics().availableNanos() == -1_700_000, "search and callback overshoot recorded");
        require(scheduler.metrics().chargedNanos() == 21_700_000, "nested account union counted once");
        begin(server, () -> true);
        require(scheduler.metrics().availableNanos() == 20_000_000, "next tick starts without previous overshoot");
        end(server, true);
        require(second.isDone() && calls.get() == 2, "other dimension progresses");
        require(scheduler.budgetMetrics().lastTick().fullNanos() == 0, "zero clock is valid");

        UUID owner = UUID.randomUUID();
        var old = scheduler.submitSoft(Level.OVERWORLD, owner, NavigationScheduler.Priority.ACTIVE, 500_000, () -> 1);
        var next = scheduler.submitSoft(Level.OVERWORLD, owner, NavigationScheduler.Priority.ACTIVE, 500_000, () -> 2);
        require(old.isCancelled() && !next.isDone(), "same key replaces");
        scheduler.cancel(Level.OVERWORLD, owner);
        require(next.isCancelled(), "cancel prevents execution");
        Parking search = new Parking();
        var parked = scheduler.submitStrict(Level.OVERWORLD, UUID.randomUUID(), NavigationScheduler.Priority.ACTIVE, search);
        begin(server, () -> true); end(server, true);
        require(search.steps == 1 && !parked.isDone() && scheduler.budgetMetrics().parkedRequests() == 1,
                "parked task classified as dependency");
        search.wake.run();
        require(search.steps == 1 && scheduler.budgetMetrics().runnableRequests() == 1, "wake only enqueues");
        begin(server, () -> true); end(server, true);
        require(parked.join() == 9 && search.steps == 2, "wake runs at later END");

        idle(server, 45);
        long maximum = NavigationScheduler.TICK_BUDGET_NANOS;
        require(scheduler.budgetMetrics().lastTick().grantNanos() == maximum, "allowance remains fixed");
        calls.set(0);
        first = task(scheduler, clock, calls, maximum + 1_000_000, false);
        second = task(scheduler, clock, calls, 0, true);
        begin(server, () -> true); end(server, true);
        require(first.isDone() && !second.isDone() && calls.get() == 1, "steady maximum overshoot halts");
        require(scheduler.metrics().availableNanos() == -1_000_000, "steady overshoot recorded");
        begin(server, () -> true);
        require(scheduler.metrics().availableNanos() == grant(scheduler), "steady tick starts with fresh grant");
        end(server, true);
        require(second.isDone(), "steady next tick progresses");

        calls.set(0);
        first = task(scheduler, clock, calls, 100_000, false);
        second = task(scheduler, clock, calls, 0, true);
        begin(server, () -> calls.get() == 0); end(server, true);
        require(first.isDone() && !second.isDone()
                && scheduler.budgetMetrics().lastTick().stopReason() == NavigationScheduler.StopReason.SERVER_NO_TIME,
                "supplier withdrawal stops next task");
        begin(server, () -> true); end(server, true);
        require(second.isDone(), "withdrawal preserves queued task");

        reset(scheduler, clock);
        calls.set(0);
        first = task(scheduler, clock, calls, 1_000_000, false);
        second = task(scheduler, clock, calls, 0, true);
        begin(server, () -> true); clock.addAndGet(42_000_000); end(server, true);
        require(first.isDone() && !second.isDone()
                && scheduler.budgetMetrics().lastTick().stopReason() == NavigationScheduler.StopReason.HEADROOM,
                "real deadline rechecked between tasks");
        begin(server, () -> true); end(server, true);
        require(second.isDone(), "deadline preserves queued work");

        reset(scheduler, clock);
        calls.set(0);
        first = task(scheduler, clock, calls, 60_000_000, false);
        begin(server, () -> true); end(server, true);
        require(first.isDone() && scheduler.budgetMetrics().lastTick().fullNanos() == 60_000_000,
                "long atom measured without hard preemption");
        require(scheduler.metrics().availableNanos() == -40_000_000, "long atom actual overshoot not clipped");
        begin(server, () -> true);
        require(grant(scheduler) == 20_000_000, "long tick does not change next grant");
        require(scheduler.metrics().availableNanos() == 20_000_000, "long atom overshoot does not reduce next balance");
        end(server, true);

        reset(scheduler, clock);
        begin(server, () -> true); clock.addAndGet(48_000_000); end(server, true);
        try (var ignored = scheduler.account()) { clock.addAndGet(6_000_000); }
        owner = UUID.randomUUID();
        var blocked = scheduler.submitSoft(Level.OVERWORLD, owner, NavigationScheduler.Priority.ACTIVE, 500_000, () -> 1);
        begin(server, () -> true);
        require(grant(scheduler) == 20_000_000, "previous overload does not change grant");
        require(scheduler.metrics().availableNanos() == 20_000_000, "outside work does not carry into new balance");
        require(scheduler.metrics().chargedNanos() == 6_000_000, "outside work remains fully accounted");
        end(server, true);
        require(blocked.isDone(), "light tick progresses after earlier overload");

        reset(scheduler, clock);
        idle(server, 8);
        long balance = scheduler.metrics().availableNanos();
        for (int i = 0; i < 20; i++) {
            NavigationScheduler.beginTickEnvelope(server, () -> true);
            clock.addAndGet(100_000); scheduler.finishTickEnvelope(true);
        }
        require(scheduler.budgetMetrics().validTicks() == 8 && scheduler.budgetMetrics().skippedTicks() == 20,
                "pause excludes normal tick samples");
        require(scheduler.metrics().availableNanos() == balance, "pause does not refill");
        begin(server, () -> true);
        require(grant(scheduler) == 20_000_000, "pause does not change allowance");
        end(server, true);
        long valid = scheduler.budgetMetrics().validTicks();
        begin(server, () -> true); clock.addAndGet(1_000); scheduler.finishTickEnvelope(false);
        require(scheduler.budgetMetrics().validTicks() == valid && !(boolean) field("inEnvelope").get(scheduler),
                "exception excludes sample and clears state");

        reset(scheduler, clock);
        try (var outer = scheduler.account()) {
            clock.addAndGet(1_000_000); begin(server, () -> true);
            require(scheduler.metrics().availableNanos() == 20_000_000, "open account excludes work before tick");
            clock.addAndGet(2_000_000); end(server, true);
            require(scheduler.budgetMetrics().lastTick().navigationNanos() == 2_000_000
                    && scheduler.budgetMetrics().lastTick().outsideNavigationNanos() == 1_000_000, "first boundary attribution");
            clock.addAndGet(1_000_000); begin(server, () -> true);
            require(scheduler.metrics().availableNanos() == 20_000_000, "active refill caps effective balance");
            clock.addAndGet(1_000_000); end(server, true);
            require(scheduler.budgetMetrics().lastTick().navigationNanos() == 1_000_000
                    && scheduler.budgetMetrics().lastTick().outsideNavigationNanos() == 1_000_000, "second boundary attribution");
        }
        require(scheduler.metrics().chargedNanos() == 5_000_000 && scheduler.metrics().availableNanos() == 19_000_000,
                "open account close conserves charges");

        reset(scheduler, clock);
        try (var outer = scheduler.account()) {
            clock.addAndGet(2_000_000);
            NavigationScheduler.beginTickEnvelope(server, () -> true);
            clock.addAndGet(1_000_000);
            NavigationScheduler.beginServerTick(server);
            require(scheduler.metrics().availableNanos() == 19_000_000, "START retains current tick open-account work");
            clock.addAndGet(500_000);
            end(server, true);
        }
        require(scheduler.metrics().chargedNanos() == 3_500_000
                && scheduler.metrics().availableNanos() == 18_500_000,
                "open account closes without recharging earlier tick work");

        reset(scheduler, clock);
        NavigationScheduler.beginTickEnvelope(server, () -> true);
        try (var ignored = scheduler.account()) { clock.addAndGet(1_000_000); }
        NavigationScheduler.beginServerTick(server);
        require(scheduler.metrics().availableNanos() == 19_000_000, "START retains current tick closed-account work");
        end(server, true);

        reset(scheduler, clock);
        begin(server, () -> true); clock.addAndGet(2_000_000);
        try (var ignored = scheduler.account()) { clock.addAndGet(1_000_000); }
        NavigationScheduler.endServerTick(server, true); clock.addAndGet(3_000_000);
        try (var ignored = scheduler.account()) { clock.addAndGet(2_000_000); }
        scheduler.finishTickEnvelope(true);
        var sample = scheduler.budgetMetrics().lastTick();
        require(sample.fullNanos() == 8_000_000 && sample.navigationNanos() == 3_000_000
                && sample.measuredTailNanos() == 3_000_000, "tail subtracts navigation once");

        for (Runnable entry : List.<Runnable>of(
                () -> NavigationScheduler.beginTickEnvelope(server, () -> true),
                () -> NavigationScheduler.beginServerTick(server),
                () -> NavigationScheduler.endServerTick(server, true),
                () -> scheduler.finishTickEnvelope(true))) {
            AtomicReference<Throwable> rejection = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { entry.run(); } catch (Throwable error) { rejection.set(error); } });
            worker.start(); worker.join(2000);
            require(!worker.isAlive() && rejection.get() instanceof IllegalStateException
                    && rejection.get().getMessage().contains("server thread"), "off-thread entry rejected");
        }
        NavigationScheduler.beginTickEnvelope(server, () -> true);
        try { NavigationScheduler.beginTickEnvelope(server, () -> true); throw new AssertionError("nested accepted"); }
        catch (IllegalStateException expected) { require(true, "nested rejected"); }
        NavigationScheduler.beginServerTick(server);
        try { NavigationScheduler.beginServerTick(server); throw new AssertionError("START accepted twice"); }
        catch (IllegalStateException expected) { require(true, "duplicate START rejected"); }
        NavigationScheduler.endServerTick(server, true);
        try { NavigationScheduler.endServerTick(server, true); throw new AssertionError("END accepted twice"); }
        catch (IllegalStateException expected) { require(true, "duplicate END rejected"); }
        scheduler.finishTickEnvelope(true);
        require(scheduler.metrics().pendingRequests() == 0, "all controls clean up");
    }

    private static final class Parking implements ResumableSearch<Integer> {
        int steps; Runnable wake;
        public Status step(int budget, long deadline) { return ++steps == 1 ? Status.RUNNING : Status.SUCCEEDED; }
        public Status status() { return steps < 2 ? Status.RUNNING : Status.SUCCEEDED; }
        public Integer result() { return 9; }
        public boolean park(Runnable wake) { this.wake = wake; return steps == 1; }
        public void cancel() {}
    }
}
