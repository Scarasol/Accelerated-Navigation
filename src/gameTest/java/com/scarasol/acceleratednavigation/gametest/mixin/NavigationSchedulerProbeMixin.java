package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NavigationScheduler.class, remap = false)
public abstract class NavigationSchedulerProbeMixin {

    @Inject(method = "endTick", at = @At("HEAD"))
    private void acceleratedNavigation$recordSchedulerTick(
            boolean haveTime,
            CallbackInfo callback) {
        if (!MacroSearchMetricsProbe.queryTimingActive()) {
            return;
        }
        MacroSearchMetricsProbe.recordSchedulerTick(haveTime);
    }

    @Inject(method = "drain", at = @At("HEAD"))
    private void acceleratedNavigation$recordDrainStart(
            long schedulerDeadline,
            int maxSlices,
            CallbackInfo callback) {
        if (!MacroSearchMetricsProbe.queryTimingActive()) {
            return;
        }
        MacroSearchMetricsProbe.recordSchedulerDrainStart();
    }

    @Inject(method = "drain", at = @At("RETURN"))
    private void acceleratedNavigation$recordDrainEnd(
            long schedulerDeadline,
            int maxSlices,
            CallbackInfo callback) {
        if (!MacroSearchMetricsProbe.queryTimingActive()) {
            return;
        }
        MacroSearchMetricsProbe.recordSchedulerDrainEnd();
    }

    @Inject(method = "chargeSlice", at = @At("RETURN"))
    private void acceleratedNavigation$recordChargedSlice(
            long sliceStarted,
            CallbackInfoReturnable<Long> callback) {
        if (!MacroSearchMetricsProbe.queryTimingActive()) {
            return;
        }
        MacroSearchMetricsProbe.recordSchedulerSlice(callback.getReturnValue());
    }

    @Inject(method = "takeNext", at = @At("RETURN"))
    private void acceleratedNavigation$recordSelectedPriority(
            long now,
            CallbackInfoReturnable<?> callback) {
        if (!MacroSearchMetricsProbe.queryTimingActive()) {
            return;
        }
        MacroSearchMetricsProbe.recordSchedulerSelection(callback.getReturnValue());
    }
}
