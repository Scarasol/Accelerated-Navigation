package an.production;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NativeQueryProof;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Read-only movement instrumentation in a copied world; no navigation repairs. */
public final class SpeedHandoffProbe {
    private record Trial(String site, String mode, int amplifier, int repeat,
            BlockPos start, BlockPos shortGoal, BlockPos continuousGoal) {}

    private final java.nio.file.Path output =
            java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final Gson gson = new Gson();
    private final JsonArray results = new JsonArray();
    private final List<Trial> trials = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private BufferedWriter trace;
    private Husk actor;
    private NavigationRequestPath request;
    private Path nativePath;
    private JsonObject summary;
    private int tick, trialIndex = -1, spawnedTick, movementTick = -1, doneTick = -1;
    private int isolated, reversedTicks;
    private boolean closing, anyEntry, groundedEntry;
    private double maxHorizontalStep;
    private Vec3 previous;
    private String lastNodes = "";
    private Throwable failure;
    private Field operationField;

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @SuppressWarnings("unchecked")
    private void bypass(boolean enabled) throws Exception {
        Field field = NavigationPolicy.class.getDeclaredField("bypassEntityIds");
        field.setAccessible(true);
        field.set(null, enabled ? Set.of(new ResourceLocation("minecraft", "husk")) : Set.of());
    }

