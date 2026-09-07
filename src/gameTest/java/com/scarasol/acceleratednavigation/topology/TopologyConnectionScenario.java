package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch.Status;
import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import static com.scarasol.acceleratednavigation.topology.TopologyTestBridge.*;

/** L01/L02 compare known world anchors with primitives, parent contractions and a public query. */
public final class TopologyConnectionScenario implements AutoCloseable {
    private final TopologyService service;
    private final ServerLevel level;
    private final ProductionRemediationPlan.Target target;
    private final boolean mutated, volume, bands, allowed, unconditional;
    private final Direction direction;
    private final BlockPos start, goal;
    private final List<BlockPos> starts, goals;
    private final Set<SectionPos> sections = new HashSet<>();
    private final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    private final Map<SectionPos, BaseClusterTopology> bases = new LinkedHashMap<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final BaseClusterTopology.TraversalProfile profile;
    private final BaseClusterTopology.BuildScratch scratch = new BaseClusterTopology.BuildScratch();
    private TopologyService.MacroRequest query;
    private int stage, changedTick;

    public TopologyConnectionScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; this.target = target; mutated = "mutated".equals(control);
        volume = target.kind().equals("L01/volume"); bands = target.kind().startsWith("L02");
        direction = Direction.byName(target.parameters().get("direction"));
        if (direction == null) throw new IllegalArgumentException("missing direction");
        int inset = bands ? Integer.parseInt(target.parameters().get("inset")) : 0;
        int distance = volume ? 1 : bands ? inset + 1 : Integer.parseInt(target.parameters().get("distance"));
        int dy = volume ? direction.getStepY() : Integer.parseInt(target.parameters().get("dy"));
        String placement = target.parameters().getOrDefault("placement", "section-boundary");
        int boundary = placement.equals("parent-boundary") ? 2336 : 2320;
        int x = 2312, y = 296, z = 2312;
        if (!placement.equals("inside")) {
            if (direction.getStepX() != 0) x = direction.getStepX() > 0 ? boundary - 1 - inset : boundary + inset;
            if (direction.getStepZ() != 0) z = direction.getStepZ() > 0 ? boundary - 1 - inset : boundary + inset;
            if (direction.getStepY() != 0) y = direction.getStepY() > 0 ? 303 - inset : 304 + inset;
        }
        start = new BlockPos(x, y, z);
        boolean verticalBands = bands && direction.getAxis().isVertical();
        goal = volume ? start.relative(direction) : verticalBands ? start.offset(2, dy, 0)
                : start.offset(direction.getStepX() * distance, dy, direction.getStepZ() * distance);
        starts = bands && !verticalBands ? List.of(start, start.relative(direction.getOpposite())) : List.of(start);
        goals = verticalBands ? List.of(goal, start.offset(-2, dy, 0), start.offset(0, dy, 2), start.offset(0, dy, -2))
                : bands ? List.of(goal, goal.relative(direction).above()) : List.of(goal);
        allowed = !"forbidden".equals(target.parameters().get("capability"));
        unconditional = !volume && !bands && distance == 1 && dy == 0;
        int step = bands ? 1 : Math.max(0, dy), jump = bands ? 3 : distance, drop = bands ? 4 : Math.max(0, -dy);
        if (!allowed) {
            if (dy > 0) step = 0; else if (dy < 0) drop = 0; else jump = 0;
        }
        profile = new BaseClusterTopology.TraversalProfile(.6F, .9F, step, jump, drop, false);
        sections.addAll(SuperClusterTopology.childSections(SuperClusterTopology.originOf(SectionPos.of(start))));
        sections.addAll(SuperClusterTopology.childSections(SuperClusterTopology.originOf(SectionPos.of(goal))));
    }
    public Set<Long> chunkPositions() {
        Set<Long> result = new HashSet<>(); for (SectionPos section : sections) result.add(section.chunk().toLong()); return Set.copyOf(result);
    }
    public Map<String, Object> tick() {
        if (!allowed && unconditional) return Map.of("outcome", "NOT_APPLICABLE", "reason", "Unit horizontal movement has no disabling capability",
                "staticEvidence", Map.of("path", "src/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java",
                        "relationship", "Component flood fill retains unit flat movement even for MovementKey(0,0,0)"));
        if (stage == 0) {
            for (SectionPos section : sections) {
                LevelChunk chunk = chunks.computeIfAbsent(section.chunk().toLong(), ignored -> {
                    LevelChunk loaded = level.getChunk(section.x(), section.z()); TopologyService.onChunkLoaded(level, loaded); return loaded;
                });
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    chunk.setBlockState(new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z),
                            volume ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false);
                }
            }
            for (BlockPos anchor : java.util.stream.Stream.concat(starts.stream(), goals.stream()).toList()) {
                set(anchor, false); if (!volume) set(anchor.below(), true);
            }
            changedTick = level.getServer().getTickCount(); stage = 1; return null;
        }
        if (stage == 1) {
            if (level.getServer().getTickCount() <= changedTick || !settled() || !captureFacts()) return null;
            verifyClearance();
            verifyTopology();
            query = service.requestMacroQuery(level, UUID.randomUUID(), start, goal, channel(), profile, NavigationScheduler.Priority.ACTIVE);
            stage = 2; return null;
        }
        if (!query.future().isDone()) return null;
        boolean succeeded = query.future().join() != null;
        require(succeeded == allowed, "public query agrees with the enabled capability for the constructed anchors");
        if (!allowed) require(query.progress().failure() == MacroSearch.Failure.NO_STRUCTURAL_ROUTE, "disabled movement has no structural route");
        evidence.put("request", TopologyValidationAccess.requestIdentity(query)); evidence.put("progress", query.progress());
        if (mutated) {
            boolean detected = false;
            try { verifyRelation(!allowed, allowed, "qualification substitutes the primitive relation"); }
            catch (AssertionError expected) { detected = true; }
            require(detected, "connection validator detects its deliberate relation mutation"); evidence.put("mutationDetected", true);
        }
        evidence.put("outcome", "PASS"); return Map.copyOf(evidence);
    }

    private boolean captureFacts() {
        Map<SectionPos, TopologyStore.SectionRecord> facts = new LinkedHashMap<>();
        Object runtime = readField(service, "runtime");
        synchronized (readField(runtime, "runtimeLock")) {
            for (SectionPos section : sections) {
                Object entry = ((Map<?, ?>) readField(runtime, "clusters")).get(new TopologyWorkerRuntime.ClusterKey(level.dimension(), section));
                if (entry == null || !Boolean.TRUE.equals(invoke(entry, "current")) || readField(entry, "facts") == null) return false;
                facts.put(section, new TopologyStore.SectionRecord((long) readField(entry, "revision"), (BaseClusterTopology.PackedFacts) readField(entry, "facts")));
            }
        }
        for (var item : facts.entrySet()) {
            SectionPos section = item.getKey(); LevelChunk chunk = chunks.get(section.chunk().toLong());
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockPos position = new BlockPos(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
                require(item.getValue().facts().flags(x | z << 4 | y << 8) == TopologyGenerationProbe.independentFlags(chunk, position),
                        "published construction facts equal the independent world oracle");
            }
            byte[] offsets = new byte[26];
            BaseClusterTopology.PackedFacts[] neighbors = new BaseClusterTopology.PackedFacts[26];
            long[] versions = new long[26], fingerprints = new long[26];
            int count = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                var neighbor = facts.get(SectionPos.of(section.x() + dx, section.y() + dy, section.z() + dz));
                if (neighbor == null) continue;
                offsets[count] = (byte) BaseClusterTopology.haloIndex(dx, dy, dz);
                neighbors[count] = neighbor.facts(); versions[count] = neighbor.version();
                fingerprints[count++] = neighbor.facts().fingerprint();
            }
            var input = new BaseClusterTopology.BuildInput(item.getValue().facts(), java.util.Arrays.copyOf(offsets, count),
                    java.util.Arrays.copyOf(neighbors, count), java.util.Arrays.copyOf(versions, count), java.util.Arrays.copyOf(fingerprints, count));
            bases.put(section, BaseClusterTopology.build(section, item.getValue().version(), input, profile.geometry(channel()), scratch));
        }
        evidence.put("facts", facts.entrySet().stream().map(item -> Map.of("section", item.getKey().toString(), "version", item.getValue().version(),
                "fingerprint", item.getValue().facts().fingerprint())).toList());
        return true;
    }
    private void verifyClearance() {
        for (BlockPos anchor : java.util.stream.Stream.concat(starts.stream(), goals.stream()).toList()) {
            require(level.getBlockState(anchor).isAir(), "each anchor is physically clear");
            if (!volume) require(level.getBlockState(anchor.below()).is(Blocks.STONE), "each ground anchor has independent full support");
        }
        if (!volume) {
            Set<BlockPos> supports = new HashSet<>(); for (BlockPos anchor : starts) supports.add(anchor.below()); for (BlockPos anchor : goals) supports.add(anchor.below());
            for (BlockPos cell : BlockPos.betweenClosed(Math.min(start.getX(), goal.getX()), Math.min(start.getY(), goal.getY()), Math.min(start.getZ(), goal.getZ()),
                    Math.max(start.getX(), goal.getX()), Math.max(start.getY(), goal.getY()) + 1, Math.max(start.getZ(), goal.getZ()))) {
                require(supports.contains(cell) || level.getBlockState(cell).isAir(), "movement box has no obstruction apart from its endpoint supports");
            }
        }
    }
    private void verifyTopology() {
        BaseClusterTopology source = bases.get(SectionPos.of(start)), destination = bases.get(SectionPos.of(goal));
        int from = component(source, start), to = component(destination, goal);
        require(from >= 0 && to >= 0, "both constructed anchors have base components");
        boolean primitive = primitive(source, from, destination, to, profile.movement(channel()));
        verifyRelation(primitive, allowed, "directed primitive respects the selected capability");
        if (!volume && goal.getY() < start.getY() - 1) {
            require(!primitive(destination, to, source, from, new BaseClusterTopology.MovementKey(1, 2, 4)), "drop does not manufacture a reverse step");
        }
        SectionPos sourceOrigin = SuperClusterTopology.originOf(source.section()), targetOrigin = SuperClusterTopology.originOf(destination.section());
        BaseClusterTopology[] sourceChildren = children(sourceOrigin), targetChildren = children(targetOrigin);
        var parent = SuperClusterTopology.build(sourceOrigin, sourceChildren, profile.geometry(channel()), profile.movement(channel()), scratch);
        var neighbor = sourceOrigin.equals(targetOrigin) ? parent : SuperClusterTopology.build(targetOrigin, targetChildren,
                profile.geometry(channel()), profile.movement(channel()), scratch);
        int sourceAggregate = parent.aggregateId(source.section(), from), targetAggregate = neighbor.aggregateId(destination.section(), to);
        boolean parentRelation;
        if (sourceOrigin.equals(targetOrigin)) parentRelation = reachable(parent, sourceAggregate, targetAggregate);
        else {
            Direction face = face(sourceOrigin, targetOrigin);
            var crossing = parent.crossingIndex(face, neighbor, sourceChildren, targetChildren);
            parentRelation = false;
            for (int edge = crossing.edgeStart(sourceAggregate); edge < crossing.edgeEnd(sourceAggregate); edge++) {
                if (crossing.targetAggregate(edge) == targetAggregate) {
                    parentRelation = true;
                    require(crossing.face(edge) == face(source.section(), destination.section()), "parent crossing keeps its primitive face witness");
                }
            }
            if (allowed) require(parent.hasPotentialExit(sourceAggregate, targetOrigin), "a real crossing retains the parent exit");
        }
        verifyRelation(parentRelation, allowed, "parent contraction agrees with the primitive directed relation");
        if (!source.section().equals(destination.section()) && allowed) {
            require(source.mayExit(from, destination.section(), profile.movement(channel())), "a real primitive is never rejected by source bounds");
        }
        if (bands) verifyBands(source, destination);
        evidence.put("primitive", primitive); evidence.put("parentRelation", parentRelation);
        evidence.put("start", start.toShortString()); evidence.put("goal", goal.toShortString()); evidence.put("profile", profile);
    }
    private void verifyBands(BaseClusterTopology source, BaseClusterTopology destination) {
        var links = SuperClusterTopology.boundaryLinks(source, destination, direction);
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (BlockPos from : starts) for (BlockPos to : goals) {
            int distance = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
            int dy = to.getY() - from.getY();
            if (distance >= 1 && distance <= 3 && dy >= -4 && dy <= 1) expected.add(pair(from, to));
        }
        for (int component = 0; component < source.componentCount(); component++) for (int edge = links.edgeStart(component); edge < links.edgeEnd(component); edge++) {
            var materialized = materialize(links, edge, -1L);
            require(materialized.bandCount() == links.bandEnd(edge) - links.bandStart(edge), "output copies all requested bands");
            require(materialized.retainedBytes() == 40 + materialized.bandCount() * (Short.BYTES + 4 * Long.BYTES), "output bytes include descriptors and masks");
            for (int band = 0; band < materialized.bandCount(); band++) for (int word = 0; word < 4; word++) {
                long mask = materialized.maskWord(band, word);
                while (mask != 0) {
                    int bit = word * 64 + Long.numberOfTrailingZeros(mask); mask &= mask - 1;
                    int u = bit & 15, y = bit >> 4, inset = materialized.sourceInset(band);
                    BlockPos from = direction.getAxis().isVertical()
                            ? new BlockPos(source.section().minBlockX() + u,
                                    source.section().minBlockY() + (direction == Direction.UP ? 15 - inset : inset), source.section().minBlockZ() + y)
                            : direction.getAxis() == Direction.Axis.X
                            ? new BlockPos(source.section().minBlockX() + (direction == Direction.EAST ? 15 - inset : inset), source.section().minBlockY() + y, source.section().minBlockZ() + u)
                            : new BlockPos(source.section().minBlockX() + u, source.section().minBlockY() + y, source.section().minBlockZ() + (direction == Direction.SOUTH ? 15 - inset : inset));
                    Direction heading = materialized.horizontalDirection(band);
                    BlockPos to = from.offset(heading.getStepX() * materialized.horizontalDistance(band), materialized.verticalShift(band), heading.getStepZ() * materialized.horizontalDistance(band));
                    require(actual.add(pair(from, to)), "different bands never duplicate one directed movement");
                }
            }
            short[] descriptors = (short[]) readField(links, "descriptors");
            int index = links.bandStart(edge); short saved = descriptors[index];
            int copiedInset = materialized.sourceInset(0);
            try { descriptors[index] ^= 1; require(materialized.sourceInset(0) == copiedInset, "materialized output owns its descriptor copy"); }
            finally { descriptors[index] = saved; }
            long[] masks = (long[]) readField(links, "masks"); long savedMask = masks[index * 4];
            long copiedMask = materialized.maskWord(0, 0);
            try { masks[index * 4] ^= 1; require(materialized.maskWord(0, 0) == copiedMask, "materialized output owns its spatial mask copy"); }
            finally { masks[index * 4] = savedMask; }
            for (int band = links.bandStart(edge); band < links.bandEnd(edge); band++) {
                long selected = Long.lowestOneBit(links.capabilityMask(band));
                var filtered = materialize(links, edge, selected);
                int count = 0; for (int candidate = links.bandStart(edge); candidate < links.bandEnd(edge); candidate++) if ((links.capabilityMask(candidate) & selected) != 0) count++;
                require(filtered.bandCount() == count, "movement filtering preserves exactly the supported descriptors");
            }
        }
        require(actual.equals(expected), "decoded bands equal independently enumerated supported landing pairs: " + actual + " / " + expected);
        int arrayBytes = 0;
        for (String field : List.of("offsets", "targets", "bandOffsets")) arrayBytes += ((int[]) readField(links, field)).length * Integer.BYTES;
        arrayBytes += ((short[]) readField(links, "descriptors")).length * Short.BYTES;
        for (String field : List.of("capabilities", "masks")) arrayBytes += ((long[]) readField(links, field)).length * Long.BYTES;
        arrayBytes += ((float[]) readField(links, "lowerBounds")).length * Float.BYTES;
        require(links.retainedBytes() == 72 + arrayBytes, "boundary cache byte accounting includes every retained array");
        evidence.put("landingPairs", actual); evidence.put("boundaryBytes", links.retainedBytes());
    }
    private static MacroSearch.BoundaryTransition materialize(SuperClusterTopology.BoundaryLinks links, int edge, long movement) {
        var start = new MacroSearch.ExactEndpoint(0, BlockPos.ZERO, 1); var goal = new MacroSearch.ExactEndpoint(1, new BlockPos(1, 0, 0), 1);
        MacroSearch search = new MacroSearch(new MacroSearch.Graph() {
            @Override public MacroSearch.Endpoint start() { return start; }
            @Override public MacroSearch.Endpoint goal() { return goal; }
            @Override public boolean revisionsValid() { return true; }
            @Override public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
                if (from.id() == 0) output.addBoundary(1, goal, links.lowerBound(edge), links, edge, movement);
            }
        }, 1, 10);
        require(search.step(10) == Status.SUCCEEDED, "real MacroSearch materializes the selected boundary");
        return (MacroSearch.BoundaryTransition) search.result().connections().get(0).transition();
    }
    private static boolean primitive(BaseClusterTopology source, int from, BaseClusterTopology target, int to, BaseClusterTopology.MovementKey movement) {
        if (source.section().equals(target.section())) {
            if (from == to) return true;
            for (int edge = source.localEdgeStart(from); edge < source.localEdgeEnd(from); edge++) if (source.localEdgeTarget(edge) == to && source.localEdgeSupports(edge, movement)) return true;
        } else {
            var links = SuperClusterTopology.boundaryLinks(source, target, face(source.section(), target.section()));
            for (int edge = links.edgeStart(from); edge < links.edgeEnd(from); edge++) if (links.targetComponent(edge) == to && links.supports(edge, movement)) return true;
        }
        return false;
    }
    private static boolean reachable(SuperClusterTopology parent, int from, int to) {
        Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> pending = new ArrayDeque<>(); pending.add(from);
        while (!pending.isEmpty()) {
            int current = pending.removeFirst(); if (current == to) return true; if (!seen.add(current)) continue;
            for (int edge = parent.outgoingStart(current); edge < parent.outgoingEnd(current); edge++) pending.add(parent.outgoingTarget(edge));
        }
        return false;
    }
    private BaseClusterTopology[] children(SectionPos origin) { return SuperClusterTopology.childSections(origin).stream().map(bases::get).toArray(BaseClusterTopology[]::new); }
    private BaseClusterTopology.Channel channel() { return volume ? BaseClusterTopology.Channel.VOLUME : BaseClusterTopology.Channel.GROUND; }
    private static int component(BaseClusterTopology base, BlockPos point) { return base.componentAt(point.getX() & 15, point.getY() & 15, point.getZ() & 15); }
    private static Direction face(SectionPos from, SectionPos to) {
        if (from.x() != to.x()) return to.x() > from.x() ? Direction.EAST : Direction.WEST;
        if (from.z() != to.z()) return to.z() > from.z() ? Direction.SOUTH : Direction.NORTH;
        return to.y() > from.y() ? Direction.UP : Direction.DOWN;
    }
    private static String pair(BlockPos from, BlockPos to) { return from.toShortString() + ">" + to.toShortString(); }
    private void set(BlockPos point, boolean solid) { chunks.get(new ChunkPos(point).toLong()).setBlockState(point, solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), false); }
    private boolean settled() { return Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions()).get("settled")); }
    private static void verifyRelation(boolean actual, boolean expected, String message) { require(actual == expected, message); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        if (query != null) { query.cancel(); TopologyValidationAccess.forgetRequest(query); query = null; }
        for (LevelChunk chunk : chunks.values()) TopologyService.onChunkUnloaded(level, chunk);
        chunks.clear(); bases.clear();
    }
}
