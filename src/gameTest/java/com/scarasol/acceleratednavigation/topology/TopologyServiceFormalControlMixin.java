package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.TopologyFormalControlAccess;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Formal benchmark controls; absent from the independent refresh run. */
@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyServiceFormalControlMixin implements TopologyFormalControlAccess {

    @Override
    public void acceleratedNavigation$clearCompletedCorridors() {
        TopologyTestBridge.formalRuntimeAccess(this).acceleratedNavigation$clearCompletedCorridors();
    }

    @Override
    public void acceleratedNavigation$invalidateSections(ResourceKey<Level> dimension,
                                                         List<SectionPos> sections) {
        TopologyTestBridge.invalidateSections(this, dimension, sections);
    }
}
