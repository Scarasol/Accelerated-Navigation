package com.scarasol.acceleratednavigation.navigation;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.apache.commons.lang3.mutable.MutableLong;

/** The saved batch resumes at the original AcquirePoi business branch. */
public final class NavigationPoiAdapter {
    private static final Map<MutableLong,Map<PathfinderMob,Batch>> BATCHES=new IdentityHashMap<>();
    public static final class Batch {
        final MutableLong clock;
        final PathfinderMob mob;
        final SegmentedNavigationService service;
        public final Set<Pair<Holder<PoiType>,BlockPos>> candidates;
        public final NavigationRequestPath request;
        public boolean resume;
        Batch(MutableLong clock,PathfinderMob mob,Set<Pair<Holder<PoiType>,BlockPos>> candidates,
                SegmentedNavigationService service,NavigationRequestPath request) {
            this.clock=clock;this.mob=mob;this.candidates=Set.copyOf(candidates);
            this.service=service;this.request=request;
        }
    }
    private NavigationPoiAdapter() {}
    public static Batch get(MutableLong clock,PathfinderMob mob) {
        Map<PathfinderMob,Batch> byMob=BATCHES.get(clock);
        return byMob==null?null:byMob.get(mob);
    }
    public static boolean beforeRetry(MutableLong clock,PathfinderMob mob) {
        Batch batch=get(clock,mob);
        if(batch==null)return false;
        if(!AutomaticNavigationBridge.enabled(mob.getNavigation())) { release(batch);return true; }
        var state=batch.request.state;
        if(state==NavigationRequestPath.State.PENDING)return true;
        if((state!=NavigationRequestPath.State.REACHABLE&&state!=NavigationRequestPath.State.UNREACHABLE)
                ||!batch.service.decisionCurrent(batch.request)) { release(batch);return true; }
        batch.resume=true;
        return false;
    }
    public static boolean beforeQuery(MutableLong clock,PathfinderMob mob,Set<Pair<Holder<PoiType>,BlockPos>> candidates) {
        Batch old=get(clock,mob);
        if(old!=null&&old.resume)return false;
        if(candidates.isEmpty()||!AutomaticNavigationBridge.enabled(mob.getNavigation()))return false;
        var navigation=mob.getNavigation();
        var service=AutomaticNavigationBridge.service(navigation);
        int accuracy=candidates.stream().mapToInt(pair->pair.getFirst().value().validRange()).max().orElse(1);
        var intent=AutomaticNavigationBridge.intent(navigation,
                candidates.stream().map(Pair::getSecond).collect(Collectors.toSet()),null,false,8,false,
                Math.max(1,accuracy),AutomaticNavigationBridge.range(navigation));
        NavigationRequestPath request=service.prepare(navigation,intent,clock,0);
        if(request==null)return true;
        Batch batch=new Batch(clock,mob,candidates,service,request);
        BATCHES.computeIfAbsent(clock,key->new IdentityHashMap<>()).put(mob,batch);
        return true;
    }
    public static boolean validSelection(Batch batch,PoiManager manager,java.util.function.Predicate<Holder<PoiType>> types) {
        if(!batch.service.decisionCurrent(batch.request))return false;
        if(batch.request.state==NavigationRequestPath.State.UNREACHABLE)return true;
        BlockPos selected=batch.request.getTarget();
        return batch.candidates.stream().anyMatch(pair->pair.getSecond().equals(selected)
                &&manager.getType(selected).filter(holder->holder.equals(pair.getFirst())&&types.test(holder)).isPresent())
                &&manager.findAllClosestFirstWithType(types,selected::equals,selected,1,PoiManager.Occupancy.HAS_SPACE)
                    .findAny().isPresent();
    }
    public static void afterBusiness(MutableLong clock,PathfinderMob mob) {
        Batch batch=get(clock,mob);
        if(batch!=null&&batch.resume)release(batch);
    }
    private static void release(Batch batch) {
        batch.service.consume(batch.request);
        Map<PathfinderMob,Batch> byMob=BATCHES.get(batch.clock);
        if(byMob!=null) { byMob.remove(batch.mob);if(byMob.isEmpty())BATCHES.remove(batch.clock); }
    }
    public static void sweep(MinecraftServer server,boolean stopping) {
        for(var byMob:new ArrayList<>(BATCHES.values()))for(Batch batch:new ArrayList<>(byMob.values())) {
            if(!(batch.mob.level() instanceof ServerLevel level)||level.getServer()!=server)continue;
            var state=batch.request.state;
            if(stopping||batch.mob.isRemoved()||level.getGameTime()-batch.request.createdTick>200
                    ||state==NavigationRequestPath.State.CANCELLED||state==NavigationRequestPath.State.UNAVAILABLE)
                release(batch);
        }
    }
}
