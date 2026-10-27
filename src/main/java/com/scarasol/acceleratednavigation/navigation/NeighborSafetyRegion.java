package com.scarasol.acceleratednavigation.navigation;

import net.minecraft.server.level.ServerLevel;

public interface NeighborSafetyRegion {
    void acceleratedNavigation$neighborLevel(ServerLevel level);
    boolean acceleratedNavigation$neighborsSafe(int x, int y, int z);
}
