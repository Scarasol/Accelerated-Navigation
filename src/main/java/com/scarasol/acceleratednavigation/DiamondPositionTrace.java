package com.scarasol.acceleratednavigation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Temporary route recording: hold a diamond to write two position samples per second. */
@Mod.EventBusSubscriber(modid = AcceleratedNavigation.MOD_ID)
public final class DiamondPositionTrace {
    private static final long INTERVAL_NANOS = 500_000_000L;
    // All handlers run on the server thread; keep entries only for players recording a route.
    private static final Map<UUID, Long> LAST_SAMPLE = new HashMap<>();

    private DiamondPositionTrace() {}

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) return;
        UUID owner = player.getUUID();
        if (!player.getMainHandItem().is(Items.DIAMOND)
                && !player.getOffhandItem().is(Items.DIAMOND)) {
            LAST_SAMPLE.remove(owner);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_SAMPLE.get(owner);
        if (previous != null && now - previous < INTERVAL_NANOS) return;
        LAST_SAMPLE.put(owner, now);
        AcceleratedNavigation.LOGGER.info(
                "Navigation player trace owner={} dimension={} x={} y={} z={}",
                owner, player.level().dimension().location(),
                coordinate(player.getX()), coordinate(player.getY()), coordinate(player.getZ()));
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST_SAMPLE.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LAST_SAMPLE.clear();
    }
}
