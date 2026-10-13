package com.scarasol.acceleratednavigation.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Unknown region cells have the same conservative block and collision surface. */
public record UnknownNavigationChunk(int height,int minBuildHeight) implements BlockGetter {
    public BlockState getBlockState(BlockPos position) { return Blocks.BEDROCK.defaultBlockState(); }
    public FluidState getFluidState(BlockPos position) { return Fluids.EMPTY.defaultFluidState(); }
    public BlockEntity getBlockEntity(BlockPos position) { return null; }
    public int getHeight() { return height; }
    public int getMinBuildHeight() { return minBuildHeight; }
}
