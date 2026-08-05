package com.scarasol.acceleratednavigation.topology;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;

import java.util.Arrays;
import java.util.Objects;

/** One immutable macro traversal view for a section generation and normalized geometry. */
public final class BaseClusterTopology {

    public static final int SIDE = 16;
    public static final int CELL_COUNT = SIDE * SIDE * SIDE;
    static final int PACKED_FACT_BYTES = CELL_COUNT / 2;
    public static final int VOLUME_OPEN = 1;
    public static final int GROUND_OPEN = 1 << 1;
    public static final int FLUID = 1 << 2;
    public static final int EXACT_REQUIRED = 1 << 3;

    private static final int VALID_FLAGS = VOLUME_OPEN | GROUND_OPEN | FLUID | EXACT_REQUIRED;
    static final int MAX_STRUCTURAL_STEP = 1;
    static final int MAX_STRUCTURAL_JUMP = 3;
    static final int MAX_STRUCTURAL_DROP = 4;
    private static final int CENTER_HALO_INDEX = 13;
    private static final int EXTENDED_MIN = -8;
    private static final int EXTENDED_SIDE = SIDE * 2;
    private static final int PRISM_LAYERS = SIDE + 1;
    private static final int SEALED_SIDE = SIDE + 2;

    private final SectionPos section;
    private final long revision;
    private final long sourceFingerprint;
    private final GeometryKey geometry;
    private final BitStorage componentLabels;
    private final int baseLabel;
    private final int[] componentMetadata;
    private final int[] outgoingOffsets;
    private final int[] outgoingTargets;
    private final long[] outgoingCapabilityMasks;
    private final float[] outgoingLowerBounds;
    private final byte[] haloOffsets;
    private final long[] haloRevisions;
    private final long[] haloFingerprints;
    private final long signature;
    private final int retainedBytes;

    private BaseClusterTopology(SectionPos section,
                                long revision,
                                long sourceFingerprint,
                                GeometryKey geometry,
                                BitStorage componentLabels,
                                int baseLabel,
                                int[] componentMetadata,
                                int[] outgoingOffsets,
                                int[] outgoingTargets,
                                long[] outgoingCapabilityMasks,
                                float[] outgoingLowerBounds,
                                byte[] haloOffsets,
                                long[] haloRevisions,
                                long[] haloFingerprints) {
        this.section = Objects.requireNonNull(section, "section");
        this.revision = revision;
        this.sourceFingerprint = sourceFingerprint;
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.componentLabels = Objects.requireNonNull(componentLabels, "componentLabels");
        this.baseLabel = baseLabel;
        this.componentMetadata = componentMetadata;
        this.outgoingOffsets = outgoingOffsets;
        this.outgoingTargets = outgoingTargets;
        this.outgoingCapabilityMasks = outgoingCapabilityMasks;
        this.outgoingLowerBounds = outgoingLowerBounds;
        this.haloOffsets = haloOffsets;
        this.haloRevisions = haloRevisions;
        this.haloFingerprints = haloFingerprints;
        this.signature = signature(section, revision, sourceFingerprint, geometry,
                haloOffsets, haloRevisions, haloFingerprints);
        this.retainedBytes = 128 + componentLabels.getRaw().length * Long.BYTES
                + (componentMetadata.length + outgoingOffsets.length + outgoingTargets.length)
                * Integer.BYTES
                + outgoingCapabilityMasks.length * Long.BYTES
                + outgoingLowerBounds.length * Float.BYTES
                + haloOffsets.length + (haloRevisions.length + haloFingerprints.length) * Long.BYTES;
        validate();
    }

