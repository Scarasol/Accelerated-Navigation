package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Path;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MoveToTargetSink.class)
public abstract class NavigationSinkMixin {
    @Shadow private Path path;
    @Shadow protected abstract boolean reachedTarget(Mob mob,WalkTarget target);
    @Unique private boolean acceleratedNavigation$unavailable;
    @Inject(method="tryComputePath(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z",
            at=@At(value="FIELD",target="Lnet/minecraft/world/entity/ai/behavior/MoveToTargetSink;speedModifier:F",
                    opcode=Opcodes.PUTFIELD,shift=At.Shift.AFTER),cancellable=true,require=1,expect=1,allow=1)
    private void ready(Mob mob,WalkTarget target,long time,CallbackInfoReturnable<Boolean> callback) {
        acceleratedNavigation$unavailable=false;
        if(!AutomaticNavigationBridge.prepareAllowed(mob.getNavigation())||reachedTarget(mob,target))return;
        if(path==null) {
            acceleratedNavigation$unavailable=true;callback.setReturnValue(false);
        } else if(path instanceof NavigationRequestPath request&&!request.terminal()) {
            callback.setReturnValue(true);
        }
    }
    @Inject(method="checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/Brain;eraseMemory(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)V",
                    ordinal=0),cancellable=true,require=1,expect=1,allow=1)
    private void preserveTarget(ServerLevel level,Mob mob,CallbackInfoReturnable<Boolean> callback,@Local WalkTarget target) {
        if(acceleratedNavigation$unavailable&&!reachedTarget(mob,target))callback.setReturnValue(false);
    }
    @Inject(method="canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)Z",
            at=@At("HEAD"),require=1,expect=1,allow=1)
    private void continued(ServerLevel level,Mob mob,long time,CallbackInfoReturnable<Boolean> callback) {
        acceleratedNavigation$decision(mob,time);
    }
    @Inject(method="tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)V",
            at=@At("HEAD"),require=1,expect=1,allow=1)
    private void tick(ServerLevel level,Mob mob,long time,CallbackInfo callback) {
        acceleratedNavigation$decision(mob,time);
    }
    @Unique private void acceleratedNavigation$decision(Mob mob,long time) {
        if(!(path instanceof NavigationRequestPath request))return;
        if(request.state==NavigationRequestPath.State.REACHABLE&&request.canReach())
            mob.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        else if(request.state==NavigationRequestPath.State.UNREACHABLE||request.state==NavigationRequestPath.State.FAILED
                ||request.nativeBackend&&request.state==NavigationRequestPath.State.REACHABLE&&!request.nativeReachable)
            if(!mob.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE))
                mob.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,time);
    }
}
