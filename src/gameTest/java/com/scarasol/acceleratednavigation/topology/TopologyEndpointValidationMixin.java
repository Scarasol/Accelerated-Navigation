package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import com.scarasol.acceleratednavigation.topology.TopologyEndpointFailureScenario;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$ResolveFlight", remap = false)
abstract class TopologyEndpointValidationMixin {
    @Inject(method = "releaseHeld", at = @At("HEAD"))
    private void observeInputs(CallbackInfo callback) { TopologyValidationAccess.endpointInputs(this); }
    @Inject(method = "resolved", at = @At("HEAD"))
    private void observeResolution(@Coerce Object starts, @Coerce Object goals, CallbackInfo callback) {
        TopologyEndpointFailureScenario.resolved(this, starts, goals);
    }
}
