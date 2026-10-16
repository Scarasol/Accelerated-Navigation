package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Common read-only full-tick instrument, also usable with the archived 4ms jar. */
public final class PerformanceTickTelemetry {
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();
    private static final int MAX_SAMPLES = 24_000;
    private static boolean registered;
    private static final class State {
        final JsonArray samples = new JsonArray();
        long previousChargeAtEnd;
        Span current;
        boolean truncated;
    }
    public static final class Span {
        final NavigationScheduler scheduler;
        final long start, charge, outside;
        boolean startSeen, endSeen;
        long grant = 4_000_000, reserve = -1;
        long nativeTime;
        Span(NavigationScheduler scheduler, long start, long charge, long outside) {
            this.scheduler = scheduler;
            this.start = start; this.charge = charge; this.outside = outside;
        }
    }
    public static void register() {
        if (!registered) { registered = true; MinecraftForge.EVENT_BUS.register(new PerformanceTickTelemetry()); }
    }
    public static Span begin(MinecraftServer server) {
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        long start = System.nanoTime();
        NavigationScheduler scheduler = NavigationScheduler.forServer(server);
        long charged = scheduler.metrics().chargedNanos();
        if (state.current != null) throw new IllegalStateException("nested telemetry envelope");
        return state.current = new Span(scheduler, start, charged, Math.max(0L, charged - state.previousChargeAtEnd));
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void tick(TickEvent.ServerTickEvent event) {
        State state = STATES.get(event.getServer());
        if (state == null || state.current == null) return;
        Span span = state.current;
        if (event.phase == TickEvent.Phase.START) {
            span.startSeen = true;
            try {
                span.grant = NavigationScheduler.class.getField("TICK_BUDGET_NANOS").getLong(null);
                span.reserve = NavigationScheduler.class.getField("TAIL_RESERVE_NANOS").getLong(null);
            } catch (NoSuchFieldException archivedBaseline) {
                // The original 4ms scheduler has no controller fields.
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        } else {
            span.endSeen = true;
            span.nativeTime = event.getServer().tickTimes[event.getServer().getTickCount() % 100];
        }
    }
    public static void finish(MinecraftServer server, Span span, boolean completed) {
        long end = System.nanoTime();
        State state = STATES.get(server);
        long charged = span.scheduler.metrics().chargedNanos();
        state.previousChargeAtEnd = charged;
        state.current = null;
        if (!completed || !span.startSeen || !span.endSeen) return;
        if (state.samples.size() == MAX_SAMPLES) { state.truncated = true; return; }
        JsonObject sample = new JsonObject();
        sample.addProperty("tick", server.getTickCount());
        sample.addProperty("gameTime", server.overworld().getGameTime());
        sample.addProperty("fullNanos", end - span.start);
        sample.addProperty("startNanos", span.start);
        sample.addProperty("endNanos", end);
        sample.addProperty("navigationNanos", charged - span.charge);
        sample.addProperty("outsideNavigationNanos", span.outside);
        sample.addProperty("nativeReportedNanos", span.nativeTime);
        sample.addProperty("grantNanos", span.grant);
        sample.addProperty("tailReserveNanos", span.reserve);
        sample.addProperty("finalBalanceNanos", span.scheduler.metrics().availableNanos());
        sample.addProperty("pending", span.scheduler.metrics().pendingRequests());
        try {
            Field queuesField = NavigationScheduler.class.getDeclaredField("queues");
            queuesField.setAccessible(true);
            int runnable = 0;
            for (Object queue : ((Map<?, ?>) queuesField.get(span.scheduler)).values()) {
                Field size = queue.getClass().getDeclaredField("size");
                size.setAccessible(true); runnable += size.getInt(queue);
            }
            sample.addProperty("runnable", runnable);
            sample.addProperty("parked", Math.max(0, span.scheduler.metrics().pendingRequests() - runnable));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        state.samples.add(sample);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        State state = STATES.remove(event.getServer());
        if (state == null) return;
        try {
            JsonObject output = new JsonObject();
            output.addProperty("schema", "READ_ONLY_FULL_TICK_V1");
            output.addProperty("truncated", state.truncated);
            output.add("samples", state.samples);
            Files.writeString(Path.of(System.getProperty("an.production.output"), "full-ticks.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(output));
            PerformanceScheduleTelemetry.write();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
