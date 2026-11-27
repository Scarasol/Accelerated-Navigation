package com.scarasol.acceleratednavigation.topology;

import com.google.gson.stream.JsonWriter;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;

/** One immutable macro traversal view for a section generation and normalized geometry. */
public final class BaseClusterTopology {

    public static final int SIDE = 16;
    public static final int CELL_COUNT = SIDE * SIDE * SIDE;
    static final int PACKED_FACT_BYTES = CELL_COUNT / 2;
    static final int FACTS_ALGORITHM_VERSION = 7;
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
    private final long[] componentMetadata;
    private final int[] outgoingOffsets;
    private final int[] outgoingTargets;
    private final long[] outgoingCapabilityMasks;
    private final float[] outgoingLowerBounds;
    private final byte[] haloOffsets;
    private final long[] haloRevisions;
    private final long[] haloFingerprints;
    private final BuildInput boundaryFacts;
    private final BoundaryPortBits portBits;
    BoundaryPortBits portBits() { return portBits; }
    final GraphReuse.Rows reusableRows;
    private final long signature;
    private final int retainedBytes;
    private volatile IncomingEdges incomingEdges;

    private BaseClusterTopology(SectionPos section,
                                long revision,
                                long sourceFingerprint,
                                GeometryKey geometry,
                                BitStorage componentLabels,
                                int baseLabel,
                                long[] componentMetadata,
                                int[] outgoingOffsets,
                                int[] outgoingTargets,
                                long[] outgoingCapabilityMasks,
                                float[] outgoingLowerBounds,
                                byte[] haloOffsets,
                                long[] haloRevisions,
                                long[] haloFingerprints,
                                BuildInput boundaryFacts, BoundaryPortBits portBits, GraphReuse.Rows reusableRows) {
        this.section = Objects.requireNonNull(section, "section");
        this.revision = revision;
        this.sourceFingerprint = sourceFingerprint;
        this.geometry = geometry;
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
        this.boundaryFacts = Objects.requireNonNull(boundaryFacts, "boundaryFacts");
        this.portBits = portBits;
        this.reusableRows = reusableRows;
        this.signature = signature(section, revision, sourceFingerprint, geometry,
                haloOffsets, haloRevisions, haloFingerprints);
        this.retainedBytes = 128 + labelBytes(componentLabels)
                + componentMetadata.length * Long.BYTES
                + (outgoingOffsets.length + outgoingTargets.length) * Integer.BYTES
                + outgoingCapabilityMasks.length * Long.BYTES
                + outgoingLowerBounds.length * Float.BYTES
                + haloOffsets.length + (haloRevisions.length + haloFingerprints.length) * Long.BYTES
                + boundaryFacts.referenceBytes() + (portBits == null ? 0 : portBits.retainedBytes())
                + (reusableRows == null ? 0 : reusableRows.bytes())
                + IncomingEdges.reservedBytes(componentCount(), outgoingTargets.length);
        validate();
    }

    private BaseClusterTopology(BaseClusterTopology donor, GeometryKey target) {
        section = donor.section;
        revision = donor.revision;
        sourceFingerprint = donor.sourceFingerprint;
        geometry = target;
        componentLabels = donor.componentLabels;
        baseLabel = donor.baseLabel;
        componentMetadata = donor.componentMetadata;
        outgoingOffsets = donor.outgoingOffsets;
        outgoingTargets = donor.outgoingTargets;
        outgoingCapabilityMasks = donor.outgoingCapabilityMasks;
        outgoingLowerBounds = donor.outgoingLowerBounds;
        haloOffsets = donor.haloOffsets;
        haloRevisions = donor.haloRevisions;
        haloFingerprints = donor.haloFingerprints;
        boundaryFacts = donor.boundaryFacts;
        portBits = donor.portBits;
        reusableRows = new GraphReuse.Rows(donor.reusableRows, target);
        signature = signature(section, revision, sourceFingerprint, target,
                haloOffsets, haloRevisions, haloFingerprints);
        // Charge the full backing store to both views so either can outlive the other.
        retainedBytes = donor.retainedBytes;
    }

    String topologySharingGap(GeometryKey target) {
        if (reusableRows == null || target.heightCells() != geometry.heightCells()) return "ROW_DOMAIN";
        for (int sy = -1; sy <= 1; sy++) {
            PackedFacts facts = boundaryFacts.atSection(0, sy, 0);
            if (facts != null && facts.geometry().hasDoors()) return "DOOR_COLUMN";
        }
        // A one-cell ground footprint reads center/upper volume rows and center
        // ground support. Without a door in that column only exact volume and
        // fluid flags can change these rows. Labels, ports and local edges then
        // depend solely on the identical rows and the common cell dimensions.
        for (int sy = 0; sy <= 1; sy++) {
            PackedFacts facts = boundaryFacts.atSection(0, sy, 0);
            if (facts == null) continue;
            int usedLayers = sy == 0 ? 0xffff : (1 << target.heightCells()) - 1;
            if (target.acceptsFluid() != geometry.acceptsFluid()
                    && (facts.fluidLayers & usedLayers) != 0) return "FLUID_ROWS";
            if (target.width() == geometry.width() && target.height() == geometry.height()) continue;
            for (int layers = facts.exactOpenLayers & usedLayers; layers != 0; layers &= layers - 1) {
                int y = Integer.numberOfTrailingZeros(layers);
                for (int z = 0; z < SIDE; z++) {
                    int row = y * SIDE + z;
                    long packed = facts.packedRow(row);
                    long checks = packed & (packed >>> 3) & 0x1111111111111111L;
                    if (!target.acceptsFluid()) checks &= ~(packed >>> 2);
                    int donorVolume = Short.toUnsignedInt(reusableRows.volume[sy * CELL_COUNT / SIDE + row]);
                    for (; checks != 0; checks &= checks - 1) {
                        int x = Long.numberOfTrailingZeros(checks) >>> 2;
                        // The candidate body is contained in the donor body.
                        if ((donorVolume & (1 << x)) != 0) continue;
                        boolean occupied = facts.geometry().occupied(row * SIDE + x,
                                target.width(), target.height(), target.canOpenDoors());
                        if (!occupied) return "VOLUME_ROWS";
                    }
                }
            }
        }
        return null;
    }

    BaseClusterTopology sharedTopology(GeometryKey target) {
        return new BaseClusterTopology(this, target);
    }

    boolean matchesLocalRepairFacts(BuildInput input, GeometryKey target) {
        return boundaryFacts.matchesBoundaryProjection(input, target);
    }

    BaseClusterTopology repairLocal(BuildInput input, GeometryKey target, BuildScratch scratch,
                                    GraphReuse reuse, String dimension) {
        return new LocalRepair(this, input, target, scratch).finish(reuse, dimension);
    }

    private static int labelBytes(BitStorage labels) {
        return labels instanceof PatchedLabels patch ? patch.retainedBytes : labels.getRaw().length * Long.BYTES;
    }

    private static final class PatchedLabels implements BitStorage {
        private final BitStorage old;
        private final int oldBase;
        private final int[] remap;
        private final Int2IntOpenHashMap changes;
        private final int bits;
        private final int retainedBytes;

        PatchedLabels(BaseClusterTopology donor, int[] remap, Int2IntOpenHashMap changes, int count) {
            old = donor.componentLabels;
            oldBase = donor.baseLabel;
            this.remap = remap;
            this.changes = changes;
            bits = Integer.SIZE - Integer.numberOfLeadingZeros(count);
            retainedBytes = 48 + labelBytes(old) + 4 * remap.length + 24 * changes.size();
        }
        @Override public int get(int cell) {
            int changed = changes.getOrDefault(cell, -1);
            if (changed >= 0) return changed;
            int encoded = oldBase + old.get(cell);
            return encoded == 0 ? 0 : remap[encoded - 1];
        }
        @Override public int getBits() { return bits; }
        @Override public int getSize() { return CELL_COUNT; }
        @Override public int getAndSet(int cell, int value) { throw new UnsupportedOperationException(); }
        @Override public void set(int cell, int value) { throw new UnsupportedOperationException(); }
        @Override public void getAll(IntConsumer sink) {
            for (int cell = 0; cell < CELL_COUNT; cell++) sink.accept(get(cell));
        }
        @Override public void unpack(int[] output) {
            for (int cell = 0; cell < CELL_COUNT; cell++) output[cell] = get(cell);
        }
        @Override public BitStorage copy() {
            if (bits == 0) return new ZeroBitStorage(CELL_COUNT);
            var copy = new SimpleBitStorage(bits, CELL_COUNT);
            for (int cell = 0; cell < CELL_COUNT; cell++) copy.set(cell, get(cell));
            return copy;
        }
        @Override public long[] getRaw() { return copy().getRaw(); }
    }

    private static final class LocalRepair {
        private final BaseClusterTopology donor;
        private final GeometryKey target;
        private final BuildInput input;
        private final BuildScratch scratch;
        private final BoundaryGeometry collision;
        private final GraphReuse.Rows old;
        private final short[] volume, ground, prism, legal;
        private final BitSet prismInputs = new BitSet(), deltaPrism = new BitSet(), deltaLegal = new BitSet();
        private final BitSet flatDoorSources = new BitSet(), moveSources = new BitSet();
        private final BitSet dirty = new BitSet(), repairedCells = new BitSet();
        private final ArrayList<Long> gainedFlat = new ArrayList<>();
        private final int[] parent;
        private final int[] nodeLabel;
        private final int count;
        private long rawChecks, flatChecks, moveChecks, moveSourceCount, pairSourceCount, reusedEdges, stableComponents;

        LocalRepair(BaseClusterTopology donor, BuildInput input, GeometryKey target, BuildScratch scratch) {
            this.donor = donor;
            this.input = input;
            this.target = target;
            this.scratch = scratch;
            collision = input.hasDoors ? new BoundaryGeometry(input) : null;
            old = donor.reusableRows;
            volume = old.volume.clone(); ground = old.ground.clone();
            prism = old.prism.clone(); legal = old.legal.clone();
            count = donor.componentCount();
            parent = new int[count + CELL_COUNT];
            nodeLabel = new int[parent.length];
        }

