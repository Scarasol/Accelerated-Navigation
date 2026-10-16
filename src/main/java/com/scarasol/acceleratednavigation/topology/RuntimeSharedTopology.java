package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Shared section rows owned, published and closed by the Runtime. */
final class RuntimeSharedTopology {
    record Result(boolean candidate, String state, List<Integer> cells, long version,
                  List<TopologyWorkerRuntime.SectionStamp> stamps, long newRows, long workNs,
                  AtomicBoolean ownerOpen) {
        boolean current() { return ownerOpen.get() && stamps.stream().allMatch(TopologyWorkerRuntime.SectionStamp::current); }
    }
    record FlightKey(TopologyWorkerRuntime.ClusterKey section, SharedRows.Query query) {}
    static final class Timing {
        final long requestNs = System.nanoTime();
        long admitNs, enqueuedNs, selectedNs, workerNs, queryBeginNs, queryEndNs, publicationNs, deliveryNs;
        long taskSequence, queueAtEnqueue, queueAtSelection, workerThread;
        boolean joined;
        void copy(JobTiming job) {
            if (job == null) return;
            enqueuedNs = job.enqueuedNs; selectedNs = job.selectedNs;
            workerNs = job.workerNs; queryBeginNs = job.queryBeginNs; queryEndNs = job.queryEndNs;
            publicationNs = job.publicationNs; taskSequence = job.taskSequence;
            queueAtEnqueue = job.queueAtEnqueue; queueAtSelection = job.queueAtSelection;
            workerThread = job.workerThread;
        }
    }
    static final class JobTiming {
        long enqueuedNs, selectedNs, workerNs, queryBeginNs, queryEndNs, publicationNs;
        long taskSequence, queueAtEnqueue, queueAtSelection, workerThread;
    }
    record Ticket(CompletableFuture<Result> future, Timing timing) {}
    static final class Slot {
        final TopologyWorkerRuntime.BaseGraphView lease;
        SharedRows snapshot;
        CompletableFuture<SharedRows> base = new CompletableFuture<>();
        Slot(TopologyWorkerRuntime.BaseGraphView lease) { this.lease = lease; }
    }
    static final class Waiter {
        final CompletableFuture<Result> result = new CompletableFuture<>();
        final BooleanSupplier current;
        final long expires;
        final Timing timing;
        Waiter(BooleanSupplier current, long expires, Timing timing) {
            this.current = current; this.expires = expires; this.timing = timing;
        }
    }
    static final class Flight {
        final FlightKey key;
        final Slot slot;
        final List<Waiter> waiters = new ArrayList<>();
        TopologyTaskExecutor.TaskHandle task;
        JobTiming timing;
        Flight(FlightKey key, Slot slot) { this.key = key; this.slot = slot; }
    }
    interface Hook { void enter(String point, FlightKey key) throws Exception; }
    final TopologyWorkerRuntime runtime;
    final AtomicBoolean ownerOpen = new AtomicBoolean(true);
    final Map<TopologyWorkerRuntime.ClusterKey, Slot> slots = new LinkedHashMap<>();
    final Map<FlightKey, Flight> flights = new HashMap<>();
    final Set<CompletableFuture<TopologyWorkerRuntime.BaseGraphView>> captures = new HashSet<>();
    Hook hook = (point, key) -> {};
    SharedRows.QueryHook queryHook;
    boolean queryScratch = true;
    long jobs, joined, baseBuilds, rows, replacements, stale, cancelled, expired, errors;
    long retained, peakRetained, peakFlights, peakCaptures, rejected, conflicting;
    long capturesStarted, fastHits;
    boolean fastCapture = true, packedRows = true;
    boolean timingEnabled;
    int maxFlights = 1024, maxSlots = 64, maxCaptures = 1024;
    long maxBytes = 32L * 1024 * 1024;
    boolean closed;
    RuntimeSharedTopology(TopologyWorkerRuntime runtime) { this.runtime = runtime; }

