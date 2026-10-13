package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/** Captured query flags are independent of later temporary changes by a Goal. */
public record NavigationCapabilities(float width, float height, float step, int drop, Pose pose,
        Class<?> evaluator, boolean openDoors, boolean passDoors, boolean floats,
        boolean overFences, Map<BlockPathTypes, Float> malus) {
    public NavigationCapabilities {
        malus = Map.copyOf(malus);
    }

    public static NavigationCapabilities capture(Mob mob) {
        NodeEvaluator evaluator = mob.getNavigation().getNodeEvaluator();
        EnumMap<BlockPathTypes, Float> costs = new EnumMap<>(BlockPathTypes.class);
        for (BlockPathTypes type : BlockPathTypes.values()) costs.put(type, mob.getPathfindingMalus(type));
        return new NavigationCapabilities(mob.getBbWidth(), mob.getBbHeight(), mob.getStepHeight(),
                mob.getMaxFallDistance(), mob.getPose(), evaluator.getClass(),
                evaluator.canOpenDoors(), evaluator.canPassDoors(), evaluator.canFloat(),
                evaluator.canWalkOverFences(), costs);
    }

    public boolean physicalMatches(Mob mob) {
        return width == mob.getBbWidth() && height == mob.getBbHeight()
                && step == mob.getStepHeight() && drop == mob.getMaxFallDistance()
                && pose == mob.getPose() && evaluator == mob.getNavigation().getNodeEvaluator().getClass();
    }

    public void apply(Mob mob) {
        NodeEvaluator nodes = mob.getNavigation().getNodeEvaluator();
        nodes.setCanOpenDoors(openDoors);
        nodes.setCanPassDoors(passDoors);
        nodes.setCanFloat(floats);
        nodes.setCanWalkOverFences(overFences);
        malus.forEach(mob::setPathfindingMalus);
    }

    public boolean macroSupported() {
        if (evaluator != WalkNodeEvaluator.class || overFences || !passDoors || width > 1 || height > 3) return false;
        for (var entry : malus.entrySet()) {
            if (entry.getKey() != BlockPathTypes.WATER
                    && entry.getKey() != BlockPathTypes.DAMAGE_FIRE
                    && entry.getKey() != BlockPathTypes.DAMAGE_OTHER
                    && entry.getValue() < 0 && entry.getKey().getMalus() >= 0) return false;
        }
        return true;
    }

    public BaseClusterTopology.TraversalProfile profile() {
        // WalkNodeEvaluator permits an ordinary one-block jump even with step < 1.
        return new BaseClusterTopology.TraversalProfile(width, height, Math.min(1, (int)Math.max(1, step)),
                0, Math.min(4, drop), false, openDoors);
    }
}
