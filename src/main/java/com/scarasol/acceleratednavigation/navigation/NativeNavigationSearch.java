package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import java.util.ArrayDeque;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;

/** Uses the existing navigation's actual search and restores all temporary state. */
public final class NativeNavigationSearch {
    private static final ThreadLocal<Scope> RAW = new ThreadLocal<>();
    public record Result(Path path, boolean unavailable,NativeQueryProof proof) {}
    public static final class Scope {
        public final PathNavigation navigation;
        public final ServerLevel level;
        public final Set<BlockPos> avoided;
        public final ArrayDeque<ProfilerFiller> profilers = new ArrayDeque<>();
        public net.minecraft.world.level.PathNavigationRegion region;
        public boolean prepared, completed, unknown, entered;
        Scope(PathNavigation navigation,ServerLevel level,Set<BlockPos> avoided) {
            this.navigation=navigation;this.level=level;this.avoided=avoided;
        }
    }
    public static Scope scope() { return RAW.get(); }
    public static boolean raw() { return RAW.get()!=null; }

    public static Result find(NavigationRequestPath request,Set<BlockPos> targets,int accuracy,
            float range,Set<BlockPos> avoided) {
        return find(request,targets,accuracy,range,avoided,true);
    }

    private static Result find(NavigationRequestPath request,Set<BlockPos> targets,int accuracy,
            float range,Set<BlockPos> avoided,boolean isolate) {
        if(raw())throw new IllegalStateException("reentrant native evaluator search");
        Mob mob=request.mob;
        if(!request.intent.capabilities().physicalMatches(mob))return new Result(null,true,null);
        NavigationAccess access=(NavigationAccess)request.navigation;
        Path path=access.acceleratedNavigation$path();
        BlockPos target=access.acceleratedNavigation$target();
        int reach=access.acceleratedNavigation$accuracy();
        float multiplier=access.acceleratedNavigation$multiplier();
        var timeoutNode=access.acceleratedNavigation$timeoutNode();
        long timeoutTimer=access.acceleratedNavigation$timeoutTimer();
        double timeoutLimit=access.acceleratedNavigation$timeoutLimit();
        boolean stuck=access.acceleratedNavigation$stuck();
        NavigationRestoreState previous=NavigationRestoreState.capture(mob);
        Scope scope=new Scope(request.navigation,(ServerLevel)mob.level(),Set.copyOf(avoided));
        RAW.set(scope);
        Throwable originalFailure=null;
        try {
            request.intent.capabilities().apply(mob);
            if(isolate) {
                access.acceleratedNavigation$path(null);
                access.acceleratedNavigation$target(null);
            }
            access.acceleratedNavigation$multiplier(request.intent.visitedMultiplier());
            Path result=access.acceleratedNavigation$find(targets,request.intent.regionOffset(),
                    request.intent.upward(),accuracy,range);
            return new Result(result,scope.unknown&&(result==null||!result.canReach()),
                    scope.region==null?null:NativeQueryProof.capture(scope.level,scope.region));
        } catch(RuntimeException|Error failure) {
            originalFailure=failure;
            throw failure;
        } finally {
            Throwable cleanupFailure=null;
            try {
                if(scope.prepared&&!scope.completed) {
                    scope.completed=true;
                    request.navigation.getNodeEvaluator().done();
                }
            } catch(RuntimeException|Error error) { cleanupFailure=error; }
            while(!scope.profilers.isEmpty()) {
                try { scope.profilers.pop().pop(); }
                catch(RuntimeException|Error error) {
                    if(cleanupFailure==null)cleanupFailure=error;else cleanupFailure.addSuppressed(error);
                }
            }
            try {
                try { previous.apply(mob); }
                finally {
                    if(isolate) {
                        access.acceleratedNavigation$path(path);
                        access.acceleratedNavigation$target(target);
                        access.acceleratedNavigation$accuracy(reach);
                        access.acceleratedNavigation$timeoutNode(timeoutNode);
                        access.acceleratedNavigation$timeoutTimer(timeoutTimer);
                        access.acceleratedNavigation$timeoutLimit(timeoutLimit);
                        access.acceleratedNavigation$stuck(stuck);
                    }
                    access.acceleratedNavigation$multiplier(multiplier);
                }
            } finally {
                NeighborSafety.close(scope.region);
                RAW.remove();
            }
            if(cleanupFailure!=null) {
                if(originalFailure!=null)originalFailure.addSuppressed(cleanupFailure);
                else if(cleanupFailure instanceof Error error)throw error;
                else throw (RuntimeException)cleanupFailure;
            }
        }
    }

    public static Path legacy(PathNavigation navigation,NavigationIntent intent) {
        Mob mob=((NavigationAccess)navigation).acceleratedNavigation$mob();
        var service=SegmentedNavigationService.forServer(((ServerLevel)mob.level()).getServer());
        try(var ignored=com.scarasol.acceleratednavigation.scheduler.NavigationScheduler
                .forServer(((ServerLevel)mob.level()).getServer()).account()) {
            service.recordLegacy();
            var request=new NavigationRequestPath(navigation,mob,intent,navigation,0,((ServerLevel)mob.level()).getGameTime());
            Set<BlockPos> targets=new java.util.LinkedHashSet<>();
            try {
                for(BlockPos point:intent.currentTargets())targets.add(normalize((ServerLevel)mob.level(),point,intent.normalizeGround()));
            } catch(IllegalStateException unloaded) { return null; }
            Path path=find(request,targets,intent.accuracy(),intent.range(),Set.of(),service.owns(navigation)).path();
            service.legacyPayload(navigation,path,intent);
            return path;
        }
    }

    public static int filterNeighbors(Node[] nodes,int count) {
        Scope scope=RAW.get();
        if(scope==null||scope.avoided.isEmpty())return count;
        int accepted=0;
        for(int i=0;i<count;i++)if(!scope.avoided.contains(nodes[i].asBlockPos()))nodes[accepted++]=nodes[i];
        return accepted;
    }

    public static BlockPos normalize(ServerLevel level,BlockPos input,boolean ground) {
        if(!level.hasChunkAt(input))throw new IllegalStateException("target chunk is not loaded");
        if(!ground)return input.immutable();
        BlockPos point=input;
        if(level.getBlockState(point).isAir()) {
            BlockPos below=point.below();
            while(below.getY()>level.getMinBuildHeight()&&level.getBlockState(below).isAir())below=below.below();
            if(below.getY()>level.getMinBuildHeight())return below.above();
            while(below.getY()<level.getMaxBuildHeight()&&level.getBlockState(below).isAir())below=below.above();
            point=below;
        }
        if(!level.getBlockState(point).isSolid())return point.immutable();
        point=point.above();
        while(point.getY()<level.getMaxBuildHeight()&&level.getBlockState(point).isSolid())point=point.above();
        return point.immutable();
    }
}
