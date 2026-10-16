package com.scarasol.acceleratednavigation.topology;

import com.google.gson.GsonBuilder;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class ScratchControls {
    static final Map<String, Object> evidence = new LinkedHashMap<>();
    static final List<Map<String, Object>> pairs = new ArrayList<>();
    static void check(boolean condition, String name) { SharedRuntimeProbe.check(condition, name); }
    static SharedRows.Query query(BaseClusterTopology.GeometryKey body, int start, int goal, int limit) {
        return new SharedRows.Query(body, SharedRuntimeProbe.WALK, start, goal, limit);
    }
    static Map<String, Object> dump(SharedRows.Outcome outcome) {
        var rows = new ArrayList<Object>();
        new TreeMap<>(outcome.change().rows()).forEach((cell, row) ->
                rows.add(Map.of("cell", cell, "targets", row.targets(), "masks", row.masks())));
        return Map.of("candidate", outcome.candidate(), "state", outcome.state(), "cells", outcome.cells(),
                "legal", outcome.change().legal(), "rows", rows,
                "evaluations", outcome.evaluations(), "replacements", outcome.replacements());
    }
    static void equal(SharedRows.Outcome a, SharedRows.Outcome b) {
        check(a.candidate() == b.candidate() && a.state().equals(b.state())
                && a.cells().equals(b.cells()) && a.evaluations() == b.evaluations()
                && a.replacements() == b.replacements(), "allocation/reuse exact outcome");
        check(Arrays.equals(a.change().legal(), b.change().legal())
                && a.change().rows().keySet().equals(b.change().rows().keySet()), "allocation/reuse exact delta keys/legal");
        for (var entry : a.change().rows().entrySet())
            check(SharedRows.same(entry.getValue(), b.change().rows().get(entry.getKey())), "allocation/reuse exact delta edges");
    }
    static <T> T worker(SharedRuntimeProbe.Host host, Callable<T> action) throws Exception {
        var done = new CompletableFuture<T>();
        host.runtime.sharedTransition(() -> {
            host.runtime.submitSharedWork(() -> {
                try { done.complete(action.call()); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            }, handle -> {}, done::completeExceptionally);
            return null;
        });
        return done.get(15, TimeUnit.SECONDS);
    }
    static void differential() throws Exception {
        var fixtures = List.of(Fixtures.fromFlags("flat", Fixtures.flat()),
                Fixtures.wall("narrow", 1, false), Fixtures.wall("water", 1, true),
                Fixtures.door("wood", true, false, true), Fixtures.door("iron", false, false, true));
        var bodies = List.of(SharedRuntimeProbe.OPEN, SharedRuntimeProbe.SMALL,
                Fixtures.body(.9F, 1.4F, false, false), Fixtures.body(.6F, 1.95F, true, true),
                Fixtures.body(.6F, 2.9F, false, true));
        var movements = List.of(new BaseClusterTopology.MovementKey(0, 0, 0), SharedRuntimeProbe.WALK,
                new BaseClusterTopology.MovementKey(1, 2, 4));
        var scratch = new QueryScratch();
        var retained = new ArrayList<SharedRows.Outcome>();
        var gson = new GsonBuilder().create();
        var before = new ArrayList<String>();
        for (var fixture : fixtures) {
            var snapshot = SharedRows.create(fixture.input());
            var original = snapshot;
            for (var body : bodies) for (var movement : movements) for (int limit : new int[]{0, 4096}) {
                var query = new SharedRows.Query(body, movement, Fixtures.cell(3, 1, 7),
                        Fixtures.cell(12, 1, 7), limit);
                Arrays.fill(scratch.parent, 4095); Arrays.fill(scratch.queue, 4095);
                var allocated = snapshot.query(query);
                var reused = snapshot.query(query, scratch);
                equal(allocated, reused);
                check(!scratch.busy, "scratch released after differential query");
                var kernel = new LocalKernel(body.canOpenDoors() ? snapshot.air : snapshot.raw,
                        body, new BaseClusterTopology.BuildScratch());
                var masks = new ArrayList<Long>();
                for (int i = 0; i + 1 < reused.cells().size(); i++) {
                    long mask = kernel.outgoing(reused.cells().get(i)).mask(reused.cells().get(i + 1));
                    masks.add(mask);
                    check((mask & movement.capabilityMask()) != 0, "differential path uses exact capability edge");
                }
                pairs.add(Map.of("fixture", fixture.name(), "query", query, "capability", movement.capabilityMask(),
                        "allocated", dump(allocated), "reused", dump(reused), "edgeMasks", masks));
                retained.add(reused); before.add(gson.toJson(dump(reused)));
                snapshot = snapshot.merge(body, reused.change());
            }
            check(original.layers.isEmpty(), "original snapshot remains unchanged after sequential upgrades");
        }
        long immutableLists = 0;
        for (int i = 0; i < retained.size(); i++) {
            check(before.get(i).equals(gson.toJson(dump(retained.get(i)))), "old Outcome survives all later dirty-array queries");
            try { retained.get(i).cells().add(0); throw new AssertionError("mutable cells"); }
            catch (UnsupportedOperationException expected) { immutableLists++; }
        }
        check(pairs.size() == 150 && immutableLists == 150, "150 interleaved differential cases and immutable result lists");
        evidence.put("pairs", pairs);
        evidence.put("oldResultSnapshots", before);
        evidence.put("oldResultSnapshotsAfter", retained.stream().map(o -> gson.toJson(dump(o))).toList());
    }
    static void runtimeIsolation() throws Exception {
        var host = new SharedRuntimeProbe.Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center());
        var owners = new ConcurrentHashMap<Long, QueryScratch>();
        var events = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        try {
            SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN));
            try { host.runtime.sharedQueryScratch(); throw new AssertionError("caller obtained worker scratch"); }
            catch (IllegalStateException expected) { check(true, "scratch access rejects outside actual worker task"); }
            for (int batch = 0; batch < 2; batch++) {
                SharedRuntimeProbe.drain(host);
                var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
                final int round = batch;
                host.runtime.shared.queryHook = (phase, rows, query, active) -> {
                    if (!phase.equals("known")) return;
                    long thread = Thread.currentThread().getId();
                    if (host.runtime.sharedLockHeld() || host.runtime.sharedQueryMonitorHeld())
                        throw new AssertionError("query hook under lock");
                    if (active != host.runtime.sharedQueryScratch() || active.owner != Thread.currentThread() || !active.busy)
                        throw new AssertionError("not actual acquired worker scratch");
                    var previous = owners.putIfAbsent(thread, active);
                    if (previous != null && previous != active) throw new AssertionError("worker did not reuse scratch");
                    events.add(Map.of("batch", round, "thread", thread,
                            "scratch", System.identityHashCode(active), "parent", System.identityHashCode(active.parent),
                            "queue", System.identityHashCode(active.queue),
                            "bytes", SizeAgent.deep(active, Set.of(active.owner)), "locksHeld", false));
                    entered.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("scratch gate timeout");
                };
                var a = host.request(SharedRuntimeProbe.OPEN, Fixtures.cell(3 + batch, 1, 7), Fixtures.cell(12, 1, 7),
                        4096, () -> true, Long.MAX_VALUE);
                var b = host.request(SharedRuntimeProbe.OPEN, Fixtures.cell(6 + batch, 1, 7), Fixtures.cell(13, 1, 7),
                        4096, () -> true, Long.MAX_VALUE);
                try { check(entered.await(8, TimeUnit.SECONDS), "both actual workers hold acquired scratch"); }
                finally { release.countDown(); }
                check(SharedRuntimeProbe.get(a).candidate() && SharedRuntimeProbe.get(b).candidate(), "gated scratch queries succeed");
            }
            check(owners.size() == 2, "two actual worker owners");
            var objects = new ArrayList<>(owners.values());
            check(objects.get(0) != objects.get(1) && objects.get(0).parent != objects.get(1).parent
                    && objects.get(0).queue != objects.get(1).queue, "two workers have distinct arrays");
            host.runtime.shared.queryHook = null;
            var snapshot = host.runtime.sharedTransition(() -> host.runtime.shared.slots.values().iterator().next().snapshot);
            try { snapshot.query(query(SharedRuntimeProbe.OPEN, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7), 4096), objects.get(0));
                throw new AssertionError("foreign scratch accepted"); }
            catch (IllegalStateException expected) { check(true, "foreign-thread supplied scratch rejected"); }
            evidence.put("workerEvents", events); evidence.put("beforeClose", host.runtime.sharedScratchMetrics());
        } finally { host.close(); }
        evidence.put("afterClose", host.runtime.sharedScratchMetrics());
    }
    static void nestedAndFailures() throws Exception {
        try (var host = new SharedRuntimeProbe.Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            var records = worker(host, () -> {
                var scratch = host.runtime.sharedQueryScratch();
                var flat = SharedRows.create(Fixtures.fromFlags("flat", Fixtures.flat()).input());
                var outer = query(SharedRuntimeProbe.OPEN, Fixtures.cell(3, 1, 7), Fixtures.cell(12, 1, 7), 4096);
                var inner = query(SharedRuntimeProbe.OPEN, Fixtures.cell(14, 1, 9), Fixtures.cell(2, 1, 2), 4096);
                var record = new LinkedHashMap<String, Object>();
                var outerBaseline = flat.query(outer);
                var innerBaseline = flat.query(inner);
                var nestedSeen = new AtomicBoolean();
                var result = flat.query(outer, scratch, (phase, rows, query, active) -> {
                    check(phase.equals("known") && active == scratch && active.busy, "outer hook after BFS before reconstruct");
                    check(!host.runtime.sharedLockHeld() && !host.runtime.sharedQueryMonitorHeld(), "nested hook outside both locks");
                    int[] parentBefore = active.parent.clone(), queueBefore = active.queue.clone();
                    var nested = rows.query(inner, active, (nestedPhase, nestedRows, nestedQuery, nestedScratch) -> {
                        check(nestedScratch != active && nestedScratch.parent != active.parent
                                && nestedScratch.queue != active.queue && nestedScratch.busy,
                                "busy scratch uses exclusive fallback arrays");
                        check(nestedScratch.owner == Thread.currentThread() && active.owner == Thread.currentThread(),
                                "nested query stays on same actual worker");
                        nestedSeen.set(true);
                    });
                    equal(innerBaseline, nested);
                    check(active.busy && Arrays.equals(parentBefore, active.parent) && Arrays.equals(queueBefore, active.queue),
                            "nested query does not change outer arrays before reconstruction");
                    record.put("outerParentBefore", parentBefore); record.put("outerParentAfter", active.parent.clone());
                    record.put("outerQueueBefore", queueBefore); record.put("outerQueueAfter", active.queue.clone());
                    record.put("innerBaseline", dump(innerBaseline)); record.put("innerResult", dump(nested));
                });
                equal(outerBaseline, result);
                check(nestedSeen.get() && scratch.fallbacks == 1 && !scratch.busy, "nested fallback observed and outer released");
                record.put("outerBaseline", dump(outerBaseline)); record.put("outerResult", dump(result));
                record.put("fallbacks", scratch.fallbacks); record.put("worker", Thread.currentThread().getId());
                var failureRows = new ArrayList<Object>();
                for (String kind : List.of("exception", "Error", "interrupt", "nested-Error")) {
                    try {
                        flat.query(outer, scratch, (phase, rows, query, active) -> {
                            switch (kind) {
                                case "exception" -> throw new IllegalStateException("injected exception");
                                case "Error" -> throw new AssertionError("injected Error");
                                case "interrupt" -> throw new InterruptedException("injected interrupt");
                                case "nested-Error" -> rows.query(inner, active, (p, r, q, temporary) -> {
                                    throw new AssertionError("injected nested Error");
                                });
                            }
                        });
                        throw new AssertionError("injection did not fail");
                    } catch (Throwable failure) {
                        check(failure.getMessage().startsWith("injected"), "expected acquired-query failure");
                    }
                    check(!scratch.busy, "released after " + kind);
                    Arrays.fill(scratch.parent, 4095); Arrays.fill(scratch.queue, 4095);
                    var fresh = flat.query(outer, scratch); equal(outerBaseline, fresh);
                    failureRows.add(Map.of("kind", kind, "baseline", dump(outerBaseline), "after", dump(fresh), "busy", scratch.busy));
                }
                var water = SharedRows.create(Fixtures.wall("water", 1, true).input());
                var wet = query(Fixtures.body(.6F, 1.95F, true, true), outer.start(), outer.goal(), 4096);
                var phases = new ArrayList<String>();
                var upgraded = water.query(wet, scratch, (phase, rows, query, active) -> {
                    check(active == scratch, "known and local reuse same acquired arrays");
                    phases.add(phase);
                });
                check(upgraded.candidate() && upgraded.evaluations() > 0 && phases.equals(List.of("known", "local")),
                        "known search fails then exact local search succeeds");
                equal(water.query(wet), upgraded);
                try {
                    water.query(wet, scratch, (phase, rows, query, active) -> {
                        if (phase.equals("known")) Thread.currentThread().interrupt();
                    });
                    throw new AssertionError("local search ignored interruption");
                } catch (InterruptedException expected) {
                    check(Thread.currentThread().isInterrupted() && !scratch.busy, "actual local interrupt releases scratch");
                } finally { Thread.interrupted(); }
                equal(water.query(wet), water.query(wet, scratch));
                record.put("failures", failureRows); record.put("upgradePhases", phases);
                record.put("upgraded", dump(upgraded)); record.put("busyAfter", scratch.busy);
                return record;
            });
            evidence.put("nested", records);
            var captured = new AtomicReference<QueryScratch>();
            for (String kind : List.of("exception", "Error", "interrupt")) {
                host.runtime.shared.queryHook = (phase, rows, query, active) -> {
                    captured.set(active);
                    if (kind.equals("Error")) throw new AssertionError("injected Runtime Error");
                    if (kind.equals("interrupt")) throw new InterruptedException("injected Runtime interrupt");
                    throw new IllegalStateException("injected Runtime exception");
                };
                SharedRuntimeProbe.rejected(host.request(SharedRuntimeProbe.OPEN), "Runtime settles acquired-hook " + kind);
                check(captured.get() != null && !captured.get().busy && host.runtime.shared.metrics().get("flights") == 0,
                        "Runtime failure releases scratch and flight");
                host.runtime.shared.queryHook = null;
                check(SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN)).candidate(), "Runtime recovers after acquired-hook failure");
            }
        }
    }
    static void exitPaths() throws Exception {
        var exits = new ArrayList<Object>();
        for (String kind : List.of("unused", "partial", "VirtualMachineError", "ThreadDeath")) {
            var executor = new TopologyTaskExecutor(() -> true);
            var done = new CountDownLatch(1);
            if (!kind.equals("unused")) executor.submitSearch(NavigationScheduler.Priority.ACTIVE,
                    TopologyTaskExecutor.WorkKind.QUICK_SEARCH, () -> {
                        executor.currentQueryScratch(); done.countDown();
                        if (kind.equals("VirtualMachineError")) throw new InternalError("injected worker fatal");
                        if (kind.equals("ThreadDeath")) throw new ThreadDeath();
                    });
            if (!kind.equals("unused")) check(done.await(8, TimeUnit.SECONDS), "exit control actual task ran");
            executor.shutdown();
            check(executor.awaitTermination(8, TimeUnit.SECONDS), "exit control terminated");
            var metrics = executor.scratchMetrics();
            check(metrics.get("created") == (kind.equals("unused") ? 0 : 1) && metrics.get("live") == 0
                    && metrics.get("created").equals(metrics.get("cleared")) && metrics.get("aliveWorkers") == 0,
                    "cache removed on " + kind + " with actual dead threads");
            exits.add(Map.of("kind", kind, "metrics", metrics));
        }
        evidence.put("exitPaths", exits);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Throwable failure = null;
        try { differential(); runtimeIsolation(); nestedAndFailures(); exitPaths(); }
        catch (Throwable problem) { failure = problem; problem.printStackTrace(); }
        var output = new LinkedHashMap<String, Object>();
        output.put("passed", failure == null); output.put("failure", failure == null ? "" : failure.toString());
        output.put("checks", SharedRuntimeProbe.checks); output.put("evidence", evidence);
        output.put("jvmPid", ProcessHandle.current().pid());
        Files.writeString(Path.of(args[0]), new GsonBuilder().create().toJson(output));
        System.out.println("SCRATCH_CONTROLS checks=" + SharedRuntimeProbe.checks.size() + " passed=" + (failure == null));
        if (failure != null) System.exit(1);
    }
}
