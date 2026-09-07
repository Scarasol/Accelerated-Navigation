package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.TopologyFlushDiagnosticAccess;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import org.spongepowered.asm.mixin.Mixin;

/** Store control entry for the independent refresh diagnostic only. */
@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyServiceFlushDiagnosticMixin implements TopologyFlushDiagnosticAccess {

    @Override
    public TopologyStoreFlushAccess acceleratedNavigation$flushAccess() {
        return TopologyTestBridge.flushAccess(this);
    }
}
