package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NavigationIntent;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Replays archived native inputs against the same deterministic terrain. */
public final class PartialReplayProbe {
    private final Path output = Path.of(System.getProperty("an.production.output"));
    private final JsonArray results = new JsonArray(), checks = new JsonArray(), identities = new JsonArray();
    private MinecraftServer server;
    private ServerLevel level;
    private int tick;
    private boolean closing;
    private Throwable failure;
    private static Vec3 vector(JsonArray values) {
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }
    private JsonObject readArchive(Path source) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        JsonObject identity = new JsonObject();
        identity.addProperty("path", source.toString());
        identity.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        identities.add(identity);
        return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private JsonObject archived(String run, boolean reachable) throws Exception {
        Path workspace = output.getParent().getParent().getParent();
        Path source = workspace.resolve(".tmp/navigation-budget-handoff-20261014-grace2/" + run + "/budget-path-traces.json");
        for (var element : readArchive(source).getAsJsonArray("searches")) {
            JsonObject row = element.getAsJsonObject(), input = row.getAsJsonObject("input");
            if (row.get("canReach").getAsBoolean() == reachable
                    && input.getAsJsonArray("initial").get(2).getAsInt() == -137
                    && input.getAsJsonArray("targets").size() == 1
                    && input.getAsJsonArray("targets").get(0).getAsJsonArray().get(2).getAsInt() == -155)
                return row;
        }
        throw new AssertionError("fixed replay input missing");
    }
    private JsonObject actualArchive(String property, boolean reachable, JsonArray targets) throws Exception {
        JsonObject archive = readArchive(Path.of(System.getProperty(property)));
        for (var element : archive.getAsJsonArray("searches")) {
            JsonObject row = element.getAsJsonObject(), input = row.getAsJsonObject("input");
            if (input.get("actor").getAsInt() == 281 && input.get("distance").getAsInt() == 28
                    && row.getAsJsonObject("result").get("reachable").getAsBoolean() == reachable
                    && input.getAsJsonArray("targets").size() == 1
                    && (targets == null || targets.equals(input.getAsJsonArray("targets"))))
                return row;
        }
        throw new AssertionError("actual actor281 replay input missing");
    }
    private void check(String name, boolean passed, Object value) {
        JsonObject row = new JsonObject(); row.addProperty("name", name); row.addProperty("passed", passed);
        row.add("value", new GsonBuilder().create().toJsonTree(value)); checks.add(row);
        if (!passed) throw new AssertionError(name + ": " + value);
    }
    private NativeReplayTelemetry.Stats replay(String label, JsonObject input, Vec3 position, Vec3 velocity,
            float range, float multiplier) throws Exception {
        var actor = new PerformanceComparisonProbe.BenchmarkHusk(level);
        actor.setPos(position.x, position.y, position.z);
        actor.setDeltaMovement(velocity); actor.setOnGround(input.get("onGround").getAsBoolean());
        var target = input.getAsJsonArray("targets").get(0).getAsJsonArray();
        Set<BlockPos> targets = Set.of(new BlockPos(target.get(0).getAsInt(), target.get(1).getAsInt(), target.get(2).getAsInt()));
        var intent = AutomaticNavigationBridge.intent(actor.getNavigation(), targets, null, false,
                8, false, input.get("accuracy").getAsInt(), range);
        intent = new NavigationIntent(intent.targets(), intent.entity(), intent.normalizeGround(), intent.regionOffset(),
                intent.upward(), intent.accuracy(), range, multiplier, intent.capabilities());
        var constructor = NavigationRequestPath.class.getDeclaredConstructor(PathNavigation.class, Mob.class,
                NavigationIntent.class, Object.class, long.class, long.class);
        constructor.setAccessible(true);
        var request = constructor.newInstance(actor.getNavigation(), actor, intent, actor.getNavigation(), 0L, level.getGameTime());
        var stats = NativeReplayTelemetry.expect(label);
        Set<BlockPos> avoided = new HashSet<>();
        if (input.has("avoided")) for (var element : input.getAsJsonArray("avoided")) {
            JsonArray pos = element.getAsJsonArray();
            avoided.add(new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()));
        }
        try (var ignored = NavigationScheduler.forServer(server).account()) {
            var result = NativeNavigationSearch.find(request, targets, intent.accuracy(), range, avoided);
            check(label + "-real-native-result", !result.unavailable() && result.path() != null && stats.popped > 0, "actual finder");
        } finally { NativeReplayTelemetry.clear(); actor.discard(); }
        JsonObject row = new GsonBuilder().create().toJsonTree(stats).getAsJsonObject();
        row.add("position", new GsonBuilder().create().toJsonTree(List.of(position.x, position.y, position.z)));
        row.add("velocity", new GsonBuilder().create().toJsonTree(List.of(velocity.x, velocity.y, velocity.z)));
        results.add(row);
        return stats;
    }
    private void actualReplay() throws Exception {
        JsonObject failed = actualArchive("an.production.partialReplaySource", false, null);
        JsonObject input = failed.getAsJsonObject("input"), expected = failed.getAsJsonObject("result");
        JsonObject successful = actualArchive("an.production.partialReplayControl", true, input.getAsJsonArray("targets"));
        JsonObject control = successful.getAsJsonObject("input");
        Vec3 position = vector(input.getAsJsonArray("position")), velocity = vector(input.getAsJsonArray("velocity"));
        float range = input.get("range").getAsFloat(), multiplier = input.get("multiplier").getAsFloat();
        var original = replay("actor281-exact", input, position, velocity, range, multiplier);
        check("actor281-exact-partial-nodes", !original.reachable
                && new GsonBuilder().create().toJsonTree(original.nodes).equals(expected.get("nodes")), original.nodes);
        check("actor281-exact-termination", original.popped == expected.get("popped").getAsInt()
                && original.visitedLimit == expected.get("visitedLimit").getAsInt()
                && original.rangeRejectedNeighbors == expected.get("rangeRejectedNeighbors").getAsInt()
                && original.openRemaining == expected.get("openRemaining").getAsInt(), original);
        check("actor281-range-limited-open-exhaustion", original.rangeRejectedNeighbors > 0
                && original.popped < original.visitedLimit && original.openRemaining == 0, original);
        var still = replay("actor281-zero-velocity", input, position, Vec3.ZERO, range, multiplier);
        check("actor281-velocity-alone-keeps-partial", !still.reachable && original.nodes.equals(still.nodes), still.nodes);
        var largerLimit = replay("actor281-diagnostic-multiplier4", input, position, velocity, range, 4);
        check("actor281-visited-limit-alone-keeps-partial", !largerLimit.reachable
                && original.nodes.equals(largerLimit.nodes), largerLimit);
        var largerRange = replay("actor281-diagnostic-range64", input, position, velocity, 64, multiplier);
        check("actor281-diagnostic-range64-reaches", largerRange.reachable, largerRange);
        var shifted = replay("actor281-control-position-failed-parameters", input,
                vector(control.getAsJsonArray("position")), vector(control.getAsJsonArray("velocity")), range, multiplier);
        check("actor281-control-position-original-range-reaches", shifted.reachable, shifted);
        var natural = replay("actor281-control-exact", control, vector(control.getAsJsonArray("position")),
                vector(control.getAsJsonArray("velocity")), control.get("range").getAsFloat(), control.get("multiplier").getAsFloat());
        check("actor281-control-exact-nodes", natural.reachable
                && new GsonBuilder().create().toJsonTree(natural.nodes).equals(successful.getAsJsonObject("result").get("nodes")), natural.nodes);
    }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob) event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        server = event.getServer(); level = server.overworld();
        try {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            PerformanceComparisonProbe.prepareArena(server, level);
        } catch (Throwable problem) { abort(problem); }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing || ++tick < 180) return;
        try {
            if (System.getProperty("an.production.partialReplaySource") != null) {
                actualReplay(); stop(); return;
            }
            JsonObject historical = archived("dynamic-unchanged", false), control = archived("dynamic-grace2", true);
            JsonObject input = historical.getAsJsonObject("input"), success = control.getAsJsonObject("input");
            Vec3 position = vector(input.getAsJsonArray("position")), velocity = vector(input.getAsJsonArray("velocity"));
            float range = input.get("range").getAsFloat(), multiplier = input.get("multiplier").getAsFloat();
            var original = replay("historical-exact", input, position, velocity, range, multiplier);
            check("reproduces-original-partial-and-nodes", !original.reachable
                    && new GsonBuilder().create().toJsonTree(original.nodes).equals(historical.get("nodes")), original.nodes);
            var still = replay("same-position-zero-velocity", input, position, Vec3.ZERO, range, multiplier);
            check("velocity-alone-keeps-real-search-result", original.nodes.equals(still.nodes) && !still.reachable, still.nodes);
            replay("diagnostic-only-range64", input, position, velocity, 64, multiplier);
            replay("diagnostic-only-multiplier4", input, position, velocity, range, 4);
            replay("control-position-frozen-failed-parameters", input, vector(success.getAsJsonArray("position")),
                    vector(success.getAsJsonArray("velocity")), range, multiplier);
            var naturalControl = replay("historical-success-exact", success, vector(success.getAsJsonArray("position")),
                    vector(success.getAsJsonArray("velocity")), success.get("range").getAsFloat(), success.get("multiplier").getAsFloat());
            check("reproduces-original-reachable-control", naturalControl.reachable
                    && new GsonBuilder().create().toJsonTree(naturalControl.nodes).equals(control.get("nodes")), naturalControl.nodes);
            stop();
        } catch (Throwable problem) { abort(problem); }
    }
    private void abort(Throwable problem) { failure = problem; problem.printStackTrace(); stop(); }
    private void stop() {
        if (closing) return; closing = true;
        try {
            JsonObject result = new JsonObject();
            result.addProperty("failed", failure != null);
            result.addProperty("failure", failure == null ? "" : failure.toString());
            result.add("checks", checks); result.add("replays", results); result.add("inputIdentities", identities);
            Files.writeString(output.resolve("partial-replay.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }
}
