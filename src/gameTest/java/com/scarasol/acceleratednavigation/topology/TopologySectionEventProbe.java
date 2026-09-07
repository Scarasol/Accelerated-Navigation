package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** A bounded target observation retains values and weak identity tokens, never complete fact objects. */
public final class TopologySectionEventProbe implements AutoCloseable {
    private static volatile TopologySectionEventProbe active;
    private final TopologyWorkerRuntime.ClusterKey key;
    private final List<Publication> publications = new ArrayList<>();
    private final Map<Long, Integer> scans = new LinkedHashMap<>();
    private boolean closed;
    private boolean delayNextDelta;
    private boolean delayNextFailure;
    private Runnable delayedDelta;

    public record Publication(long load, long previousVersion, long version, String state,
                              long factsToken, Map<Integer, Byte> changes) { }

    private TopologySectionEventProbe(ResourceKey<Level> dimension, SectionPos section) {
        key = new TopologyWorkerRuntime.ClusterKey(dimension, section);
    }

    public static synchronized TopologySectionEventProbe watch(ResourceKey<Level> dimension, SectionPos section) {
        if (active != null) throw new IllegalStateException("overlapping section observations");
        active = new TopologySectionEventProbe(dimension, section);
        return active;
    }

    public static void event(Object value) {
        TopologySectionEventProbe probe = active;
        if (probe == null) return;
        var event = (TopologyWorkerRuntime.SectionEvent) value;
        synchronized (probe) {
            if (probe.closed || !probe.key.equals(event.key())) return;
            if (probe.publications.size() >= 64) throw new IllegalStateException("section observation exceeded its bound");
            probe.publications.add(new Publication(event.loadIdentity(), event.previousVersion(), event.version(),
                    event.state().name(), TopologyValidationAccess.token(event.facts()), Map.copyOf(event.changes())));
        }
    }

    public static void scan(Object section) {
        TopologySectionEventProbe probe = active;
        if (probe == null) return;
        synchronized (probe) {
            if (probe.closed || !probe.key.equals(readField(section, "key"))) return;
            long load = (long) readField(readField(section, "chunk"), "identity");
            if (probe.scans.size() >= 8 && !probe.scans.containsKey(load)) throw new IllegalStateException("unbounded observation load identities");
            probe.scans.merge(load, 1, Integer::sum);
        }
    }

    public synchronized List<Publication> publications() { return List.copyOf(publications); }
    public synchronized Map<Long, Integer> scans() { return Map.copyOf(scans); }
    public synchronized void delayNextDelta() {
        if (delayNextDelta || delayNextFailure || delayedDelta != null) throw new IllegalStateException("overlapping delayed section input");
        delayNextDelta = true;
    }
    public synchronized void delayNextFailure() {
        if (delayNextDelta || delayNextFailure || delayedDelta != null) throw new IllegalStateException("overlapping delayed section input");
        delayNextFailure = true;
    }
    public static boolean defer(Object owner, Object value) {
        TopologySectionEventProbe probe = active;
        if (probe == null) return false;
        var event = (TopologyWorkerRuntime.SectionEvent) value;
        synchronized (probe) {
            if (probe.closed || !probe.key.equals(event.key())) return false;
            boolean matching = probe.delayNextDelta && !event.changes().isEmpty()
                    || probe.delayNextFailure && (event.state() == TopologyWorkerRuntime.FactState.RECOVERY_FAILED
                    || event.state() == TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE);
            if (!matching) return false;
            probe.delayNextDelta = false; probe.delayNextFailure = false;
            probe.delayedDelta = () -> ((TopologyWorkerRuntime) owner).publishSection(event);
            return true;
        }
    }
    public synchronized boolean deltaDelayed() { return delayedDelta != null; }
    public void releaseDelta() {
        Runnable resume;
        synchronized (this) { resume = delayedDelta; delayedDelta = null; }
        if (resume != null) resume.run();
    }
    public Object loaded(Object service) { return ((Map<?, ?>) readField(service, "loadedSections")).get(key); }
    public void comparePublishedSection(Object service, ChunkAccess chunk, int sectionY) {
        BaseClusterTopology.PackedFacts actual = facts(service);
        if (actual == null) throw new AssertionError("complete current facts are published before consumer validation");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockPos position = new BlockPos(chunk.getPos().getMinBlockX() + x, sectionY * 16 + y, chunk.getPos().getMinBlockZ() + z);
            if (actual.flags(x | z << 4 | y << 8) != TopologyGenerationProbe.independentFlags(chunk, position)) {
                throw new AssertionError("published final cell disagrees with independent world classification at " + position);
            }
        }
    }
    private BaseClusterTopology.PackedFacts facts(Object service) {
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(key);
            return entry == null ? null : (BaseClusterTopology.PackedFacts) readField(entry, "facts");
        }
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        try { releaseDelta(); }
        finally { synchronized (TopologySectionEventProbe.class) { if (active == this) active = null; } }
    }
}
