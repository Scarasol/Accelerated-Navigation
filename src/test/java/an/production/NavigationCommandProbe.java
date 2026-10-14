package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Drives the registered command with a Forge fake player on a real release server. */
public final class NavigationCommandProbe {
    private final java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final JsonArray checks = new JsonArray(), samples = new JsonArray(), commands = new JsonArray();
    private final List<ChunkPos> held = new ArrayList<>(), remote = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private RecordingPlayer player;
    private Map<UUID, ServerPlayer> online;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private Husk actor, stoppingActor;
    private NavigationRequestPath request, stoppingRequest;
    private Vec3 previous;
    private int tick, phase, phaseTick, walkedTicks, glowingTicks, glowingOutlineTicks;
    private double maxStep;
    private boolean closing, sawOpenDoor;
    private int isolatedMobs;
    private Throwable failure;
    private final BlockPos door = new BlockPos(12, 81, 12);

    public static final class RecordingPlayer extends FakePlayer {
        final List<String> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.fromString("635a668b-c135-4a94-82a7-d05630388628"), "NavigationProbe"));
        }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public void sendSystemMessage(Component message, boolean overlay) { messages.add(message.getString()); }
    }

    private void check(String name, boolean passed, Object value) {
        JsonObject row = new JsonObject();
        row.addProperty("name", name);
        row.addProperty("passed", passed);
        row.add("value", new GsonBuilder().create().toJsonTree(value));
        checks.add(row);
        if (!passed) throw new AssertionError(name + ": " + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> onlinePlayers(PlayerList list) throws Exception {
        for (var field : PlayerList.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())
                    && field.getGenericType().getTypeName().contains("java.util.UUID")) {
                field.setAccessible(true);
                return (Map<UUID, ServerPlayer>) field.get(list);
            }
        }
        throw new IllegalStateException("PlayerList UUID lookup not found");
    }

    private void hold(int x, int z, List<ChunkPos> positions) {
        level.setChunkForced(x, z, true);
        level.getChunk(x, z);
        positions.add(new ChunkPos(x, z));
    }

    private void floor(int fromX, int toX, int fromZ, int toZ) {
        for (int x = fromX; x <= toX; x++) for (int z = fromZ; z <= toZ; z++) {
            level.setBlock(new BlockPos(x, 80, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 81; y <= 84; y++)
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    @SubscribeEvent public void isolateFixture(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob
                && !event.getEntity().getClass().getName()
                        .equals("com.scarasol.acceleratednavigation.NavigationTestMob")) {
            isolatedMobs++;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent public void start(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(6000);
            server.setDifficulty(Difficulty.NORMAL, true);
            for (int x = -1; x <= 4; x++) for (int z = -1; z <= 3; z++) hold(x, z, held);
            floor(0, 48, 0, 32);
            for (int x = 0; x <= 28; x++) for (int y = 81; y <= 83; y++) {
                level.setBlock(new BlockPos(x, y, 11), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, 13), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, 83, 12), Blocks.STONE.defaultBlockState(), 2);
            }
            var bottom = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            level.setBlock(door, bottom, 2);
            level.setBlock(door.above(), bottom.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            for (int x = 62; x <= 67; x++) for (int z = -1; z <= 1; z++) hold(x, z, remote);
            floor(1024, 1060, 0, 8);
            player = new RecordingPlayer(level);
            player.setPos(250.5, 81, 3.5);
            online = onlinePlayers(server.getPlayerList());
            online.put(player.getUUID(), player);
            level.addNewPlayer(player);
            service = SegmentedNavigationService.forServer(server);
            scheduler = NavigationScheduler.forServer(server);
        } catch (Throwable problem) { abort(problem); }
    }

    private int command(String text) throws Exception {
        int result = server.getCommands().getDispatcher().execute(text,
                player.createCommandSourceStack().withPermission(4));
        JsonObject row = new JsonObject();
        row.addProperty("tick", tick);
        row.addProperty("command", text);
        row.addProperty("result", result);
        commands.add(row);
        return result;
    }

    private List<Husk> actors() {
        List<Husk> result = new ArrayList<>();
        for (Entity entity : level.getAllEntities())
            if (entity instanceof Husk husk && !husk.isRemoved()) result.add(husk);
        return result;
    }

    private void beginRoute(int fromX, int goalX, int z, boolean doors) throws Exception {
        player.messages.clear();
        check("command-accepted-" + phase, command("navigation " + fromX + " 81 " + z
                + " " + goalX + " 81 " + z + " true " + doors) == 1, "registered dispatcher");
        List<Husk> spawned = actors();
        check("one-session-actor-" + phase, spawned.size() == 1, spawned.size());
        actor = spawned.get(0);
        request = service.active(actor.getNavigation());
        check("real-movement-handle-" + phase, request != null && request.adopted
                && request.controller != actor.getNavigation(), request == null ? "null" : request.state);
        check("native-movement-accuracy-" + phase, request.intent.accuracy() == 1, request.intent.accuracy());
        check("door-capability-" + phase, actor.getNavigation() instanceof GroundPathNavigation ground
                && ground.getNodeEvaluator().canOpenDoors() == doors, doors);
        phaseTick = tick;
    }

    private void cleaned(String name) {
        check(name, actor.isRemoved() && actors().isEmpty() && request.terminal()
                && actor.getNavigation().getPath() == null && service.metrics().records() == 0
                && service.metrics().active() == 0 && scheduler.metrics().pendingRequests() == 0,
                request.state + " " + service.metrics());
    }

    private void next(int value) { phase = value; phaseTick = tick; }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != TickEvent.Phase.END || closing) return;
        tick++;
        try {
            if (tick > 30) step();
            if (tick % 20 == 0) {
                JsonObject row = new JsonObject();
                row.addProperty("tick", tick);
                row.addProperty("phase", phase);
                if (actor != null) {
                    row.add("position", new GsonBuilder().create().toJsonTree(List.of(actor.getX(), actor.getY(), actor.getZ())));
                    row.addProperty("removed", actor.isRemoved());
                    row.addProperty("glowing", actor.hasEffect(MobEffects.GLOWING));
                }
                if (request != null) row.addProperty("state", request.state.name());
                samples.add(row);
                Files.writeString(output.resolve("command-progress.json"), samples.toString());
            }
            if (tick - phaseTick > 1800 || tick > 6000) throw new AssertionError("timeout phase=" + phase);
        } catch (Throwable problem) { abort(problem); }
    }

    private void step() throws Exception {
        if (phase == 0) {
            beginRoute(3, 42, 3, false);
            check("adult-default-ground-size", !actor.isBaby() && !actor.isNoAi()
                    && Math.abs(actor.getBbWidth() - .6F) < .001F
                    && Math.abs(actor.getBbHeight() - 1.95F) < .001F, actor.getDimensions(actor.getPose()));
            check("infinite-glowing-effect", actor.getEffect(MobEffects.GLOWING) != null
                    && actor.getEffect(MobEffects.GLOWING).isInfiniteDuration(),
                    actor.getEffect(MobEffects.GLOWING) == null ? "missing" : actor.getEffect(MobEffects.GLOWING).getDuration());
            var speed = actor.getEffect(MobEffects.MOVEMENT_SPEED);
            check("infinite-speed-two-effect", speed != null && speed.isInfiniteDuration()
                    && speed.getAmplifier() == 1, speed == null ? "missing"
                    : "duration=" + speed.getDuration() + " amplifier=" + speed.getAmplifier());
            var movement = actor.getAttribute(Attributes.MOVEMENT_SPEED);
            check("speed-two-applies-native-movement-attribute",
                    Math.abs(movement.getValue() / movement.getBaseValue() - 1.4D) < .001D,
                    List.of(movement.getBaseValue(), movement.getValue()));
            check("distance-persistence", actor.isPersistenceRequired() && actor.isInvulnerable(), "persistent actor");
            check("temporary-actor-not-saved", !actor.shouldBeSaved(), "no orphan after unload or restart");
            check("temporary-actor-does-not-change-dimensions", !actor.canChangeDimensions(),
                    "portals cannot recreate an unmanaged vanilla husk");
            check("nearest-player-over-despawn-distance", level.getNearestPlayer(actor, -1) == player
                    && actor.distanceToSqr(player) > 128 * 128, actor.distanceToSqr(player));
            Husk control = new Husk(EntityType.HUSK, level);
            control.setPos(actor.position());
            control.checkDespawn();
            check("stock-husk-distance-control-despawns", control.isRemoved(), "vanilla distance control");
            actor.checkDespawn();
            check("test-husk-distance-survives", !actor.isRemoved(), "same distance, persistence enabled");
            server.setDifficulty(Difficulty.PEACEFUL, true);
            actor.checkDespawn();
            check("peaceful-survives", !actor.isRemoved(), "verification remains available in peaceful");
            previous = actor.position();
            next(1);
        } else if (phase == 1) {
            double distance = actor.position().distanceTo(previous);
            maxStep = Math.max(maxStep, distance);
            if (distance > .001D) walkedTicks++;
            previous = actor.position();
            if (actor.getEffect(MobEffects.GLOWING) == null
                    || !actor.getEffect(MobEffects.GLOWING).isInfiniteDuration())
                throw new AssertionError("glowing effect ended at tick " + tick);
            glowingTicks++;
            var speed = actor.getEffect(MobEffects.MOVEMENT_SPEED);
            if (speed == null || !speed.isInfiniteDuration() || speed.getAmplifier() != 1)
                throw new AssertionError("speed II effect ended at tick " + tick);
            if (!actor.isRemoved()) {
                if (!actor.isCurrentlyGlowing()) throw new AssertionError("glowing outline missing at tick " + tick);
                glowingOutlineTicks++;
            }
            if (request.terminal()) {
                check("actual-natural-arrival", request.state == NavigationRequestPath.State.SUCCEEDED
                        && actor.blockPosition().distManhattan(request.getTarget()) <= request.intent.accuracy()
                        && walkedTicks > 100 && maxStep < 1.0D,
                        request.state + " feet=" + actor.blockPosition() + " target=" + request.getTarget()
                                + " walkedTicks=" + walkedTicks + " maxStep=" + maxStep);
                check("segmented-command-without-preview-or-whole-fallback", service.metrics().segmentCalls() > 0
                        && service.metrics().nativeWholeCalls() == 0 && service.metrics().previewCompleted() == 0,
                        service.metrics());
                check("glowing-retained-for-entire-walk", glowingTicks > 100, glowingTicks);
                check("glowing-outline-retained-for-entire-walk",
                        glowingOutlineTicks >= glowingTicks - 1, glowingOutlineTicks);
                check("speed-two-retained-for-entire-walk", glowingTicks > 100, glowingTicks);
                check("real-success-feedback", player.messages.stream().anyMatch(text -> text.contains("详细寻路成功")),
                        player.messages);
                cleaned("arrival-removes-actor-and-work");
                server.setDifficulty(Difficulty.NORMAL, true);
                next(2);
            }
        } else if (phase == 2) {
            beginRoute(3, 42, 3, false);
            Husk replaced = actor;
            NavigationRequestPath old = request;
            beginRoute(4, 38, 3, false);
            check("replacement-cleans-prior-actor", replaced.isRemoved()
                    && old.state == NavigationRequestPath.State.CANCELLED && actor != replaced,
                    old.state);
            check("cancel-command-accepted", command("navigation cancel") == 1, "registered cancel");
            cleaned("manual-cancel-cleans");
            next(3);
        } else if (phase == 3) {
            beginRoute(3, 42, 3, false);
            online.remove(player.getUUID());
            next(4);
        } else if (phase == 4) {
            cleaned("offline-cleans");
            online.put(player.getUUID(), player);
            beginRoute(3, 42, 3, false);
            player.setServerLevel(server.getLevel(Level.NETHER));
            next(5);
        } else if (phase == 5) {
            cleaned("dimension-change-cleans");
            player.setServerLevel(level);
            beginRoute(3, 42, 3, false);
            actor.discard();
            next(6);
        } else if (phase == 6) {
            cleaned("external-removal-cleans");
            check("removed-actor-failure-feedback", player.messages.stream().anyMatch(text -> text.contains("生物被移除")),
                    player.messages);
            beginRoute(3, 25, 12, true);
            next(7);
        } else if (phase == 7) {
            if (level.getBlockState(door).getValue(DoorBlock.OPEN)) sawOpenDoor = true;
            if (request.terminal()) {
                check("door-opened-during-natural-traversal", sawOpenDoor, "closed oak door opened by the actor");
                check("door-route-arrived", request.state == NavigationRequestPath.State.SUCCEEDED
                        && actor.blockPosition().distManhattan(request.getTarget()) <= request.intent.accuracy(),
                        request.state + " " + actor.blockPosition());
                cleaned("door-route-cleans");
                level.setBlock(door, level.getBlockState(door).setValue(DoorBlock.OPEN, false), 2);
                level.setBlock(door.above(), level.getBlockState(door.above()).setValue(DoorBlock.OPEN, false), 2);
                beginRoute(3, 25, 12, false);
                next(8);
            }
        } else if (phase == 8 && request.terminal()) {
            check("disabled-door-not-false-success", request.state != NavigationRequestPath.State.SUCCEEDED,
                    request.state);
            cleaned("failed-route-cleans");
            check("unloaded-start-rejected", command("navigation 20000 81 20000 20008 81 20000 true") == 0
                    && actors().isEmpty() && service.metrics().records() == 0, "no entity or request created");
            beginRoute(1027, 1050, 3, false);
            for (ChunkPos chunk : remote) level.setChunkForced(chunk.x, chunk.z, false);
            next(9);
        } else if (phase == 9 && actor.isRemoved()) {
            cleaned("stopped-entity-chunk-cleans");
            check("chunk-failure-feedback", player.messages.stream().anyMatch(text -> text.contains("所需区块未加载")),
                    player.messages);
            for (ChunkPos chunk : remote) level.setChunkForced(chunk.x, chunk.z, true);
            next(10);
        } else if (phase == 10 && tick - phaseTick > 40) {
            check("unloaded-actor-does-not-reappear", actors().isEmpty() && level.getEntity(actor.getUUID()) == null,
                    "remote chunks reloaded");
            beginRoute(3, 42, 3, false);
            stoppingActor = actor;
            stoppingRequest = request;
            check("shutdown-with-active-command", !request.terminal() && !actor.isRemoved(), request.state);
            finish();
        }
    }

    private void abort(Throwable problem) { failure = problem; problem.printStackTrace(); finish(); }

    private void writeResult() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("failed", failure != null);
        result.addProperty("failure", failure == null ? "" : failure.toString());
        result.addProperty("phase", phase);
        result.addProperty("tick", tick);
        result.addProperty("isolatedFrozenFixtureMobs", isolatedMobs);
        result.add("checks", checks);
        result.add("commands", commands);
        result.add("samples", samples);
        result.add("messages", new GsonBuilder().create().toJsonTree(player == null ? List.of() : player.messages));
        result.add("metrics", new GsonBuilder().create().toJsonTree(service == null ? null : service.metrics()));
        Files.writeString(output.resolve("command-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private void finish() {
        if (closing) return;
        closing = true;
        try { writeResult(); } catch (Exception error) { error.printStackTrace(); }
        server.halt(false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void stopped(ServerStoppedEvent event) {
        try {
            if (!closing) failure = new AssertionError("server stopped before command verification completed");
            boolean rejected = false;
            try { SegmentedNavigationService.forServer(server); }
            catch (IllegalStateException expected) { rejected = true; }
            if (failure == null) check("server-stop-cleans-active-command",
                    stoppingActor != null && stoppingActor.isRemoved()
                            && stoppingRequest.state == NavigationRequestPath.State.CANCELLED
                            && stoppingActor.getNavigation().getPath() == null,
                    stoppingRequest == null ? "null" : stoppingRequest.state);
            check("shutdown-zero-work-and-late-rejection", service.metrics().records() == 0
                    && service.metrics().active() == 0 && scheduler.metrics().pendingRequests() == 0 && rejected,
                    service.metrics());
            writeResult();
        } catch (Throwable problem) {
            if (failure == null) failure = problem;
            problem.printStackTrace();
            try { writeResult(); } catch (Exception error) { error.printStackTrace(); }
        } finally {
            if (online != null && player != null) online.remove(player.getUUID());
            System.out.println("COMMAND_VERIFICATION_EXIT failed=" + (failure != null));
        }
    }
}
