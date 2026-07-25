package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyServiceTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateSnapshotRequestsShareOneWorkerBuild() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor publisher = new ManualExecutor();
        TopologyService service = new TopologyService(worker, publisher, () -> true);
        TopologyService.ClusterKey key = key(1, 2, 3);
        BaseClusterTopology.Snapshot snapshot = openSnapshot();

        CompletableFuture<BaseClusterTopology> first = service.submitSnapshot(key, snapshot);
        CompletableFuture<BaseClusterTopology> second = service.submitSnapshot(key, snapshot);

        assertSame(first, second);
        assertEquals(1, worker.size());
        worker.runNext();
        assertFalse(first.isDone());
        assertEquals(1, publisher.size());
        publisher.runNext();
        assertTrue(first.isDone());
        assertSame(first.join(), service.topology(key));
        assertEquals(1, service.metrics().buildRequests());
        assertEquals(1, service.metrics().publishedClusters());
    }

    @Test
    void invalidationDiscardsWorkerResultBeforeSingleWriterPublishesIt() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor publisher = new ManualExecutor();
        TopologyService service = new TopologyService(worker, publisher, () -> true);
        TopologyService.ClusterKey key = key(0, 0, 0);

        CompletableFuture<BaseClusterTopology> future = service.submitSnapshot(key, openSnapshot());
        worker.runNext();
        service.invalidate(key);
        publisher.runNext();

        assertTrue(future.isCompletedExceptionally());
        assertThrows(RuntimeException.class, future::join);
        assertNull(service.topology(key));
        assertEquals(1, service.metrics().staleBuilds());
        assertEquals(0, service.metrics().publishedClusters());
    }

    @Test
    void replacingSnapshotAfterInvalidationBuildsOnlyCurrentRevision() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor publisher = new ManualExecutor();
        TopologyService service = new TopologyService(worker, publisher, () -> true);
        TopologyService.ClusterKey key = key(4, -2, 9);

        CompletableFuture<BaseClusterTopology> old = service.submitSnapshot(key, openSnapshot());
        service.invalidate(key);
        CompletableFuture<BaseClusterTopology> current = service.submitSnapshot(key, fluidSnapshot());
        worker.runAll();
        publisher.runAll();

        assertTrue(old.isCompletedExceptionally());
        assertEquals(1L, current.join().revision());
        assertEquals(fluidSnapshot().fingerprint(), current.join().sourceFingerprint());
        assertSame(current.join(), service.topology(key));
    }

    @Test
    void repeatedChangesCoalesceUntilTheSectionStartsRebuilding() {
        TopologyService service = immediateService();
        TopologyService.ClusterKey key = key(2, 0, 2);
        service.submitSnapshot(key, openSnapshot()).join();

        service.invalidate(key);
        service.invalidate(key);
        BaseClusterTopology rebuilt = service.submitSnapshot(key, openSnapshot()).join();

        assertEquals(1L, rebuilt.revision());
    }

    @Test
    void invalidatingUnknownSectionDoesNotCreateHistoricalState() {
        TopologyService service = immediateService();
        TopologyService.ClusterKey key = key(100, 0, 100);

        service.invalidate(key);
        BaseClusterTopology firstBuild = service.submitSnapshot(key, openSnapshot()).join();

        assertEquals(0L, firstBuild.revision());
    }

    @Test
    void shutdownPreventsQueuedBuildFromPublishing() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor publisher = new ManualExecutor();
        TopologyService service = new TopologyService(worker, publisher, () -> true);
        TopologyService.ClusterKey key = key(0, 1, 0);

        CompletableFuture<BaseClusterTopology> future = service.submitSnapshot(key, openSnapshot());
        service.shutdown();
        worker.runAll();
        publisher.runAll();

        assertTrue(future.isCompletedExceptionally());
        assertNull(service.topology(key));
        assertThrows(IllegalStateException.class, () -> service.submitSnapshot(key, openSnapshot()));
    }

    @Test
    void minecraftCollisionShapesProduceConservativeTopologyFlags() {
        assertEquals(0, classify(Blocks.STONE.defaultBlockState(), false));

        int supportedAir = classify(Blocks.AIR.defaultBlockState(), true);
        assertTrue((supportedAir & BaseClusterTopology.VOLUME_OPEN) != 0);
        assertTrue((supportedAir & BaseClusterTopology.GROUND_OPEN) != 0);
        assertEquals(0, supportedAir & BaseClusterTopology.EXACT_REQUIRED);

        int slab = classify(Blocks.OAK_SLAB.defaultBlockState(), false);
        assertTrue((slab & BaseClusterTopology.VOLUME_OPEN) != 0);
        assertTrue((slab & BaseClusterTopology.GROUND_OPEN) != 0);
        assertTrue((slab & BaseClusterTopology.EXACT_REQUIRED) != 0);

    }

    @Test
    void randomTickStateChangesWithTheSameCollisionShapeKeepTopologyCurrent() {
        BlockState wheatYoung = Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 0);
        BlockState wheatMature = wheatYoung.setValue(BlockStateProperties.AGE_7, 7);
        BlockState cactusYoung = Blocks.CACTUS.defaultBlockState()
                .setValue(BlockStateProperties.AGE_15, 0);
        BlockState cactusReady = cactusYoung.setValue(BlockStateProperties.AGE_15, 15);
        BlockState shallowWater = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
        BlockState deeperWater = shallowWater.setValue(LiquidBlock.LEVEL, 7);

        assertFalse(navigationGeometryChanged(Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState()));
        assertFalse(navigationGeometryChanged(Blocks.OAK_SLAB.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState()));
        assertFalse(navigationGeometryChanged(wheatYoung, wheatMature));
        assertFalse(navigationGeometryChanged(cactusYoung, cactusReady));
        assertFalse(navigationGeometryChanged(shallowWater, true, deeperWater, true));
    }

    @Test
    void collisionOrFluidOccupancyChangesInvalidateTopology() {
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState openTrapdoor = closedTrapdoor.setValue(TrapDoorBlock.OPEN, true);
        BlockState drySlab = Blocks.OAK_SLAB.defaultBlockState();
        BlockState waterloggedSlab = drySlab.setValue(BlockStateProperties.WATERLOGGED, true);

        assertTrue(navigationGeometryChanged(closedTrapdoor, openTrapdoor));
        VoxelShape slabShape = drySlab.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        VoxelShape waterloggedSlabShape = waterloggedSlab.getCollisionShape(
                EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO
        );
        assertTrue(TopologyService.navigationGeometryChanged(
                slabShape,
                false,
                waterloggedSlabShape,
                true
        ));
    }

    @Test
    void snapshotSearchYieldsAtExpansionBudgetWithoutLosingCells() {
        int[] sampled = {0};
        TopologyService.SnapshotSearch search = new TopologyService.SnapshotSearch(index -> {
            sampled[0]++;
            return index == 4095 ? BaseClusterTopology.FLUID : BaseClusterTopology.VOLUME_OPEN;
        });

        assertEquals(TopologyService.SnapshotSearch.Status.RUNNING, search.step(17, Long.MAX_VALUE));
        assertEquals(17, sampled[0]);
        assertEquals(TopologyService.SnapshotSearch.Status.SUCCEEDED,
                search.step(BaseClusterTopology.CELL_COUNT, Long.MAX_VALUE));
        assertEquals(BaseClusterTopology.CELL_COUNT, sampled[0]);
        assertEquals(BaseClusterTopology.FLUID, search.result().flags(4095));
    }

    @Test
    void topologyWorkerRunsPromotedDemandBeforeQueuedBackgroundWork() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(
                "topology-priority-test",
                Thread.NORM_PRIORITY
        );
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            executor.submit(NavigationScheduler.Priority.BACKGROUND,
                    () -> {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

            TopologyTaskExecutor.TaskHandle promoted = executor.submit(
                    NavigationScheduler.Priority.BACKGROUND,
                    () -> {
                        order.add("promoted");
                        completed.countDown();
                    }
            );
            executor.submit(
                    NavigationScheduler.Priority.ACTIVE,
                    () -> {
                        order.add("active");
                        completed.countDown();
                    }
            );
            promoted.promote(
                    NavigationScheduler.Priority.PLAYER_PURSUIT
            );
            releaseBlocker.countDown();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("promoted", "active"), order);
            assertEquals(1L, executor.metrics().promotedTasks());
        } finally {
            releaseBlocker.countDown();
            executor.shutdown();
        }
    }

    @Test
    void topologyWorkerAgingPreventsBackgroundStarvation() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(
                "topology-aging-test",
                Thread.NORM_PRIORITY
        );
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            executor.submit(NavigationScheduler.Priority.ACTIVE,
                    () -> {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            executor.submit(
                    NavigationScheduler.Priority.BACKGROUND,
                    () -> {
                        order.add("aged-background");
                        completed.countDown();
                    }
            );

            Thread.sleep(800L);
            executor.submit(
                    NavigationScheduler.Priority.PLAYER_PURSUIT,
                    () -> {
                        order.add("new-pursuit");
                        completed.countDown();
                    }
            );
            releaseBlocker.countDown();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("aged-background", "new-pursuit"), order);
        } finally {
            releaseBlocker.countDown();
            executor.shutdown();
        }
    }

    @Test
    void graphConnectsOnlyOverlappingEntrancesAcrossAdjacentSections() {
        TopologyService service = immediateService();
        TopologyService.ClusterKey left = key(0, 0, 0);
        TopologyService.ClusterKey right = key(1, 0, 0);
        service.submitSnapshot(left, xCorridorSnapshot(1, 15, 2, 4)).join();
        service.submitSnapshot(right, xCorridorSnapshot(0, 1, 2, 4)).join();

        MacroSearch search = new MacroSearch(
                service.graph(Level.OVERWORLD, new BlockPos(1, 2, 4), new BlockPos(17, 2, 4),
                        BaseClusterTopology.Channel.GROUND),
                MacroSearch.DEFAULT_WEIGHT
        );

        runMacroSearch(search);
        assertEquals(3, search.result().connections().size());
        assertTrue(search.result().connections().stream()
                .anyMatch(connection -> connection.transition()
                        instanceof MacroSearch.BoundaryTransition));
    }

    @Test
    void publishedConnectivityPreselectsDirectedReachableEndpoints() {
        TopologyService service = immediateService();
        SectionPos leftSection = SectionPos.of(0, 0, 0);
        SectionPos rightSection = SectionPos.of(1, 0, 0);
        service.submitSnapshot(
                key(0, 0, 0),
                xCorridorSnapshot(1, 15, 2, 4)
        ).join();
        service.submitSnapshot(
                key(1, 0, 0),
                xCorridorSnapshot(0, 1, 2, 4)
        ).join();

        TopologyGraphAudit.PublishedConnectivity connectivity =
                TopologyGraphAudit.indexPublished(
                        service,
                        Level.OVERWORLD,
                        List.of(leftSection, rightSection),
                        BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
                );
        TopologyGraphAudit.PairSelection selection = connectivity.selectPair(
                List.of(new BlockPos(1, 2, 4), new BlockPos(17, 2, 4)),
                16.0D
        );

        assertEquals(new BlockPos(1, 2, 4), selection.start());
        assertEquals(new BlockPos(17, 2, 4), selection.goal());
        assertEquals(16.0D, selection.directDistance());
        assertEquals(selection.sourceStrongComponentId(), selection.targetStrongComponentId());
        assertEquals(2, selection.sourceStrongComponentNodes());
        assertEquals(2, selection.targetStrongComponentNodes());
    }

    @Test
    void publishedConnectivityAcceptsOneWayReachabilityAcrossStrongComponents() {
        TopologyService service = immediateService();
        SectionPos section = SectionPos.of(0, 0, 0);
        service.submitSnapshot(key(0, 0, 0), oneWayDropSnapshot()).join();

        TopologyGraphAudit.PublishedConnectivity connectivity =
                TopologyGraphAudit.indexPublished(
                        service,
                        Level.OVERWORLD,
                        List.of(section),
                        BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND
                );
        BlockPos lower = new BlockPos(2, 2, 1);
        BlockPos upper = new BlockPos(1, 5, 1);
        TopologyGraphAudit.PairSelection selection = connectivity.selectPair(
                List.of(lower, upper),
                Math.sqrt(10.0D)
        );

        assertEquals(upper, selection.start());
        assertEquals(lower, selection.goal());
        assertNotEquals(selection.sourceStrongComponentId(), selection.targetStrongComponentId());
        assertTrue(selection.reachableStrongComponentsFromSource() >= 2);
    }

    @Test
    void graphDoesNotConnectDisjointBoundaryPatches() {
        TopologyService service = immediateService();
        service.submitSnapshot(key(0, 0, 0), xCorridorSnapshot(1, 15, 2, 2)).join();
        service.submitSnapshot(key(1, 0, 0), xCorridorSnapshot(0, 1, 2, 12)).join();
        MacroSearch search = new MacroSearch(
                service.graph(Level.OVERWORLD, new BlockPos(1, 2, 2), new BlockPos(17, 2, 12),
                        BaseClusterTopology.Channel.GROUND),
                MacroSearch.DEFAULT_WEIGHT
        );

        for (int iteration = 0; iteration < 20 && search.status() == MacroSearch.Status.RUNNING; iteration++) {
            search.step(64, Long.MAX_VALUE);
        }

        assertEquals(MacroSearch.Status.FAILED, search.status());
        assertNull(search.result());
    }

    @Test
    void graphRevisionValidationTracksTouchedClusters() {
        TopologyService service = immediateService();
        TopologyService.ClusterKey key = key(0, 0, 0);
        service.submitSnapshot(key, xCorridorSnapshot(1, 5, 2, 4)).join();
        MacroSearch.Graph graph = service.graph(
                Level.OVERWORLD,
                new BlockPos(1, 2, 4),
                new BlockPos(5, 2, 4),
                BaseClusterTopology.Channel.GROUND
        );
        assertTrue(graph.revisionsValid());

        service.invalidate(key);

        assertFalse(graph.revisionsValid());
    }

    @Test
    void corridorCanOnlyBeCommittedWhileItsSectionRevisionsAreCurrent() {
        TopologyService service = immediateService();
        TopologyService.ClusterKey left = key(0, 0, 0);
        TopologyService.ClusterKey right = key(1, 0, 0);
        service.submitSnapshot(left, xCorridorSnapshot(1, 15, 2, 4)).join();
        service.submitSnapshot(right, xCorridorSnapshot(0, 1, 2, 4)).join();
        MacroSearch search = new MacroSearch(
                service.graph(Level.OVERWORLD, new BlockPos(1, 2, 4), new BlockPos(17, 2, 4),
                        BaseClusterTopology.Channel.GROUND),
                MacroSearch.DEFAULT_WEIGHT
        );
        runMacroSearch(search);

        assertTrue(service.isCurrent(Level.OVERWORLD, search.result()));

        service.invalidate(right);

        assertFalse(service.isCurrent(Level.OVERWORLD, search.result()));
    }

    private static int classify(BlockState state, boolean supportBelow) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return TopologyService.classifyCell(state, shape, supportBelow);
    }

    private static boolean navigationGeometryChanged(BlockState oldState, BlockState newState) {
        return navigationGeometryChanged(oldState, false, newState, false);
    }

    private static boolean navigationGeometryChanged(BlockState oldState,
                                                     boolean oldContainsFluid,
                                                     BlockState newState,
                                                     boolean newContainsFluid) {
        VoxelShape oldShape = oldState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        VoxelShape newShape = newState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return TopologyService.navigationGeometryChanged(
                oldShape,
                oldContainsFluid,
                newShape,
                newContainsFluid
        );
    }

    private static TopologyService immediateService() {
        return new TopologyService(Runnable::run, Runnable::run, () -> true);
    }

    private static BaseClusterTopology.Snapshot xCorridorSnapshot(int fromX, int toX, int y, int z) {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        for (int x = fromX; x <= toX; x++) {
            cells[BaseClusterTopology.cellIndex(x, y, z)] =
                    (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);
            cells[BaseClusterTopology.cellIndex(x, y + 1, z)] =
                    (byte) BaseClusterTopology.VOLUME_OPEN;
        }
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot oneWayDropSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int openGround = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        cells[BaseClusterTopology.cellIndex(1, 5, 1)] = (byte) openGround;
        cells[BaseClusterTopology.cellIndex(1, 6, 1)] = BaseClusterTopology.VOLUME_OPEN;
        cells[BaseClusterTopology.cellIndex(2, 2, 1)] = (byte) openGround;
        cells[BaseClusterTopology.cellIndex(2, 3, 1)] = BaseClusterTopology.VOLUME_OPEN;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static void runMacroSearch(MacroSearch search) {
        for (int iteration = 0; iteration < 30 && search.status() == MacroSearch.Status.RUNNING; iteration++) {
            search.step(64, Long.MAX_VALUE);
        }
        assertEquals(MacroSearch.Status.SUCCEEDED, search.status(), () ->
                "failure=" + search.failure()
                        + ", blockedSection=" + search.blockedSection()
                        + ", metrics=" + search.metrics());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test worker interrupted", exception);
        }
    }

    private static TopologyService.ClusterKey key(int x, int y, int z) {
        return new TopologyService.ClusterKey(Level.OVERWORLD, SectionPos.of(x, y, z));
    }

    private static BaseClusterTopology.Snapshot openSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        cells[BaseClusterTopology.cellIndex(15, 1, 1)] =
                (byte) (BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN);
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot fluidSnapshot() {
        byte[] cells = openSnapshot().cells();
        cells[BaseClusterTopology.cellIndex(15, 1, 1)] |= BaseClusterTopology.FLUID;
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
