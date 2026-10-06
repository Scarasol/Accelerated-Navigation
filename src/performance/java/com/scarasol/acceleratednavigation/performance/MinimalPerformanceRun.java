package com.scarasol.acceleratednavigation.performance;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only compiled into the dedicated performance run; absent from the production jar. */
@Mod.EventBusSubscriber(modid = "accelerated_navigation", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MinimalPerformanceRun {
    private static final int SAMPLES = 4;
    private static final TicketType<ChunkPos> DISTANCE_TICKET = TicketType.create(
            "an_surface_distance", (left, right) -> Long.compare(left.toLong(), right.toLong()));
    private static final List<ChunkPos> heldChunks = new ArrayList<>();
    private static final long MAX_WALL_NANOS = 30_000_000_000L;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos start;
    private static BlockPos goal;
    private static BlockPos longGoal;
    private static boolean surfaceDistances;
    private static long ticks;
    private static long readyTick;
    private static int completed;
    private static Active active;

    private MinimalPerformanceRun() { }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        try {
            String scenario = System.getProperty("an.performance.scenario", "short");
            if (!scenario.equals("short") && !scenario.equals("surface96_512")) {
                throw new IllegalArgumentException("unknown performance scenario: " + scenario);
            }
            surfaceDistances = scenario.equals("surface96_512");
            // Generate the entire local route neighbourhood before starting any stopwatch.
            for (int x = -1; x <= (surfaceDistances ? 33 : 2); x++) {
                for (int z = (surfaceDistances ? -2 : -1); z <= (surfaceDistances ? 2 : 1); z++) {
                    if (surfaceDistances) {
                        ChunkPos position = new ChunkPos(x, z);
                        level.getChunkSource().addRegionTicket(DISTANCE_TICKET, position, 0, position);
                        heldChunks.add(position);
                    }
                    level.getChunk(x, z);
                }
            }
            start = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(8, 0, 8));
            goal = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(surfaceDistances ? 104 : 24, 0, 8));
            if (surfaceDistances) {
                longGoal = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(520, 0, 8));
            }
            readyTick = surfaceDistances ? 400 : 80;
            System.out.println("AN_PERF_READY scenario=" + scenario + " start=" + start
                    + " goal=" + goal + " longGoal=" + longGoal);
        } catch (Throwable failure) {
            abort("prepare", failure);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (server == null || event.getServer() != server || event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (active != null && System.nanoTime() - active.startedNanos > MAX_WALL_NANOS) {
            Active expired = active;
            active = null;
            expired.request.cancel();
            System.out.println("AN_PERF_SAMPLE index=" + expired.index + " route=" + expired.route
                    + " result=TIMEOUT wallMs=" + millis(System.nanoTime() - expired.startedNanos)
                    + " ticks=" + (ticks - expired.startedTick));
            finishOne();
        }
        if (active != null || ticks < readyTick || completed == SAMPLES) return;
        if (surfaceDistances && !allChunksLoaded()) {
            if (ticks > 600) {
                abort("chunk-readiness", new IllegalStateException("held chunks did not stay loaded"));
                return;
            }
            readyTick = ticks + 20;
            return;
        }
        int index = completed + 1;
        boolean firstRoute = index % 2 == 1;
        String route = surfaceDistances ? (firstRoute ? "surface_96" : "surface_512")
                : (firstRoute ? "same" : "short");
        BlockPos destination = surfaceDistances ? (firstRoute ? goal : longGoal)
                : (firstRoute ? start : goal);
        try {
            long begin = System.nanoTime();
            TopologyService.MacroRequest request = TopologyService.forServer(server)
                    .requestMacroQuery(level, UUID.randomUUID(), start, destination,
                            BaseClusterTopology.Channel.GROUND,
                            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                            NavigationScheduler.Priority.ACTIVE);
            Active submitted = new Active(index, route, begin, ticks, request);
            active = submitted;
            request.future().whenComplete((corridor, failure) -> complete(submitted, corridor, failure));
        } catch (Throwable failure) {
            abort("submit-" + index, failure);
        }
    }

    private static void complete(Active submitted, MacroSearch.Corridor corridor, Throwable failure) {
        long elapsed = System.nanoTime() - submitted.startedNanos;
        if (active != submitted) return;
        active = null;
        String result = failure != null ? "EXCEPTION:" + failure.getClass().getSimpleName()
                : corridor != null ? "SUCCESS" : "BUSINESS_FAILURE:" + submitted.request.progress().failure();
        System.out.println("AN_PERF_SAMPLE index=" + submitted.index + " route=" + submitted.route
                + " result=" + result + " wallMs=" + millis(elapsed)
                + " ticks=" + (ticks - submitted.startedTick)
                + " cache=" + submitted.request.completedFromCache());
        finishOne();
    }

    private static void finishOne() {
        completed++;
        if (completed == SAMPLES) {
            System.out.println("AN_PERF_COMPLETE samples=" + completed);
            releaseTickets();
            server.halt(false);
        } else {
            readyTick = ticks + 5;
        }
    }

    private static boolean allChunksLoaded() {
        for (ChunkPos position : heldChunks) {
            if (level.getChunkSource().getChunkNow(position.x, position.z) == null) return false;
        }
        return true;
    }

    private static void releaseTickets() {
        if (level == null) return;
        for (ChunkPos position : heldChunks) {
            level.getChunkSource().removeRegionTicket(DISTANCE_TICKET, position, 0, position);
        }
        heldChunks.clear();
    }

    private static long millis(long nanos) {
        return (nanos + 500_000L) / 1_000_000L;
    }

    private static void abort(String stage, Throwable failure) {
        System.out.println("AN_PERF_FATAL stage=" + stage + " type=" + failure.getClass().getName()
                + " message=" + failure.getMessage());
        failure.printStackTrace();
        releaseTickets();
        server.halt(false);
    }

    private record Active(int index, String route, long startedNanos, long startedTick,
                          TopologyService.MacroRequest request) { }
}
