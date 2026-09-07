package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$TopologyGraph", remap = false)
abstract class TopologySourceExitValidationMixin {
    @Redirect(method = "expandBoundary", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;mayExit(ILnet/minecraft/core/SectionPos;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$MovementKey;)Z"))
    private boolean mayExit(BaseClusterTopology topology, int component, SectionPos neighbor, BaseClusterTopology.MovementKey movement) {
        boolean allowed = topology.mayExit(component, neighbor, movement);
        TopologyMixedFailureScenario.sourceExit(this, neighbor, allowed); return allowed;
    }
}
