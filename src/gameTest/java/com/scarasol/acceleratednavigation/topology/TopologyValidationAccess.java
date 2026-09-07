package com.scarasol.acceleratednavigation.topology;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Test-only owner operations and identity observations. Snapshots never pin derived graphs. */
public final class TopologyValidationAccess {
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static final Map<WeakIdentity, Long> TOKENS = new HashMap<>();
    private static long sequence;
    private static volatile Capture activeCapture;
    private static final Map<Long, List<Map<String, Long>>> REQUEST_SEARCHES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> EVICT_IDLE = ThreadLocal.withInitial(() -> false);

    private TopologyValidationAccess() { }

    public record Stamp(long token, long load, long version, long validity) { }
    public record Snapshot(Map<String, Stamp> objects, List<String> unavailableFacts,
                           boolean idle, boolean handoffsReleased, long topologyTick) { }

    public static Snapshot snapshot(Object service, ResourceKey<Level> dimension) {
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) { return runtimeSnapshot(runtime, dimension); }
    }

    static Snapshot runtimeSnapshot(Object runtime, ResourceKey<Level> dimension) {
        Map<String, Stamp> objects = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (var item : map(runtime, "clusters").entrySet()) {
            var key = (TopologyWorkerRuntime.ClusterKey) item.getKey();
            if (!key.dimension().equals(dimension)) continue;
            Object entry = item.getValue();
            Object facts = readField(entry, "facts");
            if (!Boolean.TRUE.equals(invoke(entry, "current")) || facts == null) missing.add(key.toString());
            else objects.put(factKey(key), factStamp(entry));
            for (Object view : map(entry, "views").values()) {
                Object topology = readField(view, "topology");
                if (topology != null && Boolean.TRUE.equals(invoke(entry, "current"))
                        && readField(view, "topologyValidity").equals(readField(view, "validity"))) {
                    objects.put(baseKey(key, view), topologyStamp(view));
                    collectLinks(objects, view, "base-boundary/" + baseKey(key, view));
                }
            }
        }
        for (var item : map(runtime, "superClusters").entrySet()) {
            Object entry = item.getValue();
            if (!readField(item.getKey(), "dimension").equals(dimension)) continue;
            if (Boolean.TRUE.equals(invoke(runtime, "superEntryCurrent", entry))) {
                objects.put(parentKey(item.getKey()), topologyStamp(entry));
                collectLinks(objects, entry, "parent-boundary/" + parentKey(item.getKey()));
            }
        }
        boolean idle = ((Collection<?>) readField(runtime, "macroRequests")).isEmpty()
                && map(runtime, "resolveFlights").isEmpty() && map(runtime, "macroFlights").isEmpty()
                && (int) readField(runtime, "buildDemands") == 0 && (int) readField(runtime, "dependencyConsumers") == 0
                && (int) readField(runtime, "prewarmAdmitted") == 0 && !(boolean) readField(runtime, "eventBatchActive")
                && map(runtime, "pendingSectionEvents").isEmpty() && map(runtime, "processingSectionEvents").isEmpty()
                && map(runtime, "pendingRequestEvents").isEmpty() && map(runtime, "processingRequestEvents").isEmpty()
                && map(runtime, "pendingChunkUnloads").isEmpty() && map(runtime, "persistenceHolds").isEmpty();
        boolean handoffs = ((Collection<?>) readField(runtime, "baseHandoffs")).isEmpty()
                && ((Collection<?>) readField(runtime, "superHandoffs")).isEmpty();
        return new Snapshot(Map.copyOf(objects), List.copyOf(missing), idle, handoffs, (long) readField(runtime, "topologyTick"));
    }

    private static void collectLinks(Map<String, Stamp> objects, Object owner, String prefix) {
        Object[] links = (Object[]) readField(owner, "links");
        for (int slot = 0; slot < links.length; slot++) {
            Object link = links[slot];
            if (link != null && readField(link, "value") != null && !(boolean) readField(link, "retired")) {
                objects.put(prefix + "/" + slot, new Stamp(token(readField(link, "value")), 0, 0, 0));
            }
        }
    }

    public static List<String> validateExpected(Snapshot snapshot, Map<String, Stamp> expected) {
        List<String> issues = new ArrayList<>();
        if (!snapshot.idle) issues.add("TARGET_WORK_PENDING");
        for (var entry : expected.entrySet()) if (!entry.getValue().equals(snapshot.objects.get(entry.getKey()))) issues.add(entry.getKey());
        return List.copyOf(issues);
    }

    public static boolean clearDerived(Object service, ResourceKey<Level> dimension) {
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            Snapshot before = runtimeSnapshot(runtime, dimension);
            if (!before.idle || !before.handoffsReleased) return false;
            for (var item : List.copyOf(map(runtime, "superClusters").entrySet())) {
                if (!readField(item.getKey(), "dimension").equals(dimension)) continue;
                Object entry = item.getValue();
                if (readField(entry, "topology") != null && !Boolean.TRUE.equals(invoke(runtime, "superIdle", entry))) return false;
            }
            for (var item : map(runtime, "clusters").entrySet()) {
                var key = (TopologyWorkerRuntime.ClusterKey) item.getKey();
                if (!key.dimension().equals(dimension)) continue;
                for (Object view : map(item.getValue(), "views").values()) {
                    if (readField(view, "topology") != null && !Boolean.TRUE.equals(invoke(runtime, "baseIdle", view))) return false;
                }
            }
            for (var item : List.copyOf(map(runtime, "superClusters").entrySet())) {
                if (!readField(item.getKey(), "dimension").equals(dimension)) continue;
                invoke(runtime, "removeIdleSuper", item.getValue());
                invoke(runtime, "removeSuperTopology", item.getKey(), item.getValue());
                invoke(runtime, "removeSuperEntry", item.getKey(), item.getValue());
            }
            for (var item : List.copyOf(map(runtime, "clusters").entrySet())) {
                var key = (TopologyWorkerRuntime.ClusterKey) item.getKey();
                if (!key.dimension().equals(dimension)) continue;
                for (Object view : List.copyOf(map(item.getValue(), "views").values())) {
                    Object topology = readField(view, "topology");
                    if (topology == null) continue;
                    invoke(runtime, "invalidateBaseBoundaryLinks", key, topology);
                    invoke(runtime, "removeIdleBase", view);
                    invoke(runtime, "retireBaseTopology", view);
                    writeField(view, "topology", null);
                    invoke(runtime, "pruneView", view);
                }
            }
            clearCompletedCorridors(runtime);
            return runtimeSnapshot(runtime, dimension).objects.keySet().stream().allMatch(key -> key.startsWith("facts/"));
        }
    }

    public static void clearCompletedCorridors(Object runtime) {
        synchronized (readField(runtime, "runtimeLock")) {
            map(runtime, "completedCorridors").clear();
            writeField(runtime, "completedCorridorBytes", 0L);
        }
    }

    public static final class FactsLease implements AutoCloseable {
        private final Object runtime;
        private final Map<Object, BaseClusterTopology.PackedFacts> pinned = new IdentityHashMap<>();
        private final Set<TopologyWorkerRuntime.ClusterKey> keys = new HashSet<>();
        private boolean closed;
        private FactsLease(Object runtime) { this.runtime = runtime; }
        public long bytes() {
            Set<BaseClusterTopology.PackedFacts> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            unique.addAll(pinned.values());
            return unique.stream().mapToLong(BaseClusterTopology.PackedFacts::retainedBytes).sum();
        }
        public int sections() { return pinned.size(); }
        @Override public void close() {
            synchronized (readField(runtime, "runtimeLock")) {
                if (closed) return;
                closed = true;
                for (var entry : pinned.entrySet()) {
                    invoke(runtime, "unpinFacts", entry.getKey(), entry.getValue());
                    invoke(runtime, "releaseUnusedFacts", entry.getKey());
                }
                pinned.clear(); keys.clear(); invoke(runtime, "evictBaseCache");
            }
        }
    }

    /** Called only outside timed requests; the existing server read/recovery path supplies missing facts. */
    public static FactsLease factsLease(Object service) { return new FactsLease(readField(service, "runtime")); }

    public static List<String> prepareFacts(Object service, ResourceKey<Level> dimension, FactsLease lease,
                                            Set<SectionPos> sections) {
        if (lease.closed) throw new IllegalStateException("closed facts lease");
        List<Object> reads = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        synchronized (readField(lease.runtime, "runtimeLock")) {
            for (SectionPos section : sections) {
                var key = new TopologyWorkerRuntime.ClusterKey(dimension, section);
                Object entry = map(lease.runtime, "clusters").get(key);
                if (entry == null) { missing.add("UNLOADED:" + key); continue; }
                Object current = readField(entry, "facts");
                if (!Boolean.TRUE.equals(invoke(entry, "current")) || current == null) {
                    missing.add("PENDING:" + key);
                    Object loaded = map(service, "loadedSections").get(key);
                    if (loaded != null) reads.add(loaded);
                    continue;
                }
                if (lease.keys.add(key)) {
                    BaseClusterTopology.PackedFacts facts = (BaseClusterTopology.PackedFacts) current;
                    invoke(lease.runtime, "removeIdleFact", entry);
                    invoke(lease.runtime, "pinFacts", entry, facts);
                    lease.pinned.put(entry, facts);
                } else if (lease.pinned.get(entry) != current) missing.add("FACTS_CHANGED:" + key);
            }
        }
        for (Object loaded : reads) invoke(service, "readPersisted", loaded);
        return List.copyOf(missing);
    }

    public static Set<SectionPos> loadedSections(Object service, ResourceKey<Level> dimension, Set<Long> chunks) {
        Set<SectionPos> result = new HashSet<>();
        for (Object key : map(service, "loadedSections").keySet()) {
            var section = (TopologyWorkerRuntime.ClusterKey) key;
            if (section.dimension().equals(dimension) && chunks.contains(section.section().chunk().toLong())) result.add(section.section());
        }
        return Set.copyOf(result);
    }

    public static final class Capture {
        private final Map<ObservationKey, Stamp> used = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.LongAdder directNanos = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder calls = new java.util.concurrent.atomic.LongAdder();
        public Map<String, Stamp> used() {
            Map<String, Stamp> result = new LinkedHashMap<>();
            used.forEach((key, stamp) -> result.put(key.description(), stamp));
            return Map.copyOf(result);
        }
        public Map<String, Long> cost() { return Map.of("directWallNanos", directNanos.sum(), "calls", calls.sum()); }
        private void observed(long started) { directNanos.add(System.nanoTime() - started); calls.increment(); }
    }

    // Keep immutable keys and scalar stamps; render report strings after the request ends.
    private record ObservationKey(String kind, Object key, Object geometry, int slot) {
        String description() {
            String owner = kind + "/" + key + (kind.equals("base") ? "/" + geometry : "");
            return slot < 0 ? owner : kind + "-boundary/" + owner + "/" + slot;
        }
    }

    public static Capture beginCapture() {
        if (activeCapture != null) throw new IllegalStateException("overlapping timed capture");
        Capture capture = new Capture(); activeCapture = capture; return capture;
    }

    public static void endCapture(Capture capture) {
        if (activeCapture != capture) throw new IllegalStateException("capture identity mismatch");
        activeCapture = null;
    }

    public static void graphClosing(Object graph) {
        Capture capture = activeCapture;
        if (capture == null || (boolean) readField(graph, "closed")) return;
        long observedAt = System.nanoTime();
        try {
        Object runtime = readField(graph, "this$0");
        if (!Thread.holdsLock(readField(runtime, "runtimeLock"))) throw new IllegalStateException("graph release outside owner boundary");
        for (Object entry : map(graph, "topologySnapshot").values()) {
            Object owner = readField(entry, "owner"), key = readField(entry, "key");
            boolean base = key instanceof TopologyWorkerRuntime.ClusterKey;
            Object topology = readField(entry, "topology");
            capture.used.put(new ObservationKey(base ? "base" : "parent", key, base ? readField(owner, "geometry") : null, -1),
                    new Stamp(token(topology), 0, topology instanceof BaseClusterTopology value ? value.revision() : 0, (long) readField(entry, "validity")));
            for (Object stamp : (List<?>) readField(entry, "stamps")) {
                var sectionKey = (TopologyWorkerRuntime.ClusterKey) readField(stamp, "key");
                Object facts = map(runtime, "clusters").get(sectionKey);
                if (facts != null) capture.used.put(new ObservationKey("facts", sectionKey, null, -1), new Stamp(token(readField(facts, "facts")),
                        (long) invoke(stamp, "loadIdentity"), (long) invoke(stamp, "version"), 0));
            }
        }
        for (var boundary : map(graph, "boundarySnapshot").entrySet()) {
            Object key = boundary.getKey(), source = readField(key, "source"), target = readField(key, "target");
            boolean base = source instanceof BaseClusterTopology;
            SectionPos origin = base ? ((BaseClusterTopology) source).section() : ((SuperClusterTopology) source).origin();
            SectionPos destination = base ? ((BaseClusterTopology) target).section() : ((SuperClusterTopology) target).origin();
            Object capturedSource = map(graph, "topologySnapshot").get(origin.asLong());
            Object owner = readField(capturedSource, "owner");
            Object sourceKey = readField(capturedSource, "key");
            int slot = (int) invoke(runtime, base ? "baseLinkSlot" : "superLinkSlot", origin, destination, readField(key, "face"));
            Object value = readField(boundary.getValue(), "value");
            capture.used.put(new ObservationKey(base ? "base" : "parent", sourceKey, base ? readField(owner, "geometry") : null, slot),
                    new Stamp(token(value), 0, 0, 0));
        }
        } finally { capture.observed(observedAt); }
    }

    public static void endpointInputs(Object flight) {
        Capture capture = activeCapture;
        if (capture == null) return;
        long observedAt = System.nanoTime();
        try {
        for (Object entry : (List<?>) readField(flight, "held")) {
            Object view = readField(entry, "owner"); Object cluster = readField(view, "owner");
            Object key = readField(cluster, "key");
            capture.used.put(new ObservationKey("base", key, readField(view, "geometry"), -1), topologyStamp(view));
            capture.used.put(new ObservationKey("facts", key, null, -1), factStamp(cluster));
        }
        } finally { capture.observed(observedAt); }
    }

    public static void bindFlight(Object flight, Object request) {
        Capture capture = activeCapture;
        long observedAt = capture == null ? 0 : System.nanoTime();
        try {
        long requestId = token(request), flightId = token(flight), attempt = (long) readField(request, "attempt");
        REQUEST_SEARCHES.compute(requestId, (key, previous) -> {
            List<Map<String, Long>> records = previous == null ? new ArrayList<>() : new ArrayList<>(previous);
            Map<String, Long> binding = Map.of("attempt", attempt, "physicalSearchId", flightId);
            if (!records.contains(binding)) records.add(binding);
            return List.copyOf(records);
        });
        } finally { if (capture != null) capture.observed(observedAt); }
    }

    public static Map<String, Object> requestIdentity(Object serverRequest) {
        Object worker = readField(serverRequest, "workerRequest");
        if (worker == null) return Map.of("attempt", 0L, "physicalSearchIds", List.of());
        return Map.of("attempt", readField(worker, "attempt"), "physicalSearchIds", REQUEST_SEARCHES.getOrDefault(token(worker), List.of()));
    }

    public static void forgetRequest(Object serverRequest) {
        Object worker = readField(serverRequest, "workerRequest");
        if (worker != null) REQUEST_SEARCHES.remove(token(worker));
    }

    public static Object fixedMetrics(Object service) { return invoke(service, "metrics"); }

    public static long evictionLimit(long configured) { return EVICT_IDLE.get() ? 0L : configured; }

    /** Exercise the actual eviction owner with a test-scoped limit, including its accounting and terminal transitions. */
    public static void evictIdle(Object service) {
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            if (EVICT_IDLE.get()) throw new IllegalStateException("nested forced eviction");
            EVICT_IDLE.set(true);
            try { invoke(runtime, "evictBaseCache"); }
            finally { EVICT_IDLE.remove(); }
        }
    }

    /** Observe both owners separately; never acquire the I/O monitor under the runtime lock. */
    public static Map<String, Object> chunkSettlement(Object service, ResourceKey<Level> dimension, Set<Long> chunks) {
        Object runtime = readField(service, "runtime");
        Map<String, Object> result = new LinkedHashMap<>();
        synchronized (readField(runtime, "runtimeLock")) {
            long events = 0;
            for (String field : List.of("pendingSectionEvents", "processingSectionEvents", "pendingChunkUnloads")) {
                events += map(runtime, field).keySet().stream().filter(key -> matchesChunk(key, dimension, chunks)).count();
            }
            long holds = map(runtime, "persistenceHolds").keySet().stream()
                    .filter(decision -> matchesChunk(readField(decision, "key"), dimension, chunks)).count();
            long references = 0, tails = 0;
            for (var item : map(runtime, "clusters").entrySet()) {
                if (!matchesChunk(item.getKey(), dimension, chunks)) continue;
                Object entry = item.getValue();
                references += map(entry, "pinnedFacts").values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
                if (readField(entry, "tail") != null || readField(entry, "decision") != null) tails++;
            }
            result.put("events", events); result.put("holds", holds);
            result.put("factPins", references); result.put("tailsOrDecisions", tails);
            result.put("eventBatchActive", readField(runtime, "eventBatchActive"));
        }
        Object store = readField(service, "store");
        long receipts = 0, pending = 0, loads = 0;
        if (store != null) synchronized (readField(store, "monitor")) {
            receipts = ((Collection<?>) readField(store, "receipts")).stream()
                    .filter(receipt -> matchesChunk(readField(readField(receipt, "decision"), "key"), dimension, chunks)).count();
            pending = map(store, "pending").keySet().stream().filter(key -> matchesChunk(key, dimension, chunks)).count();
            loads = map(store, "loads").keySet().stream().filter(key -> matchesChunk(key, dimension, chunks)).count();
        }
        result.put("receipts", receipts); result.put("pendingWrites", pending); result.put("loads", loads);
        boolean settled = !Boolean.TRUE.equals(result.get("eventBatchActive"))
                && result.values().stream().filter(Number.class::isInstance).allMatch(value -> ((Number) value).longValue() == 0);
        result.put("settled", settled);
        return Map.copyOf(result);
    }

    private static boolean matchesChunk(Object key, ResourceKey<Level> dimension, Set<Long> chunks) {
        if (!readField(key, "dimension").equals(dimension)) return false;
        if (key instanceof TopologyWorkerRuntime.ClusterKey cluster) return chunks.contains(cluster.section().chunk().toLong());
        Object position = readField(key, key.getClass().getEnclosingClass() == TopologyWorkerRuntime.class ? "chunkLong" : "chunk");
        return chunks.contains(position instanceof ChunkPos chunk ? chunk.toLong() : ((Number) position).longValue());
    }

    public static synchronized long token(Object object) {
        if (object == null) return 0;
        WeakIdentity dead;
        while ((dead = (WeakIdentity) COLLECTED.poll()) != null) TOKENS.remove(dead);
        WeakIdentity key = new WeakIdentity(object);
        return TOKENS.computeIfAbsent(key, ignored -> ++sequence);
    }

    private static final class WeakIdentity extends WeakReference<Object> {
        private final int hash;
        WeakIdentity(Object object) { super(object, COLLECTED); hash = System.identityHashCode(object); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { return this == other || other instanceof WeakIdentity weak && get() != null && get() == weak.get(); }
    }

    private static Stamp factStamp(Object entry) { return new Stamp(token(readField(entry, "facts")), (long) readField(entry, "loadIdentity"), (long) readField(entry, "revision"), 0); }
    private static Stamp topologyStamp(Object owner) {
        Object topology = readField(owner, "topology");
        return new Stamp(token(topology), 0, topology instanceof BaseClusterTopology base ? base.revision() : 0, (long) readField(owner, "validity"));
    }
    private static String factKey(Object key) { return "facts/" + key; }
    private static String baseKey(Object key, Object view) { return "base/" + key + "/" + readField(view, "geometry"); }
    private static String parentKey(Object key) { return "parent/" + key; }
    private static Map<?, ?> map(Object target, String field) { return (Map<?, ?>) readField(target, field); }
}
