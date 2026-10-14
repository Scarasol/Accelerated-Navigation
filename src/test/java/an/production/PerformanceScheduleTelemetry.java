package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/** Bounded test-only dispatch and wake timings, without changing scheduler decisions. */
public final class PerformanceScheduleTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("an.production.performanceComparison");
    private static final int LIMIT = 100_000;
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Map<Long, Long> WAKES = new HashMap<>();
    private static final JsonArray SAMPLES = new JsonArray();
    private static boolean truncated;

    private static Object field(Object target, String name) {
        try {
            String key = target.getClass().getName() + "/" + name;
            Field field = FIELDS.get(key);
            if (field == null) {
                field = target.getClass().getDeclaredField(name);
                field.setAccessible(true); FIELDS.put(key, field);
            }
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static void wake(Object request) {
        if (!ENABLED || !(boolean) field(request, "queued") || (boolean) field(request, "parked")) return;
        if (WAKES.size() >= 1024) { truncated = true; return; }
        WAKES.putIfAbsent((long) field(request, "sequence"), System.nanoTime());
    }
    public static boolean parked(Object request) { return ENABLED && (boolean) field(request, "parked"); }
    public static void cancel(Object request) {
        if (ENABLED) WAKES.remove((long) field(request, "sequence"));
    }
    public static void dispatch(MinecraftServer server, Object request) {
        if (!ENABLED || request == null) return;
        long now = System.nanoTime();
        long sequence = (long) field(request, "sequence");
        Long wake = WAKES.remove(sequence);
        if (SAMPLES.size() == LIMIT) { truncated = true; return; }
        JsonObject sample = new JsonObject();
        Object key = field(request, "key");
        sample.addProperty("tick", server.getTickCount());
        sample.addProperty("sequence", sequence);
        sample.addProperty("owner", String.valueOf(field(key, "owner")));
        sample.addProperty("dimension", String.valueOf(field(key, "dimension")));
        sample.addProperty("submitToDispatchNanos", Math.max(0L, now - (long) field(request, "enqueuedNanos")));
        if (wake != null) sample.addProperty("wakeToDispatchNanos", Math.max(0L, now - wake));
        SAMPLES.add(sample);
    }
    public static void write() {
        if (!ENABLED) return;
        try {
            JsonObject output = new JsonObject();
            output.addProperty("schema", "READ_ONLY_DISPATCH_V1");
            output.addProperty("truncated", truncated);
            output.addProperty("unconsumedWakes", WAKES.size());
            output.add("samples", SAMPLES);
            Files.writeString(Path.of(System.getProperty("an.production.output"), "dispatch-timings.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(output));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
