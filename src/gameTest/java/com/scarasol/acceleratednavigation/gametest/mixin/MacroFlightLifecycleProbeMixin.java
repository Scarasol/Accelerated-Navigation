package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Test-only timing around the shared macro flight and scheduler future. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyService$MacroFlight",
        remap = false)
public abstract class MacroFlightLifecycleProbeMixin {

    @Inject(method = "start", at = @At("HEAD"))
    private void acceleratedNavigation$recordFlightStart(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordFlightStart();
    }

    @Redirect(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/scarasol/acceleratednavigation/scheduler/NavigationScheduler;submitStrict(Lnet/minecraft/resources/ResourceKey;Ljava/util/UUID;Lcom/scarasol/acceleratednavigation/scheduler/NavigationScheduler$Priority;Lcom/scarasol/acceleratednavigation/api/ResumableSearch;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<?> acceleratedNavigation$recordSchedulerSubmit(
            NavigationScheduler scheduler,
            ResourceKey<Level> dimension,
            UUID owner,
            NavigationScheduler.Priority priority,
            ResumableSearch<?> search) {
        MacroSearchMetricsProbe.recordSchedulerSubmit();
        CompletableFuture<?> future = scheduler.submitStrict(
                dimension,
                owner,
                priority,
                search
        );
        MacroSearchMetricsProbe.recordSchedulerSubmitReturned();
        future.whenComplete((ignored, failure) ->
                MacroSearchMetricsProbe.recordSchedulerFutureComplete());
        return future;
    }

    @Inject(method = "complete", at = @At("HEAD"))
    private void acceleratedNavigation$recordFlightCompleteCallback(
            MacroSearch.Corridor corridor,
            Throwable searchFailure,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordFlightCompleteCallback();
    }
}
