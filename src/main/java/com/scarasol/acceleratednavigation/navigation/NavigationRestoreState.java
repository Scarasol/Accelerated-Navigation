package com.scarasol.acceleratednavigation.navigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

final class NavigationRestoreState {
    private static final BlockPathTypes[] TYPES = BlockPathTypes.values();
    private final float[] malus;
    private final boolean openDoors, passDoors, floats, overFences;

    private NavigationRestoreState(NodeEvaluator evaluator, float[] malus) {
        this.malus = malus;
        openDoors = evaluator.canOpenDoors();
        passDoors = evaluator.canPassDoors();
        floats = evaluator.canFloat();
        overFences = evaluator.canWalkOverFences();
    }

    static NavigationRestoreState capture(Mob mob) {
        NodeEvaluator evaluator = mob.getNavigation().getNodeEvaluator();
        float[] malus = new float[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) malus[i] = mob.getPathfindingMalus(TYPES[i]);
        return new NavigationRestoreState(evaluator, malus);
    }

    void apply(Mob mob) {
        NodeEvaluator evaluator = mob.getNavigation().getNodeEvaluator();
        evaluator.setCanOpenDoors(openDoors);
        evaluator.setCanPassDoors(passDoors);
        evaluator.setCanFloat(floats);
        evaluator.setCanWalkOverFences(overFences);
        for (int i = 0; i < TYPES.length; i++) mob.setPathfindingMalus(TYPES[i], malus[i]);
    }
}