    private Trial trial() { return trials.get(trialIndex); }

    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob
                && !event.getEntity().getClass().getName()
                        .equals("com.scarasol.acceleratednavigation.NavigationTestMob")) {
            isolated++;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent public void start(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            trace = Files.newBufferedWriter(output.resolve("trajectory.jsonl"));
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(6000);
            server.setDifficulty(Difficulty.NORMAL, true);
            for (int x = -4; x <= 0; x++) for (int z = -9; z <= -5; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
            service = SegmentedNavigationService.forServer(server);
            for (Field field : MoveControl.class.getDeclaredFields()) {
                if (field.getType().isEnum()) {
                    field.setAccessible(true);
                    operationField = field;
                }
            }
            if (operationField == null) throw new IllegalStateException("Move operation not found");
            for (int repeat = 0; repeat < 2; repeat++) {
                for (String site : List.of("north-stair", "west-stair")) {
                    BlockPos from = site.equals("north-stair")
                            ? new BlockPos(-5, 74, -103) : new BlockPos(-35, 85, -119);
                    BlockPos middle = site.equals("north-stair")
                            ? new BlockPos(-5, 76, -101) : new BlockPos(-35, 87, -117);
                    BlockPos end = site.equals("north-stair")
                            ? new BlockPos(-5, 78, -99) : new BlockPos(-35, 92, -118);
                    for (String mode : List.of("native-short", "native-continuous", "segmented")) {
                        for (int amp : repeat == 0 ? List.of(1, 3) : List.of(3, 1))
                            trials.add(new Trial(site, mode, amp, repeat, from, middle, end));
                    }
                }
            }
        } catch (Throwable problem) { abort(problem); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void early(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || closing) return;
        try {
            if (event.phase == TickEvent.Phase.START) {
                tick++;
                sample("SERVER_PRE");
            } else sample("PHYSICS_POST");
        } catch (Throwable problem) { abort(problem); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void entity(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() == actor && !closing) {
            try { sample("ENTITY_PRE"); }
            catch (Throwable problem) { abort(problem); }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        try {
            sample("SCHEDULER_POST");
            if (tick < 40) return;
            if (actor == null) {
                if (++trialIndex == trials.size()) { stop(); return; }
                spawn();
                return;
            }
            if (movementTick < 0) {
                if (tick - spawnedTick >= 20) begin();
                return;
            }
            Vec3 position = actor.position();
            Vec3 delta = position.subtract(previous);
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            maxHorizontalStep = Math.max(maxHorizontalStep, horizontal);
            if (trial().site.equals("north-stair") && delta.z < -.02) reversedTicks++;
            previous = position;
            boolean done = request != null ? request.terminal()
                    : actor.getNavigation().isDone();
            if (done && doneTick < 0) {
                doneTick = tick;
                summary.addProperty("firstDoneTick", tick);
                summary.add("firstDonePosition", vec(position));
            }
            if (doneTick >= 0 && tick - doneTick >= 30 || tick - movementTick >= 600)
                complete();
            if (tick % 20 == 0) {
                trace.flush();
                Files.writeString(output.resolve("progress.json"),
                        gson.toJson(Map.of("tick", tick, "trial", trialIndex,
                                "completed", results.size(), "mode", trial().mode,
                                "amplifier", trial().amplifier, "site", trial().site)));
            }
        } catch (Throwable problem) { abort(problem); }
    }

    private static JsonArray vec(Vec3 value) {
        JsonArray result = new JsonArray();
        result.add(value.x); result.add(value.y); result.add(value.z);
        return result;
    }

    private static JsonArray pos(BlockPos value) {
        JsonArray result = new JsonArray();
        result.add(value.getX()); result.add(value.getY()); result.add(value.getZ());
        return result;
    }

    private void spawn() throws Exception {
        bypass(!trial().mode.equals("segmented"));
        Class<?> type = Class.forName("com.scarasol.acceleratednavigation.NavigationTestMob");
        var constructor = type.getDeclaredConstructor(ServerLevel.class, boolean.class);
        constructor.setAccessible(true);
        actor = (Husk) constructor.newInstance(level, false);
        actor.removeEffect(MobEffects.MOVEMENT_SPEED);
        actor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                MobEffectInstance.INFINITE_DURATION, trial().amplifier, false, false));
        actor.setPos(trial().start.getX() + .5, trial().start.getY() + .01,
                trial().start.getZ() + .5);
        actor.setYRot(0);
        actor.setYHeadRot(0);
        if (!level.addFreshEntity(actor)) throw new IllegalStateException("Actor not added");
        spawnedTick = tick;
        movementTick = doneTick = -1;
        request = null; nativePath = null;
        anyEntry = groundedEntry = false;
        reversedTicks = 0; maxHorizontalStep = 0;
        lastNodes = "";
        summary = new JsonObject();
        summary.addProperty("trial", trialIndex);
        summary.addProperty("site", trial().site);
        summary.addProperty("mode", trial().mode);
        summary.addProperty("amplifier", trial().amplifier);
        summary.addProperty("repeat", trial().repeat);
    }

    private void begin() throws Exception {
        if (!actor.onGround()) throw new IllegalStateException("Unsettled trial start");
        movementTick = tick;
        previous = actor.position();
        BlockPos target = trial().mode.equals("native-short") ? trial().shortGoal
                : trial().continuousGoal;
        int accuracy = trial().mode.equals("native-short") ? 0 : 1;
        summary.add("start", vec(actor.position()));
        summary.add("goal", pos(target));
        summary.addProperty("accuracy", accuracy);
        summary.addProperty("movementSpeed", actor.getAttributeValue(Attributes.MOVEMENT_SPEED));
        summary.addProperty("nativeBefore", service.metrics().nativeCalls());
        summary.addProperty("recoverBefore", service.metrics().recoveries());
        if (trial().mode.equals("segmented")) {
            if (!actor.getNavigation().moveTo(target.getX() + .5, target.getY(),
                    target.getZ() + .5, 1)) throw new IllegalStateException("Segmented move rejected");
            request = service.active(actor.getNavigation());
            if (request == null) throw new IllegalStateException("Missing segmented request");
            summary.addProperty("queryId", request.queryId.toString());
        } else {
            var intent = AutomaticNavigationBridge.intent(actor.getNavigation(), Set.of(target),
                    null, false, 8, false, accuracy, 16);
            nativePath = NativeNavigationSearch.legacy(actor.getNavigation(), intent);
            if (nativePath == null) throw new IllegalStateException("Native path missing");
            summary.addProperty("nativeCanReach", nativePath.canReach());
            if (!actor.getNavigation().moveTo(nativePath, 1))
                throw new IllegalStateException("Native move rejected");
        }
        sample("MOVE_BEGIN");
        System.out.println("SPEED_TRIAL " + summary);
    }

    @SuppressWarnings("unchecked")
    private void sample(String kind) throws Exception {
        if (actor == null || actor.isRemoved()) return;
        JsonObject row = new JsonObject();
        row.addProperty("tick", tick);
        row.addProperty("trial", trialIndex);
        row.addProperty("kind", kind);
        row.add("position", vec(actor.position()));
        row.add("deltaMovement", vec(actor.getDeltaMovement()));
        row.addProperty("onGround", actor.onGround());
        row.addProperty("yaw", actor.getYRot());
        row.addProperty("speed", actor.getSpeed());
        MoveControl move = actor.getMoveControl();
        row.addProperty("operation", operationField.get(move).toString());
        row.add("wanted", vec(new Vec3(move.getWantedX(), move.getWantedY(), move.getWantedZ())));
        row.addProperty("moveModifier", move.getSpeedModifier());
        row.addProperty("insideShortGoal", actor.blockPosition().equals(trial().shortGoal));
        if (movementTick >= 0 && actor.blockPosition().equals(trial().shortGoal)) {
            anyEntry = true;
            if (actor.onGround()) groundedEntry = true;
        }
        Path path = actor.getNavigation().getPath();
        if (path != null) {
            row.addProperty("nodeIndex", path.getNextNodeIndex());
            row.addProperty("nodeCount", path.getNodeCount());
            row.addProperty("pathDone", path.isDone());
            row.addProperty("canReach", path.canReach());
            JsonArray nodes = new JsonArray();
            for (int index = 0; index < path.getNodeCount(); index++)
                nodes.add(pos(path.getNode(index).asBlockPos()));
            String key = nodes.toString();
            if (!key.equals(lastNodes)) {
                row.add("nodes", nodes);
                lastNodes = key;
            }
            if (path.getEndNode() != null) row.add("endNode", pos(path.getEndNode().asBlockPos()));
        }
        if (request != null) {
            row.addProperty("state", request.state.name());
            Map<NavigationRequestPath, ?> records =
                    (Map<NavigationRequestPath, ?>) field(service, "records");
            Object work = records.get(request);
            if (work != null) {
                for (String name : List.of("cursor", "stageEnd", "attempt", "stride", "searches",
                        "stable", "previousY", "doneSince", "queued", "preparing", "nativeStopped",
                        "segmentReachable", "last")) {
                    Object value = field(work, name);
                    if (value instanceof Double number && !Double.isFinite(number))
                        row.addProperty(name, number.toString());
                    else row.add(name, gson.toJsonTree(value));
                }
                GroundSegmentWindow window = (GroundSegmentWindow) field(work, "window");
                if (window != null) {
                    row.addProperty("windowCurrent", window.current());
                    row.addProperty("insideDestination", window.contains(actor.blockPosition()));
                    row.addProperty("component", window.destination.componentId());
                    row.add("destinationAnchor", pos(window.destination.anchor()));
                }
                NativeQueryProof proof = (NativeQueryProof) field(work, "nativeProof");
                row.addProperty("nativeInputCurrent", proof != null && proof.current());
            }
        }
        trace.write(gson.toJson(row));
        trace.newLine();
    }

    private void complete() throws Exception {
        summary.addProperty("ticks", tick - movementTick);
        summary.addProperty("timedOut", doneTick < 0);
        summary.addProperty("actualDone", actor.getNavigation().isDone());
        summary.addProperty("state", request == null ? "NATIVE_DONE" : request.state.name());
        summary.add("settledPosition", vec(actor.position()));
        summary.addProperty("settledOnGround", actor.onGround());
        summary.addProperty("actualShortGoalEntry", anyEntry);
        summary.addProperty("groundedShortGoalEntry", groundedEntry);
        summary.addProperty("reverseZTicks", reversedTicks);
        summary.addProperty("maxHorizontalStep", maxHorizontalStep);
        summary.addProperty("recoverAfter", service.metrics().recoveries());
        summary.addProperty("nativeAfter", service.metrics().nativeCalls());
        results.add(summary);
        System.out.println("SPEED_RESULT " + summary);
        actor.getNavigation().stop();
        actor.discard();
        actor = null;
        bypass(false);
        trace.flush();
        writeResult();
    }

    private void writeResult() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("failed", failure != null);
        result.addProperty("failure", failure == null ? "" : failure.toString());
        result.addProperty("tick", tick);
        result.addProperty("isolatedOtherMobs", isolated);
        result.add("trials", results);
        if (service != null) result.add("navigation", gson.toJsonTree(service.metrics()));
        Files.writeString(output.resolve("diagnosis.json"), gson.toJson(result));
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
            bypass(false);
            writeResult();
            if (trace != null) trace.close();
        } catch (Throwable problem) { problem.printStackTrace(); }
        if (server != null) server.halt(false);
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("tick", tick);
            row.addProperty("failed", failure != null);
            row.add("trials", results);
            Files.writeString(output.resolve("shutdown.json"), gson.toJson(row));
        } catch (Throwable problem) { problem.printStackTrace(); }
    }
}
