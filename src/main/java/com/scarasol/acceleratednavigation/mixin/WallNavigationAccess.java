package com.scarasol.acceleratednavigation.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WallClimberNavigation.class)
public interface WallNavigationAccess {
    @Accessor("pathToPosition") BlockPos acceleratedNavigation$climbTarget();
    @Accessor("pathToPosition") void acceleratedNavigation$climbTarget(BlockPos target);
}
