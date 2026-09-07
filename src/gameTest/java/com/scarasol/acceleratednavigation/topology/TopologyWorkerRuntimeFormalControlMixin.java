package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyRuntimeFormalControlAccess;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import com.scarasol.acceleratednavigation.topology.TopologyPrewarmScenario;
import org.spongepowered.asm.mixin.Mixin;

/** Formal-only cache control used before each timed request. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap = false)
abstract class TopologyWorkerRuntimeFormalControlMixin
        implements TopologyRuntimeFormalControlAccess {

    @Override
    public void acceleratedNavigation$clearCompletedCorridors() {
        TopologyValidationAccess.clearCompletedCorridors(this);
    }

    @Override
    public boolean acceleratedNavigation$allowsPrewarm() {
        return TopologyPrewarmScenario.allows(this);
    }
}
