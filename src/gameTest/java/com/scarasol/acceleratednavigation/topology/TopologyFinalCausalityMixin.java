package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TopologyService.MacroRequest.class, remap = false)
abstract class TopologyFinalCausalityMixin {
    @Unique private TopologyCausalityObservation.Span acceleratedNavigation$validation;
    @Unique private Map<String, Object> acceleratedNavigation$identity;
    @Inject(method = "completeWorker", at = @At("HEAD"))
    private void beginValidation(@Coerce Object result, Throwable failure, CallbackInfo callback) {
        acceleratedNavigation$validation = TopologyCausalityObservation.begin("final-validation", "MacroRequest.completeWorker", this);
        if (acceleratedNavigation$validation != null) acceleratedNavigation$identity = TopologyValidationAccess.requestIdentity(this);
    }
    @Inject(method = "completeWorker", at = @At("RETURN"))
    private void endValidation(@Coerce Object result, Throwable failure, CallbackInfo callback) {
        if (acceleratedNavigation$validation == null) return;
        Map<String, Object> row = new LinkedHashMap<>(acceleratedNavigation$identity);
        row.put("progress", ((TopologyService.MacroRequest) (Object) this).progress());
        row.put("workerResultToken", TopologyValidationAccess.token(result)); row.put("exception", failure == null ? null : failure.toString());
        TopologyCausalityObservation.end(acceleratedNavigation$validation, row); acceleratedNavigation$validation = null;
        acceleratedNavigation$identity = null;
    }
}
