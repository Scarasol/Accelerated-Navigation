package an.production;

import an.trace.Trace;
import com.google.gson.*;
import com.scarasol.acceleratednavigation.navigation.*;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;

/** Per-request inputs and actual execution, including short chords with long detours. */
public final class ShortNativeScenarioProbe {
    private static ShortNativeScenarioProbe instance;
    private static final List<String> FLAT=List.of("flat8","cross8","radius16","radius17","middle28","super304",
            "range4","range12","forced-null","forced-unavailable","cancel-pending","replace-pending",
            "query-only","query-pending","query-waiting","query-ready","query-mixed","entity-drift","dynamic-block","real-stuck","waiting-resume",
            "waiting-cancel","waiting-ttl","shutdown-published");
    private final boolean city=Boolean.getBoolean("an.trace.cityWorld");
    private final List<String> scenes=new ArrayList<>(city?List.of("stairs-forward","stairs-backward"):FLAT);
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final JsonArray trials=new JsonArray();
    private final List<NavigationRequestPath> requests=new ArrayList<>();
    private final Map<BlockPos,BlockState> changes=new LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private PathfinderMob mob,target;
    private PerformanceSearchTelemetry.Stats stats;
    private PublicationTelemetry.Stamp publication;
    private NavigationRequestPath query;
    private JsonArray searches,actions;
    private BlockPos originalStart,originalGoal,start,goal;
    private long started,ready,submitted,submitEnd,arrival,terminal,searchWall,searchCpu,searchBytes;
    private int index=-1,tick,preparedTick,submitTick,terminalTick,macros,windows,shared;
    private boolean closing,done,accepted,acted,injected,shutdownScene;
    private String failure="";

