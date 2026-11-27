package com.scarasol.acceleratednavigation.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** A query-only preview; its virtual actor is never added to a level or ticked. */
public final class NavigationPreview {
    public final CompletableFuture<NavigationRequestPath.State> future=new CompletableFuture<>();
    private Runnable cancellation;
    private Consumer<List<Vec3>> segments;

    NavigationPreview(Runnable cancellation,Consumer<List<Vec3>> segments) {
        this.cancellation=cancellation;this.segments=segments;
    }

    static Zombie virtualActor(ServerLevel level,BlockPos start,boolean doors) {
        Zombie actor=new Zombie(level);
        actor.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        actor.setOnGround(true);
        ((GroundPathNavigation)actor.getNavigation()).setCanOpenDoors(doors);
        return actor;
    }

    void publish(Path path,Mob actor,boolean last) {
        List<Vec3> nodes=new ArrayList<>();
        for(int index=0;index<path.getNodeCount();index++)nodes.add(Vec3.atBottomCenterOf(path.getNode(index).asBlockPos()));
        segments.accept(List.copyOf(nodes));
        if(!last) {
            BlockPos end=path.getEndNode().asBlockPos();
            actor.setPos(end.getX()+.5,end.getY(),end.getZ()+.5);
        }
    }

    void finish(Mob actor,NavigationRequestPath.State state) {
        actor.discard();
        cancellation=null;segments=null;
        future.complete(state);
    }

    public void cancel() {
        Runnable action=cancellation;
        if(action!=null)action.run();
    }
}
