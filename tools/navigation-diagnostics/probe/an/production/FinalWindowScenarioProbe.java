package an.production;

import an.trace.Trace;
import com.google.gson.*;
import com.scarasol.acceleratednavigation.navigation.*;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;

/** Real movement and lifecycle boundaries around the final segment dispatch. */
public final class FinalWindowScenarioProbe {
    private static FinalWindowScenarioProbe instance;
    private static final List<String> NAMES=List.of("zero-edge8-first","zero-edge8-repeat","cross8",
            "macro-cancel","macro-replace","macro-block","budget-exhausted",
            "waiting-resume","waiting-cancel","waiting-replace","waiting-ttl","waiting-shutdown","shutdown-final");
    private final List<String> scenes=new ArrayList<>(NAMES);
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final JsonArray trials=new JsonArray();
    private final List<NavigationRequestPath> requests=new ArrayList<>();
    private final Map<BlockPos,BlockState> changed=new LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private PerformanceComparisonProbe.BenchmarkHusk mob;
    private PerformanceSearchTelemetry.Stats stats;
    private PublicationTelemetry.Stamp publication;
    private JsonArray actions,searches;
    private BlockPos start,goal;
    private Object committed;
    private Measurements.Snapshot costStart;
    private long started,ready,submitted,submitEnd,arrival,terminal;
    private int index=-1,tick,preparedTick,terminalTick,waitingTick;
    private boolean accepted,acted,closing,done,shutdownRequested,waitingAction;
    private String failure="";

