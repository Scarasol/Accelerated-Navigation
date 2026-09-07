package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologySectionEventProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap = false)
abstract class TopologySectionEventValidationMixin {
    @Inject(method = "publishSection", at = @At("HEAD"), cancellable = true)
    private void event(@Coerce Object event, CallbackInfo callback) {
        TopologySectionEventProbe.event(event);
        if (TopologySectionEventProbe.defer(this, event)) callback.cancel();
    }
}
