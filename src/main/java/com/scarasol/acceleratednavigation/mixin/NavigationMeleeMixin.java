package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MeleeAttackGoal.class)
public abstract class NavigationMeleeMixin {
    @Shadow protected PathfinderMob mob;
    // Forge adds this field without an SRG mapping; tick and mob retain their normal mappings.
    @WrapOperation(method="tick()V",at=@At(value="FIELD",
            target="Lnet/minecraft/world/entity/ai/goal/MeleeAttackGoal;canPenalize:Z",
            opcode=Opcodes.GETFIELD,remap=false),require=1,expect=1,allow=1)
    private boolean penalize(MeleeAttackGoal goal,Operation<Boolean> original) {
        if(mob.getNavigation().getPath() instanceof NavigationRequestPath request
                &&!request.nativeBackend&&!request.terminal())return false;
        return original.call(goal);
    }
}
