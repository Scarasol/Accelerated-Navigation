package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
public abstract class NativeWalkedDistanceMixin {
    @Unique private boolean acceleratedNavigation$rawDistance;
    @Unique private float acceleratedNavigation$pendingDistance;

    @Inject(method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void beginDistance(ProfilerFiller profiler, Node start, Map<Target, BlockPos> targets,
            float range, int accuracy, float multiplier, CallbackInfoReturnable<Path> callback) {
        acceleratedNavigation$rawDistance = NativeNavigationSearch.raw();
        acceleratedNavigation$pendingDistance = 0;
    }

    @Redirect(method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/pathfinder/Node;walkedDistance:F",
                    opcode = Opcodes.PUTFIELD), require = 1, expect = 1, allow = 1)
    private void candidateDistance(Node node, float distance) {
        if (acceleratedNavigation$rawDistance) acceleratedNavigation$pendingDistance = distance;
        else node.walkedDistance = distance;
    }

    @Redirect(method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/pathfinder/Node;walkedDistance:F",
                    opcode = Opcodes.GETFIELD, ordinal = 1), require = 1, expect = 1, allow = 1)
    private float testedDistance(Node node) {
        return acceleratedNavigation$rawDistance ? acceleratedNavigation$pendingDistance : node.walkedDistance;
    }

    @Redirect(method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/pathfinder/Node;cameFrom:Lnet/minecraft/world/level/pathfinder/Node;",
                    opcode = Opcodes.PUTFIELD), require = 1, expect = 1, allow = 1)
    private void acceptDistance(Node node, Node parent) {
        // Keep the stored length attached to the accepted predecessor.
        if (acceleratedNavigation$rawDistance) node.walkedDistance = acceleratedNavigation$pendingDistance;
        node.cameFrom = parent;
    }
}