    CompletableFuture<Result> request(ResourceKey<Level> dimension, SectionPos section,
                                      SharedRows.Query query, BooleanSupplier current, long expires) {
        return request(dimension, section, query, current, expires, null);
    }
    Ticket requestTimed(ResourceKey<Level> dimension, SectionPos section,
                        SharedRows.Query query, BooleanSupplier current, long expires) {
        if (!timingEnabled) throw new IllegalStateException("timing not enabled");
        var timing = new Timing();
        return new Ticket(request(dimension, section, query, current, expires, timing), timing);
    }
    private CompletableFuture<Result> request(ResourceKey<Level> dimension, SectionPos section,
                                      SharedRows.Query query, BooleanSupplier current, long expires, Timing timing) {
        var waiter = new Waiter(current, expires, timing);
        var key = new FlightKey(new TopologyWorkerRuntime.ClusterKey(dimension, section), query);
        if (fastCapture) {
            boolean reused = runtime.sharedTransition(() -> {
                if (closed || runtime.sharedStopping()) {
                    runtime.sharedComplete(waiter.result, null, new CancellationException("Runtime closing"));
                    return true;
                }
                Slot slot = slots.get(key.section());
                if (slot != null && !slot.lease.current()) {
                    retire(key.section(), slot);
                    slot = null;
                }
                if (slot == null) return false;
                fastHits++;
                admit(key, slot, waiter);
                return true;
            });
            if (reused) {
                waiter.result.whenComplete((value, failure) -> {
                    if (waiter.result.isCancelled()) cancelWaiter(key, waiter);
                });
                return waiter.result;
            }
        }
        var capture = new CompletableFuture<TopologyWorkerRuntime.BaseGraphView>();
        boolean admitted = runtime.sharedTransition(() -> {
            if (closed || runtime.sharedStopping()) {
                runtime.sharedComplete(waiter.result,null,new CancellationException("Runtime closing"));return false;
            }
            if (captures.size() >= maxCaptures) {
                rejected++;runtime.sharedComplete(waiter.result,null,new SharedSectionCandidate.AdmissionRejectedException("shared capture cap"));return false;
            }
            captures.add(capture); capturesStarted++;
            peakCaptures=Math.max(peakCaptures,captures.size());return true;
        });
        if (!admitted) return waiter.result;
        waiter.result.whenComplete((value, failure) -> {
            if (waiter.result.isCancelled()) {
                cancelWaiter(key, waiter);
                runtime.sharedAfter(() -> capture.cancel(false));
            }
        });
        capture.whenComplete((lease, failure) -> {
            try {
                runtime.sharedTransition(() -> {
                    captures.remove(capture);
                    if (failure != null) { runtime.sharedComplete(waiter.result, null, failure); return null; }
                    if (closed || runtime.sharedStopping() || waiter.result.isDone() || !lease.current()) {
                        lease.close(); runtime.sharedComplete(waiter.result, null, new CancellationException("capture obsolete"));
                        return null;
                    }
                    Slot slot = slots.get(key.section());
                    if (slot != null && !slot.lease.current()) {
                        retire(key.section(), slot); slot = null;
                    }
                    if (slot == null) {
                        if (slots.size() >= maxSlots) {
                            lease.close(); rejected++;
                            runtime.sharedComplete(waiter.result, null, new SharedSectionCandidate.AdmissionRejectedException("shared slot cap"));
                            return null;
                        }
                        slot = new Slot(lease); slots.put(key.section(), slot); launchBase(slot);
                    } else lease.close();
                    admit(key, slot, waiter);
                    return null;
                });
            } catch (Throwable problem) {
                if (lease != null) lease.close();
                waiter.result.completeExceptionally(problem);
            }
        });
        try {
            var source = runtime.requestBaseGraph(dimension, section, SharedRows.BASIS,
                    NavigationScheduler.Priority.ACTIVE);
            capture.whenComplete((lease,failure) -> {if(capture.isCancelled())source.cancel(false);});
            source.whenComplete((lease,failure) -> {
                if(failure!=null)capture.completeExceptionally(failure);
                else if(!capture.complete(lease))lease.close();
            });
        } catch(Throwable failure) { capture.completeExceptionally(failure); }
        return waiter.result;
    }
    private void cancelWaiter(FlightKey key, Waiter waiter) {
        runtime.sharedTransition(() -> {
            cancelled++;
            var flight = flights.get(key);
            if (flight != null) {
                flight.waiters.remove(waiter);
                if (flight.waiters.isEmpty() && flight.task != null)
                    runtime.sharedAfter(() -> flight.task.cancelWhenQueued(removed -> runtime.sharedTransition(() -> {
                        if (removed) flights.remove(key, flight);
                        return null;
                    })));
            }
            return null;
        });
    }
    private void admit(FlightKey key, Slot slot, Waiter waiter) {
        if (waiter.timing != null) waiter.timing.admitNs = System.nanoTime();
        var previous = flights.get(key);
        if (previous != null && previous.slot == slot) {
            if (waiter.timing != null) waiter.timing.joined = true;
            joined++; previous.waiters.add(waiter); return;
        }
        if (flights.size() >= maxFlights) {
            rejected++; runtime.sharedComplete(waiter.result, null, new SharedSectionCandidate.AdmissionRejectedException("shared flight cap"));
            return;
        }
        Flight flight = new Flight(key, slot); flight.waiters.add(waiter);
        if (timingEnabled) flight.timing = new JobTiming();
        flights.put(key, flight); peakFlights = Math.max(peakFlights, flights.size());
        runtime.sharedAfter(() -> slot.base.whenComplete((base, problem) -> {
            if (problem != null) finish(flight, null, problem);
            else runtime.sharedTransition(() -> {
                if (closed || flights.get(key) != flight || flight.waiters.isEmpty()) {
                    finish(flight, null, new CancellationException("upgrade unsubscribed")); return null;
                }
                runtime.submitSharedWork(() -> run(flight), handle -> {
                    flight.task = handle;
                    if (closed || flights.get(key) != flight || flight.waiters.isEmpty())
                        runtime.sharedAfter(() -> handle.cancelWhenQueued(removed -> {}));
                }, rejected -> finish(flight, null, rejected));
                return null;
            });
        }));
    }
    private void launchBase(Slot slot) {
        runtime.submitSharedWork(() -> {
                try {
                    hook.enter("before-base", null);
                    var snapshot = SharedRows.create(slot.lease.topology().boundaryFacts(), packedRows);
                    runtime.sharedTransition(() -> {
                        if (closed || runtime.sharedStopping() || !slot.lease.current()
                                || !slots.containsValue(slot)) {
                            failBase(slot, new CancellationException("shared base obsolete"));
                        } else if (retained + snapshot.retainedBytes > maxBytes) {
                            rejected++; failBase(slot, new SharedSectionCandidate.AdmissionRejectedException("shared byte cap"));
                        } else {
                            slot.snapshot = snapshot; retained += snapshot.retainedBytes;
                            peakRetained = Math.max(peakRetained, retained); baseBuilds++;
                            runtime.sharedComplete(slot.base, snapshot, null);
                        }
                        return null;
                    });
                } catch (Throwable failure) {
                    runtime.sharedTransition(() -> {
                        errors++; failBase(slot, failure); return null;
                    });
                }
            }, handle -> {}, failure -> failBase(slot, failure));
    }
    private void failBase(Slot slot, Throwable failure) {
        var key = slots.entrySet().stream().filter(entry -> entry.getValue() == slot)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        runtime.sharedComplete(slot.base, null, failure);
        if (key != null) {
            // Preserve the failure type after retiring the lease before deferred delivery.
            var stamps = slot.lease.stamps();
            for (Flight flight : List.copyOf(flights.values())) if (flight.slot == slot) {
                flights.remove(flight.key, flight);
                for (Waiter waiter : List.copyOf(flight.waiters)) runtime.sharedAfter(() -> {
                    if (waiter.result.isDone()) return;
                    try {
                        if (!ownerOpen.get() || !waiter.current.getAsBoolean()
                                || !stamps.stream().allMatch(TopologyWorkerRuntime.SectionStamp::current))
                            waiter.result.completeExceptionally(new CancellationException("shared base obsolete"));
                        else if (System.nanoTime() >= waiter.expires) {
                            runtime.sharedTransition(() -> { expired++; return null; });
                            waiter.result.completeExceptionally(new TimeoutException("shared request TTL"));
                        } else waiter.result.completeExceptionally(failure);
                    } catch (Throwable problem) { waiter.result.completeExceptionally(problem); }
                });
                flight.waiters.clear();
            }
            retire(key, slot);
        }
    }
    private void run(Flight flight) {
        try {
            if (flight.timing != null) {
                var task = runtime.sharedTaskTiming();
                if (task == null) throw new AssertionError("missing real task timing");
                var timing = flight.timing;
                timing.workerNs = System.nanoTime();
                timing.workerThread = Thread.currentThread().getId();
                timing.enqueuedNs = task.enqueuedNs(); timing.selectedNs = task.selectedNs();
                timing.taskSequence = task.sequence();
                timing.queueAtEnqueue = task.queueAtEnqueue(); timing.queueAtSelection = task.queueAtSelection();
            }
            SharedRows snapshot = runtime.sharedTransition(() -> flight.slot.snapshot);
            hook.enter("before-query", flight.key);
            if (flight.timing != null) flight.timing.queryBeginNs = System.nanoTime();
            var outcome = queryScratch
                    ? snapshot.query(flight.key.query(), runtime.sharedQueryScratch(), queryHook)
                    : snapshot.query(flight.key.query());
            if (flight.timing != null) flight.timing.queryEndNs = System.nanoTime();
            hook.enter("before-publish", flight.key);
            finish(flight, outcome, null);
        } catch (Throwable failure) { finish(flight, null, failure); }
    }
    private void finish(Flight flight, SharedRows.Outcome outcome, Throwable failure) {
        runtime.sharedTransition(() -> {
            if (flights.get(flight.key) != flight) return null;
            flights.remove(flight.key);
            boolean valid = !closed && !runtime.sharedStopping() && flight.slot.lease.current()
                    && slots.get(flight.key.section()) == flight.slot;
            if (!valid) stale++;
            if (outcome != null) jobs++;
            Throwable deliveredFailure = valid ? failure : null;
            if (valid && !flight.waiters.isEmpty() && failure == null && outcome != null) {
                rows += outcome.evaluations(); replacements += outcome.replacements();
                try {
                    var merged = flight.slot.snapshot.merge(flight.key.query().body(), outcome.change());
                    long next = retained - flight.slot.snapshot.retainedBytes + merged.retainedBytes;
                    if (next <= maxBytes) {
                        flight.slot.snapshot = merged; retained = next;
                        peakRetained = Math.max(peakRetained, retained);
                    } else rejected++;
                } catch (Throwable mergeFailure) { deliveredFailure = mergeFailure; conflicting++; }
            }
            if (deliveredFailure != null) errors++;
            var result = valid && deliveredFailure == null && outcome != null
                    ? new Result(outcome.candidate(), outcome.state(), outcome.cells(),
                            flight.slot.snapshot.version, flight.slot.lease.stamps(),
                            outcome.evaluations(), outcome.workNs(),ownerOpen) : null;
            Throwable finalFailure = deliveredFailure;
            if (flight.timing != null) flight.timing.publicationNs = System.nanoTime();
            for (Waiter waiter : List.copyOf(flight.waiters)) {
                if (waiter.result.isDone()) continue;
                if (waiter.timing != null) waiter.timing.copy(flight.timing);
                runtime.sharedAfter(() -> {
                    try {
                        hook.enter("before-delivery",flight.key);
                        if (waiter.timing != null) waiter.timing.deliveryNs = System.nanoTime();
                        if (!valid || !waiter.current.getAsBoolean()
                                || runtime.sharedTransition(() -> runtime.sharedStopping()
                                        || !flight.slot.lease.current()))
                            waiter.result.completeExceptionally(new CancellationException("old input or request"));
                        else if (System.nanoTime() >= waiter.expires) {
                            runtime.sharedTransition(() -> {expired++;return null;});
                            waiter.result.completeExceptionally(new TimeoutException("shared request TTL"));
                        } else if (finalFailure != null) waiter.result.completeExceptionally(finalFailure);
                        else waiter.result.complete(result);
                    } catch (Throwable problem) { waiter.result.completeExceptionally(problem); }
                });
            }
            flight.waiters.clear();
            return null;
        });
    }
    private void retire(TopologyWorkerRuntime.ClusterKey key, Slot slot) {
        if (!slots.remove(key, slot)) return;
        if (slot.snapshot != null) retained -= slot.snapshot.retainedBytes;
        for (Flight flight : List.copyOf(flights.values()))
            if (flight.slot == slot) finish(flight, null, new CancellationException("shared slot retired"));
        runtime.sharedComplete(slot.base, null, new CancellationException("shared slot retired"));
        slot.lease.close();
    }
    void close() {
        if (closed) return;
        closed = true;
        ownerOpen.set(false);
        for (var capture : List.copyOf(captures)) runtime.sharedAfter(() -> capture.cancel(false));
        captures.clear();
        for (Flight flight : List.copyOf(flights.values()))
            finish(flight, null, new CancellationException("Runtime closed"));
        for (var entry : List.copyOf(slots.entrySet())) {
            runtime.sharedComplete(entry.getValue().base, null, new CancellationException("Runtime closed"));
            retire(entry.getKey(), entry.getValue());
        }
    }
    Map<String, Long> metrics() {
        return runtime.sharedTransition(() -> Map.ofEntries(
                Map.entry("capturesStarted", capturesStarted), Map.entry("fastHits", fastHits),
                Map.entry("packedChunks", slots.values().stream().filter(s -> s.snapshot != null)
                        .mapToLong(s -> s.snapshot.base.rows().chunks()
                                + s.snapshot.layers.values().stream().mapToLong(l -> l.rows().chunks()).sum()).sum()),
                Map.entry("basisRowReferences", slots.values().stream().filter(s -> s.snapshot != null)
                        .mapToLong(s -> s.snapshot.layers.values().stream()
                                .mapToLong(l -> l.rows().basisReferences()).sum()).sum()),
                Map.entry("jobs", jobs), Map.entry("joined", joined), Map.entry("baseBuilds", baseBuilds),
                Map.entry("rows", rows), Map.entry("replacements", replacements), Map.entry("stale", stale),
                Map.entry("cancelled", cancelled), Map.entry("expired", expired), Map.entry("errors", errors),
                Map.entry("retained", retained), Map.entry("peakRetained", peakRetained),
                Map.entry("flights", (long) flights.size()), Map.entry("captures", (long) captures.size()),
                Map.entry("slots", (long) slots.size()), Map.entry("peakFlights", peakFlights),
                Map.entry("peakCaptures",peakCaptures),
                Map.entry("rejected", rejected), Map.entry("conflicting", conflicting)));
    }
}
