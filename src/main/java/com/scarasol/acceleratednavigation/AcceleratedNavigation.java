package com.scarasol.acceleratednavigation;

import com.mojang.logging.LogUtils;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(AcceleratedNavigation.MOD_ID)
public final class AcceleratedNavigation {

    public static final String MOD_ID = "accelerated_navigation";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AcceleratedNavigation() {
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON,
                NavigationPolicy.CONFIG_SPEC,
                "accelerated-navigation-common.toml"
        );

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onConfigLoaded);
        modBus.addListener(this::onConfigReloaded);

        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(this::onLevelSave);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onConfigLoaded(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MOD_ID)) {
            NavigationPolicy.reloadBypassIds();
        }
    }

    private void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MOD_ID)) {
            NavigationPolicy.reloadBypassIds();
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            NavigationScheduler.beginServerTick(event.getServer());
        } else {
            TopologyService.endServerTick(event.getServer());
            NavigationScheduler.endServerTick(event.getServer(), event.haveTime());
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        TopologyService.shutdown(event.getServer());
        NavigationScheduler.shutdown(event.getServer());
    }

    private void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TopologyService.onChunkUnloaded(level, event.getChunk().getPos());
        }
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TopologyService.onChunkLoaded(level, event.getChunk().getPos());
        }
    }

    private void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            TopologyService.onLevelSave(level);
        }
    }
}


