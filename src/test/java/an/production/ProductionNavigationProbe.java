package an.production;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import com.scarasol.acceleratednavigation.navigation.NavigationCapabilities;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.mixin.WallNavigationAccess;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Actual production classes, original GoalSelector/Brain and naturally moving actors. */
@Mod("an_navigation_verification")
public final class ProductionNavigationProbe {
    private final java.nio.file.Path output=java.nio.file.Path.of(System.getProperty("an.production.output"));
    private final JsonArray checks=new JsonArray(),routes=new JsonArray(),ticks=new JsonArray(),samples=new JsonArray(),segments=new JsonArray();
    private final List<ChunkPos> held=new ArrayList<>();
    private final List<Mob> actors=new ArrayList<>();
    private final List<Mob> nativeTypes=new ArrayList<>();
    private final List<TestZombie> crowd=new ArrayList<>();
    private final List<TestPig> crowdTargets=new ArrayList<>();
    private final java.util.Set<Mob> crowdFinished=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private MinecraftServer server;
    private ServerLevel level;
    private SegmentedNavigationService service;
    private NavigationScheduler scheduler;
    private Mob pig;
    private TestPig target;
    private TestZombie melee;
    private TestVillager villager;
    private MoveToTargetSink sink;
    private BehaviorControl<net.minecraft.world.entity.PathfinderMob> poi;
    private NavigationRequestPath request;
    private JsonArray routeInputs;
    private int tick,phase,phaseTick,routeIndex=Integer.getInteger("an.production.routeStart",0);
    private boolean closing;
    private int targetMoves;
    private int recomputations;
    private int semanticChanges;
    private int semanticSearches;
    private long searchBeforeChange=-1,wholeBeforePartial;
    private long deferredNativeBefore,deferredDecisionBefore,deferredWholeBefore,deferredSegmentsBefore;
    private boolean sawSegmentPartial;
    private BlockPos selectedDecisionTarget;
    private boolean poiPending;
    private long tickStarted,chargedBefore,poiLegacy;
    private int peakActive;
    private String lastSegment="";
    private Throwable failure;
    public ProductionNavigationProbe() {
        PerformanceTickTelemetry.register();
        MinecraftForge.EVENT_BUS.register(Boolean.getBoolean("an.production.tickBoundaryLoad")
                ? new TickBoundaryLoadProbe() : Boolean.getBoolean("an.production.partialReplay")
                ? new PartialReplayProbe() : Boolean.getBoolean("an.production.waitBoundaries")
                ? new HandoffWaitBoundaryProbe() : Boolean.getBoolean("an.production.performanceComparison")
                ? new PerformanceComparisonProbe() : Boolean.getBoolean("an.production.handoffBoundaries")
                ? new HandoffBoundaryProbe() : Boolean.getBoolean("an.production.speedDiagnosis")
                ? new SpeedHandoffProbe() : Boolean.getBoolean("an.production.commandOnly")
                ? new NavigationCommandProbe() : this);
    }
    public static final class TestPig extends Pig {
        public TestPig(ServerLevel level) { super(EntityType.PIG,level);setPersistenceRequired();setInvulnerable(true); }
        @Override protected void registerGoals() {}
    }
    public static final class TestZombie extends Zombie {
        public TestZombie(ServerLevel level) { super(EntityType.ZOMBIE,level);setPersistenceRequired();setInvulnerable(true); }
        @Override protected void registerGoals() { goalSelector.addGoal(1,new MeleeAttackGoal(this,1,true)); }
        @Override protected boolean isSunSensitive() { return false; }
    }
    public static final class TestVillager extends Villager {
        public TestVillager(ServerLevel level) { super(EntityType.VILLAGER,level);setPersistenceRequired();setInvulnerable(true); }
        @Override protected void registerGoals() {}
        @Override protected void customServerAiStep() { getBrain().tick((ServerLevel)level(),this); }
    }
    public static final class TestBee extends net.minecraft.world.entity.animal.Bee {
        public TestBee(ServerLevel level) { super(EntityType.BEE,level);setPersistenceRequired(); }
    }
    public static final class TestDolphin extends net.minecraft.world.entity.animal.Dolphin {
        public TestDolphin(ServerLevel level) { super(EntityType.DOLPHIN,level);setPersistenceRequired(); }
        @Override protected void registerGoals() {}
    }
    public static final class TestFrog extends net.minecraft.world.entity.animal.frog.Frog {
        public TestFrog(ServerLevel level) { super(EntityType.FROG,level);setPersistenceRequired(); }
        @Override protected void customServerAiStep() {}
    }
    public static final class TestSpider extends net.minecraft.world.entity.monster.Spider {
        public TestSpider(ServerLevel level) { super(EntityType.SPIDER,level);setPersistenceRequired(); }
        @Override protected void registerGoals() {}
    }
    private void check(String name,boolean passed,Object value) {
        JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("passed",passed);
        row.add("value",new GsonBuilder().create().toJsonTree(value));checks.add(row);
        if(!passed)throw new AssertionError(name+": "+value);
    }
    private <T extends Mob> T spawn(T actor,BlockPos position) {
        actor.setPos(position.getX()+.5,position.getY()+.01,position.getZ()+.5);
        level.addFreshEntity(actor);actors.add(actor);return actor;
    }
    private static BlockPos pos(JsonArray array) { return new BlockPos(array.get(0).getAsInt(),array.get(1).getAsInt(),array.get(2).getAsInt()); }
    @SubscribeEvent public void start(ServerStartedEvent event) {
        server=event.getServer();level=server.overworld();
        try {
            ProductionBudgetChecks.run(server);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(18000);
            routeInputs=JsonParser.parseString(Files.readString(output.resolve("stages.json"))).getAsJsonArray();
            for(int x=-4;x<=8;x++)for(int z=-19;z<=3;z++) {
                level.setChunkForced(x,z,true);level.getChunk(x,z);held.add(new ChunkPos(x,z));
            }
            for(int x=0;x<=48;x++)for(int z=0;z<=50;z++) {
                level.setBlock(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState(),2);
                for(int y=81;y<=84;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
            }
            level.setBlock(new BlockPos(14,80,4),Blocks.MAGMA_BLOCK.defaultBlockState(),2);
            level.setBlock(new BlockPos(14,81,5),Blocks.RAIL.defaultBlockState(),2);
            pig=spawn(new TestZombie(level),new BlockPos(3,81,3));
            target=spawn(new TestPig(level),new BlockPos(26,81,3));
            nativeTypes.add(spawn(new TestBee(level),new BlockPos(40,81,2)));
            nativeTypes.add(spawn(new TestDolphin(level),new BlockPos(40,81,7)));
            nativeTypes.add(spawn(new TestFrog(level),new BlockPos(40,81,12)));
            nativeTypes.add(spawn(new TestSpider(level),new BlockPos(40,81,26)));
            service=SegmentedNavigationService.forServer(server);
            scheduler=NavigationScheduler.forServer(server);
            check("production-main-loaded",Class.forName("com.scarasol.acceleratednavigation.AcceleratedNavigation")
                    .getDeclaredConstructors().length==1,"real main");
            check("actual-mixin-access",pig.getNavigation() instanceof NavigationAccess,pig.getNavigation().getClass().getName());
            check("automatic-ground-qualified",AutomaticNavigationBridge.enabled(pig.getNavigation()),"stock GroundPathNavigation");
            System.out.println("PRODUCTION_CAPABILITIES "+NavigationCapabilities.capture(pig));
        } catch(Throwable problem) { abort(problem); }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void end(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.END||closing)return;
        tick++;
        try {
            JsonObject sample=new JsonObject();sample.addProperty("tick",tick);sample.addProperty("phase",phase);
            sample.addProperty("tickNanos",System.nanoTime()-tickStarted);
            var budget=NavigationScheduler.forServer(server).metrics();
            sample.addProperty("chargedNanos",budget.chargedNanos()-chargedBefore);
            sample.addProperty("availableNanos",budget.availableNanos());samples.add(sample);
            if(tick>30)step();
            if(phase==7&&request!=null&&request.getNodeCount()>0&&request.getNextNodeIndex()==0) {
                String key=request.queryId+":"+request.getNodeCount()+":"+request.getEndNode().asBlockPos();
                if(!key.equals(lastSegment)) {
                    lastSegment=key;
                    JsonObject row=new JsonObject();row.addProperty("tick",tick);row.addProperty("route",routeIndex);
                    row.addProperty("nativeBackend",request.nativeBackend);row.addProperty("canReach",request.canReach());
                    row.add("start",new GsonBuilder().create().toJsonTree(List.of(pig.getX(),pig.getY(),pig.getZ())));
                    JsonArray nodes=new JsonArray();
                    for(int index=0;index<request.getNodeCount();index++) {
                        var node=request.getNode(index);nodes.add(new GsonBuilder().create().toJsonTree(List.of(node.x,node.y,node.z)));
                    }
                    row.add("nodes",nodes);segments.add(row);
                }
            }
            if(tick%20==0) {
                JsonObject row=new JsonObject();row.addProperty("tick",tick);row.addProperty("phase",phase);
                row.add("navigation",new GsonBuilder().create().toJsonTree(service.metrics()));
                row.add("budget",new GsonBuilder().create().toJsonTree(NavigationScheduler.forServer(server).metrics()));
                if(request!=null) { row.addProperty("state",request.state.name());row.addProperty("nodes",request.getNodeCount());row.addProperty("next",request.getNextNodeIndex()); }
                if(pig!=null)row.add("position",new GsonBuilder().create().toJsonTree(List.of(pig.getX(),pig.getY(),pig.getZ())));
                ticks.add(row);
                Files.writeString(output.resolve("progress.json"),ticks.toString());
            }
            if(tick-phaseTick>6000||tick>20000)throw new AssertionError("timeout phase="+phase);
        } catch(Throwable problem) { abort(problem); }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void begin(TickEvent.ServerTickEvent event) {
        if(event.getServer()!=server||event.phase!=TickEvent.Phase.START||closing)return;
        tickStarted=System.nanoTime();
        chargedBefore=NavigationScheduler.forServer(server).metrics().chargedNanos();
    }
    private void phase(int next) { phase=next;phaseTick=tick;System.out.println("PRODUCTION_PHASE "+next); }
    private void step() throws Exception {
        if(phase==0) {
            check("real-physics-ground",pig.onGround()&&Math.abs(pig.getY()-81)<.001,pig.getY());
            for(Mob actor:nativeTypes) {
                var navigation=actor.getNavigation();
                String type=navigation.getClass().getSimpleName();
                check("native-type-qualified-"+type,AutomaticNavigationBridge.enabled(navigation),navigation.getClass().getName());
                check("native-type-move-accepted-"+type,navigation.moveTo(target,1),"entity entry");
                var pending=service.active(navigation);
                check("native-type-pending-local-safe-"+type,pending!=null&&pending.isDone()&&!navigation.isDone(),pending==null?"null":pending.state);
                check("native-type-entity-parameters-"+type,pending.intent.entity()==target
                        &&pending.intent.accuracy()==(navigation instanceof net.minecraft.world.entity.ai.navigation.WallClimberNavigation?0:1)
                        &&pending.intent.normalizeGround()==(navigation instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation),
                        pending.intent.regionOffset()+":"+pending.intent.upward()+":"+pending.intent.accuracy());
                navigation.tick();
                navigation.stop();
                check("native-type-cancel-cleans-"+type,pending.state==NavigationRequestPath.State.CANCELLED
                        &&navigation.getPath()==null&&navigation.getTargetPos()==null
                        &&(!(navigation instanceof WallNavigationAccess climb)||climb.acceleratedNavigation$climbTarget()==null),pending.state);
                actor.discard();
            }
            var goal=new MeleeAttackGoal((net.minecraft.world.entity.PathfinderMob)pig,1,true);
            request=AutomaticNavigationBridge.delegate(goal,1,"SELECT",()->(NavigationRequestPath)pig.getNavigation().createPath(target,1));
            check("query-does-not-move",request!=null&&pig.getNavigation().isDone()&&pig.getNavigation().getPath()==null,request==null?"null":request.state);
            check("pending-empty-path",request.isDone()&&request.getNodeCount()==0&&!request.canReach(),request.state);
            check("adopt-pending",AutomaticNavigationBridge.delegate(goal,1,"START",()->pig.getNavigation().moveTo(request,1)),"accepted");
            check("whole-versus-local",!pig.getNavigation().isDone()&&request.isDone(),"active pending");
            phase(1);
        } else if(phase==1&&request.getNodeCount()>0) {
            check("real-native-segment",request.getEndNode()!=null
                    &&(request.nativeBackend||request.canReach()),
                    request.state+" macro="+NavigationCapabilities.capture(pig).macroSupported()+" "+request.getNodeCount());
            check("semantic-neighborhood-enters-real-segment-search",!request.nativeBackend
                    &&service.metrics().segmentCalls()>0&&service.metrics().nativeWholeCalls()==0,
                    "magma and rail near the candidate are decided by the native evaluator");
            int retainedNodes=request.getNodeCount(),retainedNext=request.getNextNodeIndex();
            long retainedCalls=service.metrics().nativeCalls();
            check("repeated-request-path-move-keeps-current-segment",pig.getNavigation().moveTo(request,.9)
                    &&request.state==NavigationRequestPath.State.REACHABLE
                    &&request.getNodeCount()==retainedNodes&&request.getNextNodeIndex()==retainedNext
                    &&service.metrics().nativeCalls()==retainedCalls
                    &&pig.getNavigation().getPath()==request&&service.executionReady(pig.getNavigation()),
                    "only speed changes");
            net.minecraft.network.FriendlyByteBuf wire=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                request.writeToStream(wire);
                Path decoded=Path.createFromStream(wire);
                check("native-wire-format",decoded.getTarget().equals(request.getTarget())
                        &&decoded.getNodeCount()==request.getNodeCount()&&decoded.canReach()==request.canReach()
                        &&decoded.getNextNodeIndex()==request.getNextNodeIndex()&&wire.readableBytes()==0,decoded.getTarget());
            } finally { wire.release(); }
            NavigationAccess access=(NavigationAccess)pig.getNavigation();
            long timer=access.acceleratedNavigation$timeoutTimer();
            Path old=access.acceleratedNavigation$path();
            Path query=pig.getNavigation().createPath(new BlockPos(8,81,6),0);
            check("legacy-query-isolation",access.acceleratedNavigation$path()==old
                    &&access.acceleratedNavigation$timeoutTimer()==timer&&!(query instanceof NavigationRequestPath),"legacy native payload");
            Path bounded=pig.getNavigation().createPath(new BlockPos(40,81,3),0,8);
            check("bounded-query-keeps-eight-range",bounded!=null&&!(bounded instanceof NavigationRequestPath)
                    &&!bounded.canReach()&&bounded.getEndNode().asBlockPos().distSqr(pig.blockPosition())<=81,bounded==null?"null":bounded.getEndNode().asBlockPos());
            check("empty-set-keeps-original-null",pig.getNavigation().createPath(java.util.Set.of(),0)==null,"empty targets");
            var tested=NativeNavigationSearch.find(request,java.util.Set.of(new BlockPos(9,81,7)),0,16,java.util.Set.of());
            check("native-proof-current",tested.proof()!=null&&tested.proof().current(),"loaded native proof");
            level.setBlock(new BlockPos(1,80,1),Blocks.MAGMA_BLOCK.defaultBlockState(),2);
            check("same-collision-type-change-invalidates-native-proof",!tested.proof().current(),"stone to magma");
            level.setBlock(new BlockPos(1,80,1),Blocks.STONE.defaultBlockState(),2);
            pig.getNavigation().stop();
            check("external-stop-terminal",request.state==NavigationRequestPath.State.CANCELLED&&pig.getNavigation().isDone(),request.state);
            var controllerA=new MeleeAttackGoal((net.minecraft.world.entity.PathfinderMob)pig,1,true);
            var controllerB=new MeleeAttackGoal((net.minecraft.world.entity.PathfinderMob)pig,1,true);
            AutomaticNavigationBridge.delegate(controllerA,1,"TICK",()->pig.getNavigation().moveTo(12,81,7,1));
            var prior=service.active(pig.getNavigation());
            AutomaticNavigationBridge.delegate(controllerB,1,"TICK",()->pig.getNavigation().moveTo(12,81,7,1));
            var current=service.active(pig.getNavigation());
            check("same-target-new-controller-replaces",current!=prior&&prior.state==NavigationRequestPath.State.CANCELLED,"controller identity");
            AutomaticNavigationBridge.delegate(controllerA,1,"STOP",()-> { pig.getNavigation().stop();return null; });
            check("old-controller-stop-preserves-new",service.active(pig.getNavigation())==current,"new intent remains");
            AutomaticNavigationBridge.delegate(controllerA,1,"STOP",()->pig.getNavigation().moveTo(query,1));
            check("foreign-path-replacement-unconditional",current.state==NavigationRequestPath.State.CANCELLED
                    &&service.active(pig.getNavigation())==null,"foreign path owns native execution");
            pig.getNavigation().stop();
            AutomaticNavigationBridge.delegate(controllerA,1,"START",()->pig.getNavigation().moveTo(query,1));
            query.setNextNodeIndex(1);
            AutomaticNavigationBridge.delegate(controllerB,1,"START",()->pig.getNavigation().moveTo(query,1));
            Path foreign=pig.getNavigation().getPath();
            check("foreign-same-path-keeps-native-index",foreign.getNextNodeIndex()==1,foreign.getNextNodeIndex());
            AutomaticNavigationBridge.delegate(controllerA,1,"STOP",()-> { pig.getNavigation().stop();return null; });
            check("old-stop-preserves-new-foreign-path",pig.getNavigation().getPath()==foreign&&foreign!=null,"foreign control identity");
            AutomaticNavigationBridge.delegate(controllerB,1,"STOP",()->null);
            check("controller-stop-without-native-stop-cleans-foreign",pig.getNavigation().getPath()==null&&service.metrics().foreignPaths()==0,"scope cleanup");
            check("ordinary-coordinate-move",pig.getNavigation().moveTo(24,81,3,1),"accepted");
            request=service.active(pig.getNavigation());
            var identity=request.queryId;
            check("duplicate-move-same-identity",pig.getNavigation().moveTo(24,81,3,.8)&&service.active(pig.getNavigation()).queryId.equals(identity),"deduplicated");
            check("new-target-replaces",pig.getNavigation().moveTo(28,81,6,1)&&request.state==NavigationRequestPath.State.CANCELLED,"replaced");
            request=service.active(pig.getNavigation());phase(2);
        } else if(phase==2) {
            if(searchBeforeChange>=0&&service.metrics().segmentCalls()>searchBeforeChange
                    &&service.executionReady(pig.getNavigation())) {
                check("native-input-change-runs-new-pathfinder-"+semanticSearches,
                        !request.nativeBackend&&service.metrics().nativeWholeCalls()==0,
                        "real segment searches "+searchBeforeChange+" -> "+service.metrics().segmentCalls());
                semanticSearches++;searchBeforeChange=-1;
            }
            if(!request.terminal()&&request.getNodeCount()>0&&semanticChanges<2
                    &&tick-phaseTick>=80+semanticChanges*25) {
                searchBeforeChange=service.metrics().segmentCalls();
                level.setBlock(new BlockPos(17,80,1),
                        (semanticChanges==0?Blocks.ANDESITE:Blocks.STONE).defaultBlockState(),2);
                check("changed-native-input-pauses-local-execution-"+semanticChanges,
                        !request.nativeBackend&&!service.executionReady(pig.getNavigation())
                                &&AutomaticNavigationBridge.localDone(pig.getNavigation(),()->false)
                                &&!pig.getNavigation().isDone(),"actual native read input awaits END search");
                semanticChanges++;
            }
            if(!request.terminal()&&recomputations<3&&tick-phaseTick>=20+recomputations*25) {
                var identity=request.queryId;
                ((NavigationAccess)pig.getNavigation()).acceleratedNavigation$delayed(true);
                pig.getNavigation().recomputePath();
                check("normal-recomputation-retains-intent-"+recomputations,
                        service.active(pig.getNavigation()).queryId.equals(identity)
                                &&!((NavigationAccess)pig.getNavigation()).acceleratedNavigation$delayed(),
                        "same identity, original delayed flag consumed");
                recomputations++;
            }
            if(request.terminal()) {
                check("coordinate-natural-arrival",request.state==NavigationRequestPath.State.SUCCEEDED
                        &&pig.blockPosition().distManhattan(new BlockPos(28,81,6))<=1,request.state+" "+pig.blockPosition());
                check("three-normal-recomputations-complete",recomputations==3,recomputations);
                check("two-safe-semantic-changes-complete",semanticChanges==2,semanticChanges);
                check("two-native-input-researches-complete",semanticSearches==2,semanticSearches);
                melee=spawn(new TestZombie(level),new BlockPos(3,81,10));melee.setTarget(target);phase(3);
            }
        } else if(phase==3) {
            if(tick-phaseTick<10)return;
            NavigationRequestPath active=service.active(melee.getNavigation());
            if(active!=null&&request!=active)request=active;
            if(active!=null&&targetMoves<3&&tick-phaseTick>=20+targetMoves*25) {
                var id=active.queryId;
                target.setPos(target.getX()+3,target.getY(),target.getZ());
                AutomaticNavigationBridge.delegate(active.controller,active.controlGeneration,"TICK",()->melee.getNavigation().moveTo(target,1));
                check("moving-entity-retains-request-"+targetMoves,service.active(melee.getNavigation()).queryId.equals(id),"entity drift keeps identity");
                targetMoves++;
            }
            if(melee.distanceTo(target)<2.5) {
                check("normal-goalselector-melee",request!=null&&request.controller instanceof MeleeAttackGoal,
                        melee.distanceTo(target));
                check("multiple-entity-drifts-complete",targetMoves==3,targetMoves);
                melee.setTarget(null);melee.getNavigation().stop();melee.discard();
                villager=spawn(new TestVillager(level),new BlockPos(3,81,18));
                sink=new MoveToTargetSink(150,250);
                villager.getBrain().removeAllBehaviors();
                villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                villager.getBrain().addActivity(net.minecraft.world.entity.schedule.Activity.CORE,0,com.google.common.collect.ImmutableList.of(sink));
                villager.getBrain().setCoreActivities(java.util.Set.of(net.minecraft.world.entity.schedule.Activity.CORE));
                villager.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.CORE);
                phase(4);
            }
        } else if(phase==4) {
            if(tick-phaseTick==5) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(new BlockPosTracker(new BlockPos(15,81,18)),1,0));
            }
            var active=service.active(villager.getNavigation());
            if(active!=null) {
                request=active;
                if(active.state==NavigationRequestPath.State.PENDING)
                    check("sink-pending-preserves-walk-target",villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                            &&!villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                            active.state+" controller="+active.controller.getClass().getName()
                                    +" walk="+villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                                    +" unreachable="+villager.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE));
            }
            if(villager.blockPosition().distManhattan(new BlockPos(15,81,18))==0) {
                check("normal-brain-sink-arrival",request!=null&&request.controller==sink,villager.blockPosition());
                villager.getNavigation().stop();
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                level.setBlock(new BlockPos(24,81,22),Blocks.BARREL.defaultBlockState(),2);
                var holder=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE).getHolderOrThrow(PoiTypes.FISHERMAN);
                level.getPoiManager().remove(new BlockPos(24,81,22));level.getPoiManager().add(new BlockPos(24,81,22),holder);
                poi=AcquirePoi.create(type->type.is(PoiTypes.FISHERMAN),MemoryModuleType.JOB_SITE,false,java.util.Optional.empty());
                villager.getBrain().removeAllBehaviors();
                villager.getBrain().addActivity(net.minecraft.world.entity.schedule.Activity.CORE,0,com.google.common.collect.ImmutableList.of(poi));
                poiLegacy=service.metrics().legacyCalls();
                phase(5);
            }
        } else if(phase==5) {
            if(service.metrics().records()>0&&!villager.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) {
                poiPending=true;
                check("poi-pending-does-not-activate-navigation",service.active(villager.getNavigation())==null
                        &&villager.getNavigation().getPath()==null,"query-only child");
            }
            if(villager.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) {
                check("acquire-poi-selected-ticket",villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos().equals(new BlockPos(24,81,22)),
                        villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos());
                check("poi-original-brain-truly-async",poiPending&&service.metrics().legacyCalls()==poiLegacy,
                        "pending="+poiPending+" legacyDelta="+(service.metrics().legacyCalls()-poiLegacy));
                villager.discard();pig.discard();target.discard();
                phase(6);
            }
        } else if(phase==6) {
            if(routeIndex==routeInputs.size()) {
                for(int index=0;index<4;index++) {
                    TestPig prey=spawn(new TestPig(level),new BlockPos(37,81,2+index));
                    TestZombie hunter=spawn(new TestZombie(level),new BlockPos(3,81,2+index));
                    hunter.setTarget(prey);crowdTargets.add(prey);crowd.add(hunter);
                }
                phase(8);return;
            }
            JsonObject route=routeInputs.get(routeIndex).getAsJsonObject();
            BlockPos start=pos(route.getAsJsonArray("start"));
            String diagnosticStart=System.getProperty("an.production.diagnosticStart");
            if(diagnosticStart!=null&&routes.isEmpty()) {
                String[] coordinates=diagnosticStart.split(",");
                start=new BlockPos(Integer.parseInt(coordinates[0]),Integer.parseInt(coordinates[1]),Integer.parseInt(coordinates[2]));
                check("diagnostic-start-is-not-full-route-qualification",true,start);
            }
            pig=spawn(new TestZombie(level),start);
            BlockPos goal=pos(route.getAsJsonArray("goal"));
            check("full-route-accepted-"+routeIndex,pig.getNavigation().moveTo(goal.getX(),goal.getY(),goal.getZ(),1),route.get("name"));
            request=service.active(pig.getNavigation());phase(7);
        } else if(phase==7&&request.terminal()) {
            JsonObject route=routeInputs.get(routeIndex).getAsJsonObject();
            check("full-route-natural-arrival-"+routeIndex,request.state==NavigationRequestPath.State.SUCCEEDED
                    &&!request.nativeBackend&&pig.blockPosition().distManhattan(request.getTarget())<=1,
                    request.state+" "+pig.blockPosition()+" target="+request.getTarget()+" native="+request.nativeBackend);
            JsonObject row=new JsonObject();row.add("route",route.get("name"));row.addProperty("ticks",tick-phaseTick);
            row.add("arrival",new GsonBuilder().create().toJsonTree(List.of(pig.getX(),pig.getY(),pig.getZ())));
            routes.add(row);pig.discard();routeIndex++;phase(6);
        } else if(phase==8) {
            peakActive=Math.max(peakActive,service.metrics().active());
            for(int index=0;index<crowd.size();index++) {
                TestZombie hunter=crowd.get(index);
                if(crowdFinished.contains(hunter)||hunter.distanceTo(crowdTargets.get(index))>=2.5)continue;
                var active=service.active(hunter.getNavigation());
                check("concurrent-original-goal-arrival-"+index,active!=null&&!active.nativeBackend
                        &&active.controller instanceof MeleeAttackGoal,hunter.distanceTo(crowdTargets.get(index)));
                crowdFinished.add(hunter);hunter.setTarget(null);hunter.getNavigation().stop();
                hunter.discard();crowdTargets.get(index).discard();
            }
            if(crowdFinished.size()==crowd.size()) {
                check("concurrent-four-original-goals-progress",peakActive==4,peakActive);
                pig=spawn(new TestZombie(level),new BlockPos(3,81,40));
                var navigation=pig.getNavigation();
                navigation.getNodeEvaluator().setCanPassDoors(false);
                pig.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(8);
                check("native-partial-automatic-entry",navigation.moveTo(40,81,40,1),"bounded native fallback");
                request=service.active(navigation);
                navigation.getNodeEvaluator().setCanPassDoors(true);
                phase(9);
            }
        } else if(phase==9&&request.terminal()) {
            check("native-partial-exhaustion-is-not-arrival",request.nativeBackend
                    &&request.state==NavigationRequestPath.State.EXHAUSTED
                    &&pig.blockPosition().distManhattan(request.getTarget())>request.intent.accuracy()
                    &&service.metrics().nativeExhausted()>0,
                    request.state+" "+pig.blockPosition()+" target="+request.getTarget());
            check("native-query-restores-temporary-capability",pig.getNavigation().getNodeEvaluator().canPassDoors(),"captured false, current true");
            prepareNativeDecision();phase(10);
        } else if(phase==10&&request.state==NavigationRequestPath.State.REACHABLE) {
            check("native-ready-query-remains-inactive",service.active(pig.getNavigation())==null
                    &&pig.getNavigation().getPath()==null&&service.decisionCurrent(request),"READY query");
            level.setBlock(new BlockPos(1,80,40),Blocks.MAGMA_BLOCK.defaultBlockState(),2);
            check("native-ready-semantic-change-rejects-adoption",!pig.getNavigation().moveTo(request,1)
                    &&request.state==NavigationRequestPath.State.UNAVAILABLE&&pig.getNavigation().getPath()==null,
                    "same collision, changed native node type");
            level.setBlock(new BlockPos(1,80,40),Blocks.STONE.defaultBlockState(),2);
            prepareNativeDecision();phase(11);
        } else if(phase==11&&request.state==NavigationRequestPath.State.REACHABLE) {
            request.createdTick=level.getGameTime()-201;
            check("ready-query-ttl-rejects-adoption",!pig.getNavigation().moveTo(request,1)
                    &&request.state==NavigationRequestPath.State.UNAVAILABLE&&pig.getNavigation().getPath()==null,
                    "unconsumed query older than 200 ticks");
            check("zero-business-records",service.metrics().records()==0&&service.metrics().active()==0,service.metrics());
            check("zero-pending-scheduler",scheduler.metrics().pendingRequests()==0,scheduler.metrics());
            for(int x=13;x<=15;x++)for(int z=39;z<=41;z++)
                if(x!=14||z!=40)for(int y=81;y<=82;y++)
                    level.setBlock(new BlockPos(x,y,z),Blocks.POWDER_SNOW.defaultBlockState(),2);
            pig.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(35);
            selectedDecisionTarget=new BlockPos(20,81,44);
            request=service.prepare(pig.getNavigation(),AutomaticNavigationBridge.intent(pig.getNavigation(),
                    java.util.Set.of(new BlockPos(14,81,40),selectedDecisionTarget),null,false,8,false,0,35),
                    pig.getNavigation(),0);
            check("ready-multi-target-query-enters-native-decision",request!=null,"closer native-blocked target");
            phase(12);
        } else if(phase==12&&request.state==NavigationRequestPath.State.REACHABLE) {
            check("ready-query-selects-actual-reachable-target",request.canReach()&&request.nativeBackend
                    &&request.getTarget().equals(selectedDecisionTarget)&&service.active(pig.getNavigation())==null,
                    request.getTarget());
            check("ready-ground-query-adoption-restarts-segmented-execution",pig.getNavigation().moveTo(request,1)
                    &&request.state==NavigationRequestPath.State.PENDING&&!request.nativeBackend
                    &&request.getNodeCount()==0&&!pig.getNavigation().isDone(),"same request, selected target");
            phase(13);
        } else if(phase==13&&request.terminal()) {
            check("adopted-query-keeps-selected-final-target",request.state==NavigationRequestPath.State.SUCCEEDED
                    &&request.getTarget().equals(selectedDecisionTarget)&&pig.blockPosition().equals(selectedDecisionTarget),
                    request.state+" "+pig.blockPosition()+" target="+request.getTarget());
            for(int x=43;x<=47;x++)for(int z=44;z<=48;z++)
                if(x!=45||z!=46)for(int y=81;y<=82;y++)
                    level.setBlock(new BlockPos(x,y,z),Blocks.POWDER_SNOW.defaultBlockState(),2);
            pig.setPos(40.5,81,46.5);
            wholeBeforePartial=service.metrics().nativeWholeCalls();
            check("segmented-final-partial-entry",pig.getNavigation().moveTo(45,81,46,1),"native powder snow rule");
            request=service.active(pig.getNavigation());phase(14);
        } else if(phase==14) {
            if(!request.terminal()&&request.getNodeCount()>0&&!request.canReach()&&!sawSegmentPartial) {
                check("segmented-native-partial-is-executed",!request.nativeBackend
                        &&pig.getNavigation().getPath()==request&&!pig.getNavigation().isDone(),
                        request.getEndNode().asBlockPos());
                sawSegmentPartial=true;
            }
            if(request.terminal()) {
                check("segmented-final-partial-exhausts-without-whole-fallback",sawSegmentPartial
                        &&request.state==NavigationRequestPath.State.EXHAUSTED&&!request.nativeBackend
                        &&pig.blockPosition().distManhattan(request.getTarget())>request.intent.accuracy()
                        &&service.metrics().nativeWholeCalls()==wholeBeforePartial,
                        request.state+" "+pig.blockPosition()+" target="+request.getTarget());
                check("partial-finish-cleans-records",service.metrics().records()==0
                        &&scheduler.metrics().pendingRequests()==0,service.metrics());
                pig.discard();
                for(int y=85;y<=112;y++)
                    level.setBlock(new BlockPos(6,y,32),Blocks.AIR.defaultBlockState(),2);
                pig=spawn(new TestPig(level),new BlockPos(6,111,32));
                deferredNativeBefore=service.metrics().nativeCalls();
                deferredDecisionBefore=service.metrics().nativeDecisionCalls();
                deferredWholeBefore=service.metrics().nativeWholeCalls();
                deferredSegmentsBefore=service.metrics().segmentCalls();
                request=service.prepare(pig.getNavigation(),AutomaticNavigationBridge.intent(pig.getNavigation(),
                        java.util.Set.of(new BlockPos(16,81,32)),null,false,8,false,1,24),
                        pig.getNavigation(),0);
                check("airborne-query-prepared",request!=null&&!pig.onGround(),"natural fall before native search");
                phase(15);
            }
        } else if(phase==15&&tick-phaseTick>=2&&scheduler.metrics().pendingRequests()==0) {
            check("airborne-query-awaits-native-update",request.state==NavigationRequestPath.State.PENDING
                    &&!((NavigationAccess)pig.getNavigation()).acceleratedNavigation$canUpdate()
                    &&service.active(pig.getNavigation())==null&&service.metrics().nativeCalls()==deferredNativeBefore,
                    "query is waiting for the actual navigation to allow search");
            check("airborne-pending-query-adopted",pig.getNavigation().moveTo(request,1)
                    &&service.active(pig.getNavigation())==request&&!pig.getNavigation().isDone(),
                    "same pending handle adopted before landing");
            phase(16);
        } else if(phase==16&&request.terminal()) {
            check("adopted-deferred-query-produces-segment",false,request.state+" "+pig.blockPosition()
                    +" captured="+request.intent.capabilities()+" actual="+NavigationCapabilities.capture(pig));
        } else if(phase==16&&request.getNodeCount()>0) {
            check("adopted-deferred-query-enters-segmented-search",!request.nativeBackend
                    &&service.metrics().segmentCalls()>deferredSegmentsBefore
                    &&service.metrics().nativeDecisionCalls()==deferredDecisionBefore
                    &&service.metrics().nativeWholeCalls()==deferredWholeBefore,
                    service.metrics());
            phase(17);
        } else if(phase==17&&request.terminal()) {
            check("adopted-deferred-query-naturally-arrives",request.state==NavigationRequestPath.State.SUCCEEDED
                    &&!request.nativeBackend&&pig.blockPosition().distManhattan(request.getTarget())<=1,
                    request.state+" "+pig.blockPosition()+" target="+request.getTarget());
            finish();
        }
    }
    private void prepareNativeDecision() {
        var navigation=pig.getNavigation();
        navigation.getNodeEvaluator().setCanPassDoors(false);
        try {
            request=service.prepare(navigation,AutomaticNavigationBridge.intent(navigation,
                    java.util.Set.of(new BlockPos(9,81,40)),null,false,8,false,0,8),navigation,0);
            check("native-decision-query-accepted",request!=null,"unadopted query");
        } finally { navigation.getNodeEvaluator().setCanPassDoors(true); }
    }
    private void abort(Throwable problem) { failure=problem;problem.printStackTrace();finish(); }
    private void finish() {
        if(closing)return;closing=true;
        for(Mob actor:actors) { actor.getNavigation().stop();actor.discard(); }
        for(ChunkPos chunk:held)level.setChunkForced(chunk.x,chunk.z,false);
        try {
            JsonObject result=new JsonObject();result.addProperty("failed",failure!=null);
            result.addProperty("failure",failure==null?"":failure.toString());result.addProperty("phase",phase);result.addProperty("tick",tick);
            result.add("checks",checks);result.add("routes",routes);result.add("ticks",ticks);
            result.add("samples",samples);
            result.add("segments",segments);
            result.add("metrics",new GsonBuilder().create().toJsonTree(service==null?null:service.metrics()));
            Files.writeString(output.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch(Exception error) { error.printStackTrace(); }
        server.halt(false);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void stopped(ServerStoppedEvent event) {
        if(!closing) {
            failure=new AssertionError("server stopped before verification completed");
            try {
                JsonObject result=new JsonObject();result.addProperty("failed",true);
                result.addProperty("failure",failure.toString());result.addProperty("phase",phase);result.addProperty("tick",tick);
                result.add("checks",checks);result.add("routes",routes);result.add("ticks",ticks);
                Files.writeString(output.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
            } catch(Exception error) { error.printStackTrace(); }
        }
        try {
            boolean serviceRejected=false,schedulerRejected=false;
            try { SegmentedNavigationService.forServer(server); } catch(IllegalStateException expected) { serviceRejected=true; }
            try { NavigationScheduler.forServer(server); } catch(java.util.concurrent.RejectedExecutionException expected) { schedulerRejected=true; }
            JsonObject shutdown=new JsonObject();
            shutdown.addProperty("passed",closing&&failure==null&&serviceRejected&&schedulerRejected
                    &&service.metrics().records()==0&&scheduler.metrics().pendingRequests()==0);
            shutdown.addProperty("serviceRejected",serviceRejected);shutdown.addProperty("schedulerRejected",schedulerRejected);
            shutdown.add("navigation",new GsonBuilder().create().toJsonTree(service.metrics()));
            shutdown.add("scheduler",new GsonBuilder().create().toJsonTree(scheduler.metrics()));
            Files.writeString(output.resolve("shutdown.json"),new GsonBuilder().setPrettyPrinting().create().toJson(shutdown));
        } catch(Exception error) { error.printStackTrace(); }
        System.out.println("PRODUCTION_VERIFICATION_EXIT failed="+(failure!=null));
    }
}
