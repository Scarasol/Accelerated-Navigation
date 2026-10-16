package com.scarasol.acceleratednavigation.topology;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;

public final class SharedRuntimeProbe {
    static final List<Map<String, Object>> checks = new ArrayList<>(), cases = new ArrayList<>();
    static final SectionPos SECTION = SectionPos.of(0, 5, 0);
    static final BaseClusterTopology.GeometryKey SMALL = Fixtures.body(.6F, 1.95F, false, false);
    static final BaseClusterTopology.GeometryKey OPEN = Fixtures.body(.6F, 1.95F, false, true);
    static final BaseClusterTopology.MovementKey WALK = new BaseClusterTopology.MovementKey(1, 0, 3);
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add(Map.of("name", name, "passed", true));
    }
    static final class Host implements AutoCloseable {
        final TopologyWorkerRuntime runtime = new TopologyWorkerRuntime(
                (key, id, delta) -> {}, decision -> { throw new AssertionError("unexpected persistence"); });
        final Map<SectionPos, Long> versions = new HashMap<>();
        void publish(SectionPos section, long load, BaseClusterTopology.PackedFacts facts) {
            long previous = versions.getOrDefault(section, 0L), next = previous + 1;
            versions.put(section, next);
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section),
                    load, previous, next, TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of(), false));
        }
        Host(BaseClusterTopology.PackedFacts facts) {
            runtime.shared.packedRows = !Boolean.getBoolean("an.objectRows");
            runtime.shared.fastCapture = !Boolean.getBoolean("an.captureEach");
            runtime.shared.queryScratch = Boolean.parseBoolean(System.getProperty("an.queryScratch", "true"));
            runtime.sharedTiming(Boolean.getBoolean("an.timing"));
            publish(SECTION, 1, facts);
        }
        void unload() {
            long previous = versions.get(SECTION), next = previous + 1;
            versions.put(SECTION, next);
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SECTION),
                    1, previous, next, TopologyWorkerRuntime.FactState.UNLOADED, null, Map.of(), false));
            runtime.publishChunkUnload(Level.OVERWORLD, new ChunkPos(0, 0), 1);
        }
        CompletableFuture<RuntimeSharedTopology.Result> request(BaseClusterTopology.GeometryKey body,
                                                               int start, int goal, int limit,
                                                               BooleanSupplier current, long ttl) {
            return runtime.shared.request(Level.OVERWORLD, SECTION,
                    new SharedRows.Query(body, WALK, start, goal, limit), current,
                    ttl == Long.MAX_VALUE ? ttl : System.nanoTime() + ttl);
        }
        CompletableFuture<RuntimeSharedTopology.Result> request(BaseClusterTopology.GeometryKey body) {
            return request(body, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7),
                    4096, () -> true, Long.MAX_VALUE);
        }
        @Override public void close() {
            check(runtime.awaitStopped(10, TimeUnit.SECONDS), "Runtime workers stopped");
            var metrics = runtime.shared.metrics();
            check(metrics.get("flights") == 0 && metrics.get("captures") == 0
                    && metrics.get("slots") == 0 && metrics.get("retained") == 0, "shared state zero on close");
            check(runtime.metrics().activeReferences() == 0 && runtime.metrics().buildDemands() == 0,
                    "Runtime input pins and build demands zero on close");
            var scratch = runtime.sharedScratchMetrics();
            check(scratch.get("live") == 0 && scratch.get("created").equals(scratch.get("cleared"))
                    && scratch.get("aliveWorkers") == 0, "worker scratch cleared and threads dead on close");
        }
    }
    static final class Gate implements RuntimeSharedTopology.Hook {
        final String point;
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);
        final Set<Long> threads = ConcurrentHashMap.newKeySet();
        Gate(String point, int workers) { this.point = point; entered = new CountDownLatch(workers); }
        public void enter(String at, RuntimeSharedTopology.FlightKey key) throws Exception {
            if (!at.equals(point)) return;
            threads.add(Thread.currentThread().getId()); entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("controlled gate timeout");
        }
        void await() throws Exception { check(entered.await(8, TimeUnit.SECONDS), "real worker gate entered"); }
    }
    static RuntimeSharedTopology.Result get(CompletableFuture<RuntimeSharedTopology.Result> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
    static void rejected(CompletableFuture<?> future, String name) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); throw new AssertionError(name + " unexpectedly succeeded"); }
        catch (CancellationException | ExecutionException expected) { check(future.isDone(), name); }
    }
    static void settle(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        check(condition.getAsBoolean(), "controlled state settled");
    }
    static void drain(Host host) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            var tasks=host.runtime.metrics().tasks();
            if(tasks.queued().quickSearches()==0&&tasks.running().quickSearches()==0
                    &&host.runtime.shared.metrics().get("flights")==0
                    &&host.runtime.shared.metrics().get("captures")==0)return;
            Thread.sleep(1);
        }
        throw new AssertionError("drain timed out");
    }
    static void mergedCancellation() throws Exception {
        try (var host = new Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            get(host.request(OPEN));
            var gate = new Gate("before-query", 1); host.runtime.shared.hook = gate;
            var first = host.request(SMALL); gate.await();
            var rest = new ArrayList<CompletableFuture<RuntimeSharedTopology.Result>>();
            for (int i = 0; i < 127; i++) rest.add(host.request(SMALL));
            settle(() -> host.runtime.shared.metrics().get("joined") == 127);
            check(first.cancel(false), "leader subscriber cancelled");
            gate.release.countDown();
            for (var future : rest) check(get(future).candidate(), "follower survives leader cancellation");
            check(host.runtime.shared.metrics().get("jobs") == 2, "identical request only one worker job");
            long rows = host.runtime.shared.metrics().get("rows");
            get(host.request(SMALL));
            check(host.runtime.shared.metrics().get("rows") == rows, "warm query generates no new rows");
            cases.add(Map.of("name", "same-key-128-and-leader-cancel", "metrics", host.runtime.shared.metrics()));
        }
    }
    static void twoWorkersMerge() throws Exception {
        try (var host = new Host(Fixtures.door("door", true, false, true).input().center())) {
            get(host.request(OPEN));
            var before = host.runtime.shared.slots.values().iterator().next().snapshot;
            var gate = new Gate("before-publish", 2); host.runtime.shared.hook = gate;
            var a = host.request(SMALL);
            var b = host.request(Fixtures.body(.9F, 1.4F, false, false));
            gate.await();
            check(gate.threads.size() == 2, "two actual Runtime workers run independent upgrades");
            check(before.layers.isEmpty(), "unpublished upgrades leave old snapshot immutable");
            gate.release.countDown();
            check(!get(a).candidate() && !get(b).candidate(), "closed door negative controls");
            var after = host.runtime.shared.slots.values().iterator().next().snapshot;
            check(after.layers.size() == 2 && before.layers.isEmpty(), "both geometry deltas published without mutating old version");
            check(host.runtime.shared.metrics().get("conflicting") == 0, "concurrent row merge consistent");
            check(host.runtime.shared.metrics().get("replacements") > 0, "door replacement actually executed");
            check(get(host.request(OPEN)).candidate(), "open-door basis unchanged by closed-door replacements");
            cases.add(Map.of("name", "two-worker-different-geometry", "metrics", host.runtime.shared.metrics()));
        }
    }
    static void inputRace(String name, boolean halo, boolean reload) throws Exception {
        try (var host = new Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            if (name.equals("loaded-halo-version"))
                host.publish(SectionPos.of(1, 5, 0), 1, Fixtures.fromFlags("neighbor", Fixtures.flat()).input().center());
            get(host.request(OPEN));
            var lease = host.runtime.sharedTransition(() -> host.runtime.shared.slots.values().iterator().next().lease);
            var gate = new Gate("before-publish", 1); host.runtime.shared.hook = gate;
            var old = host.request(SMALL); gate.await();
            if (reload) {
                host.unload();
                settle(() -> !lease.current());
                settle(() -> host.runtime.metrics().events().pendingKeys() == 0
                        && host.runtime.metrics().events().activeBatchKeys() == 0);
                host.publish(SECTION, 2, Fixtures.fromFlags("flat2", Fixtures.flat()).input().center());
            } else host.publish(halo ? SectionPos.of(1, 5, 0) : SECTION, 1,
                    Fixtures.wall("blocked", 0, false).input().center());
            settle(() -> !lease.current());
            gate.release.countDown(); rejected(old, name + " late result rejected");
            host.runtime.shared.hook = (point, key) -> {};
            var fresh = get(host.request(SMALL));
            check(fresh.current(), name + " fresh current result");
            if (!halo && !reload) check(!fresh.candidate(), "center change fresh candidate blocked");
            else check(fresh.candidate(), name + " fresh positive control");
            cases.add(Map.of("name", name, "metrics", host.runtime.shared.metrics()));
        }
    }
    static void subscriberRules() throws Exception {
        try (var host = new Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            get(host.request(OPEN));
            var gate = new Gate("before-publish", 1); host.runtime.shared.hook = gate;
            AtomicLong generation = new AtomicLong(1);
            var old = host.request(SMALL, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7),
                    4096, () -> generation.get() == 1, Long.MAX_VALUE);
            gate.await();
            var ttl = host.request(SMALL, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7),
                    4096, () -> true, 0);
            var good = host.request(SMALL);
            generation.incrementAndGet();
            settle(() -> host.runtime.shared.metrics().get("joined") == 2);
            gate.release.countDown();
            rejected(old, "request/capability generation rejected");
            rejected(ttl, "TTL waiter rejected");
            check(get(good).candidate(), "current waiter survives supersession and TTL peer");
            cases.add(Map.of("name", "request-generation-and-TTL", "metrics", host.runtime.shared.metrics()));
        }
    }
    static void deliveryTtl() throws Exception {
        try(var host=new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())){
            get(host.request(OPEN));
            var publish=new Gate("before-publish",1);
            var delivery=new Gate("before-delivery",1);
            host.runtime.shared.hook=publish;
            long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            var query=new SharedRows.Query(SMALL,WALK,Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096);
            var timed=host.runtime.shared.request(Level.OVERWORLD,SECTION,query,()->true,expires);
            publish.await();
            var good=host.request(SMALL);
            settle(()->host.runtime.shared.metrics().get("joined")==1);
            host.runtime.shared.hook=delivery;
            publish.release.countDown();
            delivery.await();
            long observed=System.nanoTime();
            try {
                check(observed<expires,"flight settled while TTL still valid");
                settle(()->System.nanoTime()>=expires);
            }finally{delivery.release.countDown();}
            long released=System.nanoTime();
            rejected(timed,"TTL expires between settlement and delivery");
            check(get(good).candidate(),"nonexpired subscriber survives delayed delivery");
            check(host.runtime.shared.metrics().get("expired")==1,"delivery-time expiry counted once");
            cases.add(Map.of("name","settlement-before-delivery-TTL","settleObservedNs",observed,
                    "expiresNs",expires,"releasedNs",released,"metrics",host.runtime.shared.metrics()));
        }
    }
    static void errorSettlement() throws Exception {
        try (var host = new Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            get(host.request(OPEN));
            host.runtime.shared.hook = (point, key) -> {
                if (point.equals("before-query")) throw new AssertionError("injected Error");
            };
            rejected(host.request(SMALL), "AssertionError completes future");
            check(host.runtime.shared.metrics().get("flights") == 0, "Error leaves no pending flight");
            host.runtime.shared.hook = (point, key) -> {};
            check(get(host.request(SMALL)).candidate(), "Runtime usable after Error");
            cases.add(Map.of("name", "Error-settlement", "metrics", host.runtime.shared.metrics()));
        }
    }
    static void sameLayerEndpoints() throws Exception {
        try (var host = new Host(Fixtures.wall("water", 1, true).input().center())) {
            get(host.request(OPEN));
            var before = host.runtime.sharedTransition(() -> host.runtime.shared.slots.values().iterator().next().snapshot);
            var gate = new Gate("before-publish", 2); host.runtime.shared.hook = gate;
            var water = Fixtures.body(.6F, 1.95F, true, true);
            var a = host.request(water);
            var b = host.request(water, Fixtures.cell(12,1,7), Fixtures.cell(3,1,7), 4096, () -> true, Long.MAX_VALUE);
            gate.await(); gate.release.countDown();
            check(get(a).candidate() && get(b).candidate(), "same-layer different endpoints both succeed");
            var after = host.runtime.sharedTransition(() -> host.runtime.shared.slots.values().iterator().next().snapshot);
            check(gate.threads.size() == 2 && after.layers.size() == before.layers.size() + 1
                            && !before.layers.containsKey(water),
                    "same geometry overlapping rows merge from two workers");
            check(after.version == before.version + 2, "two independently published immutable versions");
            check(host.runtime.shared.metrics().get("conflicting") == 0, "overlapping exact rows agree");
            cases.add(Map.of("name","two-worker-same-layer-endpoints","metrics",host.runtime.shared.metrics()));
        }
    }
    static void callbackAndSupplier() throws Exception {
        try (var host = new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())) {
            get(host.request(OPEN));
            var gate = new Gate("before-publish",1); host.runtime.shared.hook = gate;
            var broken = host.request(SMALL, Fixtures.cell(3,1,7), Fixtures.cell(12,1,7),4096,
                    () -> { if (host.runtime.sharedLockHeld()) throw new AssertionError("supplier held lock");
                            throw new IllegalStateException("injected subscriber"); },Long.MAX_VALUE);
            gate.await();
            var good = host.request(SMALL);
            AtomicBoolean locked = new AtomicBoolean(true);
            var callback = good.thenRun(() -> locked.set(host.runtime.sharedLockHeld()));
            settle(() -> host.runtime.shared.metrics().get("joined") == 1);
            gate.release.countDown();
            rejected(broken,"throwing supplier settles itself");
            check(get(good).candidate(),"throwing supplier does not strand peer");
            callback.get(10,TimeUnit.SECONDS);
            check(!locked.get(),"future callbacks execute outside Runtime lock");
            cases.add(Map.of("name","callback-lock-and-supplier-failure","metrics",host.runtime.shared.metrics()));
        }
    }
    static void allCancelAndBaseFailure() throws Exception {
        try (var host = new Host(Fixtures.wall("water",1,true).input().center())) {
            get(host.request(OPEN)); long rows = host.runtime.shared.metrics().get("rows");
            var gate = new Gate("before-publish",1); host.runtime.shared.hook = gate;
            var a = host.request(Fixtures.body(.6F,1.95F,true,true)); gate.await();
            var b = host.request(Fixtures.body(.6F,1.95F,true,true));
            settle(() -> host.runtime.shared.metrics().get("joined") == 1);
            check(a.cancel(false) && b.cancel(false),"all subscribers cancelled");
            gate.release.countDown();
            settle(() -> host.runtime.shared.metrics().get("flights") == 0);
            check(host.runtime.shared.metrics().get("rows") == rows,"unsubscribed job publishes no delta");
            host.runtime.shared.hook = (point,key) -> {};
            check(get(host.request(Fixtures.body(.6F,1.95F,true,true))).candidate(),"new request after all cancelled");
            cases.add(Map.of("name","all-subscribers-cancel","metrics",host.runtime.shared.metrics()));
        }
        try (var host = new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())) {
            host.runtime.shared.maxBytes = 0;
            rejected(host.request(OPEN),"base byte cap settles future");
            check(host.runtime.shared.metrics().get("slots")==0,"byte rejected base lease retired");
            host.runtime.shared.maxBytes = 32L*1024*1024;
            host.runtime.shared.hook=(point,key)->{if(point.equals("before-base"))throw new AssertionError("base Error");};
            rejected(host.request(OPEN),"base Error settles future");
            check(host.runtime.shared.metrics().get("slots")==0,"failed base lease retired");
            host.runtime.shared.hook=(point,key)->{};
            check(get(host.request(OPEN)).candidate(),"base retry after rejection and Error succeeds");
            cases.add(Map.of("name","base-cap-error-and-retry","metrics",host.runtime.shared.metrics()));
        }
    }
    static void closeDuringBase() throws Exception {
        var host = new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center());
        var gate = new Gate("before-base",1); host.runtime.shared.hook=gate;
        try {
            var pending=host.request(OPEN);gate.await();host.runtime.beginStopping();
            rejected(pending,"close during base promptly settles future");
        } finally {gate.release.countDown();host.close();}
        cases.add(Map.of("name","close-during-base","metrics",host.runtime.shared.metrics()));
    }
    static void closeRace() throws Exception {
        var host = new Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center());
        try {
            var delivered = get(host.request(OPEN));
            var gate = new Gate("before-publish", 1); host.runtime.shared.hook = gate;
            var pending = host.request(SMALL); gate.await();
            host.runtime.beginStopping();
            check(!delivered.current(),"already delivered candidate invalid on beginStopping");
            rejected(pending, "Runtime close promptly settles in-flight future");
            gate.release.countDown();
        } finally { host.close(); }
        cases.add(Map.of("name", "close-during-publish", "metrics", host.runtime.shared.metrics()));
    }
    static void limitsAndUnknown() throws Exception {
        try (var host = new Host(Fixtures.wall("water", 1, true).input().center())) {
            var water = Fixtures.body(.6F, 1.95F, true, true);
            var limited = get(host.request(water, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7),
                    0, () -> true, Long.MAX_VALUE));
            check(!limited.candidate() && limited.state().equals("UNKNOWN_LIMIT"), "zero upgrade remains unknown");
            var upgraded = get(host.request(water));
            check(upgraded.candidate() && upgraded.newRows() > 0, "water connectivity locally upgraded");
            host.runtime.shared.maxFlights = 0;
            rejected(host.request(SMALL), "bounded flight admission rejects");
            host.runtime.shared.maxFlights = 1024;
            check(get(host.request(water)).candidate(), "admission recovery preserves current cache");
            cases.add(Map.of("name", "bounded-admission-and-unknown", "metrics", host.runtime.shared.metrics()));
        }
        try (var host = new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())) {
            host.runtime.shared.maxSlots=0;
            rejected(host.request(OPEN),"bounded slots reject capture");
            check(host.runtime.shared.metrics().get("slots")==0,"slot rejection retains no cache");
            host.runtime.shared.maxSlots=64;
            check(get(host.request(OPEN)).candidate(),"slot admission recovery");
            cases.add(Map.of("name","slot-cap-and-recovery","metrics",host.runtime.shared.metrics()));
        }
    }
    static void concurrentCallers() throws Exception {
        try(var host=new Host(Fixtures.wall("water",1,true).input().center())){
            var callers=Executors.newFixedThreadPool(8);
            var threads=ConcurrentHashMap.<Long>newKeySet();
            var submitted=new ArrayList<Future<CompletableFuture<RuntimeSharedTopology.Result>>>();
            var ready=new CountDownLatch(8);var start=new CountDownLatch(1);
            try{
                for(int i=0;i<512;i++){
                    final int index=i;
                    submitted.add(callers.submit(()->{
                        threads.add(Thread.currentThread().getId());ready.countDown();start.await();
                        return host.request(Fixtures.body(index%2==0?.6F:.9F,1.95F,true,true),
                                Fixtures.cell(2+index%4,1,1+index/4%14),
                                Fixtures.cell(10+index/56%4,1,1+index/16%14),4096,()->true,Long.MAX_VALUE);
                    }));
                }
                check(ready.await(8,TimeUnit.SECONDS),"eight caller threads ready");start.countDown();
                for(var pending:submitted)check(get(pending.get(10,TimeUnit.SECONDS)).candidate(),"concurrent caller exact candidate");
                check(threads.size()==8,"eight concurrent caller threads used");
                check(host.runtime.shared.metrics().get("flights")==0 && host.runtime.shared.metrics().get("captures")==0,
                        "512 caller requests fully settled");
                cases.add(Map.of("name","eight-callers-512-requests","metrics",host.runtime.shared.metrics()));
            } finally {start.countDown();callers.shutdownNow();check(callers.awaitTermination(10,TimeUnit.SECONDS),"caller executor stopped");}
        }
    }
    static void captureAdmission() throws Exception {
        try(var host=new Host(Fixtures.fromFlags("flat",Fixtures.flat()).input().center())){
            get(host.request(OPEN));
            var gate=new Gate("before-query",2);host.runtime.shared.hook=gate;
            var a=host.request(SMALL);
            var b=host.request(Fixtures.body(.9F,1.4F,false,true));
            gate.await();
            try {
                var section=SectionPos.of(10,5,10);
                host.publish(section,1,Fixtures.fromFlags("other",Fixtures.flat()).input().center());
                host.runtime.shared.maxCaptures=1;
                var query=new SharedRows.Query(OPEN,WALK,Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096);
                var waiting=host.runtime.shared.request(Level.OVERWORLD,section,query,()->true,Long.MAX_VALUE);
                check(host.runtime.shared.metrics().get("captures")==1,"pending base capture actually queued");
                rejected(host.runtime.shared.request(Level.OVERWORLD,section,query,()->true,Long.MAX_VALUE),
                        "pending capture admission rejects before Runtime demand");
                check(waiting.cancel(false),"pending capture subscription cancelled");
                check(host.runtime.shared.metrics().get("captures")==0,"pending capture record removed after cancel");
            } finally {gate.release.countDown();}
            check(get(a).candidate()&&get(b).candidate(),"queued capture rejection preserves existing workers");
            host.runtime.shared.hook=(point,key)->{};
            check(get(host.request(OPEN)).candidate(),"capture admission recovery");
            cases.add(Map.of("name","pending-capture-cap-and-cancel","metrics",host.runtime.shared.metrics()));
        }
    }
    static void fullRowOracle() throws Exception {
        var fixtures = List.of(
                Fixtures.fromFlags("flat", Fixtures.flat()),
                Fixtures.wall("narrow", 1, false),
                Fixtures.wall("water", 1, true),
                Fixtures.door("wood", true, false, true),
                Fixtures.door("iron", false, false, true));
        for (var fixture : fixtures) try (var host = new Host(fixture.input().center())) {
            for (var body : List.of(OPEN, SMALL, Fixtures.body(.9F, 1.4F, false, false),
                    Fixtures.body(.6F, 1.95F, true, true), Fixtures.body(.6F, 2.9F, false, false))) {
                var answer = get(host.request(body));
                var input = body.canOpenDoors() ? SharedRows.airInput(fixture.input()) : fixture.input();
                var graph = BaseClusterTopology.build(SECTION, 1, input, body, new BaseClusterTopology.BuildScratch());
                boolean reference = SharedTopologyReference.reference(graph, new SharedTopologyReference.Query(
                        body, WALK, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7)));
                check(reference == answer.candidate(), fixture.name() + " exact compact topology candidate control");
                if (answer.candidate()) {
                    var kernel = new LocalKernel(input, body, new BaseClusterTopology.BuildScratch());
                    for (int i = 0; i + 1 < answer.cells().size(); i++)
                        check((kernel.outgoing(answer.cells().get(i)).mask(answer.cells().get(i + 1))
                                & WALK.capabilityMask()) != 0, fixture.name() + " every delivered edge exact");
                }
            }
            cases.add(Map.of("name", "oracle-" + fixture.name(), "metrics", host.runtime.shared.metrics()));
        }
    }
    static void movementMasks() throws Exception {
        byte[] step=Fixtures.flat(),drop=Fixtures.flat(),gap=Fixtures.flat();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            if(x>=8){step[Fixtures.cell(x,1,z)]=0;step[Fixtures.cell(x,2,z)]|=BaseClusterTopology.GROUND_OPEN;}
            if(x<8){for(int y=1;y<=3;y++)drop[Fixtures.cell(x,y,z)]=0;drop[Fixtures.cell(x,4,z)]|=BaseClusterTopology.GROUND_OPEN;}
            if(x==7||x==8)gap[Fixtures.cell(x,1,z)]&=~BaseClusterTopology.GROUND_OPEN;
        }
        var fixtures=List.of(Fixtures.fromFlags("step-mask",step),Fixtures.fromFlags("drop-mask",drop),Fixtures.fromFlags("jump-mask",gap));
        for(var fixture:fixtures)try(var host=new Host(fixture.input().center())){
            int start=Fixtures.cell(3,fixture.name().startsWith("drop")?4:1,7);
            int goal=Fixtures.cell(12,fixture.name().startsWith("step")?2:1,7);
            var graph=BaseClusterTopology.build(SECTION,1,fixture.input(),OPEN,new BaseClusterTopology.BuildScratch());
            var movements = new ArrayList<BaseClusterTopology.MovementKey>();
            for (int stepCapability = 0; stepCapability <= 1; stepCapability++) for (int jump = 0; jump <= 2; jump++)
                for (int depth = 0; depth <= 4; depth++)
                    movements.add(new BaseClusterTopology.MovementKey(stepCapability, jump, depth));
            for(var movement:movements){
                var answer=get(host.runtime.shared.request(Level.OVERWORLD,SECTION,
                        new SharedRows.Query(OPEN,movement,start,goal,4096),()->true,Long.MAX_VALUE));
                check(answer.candidate()==SharedTopologyReference.reference(graph,
                        new SharedTopologyReference.Query(OPEN,movement,start,goal)),fixture.name()+" movement mask exact candidate");
                var kernel=new LocalKernel(fixture.input(),OPEN,new BaseClusterTopology.BuildScratch());
                for(int i=0;i+1<answer.cells().size();i++)
                    check((kernel.outgoing(answer.cells().get(i)).mask(answer.cells().get(i+1))
                            &movement.capabilityMask())!=0,fixture.name()+" delivered movement mask exact");
            }
            check(host.runtime.shared.metrics().get("baseBuilds")==1,"movement keys share one basis");
            cases.add(Map.of("name",fixture.name(),"metrics",host.runtime.shared.metrics()));
        }
    }
    static void doorSupportInput() throws Exception {
        var door=Fixtures.door("support",true,true).input().center();
        var flags=door.cells();var shapes=new SectionGeometry.Cell[4096];
        for(int c=0;c<4096;c++)shapes[c]=new SectionGeometry.Cell(door.geometry().shape(c));
        int above=Fixtures.cell(8,1,7);
        shapes[above]=SectionGeometry.boxes(List.of(SectionGeometry.quantized(0,0,0,1,.5,1)));
        flags[above]=(byte)(BaseClusterTopology.VOLUME_OPEN|BaseClusterTopology.GROUND_OPEN|BaseClusterTopology.EXACT_REQUIRED);
        var slab=BaseClusterTopology.PackedFacts.fromCells(flags,SectionGeometry.fromCells(shapes));
        check((SharedRows.airFacts(slab).flags(above)&BaseClusterTopology.GROUND_OPEN)!=0,"intrinsic slab support above door retained");
        var lowerFlags=new byte[4096];Arrays.fill(lowerFlags,(byte)BaseClusterTopology.VOLUME_OPEN);
        var lowerShapes=new SectionGeometry.Cell[4096];Arrays.fill(lowerShapes,SectionGeometry.empty());
        lowerShapes[Fixtures.cell(8,15,7)]=new SectionGeometry.Cell(door.geometry().shape(Fixtures.cell(8,0,7)));
        var lower=BaseClusterTopology.PackedFacts.fromCells(lowerFlags,SectionGeometry.fromCells(lowerShapes));
        var centerFlags=new byte[4096];Arrays.fill(centerFlags,(byte)BaseClusterTopology.VOLUME_OPEN);
        centerFlags[Fixtures.cell(8,0,7)]|=BaseClusterTopology.GROUND_OPEN;
        var center=BaseClusterTopology.PackedFacts.fromCells(centerFlags);
        var cleared=SharedRows.airInput(Fixtures.input(center,Map.of(BaseClusterTopology.haloIndex(0,-1,0),lower)));
        check((cleared.center().flags(Fixtures.cell(8,0,7))&BaseClusterTopology.GROUND_OPEN)==0,"halo door is not support at center bottom");
        var grounded=SharedRows.airInput(Fixtures.input(door,Map.of(BaseClusterTopology.haloIndex(0,-1,0),
                BaseClusterTopology.PackedFacts.fromCells(new byte[4096]))));
        check((grounded.center().flags(Fixtures.cell(8,0,7))&BaseClusterTopology.GROUND_OPEN)!=0,"section-bottom door uses ordinary lower-halo support");
        flags=door.cells();flags[above]|=BaseClusterTopology.FLUID;
        var ambiguous=BaseClusterTopology.PackedFacts.fromCells(flags,door.geometry());
        try(var host=new Host(ambiguous)){
            Throwable problem=host.request(OPEN).handle((value,error)->error).get(10,TimeUnit.SECONDS);
            check(problem instanceof SharedSectionCandidate.UnsupportedInputException,"ambiguous fluid-door facts preserve typed rejection");
            check(host.runtime.shared.metrics().get("slots")==0,"unsupported air conversion releases lease");
            cases.add(Map.of("name","door-support-input-and-unsupported-fluid","metrics",host.runtime.shared.metrics()));
        }
        flags=door.cells();
        flags[Fixtures.cell(8,0,7)]|=BaseClusterTopology.FLUID;
        var fluidDoor=BaseClusterTopology.PackedFacts.fromCells(flags,door.geometry());
        byte[] before=fluidDoor.cells();
        try(var host=new Host(fluidDoor)){
            var gate=new Gate("before-base",1);host.runtime.shared.hook=gate;
            var leader=host.request(OPEN);gate.await();
            var follower=host.request(OPEN);
            var expired=host.request(OPEN,Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096,()->true,-1);
            var uninterested=host.request(OPEN,Fixtures.cell(3,1,7),Fixtures.cell(12,1,7),4096,()->false,Long.MAX_VALUE);
            check(leader.cancel(false),"unsupported base leader cancellation accepted");
            gate.release.countDown();
            Throwable problem=follower.handle((value,error)->error).get(10,TimeUnit.SECONDS);
            check(problem instanceof SharedSectionCandidate.UnsupportedInputException,"fluid inside door follower preserves typed rejection: "+problem);
            check(expired.handle((value,error)->error).get(10,TimeUnit.SECONDS) instanceof TimeoutException,"failed base still checks subscriber TTL");
            check(uninterested.handle((value,error)->error).get(10,TimeUnit.SECONDS) instanceof CancellationException,"failed base still checks subscriber interest");
            check(Arrays.equals(before,fluidDoor.cells()),"unsupported door conversion leaves raw facts unchanged");
            check(host.runtime.shared.metrics().get("slots")==0,"fluid inside door releases shared slot and lease");
            cases.add(Map.of("name","fluid-inside-door-unsupported","metrics",host.runtime.shared.metrics()));
        }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Throwable failure = null;
        try {
            mergedCancellation(); twoWorkersMerge(); sameLayerEndpoints(); inputRace("center-version", false, false);
            inputRace("missing-halo-arrival", true, false); inputRace("unload-reload", false, true);
            inputRace("loaded-halo-version",true,false);
            subscriberRules(); deliveryTtl(); errorSettlement(); callbackAndSupplier(); allCancelAndBaseFailure();
            limitsAndUnknown(); concurrentCallers(); captureAdmission(); closeRace(); closeDuringBase(); fullRowOracle();movementMasks();doorSupportInput();
            OptimizationControls.run();
        } catch (Throwable problem) { failure = problem; problem.printStackTrace(); }
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", failure == null); report.put("failure", failure == null ? "" : failure.toString());
        report.put("checks", checks); report.put("cases", cases); report.put("checkCount", checks.size());
        report.put("optimization", OptimizationControls.evidence);
        report.put("jvmPid", ProcessHandle.current().pid());
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SHARED_RUNTIME checks=" + checks.size() + " passed=" + (failure == null));
        if (failure != null) System.exit(1);
    }
}
