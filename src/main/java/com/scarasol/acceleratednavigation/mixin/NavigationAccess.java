package com.scarasol.acceleratednavigation.mixin;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PathNavigation.class)
public interface NavigationAccess {
    @Accessor("mob") Mob acceleratedNavigation$mob();
    @Accessor("path") Path acceleratedNavigation$path();
    @Accessor("path") void acceleratedNavigation$path(Path path);
    @Accessor("targetPos") BlockPos acceleratedNavigation$target();
    @Accessor("targetPos") void acceleratedNavigation$target(BlockPos target);
    @Accessor("reachRange") int acceleratedNavigation$accuracy();
    @Accessor("reachRange") void acceleratedNavigation$accuracy(int accuracy);
    @Accessor("maxVisitedNodesMultiplier") float acceleratedNavigation$multiplier();
    @Accessor("maxVisitedNodesMultiplier") void acceleratedNavigation$multiplier(float value);
    @Accessor("speedModifier") void acceleratedNavigation$speed(double speed);
    @Accessor("tick") int acceleratedNavigation$tick();
    @Accessor("lastStuckCheck") void acceleratedNavigation$lastCheck(int tick);
    @Accessor("lastStuckCheckPos") void acceleratedNavigation$lastPosition(Vec3 position);
    @Accessor("hasDelayedRecomputation") void acceleratedNavigation$delayed(boolean value);
    @Accessor("hasDelayedRecomputation") boolean acceleratedNavigation$delayed();
    @Accessor("timeoutCachedNode") Vec3i acceleratedNavigation$timeoutNode();
    @Accessor("timeoutCachedNode") void acceleratedNavigation$timeoutNode(Vec3i value);
    @Accessor("timeoutTimer") long acceleratedNavigation$timeoutTimer();
    @Accessor("timeoutTimer") void acceleratedNavigation$timeoutTimer(long value);
    @Accessor("timeoutLimit") double acceleratedNavigation$timeoutLimit();
    @Accessor("timeoutLimit") void acceleratedNavigation$timeoutLimit(double value);
    @Accessor("isStuck") boolean acceleratedNavigation$stuck();
    @Accessor("isStuck") void acceleratedNavigation$stuck(boolean value);
    @Invoker("trimPath") void acceleratedNavigation$trim();
    @Invoker("resetStuckTimeout") void acceleratedNavigation$resetTimeout();
    @Invoker("canUpdatePath") boolean acceleratedNavigation$canUpdate();
    @Invoker("createPath") Path acceleratedNavigation$find(Set<BlockPos> targets, int offset,
            boolean upward, int accuracy, float range);
}
