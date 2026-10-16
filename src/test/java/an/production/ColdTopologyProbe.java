package an.production;

import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** One first-path request per fresh JVM, with identical baseline/candidate input. */
public final class ColdTopologyProbe {
    public static final class Actor extends Husk {
        Actor(ServerLevel level) {
            super(EntityType.HUSK,level);setBaby(false);setPersistenceRequired();setInvulnerable(true);
        }
        @Override protected void registerGoals() {}
        @Override protected boolean convertsInWater(){return false;}
        @Override public boolean shouldBeSaved(){return false;}
    }
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final String route=System.getProperty("an.production.coldRoute","same");
    private final List<Map<String,Object>> checks=new ArrayList<>();
    private final Map<String,Object> result=new LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel level;
    private Actor actor;
    private SegmentedNavigationService service;
    private TopologyService topology;
    private NavigationScheduler scheduler;
    private NavigationRequestPath request;
    private BlockPos goal;
    private int ticks,submittedTick;
    private long submittedNanos,firstPathNanos;
    private long readyNanos;
    private long wholeBefore;
    private boolean closing;
    private Throwable failure;
    private void check(String name,boolean passed,Object detail) {
        checks.add(Map.of("name",name,"passed",passed,"detail",detail));
        if(!passed)throw new AssertionError(name+": "+detail);
    }
    private Map<?,?> sharedMetrics() {
        try{return (Map<?,?>)TopologyService.class.getMethod("sharedTopologyMetrics").invoke(topology);}
        catch(NoSuchMethodException baseline){return Map.of("available",false);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private static Object field(Object value,String name) throws ReflectiveOperationException {
        var member=value.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(value);
    }
    private static Object call(Object value,String name) throws ReflectiveOperationException {
        var method=value.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(value);
    }
    private Object workerMetrics() throws ReflectiveOperationException {
        return call(call(topology,"metrics"),"worker");
    }
    private Map<String,Object> graphColdState(Object geometry) throws ReflectiveOperationException {
        Object runtime=field(topology,"runtime"),lock=field(runtime,"runtimeLock");
        synchronized(lock) {
            int matchingViews=0;
            var facts=new java.util.TreeMap<String,String>();
            for(Object entry:((Map<?,?>)field(runtime,"clusters")).values()) {
                Object view=((Map<?,?>)field(entry,"views")).get(geometry);
                if(view!=null&&field(view,"topology")!=null)matchingViews++;
                Object key=field(entry,"key");
                var getter=key.getClass().getDeclaredMethod("section");getter.setAccessible(true);
                var section=(net.minecraft.core.SectionPos)getter.invoke(key);
                if(section.x()>=0&&section.x()<=5&&section.y()>=4&&section.y()<=5
                        &&section.z()>=0&&section.z()<=1) {
                    Object packed=field(entry,"facts");
                    facts.put(section.x()+","+section.y()+","+section.z(),
                            packed==null?"absent":String.valueOf(call(packed,"fingerprint")));
                }
            }
            return Map.of("matchingPublishedViews",matchingViews,"selectedFactFingerprints",facts);
        }
    }
    @SubscribeEvent public void start(ServerStartedEvent event) {
        server=event.getServer();level=server.overworld();
        try {
            Files.createDirectories(output);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(18000);
            for(int x=-1;x<=6;x++)for(int z=-1;z<=2;z++){
                level.setChunkForced(x,z,true);level.getChunk(x,z);
            }
            for(int x=0;x<96;x++)for(int z=0;z<32;z++)for(int y=80;y<=84;y++)
                level.setBlock(new BlockPos(x,y,z),(y==80?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            actor=new Actor(level);
            actor.getNavigation().getNodeEvaluator().setCanPassDoors(true);
            actor.getNavigation().getNodeEvaluator().setCanOpenDoors(false);
            actor.setPos(3.5,81.01,7.5);
            check("actor added",level.addFreshEntity(actor),"Husk adult .6x1.95");
            goal=new BlockPos(switch(route){case "same"->12;case "base"->28;case "super"->72;
                default->throw new IllegalArgumentException(route);},81,7);
            topology=TopologyService.forServer(server);service=SegmentedNavigationService.forServer(server);
            scheduler=NavigationScheduler.forServer(server);
            readyNanos=System.nanoTime();
            result.put("route",route);result.put("input","flat seed20261013; x0..95/z0..31/y80stone/y81..84air");
            result.put("start",List.of(3,81,7));result.put("goal",List.of(goal.getX(),81,7));
        }catch(Throwable error){finish(error);}
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(closing||event.getServer()!=server||event.phase!=TickEvent.Phase.END)return;
        ticks++;
        try {
            if(request==null) {
                if(System.nanoTime()-readyNanos<30_000_000_000L)return;
                if(ticks<30)return;
                check("actor settled",actor.onGround()&&actor.blockPosition().equals(new BlockPos(3,81,7)),actor.position());
                check("adapter enabled",AutomaticNavigationBridge.enabled(actor.getNavigation()),"formal automatic");
                result.put("sharedBefore",sharedMetrics());result.put("workerBefore",workerMetrics());
                result.put("preSubmitTelemetry",ColdTopologyTelemetry.snapshot());
                var intent=AutomaticNavigationBridge.movementIntent(actor.getNavigation(),goal,null,0);
                result.put("profile",intent.capabilities().profile().toString());
                var geometry=intent.capabilities().profile().geometry(
                        com.scarasol.acceleratednavigation.topology.BaseClusterTopology.Channel.GROUND);
                result.put("geometry",geometry.toString());
                var cold=graphColdState(geometry);result.put("graphColdBefore",cold);
                check("requested body has no published matching Base",((Number)cold.get("matchingPublishedViews")).intValue()==0,cold);
                wholeBefore=service.metrics().nativeWholeCalls();
                submittedTick=server.getTickCount();submittedNanos=System.nanoTime();
                check("formal move accepted",AutomaticNavigationBridge.delegate(this,1,"TICK",
                        ()->AutomaticNavigationBridge.move(actor.getNavigation(),intent,1)),goal);
                request=service.active(actor.getNavigation());
                check("request retained",request!=null,goal);
                return;
            }
            if(request.getNodeCount()>0) {
                firstPathNanos=System.nanoTime();
                check("real detailed search",service.metrics().nativeCalls()>0,service.metrics().nativeCalls());
                check("no whole fallback",service.metrics().nativeWholeCalls()==wholeBefore,service.metrics().nativeWholeCalls());
                check("actual returned path endpoint",request.getEndNode()!=null,request.getNodeCount());
                result.put("firstPathNodes",request.getNodeCount());result.put("firstPathCanReach",request.canReach());
                result.put("firstPathEnd",request.getEndNode().asBlockPos().toString());
                result.put("requestStateAtFirstPath",request.state);result.put("sharedAfter",sharedMetrics());
                result.put("workerAtFirstPath",workerMetrics());result.put("navigation",service.metrics());
                finish(null);return;
            }
            if(request.terminal())throw new AssertionError("no first path: "+request.state);
            if(System.nanoTime()-submittedNanos>90_000_000_000L)throw new AssertionError("cold request timeout "+request.state);
        }catch(Throwable error){finish(error);}
    }
    private void finish(Throwable error) {
        if(closing)return;closing=true;failure=error;
        if(error!=null)error.printStackTrace();
        if(actor!=null){actor.getNavigation().stop();actor.discard();}
        server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        if(event.getServer()!=server)return;
        try{
            check("service records cleared",service!=null&&service.metrics().records()==0,
                    service==null?"absent":service.metrics().records());
            check("scheduler drained",scheduler!=null&&scheduler.metrics().pendingRequests()==0,"shutdown");
        }catch(Throwable error){if(failure==null)failure=error;}
        result.put("schema","ACTUAL_TOPOLOGY_FIRST_PATH_DIAGNOSIS_V1");
        result.put("passed",failure==null&&firstPathNanos>submittedNanos);
        result.put("failed",failure!=null||firstPathNanos<=submittedNanos);
        result.put("failure",failure==null?"":failure.toString());result.put("checks",checks);
        result.put("submittedNanos",submittedNanos);result.put("firstPathNanos",firstPathNanos);
        result.put("firstPathWaitNanos",firstPathNanos-submittedNanos);result.put("submittedTick",submittedTick);
        result.put("jvmPid",ProcessHandle.current().pid());result.put("telemetry",ColdTopologyTelemetry.snapshot());
        result.put("scope","server first installed genuine Path, not completed movement; fresh JVM/fresh fixed world, existing prewarm retained");
        try{Files.writeString(output.resolve("cold-topology.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
}
