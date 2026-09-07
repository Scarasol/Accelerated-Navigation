package com.scarasol.acceleratednavigation.topology;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyServiceShutdownValidationMixin {
    @Inject(method = "beginStopping(Lnet/minecraft/server/MinecraftServer;)V", at = @At("HEAD"), cancellable = true)
    private static void stopping(MinecraftServer server, CallbackInfo callback) {
        if (TopologyShutdownScenario.bypassStopping(server)) callback.cancel();
    }
    @Inject(method = "finishStopping", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyService;beginStopping()V", shift = At.Shift.AFTER))
    private void finalStopping(CallbackInfo callback) { TopologyShutdownScenario.finalStopping(this); }
}
