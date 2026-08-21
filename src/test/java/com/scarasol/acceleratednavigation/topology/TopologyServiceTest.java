package com.scarasol.acceleratednavigation.topology;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyServiceTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void minecraftCollisionShapesProduceConservativeTopologyFlags() {
        assertEquals(0, classify(Blocks.STONE.defaultBlockState()));
        int air = classify(Blocks.AIR.defaultBlockState());
        assertTrue((air & BaseClusterTopology.VOLUME_OPEN) != 0);
        assertEquals(0, air & BaseClusterTopology.EXACT_REQUIRED);

        int slab = classify(Blocks.OAK_SLAB.defaultBlockState());
        assertTrue((slab & BaseClusterTopology.VOLUME_OPEN) != 0);
        assertTrue((slab & BaseClusterTopology.GROUND_OPEN) != 0);
        assertTrue((slab & BaseClusterTopology.EXACT_REQUIRED) != 0);
    }

    @Test
    void collisionClassificationIgnoresNonGeometricStateChanges() {
        BlockState wheatYoung = Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 0);
        BlockState wheatMature = wheatYoung.setValue(BlockStateProperties.AGE_7, 7);
        assertEquals(classify(wheatYoung), classify(wheatMature));

        BlockState shallowWater = Blocks.WATER.defaultBlockState()
                .setValue(LiquidBlock.LEVEL, 1);
        BlockState deeperWater = shallowWater.setValue(LiquidBlock.LEVEL, 7);
        assertEquals(classify(shallowWater), classify(deeperWater));
    }

    private static int classify(BlockState state) {
        return (int) TopologyService.sampleColumnFacts(new SingleStateGetter(state),
                BlockPos.ZERO) & 0xFF;
    }

    private record SingleStateGetter(BlockState state) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return position.equals(BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
