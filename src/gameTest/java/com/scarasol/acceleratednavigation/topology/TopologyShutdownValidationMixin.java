package com.scarasol.acceleratednavigation.topology;

import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyStore.class, remap = false)
abstract class TopologyShutdownValidationMixin {
    @Inject(method = "requestSave", at = @At("RETURN"))
    private void requested(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, CallbackInfo callback) {
        TopologyFlushWindowProbe.requested(this, dimension);
    }
    @Inject(method = "scheduleFlushIfReadyLocked", at = @At(value = "INVOKE",
            target = "Lcom/scarasol/acceleratednavigation/topology/TopologyStore;enqueueLocked(Ljava/util/ArrayDeque;Ljava/lang/Runnable;)V",
            shift = At.Shift.AFTER))
    private void scheduled(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, CallbackInfo callback) {
        TopologyFlushWindowProbe.scheduled(this, dimension);
    }
    @Redirect(method = "runWorker", at = @At(value = "INVOKE", target = "Ljava/util/ArrayDeque;removeFirst()Ljava/lang/Object;"))
    private Object dequeue(java.util.ArrayDeque<?> queue) { return TopologyFlushWindowProbe.dequeue(this, queue); }
    @Inject(method = "runRequestedFlush", at = @At("HEAD"))
    private void dequeued(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, CallbackInfo callback) {
        TopologyFlushWindowProbe.point(this, dimension, "dequeued");
    }
    @Inject(method = "runRequestedFlush", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyStore;flush(Lnet/minecraft/resources/ResourceKey;)I"))
    private void beforeFlush(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, CallbackInfo callback) {
        TopologyFlushWindowProbe.point(this, dimension, "removed-before-flush");
    }
    @Inject(method = "runRequestedFlush", at = @At("RETURN"))
    private void complete(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, CallbackInfo callback) {
        TopologyFlushWindowProbe.point(this, dimension, "complete");
    }
    @Inject(method = "accept", at = @At("HEAD"))
    private void accepting(TopologyWorkerRuntime.FactDecision decision, CallbackInfoReturnable<TopologyStore.WriteReceipt> callback) {
        TopologyShutdownScenario.accepting(this, decision);
    }
    @Inject(method = "writeChunkRecord", at = @At(value = "INVOKE", target = "Ljava/io/DataOutputStream;writeInt(I)V", ordinal = 0))
    private void writing(@Coerce Object key, @Coerce Object image, CallbackInfo callback) throws IOException {
        TopologyShutdownScenario.writing(this, key);
    }
    @Redirect(method = "flushAndCloseRegions", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;flush()V", remap = true))
    private void finalFlush(net.minecraft.world.level.chunk.storage.RegionFile file) throws IOException {
        TopologyShutdownScenario.cleaning(this); file.flush();
    }
}
