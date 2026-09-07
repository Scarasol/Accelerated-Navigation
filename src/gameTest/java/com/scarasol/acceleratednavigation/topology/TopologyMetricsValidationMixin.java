package com.scarasol.acceleratednavigation.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import com.scarasol.acceleratednavigation.topology.TopologyService;

@Mixin(value = {TopologyService.class, TopologyService.MacroRequest.class}, targets = {
        "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime",
        "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroRequest",
        "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroQuery",
        "com.scarasol.acceleratednavigation.topology.TopologyStore",
        "com.scarasol.acceleratednavigation.topology.TopologyTaskExecutor"}, remap = false)
abstract class TopologyMetricsValidationMixin { }