        BaseClusterTopology finish(GraphReuse reuse, String dimension) {
            patchRows();
            findDirtyComponents();
            connectFragments();
            long[] metadata = packComponents();
            var changes = new Int2IntOpenHashMap();
            for (int c = repairedCells.nextSetBit(0); c >= 0; c = repairedCells.nextSetBit(c + 1))
                changes.put(c, bit(legal, c) ? targetLabel(c) : 0);
            int[] remap = Arrays.copyOf(nodeLabel, count);
            var labels = new PatchedLabels(donor, remap, changes, metadata.length);
            var edges = patchEdges(metadata.length);
            var rows = new GraphReuse.Rows(old, target, volume, ground, prism, legal);
            reuse.baseChoice("PATCHED_TOPOLOGY", dimension, donor.section, donor.revision, target, old);
            if (reuse.diagnostics()) {
                reuse.mark("base.PATCHED_TOPOLOGY", 1);
                reuse.mark("base.PATCH_REUSED_COMPONENTS", stableComponents);
                reuse.mark("base.PATCH_REUSED_EDGES", reusedEdges);
                reuse.mark("base.PATCH_RAW_CHECKS", rawChecks);
                reuse.mark("base.PATCH_CHANGED_ANCHORS", deltaLegal.cardinality());
                reuse.mark("base.PATCH_CHANGED_PRISM", deltaPrism.cardinality());
                reuse.mark("base.PATCH_RELABEL_CELLS", repairedCells.cardinality());
                reuse.mark("base.PATCH_DIRTY_COMPONENTS", dirty.cardinality());
                reuse.mark("base.PATCH_FLAT_CHECKS", flatChecks);
                reuse.mark("base.PATCH_MOVE_SOURCES", moveSourceCount);
                reuse.mark("base.PATCH_MOVE_CHECKS", moveChecks);
                reuse.mark("base.PATCH_PAIR_SOURCES", pairSourceCount);
            }
            return new BaseClusterTopology(donor.section, donor.revision, donor.sourceFingerprint, target,
                    labels, 0, metadata, edges.offsets, edges.targets, edges.capabilities, edges.lowerBounds,
                    donor.haloOffsets, donor.haloRevisions, donor.haloFingerprints, donor.boundaryFacts,
                    deltaLegal.isEmpty() ? donor.portBits
                            : new BoundaryPortBits(donor.portBits, deltaLegal, portBounds(metadata)), rows);
        }

        private static long portBounds(long[] metadata) {
            if (metadata.length == 0) return -1L;
            long bounds = metadata[0];
            for (int i = 1; i < metadata.length; i++) bounds = mergeBounds(bounds, metadata[i]);
            return bounds & ~4095L;
        }
        private static boolean bit(short[] rows, int cell) {
            return ((rows[cell >>> 4] >>> (cell & 15)) & 1) != 0;
        }
        private static void put(short[] rows, int cell, boolean value) {
            int mask = 1 << (cell & 15), index = cell >>> 4;
            if (value) rows[index] |= (short) mask;
            else rows[index] &= (short) ~mask;
        }
        private static int doors(PackedFacts facts, int y, int z) {
            return facts == null || facts.doorRows == null ? 0 : Short.toUnsignedInt(facts.doorRows[y * SIDE + z]);
        }
        private static final long NIBBLES = 0x1111111111111111L;
        private void patchRows() {
            boolean f = old.geometry.acceptsFluid() != target.acceptsFluid();
            boolean d = old.geometry.canOpenDoors() != target.canOpenDoors();
            boolean physical = old.geometry.width() != target.width() || old.geometry.height() != target.height();
            var lower = input.atSection(0, -1, 0);
            for (int wy = 0; wy < SIDE + target.heightCells(); wy++) {
                int y = wy & 15;
                var facts = input.atSection(0, wy >>> 4, 0);
                if (facts == null) continue;
                boolean exact = (facts.exactOpenLayers & (1 << y)) != 0 && (physical || d);
                boolean fluid = f && (facts.fluidLayers & (1 << y)) != 0;
                boolean door = facts.geometry().hasDoors();
                boolean belowDoor = wy < SIDE && d && (wy == 0
                        ? lower != null && lower.geometry().hasDoors() : door);
                if (!exact && !fluid && !door && !belowDoor) continue;
                for (int z = 0; z < SIDE; z++) {
                    int doorBits = doors(facts, y, z);
                    for (int left = doorBits; left != 0; left &= left - 1) {
                        int x = Integer.numberOfTrailingZeros(left);
                        stencil(flatDoorSources, x, wy, z, 1, target.heightCells() - 1, 0);
                        stencil(moveSources, x, wy, z, MAX_STRUCTURAL_JUMP,
                                target.heightCells() + MAX_STRUCTURAL_STEP, MAX_STRUCTURAL_DROP);
                    }
                    long packed = exact || fluid ? facts.packedRow(y * SIDE + z) : 0;
                    int fluidBits = fluid ? (int) RawRows.compact(packed >>> 2) : 0;
                    int solidBits = exact ? (int) RawRows.compact(packed & (packed >>> 3) & NIBBLES) : 0;
                    for (int bits = fluidBits | solidBits; bits != 0; bits &= bits - 1) {
                        int x = Integer.numberOfTrailingZeros(bits), c = (wy << 8) | (z << 4) | x;
                        if (bit(volume, c) && !(d && !target.canOpenDoors() && (doorBits & (1 << x)) != 0))
                            continue;
                        rawChecks++;
                        boolean next = (target.acceptsFluid() || (facts.flags(cellIndex(x, y, z)) & FLUID) == 0)
                                && input.volumeOpen(x, wy, z, target, scratch);
                        if (bit(volume, c) == next) continue;
                        put(volume, c, next);
                        for (int py = Math.max(0, wy - target.heightCells() + 1);
                             py <= Math.min(SIDE, wy); py++) prismInputs.set((py << 8) | (z << 4) | x);
                    }
                    if (wy < SIDE) {
                        int supportDoor = belowDoor ? doors(wy == 0 ? lower : facts, (y - 1) & 15, z) : 0;
                        for (int bits = fluidBits | (d ? doorBits | supportDoor : 0); bits != 0; bits &= bits - 1) {
                            int x = Integer.numberOfTrailingZeros(bits), c = cellIndex(x, wy, z);
                            rawChecks++;
                            int flags = input.flags(x, wy, z, scratch);
                            boolean next = (target.acceptsFluid() || (flags & FLUID) == 0)
                                    && input.groundOpen(x, wy, z, flags, target, scratch);
                            if (bit(ground, c) != next) {
                                put(ground, c, next);
                                prismInputs.set(c);
                            }
                        }
                    }
                }
            }
            for (int c = prismInputs.nextSetBit(0); c >= 0; c = prismInputs.nextSetBit(c + 1)) {
                boolean pass = true;
                for (int layer = 0; layer < target.heightCells() && pass; layer++)
                    pass = bit(volume, c + layer * SIDE * SIDE);
                if (bit(prism, c) != pass) {
                    put(prism, c, pass);
                    deltaPrism.set(c);
                    stencil(moveSources, x(c), c >>> 8, z(c), MAX_STRUCTURAL_JUMP, 1, MAX_STRUCTURAL_DROP);
                }
                if (c < CELL_COUNT) {
                    boolean anchor = pass && bit(ground, c);
                    if (bit(legal, c) != anchor) {
                        put(legal, c, anchor);
                        deltaLegal.set(c);
                    }
                }
            }
        }

        private static void stencil(BitSet sources, int x, int y, int z, int reach, int below, int above) {
            // All supported local moves are cardinal, including their intermediate probes.
            for (int py = Math.max(0, y - below); py <= Math.min(SIDE - 1, y + above); py++) {
                for (int dx = Math.max(-reach, -x); dx <= Math.min(reach, SIDE - 1 - x); dx++)
                    sources.set(cellIndex(x + dx, py, z));
                for (int dz = Math.max(-reach, -z); dz <= Math.min(reach, SIDE - 1 - z); dz++)
                    sources.set(cellIndex(x, py, z + dz));
            }
        }
        private int oldLabel(int c) { return donor.baseLabel + donor.componentLabels.get(c) - 1; }
        private static long pair(int a, int b) { return ((long) a << 32) | Integer.toUnsignedLong(b); }
        private boolean doorOpen(int from, Direction face, int distance, int dy, GeometryKey body) {
            return collision == null || input.doorMoveOpen(x(from), y(from), z(from), face, distance, dy, body, collision);
        }
        private void findDirtyComponents() {
            for (int c = deltaLegal.nextSetBit(0); c >= 0; c = deltaLegal.nextSetBit(c + 1))
                if (bit(old.legal, c) && !bit(legal, c)) dirty.set(oldLabel(c));
            for (int a = flatDoorSources.nextSetBit(0); a >= 0; a = flatDoorSources.nextSetBit(a + 1)) {
                if (!bit(old.legal, a) || !bit(legal, a)) continue;
                for (Direction face : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                    int bx = x(a) + face.getStepX(), bz = z(a) + face.getStepZ();
                    if (!inBounds(bx, y(a), bz)) continue;
                    int b = packCell(bx, y(a), bz);
                    if (!bit(old.legal, b) || !bit(legal, b)) continue;
                    flatChecks++;
                    boolean was = doorOpen(a, face, 1, 0, old.geometry);
                    boolean now = doorOpen(a, face, 1, 0, target);
                    if (was && !now) dirty.set(oldLabel(a));
                    if (!was && now) gainedFlat.add(pair(a, b));
                }
            }
            repairedCells.or(deltaLegal);
            // A gain can join old components as units. A lost cell or flat
            // connection can split them, so expand only those old components.
            long[] words = new long[CELL_COUNT / Long.SIZE];
            for (int id = dirty.nextSetBit(0); id >= 0; id = dirty.nextSetBit(id + 1))
                donor.captureComponentCells(id, words);
            repairedCells.or(BitSet.valueOf(words));
        }
        private int node(int cell) { return repairedCells.get(cell) ? count + cell : oldLabel(cell); }
        private int targetLabel(int cell) { return nodeLabel[node(cell)]; }
        private void join(int a, int b) {
            a = runRoot(parent, a); b = runRoot(parent, b);
            if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
        }
        private void connectFragments() {
            for (int a = 0; a < count; a++) parent[a] = a;
            for (int c = repairedCells.nextSetBit(0); c >= 0; c = repairedCells.nextSetBit(c + 1))
                parent[count + c] = count + c;
            for (int c = repairedCells.nextSetBit(0); c >= 0; c = repairedCells.nextSetBit(c + 1)) {
                stencil(moveSources, x(c), y(c), z(c), MAX_STRUCTURAL_JUMP, MAX_STRUCTURAL_STEP, MAX_STRUCTURAL_DROP);
                if (!bit(legal, c)) continue;
                for (Direction face : HORIZONTAL_DIRECTIONS) {
                    int nx = x(c) + face.getStepX(), nz = z(c) + face.getStepZ();
                    if (!inBounds(nx, y(c), nz)) continue;
                    int b = packCell(nx, y(c), nz);
                    if (!bit(legal, b)) continue;
                    boolean forward = b > c;
                    flatChecks++;
                    if (doorOpen(forward ? c : b, forward ? face : face.getOpposite(), 1, 0, target))
                        join(node(c), node(b));
                }
            }
            for (long e : gainedFlat) join(node((int) (e >>> 32)), node((int) e));
        }

