package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Operation-bound, nonblocking control of existing handoff calls. No fabricated completions. */
public final class TopologyHandoffProbe implements AutoCloseable {
    private static volatile TopologyHandoffProbe active;
    private final TopologyWorkerRuntime.ClusterKey key;
    private final long loadIdentity, version;
    private final String barrier;
    private Object runtime;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Set<String> recorded = new HashSet<>();
    private final List<Runnable> completions = new ArrayList<>();
    private Runnable continuation, queuedAction;
    private TopologyWorkerRuntime.FactDecision pausedDecision;
    private boolean released, closed;
    private long arrived;

    private TopologyHandoffProbe(ResourceKey<Level> dimension, SectionPos section, long load, long version, String barrier) {
        key = new TopologyWorkerRuntime.ClusterKey(dimension, section);
        loadIdentity = load; this.version = version; this.barrier = barrier;
    }

    public static synchronized TopologyHandoffProbe install(ResourceKey<Level> dimension, SectionPos section,
                                                            long load, long version, String barrier) {
        if (active != null) throw new IllegalStateException("overlapping handoff controls");
        if (barrier != null && !Set.of("B1", "B2", "B3", "B4", "B5", "B6", "B7").contains(barrier)) {
            throw new IllegalArgumentException("unknown handoff barrier");
        }
        active = new TopologyHandoffProbe(dimension, section, load, version, barrier);
        return active;
    }

    private boolean matches(TopologyWorkerRuntime.FactDecision decision) {
        return !closed && key.equals(decision.key()) && decision.loadIdentity() == loadIdentity && decision.version() >= version;
    }

    public synchronized void observeRuntime(Object service) { runtime = readField(service, "runtime"); }

    static boolean pause(String point, TopologyWorkerRuntime.FactDecision decision, Runnable resume, Object facts) {
        TopologyHandoffProbe probe = active;
        if (probe == null) return false;
        synchronized (probe) {
            if (!probe.matches(decision)) return false;
            String id = point + "/" + decision.generation();
            if (probe.recorded.add(id)) probe.record(point, decision, facts, false);
            if (!point.equals(probe.barrier) || probe.released || decision.version() != probe.version) return false;
            if (probe.continuation != null) {
                if (probe.pausedDecision != decision) throw new IllegalStateException("barrier captured two operations");
                return true;
            }
            probe.continuation = resume; probe.pausedDecision = decision; probe.arrived = System.nanoTime();
            return true;
        }
    }

