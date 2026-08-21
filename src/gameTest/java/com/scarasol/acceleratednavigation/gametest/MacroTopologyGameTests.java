package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("accelerated_navigation")
@PrefixGameTestTemplate(false)
public final class MacroTopologyGameTests {

    private static final String EMPTY_TEMPLATE = "bastion/treasure/big_air_full";

    private MacroTopologyGameTests() {
    }

    /** Exercises the production request boundary without reaching into worker internals. */
    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE,
            batch = "macro_topology_request", timeoutTicks = 100)
    public static void productionMacroRequestCompletes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(8, 4, 8));
        BlockPos goal = helper.absolutePos(new BlockPos(12, 4, 8));
        TopologyService.MacroRequest request = TopologyService.forServer(level.getServer())
                .requestMacroQuery(
                        level,
                        UUID.randomUUID(),
                        start,
                        goal,
                        BaseClusterTopology.Channel.GROUND,
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                        NavigationScheduler.Priority.ACTIVE
                );

        helper.succeedWhen(() -> {
            if (!request.future().isDone()) {
                throw new GameTestAssertException("macro request is still pending");
            }
            try {
                request.future().join();
            } catch (RuntimeException failure) {
                throw new GameTestAssertException(
                        "production macro request failed: " + failure.getMessage());
            }
            MacroSearch.Progress progress = request.progress();
            if (progress.status() == ResumableSearch.Status.RUNNING) {
                throw new GameTestAssertException("completed request still reports running");
            }
        });
    }
}
