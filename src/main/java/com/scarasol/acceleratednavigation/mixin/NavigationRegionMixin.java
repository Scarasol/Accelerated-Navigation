package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import com.scarasol.acceleratednavigation.navigation.UnknownNavigationChunk;
import com.scarasol.acceleratednavigation.navigation.NavigationRegionSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigationRegion.class)
public abstract class NavigationRegionMixin implements NavigationRegionSnapshot {
    @Shadow protected int centerX;
    @Shadow protected int centerZ;
    @Shadow protected ChunkAccess[][] chunks;
    public int acceleratedNavigation$centerX() { return centerX; }
    public int acceleratedNavigation$centerZ() { return centerZ; }
    public ChunkAccess[][] acceleratedNavigation$chunks() { return chunks; }
    private boolean acceleratedNavigation$unknown(int x,int z) {
        var scope=NativeNavigationSearch.scope();
        if(scope==null||scope.region!=(Object)this)return false;
        int localX=x-centerX,localZ=z-centerZ;
        if(localX>=0&&localX<chunks.length&&localZ>=0&&localZ<chunks[localX].length
                &&chunks[localX][localZ]!=null&&!(chunks[localX][localZ] instanceof EmptyLevelChunk)
                &&scope.level.getChunkSource().hasChunk(x,z))return false;
        scope.unknown=true;
        return true;
    }
    @Inject(method="getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void block(BlockPos position,CallbackInfoReturnable<BlockState> callback) {
        if(acceleratedNavigation$unknown(position.getX()>>4,position.getZ()>>4))callback.setReturnValue(Blocks.BEDROCK.defaultBlockState());
    }
    @Inject(method="getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void fluid(BlockPos position,CallbackInfoReturnable<FluidState> callback) {
        if(acceleratedNavigation$unknown(position.getX()>>4,position.getZ()>>4))callback.setReturnValue(Fluids.EMPTY.defaultFluidState());
    }
    @Inject(method="getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void collisions(int x,int z,CallbackInfoReturnable<BlockGetter> callback) {
        if(acceleratedNavigation$unknown(x,z)) {
            var level=NativeNavigationSearch.scope().level;
            callback.setReturnValue(new UnknownNavigationChunk(level.getHeight(),level.getMinBuildHeight()));
        }
    }
}
