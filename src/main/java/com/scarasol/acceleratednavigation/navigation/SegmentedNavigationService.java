package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.mixin.WallNavigationAccess;
import com.scarasol.acceleratednavigation.policy.NavigationPolicy;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.SharedSectionCandidate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-thread owner of movement intents, queries, current windows and recovery. */
public final class SegmentedNavigationService {
    private static final Map<MinecraftServer,SegmentedNavigationService> SERVICES=new IdentityHashMap<>();
    private static final Set<MinecraftServer> CLOSED=Collections.newSetFromMap(new WeakHashMap<>());
    private static final int MAX_RECORDS=1024,QUERY_TTL=200,PLANNING_TTL=200,LANDING_TTL=40;
    private final MinecraftServer server;
    private final NavigationScheduler scheduler;
    private final Map<PathNavigation,Work> active=new IdentityHashMap<>();
    private final Map<PathNavigation,ForeignExecution> foreign=new IdentityHashMap<>();
    private final Map<NavigationRequestPath,Work> records=new IdentityHashMap<>();
    private final Map<PathNavigation,FailureBackoff> backoffs=new IdentityHashMap<>();
    private boolean stopping;
    private long nativeCalls,segmentCalls,nativeWholeCalls,nativeDecisionCalls,arrivals,recoveries,legacyCalls,failures,completed,previewCompleted,nativeExhausted;
    private long handoffDeferrals;
    private long sharedSectionRequests,sharedSectionSegments;
    private long sharedSectionFallbacks;

    /**
     * Evidence captured while the current real Path is still on its last node.
     * It is only a handoff fact for an intermediate macro segment; it is never a
     * replacement for the next real native search.
     */
    private record SegmentDestinationEntry(NavigationRequestPath request,long publication,long windowRevision,
            int destinationComponent,NativeQueryProof nativeProof,long tick,int nodeIndex,BlockPos node,
            BlockPos blockPosition,Vec3 position) {}

    private SegmentedNavigationService(MinecraftServer server) {
        this.server=server;this.scheduler=NavigationScheduler.forServer(server);
    }
    public static SegmentedNavigationService forServer(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("navigation service requires server thread");
        if(CLOSED.contains(server))throw new IllegalStateException("navigation service is stopped");
        return SERVICES.computeIfAbsent(server,SegmentedNavigationService::new);
    }
    public static void observeServer(MinecraftServer server) {
        var service=SERVICES.get(server);
        if(service!=null) {
            service.observe();
            try(var ignored=service.scheduler.account()) { NavigationPoiAdapter.sweep(server,false); }
        }
    }
    public static void shutdown(MinecraftServer server) {
        CLOSED.add(server);
        var service=SERVICES.remove(server);
        if(service!=null) {
            service.stopping=true;
            NavigationPoiAdapter.sweep(server,true);
            for(Work work:new ArrayList<>(service.records.values()))service.finish(work,NavigationRequestPath.State.CANCELLED);
            for(PathNavigation navigation:new ArrayList<>(service.foreign.keySet()))service.clearForeign(navigation,true);
            service.active.clear();
            service.backoffs.clear();
        }
    }
    public static boolean isClosed(MinecraftServer server) { return CLOSED.contains(server); }
    public static void stopControllerOnCurrentThread(Object controller,long generation) {
        for(var service:new ArrayList<>(SERVICES.values()))if(service.server.isSameThread())service.stopController(controller,generation);
    }
    public record Metrics(int records,int active,long nativeCalls,long arrivals,long recoveries,
            long legacyCalls,long failures,long completed,long previewCompleted,long segmentCalls,long nativeWholeCalls,long nativeDecisionCalls,long nativeExhausted,int foreignPaths) {}
    public Metrics metrics() { return new Metrics(records.size()+foreign.size(),active.size(),nativeCalls,arrivals,recoveries,legacyCalls,failures,completed,previewCompleted,segmentCalls,nativeWholeCalls,nativeDecisionCalls,nativeExhausted,foreign.size()); }
    /** Counts actual last-node advance deferrals, not elapsed handoff waiting ticks. */
    public long handoffDeferrals() { return handoffDeferrals; }
    public long sharedSectionRequests() { return sharedSectionRequests; }
    public long sharedSectionSegments() { return sharedSectionSegments; }
    public long sharedSectionFallbacks() { return sharedSectionFallbacks; }
    public void recordLegacy() { legacyCalls++; }
    public NavigationPreview preview(ServerLevel level,UUID owner,BlockPos start,BlockPos goal,boolean doors,
            Consumer<List<Vec3>> segments) {
        try(var ignored=scheduler.account()) {
            Zombie actor=new Zombie(level);
            actor.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            actor.setOnGround(true);
            ((GroundPathNavigation)actor.getNavigation()).setCanOpenDoors(doors);
            NavigationIntent intent=AutomaticNavigationBridge.movementIntent(actor.getNavigation(),goal,null,1);
            Work work=new Work(actor.getNavigation(),intent,owner,0);
            NavigationPreview preview=new NavigationPreview(()->finish(work,NavigationRequestPath.State.CANCELLED));
            work.preview=preview;work.previewSegments=segments;
            if(!capacity(actor.getNavigation())) { actor.discard();preview.future.complete(NavigationRequestPath.State.UNAVAILABLE);return preview; }
            records.put(work.request,work);
            queue(work,()-> { initialize(work);return null; });
            return preview;
        }
    }
    private record FailureBackoff(NavigationIntent intent,long until) {}
    public NavigationRequestPath active(PathNavigation navigation) {
        Work work=active.get(navigation);
        return work==null?null:work.request;
    }
    /** Execution ownership only; world revisions cannot revoke a committed route. */
    public boolean executionReady(PathNavigation navigation) {
        Work work=active.get(navigation);
        return work==null||live(work)&&!work.nativeStopped&&(work.window==null||work.window.current());
    }
    public NavigationRequestPath prepare(PathNavigation navigation,NavigationIntent intent,Object controller,long generation) {
        try(var ignored=scheduler.account()) {
            FailureBackoff backoff=backoffs.get(navigation);
            long tick=((ServerLevel)((NavigationAccess)navigation).acceleratedNavigation$mob().level()).getGameTime();
            if(backoff!=null&&tick<backoff.until&&sameIntent(backoff.intent,intent))return null;
            for(Work old:records.values()) if(old.request.navigation==navigation
                    && old.request.controller==controller && old.request.controlGeneration==generation
                    && sameIntent(old.request.intent,intent)&&!old.request.terminal())return old.request;
            if(!capacity(navigation))return null;
            Work work=new Work(navigation,intent,controller,generation);
            records.put(work.request,work);
            queue(work,()-> { initialize(work);return null; });
            return work.request;
        }
    }
    public boolean move(PathNavigation navigation,NavigationIntent intent,Object controller,long generation,double speed) {
        if(!Double.isFinite(speed)||speed<0)return false;
        try(var ignored=scheduler.account()) {
            Work old=active.get(navigation);
            if(old!=null&&old.request.controller==controller&&old.request.controlGeneration==generation
                    && sameIntent(old.request.intent,intent)) {
                old.request.speed=speed;
                ((NavigationAccess)navigation).acceleratedNavigation$speed(speed);
                return true;
            }
            NavigationRequestPath prepared=prepare(navigation,intent,controller,generation);
            return prepared!=null&&adopt(navigation,prepared,speed);
        }
    }
    private static boolean sameIntent(NavigationIntent a,NavigationIntent b) {
        return a.entity()!=null&&a.entity()==b.entity()
                ? a.accuracy()==b.accuracy()&&a.capabilities().equals(b.capabilities())
                    &&a.normalizeGround()==b.normalizeGround()&&a.regionOffset()==b.regionOffset()
                    &&a.upward()==b.upward()&&a.range()==b.range()&&a.visitedMultiplier()==b.visitedMultiplier()
                : a.equals(b);
    }
    public boolean adopt(PathNavigation navigation,NavigationRequestPath request,double speed) {
        Work work=records.get(request);
        if(work==null||request.navigation!=navigation||request.terminal()||!valid(work)
                ||!Double.isFinite(speed)||speed<0)return false;
        try(var ignored=scheduler.account()) {
            if(request.adopted) {
                if(active.get(navigation)!=work)return false;
                request.speed=speed;
                ((NavigationAccess)navigation).acceleratedNavigation$speed(speed);
                return true;
            }
            if(!request.adopted&&(work.level.getGameTime()-request.createdTick>QUERY_TTL
                    ||request.state==NavigationRequestPath.State.REACHABLE&&!decisionCurrent(request))) {
                finish(work,NavigationRequestPath.State.UNAVAILABLE);return false;
            }
            Work old=active.get(navigation);
            if(old!=null&&old!=work)finish(old,NavigationRequestPath.State.CANCELLED);
            clearForeign(navigation,true);
            request.adopted=true;request.speed=speed;active.put(navigation,work);
            NavigationAccess access=(NavigationAccess)navigation;
            access.acceleratedNavigation$path(request);
            access.acceleratedNavigation$target(request.getTarget());
            access.acceleratedNavigation$speed(speed);
            if(navigation instanceof WallClimberNavigation)
                ((WallNavigationAccess)navigation).acceleratedNavigation$climbTarget(request.intent.currentTargets().iterator().next());
            if(request.state==NavigationRequestPath.State.REACHABLE&&!work.queued&&!work.preparing) {
                if(work.segmented) {
                    closeWindow(work);
                    work.decisionAdopted=true;
                    work.targets=new ArrayList<>(request.intent.entity()==null
                            ?Set.of(request.getTarget()):request.intent.currentTargets());
                    work.candidateIndex=0;
                    request.nativeBackend=false;request.nativeReachable=false;
                    request.clearNodes();request.state=NavigationRequestPath.State.PENDING;
                    queue(work,()-> { macroCandidate(work);return null; });
                } else publish(work,request);
            }
            return true;
        }
    }
    private boolean capacity(PathNavigation navigation) {
        if(stopping||records.size()+foreign.size()>=MAX_RECORDS)return false;
        return records.values().stream().filter(w->w.request.navigation==navigation&&!w.request.adopted).count()<2;
    }

