package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic data belongs to its actual request/flight; no global identity retention. */
public final class TopologySegmentTrace {
    public static final boolean ENABLED = "diagnostic".equals(System.getProperty("acceleratedNavigation.validation.profile"));
    private TopologySegmentTrace() { }
    /** Keep branches out of Mixin field initializer bytecode (Mixin 0.8.5 restriction). */
    public static Request newRequest() { return ENABLED ? new Request() : null; }
    public interface RequestAccess { Request segmentRequest(); }
    public interface FlightAccess { Flight segmentFlight(); }

    public static final class Flight {
        public final long created = System.nanoTime();
        public volatile long complete;
        private final Map<Thread, Long> taskStarts = new java.util.IdentityHashMap<>();
        private long taskNanos;
        private int taskCount, activeTasks;
        public synchronized void taskStart() { taskStarts.put(Thread.currentThread(), System.nanoTime()); taskCount++; activeTasks++; }
        public synchronized void taskEnd() {
            Long start = taskStarts.remove(Thread.currentThread());
            if (start == null) throw new IllegalStateException("Task exit without entry");
            taskNanos += System.nanoTime() - start; activeTasks--;
        }
        synchronized Map<String, Object> snapshot(long id) {
            return Map.of("flightId", id, "createdNanos", created, "completeEntryNanos", complete,
                    "taskCount", taskCount, "taskNanos", taskNanos, "activeTasks", activeTasks);
        }
    }

    public static final class Request {
        private final Map<Long, Flight> flights = new LinkedHashMap<>();
        private long submit, workerReady, queued, serverStart;
        private int queues, serverCalls;
        private boolean cleared;
        public synchronized void submit() { if (!cleared && submit == 0) submit = System.nanoTime(); }
        public synchronized void bind(long id, Flight flight) { if (!cleared) flights.putIfAbsent(id, flight); }
        public synchronized void workerReady() { if (!cleared) workerReady = System.nanoTime(); }
        public synchronized void queued() { if (!cleared) { queued = System.nanoTime(); queues++; } }
        public synchronized void serverStart() { if (!cleared) { serverStart = System.nanoTime(); serverCalls++; } }
        public synchronized void clear() { cleared = true; flights.clear(); }
        public synchronized boolean cleared() { return cleared; }
        public synchronized int retainedFlights() { return flights.size(); }

        public synchronized Map<String, Object> snapshot(long serviceId, long workerId, Request worker,
                                                         long start, Map<String, Object> completion) {
            var result = new LinkedHashMap<String, Object>();
            result.put("serviceRequestId", serviceId); result.put("workerRequestId", workerId);
            result.put("submitNanos", submit); result.put("serverQueueCallNanos", queued);
            result.put("serverStartNanos", serverStart); result.put("queueCalls", queues);
            result.put("serverCalls", serverCalls); result.put("cleared", cleared);
            List<Map<String, Object>> searches = new ArrayList<>();
            long ready = 0;
            if (worker != null) synchronized (worker) {
                ready = worker.workerReady;
                worker.flights.forEach((id, flight) -> searches.add(flight.snapshot(id)));
            }
            result.put("workerResultReadyNanos", ready); result.put("flights", searches);
            List<String> issues = new ArrayList<>();
            if (cleared || worker == null || worker.cleared()) issues.add("MISSING_OR_CLEARED_REQUEST");
            if (searches.size() != 1) issues.add("NOT_SINGLE_FLIGHT");
            if (queues != 1 || serverCalls != 1) issues.add("NOT_SINGLE_DELIVERY");
            if (!"BOUNDED".equals(completion.get("status"))) issues.add("UNPROVEN_COMPLETION");
            if (!"complete".equals(completion.get("writer"))) issues.add("NON_NORMAL_COMPLETION");
            long created = searches.size() == 1 ? ((Number) searches.get(0).get("createdNanos")).longValue() : 0;
            long complete = searches.size() == 1 ? ((Number) searches.get(0).get("completeEntryNanos")).longValue() : 0;
            long call = completion.get("callBeforeNanos") instanceof Number n ? n.longValue() : 0;
            long[] anchors = {start, submit, created, complete, ready, queued, serverStart, call};
            for (int i = 0; i < anchors.length; i++) {
                if (anchors[i] == 0 || i > 0 && anchors[i] < anchors[i - 1]) { issues.add("MISSING_OR_UNORDERED_ANCHORS"); break; }
            }
            if (searches.stream().anyMatch(f -> ((Number) f.get("activeTasks")).intValue() != 0)) issues.add("TASK_OBSERVATION_ACTIVE");
            String[] names = {"entryToSubmitNanos", "submitToFlightNanos", "flightLifetimeNanos",
                    "flightToWorkerResultNanos", "workerResultToQueueCallNanos", "queueCallToServerStartNanos", "serverStartToCompletionCallNanos"};
            var durations = new LinkedHashMap<String, Object>();
            for (int i = 0; i < names.length; i++) durations.put(names[i], issues.isEmpty() ? anchors[i + 1] - anchors[i] : null);
            result.put("status", issues.isEmpty() ? "COMPLETE" : "UNAVAILABLE");
            result.put("issues", issues); result.put("durations", durations);
            result.put("meaning", "Diagnostic elapsed segments include waits/probes; task sum overlaps flight lifetime; no subtraction");
            return result;
        }
    }

