package an.production;

import an.trace.Trace;
import com.google.common.collect.ImmutableList;
import com.google.gson.*;
import com.scarasol.acceleratednavigation.navigation.*;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.memory.*;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;

/** Controlled entry census, not an estimate of an arbitrary server's traffic mix. */
public final class CoverageScenarioProbe {
    private record Scene(String name,int distance,int count,String kind) {}
    private static final List<String> NAMES=List.of("entity-stationary","entity-drift","ai-goalselector",
            "brain-walk-target","query-only-multi","query-ready-adopt-multi","query-pending-adopt-multi",
            "shared-cold1","shared-warm1","shared-accuracy0","legacy-createpath-move","poi-acquire","dynamic-block","native-stuck-recovery",
            "shared-accuracy1","shared-boundary1","shared-neighbor1","shared-obstacle1",
            "shared-drift1","shared-cancel1","shared-replace1","shared-range4","shared-range12",
            "shared-dynamic1","shared-pending-cancel1","shared-pending-replace1","shared-timeout1",
            "shared-low-budget0","shared-anchor-drift0",
            "shared-query-only-multi","shared-query-ready-adopt-multi","shared-query-pending-adopt-multi");
    public static final class ProbeHusk extends Husk {
        boolean frozen;
        ProbeHusk(ServerLevel level) {
            super(EntityType.HUSK,level);setBaby(false);setPersistenceRequired();setInvulnerable(true);
        }
        @Override protected void registerGoals() {}
        void melee() { goalSelector.addGoal(1,new MeleeAttackGoal(this,1,true)); }
        @Override public void travel(Vec3 input) {
            if(frozen) { setDeltaMovement(Vec3.ZERO);return; }
            super.travel(input);
        }
        @Override protected boolean shouldDespawnInPeaceful() { return false; }
        @Override protected boolean convertsInWater() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
    }
    public static final class ProbeVillager extends Villager {
        ProbeVillager(ServerLevel level) { super(EntityType.VILLAGER,level);setPersistenceRequired();setInvulnerable(true); }
        @Override protected void registerGoals() {}
        @Override protected void customServerAiStep() { getBrain().tick((ServerLevel)level(),this); }
        @Override public boolean shouldBeSaved() { return false; }
    }
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final List<Scene> scenes=new ArrayList<>();
    private final JsonArray rows=new JsonArray();
    private final List<NavigationRequestPath> requests=new ArrayList<>();
    private final Map<NavigationRequestPath,Long> firstSeen=new IdentityHashMap<>();
    private final List<BlockPos> changed=new ArrayList<>();
    private final Map<String,BlockPos> poiSites=new LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private Method metrics;
    private Mob mob,target;
    private BlockPos start,goal;
    private Vec3 initial;
    private Path query;
    private Object controller;
    private NavigationRequestPath latest;
    private PerformanceSearchTelemetry.Stats searches;
    private PublicationTelemetry.Stamp publication;
    private JsonArray samples,observations,actions;
    private Measurements.Snapshot before,after;
    private Object topologyBefore,topologyAfter;
    private Object sharedRuntimeBefore,threadCostsBefore;
    private Object sharedHooks,previousSharedHook;
    private Field sharedHookField;
    private int index=-1,tick,prepareTick,submitTick,terminalTick,drifts;
    private long started,ready,submitted,submitEnd,closed,firstMove,arrival,intentArrival,terminal,nextSample,queryReady,adoptedAt;
    private boolean closing,done,accepted,submittedScene,actionTaken;
    private long sharedRequestsBefore,sharedSegmentsBefore,sharedFallbacksBefore;
    private String state="PENDING",failure="",lastObservation="";