    private final class Work {
        final NavigationRequestPath request;
        final ServerLevel level;
        final NavigationScheduler.Priority priority;
        final Set<BlockPos> avoided=new LinkedHashSet<>(),failedTargets=new LinkedHashSet<>();
        List<BlockPos> targets;
        int candidateIndex,cursor,stageEnd,stride=2,attempt,refreshes,searches,recoveryCount,searchLimit,recoveryLimit;
        long pendingSince,lastProgress,doneSince=-1,lastRecompute=-100;
        long segmentPublication;
        int stable;
        double previousY=Double.NaN;
        boolean segmented,last,segmentReachable,decisionAdopted,queued,preparing,nativeStopped,running;
        boolean longSpanDisabled,mergedFinal,finalMergeRejected,finalSearchCommitted;
        Supplier<?> followup;
        Supplier<?> waitingSearch;
        BlockPos lastNode,observedPosition,plannedEntityTarget,plannedStart,segmentTarget;
        MacroSearch.Corridor corridor;
        List<MacroSearch.Connection> edges=List.of();
        GroundSegmentWindow window;
        NativeQueryProof nativeProof;
        SegmentDestinationEntry segmentEntry;
        Set<BlockPos> segmentTargets=Set.of();
        int segmentAccuracy;
        Path legacyPath;
        NavigationIntent legacyIntent;
        CompletableFuture<GroundSegmentWindow> pendingWindow;
        CompletableFuture<SharedSectionCandidate> pendingShared;
        SharedSectionCandidate sharedCandidate;
        boolean sharedMergeRejected;
        AtomicBoolean sharedInterest;
        TopologyService.MacroRequest macro;
        NavigationPreview preview;
        Consumer<List<Vec3>> previewSegments;
        Work(PathNavigation navigation,NavigationIntent intent,Object controller,long generation) {
            Mob mob=((NavigationAccess)navigation).acceleratedNavigation$mob();
            level=(ServerLevel)mob.level();
            request=new NavigationRequestPath(navigation,mob,intent,controller,generation,level.getGameTime());
            priority=NavigationPolicy.priorityFor(mob);
            pendingSince=lastProgress=level.getGameTime();
        }
    }
    private static final class ForeignExecution {
        final Path path;
        final Object controller;
        final long generation;
        final ServerLevel level;
        Path legacyPath;
        NavigationIntent legacyIntent;
        ForeignExecution(Path path,Object controller,long generation,ServerLevel level) {
            this.path=path;this.controller=controller;this.generation=generation;this.level=level;
        }
    }
    public boolean owns(PathNavigation navigation) { return active.containsKey(navigation)||foreign.containsKey(navigation); }
    public void legacyPayload(PathNavigation navigation,Path path,NavigationIntent intent) {
        Work work=active.get(navigation);
        if(work!=null) { work.legacyPath=path;work.legacyIntent=intent; }
        ForeignExecution execution=foreign.get(navigation);
        if(execution!=null) { execution.legacyPath=path;execution.legacyIntent=intent; }
    }
    public boolean foreignMove(PathNavigation navigation,Path path,double speed,Object controller,long generation,Supplier<Boolean> original) {
        try(var ignored=scheduler.account()) {
            Work work=active.get(navigation);
            ForeignExecution old=foreign.get(navigation);
            if(path!=null&&path.getNodeCount()>0&&work==null&&old==null&&records.size()+foreign.size()>=MAX_RECORDS)return false;
            NavigationIntent intent=work!=null&&work.legacyPath==path?work.legacyIntent
                    :old!=null&&old.legacyPath==path?old.legacyIntent:null;
            if(work!=null)finish(work,NavigationRequestPath.State.CANCELLED);
            clearForeign(navigation,path==null);
            boolean accepted=original.get();
            NavigationAccess access=(NavigationAccess)navigation;
            if(accepted&&access.acceleratedNavigation$path()!=null) {
                if(intent!=null) {
                    access.acceleratedNavigation$target(path.getTarget());
                    access.acceleratedNavigation$accuracy(intent.accuracy());
                }
                foreign.put(navigation,new ForeignExecution(access.acceleratedNavigation$path(),controller,generation,
                        (ServerLevel)access.acceleratedNavigation$mob().level()));
            }
            return accepted;
        }
    }
    private void clearForeign(PathNavigation navigation,boolean stop) {
        ForeignExecution execution=foreign.remove(navigation);
        if(execution==null||!stop)return;
        NavigationAccess access=(NavigationAccess)navigation;
        if(access.acceleratedNavigation$path()!=execution.path)return;
        access.acceleratedNavigation$path(null);access.acceleratedNavigation$target(null);
        access.acceleratedNavigation$accuracy(0);access.acceleratedNavigation$delayed(false);
        access.acceleratedNavigation$resetTimeout();
        if(navigation instanceof WallClimberNavigation)((WallNavigationAccess)navigation).acceleratedNavigation$climbTarget(null);
    }
    private boolean valid(Work work) {
        return !stopping&&!work.request.mob.isRemoved()&&work.request.mob.level()==work.level
                &&(work.preview!=null||NavigationPolicy.automaticEnabled()
                    &&NavigationPolicy.modeFor(work.request.mob)!=NavigationPolicy.SchedulingMode.BYPASS)
                &&work.request.mob.getNavigation()==work.request.navigation
                &&work.request.intent.capabilities().physicalMatches(work.request.mob)
                &&(work.request.intent.entity()==null||work.request.intent.entity().isAlive()
                    &&work.request.intent.entity().level()==work.level);
    }
    private boolean live(Work work) { return records.get(work.request)==work&&!work.request.terminal()&&valid(work); }
    private <T> void queue(Work work,Supplier<T> operation) {
        if(!live(work))return;
        if(work.running) {
            work.followup=operation;work.queued=true;return;
        }
        work.queued=true;work.pendingSince=work.level.getGameTime();
        scheduler.submitSoft(work.level.dimension(),work.request.queryId,work.priority,500_000,()-> {
            work.queued=false;
            if(!live(work))return null;
            work.running=true;
            try { return operation.get(); }
            finally { work.running=false; }
        }).whenComplete((value,error)-> {
            if(error!=null&&records.get(work.request)==work) {
                AcceleratedNavigation.LOGGER.debug("Navigation request {} failed",work.request.queryId,error);
                finish(work,NavigationRequestPath.State.UNAVAILABLE);
            } else if(work.followup!=null&&live(work)) {
                Supplier<?> next=work.followup;work.followup=null;
                queue(work,next);
            }
        });
    }
    private void initialize(Work work) {
        NavigationIntent intent=work.request.intent;
        work.targets=new ArrayList<>(intent.currentTargets());
        BlockPos actual=work.request.mob.blockPosition();
        work.targets.sort(Comparator.comparingDouble((BlockPos p)->p.distSqr(actual))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        work.segmented=work.request.navigation instanceof GroundPathNavigation
                &&!(work.request.navigation instanceof WallClimberNavigation)
                &&!work.request.mob.isInWaterOrBubble()&&!work.request.mob.isInLava()
                &&intent.capabilities().macroSupported();
        if(!work.request.adopted&&work.preview==null)nativeDecision(work);
        else if(work.segmented)macroCandidate(work);
        else nativeWhole(work);
    }
    private <T> void queueReady(Work work,Supplier<T> operation) {
        queue(work,operation);
        scheduler.requestReadyDispatch();
    }
    private void macroCandidate(Work work) {
        closeWindow(work);
        work.corridor=null;work.edges=List.of();
        cancelShared(work);
        work.sharedCandidate=null;
        if(work.candidateIndex>=work.targets.size()) {
            finish(work,NavigationRequestPath.State.UNAVAILABLE);
            return;
        }
        BlockPos input=work.targets.get(work.candidateIndex);
        work.plannedStart=work.request.mob.blockPosition();
        BlockPos target=NativeNavigationSearch.normalize(work.level,input,work.request.intent.normalizeGround());
        work.request.select(target);
        work.plannedEntityTarget=input;
        work.preparing=true;work.pendingSince=work.level.getGameTime();
        int selected=work.candidateIndex;
        if(SharedSectionCandidate.supports(work.request.intent.capabilities().profile(),
                work.plannedStart,target,work.request.intent.accuracy())) {
            sharedCandidate(work,target,selected);
        } else existingMacroCandidate(work,target,selected);
    }
    private void sharedCandidate(Work work,BlockPos target,int selected) {
        var interested=new AtomicBoolean(true);
        work.sharedInterest=interested;
        var future=TopologyService.forServer(server).requestSharedSection(work.level,
                work.request.intent.capabilities().profile(),work.plannedStart,target,interested::get,
                System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
        work.pendingShared=future;
        sharedSectionRequests++;
        future.whenComplete((candidate,error)-> {
            try(var ignored=scheduler.account()) {
                if(!live(work)||work.candidateIndex!=selected||work.pendingShared!=future)return;
                work.pendingShared=null;work.preparing=false;
                Throwable cause=error;
                while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                if(cause instanceof SharedSectionCandidate.UnsupportedInputException
                        ||cause instanceof SharedSectionCandidate.AdmissionRejectedException) {
                    sharedSectionFallbacks++;
                    interested.set(false);work.preparing=true;
                    existingMacroCandidate(work,target,selected);
                    return;
                }
                if(error!=null) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
                if(entityDrifted(work)) { queue(work,()-> { replan(work);return null; });return; }
                if(!candidate.candidate()||candidate.anchors().isEmpty()) {
                    if(candidate.state().equals("NO_MACRO_CANDIDATE")) {
                        work.candidateIndex++;queue(work,()-> { macroCandidate(work);return null; });
                    } else finish(work,NavigationRequestPath.State.UNAVAILABLE);
                    return;
                }
                work.sharedCandidate=candidate;work.cursor=0;
                if(work.searchLimit==0) {
                    work.searchLimit=12;
                    work.recoveryLimit=8;
                }
                queueReady(work,()-> { nextSegment(work);return null; });
            }
        });
    }
    private void existingMacroCandidate(Work work,BlockPos target,int selected) {
        var macro=TopologyService.forServer(server).requestMacroQuery(work.level,work.request.macroOwner,
                work.request.mob.blockPosition(),target,BaseClusterTopology.Channel.GROUND,
                work.request.intent.capabilities().profile(),work.priority);
        work.macro=macro;
        macro.future().whenComplete((corridor,error)-> {
            try(var ignored=scheduler.account()) {
                if(!live(work)||work.candidateIndex!=selected||work.macro!=macro)return;
                work.preparing=false;
                if(error!=null) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
                if(entityDrifted(work)) { queue(work,()-> { replan(work);return null; });return; }
                if(corridor==null) {
                    if(macro.progress().failure()==MacroSearch.Failure.NO_STRUCTURAL_ROUTE) {
                        work.candidateIndex++;queue(work,()-> { macroCandidate(work);return null; });
                    } else finish(work,NavigationRequestPath.State.UNAVAILABLE);
                    return;
                }
                work.corridor=corridor;
                AcceleratedNavigation.LOGGER.debug("Navigation {} macro candidate: target={} endpointCount={} connectionCount={}",
                        work.request.queryId,target,corridor.endpoints().size(),corridor.connections().size());
                if(AcceleratedNavigation.LOGGER.isTraceEnabled())
                    AcceleratedNavigation.LOGGER.trace("Navigation {} macro details: endpoints={} connections={}",
                            work.request.queryId,corridor.endpoints(),corridor.connections());
                work.edges=corridor.connections().stream().filter(e->e.from() instanceof MacroSearch.ComponentEndpoint
                        &&e.to() instanceof MacroSearch.ComponentEndpoint).toList();
                if(work.searchLimit==0) {
                    work.searchLimit=Math.max(12,(work.edges.size()+1)*3);
                    work.recoveryLimit=Math.max(8,(work.edges.size()+1)*2);
                }
                queueReady(work,()-> { nextSegment(work);return null; });
            }
        });
    }
    private void prepareWindow(Work work,List<MacroSearch.Connection> edges,MacroSearch.ComponentEndpoint destination,
            BlockPos reach,int accuracy,Runnable ready) {
        work.preparing=true;work.pendingSince=work.level.getGameTime();
        var future=TopologyService.forServer(server).prepareGroundWindow(work.level,work.corridor,work.request.intent.capabilities().profile(),
                work.priority,work.request.mob.blockPosition(),edges,destination,reach,accuracy);
        work.pendingWindow=future;
        future.whenComplete((window,error)->server.execute(()-> {
            try(var ignored=scheduler.account()) {
                if(!live(work)||work.pendingWindow!=future) { if(window!=null)window.close();return; }
                work.pendingWindow=null;work.preparing=false;
                if(error!=null||window==null) {
                    if(window!=null)window.close();
                    AcceleratedNavigation.LOGGER.debug("Navigation {} window preparation failed: cursor={} destination={} position={}",
                            work.request.queryId,work.cursor,destination,work.request.mob.blockPosition(),error);
                    if(work.stride>2&&!work.last) { recover(work);return; }
                    finish(work,NavigationRequestPath.State.UNAVAILABLE);return;
                }
                work.window=window;
                AcceleratedNavigation.LOGGER.debug("Navigation {} window inputs: cursor={} end={} sectionCount={} candidateCount={}",
                        work.request.queryId,work.cursor,work.stageEnd,window.sections().size(),window.candidates.size());
                if(AcceleratedNavigation.LOGGER.isTraceEnabled())
                    AcceleratedNavigation.LOGGER.trace("Navigation {} window details: sections={} destination={} candidates={}",
                            work.request.queryId,window.sections(),window.destination,window.candidates);
                queueReady(work,()-> { ready.run();return null; });
            }
        }));
    }
    private void nextSegment(Work work) {
        if(!live(work))return;
        closeWindow(work);
        work.mergedFinal=false;
        work.nativeProof=null;
        work.segmentEntry=null;
        work.segmentTarget=null;work.segmentReachable=false;
        work.nativeStopped=false;work.doneSince=-1;
        if(work.sharedCandidate!=null) {
            var anchors=work.sharedCandidate.anchors();
            int legacyEnd=Math.min(anchors.size()-1,work.cursor+4);
            int legacyStages=(anchors.size()-1-work.cursor+3)/4;
            boolean coalesce=!work.sharedMergeRejected&&legacyStages<work.searchLimit-work.searches;
            work.stageEnd=work.sharedCandidate.stageEnd(work.cursor,work.request.intent.range(),coalesce);
            work.last=work.stageEnd==anchors.size()-1;
            if(work.last) {
                updateEntityTarget(work);
                if(work.stageEnd>legacyEnd&&!anchors.get(anchors.size()-1).equals(work.request.getTarget())) {
                    work.stageEnd=legacyEnd;work.last=work.stageEnd==anchors.size()-1;
                }
            }
            BlockPos target=work.last?work.request.getTarget():anchors.get(work.stageEnd);
            sharedSectionSegments++;
            findSegment(work,Set.of(target),work.last?work.request.intent.accuracy():0);
            return;
        }
        if(work.finalSearchCommitted||work.cursor>=work.edges.size()) {
            finalSegment(work,false);
            return;
        }
        work.last=false;
        if(!work.longSpanDisabled&&work.attempt==0&&work.stride==2&&work.edges.size()-work.cursor>2)
            work.stride=4;
        work.stageEnd=Math.min(work.edges.size(),work.cursor+work.stride);
        Set<SectionPos> sections=new LinkedHashSet<>();
        sections.add(SectionPos.of(work.request.mob.blockPosition()));
        for(var edge:work.edges.subList(work.cursor,work.stageEnd)) {
            sections.add(((MacroSearch.ComponentEndpoint)edge.from()).section());
            sections.add(((MacroSearch.ComponentEndpoint)edge.to()).section());
        }
        if(sections.size()>(work.stride>2?9:4)) {
            recoveries++;
            if(++work.recoveryCount>work.recoveryLimit) { finish(work,NavigationRequestPath.State.FAILED);return; }
            work.stride=1;work.stageEnd=work.cursor+1;
        }
        if(work.stageEnd==work.edges.size()&&!work.finalMergeRejected) {
            finalSegment(work,true);
            return;
        }
        var edges=work.edges.subList(work.cursor,work.stageEnd);
        var destination=(MacroSearch.ComponentEndpoint)edges.get(edges.size()-1).to();
        prepareWindow(work,edges,destination,null,0,()-> {
            List<BlockPos> candidates=new ArrayList<>(work.attempt==0?work.window.candidates:work.window.nearbyCandidates());
            candidates.removeIf(work.failedTargets::contains);
            Comparator<BlockPos> order=Comparator.comparingDouble((BlockPos p)->p.distSqr(work.request.mob.blockPosition())
                    +.05*p.distSqr(work.request.getTarget()));
            if(work.attempt>0)order=Comparator.comparingInt((BlockPos p)->-work.window.interiorNeighbors(p)).thenComparing(order);
            candidates.sort(order.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
            if(candidates.isEmpty()) {
                if(work.stride>2)recover(work);else finish(work,NavigationRequestPath.State.UNAVAILABLE);
                return;
            }
            // Several same-component ports are an internal segment choice, not an API target truncation.
            findSegment(work,new LinkedHashSet<>(candidates.subList(0,Math.min(work.attempt>0?1:8,candidates.size()))),0);
        });
    }
    private void finalSegment(Work work,boolean merged) {
        updateEntityTarget(work);
        MacroSearch.ComponentEndpoint destination=null;
        for(var endpoint:work.corridor.endpoints())
            if(endpoint instanceof MacroSearch.ComponentEndpoint component)destination=component;
        if(destination==null) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
        work.corridor.component(destination);
        work.last=true;work.mergedFinal=merged;
        // Final targets need no port window; retain the search's own scheduler admission.
        queue(work,()-> {
            findSegment(work,Set.of(work.request.getTarget()),work.request.intent.accuracy());
            return null;
        });
    }
    private void rejectFinalMerge(Work work) {
        AcceleratedNavigation.LOGGER.debug("Navigation {} final merge rejected: cursor={} end={}",
                work.request.queryId,work.cursor,work.stageEnd);
        work.finalMergeRejected=true;work.mergedFinal=false;work.last=false;
        work.nativeProof=null;
        nextSegment(work);
    }
    private float localRange(Work work,Set<BlockPos> targets) {
        double farthest=0;
        for(BlockPos point:targets)farthest=Math.max(farthest,Math.sqrt(point.distSqr(work.request.mob.blockPosition())));
        return (float)Math.min(96,Math.max(16,farthest+8));
    }
    private void findSegment(Work work,Set<BlockPos> targets,int accuracy) {
        work.segmentTargets=Set.copyOf(targets);work.segmentAccuracy=accuracy;
        if(awaitNative(work,()-> { findSegment(work,targets,accuracy);return null; }))return;
        if(++work.searches>work.searchLimit) { finish(work,NavigationRequestPath.State.FAILED);return; }
        work.segmentEntry=null;work.segmentTarget=null;work.segmentReachable=false;
        // Endpoint distance does not bound native exploration around the final target.
        float range=work.last?work.request.intent.range():localRange(work,targets);
        NativeNavigationSearch.Result result=NativeNavigationSearch.find(work.request,targets,accuracy,range,work.avoided);
        nativeCalls++;segmentCalls++;
        work.nativeProof=result.proof();
        Path path=result.path();
        if(work.sharedCandidate!=null&&!work.sharedMergeRejected&&work.stageEnd>work.cursor+4
                &&(result.unavailable()||path==null||path.getNodeCount()==0||path.getEndNode()==null||!path.canReach())) {
            work.sharedMergeRejected=true;
            queueReady(work,()-> { nextSegment(work);return null; });
            return;
        }
        work.segmentTarget=path==null?null:path.getTarget();
        AcceleratedNavigation.LOGGER.debug("Navigation {} native segment result: cursor={} end={} last={} range={} reachable={} nodeCount={} unknown={}",
                work.request.queryId,work.cursor,work.stageEnd,work.last,range,
                path!=null&&path.canReach(),path==null?0:path.getNodeCount(),result.unavailable());
        if(AcceleratedNavigation.LOGGER.isTraceEnabled())
            AcceleratedNavigation.LOGGER.trace("Navigation {} native segment details: attempt={} stride={} start={} targets={} nativeEnd={}",
                    work.request.queryId,work.attempt,work.stride,work.request.mob.blockPosition(),
                    targets,path==null?null:path.getEndNode());
        if(work.mergedFinal&&(result.unavailable()||path==null||path.getNodeCount()==0
                ||path.getEndNode()==null||!path.canReach())) {
            rejectFinalMerge(work);return;
        }
        if(!work.last&&work.stride>2&&(result.unavailable()||path==null||path.getNodeCount()==0
                ||path.getEndNode()==null||!path.canReach())) {
            recover(work);return;
        }
        if(result.unavailable()) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
        if(path==null||path.getNodeCount()==0||path.getEndNode()==null) {
            recover(work);return;
        }
        work.segmentReachable=path.canReach();work.request.nativeReachable=path.canReach();
        work.request.state=NavigationRequestPath.State.REACHABLE;
        work.lastNode=path.getNextNodePos();
        publish(work,path);
    }
    private void nativeWhole(Work work) {
        searchNative(work,true);
    }
    private void nativeDecision(Work work) {
        searchNative(work,false);
    }
    private void searchNative(Work work,boolean movement) {
        if(!movement&&work.request.adopted) {
            if(work.segmented)macroCandidate(work);else nativeWhole(work);
            return;
        }
        if(awaitNative(work,()-> { searchNative(work,movement);return null; }))return;
        work.nativeStopped=false;
        work.plannedStart=work.request.mob.blockPosition();
        if(work.request.intent.entity()!=null)work.targets=new ArrayList<>(work.request.intent.currentTargets());
        if(work.request.adopted&&work.request.navigation instanceof WallClimberNavigation&&work.request.intent.entity()!=null)
            ((WallNavigationAccess)work.request.navigation).acceleratedNavigation$climbTarget(work.request.intent.entity().blockPosition());
        Set<BlockPos> targets=new LinkedHashSet<>();
        for(BlockPos target:work.targets)targets.add(NativeNavigationSearch.normalize(work.level,target,work.request.intent.normalizeGround()));
        var result=NativeNavigationSearch.find(work.request,targets,work.request.intent.accuracy(),work.request.intent.range(),Set.of());
        nativeCalls++;
        if(movement)nativeWholeCalls++;else nativeDecisionCalls++;
        work.nativeProof=result.proof();
        if(result.unavailable()) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
        Path path=result.path();
        if(path==null||path.getNodeCount()==0||path.getEndNode()==null) {
            if(work.request.adopted&&work.request.navigation instanceof WallClimberNavigation&&work.request.intent.entity()!=null) {
                work.request.nativeBackend=true;work.request.nativeReachable=false;
                work.request.state=NavigationRequestPath.State.REACHABLE;
                work.request.clearNodes();work.last=true;
                return;
            }
            if(!work.request.adopted&&work.preview==null&&work.nativeProof!=null) {
                work.request.nativeBackend=true;work.request.nativeReachable=false;
                work.request.state=NavigationRequestPath.State.UNREACHABLE;
            } else finish(work,NavigationRequestPath.State.UNREACHABLE);
            return;
        }
        work.request.select(path.getTarget());
        work.plannedEntityTarget=work.request.intent.entity()==null?path.getTarget():work.request.intent.entity().blockPosition();
        work.request.nativeBackend=true;work.request.nativeReachable=path.canReach();
        work.request.state=NavigationRequestPath.State.REACHABLE;
        work.last=true;
        if(path!=work.request)work.request.publish(path);
        if(work.request.adopted||work.preview!=null)publish(work,path);
    }
    private boolean awaitNative(Work work,Supplier<?> search) {
        if(((NavigationAccess)work.request.navigation).acceleratedNavigation$canUpdate())return false;
        work.waitingSearch=search;work.preparing=true;work.pendingSince=work.level.getGameTime();
        return true;
    }
    private void publish(Work work,Path path) {
        for(int index=0;index<path.getNodeCount();index++)
            if(!work.level.hasChunkAt(path.getNode(index).asBlockPos())) {
                if(work.mergedFinal) { rejectFinalMerge(work);return; }
                if(work.segmented&&!work.last&&work.stride>2) { recover(work);return; }
                finish(work,NavigationRequestPath.State.UNAVAILABLE);return;
            }
        // A published final path may already have crossed components when execution later fails.
        if(work.mergedFinal)work.finalSearchCommitted=true;
        if(path!=work.request)work.request.publish(path);
        if(work.segmented) {
            work.segmentPublication++;
            work.segmentEntry=null;
        }
        work.doneSince=-1;work.lastProgress=work.level.getGameTime();
        if(work.preview!=null) {
            List<Vec3> nodes=new ArrayList<>();
            for(int index=0;index<path.getNodeCount();index++)nodes.add(Vec3.atBottomCenterOf(path.getNode(index).asBlockPos()));
            work.previewSegments.accept(List.copyOf(nodes));
            if(work.last) { finish(work,path.canReach()?NavigationRequestPath.State.SUCCEEDED:NavigationRequestPath.State.EXHAUSTED);return; }
            BlockPos end=path.getEndNode().asBlockPos();
            work.request.mob.setPos(end.getX()+.5,end.getY(),end.getZ()+.5);
            if(!work.segmentReachable) {
                queue(work,()-> { recover(work);return null; });
                return;
            }
            work.cursor=work.stageEnd;work.attempt=0;work.stride=2;
            work.avoided.clear();work.failedTargets.clear();
            queue(work,()-> { nextSegment(work);return null; });
            return;
        }
        if(!work.request.adopted)return;
        NavigationAccess access=(NavigationAccess)work.request.navigation;
        access.acceleratedNavigation$path(work.request);
        access.acceleratedNavigation$target(work.request.getTarget());
        access.acceleratedNavigation$accuracy(work.request.intent.accuracy());
        access.acceleratedNavigation$speed(work.request.speed);
        access.acceleratedNavigation$trim();
        access.acceleratedNavigation$resetTimeout();
        access.acceleratedNavigation$lastCheck(access.acceleratedNavigation$tick());
        access.acceleratedNavigation$lastPosition(work.request.mob.position());
    }
    public void nativeStopped(PathNavigation navigation) {
        Work work=active.get(navigation);
        if(work!=null) {
            Path path=((NavigationAccess)navigation).acceleratedNavigation$path();
            if(path!=null&&!path.isDone())work.lastNode=path.getNextNodePos();
            work.nativeStopped=true;
            work.segmentEntry=null;
            if(work.lastNode!=null)work.avoided.add(work.lastNode);
        }
    }
    public boolean deferSegmentAdvance(PathNavigation navigation,Path path,boolean following) {
        Work work=active.get(navigation);
        if(work==null||!work.segmented||!work.segmentReachable||path!=work.request
                ||((NavigationAccess)navigation).acceleratedNavigation$path()!=path
                ||path.getNodeCount()==0
                ||path.getNextNodeIndex()!=path.getNodeCount()-1)return false;
        try(var ignored=scheduler.account()) {
            if(!live(work)||!work.request.adopted)return false;
            if(work.last)return work.stable<3||!work.request.mob.onGround()||!finalArrival(work);
            if(work.sharedCandidate!=null)return false;
            if(work.nativeStopped||work.window==null||!work.window.current())return false;
            recordDestinationEntry(work,path);
            if(!following||intermediateArrival(work))return false;
            if(handoffDeferrals<Long.MAX_VALUE)handoffDeferrals++;
            return true;
        }
    }
    private void recordDestinationEntry(Work work,Path path) {
        if(work.last||!work.segmentReachable||work.nativeStopped
                ||!work.window.contains(work.request.mob.blockPosition()))return;
        SegmentDestinationEntry entry=work.segmentEntry;
        if(entry==null||entry.publication()!=work.segmentPublication) {
            work.segmentEntry=new SegmentDestinationEntry(work.request,work.segmentPublication,
                    work.window.destination.revision(),work.window.destination.componentId(),work.nativeProof,
                    work.level.getGameTime(),path.getNextNodeIndex(),path.getNextNodePos(),
                    work.request.mob.blockPosition(),work.request.mob.position());
            if(AcceleratedNavigation.LOGGER.isTraceEnabled())AcceleratedNavigation.LOGGER.trace(
                    "Navigation {} recorded intermediate destination entry: publication={} cursor={} nodeIndex={} node={} position={} component={} revision={}",
                    work.request.queryId,work.segmentPublication,work.cursor,path.getNextNodeIndex(),
                    path.getNextNodePos(),work.request.mob.position(),work.window.destination.componentId(),
                    work.window.destination.revision());
        }
    }
    public boolean decisionCurrent(NavigationRequestPath request) {
        Work work=records.get(request);
        if(work==null||!valid(work))return false;
        try(var ignored=scheduler.account()) {
            if(!request.adopted&&(!request.mob.blockPosition().equals(work.plannedStart)
                    ||request.intent.entity()!=null
                        &&!request.intent.entity().blockPosition().equals(work.plannedEntityTarget)))return false;
            if(request.state!=NavigationRequestPath.State.REACHABLE
                    &&request.state!=NavigationRequestPath.State.UNREACHABLE)return false;
            return work.nativeProof!=null&&work.nativeProof.current();
        }
    }
    public void consume(NavigationRequestPath request) {
        Work work=records.get(request);
        if(work!=null&&!request.adopted)finish(work,NavigationRequestPath.State.CANCELLED);
    }
    public boolean externalStop(PathNavigation navigation,Object controller,long generation,boolean controllerStop) {
        Work work=active.get(navigation);
        if(work==null) {
            ForeignExecution execution=foreign.get(navigation);
            if(execution!=null&&controllerStop&&(execution.controller!=controller||execution.generation!=generation))return true;
            clearForeign(navigation,true);
            return false;
        }
        if(controllerStop&&(work.request.controller!=controller||work.request.controlGeneration!=generation))return true;
        finish(work,NavigationRequestPath.State.CANCELLED);
        return false;
    }
    public void stopController(Object controller,long generation) {
        for(var entry:new ArrayList<>(foreign.entrySet()))if(entry.getValue().controller==controller
                &&entry.getValue().generation==generation)clearForeign(entry.getKey(),true);
        for(Work work:new ArrayList<>(records.values()))if(work.request.controller==controller
                &&work.request.controlGeneration==generation)finish(work,NavigationRequestPath.State.CANCELLED);
    }
    public void speed(PathNavigation navigation,double value) {
        Work work=active.get(navigation);if(work!=null)work.request.speed=value;
    }
    public boolean recompute(PathNavigation navigation) {
        Work work=active.get(navigation);
        if(work==null)return false;
        ((NavigationAccess)navigation).acceleratedNavigation$delayed(false);
        // Block update callbacks cannot revoke an already committed macro route.
        if(work.request.adopted&&work.segmented&&(work.corridor!=null||work.sharedCandidate!=null)
                &&!work.nativeStopped)return true;
        if(work.level.getGameTime()-work.lastRecompute>=20&&!work.queued&&!work.preparing) {
            work.lastRecompute=work.level.getGameTime();
            queue(work,()-> { if(work.segmented)replan(work);else nativeWhole(work);return null; });
        }
        return true;
    }
    private void observe() {
        try(var ignored=scheduler.account()) {
            for(var entry:new ArrayList<>(foreign.entrySet())) {
                PathNavigation navigation=entry.getKey();ForeignExecution execution=entry.getValue();
                Mob mob=((NavigationAccess)navigation).acceleratedNavigation$mob();
                boolean invalid=mob.isRemoved()||mob.level()!=execution.level||mob.getNavigation()!=navigation
                        ||!NavigationPolicy.automaticEnabled()||NavigationPolicy.modeFor(mob)==NavigationPolicy.SchedulingMode.BYPASS;
                if(invalid)clearForeign(navigation,true);
                else if(((NavigationAccess)navigation).acceleratedNavigation$path()!=execution.path||execution.path.isDone())
                    clearForeign(navigation,false);
            }
            backoffs.entrySet().removeIf(entry->((NavigationAccess)entry.getKey()).acceleratedNavigation$mob().isRemoved()
                    ||((NavigationAccess)entry.getKey()).acceleratedNavigation$mob().level().getGameTime()>=entry.getValue().until);
            for(Work work:new ArrayList<>(records.values())) {
                if(!valid(work)) { finish(work,NavigationRequestPath.State.CANCELLED);continue; }
                long tick=work.level.getGameTime();
                if(work.waitingSearch!=null&&((NavigationAccess)work.request.navigation).acceleratedNavigation$canUpdate()) {
                    Supplier<?> search=work.waitingSearch;work.waitingSearch=null;work.preparing=false;
                    queue(work,search);
                }
                if(!work.request.adopted&&tick-work.request.createdTick>QUERY_TTL) { finish(work,NavigationRequestPath.State.UNAVAILABLE);continue; }
                if(work.queued||work.preparing) {
                    if(tick-work.pendingSince>PLANNING_TTL)finish(work,NavigationRequestPath.State.UNAVAILABLE);
                    continue;
                }
                if(!work.request.adopted)continue;
                Mob mob=work.request.mob;
                work.stable=mob.onGround()&&Math.abs(mob.getY()-work.previousY)<.000001?work.stable+1:0;
                work.previousY=mob.getY();
                if(work.request.intent.entity()!=null&&work.plannedEntityTarget!=null
                        &&work.request.intent.entity().blockPosition().distSqr(work.plannedEntityTarget)>4
                        &&tick-work.lastRecompute>=20) {
                    work.lastRecompute=tick;
                    queue(work,()-> {
                        if(work.segmented)replan(work);else nativeWhole(work);
                        return null;
                    });
                    continue;
                }
                if(work.observedPosition==null||!work.observedPosition.equals(mob.blockPosition())) {
                    work.lastProgress=tick;work.observedPosition=mob.blockPosition();
                }
                Path current=((NavigationAccess)work.request.navigation).acceleratedNavigation$path();
                if(current!=null&&!current.isDone()) {
                    work.lastNode=current.getNextNodePos();
                    if(work.segmented&&work.window!=null&&!work.last&&work.segmentReachable&&!work.nativeStopped
                            &&current.getNextNodeIndex()>=current.getNodeCount()-1)recordDestinationEntry(work,current);
                    if(tick-work.lastProgress>600)finish(work,NavigationRequestPath.State.FAILED);
                    continue;
                }
                if(!work.segmented) {
                    if(work.request.navigation instanceof WallClimberNavigation
                            &&((WallNavigationAccess)work.request.navigation).acceleratedNavigation$climbTarget()!=null) {
                        if(tick-work.lastProgress>600)finish(work,NavigationRequestPath.State.FAILED);
                        continue;
                    }
                    finish(work,work.nativeStopped?NavigationRequestPath.State.FAILED
                            :work.request.nativeReachable&&finalArrival(work)
                                ?NavigationRequestPath.State.SUCCEEDED:NavigationRequestPath.State.EXHAUSTED);
                    continue;
                }
                if(work.sharedCandidate!=null&&work.nativeStopped) {
                    finish(work,NavigationRequestPath.State.EXHAUSTED);continue;
                }
                if(work.doneSince<0)work.doneSince=tick;
                if(tick-work.doneSince>LANDING_TTL) {
                    if(work.lastNode!=null)work.avoided.add(work.lastNode);
                    queue(work,()-> { recover(work);return null; });
                    continue;
                }
                if(work.stable>=3)queue(work,()-> { settle(work);return null; });
            }
        }
    }
    private void updateEntityTarget(Work work) {
        if(work.request.intent.entity()==null)return;
        BlockPos input=work.request.intent.entity().blockPosition();
        work.request.select(NativeNavigationSearch.normalize(work.level,input,work.request.intent.normalizeGround()));
        ((NavigationAccess)work.request.navigation).acceleratedNavigation$target(work.request.getTarget());
    }
    private void settle(Work work) {
        if(!live(work))return;
        if(work.doneSince>=0&&work.level.getGameTime()-work.doneSince>LANDING_TTL) {
            if(work.lastNode!=null)work.avoided.add(work.lastNode);
            queue(work,()-> { recover(work);return null; });return;
        }
        if(!physical(work))return;
        updateEntityTarget(work);
        if(work.last&&!work.segmentReachable&&!work.nativeStopped) {
            finish(work,NavigationRequestPath.State.EXHAUSTED);return;
        }
        if(work.segmentReachable&&(work.last?finalArrival(work):intermediateArrival(work))) {
            arrivals++;
            if(work.last) { finish(work,NavigationRequestPath.State.SUCCEEDED);return; }
            work.cursor=work.stageEnd;work.attempt=0;work.stride=2;
            work.avoided.clear();work.failedTargets.clear();
            queue(work,()-> { nextSegment(work);return null; });
        } else queue(work,()-> { recover(work);return null; });
    }
    private boolean intermediateArrival(Work work) {
        if(work.sharedCandidate!=null)
            return !work.nativeStopped&&work.segmentReachable&&work.request.isDone();
        if(work.nativeStopped||work.window==null)return false;
        SegmentDestinationEntry entry=work.segmentEntry;
        if(entry!=null&&entry.request()==work.request&&entry.publication()==work.segmentPublication
                &&entry.nativeProof()==work.nativeProof&&work.window!=null
                &&work.window.destination.revision()==entry.windowRevision()
                &&work.window.destination.componentId()==entry.destinationComponent()) {
            return true;
        }
        return work.window.contains(work.request.mob.blockPosition());
    }
    private boolean finalArrival(Work work) {
        return work.request.mob.blockPosition().distManhattan(work.request.getTarget())<=work.request.intent.accuracy();
    }
    private boolean physical(Work work) {
        Mob mob=work.request.mob;
        AABB body=mob.getBoundingBox();
        return work.stable>=3&&mob.onGround()&&loaded(work.level,body)
                &&!work.level.getBlockCollisions(mob,body.deflate(.000001)).iterator().hasNext()
                &&work.level.getBlockCollisions(mob,body.move(0,-.025,0)).iterator().hasNext();
    }
    private static boolean loaded(ServerLevel level,AABB body) {
        for(int x=((int)Math.floor(body.minX))>>4;x<=((int)Math.floor(body.maxX))>>4;x++)
            for(int z=((int)Math.floor(body.minZ))>>4;z<=((int)Math.floor(body.maxZ))>>4;z++)
                if(!level.getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    private void recover(Work work) {
        if(!live(work))return;
        if(work.sharedCandidate!=null&&work.nativeStopped) {
            finish(work,NavigationRequestPath.State.EXHAUSTED);return;
        }
        if(work.request.adopted&&!work.last&&work.segmentReachable&&!work.nativeStopped
                &&((NavigationAccess)work.request.navigation).acceleratedNavigation$path()==work.request
                &&work.request.isDone()&&work.doneSince>=0
                &&work.level.getGameTime()-work.doneSince<=LANDING_TTL
                &&intermediateArrival(work)&&physical(work)) {
            settle(work);return;
        }
        AcceleratedNavigation.LOGGER.debug("Navigation {} recover: cursor={} end={} attempt={} stride={} last={} avoidedCount={}",
                work.request.queryId,work.cursor,work.stageEnd,work.attempt,work.stride,work.last,work.avoided.size());
        if(AcceleratedNavigation.LOGGER.isTraceEnabled())
            AcceleratedNavigation.LOGGER.trace("Navigation {} recovery details: position={} avoided={}",
                    work.request.queryId,work.request.mob.blockPosition(),work.avoided);
        if(work.segmentTarget!=null)work.failedTargets.add(work.segmentTarget);
        recoveries++;work.recoveryCount++;
        if(work.recoveryCount>work.recoveryLimit) { finish(work,NavigationRequestPath.State.FAILED);return; }
        if(work.stride>2&&!work.last) {
            work.longSpanDisabled=true;work.stride=2;work.attempt=0;
            nextSegment(work);return;
        }
        if(work.attempt<2)work.attempt++;
        else if(work.stride==2&&!work.last) { work.stride=1;work.attempt=0; }
        else { refresh(work);return; }
        nextSegment(work);
    }
    private void refresh(Work work) {
        AcceleratedNavigation.LOGGER.debug("Navigation {} refresh {} at {}",work.request.queryId,work.refreshes,work.request.mob.blockPosition());
        if(work.refreshes++>=1) { finish(work,NavigationRequestPath.State.UNAVAILABLE);return; }
        replan(work);
    }
    private boolean entityDrifted(Work work) {
        return work.request.intent.entity()!=null&&work.plannedEntityTarget!=null
                &&work.request.intent.entity().blockPosition().distSqr(work.plannedEntityTarget)>4;
    }
    private void replan(Work work) {
        closeWindow(work);
        work.finalSearchCommitted=false;
        work.segmentEntry=null;
        work.cursor=0;work.attempt=0;work.stride=2;work.avoided.clear();work.failedTargets.clear();
        work.request.clearNodes();work.request.state=NavigationRequestPath.State.PENDING;
        work.targets=new ArrayList<>(work.decisionAdopted&&work.request.intent.entity()==null
                ?Set.of(work.request.getTarget()):work.request.intent.currentTargets());work.candidateIndex=0;
        macroCandidate(work);
    }
    private void closeWindow(Work work) {
        if(work.window!=null) { work.window.close();work.window=null; }
        work.nativeProof=null;
        work.segmentEntry=null;
        work.segmentTarget=null;
    }
    private void cancelShared(Work work) {
        if(work.sharedInterest!=null)work.sharedInterest.set(false);
        if(work.pendingShared!=null)work.pendingShared.cancel(false);
        work.pendingShared=null;
    }
    private void finish(Work work,NavigationRequestPath.State state) {
        if(records.remove(work.request)!=work)return;
        try(var ignored=scheduler.account()) {
            work.request.state=state;active.remove(work.request.navigation,work);
            if(state==NavigationRequestPath.State.SUCCEEDED) {
                if(work.preview!=null)previewCompleted++;else completed++;
            }
            else if(state==NavigationRequestPath.State.EXHAUSTED)nativeExhausted++;
            else if(state!=NavigationRequestPath.State.CANCELLED)failures++;
            if(work.request.adopted&&state!=NavigationRequestPath.State.SUCCEEDED&&state!=NavigationRequestPath.State.EXHAUSTED
                    &&state!=NavigationRequestPath.State.CANCELLED) {
                if(backoffs.size()<MAX_RECORDS||backoffs.containsKey(work.request.navigation))
                    backoffs.put(work.request.navigation,new FailureBackoff(work.request.intent,work.level.getGameTime()+40));
            }
            if(work.macro!=null)work.macro.cancel();
            cancelShared(work);
            work.sharedCandidate=null;work.corridor=null;work.edges=List.of();work.macro=null;
            if(work.pendingWindow!=null) {
                if(work.pendingWindow.isDone()&&!work.pendingWindow.isCompletedExceptionally()) {
                    GroundSegmentWindow pending=work.pendingWindow.getNow(null);
                    if(pending!=null)pending.close();
                }
                work.pendingWindow.cancel(false);
            }
            scheduler.cancel(work.level.dimension(),work.request.queryId);
            closeWindow(work);
            NavigationAccess access=(NavigationAccess)work.request.navigation;
            if(access.acceleratedNavigation$path()==work.request) {
                if(work.request.navigation instanceof WallClimberNavigation)
                    ((WallNavigationAccess)work.request.navigation).acceleratedNavigation$climbTarget(null);
                access.acceleratedNavigation$path(null);access.acceleratedNavigation$target(null);
                access.acceleratedNavigation$accuracy(0);access.acceleratedNavigation$delayed(false);
                access.acceleratedNavigation$resetTimeout();
            }
            work.request.clearNodes();
            if(work.preview!=null) {
                work.request.mob.discard();
                work.preview.future.complete(state);
            }
        }
    }
}
