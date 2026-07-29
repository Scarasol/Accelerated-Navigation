package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, entity-independent structural topology exported by one 16-cubed section. */
public final class BaseClusterTopology {

    public static final int SIDE = 16;
    public static final int CELL_COUNT = SIDE * SIDE * SIDE;
    static final int PACKED_FACT_BYTES = CELL_COUNT / 2;
    public static final int VOLUME_OPEN = 1;
    public static final int GROUND_OPEN = 1 << 1;
    public static final int FLUID = 1 << 2;
    public static final int EXACT_REQUIRED = 1 << 3;

    private static final int FACE_CELL_COUNT = SIDE * SIDE;
    private static final int FACE_WORDS = FACE_CELL_COUNT / Long.SIZE;
    private static final int VALID_FLAGS = VOLUME_OPEN | GROUND_OPEN | FLUID | EXACT_REQUIRED;
    private static final int MAX_STRUCTURAL_STEP = 1;
    private static final int MAX_STRUCTURAL_JUMP = 3;
    private static final int MAX_STRUCTURAL_DROP = 4;

    private final SectionPos section;
    private final long revision;
    private final long sourceFingerprint;
    private final List<Component> components;
    private final List<LocalConnection> localConnections;
    private final int[][] componentIdsByChannel;
    private final int[][][] boundaryComponentIds;
    private final List<Component>[] componentViewsByChannel;
    private final List<Component>[][] boundaryComponentViews;
    private final int[] localOutgoingOffsets;
    private final LocalConnection[] localOutgoing;
    private final List<LocalConnection>[] localOutgoingViews;
    private final BitStorage[] componentLabels;
    private final long[][] fluidFaces;
    private final int nonEmptyFluidFaceMask;
    private final int retainedBytes;

    private BaseClusterTopology(SectionPos section,
                                long revision,
                                long sourceFingerprint,
                                List<Component> components,
                                List<LocalConnection> localConnections,
                                char[][] componentLabels,
                                long[][] fluidFaces) {
        this.section = Objects.requireNonNull(section, "section");
        this.revision = revision;
        this.sourceFingerprint = sourceFingerprint;
        this.components = List.copyOf(components);
        List<LocalConnection> sortedConnections = new ArrayList<>(localConnections);
        sortedConnections.sort(Comparator.comparingInt(LocalConnection::fromComponent)
                .thenComparingInt(LocalConnection::toComponent));
        this.localConnections = List.copyOf(sortedConnections);
        this.fluidFaces = copyFaces(fluidFaces);
        this.componentIdsByChannel = buildComponentIds(this.components);
        this.componentLabels = compactLabels(componentLabels, componentIdsByChannel);
        this.boundaryComponentIds = buildBoundaryComponentIds(this.components);
        this.componentViewsByChannel = buildComponentViews(this.components, componentIdsByChannel);
        this.boundaryComponentViews = buildBoundaryComponentViews(this.components, boundaryComponentIds);
        this.localOutgoingOffsets = buildOutgoingOffsets(this.components.size(), this.localConnections);
        this.localOutgoing = this.localConnections.toArray(LocalConnection[]::new);
        this.localOutgoingViews = buildOutgoingViews(localOutgoingOffsets, localOutgoing);

        int fluidFaceMask = 0;
        for (Direction face : Direction.values()) {
            if (this.fluidFaces[face.ordinal()] != null) {
                fluidFaceMask |= 1 << face.ordinal();
            }
        }
        this.nonEmptyFluidFaceMask = fluidFaceMask;
        this.retainedBytes = 96
                + Arrays.stream(this.componentLabels).mapToInt(labels -> labels.getRaw().length).sum()
                * Long.BYTES
                + Integer.bitCount(fluidFaceMask) * FACE_WORDS * Long.BYTES
                + this.components.stream().mapToInt(Component::retainedBytes).sum()
                + this.localConnections.size() * LocalConnection.RETAINED_BYTES
                + Arrays.stream(componentIdsByChannel).mapToInt(ids -> ids.length).sum() * Integer.BYTES
                + Arrays.stream(boundaryComponentIds)
                .flatMap(Arrays::stream)
                .mapToInt(ids -> ids.length)
                .sum() * Integer.BYTES
                + localOutgoingOffsets.length * Integer.BYTES;
        validate();
    }

