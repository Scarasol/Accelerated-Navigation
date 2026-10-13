package com.scarasol.acceleratednavigation.navigation;

import net.minecraft.world.level.chunk.ChunkAccess;

public interface NavigationRegionSnapshot {
    int acceleratedNavigation$centerX();
    int acceleratedNavigation$centerZ();
    ChunkAccess[][] acceleratedNavigation$chunks();
}
