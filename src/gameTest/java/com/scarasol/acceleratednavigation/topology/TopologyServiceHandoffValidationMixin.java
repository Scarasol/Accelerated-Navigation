package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.*;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyServiceHandoffValidationMixin {
    @Inject(method = "queueFactDecision", at = @At("HEAD"), cancellable = true)
    private void offered(@Coerce Object decision, CallbackInfo callback, @Share("decision") LocalRef<Object> captured) {
        captured.set(decision);
        if (TopologyHandoffProbe.pause("B1", decision,
                () -> TopologyTestBridge.invoke(this, "queueFactDecision", decision), TopologyTestBridge.readField(decision, "facts"))) callback.cancel();
    }

    @ModifyArg(method = "queueFactDecision", at = @At(value = "INVOKE",
            target = "Lcom/scarasol/acceleratednavigation/topology/TopologyService;queuePersistence(Ljava/lang/Runnable;)Z"), index = 0)
    private Runnable queued(Runnable action, @Share("decision") LocalRef<Object> captured) {
        return TopologyHandoffProbe.queued(this, action, captured.get());
    }

    @Inject(method = "drainPersistence", at = @At("HEAD"), cancellable = true)
    private void draining(CallbackInfo callback) { if (TopologyHandoffProbe.beforeDrain(this)) callback.cancel(); }

    @Inject(method = "persistDecision", at = @At(value = "INVOKE",
            target = "Lcom/scarasol/acceleratednavigation/topology/TopologyStore;accept(Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime$FactDecision;)Lcom/scarasol/acceleratednavigation/topology/TopologyStore$WriteReceipt;"), cancellable = true)
    private void accepting(@Coerce Object decision, CallbackInfo callback, @Share("decision") LocalRef<Object> captured) {
        captured.set(decision);
        if (TopologyHandoffProbe.pause("B3", decision,
                () -> TopologyTestBridge.invoke(this, "persistDecision", decision), TopologyTestBridge.readField(decision, "facts"))) callback.cancel();
    }

    @ModifyArg(method = "persistDecision", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;whenComplete(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;"), index = 0)
    private BiConsumer<Object, Throwable> formed(BiConsumer<Object, Throwable> action,
                                                @Share("decision") LocalRef<Object> captured) {
        return TopologyHandoffProbe.formation(captured.get(), action);
    }

    @Inject(method = "completePersistence", at = @At("HEAD"), cancellable = true)
    private void completed(@Coerce Object decision, boolean formed, boolean written, CallbackInfo callback) {
        if (TopologyHandoffProbe.completion(this, decision, formed, written)) callback.cancel();
    }
}
