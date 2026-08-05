package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyService.class, remap = false)
public abstract class TopologyServiceBudgetProbeMixin {

    @Inject(method = "staticCellClassification", at = @At("RETURN"))
    private static void acceleratedNavigation$recordStaticClassification(
            BlockState state,
            CallbackInfoReturnable<Integer> callback) {
        MacroSearchMetricsProbe.recordStaticClassification(state, callback.getReturnValue());
    }

    @Inject(method = "queryNodeBudget", at = @At("RETURN"), cancellable = true)
    private static void acceleratedNavigation$overrideTerrainProbeBudget(
            BlockPos start,
            BlockPos goal,
            boolean hierarchical,
            CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(MacroSearchMetricsProbe.effectiveNodeBudget(
                callback.getReturnValue()
        ));
    }
}