    private void record(String point, TopologyWorkerRuntime.FactDecision decision, Object facts, boolean release) {
        if (events.size() >= 256) throw new IllegalStateException("handoff observation budget exceeded");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("barrier", point); row.put("released", release); row.put("nanoTime", System.nanoTime());
        row.put("dimension", decision.key().dimension().location().toString()); row.put("section", decision.key().section().toString());
        row.put("loadIdentity", decision.loadIdentity()); row.put("generation", decision.generation());
        row.put("previousVersion", decision.previousVersion()); row.put("version", decision.version());
        row.put("decisionToken", TopologyValidationAccess.token(decision)); row.put("factsToken", TopologyValidationAccess.token(facts));
        row.put("changes", decision.changes().size());
        if (runtime != null && point.equals("B6")) synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(decision.key());
            if (entry != null) {
                row.put("publishedFactsToken", TopologyValidationAccess.token(readField(entry, "facts")));
                row.put("publishedVersion", readField(entry, "revision"));
                row.put("writeFactsPinned", ((Map<?, ?>) readField(entry, "pinnedFacts")).containsKey(facts));
            }
        }
        events.add(Map.copyOf(row));
    }

    static Runnable queued(Object service, Runnable action, TopologyWorkerRuntime.FactDecision decision) {
        TopologyHandoffProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (probe.matches(decision) && decision.version() == probe.version && "B2".equals(probe.barrier)) {
                probe.queuedAction = action; probe.pausedDecision = decision;
            }
        }
        return action;
    }

    static boolean beforeDrain(Object service) {
        TopologyHandoffProbe probe = active;
        if (probe == null) return false;
        synchronized (probe) {
            if (probe.closed || probe.released || probe.queuedAction == null) return false;
            var queue = (ConcurrentLinkedQueue<?>) readField(service, "persistenceCallbacks");
            if (!queue.contains(probe.queuedAction)) return false;
            return pause("B2", probe.pausedDecision, () -> invoke(service, "drainPersistence"), probe.pausedDecision.facts());
        }
    }

    static BiConsumer<Object, Throwable> formation(TopologyWorkerRuntime.FactDecision decision, BiConsumer<Object, Throwable> callback) {
        return (result, failure) -> {
            Runnable deliver = () -> callback.accept(result, failure);
            if (!(result instanceof TopologyStore.Formation formed) || formed.facts() == null
                    || !pause("B5", decision, deliver, formed.facts())) deliver.run();
        };
    }

    static void accepted(TopologyStore.WriteReceipt receipt) {
        var probe = active;
        if (probe == null) return;
        var decision = (TopologyWorkerRuntime.FactDecision) readField(receipt, "decision");
        synchronized (probe) { if (!probe.matches(decision)) return; }
        receipt.completed.whenComplete((result, failure) -> {
            synchronized (probe) {
                if (probe.closed) return;
                if (probe.events.size() >= 256) throw new IllegalStateException("handoff observation budget exceeded");
                probe.events.add(Map.of("event", "store-terminal", "generation", decision.generation(),
                        "loadIdentity", decision.loadIdentity(), "version", decision.version(),
                        "status", failure != null ? "EXCEPTION" : result.status().name(), "nanoTime", System.nanoTime()));
            }
        });
    }

    static boolean completion(Object service, TopologyWorkerRuntime.FactDecision decision, boolean formed, boolean written) {
        Runnable resume = () -> invoke(service, "completePersistence", decision, formed, written);
        TopologyHandoffProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (probe.matches(decision) && probe.recorded.add("completion/" + decision.generation())) {
                probe.completions.add(resume);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event", "write-terminal"); row.put("generation", decision.generation()); row.put("version", decision.version());
                row.put("nanoTime", System.nanoTime());
                row.put("formed", formed); row.put("written", written); probe.events.add(Map.copyOf(row));
            }
        }
        return pause("B7", decision, resume, decision.facts());
    }

    static void resumeFormation(Object store, Object receipt) {
        synchronized (readField(store, "monitor")) {
            @SuppressWarnings("unchecked") ArrayDeque<Object> queue = (ArrayDeque<Object>) readField(store, "foreground");
            invoke(store, "enqueueLocked", queue, (Runnable) () -> invoke(store, "formDelta", receipt));
        }
    }

    public synchronized boolean reached() { return continuation != null || released; }
    public synchronized boolean timedOut() { return !released && arrived != 0 && System.nanoTime() - arrived >= 30_000_000_000L; }
    public synchronized List<Map<String, Object>> events() { return List.copyOf(events); }
    public synchronized int completionCount() { return completions.size(); }

    public void replayCompletion(int index) {
        Runnable message;
        synchronized (this) { message = completions.get(index); }
        message.run();
    }

    public void release() {
        Runnable resume;
        synchronized (this) {
            if (released) return;
            if (continuation == null) throw new IllegalStateException("release before barrier arrival");
            released = true; resume = continuation; continuation = null; queuedAction = null;
            record(barrier, pausedDecision, pausedDecision.facts(), true);
        }
        resume.run();
    }

    @Override public void close() {
        boolean resume;
        synchronized (this) { resume = continuation != null && !released; }
        try { if (resume) release(); }
        finally {
            synchronized (this) { closed = true; queuedAction = null; completions.clear(); }
            synchronized (TopologyHandoffProbe.class) { if (active == this) active = null; }
        }
    }
}
