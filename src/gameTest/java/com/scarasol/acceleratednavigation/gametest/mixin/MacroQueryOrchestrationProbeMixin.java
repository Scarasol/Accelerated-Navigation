package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyService$MacroQuery",
        remap = false)
public abstract class MacroQueryOrchestrationProbeMixin {

    @Inject(method = "step", at = @At("HEAD"))
    private void acceleratedNavigation$recordMacroStep(
            int expansionBudget,
            long deadlineNanos,
            CallbackInfoReturnable<?> callback) {
        MacroSearchMetricsProbe.recordMacroStepStart();
    }

    @Inject(method = "step", at = @At("RETURN"))
    private void acceleratedNavigation$recordMacroStepReturn(
            int expansionBudget,
            long deadlineNanos,
            CallbackInfoReturnable<?> callback) {
        MacroSearchMetricsProbe.recordMacroStepEnd(
                String.valueOf(callback.getReturnValue()),
                waitingForBuild,
                refining
        );
    }

    @Shadow
    private boolean waitingForBuild;

    @Shadow
    private boolean refining;

    @Shadow
    @Final
    private Set<?> pendingDependencyNotifications;

    @Inject(method = "requestPendingSections", at = @At("HEAD"))
    private void acceleratedNavigation$recordPendingDependencyPass(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordPendingDependencyPass();
    }

    @Inject(method = "drainDependencyNotifications",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Set;copyOf(Ljava/util/Collection;)Ljava/util/Set;"))
    private void acceleratedNavigation$recordDependencyCompletionBatch(
            long scheduledGeneration,
            CallbackInfo callback) {
        MacroSearchMetricsProbe.recordDependencyCompletionBatch(
                pendingDependencyNotifications.size()
        );
    }

    @Inject(method = "park", at = @At("RETURN"))
    private void acceleratedNavigation$recordPark(
            Runnable wakeup,
            CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.TRUE.equals(callback.getReturnValue())) {
            MacroSearchMetricsProbe.recordQueryPark();
        }
    }

    @Inject(method = "signalWakeup",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void acceleratedNavigation$recordWake(CallbackInfo callback) {
        MacroSearchMetricsProbe.recordQueryWake();
    }
}