    ShortNativeScenarioProbe() {
        if(Boolean.getBoolean("an.production.performanceVanilla"))throw new IllegalArgumentException("Adapter required");
        String filter=System.getProperty("an.trace.scene","");
        if(!filter.isEmpty()) {
            Set<String> selected=new HashSet<>(Arrays.asList(filter.split(",")));
            if(!scenes.containsAll(selected))throw new IllegalArgumentException("Unknown short-native scene");
            scenes.removeIf(name->!selected.contains(name));
        }
        instance=this;MinecraftForge.EVENT_BUS.register(this);
    }
    private String scene() { return scenes.get(index); }
    private static Object field(Object value,String name) throws ReflectiveOperationException {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private Object work(NavigationRequestPath request) throws ReflectiveOperationException {
        return ((Map<?,?>)field(service,"records")).get(request);
    }
    private void track() {
        NavigationRequestPath request=service.active(mob.getNavigation());
        if(request!=null&&!requests.contains(request)) {
            requests.add(request);Trace.bind(mob.getNavigation(),request,mob);
        }
    }
    public static void macro() { if(instance!=null&&instance.submitted!=0&&!instance.closing)instance.macros++; }
    public static void shared() { if(instance!=null&&instance.submitted!=0&&!instance.closing)instance.shared++; }
    public static void window() { if(instance!=null&&instance.submitted!=0&&!instance.closing)instance.windows++; }
    public static void nativeStopped(net.minecraft.world.entity.ai.navigation.PathNavigation navigation) {
        if(instance==null||instance.mob==null||instance.mob.getNavigation()!=navigation||instance.submitted==0)return;
        instance.action("real-nativeStopped");
        if(instance.mob instanceof CoverageScenarioProbe.ProbeHusk husk&&husk.frozen) {
            husk.frozen=false;instance.action("release-after-nativeStopped");
        }
    }
    private static List<Integer> point(BlockPos p) { return List.of(p.getX(),p.getY(),p.getZ()); }
    private void action(String name) {
        JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("nanoTime",System.nanoTime());
        row.addProperty("serverTick",server.getTickCount());actions.add(row);
    }
    public static NativeNavigationSearch.Result searched(NavigationRequestPath request,Set<BlockPos> targets,
            int accuracy,float range,Set<BlockPos> avoided,NativeNavigationSearch.Result result) {
        if(instance==null)return result;
        try { return instance.search(request,targets,accuracy,range,avoided,result); }
        catch(Throwable error) { instance.abort(error);return result; }
    }
    private NativeNavigationSearch.Result search(NavigationRequestPath request,Set<BlockPos> targets,
            int accuracy,float range,Set<BlockPos> avoided,NativeNavigationSearch.Result actual) throws Exception {
        if(closing||submitted==0||request.mob!=mob)return actual;
        if(!requests.contains(request))requests.add(request);
        Object work=work(request);
        JsonObject row=new JsonObject();row.addProperty("nanoTime",System.nanoTime());
        row.addProperty("queryId",request.queryId.toString());row.addProperty("adopted",request.adopted);
        row.addProperty("segmented",(boolean)field(work,"segmented"));
        row.addProperty("last",(boolean)field(work,"last"));
        row.addProperty("range",range);row.addProperty("requestedRange",request.intent.range());
        row.addProperty("accuracy",accuracy);row.addProperty("requestedAccuracy",request.intent.accuracy());
        row.addProperty("visitedMultiplier",request.intent.visitedMultiplier());
        row.addProperty("regionOffset",request.intent.regionOffset());row.addProperty("upward",request.intent.upward());
        var caps=request.intent.capabilities();
        JsonObject flags=new JsonObject();flags.addProperty("openDoors",caps.openDoors());
        flags.addProperty("passDoors",caps.passDoors());flags.addProperty("floats",caps.floats());
        flags.addProperty("overFences",caps.overFences());flags.addProperty("evaluator",caps.evaluator().getName());
        flags.addProperty("width",caps.width());flags.addProperty("height",caps.height());
        flags.addProperty("step",caps.step());flags.addProperty("drop",caps.drop());
        flags.add("malus",gson.toJsonTree(caps.malus()));row.add("capabilities",flags);
        row.add("originalTargets",gson.toJsonTree(request.intent.targets().stream().map(ShortNativeScenarioProbe::point).toList()));
        row.addProperty("wallNanos",stats.wallNanos-searchWall);searchWall=stats.wallNanos;
        row.addProperty("cpuNanos",stats.cpuNanos-searchCpu);searchCpu=stats.cpuNanos;
        row.addProperty("allocatedBytes",stats.allocatedBytes-searchBytes);searchBytes=stats.allocatedBytes;
        row.add("start",gson.toJsonTree(point(mob.blockPosition())));
        row.add("targets",gson.toJsonTree(targets.stream().map(ShortNativeScenarioProbe::point).toList()));
        row.add("avoided",gson.toJsonTree(avoided.stream().map(ShortNativeScenarioProbe::point).toList()));
        row.addProperty("macroCallsBefore",macros);row.addProperty("sharedCallsBefore",shared);row.addProperty("windowCallsBefore",windows);
        row.addProperty("actualUnavailable",actual.unavailable());
        row.addProperty("actualNull",actual.path()==null);
        row.addProperty("actualCanReach",actual.path()!=null&&actual.path().canReach());
        NativeNavigationSearch.Result result=actual;
        boolean force=!injected&&(scene().equals("forced-null")||scene().equals("forced-unavailable"));
        if(force) {
            injected=true;
            result=new NativeNavigationSearch.Result(null,scene().equals("forced-unavailable"),actual.proof());
        }
        row.addProperty("injected",force);row.addProperty("unavailable",result.unavailable());
        row.addProperty("canReach",result.path()!=null&&result.path().canReach());
        JsonArray nodes=new JsonArray();
        if(actual.path()!=null)for(int n=0;n<actual.path().getNodeCount();n++)
            nodes.add(gson.toJsonTree(point(actual.path().getNode(n).asBlockPos())));
        row.add("actualNodes",nodes);searches.add(row);return result;
    }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof Mob&&!(event.getEntity() instanceof PerformanceComparisonProbe.BenchmarkHusk)
                &&!(event.getEntity() instanceof CoverageScenarioProbe.ProbeHusk))
            event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        try {
            server=event.getServer();started=System.nanoTime();level=server.overworld();
            Field bypass=NavigationPolicy.class.getDeclaredField("bypassEntityIds");bypass.setAccessible(true);
            NavigationPolicy.reloadBypassIds();bypass.set(null,Set.of());
            for(var rule:List.of(GameRules.RULE_DOMOBSPAWNING,GameRules.RULE_DAYLIGHT,GameRules.RULE_WEATHER_CYCLE))
                level.getGameRules().getRule(rule).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);level.setDayTime(6000);
            if(city) {
                for(int x=-4;x<=8;x++)for(int z=-19;z<=3;z++) {
                    level.setChunkForced(x,z,true);level.getChunk(x,z);
                }
            } else {
                var load=PerformanceComparisonProbe.class.getDeclaredMethod("loadArena",ServerLevel.class);
                load.setAccessible(true);load.invoke(null,level);
            }
            service=SegmentedNavigationService.forServer(server);scheduler=NavigationScheduler.forServer(server);
            System.out.println("SHORT_NATIVE_READY city="+city+" scenes="+scenes.size());
        } catch(Throwable error) { abort(error); }
    }
    private void prepare() {
        index++;
        if(index==scenes.size()) { done=true;stop();return; }
        requests.clear();changes.clear();query=null;target=null;
        searches=new JsonArray();actions=new JsonArray();macros=windows=shared=0;acted=injected=shutdownScene=false;
        searchWall=searchCpu=searchBytes=0;
        ready=submitted=submitEnd=arrival=terminal=0;terminalTick=0;
        int distance=switch(scene()) {case "radius16"->16;case "radius17"->17;case "middle28"->28;case "super304"->304;default->8;};
        originalStart=new BlockPos(scene().equals("cross8")?-148:-156,81,-147);
        if(scene().equals("super304"))originalStart=new BlockPos(-156,81,-159);
        originalGoal=originalStart.offset(distance,0,0);
        if(city) {
            originalStart=new BlockPos(-23,100,-198);originalGoal=new BlockPos(-37,100,-195);
            if(scene().equals("stairs-backward")) {BlockPos swap=originalStart;originalStart=originalGoal;originalGoal=swap;}
        }
        start=NativeNavigationSearch.normalize(level,originalStart,true);
        goal=NativeNavigationSearch.normalize(level,originalGoal,true);
        mob=scene().equals("real-stuck")?new CoverageScenarioProbe.ProbeHusk(level):new PerformanceComparisonProbe.BenchmarkHusk(level);
        mob.setPos(start.getX()+.5,start.getY()+.01,start.getZ()+.5);
        if(!level.addFreshEntity(mob))throw new IllegalStateException("Actor rejected");
        stats=new PerformanceSearchTelemetry.Stats();publication=new PublicationTelemetry.Stamp();
        PerformanceSearchTelemetry.ACTORS.put(mob,stats);PublicationTelemetry.ACTORS.put(mob.getNavigation(),publication);
        if(scene().equals("entity-drift")) {
            target=new PerformanceComparisonProbe.BenchmarkHusk(level);target.setNoAi(true);
            target.setPos(goal.getX()+.5,goal.getY(),goal.getZ()+.5);level.addFreshEntity(target);
        }
        preparedTick=tick;System.out.println("SHORT_NATIVE_PREPARE "+scene());
    }
    private void submit() throws Exception {
        if(index==0)ProductionBudgetChecks.run(server);
        submitted=System.nanoTime();submitTick=tick;Trace.trial=index+1;
        var nav=mob.getNavigation();
        if(scene().startsWith("waiting-")||scene().equals("query-waiting")) {
            mob.setNoGravity(true);mob.setNoAi(true);mob.setPos(mob.getX(),start.getY()+1,mob.getZ());mob.setOnGround(false);
        }
        if(scene().startsWith("query-")) {
            Object controller=new MeleeAttackGoal(mob,1,true);
            Set<BlockPos> targets=new LinkedHashSet<>(List.of(originalGoal,originalStart.offset(10,0,1),originalStart.offset(12,0,2)));
            if(scene().equals("query-mixed"))targets.add(originalStart.offset(28,0,0));
            Path path=AutomaticNavigationBridge.delegate(controller,0,"TICK",()->nav.createPath(targets,0));
            if(!(path instanceof NavigationRequestPath request))throw new IllegalStateException("No query handle");
            query=request;requests.add(request);
            accepted=scene().equals("query-ready")||scene().equals("query-only")||scene().equals("query-mixed")
                    ||scene().equals("query-waiting")||nav.moveTo(query,1);
        } else if(scene().startsWith("range")) {
            int range=Integer.parseInt(scene().substring(5));
            var intent=AutomaticNavigationBridge.intent(nav,Set.of(originalGoal),null,true,8,false,1,range);
            accepted=AutomaticNavigationBridge.move(nav,intent,1);
        } else if(target!=null)accepted=nav.moveTo(target,1);
        else {
            if(scene().equals("replace-pending")) {
                nav.moveTo(originalGoal.getX()+.5,originalGoal.getY(),originalGoal.getZ()+4.5,1);track();
            }
            accepted=nav.moveTo(originalGoal.getX()+.5,originalGoal.getY(),originalGoal.getZ()+.5,1);
        }
        submitEnd=System.nanoTime();track();
        if(scene().equals("cancel-pending")) {nav.stop();action("cancel-pending");}
        System.out.println("SHORT_NATIVE_SUBMIT "+scene());
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void start(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.START||closing)return;
        try {
            tick++;Trace.tick=server.getTickCount();
            if(mob==null) {if(tick>40)prepare();return;}
            if(terminal!=0&&tick-terminalTick>=10) {finishScene();prepare();return;}
            long now=System.nanoTime();
            if(submitted==0&&ready!=0&&now-ready>=30_000_000_000L&&now-started>=30_000_000_000L)submit();
            if(submitted==0||terminal!=0)return;
            track();
            if(scene().equals("query-waiting")&&!acted&&work(query)!=null&&field(work(query),"waitingSearch")!=null) {
                action("decision-wait-observed");accepted=mob.getNavigation().moveTo(query,1);
                mob.setNoAi(false);mob.setNoGravity(false);acted=true;action("waiting-adopt");track();
            }
            if(query!=null&&(scene().equals("query-ready")||scene().equals("query-mixed"))&&!acted&&query.state==NavigationRequestPath.State.REACHABLE) {
                goal=query.getTarget();accepted=mob.getNavigation().moveTo(query,1);acted=true;action("ready-adopt");track();
            }
            if(scene().equals("entity-drift")&&!acted&&publication.firstReadyNanos!=0&&tick-submitTick>=5) {
                target.setPos(target.getX()+24,target.getY(),target.getZ());goal=target.blockPosition();acted=true;action("entity-drift");
            }
            if(scene().equals("dynamic-block")&&!acted&&publication.firstReadyNanos!=0) {
                Path path=mob.getNavigation().getPath();
                if(path!=null&&path.getNodeCount()>3) {
                    BlockPos at=path.getNode(Math.min(3,path.getNodeCount()-1)).asBlockPos();
                    for(int y=0;y<3;y++) {
                        BlockPos block=at.above(y);changes.put(block,level.getBlockState(block));
                        level.setBlock(block,Blocks.STONE.defaultBlockState(),3);
                    }
                    acted=true;action("dynamic-block");
                }
            }
            if(scene().equals("real-stuck")&&!acted&&publication.firstReadyNanos!=0) {
                ((CoverageScenarioProbe.ProbeHusk)mob).frozen=true;acted=true;action("controlled-immobilization");
            }
            if(scene().startsWith("waiting-")&&!acted&&tick-submitTick>=5) {
                acted=true;
                if(scene().equals("waiting-resume")) {mob.setNoAi(false);mob.setNoGravity(false);action("resume");}
                if(scene().equals("waiting-cancel")) {mob.getNavigation().stop();action("waiting-cancel");}
            }
            if(scene().equals("shutdown-published")&&publication.firstReadyNanos!=0) {
                shutdownScene=true;action("actual-shutdown");server.halt(false);
            }
        } catch(Throwable error) {abort(error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void end(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.END||closing||mob==null)return;
        try {
            if(submitted==0) {
                if(ready==0&&tick-preparedTick>=20&&mob.onGround()&&mob.blockPosition().equals(start))ready=System.nanoTime();
                if(tick-preparedTick>1800&&ready==0)throw new IllegalStateException("Scene not grounded "+scene()+" "+mob.blockPosition());
                return;
            }
            track();long now=System.nanoTime();
            if(scene().equals("query-only")&&query!=null&&query.state==NavigationRequestPath.State.REACHABLE&&terminal==0) {
                terminal=now;terminalTick=tick;
            }
            if(arrival==0&&mob.blockPosition().distManhattan(goal)<=1)arrival=now;
            NavigationRequestPath current=requests.isEmpty()?null:requests.get(requests.size()-1);
            if(terminal==0&&current!=null&&current.terminal()) {terminal=now;terminalTick=tick;}
            if(terminal==0&&now-submitted>(city?300e9:100e9))throw new IllegalStateException("Scene timeout "+scene());
        }catch(Throwable error){abort(error);}
    }
    private void finishScene() {
        JsonObject row=new JsonObject();row.addProperty("name",scene());
        row.addProperty("serverStartedNanos",started);row.addProperty("sceneReadyNanos",ready);
        row.addProperty("submittedNanos",submitted);row.addProperty("submitEndNanos",submitEnd);
        row.addProperty("terminalNanos",terminal);row.addProperty("arrivalNanos",arrival);
        row.addProperty("accepted",accepted);row.addProperty("arrived",arrival!=0);
        row.addProperty("actualShutdown",shutdownScene);
        row.addProperty("macroCalls",macros);row.addProperty("sharedCalls",shared);row.addProperty("windowCalls",windows);
        row.addProperty("pathFinderCalls",stats.calls);
        row.addProperty("firstPathNanos",publication.firstReadyNanos);
        row.add("originalStart",gson.toJsonTree(point(originalStart)));row.add("originalGoal",gson.toJsonTree(point(originalGoal)));
        row.add("start",gson.toJsonTree(point(start)));row.add("goal",gson.toJsonTree(point(goal)));
        row.add("position",gson.toJsonTree(point(mob.blockPosition())));
        JsonArray handles=new JsonArray();
        for(var request:requests) {
            JsonObject h=new JsonObject();h.addProperty("queryId",request.queryId.toString());h.addProperty("state",request.state.name());
            handles.add(h);
        }
        row.add("requests",handles);row.add("searches",searches);row.add("actions",actions);trials.add(row);
        if(!closing) {
            if(query!=null&&!query.adopted)service.consume(query);
            mob.getNavigation().stop();mob.discard();PerformanceSearchTelemetry.ACTORS.remove(mob);
            PublicationTelemetry.ACTORS.remove(mob.getNavigation());
            if(target!=null)target.discard();
            for(var entry:changes.entrySet())level.setBlock(entry.getKey(),entry.getValue(),3);
        }
        mob=null;submitted=0;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void stopped(ServerStoppedEvent event) throws Exception {
        if(submitted!=0&&mob!=null&&shutdownScene) {terminal=System.nanoTime();finishScene();done=true;}
        JsonObject result=new JsonObject();result.addProperty("schema","SHORT_NATIVE_CONTROLS_V1");
        result.addProperty("complete",done&&failure.isEmpty());result.addProperty("failure",failure);
        result.addProperty("cityWorld",city);result.add("scenes",gson.toJsonTree(scenes));result.add("trials",trials);
        Files.writeString(output.resolve("timeline.json"),gson.toJson(result));
        JsonObject shutdown=new JsonObject();shutdown.add("navigation",gson.toJsonTree(service.metrics()));
        shutdown.add("scheduler",gson.toJsonTree(scheduler.metrics()));
        shutdown.addProperty("passed",service.metrics().records()==0&&service.metrics().active()==0&&scheduler.metrics().pendingRequests()==0);
        Files.writeString(output.resolve("shutdown.json"),gson.toJson(shutdown));
    }
    private void abort(Throwable error) {failure=error.toString();error.printStackTrace();stop();}
    private void stop() {closing=true;if(server!=null)server.halt(false);}
}
