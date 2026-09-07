package com.scarasol.acceleratednavigation.topology;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroQuery", remap = false)
abstract class TopologyMixedFailureMixin {
    @Inject(method = "createSearchOnWorker", at = @At("RETURN"))
    private void created(CallbackInfo callback) { TopologyMixedFailureScenario.created(this); }

    @Inject(method = "peekResolvedDependencies", at = @At("RETURN"), cancellable = true)
    private void resolutions(CallbackInfoReturnable<List<?>> callback) {
        callback.setReturnValue(TopologyMixedFailureScenario.resolutions(this, callback.getReturnValue()));
    }

    @Redirect(method = "applyResolvedDependencies", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/MacroSearch;dependencyUnavailable(Lcom/scarasol/acceleratednavigation/topology/MacroSearch$DependencyKey;Lcom/scarasol/acceleratednavigation/topology/MacroSearch$Unavailability;)V"))
    private void unavailable(MacroSearch search, MacroSearch.DependencyKey key, MacroSearch.Unavailability reason) {
        TopologyMixedFailureScenario.unavailable(search, key, reason);
    }

    @Inject(method = "requestDependency", at = @At("HEAD"))
    private void requested(MacroSearch.DependencyKey key, CallbackInfo callback) { TopologyMixedFailureScenario.requested(this, key); }
}
