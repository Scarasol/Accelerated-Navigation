package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OreFeature.class)
abstract class TopologyOreValidationMixin {
    @Inject(method = "place", at = @At("HEAD"))
    private void entered(FeaturePlaceContext<OreConfiguration> context, CallbackInfoReturnable<Boolean> callback) {
        if (TopologyGenerationProbe.ENABLED) TopologyGenerationProbe.enter("Ore", context.level().getChunk(context.origin()));
    }
    @Inject(method = "place", at = @At("RETURN"))
    private void completed(CallbackInfoReturnable<Boolean> callback) { TopologyGenerationProbe.exit(); }
}
