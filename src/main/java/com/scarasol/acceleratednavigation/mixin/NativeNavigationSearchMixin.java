package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NeighborSafety;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PathFinder.class)
public abstract class NativeNavigationSearchMixin {
    @WrapOperation(method="findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/pathfinder/NodeEvaluator;prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V"),
            require=1,expect=1,allow=1)
    private void prepare(NodeEvaluator evaluator,PathNavigationRegion region,Mob mob,Operation<Void> original) {
        if(NativeNavigationSearch.raw()) {
            NativeNavigationSearch.scope().prepared=true;
            NativeNavigationSearch.scope().region=region;
            NeighborSafety.bind(region,NativeNavigationSearch.scope().level,evaluator);
        }
        original.call(evaluator,region,mob);
    }
    @WrapOperation(method="findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/pathfinder/NodeEvaluator;done()V"),
            require=1,expect=1,allow=1)
    private void done(NodeEvaluator evaluator,Operation<Void> original) {
        if(NativeNavigationSearch.raw())NativeNavigationSearch.scope().completed=true;
        original.call(evaluator);
    }
    @WrapOperation(method="findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/pathfinder/NodeEvaluator;getNeighbors([Lnet/minecraft/world/level/pathfinder/Node;Lnet/minecraft/world/level/pathfinder/Node;)I"),
            require=1,expect=1,allow=1)
    private int neighbors(NodeEvaluator evaluator,Node[] neighbors,Node node,Operation<Integer> original) {
        return NativeNavigationSearch.filterNeighbors(neighbors,original.call(evaluator,neighbors,node));
    }
    @WrapOperation(method="findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"),
            require=1,expect=1,allow=1)
    private void enter(ProfilerFiller profiler,String label,Operation<Void> original) {
        original.call(profiler,label);
        if(NativeNavigationSearch.raw())NativeNavigationSearch.scope().profilers.push(profiler);
    }
    @WrapOperation(method="findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"),
            require=1,expect=1,allow=1)
    private void leave(ProfilerFiller profiler,Operation<Void> original) {
        if(NativeNavigationSearch.raw())NativeNavigationSearch.scope().profilers.pop();
        original.call(profiler);
    }
}