    public static Map<String, Object> snapshot(Object service, long start, Map<String, Object> completion) {
        if (!ENABLED) return Map.of("status", "DISABLED");
        if (!(service instanceof RequestAccess access)) return Map.of("status", "UNAVAILABLE", "issues", List.of("NO_SERVICE_TRACE"));
        Object worker = TopologyTestBridge.readField(service, "workerRequest");
        return access.segmentRequest().snapshot(TopologyValidationAccess.token(service), TopologyValidationAccess.token(worker),
                worker instanceof RequestAccess workerAccess ? workerAccess.segmentRequest() : null, start, completion);
    }

    public static void clear(Object service) {
        if (!ENABLED) return;
        if (service instanceof RequestAccess access) access.segmentRequest().clear();
        Object worker = TopologyTestBridge.readField(service, "workerRequest");
        if (worker instanceof RequestAccess access) access.segmentRequest().clear();
    }

    public static Map<String, Object> cleanupEvidence(Object service) {
        if (!(service instanceof RequestAccess access)) return Map.of("cleared", false);
        Object worker = TopologyTestBridge.readField(service, "workerRequest");
        Request serverTrace = access.segmentRequest();
        Request workerTrace = worker instanceof RequestAccess other ? other.segmentRequest() : null;
        return Map.of("serviceRequestId", TopologyValidationAccess.token(service),
                "workerRequestId", TopologyValidationAccess.token(worker),
                "cleared", serverTrace.cleared() && (workerTrace == null || workerTrace.cleared()),
                "retainedFlights", serverTrace.retainedFlights() + (workerTrace == null ? 0 : workerTrace.retainedFlights()));
    }

    public static Map<String, Long> clockAnchor() {
        long before = System.nanoTime(), millis = System.currentTimeMillis(), after = System.nanoTime();
        return Map.of("nanoBefore", before, "epochMillis", millis, "nanoAfter", after, "readSpanNanos", after - before);
    }

    /** Fixed-size tick boundary history. Adjacent entries bracket whole ticks, including idle time. */
    public static final class TickTimeline {
        private final long[] nanos = new long[2048];
        private final int[] ticks = new int[2048];
        private long count;
        public synchronized void clear() { count = 0; }
        public synchronized void record(long time, int tick) {
            int index = (int) (count++ % nanos.length); nanos[index] = time; ticks[index] = tick;
        }
        public synchronized Map<String, Object> window(long from, long to) {
            List<Map<String, Object>> intervals = new ArrayList<>();
            boolean left = false, right = false, continuous = true;
            long max = 0;
            for (long i = Math.max(1, count - nanos.length + 1); i < count; i++) {
                int a = (int) ((i - 1) % nanos.length), b = (int) (i % nanos.length);
                if (nanos[a] > to || nanos[b] < from) continue;
                long elapsed = nanos[b] - nanos[a]; int delta = ticks[b] - ticks[a];
                left |= nanos[a] <= from && nanos[b] >= from;
                right |= nanos[a] <= to && nanos[b] >= to;
                continuous &= delta == 1 && elapsed >= 0;
                max = Math.max(max, elapsed);
                intervals.add(Map.of("fromNanos", nanos[a], "toNanos", nanos[b], "elapsedNanos", elapsed,
                        "fromTick", ticks[a], "toTick", ticks[b], "tickDelta", delta));
            }
            boolean covered = from <= to && left && right && continuous;
            return Map.of("status", covered ? "COVERED" : "INCOMPLETE", "intervals", intervals,
                    "maxIntervalNanos", max, "nominalTickNanos", 50_000_000L,
                    "maxExcessOverNominalNanos", Math.max(0, max - 50_000_000L),
                    "meaning", "Whole tick intervals overlapping window include outside time; not an acceptance threshold");
        }
    }
}
