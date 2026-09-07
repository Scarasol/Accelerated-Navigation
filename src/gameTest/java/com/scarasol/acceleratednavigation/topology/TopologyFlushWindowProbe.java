package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Keeps the actual flush task in its original queue or on its original I/O worker. */
final class TopologyFlushWindowProbe implements AutoCloseable {
    private static volatile TopologyFlushWindowProbe active;
    private final Object store, monitor;
    private final ResourceKey<Level> dimension;
    private final String window;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Map<String, Object>> events = new ArrayList<>();
    private Object task;
    private boolean current, reached, timedOut, closed;
    private int completed;

    private TopologyFlushWindowProbe(Object store, ResourceKey<Level> dimension, String window) {
        this.store = store; this.dimension = dimension; this.window = window; monitor = readField(store, "monitor");
    }
    static synchronized TopologyFlushWindowProbe watch(Object store, ResourceKey<Level> dimension, String window) {
        if (active != null) throw new IllegalStateException("overlapping flush controls");
        active = new TopologyFlushWindowProbe(store, dimension, window); return active;
    }
    static void requested(Object store, ResourceKey<Level> dimension) {
        var probe = active;
        if (probe == null || probe.store != store || !probe.dimension.equals(dimension)) return;
        synchronized (probe.monitor) {
            if (probe.closed) return;
            probe.record("request");
            if (probe.window.equals("request")) {
                require(((Set<?>) readField(store, "requestedFlushes")).contains(dimension), "the real request is pending");
                require(!((Set<?>) readField(store, "queuedFlushes")).contains(dimension), "accepted writes still prevent flush queueing");
                probe.reached = true;
            }
        }
    }
    static void scheduled(Object store, ResourceKey<Level> dimension) {
        var probe = active;
        if (probe == null || probe.store != store || !probe.dimension.equals(dimension)) return;
        synchronized (probe.monitor) {
            if (probe.closed || probe.task != null || !((Set<?>) readField(store, "queuedFlushes")).contains(dimension)) return;
            probe.task = ((ArrayDeque<?>) readField(store, "background")).peekLast();
            require(probe.task != null, "scheduled flush has its actual queue object");
            probe.record("queued");
        }
    }
    static Object dequeue(Object store, ArrayDeque<?> queue) {
        var probe = active;
        if (probe == null || probe.store != store) return queue.removeFirst();
        require(Thread.holdsLock(probe.monitor), "dequeue and target queue identity share the store monitor");
        boolean selected = !probe.closed && queue.peekFirst() == probe.task && probe.task != null;
        if (selected && probe.window.equals("queued")) {
            probe.reached = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (probe.release.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { probe.timedOut = true; throw new IllegalStateException("queued flush was not released"); }
                try { TimeUnit.NANOSECONDS.timedWait(probe.monitor, remaining); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        }
        Object removed = queue.removeFirst(); probe.current = selected;
        if (selected) probe.record("dequeued");
        return removed;
    }
    static void point(Object store, ResourceKey<Level> dimension, String point) {
        var probe = active;
        if (probe == null || probe.store != store || !probe.dimension.equals(dimension)) return;
        boolean pause;
        synchronized (probe.monitor) {
            if (probe.closed || !probe.current) return;
            probe.record(point);
            if (point.equals("complete")) { probe.completed++; probe.current = false; }
            pause = point.equals(probe.window) && probe.release.getCount() != 0;
            if (pause || point.equals("complete") && probe.window.equals("completion-before-callback")) probe.reached = true;
        }
        if (!pause) return;
        require(!Thread.holdsLock(probe.monitor), "executing flush pauses outside the store monitor");
        try {
            if (!probe.release.await(30, TimeUnit.SECONDS)) {
                synchronized (probe.monitor) { probe.timedOut = true; }
                throw new IllegalStateException("running flush was not released");
            }
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private void record(String point) {
        if (events.size() >= 32) throw new IllegalStateException("flush observation exceeded its bound");
        events.add(Map.of("point", point, "task", TopologyValidationAccess.token(task),
                "requested", ((Set<?>) readField(store, "requestedFlushes")).contains(dimension),
                "queued", ((Set<?>) readField(store, "queuedFlushes")).contains(dimension),
                "thread", Thread.currentThread().getName(), "nanoTime", System.nanoTime()));
    }
    boolean reached() { synchronized (monitor) { return reached; } }
    Map<String, Object> evidence() {
        synchronized (monitor) {
            var result = new LinkedHashMap<String, Object>();
            result.put("events", List.copyOf(events)); result.put("completed", completed); result.put("timedOut", timedOut);
            result.put("targetTask", TopologyValidationAccess.token(task)); result.put("current", current);
            result.put("inOriginalQueue", ((ArrayDeque<?>) readField(store, "background")).contains(task));
            result.put("dimension", dimension.location().toString()); return Map.copyOf(result);
        }
    }
    void release() { release.countDown(); synchronized (monitor) { monitor.notifyAll(); } }
    @Override public void close() {
        release(); synchronized (monitor) { closed = true; }
        synchronized (TopologyFlushWindowProbe.class) { if (active == this) active = null; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
