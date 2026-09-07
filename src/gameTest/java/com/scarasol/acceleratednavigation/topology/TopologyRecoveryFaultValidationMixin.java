package com.scarasol.acceleratednavigation.topology;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyRecoveryFaultValidationMixin {
    @Inject(method = "runOneRecovery", at = @At("HEAD"), cancellable = true)
    private void queuedRecovery(CallbackInfo callback) { if (TopologyShutdownScenario.holdRecovery(this)) callback.cancel(); }
    @Inject(method = "scanSection", at = @At("HEAD"))
    private void scanning(@Coerce Object section, CallbackInfoReturnable<?> callback) {
        TopologyShutdownScenario.recoveryStage(this, section, "scan"); TopologyHandoffFaultProbe.recovery("scan", section);
    }
    @Inject(method = "scanSection", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$PackedFacts;fromCells([B)Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$PackedFacts;"))
    private void packing(@Coerce Object section, CallbackInfoReturnable<?> callback) { TopologyHandoffFaultProbe.recovery("pack", section); }
    @Inject(method = "writeFull", at = @At("HEAD"))
    private void publishing(@Coerce Object section, BaseClusterTopology.PackedFacts facts, CallbackInfo callback) {
        TopologyShutdownScenario.recoveryStage(this, section, "publish"); TopologyHandoffFaultProbe.recovery("publish", section);
    }
    @WrapOperation(method = "scanSection", at = @At(value = "INVOKE", target =
            "Lcom/scarasol/acceleratednavigation/topology/TopologyService;collisionClassification(Lnet/minecraft/world/level/block/state/BlockState;Z)I"))
    private int collision(BlockState state, boolean staticFullChecked, Operation<Integer> original,
                          @Local BlockPos.MutableBlockPos cursor) {
        TopologyHandoffFaultProbe.collision(cursor);
        return original.call(state, staticFullChecked);
    }
}
