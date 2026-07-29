package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.TopologyGraphAudit;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.CompletableFuture;

@GameTestHolder("accelerated_navigation")
@PrefixGameTestTemplate(false)
public final class MacroTopologyGameTests {

    private static final String EMPTY_TEMPLATE = "bastion/treasure/big_air_full";

    private MacroTopologyGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE,
            batch = "macro_topology_sampling", timeoutTicks = 100)
    public static void snapshotsRealCollisionAndFluidShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos reference = helper.absolutePos(new BlockPos(8, 4, 8));
        SectionPos section = SectionPos.of(reference);
        BlockPos boundary = new BlockPos(
                section.maxBlockX(),
                section.minBlockY() + 4,
                section.minBlockZ() + 6
        );
        level.setBlockAndUpdate(
                boundary,
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)
        );

        TopologyService service = TopologyService.forServer(level.getServer());
        TopologyService.Metrics before = service.metrics();
        CompletableFuture<BaseClusterTopology> future = TopologyGraphAudit.requestClusterDependency(
                service,
                level,
                section,
                NavigationScheduler.Priority.ACTIVE
        );

        helper.succeedWhen(() -> {
            if (!future.isDone()) {
                throw new GameTestAssertException("topology snapshot is still pending");
            }
            BaseClusterTopology topology = future.join();
            int u = boundary.getZ() & 15;
            int v = boundary.getY() & 15;
            if (!topology.hasFluid(Direction.EAST, u, v)) {
                throw new GameTestAssertException("waterlogged boundary cell was not recorded as fluid");
            }
            boolean exactComponent = topology.boundaryComponents(
                            Direction.EAST,
                            BaseClusterTopology.Channel.GROUND
                    ).stream()
                    .anyMatch(component -> component.requiresExactCheck()
                            && component.containsFluid());
            if (!exactComponent) {
                throw new GameTestAssertException("partial fluid collision did not require exact backend check");
            }
            TopologyService.Metrics after = service.metrics();
            if (after.snapshotCells() - before.snapshotCells() < BaseClusterTopology.CELL_COUNT) {
                throw new GameTestAssertException("snapshot did not account for a complete section");
            }
            if (after.snapshotNanos() <= before.snapshotNanos()
                    || after.buildNanos() <= before.buildNanos()) {
                throw new GameTestAssertException("snapshot/build resource counters were not updated");
            }
            AcceleratedNavigation.LOGGER.info(
                    "Macro topology real-section sample: snapshot={} us, build={} us, retained={} bytes",
                    (after.snapshotNanos() - before.snapshotNanos()) / 1_000L,
                    (after.buildNanos() - before.buildNanos()) / 1_000L,
                    after.retainedBytes() - before.retainedBytes()
            );
        });
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE,
            batch = "macro_topology_invalidation", timeoutTicks = 100)
    public static void invalidatesPublishedSectionAfterBlockChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos changedPosition = helper.absolutePos(new BlockPos(6, 5, 6));
        SectionPos section = SectionPos.of(changedPosition);
        TopologyService service = TopologyService.forServer(level.getServer());
        TopologyService.ClusterKey key = new TopologyService.ClusterKey(level.dimension(), section);
        CompletableFuture<BaseClusterTopology> future = TopologyGraphAudit.requestClusterDependency(
                service,
                level,
                section,
                NavigationScheduler.Priority.ACTIVE
        );
        boolean[] changed = {false};
        long[] publishedRevision = {-1L};

        helper.succeedWhen(() -> {
            if (!future.isDone()) {
                throw new GameTestAssertException("topology snapshot is still pending");
            }
            if (!changed[0]) {
                future.join();
                publishedRevision[0] = service.revision(key);
                level.setBlockAndUpdate(changedPosition, Blocks.STONE.defaultBlockState());
                changed[0] = true;
            }
            if (service.revision(key) <= publishedRevision[0]) {
                throw new GameTestAssertException("block change did not advance the section revision");
            }
            if (service.topology(key) != null) {
                throw new GameTestAssertException("stale section topology remained published");
            }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE,
            batch = "macro_topology_semantic_invalidation", timeoutTicks = 150)
    public static void keepsDemandAliveAndIgnoresEqualCollisionShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos changedPosition = helper.absolutePos(new BlockPos(6, 5, 6));
        SectionPos section = SectionPos.of(changedPosition);
        TopologyService service = TopologyService.forServer(level.getServer());
        TopologyService.ClusterKey key = new TopologyService.ClusterKey(level.dimension(), section);
        CompletableFuture<BaseClusterTopology> request = TopologyGraphAudit.requestClusterDependency(
                service,
                level,
                section,
                NavigationScheduler.Priority.ACTIVE
        );

        level.setBlockAndUpdate(changedPosition, Blocks.STONE.defaultBlockState());
        long changedRevision = service.revision(key);
        level.setBlockAndUpdate(changedPosition, Blocks.DIRT.defaultBlockState());
        if (changedRevision <= 0L || service.revision(key) != changedRevision) {
            throw new GameTestAssertException(
                    "equal full-block collision shapes advanced the topology revision"
            );
        }

        helper.succeedWhen(() -> {
            if (!request.isDone()) {
                throw new GameTestAssertException("stable topology demand is still pending");
            }
            BaseClusterTopology topology = request.join();
            if (topology.revision() != changedRevision || service.topology(key) != topology) {
                throw new GameTestAssertException(
                        "request did not survive the pre-build geometry invalidation"
                );
            }

            level.setBlockAndUpdate(changedPosition, Blocks.COBBLESTONE.defaultBlockState());
            if (service.revision(key) != changedRevision || service.topology(key) != topology) {
                throw new GameTestAssertException(
                        "equal collision shapes invalidated an already published topology"
                );
            }
        });
    }
}
