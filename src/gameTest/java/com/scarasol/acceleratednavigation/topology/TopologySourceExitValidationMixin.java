package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.*;

import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$TopologyGraph", remap = false)
abstract class TopologySourceExitValidationMixin {
    @WrapOperation(method = "expandBoundary", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;mayExit(ILnet/minecraft/core/SectionPos;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$MovementKey;)Z"))
    private boolean mayExit(BaseClusterTopology topology, int component, SectionPos neighbor, BaseClusterTopology.MovementKey movement, Operation<Boolean> original) {
        boolean allowed = original.call(topology, component, neighbor, movement);
        TopologyMixedFailureScenario.sourceExit(this, neighbor, allowed); return allowed;
    }
}
