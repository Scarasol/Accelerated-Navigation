package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FlyingPathNavigation.class)
public abstract class FlyingNavigationMixin {
    @Inject(method="createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void prepareEntity(Entity target,int accuracy,CallbackInfoReturnable<Path> callback) {
        FlyingPathNavigation navigation=(FlyingPathNavigation)(Object)this;
        if(AutomaticNavigationBridge.prepareAllowed(navigation))
            callback.setReturnValue(AutomaticNavigationBridge.prepare(navigation,
                    AutomaticNavigationBridge.movementIntent(navigation,target.blockPosition(),target,accuracy)));
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/ai/navigation/FlyingPathNavigation;isDone()Z"),require=2,expect=2,allow=2)
    private boolean localDone(FlyingPathNavigation navigation,Operation<Boolean> original) {
        return AutomaticNavigationBridge.localDone(navigation,()->original.call(navigation));
    }
}
