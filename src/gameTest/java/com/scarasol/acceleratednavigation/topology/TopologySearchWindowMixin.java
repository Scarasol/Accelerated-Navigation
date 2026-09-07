package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MacroSearch.class, remap = false)
abstract class TopologySearchWindowMixin {
    @Inject(method = "step", at = @At("RETURN"))
    private void capturedReference(int budget, CallbackInfoReturnable<ResumableSearch.Status> callback) {
        TopologyBuildWindowProbe.searchBoundary((MacroSearch) (Object) this);
    }
}
