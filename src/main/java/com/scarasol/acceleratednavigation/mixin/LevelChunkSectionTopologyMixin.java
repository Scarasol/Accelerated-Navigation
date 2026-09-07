package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionTopologyMixin implements TopologyService.GenerationSection {
    @Unique private ChunkAccess acceleratedNavigation$owner;
    @Unique private int acceleratedNavigation$sectionY;

    @Override
    public void acceleratedNavigation$bind(ChunkAccess owner, int sectionY) {
        acceleratedNavigation$owner = owner;
        acceleratedNavigation$sectionY = sectionY;
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)"
            + "Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void acceleratedNavigation$record(int x, int y, int z, BlockState state,
            boolean checked, CallbackInfoReturnable<BlockState> callback) {
        ChunkAccess owner = acceleratedNavigation$owner;
        if (owner == null || callback.getReturnValue() == state) return;
        BlockPos position = new BlockPos(owner.getPos().getMinBlockX() + x,
                (acceleratedNavigation$sectionY << 4) + y,
                owner.getPos().getMinBlockZ() + z);
        ((TopologyService.ChunkFactsCarrier) owner).acceleratedNavigation$factsState()
                .recordGeneratedWrite(owner, position);
    }
}
