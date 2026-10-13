package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/** Bounded explicit control scopes, never a caller-stack heuristic. */
public final class AutomaticNavigationBridge {
    public record Scope(Object controller,long generation,String phase) {}
    private static final ThreadLocal<ArrayDeque<Scope>> CONTROLS=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<PathNavigation> SEGMENT_STOP=new ThreadLocal<>();
    private static final ThreadLocal<PathNavigation> INLINE_MOVEMENT=new ThreadLocal<>();
    private static final Map<Class<?>,Boolean> SUPPORTED=Collections.synchronizedMap(new WeakHashMap<>());
    private AutomaticNavigationBridge() {}

    public static Scope scope(PathNavigation navigation) {
        Scope current=CONTROLS.get().peek();
        return current==null?new Scope(navigation,0,"EXTERNAL"):current;
    }
    public static <T> T delegate(Object controller,long generation,String phase,Supplier<T> original) {
        Scope scope=new Scope(controller,generation,phase);
        CONTROLS.get().push(scope);
        boolean complete=false;
        try { T result=original.get();complete=true;return result; }
        finally {
            try {
                if(phase.equals("STOP")||!complete)SegmentedNavigationService.stopControllerOnCurrentThread(controller,generation);
            } finally {
                CONTROLS.get().pop();
                if(CONTROLS.get().isEmpty())CONTROLS.remove();
            }
        }
    }
    public static boolean enabled(PathNavigation navigation) {
        if(NativeNavigationSearch.raw()||INLINE_MOVEMENT.get()==navigation)return false;
        Mob mob=((NavigationAccess)navigation).acceleratedNavigation$mob();
        return mob.level() instanceof ServerLevel level&&level.getServer().isSameThread()
                &&mob.getNavigation()==navigation&&!mob.isRemoved()
                &&!SegmentedNavigationService.isClosed(level.getServer())&&NavigationPolicy.automaticEnabled()
                &&NavigationPolicy.modeFor(mob)!=NavigationPolicy.SchedulingMode.BYPASS
                &&SUPPORTED.computeIfAbsent(navigation.getClass(),type->supported(navigation));
    }
    private static boolean supported(PathNavigation navigation) {
        if(!(navigation instanceof GroundPathNavigation||navigation instanceof FlyingPathNavigation
                ||navigation instanceof WaterBoundPathNavigation||navigation instanceof AmphibiousPathNavigation))return false;
        try {
            Set<Class<?>> stock=Set.of(PathNavigation.class,GroundPathNavigation.class,FlyingPathNavigation.class,
                    WaterBoundPathNavigation.class,AmphibiousPathNavigation.class,WallClimberNavigation.class);
            boolean beeTick=navigation instanceof FlyingPathNavigation
                    &&navigation.getClass().getName().equals("net.minecraft.world.entity.animal.Bee$1");
            return (beeTick||stockMethod(navigation,stock,"tick","m_7638_"))
                    &&stockMethod(navigation,stock,"createPath","m_7864_",BlockPos.class,int.class)
                    &&stockMethod(navigation,stock,"createPath","m_6570_",Entity.class,int.class)
                    &&stockMethod(navigation,stock,"moveTo","m_5624_",Entity.class,double.class)
                    &&stockMethod(navigation,stock,"moveTo","m_26519_",double.class,double.class,double.class,double.class)
                    &&stockMethod(navigation,stock,"moveTo","m_26536_",Path.class,double.class)
                    &&stockMethod(navigation,stock,"stop","m_26573_")
                    &&stockMethod(navigation,stock,"recomputePath","m_26569_")
                    &&stockMethod(navigation,stock,"followThePath","m_7636_")
                    &&stockMethod(navigation,stock,"createPath","m_26551_",Set.class,int.class,boolean.class,int.class)
                    &&stockMethod(navigation,stock,"createPath","m_26548_",Set.class,int.class)
                    &&stockMethod(navigation,stock,"createPath","m_148218_",BlockPos.class,int.class,int.class)
                    &&stockMethod(navigation,stock,"createPath","m_148222_",Set.class,int.class,boolean.class,int.class,float.class);
        } catch(ReflectiveOperationException error) { return false; }
    }
    private static boolean stockMethod(PathNavigation navigation,Set<Class<?>> stock,String official,String srg,Class<?>... parameters)
            throws ReflectiveOperationException {
        // Current Forge userdev and release jars use official and SRG member names respectively.
        String name;
        try { name=PathNavigation.class.getDeclaredMethod(official,parameters).getName(); }
        catch(NoSuchMethodException release) { name=PathNavigation.class.getDeclaredMethod(srg,parameters).getName(); }
        for(Class<?> type=navigation.getClass();type!=null;type=type.getSuperclass()) {
            try { return stock.contains(type.getDeclaredMethod(name,parameters).getDeclaringClass()); }
            catch(NoSuchMethodException inherited) { /* Continue to the inherited implementation. */ }
        }
        return false;
    }
    public static SegmentedNavigationService service(PathNavigation navigation) {
        Mob mob=((NavigationAccess)navigation).acceleratedNavigation$mob();
        return SegmentedNavigationService.forServer(((ServerLevel)mob.level()).getServer());
    }
    public static boolean prepareAllowed(PathNavigation navigation) {
        if(!enabled(navigation))return false;
        Object controller=scope(navigation).controller();
        if(controller.getClass()==MoveToTargetSink.class)return true;
        Class<?> expected=controller instanceof MeleeAttackGoal?MeleeAttackGoal.class
                :controller instanceof AvoidEntityGoal?AvoidEntityGoal.class:null;
        if(expected==null)return false;
        try {
            String[] official={"canUse","start","tick"};
            String[] srg={"m_8036_","m_8056_","m_8037_"};
            for(int index=0;index<official.length;index++) {
                String name;
                try { name=net.minecraft.world.entity.ai.goal.Goal.class.getMethod(official[index]).getName(); }
                catch(NoSuchMethodException release) { name=net.minecraft.world.entity.ai.goal.Goal.class.getMethod(srg[index]).getName(); }
                if(controller.getClass().getMethod(name).getDeclaringClass()!=expected)return false;
            }
            return true;
        } catch(ReflectiveOperationException error) { return false; }
    }
    public static NavigationIntent intent(PathNavigation navigation,Set<BlockPos> targets,Entity entity,
            boolean normalize,int offset,boolean upward,int accuracy,float range) {
        NavigationAccess access=(NavigationAccess)navigation;
        try(var ignored=com.scarasol.acceleratednavigation.scheduler.NavigationScheduler
                .forServer(((ServerLevel)access.acceleratedNavigation$mob().level()).getServer()).account()) {
            return new NavigationIntent(targets,entity,normalize,offset,upward,accuracy,range,
                    access.acceleratedNavigation$multiplier(),NavigationCapabilities.capture(access.acceleratedNavigation$mob()));
        }
    }
    public static float range(PathNavigation navigation) {
        return (float)((NavigationAccess)navigation).acceleratedNavigation$mob().getAttributeValue(Attributes.FOLLOW_RANGE);
    }
    public static NavigationIntent movementIntent(PathNavigation navigation,BlockPos target,Entity entity,int accuracy) {
        boolean ground=navigation instanceof GroundPathNavigation;
        boolean blockEntity=ground||navigation instanceof FlyingPathNavigation;
        return intent(navigation,Set.of(target),entity,ground,entity==null||blockEntity?8:16,
                entity!=null&&!blockEntity,accuracy,range(navigation));
    }
    public static NavigationRequestPath prepare(PathNavigation navigation,NavigationIntent intent) {
        Scope control=scope(navigation);
        return service(navigation).prepare(navigation,intent,control.controller(),control.generation());
    }
    public static boolean move(PathNavigation navigation,NavigationIntent intent,double speed) {
        Scope control=scope(navigation);
        return service(navigation).move(navigation,intent,control.controller(),control.generation(),speed);
    }
    public static boolean movePath(PathNavigation navigation,Path path,double speed) {
        if(path instanceof NavigationRequestPath request)return service(navigation).adopt(navigation,request,speed);
        Scope control=scope(navigation);
        return service(navigation).foreignMove(navigation,path,speed,control.controller(),control.generation(),()-> {
            PathNavigation previous=INLINE_MOVEMENT.get();INLINE_MOVEMENT.set(navigation);
            try { return navigation.moveTo(path,speed); }
            finally { if(previous==null)INLINE_MOVEMENT.remove();else INLINE_MOVEMENT.set(previous); }
        });
    }
    public static boolean externalStop(PathNavigation navigation) {
        if(!enabled(navigation)||SEGMENT_STOP.get()==navigation)return false;
        Scope control=scope(navigation);
        return service(navigation).externalStop(navigation,control.controller(),control.generation(),control.phase().equals("STOP"));
    }
    public static void nativeStop(PathNavigation navigation,Runnable original) {
        PathNavigation previous=SEGMENT_STOP.get();
        SEGMENT_STOP.set(navigation);
        try {
            if(enabled(navigation))service(navigation).nativeStopped(navigation);
            original.run();
        } finally {
            if(previous==null)SEGMENT_STOP.remove();else SEGMENT_STOP.set(previous);
        }
    }
    public static boolean localDone(PathNavigation navigation,Supplier<Boolean> original) {
        if(!enabled(navigation)||service(navigation).active(navigation)==null)return original.get();
        Path path=((NavigationAccess)navigation).acceleratedNavigation$path();
        return path==null||path.isDone()||!service(navigation).executionReady(navigation);
    }
    public static boolean deferAdvance(PathNavigation navigation,Path path) {
        return enabled(navigation)&&service(navigation).deferFinalAdvance(navigation,path);
    }
}
