package com.scarasol.acceleratednavigation.topology;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** Faults apply to actual store operations and one loaded section's real recovery. */
public final class TopologyHandoffFaultProbe implements AutoCloseable {
    private static volatile TopologyHandoffFaultProbe active;
    private final TopologyStore store;
    private final TopologyWorkerRuntime.ClusterKey key;
    private final long load;
    private final List<String> formations = new ArrayList<>();
    private String fault;
    private boolean closed, scanning;
    private int reads, rejectedReads, writes, rejectedWrites, scans, packs, publications, collisions;

    private TopologyHandoffFaultProbe(Object service, ResourceKey<Level> dimension, SectionPos section, long load) {
        store = (TopologyStore) readField(service, "store");
        if (store == null) throw new IllegalStateException("fault control requires the real persistence owner");
        key = new TopologyWorkerRuntime.ClusterKey(dimension, section); this.load = load;
    }

    public static synchronized TopologyHandoffFaultProbe install(Object service, ResourceKey<Level> dimension,
                                                                 SectionPos section, long load) {
        if (active != null) throw new IllegalStateException("overlapping persistence fault controls");
        active = new TopologyHandoffFaultProbe(service, dimension, section, load);
        return active;
    }

    public synchronized void fault(String value) {
        if (closed || value != null && !List.of("io", "write", "scan", "pack", "publish", "collision").contains(value)) {
            throw new IllegalArgumentException("invalid or closed fault control");
        }
        fault = value;
    }

    private boolean matches(Object owner, Object chunkKey) {
        return !closed && store == owner && key.dimension().equals(readField(chunkKey, "dimension"))
                && key.section().chunk().equals(readField(chunkKey, "chunk"));
    }

