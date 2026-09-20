package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyStore", remap = false)
abstract class TopologyStoreHandoffValidationMixin {
    @Inject(method = "accept", at = @At("RETURN"))
    private void accepted(@Coerce Object decision, CallbackInfoReturnable<?> callback) {
        TopologyHandoffProbe.accepted(callback.getReturnValue());
    }
    @Inject(method = "formDelta", at = @At("HEAD"), cancellable = true)
    private void forming(@Coerce Object receipt, CallbackInfo callback) {
        var decision = TopologyTestBridge.readField(receipt, "decision");
        if (TopologyHandoffProbe.pause("B4", decision, () -> TopologyHandoffProbe.resumeFormation(this, receipt), null)) callback.cancel();
    }

    @Inject(method = "continueWrite", at = @At("HEAD"), cancellable = true)
    private void writing(@Coerce Object receipt, CallbackInfo callback) {
        if (TopologyHandoffProbe.beforeWrite(this, receipt)) callback.cancel();
    }
}
