package com.scarasol.acceleratednavigation.topology;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroQuery", remap = false)
abstract class TopologyDependencyLimitMixin {
    @Inject(method = "createSearchOnWorker", at = @At("RETURN"))
    private void beforeSearch(CallbackInfo callback) { TopologyDependencyLimitScenario.beforeSearch(this); }
}
