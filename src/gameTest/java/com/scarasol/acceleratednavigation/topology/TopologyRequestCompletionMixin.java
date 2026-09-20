package com.scarasol.acceleratednavigation.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = TopologyService.MacroRequest.class, remap = false)
abstract class TopologyRequestCompletionMixin implements TopologyValidationAccess.CompletionAccess {
    @Unique private final TopologyValidationAccess.Completion acceleratedNavigation$completion = new TopologyValidationAccess.Completion();
    @Override public TopologyValidationAccess.Completion completion() { return acceleratedNavigation$completion; }
    @Unique private MinecraftServer acceleratedNavigation$server() {
        return (MinecraftServer) TopologyTestBridge.readField(TopologyTestBridge.readField(this, "this$0"), "server");
    }
    @WrapOperation(method = "completeWorker", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private boolean complete(CompletableFuture<Object> future, Object value, Operation<Boolean> original) {
        MinecraftServer server = acceleratedNavigation$server();
        return acceleratedNavigation$completion.invoke(() -> original.call(future, value), server::getTickCount, "complete");
    }
    @WrapOperation(method = "finishExceptionally", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;completeExceptionally(Ljava/lang/Throwable;)Z"))
    private boolean exception(CompletableFuture<?> future, Throwable failure, Operation<Boolean> original) {
        MinecraftServer server = acceleratedNavigation$server();
        return acceleratedNavigation$completion.invoke(() -> original.call(future, failure), server::getTickCount, "exception");
    }
    @WrapOperation(method = {"cancel", "cancelOnServer"}, at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;cancel(Z)Z"))
    private boolean cancel(CompletableFuture<?> future, boolean interrupt, Operation<Boolean> original) {
        MinecraftServer server = acceleratedNavigation$server();
        return acceleratedNavigation$completion.invoke(() -> original.call(future, interrupt), server::getTickCount, "cancel");
    }
}
