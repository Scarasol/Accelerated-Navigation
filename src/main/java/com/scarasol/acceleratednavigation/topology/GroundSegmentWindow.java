package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

/** A segment of a completed macro snapshot, independent of later world revisions. */
public final class GroundSegmentWindow implements AutoCloseable {
    private final Set<SectionPos> sections;
    private volatile MacroSearch.ComponentSnapshot membership;
    private final AtomicBoolean closed = new AtomicBoolean();
    public final MacroSearch.ComponentEndpoint destination;
    public final List<BlockPos> candidates;
    private final List<BlockPos> recoveryCandidates;

    private GroundSegmentWindow(Set<SectionPos> sections, MacroSearch.Corridor corridor,
            MacroSearch.ComponentEndpoint destination, List<BlockPos> candidates) {
        this.sections = Set.copyOf(sections);
        this.membership = corridor.component(destination);
        this.destination = destination;
        this.candidates = List.copyOf(candidates);
        var nearby = new LinkedHashSet<>(candidates);
        for (BlockPos seed : candidates) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 2) continue;
            BlockPos point = seed.offset(dx, 0, dz);
            if (membership.contains(point)) nearby.add(point);
        }
        recoveryCandidates = List.copyOf(nearby);
    }

    public Set<SectionPos> sections() { return sections; }
    /** Whether this retained window is open; later world changes do not close it. */
    public boolean current() { return !closed.get(); }
    public boolean contains(BlockPos position) {
        var cells = membership;
        return !closed.get() && cells != null && cells.contains(position);
    }
    public List<BlockPos> nearbyCandidates() { return recoveryCandidates; }
    public int interiorNeighbors(BlockPos position) {
        int result = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) if (contains(position.relative(direction))) result++;
        return result;
    }
    @Override public void close() { closed.set(true); membership = null; }

    static CompletableFuture<GroundSegmentWindow> capture(TopologyWorkerRuntime runtime,
            MacroSearch.Corridor corridor, BaseClusterTopology.TraversalProfile profile,
            NavigationScheduler.Priority priority, BlockPos actual, List<MacroSearch.Connection> edges,
            MacroSearch.ComponentEndpoint destination, BlockPos reachTarget, int accuracy) {
        Set<SectionPos> sections = new LinkedHashSet<>();
        sections.add(SectionPos.of(actual));
        sections.add(destination.section());
        for (var edge : edges) {
            sections.add(((MacroSearch.ComponentEndpoint)edge.from()).section());
            sections.add(((MacroSearch.ComponentEndpoint)edge.to()).section());
        }
        if (sections.size() > 9) return CompletableFuture.failedFuture(
                new IllegalArgumentException("segment exceeds nine sections"));
        CompletableFuture<GroundSegmentWindow> result = new CompletableFuture<>();
        try {
            runtime.submitWindowWork(priority, () -> {
                if (result.isDone()) return;
                try {
                    var candidates = new LinkedHashSet<BlockPos>();
                    if (reachTarget != null) {
                        var membership = corridor.component(destination);
                        for (int cell = 0; cell < 4096; cell++) {
                            BlockPos point = position(destination.section(), cell);
                            if (point.distManhattan(reachTarget) <= accuracy && membership.contains(point)) candidates.add(point);
                        }
                        var ordered = new ArrayList<>(candidates);
                        ordered.sort(Comparator.comparingInt((BlockPos p) -> p.distManhattan(reachTarget))
                                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
                        candidates = new LinkedHashSet<>(ordered);
                    } else if (!edges.isEmpty()) enumerate(corridor, edges.get(edges.size()-1), profile, candidates);
                    var window = new GroundSegmentWindow(sections, corridor, destination, List.copyOf(candidates));
                    if (!result.complete(window)) window.close();
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static void enumerate(MacroSearch.Corridor corridor, MacroSearch.Connection edge,
            BaseClusterTopology.TraversalProfile profile, Set<BlockPos> result) {
        var source = (MacroSearch.ComponentEndpoint)edge.from();
        var target = (MacroSearch.ComponentEndpoint)edge.to();
        var sourceCells = corridor.component(source);
        var targetCells = corridor.component(target);
        if (edge.transition() instanceof MacroSearch.BoundaryTransition bands) {
            for (int band = 0; band < bands.bandCount(); band++) for (int bit = 0; bit < 256; bit++) {
                if ((bands.maskWord(band, bit >>> 6) & (1L << bit)) == 0) continue;
                BlockPos from = position(source.section(), SuperClusterTopology.faceCell(
                        bands.face(), bit & 15, bit >>> 4, bands.sourceInset(band)));
                Direction direction = bands.horizontalDirection(band);
                BlockPos to = from.offset(direction == null ? 0 : direction.getStepX()*bands.horizontalDistance(band),
                        bands.verticalShift(band), direction == null ? 0 : direction.getStepZ()*bands.horizontalDistance(band));
                if (sourceCells.contains(from) && targetCells.contains(to)) result.add(to);
            }
        } else {
            for (int cell = 0; cell < 4096; cell++) {
                BlockPos from = position(source.section(), cell);
                if (!sourceCells.contains(from)) continue;
                for (Direction direction : Direction.Plane.HORIZONTAL)
                    for (int dy = -profile.maxDrop(); dy <= profile.maxStep(); dy++) {
                        BlockPos to = from.relative(direction).offset(0,dy,0);
                        if (targetCells.contains(to)) result.add(to);
                    }
            }
        }
    }

    private static BlockPos position(SectionPos section, int cell) {
        return new BlockPos(section.minBlockX()+BaseClusterTopology.x(cell),
                section.minBlockY()+BaseClusterTopology.y(cell), section.minBlockZ()+BaseClusterTopology.z(cell));
    }
}
