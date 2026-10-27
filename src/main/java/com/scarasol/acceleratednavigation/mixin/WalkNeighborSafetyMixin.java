package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.NeighborSafetyRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNeighborSafetyMixin {
    @WrapOperation(method = "getBlockPathTypeStatic",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/WalkNodeEvaluator;checkNeighbourBlocks(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/world/level/pathfinder/BlockPathTypes;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;"),
            require = 1, expect = 1, allow = 1)
    private static BlockPathTypes neighborType(BlockGetter world, BlockPos.MutableBlockPos position,
            BlockPathTypes type, Operation<BlockPathTypes> original) {
        int x = position.getX(), y = position.getY(), z = position.getZ();
        if (world instanceof NeighborSafetyRegion region && region.acceleratedNavigation$neighborsSafe(x, y, z)) {
            position.set(x + 1, y + 1, z + 1);
            return type;
        }
        return original.call(world, position, type);
    }
}