    FinalWindowScenarioProbe() {
        if(Boolean.getBoolean("an.production.performanceVanilla"))throw new IllegalArgumentException("Accelerated control required");
        String filter=System.getProperty("an.trace.scene","");
        if(!filter.isEmpty()) {
            Set<String> selected=new HashSet<>(Arrays.asList(filter.split(",")));
            if(!NAMES.containsAll(selected))throw new IllegalArgumentException("Unknown final-window scene");
            scenes.removeIf(name->!selected.contains(name));
        } else scenes.remove("waiting-shutdown");
        instance=this;
        MinecraftForge.EVENT_BUS.register(this);
    }
    private String scene() { return scenes.get(index); }
    private static Object field(Object object,String name) throws ReflectiveOperationException {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private Object work(NavigationRequestPath request) throws ReflectiveOperationException {
        return ((Map<?,?>)field(service,"records")).get(request);
    }
    private NavigationRequestPath track() {
        NavigationRequestPath request=service.active(mob.getNavigation());
        if(request!=null&&!requests.contains(request)) {
            requests.add(request);Trace.bind(mob.getNavigation(),request,mob);
        }
        return request;
    }
    private JsonObject action(String kind) {
        JsonObject action=new JsonObject();action.addProperty("kind",kind);
        action.addProperty("nanoTime",System.nanoTime());actions.add(action);return action;
    }
    public static void macroDelivered() {
        if(instance==null)return;
        try { instance.afterMacro(); }
        catch(Throwable error) { instance.abort(error); }
    }
    private void afterMacro() throws Exception {
        if(closing||submitted==0||acted)return;
        NavigationRequestPath request=track();
        if(request==null)return;
        Object work=work(request);
        if(work==null||field(work,"corridor")==null)return;
        committed=field(work,"corridor");
        if(!scene().startsWith("macro-")&&!scene().startsWith("waiting-")&&!scene().equals("budget-exhausted"))return;
        acted=true;
        JsonObject event=action(scene());
        event.addProperty("searchCallsBefore",stats.calls);
        event.addProperty("queryId",request.queryId.toString());
        if(scene().equals("macro-cancel"))mob.getNavigation().stop();
        else if(scene().equals("macro-replace")) {
            accepted=mob.getNavigation().moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,1);
            track();
        } else if(scene().equals("macro-block")) {
            for(int z=-1;z<=1;z++)for(int y=0;y<3;y++) {
                BlockPos position=start.offset(4,y,z);
                changed.put(position,level.getBlockState(position));
                level.setBlock(position,Blocks.STONE.defaultBlockState(),3);
            }
        } else if(scene().equals("budget-exhausted")) {
            try(var ignored=scheduler.account()) {
                long limit=System.nanoTime()+50_000_000L;
                while(scheduler.metrics().availableNanos()>0&&System.nanoTime()<limit)Thread.onSpinWait();
            }
            event.addProperty("balanceAfter",scheduler.metrics().availableNanos());
            event.addProperty("serverTick",server.getTickCount());
        } else {
            mob.setNoGravity(true);mob.setNoAi(true);
            mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mob.setPos(mob.getX(),start.getY()+1,mob.getZ());mob.setOnGround(false);
        }
    }
    public static void searched(NavigationRequestPath request,Set<BlockPos> targets,int accuracy,float range,
            NativeNavigationSearch.Result result) {
        if(instance==null)return;
        try { instance.recordSearch(request,targets,accuracy,range,result); }
        catch(Throwable error) { instance.abort(error); }
    }
    private void recordSearch(NavigationRequestPath request,Set<BlockPos> targets,int accuracy,float range,
            NativeNavigationSearch.Result result) throws Exception {
        if(closing||mob==null||request.mob!=mob)return;
        if(!requests.contains(request))requests.add(request);
        Object work=work(request);
        JsonObject row=new JsonObject();row.addProperty("nanoTime",System.nanoTime());
        row.addProperty("queryId",request.queryId.toString());row.addProperty("last",(boolean)field(work,"last"));
        row.addProperty("window",field(work,"window")!=null);
        row.addProperty("pendingWindow",field(work,"pendingWindow")!=null);
        row.addProperty("shared",field(work,"sharedCandidate")!=null);
        row.addProperty("serverTick",server.getTickCount());
        row.add("account",gson.toJsonTree(scheduler.metrics()));
        row.addProperty("range",range);row.addProperty("requestedRange",request.intent.range());
        row.addProperty("accuracy",accuracy);row.addProperty("requestedAccuracy",request.intent.accuracy());
        row.addProperty("regionOffset",request.intent.regionOffset());
        row.addProperty("visitedMultiplier",request.intent.visitedMultiplier());
        row.addProperty("refreshes",(int)field(work,"refreshes"));
        row.addProperty("committedIdentityRetained",committed==null||committed==field(work,"corridor"));
        row.add("start",gson.toJsonTree(point(request.mob.blockPosition())));
        row.add("targets",gson.toJsonTree(targets.stream().map(FinalWindowScenarioProbe::point).toList()));
        Path path=result.path();row.addProperty("unavailable",result.unavailable());
        row.addProperty("canReach",path!=null&&path.canReach());
        JsonArray nodes=new JsonArray();
        if(path!=null)for(int i=0;i<path.getNodeCount();i++)nodes.add(gson.toJsonTree(point(path.getNode(i).asBlockPos())));
        row.add("nodes",nodes);searches.add(row);
    }
    private static List<Integer> point(BlockPos p) { return List.of(p.getX(),p.getY(),p.getZ()); }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof Mob&&!(event.getEntity() instanceof PerformanceComparisonProbe.BenchmarkHusk))
            event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        try {
            server=event.getServer();started=System.nanoTime();level=server.overworld();
            var bypass=com.scarasol.acceleratednavigation.policy.NavigationPolicy.class.getDeclaredField("bypassEntityIds");
            bypass.setAccessible(true);com.scarasol.acceleratednavigation.policy.NavigationPolicy.reloadBypassIds();
            bypass.set(null,Set.of());
            for(var rule:List.of(GameRules.RULE_DOMOBSPAWNING,GameRules.RULE_DAYLIGHT,GameRules.RULE_WEATHER_CYCLE))
                level.getGameRules().getRule(rule).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);level.setDayTime(6000);
            var load=PerformanceComparisonProbe.class.getDeclaredMethod("loadArena",ServerLevel.class);
            load.setAccessible(true);load.invoke(null,level);
            service=SegmentedNavigationService.forServer(server);scheduler=NavigationScheduler.forServer(server);
            System.out.println("FINAL_WINDOW_READY scenes="+scenes.size());
        } catch(Throwable error) { abort(error); }
    }
    private void prepare() {
        index++;
        if(index==scenes.size()) {done=true;stop();return;}
        requests.clear();changed.clear();committed=null;acted=waitingAction=false;waitingTick=0;
        actions=new JsonArray();searches=new JsonArray();
        ready=submitted=submitEnd=arrival=terminal=0;terminalTick=0;
        start=new BlockPos(scene().equals("cross8")?-148:-156,81,-147);goal=start.offset(8,0,0);
        mob=new PerformanceComparisonProbe.BenchmarkHusk(level);mob.setPos(start.getX()+.5,81.01,start.getZ()+.5);
        if(!level.addFreshEntity(mob))throw new IllegalStateException("Actor rejected");
        stats=new PerformanceSearchTelemetry.Stats();publication=new PublicationTelemetry.Stamp();
        PerformanceSearchTelemetry.ACTORS.put(mob,stats);PublicationTelemetry.ACTORS.put(mob.getNavigation(),publication);
        preparedTick=tick;System.out.println("FINAL_WINDOW_PREPARE "+scene());
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void start(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.START||closing)return;
        try {
            tick++;Trace.tick=server.getTickCount();
            if(mob==null) { if(tick>40)prepare();return; }
            if(terminal!=0&&tick-terminalTick>=10) { finishScene();prepare();return; }
            long now=System.nanoTime();
            if(submitted==0&&ready!=0&&now-ready>=30_000_000_000L&&now-started>=30_000_000_000L) {
                if(index==0)ProductionBudgetChecks.run(server);
                Trace.trial=index+1;costStart=Measurements.snapshot();submitted=System.nanoTime();
                BlockPos first=scene().equals("macro-replace")||scene().equals("waiting-replace")
                        ?goal.offset(0,0,4):goal;
                accepted=mob.getNavigation().moveTo(first.getX()+.5,first.getY(),first.getZ()+.5,1);
                submitEnd=System.nanoTime();track();
                System.out.println("FINAL_WINDOW_SUBMIT "+scene());
            }
        } catch(Throwable error) {abort(error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void end(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.END||closing||mob==null)return;
        try {
            long now=System.nanoTime();
            if(submitted==0) {
                if(ready==0&&tick-preparedTick>=20&&mob.onGround()&&mob.blockPosition().equals(start))ready=now;
                return;
            }
            NavigationRequestPath request=track();
            if(scene().startsWith("waiting-")&&request!=null) {
                Object work=work(request);
                if(work!=null&&field(work,"waitingSearch")!=null) {
                    if(waitingTick==0) {waitingTick=tick;action("waiting-search-observed");}
                    if(!waitingAction&&!scene().equals("waiting-ttl")&&tick-waitingTick>=3) {
                        waitingAction=true;action(scene()+"-action");
                        if(scene().equals("waiting-cancel"))mob.getNavigation().stop();
                        else if(scene().equals("waiting-shutdown")) {
                            shutdownRequested=true;stop();return;
                        } else {
                            mob.setNoGravity(false);mob.setNoAi(false);
                            if(scene().equals("waiting-replace")) {
                                accepted=mob.getNavigation().moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,1);
                                track();
                            }
                        }
                    }
                }
            }
            if(arrival==0&&mob.blockPosition().distManhattan(goal)<=1)arrival=now;
            if(scene().equals("shutdown-final")&&publication.firstReadyNanos!=0) {
                action("actual-server-shutdown");shutdownRequested=true;stop();return;
            }
            NavigationRequestPath latest=requests.isEmpty()?null:requests.get(requests.size()-1);
            if(terminal==0&&(!accepted||latest!=null&&latest.terminal())) {terminal=now;terminalTick=tick;}
            if(now-submitted>90_000_000_000L)throw new IllegalStateException("Scene timeout "+scene());
        } catch(Throwable error) {abort(error);}
    }
    private void finishScene() throws Exception {
        JsonObject row=new JsonObject();row.addProperty("name",scene());
        row.addProperty("serverStartedNanos",started);row.addProperty("sceneReadyNanos",ready);
        row.addProperty("submittedNanos",submitted);row.addProperty("submitEndNanos",submitEnd);
        row.addProperty("firstReadyNanos",publication.firstReadyNanos);row.addProperty("arrivalNanos",arrival);
        row.addProperty("closedNanos",System.nanoTime());row.addProperty("accepted",accepted);
        row.addProperty("sameSection",net.minecraft.core.SectionPos.of(start).equals(net.minecraft.core.SectionPos.of(goal)));
        row.add("start",gson.toJsonTree(point(start)));row.add("goal",gson.toJsonTree(point(goal)));
        row.add("costStart",gson.toJsonTree(costStart));row.add("costEnd",gson.toJsonTree(Measurements.snapshot()));
        row.add("search",gson.toJsonTree(stats));row.add("searches",searches);row.add("actions",actions);
        JsonArray generations=new JsonArray();
        for(NavigationRequestPath request:requests) {
            JsonObject generation=new JsonObject();generation.addProperty("queryId",request.queryId.toString());
            generation.addProperty("state",request.state.name());generations.add(generation);
        }
        row.add("generations",generations);trials.add(row);
        if(!shutdownRequested) { mob.getNavigation().stop();mob.discard(); }
        PerformanceSearchTelemetry.ACTORS.remove(mob);PublicationTelemetry.ACTORS.remove(mob.getNavigation());
        changed.forEach((position,state)->level.setBlock(position,state,3));
        mob=null;Trace.trial=0;write();
        System.out.println("FINAL_WINDOW_FINISH "+scene());
    }
    private void write() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("schema","FINAL_WINDOW_LIFECYCLE_V1");
        root.addProperty("complete",done);root.addProperty("diagnostic",true);
        root.addProperty("failure",failure);root.add("scenes",gson.toJsonTree(scenes));root.add("trials",trials);
        Files.writeString(output.resolve("timeline.json"),gson.toJson(root));
    }
    private void abort(Throwable error) {failure=error.toString();error.printStackTrace();stop();}
    private void stop() {
        if(closing)return;closing=true;
        try {write();}catch(Exception error){error.printStackTrace();}
        if(server!=null)server.halt(false);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void stopped(ServerStoppedEvent event) throws Exception {
        if(shutdownRequested) {
            done=index==scenes.size()-1;terminal=System.nanoTime();finishScene();
        }
        JsonObject shutdown=new JsonObject();
        shutdown.addProperty("passed",done&&failure.isEmpty()&&service.metrics().records()==0&&scheduler.metrics().pendingRequests()==0);
        shutdown.add("service",gson.toJsonTree(service.metrics()));shutdown.add("scheduler",gson.toJsonTree(scheduler.metrics()));
        Files.writeString(output.resolve("shutdown.json"),gson.toJson(shutdown));write();instance=null;
    }
}
