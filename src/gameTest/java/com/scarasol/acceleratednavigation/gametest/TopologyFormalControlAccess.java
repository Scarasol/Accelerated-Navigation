package com.scarasol.acceleratednavigation.gametest;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/** Controls used only by the formal terrain benchmark. */
public interface TopologyFormalControlAccess {

    void acceleratedNavigation$clearCompletedCorridors();

    void acceleratedNavigation$invalidateSections(ResourceKey<Level> dimension,
                                                   List<SectionPos> sections);
}
