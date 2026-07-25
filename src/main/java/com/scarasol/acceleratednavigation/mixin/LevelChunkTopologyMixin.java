package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkTopologyMixin {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void acceleratedNavigation$captureOldGeometry(BlockPos position,
                                                           BlockState newState,
                                                           boolean moved,
                                                           CallbackInfoReturnable<BlockState> callback,
                                                           @Share("oldState") LocalRef<BlockState> oldState,
                                                           @Share("oldShape") LocalRef<VoxelShape> oldShape) {
        BlockState current = ((LevelChunk) (Object) this).getBlockState(position);
        oldState.set(current);
        oldShape.set(current.getCollisionShape(level, position));
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void acceleratedNavigation$invalidateChangedSection(BlockPos position,
                                                                 BlockState newState,
                                                                 boolean moved,
                                                                 CallbackInfoReturnable<BlockState> callback,
                                                                 @Share("oldState") LocalRef<BlockState> oldState,
                                                                 @Share("oldShape") LocalRef<VoxelShape> oldShape) {
        if (callback.getReturnValue() != null) {
            BlockState current = ((LevelChunk) (Object) this).getBlockState(position);
            TopologyService.onBlockChanged(
                    level,
                    position,
                    oldState.get(),
                    oldShape.get(),
                    current,
                    current.getCollisionShape(level, position)
            );
        }
    }
}
