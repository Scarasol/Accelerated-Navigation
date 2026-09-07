package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ProtoChunk.class)
abstract class ProtoChunkTopologyMixin implements TopologyService.ChunkFactsCarrier {

    @Unique
    private TopologyService.ChunkFactsState acceleratedNavigation$facts =
            new TopologyService.ChunkFactsState();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void acceleratedNavigation$bindSections(CallbackInfo callback) {
        // ImposterProtoChunk has not initialized its wrapped chunk at this point.
        if ((Object) this instanceof ImposterProtoChunk) return;
        TopologyService.bindGeneration((ProtoChunk) (Object) this);
    }

    @Override
    public TopologyService.ChunkFactsState acceleratedNavigation$factsState() {
        return acceleratedNavigation$facts;
    }

}
