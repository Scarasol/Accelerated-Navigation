package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyResultValidationProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TopologyService.MacroRequest.class, remap = false)
abstract class TopologyResultValidationMixin {
    @Inject(method = "completeWorker", at = @At("HEAD"), cancellable = true)
    private void finalValidation(@Coerce Object result, Throwable failure, CallbackInfo callback) {
        if (TopologyResultValidationProbe.pause(this, result, failure)) callback.cancel();
    }
}