    public static BaseClusterTopology build(SectionPos section,
                                            long revision,
                                            BuildInput input,
                                            GeometryKey geometry,
                                            BuildScratch scratch) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(scratch, "scratch").reset();
        deriveLegalAnchors(input, geometry, scratch);
        int componentCount = labelComponents(geometry.channel(), scratch);
        BitStorage labels = compactLabels(scratch.labels, componentCount);
        int uniformLabel = uniformLabel(scratch.labels);
        int baseLabel = uniformLabel < 0 ? 0 : uniformLabel;
        int[] metadata = Arrays.copyOf(scratch.componentMetadata, componentCount);
        PrimitiveEdges edges = geometry.channel() == Channel.GROUND
                ? buildGroundEdges(input, geometry, scratch, componentCount)
                : PrimitiveEdges.empty(componentCount);
        HaloStamps stamps = input.usedStamps(scratch.haloUsed);
        return new BaseClusterTopology(
                section,
                revision,
                input.center().fingerprint(),
                geometry,
                labels,
                baseLabel,
                metadata,
                edges.offsets,
                edges.targets,
                edges.capabilities,
                edges.lowerBounds,
                stamps.offsets,
                stamps.revisions,
                stamps.fingerprints
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

    public GeometryKey geometry() {
        return geometry;
    }

    public int componentCount() {
        return componentMetadata.length;
    }

    public int componentAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return -1;
        }
        int label = baseLabel + componentLabels.get(cellIndex(x, y, z));
        return label == 0 ? -1 : label - 1;
    }

    public int componentAnchorCell(int componentId) {
        return metadata(componentId) & 0xfff;
    }

    public int localEdgeStart(int componentId) {
        metadata(componentId);
        return outgoingOffsets[componentId];
    }

    public int localEdgeEnd(int componentId) {
        metadata(componentId);
        return outgoingOffsets[componentId + 1];
    }

    public int localEdgeTarget(int edge) {
        return outgoingTargets[edge];
    }

    public long localEdgeCapabilities(int edge) {
        return outgoingCapabilityMasks[edge];
    }

    public float localEdgeLowerBound(int edge) {
        return outgoingLowerBounds[edge];
    }

    public boolean localEdgeSupports(int edge, MovementKey movement) {
        return (outgoingCapabilityMasks[edge] & movement.capabilityMask()) != 0L;
    }

    public int haloStampCount() {
        return haloOffsets.length;
    }

    public byte haloOffset(int index) {
        return haloOffsets[index];
    }

    public long haloRevision(int index) {
        return haloRevisions[index];
    }

    public long haloFingerprint(int index) {
        return haloFingerprints[index];
    }

    public int retainedBytes() {
        return retainedBytes;
    }

    long signature() {
        return signature;
    }

    private int metadata(int componentId) {
        if (componentId < 0 || componentId >= componentMetadata.length) {
            throw new IndexOutOfBoundsException("unknown component " + componentId);
        }
        return componentMetadata[componentId];
    }

    private static void deriveLegalAnchors(BuildInput input,
                                           GeometryKey geometry,
                                           BuildScratch scratch) {
        int minimum = minimumFootprintOffset(geometry.widthCells(), 0);
        int maximum = minimumFootprintOffset(geometry.widthCells(),
                (geometry.widthCells() & 1) == 0 ? 1 : 0) + geometry.widthCells() - 1;
        int maximumY = geometry.channel() == Channel.GROUND
                ? SIDE + geometry.heightCells() - 1
                : SIDE + geometry.heightCells() - 2;
        for (int y = 0; y <= maximumY; y++) {
            for (int z = minimum; z <= SIDE - 1 + maximum; z++) {
                int volume = 0;
                int ground = 0;
                for (int x = minimum; x <= SIDE - 1 + maximum; x++) {
                    int flags = input.flags(x, y, z, scratch);
                    if ((geometry.acceptsFluid() || (flags & FLUID) == 0)
                            && (flags & VOLUME_OPEN) != 0) {
                        volume |= 1 << (x - EXTENDED_MIN);
                    }
                    if (y < SIDE && (geometry.acceptsFluid() || (flags & FLUID) == 0)
                            && (flags & GROUND_OPEN) != 0) {
                        ground |= 1 << (x - EXTENDED_MIN);
                    }
                }
                int row = z - EXTENDED_MIN;
                scratch.volumeRows[y * EXTENDED_SIDE + row] = volume;
                if (y < SIDE) scratch.groundRows[y * EXTENDED_SIDE + row] = ground;
            }
        }

        int alignments = (geometry.widthCells() & 1) == 0 ? 4 : 1;
        for (int alignment = 0; alignment < alignments; alignment++) {
            int minX = minimumFootprintOffset(geometry.widthCells(), alignment & 1);
            int minZ = minimumFootprintOffset(geometry.widthCells(), alignment >>> 1);
            for (int y = 0; y < PRISM_LAYERS; y++) {
                erodeRows(geometry, scratch, y, minX, false);
                appendErodedLayer(scratch.prismMask, scratch.erodedRows,
                        y, minZ, geometry.widthCells());
            }
            if (geometry.channel() == Channel.GROUND) {
                for (int y = 0; y < SIDE; y++) {
                    erodeRows(geometry, scratch, y, minX, true);
                    appendErodedLayer(scratch.legalMask, scratch.erodedRows,
                            y, minZ, geometry.widthCells());
                }
            }
        }
        if (geometry.channel() == Channel.VOLUME) {
            System.arraycopy(scratch.prismMask, 0, scratch.legalMask, 0,
                    scratch.legalMask.length);
        }
        if (geometry.widthCells() == 1 && geometry.heightCells() == 1) {
            removeSealedCells(input, scratch);
        }
    }

    /**
     * Bits 1..16 represent the center section x coordinates; bits 0 and 17 are
     * the west/east halo. The row grid uses the same one-cell shell for y and z.
     */
    private static void removeSealedCells(BuildInput input, BuildScratch scratch) {
        input.populateFullCollisionRows(scratch);
        for (int y = 0; y < SIDE; y++) {
            for (int z = 0; z < SIDE; z++) {
                int row = (y + 1) * SEALED_SIDE + z + 1;
                int full = scratch.fullCollisionRows[row];
                int sealed = (full << 1) & (full >>> 1)
                        & scratch.fullCollisionRows[row - 1]
                        & scratch.fullCollisionRows[row + 1]
                        & scratch.fullCollisionRows[row - SEALED_SIDE]
                        & scratch.fullCollisionRows[row + SEALED_SIDE];
                int word = (y << 2) + (z >>> 2);
                int shift = (z & 3) << 4;
                int legal = (int) (scratch.legalMask[word] >>> shift) & 0xffff;
                int sealedAnchors = (sealed >>> 1) & legal & 0xffff;
                scratch.legalMask[word] &= ~((long) sealedAnchors << shift);
            }
        }
    }

    private static int minimumFootprintOffset(int width, int positiveEvenAlignment) {
        return (width & 1) == 1 ? -(width >>> 1) : -(width >>> 1) + positiveEvenAlignment;
    }

    private static void erodeRows(GeometryKey geometry,
                                  BuildScratch scratch,
                                  int anchorY,
                                  int minX,
                                  boolean ground) {
        for (int row = 0; row < EXTENDED_SIDE; row++) {
            int open = -1;
            for (int dy = 0; dy < geometry.heightCells(); dy++) {
                open &= scratch.volumeRows[(anchorY + dy) * EXTENDED_SIDE + row];
            }
            if (ground) open &= scratch.groundRows[anchorY * EXTENDED_SIDE + row];
            int anchors = 0xffff;
            for (int dx = 0; dx < geometry.widthCells(); dx++) {
                anchors &= open >>> (minX + dx - EXTENDED_MIN);
            }
            scratch.erodedRows[row] = anchors & 0xffff;
        }
    }

    private static void appendErodedLayer(long[] destination,
                                          int[] rows,
                                          int y,
                                          int minZ,
                                          int width) {
        int layer = y << 2;
        for (int z = 0; z < SIDE; z++) {
            int anchors = 0xffff;
            for (int dz = 0; dz < width; dz++) {
                anchors &= rows[z + minZ + dz - EXTENDED_MIN];
            }
            destination[layer + (z >>> 2)] |= (long) anchors << ((z & 3) << 4);
        }
    }

    private static int labelComponents(Channel channel, BuildScratch scratch) {
        int componentCount = 0;
        for (int candidate = 0; candidate < CELL_COUNT; candidate++) {
            if (!isSet(scratch.legalMask, candidate) || scratch.labels[candidate] != 0) {
                continue;
            }
            if (componentCount >= Character.MAX_VALUE) {
                throw new IllegalStateException("too many topology components");
            }
            int head = 0;
            int tail = 0;
            scratch.queue[tail++] = candidate;
            scratch.labels[candidate] = (char) (componentCount + 1);
            while (head < tail) {
                int cell = scratch.queue[head++];
                int x = x(cell);
                int y = y(cell);
                int z = z(cell);
                tail = enqueue(x - 1, y, z, componentCount, scratch, tail);
                tail = enqueue(x + 1, y, z, componentCount, scratch, tail);
                tail = enqueue(x, y, z - 1, componentCount, scratch, tail);
                tail = enqueue(x, y, z + 1, componentCount, scratch, tail);
                if (channel == Channel.VOLUME) {
                    tail = enqueue(x, y - 1, z, componentCount, scratch, tail);
                    tail = enqueue(x, y + 1, z, componentCount, scratch, tail);
                }
            }
            scratch.componentMetadata[componentCount] = candidate;
            componentCount++;
        }
        return componentCount;
    }

    private static int enqueue(int x,
                               int y,
                               int z,
                               int componentId,
                               BuildScratch scratch,
                               int tail) {
        if (!inBounds(x, y, z)) {
            return tail;
        }
        int cell = cellIndex(x, y, z);
        if (isSet(scratch.legalMask, cell) && scratch.labels[cell] == 0) {
            scratch.labels[cell] = (char) (componentId + 1);
            scratch.queue[tail++] = cell;
        }
        return tail;
    }

    private static PrimitiveEdges buildGroundEdges(BuildInput input,
                                                    GeometryKey geometry,
                                                    BuildScratch scratch,
                                                    int componentCount) {
        Long2LongOpenHashMap merged = scratch.edgeMap;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int encoded = scratch.labels[cell];
            if (encoded == 0) {
                continue;
            }
            int from = encoded - 1;
            int x = x(cell);
            int y = y(cell);
            int z = z(cell);
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                for (int horizontal = 1; horizontal <= MAX_STRUCTURAL_JUMP; horizontal++) {
                    int nx = x + direction.getStepX() * horizontal;
                    int nz = z + direction.getStepZ() * horizontal;
                    if (nx < 0 || nx >= SIDE || nz < 0 || nz >= SIDE) {
                        break;
                    }
                    for (int dy = -MAX_STRUCTURAL_DROP; dy <= MAX_STRUCTURAL_STEP; dy++) {
                        int ny = y + dy;
                        if (ny < 0 || ny >= SIDE) {
                            continue;
                        }
                        int targetLabel = scratch.labels[cellIndex(nx, ny, nz)];
                        if (targetLabel == 0 || targetLabel - 1 == from) {
                            continue;
                        }
                        int jumpClass = horizontal - 1;
                        int requiredStep = Math.max(0, dy);
                        int requiredDrop = Math.max(0, -dy);
                        if (!movementEnvelopeOpen(
                                x,
                                y,
                                z,
                                direction,
                                horizontal,
                                dy,
                                scratch
                        )) {
                            continue;
                        }
                        long capabilityMask = supportingCapabilities(
                                requiredStep,
                                jumpClass,
                                requiredDrop
                        );
                        if (capabilityMask == 0L) {
                            continue;
                        }
                        int target = targetLabel - 1;
                        long key = ((long) from << 32) | Integer.toUnsignedLong(target);
                        float cost = (float) Math.sqrt(horizontal * horizontal + dy * dy);
                        long old = merged.getOrDefault(key, 0L);
                        long capabilities = (old & 0x3fff_ffffL) | capabilityMask;
                        float oldCost = old == 0L
                                ? Float.POSITIVE_INFINITY
                                : Float.intBitsToFloat((int) (old >>> 32));
                        merged.put(key,
                                ((long) Float.floatToRawIntBits(Math.min(oldCost, cost)) << 32)
                                        | capabilities);
                    }
                }
            }
        }
        long[] keys = merged.keySet().toLongArray();
        Arrays.sort(keys);
        int[] offsets = new int[componentCount + 1];
        for (long key : keys) {
            offsets[(int) (key >>> 32) + 1]++;
        }
        for (int index = 1; index < offsets.length; index++) {
            offsets[index] += offsets[index - 1];
        }
        int[] targets = new int[keys.length];
        long[] capabilities = new long[keys.length];
        float[] lowerBounds = new float[keys.length];
        for (int index = 0; index < keys.length; index++) {
            long key = keys[index];
            long value = merged.get(key);
            targets[index] = (int) key;
            capabilities[index] = value & 0x3fff_ffffL;
            lowerBounds[index] = Float.intBitsToFloat((int) (value >>> 32));
        }
        return new PrimitiveEdges(offsets, targets, capabilities, lowerBounds);
    }

    private static boolean movementEnvelopeOpen(int x,
                                                int y,
                                                int z,
                                                Direction direction,
                                                int horizontal,
                                                int dy,
                                                BuildScratch scratch) {
        if (dy < 0) {
            int landingX = x + direction.getStepX() * horizontal;
            int landingZ = z + direction.getStepZ() * horizontal;
            for (int shaftY = y - 1; shaftY > y + dy; shaftY--) {
                if (!prismOpen(landingX, shaftY, landingZ, scratch)) {
                    return false;
                }
            }
        }
        for (int distance = 1; distance < horizontal; distance++) {
            int intermediateY = y + Math.floorDiv(dy * distance, horizontal);
            int intermediateX = x + direction.getStepX() * distance;
            int intermediateZ = z + direction.getStepZ() * distance;
            if (!prismOpen(intermediateX, intermediateY, intermediateZ, scratch)
                    && !prismOpen(intermediateX, intermediateY + 1, intermediateZ, scratch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean prismOpen(int anchorX,
                                     int anchorY,
                                     int anchorZ,
                                     BuildScratch scratch) {
        if (!inBounds(anchorX, 0, anchorZ) || anchorY < 0 || anchorY >= PRISM_LAYERS) return false;
        int cell = (anchorY << 8) | (anchorZ << 4) | anchorX;
        return isSet(scratch.prismMask, cell);
    }

    static long supportingCapabilities(int step, int jumpClass, int drop) {
        long result = 0L;
        for (int candidateStep = step; candidateStep <= MAX_STRUCTURAL_STEP; candidateStep++) {
            for (int candidateJump = jumpClass; candidateJump < 3; candidateJump++) {
                for (int candidateDrop = drop; candidateDrop <= MAX_STRUCTURAL_DROP; candidateDrop++) {
                    result |= 1L << new MovementKey(
                            candidateStep,
                            candidateJump,
                            candidateDrop
                    ).capabilityBit();
                }
            }
        }
        return result;
    }

    private static BitStorage compactLabels(char[] labels, int componentCount) {
        int uniform = uniformLabel(labels);
        if (uniform >= 0) {
            return new ZeroBitStorage(CELL_COUNT);
        }
        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(componentCount);
        BitStorage packed = new SimpleBitStorage(bits, CELL_COUNT);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            packed.set(cell, labels[cell]);
        }
        return packed;
    }

    private static int uniformLabel(char[] labels) {
        int first = labels[0];
        for (int cell = 1; cell < labels.length; cell++) {
            if (labels[cell] != first) {
                return -1;
            }
        }
        return first;
    }

    private void validate() {
        if (componentLabels.getSize() != CELL_COUNT
                || outgoingOffsets.length != componentMetadata.length + 1
                || outgoingTargets.length != outgoingCapabilityMasks.length
                || outgoingTargets.length != outgoingLowerBounds.length
                || haloOffsets.length != haloRevisions.length
                || haloOffsets.length != haloFingerprints.length) {
            throw new IllegalArgumentException("inconsistent primitive topology arrays");
        }
        for (int edge = 0; edge < outgoingTargets.length; edge++) {
            if (outgoingTargets[edge] < 0 || outgoingTargets[edge] >= componentMetadata.length
                    || outgoingCapabilityMasks[edge] == 0L
                    || !Float.isFinite(outgoingLowerBounds[edge])
                    || outgoingLowerBounds[edge] < 0.0F) {
                throw new IllegalArgumentException("invalid local topology edge");
            }
        }
    }

    private static boolean isSet(long[] mask, int cell) {
        return (mask[cell >>> 6] & (1L << cell)) != 0L;
    }

    private static boolean inBounds(int x, int y, int z) {
        return (x | y | z) >= 0 && x < SIDE && y < SIDE && z < SIDE;
    }

    public static int cellIndex(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("cell coordinates must be in [0, 15]");
        }
        return (y << 8) | (z << 4) | x;
    }

    public static int x(int cell) {
        return cell & 15;
    }

    public static int y(int cell) {
        return (cell >>> 8) & 15;
    }

    public static int z(int cell) {
        return (cell >>> 4) & 15;
    }

    private static int faceIndex(int u, int v) {
        return (v << 4) | u;
    }

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public enum Channel {
        GROUND,
        VOLUME
    }

    public record GeometryKey(Channel channel,
                              int widthCells,
                              int heightCells,
                              boolean acceptsFluid) {
        public GeometryKey {
            Objects.requireNonNull(channel, "channel");
            if (widthCells < 1 || widthCells > SIDE || heightCells < 1 || heightCells > SIDE) {
                throw new IllegalArgumentException("unsupported macro geometry");
            }
        }
    }

    public record MovementKey(int maxStep, int jumpClass, int maxDrop) {
        public MovementKey {
            if (maxStep < 0 || maxStep > MAX_STRUCTURAL_STEP
                    || jumpClass < 0 || jumpClass > 2
                    || maxDrop < 0 || maxDrop > MAX_STRUCTURAL_DROP) {
                throw new IllegalArgumentException("unsupported macro movement");
            }
        }

        public int capabilityBit() {
            return (maxStep * 3 + jumpClass) * 5 + maxDrop;
        }

        public long capabilityMask() {
            return 1L << capabilityBit();
        }
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

        public GeometryKey geometry(Channel channel) {
            int widthCells = (int) Math.ceil(width);
            int heightCells = (int) Math.ceil(height);
            return new GeometryKey(channel, widthCells, heightCells, acceptsFluid);
        }

        public MovementKey movement(Channel channel) {
            if (channel == Channel.VOLUME) {
                return new MovementKey(0, 0, 0);
            }
            int jumpClass = maxJump <= 1 ? 0 : maxJump - 1;
            return new MovementKey(maxStep, jumpClass, maxDrop);
        }
    }

    /** Immutable facts and sparse loaded-neighbour stamps supplied to the worker. */
    static final class BuildInput {
        private final PackedFacts center;
        private final byte[] offsets;
        private final PackedFacts[] facts;
        private final long[] revisions;
        private final long[] fingerprints;

        BuildInput(PackedFacts center,
                   byte[] offsets,
                   PackedFacts[] facts,
                   long[] revisions,
                   long[] fingerprints) {
            this.center = Objects.requireNonNull(center, "center");
            this.offsets = offsets.clone();
            this.facts = facts.clone();
            this.revisions = revisions.clone();
            this.fingerprints = fingerprints.clone();
            if (offsets.length != facts.length || offsets.length != revisions.length
                    || offsets.length != fingerprints.length || offsets.length > 26) {
                throw new IllegalArgumentException("inconsistent halo facts");
            }
            int previous = -1;
            for (byte encoded : this.offsets) {
                int value = Byte.toUnsignedInt(encoded);
                if (value == CENTER_HALO_INDEX || value <= previous || value > 26) {
                    throw new IllegalArgumentException("halo offsets must be unique and sorted");
                }
                previous = value;
            }
        }

        static BuildInput center(PackedFacts facts) {
            return new BuildInput(facts, new byte[0], new PackedFacts[0], new long[0], new long[0]);
        }

        PackedFacts center() {
            return center;
        }

        int flags(int x, int y, int z, BuildScratch scratch) {
            int sectionX = Math.floorDiv(x, SIDE);
            int sectionY = Math.floorDiv(y, SIDE);
            int sectionZ = Math.floorDiv(z, SIDE);
            if (sectionX == 0 && sectionY == 0 && sectionZ == 0) {
                int flags = center.flags(cellIndex(x, y, z));
                if (y == 0 && (flags & VOLUME_OPEN) != 0) {
                    int lowerIndex = Arrays.binarySearch(offsets,
                            (byte) haloIndex(0, -1, 0));
                    if (lowerIndex >= 0) {
                        scratch.haloUsed[lowerIndex] = true;
                        int below = facts[lowerIndex].flags(cellIndex(x, SIDE - 1, z));
                        if ((below & VOLUME_OPEN) == 0 || (below & EXACT_REQUIRED) != 0) {
                            flags |= GROUND_OPEN;
                        }
                    }
                }
                return flags;
            }
            if (Math.abs(sectionX) > 1 || Math.abs(sectionY) > 1 || Math.abs(sectionZ) > 1) {
                return 0;
            }
            int encoded = haloIndex(sectionX, sectionY, sectionZ);
            int index = Arrays.binarySearch(offsets, (byte) encoded);
            if (index < 0) {
                return 0;
            }
            scratch.haloUsed[index] = true;
            return facts[index].flags(cellIndex(
                    Math.floorMod(x, SIDE),
                    Math.floorMod(y, SIDE),
                    Math.floorMod(z, SIDE)
            ));
        }

        private void populateFullCollisionRows(BuildScratch scratch) {
            for (int y = 0; y < SIDE; y++) {
                for (int z = 0; z < SIDE; z++) {
                    int open = scratch.volumeRows[y * EXTENDED_SIDE + z - EXTENDED_MIN];
                    int full = (~(open >>> -EXTENDED_MIN)) & 0xffff;
                    scratch.fullCollisionRows[(y + 1) * SEALED_SIDE + z + 1] = full << 1;
                }
            }
            for (Direction face : Direction.values()) {
                copyFullCollisionFace(face, scratch);
            }
        }

        private void copyFullCollisionFace(Direction face, BuildScratch scratch) {
            int index = Arrays.binarySearch(offsets, (byte) haloIndex(
                    face.getStepX(), face.getStepY(), face.getStepZ()
            ));
            if (index < 0) {
                return;
            }
            scratch.haloUsed[index] = true;
            PackedFacts source = facts[index];
            for (int first = 0; first < SIDE; first++) {
                for (int second = 0; second < SIDE; second++) {
                    int sourceX;
                    int sourceY;
                    int sourceZ;
                    int targetX;
                    int targetY;
                    int targetZ;
                    switch (face.getAxis()) {
                        case X -> {
                            sourceX = face == Direction.WEST ? SIDE - 1 : 0;
                            sourceY = first;
                            sourceZ = second;
                            targetX = face == Direction.WEST ? -1 : SIDE;
                            targetY = first;
                            targetZ = second;
                        }
                        case Y -> {
                            sourceX = first;
                            sourceY = face == Direction.DOWN ? SIDE - 1 : 0;
                            sourceZ = second;
                            targetX = first;
                            targetY = face == Direction.DOWN ? -1 : SIDE;
                            targetZ = second;
                        }
                        case Z -> {
                            sourceX = first;
                            sourceY = second;
                            sourceZ = face == Direction.NORTH ? SIDE - 1 : 0;
                            targetX = first;
                            targetY = second;
                            targetZ = face == Direction.NORTH ? -1 : SIDE;
                        }
                        default -> throw new IllegalStateException("unknown direction axis");
                    }
                    if ((source.flags(cellIndex(sourceX, sourceY, sourceZ))
                            & VOLUME_OPEN) == 0) {
                        scratch.fullCollisionRows[(targetY + 1) * SEALED_SIDE + targetZ + 1]
                                |= 1 << (targetX + 1);
                    }
                }
            }
        }

        HaloStamps usedStamps(boolean[] used) {
            int count = 0;
            for (int index = 0; index < offsets.length; index++) {
                if (used[index]) count++;
            }
            byte[] usedOffsets = new byte[count];
            long[] usedRevisions = new long[count];
            long[] usedFingerprints = new long[count];
            int cursor = 0;
            for (int index = 0; index < offsets.length; index++) {
                if (!used[index]) continue;
                usedOffsets[cursor] = offsets[index];
                usedRevisions[cursor] = revisions[index];
                usedFingerprints[cursor] = fingerprints[index];
                cursor++;
            }
            return new HaloStamps(usedOffsets, usedRevisions, usedFingerprints);
        }

    }

    /** Reused by the single topology worker; never retained by a published graph. */
    static final class BuildScratch {
        private static final int MAX_PARENT_NODES = CELL_COUNT * 8;
        private final char[] labels = new char[CELL_COUNT];
        private final int[] queue = new int[CELL_COUNT];
        private final int[] componentMetadata = new int[CELL_COUNT];
        private final long[] legalMask = new long[CELL_COUNT / Long.SIZE];
        private final long[] prismMask = new long[PRISM_LAYERS * SIDE * SIDE / Long.SIZE];
        private final int[] volumeRows = new int[(SIDE * 2) * EXTENDED_SIDE];
        private final int[] groundRows = new int[SIDE * EXTENDED_SIDE];
        private final int[] erodedRows = new int[EXTENDED_SIDE];
        private final int[] fullCollisionRows = new int[SEALED_SIDE * SEALED_SIDE];
        private final boolean[] haloUsed = new boolean[26];
        private final Long2LongOpenHashMap edgeMap = new Long2LongOpenHashMap();
        final int[] parentCounts = new int[MAX_PARENT_NODES + 1];
        final int[] parentCursors = new int[MAX_PARENT_NODES + 1];
        final int[] parentOrder = new int[MAX_PARENT_NODES];
        final int[] parentStackNodes = new int[MAX_PARENT_NODES];
        final int[] parentStackEdges = new int[MAX_PARENT_NODES];
        final int[] parentComponents = new int[MAX_PARENT_NODES];
        final boolean[] parentVisited = new boolean[MAX_PARENT_NODES];

        private void reset() {
            Arrays.fill(labels, (char) 0);
            Arrays.fill(legalMask, 0L);
            Arrays.fill(prismMask, 0L);
            Arrays.fill(volumeRows, 0);
            Arrays.fill(groundRows, 0);
            Arrays.fill(fullCollisionRows, 0);
            Arrays.fill(haloUsed, false);
            edgeMap.clear();
        }

        int retainedBytes() {
            return labels.length * Character.BYTES + queue.length * Integer.BYTES
                    + componentMetadata.length * Integer.BYTES
                    + (legalMask.length + prismMask.length) * Long.BYTES
                    + (volumeRows.length + groundRows.length + erodedRows.length
                    + fullCollisionRows.length) * Integer.BYTES
                    + haloUsed.length + (parentCounts.length + parentCursors.length
                    + parentOrder.length + parentStackNodes.length + parentStackEdges.length
                    + parentComponents.length) * Integer.BYTES + parentVisited.length;
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
            this.fingerprint = fingerprintCells(this.cells);
        }

        public int flags(int index) {
            return Byte.toUnsignedInt(cells[index]);
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
        private final int uniformFlags;
        private final long fingerprint;

        private PackedFacts(byte[] data, int uniformFlags, long fingerprint) {
            this.data = data;
            this.uniformFlags = uniformFlags;
            this.fingerprint = fingerprint;
        }

        static PackedFacts fromBytes(byte[] packed) {
            Objects.requireNonNull(packed, "packed");
            if (packed.length != PACKED_FACT_BYTES) {
                throw new IllegalArgumentException("packed facts must contain exactly 2048 bytes");
            }
            byte[] copy = packed.clone();
            return new PackedFacts(copy, -1, fingerprintPacked(copy));
        }

        static PackedFacts allAir() {
            byte[] cells = new byte[CELL_COUNT];
            Arrays.fill(cells, (byte) VOLUME_OPEN);
            return new PackedFacts(null, VOLUME_OPEN, fingerprintCells(cells));
        }

        private static PackedFacts fromCells(byte[] cells, long fingerprint) {
            byte[] packed = new byte[PACKED_FACT_BYTES];
            for (int cell = 0; cell < CELL_COUNT; cell += 2) {
                packed[cell >>> 1] = (byte) ((cells[cell] & VALID_FLAGS)
                        | ((cells[cell + 1] & VALID_FLAGS) << 4));
            }
            return new PackedFacts(packed, -1, fingerprint);
        }

        int flags(int cell) {
            if (uniformFlags >= 0) {
                return uniformFlags;
            }
            int packed = Byte.toUnsignedInt(data[cell >>> 1]);
            return (cell & 1) == 0 ? packed & VALID_FLAGS : packed >>> 4;
        }

        byte[] bytes() {
            if (data != null) {
                return data.clone();
            }
            byte packed = (byte) (uniformFlags | (uniformFlags << 4));
            byte[] expanded = new byte[PACKED_FACT_BYTES];
            Arrays.fill(expanded, packed);
            return expanded;
        }

        long fingerprint() {
            return fingerprint;
        }

        boolean isAllAir() {
            return uniformFlags == VOLUME_OPEN;
        }

        int retainedBytes() {
            return data == null ? 32 : 32 + data.length;
        }

    }

    private static long fingerprintCells(byte[] cells) {
        long hash = 0xcbf29ce484222325L;
        for (byte cell : cells) {
            if ((cell & ~VALID_FLAGS) != 0) {
                throw new IllegalArgumentException("snapshot contains unknown cell flags");
            }
            hash ^= Byte.toUnsignedInt(cell);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long fingerprintPacked(byte[] data) {
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

    private static long signature(SectionPos section,
                                  long revision,
                                  long fingerprint,
                                  GeometryKey geometry,
                                  byte[] offsets,
                                  long[] revisions,
                                  long[] fingerprints) {
        long hash = 0xcbf29ce484222325L;
        hash = (hash ^ section.asLong()) * 0x100000001b3L;
        hash = (hash ^ revision) * 0x100000001b3L;
        hash = (hash ^ fingerprint) * 0x100000001b3L;
        hash = (hash ^ geometry.hashCode()) * 0x100000001b3L;
        for (int index = 0; index < offsets.length; index++) {
            hash = (hash ^ Byte.toUnsignedInt(offsets[index])) * 0x100000001b3L;
            hash = (hash ^ revisions[index]) * 0x100000001b3L;
            hash = (hash ^ fingerprints[index]) * 0x100000001b3L;
        }
        return hash;
    }

    static int haloIndex(int x, int y, int z) {
        return (x + 1) * 9 + (y + 1) * 3 + z + 1;
    }

    static int haloX(int encoded) {
        return encoded / 9 - 1;
    }

    static int haloY(int encoded) {
        return encoded / 3 % 3 - 1;
    }

    static int haloZ(int encoded) {
        return encoded % 3 - 1;
    }

    private record PrimitiveEdges(int[] offsets,
                                  int[] targets,
                                  long[] capabilities,
                                  float[] lowerBounds) {
        private static PrimitiveEdges empty(int componentCount) {
            return new PrimitiveEdges(
                    new int[componentCount + 1],
                    new int[0],
                    new long[0],
                    new float[0]
            );
        }
    }

    private record HaloStamps(byte[] offsets, long[] revisions, long[] fingerprints) {
    }
}