        private static long cellBounds(int c) {
            return c | (long) x(c) << 12 | (long) y(c) << 16 | (long) z(c) << 20
                    | (long) x(c) << 24 | (long) y(c) << 28 | (long) z(c) << 32;
        }
        private static long mergeBounds(long a, long b) {
            long next = Math.min(a & 4095, b & 4095);
            for (int i = 12; i <= 32; i += 4) {
                long av = (a >>> i) & 15, bv = (b >>> i) & 15;
                next |= (i < 24 ? Math.min(av, bv) : Math.max(av, bv)) << i;
            }
            return next;
        }
        private static void merge(long[] bounds, ArrayList<Integer> roots, int id, long value) {
            if (bounds[id] == 0) {
                roots.add(id);
                bounds[id] = value + 1;
            } else bounds[id] = mergeBounds(bounds[id] - 1, value) + 1;
        }
        private long[] packComponents() {
            var roots = new ArrayList<Integer>();
            long[] bounds = new long[parent.length];
            for (int id = 0; id < count; id++) {
                if (dirty.get(id)) continue;
                stableComponents++;
                merge(bounds, roots, runRoot(parent, id), donor.metadata(id));
            }
            for (int c = repairedCells.nextSetBit(0); c >= 0; c = repairedCells.nextSetBit(c + 1))
                if (bit(legal, c)) merge(bounds, roots, runRoot(parent, count + c), cellBounds(c));
            roots.sort(Comparator.comparingLong(id -> (bounds[id] - 1) & 4095));
            long[] metadata = new long[roots.size()];
            for (int i = 0; i < roots.size(); i++) {
                int root = roots.get(i);
                metadata[i] = bounds[root] - 1;
                nodeLabel[root] = i + 1;
            }
            for (int id = 0; id < count; id++)
                if (!dirty.get(id)) nodeLabel[id] = nodeLabel[runRoot(parent, id)];
            for (int c = repairedCells.nextSetBit(0); c >= 0; c = repairedCells.nextSetBit(c + 1))
                if (bit(legal, c)) nodeLabel[count + c] = nodeLabel[runRoot(parent, count + c)];
            return metadata;
        }

        private interface MoveVisitor { void accept(int dest, Direction face, int distance, int dy); }
        private static void visitMoves(int source, MoveVisitor visitor) {
            int x = x(source), y = y(source), z = z(source);
            for (Direction face : HORIZONTAL_DIRECTIONS) {
                for (int distance = 1; distance <= MAX_STRUCTURAL_JUMP; distance++) {
                    int nx = x + face.getStepX() * distance, nz = z + face.getStepZ() * distance;
                    if (!inBounds(nx, y, nz)) break;
                    for (int dy = -MAX_STRUCTURAL_DROP; dy <= MAX_STRUCTURAL_STEP; dy++) {
                        int ny = y + dy;
                        if (ny >= 0 && ny < SIDE) visitor.accept(packCell(nx, ny, nz), face, distance, dy);
                    }
                }
            }
        }
        private static boolean envelope(short[] prism, int a, int b, Direction face, int distance, int dy) {
            for (int cy = y(a) - 1; cy > y(b); cy--)
                if (!bit(prism, packCell(x(b), cy, z(b)))) return false;
            for (int i = 1; i < distance; i++) {
                int y = y(a) + Math.floorDiv(dy * i, distance);
                int c = packCell(x(a) + i * face.getStepX(), y, z(a) + i * face.getStepZ());
                if (!bit(prism, c) && !bit(prism, c + SIDE * SIDE)) return false;
            }
            return true;
        }
        private boolean pass(int a, int b, Direction face, int d, int dy, boolean previous) {
            moveChecks++;
            return envelope(previous ? old.prism : prism, a, b, face, d, dy)
                    && doorOpen(a, face, d, dy, previous ? old.geometry : target);
        }
        private static void addEdge(Long2LongOpenHashMap edges, long key, long capability, float cost) {
            long was = edges.get(key);
            long mask = (was & 0x3fff_ffffL) | capability;
            float min = was == 0 ? cost : Math.min(cost, Float.intBitsToFloat((int) (was >>> 32)));
            edges.put(key, ((long) Float.floatToRawIntBits(min) << 32) | mask);
        }
        private static void addMove(Long2LongOpenHashMap edges, int from, int to, int d, int dy) {
            long caps = CpuMoveCapabilities.VALUES[(dy + 4) * 3 + d - 1];
            addEdge(edges, pair(from, to), caps, (float) Math.sqrt(d * d + dy * dy));
        }
        private PrimitiveEdges patchEdges(int components) {
            if (components <= 1) return PrimitiveEdges.empty(components);
            var values = new Long2LongOpenHashMap();
            var invalid = new LongOpenHashSet();
            for (int cell = moveSources.nextSetBit(0); cell >= 0; cell = moveSources.nextSetBit(cell + 1)) {
                final int a = cell;
                if (!bit(legal, a)) continue;
                moveSourceCount++;
                int from = targetLabel(a) - 1;
                visitMoves(a, (b, face, d, dy) -> {
                    if (!bit(legal, b)) return;
                    int to = targetLabel(b) - 1;
                    if (from == to) return;
                    boolean has = pass(a, b, face, d, dy, false);
                    if (has) addMove(values, from, to, d, dy);
                    else if (!repairedCells.get(a) && !repairedCells.get(b)
                            && !invalid.contains(pair(oldLabel(a), oldLabel(b)))
                            && pass(a, b, face, d, dy, true))
                        invalid.add(pair(oldLabel(a), oldLabel(b)));
                });
            }
            for (int from = 0; from < count; from++) {
                if (dirty.get(from)) continue;
                for (int e = donor.localEdgeStart(from); e < donor.localEdgeEnd(from); e++) {
                    int to = donor.localEdgeTarget(e);
                    if (dirty.get(to) || nodeLabel[from] == nodeLabel[to]) continue;
                    if (invalid.contains(pair(from, to))) {
                        // The old aggregate does not identify the lost
                        // primitive's contribution to its mask or minimum.
                        rebuildPair(values, from, to);
                    } else {
                        addEdge(values, pair(nodeLabel[from] - 1, nodeLabel[to] - 1),
                                donor.localEdgeCapabilities(e), donor.localEdgeLowerBound(e));
                        reusedEdges++;
                    }
                }
            }
            return packEdges(values, components);
        }
        private void rebuildPair(Long2LongOpenHashMap values, int oldFrom, int oldTo) {
            long[] words = new long[CELL_COUNT / Long.SIZE];
            donor.captureComponentCells(oldFrom, words);
            BitSet cells = BitSet.valueOf(words);
            for (int c = cells.nextSetBit(0); c >= 0; c = cells.nextSetBit(c + 1)) {
                final int a = c;
                pairSourceCount++;
                visitMoves(a, (b, face, d, dy) -> {
                    if (bit(legal, b) && !repairedCells.get(b) && oldLabel(b) == oldTo
                            && pass(a, b, face, d, dy, false))
                        addMove(values, nodeLabel[oldFrom] - 1, nodeLabel[oldTo] - 1, d, dy);
                });
            }
        }
    }

    public static BaseClusterTopology build(SectionPos section,
                                            long revision,
                                            BuildInput input,
                                            GeometryKey geometry,
                                            BuildScratch scratch) {
        return build(section, revision, input, geometry, scratch, null, null);
    }

    static BaseClusterTopology build(SectionPos section, long revision, BuildInput input, GeometryKey geometry,
                                     BuildScratch scratch, GraphReuse.Rows donor, GraphReuse reuse) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(scratch, "scratch").reset();
        deriveLegalAnchors(input, geometry, scratch, donor, reuse);
        GraphReuse.Rows rows = reuse != null && reuse.enabled() && GraphReuse.supported(geometry)
                ? new GraphReuse.Rows(input,revision,geometry,input.hasDoors,scratch.volumeRows,scratch.groundRows,
                        scratch.prismMask,scratch.legalMask) : null;
        int componentCount = labelComponents(input, geometry, scratch);
        int uniformLabel = uniformLabel(scratch.labels);
        BitStorage labels = compactLabels(scratch.labels, componentCount, uniformLabel);
        int baseLabel = uniformLabel < 0 ? 0 : uniformLabel;
        long[] metadata = Arrays.copyOf(scratch.componentMetadata, componentCount);
        PrimitiveEdges edges = geometry.channel() == Channel.GROUND
                ? buildGroundEdges(input, geometry, scratch, componentCount)
                : PrimitiveEdges.empty(componentCount);
        BuildInput boundaryFacts = input.boundarySnapshot(geometry, scratch.haloUsed);
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
                stamps.fingerprints,
                boundaryFacts, geometry.channel() == Channel.GROUND ? new BoundaryPortBits(scratch.legalMask) : null, rows
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

