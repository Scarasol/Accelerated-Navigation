package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroQuery", remap = false)
abstract class MacroQueryCausalityMixin {
    @Inject(method = "dependencyWaiter", at = @At("RETURN"))
    private void requested(MacroSearch.DependencyKey key, CallbackInfoReturnable<Object> callback) {
        TopologyCausalityObservation.boundaryHandoff(this, "request", key, callback.getReturnValue(), null);
    }
    @Inject(method = "completeDependencyRequest", at = @At("HEAD"))
    private void completed(MacroSearch.DependencyKey key, @Coerce Object waiter, CallbackInfo callback) {
        TopologyCausalityObservation.boundaryHandoff(this, "complete", key, waiter, null);
    }
    @Inject(method = "retainResolvedDependency", at = @At("HEAD"))
    private void retained(MacroSearch.DependencyKey key, @Coerce Object resolved, CallbackInfo callback) {
        TopologyCausalityObservation.boundaryHandoff(this, "retain", key, null, resolved);
    }
    @Inject(method = "attachResolved", at = @At("HEAD"))
    private void attaching(@Coerce Object resolved, CallbackInfoReturnable<Boolean> callback) {
        TopologyCausalityObservation.boundaryHandoff(this, "attach", null, null, resolved);
    }
    @Inject(method = "preparePendingRecovery", at = @At("RETURN"))
    private void recovery(CallbackInfo callback) {
        TopologyCausalityObservation.boundaryHandoff(this, "recovery-prepared", null, null, null);
    }
    @Inject(method = "restartStaleSearch", at = @At("HEAD"))
    private void stale(CallbackInfo callback) { TopologyCausalityObservation.queryDecision(this, "restart-stale-search"); }
    @Inject(method = "finishOnOwner", at = @At("HEAD"))
    private void ending(CallbackInfo callback) { TopologyCausalityObservation.queryDecision(this, "finish-on-owner"); }
    @Inject(method = "createSearchOnWorker", at = @At("RETURN"))
    private void observeSearch(CallbackInfo callback) {
        TopologyCausalityObservation.bindSearch(this);
    }

    @Inject(method = "dependencyAvailableInWorld", at = @At("RETURN"))
    private void observeUnavailable(MacroSearch.DependencyKey key, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) TopologyCausalityObservation.unavailable(this, key);
    }
}
