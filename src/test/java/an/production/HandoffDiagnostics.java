package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NativeQueryProof;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

/** Bounded tracing of selected real actors, enabled only for isolated diagnosis. */
public final class HandoffDiagnostics {
    private static final boolean ENABLED = Boolean.getBoolean("an.production.handoffTrace");
    private static final Set<Integer> WATCHED = Arrays.stream(
            System.getProperty("an.production.handoffActors", "25,29,281,285").split(","))
            .map(Integer::parseInt).collect(Collectors.toUnmodifiableSet());
    private record Actor(int index, int distance, int repeat, NavigationRequestPath request) {}
    private static final Map<Mob, Actor> ACTORS = new IdentityHashMap<>();
    private static final JsonArray EVENTS = new JsonArray(), SEARCHES = new JsonArray();
    private static JsonObject nativeInput;
    private static NativeReplayTelemetry.Stats stats;
    private static boolean truncated;
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static List<Double> vector(Vec3 vector) { return List.of(vector.x, vector.y, vector.z); }
    private static List<Integer> point(BlockPos pos) { return List.of(pos.getX(), pos.getY(), pos.getZ()); }
    public static void register(Mob mob, int index, int distance, int repeat, NavigationRequestPath request) {
        if (!ENABLED || !WATCHED.contains(index)) return;
        ACTORS.put(mob, new Actor(index, distance, repeat, request));
    }
    public static void forget(Mob mob) { ACTORS.remove(mob); }
    public static void event(SegmentedNavigationService service, Object work, String event) {
        if (!ENABLED) return;
        try {
            NavigationRequestPath request = (NavigationRequestPath) field(work, "request");
            Actor actor = ACTORS.get(request.mob);
            if (actor == null) return;
            if (EVENTS.size() >= 10_000) { truncated = true; return; }
            var gson = new GsonBuilder().create();
            JsonObject row = new JsonObject();
            row.addProperty("actor", actor.index()); row.addProperty("distance", actor.distance());
            row.addProperty("repeat", actor.repeat()); row.addProperty("event", event);
            row.addProperty("tick", request.mob.level().getGameTime());
            row.addProperty("query", request.queryId.toString()); row.addProperty("state", request.state.name());
            row.add("position", gson.toJsonTree(vector(request.mob.position())));
            row.add("velocity", gson.toJsonTree(vector(request.mob.getDeltaMovement())));
            row.add("block", gson.toJsonTree(point(request.mob.blockPosition())));
            row.addProperty("pathDone", request.isDone()); row.addProperty("nodes", request.getNodeCount());
            row.addProperty("index", request.getNextNodeIndex()); row.addProperty("canReach", request.canReach());
            row.addProperty("handoffDeferrals", service.handoffDeferrals());
            for (String name : List.of("cursor", "stageEnd", "stable", "doneSince", "last", "segmentReachable",
                    "nativeStopped", "attempt", "recoveryCount", "segmentPublication", "queued", "preparing"))
                row.add(name, gson.toJsonTree(field(work, name)));
            GroundSegmentWindow window = (GroundSegmentWindow) field(work, "window");
            NativeQueryProof proof = (NativeQueryProof) field(work, "nativeProof");
            row.addProperty("windowCurrent", window != null && window.current());
            row.addProperty("proofCurrent", proof != null && proof.current());
            row.addProperty("contains", window != null && window.contains(request.mob.blockPosition()));
            row.addProperty("entryPresent", field(work, "segmentEntry") != null);
            BlockPos target = (BlockPos) field(work, "segmentTarget");
            row.add("segmentTarget", gson.toJsonTree(target == null ? null : point(target)));
            @SuppressWarnings("unchecked")
            Set<BlockPos> failed = (Set<BlockPos>) field(work, "failedTargets");
            row.add("failedTargets", gson.toJsonTree(failed.stream().map(HandoffDiagnostics::point).toList()));
            EVENTS.add(row);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static void advanced(SegmentedNavigationService service, PathNavigation navigation,
            net.minecraft.world.level.pathfinder.Path path, boolean held, boolean following) {
        if (!ENABLED || !(path instanceof NavigationRequestPath request)
                || path.getNextNodeIndex() != path.getNodeCount() - 1) return;
        try {
            Object work = ((Map<?, ?>) field(service, "records")).get(request);
            if (work != null) event(service, work,
                    (following ? "follow-" : "fall-") + (held ? "last-node-held" : "last-node-advance"));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static void observed(SegmentedNavigationService service) {
        if (!ENABLED) return;
        try {
            Map<?, ?> records = (Map<?, ?>) field(service, "records");
            for (Actor actor : ACTORS.values()) {
                NavigationRequestPath request = actor.request();
                if (request == null || request.getNodeCount() == 0
                        || request.getNextNodeIndex() < request.getNodeCount() - 2) continue;
                Object work = records.get(request);
                if (work != null) event(service, work, "observe-return");
            }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static void nativeBegin(Mob mob, Set<BlockPos> targets, float range, int accuracy, float multiplier) {
        Actor actor = ACTORS.get(mob);
        if (!ENABLED || actor == null) return;
        var gson = new GsonBuilder().create();
        nativeInput = new JsonObject();
        nativeInput.addProperty("actor", actor.index()); nativeInput.addProperty("distance", actor.distance());
        nativeInput.addProperty("repeat", actor.repeat()); nativeInput.addProperty("tick", mob.level().getGameTime());
        nativeInput.addProperty("query", actor.request().queryId.toString());
        nativeInput.add("position", gson.toJsonTree(vector(mob.position())));
        nativeInput.add("velocity", gson.toJsonTree(vector(mob.getDeltaMovement())));
        nativeInput.addProperty("onGround", mob.onGround());
        nativeInput.add("targets", gson.toJsonTree(targets.stream().map(HandoffDiagnostics::point).toList()));
        nativeInput.addProperty("range", range); nativeInput.addProperty("accuracy", accuracy);
        nativeInput.addProperty("multiplier", multiplier);
        var scope = NativeNavigationSearch.scope();
        if (scope != null) nativeInput.add("avoided", gson.toJsonTree(scope.avoided.stream().map(HandoffDiagnostics::point).toList()));
        stats = NativeReplayTelemetry.expect("actual-" + actor.index());
    }
    public static void nativeEnd(Mob mob) {
        if (!ENABLED || !ACTORS.containsKey(mob) || nativeInput == null) return;
        JsonObject row = new JsonObject(); row.add("input", nativeInput);
        row.add("result", new GsonBuilder().create().toJsonTree(stats));
        if (SEARCHES.size() < 10_000) SEARCHES.add(row); else truncated = true;
        nativeInput = null; stats = null; NativeReplayTelemetry.clear();
    }
    public static void write() throws Exception {
        if (!ENABLED) return;
        JsonObject result = new JsonObject(); result.addProperty("truncated", truncated);
        result.add("events", EVENTS); result.add("searches", SEARCHES);
        Files.writeString(Path.of(System.getProperty("an.production.output"), "handoff-diagnostics.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
