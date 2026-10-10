package com.scarasol.acceleratednavigation;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** A player-facing view of the existing macro query; the particles are corridor sketches. */
final class NavigationCommand {
    private static final int MAX_POINTS = 4096;
    private static final int MAX_PARTICLES_PER_REFRESH = 256;
    private static final int REFRESH_TICKS = 20;
    private static final long DISPLAY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final double VISIBLE_DISTANCE_SQUARED = 30.0D * 30.0D;

    // This map belongs to the server thread. A player has at most one query or display.
    private final Map<UUID, Session> sessions = new HashMap<>();

    void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("navigation")
                .requires(source -> source.hasPermission(2) && source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("graph")
                        .then(Commands.argument("distance", IntegerArgumentType.integer(
                                        1, TopologyService.MAX_GRAPH_CAPTURE_DISTANCE))
                                .executes(this::executeGraph)))
                .then(Commands.argument("start", BlockPosArgument.blockPos())
                        .then(Commands.argument("goal", BlockPosArgument.blockPos())
                                .then(Commands.argument("detailed", BoolArgumentType.bool())
                                        .executes(context -> execute(context, false))
                                        .then(Commands.argument("canOpenDoors", BoolArgumentType.bool())
                                                .executes(context -> execute(context,
                                                        BoolArgumentType.getBool(context, "canOpenDoors"))))))));
    }

    private int executeGraph(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        ServerLevel level = source.getLevel();
        MinecraftServer server = level.getServer();
        int distance = IntegerArgumentType.getInteger(context, "distance");
        try {
            var capture = TopologyService.forServer(server)
                    .captureBaseGraphs(level, player.position(), distance);
            player.sendSystemMessage(Component.literal("开始采集半径 " + distance
                    + " 格内的默认地面基础图；完成后会报告文件路径。"));
            UUID owner = player.getUUID();
            ResourceKey<Level> dimension = level.dimension();
            capture.whenComplete((result, failure) -> {
                Runnable publish = () -> {
                    ServerPlayer current = server.getPlayerList().getPlayer(owner);
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause() : failure;
                        AcceleratedNavigation.LOGGER.warn("Navigation base graph export failed owner={} dimension={}",
                                owner, dimension.location(), cause);
                        if (current != null && current.level().dimension() == dimension) {
                            current.sendSystemMessage(Component.literal("基础图导出失败：" + cause.getMessage()));
                        }
                        return;
                    }
                    AcceleratedNavigation.LOGGER.info(
                            "Navigation base graph export owner={} dimension={} path={} ready={} unloaded={} failed={} stable={} complete={}",
                            owner, dimension.location(), result.path(), result.ready(), result.unloaded(),
                            result.failed(), result.stable(), result.complete());
                    if (current != null && current.level().dimension() == dimension) {
                        current.sendSystemMessage(Component.literal("基础图已保存：" + result.path()
                                + "；成功 " + result.ready() + "，未加载 " + result.unloaded()
                                + "，失败 " + result.failed() + "；"
                                + (result.complete() ? "指定范围采集完整；范围外仍未知。" : "指定范围采集不完整，不能直接作为完整图对照。")
                                + (result.stable() ? "" : " 采集期间地形发生变化。")));
                    }
                };
                if (server.isSameThread()) publish.run();
                else {
                    try {
                        server.execute(publish);
                    } catch (RejectedExecutionException stopped) {
                        AcceleratedNavigation.LOGGER.info("Navigation graph export finished after server stopped");
                    }
                }
            });
            return 1;
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("基础图导出未启动：" + failure.getMessage()));
            AcceleratedNavigation.LOGGER.warn("Navigation base graph export could not start", failure);
            return 0;
        }
    }

    private int execute(CommandContext<CommandSourceStack> context, boolean canOpenDoors) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (BoolArgumentType.getBool(context, "detailed")) {
            source.sendFailure(Component.literal("详细寻路尚未接入；请使用 false 查询宏观走廊。"));
            AcceleratedNavigation.LOGGER.info("Navigation detailed query is not implemented for {}", player.getUUID());
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos start = BlockPosArgument.getBlockPos(context, "start");
        BlockPos goal = BlockPosArgument.getBlockPos(context, "goal");
        if (!level.isInWorldBounds(start) || !level.isInWorldBounds(goal)) {
            source.sendFailure(Component.literal("起点或终点超出世界边界。"));
            return 0;
        }

        UUID owner = player.getUUID();
        Session old = sessions.remove(owner);
        if (old != null && old.request != null) {
            old.request.cancel();
            report(level.getServer(), old, "CANCELLED_REPLACED", null,
                    System.nanoTime() - old.startedNanos);
        }
        Session session = new Session(owner, level.dimension(), start, goal, canOpenDoors, System.nanoTime());
        sessions.put(owner, session);
        try {
            TopologyService.MacroRequest request = TopologyService.forServer(level.getServer())
                    .requestMacroQuery(level, owner, start, goal,
                            BaseClusterTopology.Channel.GROUND,
                            new BaseClusterTopology.TraversalProfile(0.6F, 1.95F, 1, 0, 3,
                                    false, canOpenDoors),
                            NavigationScheduler.Priority.ACTIVE);
            session.request = request;
            player.sendSystemMessage(Component.literal("宏观寻路已提交，完成后会报告结果与耗时。"));
            request.future().whenComplete((corridor, failure) -> {
                long completedNanos = System.nanoTime();
                MinecraftServer server = level.getServer();
                if (server.isSameThread()) complete(server, session, corridor, failure, completedNanos);
                else server.execute(() -> complete(server, session, corridor, failure, completedNanos));
            });
            return 1;
        } catch (RuntimeException failure) {
            sessions.remove(owner, session);
            if (session.request != null) session.request.cancel();
            report(level.getServer(), session, "ERROR", failure,
                    System.nanoTime() - session.startedNanos);
            return 0;
        }
    }

    private void complete(MinecraftServer server, Session session, MacroSearch.Corridor corridor,
                          Throwable failure, long completedNanos) {
        if (sessions.get(session.owner) != session) return;
        TopologyService.MacroRequest terminalRequest = session.request;
        session.request = null;
        long elapsedNanos = completedNanos - session.startedNanos;
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            report(server, session, cause instanceof CancellationException ? "CANCELLED" : "ERROR",
                    cause, elapsedNanos);
            sessions.remove(session.owner, session);
            return;
        }
        if (corridor == null) {
            MacroSearch.Failure reason = terminalRequest.progress().failure();
            report(server, session, "FAILED_" + reason, null, elapsedNanos);
            sessions.remove(session.owner, session);
            return;
        }
        session.points = sampleCorridor(corridor);
        report(server, session, "SUCCESS", null, elapsedNanos);
        session.displayUntilNanos = System.nanoTime() + DISPLAY_NANOS;
        ServerPlayer player = server.getPlayerList().getPlayer(session.owner);
        if (player != null && player.level().dimension() == session.dimension) {
            player.sendSystemMessage(Component.literal("粒子用直线连接宏观走廊端点（最多 4096 个采样点），非逐格可走路线；附近路段刷新一分钟。"
                    + (corridor.endpoints().size() > MAX_POINTS ? "走廊端点过多，粒子已抽样。" : "")));
            refresh(server, session, player);
            session.nextRefreshTick = server.getTickCount() + REFRESH_TICKS;
        }
    }

    void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || sessions.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = System.nanoTime();
        var iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(session.owner);
            if (player == null || player.level().dimension() != session.dimension) {
                iterator.remove();
                if (session.request != null) {
                    session.request.cancel();
                    report(server, session, "CANCELLED_PLAYER_LEFT", null, now - session.startedNanos);
                }
            } else if (session.request == null) {
                if (now >= session.displayUntilNanos) {
                    iterator.remove();
                } else if (server.getTickCount() >= session.nextRefreshTick) {
                    refresh(server, session, player);
                    session.nextRefreshTick = server.getTickCount() + REFRESH_TICKS;
                }
            }
        }
    }

    void stopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        List<Session> ending = List.copyOf(sessions.values());
        sessions.clear();
        for (Session session : ending) {
            if (session.request != null) {
                session.request.cancel();
                report(server, session, "CANCELLED_SERVER_STOPPING", null,
                        System.nanoTime() - session.startedNanos);
            }
        }
    }

    private static void report(MinecraftServer server, Session session, String result,
                               Throwable failure, long elapsedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsedNanos));
        AcceleratedNavigation.LOGGER.info("Navigation macro query owner={} dimension={} start={} goal={} canOpenDoors={} result={} elapsedMs={}",
                session.owner, session.dimension.location(), session.start, session.goal, session.canOpenDoors, result, elapsedMillis);
        if (failure != null && !(failure instanceof CancellationException)) {
            AcceleratedNavigation.LOGGER.warn("Navigation macro query failed for {}", session.owner, failure);
        }
        ServerPlayer player = server.getPlayerList().getPlayer(session.owner);
        if (player != null && player.level().dimension() == session.dimension) {
            player.sendSystemMessage(Component.literal("宏观寻路" + resultText(result)
                    + "；耗时 " + elapsedMillis + " ms（提交到查询终态，不含粒子展示）。"));
        }
    }

    private static String resultText(String result) {
        return switch (result) {
            case "SUCCESS" -> "成功";
            case "CANCELLED_REPLACED" -> "已取消（被新请求替代）";
            case "CANCELLED_PLAYER_LEFT" -> "已取消（玩家离线或换维度）";
            case "CANCELLED_SERVER_STOPPING" -> "已取消（服务器停止）";
            case "CANCELLED" -> "已取消";
            case "ERROR" -> "异常失败（详见服务器日志）";
            case "FAILED_NO_STRUCTURAL_ROUTE" -> "失败（无宏观结构路线）";
            case "FAILED_SEARCH_LIMIT_REACHED" -> "失败（达到搜索上限）";
            case "FAILED_UNAVAILABLE_CHUNK" -> "失败（所需区块未加载）";
            case "FAILED_FACTS_RECOVERY_FAILED" -> "失败（地形事实恢复失败）";
            case "FAILED_FACTS_PERSISTENCE_UNAVAILABLE" -> "失败（地形事实存储不可用）";
            case "FAILED_STALE_WORLD" -> "失败（世界已变化）";
            default -> "失败（" + result + "）";
        };
    }

    private static List<Vec3> sampleCorridor(MacroSearch.Corridor corridor) {
        List<MacroSearch.Endpoint> endpoints = corridor.endpoints();
        if (endpoints.isEmpty()) return List.of();
        if (endpoints.size() > MAX_POINTS) return sampleLongCorridor(endpoints);

        double length = 0.0D;
        for (int i = 1; i < endpoints.size(); i++) {
            length += Vec3.atCenterOf(endpoints.get(i - 1).anchor())
                    .distanceTo(Vec3.atCenterOf(endpoints.get(i).anchor()));
        }
        int extra = MAX_POINTS - endpoints.size();
        double spacing = extra == 0 ? Double.POSITIVE_INFINITY : Math.max(0.5D, length / extra);
        List<Vec3> samples = new ArrayList<>(MAX_POINTS);
        samples.add(Vec3.atCenterOf(endpoints.get(0).anchor()));
        for (int i = 1; i < endpoints.size(); i++) {
            Vec3 from = Vec3.atCenterOf(endpoints.get(i - 1).anchor());
            Vec3 to = Vec3.atCenterOf(endpoints.get(i).anchor());
            double segment = from.distanceTo(to);
            for (double distance = spacing; distance < segment && samples.size() < MAX_POINTS - 1;
                 distance += spacing) {
                samples.add(from.lerp(to, distance / segment));
            }
            samples.add(to);
        }
        return List.copyOf(samples);
    }

    // Extremely long corridors cannot preserve every corner within the display budget.
    private static List<Vec3> sampleLongCorridor(List<MacroSearch.Endpoint> endpoints) {
        double length = 0.0D;
        for (int i = 1; i < endpoints.size(); i++) {
            length += Vec3.atCenterOf(endpoints.get(i - 1).anchor())
                    .distanceTo(Vec3.atCenterOf(endpoints.get(i).anchor()));
        }
        if (length == 0.0D) return List.of(Vec3.atCenterOf(endpoints.get(0).anchor()));
        double spacing = length / (MAX_POINTS - 1);
        List<Vec3> samples = new ArrayList<>(MAX_POINTS);
        double travelled = 0.0D;
        double next = 0.0D;
        for (int i = 1; i < endpoints.size() && samples.size() < MAX_POINTS - 1; i++) {
            Vec3 from = Vec3.atCenterOf(endpoints.get(i - 1).anchor());
            Vec3 to = Vec3.atCenterOf(endpoints.get(i).anchor());
            double segment = from.distanceTo(to);
            if (segment == 0.0D) continue;
            while (next <= travelled + segment && samples.size() < MAX_POINTS - 1) {
                if (next >= travelled) samples.add(from.lerp(to, Math.min(1.0D, (next - travelled) / segment)));
                next += spacing;
            }
            travelled += segment;
        }
        Vec3 goal = Vec3.atCenterOf(endpoints.get(endpoints.size() - 1).anchor());
        if (samples.isEmpty() || !samples.get(samples.size() - 1).equals(goal)) samples.add(goal);
        return List.copyOf(samples);
    }

    private static void refresh(MinecraftServer server, Session session, ServerPlayer player) {
        ServerLevel level = server.getLevel(session.dimension);
        if (level == null) return;
        Vec3 viewer = player.position();
        int visible = 0;
        for (Vec3 point : session.points) {
            if (point.distanceToSqr(viewer) < VISIBLE_DISTANCE_SQUARED) visible++;
        }
        int stride = Math.max(1, (visible + MAX_PARTICLES_PER_REFRESH - 1) / MAX_PARTICLES_PER_REFRESH);
        int index = 0;
        for (Vec3 point : session.points) {
            if (point.distanceToSqr(viewer) >= VISIBLE_DISTANCE_SQUARED) continue;
            if (index++ % stride != 0) continue;
            level.sendParticles(player, ParticleTypes.END_ROD, false,
                    point.x, point.y, point.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static final class Session {
        private final UUID owner;
        private final ResourceKey<Level> dimension;
        private final long startedNanos;
        private final BlockPos start;
        private final BlockPos goal;
        private final boolean canOpenDoors;
        private TopologyService.MacroRequest request;
        private List<Vec3> points = List.of();
        private long displayUntilNanos;
        private int nextRefreshTick;

        private Session(UUID owner, ResourceKey<Level> dimension, BlockPos start, BlockPos goal,
                        boolean canOpenDoors, long startedNanos) {
            this.owner = owner;
            this.dimension = dimension;
            this.start = start.immutable();
            this.goal = goal.immutable();
            this.startedNanos = startedNanos;
            this.canOpenDoors = canOpenDoors;
        }
    }
}
