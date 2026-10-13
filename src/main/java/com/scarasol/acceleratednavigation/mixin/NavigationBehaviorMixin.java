package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Behavior.class)
public abstract class NavigationBehaviorMixin {
    @Unique private long acceleratedNavigation$generation=1;
    @WrapOperation(method="tryStart(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/Behavior;checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)Z"),
            require=1,expect=1,allow=1)
    private boolean select(Behavior<?> behavior,ServerLevel level,LivingEntity entity,Operation<Boolean> original) {
        return AutomaticNavigationBridge.delegate(behavior,acceleratedNavigation$generation,"SELECT",()->original.call(behavior,level,entity));
    }
    @WrapOperation(method="tryStart(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/Behavior;start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V"),
            require=1,expect=1,allow=1)
    private void start(Behavior<?> behavior,ServerLevel level,LivingEntity entity,long time,Operation<Void> original) {
        AutomaticNavigationBridge.delegate(behavior,acceleratedNavigation$generation,"START",()->original.call(behavior,level,entity,time));
    }
    @WrapOperation(method="tickOrStop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/Behavior;canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z"),
            require=1,expect=1,allow=1)
    private boolean continued(Behavior<?> behavior,ServerLevel level,LivingEntity entity,long time,Operation<Boolean> original) {
        return AutomaticNavigationBridge.delegate(behavior,acceleratedNavigation$generation,"CONTINUE",()->original.call(behavior,level,entity,time));
    }
    @WrapOperation(method="tickOrStop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/Behavior;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V"),
            require=1,expect=1,allow=1)
    private void tick(Behavior<?> behavior,ServerLevel level,LivingEntity entity,long time,Operation<Void> original) {
        AutomaticNavigationBridge.delegate(behavior,acceleratedNavigation$generation,"TICK",()->original.call(behavior,level,entity,time));
    }
    @WrapOperation(method="doStop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/Behavior;stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V"),
            require=1,expect=1,allow=1)
    private void stop(Behavior<?> behavior,ServerLevel level,LivingEntity entity,long time,Operation<Void> original) {
        try { AutomaticNavigationBridge.delegate(behavior,acceleratedNavigation$generation,"STOP",()->original.call(behavior,level,entity,time)); }
        finally { acceleratedNavigation$generation++; }
    }
}
