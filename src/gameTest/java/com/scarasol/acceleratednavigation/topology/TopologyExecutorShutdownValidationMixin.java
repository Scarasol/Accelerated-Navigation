package com.scarasol.acceleratednavigation.topology;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyTaskExecutor.class, remap = false)
abstract class TopologyExecutorShutdownValidationMixin {
    @Inject(method = "takeNext", at = @At("HEAD"))
    private void queued(long now, CallbackInfoReturnable<?> callback) { TopologyBuildWindowProbe.executorQueued(this); }
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void claimed(Runnable command) { TopologyBuildWindowProbe.executorClaimed(this); command.run(); }
    @Inject(method = "submitControl", at = @At("HEAD"))
    private void submitting(Runnable command, CallbackInfoReturnable<TopologyTaskExecutor.TaskHandle> callback) {
        TopologyShutdownScenario.submittingControl(this);
    }
}
