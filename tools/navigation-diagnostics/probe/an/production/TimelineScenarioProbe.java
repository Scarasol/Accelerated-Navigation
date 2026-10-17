package an.production;

import an.trace.Trace;
import com.google.gson.*;
import com.scarasol.acceleratednavigation.navigation.*;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

@Mod("antimelineprobe")
public final class TimelineScenarioProbe {
    private record Scene(String name, int distance, int count, String kind) {}
    private record Route(BlockPos start, BlockPos goal, String kind, int tile, int slot) {}
    private static final class Actor {
        final PerformanceComparisonProbe.BenchmarkHusk mob;
        final Route route;
        final Vec3 initial;
        final PerformanceSearchTelemetry.Stats searches = new PerformanceSearchTelemetry.Stats();
        final PublicationTelemetry.Stamp publication = new PublicationTelemetry.Stamp();
        final List<NavigationRequestPath> requests=new ArrayList<>();
        NavigationRequestPath request;
        long submitted, submitEnd, move, arrival, terminal;
        int terminalTick;
        boolean accepted;
        boolean cancelled;
        String state = "PENDING";
        Actor(ServerLevel level, Route route) {
            this.route=route;mob=new PerformanceComparisonProbe.BenchmarkHusk(level);
            mob.setPos(route.start.getX()+.5, route.start.getY()+.01, route.start.getZ()+.5);
            initial=mob.position();
        }
    }
    private final List<Scene> scenes=new ArrayList<>();
    private final List<Actor> actors=new ArrayList<>();
    private final JsonArray results=new JsonArray();
    private JsonArray costSamples=new JsonArray();
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final boolean vanilla=Boolean.getBoolean("an.production.performanceVanilla");
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private int index=-1, ticks, prepareTick, closeTick=-1;
    private long started, ready, submitted, closed, nextSample;
    private Measurements.Snapshot initialCost, finalCost;
    private Object topologyBefore, topologyAfter;
    private Method metrics;
    private boolean closing, done, submittedScene;
    private String failure="";
    private MeleeAttackGoal goal;
    private WrappedGoal wrappedGoal;
    private Actor target;
    private Path aiPath;
    private boolean aiStarted;

