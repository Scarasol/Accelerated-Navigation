package com.scarasol.acceleratednavigation.topology;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/** One disk fixture, bound to the tag actually consumed by ChunkSerializer.read. */
public final class TopologySeededChunkProbe implements AutoCloseable {
    private static TopologySeededChunkProbe active;
    private static final String TAG = "anav_fixture_seed";
    private final ServerLevel level;
    private final ChunkPos position;
    private final String marker = java.util.UUID.randomUUID().toString();
    private long readState;
    private int reads;

    public TopologySeededChunkProbe(ServerLevel level, ChunkPos position, CompoundTag tag) {
        this.level = level; this.position = position;
        synchronized (TopologySeededChunkProbe.class) {
            if (active != null) throw new IllegalStateException("Overlapping seeded chunk fixtures");
            tag.putString(TAG, marker); active = this;
        }
    }

    public static synchronized void read(ServerLevel level, ChunkAccess chunk, CompoundTag tag) {
        var probe = active;
        if (probe == null || probe.level != level || !probe.position.equals(chunk.getPos())) return;
        if (!probe.marker.equals(tag.getString(TAG))) throw new IllegalStateException("World loaded a different disk baseline");
        if (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        probe.readState = TopologyValidationAccess.token(((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState());
        probe.reads++;
    }

    public void verify(ChunkAccess chunk) {
        synchronized (TopologySeededChunkProbe.class) {
            if (reads != 1 || readState != TopologyValidationAccess.token(((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState())) {
                throw new AssertionError("Loaded chunk must descend from the single seeded disk read: reads=" + reads + ", readState=" + readState
                        + ", loadedState=" + TopologyValidationAccess.token(((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState()));
            }
        }
    }

    @Override public void close() { synchronized (TopologySeededChunkProbe.class) { if (active == this) active = null; } }
}
