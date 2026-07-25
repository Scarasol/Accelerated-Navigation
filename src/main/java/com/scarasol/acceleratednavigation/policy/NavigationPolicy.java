package com.scarasol.acceleratednavigation.policy;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.TimeSlicedPathNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves routing policy without knowing or invoking a pathfinding backend. */
public final class NavigationPolicy {

    private static final double NEARBY_PLAYER_RANGE = 32.0D;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BYPASS_ENTITY_IDS;
    private static final Set<String> WARNED_IDS = new HashSet<>();

    public static final ForgeConfigSpec CONFIG_SPEC;

    private static volatile Set<ResourceLocation> bypassEntityIds = Set.of();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        BYPASS_ENTITY_IDS = builder
                .comment("Exact entity type registry IDs that retain original navigation without scheduling.")
                .defineListAllowEmpty("bypassEntityIds", List.<String>of(), value -> value instanceof String);
        CONFIG_SPEC = builder.build();
    }

    private NavigationPolicy() {
    }

    public static SchedulingMode modeFor(Mob mob) {
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
        if (entityId != null && bypassEntityIds.contains(entityId)) {
            return SchedulingMode.BYPASS;
        }
        return mob.getNavigation() instanceof TimeSlicedPathNavigation
                ? SchedulingMode.STRICT
                : SchedulingMode.SOFT;
    }

    public static NavigationScheduler.Priority priorityFor(Mob mob) {
        LivingEntity target = mob.getTarget();
        if (target instanceof Player) {
            return NavigationScheduler.Priority.PLAYER_PURSUIT;
        }
        if (mob.level().getNearestPlayer(mob, NEARBY_PLAYER_RANGE) != null) {
            return NavigationScheduler.Priority.PLAYER_NEARBY;
        }
        if (target != null || !mob.getNavigation().isDone()) {
            return NavigationScheduler.Priority.ACTIVE;
        }
        return NavigationScheduler.Priority.BACKGROUND;
    }

    public static void reloadBypassIds() {
        Set<ResourceLocation> resolved = new HashSet<>();
        for (String configuredId : BYPASS_ENTITY_IDS.get()) {
            ResourceLocation id = ResourceLocation.tryParse(configuredId);
            if (id != null && ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
                resolved.add(id);
                continue;
            }
            synchronized (WARNED_IDS) {
                if (WARNED_IDS.add(configuredId)) {
                    AcceleratedNavigation.LOGGER.warn(
                            "Ignoring unknown bypass entity ID '{}' in accelerated-navigation-common.toml",
                            configuredId
                    );
                }
            }
        }
        bypassEntityIds = Set.copyOf(resolved);
    }

    public enum SchedulingMode {
        BYPASS,
        STRICT,
        SOFT
    }
}
