package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
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

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyServiceTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
    void geometryInvalidationIgnoresEquivalentShapesButTracksFluidAndCollision() {
        BlockState wheatYoung = Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 0);
        BlockState wheatMature = wheatYoung.setValue(BlockStateProperties.AGE_7, 7);
        BlockState shallowWater = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
        BlockState deeperWater = shallowWater.setValue(LiquidBlock.LEVEL, 7);
        assertFalse(navigationGeometryChanged(wheatYoung, wheatMature));
        assertFalse(navigationGeometryChanged(shallowWater, true, deeperWater, true));

        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState openTrapdoor = closedTrapdoor.setValue(TrapDoorBlock.OPEN, true);
        assertTrue(navigationGeometryChanged(closedTrapdoor, openTrapdoor));
        BlockState drySlab = Blocks.OAK_SLAB.defaultBlockState();
        BlockState wetSlab = drySlab.setValue(BlockStateProperties.WATERLOGGED, true);
        assertTrue(TopologyService.navigationGeometryChanged(
                drySlab.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                false,
                true,
                wetSlab.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                true,
                true
        ));
    }

    @Test
    void snapshotSearchYieldsWithoutLosingCells() {
        int[] sampled = {0};
        TopologyService.SnapshotSearch search = new TopologyService.SnapshotSearch(index -> {
            sampled[0]++;
            return index == BaseClusterTopology.CELL_COUNT - 1
                    ? BaseClusterTopology.FLUID
                    : BaseClusterTopology.VOLUME_OPEN;
        });
        assertEquals(TopologyService.SnapshotSearch.Status.RUNNING,
                search.step(17, Long.MAX_VALUE));
        assertEquals(17, sampled[0]);
        assertEquals(TopologyService.SnapshotSearch.Status.SUCCEEDED,
                search.step(BaseClusterTopology.CELL_COUNT, Long.MAX_VALUE));
        assertEquals(BaseClusterTopology.CELL_COUNT, sampled[0]);
        assertEquals(BaseClusterTopology.FLUID,
                search.result().flags(BaseClusterTopology.CELL_COUNT - 1));
    }

    @Test
    void hierarchicalQueriesUseHigherMinimumWithoutChangingDistanceScaling() {
        assertEquals(2_048, queryNodeBudget(
                BlockPos.ZERO,
                new BlockPos(96, 0, 0),
                true
        ));
        assertEquals(1_024, queryNodeBudget(
                BlockPos.ZERO,
                new BlockPos(96, 0, 0),
                false
        ));
        assertEquals(4_096, queryNodeBudget(
                BlockPos.ZERO,
                new BlockPos(512, 0, 0),
                true
        ));
        assertEquals(8_192, queryNodeBudget(
                BlockPos.ZERO,
                new BlockPos(2_000, 0, 0),
                true
        ));
    }

    @Test
    void productionApiDoesNotExposeOrdinaryTopologyRequests() {
        assertFalse(Arrays.stream(TopologyService.class.getMethods())
                .anyMatch(method -> method.getName().equals("requestCluster")));
    }

    @Test
    void topologyWorkerHonorsPromotionAndDimensionAwareSignature() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(
                "topology-priority-test",
                Thread.NORM_PRIORITY
        );
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            executor.submit(Level.OVERWORLD, NavigationScheduler.Priority.BACKGROUND, () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            TopologyTaskExecutor.TaskHandle promoted = executor.submit(
                    Level.OVERWORLD,
                    NavigationScheduler.Priority.BACKGROUND,
                    () -> {
                        order.add("promoted");
                        completed.countDown();
                    }
            );
            executor.submit(Level.NETHER, NavigationScheduler.Priority.ACTIVE, () -> {
                order.add("active");
                completed.countDown();
            });
            promoted.promote(NavigationScheduler.Priority.PLAYER_PURSUIT);
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
    void topologyWorkerKeepsPurePrewarmBelowActiveWork() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(
                "topology-strict-priority-test",
                Thread.NORM_PRIORITY
        );
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            executor.submit(Level.OVERWORLD, NavigationScheduler.Priority.BACKGROUND, () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            executor.submit(
                    Level.OVERWORLD,
                    NavigationScheduler.Priority.BACKGROUND,
                    () -> {
                        order.add("prewarm");
                        completed.countDown();
                    },
                    false
            );
            executor.submit(
                    Level.OVERWORLD,
                    NavigationScheduler.Priority.ACTIVE,
                    () -> {
                        order.add("active");
                        completed.countDown();
                    }
            );
            releaseBlocker.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("active", "prewarm"), order);
        } finally {
            releaseBlocker.countDown();
            executor.shutdown();
        }
    }

    @Test
    void demandQueueSkipsIneligibleHeadWhenLaterDemandCanRun() throws Exception {
        Class<?> demandQueue = Arrays.stream(TopologyService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("DemandQueue"))
                .findFirst()
                .orElseThrow();
        Method firstEligible = demandQueue.getDeclaredMethod(
                "firstEligible",
                Iterable.class,
                java.util.function.Predicate.class
        );
        firstEligible.setAccessible(true);
        ArrayDeque<String> candidates = new ArrayDeque<>(List.of("blocked", "ready"));
        @SuppressWarnings("unchecked")
        String selected = (String) firstEligible.invoke(
                null,
                candidates,
                (java.util.function.Predicate<String>) candidate -> candidate.equals("ready")
        );
        assertEquals("ready", selected);
    }

    private static int classify(BlockState state, boolean supportBelow) {
        try {
            Method method = TopologyService.class.getDeclaredMethod(
                    "staticCellClassification",
                    BlockState.class
            );
            method.setAccessible(true);
            int flags = (int) method.invoke(null, state);
            flags &= BaseClusterTopology.VOLUME_OPEN
                    | BaseClusterTopology.GROUND_OPEN
                    | BaseClusterTopology.FLUID
                    | BaseClusterTopology.EXACT_REQUIRED;
            if (supportBelow && (flags & BaseClusterTopology.VOLUME_OPEN) != 0) {
                flags |= BaseClusterTopology.GROUND_OPEN;
            }
            return flags;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("static cell classification is not available", failure);
        }
    }

    private static boolean navigationGeometryChanged(BlockState oldState, BlockState newState) {
        return navigationGeometryChanged(
                oldState,
                !oldState.getFluidState().isEmpty(),
                newState,
                !newState.getFluidState().isEmpty()
        );
    }

    private static boolean navigationGeometryChanged(BlockState oldState,
                                                     boolean oldFluid,
                                                     BlockState newState,
                                                     boolean newFluid) {
        return TopologyService.navigationGeometryChanged(
                oldState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                oldFluid,
                !oldState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(),
                newState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                newFluid,
                !newState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
        );
    }

    private static int queryNodeBudget(BlockPos start,
                                       BlockPos goal,
                                       boolean hierarchical) {
        try {
            Method method = TopologyService.class.getDeclaredMethod(
                    "queryNodeBudget",
                    BlockPos.class,
                    BlockPos.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (int) method.invoke(null, start, goal, hierarchical);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("query node budget policy is not available", failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
