package an.production;

import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.SharedSectionCandidate;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Naturally moving actors use the real service and the actual release Mixin chain. */
public final class SharedTopologyProductionProbe {
    private static final List<String> CASES = List.of("flat","reuse-flat","narrow","wood-open",
            "wood-closed-detour","iron-negative","input-change","cancel","replace",
            "native-stop-resume","cross-section","small-legacy",
            "capacity-fallback","generic-rejection","unavailable-rejection");
    public static final class Actor extends Husk {
        Actor(ServerLevel level) {
            super(EntityType.HUSK,level);
            setBaby(false);setPersistenceRequired();setInvulnerable(true);
        }
        @Override protected void registerGoals() {
            goalSelector.addGoal(0,new FloatGoal(this));
            goalSelector.addGoal(1,new OpenDoorGoal(this,true));
        }
        @Override protected boolean convertsInWater() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
        @Override protected boolean shouldDespawnInPeaceful() { return false; }
    }
    public static final class SmallActor extends Pig {
        SmallActor(ServerLevel level) {
            super(EntityType.PIG,level);setPersistenceRequired();setInvulnerable(true);
        }
        @Override protected void registerGoals() {}
        @Override public boolean shouldBeSaved() { return false; }
    }

    private final java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final List<Map<String,Object>> checks = new ArrayList<>(),cases = new ArrayList<>(),positions = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private TopologyService topology;
    private SegmentedNavigationService service;
    private Mob actor;
    private NavigationRequestPath request,replaced;
    private BlockPos goal;
    private int index,tick,started,submitted;
    private int stopTick;
    private long sharedBefore,nativeBefore,segmentsBefore,wholeBefore,baseBefore,changeCalls;
    private long fallbackBefore;
    private Object sharedRuntime;
    private java.lang.reflect.Field slotLimit;
    private int previousSlotLimit;
    private boolean changed,closing;
    private String phase;
    private Throwable failure;

