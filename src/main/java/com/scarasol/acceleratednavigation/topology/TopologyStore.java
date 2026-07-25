package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Versioned sidecar storage backed by Minecraft's crash-resistant RegionFile. */
public final class TopologyStore implements AutoCloseable {

    private static final int MAGIC = 0x414E544F;
    private static final int SCHEMA_VERSION = 2;
    private static final int ALGORITHM_VERSION = 2;
    private static final int MAX_SECTIONS_PER_CHUNK = 1_024;
    private static final int MAX_COMPONENTS_PER_SECTION = BaseClusterTopology.CELL_COUNT * 2;
    private static final int MAX_CONNECTIONS_PER_SECTION = 131_072;

    private final Path root;
    private final Map<RegionKey, RegionFile> regions = new HashMap<>();
    private boolean closed;

    public TopologyStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root");
        Files.createDirectories(root);
    }

    public synchronized Optional<BaseClusterTopology> read(ResourceKey<Level> dimension,
                                                            SectionPos section) throws IOException {
        ensureOpen();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        ChunkRecords records = readChunk(dimension, new ChunkPos(section.x(), section.z()));
        if (!records.valid) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.sections.get(section.y()));
    }

    public synchronized void write(ResourceKey<Level> dimension,
                                   BaseClusterTopology topology) throws IOException {
        ensureOpen();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(topology, "topology");
        SectionPos section = topology.section();
        ChunkPos chunk = new ChunkPos(section.x(), section.z());
        ChunkRecords existing = readChunk(dimension, chunk);
        Map<Integer, BaseClusterTopology> sections = existing.valid
                ? new LinkedHashMap<>(existing.sections)
                : new LinkedHashMap<>();
        sections.put(section.y(), topology);

        RegionFile region = region(dimension, chunk);
        try (DataOutputStream output = region.getChunkDataOutputStream(chunk)) {
            writeChunk(output, sections);
        }
        region.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (RegionFile region : regions.values()) {
            try {
                region.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        regions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private ChunkRecords readChunk(ResourceKey<Level> dimension, ChunkPos chunk) throws IOException {
        RegionFile region = region(dimension, chunk);
        try (DataInputStream input = region.getChunkDataInputStream(chunk)) {
            if (input == null) {
                return ChunkRecords.valid(Map.of());
            }
            try {
                return decodeChunk(input, chunk);
            } catch (IOException | RuntimeException exception) {
                return ChunkRecords.invalid();
            }
        }
    }

    private static ChunkRecords decodeChunk(DataInputStream input, ChunkPos chunk) throws IOException {
        if (input.readInt() != MAGIC
                || input.readInt() != SCHEMA_VERSION
                || input.readInt() != ALGORITHM_VERSION) {
            return ChunkRecords.invalid();
        }
        int sectionCount = input.readInt();
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS_PER_CHUNK) {
            return ChunkRecords.invalid();
        }

        Map<Integer, BaseClusterTopology> sections = new LinkedHashMap<>();
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            int sectionY = input.readInt();
            long revision = input.readLong();
            long fingerprint = input.readLong();
            long[][] fluidFaces = readFluidFaces(input);

            int componentCount = input.readUnsignedShort();
            if (componentCount > MAX_COMPONENTS_PER_SECTION) {
                return ChunkRecords.invalid();
            }
            List<BaseClusterTopology.Component> components = new ArrayList<>(componentCount);
            for (int componentIndex = 0; componentIndex < componentCount; componentIndex++) {
                int id = input.readUnsignedShort();
                int channelOrdinal = input.readUnsignedByte();
                int anchorCell = input.readUnsignedShort();
                int cellCount = input.readUnsignedShort();
                int flags = input.readUnsignedByte();
                int boundaryFaceMask = input.readUnsignedByte();
                if (id != componentIndex
                        || channelOrdinal >= BaseClusterTopology.Channel.values().length
                        || anchorCell >= BaseClusterTopology.CELL_COUNT
                        || cellCount == 0
                        || (flags & ~3) != 0
                        || (boundaryFaceMask & ~0x3F) != 0) {
                    return ChunkRecords.invalid();
                }
                long[][] boundaryMasks = new long[Direction.values().length][];
                for (Direction face : Direction.values()) {
                    if ((boundaryFaceMask & (1 << face.ordinal())) != 0) {
                        boundaryMasks[face.ordinal()] = readFaceMask(input);
                    }
                }
                components.add(new BaseClusterTopology.Component(
                        id,
                        BaseClusterTopology.Channel.values()[channelOrdinal],
                        anchorCell,
                        cellCount,
                        boundaryMasks,
                        (flags & 1) != 0,
                        (flags & 2) != 0
                ));
            }

            int connectionCount = input.readInt();
            if (connectionCount < 0 || connectionCount > MAX_CONNECTIONS_PER_SECTION) {
                return ChunkRecords.invalid();
            }
            List<BaseClusterTopology.LocalConnection> connections = new ArrayList<>(connectionCount);
            for (int connectionIndex = 0; connectionIndex < connectionCount; connectionIndex++) {
                int from = input.readUnsignedShort();
                int to = input.readUnsignedShort();
                int rise = input.readUnsignedByte();
                int drop = input.readUnsignedByte();
                int horizontal = input.readUnsignedByte();
                int kindOrdinal = input.readUnsignedByte();
                if (from >= componentCount || to >= componentCount
                        || kindOrdinal >= BaseClusterTopology.TraversalKind.values().length) {
                    return ChunkRecords.invalid();
                }
                connections.add(new BaseClusterTopology.LocalConnection(
                        from,
                        to,
                        rise,
                        drop,
                        horizontal,
                        BaseClusterTopology.TraversalKind.values()[kindOrdinal]
                ));
            }

            char[][] labels = new char[BaseClusterTopology.Channel.values().length][];
            for (BaseClusterTopology.Channel channel : BaseClusterTopology.Channel.values()) {
                char[] channelLabels = new char[BaseClusterTopology.CELL_COUNT];
                for (int cell = 0; cell < channelLabels.length; cell++) {
                    channelLabels[cell] = (char) input.readUnsignedShort();
                }
                labels[channel.ordinal()] = channelLabels;
            }

            SectionPos section = SectionPos.of(chunk.x, sectionY, chunk.z);
            BaseClusterTopology topology = BaseClusterTopology.restore(
                    section,
                    revision,
                    fingerprint,
                    components,
                    connections,
                    labels,
                    fluidFaces
            );
            if (sections.put(sectionY, topology) != null) {
                return ChunkRecords.invalid();
            }
        }
        if (input.read() != -1) {
            return ChunkRecords.invalid();
        }
        return ChunkRecords.valid(sections);
    }

    private static long[][] readFluidFaces(DataInputStream input) throws IOException {
        int fluidFaceMask = input.readUnsignedByte();
        if ((fluidFaceMask & ~0x3F) != 0) {
            throw new IOException("invalid fluid face mask");
        }
        long[][] faces = new long[Direction.values().length][];
        for (Direction face : Direction.values()) {
            if ((fluidFaceMask & (1 << face.ordinal())) != 0) {
                faces[face.ordinal()] = readFaceMask(input);
            }
        }
        return faces;
    }

    private static long[] readFaceMask(DataInputStream input) throws IOException {
        long[] words = new long[4];
        boolean nonEmpty = false;
        for (int word = 0; word < words.length; word++) {
            words[word] = input.readLong();
            nonEmpty |= words[word] != 0L;
        }
        if (!nonEmpty) {
            throw new IOException("persisted face mask is empty");
        }
        return words;
    }

    private static void writeChunk(DataOutputStream output,
                                   Map<Integer, BaseClusterTopology> sections) throws IOException {
        if (sections.size() > MAX_SECTIONS_PER_CHUNK) {
            throw new IOException("too many topology sections in one chunk");
        }
        output.writeInt(MAGIC);
        output.writeInt(SCHEMA_VERSION);
        output.writeInt(ALGORITHM_VERSION);
        output.writeInt(sections.size());
        for (Map.Entry<Integer, BaseClusterTopology> sectionEntry : sections.entrySet()) {
            BaseClusterTopology topology = sectionEntry.getValue();
            output.writeInt(sectionEntry.getKey());
            output.writeLong(topology.revision());
            output.writeLong(topology.sourceFingerprint());
            writeFluidFaces(output, topology);

            List<BaseClusterTopology.Component> components = topology.components();
            if (components.size() > MAX_COMPONENTS_PER_SECTION) {
                throw new IOException("too many components in one topology section");
            }
            output.writeShort(components.size());
            for (BaseClusterTopology.Component component : components) {
                output.writeShort(component.id());
                output.writeByte(component.channel().ordinal());
                output.writeShort(component.anchorCell());
                output.writeShort(component.cellCount());
                output.writeByte((component.containsFluid() ? 1 : 0)
                        | (component.requiresExactCheck() ? 2 : 0));
                output.writeByte(component.boundaryFaceMask());
                for (Direction face : Direction.values()) {
                    if (component.touches(face)) {
                        writeFaceMask(output, component.boundaryMask(face));
                    }
                }
            }

            List<BaseClusterTopology.LocalConnection> connections = topology.localConnections();
            if (connections.size() > MAX_CONNECTIONS_PER_SECTION) {
                throw new IOException("too many local connections in one topology section");
            }
            output.writeInt(connections.size());
            for (BaseClusterTopology.LocalConnection connection : connections) {
                output.writeShort(connection.fromComponent());
                output.writeShort(connection.toComponent());
                output.writeByte(connection.rise());
                output.writeByte(connection.drop());
                output.writeByte(connection.horizontalDistance());
                output.writeByte(connection.kind().ordinal());
            }

            for (BaseClusterTopology.Channel channel : BaseClusterTopology.Channel.values()) {
                for (char label : topology.componentLabels(channel)) {
                    output.writeShort(label);
                }
            }
        }
    }

    private static void writeFluidFaces(DataOutputStream output,
                                        BaseClusterTopology topology) throws IOException {
        output.writeByte(topology.nonEmptyFluidFaceMask());
        for (Direction face : Direction.values()) {
            if ((topology.nonEmptyFluidFaceMask() & (1 << face.ordinal())) != 0) {
                writeFaceMask(output, topology.fluidMask(face));
            }
        }
    }

    private static void writeFaceMask(DataOutputStream output, long[] mask) throws IOException {
        for (long word : mask) {
            output.writeLong(word);
        }
    }

    private RegionFile region(ResourceKey<Level> dimension, ChunkPos chunk) throws IOException {
        RegionKey key = new RegionKey(dimension.location(), chunk.getRegionX(), chunk.getRegionZ());
        RegionFile open = regions.get(key);
        if (open != null) {
            return open;
        }
        Path directory = dimensionDirectory(key.dimension);
        Files.createDirectories(directory);
        Path file = directory.resolve("r." + key.regionX + "." + key.regionZ + ".mca");
        RegionFile created = new RegionFile(file, directory, false);
        regions.put(key, created);
        return created;
    }

    private Path dimensionDirectory(ResourceLocation dimension) {
        Path directory = root.resolve(dimension.getNamespace());
        for (String segment : dimension.getPath().split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid dimension path");
            }
            directory = directory.resolve(segment);
        }
        return directory;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("topology store is closed");
        }
    }

    private record RegionKey(ResourceLocation dimension, int regionX, int regionZ) {
    }

    private record ChunkRecords(boolean valid, Map<Integer, BaseClusterTopology> sections) {
        private static ChunkRecords valid(Map<Integer, BaseClusterTopology> sections) {
            return new ChunkRecords(true, Map.copyOf(sections));
        }

        private static ChunkRecords invalid() {
            return new ChunkRecords(false, Map.of());
        }
    }
}
