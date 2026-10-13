package com.scarasol.acceleratednavigation.navigation;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public record NavigationIntent(Set<BlockPos> targets, Entity entity, boolean normalizeGround,
        int regionOffset, boolean upward, int accuracy, float range, float visitedMultiplier,
        NavigationCapabilities capabilities) {
    public NavigationIntent {
        targets = Set.copyOf(targets);
        if (targets.isEmpty() || targets.size() > 1024 || regionOffset < 0 || accuracy < 0
                || !Float.isFinite(range) || range < 0 || !Float.isFinite(visitedMultiplier) || visitedMultiplier < 0)
            throw new IllegalArgumentException("invalid navigation request");
    }

    public Set<BlockPos> currentTargets() {
        return entity == null ? targets : Set.of(entity.blockPosition().immutable());
    }
}
