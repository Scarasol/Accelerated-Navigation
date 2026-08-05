package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyService$SnapshotSearch",
        remap = false)
public abstract class SnapshotSearchProbeMixin {

    @Inject(method = "uniform", at = @At("RETURN"))
    private static void acceleratedNavigation$recordPaletteUniform(
            int flags,
            CallbackInfoReturnable<?> callback) {
        MacroSearchMetricsProbe.recordPaletteUniform(flags);
    }
}
