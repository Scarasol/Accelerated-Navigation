package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
abstract class TopologyGenerationSerializationMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void written(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        TopologyGenerationProbe.serialized(level, chunk, "write", callback.getReturnValue());
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void read(ServerLevel level, PoiManager poi, ChunkPos position, CompoundTag tag,
                             CallbackInfoReturnable<ProtoChunk> callback) {
        TopologyGenerationProbe.serialized(level, callback.getReturnValue(), "read", tag);
        com.scarasol.acceleratednavigation.topology.TopologySeededChunkProbe.read(level, callback.getReturnValue(), tag);
    }
}
