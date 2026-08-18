package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only timing around the shared macro flight and worker task. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyService$MacroFlight",
        remap = false)
public abstract class MacroFlightLifecycleProbeMixin {

    @Inject(method = "start", at = @At("HEAD"))
    private void acceleratedNavigation$recordFlightStart(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordFlightStart();
    }

    @Inject(method = "resume", at = @At("HEAD"))
    private void acceleratedNavigation$recordWorkerSubmit(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordSchedulerSubmit();
        MacroSearchMetricsProbe.recordSchedulerSubmitReturned();
    }

    @Inject(method = "runWorkerTask", at = @At("HEAD"))
    private void acceleratedNavigation$recordWorkerStart(
            long generation,
            int budget,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordSchedulerFutureComplete();
    }

    @Inject(method = "complete", at = @At("HEAD"))
    private void acceleratedNavigation$recordFlightCompleteCallback(
            MacroSearch.Corridor corridor,
            Throwable searchFailure,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordFlightCompleteCallback();
    }
}