    static void read(Object owner, Object chunkKey) throws IOException {
        TopologyHandoffFaultProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (!probe.matches(owner, chunkKey)) return;
            probe.reads++;
            if ("io".equals(probe.fault)) { probe.rejectedReads++; throw new IOException("H02 actual read fault"); }
        }
    }

    static void write(Object owner, Object chunkKey) throws IOException {
        TopologyHandoffFaultProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (!probe.matches(owner, chunkKey)) return;
            probe.writes++;
            if ("write".equals(probe.fault)) { probe.rejectedWrites++; throw new IOException("H03 actual write fault"); }
        }
    }

    static void formed(TopologyStore.WriteReceipt receipt) {
        TopologyHandoffFaultProbe probe = active;
        if (probe != null) synchronized (probe) {
            var decision = (TopologyWorkerRuntime.FactDecision) readField(receipt, "decision");
            if (probe.closed || !probe.key.equals(decision.key()) || probe.load != decision.loadIdentity()) return;
            TopologyStore.Formation formation = receipt.formed.getNow(null);
            if (formation != null) {
                if (probe.formations.size() >= 32) throw new IllegalStateException("unbounded fault attempts");
                probe.formations.add(formation.facts() == null ? formation.failureStatus().name() : "FORMED");
            }
        }
    }

    public static void recovery(String point, Object section) {
        TopologyHandoffFaultProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (probe.closed || !probe.key.equals(readField(section, "key"))
                    || probe.load != (long) readField(readField(section, "chunk"), "identity")) return;
            if (point.equals("publish") && !probe.scanning) return;
            switch (point) {
                case "scan" -> { probe.scans++; probe.scanning = true; }
                case "pack" -> probe.packs++;
                case "publish" -> { probe.publications++; probe.scanning = false; }
                case "end" -> probe.scanning = false;
                default -> throw new IllegalArgumentException(point);
            }
            if (point.equals(probe.fault)) {
                probe.scanning = false;
                throw new IllegalStateException("H02 actual recovery " + point + " fault");
            }
        }
    }

    public static void collision(BlockPos position) {
        TopologyHandoffFaultProbe probe = active;
        if (probe != null) synchronized (probe) {
            if (!probe.closed && probe.scanning && "collision".equals(probe.fault) && probe.collisions == 0
                    && position.equals(new BlockPos(probe.key.section().minBlockX() + 4,
                    probe.key.section().minBlockY() + 1, probe.key.section().minBlockZ() + 4))) {
                probe.collisions++;
                throw new IllegalStateException("H02 one-cell collision fault");
            }
        }
    }

    /** Mutate a disposable real record on its original I/O owner, then invalidate that owner's decode cache. */
    public CompletableFuture<Void> corrupt(String kind) {
        if (!List.of("missing", "truncated", "version").contains(kind)) throw new IllegalArgumentException(kind);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (readField(store, "monitor")) {
            invoke(store, "enqueueLocked", (ArrayDeque<?>) readField(store, "foreground"), (Runnable) () -> {
                try {
                    synchronized (this) { if (closed) { completion.cancel(false); return; } }
                    Class<?> type = Class.forName(TopologyStore.class.getName() + "$ChunkKey");
                    var constructor = type.getDeclaredConstructor(ResourceKey.class, net.minecraft.world.level.ChunkPos.class);
                    constructor.setAccessible(true);
                    Object chunkKey = constructor.newInstance(key.dimension(), key.section().chunk());
                    RegionFile region = (RegionFile) invoke(store, "region", chunkKey);
                    byte[] input;
                    try (DataInputStream stream = region.getChunkDataInputStream(key.section().chunk())) {
                        if (stream == null) throw new IOException("fault setup requires an existing valid record");
                        input = stream.readAllBytes();
                    }
                    byte[] output = corruptRecord(input, key.section().y(), kind);
                    try (DataOutputStream stream = region.getChunkDataOutputStream(key.section().chunk())) { stream.write(output); }
                    store.unload(key.dimension(), key.section().chunk());
                    completion.complete(null);
                } catch (Exception failure) { completion.completeExceptionally(failure); }
            });
        }
        return completion;
    }

    static byte[] corruptRecord(byte[] original, int sectionY, String kind) throws IOException {
        if (kind.equals("truncated")) {
            if (original.length == 0) throw new IOException("empty record");
            return Arrays.copyOf(original, original.length - 1);
        }
        record Record(int y, long version, byte[] facts) { }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(original))) {
            int magic = input.readInt(), schema = input.readInt(), algorithm = input.readInt(), count = input.readInt();
            if (count < 0 || count > 1024) throw new IOException("invalid setup record count");
            List<Record> records = new ArrayList<>();
            boolean found = false;
            for (int i = 0; i < count; i++) {
                int y = input.readInt(); long version = input.readLong();
                byte[] facts = new byte[BaseClusterTopology.PACKED_FACT_BYTES]; input.readFully(facts);
                if (y == sectionY) {
                    found = true;
                    if (kind.equals("missing")) continue;
                    if (!kind.equals("version")) throw new IllegalArgumentException(kind);
                    version = version == 0 ? 1 : version - 1;
                }
                records.add(new Record(y, version, facts));
            }
            if (!found || input.read() != -1) throw new IOException("fault setup target missing or trailing data");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(magic); output.writeInt(schema); output.writeInt(algorithm); output.writeInt(records.size());
                for (Record record : records) { output.writeInt(record.y); output.writeLong(record.version); output.write(record.facts); }
            }
            return bytes.toByteArray();
        }
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reads", reads); result.put("rejectedReads", rejectedReads);
        result.put("writes", writes); result.put("rejectedWrites", rejectedWrites);
        result.put("scans", scans); result.put("packs", packs); result.put("publications", publications);
        result.put("collisionFailures", collisions); result.put("formations", List.copyOf(formations));
        return Map.copyOf(result);
    }

    @Override public void close() {
        synchronized (this) { fault = null; scanning = false; closed = true; }
        synchronized (TopologyHandoffFaultProbe.class) { if (active == this) active = null; }
    }
}
