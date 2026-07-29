package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.MacroSearchMetricsProbe;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = MacroSearch.class, remap = false)
public abstract class MacroSearchMetricsProbeMixin {

    @Shadow
    @Final
    private int maxVisitedNodes;

    @Shadow
    @Final
    private Long2ObjectOpenHashMap<?> nodes;

    @Shadow
    @Final
    private BinaryHeap openSet;

    @Shadow
    @Final
    private Set<?> blockedNodes;

    @Inject(method = "metrics", at = @At("RETURN"))
    private void acceleratedNavigation$captureMetrics(
            CallbackInfoReturnable<MacroSearch.Metrics> callback) {
        MacroSearchMetricsProbe.capture(
                callback.getReturnValue(),
                maxVisitedNodes,
                nodes.size(),
                openSet.size(),
                blockedNodes.size()
        );
    }
}
