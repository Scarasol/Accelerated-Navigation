package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockBehaviour.BlockStateBase.class, remap = false)
public abstract class BlockCollisionMetricsProbeMixin {

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"))
    private void acceleratedNavigation$recordCollisionShape(
            BlockGetter getter,
            BlockPos position,
            CallbackInfoReturnable<VoxelShape> callback) {
        MacroSearchMetricsProbe.recordCollisionShapeCall();
        BlockBehaviour.BlockStateBase state = (BlockBehaviour.BlockStateBase) (Object) this;
        if (state.getBlock().hasDynamicShape()
                && state.getBlock().getClass() != LiquidBlock.class) {
            MacroSearchMetricsProbe.recordCollisionFallbackCall();
        }
    }
}