    CoverageScenarioProbe() {
        for(String name:NAMES)scenes.add(new Scene(name,name.startsWith("shared-")
                ?name.equals("shared-boundary1")?11:8:name.equals("entity-drift")?48:28,1,name));
        String filter=System.getProperty("an.trace.scene","");
        if(!filter.isEmpty()) {
            Set<String> selected=new HashSet<>(Arrays.asList(filter.split(",")));
            if(!NAMES.containsAll(selected))throw new IllegalArgumentException("Unknown coverage scene");
            scenes.removeIf(scene->!selected.contains(scene.name));
        }
        if(Boolean.getBoolean("an.production.performanceVanilla"))throw new IllegalArgumentException("Coverage requires production adapter");
        MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof Mob && !(event.getEntity() instanceof ProbeHusk)
                && !(event.getEntity() instanceof ProbeVillager))event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        try {
            started=System.nanoTime();server=event.getServer();level=server.overworld();
            Field bypass=NavigationPolicy.class.getDeclaredField("bypassEntityIds");bypass.setAccessible(true);
            NavigationPolicy.reloadBypassIds();bypass.set(null,Set.of());
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);level.setDayTime(6000);
            Method load=PerformanceComparisonProbe.class.getDeclaredMethod("loadArena",ServerLevel.class);
            load.setAccessible(true);load.invoke(null,level);
            service=SegmentedNavigationService.forServer(server);
            metrics=TopologyService.class.getDeclaredMethod("metrics");metrics.setAccessible(true);
            System.out.println("TIMELINE_SERVER_READY "+scenes.size());
        } catch(Throwable error) {abort(error);}
    }
    private String kind() {return scenes.get(index).kind;}
    private static Object threadCosts() {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        var costs=new ArrayList<Object>();
        for(long id:bean.getAllThreadIds()) {
            var info=bean.getThreadInfo(id);if(info==null)continue;
            costs.add(Map.of("id",id,"name",info.getThreadName(),
                    "cpuNanos",bean.getThreadCpuTime(id),"allocatedBytes",bean.getThreadAllocatedBytes(id)));
        }
        return Map.of("nanoTime",System.nanoTime(),"threads",costs);
    }
    private <T extends Mob> T spawn(T actor,BlockPos at) {
        actor.setPos(at.getX()+.5,at.getY()+.01,at.getZ()+.5);
        if(!level.addFreshEntity(actor))throw new IllegalStateException("Actor rejected");
        return actor;
    }
    private static Object optionalField(Object object,String name) {
        try {
            Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
        } catch(NoSuchFieldException absent) {return null;}
        catch(IllegalAccessException error) {throw new IllegalStateException(error);}
    }
    private static int nativeVisitLimit(Object navigation) {
        try {
            Object finder=null;
            for(Class<?> type=navigation.getClass();type!=null;type=type.getSuperclass())
                for(Field field:type.getDeclaredFields())if(field.getType()==net.minecraft.world.level.pathfinder.PathFinder.class) {
                    field.setAccessible(true);finder=field.get(navigation);
                }
            if(finder==null)throw new IllegalStateException("Missing actual PathFinder");
            List<Field> limits=Arrays.stream(finder.getClass().getDeclaredFields())
                    .filter(field->field.getType()==int.class).toList();
            if(limits.size()!=1)throw new IllegalStateException("Ambiguous native visit limit");
            Field limit=limits.get(0);limit.setAccessible(true);
            int value=limit.getInt(finder);
            if(value<16)throw new IllegalStateException("Unexpected native visit limit");
            return value;
        } catch(IllegalAccessException error) {throw new IllegalStateException(error);}
    }
    private void prepare() {
        index++;
        if(index==scenes.size()) {done=true;stop();return;}
        requests.clear();firstSeen.clear();changed.clear();poiSites.clear();
        samples=new JsonArray();observations=new JsonArray();actions=new JsonArray();
        searches=new PerformanceSearchTelemetry.Stats();publication=new PublicationTelemetry.Stamp();
        query=null;latest=null;controller=null;target=null;
        submittedScene=accepted=actionTaken=false;drifts=0;
        ready=submitted=submitEnd=closed=firstMove=arrival=intentArrival=terminal=queryReady=adoptedAt=0;
        terminalTick=0;state="PENDING";lastObservation="";
        start=new BlockPos(kind().equals("shared-anchor-drift0")?-124:-156,81,-159);
        goal=start.offset(scenes.get(index).distance,0,0);
        if(kind().startsWith("shared-"))goal=start.offset(kind().equals("shared-boundary1")?11:8,0,0);
        mob=spawn(kind().equals("brain-walk-target")||kind().equals("poi-acquire")
                ?new ProbeVillager(level):new ProbeHusk(level),start);
        if(mob instanceof Villager villager) {
            villager.getBrain().removeAllBehaviors();
            villager.getBrain().setCoreActivities(Set.of(Activity.CORE));
            villager.getBrain().setActiveActivityIfPossible(Activity.CORE);
        }
        initial=mob.position();
        if(kind().startsWith("entity-")||kind().equals("ai-goalselector")||kind().equals("shared-drift1")
                ||kind().equals("shared-anchor-drift0")) {
            target=spawn(new ProbeHusk(level),goal);target.setNoAi(true);
            if(kind().equals("shared-anchor-drift0"))
                target.setPos(goal.getX()+.9,target.getY(),target.getZ());
        }
        if(kind().equals("shared-neighbor1")||kind().equals("shared-obstacle1")) {
            BlockPos obstacle=kind().equals("shared-neighbor1")?goal:start.offset(4,0,0);
            for(int y=0;y<3;y++) {
                BlockPos point=obstacle.above(y);level.setBlock(point,Blocks.STONE.defaultBlockState(),3);changed.add(point);
            }
        }
        if(kind().equals("poi-acquire")) {
            var holder=level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE).getHolderOrThrow(PoiTypes.FISHERMAN);
            for(int delta:new int[]{16,28}) {
                BlockPos pos=start.offset(delta,0,0);poiSites.put("site"+delta,pos);
                level.setBlock(pos,Blocks.BARREL.defaultBlockState(),2);
                level.getPoiManager().remove(pos);level.getPoiManager().add(pos,holder);changed.add(pos);
            }
        }
        PerformanceSearchTelemetry.ACTORS.put(mob,searches);
        PublicationTelemetry.ACTORS.put(mob.getNavigation(),publication);
        if(kind().equals("shared-timeout1")||kind().equals("shared-anchor-drift0")) {
            try {
                sharedHooks=Trace.field(Trace.field(TopologyService.forServer(server),"runtime"),"shared");
                sharedHookField=sharedHooks.getClass().getDeclaredField("hook");sharedHookField.setAccessible(true);
                previousSharedHook=sharedHookField.get(sharedHooks);
                var once=new java.util.concurrent.atomic.AtomicBoolean();
                sharedHookField.set(sharedHooks,Proxy.newProxyInstance(sharedHookField.getType().getClassLoader(),
                        new Class<?>[]{sharedHookField.getType()},(proxy,method,args)-> {
                            if(method.getName().equals("enter")&&args[0].equals("before-query")&&once.compareAndSet(false,true)) {
                                if(kind().equals("shared-timeout1"))
                                    throw new java.util.concurrent.TimeoutException("controlled shared query timeout");
                                var moved=new java.util.concurrent.CompletableFuture<Void>();
                                server.execute(()-> {
                                    target.setPos(target.getX()+1,target.getY(),target.getZ());
                                    goal=target.blockPosition();
                                    action("target-moved-before-shared-result",Map.of("target",goal.toString(),
                                            "position",List.of(target.getX(),target.getY(),target.getZ())));
                                    moved.complete(null);
                                });
                                moved.get(5,java.util.concurrent.TimeUnit.SECONDS);
                            }
                            return null;
                        }));
            } catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
        }
        prepareTick=tick;System.out.println("TIMELINE_PREPARE "+index+" "+kind());
    }
    private void action(String name,Object detail) {
        JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("nanoTime",System.nanoTime());
        row.addProperty("tick",server.getTickCount());row.add("detail",gson.toJsonTree(detail));actions.add(row);
    }
    private void add(NavigationRequestPath request) {
        if(!requests.contains(request)) {
            requests.add(request);firstSeen.put(request,System.nanoTime());
            Trace.bind(mob.getNavigation(),request,mob);
        }
        latest=request;
    }
    private void track() {
        NavigationRequestPath current=service.active(mob.getNavigation());
        if(current!=null)add(current);
        Map<?,?> records=(Map<?,?>)Trace.field(service,"records");
        for(Object entry:records.keySet())if(entry instanceof NavigationRequestPath request&&request.mob==mob)add(request);
    }
    private void submit() {
        var nav=mob.getNavigation();
        if(!AutomaticNavigationBridge.enabled(nav))throw new IllegalStateException("Wrong backend");
        switch(kind()) {
            case "shared-low-budget0" -> {
                int limit=nativeVisitLimit(nav);
                float multiplier=6.25f/limit;
                action("controlled-native-visit-limit",Map.of("maxVisitedNodes",limit,"visitedMultiplier",multiplier));
                NavigationIntent intent=new NavigationIntent(Set.of(goal),null,false,8,false,0,35,multiplier,
                        NavigationCapabilities.capture(mob));
                accepted=service.move(nav,intent,this,0,1);
            }
            case "shared-anchor-drift0" -> {
                NavigationIntent intent=new NavigationIntent(Set.of(goal),target,false,8,false,0,35,1,
                        NavigationCapabilities.capture(mob));
                accepted=service.move(nav,intent,this,0,1);
            }
            case "entity-stationary","entity-drift","shared-drift1" -> accepted=nav.moveTo(target,1);
            case "shared-neighbor1" -> {
                NavigationIntent intent=new NavigationIntent(Set.of(goal),null,false,8,false,1,35,1,
                        NavigationCapabilities.capture(mob));
                accepted=service.move(nav,intent,this,0,1);
            }
            case "shared-range4","shared-range12" -> {
                int range=kind().equals("shared-range4")?4:12;
                NavigationIntent intent=AutomaticNavigationBridge.intent(nav,Set.of(goal),null,true,8,false,1,range);
                accepted=AutomaticNavigationBridge.move(nav,intent,1);
            }
            case "ai-goalselector" -> {
                ((ProbeHusk)mob).melee();mob.setTarget((net.minecraft.world.entity.LivingEntity)target);accepted=true;
            }
            case "brain-walk-target" -> {
                var villager=(Villager)mob;var sink=new MoveToTargetSink(150,250);
                villager.getBrain().addActivity(Activity.CORE,0,ImmutableList.of(sink));
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(new BlockPosTracker(goal),1,0));
                accepted=true;
            }
            case "poi-acquire" -> {
                var villager=(Villager)mob;
                var behavior=AcquirePoi.create(type->type.is(PoiTypes.FISHERMAN),MemoryModuleType.JOB_SITE,false,Optional.empty());
                villager.getBrain().addActivity(Activity.CORE,0,ImmutableList.of(behavior));accepted=true;
            }
            case "query-only-multi","query-ready-adopt-multi","query-pending-adopt-multi","shared-accuracy0",
                    "shared-query-only-multi","shared-query-ready-adopt-multi","shared-query-pending-adopt-multi" -> {
                controller=new MeleeAttackGoal((ProbeHusk)mob,1,true);
                Set<BlockPos> targets=kind().equals("shared-accuracy0")?Set.of(goal)
                        :kind().startsWith("shared-query-")
                            ?new LinkedHashSet<>(List.of(goal,start.offset(9,0,1),start.offset(10,0,2)))
                        :new LinkedHashSet<>(List.of(goal,start.offset(18,0,1),start.offset(24,0,2)));
                query=AutomaticNavigationBridge.delegate(controller,0,"TICK",()->nav.createPath(targets,0));
                accepted=query instanceof NavigationRequestPath;
                if(query instanceof NavigationRequestPath request)add(request);
                if(accepted&&(kind().equals("query-pending-adopt-multi")
                        ||kind().equals("shared-query-pending-adopt-multi")||kind().equals("shared-accuracy0"))) {
                    accepted=nav.moveTo(query,1);adoptedAt=System.nanoTime();
                }
                action("query-created",Map.of("targets",targets.toString(),"pending",query instanceof NavigationRequestPath request
                        &&request.state==NavigationRequestPath.State.PENDING));
            }
            case "legacy-createpath-move" -> {
                query=nav.createPath(Set.of(goal,start.offset(18,0,1)),1);
                if(query!=null) {goal=query.getTarget();action("legacy-selected-target",goal.toString());}
                accepted=query!=null&&nav.moveTo(query,1);
            }
            default -> accepted=nav.moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,1);
        }
        track();
        if(kind().equals("shared-pending-cancel1")) {
            nav.stop();action("cancel-pending",true);
        } else if(kind().equals("shared-pending-replace1")) {
            goal=start.offset(6,0,2);
            boolean replacement=nav.moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,1);
            if(!replacement)throw new IllegalStateException("Pending replacement rejected");
            action("replace-pending",goal.toString());track();
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void start(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.START||closing)return;
        tick++;Trace.tick=server.getTickCount();
        try {
            if(mob==null) {if(tick>40)prepare();return;}
            long now=System.nanoTime();
            if(!submittedScene&&ready!=0&&now-ready>=30_000_000_000L&&now-started>=30_000_000_000L) {
                Trace.trial=index+1;Trace.clock();topologyBefore=metrics.invoke(TopologyService.forServer(server));
                sharedRequestsBefore=service.sharedSectionRequests();
                sharedSegmentsBefore=service.sharedSectionSegments();
                sharedFallbacksBefore=service.sharedSectionFallbacks();
                sharedRuntimeBefore=TopologyService.forServer(server).sharedTopologyMetrics();
                threadCostsBefore=threadCosts();
                before=Measurements.snapshot();submitted=System.nanoTime();submitTick=tick;submittedScene=true;
                submit();submitEnd=System.nanoTime();nextSample=submitted+100_000_000L;
                System.out.println("TIMELINE_SUBMIT "+index+" "+kind());
            }
            if(!submittedScene||terminal!=0)return;
            track();
            if(kind().equals("entity-drift")&&drifts<3&&tick-submitTick>=20+20*drifts) {
                target.setPos(target.getX()+4,target.getY(),target.getZ());goal=target.blockPosition();drifts++;
                action("target-drift",List.of(target.getX(),target.getY(),target.getZ()));
            }
            if(kind().equals("shared-drift1")&&drifts==0&&publication.firstReadyNanos!=0) {
                target.setPos(target.getX()+4,target.getY(),target.getZ());goal=target.blockPosition();drifts++;
                action("target-drift",List.of(target.getX(),target.getY(),target.getZ()));
            }
            if((kind().equals("shared-cancel1")||kind().equals("shared-replace1"))
                    &&!actionTaken&&publication.firstReadyNanos!=0) {
                actionTaken=true;
                if(kind().equals("shared-cancel1")) {mob.getNavigation().stop();action("cancel-published",true);}
                else {
                    goal=start.offset(6,0,2);
                    boolean replacement=mob.getNavigation().moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,1);
                    if(!replacement)throw new IllegalStateException("Replacement rejected");
                    action("replace-published",goal.toString());track();
                }
            }
            if(query instanceof NavigationRequestPath request&&request.state==NavigationRequestPath.State.REACHABLE&&queryReady==0) {
                queryReady=System.nanoTime();goal=request.getTarget();action("query-ready",request.getTarget().toString());
                if(kind().equals("query-ready-adopt-multi")||kind().equals("shared-query-ready-adopt-multi")) {
                    accepted=mob.getNavigation().moveTo(query,1);adoptedAt=System.nanoTime();action("ready-adopt",accepted);
                }
            }
            if((kind().equals("dynamic-block")||kind().equals("shared-dynamic1"))
                    &&!actionTaken&&publication.firstReadyNanos!=0&&latest!=null&&latest.getNodeCount()>3) {
                for(int n=1;n<Math.min(4,latest.getNodeCount());n++) {
                    BlockPos point=latest.getNode(n).asBlockPos();
                    for(int y=0;y<3;y++) {
                        BlockPos pos=point.above(y);if(!pos.equals(mob.blockPosition())) {
                            level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);changed.add(pos);
                        }
                    }
                }
                actionTaken=true;action("blocks-after-publication",changed.toString());
            }
            if(kind().equals("native-stuck-recovery")&&publication.firstReadyNanos!=0&&mob instanceof ProbeHusk husk) {
                if(!actionTaken) {husk.frozen=true;actionTaken=true;action("controlled-immobilization",true);}
                Object work=latest==null?null:((Map<?,?>)Trace.field(service,"records")).get(latest);
                if(husk.frozen&&work!=null&&((Number)Trace.field(work,"refreshes")).intValue()>0) {
                    husk.frozen=false;action("release-after-real-refresh",true);
                }
            }
        }catch(Throwable error) {abort(error);}
    }
    private void observe(long now) {
        if(latest==null)return;
        Object work=((Map<?,?>)Trace.field(service,"records")).get(latest);
        String key=latest.queryId+":"+latest.state+":"+latest.getNodeCount()+":"+latest.getNextNodeIndex();
        if(work!=null)for(String name:List.of("cursor","stageEnd","attempt","stride","stable","nativeStopped","preparing","queued","refreshes"))
            key+=":"+Trace.field(work,name);
        if(key.equals(lastObservation))return;
        lastObservation=key;JsonObject row=new JsonObject();
        row.addProperty("nanoTime",now);row.addProperty("tick",server.getTickCount());
        row.addProperty("queryId",latest.queryId.toString());row.addProperty("state",latest.state.name());
        row.addProperty("nodeCount",latest.getNodeCount());row.addProperty("nodeIndex",latest.getNextNodeIndex());
        row.addProperty("canReach",latest.canReach());row.add("position",gson.toJsonTree(List.of(mob.getX(),mob.getY(),mob.getZ())));
        row.addProperty("onGround",mob.onGround());
        row.add("selectedTarget",gson.toJsonTree(List.of(latest.getTarget().getX(),latest.getTarget().getY(),latest.getTarget().getZ())));
        if(latest.getNodeCount()>0) {
            BlockPos end=latest.getEndNode().asBlockPos();
            row.add("pathEnd",gson.toJsonTree(List.of(end.getX(),end.getY(),end.getZ())));
        }
        if(target!=null)row.add("entityTargetPosition",gson.toJsonTree(List.of(target.getX(),target.getY(),target.getZ())));
        if(work!=null)for(String name:List.of("cursor","stageEnd","attempt","stride","stable","nativeStopped","preparing",
                "queued","refreshes","recoveryCount","segmentPublication","doneSince","last","segmentReachable","segmentEntry")) {
            Object value=Trace.field(work,name);
            row.add(name,name.equals("segmentEntry")?new JsonPrimitive(value!=null):gson.toJsonTree(value));
        }
        if(work!=null)row.add("sharedMergeRejected",gson.toJsonTree(optionalField(work,"sharedMergeRejected")));
        observations.add(row);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void end(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.END||closing||mob==null)return;
        try {
            long now=System.nanoTime();
            if(!submittedScene) {
                if(mob.isRemoved())throw new IllegalStateException("Prepared actor removed before submission: "+kind());
                if(ready==0&&tick-prepareTick>1200)
                    throw new IllegalStateException("Scene not ready: "+kind()+" position="+mob.position()+" onGround="+mob.onGround());
                if(ready==0&&tick-prepareTick>=20&&mob.onGround()&&mob.blockPosition().equals(start)) {
                    ready=now;System.out.println("TIMELINE_SCENE_READY "+index);
                }
                return;
            }
            track();if(!Boolean.getBoolean("an.trace.omitObservations"))observe(now);
            if(now>=nextSample) {samples.add(gson.toJsonTree(Measurements.snapshot()));nextSample=now+100_000_000L;}
            if(firstMove==0&&mob.position().distanceTo(initial)>.05)firstMove=now;
            if(latest!=null&&latest.adopted)goal=latest.getTarget();
            if(!kind().equals("poi-acquire")&&!kind().equals("query-only-multi")&&!kind().equals("shared-query-only-multi")
                    &&mob.blockPosition().distManhattan(goal)<=1&&arrival==0)arrival=now;
            if(latest!=null&&latest.adopted&&intentArrival==0
                    &&mob.blockPosition().distManhattan(goal)<=latest.intent.accuracy())intentArrival=now;
            if(terminal==0) {
                if(!accepted)state="REJECTED";
                else if(kind().equals("poi-acquire")&&((Villager)mob).getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) {
                    state="POI_SELECTED";action("poi-ticket",((Villager)mob).getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos().toString());
                } else if((kind().equals("query-only-multi")||kind().equals("shared-query-only-multi"))
                        &&query instanceof NavigationRequestPath request
                        &&(request.state==NavigationRequestPath.State.REACHABLE||request.state==NavigationRequestPath.State.UNREACHABLE)) {
                    state="QUERY_"+request.state.name();
                } else if(latest!=null&&latest.terminal()&&service.active(mob.getNavigation())==null)state=latest.state.name();
                else if(kind().equals("legacy-createpath-move")&&mob.getNavigation().isDone())state=arrival!=0?"SUCCEEDED":"EXHAUSTED";
                else if(now-submitted>=90_000_000_000L)state="TIMEOUT";
                else return;
                terminal=now;terminalTick=tick;
            }
            if(tick-terminalTick>=10)finish(now);
        }catch(Throwable error) {abort(error);}
    }
    private void finish(long now) throws Exception {
        closed=now;after=Measurements.snapshot();topologyAfter=metrics.invoke(TopologyService.forServer(server));
        Trace.clock();Trace.trial=0;
        JsonObject row=new JsonObject();row.addProperty("trial",index+1);row.add("scene",gson.toJsonTree(scenes.get(index)));
        row.addProperty("serverStartedNanos",started);row.addProperty("sceneReadyNanos",ready);
        row.addProperty("submittedNanos",submitted);row.addProperty("closedNanos",closed);
        row.add("costStart",gson.toJsonTree(before));row.add("costEnd",gson.toJsonTree(after));row.add("costSamples",samples);
        row.add("topologyBefore",gson.toJsonTree(topologyBefore));row.add("topologyAfter",gson.toJsonTree(topologyAfter));
        row.add("observations",observations);row.add("actions",actions);
        row.add("sharedRuntimeBefore",gson.toJsonTree(sharedRuntimeBefore));
        row.add("sharedRuntimeAfter",gson.toJsonTree(TopologyService.forServer(server).sharedTopologyMetrics()));
        row.add("javaThreadCostsBefore",gson.toJsonTree(threadCostsBefore));
        row.add("javaThreadCostsAfter",gson.toJsonTree(threadCosts()));
        row.add("sharedDelta",gson.toJsonTree(Map.of(
                "requests",service.sharedSectionRequests()-sharedRequestsBefore,
                "segments",service.sharedSectionSegments()-sharedSegmentsBefore,
                "fallbacks",service.sharedSectionFallbacks()-sharedFallbacksBefore)));
        JsonObject a=new JsonObject();a.addProperty("tile",0);a.addProperty("slot",0);a.addProperty("kind",kind());
        a.add("start",gson.toJsonTree(List.of(start.getX(),start.getY(),start.getZ())));
        a.add("goal",gson.toJsonTree(List.of(goal.getX(),goal.getY(),goal.getZ())));
        a.addProperty("sameSection",SectionPos.of(start).equals(SectionPos.of(goal)));
        a.addProperty("queryId",latest==null?"":latest.queryId.toString());
        a.add("queryIds",gson.toJsonTree(requests.stream().map(request->request.queryId.toString()).toList()));
        JsonArray generations=new JsonArray();
        for(NavigationRequestPath request:requests) {
            JsonObject generation=new JsonObject();generation.addProperty("queryId",request.queryId.toString());
            generation.addProperty("firstSeenNanos",firstSeen.get(request));generation.addProperty("state",request.state.name());
            generation.addProperty("accuracy",request.intent.accuracy());generation.addProperty("adopted",request.adopted);
            generation.addProperty("controller",request.controller.getClass().getName());
            generation.addProperty("entityTarget",request.intent.entity()!=null);
            generation.addProperty("normalizeGround",request.intent.normalizeGround());
            generation.addProperty("range",request.intent.range());
            generation.addProperty("regionOffset",request.intent.regionOffset());
            generation.addProperty("visitedMultiplier",request.intent.visitedMultiplier());
            generation.addProperty("targetCount",request.intent.targets().size());generations.add(generation);
        }
        a.add("requestGenerations",generations);a.addProperty("accepted",accepted);a.addProperty("state",state);
        a.addProperty("submitStartNanos",submitted);a.addProperty("submitEndNanos",submitEnd);
        a.addProperty("queryReadyNanos",queryReady);a.addProperty("adoptedNanos",adoptedAt);
        a.addProperty("firstReadyNanos",publication.firstReadyNanos);a.addProperty("firstMoveNanos",firstMove);
        a.addProperty("arrivalNanos",arrival);a.addProperty("intentAccuracyArrivalNanos",intentArrival);
        a.addProperty("terminalNanos",terminal);a.add("search",gson.toJsonTree(searches));
        a.add("finalPosition",gson.toJsonTree(List.of(mob.getX(),mob.getY(),mob.getZ())));
        JsonArray actors=new JsonArray();actors.add(a);row.add("actors",actors);rows.add(row);
        mob.getNavigation().stop();
        for(NavigationRequestPath request:requests)service.consume(request);
        PerformanceSearchTelemetry.ACTORS.remove(mob);PublicationTelemetry.ACTORS.remove(mob.getNavigation());
        mob.discard();if(target!=null)target.discard();mob=null;
        if(sharedHookField!=null) {
            sharedHookField.set(sharedHooks,previousSharedHook);
            sharedHookField=null;sharedHooks=previousSharedHook=null;
        }
        for(BlockPos point:changed) {level.getPoiManager().remove(point);level.setBlock(point,Blocks.AIR.defaultBlockState(),3);}
        System.out.println("TIMELINE_FINISH "+index+" "+kind()+" "+state);
        write();if(index+1==scenes.size()) {done=true;stop();}
    }
    private void write() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("schema","NAVIGATION_TIMELINE_V1");
        root.addProperty("diagnostic",true);root.addProperty("deepTrace",Trace.ENABLED);root.addProperty("vanillaSameRuntime",false);
        root.addProperty("complete",done);root.addProperty("failure",failure);root.addProperty("coverageScope","controlled entry census");
        root.addProperty("coverageProbeRevision",6);
        root.add("scenes",gson.toJsonTree(scenes));root.add("trials",rows);Files.writeString(output.resolve("timeline.json"),gson.toJson(root));
        FailureSearchTelemetry.write(output);
    }
    private void abort(Throwable error) {failure=error.toString();error.printStackTrace();stop();}
    private void stop() {
        if(closing)return;closing=true;Trace.trial=0;
        try {write();}catch(Exception error) {error.printStackTrace();}server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) throws Exception {
        JsonObject row=new JsonObject();row.addProperty("passed",done&&failure.isEmpty()&&service.metrics().records()==0
                &&NavigationScheduler.forServer(server).metrics().pendingRequests()==0);
        row.add("service",gson.toJsonTree(service.metrics()));Files.writeString(output.resolve("shutdown.json"),gson.toJson(row));
    }
}
