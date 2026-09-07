package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyRuntimeFormalControlAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ordinary benchmarks suppress prewarm; the explicit lifecycle control opts in by owner. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap = false)
abstract class TopologyWorkerRuntimePrewarmMixin {

    @Inject(method = "admitPrewarm", at = @At("HEAD"), cancellable = true)
    private void acceleratedNavigation$blockPrewarmAdmission(CallbackInfo callback) {
        if (!((Object) this instanceof TopologyRuntimeFormalControlAccess control)
                || !control.acceleratedNavigation$allowsPrewarm()) callback.cancel();
    }
}
