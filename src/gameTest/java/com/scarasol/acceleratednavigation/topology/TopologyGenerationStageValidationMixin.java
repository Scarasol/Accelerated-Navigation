package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatus.class)
abstract class TopologyGenerationStageValidationMixin {
    @Inject(method = "generate", at = @At("HEAD"))
    private void entered(CallbackInfoReturnable<CompletableFuture<?>> callback) { TopologyGenerationProbe.beginStage(); }
    @Inject(method = "generate", at = @At("RETURN"))
    private void returned(CallbackInfoReturnable<CompletableFuture<?>> callback) {
        if (TopologyGenerationProbe.COST) callback.getReturnValue().whenComplete((result, failure) -> TopologyGenerationProbe.endStage());
    }
}
