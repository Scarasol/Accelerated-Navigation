package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GroundPathNavigation.class)
public abstract class GroundNavigationMixin {
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
}
