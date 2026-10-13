package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WrappedGoal.class)
public abstract class NavigationGoalMixin {
    @Unique private long acceleratedNavigation$generation=1;
    @WrapOperation(method="canUse()Z",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/goal/Goal;canUse()Z"),
            require=1,expect=1,allow=1)
    private boolean select(Goal goal,Operation<Boolean> original) {
        return AutomaticNavigationBridge.delegate(goal,acceleratedNavigation$generation,"SELECT",()->original.call(goal));
    }
    @WrapOperation(method="canContinueToUse()Z",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/goal/Goal;canContinueToUse()Z"),
            require=1,expect=1,allow=1)
    private boolean continued(Goal goal,Operation<Boolean> original) {
        return AutomaticNavigationBridge.delegate(goal,acceleratedNavigation$generation,"CONTINUE",()->original.call(goal));
    }
    @WrapOperation(method="start()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/goal/Goal;start()V"),
            require=1,expect=1,allow=1)
    private void start(Goal goal,Operation<Void> original) {
        AutomaticNavigationBridge.delegate(goal,acceleratedNavigation$generation,"START",()->original.call(goal));
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/goal/Goal;tick()V"),
            require=1,expect=1,allow=1)
    private void tick(Goal goal,Operation<Void> original) {
        AutomaticNavigationBridge.delegate(goal,acceleratedNavigation$generation,"TICK",()->original.call(goal));
    }
    @WrapOperation(method="stop()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/goal/Goal;stop()V"),
            require=1,expect=1,allow=1)
    private void stop(Goal goal,Operation<Void> original) {
        try { AutomaticNavigationBridge.delegate(goal,acceleratedNavigation$generation,"STOP",()->original.call(goal)); }
        finally { acceleratedNavigation$generation++; }
    }
}
