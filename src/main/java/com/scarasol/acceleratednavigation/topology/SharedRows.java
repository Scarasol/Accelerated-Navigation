package com.scarasol.acceleratednavigation.topology;

import java.util.*;

/** Immutable section records; all temporary evaluators belong to one worker invocation. */
final class SharedRows {
    static final BaseClusterTopology.GeometryKey BASIS =
            new BaseClusterTopology.TraversalProfile(1, 2, 1, 0, 3, false, true)
                    .geometry(BaseClusterTopology.Channel.GROUND);
    record Query(BaseClusterTopology.GeometryKey body, BaseClusterTopology.MovementKey movement,
                 int start, int goal, int limit) {
        Query {
            Objects.requireNonNull(body); Objects.requireNonNull(movement);
            if (body.channel() != BaseClusterTopology.Channel.GROUND || body.width() > 1
                    || body.heightCells() < 2 || start < 0 || start >= 4096
                    || goal < 0 || goal >= 4096 || limit < 0 || limit > 4096)
                throw new IllegalArgumentException("unsupported shared section query");
        }
    }
    record Layer(RowStorage rows, byte[] legal, BitSet evaluated) {}
    record Change(Map<Integer, LocalKernel.Row> rows, byte[] legal) {}
    record Outcome(boolean candidate, String state, List<Integer> cells, Change change,
                   long evaluations, long replacements, long workNs) {}
    interface QueryHook {
        void beforeReconstruct(String phase, SharedRows rows, Query query, QueryScratch active) throws Exception;
    }
    final BaseClusterTopology.BuildInput raw, air;
    final Layer base;
    final List<int[]> doors;
    final Map<BaseClusterTopology.GeometryKey, Layer> layers;
    final long version;
    final long retainedBytes;

