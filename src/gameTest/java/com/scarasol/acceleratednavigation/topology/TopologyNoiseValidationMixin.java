package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class TopologyNoiseValidationMixin {
    @Inject(method = "doFill", at = @At("HEAD"))
    private void entered(Blender blender, StructureManager structures, RandomState random, ChunkAccess chunk,
                         int minimum, int count, CallbackInfoReturnable<ChunkAccess> callback) { TopologyGenerationProbe.enter("Noise", chunk); }
    @Inject(method = "doFill", at = @At("RETURN"))
    private void completed(CallbackInfoReturnable<ChunkAccess> callback) { TopologyGenerationProbe.exit(); }
}
