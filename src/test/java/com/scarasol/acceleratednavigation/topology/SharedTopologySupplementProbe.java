package com.scarasol.acceleratednavigation.topology;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Supplementary ownership and capacity observations, after the core acceptance. */
public final class SharedTopologySupplementProbe {
    private static final List<Map<String,Object>> checks=new ArrayList<>(),samples=new ArrayList<>();
    private static void check(boolean value,String name) {
        checks.add(Map.of("name",name,"passed",value));
        if (!value) throw new AssertionError(name);
    }
    private static Map<String,Object> measure(SharedRuntimeProbe.Host host,String name,Object old) throws Exception {
        return host.runtime.sharedTransition(()-> {
            try {
                Set<Object> excluded=Collections.newSetFromMap(new IdentityHashMap<>());
                excluded.add(host.runtime.shared.hook);
                if (host.runtime.shared.queryHook!=null) excluded.add(host.runtime.shared.queryHook);
                excluded.add(LocalKernel.Row.EMPTY);
                var workerRoots=SharedOwnershipAgent.roots();
                var all=new ArrayList<Object>();all.add(host.runtime);all.addAll(workerRoots);
                if(old!=null)all.add(old);
                long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
                var runtime=SharedOwnedSize.measure(host.runtime,excluded);
                var union=SharedOwnedSize.measure(all,excluded);
                return Map.<String,Object>of("name",name,"runtimeReachable",runtime,
                        "unionWithActualWorkerAndOldSnapshotRoots",union,
                        "estimatedRowsBytes",host.runtime.shared.retained,"heapProxyBeforeWalk",heap,
                        "workerRoots",workerRoots.size(),"temporaryRoots",SharedOwnershipAgent.temporaryCount(),
                        "metrics",host.runtime.shared.metrics());
            } catch(Exception failure) { throw new RuntimeException(failure); }
        });
    }
    private static void drain(SharedRuntimeProbe.Host host) throws Exception {
        SharedRuntimeProbe.drain(host);
        SharedRuntimeProbe.settle(()-> {
            var tasks=host.runtime.metrics().tasks();
            var queued=tasks.queued();var running=tasks.running();
            return SharedOwnershipAgent.temporaryCount()==0
                    &&queued.control()+queued.builds()+queued.quickSearches()+queued.longSearches()+queued.prewarms()==0
                    &&running.control()+running.builds()+running.quickSearches()+running.longSearches()+running.prewarms()==0;
        });
    }
    private static void fullHalo(String name,boolean doors) throws Exception {
        var center=doors?Fixtures.door("center",true,false,true).input().center()
                :Fixtures.fromFlags("center",Fixtures.flat()).input().center();
        try(var host=new SharedRuntimeProbe.Host(center)) {
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
                if((dx|dy|dz)!=0) {
                    var facts=doors?Fixtures.door("halo",true,false,true).input().center()
                            :Fixtures.fromFlags("halo",Fixtures.flat()).input().center();
                    host.publish(SectionPos.of(dx,5+dy,dz),1,facts);
                }
            SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN));
            SharedRuntimeProbe.get(host.request(Fixtures.body(.6F,2.9F,false,true)));
            drain(host);
            var sample=measure(host,name,null);
            check((long)((Map<?,?>)sample.get("runtimeReachable")).get("bytes")>
                    (long)sample.get("estimatedRowsBytes"),name+" full state exceeds row estimate");
            samples.add(sample);
        }
        check(SharedOwnershipAgent.roots().isEmpty(),name+" agent observes actual worker exit");
    }
    private static void oldSnapshotAndWorker() throws Exception {
        try(var host=new SharedRuntimeProbe.Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())) {
            var body=Fixtures.body(.6F,2.9F,false,true);
            int start=Fixtures.cell(3,1,7),goal=Fixtures.cell(12,1,7);
            var seeded=SharedRuntimeProbe.get(host.request(body,start,goal,8,()->true,Long.MAX_VALUE));
            check(seeded.state().equals("UNKNOWN_LIMIT"),"partial evaluation seeds old snapshot");
            drain(host);
            samples.add(measure(host,"before-overlap",null));
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var held=new AtomicReference<SharedRows>();
            host.runtime.shared.queryHook=(phase,rows,query,scratch)-> {
                if(query.start()==start&&query.goal()==goal) {
                    held.set(rows);entered.countDown();
                    if(!release.await(20,TimeUnit.SECONDS))throw new AssertionError("memory checkpoint gate timeout");
                }
            };
            var first=host.request(body,start,goal,4096,()->true,Long.MAX_VALUE);
            check(entered.await(10,TimeUnit.SECONDS),"actual local-query worker suspended before reconstruct");
            var second=host.request(body,Fixtures.cell(3,1,6),Fixtures.cell(12,1,6),4096,()->true,Long.MAX_VALUE);
            check(SharedRuntimeProbe.get(second).candidate(),"second actual worker publishes concurrent merge");
            SharedRuntimeProbe.settle(()->host.runtime.metrics().tasks().running().quickSearches()==1);
            var current=host.runtime.sharedTransition(()->host.runtime.shared.slots.values().iterator().next().snapshot);
            check(current!=held.get()&&current.version>held.get().version,"blocked worker still uses actual old snapshot");
            var peak=measure(host,"actual-worker-old-snapshot-checkpoint",held.get());
            var union=(Map<?,?>)peak.get("unionWithActualWorkerAndOldSnapshotRoots");
            var classes=(Map<?,?>)union.get("objectsByClass");
            check(((Number)classes.get(LocalKernel.class.getName())).longValue()>0,"active LocalKernel is in owned traversal");
            check(((Number)classes.get(BaseClusterTopology.BuildScratch.class.getName())).longValue()>0,"active BuildScratch is in owned traversal");
            check(((Number)classes.get(QueryScratch.class.getName())).longValue()==2,"two actual persistent worker query arrays counted");
            check((long)union.get("bytes")>(long)((Map<?,?>)peak.get("runtimeReachable")).get("bytes"),"old and active roots add reachable bytes");
            samples.add(peak);
            held.set(null);release.countDown();
            check(SharedRuntimeProbe.get(first).candidate(),"suspended query completes with unchanged real result");
            drain(host);samples.add(measure(host,"after-overlap-release",null));
        }
        check(SharedOwnershipAgent.roots().isEmpty(),"overlap roots cleared on actual thread exits");
    }
    private static void capacity() throws Exception {
        var facts=Fixtures.fromFlags("first",Fixtures.flat()).input().center();
        try(var host=new SharedRuntimeProbe.Host(facts)) {
            for(int index=0;index<64;index++) {
                SectionPos section=SectionPos.of(index*3,5,0);
                if(index>0)host.publish(section,1,Fixtures.fromFlags("unique",Fixtures.flat()).input().center());
                var query=new SharedRows.Query(SharedRuntimeProbe.OPEN,SharedRuntimeProbe.WALK,
                        Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096);
                check(SharedRuntimeProbe.get(host.runtime.shared.request(Level.OVERWORLD,section,query,()->true,Long.MAX_VALUE)).candidate(),
                        "capacity existing section "+index);
            }
            drain(host);samples.add(measure(host,"64-live-slots",null));
            SectionPos extra=SectionPos.of(192,5,0);
            host.publish(extra,1,Fixtures.fromFlags("65th",Fixtures.flat()).input().center());
            var query=new SharedRows.Query(SharedRuntimeProbe.OPEN,SharedRuntimeProbe.WALK,
                    Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096);
            Throwable error=host.runtime.shared.request(Level.OVERWORLD,extra,query,()->true,Long.MAX_VALUE)
                    .handle((value,failure)->failure).get(10,TimeUnit.SECONDS);
            check(error instanceof SharedSectionCandidate.AdmissionRejectedException,"65th distinct current slot rejected by actual typed cap");
            for(int index=0;index<64;index++) {
                SectionPos section=SectionPos.of(index*3,5,0);
                long previous=host.versions.get(section);host.versions.put(section,previous+1);
                host.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                        new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD,section),1,previous,previous+1,
                        TopologyWorkerRuntime.FactState.UNLOADED,null,Map.of(),false));
                host.runtime.publishChunkUnload(Level.OVERWORLD,new ChunkPos(index*3,0),1);
            }
            SharedRuntimeProbe.settle(()->host.runtime.sharedTransition(()->host.runtime.shared.slots.values()
                    .stream().noneMatch(slot->slot.lease.current())));
            error=host.runtime.shared.request(Level.OVERWORLD,extra,query,()->true,Long.MAX_VALUE)
                    .handle((value,failure)->failure).get(10,TimeUnit.SECONDS);
            check(error instanceof SharedSectionCandidate.AdmissionRejectedException,"unloading without another same-key query does not reclaim shared slots");
            drain(host);samples.add(measure(host,"64-stale-slots-after-unload",null));
            check(host.runtime.shared.metrics().get("slots")==64,"stale slots remain bounded but occupy cap");
        }
        check(SharedOwnershipAgent.roots().isEmpty(),"capacity observer roots cleared at actual close");
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Throwable failure=null;
        try {
            fullHalo("flat-27-facts",false);fullHalo("door-27-facts-and-air",true);
            oldSnapshotAndWorker();capacity();
        } catch(Throwable error) { failure=error;error.printStackTrace(); }
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("passed",failure==null);result.put("failure",failure==null?"":failure.toString());
        result.put("checks",checks);result.put("samples",samples);
        result.put("jvmPid",ProcessHandle.current().pid());
        result.put("transformedClasses",SharedOwnershipAgent.transformed());
        result.put("jvmArgs",ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("scope","identity-deduplicated Runtime reachable fields plus actual blocked worker roots and in-use old rows; class metadata, threads, canonical dimension keys, diagnostic hooks excluded; heap proxy is whole-process and taken before traversal");
        result.put("peakQualification","controlled checkpoint upper observation; agent retains per-task temporary roots until the existing task cleanup, so not a continuous or exclusive JVM peak");
        Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("SHARED_SUPPLEMENT checks="+checks.size()+" passed="+(failure==null));
        if(failure!=null)System.exit(1);
    }
}
