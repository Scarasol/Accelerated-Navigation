package com.scarasol.acceleratednavigation.topology;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyStore.class, remap = false)
abstract class TopologyStoreHandoffValidationMixin {
    @Inject(method = "accept", at = @At("RETURN"))
    private void accepted(TopologyWorkerRuntime.FactDecision decision, CallbackInfoReturnable<TopologyStore.WriteReceipt> callback) {
        TopologyHandoffProbe.accepted(callback.getReturnValue());
    }
    @Inject(method = "formDelta", at = @At("HEAD"), cancellable = true)
    private void forming(TopologyStore.WriteReceipt receipt, CallbackInfo callback) {
        var decision = (TopologyWorkerRuntime.FactDecision) TopologyTestBridge.readField(receipt, "decision");
        if (TopologyHandoffProbe.pause("B4", decision, () -> TopologyHandoffProbe.resumeFormation(this, receipt), null)) callback.cancel();
    }

    @Inject(method = "continueWrite", at = @At("HEAD"), cancellable = true)
    private void writing(TopologyStore.WriteReceipt receipt, CallbackInfo callback) {
        var decision = (TopologyWorkerRuntime.FactDecision) TopologyTestBridge.readField(receipt, "decision");
        var formed = receipt.formed.getNow(null);
        if (formed != null && formed.facts() != null && TopologyHandoffProbe.pause("B6", decision,
                () -> TopologyTestBridge.invoke(this, "continueWrite", receipt), formed.facts())) callback.cancel();
    }
}
