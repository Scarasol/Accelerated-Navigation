package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologySectionEventProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyLoadValidationMixin {
    @Inject(method = "scanSection", at = @At("HEAD"))
    private void scan(@Coerce Object section, CallbackInfoReturnable<?> callback) { TopologySectionEventProbe.scan(section); }
    @Inject(method = "loadChunk", at = @At("HEAD"), cancellable = true)
    private void loaded(ServerLevel level, ChunkAccess chunk, CallbackInfo callback) {
        TopologyGenerationProbe.loaded(level, chunk);
        if (TopologyGenerationProbe.COST) callback.cancel();
    }
}
