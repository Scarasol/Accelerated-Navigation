package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$StaleTopologyException", remap = false)
abstract class TopologyStaleCausalityMixin {
    @Inject(method = "<init>(Ljava/lang/String;)V", at = @At("RETURN"))
    private void constructed(String message, CallbackInfo callback) {
        if (!TopologyCausalityObservation.enabled("search")) return;
        TopologyCausalityObservation.emit("stale_exception", java.util.Map.of("message", message,
                "caller", StackWalker.getInstance().walk(frames -> frames
                        .filter(frame -> frame.getClassName().contains("TopologyWorkerRuntime"))
                        .limit(10).map(Object::toString).toList())));
    }
}
