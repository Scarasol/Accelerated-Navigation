package com.scarasol.acceleratednavigation.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Objects;
import java.util.Set;

/**
 * Opt-in contract for a navigation implementation that can refine a path
 * under strict scheduler time slices without changing its search algorithm.
 * Unmodified navigations are handled only by the scheduler's soft atomic path.
 */
public interface TimeSlicedPathNavigation {

    ResumableSearch<Path> beginScheduledPathSearch(PathRequest request);

    record PathRequest(Set<BlockPos> targets,
                       int regionOffset,
                       boolean offsetUpward,
                       int accuracy,
                       float maxVisitedNodesMultiplier) {

        public PathRequest {
            targets = Set.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("targets must not be empty");
            }
            if (regionOffset < 0 || accuracy < 0) {
                throw new IllegalArgumentException("regionOffset and accuracy must be non-negative");
            }
            if (!Float.isFinite(maxVisitedNodesMultiplier) || maxVisitedNodesMultiplier <= 0.0F) {
                throw new IllegalArgumentException("maxVisitedNodesMultiplier must be finite and positive");
            }
        }
    }
}
