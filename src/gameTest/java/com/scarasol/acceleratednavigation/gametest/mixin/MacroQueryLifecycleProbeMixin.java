package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/** Test-only timestamps for the endpoint topology resolve phase. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyService$MacroRequest",
        remap = false)
public abstract class MacroQueryLifecycleProbeMixin {

    @Inject(method = "beginResolve", at = @At("HEAD"))
    private void acceleratedNavigation$recordResolveStart(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordResolveStart();
    }

    @Inject(method = "beginFallbackResolve", at = @At("HEAD"))
    private void acceleratedNavigation$recordFallbackResolveStart(
            boolean start,
            boolean goal,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordFallbackResolveStart();
    }

    @Redirect(
            method = {"beginResolve", "beginFallbackResolve"},
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;allOf([Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<Void> acceleratedNavigation$recordEndpointFuturesReady(
            CompletableFuture<?>[] futures) {
        CompletableFuture<Void> combined = CompletableFuture.allOf(futures);
        combined.whenComplete((ignored, failure) ->
                MacroSearchMetricsProbe.recordEndpointFuturesReady());
        return combined;
    }

    @Inject(method = "completeResolve", at = @At("HEAD"))
    private void acceleratedNavigation$recordResolveCallback(
            long attempt,
            Throwable resolveFailure,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordResolveCallback();
    }

    @Inject(method = "finish", at = @At("HEAD"))
    private void acceleratedNavigation$recordFinishEnter(
            MacroSearch.Corridor result,
            MacroSearch.Failure resultFailure,
            TopologyService.QueryMetrics resultMetrics,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordRequestFinishEnter();
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private void acceleratedNavigation$recordFinishReturn(
            MacroSearch.Corridor result,
            MacroSearch.Failure resultFailure,
            TopologyService.QueryMetrics resultMetrics,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordRequestFinishReturn();
    }

    @Inject(method = {"finishExceptionally", "reject"}, at = @At("HEAD"))
    private void acceleratedNavigation$recordExceptionalFinishEnter(
            Throwable failure,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordRequestFinishEnter();
    }

    @Inject(method = {"finishExceptionally", "reject"}, at = @At("RETURN"))
    private void acceleratedNavigation$recordExceptionalFinishReturn(
            Throwable failure,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordRequestFinishReturn();
    }
}