    private SharedRows(BaseClusterTopology.BuildInput raw, BaseClusterTopology.BuildInput air,
                       Layer base, List<int[]> doors,
                       Map<BaseClusterTopology.GeometryKey, Layer> layers, long version) {
        this.raw = raw; this.air = air; this.base = base; this.doors = doors;
        this.layers = Map.copyOf(layers); this.version = version;
        long bytes = layerBytes(base);
        for (Layer layer : layers.values()) bytes += layerBytes(layer);
        retainedBytes = bytes;
    }
    static SharedRows create(BaseClusterTopology.BuildInput raw) throws Exception {
        return create(raw, true);
    }
    static SharedRows create(BaseClusterTopology.BuildInput raw, boolean packed) throws Exception {
        var air = airInput(raw);
        var kernel = new LocalKernel(air, BASIS, new BaseClusterTopology.BuildScratch());
        var rows = new LocalKernel.Row[4096];
        for (int cell = 0; cell < rows.length; cell++) rows[cell] = kernel.outgoing(cell);
        var evaluated = new BitSet(4096); evaluated.set(0, 4096);
        var doors = new ArrayList<int[]>();
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                var facts = raw.atSection(dx, dy, dz);
                if (facts == null || !facts.geometry().hasDoors()) continue;
                for (int cell = 0; cell < 4096; cell++)
                    if (facts.geometry().shape(cell).openableDoor())
                        doors.add(new int[]{dx * 16 + BaseClusterTopology.x(cell),
                                dz * 16 + BaseClusterTopology.z(cell)});
            }
        return new SharedRows(raw, air, new Layer(RowStorage.create(rows, packed), kernel.legal.clone(), evaluated),
                List.copyOf(doors), Map.of(), 1);
    }
    static BaseClusterTopology.BuildInput airInput(BaseClusterTopology.BuildInput raw) {
        var halo = new TreeMap<Integer, BaseClusterTopology.PackedFacts>();
        for (int code = 0; code < 27; code++) {
            int dx = BaseClusterTopology.haloX(code), dy = BaseClusterTopology.haloY(code);
            int dz = BaseClusterTopology.haloZ(code);
            if ((dx | dy | dz) == 0) continue;
            var facts = raw.atSection(dx, dy, dz);
            if (facts != null) halo.put(code, airFacts(facts,raw.atSection(dx,dy-1,dz)));
        }
        return raw.withFacts(airFacts(raw.center(),raw.atSection(0,-1,0)), halo);
    }
    static BaseClusterTopology.PackedFacts airFacts(BaseClusterTopology.PackedFacts facts) {
        return airFacts(facts,null);
    }
    static BaseClusterTopology.PackedFacts airFacts(BaseClusterTopology.PackedFacts facts,
                                                   BaseClusterTopology.PackedFacts belowSection) {
        if (!facts.geometry().hasDoors() && (belowSection==null||!belowSection.geometry().hasDoors())) return facts;
        var flags = facts.cells(); var cells = new SectionGeometry.Cell[4096];
        for (int cell = 0; cell < 4096; cell++) {
            var shape = facts.geometry().shape(cell);
            var below = BaseClusterTopology.y(cell)>0 ? facts.geometry().shape(cell-256)
                    : belowSection==null ? null : belowSection.geometry().shape(cell+3840);
            cells[cell] = shape.openableDoor() ? SectionGeometry.empty() : new SectionGeometry.Cell(shape);
            if (shape.openableDoor()) {
                if ((flags[cell] & BaseClusterTopology.FLUID) != 0)
                    throw new SharedSectionCandidate.UnsupportedInputException("fluid inside removed door");
                flags[cell] = (byte) BaseClusterTopology.VOLUME_OPEN;
                if (below!=null&&!below.openableDoor()&&below.coordinateCount()>0)
                    flags[cell]|=BaseClusterTopology.GROUND_OPEN;
            } else if (below!=null&&below.openableDoor()&&shape.coordinateCount()==0) {
                // Packed flags cannot distinguish fluid's own support from door-added support.
                if((flags[cell]&BaseClusterTopology.FLUID)!=0)
                    throw new SharedSectionCandidate.UnsupportedInputException("ambiguous fluid support above removed door");
                flags[cell] &= ~BaseClusterTopology.GROUND_OPEN;
            }
        }
        return BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(cells));
    }
    boolean inherits(BaseClusterTopology.GeometryKey body) {
        return body.channel() == BASIS.channel() && body.width() <= BASIS.width()
                && body.height() <= BASIS.height() && body.heightCells() >= 2;
    }
    boolean sensitive(int cell, BaseClusterTopology.GeometryKey body) {
        if (body.canOpenDoors()) return false;
        int radius = Math.max(body.widthCells(), BASIS.widthCells()) / 2 + 4;
        int x = BaseClusterTopology.x(cell), z = BaseClusterTopology.z(cell);
        for (var door : doors)
            if (Math.abs(x - door[0]) <= radius && Math.abs(z - door[1]) <= radius) return true;
        return false;
    }
    private boolean legal(int cell, BaseClusterTopology.GeometryKey body) {
        var layer = layers.get(body);
        if (layer != null && layer.legal()[cell] != 0) return layer.legal()[cell] == 2;
        return inherits(body) && !sensitive(cell, body) && base.legal()[cell] == 2;
    }
    private RowStorage row(int cell, BaseClusterTopology.GeometryKey body) {
        var layer = layers.get(body);
        if (layer != null && layer.evaluated().get(cell)) return layer.rows();
        return inherits(body) && !sensitive(cell, body) ? base.rows() : null;
    }
    List<Integer> known(Query query) {
        if (!legal(query.start(), query.body()) || !legal(query.goal(), query.body())) return List.of();
        int[] parent = new int[4096], queue = new int[4096];
        Arrays.fill(parent, -1); parent[query.start()] = query.start();
        int head = 0, tail = 0; queue[tail++] = query.start();
        while (head < tail && parent[query.goal()] < 0) {
            int at = queue[head++]; var row = row(at, query.body());
            if (row != null) tail = expand(row, at, query.movement().capabilityMask(), parent, queue, tail);
        }
        return reconstruct(parent, query.start(), query.goal());
    }
    Outcome query(Query query) throws Exception {
        return queryImpl(query, null, null);
    }
    Outcome query(Query query, QueryScratch scratch) throws Exception {
        return query(query, scratch, null);
    }
    Outcome query(Query query, QueryScratch scratch, QueryHook hook) throws Exception {
        var active = Objects.requireNonNull(scratch).acquire();
        try {
            return queryImpl(query, active, hook);
        } finally {
            active.release();
        }
    }
    private List<Integer> known(Query query, QueryScratch active, QueryHook hook) throws Exception {
        int[] parent = active.parent, queue = active.queue;
        Arrays.fill(parent, -1);
        if (!legal(query.start(), query.body()) || !legal(query.goal(), query.body())) return List.of();
        parent[query.start()] = query.start();
        int head = 0, tail = 0; queue[tail++] = query.start();
        while (head < tail && parent[query.goal()] < 0) {
            int at = queue[head++]; var row = row(at, query.body());
            if (row != null) tail = expand(row, at, query.movement().capabilityMask(), parent, queue, tail);
        }
        if (hook != null) hook.beforeReconstruct("known", this, query, active);
        return reconstruct(parent, query.start(), query.goal());
    }
    private Outcome queryImpl(Query query, QueryScratch active, QueryHook hook) throws Exception {
        long started = System.nanoTime();
        var known = active == null ? known(query) : known(query, active, hook);
        if (!known.isEmpty()) return outcome(true, "CANDIDATE", known, Map.of(), new byte[0], 0, 0, started);
        if (query.limit() == 0)
            return outcome(false, "UNKNOWN_LIMIT", List.of(), Map.of(), new byte[0], 0, 0, started);
        var existing = layers.get(query.body());
        var evaluation = new Evaluation(query.body(), existing);
        var delta = new TreeMap<Integer, LocalKernel.Row>();
        if (!evaluation.legal(query.start()))
            return outcome(false, "NO_MACRO_CANDIDATE", List.of(), delta, evaluation.newLegal(), 0, 0, started);
        int[] parent = active == null ? new int[4096] : active.parent;
        int[] queue = active == null ? new int[4096] : active.queue;
        Arrays.fill(parent, -1); parent[query.start()] = query.start();
        int head = 0, tail = 0; queue[tail++] = query.start();
        long evaluations = 0, replacements = 0;
        while (head < tail) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("upgrade interrupted");
            int at = queue[head++];
            if (at == query.goal() && evaluation.legal(at)) {
                if (hook != null) hook.beforeReconstruct("local", this, query, active);
                return outcome(true, "CANDIDATE", reconstruct(parent, query.start(), query.goal()),
                        delta, evaluation.newLegal(), evaluations, replacements, started);
            }
            LocalKernel.Row exact = null;
            RowStorage cached = existing != null && existing.evaluated().get(at) ? existing.rows() : null;
            if (cached == null) {
                if (evaluations >= query.limit())
                    return outcome(false, "UNKNOWN_LIMIT", List.of(), delta, evaluation.newLegal(),
                            evaluations, replacements, started);
                exact = evaluation.kernel().outgoing(at); evaluations++; delta.put(at, exact);
                if (sensitive(at, query.body())) replacements++;
                if (inherits(query.body()) && !sensitive(at, query.body()))
                    for (int i = 0; i < base.rows().count(at); i++)
                        if ((base.rows().mask(at, i) & ~exact.mask(base.rows().target(at, i))) != 0)
                            throw new AssertionError("inherited row lost at " + at);
            }
            if (cached != null) {
                tail = expand(cached, at, query.movement().capabilityMask(), parent, queue, tail);
            } else {
                for (int i = 0; i < exact.targets().length; i++) {
                    int next = exact.targets()[i];
                    if (parent[next] < 0 && (exact.masks()[i] & query.movement().capabilityMask()) != 0) {
                        parent[next] = at; queue[tail++] = next;
                    }
                }
            }
        }
        return outcome(false, "NO_MACRO_CANDIDATE", List.of(), delta, evaluation.newLegal(),
                evaluations, replacements, started);
    }
    private static int expand(RowStorage rows, int at, long capability,
                              int[] parent, int[] queue, int tail) {
        if (rows.objects != null) {
            var row = rows.objects[at];
            if (row == null) return tail;
            for (int edge = 0; edge < row.targets().length; edge++) {
                int next = row.targets()[edge];
                if (parent[next] < 0 && (row.masks()[edge] & capability) != 0) {
                    parent[next] = at; queue[tail++] = next;
                }
            }
        } else {
            var block = rows.block(at);
            int local = at & (RowStorage.SIZE - 1);
            for (int edge = block.offsets[local], end = block.offsets[local + 1]; edge < end; edge++) {
                int next = block.targets[edge];
                if (parent[next] < 0 && (block.masks[edge] & capability) != 0) {
                    parent[next] = at; queue[tail++] = next;
                }
            }
        }
        return tail;
    }
    private final class Evaluation {
        final BaseClusterTopology.GeometryKey body;
        final Layer previous;
        LocalKernel local;
        Evaluation(BaseClusterTopology.GeometryKey body, Layer previous) {
            this.body = body; this.previous = previous;
        }
        LocalKernel kernel() {
            if (local == null) local = new LocalKernel(body.canOpenDoors() ? air : raw, body,
                    new BaseClusterTopology.BuildScratch());
            return local;
        }
        boolean legal(int cell) {
            if (previous != null && previous.legal()[cell] != 0) return previous.legal()[cell] == 2;
            return kernel().legal(cell);
        }
        byte[] newLegal() { return local == null ? new byte[0] : local.legal; }
    }
    private static Outcome outcome(boolean candidate, String state, List<Integer> cells,
                                   Map<Integer, LocalKernel.Row> rows, byte[] legal,
                                   long evaluations, long replacements, long started) {
        return new Outcome(candidate, state, List.copyOf(cells),
                new Change(Map.copyOf(rows), legal.clone()), evaluations, replacements,
                System.nanoTime() - started);
    }
    SharedRows merge(BaseClusterTopology.GeometryKey body, Change change) {
        if (change.rows().isEmpty() && change.legal().length == 0) return this;
        Layer previous = layers.get(body);
        var rows = previous == null ? RowStorage.empty(base.rows().blocks != null, base.rows()) : previous.rows();
        var legal = previous == null ? new byte[4096] : previous.legal().clone();
        var evaluated = previous == null ? new BitSet(4096) : (BitSet) previous.evaluated().clone();
        for (var entry : change.rows().entrySet()) {
            int cell = entry.getKey();
            if (evaluated.get(cell) && !rows.same(cell, entry.getValue()))
                throw new AssertionError("conflicting concurrent exact row");
            evaluated.set(cell);
        }
        for (int cell = 0; cell < change.legal().length; cell++) if (change.legal()[cell] != 0) {
            if (legal[cell] != 0 && legal[cell] != change.legal()[cell])
                throw new AssertionError("conflicting concurrent legal anchor");
            legal[cell] = change.legal()[cell];
        }
        var copy = new HashMap<>(layers); copy.put(body, new Layer(rows.merge(change.rows()), legal, evaluated));
        return new SharedRows(raw, air, base, doors, copy, version + 1);
    }
    static boolean same(LocalKernel.Row a, LocalKernel.Row b) {
        return Arrays.equals(a.targets(), b.targets()) && Arrays.equals(a.masks(), b.masks());
    }
    static List<Integer> reconstruct(int[] parent, int start, int goal) {
        if (parent[goal] < 0) return List.of();
        var cells = new ArrayList<Integer>();
        for (int at = goal;; at = parent[at]) { cells.add(at); if (at == start) break; }
        Collections.reverse(cells); return List.copyOf(cells);
    }
    static long layerBytes(Layer layer) {
        return 32L + layer.rows().estimatedBytes() + layer.legal().length + 512;
    }
}
