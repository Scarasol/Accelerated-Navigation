package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {"com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$TopologyGraph",
        "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$SuperTopologyGraph"}, remap = false)
abstract class TopologyGraphValidationMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void observeCapture(CallbackInfo callback) { TopologyValidationAccess.graphClosing(this); }
}
