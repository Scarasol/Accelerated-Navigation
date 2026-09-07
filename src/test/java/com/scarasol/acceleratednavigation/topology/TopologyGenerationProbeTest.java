package com.scarasol.acceleratednavigation.topology;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologyGenerationProbeTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var block : java.util.List.of(Blocks.AIR, Blocks.STONE, Blocks.WATER, Blocks.STONE_SLAB, Blocks.OAK_FENCE)) {
            block.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
        }
    }

    @Test void oracleDistinguishesSupportFluidAndNonFullCollisionWithoutPacking() {
        Cells cells = new Cells();
        BlockPos position = new BlockPos(7, 7, 7);
        assertEquals(1, TopologyGenerationProbe.independentFlags(cells, position));
        cells.values.put(position, Blocks.STONE.defaultBlockState());
        assertEquals(0, TopologyGenerationProbe.independentFlags(cells, position));
        assertEquals(3, TopologyGenerationProbe.independentFlags(cells, position.above()));
        cells.values.put(position, Blocks.WATER.defaultBlockState());
        assertEquals(7, TopologyGenerationProbe.independentFlags(cells, position));
        assertEquals(1, TopologyGenerationProbe.independentFlags(cells, position.above()));
        cells.values.put(position, Blocks.STONE_SLAB.defaultBlockState());
        assertEquals(11, TopologyGenerationProbe.independentFlags(cells, position));
        assertEquals(3, TopologyGenerationProbe.independentFlags(cells, position.above()));
        cells.values.put(position, Blocks.OAK_FENCE.defaultBlockState());
        assertEquals(11, TopologyGenerationProbe.independentFlags(cells, position));
    }

    @Test void sectionBottomSupportRemainsAQueryTimeHaloDependency() {
        Cells cells = new Cells();
        cells.values.put(new BlockPos(0, 15, 0), Blocks.STONE.defaultBlockState());
        assertEquals(1, TopologyGenerationProbe.independentFlags(cells, new BlockPos(0, 16, 0)));
        cells.values.put(new BlockPos(0, 16, 0), Blocks.STONE.defaultBlockState());
        assertEquals(3, TopologyGenerationProbe.independentFlags(cells, new BlockPos(0, 17, 0)));
    }

    private static final class Cells implements BlockGetter {
        final Map<BlockPos, BlockState> values = new HashMap<>();
        @Override public BlockEntity getBlockEntity(BlockPos position) { return null; }
        @Override public BlockState getBlockState(BlockPos position) { return values.getOrDefault(position, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