    public static BaseClusterTopology build(SectionPos section, long revision, Snapshot snapshot) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(snapshot, "snapshot");

        List<Component> components = new ArrayList<>();
        char[][] labels = new char[Channel.values().length][CELL_COUNT];
        buildComponents(snapshot, Channel.GROUND, components, labels[Channel.GROUND.ordinal()]);
        buildComponents(snapshot, Channel.VOLUME, components, labels[Channel.VOLUME.ordinal()]);
        List<LocalConnection> connections = buildGroundConnections(snapshot, labels);
        return new BaseClusterTopology(
                section,
                revision,
                snapshot.fingerprint(),
                components,
                connections,
                labels,
                buildFluidFaces(snapshot)
        );
    }

    public SectionPos section() {
        return section;
    }

    public long revision() {
        return revision;
    }

    public long sourceFingerprint() {
        return sourceFingerprint;
    }

    public List<Component> components() {
        return components;
    }

    public List<Component> components(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        return componentViewsByChannel[channel.ordinal()];
    }

    public List<Component> boundaryComponents(Direction face, Channel channel) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(channel, "channel");
        return boundaryComponentViews[channel.ordinal()][face.ordinal()];
    }

    public Component component(int id) {
        if (id < 0 || id >= components.size()) {
            throw new IndexOutOfBoundsException("unknown component " + id);
        }
        return components.get(id);
    }

    public Component componentAt(Channel channel, int x, int y, int z) {
        Objects.requireNonNull(channel, "channel");
        int channelIndex = channel.ordinal();
        int encoded = componentLabels[channelIndex].get(cellIndex(x, y, z));
        return encoded == 0 ? null : component(componentIdsByChannel[channelIndex][encoded - 1]);
    }

    public Component nearestComponent(Channel channel, int x, int y, int z, int radius) {
        Objects.requireNonNull(channel, "channel");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        Component direct = componentAt(channel, x, y, z);
        if (direct != null) {
            return direct;
        }
        for (int distance = 1; distance <= radius; distance++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    int dxMagnitude = distance - Math.abs(dy) - Math.abs(dz);
                    if (dxMagnitude < 0) {
                        continue;
                    }
                    for (int sign : dxMagnitude == 0 ? new int[]{1} : new int[]{-1, 1}) {
                        int nx = x + sign * dxMagnitude;
                        int ny = y + dy;
                        int nz = z + dz;
                        if (inBounds(nx, ny, nz)) {
                            Component candidate = componentAt(channel, nx, ny, nz);
                            if (candidate != null) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<LocalConnection> outgoingConnections(int componentId) {
        if (componentId < 0 || componentId >= components.size()) {
            throw new IndexOutOfBoundsException("unknown component " + componentId);
        }
        return localOutgoingViews[componentId];
    }

    int componentCount(Channel channel) {
        return componentIdsByChannel[Objects.requireNonNull(channel, "channel").ordinal()].length;
    }

    int componentId(Channel channel, int index) {
        return componentIdsByChannel[Objects.requireNonNull(channel, "channel").ordinal()][index];
    }

    int boundaryComponentCount(Direction face, Channel channel) {
        return boundaryComponentIds[Objects.requireNonNull(channel, "channel").ordinal()]
                [Objects.requireNonNull(face, "face").ordinal()].length;
    }

    int boundaryComponentId(Direction face, Channel channel, int index) {
        return boundaryComponentIds[Objects.requireNonNull(channel, "channel").ordinal()]
                [Objects.requireNonNull(face, "face").ordinal()][index];
    }

    int localOutgoingStart(int componentId) {
        return localOutgoingOffsets[componentId];
    }

    int localOutgoingEnd(int componentId) {
        return localOutgoingOffsets[componentId + 1];
    }

    LocalConnection localOutgoing(int index) {
        return localOutgoing[index];
    }

    public List<LocalConnection> localConnections() {
        return localConnections;
    }

    public int nonEmptyFluidFaceMask() {
        return nonEmptyFluidFaceMask;
    }

    public boolean hasFluid(Direction face, int u, int v) {
        checkFaceCoordinate(u, v);
        long[] mask = fluidFaces[Objects.requireNonNull(face, "face").ordinal()];
        return mask != null && isSet(mask, faceIndex(u, v));
    }

    public long[] fluidMask(Direction face) {
        long[] mask = fluidFaces[Objects.requireNonNull(face, "face").ordinal()];
        return mask == null ? new long[FACE_WORDS] : mask.clone();
    }

    public int retainedBytes() {
        return retainedBytes;
    }

    private static int[][] buildComponentIds(List<Component> components) {
        int[][] result = new int[Channel.values().length][];
        for (Channel channel : Channel.values()) {
            int count = 0;
            for (Component component : components) {
                if (component.channel() == channel) {
                    count++;
                }
            }
            int[] ids = new int[count];
            int cursor = 0;
            for (Component component : components) {
                if (component.channel() == channel) {
                    ids[cursor++] = component.id();
                }
            }
            result[channel.ordinal()] = ids;
        }
        return result;
    }

    private static BitStorage[] compactLabels(char[][] source, int[][] componentIdsByChannel) {
        Objects.requireNonNull(source, "componentLabels");
        if (source.length != Channel.values().length) {
            throw new IllegalArgumentException("component label channel count is invalid");
        }
        BitStorage[] result = new BitStorage[source.length];
        for (Channel channel : Channel.values()) {
            int channelIndex = channel.ordinal();
            char[] labels = Objects.requireNonNull(source[channelIndex], "componentLabels entry");
            if (labels.length != CELL_COUNT) {
                throw new IllegalArgumentException("component label array has the wrong size");
            }
            int[] componentIds = componentIdsByChannel[channelIndex];
            if (componentIds.length == 0) {
                for (char label : labels) {
                    if (label != 0) {
                        throw new IllegalArgumentException("empty channel contains a component label");
                    }
                }
                result[channelIndex] = new ZeroBitStorage(CELL_COUNT);
                continue;
            }
            int[] localIds = new int[Arrays.stream(componentIds).max().orElse(-1) + 1];
            for (int index = 0; index < componentIds.length; index++) {
                localIds[componentIds[index]] = index + 1;
            }
            BitStorage packed = new SimpleBitStorage(
                    Integer.SIZE - Integer.numberOfLeadingZeros(componentIds.length),
                    CELL_COUNT
            );
            for (int cell = 0; cell < labels.length; cell++) {
                int encoded = labels[cell];
                if (encoded != 0) {
                    packed.set(cell, localIds[encoded - 1]);
                }
            }
            result[channelIndex] = packed;
        }
        return result;
    }

    private static int[][][] buildBoundaryComponentIds(List<Component> components) {
        int[][][] result = new int[Channel.values().length][Direction.values().length][];
        for (Channel channel : Channel.values()) {
            for (Direction face : Direction.values()) {
                int count = 0;
                for (Component component : components) {
                    if (component.channel() == channel && component.touches(face)) {
                        count++;
                    }
                }
                int[] ids = new int[count];
                int cursor = 0;
                for (Component component : components) {
                    if (component.channel() == channel && component.touches(face)) {
                        ids[cursor++] = component.id();
                    }
                }
                result[channel.ordinal()][face.ordinal()] = ids;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Component>[] buildComponentViews(List<Component> components, int[][] ids) {
        List<Component>[] result = (List<Component>[]) new List<?>[Channel.values().length];
        for (Channel channel : Channel.values()) {
            List<Component> view = new ArrayList<>(ids[channel.ordinal()].length);
            for (int id : ids[channel.ordinal()]) {
                view.add(components.get(id));
            }
            result[channel.ordinal()] = List.copyOf(view);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Component>[][] buildBoundaryComponentViews(List<Component> components,
                                                                    int[][][] ids) {
        List<Component>[][] result = (List<Component>[][]) new List<?>[Channel.values().length]
                [Direction.values().length];
        for (Channel channel : Channel.values()) {
            for (Direction face : Direction.values()) {
                int[] faceIds = ids[channel.ordinal()][face.ordinal()];
                List<Component> view = new ArrayList<>(faceIds.length);
                for (int id : faceIds) {
                    view.add(components.get(id));
                }
                result[channel.ordinal()][face.ordinal()] = List.copyOf(view);
            }
        }
        return result;
    }

    private static int[] buildOutgoingOffsets(int componentCount,
                                              List<LocalConnection> connections) {
        int[] offsets = new int[componentCount + 1];
        for (LocalConnection connection : connections) {
            if (connection.fromComponent() >= componentCount) {
                throw new IllegalArgumentException("local connection source is outside the component table");
            }
            offsets[connection.fromComponent() + 1]++;
        }
        for (int component = 1; component < offsets.length; component++) {
            offsets[component] += offsets[component - 1];
        }
        return offsets;
    }

    @SuppressWarnings("unchecked")
    private static List<LocalConnection>[] buildOutgoingViews(int[] offsets,
                                                               LocalConnection[] connections) {
        List<LocalConnection>[] result = (List<LocalConnection>[]) new List<?>[offsets.length - 1];
        for (int component = 0; component < result.length; component++) {
            result[component] = List.copyOf(Arrays.asList(connections)
                    .subList(offsets[component], offsets[component + 1]));
        }
        return result;
    }

    private static void buildComponents(Snapshot snapshot,
                                        Channel channel,
                                        List<Component> output,
                                        char[] labels) {
        int requiredFlag = channel == Channel.GROUND ? GROUND_OPEN : VOLUME_OPEN;
        int[] queue = new int[CELL_COUNT];
        for (int candidate = 0; candidate < CELL_COUNT; candidate++) {
            if ((snapshot.flags(candidate) & requiredFlag) == 0 || labels[candidate] != 0) {
                continue;
            }
            int id = output.size();
            if (id >= Character.MAX_VALUE) {
                throw new IllegalStateException("too many topology components");
            }
            int head = 0;
            int tail = 0;
            int anchor = candidate;
            boolean fluid = false;
            boolean exact = false;
            long[][] boundaryMasks = new long[Direction.values().length][];
            queue[tail++] = candidate;
            labels[candidate] = (char) (id + 1);
            while (head < tail) {
                int current = queue[head++];
                int flags = snapshot.flags(current);
                fluid |= (flags & FLUID) != 0;
                exact |= (flags & EXACT_REQUIRED) != 0;
                int x = x(current);
                int y = y(current);
                int z = z(current);
                addBoundaryCells(boundaryMasks, x, y, z);

                if (channel == Channel.GROUND) {
                    tail = enqueue(x - 1, y, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x + 1, y, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y, z - 1, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y, z + 1, requiredFlag, snapshot, labels, queue, tail, id);
                } else {
                    tail = enqueue(x - 1, y, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x + 1, y, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y - 1, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y + 1, z, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y, z - 1, requiredFlag, snapshot, labels, queue, tail, id);
                    tail = enqueue(x, y, z + 1, requiredFlag, snapshot, labels, queue, tail, id);
                }
            }
            output.add(new Component(id, channel, anchor, tail, boundaryMasks, fluid, exact));
        }
    }

    private static List<LocalConnection> buildGroundConnections(Snapshot snapshot,
                                                                 char[][] labels) {
        char[] ground = labels[Channel.GROUND.ordinal()];
        Map<Long, LocalConnection> best = new HashMap<>();
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (ground[cell] == 0) {
                continue;
            }
            int from = ground[cell] - 1;
            int x = x(cell);
            int y = y(cell);
            int z = z(cell);
            for (int[] direction : directions) {
                for (int horizontal = 1; horizontal <= MAX_STRUCTURAL_JUMP; horizontal++) {
                    int nx = x + direction[0] * horizontal;
                    int nz = z + direction[1] * horizontal;
                    if (!inBounds(nx, y, nz)) {
                        break;
                    }
                    if (horizontal > 1 && !structurallyOpen(snapshot, nx - direction[0], y, nz - direction[1])) {
                        break;
                    }
                    for (int dy = -MAX_STRUCTURAL_DROP; dy <= MAX_STRUCTURAL_STEP; dy++) {
                        int ny = y + dy;
                        if (!inBounds(nx, ny, nz)) {
                            continue;
                        }
                        int encoded = ground[cellIndex(nx, ny, nz)];
                        if (encoded == 0 || encoded - 1 == from) {
                            continue;
                        }
                        TraversalKind kind = horizontal == 1 ? TraversalKind.STEP : TraversalKind.JUMP;
                        LocalConnection candidate = new LocalConnection(
                                from,
                                encoded - 1,
                                Math.max(0, dy),
                                Math.max(0, -dy),
                                horizontal,
                                kind
                        );
                        long key = ((long) from << 32) | Integer.toUnsignedLong(encoded - 1);
                        best.merge(key, candidate, BaseClusterTopology::lessDemanding);
                    }
                    if (horizontal == 1) {
                        continue;
                    }
                }
            }
        }
        List<LocalConnection> result = new ArrayList<>(best.values());
        result.sort(Comparator.comparingInt(LocalConnection::fromComponent)
                .thenComparingInt(LocalConnection::toComponent));
        return List.copyOf(result);
    }

    private static LocalConnection lessDemanding(LocalConnection first, LocalConnection second) {
        int firstScore = first.rise() * 16 + first.horizontalDistance() * 4 + first.drop();
        int secondScore = second.rise() * 16 + second.horizontalDistance() * 4 + second.drop();
        return firstScore <= secondScore ? first : second;
    }

    private static boolean structurallyOpen(Snapshot snapshot, int x, int y, int z) {
        return inBounds(x, y, z)
                && (snapshot.flags(cellIndex(x, y, z)) & VOLUME_OPEN) != 0;
    }

    private static int enqueue(int x,
                               int y,
                               int z,
                               int requiredFlag,
                               Snapshot snapshot,
                               char[] labels,
                               int[] queue,
                               int tail,
                               int componentId) {
        if (!inBounds(x, y, z)) {
            return tail;
        }
        int candidate = cellIndex(x, y, z);
        if ((snapshot.flags(candidate) & requiredFlag) != 0 && labels[candidate] == 0) {
            labels[candidate] = (char) (componentId + 1);
            queue[tail++] = candidate;
        }
        return tail;
    }

    private static void addBoundaryCells(long[][] masks, int x, int y, int z) {
        if (y == 0) {
            set(boundaryMask(masks, Direction.DOWN), faceIndex(x, z));
        }
        if (y == 15) {
            set(boundaryMask(masks, Direction.UP), faceIndex(x, z));
        }
        if (z == 0) {
            set(boundaryMask(masks, Direction.NORTH), faceIndex(x, y));
        }
        if (z == 15) {
            set(boundaryMask(masks, Direction.SOUTH), faceIndex(x, y));
        }
        if (x == 0) {
            set(boundaryMask(masks, Direction.WEST), faceIndex(z, y));
        }
        if (x == 15) {
            set(boundaryMask(masks, Direction.EAST), faceIndex(z, y));
        }
    }

    private static long[] boundaryMask(long[][] masks, Direction face) {
        long[] mask = masks[face.ordinal()];
        if (mask == null) {
            mask = new long[FACE_WORDS];
            masks[face.ordinal()] = mask;
        }
        return mask;
    }

    private static long[][] buildFluidFaces(Snapshot snapshot) {
        long[][] faces = new long[Direction.values().length][];
        for (Direction face : Direction.values()) {
            long[] mask = new long[FACE_WORDS];
            for (int v = 0; v < SIDE; v++) {
                for (int u = 0; u < SIDE; u++) {
                    if ((snapshot.flags(cellIndex(face, u, v)) & FLUID) != 0) {
                        set(mask, faceIndex(u, v));
                    }
                }
            }
            if (!isEmpty(mask)) {
                faces[face.ordinal()] = mask;
            }
        }
        return faces;
    }

    private void validate() {
        for (int id = 0; id < components.size(); id++) {
            if (components.get(id).id() != id) {
                throw new IllegalArgumentException("component IDs must be dense and ordered");
            }
        }
        for (Channel channel : Channel.values()) {
            int channelIndex = channel.ordinal();
            BitStorage labels = componentLabels[channelIndex];
            if (labels.getSize() != CELL_COUNT) {
                throw new IllegalArgumentException("component label array has the wrong size");
            }
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                int encoded = labels.get(cell);
                if (encoded == 0) {
                    continue;
                }
                if (encoded > componentIdsByChannel[channelIndex].length) {
                    throw new IllegalArgumentException("component label is outside its channel table");
                }
                Component component = component(componentIdsByChannel[channelIndex][encoded - 1]);
                if (component.channel() != channel) {
                    throw new IllegalArgumentException("component label points to the wrong channel");
                }
            }
        }
        for (LocalConnection connection : localConnections) {
            if (component(connection.fromComponent()).channel() != Channel.GROUND
                    || component(connection.toComponent()).channel() != Channel.GROUND) {
                throw new IllegalArgumentException("local ground connection references another channel");
            }
        }
    }

    private static long[][] copyFaces(long[][] source) {
        Objects.requireNonNull(source, "fluidFaces");
        if (source.length != Direction.values().length) {
            throw new IllegalArgumentException("fluid face array has the wrong length");
        }
        long[][] copy = new long[source.length][];
        for (int index = 0; index < source.length; index++) {
            long[] mask = source[index];
            if (mask != null) {
                if (mask.length != FACE_WORDS || isEmpty(mask)) {
                    throw new IllegalArgumentException("invalid fluid face mask");
                }
                copy[index] = mask.clone();
            }
        }
        return copy;
    }

    private static boolean inBounds(int x, int y, int z) {
        return (x | y | z) >= 0 && x < SIDE && y < SIDE && z < SIDE;
    }

    private static boolean isEmpty(long[] mask) {
        for (long word : mask) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    private static void set(long[] mask, int index) {
        mask[index >>> 6] |= 1L << (index & 63);
    }

    private static boolean isSet(long[] mask, int index) {
        return (mask[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    private static int x(int index) {
        return index & 15;
    }

    private static int y(int index) {
        return index >>> 8;
    }

    private static int z(int index) {
        return index >>> 4 & 15;
    }

    private static int cellIndex(Direction face, int u, int v) {
        return switch (face) {
            case DOWN -> cellIndex(u, 0, v);
            case UP -> cellIndex(u, 15, v);
            case NORTH -> cellIndex(u, v, 0);
            case SOUTH -> cellIndex(u, v, 15);
            case WEST -> cellIndex(0, v, u);
            case EAST -> cellIndex(15, v, u);
        };
    }

    public static int cellIndex(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("section coordinates must be in [0, 15]");
        }
        return (y << 8) | (z << 4) | x;
    }

    private static int faceIndex(int u, int v) {
        checkFaceCoordinate(u, v);
        return (v << 4) | u;
    }

    private static void checkFaceCoordinate(int u, int v) {
        if ((u | v) < 0 || u >= SIDE || v >= SIDE) {
            throw new IndexOutOfBoundsException("face coordinates must be in [0, 15]");
        }
    }

    public enum Channel {
        GROUND,
        VOLUME
    }

    public enum TraversalKind {
        STEP,
        JUMP
    }

    public record TraversalProfile(float width,
                                   float height,
                                   int maxStep,
                                   int maxJump,
                                   int maxDrop,
                                   boolean acceptsFluid) {
        public static final TraversalProfile DEFAULT_GROUND =
                new TraversalProfile(0.6F, 1.95F, 1, 3, 3, false);

        public TraversalProfile {
            if (!Float.isFinite(width) || width <= 0.0F
                    || !Float.isFinite(height) || height <= 0.0F
                    || maxStep < 0 || maxStep > MAX_STRUCTURAL_STEP
                    || maxJump < 0 || maxJump > MAX_STRUCTURAL_JUMP
                    || maxDrop < 0 || maxDrop > MAX_STRUCTURAL_DROP) {
                throw new IllegalArgumentException("invalid traversal profile");
            }
        }

        public boolean supports(LocalConnection connection) {
            if (connection.drop() > maxDrop) {
                return false;
            }
            return connection.kind() == TraversalKind.STEP
                    ? connection.rise() <= maxStep
                    : connection.rise() <= maxStep && connection.horizontalDistance() <= maxJump;
        }
    }

    public record LocalConnection(int fromComponent,
                                  int toComponent,
                                  int rise,
                                  int drop,
                                  int horizontalDistance,
                                  TraversalKind kind) {
        private static final int RETAINED_BYTES = 32;

        public LocalConnection {
            Objects.requireNonNull(kind, "kind");
            if (fromComponent < 0 || toComponent < 0 || fromComponent == toComponent
                    || rise < 0 || drop < 0 || horizontalDistance <= 0) {
                throw new IllegalArgumentException("invalid local topology connection");
            }
        }

        public float lowerBound() {
            return (float) Math.sqrt(horizontalDistance * horizontalDistance
                    + (rise + drop) * (rise + drop));
        }
    }

    public static final class Component {
        private final int id;
        private final Channel channel;
        private final int anchorCell;
        private final int cellCount;
        private final long[][] boundaryMasks;
        private final boolean containsFluid;
        private final boolean requiresExactCheck;

        Component(int id,
                  Channel channel,
                  int anchorCell,
                  int cellCount,
                  long[][] boundaryMasks,
                  boolean containsFluid,
                  boolean requiresExactCheck) {
            if (id < 0 || anchorCell < 0 || anchorCell >= CELL_COUNT || cellCount <= 0) {
                throw new IllegalArgumentException("invalid component identity");
            }
            this.id = id;
            this.channel = Objects.requireNonNull(channel, "channel");
            this.anchorCell = anchorCell;
            this.cellCount = cellCount;
            Objects.requireNonNull(boundaryMasks, "boundaryMasks");
            if (boundaryMasks.length != Direction.values().length) {
                throw new IllegalArgumentException("boundary mask array has the wrong length");
            }
            this.boundaryMasks = new long[boundaryMasks.length][];
            for (int index = 0; index < boundaryMasks.length; index++) {
                long[] mask = boundaryMasks[index];
                if (mask != null) {
                    if (mask.length != FACE_WORDS || isEmpty(mask)) {
                        throw new IllegalArgumentException("invalid component boundary mask");
                    }
                    this.boundaryMasks[index] = mask.clone();
                }
            }
            this.containsFluid = containsFluid;
            this.requiresExactCheck = requiresExactCheck;
        }

        public int id() {
            return id;
        }

        public Channel channel() {
            return channel;
        }

        public int anchorCell() {
            return anchorCell;
        }

        public int anchorX() {
            return x(anchorCell);
        }

        public int anchorY() {
            return y(anchorCell);
        }

        public int anchorZ() {
            return z(anchorCell);
        }

        public int cellCount() {
            return cellCount;
        }

        public boolean touches(Direction face) {
            return boundaryMasks[Objects.requireNonNull(face, "face").ordinal()] != null;
        }

        public long[] boundaryMask(Direction face) {
            long[] mask = boundaryMasks[Objects.requireNonNull(face, "face").ordinal()];
            return mask == null ? new long[FACE_WORDS] : mask.clone();
        }

        public long boundaryMaskWord(Direction face, int word) {
            if (word < 0 || word >= FACE_WORDS) {
                throw new IndexOutOfBoundsException("boundary mask word must be in [0, 3]");
            }
            long[] mask = boundaryMasks[Objects.requireNonNull(face, "face").ordinal()];
            return mask == null ? 0L : mask[word];
        }

        public int boundaryFaceMask() {
            int result = 0;
            for (Direction face : Direction.values()) {
                if (touches(face)) {
                    result |= 1 << face.ordinal();
                }
            }
            return result;
        }

        public boolean containsFluid() {
            return containsFluid;
        }

        public boolean requiresExactCheck() {
            return requiresExactCheck;
        }

        private int retainedBytes() {
            return 56 + Integer.bitCount(boundaryFaceMask()) * FACE_WORDS * Long.BYTES;
        }

        @Override
        public String toString() {
            return "Component{" + id + ", " + channel + ", cells=" + cellCount + '}';
        }
    }

    public static final class Snapshot {
        private final byte[] cells;
        private final long fingerprint;

        public Snapshot(byte[] cells) {
            Objects.requireNonNull(cells, "cells");
            if (cells.length != CELL_COUNT) {
                throw new IllegalArgumentException("snapshot must contain exactly 4096 cells");
            }
            this.cells = cells.clone();
            long hash = 0xcbf29ce484222325L;
            for (byte cell : this.cells) {
                if ((cell & ~VALID_FLAGS) != 0) {
                    throw new IllegalArgumentException("snapshot contains unknown cell flags");
                }
                hash ^= Byte.toUnsignedInt(cell);
                hash *= 0x100000001b3L;
            }
            this.fingerprint = hash;
        }

        public int flags(int index) {
            return Byte.toUnsignedInt(cells[index]);
        }

        public byte[] cells() {
            return cells.clone();
        }

        PackedFacts packedFacts() {
            return PackedFacts.fromCells(cells, fingerprint);
        }

        public long fingerprint() {
            return fingerprint;
        }
    }

    /** Compact persistent form of the four structural flags for each cell. */
    static final class PackedFacts {
        private final byte[] data;
        private final long fingerprint;

        private PackedFacts(byte[] data, long fingerprint) {
            this.data = data;
            this.fingerprint = fingerprint;
        }

        static PackedFacts fromBytes(byte[] packed) {
            Objects.requireNonNull(packed, "packed");
            if (packed.length != PACKED_FACT_BYTES) {
                throw new IllegalArgumentException("packed facts must contain exactly 2048 bytes");
            }
            byte[] copy = packed.clone();
            return new PackedFacts(copy, fingerprint(copy));
        }

        private static PackedFacts fromCells(byte[] cells, long fingerprint) {
            byte[] packed = new byte[PACKED_FACT_BYTES];
            for (int cell = 0; cell < CELL_COUNT; cell += 2) {
                packed[cell >>> 1] = (byte) ((cells[cell] & VALID_FLAGS)
                        | ((cells[cell + 1] & VALID_FLAGS) << 4));
            }
            return new PackedFacts(packed, fingerprint);
        }

        byte[] bytes() {
            return data.clone();
        }

        long fingerprint() {
            return fingerprint;
        }

        Snapshot snapshot() {
            byte[] cells = new byte[CELL_COUNT];
            for (int cell = 0; cell < CELL_COUNT; cell += 2) {
                int packed = Byte.toUnsignedInt(data[cell >>> 1]);
                cells[cell] = (byte) (packed & VALID_FLAGS);
                cells[cell + 1] = (byte) (packed >>> 4);
            }
            return new Snapshot(cells);
        }

        private static long fingerprint(byte[] data) {
            long hash = 0xcbf29ce484222325L;
            for (byte packed : data) {
                int value = Byte.toUnsignedInt(packed);
                hash ^= value & VALID_FLAGS;
                hash *= 0x100000001b3L;
                hash ^= value >>> 4;
                hash *= 0x100000001b3L;
            }
            return hash;
        }
    }
}