    BuildInput boundaryFacts() {
        return boundaryFacts;
    }

    static boolean boundaryHaloRequired(GeometryKey geometry, int dx, int dy, int dz) {
        return (dx | dy | dz) != 0 && Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1
                && (geometry.width() > 1.0F || dx == 0 || dz == 0);
    }

    public int componentCount() {
        return componentMetadata.length;
    }

    public int componentAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return -1;
        }
        int label = baseLabel + componentLabels.get(packCell(x, y, z));
        return label == 0 ? -1 : label - 1;
    }

    public int componentAnchorCell(int componentId) {
        return (int) metadata(componentId) & 0xfff;
    }

    @Nullable
    long[] sharedComponentCells(int componentId) {
        if (componentId != 0 || componentCount() != 1 || baseLabel != 0
                || !(componentLabels instanceof SimpleBitStorage) || componentLabels.getBits() != 1)
            return null;
        // Published labels are read-only; one-bit labels already encode exact membership.
        long[] cells = componentLabels.getRaw();
        return cells.length == CELL_COUNT / Long.SIZE ? cells : null;
    }

    void captureComponentCells(int componentId, long[] cells) {
        long bounds = metadata(componentId);
        int minX = (int) (bounds >>> 12) & 15, maxX = (int) (bounds >>> 24) & 15;
        int minY = (int) (bounds >>> 16) & 15, maxY = (int) (bounds >>> 28) & 15;
        int minZ = (int) (bounds >>> 20) & 15, maxZ = (int) (bounds >>> 32) & 15;
        int label = componentId + 1 - baseLabel;
        // Existing bounds contain every member; packed labels still exclude holes.
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            int end = cellIndex(maxX, y, z);
            for (int cell = cellIndex(minX, y, z); cell <= end; cell++)
                if (componentLabels.get(cell) == label) cells[cell >>> 6] |= 1L << cell;
        }
    }

    int closestCell(int componentId, int targetX, int targetY, int targetZ,
                    double maximumDistanceSquared) {
        return closestCell(componentLabels, baseLabel, metadata(componentId), componentId,
                targetX, targetY, targetZ, maximumDistanceSquared);
    }

    ComponentGeometry componentGeometry() {
        return new ComponentGeometry(componentLabels, baseLabel, componentMetadata);
    }

    static final class ComponentGeometry {
        private final BitStorage labels;
        private final int baseLabel;
        private final long[] metadata;

        private ComponentGeometry(BitStorage labels, int baseLabel, long[] metadata) {
            this.labels = labels;
            this.baseLabel = baseLabel;
            this.metadata = metadata;
        }

        int retainedBytes() {
            return 48 + labelBytes(labels) + metadata.length * Long.BYTES;
        }

        int closestCell(int component, int x, int y, int z, double maximumDistanceSquared) {
            return BaseClusterTopology.closestCell(labels, baseLabel, metadata[component],
                    component, x, y, z, maximumDistanceSquared);
        }
    }

    private static int closestCell(BitStorage labels, int baseLabel, long bounds, int componentId,
                                   int targetX, int targetY, int targetZ,
                                   double maximumDistanceSquared) {
        int minX = (int) (bounds >>> 12) & 15, maxX = (int) (bounds >>> 24) & 15;
        int minY = (int) (bounds >>> 16) & 15, maxY = (int) (bounds >>> 28) & 15;
        int minZ = (int) (bounds >>> 20) & 15, maxZ = (int) (bounds >>> 32) & 15;
        int nearestX = Math.max(minX, Math.min(maxX, targetX));
        int nearestY = Math.max(minY, Math.min(maxY, targetY));
        int nearestZ = Math.max(minZ, Math.min(maxZ, targetZ));
        if (distanceSquared(nearestX, nearestY, nearestZ, targetX, targetY, targetZ)
                >= maximumDistanceSquared) return -1;
        if (baseLabel + labels.get(cellIndex(nearestX, nearestY, nearestZ)) == componentId + 1)
            return cellIndex(nearestX, nearestY, nearestZ);
        int best = -1;
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            if (distanceSquared(nearestX, y, z, targetX, targetY, targetZ)
                    >= maximumDistanceSquared) continue;
            for (int x = minX; x <= maxX; x++) {
                double distance = distanceSquared(x, y, z, targetX, targetY, targetZ);
                if (distance < maximumDistanceSquared
                        && baseLabel + labels.get(cellIndex(x, y, z)) == componentId + 1) {
                    maximumDistanceSquared = distance;
                    best = cellIndex(x, y, z);
                }
            }
        }
        return best;
    }

    private static double distanceSquared(int x, int y, int z, int targetX, int targetY, int targetZ) {
        double dx = (double) x - targetX, dy = (double) y - targetY, dz = (double) z - targetZ;
        return dx * dx + dy * dy + dz * dz;
    }

    boolean mayExit(int componentId, SectionPos target, MovementKey movement) {
        return mayConnect(componentId, target, movement, false);
    }

    boolean mayEnter(int componentId, SectionPos source, MovementKey movement) {
        return mayConnect(componentId, source, movement, true);
    }

    private boolean mayConnect(int componentId, SectionPos neighbor, MovementKey movement, boolean incoming) {
        long bounds = metadata(componentId);
        int dx = neighbor.x() - section.x();
        int dy = neighbor.y() - section.y();
        int dz = neighbor.z() - section.z();
        int horizontal = Math.abs(dx) + Math.abs(dz);
        if (horizontal > 1 || Math.abs(dy) > 1 || (horizontal == 0 && dy == 0)) return false;
        boolean volume = geometry.channel() == Channel.VOLUME;
        if (volume && horizontal + Math.abs(dy) != 1) return false;
        int reach = volume ? 1 : movement.jumpClass() + 1;
        int up = volume ? 1 : movement.maxStep();
        int down = volume ? 1 : movement.maxDrop();
        return overlapsSection((int) (bounds >>> 12) & 15, (int) (bounds >>> 24) & 15,
                reach, reach, dx)
                && overlapsSection((int) (bounds >>> 16) & 15, (int) (bounds >>> 28) & 15,
                incoming ? up : down, incoming ? down : up, dy)
                && overlapsSection((int) (bounds >>> 20) & 15, (int) (bounds >>> 32) & 15,
                reach, reach, dz);
    }

    private static boolean overlapsSection(int min, int max, int negative, int positive, int delta) {
        int targetMin = delta * SIDE;
        return min - negative < targetMin + SIDE && max + positive >= targetMin;
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

    IncomingEdges incomingEdges() {
        IncomingEdges ready = incomingEdges;
        if (ready == null) {
            synchronized (this) {
                ready = incomingEdges;
                if (ready == null) {
                    ready = IncomingEdges.build(outgoingOffsets, outgoingTargets, componentCount());
                    incomingEdges = ready;
                }
            }
        }
        return ready;
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

    /** Writes every primitive needed to reconstruct this derived graph without world access. */
    void writeSnapshot(JsonWriter json) throws IOException {
        json.beginObject();
        json.name("section").beginArray().value(section.x()).value(section.y()).value(section.z()).endArray();
        json.name("revision").value(revision);
        json.name("sourceFingerprintHex").value(Long.toUnsignedString(sourceFingerprint, 16));
        json.name("signatureHex").value(Long.toUnsignedString(signature, 16));
        json.name("geometry").beginObject();
        json.name("channel").value(geometry.channel().name());
        json.name("widthCells").value(geometry.widthCells());
        json.name("heightCells").value(geometry.heightCells());
        json.name("width").value(geometry.width());
        json.name("height").value(geometry.height());
        json.name("acceptsFluid").value(geometry.acceptsFluid());
        json.name("canOpenDoors").value(geometry.canOpenDoors());
        json.endObject();
        json.name("baseLabel").value(baseLabel);
        json.name("labelCellOrder").value("(y << 8) | (z << 4) | x");
        json.name("componentLabelValues").beginArray();
        for (int cell = 0; cell < CELL_COUNT; cell++) json.value(componentLabels.get(cell));
        json.endArray();
        json.name("componentMetadataHex").beginArray();
        for (long value : componentMetadata) json.value(Long.toUnsignedString(value, 16));
        json.endArray();
        json.name("outgoingOffsets").beginArray();
        for (int value : outgoingOffsets) json.value(value);
        json.endArray();
        json.name("outgoingTargets").beginArray();
        for (int value : outgoingTargets) json.value(value);
        json.endArray();
        json.name("outgoingCapabilityMasksHex").beginArray();
        for (long value : outgoingCapabilityMasks) json.value(Long.toUnsignedString(value, 16));
        json.endArray();
        json.name("outgoingLowerBoundBitsHex").beginArray();
        for (float value : outgoingLowerBounds) {
            json.value(Integer.toUnsignedString(Float.floatToRawIntBits(value), 16));
        }
        json.endArray();
        json.name("halo").beginArray();
        for (int index = 0; index < haloOffsets.length; index++) {
            json.beginObject();
            json.name("offset").value(Byte.toUnsignedInt(haloOffsets[index]));
            json.name("revision").value(haloRevisions[index]);
            json.name("fingerprintHex").value(Long.toUnsignedString(haloFingerprints[index], 16));
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }
    public int retainedBytes() {
        return retainedBytes;
    }

    long signature() {
        return signature;
    }

    long metadata(int componentId) {
        if (componentId < 0 || componentId >= componentMetadata.length) {
            throw new IndexOutOfBoundsException("unknown component " + componentId);
        }
        return componentMetadata[componentId];
    }

    private static void deriveLegalAnchors(BuildInput input, GeometryKey geometry, BuildScratch scratch,
                                           GraphReuse.Rows donor, GraphReuse reuse) {
        scratch.rawRows.fill(input, geometry, scratch, donor, reuse);
        if (donor != null) {
            deriveSharedMasks(geometry,scratch,donor,reuse);
            return;
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
                if (geometry.widthCells() == 1 && geometry.heightCells() == 2) {
                    intersectGroundWithPrism(scratch);
                } else {
                    for (int y = 0; y < SIDE; y++) {
                        erodeRows(geometry, scratch, y, minX, true);
                        appendErodedLayer(scratch.legalMask, scratch.erodedRows,
                                y, minZ, geometry.widthCells());
                    }
                }
            }
        }
        if (geometry.channel() == Channel.VOLUME) {
            System.arraycopy(scratch.prismMask, 0, scratch.legalMask, 0,
                    scratch.legalMask.length);
        }
        if (geometry.widthCells() == 1 && geometry.heightCells() == 1) {
            removeSealedCells(input, geometry, scratch);
        }
    }

    private static void intersectGroundWithPrism(BuildScratch scratch) {
        // For one-column footprints, prism and ground use the same x/z alignment.
        for (int y = 0; y < SIDE; y++) {
            int firstRow = y * EXTENDED_SIDE - EXTENDED_MIN;
            for (int z = 0; z < SIDE; z += 4) {
                long ground = 0;
                for (int row = 0; row < 4; row++) {
                    ground |= (scratch.groundRows[firstRow + z + row] >>> -EXTENDED_MIN & 0xffffL) << (row << 4);
                }
                int word = (y << 2) + (z >>> 2);
                scratch.legalMask[word] = scratch.prismMask[word] & ground;
            }
        }
    }

    /**
     * Bits 1..16 represent the center section x coordinates; bits 0 and 17 are
     * the west/east halo. The row grid uses the same one-cell shell for y and z.
     */
    private static void removeSealedCells(BuildInput input,
                                          GeometryKey geometry,
                                          BuildScratch scratch) {
        input.populateFullCollisionRows(geometry, scratch);
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

    private static void deriveSharedMasks(GeometryKey g, BuildScratch scratch, GraphReuse.Rows donor, GraphReuse reuse) {
        long copied=0, rebuilt=0;
        for (int y=0;y<=SIDE;y++) for(int z=0;z<SIDE;z++) {
            int row=y*SIDE+z;
            boolean equal=true;
            int ground=y==SIDE ? 0 : scratch.groundRows[y*EXTENDED_SIDE+z-EXTENDED_MIN]>>>8;
            for(int d=0;d<g.heightCells() && equal;d++) {
                int newVolume=scratch.volumeRows[(y+d)*EXTENDED_SIDE+z-EXTENDED_MIN]>>>8;
                equal=newVolume==Short.toUnsignedInt(donor.volume[row+d*SIDE]);
            }
            int p;
            if(equal) { p=Short.toUnsignedInt(donor.prism[row]); copied++; }
            else {
                p=0xffff;
                for(int d=0;d<g.heightCells();d++) p &= scratch.volumeRows[(y+d)*EXTENDED_SIDE+z-EXTENDED_MIN]>>>8;
                rebuilt++;
            }
            int shift=(row&3)*16;
            scratch.prismMask[row>>>2]|=(long)p<<shift;
            if(y<SIDE) {
                int legal;
                if(equal && ground==Short.toUnsignedInt(donor.ground[row])) {
                    legal=Short.toUnsignedInt(donor.legal[row]); copied++;
                } else { legal=p&ground; rebuilt++; }
                scratch.legalMask[row>>>2]|=(long)legal<<shift;
            }
        }
        reuse.mark("base.anchorRowsCopied",copied);
        reuse.mark("base.anchorRowsRebuilt",rebuilt);
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

    private static int labelComponents(BuildInput input, GeometryKey geometry, BuildScratch scratch) {
        BoundaryGeometry collision = input.hasDoors ? new BoundaryGeometry(input) : null;
        int[] starts = scratch.runStarts, ends = scratch.runEnds, parent = scratch.queue;
        for (int row = 0; row < 256; row++) {
            int legal = runLegal(scratch.legalMask, row);
            int east = legal & (legal >>> 1), y = row >>> 4, z = row & 15;
            if (collision != null) {
                int passed = 0;
                for (int remaining = east; remaining != 0; remaining &= remaining - 1) {
                    int x = Integer.numberOfTrailingZeros(remaining);
                    if (input.doorMoveOpen(x,y,z,Direction.EAST,1,0,geometry,collision)) passed |= 1 << x;
                }
                east = passed;
            }
            starts[row] = legal & ~(east << 1); ends[row] = legal & ~east;
            int first = starts[row];
            while (first != 0) {
                int x = Integer.numberOfTrailingZeros(first);
                first &= first - 1;
                int root = row * 16 + x; parent[root] = root;
            }
        }
        for (int row = 0; row < 256; row++) {
            int legal = runLegal(scratch.legalMask,row), y = row >>> 4, z = row & 15;
            if (z < 15) {
                int allowed = legal & runLegal(scratch.legalMask,row + 1);
                joinRuns(scratch,row,row + 1,allowed,input,geometry,collision);
            }
            if (geometry.channel() == Channel.VOLUME && y < 15)
                joinRuns(scratch,row,row + 16,legal & runLegal(scratch.legalMask,row + 16),
                        input,geometry,null);
        }
        // Save every run root before parent storage becomes the component-ID table.
        for (int row = 0; row < 256; row++) {
            for (int remaining = starts[row]; remaining != 0; remaining &= remaining - 1) {
                int cell = row * 16 + Integer.numberOfTrailingZeros(remaining);
                scratch.labels[cell] = (char)(runRoot(parent,cell) + 1);
            }
        }
        int componentCount = 0;
        for (int row = 0; row < 256; row++) {
            for (int remaining = starts[row]; remaining != 0; remaining &= remaining - 1) {
                int cell = row * 16 + Integer.numberOfTrailingZeros(remaining);
                if (scratch.labels[cell] == cell + 1) {
                    parent[cell] = ++componentCount;
                    scratch.componentMetadata[componentCount - 1] = cell | 15L << 12 | 15L << 16 | 15L << 20;
                }
            }
        }
        for (int row = 0; row < 256; row++) {
            int first = starts[row], last = ends[row], y = row >>> 4, z = row & 15;
            while (first != 0) {
                int x = Integer.numberOfTrailingZeros(first), end = Integer.numberOfTrailingZeros(last);
                first &= first - 1; last &= last - 1;
                int component = parent[scratch.labels[row * 16 + x] - 1] - 1;
                Arrays.fill(scratch.labels,row * 16 + x,row * 16 + end + 1,(char)(component + 1));
                long old = scratch.componentMetadata[component];
                scratch.componentMetadata[component] = (old & 4095)
                        | (long)Math.min((int)(old >>> 12) & 15,x) << 12
                        | (long)Math.min((int)(old >>> 16) & 15,y) << 16
                        | (long)Math.min((int)(old >>> 20) & 15,z) << 20
                        | (long)Math.max((int)(old >>> 24) & 15,end) << 24
                        | (long)Math.max((int)(old >>> 28) & 15,y) << 28
                        | (long)Math.max((int)(old >>> 32) & 15,z) << 32;
            }
        }
        return componentCount;
    }

    private static int runLegal(long[] legal,int row) {
        return (int)(legal[row >>> 2] >>> ((row & 3) * 16)) & 0xffff;
    }
    private static int runRoot(int[] parent,int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }
    private static void joinRuns(BuildScratch scratch,int row,int other,int allowed,
                                 BuildInput input,GeometryKey geometry,BoundaryGeometry collision) {
        while (allowed != 0) {
            int x = Integer.numberOfTrailingZeros(allowed), prefix = (1 << (x + 1)) - 1;
            int a = 31 - Integer.numberOfLeadingZeros(scratch.runStarts[row] & prefix);
            int b = 31 - Integer.numberOfLeadingZeros(scratch.runStarts[other] & prefix);
            int endA = Integer.numberOfTrailingZeros(scratch.runEnds[row] & (0xffff << x));
            int endB = Integer.numberOfTrailingZeros(scratch.runEnds[other] & (0xffff << x));
            int end = Math.min(endA,endB);
            a = runRoot(scratch.queue,row * 16 + a); b = runRoot(scratch.queue,other * 16 + b);
            if (a != b) {
                boolean connected = collision == null;
                // One passable crossing joins this pair of internally connected runs.
                for (int remaining = allowed & ((1 << (end + 1)) - 1);
                     !connected && remaining != 0; remaining &= remaining - 1) {
                    int crossing = Integer.numberOfTrailingZeros(remaining);
                    connected = input.doorMoveOpen(crossing,row >>> 4,row & 15,
                            Direction.SOUTH,1,0,geometry,collision);
                }
                if (connected) scratch.queue[Math.max(a,b)] = Math.min(a,b);
            }
            allowed &= 0xffff << (end + 1);
        }
    }

    private static PrimitiveEdges buildGroundEdges(BuildInput input,
                                                    GeometryKey geometry,
                                                    BuildScratch scratch,
                                                    int componentCount) {
        if (componentCount <= 1) return PrimitiveEdges.empty(componentCount);
        Long2LongOpenHashMap merged = scratch.edgeMap;
        BoundaryGeometry collision = input.hasDoors ? new BoundaryGeometry(input) : null;
        // Mask erosion is finished. Reuse one scratch row for source component labels.
        int[] sourceLabels = scratch.erodedRows;
        for (int row = 0; row < SIDE * SIDE; row++) {
            int sources = runLegal(scratch.legalMask, row);
            if (sources == 0) continue;
            int y = row >>> 4, z = row & 15;
            for (int bits = sources; bits != 0; bits &= bits - 1) {
                int x = Integer.numberOfTrailingZeros(bits);
                sourceLabels[x] = scratch.labels[(row << 4) | x] - 1;
            }
            for (int dy = Math.max(-y, -MAX_STRUCTURAL_DROP); dy <= Math.min(SIDE - 1 - y, MAX_STRUCTURAL_STEP); dy++) {
                int ny = y + dy;
                int sameZ = runLegal(scratch.legalMask, (ny << 4) | z);
                for (Direction direction : HORIZONTAL_DIRECTIONS) {
                    int stepX = direction.getStepX(), stepZ = direction.getStepZ();
                    if (stepZ == 0 && sameZ == 0) continue;
                    for (int horizontal = 1; horizontal <= MAX_STRUCTURAL_JUMP; horizontal++) {
                        int dx = stepX * horizontal;
                        int nz = z + stepZ * horizontal;
                        if (nz < 0 || nz >= SIDE) break;
                        int targets = stepZ == 0 ? sameZ : runLegal(scratch.legalMask, (ny << 4) | nz);
                        // Align target bits to their source x without wrapping into another row.
                        int candidates = sources & (dx < 0 ? targets << -dx : targets >>> dx);
                        for (int bits = candidates; bits != 0; bits &= bits - 1) {
                            int x = Integer.numberOfTrailingZeros(bits);
                            int nx = x + dx;
                            int from = sourceLabels[x];
                            int targetLabel = scratch.labels[packCell(nx, ny, nz)];
                            if (targetLabel - 1 == from) continue;
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
                            if (collision != null && !input.doorMoveOpen(
                                    x, y, z, direction, horizontal, dy, geometry, collision)) continue;
                            long capabilityMask = CpuMoveCapabilities.VALUES[(dy + 4) * 3 + horizontal - 1];
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
        }
        return packEdges(merged, componentCount);
    }

    private static PrimitiveEdges packEdges(Long2LongOpenHashMap merged, int componentCount) {
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

    // Both endpoints are in this section. Interpolation stays between them;
    // the second clearance probe can use the extra prism layer at y=16.
    static boolean movementEnvelopeOpen(int x,
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
        return isSet(scratch.prismMask, packCell(anchorX, anchorY, anchorZ));
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

    private static BitStorage compactLabels(char[] labels, int componentCount, int uniform) {
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

    private static int packCell(int x, int y, int z) {
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
                              float width,
                              float height,
                              boolean acceptsFluid,
                              boolean canOpenDoors) {
        public GeometryKey {
            Objects.requireNonNull(channel, "channel");
            if (widthCells < 1 || widthCells > SIDE || heightCells < 1 || heightCells > SIDE) {
                throw new IllegalArgumentException("unsupported macro geometry");
            }
            if (!Float.isFinite(width) || width <= 0.0F
                    || !Float.isFinite(height) || height <= 0.0F) {
                throw new IllegalArgumentException("invalid physical macro geometry");
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
                                   boolean acceptsFluid,
                                   boolean canOpenDoors) {
        public static final TraversalProfile DEFAULT_GROUND =
                new TraversalProfile(0.6F, 1.95F, 1, 0, 3, false, false);

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
            return new GeometryKey(channel, widthCells, heightCells, width, height, acceptsFluid, canOpenDoors);
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
        private final boolean hasDoors;
        private final int airSlots;

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
            this.hasDoors = center.geometry().hasDoors()
                    || Arrays.stream(facts).anyMatch(f -> f.geometry().hasDoors());
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
            int air = center == PackedFacts.allAir() ? 1 : 0;
            for (int index = 0; index < this.facts.length; index++) {
                if (this.facts[index] == PackedFacts.allAir()) air |= 1 << (index + 1);
            }
            this.airSlots = (air & (air >>> 1)) == 0 ? 0 : air;
        }

        static BuildInput center(PackedFacts facts) {
            return new BuildInput(facts, new byte[0], new PackedFacts[0], new long[0], new long[0]);
        }

        PackedFacts center() {
            return center;
        }

        @Nullable
        PackedFacts atSection(int dx, int dy, int dz) {
            if ((dx | dy | dz) == 0) return center;
            if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1) return null;
            int index = Arrays.binarySearch(offsets, (byte) haloIndex(dx, dy, dz));
            return index < 0 ? null : facts[index];
        }

        long revisionAtSection(int dx, int dy, int dz, long centerRevision) {
            if ((dx | dy | dz) == 0) return centerRevision;
            int index = Arrays.binarySearch(offsets, (byte) haloIndex(dx, dy, dz));
            if (index < 0) throw new IllegalArgumentException("missing geometry revision");
            return revisions[index];
        }

        void visitFacts(Consumer<PackedFacts> consumer) {
            consumer.accept(center);
            for (PackedFacts fact : facts) consumer.accept(fact);
        }

        boolean preservesBoundaryFacts(BuildInput other) {
            return center == other.center && Arrays.equals(offsets,other.offsets)
                    && Arrays.equals(facts,other.facts) && Arrays.equals(revisions,other.revisions);
        }

        private boolean matchesBoundaryProjection(BuildInput input, GeometryKey geometry) {
            if (center != input.center) return false;
            int at = 0;
            for (int index = 0; index < input.offsets.length; index++) {
                byte offset = input.offsets[index];
                if (!selectBoundaryHalo(geometry, offset)) continue;
                if (at == offsets.length || offsets[at] != offset || facts[at] != input.facts[index]
                        || revisions[at] != input.revisions[index]) return false;
                at++;
            }
            return at == offsets.length;
        }

        boolean acceptsRowDonor(GraphReuse.Rows donor,long generation,GeometryKey geometry) {
            return donor != null && donor.accepts(this,generation,geometry,hasDoors);
        }

        boolean hasFactRuns() {
            return airSlots != 0;
        }

        void visitFactRuns(ObjIntConsumer<PackedFacts> consumer) {
            if (airSlots == 0) {
                consumer.accept(center, 1);
                for (PackedFacts fact : facts) consumer.accept(fact, 1);
                return;
            }
            for (int slot = 0; slot <= facts.length;) {
                int shifted = airSlots >>> slot;
                if ((shifted & 1) != 0) {
                    int count = Integer.numberOfTrailingZeros(~shifted);
                    consumer.accept(PackedFacts.allAir(), count);
                    slot += count;
                } else {
                    consumer.accept(slot == 0 ? center : facts[slot - 1], 1);
                    slot++;
                }
            }
        }

        BuildInput withFacts(PackedFacts replacementCenter, Map<Integer, PackedFacts> replacements) {
            PackedFacts[] next = facts.clone();
            long[] nextFingerprints = fingerprints.clone();
            for (int index = 0; index < offsets.length; index++) {
                next[index] = Objects.requireNonNull(replacements.get(Byte.toUnsignedInt(offsets[index])));
                nextFingerprints[index] = next[index].fingerprint();
            }
            return new BuildInput(replacementCenter, offsets, next, revisions, nextFingerprints);
        }

        int referenceBytes() {
            return 136 + offsets.length + facts.length * Long.BYTES
                    + (revisions.length + fingerprints.length) * Long.BYTES;
        }

        private static boolean selectBoundaryHalo(GeometryKey geometry, byte offset) {
            // BuildInput validates the encoded neighbor domain before storing it.
            return geometry.width() > 1.0F || haloX(offset) == 0 || haloZ(offset) == 0;
        }

        private BuildInput boundarySnapshot(GeometryKey geometry, boolean[] used) {
            byte[] selectedOffsets = new byte[offsets.length];
            PackedFacts[] selectedFacts = new PackedFacts[facts.length];
            long[] selectedRevisions = new long[revisions.length];
            long[] selectedFingerprints = new long[fingerprints.length];
            int count = 0;
            for (int index = 0; index < offsets.length; index++) {
                if (!selectBoundaryHalo(geometry, offsets[index])) continue;
                used[index] = true;
                selectedOffsets[count] = offsets[index];
                selectedFacts[count] = facts[index];
                selectedRevisions[count] = revisions[index];
                selectedFingerprints[count++] = fingerprints[index];
            }
            return new BuildInput(center, Arrays.copyOf(selectedOffsets, count),
                    Arrays.copyOf(selectedFacts, count), Arrays.copyOf(selectedRevisions, count),
                    Arrays.copyOf(selectedFingerprints, count));
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

        boolean groundOpen(int x, int y, int z, int currentFlags, GeometryKey body, BuildScratch scratch) {
            if (!body.canOpenDoors() || !hasDoors) return (currentFlags & GROUND_OPEN) != 0;
            PackedFacts current = sourceFacts(x, y, z, scratch);
            boolean door = current != null && current.geometry().hasDoors() && current.geometry().shape(cellIndex(
                    Math.floorMod(x,16),Math.floorMod(y,16),Math.floorMod(z,16))).openableDoor();
            int belowFlags = flags(x, y-1, z, scratch);
            PackedFacts below = (y & 15) != 0 ? current : sourceFacts(x, y-1, z, scratch);
            boolean belowDoor = below != null && below.geometry().hasDoors() && below.geometry().shape(cellIndex(
                    Math.floorMod(x,16),Math.floorMod(y-1,16),Math.floorMod(z,16))).openableDoor();
            if (belowDoor) return false;
            if (!door) return (currentFlags & GROUND_OPEN) != 0;
            return below != null && ((belowFlags & VOLUME_OPEN) == 0 || (belowFlags & EXACT_REQUIRED) != 0);
        }

        boolean doorMoveOpen(int x, int y, int z, Direction direction, int distance, int dy,
                             GeometryKey body, BoundaryGeometry collision) {
            double half = body.width()*.5;
            int x2=x+direction.getStepX()*distance, z2=z+direction.getStepZ()*distance;
            int minX=(int)Math.floor(Math.min(x,x2)+.5-half), maxX=(int)Math.ceil(Math.max(x,x2)+.5+half)-1;
            int minZ=(int)Math.floor(Math.min(z,z2)+.5-half), maxZ=(int)Math.ceil(Math.max(z,z2)+.5+half)-1;
            int minY=Math.min(y,y+dy), maxY=(int)Math.ceil(Math.max(y,y+dy)+(distance>1?1:0)+body.height())-1;
            for(int cy=minY;cy<=maxY;cy++) for(int cz=minZ;cz<=maxZ;cz++) for(int cx=minX;cx<=maxX;cx++) {
                PackedFacts fact=sourceFacts(cx,cy,cz,null);
                if(fact!=null && fact.geometry().hasDoors() && fact.geometry().shape(cellIndex(Math.floorMod(cx,16),Math.floorMod(cy,16),Math.floorMod(cz,16))).isDoor())
                    return collision.open(body,x,y,z,direction,distance,dy);
            }
            return true;
        }

        boolean volumeOpen(int x,
                           int y,
                           int z,
                           GeometryKey geometry,
                           BuildScratch scratch) {
            int flags = flags(x, y, z, scratch);
            if ((flags & VOLUME_OPEN) == 0) return false;
            PackedFacts source = sourceFacts(x, y, z, scratch);
            if (source == null || (flags & EXACT_REQUIRED) == 0) return true;
            SectionGeometry sectionGeometry = source.geometry();
            if (sectionGeometry == null) return false;
            return !sectionGeometry.occupied(cellIndex(
                    Math.floorMod(x, SIDE),
                    Math.floorMod(y, SIDE),
                    Math.floorMod(z, SIDE)
            ), geometry.width(), geometry.height(), geometry.canOpenDoors());
        }

        @Nullable
        private PackedFacts sourceFacts(int x, int y, int z, BuildScratch scratch) {
            int sectionX = Math.floorDiv(x, SIDE);
            int sectionY = Math.floorDiv(y, SIDE);
            int sectionZ = Math.floorDiv(z, SIDE);
            if (sectionX == 0 && sectionY == 0 && sectionZ == 0) return center;
            if (Math.abs(sectionX) > 1 || Math.abs(sectionY) > 1 || Math.abs(sectionZ) > 1) {
                return null;
            }
            int encoded = haloIndex(sectionX, sectionY, sectionZ);
            int index = Arrays.binarySearch(offsets, (byte) encoded);
            return index < 0 ? null : facts[index];
        }

        private void populateFullCollisionRows(GeometryKey geometry,
                                               BuildScratch scratch) {
            for (int y = 0; y < SIDE; y++) {
                for (int z = 0; z < SIDE; z++) {
                    int full = 0;
                    for (int x = -1; x <= SIDE; x++) {
                        if (!volumeOpen(x, y, z, geometry, scratch)) {
                            full |= 1 << (x + 1);
                        }
                    }
                    scratch.fullCollisionRows[(y + 1) * SEALED_SIDE + z + 1] = full << 1;
                }
            }
            for (Direction face : Direction.values()) {
                copyFullCollisionFace(face, geometry, scratch);
            }
        }

        private void copyFullCollisionFace(Direction face,
                                           GeometryKey geometry,
                                           BuildScratch scratch) {
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
                    if (!sourceFlagsOpen(source, sourceX, sourceY, sourceZ, geometry, scratch)) {
                        scratch.fullCollisionRows[(targetY + 1) * SEALED_SIDE + targetZ + 1]
                                |= 1 << (targetX + 1);
                    }
                }
            }
        }

        private boolean sourceFlagsOpen(PackedFacts source,
                                        int x,
                                        int y,
                                        int z,
                                        GeometryKey geometry,
                                        BuildScratch scratch) {
            int flags = source.flags(cellIndex(x, y, z));
            if ((flags & VOLUME_OPEN) == 0) return false;
            if ((flags & EXACT_REQUIRED) == 0) return true;
            SectionGeometry sectionGeometry = source.geometry();
            return sectionGeometry != null
                    && !sectionGeometry.occupied(cellIndex(x, y, z),
                    geometry.width(), geometry.height(), geometry.canOpenDoors());
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
        private final RawRows rawRows = new RawRows();
        private final char[] labels = new char[CELL_COUNT];
        private final int[] queue = new int[CELL_COUNT];
        private final int[] runStarts = new int[256], runEnds = new int[256];
        private final long[] componentMetadata = new long[CELL_COUNT];
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

        long[] localPrismMask() {
            return prismMask;
        }

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

        synchronized void clearCachedRows() {
            rawRows.clear();
        }

        int retainedBytes() {
            return labels.length * Character.BYTES + (queue.length + runStarts.length + runEnds.length) * Integer.BYTES
                    + componentMetadata.length * Long.BYTES
                    + (legalMask.length + prismMask.length) * Long.BYTES
                    + (volumeRows.length + groundRows.length + erodedRows.length
                    + fullCollisionRows.length) * Integer.BYTES
                    + haloUsed.length + (parentCounts.length + parentCursors.length
                    + parentOrder.length + parentStackNodes.length + parentStackEdges.length
                    + parentComponents.length) * Integer.BYTES + parentVisited.length
                    + rawRows.retainedBytes();
        }
    }

    /** Shared only by the serial build permit; entries contain no source or body references. */
    private static final class RawRows {
        private static final int MAX_SOURCES = 64;
        // PackedFacts is final and inherits Object equality, so weak keys use source identity.
        private final WeakHashMap<PackedFacts, Rows> rows = new WeakHashMap<>();
        private final ArrayDeque<WeakReference<PackedFacts>> order = new ArrayDeque<>();

        private static final class Rows {
            private final long[] values = new long[SIDE * SIDE];
            private final long[] ready = new long[SIDE * SIDE / Long.SIZE];
        }

        private void clear() {
            rows.clear();
            order.clear();
        }

        private int retainedBytes() {
            return 1024 + rows.size() * 2304;
        }

        private static long compact(long word) {
            long value = word & 0x1111111111111111L;
            value = (value | value >>> 3) & 0x0303030303030303L;
            value = (value | value >>> 6) & 0x000F000F000F000FL;
            value = (value | value >>> 12) & 0x000000FF000000FFL;
            return (value | value >>> 24) & 0xffffL;
        }

        private long row(PackedFacts source, int y, int z) {
            Rows entry = rows.get(source);
            if (entry == null) {
                while (!order.isEmpty()
                        && (order.peekFirst().get() == null || order.size() >= MAX_SOURCES)) {
                    PackedFacts removed = order.removeFirst().get();
                    if (removed != null) rows.remove(removed);
                }
                entry = new Rows();
                rows.put(source, entry);
                order.addLast(new WeakReference<>(source));
            }
            int index = (y << 4) | z;
            if ((entry.ready[index >>> 6] & (1L << index)) == 0) {
                long packed = source.packedRow(index);
                entry.values[index] = compact(packed) | compact(packed >>> 1) << 16
                        | compact(packed >>> 2) << 32 | compact(packed >>> 3) << 48;
                entry.ready[index >>> 6] |= 1L << index;
            }
            return entry.values[index];
        }

        private static int openableDoorBits(PackedFacts source,int y,int z,int remaining) {
            if (source==null || !source.geometry().hasDoors()) return 0;
            int doors=0;
            while(remaining!=0) {
                int x=Integer.numberOfTrailingZeros(remaining);
                remaining&=remaining-1;
                if(source.geometry().shape(cellIndex(x,y&15,z&15)).openableDoor()) doors|=1<<x;
            }
            return doors;
        }

        private void fill(BuildInput input, GeometryKey body, BuildScratch scratch, GraphReuse.Rows donor, GraphReuse reuse) {
            long copied=0, unknown=0, cold=0, exactVolumeChecks=0, exactGroundChecks=0, doorlessGroundBits=0, doorScanNanos=0;
            int width = body.widthCells();
            int minimum = -(width >>> 1);
            int maximum = minimum + ((width & 1) == 0 ? 1 : 0) + width - 1;
            int maxY = SIDE + body.heightCells() - (body.channel() == Channel.GROUND ? 1 : 2);
            boolean doorGround = input.hasDoors && body.canOpenDoors();
            for (int y = 0; y <= maxY; y++) {
                for (int z = minimum; z <= SIDE - 1 + maximum; z++) {
                    int volume = 0;
                    int ground = 0;
                    int sy = Math.floorDiv(y, SIDE);
                    int sz = Math.floorDiv(z, SIDE);
                    for (int sx = Math.floorDiv(minimum, SIDE);
                         sx <= Math.floorDiv(SIDE - 1 + maximum, SIDE); sx++) {
                        int from = Math.max(minimum, sx * SIDE);
                        int to = Math.min(SIDE - 1 + maximum, sx * SIDE + SIDE - 1);
                        int localMask = ((1 << (to - from + 1)) - 1) << Math.floorMod(from, SIDE);
                        PackedFacts source;
                        if ((sx | sy | sz) == 0) {
                            source = input.center;
                        } else {
                            int index = Arrays.binarySearch(input.offsets, (byte) haloIndex(sx, sy, sz));
                            if (index < 0) {
                                // groundOpen can still read an already loaded halo below a missing section.
                                if (!doorGround || y >= SIDE) continue;
                                source = null;
                            } else {
                                scratch.haloUsed[index] = true;
                                source = input.facts[index];
                            }
                        }
                        long raw = source == null ? 0 : row(source, y & 15, z & 15);
                        int v = (int) raw & 0xffff;
                        int g = (int) (raw >>> 16) & 0xffff;
                        int fluid = (int) (raw >>> 32) & 0xffff;
                        int exact = (int) (raw >>> 48) & 0xffff;
                        // Match flags(): only center y=0 infers ground support from the lower halo.
                        if ((sx | sy | sz) == 0 && y == 0 && (v & localMask) != 0) {
                            int lower = Arrays.binarySearch(input.offsets, (byte) haloIndex(0, -1, 0));
                            if (lower >= 0) {
                                scratch.haloUsed[lower] = true;
                                long below = row(input.facts[lower], SIDE - 1, z);
                                g |= v & (~(int) below | (int) (below >>> 48));
                            }
                        }
                        int accepted = localMask & (body.acceptsFluid() ? 0xffff : ~fluid);
                        int volumeKnown=0, groundKnown=0;
                        // Ground reads at y=0/1 can touch the lower halo through flags().
                        if (donor!=null && y>=2) {
                            int shared = localMask & (body.acceptsFluid()==donor.geometry.acceptsFluid() ? 0xffff : ~fluid);
                            boolean sameDoor=body.canOpenDoors()==donor.geometry.canOpenDoors() || !input.hasDoors;
                            boolean samePhysical=body.width()==donor.geometry.width() && body.height()==donor.geometry.height();
                            // At these heights, both current and below in the ground rule are in the center section.
                            boolean timeDoors=!sameDoor && reuse.diagnostics();
                            long doorStart=timeDoors?System.nanoTime():0;
                            int here=sameDoor?0:openableDoorBits(source,y,z,shared);
                            volumeKnown=shared & (samePhysical ? ~here : ~exact);
                            if(y<SIDE) {
                                groundKnown=sameDoor?shared:shared & ~(here|openableDoorBits(source,y-1,z,shared));
                                if(!sameDoor) doorlessGroundBits+=Integer.bitCount(groundKnown);
                            }
                            if(timeDoors) doorScanNanos+=System.nanoTime()-doorStart;
                            copied+=Integer.bitCount(volumeKnown)+Integer.bitCount(groundKnown);
                            unknown+=Integer.bitCount(localMask & ~volumeKnown)
                                    +(y<SIDE?Integer.bitCount(localMask & ~groundKnown):0);
                        } else cold+=Integer.bitCount(localMask)*(y<SIDE?2:1);
                        if (doorGround && y < SIDE) {
                            int adjusted = 0;
                            for (int pending = accepted & ~groundKnown; pending != 0; pending &= pending - 1) {
                                int x = Integer.numberOfTrailingZeros(pending);
                                int flags = ((v >>> x) & 1) * VOLUME_OPEN
                                        | ((g >>> x) & 1) * GROUND_OPEN
                                        | ((fluid >>> x) & 1) * FLUID
                                        | ((exact >>> x) & 1) * EXACT_REQUIRED;
                                exactGroundChecks++;
                                if (input.groundOpen(sx * SIDE + x, y, z, flags, body, scratch)) {
                                    adjusted |= 1 << x;
                                }
                            }
                            g = adjusted;
                        }
                        v &= accepted;
                        g &= accepted;
                        for (int pending = v & exact & ~volumeKnown; pending != 0; pending &= pending - 1) {
                            int x = Integer.numberOfTrailingZeros(pending);
                            exactVolumeChecks++;
                            if (!input.volumeOpen(sx * SIDE + x, y, z, body, scratch)) v &= ~(1 << x);
                        }
                        if (volumeKnown!=0) v=(v&~volumeKnown)|(Short.toUnsignedInt(donor.volume[y*SIDE+z])&volumeKnown);
                        if (groundKnown!=0) g=(g&~groundKnown)|(Short.toUnsignedInt(donor.ground[y*SIDE+z])&groundKnown);
                        int shift = sx * SIDE - EXTENDED_MIN;
                        volume |= shift >= 0 ? v << shift : v >>> -shift;
                        ground |= shift >= 0 ? g << shift : g >>> -shift;
                    }
                    scratch.volumeRows[y * EXTENDED_SIDE + z - EXTENDED_MIN] = volume;
                    if (y < SIDE) scratch.groundRows[y * EXTENDED_SIDE + z - EXTENDED_MIN] = ground;
                }
            }
            if(reuse!=null && reuse.enabled()) {
                reuse.mark("base.rawBitsCopied",copied);
                reuse.mark("base.doorlessGroundBits",doorlessGroundBits);
                reuse.mark("base.doorScanNanos",doorScanNanos);
                reuse.mark("base.rawUnknownBits",unknown);
                reuse.mark("base.rawColdBits",cold);
                reuse.mark("base.volumeChecks",exactVolumeChecks);
                reuse.mark("base.groundChecks",exactGroundChecks);
            }
        }
    }

    /** Compact persistent form of the four structural flags for each cell. */
    static final class PackedFacts {
        private static final PackedFacts ALL_AIR = createAllAir();
        private final byte[] data;
        private final int uniformFlags;
        private final long fingerprint;
        private final SectionGeometry geometryFacts;
        private final int exactOpenLayers;
        private final int fluidLayers;
        private final short[] doorRows;

        private PackedFacts(byte[] data,
                            int uniformFlags,
                            long fingerprint,
                            SectionGeometry geometryFacts) {
            this.data = data;
            this.uniformFlags = uniformFlags;
            this.fingerprint = fingerprint;
            this.geometryFacts = Objects.requireNonNull(geometryFacts, "geometryFacts");
            int exact = 0, fluid = 0;
            if (data != null) {
                for (int index = 0; index < data.length; index++) {
                    int value = Byte.toUnsignedInt(data[index]);
                    int layer = 1 << (index >>> 7);
                    if ((value & 9) == 9 || (value & 144) == 144) exact |= layer;
                    if ((value & 68) != 0) fluid |= layer;
                }
            }
            exactOpenLayers = exact;
            fluidLayers = fluid;
            doorRows = geometryFacts.hasDoors() ? new short[SIDE * SIDE] : null;
            if (doorRows != null) {
                for (int y = 0; y < SIDE; y++) for (int z = 0; z < SIDE; z++) {
                    for (int bits = geometryFacts.nonEmptyRow(y, z); bits != 0; bits &= bits - 1) {
                        int x = Integer.numberOfTrailingZeros(bits);
                        if (geometryFacts.shape(cellIndex(x, y, z)).isDoor()) doorRows[y * SIDE + z] |= (short) (1 << x);
                    }
                }
            }
        }

        static PackedFacts fromBytes(byte[] packed) {
            Objects.requireNonNull(packed, "packed");
            if (packed.length != PACKED_FACT_BYTES) {
                throw new IllegalArgumentException("packed facts must contain exactly 2048 bytes");
            }
            byte[] cells = new byte[CELL_COUNT];
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                int value = Byte.toUnsignedInt(packed[cell >>> 1]);
                cells[cell] = (byte) ((cell & 1) == 0 ? value & VALID_FLAGS : value >>> 4);
            }
            return fromBytes(packed, SectionGeometry.fromFlags(cells));
        }

        static PackedFacts fromBytes(byte[] packed, SectionGeometry geometry) {
            Objects.requireNonNull(packed, "packed");
            Objects.requireNonNull(geometry, "geometry");
            if (packed.length != PACKED_FACT_BYTES) {
                throw new IllegalArgumentException("packed facts must contain exactly 2048 bytes");
            }
            byte[] copy = packed.clone();
            return new PackedFacts(copy, -1,
                    fingerprintPacked(copy) ^ Long.rotateLeft(geometry.fingerprint(), 17),
                    geometry);
        }

        static PackedFacts allAir() {
            return ALL_AIR;
        }

        PackedFacts independentIdentity() {
            return new PackedFacts(data, uniformFlags, fingerprint, geometryFacts);
        }

        private static PackedFacts createAllAir() {
            byte[] cells = new byte[CELL_COUNT];
            Arrays.fill(cells, (byte) VOLUME_OPEN);
            return new PackedFacts(null, VOLUME_OPEN, fingerprintCells(cells),
                    SectionGeometry.allAir());
        }

        static PackedFacts fromCells(byte[] cells) {
            Objects.requireNonNull(cells, "cells");
            if (cells.length != CELL_COUNT) {
                throw new IllegalArgumentException("facts must contain exactly 4096 cells");
            }
            return fromCells(cells, fingerprintCells(cells), SectionGeometry.fromFlags(cells));
        }

        static PackedFacts fromCells(byte[] cells, SectionGeometry geometry) {
            Objects.requireNonNull(cells, "cells");
            Objects.requireNonNull(geometry, "geometry");
            if (cells.length != CELL_COUNT) {
                throw new IllegalArgumentException("facts must contain exactly 4096 cells");
            }
            return fromCells(cells, fingerprintCells(cells), geometry);
        }

        private static PackedFacts fromCells(byte[] cells,
                                             long fingerprint,
                                             SectionGeometry geometry) {
            byte[] packed = new byte[PACKED_FACT_BYTES];
            for (int cell = 0; cell < CELL_COUNT; cell += 2) {
                packed[cell >>> 1] = (byte) ((cells[cell] & VALID_FLAGS)
                        | ((cells[cell + 1] & VALID_FLAGS) << 4));
            }
            long combined = fingerprint ^ Long.rotateLeft(geometry.fingerprint(), 17);
            return new PackedFacts(packed, -1, combined, geometry);
        }

        private long packedRow(int row) {
            if (uniformFlags >= 0) return 0x1111111111111111L * uniformFlags;
            int offset = row << 3;
            long packed = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                packed |= (long) Byte.toUnsignedInt(data[offset + index]) << (index * Byte.SIZE);
            }
            return packed;
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

        byte[] cells() {
            byte[] cells = new byte[CELL_COUNT];
            if (uniformFlags >= 0) {
                Arrays.fill(cells, (byte) uniformFlags);
                return cells;
            }
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                cells[cell] = (byte) flags(cell);
            }
            return cells;
        }

        PackedFacts withChanges(Map<Integer, Byte> changes) {
            Objects.requireNonNull(changes, "changes");
            if (changes.isEmpty()) {
                return this;
            }
            byte[] cells = cells();
            boolean changed = false;
            for (Map.Entry<Integer, Byte> entry : changes.entrySet()) {
                int cell = entry.getKey();
                int flags = Byte.toUnsignedInt(entry.getValue());
                if (cell < 0 || cell >= CELL_COUNT) {
                    throw new IllegalArgumentException("cell index is outside the section");
                }
                if ((flags & ~VALID_FLAGS) != 0) {
                    throw new IllegalArgumentException("cell contains unknown fact flags");
                }
                if (cells[cell] != (byte) flags) {
                    cells[cell] = (byte) flags;
                    changed = true;
                }
            }
            return changed ? fromCells(cells) : this;
        }

        boolean contentEquals(PackedFacts other) {
            Objects.requireNonNull(other, "other");
            if (this == other) {
                return true;
            }
            if (fingerprint != other.fingerprint) {
                return false;
            }
            if (!geometryFacts.contentEquals(other.geometryFacts)) {
                return false;
            }
            for (int cell = 0; cell < CELL_COUNT; cell++) {
                if (flags(cell) != other.flags(cell)) {
                    return false;
                }
            }
            return true;
        }

        long fingerprint() {
            return fingerprint;
        }

        SectionGeometry geometry() {
            return geometryFacts;
        }

        int retainedBytes() {
            return (data == null ? 48 : 48 + data.length) + geometryFacts.retainedBytes()
                    + (doorRows == null ? 0 : 16 + doorRows.length * Short.BYTES);
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
