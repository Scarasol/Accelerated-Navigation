package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MacroSearch.class, remap = false)
abstract class MacroSearchCausalityMixin {
    @Unique private TopologyCausalityObservation.Span acceleratedNavigation$step;
    @Inject(method = "step", at = @At("HEAD"))
    private void beginStep(int budget, CallbackInfoReturnable<ResumableSearch.Status> callback) {
        acceleratedNavigation$step = TopologyCausalityObservation.begin("search", "MacroSearch.step", this);
    }
    @Inject(method = "step", at = @At("RETURN"))
    private void observeTerminal(int budget, CallbackInfoReturnable<ResumableSearch.Status> callback) {
        TopologyCausalityObservation.end(acceleratedNavigation$step, java.util.Map.of("budget", budget, "status", callback.getReturnValue()));
        acceleratedNavigation$step = null;
        TopologyCausalityObservation.searchTerminal((MacroSearch) (Object) this);
    }
}
