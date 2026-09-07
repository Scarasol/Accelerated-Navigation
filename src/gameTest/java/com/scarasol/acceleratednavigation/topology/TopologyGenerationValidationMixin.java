package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TopologyService.ChunkFactsState.class, remap = false)
abstract class TopologyGenerationValidationMixin {
    @Inject(method = "recordGeneratedWrite", at = @At("HEAD"), cancellable = true)
    private void before(BlockGetter chunk, BlockPos position, CallbackInfo callback) {
        if (TopologyGenerationProbe.SUPPRESS) callback.cancel();
    }
}
