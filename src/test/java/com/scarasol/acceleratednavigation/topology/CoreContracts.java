package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoreContracts {
    private CoreContracts() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("macro-commit")) {
            completedComponentSnapshotCoversAllCells();
            runtimeReverseSearchWaitsUpdatesAndReleases();
            runtimeSearchSurvivesSameVersionBoundaryRecovery(false);
            runtimeSearchSurvivesSameVersionBoundaryRecovery(true);
            System.out.println("Committed macro snapshot contracts: 4 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("door")) {
            DoorContracts.run();
            graphCaptureKeepsStructureAndMarksMissingSections();
            System.out.println("Door integration and graph export contracts passed");
            return;
        }
        if (args.length == 1 && args[0].equals("boundary-recovery")) {
            runtimeSearchSurvivesSameVersionBoundaryRecovery(false);
            runtimeSearchSurvivesSameVersionBoundaryRecovery(true);
            runtimeMissingBoundaryChangesRejectCachedViews();
            runtimeRestoredBoundaryFactsRefreshMissingSnapshots();
            runtimeParentRefreshesStaleBoundaryChildren();
            incomingBoundaryRestoresOriginalForwardTransition();
            runtimeReverseSearchWaitsUpdatesAndReleases();
            boundaryGeometryFiltersAndParents();
            runtimeGeometryChangesAndReleases();
            System.out.println("Boundary recovery and affected integration contracts: 9 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("boundary-recovery-search")) {
            runtimeSearchSurvivesSameVersionBoundaryRecovery(false);
            runtimeSearchSurvivesSameVersionBoundaryRecovery(true);
            System.out.println("Boundary recovery search contracts: 2 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("boundary-parent")) {
            runtimeParentRefreshesStaleBoundaryChildren();
            System.out.println("Parent boundary lifecycle contract: 1 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("boundary-lifecycle")) {
            runtimeMissingBoundaryChangesRejectCachedViews();
            runtimeRestoredBoundaryFactsRefreshMissingSnapshots();
            System.out.println("Boundary lifecycle contracts: 2 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("boundary")) {
            incomingBoundaryRestoresOriginalForwardTransition();
            runtimeReverseSearchWaitsUpdatesAndReleases();
            boundaryGeometryFiltersAndParents();
            runtimeGeometryChangesAndReleases();
            System.out.println("Boundary integration contracts: 4 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("reverse-runtime")) {
            runtimeReverseSearchWaitsUpdatesAndReleases();
            System.out.println("Reverse runtime contract: 1 passed");
            return;
        }
        if (args.length == 1 && args[0].equals("reverse")) {
            reverseContracts();
            return;
        }
        searchCompletesOnlyWithConnectedGraph();
        disconnectedGraphFailsWithoutCorridor();
        nodeLimitReportsItsOwnFailure();
        queuedWorkCanBeCancelledAndWorkersStop();
        graphCaptureKeepsStructureAndMarksMissingSections();
        geometryFactsBlockPartialShapeAndRoundTrip();
        reverseContracts();
        System.out.println("Core contracts: 9 passed");
    }

    private static void reverseContracts() throws Exception {
        incomingLocalEdgesKeepDirectionCapabilitiesAndBudget();
        incomingBoundaryRestoresOriginalForwardTransition();
        runtimeReverseSearchWaitsUpdatesAndReleases();
        System.out.println("Reverse integration contracts: 3 passed");
    }

    private static void incomingLocalEdgesKeepDirectionCapabilitiesAndBudget() {
        BaseClusterTopology graph = groundGraph(SectionPos.of(0, 0, 0),
                groundFacts(new BlockPos(2, 4, 8), new BlockPos(3, 1, 8)));
        int high = graph.componentAt(2, 4, 8);
        int low = graph.componentAt(3, 1, 8);
        require(high >= 0 && low >= 0 && high != low, "drop fixture did not form two components");
        require(graph.localEdgeEnd(high) - graph.localEdgeStart(high) == 1
                        && graph.localEdgeStart(low) == graph.localEdgeEnd(low),
                "three-block drop is not one-way");
        int bytes = graph.retainedBytes();
        IncomingEdges incoming = graph.incomingEdges();
        require(incoming == graph.incomingEdges() && bytes == graph.retainedBytes(),
                "lazy local index was not reused or changed retained accounting");
        require(incoming.end(high) == incoming.start(high)
                        && incoming.end(low) - incoming.start(low) == 1,
                "local transpose reversed the original directed edge");
        int entry = incoming.start(low);
        int edge = incoming.edge(entry);
        require(incoming.source(entry) == high && graph.localEdgeTarget(edge) == low
                        && graph.localEdgeSupports(edge, new BaseClusterTopology.MovementKey(1, 0, 3))
                        && !graph.localEdgeSupports(edge, new BaseClusterTopology.MovementKey(1, 0, 2)),
                "incoming edge lost original source, index or capability restriction");
    }

    private static void incomingBoundaryRestoresOriginalForwardTransition() {
        BaseClusterTopology.PackedFacts sourceFacts = groundFacts(new BlockPos(15, 1, 8));
        BaseClusterTopology.BuildInput sourceInput = new BaseClusterTopology.BuildInput(
                sourceFacts,
                new byte[]{
                        (byte) BaseClusterTopology.haloIndex(0, -1, 0),
                        (byte) BaseClusterTopology.haloIndex(1, 0, 0)
                },
                new BaseClusterTopology.PackedFacts[]{
                        BaseClusterTopology.PackedFacts.allAir(),
                        BaseClusterTopology.PackedFacts.allAir()
                },
                new long[]{1L, 1L},
                new long[]{
                        BaseClusterTopology.PackedFacts.allAir().fingerprint(),
                        BaseClusterTopology.PackedFacts.allAir().fingerprint()
                });
        BaseClusterTopology source = BaseClusterTopology.build(SectionPos.of(0, 1, 0), 1L,
                sourceInput,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch());
        BaseClusterTopology target = groundGraph(SectionPos.of(1, 0, 0),
                groundFacts(new BlockPos(0, 14, 8)));
        SuperClusterTopology.BoundaryLinks links =
                SuperClusterTopology.boundaryLinks(source, target, Direction.EAST);
        int sourceComponent = source.componentAt(15, 1, 8);
        int targetComponent = target.componentAt(0, 14, 8);
        int bytes = links.retainedBytes();
        IncomingEdges incoming = links.incomingEdges();
        require(incoming == links.incomingEdges() && bytes == links.retainedBytes()
                        && incoming.end(targetComponent) - incoming.start(targetComponent) == 1,
                "boundary index was not reused, changed accounting or lost crossing");
        int entry = incoming.start(targetComponent);
        int edge = incoming.edge(entry);
        BaseClusterTopology.MovementKey movement = new BaseClusterTopology.MovementKey(1, 0, 3);
        require(incoming.source(entry) == sourceComponent && links.targetComponent(edge) == targetComponent
                        && links.supports(edge, movement)
                        && !links.supports(edge, new BaseClusterTopology.MovementKey(1, 0, 2)),
                "incoming boundary lost source, original edge or drop capability");
        MacroSearch.Endpoint from = new MacroSearch.ComponentEndpoint(2L, new BlockPos(15, 17, 8),
                source.signature(), source.section(), BaseClusterTopology.Channel.GROUND, sourceComponent);
        MacroSearch.Endpoint to = new MacroSearch.ComponentEndpoint(3L, new BlockPos(16, 14, 8),
                target.signature(), target.section(), BaseClusterTopology.Channel.GROUND, targetComponent);
        MacroSearch search = new MacroSearch(new MacroSearch.Graph() {
            @Override public MacroSearch.Endpoint start() { return to; }
            @Override public MacroSearch.Endpoint goal() { return from; }
            @Override public boolean revisionsValid() { return true; }
            @Override public float heuristic(MacroSearch.Endpoint endpoint) { return 0.0F; }
            @Override public void expandInto(MacroSearch.Endpoint endpoint, MacroSearch.ExpansionBuffer output) {
                output.addBoundary(3L << 32 | 2L, from, links.lowerBound(edge),
                        links, edge, movement.capabilityMask());
            }
        }, 1.0F, 2);
        require(search.step(2) == ResumableSearch.Status.SUCCEEDED, "reverse boundary did not succeed");
        MacroSearch.Corridor forward = TopologyWorkerRuntime.restoreForwardCorridor(search.result());
        MacroSearch.Connection connection = forward.connections().get(0);
        require(forward.endpoints().equals(List.of(from, to))
                        && connection.id() == (2L << 32 | 3L)
                        && connection.from().equals(from) && connection.to().equals(to)
                        && forward.cost() == links.lowerBound(edge)
                        && connection.transition() == search.result().connections().get(0).transition(),
                "forward corridor lost endpoint order, edge ID, cost or original transition");
        MacroSearch.BoundaryTransition boundary = (MacroSearch.BoundaryTransition) connection.transition();
        int band = links.bandStart(edge);
        require(boundary.face() == Direction.EAST && boundary.verticalShift(0) == -3
                        && boundary.horizontalDirection(0) == Direction.EAST
                        && boundary.horizontalDistance(0) == 1 && boundary.sourceInset(0) == 0
                        && boundary.bandCount() == links.bandEnd(edge) - band,
                "restoration flipped or lost original forward crossing geometry");
        for (int word = 0; word < 4; word++) {
            require(boundary.maskWord(0, word) == links.maskWord(band, word),
                    "restoration lost boundary spatial mask");
        }
    }

    private static void runtimeReverseSearchWaitsUpdatesAndReleases() throws Exception {
        // A dimension key is only an identity here; avoid bootstrapping game registries.
        var keyFactory = ResourceKey.class.getDeclaredMethod("create", ResourceLocation.class, ResourceLocation.class);
        keyFactory.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension = (ResourceKey<Level>) keyFactory.invoke(null,
                new ResourceLocation("minecraft:dimension"), new ResourceLocation("navigation:test"));
        SectionPos pending = SectionPos.of(5, 0, 0);
        CountDownLatch demanded = new CountDownLatch(1);
        TopologyWorkerRuntime runtime = new TopologyWorkerRuntime(
                (key, identity, delta) -> {
                    if (delta > 0 && key.section().equals(pending)) demanded.countDown();
                },
                decision -> { throw new AssertionError("fixture unexpectedly requested persistence"); });
        SectionPos source = SectionPos.of(0, 1, 0);
        SectionPos target = SectionPos.of(1, 0, 0);
        BlockPos start = new BlockPos(15, 17, 8);
        BlockPos goal = new BlockPos(16, 14, 8);
        BaseClusterTopology.PackedFacts targetFacts = groundFacts(new BlockPos(0, 14, 8));
        try {
            publishFacts(runtime, dimension, source, 1L, groundFacts(new BlockPos(15, 1, 8)));
            publishFacts(runtime, dimension, target, 1L, targetFacts);
            publishFacts(runtime, dimension, SectionPos.of(0, 0, 0), 1L,
                    BaseClusterTopology.PackedFacts.allAir());
            publishFacts(runtime, dimension, SectionPos.of(1, 1, 0), 1L,
                    BaseClusterTopology.PackedFacts.allAir());
            TopologyWorkerRuntime.WorkerResult result = request(runtime, dimension, start, goal);
            require(result.corridor() != null && !result.progress().hierarchical()
                            && result.progress().dependencyWaits() > 0,
                    "real BASE query did not resume from its dependencies to a corridor");
            require(result.corridor().endpoints().get(0).anchor().equals(start)
                            && result.corridor().endpoints().get(
                            result.corridor().endpoints().size() - 1).anchor().equals(goal)
                            && result.corridor().connections().stream().anyMatch(connection ->
                            connection.transition() instanceof MacroSearch.BoundaryTransition boundary
                                    && boundary.face() == Direction.EAST && boundary.verticalShift(0) == -3),
                    "runtime result did not restore the original forward crossing");
            requireCompletedComponents(result.corridor());
            var cached = request(runtime, dimension, start, goal);
            require(cached.completedFromCache() && cached.corridor().components().equals(result.corridor().components()),
                    "completed cache rematerialization lost immutable component membership");
            var edges = result.corridor().connections().stream().filter(edge ->
                    edge.from() instanceof MacroSearch.ComponentEndpoint && edge.to() instanceof MacroSearch.ComponentEndpoint).toList();
            var destination = (MacroSearch.ComponentEndpoint)edges.get(edges.size()-1).to();
            try (var window = GroundSegmentWindow.capture(runtime, result.corridor(),
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.PLAYER_PURSUIT,
                    start, edges, destination, null, 0).get(10, TimeUnit.SECONDS)) {
                require(window.contains(goal) && window.candidates.contains(goal), "original snapshot window lost boundary target");
            }
            require(request(runtime, dimension, goal, start).corridor() == null,
                    "reverse query invented an uphill route for a one-way drop");
            publishFacts(runtime, dimension, target, 2L, groundFacts());
            require(request(runtime, dimension, start, goal).corridor() == null,
                    "version update reused the stale incoming boundary or completed corridor");
            try (var window = GroundSegmentWindow.capture(runtime, result.corridor(),
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.PLAYER_PURSUIT,
                    start, edges, destination, null, 0).get(10, TimeUnit.SECONDS)) {
                require(window.contains(goal) && window.candidates.contains(goal),
                        "later world update revoked a previously completed corridor");
                window.close();
                require(!window.contains(goal), "closed window remained usable");
            }
            publishFacts(runtime, dimension, target, 3L, targetFacts);
            require(request(runtime, dimension, start, goal).corridor() != null,
                    "restored topology did not rebuild the incoming route");
            require(request(runtime, dimension, start, start).corridor().cost() == 0.0F,
                    "same-component reverse query did not restore zero-cost memberships");

            publishFacts(runtime, dimension, SectionPos.of(0, 0, 1), 1L,
                    groundFacts(new BlockPos(2, 4, 8), new BlockPos(3, 1, 8)));
            TopologyWorkerRuntime.WorkerResult interior = request(runtime, dimension,
                    new BlockPos(3, 1, 24), new BlockPos(2, 4, 24));
            require(interior.corridor() == null
                            && interior.progress().failure() == MacroSearch.Failure.NO_STRUCTURAL_ROUTE
                            && interior.progress().dependencyWaits() == 0,
                    "impossible incoming sections were treated as unknown dependencies");

            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(dimension, pending), 1L, 0L, 1L,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            TopologyWorkerRuntime.MacroRequest cancelled = runtime.requestMacroQuery(dimension,
                    new BlockPos(80, 1, 8), goal, BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.PLAYER_PURSUIT);
            require(demanded.await(10, TimeUnit.SECONDS), "missing facts did not expose a pending wait");
            cancelled.cancel();
            try {
                cancelled.future().get(10, TimeUnit.SECONDS);
                throw new AssertionError("cancelled runtime request published a result");
            } catch (java.util.concurrent.CancellationException expected) {
                // Cancellation is a terminal request outcome.
            }
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "runtime workers did not stop");
        }
        TopologyService.WorkerMetrics stopped = runtime.metrics();
        require(stopped.activeReferences() == 0 && stopped.liveSearchDependencies() == 0
                        && stopped.logicalRequests() == 0 && stopped.physicalSearches() == 0
                        && stopped.baseCache().retainedBytes() == 0,
                "reverse runtime retained requests, dependencies, pins or cache after closing");
    }

    private static TopologyWorkerRuntime.WorkerResult request(TopologyWorkerRuntime runtime,
                                                             ResourceKey<Level> dimension,
                                                             BlockPos start, BlockPos goal) throws Exception {
        return runtime.requestMacroQuery(dimension, start, goal, BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                NavigationScheduler.Priority.PLAYER_PURSUIT).future().get(10, TimeUnit.SECONDS);
    }

    private static void publishFacts(TopologyWorkerRuntime runtime, ResourceKey<Level> dimension,
                                     SectionPos section, long revision,
                                     BaseClusterTopology.PackedFacts facts) {
        runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                new TopologyWorkerRuntime.ClusterKey(dimension, section), 1L, revision - 1L, revision,
                TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of()));
    }

    private static void awaitEvents(TopologyWorkerRuntime runtime) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var events = runtime.metrics().events();
            if (events.pendingKeys() == 0 && events.activeBatchKeys() == 0
                    && !events.taskOutstanding() && !events.batchActive()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("runtime events did not settle");
    }

    private static BaseClusterTopology.PackedFacts groundFacts(BlockPos... anchors) {
        byte[] flags = new byte[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(flags, (byte) BaseClusterTopology.VOLUME_OPEN);
        for (BlockPos anchor : anchors) {
            flags[BaseClusterTopology.cellIndex(anchor.getX(), anchor.getY(), anchor.getZ())]
                    |= BaseClusterTopology.GROUND_OPEN;
        }
        return BaseClusterTopology.PackedFacts.fromCells(flags);
    }

    private static BaseClusterTopology groundGraph(SectionPos section, BaseClusterTopology.PackedFacts facts) {
        return BaseClusterTopology.build(section, 1L, BaseClusterTopology.BuildInput.center(facts),
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch());
    }

    private static void requireCompletedComponents(MacroSearch.Corridor corridor) {
        for (var endpoint : corridor.endpoints()) if (endpoint instanceof MacroSearch.ComponentEndpoint component)
            require(corridor.component(component).contains(component.anchor()), "completed component missing its original anchor");
    }

    private static void completedComponentSnapshotCoversAllCells() {
        var section = SectionPos.of(-2, -1, -3);
        var body = new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.VOLUME, 1, 1, .3F, .4F, false, false);
        var topology = BaseClusterTopology.build(section, 1, BaseClusterTopology.BuildInput.center(
                BaseClusterTopology.PackedFacts.allAir()), body, new BaseClusterTopology.BuildScratch());
        var anchor = new BlockPos(section.minBlockX(), section.minBlockY(), section.minBlockZ());
        var endpoint = new MacroSearch.ComponentEndpoint(2, anchor, topology.signature(), section,
                BaseClusterTopology.Channel.VOLUME, topology.componentAt(0, 0, 0));
        var snapshot = new MacroSearch.ComponentSnapshot(endpoint, topology);
        for (int cell = 0; cell < BaseClusterTopology.CELL_COUNT; cell++)
            require(snapshot.contains(anchor.offset(BaseClusterTopology.x(cell), BaseClusterTopology.y(cell),
                    BaseClusterTopology.z(cell))), "snapshot truncated a component cell");
        require(!snapshot.contains(anchor.offset(16,0,0)), "snapshot accepted an adjacent section");
        try {
            new MacroSearch.ComponentSnapshot(new MacroSearch.ComponentEndpoint(3, anchor, topology.signature(),
                    SectionPos.of(0,0,0), BaseClusterTopology.Channel.VOLUME, 0), topology);
            throw new AssertionError("snapshot accepted a graph from a different section");
        } catch (IllegalArgumentException expected) {}
    }

    private static BaseClusterTopology.PackedFacts doorwayFacts(boolean open) {
        SectionGeometry.Cell[] cells = new SectionGeometry.Cell[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(cells, SectionGeometry.empty());
        byte[] flags = new byte[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(flags, (byte) BaseClusterTopology.VOLUME_OPEN);
        flags[BaseClusterTopology.cellIndex(15, 4, 8)] |= BaseClusterTopology.GROUND_OPEN;
        for (int y = 4; y <= 5; y++) {
            int cell = BaseClusterTopology.cellIndex(15, y, 8);
            flags[cell] |= BaseClusterTopology.EXACT_REQUIRED;
            cells[cell] = SectionGeometry.boxes(List.of(open
                    ? SectionGeometry.quantized(0, 0, 0, 1, 1, 3. / 16)
                    : SectionGeometry.quantized(13. / 16, 0, 0, 1, 1, 1)));
        }
        return BaseClusterTopology.PackedFacts.fromCells(flags, SectionGeometry.fromCells(cells));
    }

    private static boolean hasBoundary(SuperClusterTopology.BoundaryLinks links, int component,
                                       BaseClusterTopology.MovementKey movement) {
        for (int edge = links.edgeStart(component); edge < links.edgeEnd(component); edge++)
            if (links.supports(edge, movement)) return true;
        return false;
    }

    private static void boundaryGeometryFiltersAndParents() {
        SectionPos west = SectionPos.of(0, 0, 0), east = SectionPos.of(1, 0, 0);
        var movement = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.movement(BaseClusterTopology.Channel.GROUND);
        BaseClusterTopology closed = groundGraph(west, doorwayFacts(false));
        BaseClusterTopology open = groundGraph(west, doorwayFacts(true));
        BaseClusterTopology target = groundGraph(east, groundFacts(new BlockPos(0, 4, 8)));
        int sourceComponent = closed.componentAt(15, 4, 8);
        require(sourceComponent >= 0 && target.componentAt(0, 4, 8) >= 0,
                "door control blocked an endpoint instead of the crossing");
        require(!hasBoundary(SuperClusterTopology.boundaryLinks(closed, target, Direction.EAST),
                        sourceComponent, movement)
                        && hasBoundary(SuperClusterTopology.boundaryLinks(open, target, Direction.EAST),
                        open.componentAt(15, 4, 8), movement),
                "production crossing ignored closed or open door geometry");
        for (float width : new float[]{.3F, .6F, 1.2F}) {
            var body = new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.GROUND,
                    (int) Math.ceil(width), 2, width, 1.95F, false, false);
            require(!new BoundaryGeometry(closed, target).open(body, 15, 4, 8, Direction.EAST, 1, 0),
                    "body width bypassed door geometry");
        }
        BaseClusterTopology[] children = new BaseClusterTopology[8];
        List<SectionPos> sections = SuperClusterTopology.childSections(west);
        for (int i = 0; i < children.length; i++)
            children[i] = groundGraph(sections.get(i), groundFacts());
        children[sections.indexOf(west)] = closed;
        children[sections.indexOf(east)] = target;
        var body = closed.geometry();
        var parent = SuperClusterTopology.build(west, children, body, movement, new BaseClusterTopology.BuildScratch());
        int a = parent.aggregateId(west, sourceComponent);
        int b = parent.aggregateId(east, target.componentAt(0, 4, 8));
        require(a != b && parent.outgoingStart(a) == parent.outgoingEnd(a),
                "parent internal boundary bypassed the closed door");
        children[sections.indexOf(west)] = open;
        var connected = SuperClusterTopology.build(west, children, body, movement, new BaseClusterTopology.BuildScratch());
        require(connected.aggregateId(west, open.componentAt(15, 4, 8))
                        == connected.aggregateId(east, target.componentAt(0, 4, 8)),
                "parent internal boundary removed the open passage");
        var volumeBody = new BaseClusterTopology.GeometryKey(BaseClusterTopology.Channel.VOLUME,
                1, 1, .3F, .4F, false, false);
        BaseClusterTopology lower = BaseClusterTopology.build(west, 1,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.allAir()),
                volumeBody, new BaseClusterTopology.BuildScratch());
        BaseClusterTopology upper = BaseClusterTopology.build(SectionPos.of(0, 1, 0), 1,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.allAir()),
                volumeBody, new BaseClusterTopology.BuildScratch());
        require(hasBoundary(SuperClusterTopology.boundaryLinks(lower, upper, Direction.UP),
                        lower.componentAt(8, 15, 8), new BaseClusterTopology.MovementKey(0, 0, 0)),
                "vertical volume crossing lost its existing movement definition");
    }

    private static void runtimeGeometryChangesAndReleases() throws Exception {
        var keyFactory = ResourceKey.class.getDeclaredMethod("create", ResourceLocation.class, ResourceLocation.class);
        keyFactory.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension = (ResourceKey<Level>) keyFactory.invoke(null,
                new ResourceLocation("minecraft:dimension"), new ResourceLocation("navigation:geometry"));
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {},
                decision -> { throw new AssertionError("unexpected geometry persistence request"); });
        SectionPos west = SectionPos.of(0, 0, 0), east = SectionPos.of(1, 0, 0);
        BlockPos start = new BlockPos(15, 4, 8), goal = new BlockPos(16, 4, 8);
        var closed = doorwayFacts(false);var open = doorwayFacts(true);
        var target = groundFacts(new BlockPos(0, 4, 8));
        try {
            publishFacts(runtime, dimension, west, 1, closed);
            publishFacts(runtime, dimension, east, 1, target);
            require(request(runtime, dimension, start, goal).corridor() == null,
                    "real runtime crossed a closed door");
            try (var original = runtime.requestBaseGraph(dimension, west, closedBody()).get(10, TimeUnit.SECONDS)) {
                int component = original.topology().componentAt(15, 4, 8);
                publishFacts(runtime, dimension, west, 2, open);
                require(request(runtime, dimension, start, goal).corridor() != null,
                        "real runtime failed to rebuild the open door");
                try (var changed = runtime.requestBaseGraph(dimension, west, closedBody()).get(10, TimeUnit.SECONDS)) {
                    require(!original.current() && changed.current()
                                    && changed.topology().componentAt(15, 4, 8) == component,
                            "geometry-only change failed to invalidate the snapshot or changed the control labels");
                    publishFacts(runtime, dimension, SectionPos.of(0, 1, 0), 1,
                            BaseClusterTopology.PackedFacts.allAir());
                    awaitEvents(runtime);
                    try (var halo = runtime.requestBaseGraph(dimension, west, closedBody()).get(10, TimeUnit.SECONDS)) {
                        require(!changed.current() && halo.current(),
                                "previously absent Halo remained valid after it loaded");
                    }
                }
            }
            publishFacts(runtime, dimension, west, 3, closed);
            require(request(runtime, dimension, start, goal).corridor() == null,
                    "completed corridor or incoming boundary reused the open-door geometry");
            var view = runtime.requestBaseGraph(dimension, west, closedBody()).get(10, TimeUnit.SECONDS);
            view.close();view.close();
            require(!view.current(), "closed export snapshot remained live");
            runtime.endServerTick();runtime.endServerTick();
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "geometry runtime did not stop");
        }
        var stopped = runtime.metrics();
        require(stopped.activeReferences() == 0 && stopped.facts().activeFactsBytes() == 0
                        && stopped.baseCache().retainedBytes() == 0 && stopped.parentCache().retainedBytes() == 0,
                "geometry snapshots retained fact ownership after closing");
    }

    private static BaseClusterTopology.GeometryKey closedBody() {
        return BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND);
    }

    private static ResourceKey<Level> lifecycleDimension() throws Exception {
        var factory = ResourceKey.class.getDeclaredMethod("create", ResourceLocation.class, ResourceLocation.class);
        factory.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension = (ResourceKey<Level>) factory.invoke(null,
                new ResourceLocation("minecraft:dimension"), new ResourceLocation("navigation:lifecycle"));
        return dimension;
    }

    private static void evictDecodedFacts(TopologyWorkerRuntime runtime,
                                         ResourceKey<Level> dimension, SectionPos section) throws Exception {
        var lockField = TopologyWorkerRuntime.class.getDeclaredField("runtimeLock");
        var clustersField = TopologyWorkerRuntime.class.getDeclaredField("clusters");
        lockField.setAccessible(true); clustersField.setAccessible(true);
        synchronized (lockField.get(runtime)) {
            Object entry = ((Map<?, ?>) clustersField.get(runtime)).get(
                    new TopologyWorkerRuntime.ClusterKey(dimension, section));
            var removeIdle = TopologyWorkerRuntime.class.getDeclaredMethod("removeIdleFact", entry.getClass());
            var factsField = entry.getClass().getDeclaredField("facts");
            var stateField = entry.getClass().getDeclaredField("factState");
            removeIdle.setAccessible(true); factsField.setAccessible(true); stateField.setAccessible(true);
            removeIdle.invoke(runtime, entry);
            factsField.set(entry, null);
            stateField.set(entry, TopologyWorkerRuntime.FactState.PENDING);
        }
    }

    private static void runtimeSearchSurvivesSameVersionBoundaryRecovery(boolean hierarchical) throws Exception {
        var dimension = lifecycleDimension();
        BlockPos[] floor = new BlockPos[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            floor[z * 16 + x] = new BlockPos(x, 4, z);
        var facts = groundFacts(floor);
        var owner = new java.util.concurrent.atomic.AtomicReference<TopologyWorkerRuntime>();
        var restored = java.util.concurrent.ConcurrentHashMap.<SectionPos>newKeySet();
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {
            if (delta > 0 && restored.add(key.section()))
                publishFacts(owner.get(), dimension, key.section(), 1, facts);
        }, decision -> { throw new AssertionError("unexpected persistence request"); });
        owner.set(runtime);
        try {
            for (int x = 0; x < (hierarchical ? 6 : 4); x++)
                for (int y = 0; y < (hierarchical ? 2 : 1); y++)
                    for (int z = 0; z < (hierarchical ? 2 : 1); z++)
                        publishFacts(runtime, dimension, SectionPos.of(x, y, z), 1, facts);
            awaitEvents(runtime);
            evictDecodedFacts(runtime, dimension, SectionPos.of(hierarchical ? 2 : 1, 0, 0));
            evictDecodedFacts(runtime, dimension, SectionPos.of(hierarchical ? 3 : 2, 0, 0));
            var result = request(runtime, dimension, new BlockPos(8, 4, 8),
                    new BlockPos(hierarchical ? 72 : 56, 4, 8));
            var metrics = runtime.metrics();
            System.out.println("Same-version recovery search: hierarchical=" + hierarchical
                    + ", result=" + result.progress().failure()
                    + ", restored=" + restored.size() + ", stale=" + metrics.workerStaleResults()
                    + ", retries=" + metrics.automaticStaleRetries());
            require(result.corridor() != null && result.progress().hierarchical() == hierarchical
                            && restored.size() == 2
                            && metrics.workerStaleResults() == 0 && metrics.automaticStaleRetries() == 0,
                    "unchanged world was rejected while restoring decoded boundary facts");
            requireCompletedComponents(result.corridor());
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "recovery search workers did not stop");
        }
        require(runtime.metrics().activeReferences() == 0 && runtime.metrics().facts().activeFactsBytes() == 0,
                "recovery search retained geometry facts");
    }

    private static void runtimeMissingBoundaryChangesRejectCachedViews() throws Exception {
        var dimension = lifecycleDimension();
        SectionPos center = SectionPos.of(0, 0, 0), halo = SectionPos.of(1, 1, 0);
        CountDownLatch demanded = new CountDownLatch(1);
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {
            if (delta > 0 && key.section().equals(halo)) demanded.countDown();
        },
                decision -> { throw new AssertionError("unexpected persistence request"); });
        try {
            publishFacts(runtime, dimension, center, 1, groundFacts(new BlockPos(8, 4, 8)));
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(dimension, halo), 1, 0, 1,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            awaitEvents(runtime);
            var pending = runtime.requestBaseGraph(dimension, center, closedBody());
            require(demanded.await(10, TimeUnit.SECONDS) && !pending.isDone(),
                    "published a boundary snapshot before its loaded facts were ready");
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(dimension, halo), 1, 0, 1,
                    TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of()));
            try (var before = pending.get(10, TimeUnit.SECONDS)) {
                require(before.current() && before.topology().boundaryFacts().atSection(1, 1, 0) == null,
                        "terminal optional boundary did not retain conservative missing geometry");
                runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                        new TopologyWorkerRuntime.ClusterKey(dimension, halo), 1, 0, 1,
                        TopologyWorkerRuntime.FactState.UNLOADED, null, Map.of()));
                awaitEvents(runtime);
                require(!before.current(), "unloaded boundary identity did not invalidate the captured view");
                try (var after = runtime.requestBaseGraph(dimension, center, closedBody()).get(10, TimeUnit.SECONDS)) {
                    require(after.current() && after.topology() != before.topology(),
                            "cache returned the stale view rejected by the parent input gate");
                }
            }
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "lifecycle workers did not stop");
        }
        require(runtime.metrics().activeReferences() == 0 && runtime.metrics().facts().activeFactsBytes() == 0,
                "lifecycle control retained geometry facts");
    }

    private static void runtimeRestoredBoundaryFactsRefreshMissingSnapshots() throws Exception {
        var dimension = lifecycleDimension();
        SectionPos center = SectionPos.of(0, 0, 0), halo = SectionPos.of(1, 1, 0);
        CountDownLatch demanded = new CountDownLatch(1);
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {
            if (delta > 0 && key.section().equals(halo)) demanded.countDown();
        },
                decision -> { throw new AssertionError("unexpected persistence request"); });
        try {
            publishFacts(runtime, dimension, center, 1, groundFacts(new BlockPos(8, 4, 8)));
            publishFacts(runtime, dimension, halo, 1, BaseClusterTopology.PackedFacts.allAir());
            awaitEvents(runtime);
            // Reproduce the existing decoded-fact eviction, preserving world identity.
            evictDecodedFacts(runtime, dimension, halo);
            var pending = runtime.requestBaseGraph(dimension, center, closedBody());
            require(demanded.await(10, TimeUnit.SECONDS) && !pending.isDone(),
                    "published a snapshot while same-version boundary decoding was still pending");
            publishFacts(runtime, dimension, halo, 1, BaseClusterTopology.PackedFacts.allAir());
            awaitEvents(runtime);
            try (var restored = pending.get(10, TimeUnit.SECONDS)) {
                require(restored.current() && restored.topology().boundaryFacts().atSection(1, 1, 0) != null,
                        "restored snapshot omitted the prepared boundary geometry");
                publishFacts(runtime, dimension, halo, 1, BaseClusterTopology.PackedFacts.allAir());
                awaitEvents(runtime);
                try (var unchanged = runtime.requestBaseGraph(dimension, center, closedBody()).get(10, TimeUnit.SECONDS)) {
                    require(unchanged.topology() == restored.topology(),
                            "unchanged decoded facts unnecessarily rebuilt a complete snapshot");
                }
            }
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "restoration workers did not stop");
        }
        require(runtime.metrics().activeReferences() == 0 && runtime.metrics().facts().activeFactsBytes() == 0,
                "restoration control retained geometry facts");
    }

    private static void runtimeParentRefreshesStaleBoundaryChildren() throws Exception {
        var dimension = lifecycleDimension();
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {},
                decision -> { throw new AssertionError("unexpected persistence request"); });
        SectionPos halo = SectionPos.of(1, 1, -1);
        BlockPos[] floor = new BlockPos[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            floor[z * 16 + x] = new BlockPos(x, 4, z);
        try {
            for (int x = 0; x < 6; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
                publishFacts(runtime, dimension, SectionPos.of(x, y, z), 1, groundFacts(floor));
            awaitEvents(runtime);
            for (SectionPos child : SuperClusterTopology.childSections(SectionPos.of(0, 0, 0))) {
                try (var view = runtime.requestBaseGraph(dimension, child, closedBody()).get(10, TimeUnit.SECONDS)) {
                    require(view.current(), "parent child was not captured before boundary recovery");
                }
            }
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(
                    new TopologyWorkerRuntime.ClusterKey(dimension, halo), 1, 0, 1,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            publishFacts(runtime, dimension, halo, 1, BaseClusterTopology.PackedFacts.allAir());
            awaitEvents(runtime);
            long builds = runtime.metrics().tasks().completed().builds();
            var result = request(runtime, dimension, new BlockPos(8, 4, 8), new BlockPos(72, 4, 8));
            require(result.corridor() != null && result.progress().hierarchical(),
                    "parent query did not progress past stale child input");
            System.out.println("Parent refresh completed build tasks="
                    + (runtime.metrics().tasks().completed().builds() - builds));
        } finally {
            require(runtime.awaitStopped(10, TimeUnit.SECONDS), "parent workers did not stop");
        }
        require(runtime.metrics().activeReferences() == 0 && runtime.metrics().facts().activeFactsBytes() == 0,
                "parent control retained geometry facts");
    }

    private static void geometryFactsBlockPartialShapeAndRoundTrip() throws Exception {
        byte[] flags = new byte[BaseClusterTopology.CELL_COUNT];
        for (int x = 0; x <= 2; x++) {
            flags[BaseClusterTopology.cellIndex(x, 0, 0)] =
                    (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);
            flags[BaseClusterTopology.cellIndex(x, 1, 0)] =
                    (byte) BaseClusterTopology.VOLUME_OPEN;
        }
        SectionGeometry.Cell[] cells = new SectionGeometry.Cell[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(cells, SectionGeometry.empty());
        int wallCell = BaseClusterTopology.cellIndex(1, 0, 0);
        cells[wallCell] = SectionGeometry.boxes(List.of(
                SectionGeometry.quantized(0.45, 0.0, 0.0, 0.55, 1.0, 1.0)));
        flags[wallCell] |= BaseClusterTopology.EXACT_REQUIRED;
        SectionGeometry geometry = SectionGeometry.fromCells(cells);
        BaseClusterTopology.PackedFacts facts = BaseClusterTopology.PackedFacts.fromCells(flags, geometry);
        BaseClusterTopology graph = groundGraph(SectionPos.of(0, 0, 0), facts);
        int left = graph.componentAt(0, 0, 0);
        int right = graph.componentAt(2, 0, 0);
        require(left >= 0 && right >= 0 && left != right,
                "partial collision geometry did not split the crossing components");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            geometry.write(output, new byte[8192]);
        }
        SectionGeometry restored;
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = SectionGeometry.read(input);
        }
        require(restored.fingerprint() == geometry.fingerprint()
                        && restored.occupied(wallCell, 0.6F, 1.95F, false),
                "section geometry persistence changed collision semantics");
    }

    private static void searchCompletesOnlyWithConnectedGraph() {
        MacroSearch search = new MacroSearch(new ThreeNodeGraph(true), 1.0F, 3);
        int steps = 0;
        while (search.status() == ResumableSearch.Status.RUNNING && steps++ < 4) {
            search.step(1);
        }
        require(search.status() == ResumableSearch.Status.SUCCEEDED, "connected graph did not succeed");
        require(search.result() != null && search.result().endpoints().stream()
                .map(MacroSearch.Endpoint::id).toList().equals(List.of(1L, 2L, 3L)),
                "successful corridor has wrong endpoints");
        require(search.result().connections().size() == 2 && search.result().cost() == 2.0F,
                "successful corridor has wrong connections or cost");
    }

    private static void disconnectedGraphFailsWithoutCorridor() {
        MacroSearch search = new MacroSearch(new ThreeNodeGraph(false), 1.0F, 3);
        require(search.step(3) == ResumableSearch.Status.FAILED,
                "disconnected graph did not reach a terminal failure");
        require(search.failure() == MacroSearch.Failure.NO_STRUCTURAL_ROUTE && search.result() == null,
                "disconnected graph published a corridor or wrong failure");
    }

    private static void nodeLimitReportsItsOwnFailure() {
        MacroSearch search = new MacroSearch(new ThreeNodeGraph(true), 1.0F, 1);
        require(search.step(3) == ResumableSearch.Status.FAILED,
                "node limit did not terminate search");
        require(search.failure() == MacroSearch.Failure.SEARCH_LIMIT_REACHED && search.result() == null,
                "node limit was misclassified as a path result");
    }

    private static void queuedWorkCanBeCancelledAndWorkersStop() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelledWorkRan = new AtomicBoolean();
        TopologyTaskExecutor executor = new TopologyTaskExecutor(() -> true);
        try {
            Runnable blocking = () -> {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("worker release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("worker interrupted", interrupted);
                }
            };
            executor.submitSearch(NavigationScheduler.Priority.PLAYER_PURSUIT,
                    TopologyTaskExecutor.WorkKind.QUICK_SEARCH, blocking);
            executor.submitSearch(NavigationScheduler.Priority.PLAYER_PURSUIT,
                    TopologyTaskExecutor.WorkKind.QUICK_SEARCH, blocking);
            require(started.await(10, TimeUnit.SECONDS), "both workers did not start");
            TopologyTaskExecutor.TaskHandle queued = executor.submitPrewarm(
                    () -> cancelledWorkRan.set(true));
            AtomicBoolean removed = new AtomicBoolean();
            queued.cancelWhenQueued(removed::set);
            require(removed.get(), "queued task was not cancelled");
        } finally {
            executor.shutdown();
            release.countDown();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");
        }
        require(!cancelledWorkRan.get(), "cancelled task executed");
        TopologyService.TaskMetrics metrics = executor.metrics();
        require(metrics.queued().quickSearches() == 0 && metrics.queued().prewarms() == 0
                        && metrics.running().quickSearches() == 0,
                "executor retained queued or running work");
    }

    private static void graphCaptureKeepsStructureAndMarksMissingSections() throws Exception {
        Vec3 center = new Vec3(15.8D, 64.5D, 8.0D);
        List<SectionPos> selected = BaseGraphCapture.sections(center, 1, -64, 320);
        require(selected.contains(SectionPos.of(0, 4, 0))
                        && selected.contains(SectionPos.of(1, 4, 0))
                        && !selected.contains(SectionPos.of(2, 4, 0)),
                "graph capture radius omitted an intersecting section or included a distant section");

        require(BaseGraphCapture.sections(center, 256, -64, 320).size() > selected.size()
                        && !BaseGraphCapture.sections(center, 384, -64, 320).isEmpty()
                        && BaseGraphCapture.MAX_DISTANCE == 384,
                "capture cannot cover a long-distance route with margin");
        try {
            BaseGraphCapture.sections(center, 385, -64, 320);
            throw new AssertionError("capture accepted a radius above its limit");
        } catch (IllegalArgumentException expected) {
            // Brigadier also rejects this value before the export starts.
        }

        byte[] flags = new byte[BaseClusterTopology.CELL_COUNT];
        for (int cell = 0; cell < flags.length; cell++) flags[cell] = (byte) BaseClusterTopology.VOLUME_OPEN;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                flags[BaseClusterTopology.cellIndex(x, 0, z)] |= BaseClusterTopology.GROUND_OPEN;
            }
        }
        BaseClusterTopology graph = BaseClusterTopology.build(SectionPos.of(0, 4, 0), 7L,
                BaseClusterTopology.BuildInput.center(BaseClusterTopology.PackedFacts.fromCells(flags)),
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(BaseClusterTopology.Channel.GROUND),
                new BaseClusterTopology.BuildScratch());
        Path directory = Files.createTempDirectory("navigation-graph-contract");
        Path file = directory.resolve("capture.json.gz");
        Path completeFile = directory.resolve("complete.json.gz");
        try {
            BaseGraphCapture.Result saved;
            try (BaseGraphCapture.Writer writer = BaseGraphCapture.open(file,
                    "minecraft:overworld", center, 1)) {
                writer.add(new BaseGraphCapture.Entry(graph.section(), "READY", graph, null));
                writer.add(new BaseGraphCapture.Entry(SectionPos.of(1, 4, 0), "UNLOADED", null, null));
                saved = writer.finish(true);
            }
            require(saved.ready() == 1 && saved.unloaded() == 1 && !saved.complete(),
                    "capture reported a missing section as a complete snapshot");
            BaseGraphCapture.Result complete;
            try (BaseGraphCapture.Writer writer = BaseGraphCapture.open(completeFile,
                    "minecraft:overworld", center, 1)) {
                writer.add(new BaseGraphCapture.Entry(graph.section(), "READY", graph, null));
                complete = writer.finish(true);
            }
            require(complete.complete() && complete.ready() == 1 && complete.failed() == 0,
                    "fully captured graph was not reported complete");
            require(!Files.exists(file.resolveSibling(file.getFileName() + ".part")),
                    "completed capture left a temporary file");
            try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                    StandardCharsets.UTF_8)) {
                JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject encoded = document.getAsJsonArray("sections").get(0)
                        .getAsJsonObject().getAsJsonObject("graph");
                require(document.get("version").getAsInt() == 1
                                && !document.get("complete").getAsBoolean()
                                && encoded.getAsJsonArray("componentLabelValues").size() == BaseClusterTopology.CELL_COUNT
                                && encoded.getAsJsonArray("componentMetadataHex").size() == graph.componentCount()
                                && encoded.get("signatureHex").getAsString()
                                .equals(Long.toUnsignedString(graph.signature(), 16))
                                && encoded.getAsJsonArray("outgoingOffsets").size() == graph.componentCount() + 1
                                && document.getAsJsonObject("profile").get("maxDrop").getAsInt() == 3
                                && !document.getAsJsonObject("profile").get("canOpenDoors").getAsBoolean()
                                && !encoded.getAsJsonObject("geometry").get("canOpenDoors").getAsBoolean()
                                && encoded.getAsJsonArray("componentLabelValues").get(
                                BaseClusterTopology.cellIndex(0, 0, 0)).getAsInt()
                                + encoded.get("baseLabel").getAsInt() - 1 == graph.componentAt(0, 0, 0)
                                && document.getAsJsonArray("sections").get(1).getAsJsonObject()
                                .get("state").getAsString().equals("UNLOADED"),
                        "exported graph lost structural fields or missing-section status");
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(completeFile);
            Files.deleteIfExists(directory);
        }
    }
    private static void require(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }

    private static final class ThreeNodeGraph implements MacroSearch.Graph {
        private final MacroSearch.Endpoint start = new MacroSearch.ExactEndpoint(1L,
                new BlockPos(0, 64, 0), 1L);
        private final MacroSearch.Endpoint middle = new MacroSearch.ExactEndpoint(2L,
                new BlockPos(1, 64, 0), 1L);
        private final MacroSearch.Endpoint goal = new MacroSearch.ExactEndpoint(3L,
                new BlockPos(2, 64, 0), 1L);
        private final boolean connected;

        private ThreeNodeGraph(boolean connected) {
            this.connected = connected;
        }

        @Override
        public MacroSearch.Endpoint start() {
            return start;
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return goal;
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            if (from.id() == start.id()) {
                output.addLocal(10L, middle, 1.0F);
            } else if (from.id() == middle.id() && connected) {
                output.addLocal(11L, goal, 1.0F);
            }
        }

        @Override
        public boolean revisionsValid() {
            return true;
        }
    }
}
