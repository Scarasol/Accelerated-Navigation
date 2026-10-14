package an.budget;

import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("an_budget_prototype")
public final class BudgetPrototype {
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();
    public static final String MODE = System.getProperty("an.budget.mode", "fixed4");
    private static final long TARGET = 45_000_000L, MAXIMUM = 12_000_000L;
    private static final long LOAD = Long.getLong("an.budget.addedLoadNanos", 0L);
    private static final boolean TRACE = Boolean.getBoolean("an.budget.trace");

    public BudgetPrototype() {
        if (!List.of("fixed4", "fixed8", "fixed12", "dynamic12").contains(MODE))
            throw new IllegalArgumentException("Unknown research budget mode: " + MODE);
        if (!List.of("unchanged", "grace1", "grace2").contains(HandoffTiming.MODE))
            throw new IllegalArgumentException("Unknown research handoff mode: " + HandoffTiming.MODE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public record Sample(long sequence, long gameTime, long fullNanos, long nativeReportedNanos,
            long navigationNanos, long outsideNavigationNanos, long grantNanos, long baseEstimateNanos,
            long tailReserveNanos, long measuredTailNanos, long balanceAfterRefillNanos,
            long balanceBeforeDrainNanos, long finalBalanceNanos, int pendingBeforeDrain,
            int pendingAfterDrain, String stopReason, long controllerEntryNanos) {}
    public record PathSpan(String actor, String query, int[] initial, int[] finalTarget,
            long tick, long tickOffsetNanos, double[] position, double[] velocity, boolean onGround,
            List<int[]> targets, float range, int accuracy, float multiplier) {}
    public record PathResult(PathSpan input, boolean nullPath, boolean canReach, int[] target,
            List<int[]> nodes) {}

    public static final class State {
        public final MinecraftServer server;
        public BooleanSupplier haveTime;
        public long started, grant = 4_000_000L, baseEstimate, tailReserve;
        public long chargeAtStart, chargeAtDrainEnd, drainEnded;
        public long balanceAfterRefill, balanceBeforeDrain, entryCost, sequence, previousFull;
        public long previousChargeAtEnd;
        public long handoffDeferrals;
        public int count, cursor, pendingBefore;
        public boolean inTick, sawStart, sawEnd;
        public String stopReason = "NO_END";
        private double ewma, tailEwma;
        private final long[] base = new long[32], tails = new long[32], scratch = new long[32];
        public final List<Sample> samples = new ArrayList<>();
        public final List<PathResult> searches = new ArrayList<>();
        public final List<Map<String, Object>> handoffs = new ArrayList<>();
        private final Map<Mob, int[]> traceInitial = new IdentityHashMap<>();

        State(MinecraftServer server) { this.server = server; }
        long percentile(long[] values) {
            System.arraycopy(values, 0, scratch, 0, count);
            Arrays.sort(scratch, 0, count);
            return count == 0 ? 0 : scratch[Math.max(0, (int)Math.ceil(count * .95) - 1)];
        }
        void choose() {
            baseEstimate = count == 0 ? 0 : Math.max((long)ewma, percentile(base));
            tailReserve = Math.max(2_000_000L, Math.max((long)tailEwma, percentile(tails)));
            if (MODE.equals("dynamic12")) {
                long desired = Math.max(0, Math.min(MAXIMUM, TARGET - baseEstimate));
                if (count < 8) desired = Math.min(desired, 4_000_000L);
                long old = grant;
                grant = Math.min(desired, old + 1_000_000L);
                if (previousFull > TARGET) grant = Math.min(grant, old / 2);
            } else grant = Long.parseLong(MODE.substring(5)) * 1_000_000L;
        }
    }

    public static State state(MinecraftServer server) { return STATES.get(server); }
    private static int[] point(BlockPos p) { return new int[] { p.getX(), p.getY(), p.getZ() }; }
    private static double[] vector(net.minecraft.world.phys.Vec3 p) { return new double[] { p.x, p.y, p.z }; }

    public static PathSpan searchBegin(Mob mob, Set<BlockPos> targets, float range, int accuracy, float multiplier) {
        if (!TRACE || !(mob.level() instanceof ServerLevel level)) return null;
        State state = STATES.get(level.getServer());
        if (state == null) return null;
        int[] initial = state.traceInitial.get(mob);
        if (initial == null) {
            BlockPos p = mob.blockPosition();
            if (p.getX() != -17 || !Set.of(-137, 23, -128, 32).contains(p.getZ())) return null;
            initial = point(p);
            state.traceInitial.put(mob, initial);
        }
        var request = SegmentedNavigationService.forServer(level.getServer()).active(mob.getNavigation());
        return new PathSpan(mob.getUUID().toString(), request == null ? "" : request.queryId.toString(),
                initial, request == null ? null : point(request.getTarget()), level.getGameTime(),
                state.inTick ? System.nanoTime() - state.started : -1,
                vector(mob.position()), vector(mob.getDeltaMovement()), mob.onGround(),
                targets.stream().map(BudgetPrototype::point).toList(), range, accuracy, multiplier);
    }

    public static void searchEnd(Mob mob, PathSpan span, net.minecraft.world.level.pathfinder.Path path) {
        if (span == null) return;
        State state = STATES.get(((ServerLevel)mob.level()).getServer());
        var nodes = new ArrayList<int[]>();
        if (path != null) for (int i = 0; i < path.getNodeCount(); i++) nodes.add(point(path.getNode(i).asBlockPos()));
        state.searches.add(new PathResult(span, path == null, path != null && path.canReach(),
                path == null ? null : point(path.getTarget()), nodes));
    }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        NavigationScheduler.forServer(server);
        List<String> checks = new ArrayList<>();
        for (boolean ending : new boolean[] { false, true }) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    if (ending) NavigationScheduler.endServerTick(server, true);
                    else NavigationScheduler.beginServerTick(server);
                } catch (Throwable rejected) { failure.set(rejected); }
            }, "budget-prototype-thread-check");
            worker.start();
            try { worker.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            if (worker.isAlive() || !(failure.get() instanceof IllegalStateException)
                    || !failure.get().getMessage().contains("server thread"))
                throw new IllegalStateException("Off-thread budget entry was not rejected", failure.get());
            checks.add(ending ? "off-thread END rejected" : "off-thread START rejected");
        }
        try {
            Files.writeString(Path.of(System.getProperty("an.production.output"), "budget-thread-checks.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed", true, "checks", checks)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public static State begin(MinecraftServer server, BooleanSupplier haveTime) {
        long now = System.nanoTime();
        State state = STATES.computeIfAbsent(server, State::new);
        if (state.inTick) throw new IllegalStateException("Nested tick envelope");
        state.started = now;
        state.haveTime = haveTime;
        state.inTick = true;
        state.sawStart = state.sawEnd = false;
        state.stopReason = "NO_END";
        state.drainEnded = 0;
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        state.chargeAtStart = scheduler.metrics().chargedNanos();
        state.choose();
        ((BudgetAccess)(Object)scheduler).budgetBegin(now, state.grant);
        state.balanceAfterRefill = scheduler.metrics().availableNanos();
        state.entryCost = System.nanoTime() - now;
        if (LOAD > 0 && server.getTickCount() > 150) {
            long stop = System.nanoTime() + LOAD;
            while (System.nanoTime() < stop) Thread.onSpinWait();
        }
        return state;
    }

    public static void finish(State state, boolean completedNormally) {
        long end = System.nanoTime();
        NavigationScheduler scheduler = NavigationScheduler.forServer(state.server);
        var metrics = scheduler.metrics();
        long charged = metrics.chargedNanos() - state.chargeAtStart;
        if (completedNormally && state.sawStart && state.sawEnd) {
            long full = end - state.started;
            long tail = Math.max(0, end - state.drainEnded
                    - (metrics.chargedNanos() - state.chargeAtDrainEnd));
            long other = Math.max(0, full - charged);
            long outside = Math.max(0, state.chargeAtStart - state.previousChargeAtEnd);
            long nativeReported = state.server.tickTimes[state.server.getTickCount() % 100];
            state.samples.add(new Sample(++state.sequence, state.server.overworld().getGameTime(),
                    full, nativeReported, charged, outside, state.grant, state.baseEstimate,
                    state.tailReserve, tail, state.balanceAfterRefill, state.balanceBeforeDrain,
                    metrics.availableNanos(), state.pendingBefore, metrics.pendingRequests(),
                    state.stopReason, state.entryCost));
            state.ewma = state.count == 0 ? other : state.ewma * .8 + other * .2;
            state.tailEwma = state.count == 0 ? tail : state.tailEwma * .8 + tail * .2;
            state.base[state.cursor] = other;
            state.tails[state.cursor] = tail;
            state.cursor = (state.cursor + 1) % 32;
            state.count = Math.min(32, state.count + 1);
            state.previousFull = full;
        }
        state.previousChargeAtEnd = metrics.chargedNanos();
        state.inTick = false;
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        State state = STATES.remove(event.getServer());
        if (state == null) return;
        try {
            var result = Map.of("schema", "FORGE_DYNAMIC_BUDGET_HANDOFF_PROTOTYPE_V3",
                    "mode", MODE, "handoff", HandoffTiming.MODE, "handoffDeferrals", state.handoffDeferrals,
                    "addedLoadNanos", LOAD, "samples", state.samples);
            Path output = Path.of(System.getProperty("an.production.output"), "budget-ticks.json");
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result));
            if (TRACE) Files.writeString(output.resolveSibling("budget-path-traces.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(
                            Map.of("mode", MODE, "searches", state.searches)));
            if (TRACE) Files.writeString(output.resolveSibling("budget-handoff-traces.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(
                            Map.of("schema", "BUDGET_HANDOFF_BRANCH_TRACE_V1", "mode", MODE,
                                    "events", state.handoffs)));
            System.out.println("BUDGET_PROTOTYPE_FINISH mode=" + MODE + " samples=" + state.samples.size());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
