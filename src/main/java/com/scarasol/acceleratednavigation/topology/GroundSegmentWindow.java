package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A small current window; the retained views belong to this result until close. */
public final class GroundSegmentWindow implements AutoCloseable {
    private final Map<SectionPos, TopologyWorkerRuntime.BaseGraphView> views;
    private final AtomicBoolean closed = new AtomicBoolean();
    public final MacroSearch.ComponentEndpoint destination;
    public final List<BlockPos> candidates;
    private final List<BlockPos> recoveryCandidates;

    private GroundSegmentWindow(Map<SectionPos, TopologyWorkerRuntime.BaseGraphView> views,
            MacroSearch.ComponentEndpoint destination, List<BlockPos> candidates) {
        this.views = Map.copyOf(views);
        this.destination = destination;
        this.candidates = List.copyOf(candidates);
        var nearby=new LinkedHashSet<>(candidates);
        var graph=views.get(destination.section()).topology();
        for(BlockPos seed:candidates)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            if(Math.abs(dx)+Math.abs(dz)>2)continue;
            BlockPos point=seed.offset(dx,0,dz);
            if(SectionPos.of(point).equals(destination.section())
                    &&component(graph,point)==destination.componentId())nearby.add(point);
        }
        recoveryCandidates=List.copyOf(nearby);
    }

    public boolean current() {
        return !closed.get() && views.values().stream().allMatch(TopologyWorkerRuntime.BaseGraphView::current);
    }
    public Set<SectionPos> sections() { return views.keySet(); }
    public BaseClusterTopology topology(SectionPos section) { return views.get(section).topology(); }

    public boolean contains(BlockPos position) {
        if (!SectionPos.of(position).equals(destination.section())) return false;
        var view = views.get(destination.section());
        return current() && view.topology().signature() == destination.revision()
                && component(view.topology(), position) == destination.componentId();
    }

    public List<BlockPos> nearbyCandidates() {
        return recoveryCandidates;
    }

    public int interiorNeighbors(BlockPos position) {
        int result=0;
        for (Direction direction:Direction.Plane.HORIZONTAL) if (contains(position.relative(direction))) result++;
        return result;
    }

    @Override public void close() {
        if (closed.compareAndSet(false,true)) views.values().forEach(TopologyWorkerRuntime.BaseGraphView::close);
    }

    static CompletableFuture<GroundSegmentWindow> capture(TopologyWorkerRuntime runtime,
            ResourceKey<Level> dimension, BaseClusterTopology.TraversalProfile profile,
            NavigationScheduler.Priority priority, BlockPos actual, List<MacroSearch.Connection> edges,
            MacroSearch.ComponentEndpoint destination, BlockPos reachTarget, int accuracy) {
        Set<SectionPos> sections = new LinkedHashSet<>();
        sections.add(SectionPos.of(actual));
        sections.add(destination.section());
        for (var edge:edges) {
            sections.add(((MacroSearch.ComponentEndpoint)edge.from()).section());
            sections.add(((MacroSearch.ComponentEndpoint)edge.to()).section());
        }
        if (sections.size()>4) return CompletableFuture.failedFuture(new IllegalArgumentException("segment exceeds four sections"));
        Map<SectionPos,CompletableFuture<TopologyWorkerRuntime.BaseGraphView>> pending=new LinkedHashMap<>();
        CompletableFuture<GroundSegmentWindow> result=new CompletableFuture<>();
        for (SectionPos section:sections) pending.put(section,runtime.requestBaseGraph(dimension,section,
                profile.geometry(BaseClusterTopology.Channel.GROUND),priority));
        result.whenComplete((value,failure)-> {
            if (result.isCompletedExceptionally()) pending.values().forEach(f-> {
                f.whenComplete((view,error)-> { if (view!=null) view.close(); });
                f.cancel(false);
            });
        });
        CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new)).whenComplete((unused,failure)-> {
            Map<SectionPos,TopologyWorkerRuntime.BaseGraphView> views=new LinkedHashMap<>();
            pending.forEach((section,future)-> {
                if (!future.isCompletedExceptionally() && !future.isCancelled()) {
                    var view=future.getNow(null);
                    if (view!=null) views.put(section,view);
                }
            });
            if (failure!=null || result.isDone()) {
                views.values().forEach(TopologyWorkerRuntime.BaseGraphView::close);
                if (!result.isDone()) result.completeExceptionally(failure);
                return;
            }
            try {
                runtime.submitWindowWork(priority,()-> {
                    GroundSegmentWindow window=null;
                    try {
                        for (var view:views.values()) if (!view.current()) throw new IllegalStateException("stale segment view");
                        for (var edge:edges) {
                            validate(views,(MacroSearch.ComponentEndpoint)edge.from());
                            validate(views,(MacroSearch.ComponentEndpoint)edge.to());
                        }
                        validate(views,destination);
                        var candidates=new LinkedHashSet<BlockPos>();
                        if (reachTarget!=null) {
                            var graph=views.get(destination.section()).topology();
                            for (int cell=0;cell<4096;cell++) {
                                BlockPos point=position(destination.section(),cell);
                                if (point.distManhattan(reachTarget)<=accuracy
                                        && component(graph,point)==destination.componentId()) candidates.add(point);
                            }
                            var ordered=new ArrayList<>(candidates);
                            ordered.sort(Comparator.comparingInt((BlockPos p)->p.distManhattan(reachTarget))
                                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
                            candidates=new LinkedHashSet<>(ordered);
                        } else if(!edges.isEmpty()) {
                            var edge=edges.get(edges.size()-1);
                            enumerate(views,edge,profile,candidates);
                        }
                        window=new GroundSegmentWindow(views,destination,List.copyOf(candidates));
                        if (!result.complete(window)) window.close();
                    } catch (Throwable error) {
                        if (window!=null) window.close(); else views.values().forEach(TopologyWorkerRuntime.BaseGraphView::close);
                        result.completeExceptionally(error);
                    }
                });
            } catch (RuntimeException error) {
                views.values().forEach(TopologyWorkerRuntime.BaseGraphView::close);
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private static void validate(Map<SectionPos,TopologyWorkerRuntime.BaseGraphView> views,MacroSearch.ComponentEndpoint endpoint) {
        if (views.get(endpoint.section()).topology().signature()!=endpoint.revision())
            throw new IllegalStateException("corridor component signature changed");
    }

    private static void enumerate(Map<SectionPos,TopologyWorkerRuntime.BaseGraphView> views,MacroSearch.Connection edge,
            BaseClusterTopology.TraversalProfile profile,Set<BlockPos> result) {
        var source=(MacroSearch.ComponentEndpoint)edge.from();
        var target=(MacroSearch.ComponentEndpoint)edge.to();
        var sourceGraph=views.get(source.section()).topology();
        var targetGraph=views.get(target.section()).topology();
        if (edge.transition() instanceof MacroSearch.BoundaryTransition bands) {
            for (int band=0;band<bands.bandCount();band++) for(int bit=0;bit<256;bit++) {
                if((bands.maskWord(band,bit>>>6)&(1L<<bit))==0)continue;
                BlockPos from=position(source.section(),SuperClusterTopology.faceCell(bands.face(),bit&15,bit>>>4,bands.sourceInset(band)));
                Direction direction=bands.horizontalDirection(band);
                BlockPos to=from.offset(direction==null?0:direction.getStepX()*bands.horizontalDistance(band),
                        bands.verticalShift(band),direction==null?0:direction.getStepZ()*bands.horizontalDistance(band));
                if(component(sourceGraph,from)==source.componentId() && SectionPos.of(to).equals(target.section())
                        && component(targetGraph,to)==target.componentId())result.add(to);
            }
        } else {
            for(int cell=0;cell<4096;cell++) {
                BlockPos from=position(source.section(),cell);
                if(component(sourceGraph,from)!=source.componentId())continue;
                for(Direction direction:Direction.Plane.HORIZONTAL)for(int dy=-profile.maxDrop();dy<=profile.maxStep();dy++) {
                    BlockPos to=from.relative(direction).offset(0,dy,0);
                    if(SectionPos.of(to).equals(target.section())&&component(targetGraph,to)==target.componentId())result.add(to);
                }
            }
        }
    }

    private static int component(BaseClusterTopology graph,BlockPos point) {
        return graph.componentAt(Math.floorMod(point.getX(),16),Math.floorMod(point.getY(),16),Math.floorMod(point.getZ(),16));
    }
    private static BlockPos position(SectionPos section,int cell) {
        return new BlockPos(section.minBlockX()+BaseClusterTopology.x(cell),
                section.minBlockY()+BaseClusterTopology.y(cell),section.minBlockZ()+BaseClusterTopology.z(cell));
    }
}