    public TimelineScenarioProbe() {
        String navigationLogLevel=System.getProperty("an.trace.navigationLogLevel","");
        if(!navigationLogLevel.isEmpty())
            org.apache.logging.log4j.core.config.Configurator.setLevel(
                    com.scarasol.acceleratednavigation.AcceleratedNavigation.LOGGER.getName(),
                    org.apache.logging.log4j.Level.valueOf(navigationLogLevel));
        Measurements.verify(); Trace.warm(); PerformanceTickTelemetry.register();
        String suite=System.getProperty("an.trace.suite","single");
        if(suite.equals("short-native")) { new ShortNativeScenarioProbe();return; }
        if(suite.equals("final-window")) { new FinalWindowScenarioProbe();return; }
        if(suite.equals("coverage")) { new CoverageScenarioProbe();return; }
        if(suite.equals("single")) {
            scenes.add(new Scene("same8-first-use",8,1,"same"));
            scenes.add(new Scene("same8-repeat",8,1,"same"));
            scenes.add(new Scene("cross8-first-use",8,1,"cross"));
            scenes.add(new Scene("cross8-repeat",8,1,"cross"));
            scenes.add(new Scene("cross28-first-use",28,1,"reference"));
            scenes.add(new Scene("cross28-repeat",28,1,"reference"));
            scenes.add(new Scene("single-wall8",8,8,"wall"));
            scenes.add(new Scene("doorway8",8,8,"door"));
        } else if(suite.equals("burst")) {
            scenes.add(new Scene("warmup8",8,64,"reference"));
            scenes.add(new Scene("warmup28",28,64,"reference"));
            scenes.add(new Scene("burst8",8,512,"reference"));
            scenes.add(new Scene("burst28",28,512,"reference"));
        } else if(suite.equals("supplement")) {
            scenes.add(new Scene("doorway8-known-slots",8,8,"door-known"));
            scenes.add(new Scene("super304",304,1,"long"));
            scenes.add(new Scene("ai-melee-query-adopt",8,1,"ai"));
        } else if(suite.equals("failures")) {
            for(int distance:List.of(8,28)) {
                scenes.add(new Scene("wall"+distance+"-all",distance,64,"wall-all"));
                scenes.add(new Scene("door"+distance+"-all",distance,64,"door-all"));
                scenes.add(new Scene("wall"+distance+"-reverse",distance,64,"wall-reverse"));
                scenes.add(new Scene("door"+distance+"-reverse",distance,64,"door-reverse"));
            }
            scenes.add(new Scene("explicit-range4",8,1,"range4"));
            scenes.add(new Scene("explicit-range12",8,1,"range12"));
            scenes.add(new Scene("cancel-pending",8,1,"cancel-pending"));
            scenes.add(new Scene("cancel-published",8,1,"cancel-published"));
            scenes.add(new Scene("replace-pending",8,1,"replace-pending"));
        } else throw new IllegalArgumentException("Unknown suite");
        String filter=System.getProperty("an.trace.scene","");
        if(!filter.isEmpty()) {
            Set<String> selected=new HashSet<>(Arrays.asList(filter.split(",")));
            if(!scenes.stream().map(Scene::name).toList().containsAll(selected))
                throw new IllegalArgumentException("Unknown scene filter");
            scenes.removeIf(scene->!selected.contains(scene.name));
        }
        MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void isolate(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof Mob && !(event.getEntity() instanceof PerformanceComparisonProbe.BenchmarkHusk))
            event.setCanceled(true);
    }
    @SubscribeEvent public void started(ServerStartedEvent event) {
        started=System.nanoTime();server=event.getServer();level=server.overworld();
        try {
            Field bypass=NavigationPolicy.class.getDeclaredField("bypassEntityIds");bypass.setAccessible(true);
            NavigationPolicy.reloadBypassIds();
            bypass.set(null,vanilla?Set.of(new net.minecraft.resources.ResourceLocation("minecraft","husk")):Set.of());
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
            level.setDayTime(6000);
            Method load=PerformanceComparisonProbe.class.getDeclaredMethod("loadArena",ServerLevel.class);
            load.setAccessible(true);load.invoke(null,level);
            service=SegmentedNavigationService.forServer(server);
            metrics=TopologyService.class.getDeclaredMethod("metrics");metrics.setAccessible(true);
            String base="com.scarasol.acceleratednavigation.";
            for(String name:List.of("navigation.SegmentedNavigationService$Work","topology.TopologyTaskExecutor$Task",
                    "navigation.NavigationRequestPath","topology.TopologyService$MacroRequest"))
                Trace.warmClass(Class.forName(base+name));
            System.out.println("TIMELINE_SERVER_READY "+scenes.size());
        } catch(Throwable error) { abort(error); }
    }
    private static Route reference(int distance,int actor) throws Exception {
        Method method=PerformanceComparisonProbe.class.getDeclaredMethod("route",int.class,int.class);
        method.setAccessible(true);Object route=method.invoke(null,distance,actor);
        List<Object> fields=new ArrayList<>();
        for(String name:List.of("start","goal","kind","tile","slot")) {
            Method getter=route.getClass().getDeclaredMethod(name);getter.setAccessible(true);fields.add(getter.invoke(route));
        }
        return new Route((BlockPos)fields.get(0),(BlockPos)fields.get(1),(String)fields.get(2),(int)fields.get(3),(int)fields.get(4));
    }
    private void prepare() throws Exception {
        index++;
        if(index==scenes.size()) { done=true;stop();return; }
        Scene scene=scenes.get(index);submittedScene=false;ready=0;closeTick=-1;costSamples=new JsonArray();
        goal=null;wrappedGoal=null;target=null;aiPath=null;aiStarted=false;
        for(int a=0;a<scene.count;a++) {
            Route route=switch(scene.kind) {
                case "same","ai","range4","range12","cancel-pending","cancel-published","replace-pending" ->
                        new Route(new BlockPos(-156,81,-147),new BlockPos(-148,81,-147),"flat",0,0);
                case "cross" -> new Route(new BlockPos(-148,81,-147),new BlockPos(-140,81,-147),"flat-cross",0,0);
                case "long" -> new Route(new BlockPos(-156,81,-159),new BlockPos(148,81,-159),"flat-long",0,0);
                case "wall" -> reference(scene.distance,16+a);
                case "door" -> reference(scene.distance,48+a);
                case "door-known" -> reference(scene.distance,112+a);
                case "wall-all","wall-reverse" -> reference(scene.distance,(2+8*(a/8))*8+a%8);
                case "door-all","door-reverse" -> reference(scene.distance,(6+8*(a/8))*8+a%8);
                default -> reference(scene.distance,a);
            };
            if(scene.kind.endsWith("-reverse"))
                route=new Route(route.goal,route.start,route.kind+"-reverse",route.tile,route.slot);
            Actor actor=new Actor(level,route);actors.add(actor);
            if(!level.addFreshEntity(actor.mob))throw new IllegalStateException("Actor rejected");
            PerformanceSearchTelemetry.ACTORS.put(actor.mob,actor.searches);
            PublicationTelemetry.ACTORS.put(actor.mob.getNavigation(),actor.publication);
        }
        if(scene.kind.equals("ai")) {
            target=new Actor(level,new Route(actors.get(0).route.goal,actors.get(0).route.goal,"target",0,0));
            target.mob.setNoAi(true);level.addFreshEntity(target.mob);
            actors.get(0).mob.setTarget(target.mob);
            goal=new MeleeAttackGoal(actors.get(0).mob,1.0,true);
            wrappedGoal=new WrappedGoal(1,goal);
        }
        prepareTick=ticks;
        System.out.println("TIMELINE_PREPARE "+index+" "+scene.name);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void start(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.START||closing)return;
        ticks++;Trace.tick=server.getTickCount();
        try {
            if(closeTick>=0) { finish();return; }
            if(actors.isEmpty()) { if(ticks>40)prepare();return; }
            long now=System.nanoTime();
            if(!submittedScene && ready!=0 && now-ready>=30_000_000_000L && now-started>=30_000_000_000L) {
                Trace.trial=index+1;
                Trace.clock();
                topologyBefore=metrics.invoke(TopologyService.forServer(server));
                initialCost=Measurements.snapshot();submitted=System.nanoTime();submittedScene=true;
                for(Actor actor:actors) {
                    if(AutomaticNavigationBridge.enabled(actor.mob.getNavigation())==vanilla)
                        throw new IllegalStateException("Wrong backend");
                    actor.submitted=System.nanoTime();
                    if(scenes.get(index).kind.equals("replace-pending")) {
                        actor.mob.getNavigation().moveTo(actor.route.goal.getX()+.5,actor.route.goal.getY(),
                                actor.route.goal.getZ()+4.5,1.0);
                        track(actor);
                    }
                    if(scenes.get(index).kind.startsWith("range")) {
                        int range=Integer.parseInt(scenes.get(index).kind.substring(5));
                        NavigationIntent intent=AutomaticNavigationBridge.intent(actor.mob.getNavigation(),
                                Set.of(actor.route.goal),null,true,8,false,1,range);
                        actor.accepted=AutomaticNavigationBridge.move(actor.mob.getNavigation(),intent,1.0);
                    }
                    else if(goal==null) actor.accepted=actor.mob.getNavigation().moveTo(actor.route.goal.getX()+.5,
                            actor.route.goal.getY(),actor.route.goal.getZ()+.5,1.0);
                    else {
                        actor.accepted=wrappedGoal.canUse();
                        if(actor.accepted){wrappedGoal.start();aiStarted=true;}
                    }
                    actor.submitEnd=System.nanoTime();
                    track(actor);
                    if(scenes.get(index).kind.equals("cancel-pending")) {
                        actor.mob.getNavigation().stop();actor.cancelled=true;
                    }
                }
                nextSample=submitted+100_000_000L;
                System.out.println("TIMELINE_SUBMIT "+index+" "+scenes.get(index).name);
            }
            if(submittedScene&&wrappedGoal!=null&&aiStarted&&actors.get(0).arrival==0) {
                if(wrappedGoal.canContinueToUse())wrappedGoal.tick();
                else {wrappedGoal.stop();aiStarted=false;}
                track(actors.get(0));
            }
            if(submittedScene&&scenes.get(index).kind.equals("cancel-published"))for(Actor actor:actors)
                if(!actor.cancelled&&actor.publication.firstReadyNanos!=0) {
                    actor.mob.getNavigation().stop();actor.cancelled=true;
                }
        } catch(Throwable error) {abort(error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.END||closing)return;
        try {
            if(actors.isEmpty()||closeTick>=0)return;
            long now=System.nanoTime();
            if(!submittedScene) {
                if(ready==0&&ticks-prepareTick>=20&&actors.stream().allMatch(a->a.mob.onGround()
                        &&a.mob.blockPosition().equals(a.route.start))) {
                    ready=now;System.out.println("TIMELINE_SCENE_READY "+index);
                }
                return;
            }
            if(now>=nextSample) {
                costSamples.add(gson.toJsonTree(Measurements.snapshot()));
                nextSample=now+100_000_000L;
            }
            for(Actor actor:actors) {
                track(actor);
                if(actor.move==0&&actor.mob.position().distanceTo(actor.initial)>.05)actor.move=now;
                if(actor.arrival==0&&actor.mob.blockPosition().distManhattan(actor.route.goal)<=1)actor.arrival=now;
                if(actor.terminal!=0)continue;
                if(actor.cancelled)actor.state="CANCELLED";
                else if(!actor.accepted)actor.state="REJECTED";
                else if(actor.request!=null&&actor.request.terminal())actor.state=actor.request.state.name();
                else if(vanilla&&actor.mob.getNavigation().isDone())actor.state=actor.arrival!=0?"SUCCEEDED":"EXHAUSTED";
                else if(now-submitted>90_000_000_000L)actor.state="TIMEOUT";
                else continue;
                actor.terminal=now;actor.terminalTick=ticks;
            }
            if(actors.stream().allMatch(a->a.terminal!=0&&ticks-a.terminalTick>=10)) {
                closed=System.nanoTime();finalCost=Measurements.snapshot();
                topologyAfter=metrics.invoke(TopologyService.forServer(server));
                closeTick=ticks;Trace.clock();Trace.trial=0;
            }
        }catch(Throwable error){abort(error);}
    }
    private void track(Actor actor) {
        NavigationRequestPath current=service.active(actor.mob.getNavigation());
        if(current!=null&&current!=actor.request) {
            actor.request=current;
            if(!actor.requests.contains(current))actor.requests.add(current);
            actor.terminal=0;actor.state="PENDING";
            Trace.bind(actor.mob.getNavigation(),current,actor.mob);
        }
    }
    private void finish() throws Exception {
        Scene scene=scenes.get(index);
        JsonObject row=new JsonObject();
        row.addProperty("trial",index+1);row.add("scene",gson.toJsonTree(scene));
        row.addProperty("serverStartedNanos",started);row.addProperty("sceneReadyNanos",ready);
        row.addProperty("submittedNanos",submitted);row.addProperty("closedNanos",closed);
        row.add("costStart",gson.toJsonTree(initialCost));row.add("costEnd",gson.toJsonTree(finalCost));
        row.add("costSamples",costSamples.deepCopy());
        row.add("topologyBefore",gson.toJsonTree(topologyBefore));row.add("topologyAfter",gson.toJsonTree(topologyAfter));
        JsonArray all=new JsonArray();
        for(Actor actor:actors) {
            JsonObject a=new JsonObject();
            a.addProperty("tile",actor.route.tile);a.addProperty("slot",actor.route.slot);a.addProperty("kind",actor.route.kind);
            a.add("start",gson.toJsonTree(List.of(actor.route.start.getX(),actor.route.start.getY(),actor.route.start.getZ())));
            a.add("goal",gson.toJsonTree(List.of(actor.route.goal.getX(),actor.route.goal.getY(),actor.route.goal.getZ())));
            a.addProperty("sameSection",SectionPos.of(actor.route.start).equals(SectionPos.of(actor.route.goal)));
            a.addProperty("queryId",actor.request==null?"":actor.request.queryId.toString());
            a.add("queryIds",gson.toJsonTree(actor.requests.stream().map(request->request.queryId.toString()).toList()));
            a.addProperty("firstPathScope","navigation across request generations");
            JsonArray generations=new JsonArray();
            for(NavigationRequestPath request:actor.requests) {
                JsonObject generation=new JsonObject();generation.addProperty("queryId",request.queryId.toString());
                generation.addProperty("state",request.state.name());generation.addProperty("accuracy",request.intent.accuracy());
                generation.addProperty("adopted",request.adopted);generations.add(generation);
            }
            a.add("requestGenerations",generations);
            a.addProperty("accepted",actor.accepted);a.addProperty("state",actor.state);
            a.addProperty("submitStartNanos",actor.submitted);a.addProperty("submitEndNanos",actor.submitEnd);
            a.addProperty("firstReadyNanos",actor.publication.firstReadyNanos);
            a.addProperty("firstMoveNanos",actor.move);a.addProperty("arrivalNanos",actor.arrival);
            a.addProperty("terminalNanos",actor.terminal);a.add("search",gson.toJsonTree(actor.searches));
            a.add("finalPosition",gson.toJsonTree(List.of(actor.mob.getX(),actor.mob.getY(),actor.mob.getZ())));
            all.add(a);
            actor.mob.getNavigation().stop();actor.mob.discard();
            PerformanceSearchTelemetry.ACTORS.remove(actor.mob);
            PublicationTelemetry.ACTORS.remove(actor.mob.getNavigation());
        }
        if(wrappedGoal!=null&&aiStarted)wrappedGoal.stop();
        if(target!=null)target.mob.discard();
        row.add("actors",all);results.add(row);actors.clear();closeTick=-1;
        System.out.println("TIMELINE_FINISH "+index+" "+scene.name);
        write();
        if(index+1==scenes.size()){done=true;stop();}
    }
    private void write() throws Exception {
        JsonObject root=new JsonObject();root.addProperty("schema","NAVIGATION_TIMELINE_V1");
        root.addProperty("diagnostic",true);root.addProperty("deepTrace",Trace.ENABLED);
        root.addProperty("vanillaSameRuntime",vanilla);root.addProperty("complete",done);
        root.addProperty("failure",failure);root.add("scenes",gson.toJsonTree(scenes));root.add("trials",results);
        Files.writeString(output.resolve("timeline.json"),gson.toJson(root));
        FailureSearchTelemetry.write(output);
    }
    private void abort(Throwable error){failure=error.toString();error.printStackTrace();stop();}
    private void stop(){if(closing)return;closing=true;Trace.trial=0;try{write();}catch(Exception e){e.printStackTrace();}server.halt(false);}
    @SubscribeEvent public void stopped(ServerStoppedEvent event) throws Exception {
        JsonObject row=new JsonObject();row.addProperty("passed",done&&failure.isEmpty()&&service.metrics().records()==0
                &&NavigationScheduler.forServer(server).metrics().pendingRequests()==0);
        row.add("service",gson.toJsonTree(service.metrics()));Files.writeString(output.resolve("shutdown.json"),gson.toJson(row));
    }
}
