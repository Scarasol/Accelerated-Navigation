package com.scarasol.acceleratednavigation.topology;

import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TopologyStore.class, remap = false)
abstract class TopologyPersistenceFaultValidationMixin {
    @Inject(method = "readChunk", at = @At("HEAD"))
    private void reading(@Coerce Object key, CallbackInfoReturnable<?> callback) throws IOException { TopologyHandoffFaultProbe.read(this, key); }
    @Inject(method = "writeChunkRecord", at = @At("HEAD"))
    private void writing(@Coerce Object key, @Coerce Object image, CallbackInfo callback) throws IOException { TopologyHandoffFaultProbe.write(this, key); }
    @Inject(method = "formDelta", at = @At("RETURN"))
    private void formed(TopologyStore.WriteReceipt receipt, CallbackInfo callback) { TopologyHandoffFaultProbe.formed(receipt); }
}
