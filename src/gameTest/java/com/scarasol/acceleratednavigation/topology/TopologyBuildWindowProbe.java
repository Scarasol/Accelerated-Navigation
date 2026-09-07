package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.SectionPos;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Pauses the original worker outside both owner locks while retaining its real build permit. */
public final class TopologyBuildWindowProbe implements AutoCloseable {
    private static volatile TopologyBuildWindowProbe active;
    private final Object runtime;
    private final String window;
    private final Set<Long> chunks;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Map<String, Object>> events = new ArrayList<>();
    private SectionPos section;
    private Object result, selectedInput, oldOwner, oldValue;
    private boolean reached, closed, timedOut;

    private TopologyBuildWindowProbe(Object service, String window, Set<Long> chunks) {
        runtime = readField(service, "runtime"); this.window = window; this.chunks = Set.copyOf(chunks);
    }
    public static synchronized TopologyBuildWindowProbe watch(Object service, String window, Set<Long> chunks) {
        if (active != null) throw new IllegalStateException("overlapping build-window controls");
        active = new TopologyBuildWindowProbe(service, window, chunks); return active;
    }
    static void point(Object runtime, String kind, String phase, SectionPos section, Object input, Object result) {
        var probe = active;
        if (probe == null || probe.runtime != runtime || !probe.chunks.contains(section.chunk().toLong())) return;
        boolean pause;
        synchronized (probe) {
            if (probe.closed || !probe.window.startsWith(kind + "/")) return;
            if (probe.section != null && !probe.section.equals(section)) return;
            if (probe.events.size() >= 32) throw new IllegalStateException("build-window observation exceeded its bound");
            probe.events.add(Map.of("window", kind + "/" + phase, "section", section.toString(),
                    "input", TopologyValidationAccess.token(input), "result", TopologyValidationAccess.token(result),
                    "thread", Thread.currentThread().getName(), "nanoTime", System.nanoTime()));
            pause = !probe.reached && probe.window.equals(kind + "/" + phase);
            if (pause) { probe.section = section; probe.selectedInput = input; probe.reached = true; }
            if (result != null && probe.result == null && probe.selectedInput == input) probe.result = result;
        }
        if (!pause) return;
        Object queue = readField(readField(runtime, "taskExecutor"), "monitor");
        if (Thread.holdsLock(readField(runtime, "runtimeLock")) || Thread.holdsLock(queue)) {
            throw new IllegalStateException("build-window pause would retain an owner lock");
        }
        try {
            if (!probe.release.await(30, TimeUnit.SECONDS)) {
                synchronized (probe) { probe.timedOut = true; }
                throw new IllegalStateException("build-window release timed out");
            }
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("build-window interrupted", failure); }
    }
    public synchronized boolean reached() { return reached; }
    public synchronized boolean timedOut() { return timedOut; }
    public synchronized SectionPos section() { return section; }
    public synchronized List<Map<String, Object>> events() { return List.copyOf(events); }
    public void release() {
        release.countDown();
        Object monitor = readField(readField(runtime, "taskExecutor"), "monitor");
        synchronized (monitor) { monitor.notifyAll(); }
    }
    static void executorQueued(Object executor) {
        var probe = active;
        if (probe == null || !probe.window.equals("executor/queued") || readField(probe.runtime, "taskExecutor") != executor) return;
        Object monitor = readField(executor, "monitor");
        if (!Thread.holdsLock(monitor) || Thread.holdsLock(readField(probe.runtime, "runtimeLock"))) {
            throw new IllegalStateException("executor queue capture requires only its queue monitor");
        }
        synchronized (probe) {
            if (probe.closed || probe.release.getCount() == 0) return;
            if (!probe.reached) {
                for (Object task : ((Map<?, ?>) readField(executor, "foreground")).values()) {
                    SectionPos section = taskSection(task);
                    if (section == null || !probe.chunks.contains(section.chunk().toLong())) continue;
                    probe.selectedInput = task; probe.section = section; probe.reached = true;
                    probe.events.add(Map.of("window", probe.window, "section", section.toString(),
                            "task", TopologyValidationAccess.token(task), "state", readField(task, "state").toString(),
                            "thread", Thread.currentThread().getName(), "nanoTime", System.nanoTime()));
                    break;
                }
            }
            if (!probe.reached) return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (probe.release.getCount() != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                synchronized (probe) { probe.timedOut = true; }
                throw new IllegalStateException("queued original task was not released");
            }
            try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
    }
    static void executorClaimed(Object executor) {
        var probe = active;
        if (probe == null || !probe.window.equals("executor/claimed") || readField(probe.runtime, "taskExecutor") != executor) return;
        Object task = ((ThreadLocal<?>) readField(executor, "currentTask")).get();
        SectionPos section = taskSection(task);
        if (section != null) point(probe.runtime, "executor", "claimed", section, task, null);
    }
    private static SectionPos taskSection(Object task) {
        if (task == null || !readField(task, "kind").toString().equals("BUILD")) return null;
        Object command = readField(task, "command");
        for (var field : command.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object captured = field.get(command);
                if (captured != null && captured.getClass().getSimpleName().equals("TopologyDemand")) {
                    return ((TopologyWorkerRuntime.ClusterKey) readField(captured, "key")).section();
                }
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
        return null;
    }
    Map<String, Object> executorState() {
        Object task;
        synchronized (this) { task = window.startsWith("executor/") ? selectedInput : null; }
        if (task == null) return Map.of();
        Object executor = readField(runtime, "taskExecutor");
        synchronized (readField(executor, "monitor")) {
            return Map.of("task", TopologyValidationAccess.token(task), "state", readField(task, "state").toString(),
                    "queued", ((Map<?, ?>) readField(executor, "foreground")).containsValue(task),
                    "buildPermit", readField(executor, "buildRunning"));
        }
    }
    static void searchBoundary(MacroSearch search) {
        var probe = active;
        if (probe == null || !probe.window.endsWith("/replaced")) return;
        synchronized (probe) { if (probe.closed || probe.reached) return; }
        Object graph = readField(search, "graph");
        if (graph.getClass().getSimpleName().equals("AggregateTopologyGraph")) graph = readField(graph, "delegate");
        if (!Set.of("TopologyGraph", "SuperTopologyGraph").contains(graph.getClass().getSimpleName()) || readField(graph, "this$0") != probe.runtime) return;
        Object selected = null, owner = null; SectionPos section = null;
        String kind = probe.window.substring(0, probe.window.indexOf('/'));
        synchronized (readField(probe.runtime, "runtimeLock")) {
            if (kind.endsWith("boundary")) {
                for (var item : ((Map<?, ?>) readField(graph, "boundarySnapshot")).entrySet()) {
                    Object source = readField(item.getKey(), "source");
                    if (kind.equals("base-boundary") != (source instanceof BaseClusterTopology)) continue;
                    SectionPos candidate = source instanceof BaseClusterTopology base ? base.section() : ((SuperClusterTopology) source).origin();
                    if (!probe.chunks.contains(candidate.chunk().toLong())) continue;
                    selected = readField(item.getValue(), "value"); owner = readField(item.getValue(), "owner"); section = candidate; break;
                }
            } else {
                for (Object capture : ((Map<?, ?>) readField(graph, "topologySnapshot")).values()) {
                    Object topology = readField(capture, "topology");
                    if (kind.equals("base") != (topology instanceof BaseClusterTopology)) continue;
                    SectionPos candidate = topology instanceof BaseClusterTopology base ? base.section() : ((SuperClusterTopology) topology).origin();
                    if (!probe.chunks.contains(candidate.chunk().toLong())) continue;
                    selected = topology; owner = readField(capture, "owner"); section = candidate; break;
                }
            }
        }
        if (selected == null) return;
        synchronized (probe) {
            if (probe.closed || probe.reached || probe.oldOwner != null) return;
            probe.oldOwner = owner; probe.oldValue = selected;
        }
        point(probe.runtime, kind, "replaced", section, selected, selected);
    }
    public Map<String, Object> oldReferenceState() {
        Object owner, value; synchronized (this) { owner = oldOwner; value = oldValue; }
        if (owner == null) return Map.of();
        synchronized (readField(runtime, "runtimeLock")) {
            boolean boundary = window.contains("boundary/");
            Number count = boundary ? null : (Number) ((Map<?, ?>) readField(owner, "pinnedTopologies")).get(value);
            int pins = boundary ? (int) readField(owner, "pins") : count == null ? 0 : count.intValue();
            return Map.of("value", TopologyValidationAccess.token(value), "pins", pins,
                    "retiredOrReplaced", boundary ? readField(owner, "retired") : readField(owner, "topology") != value);
        }
    }
    public boolean resultPublished() {
        Object candidate; synchronized (this) { candidate = result; }
        if (candidate == null) return false;
        synchronized (readField(runtime, "runtimeLock")) {
            for (Object entry : ((Map<?, ?>) readField(runtime, "clusters")).values()) {
                for (Object view : ((Map<?, ?>) readField(entry, "views")).values()) {
                    if (readField(view, "topology") == candidate || linkContains(view, candidate)) return true;
                }
            }
            for (Object entry : ((Map<?, ?>) readField(runtime, "superClusters")).values()) {
                if (readField(entry, "topology") == candidate || linkContains(entry, candidate)) return true;
            }
        }
        return false;
    }
    private static boolean linkContains(Object owner, Object candidate) {
        for (Object link : (Object[]) readField(owner, "links")) if (link != null && readField(link, "value") == candidate) return true;
        return false;
    }
    @Override public void close() {
        synchronized (this) { closed = true; result = null; selectedInput = null; oldOwner = null; oldValue = null; }
        release(); synchronized (TopologyBuildWindowProbe.class) { if (active == this) active = null; }
    }
}