    private void check(String label,boolean passed,Object detail) {
        checks.add(Map.of("case",index<CASES.size()?CASES.get(index):"close",
                "name",label,"passed",passed,"detail",detail));
        if(!passed)throw new AssertionError(label+": "+detail);
    }
    private int offset() { return switch(CASES.get(index)) {
        case "capacity-fallback" -> 32; case "generic-rejection" -> 48;
        case "unavailable-rejection" -> 64; default -> 0;
    }; }
    private BlockPos point(int x,int y,int z) { return new BlockPos(x+offset(),80+y,z); }
    private void block(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state) {
        level.setBlock(point(x,y,z),state,2);
    }
    private void setup() {
        String name=CASES.get(index);
        if(!name.equals("reuse-flat")) {
            int length=name.equals("cross-section")?32:16;
            for(int x=0;x<length;x++)for(int z=0;z<16;z++)for(int y=0;y<5;y++)
                block(x,y,z,x>0&&x<length-1&&z>0&&z<15&&y>=1&&y<=3
                        ?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
            if(name.equals("narrow")||name.startsWith("wood")||name.startsWith("iron"))
                for(int z=1;z<15;z++)for(int y=1;y<=3;y++)
                    if(z!=7&&!(name.equals("wood-closed-detour")&&z==11))
                        block(8,y,z,Blocks.STONE.defaultBlockState());
            if(name.startsWith("wood")||name.startsWith("iron")) {
                var door=(name.startsWith("iron")?Blocks.IRON_DOOR:Blocks.OAK_DOOR).defaultBlockState()
                        .setValue(DoorBlock.FACING,Direction.EAST).setValue(DoorBlock.OPEN,false)
                        .setValue(DoorBlock.HINGE,DoorHingeSide.LEFT);
                block(8,1,7,door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER));
                block(8,2,7,door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
                block(8,3,7,Blocks.STONE.defaultBlockState());
            }
        }
        actor=name.equals("small-legacy")?new SmallActor(level):new Actor(level);
        actor.getNavigation().getNodeEvaluator().setCanPassDoors(true);
        actor.getNavigation().getNodeEvaluator().setCanOpenDoors(name.equals("wood-open"));
        actor.setPos(3.5+offset(),81.01,7.5);
        check("actual actor added",level.addFreshEntity(actor),actor.getType().toString());
        goal=point(name.equals("cross-section")?28:12,1,7);
        request=null;replaced=null;changed=false;
        started=tick;phase="PREPARE";
    }
    private NavigationRequestPath move(BlockPos target,long generation) {
        var navigation=actor.getNavigation();
        var intent=AutomaticNavigationBridge.movementIntent(navigation,target,null,0);
        check("production automatic adapter enabled",AutomaticNavigationBridge.enabled(navigation),
                Map.of("navigation",navigation.getClass().getName(),"removed",actor.isRemoved(),
                        "removal",String.valueOf(actor.getRemovalReason()),"position",actor.position().toString(),
                        "currentNavigation",actor.getNavigation()==navigation,"automatic",NavigationPolicy.automaticEnabled(),
                        "policy",NavigationPolicy.modeFor(actor).toString()));
        boolean accepted=AutomaticNavigationBridge.delegate(this,generation,"TICK",
                ()->AutomaticNavigationBridge.move(navigation,intent,1));
        check("formal production movement accepted",accepted,target.toShortString());
        var active=service.active(navigation);
        check("real request retained",active!=null,target.toShortString());
        return active;
    }
    private void submit() {
        sharedBefore=service.sharedSectionRequests();nativeBefore=service.metrics().nativeCalls();
        segmentsBefore=service.sharedSectionSegments();wholeBefore=service.metrics().nativeWholeCalls();
        baseBefore=topology.sharedTopologyMetrics().get("baseBuilds");
        fallbackBefore=service.sharedSectionFallbacks();
        String name=CASES.get(index);
        try {
            if(name.equals("capacity-fallback"))slotLimit.setInt(sharedRuntime,0);
            if(name.equals("generic-rejection"))SharedRejectionControls.rejectSubmission=true;
            if(name.equals("unavailable-rejection"))SharedRejectionControls.rejectUnavailable=true;
        } catch(ReflectiveOperationException failure) { throw new RuntimeException(failure); }
        if(index==0) {
            var profile=AutomaticNavigationBridge.movementIntent(actor.getNavigation(),goal,null,0).capabilities().profile();
            var unsupported=topology.requestSharedSection(level,profile,actor.blockPosition(),point(28,1,7),
                    ()->true,Long.MAX_VALUE);
            check("scope rejection is typed and starts no work",unsupported.isCompletedExceptionally()
                    &&unsupported.handle((value,error)->error instanceof SharedSectionCandidate.UnsupportedInputException).join(),
                    topology.sharedTopologyMetrics());
        }
        request=move(goal,1);submitted=tick;phase="MOVING";
    }
    private void endCase() {
        String name=CASES.get(index);
        boolean negative=name.equals("iron-negative")||name.endsWith("-rejection"),cancel=name.equals("cancel");
        boolean arrived=actor.blockPosition().equals(goal);
        check("expected production terminal",negative?request.state==NavigationRequestPath.State.UNAVAILABLE
                :cancel?request.state==NavigationRequestPath.State.CANCELLED
                :request.state==NavigationRequestPath.State.SUCCEEDED&&arrived,request.state+" "+actor.position());
        boolean legacy=name.equals("cross-section")||name.equals("small-legacy");
        check("candidate branch matches declared scope",legacy?service.sharedSectionRequests()==sharedBefore
                :service.sharedSectionRequests()>sharedBefore,service.sharedSectionRequests()-sharedBefore);
        check("no whole native movement fallback",service.metrics().nativeWholeCalls()==wholeBefore,service.metrics().nativeWholeCalls()-wholeBefore);
        if(!negative&&!cancel)check("genuine native segments executed",service.metrics().nativeCalls()>nativeBefore,service.metrics().nativeCalls()-nativeBefore);
        if(name.equals("reuse-flat")) {
            check("unchanged section reuses one shared basis",topology.sharedTopologyMetrics().get("baseBuilds")==baseBefore,baseBefore);
            check("warm request uses capture reuse",topology.sharedTopologyMetrics().get("fastHits")>0,topology.sharedTopologyMetrics());
        }
        if(name.equals("input-change"))check("changed input triggered new real search",
                changed&&service.metrics().nativeCalls()>changeCalls,service.metrics().nativeCalls()-changeCalls);
        if(name.equals("replace"))check("replaced request cancelled",replaced!=null
                &&replaced.state==NavigationRequestPath.State.CANCELLED,String.valueOf(replaced==null?null:replaced.state));
        if(name.equals("native-stop-resume"))check("native stopped task remained exhausted",replaced!=null
                &&replaced.state==NavigationRequestPath.State.EXHAUSTED,String.valueOf(replaced==null?null:replaced.state));
        if(name.equals("capacity-fallback")) {
            check("typed capacity rejection enters existing macro exactly once",service.sharedSectionFallbacks()-fallbackBefore==1,service.sharedSectionFallbacks()-fallbackBefore);
            check("capacity fallback makes real native segment",service.metrics().nativeCalls()>nativeBefore,service.metrics().nativeCalls()-nativeBefore);
            check("capacity fallback creates no shared anchor stages",service.sharedSectionSegments()==segmentsBefore,service.sharedSectionSegments()-segmentsBefore);
            try { slotLimit.setInt(sharedRuntime,previousSlotLimit); }
            catch(ReflectiveOperationException failure) { throw new RuntimeException(failure); }
        }
        if(name.endsWith("-rejection")) {
            check("generic rejection has no macro fallback",service.sharedSectionFallbacks()==fallbackBefore,service.sharedSectionFallbacks()-fallbackBefore);
            check("generic rejection has no native movement",service.metrics().nativeCalls()==nativeBefore,service.metrics().nativeCalls()-nativeBefore);
            check("actual controlled generic rejection hook ran",name.equals("generic-rejection")
                    ?SharedRejectionControls.submissionHits==1:SharedRejectionControls.unavailableHits==1,name);
        }
        cases.add(Map.ofEntries(Map.entry("name",name),Map.entry("state",request.state),
                Map.entry("arrived",arrived),Map.entry("ticks",tick-submitted),
                Map.entry("sharedRequests",service.sharedSectionRequests()-sharedBefore),
                Map.entry("sharedSegments",service.sharedSectionSegments()-segmentsBefore),
                Map.entry("nativeCalls",service.metrics().nativeCalls()-nativeBefore),
                Map.entry("sharedFallbacks",service.sharedSectionFallbacks()-fallbackBefore),
                Map.entry("position",List.of(actor.getX(),actor.getY(),actor.getZ())),
                Map.entry("shared",topology.sharedTopologyMetrics())));
        actor.getNavigation().stop();actor.discard();actor=null;
        if(++index==CASES.size())finish(null);else setup();
    }
    @SubscribeEvent public void start(ServerStartedEvent event) {
        server=event.getServer();level=server.overworld();
        try {
            Files.createDirectories(output);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.setDayTime(18000);
            for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++) {
                level.setChunkForced(x,z,true);level.getChunk(x,z);
            }
            topology=TopologyService.forServer(server);service=SegmentedNavigationService.forServer(server);
            var runtimeField=TopologyService.class.getDeclaredField("runtime");runtimeField.setAccessible(true);
            var runtime=runtimeField.get(topology);
            var sharedField=runtime.getClass().getDeclaredField("shared");sharedField.setAccessible(true);
            sharedRuntime=sharedField.get(runtime);
            slotLimit=sharedRuntime.getClass().getDeclaredField("maxSlots");slotLimit.setAccessible(true);
            previousSlotLimit=slotLimit.getInt(sharedRuntime);
            setup();
        } catch(Throwable problem) { finish(problem); }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(closing||event.getServer()!=server||event.phase!=TickEvent.Phase.END)return;
        tick++;
        try {
            if(phase.equals("PREPARE")) { if(tick-started>=30)submit();return; }
            String name=CASES.get(index);
            if(tick%4==0)positions.add(Map.of("case",name,"tick",tick,
                    "position",List.of(actor.getX(),actor.getY(),actor.getZ()),"state",request.state,
                    "nativeCalls",service.metrics().nativeCalls(),"sharedRequests",service.sharedSectionRequests()));
            if(!changed&&name.equals("cancel")&&tick-submitted>=2) {
                actor.getNavigation().stop();changeCalls=service.metrics().nativeCalls();changed=true;phase="CANCEL_SETTLE";
                return;
            }
            if(!changed&&name.equals("replace")&&tick-submitted>=2) {
                replaced=request;goal=point(3,1,12);request=move(goal,2);changed=true;
            }
            if(!changed&&name.equals("input-change")&&request.getNodeCount()>0&&tick-submitted>=8) {
                changeCalls=service.metrics().nativeCalls();
                block(7,1,7,Blocks.STONE.defaultBlockState());changed=true;
            }
            if(!changed&&name.equals("native-stop-resume")&&request.getNodeCount()>0&&tick-submitted>=8) {
                replaced=request;
                AutomaticNavigationBridge.nativeStop(actor.getNavigation(),()->actor.getNavigation().stop());
                changeCalls=service.metrics().nativeCalls();stopTick=tick;changed=true;phase="NATIVE_STOP_SETTLE";
                return;
            }
            if(phase.equals("NATIVE_STOP_SETTLE")) {
                if(tick-stopTick<4)return;
                check("native-stop directly exhausts shared task",replaced.state==NavigationRequestPath.State.EXHAUSTED,replaced.state);
                check("native-stop adds no real searches",service.metrics().nativeCalls()==changeCalls,
                        service.metrics().nativeCalls()-changeCalls);
                check("native-stop clears active task",service.active(actor.getNavigation())==null,service.metrics().records());
                request=move(goal,2);phase="MOVING";
                return;
            }
            if(request.terminal()&&(!name.equals("cancel")||tick-submitted>=15)) {
                if(name.equals("cancel"))check("cancel rejects delayed native delivery",
                        service.metrics().nativeCalls()==changeCalls,service.metrics().nativeCalls()-changeCalls);
                endCase();return;
            }
            if(tick-submitted>900)throw new AssertionError(name+" timeout: "+request.state+" "+actor.position());
        } catch(Throwable problem) { finish(problem); }
    }
    private void finish(Throwable problem) {
        if(closing)return;
        closing=true;failure=problem;
        if(problem!=null)problem.printStackTrace();
        if(actor!=null) { actor.getNavigation().stop();actor.discard(); }
        server.halt(false);
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        if(event.getServer()!=server)return;
        try {
            var shared=topology.sharedTopologyMetrics();
            check("shared close releases slots and work",shared.get("slots")==0&&shared.get("flights")==0
                    &&shared.get("captures")==0&&shared.get("retained")==0,shared);
            check("production navigation records zero",service.metrics().records()==0,service.metrics().records());
        } catch(Throwable problem) { if(failure==null)failure=problem; }
        var result=new LinkedHashMap<String,Object>();
        result.put("passed",failure==null&&index==CASES.size());
        result.put("failed",failure!=null||index!=CASES.size());
        result.put("failure",failure==null?"":failure.toString());
        result.put("checks",checks);result.put("cases",cases);result.put("positions",positions);
        result.put("jvmPid",ProcessHandle.current().pid());result.put("scope","ACTUAL_PRODUCTION_SHARED_SECTION_V1");
        try { Files.writeString(output.resolve("shared-production.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
        catch(Exception problem) { throw new RuntimeException(problem); }
    }
}
