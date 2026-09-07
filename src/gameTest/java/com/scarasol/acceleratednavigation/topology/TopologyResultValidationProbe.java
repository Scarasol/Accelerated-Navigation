package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Holds at most two real final-validation messages, including the single allowed retry. */
public final class TopologyResultValidationProbe implements AutoCloseable {
    private static volatile TopologyResultValidationProbe active;
    private final ResourceKey<Level> dimension;
    private final BlockPos start, goal;
    private final int limit;
    private final List<Delivery> deliveries = new ArrayList<>();
    private Delivery pending;
    private boolean delivering, closed;

    private record Delivery(Object request, TopologyWorkerRuntime.WorkerResult result, Throwable failure) { }

    private TopologyResultValidationProbe(ResourceKey<Level> dimension, BlockPos start, BlockPos goal, int limit) {
        this.dimension = dimension; this.start = start.immutable(); this.goal = goal.immutable(); this.limit = limit;
    }
    public static synchronized TopologyResultValidationProbe watch(ResourceKey<Level> dimension, BlockPos start, BlockPos goal, int limit) {
        if (active != null) throw new IllegalStateException("overlapping final-validation controls");
        if (limit < 1 || limit > 2) throw new IllegalArgumentException("at most the initial attempt and one retry");
        active = new TopologyResultValidationProbe(dimension, start, goal, limit);
        return active;
    }
    public static boolean pause(Object request, Object value, Throwable failure) {
        var probe = active;
        if (probe == null || !(value instanceof TopologyWorkerRuntime.WorkerResult result) || result.corridor() == null) return false;
        synchronized (probe) {
            if (probe.closed || probe.delivering || !probe.start.equals(readField(request, "start"))
                    || !probe.goal.equals(readField(request, "goal"))
                    || !probe.dimension.equals(readField(readField(request, "key"), "dimension"))) return false;
            if (probe.pending != null) throw new IllegalStateException("two final-validation messages arrived without release");
            if (probe.deliveries.size() >= probe.limit) return false;
            probe.pending = new Delivery(request, result, failure);
            probe.deliveries.add(probe.pending);
            return true;
        }
    }
    public synchronized boolean reached() { return pending != null; }
    public synchronized int count() { return deliveries.size(); }
    public synchronized List<Map<String, Object>> evidence() {
        return deliveries.stream().map(delivery -> Map.<String, Object>of(
                "request", TopologyValidationAccess.token(delivery.request), "attempt", delivery.result.attempt(),
                "staleRetries", delivery.result.staleRetries(), "result", TopologyValidationAccess.token(delivery.result),
                "stamps", delivery.result.stamps().stream().map(stamp -> Map.of("section", stamp.key().section().toString(),
                        "load", stamp.loadIdentity(), "version", stamp.version())).toList())).toList();
    }
    public synchronized boolean holdsSection(net.minecraft.core.SectionPos section) {
        return pending != null && pending.result.stamps().stream().anyMatch(stamp -> stamp.key().section().equals(section));
    }
    public void release(Throwable injectedFailure) {
        Delivery delivery;
        synchronized (this) { delivery = pending; pending = null; }
        if (delivery != null) deliver(delivery, injectedFailure == null ? delivery.failure : injectedFailure);
    }
    public void replay(int index) {
        Delivery delivery;
        synchronized (this) { delivery = deliveries.get(index); }
        deliver(delivery, delivery.failure);
    }
    private void deliver(Delivery delivery, Throwable failure) {
        synchronized (this) { delivering = true; }
        try { invoke(delivery.request, "completeWorker", delivery.result, failure); }
        finally { synchronized (this) { delivering = false; } }
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        try { release(null); }
        finally {
            synchronized (this) { deliveries.clear(); }
            synchronized (TopologyResultValidationProbe.class) { if (active == this) active = null; }
        }
    }
}
