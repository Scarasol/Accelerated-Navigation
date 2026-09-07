package com.scarasol.acceleratednavigation.topology;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = TopologyWorkerRuntime.class, remap = false)
abstract class TopologyCacheEvictionValidationMixin {
    @ModifyConstant(method = "evictBaseCache", constant = @Constant(longValue = 128L * 1024L * 1024L))
    private long scopedLimit(long configured) { return TopologyValidationAccess.evictionLimit(configured); }
}
