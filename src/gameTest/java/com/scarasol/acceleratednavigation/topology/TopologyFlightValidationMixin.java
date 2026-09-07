package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroFlight", remap = false)
abstract class TopologyFlightValidationMixin {
    @Inject(method = "add", at = @At("RETURN"))
    private void observeBinding(@Coerce Object waiter, CallbackInfo callback) { TopologyValidationAccess.bindFlight(this, waiter); }
}
