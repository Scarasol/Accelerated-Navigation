package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import java.util.Map;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$ResolveFlight", remap = false)
abstract class TopologyEndpointCausalityMixin {
    @Unique private TopologyCausalityObservation.Span acceleratedNavigation$resolve;
    @Inject(method = "resolveOnWorker", at = @At("HEAD"))
    private void beginResolution(List<?> input, Map<?, ?> failures, CallbackInfo callback) {
        acceleratedNavigation$resolve = TopologyCausalityObservation.begin("endpoint", "ResolveFlight.resolveOnWorker", this);
        TopologyCausalityObservation.endpointInput(this, input, failures);
    }
    @Inject(method = "resolveOnWorker", at = @At("RETURN"))
    private void endResolution(CallbackInfo callback) {
        if (acceleratedNavigation$resolve == null) return;
        TopologyCausalityObservation.end(acceleratedNavigation$resolve, Map.of("key", TopologyTestBridge.readField(this, "key")));
        acceleratedNavigation$resolve = null;
    }
    @Inject(method = "resolved", at = @At("HEAD"))
    private void resolutionResult(@Coerce Object starts, @Coerce Object goals, CallbackInfo callback) {
        TopologyCausalityObservation.endpointResolved(this, starts, goals);
    }
}
