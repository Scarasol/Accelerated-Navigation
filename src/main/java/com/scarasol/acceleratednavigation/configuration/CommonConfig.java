package com.scarasol.acceleratednavigation.configuration;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

public final class CommonConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue AUTOMATIC_NAVIGATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BYPASS_ENTITY_IDS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        AUTOMATIC_NAVIGATION = builder
                .comment("Automatically schedule supported vanilla navigation intents; bypassEntityIds retain vanilla behavior.")
                .define("automaticNavigation", true);
        BYPASS_ENTITY_IDS = builder
                .comment("Exact entity type registry IDs that retain original navigation without scheduling.")
                .defineListAllowEmpty("bypassEntityIds", List.<String>of(), value -> value instanceof String);
        SPEC = builder.build();
    }

    private CommonConfig() {}
}
