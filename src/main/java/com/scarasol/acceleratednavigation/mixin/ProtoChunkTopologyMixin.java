package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProtoChunk.class)
abstract class ProtoChunkTopologyMixin implements TopologyService.ChunkFactsCarrier {

    @Unique
    private TopologyService.ChunkFactsState acceleratedNavigation$facts =
            new TopologyService.ChunkFactsState();

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void acceleratedNavigation$recordGeneratedFacts(
            BlockPos position,
            BlockState state,
            boolean moved,
            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() == null
                || !acceleratedNavigation$facts.recordsGeneration()) {
            return;
        }
        acceleratedNavigation$facts.recordGeneratedColumn(
                position,
                TopologyService.sampleColumnFacts((ProtoChunk) (Object) this, position));
    }

    @Override
    public TopologyService.ChunkFactsState acceleratedNavigation$factsState() {
        return acceleratedNavigation$facts;
    }

}
