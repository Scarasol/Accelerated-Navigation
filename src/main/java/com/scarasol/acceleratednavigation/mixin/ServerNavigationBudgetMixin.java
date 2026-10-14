package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public abstract class ServerNavigationBudgetMixin {
    @WrapOperation(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"),
            require = 1, expect = 1, allow = 1)
    private void navigationTick(MinecraftServer server, BooleanSupplier haveTime, Operation<Void> original) {
        NavigationScheduler scheduler = NavigationScheduler.beginTickEnvelope(server, haveTime);
        boolean completedNormally = false;
        try {
            original.call(server, haveTime);
            completedNormally = true;
        } finally {
            scheduler.finishTickEnvelope(completedNormally);
        }
    }
}
