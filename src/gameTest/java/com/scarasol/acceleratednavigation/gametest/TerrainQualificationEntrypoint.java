package com.scarasol.acceleratednavigation.gametest;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Owns the qualification-only server lifecycle without loading the production mod. */
@Mod.EventBusSubscriber(modid = TerrainQualificationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TerrainQualificationEntrypoint {
    private static ServerLifecycle controller;

    private TerrainQualificationEntrypoint() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("acceleratedNavigation.terrainBenchmark")) {
            return;
        }
        if (!"qualification".equals(System.getProperty("acceleratedNavigation.terrainTestMode"))) {
            throw new IllegalStateException(
                    "terrain qualification mod requires terrainTestMode=qualification");
        }
        if (controller != null) {
            throw new IllegalStateException("terrain qualification started twice");
        }
        controller = new TerrainQualificationController(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (controller == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            controller.tick(event.haveTime());
        } catch (RuntimeException failure) {
            controller.failAndStop(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        ServerLifecycle current = controller;
        if (current == null) {
            return;
        }
        try {
            current.serverStopped();
        } catch (RuntimeException failure) {
            current.recordStoppedFailure(failure);
        }
    }

    static void clearController() {
        controller = null;
    }

    interface ServerLifecycle {
        void tick(boolean haveTime);

        void failAndStop(RuntimeException failure);

        void serverStopped();

        void recordStoppedFailure(RuntimeException failure);
    }
}
