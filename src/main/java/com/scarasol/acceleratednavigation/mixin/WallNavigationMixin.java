package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WallClimberNavigation.class)
public abstract class WallNavigationMixin {
    @Inject(method="moveTo(Lnet/minecraft/world/entity/Entity;D)Z",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void moveEntity(Entity entity,double speed,CallbackInfoReturnable<Boolean> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation))callback.setReturnValue(AutomaticNavigationBridge.move(navigation,
                AutomaticNavigationBridge.movementIntent(navigation,entity.blockPosition(),entity,0),speed));
    }
    @Inject(method="createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void block(BlockPos target,int accuracy,CallbackInfoReturnable<Path> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)) {
            var intent=AutomaticNavigationBridge.movementIntent(navigation,target,null,accuracy);
            callback.setReturnValue(AutomaticNavigationBridge.prepareAllowed(navigation)
                    ?AutomaticNavigationBridge.prepare(navigation,intent):NativeNavigationSearch.legacy(navigation,intent));
        }
    }
    @Inject(method="createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void entity(Entity target,int accuracy,CallbackInfoReturnable<Path> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)) {
            var intent=AutomaticNavigationBridge.movementIntent(navigation,target.blockPosition(),target,accuracy);
            callback.setReturnValue(AutomaticNavigationBridge.prepareAllowed(navigation)
                    ?AutomaticNavigationBridge.prepare(navigation,intent):NativeNavigationSearch.legacy(navigation,intent));
        }
    }
    @Inject(method="tick()V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void waiting(CallbackInfo callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)) {
            NavigationRequestPath request=AutomaticNavigationBridge.service(navigation).active(navigation);
            if(request!=null&&request.state==NavigationRequestPath.State.PENDING)callback.cancel();
        }
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/navigation/WallClimberNavigation;isDone()Z"),
            require=1,expect=1,allow=1)
    private boolean local(WallClimberNavigation navigation,Operation<Boolean> original) {
        return AutomaticNavigationBridge.localDone(navigation,()->original.call(navigation));
    }
}
