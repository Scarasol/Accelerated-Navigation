package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroQuery", remap = false)
abstract class MacroQueryCausalityMixin {
    @Inject(method = "createSearchOnWorker", at = @At("RETURN"))
    private void observeSearch(CallbackInfo callback) {
        TopologyCausalityObservation.bindSearch(this);
    }

    @Inject(method = "dependencyAvailableInWorld", at = @At("RETURN"))
    private void observeUnavailable(MacroSearch.DependencyKey key, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) TopologyCausalityObservation.unavailable(this, key);
    }
}
