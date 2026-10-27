package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.extensions.IForgeBlock;
import net.minecraftforge.common.extensions.IForgeFluid;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AcceleratedNavigation.MOD_ID)
public final class NeighborSafety {
    public static final Predicate<BlockState> POTENTIAL = NeighborSafety::potential;
    private static volatile long epoch = 1;
    private static long eligibleQueries;
    private static final ClassValue<Boolean> DEFAULT_ADJACENT = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return declaredBy(type, "getAdjacentBlockPathType", IForgeBlock.class,
                    BlockState.class, BlockGetter.class, BlockPos.class, Mob.class, BlockPathTypes.class);
        }
    };

    private NeighborSafety() {}

    public static long epoch() { return epoch; }

    @SubscribeEvent
    public static void tagsUpdated(TagsUpdatedEvent event) {
        if (event.shouldUpdateStaticData()) invalidate();
    }

    public static synchronized void invalidate() { epoch++; }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        AcceleratedNavigation.LOGGER.info("AN_NEIGHBOR_EXPERIMENT variant=NS eligibleQueries={}", eligibleQueries);
    }

    public static void bind(PathNavigationRegion region, ServerLevel level, NodeEvaluator evaluator) {
        boolean eligible = region.getClass() == PathNavigationRegion.class
                && evaluator.getClass() == WalkNodeEvaluator.class
                && level.getServer().isSameThread() && !level.isDebug();
        ((NeighborSafetyRegion) region).acceleratedNavigation$neighborLevel(eligible ? level : null);
        if (eligible) eligibleQueries++;
    }

    public static void close(PathNavigationRegion region) {
        if (region != null) ((NeighborSafetyRegion) region).acceleratedNavigation$neighborLevel(null);
    }

    public static boolean potential(BlockState state) {
        NeighborStateStamp cache = (NeighborStateStamp) state;
        long generation = epoch;
        long stamp = cache.acceleratedNavigation$neighborStamp();
        if ((stamp >>> 1) == generation) return (stamp & 1) != 0;
        boolean potential = state.getClass() != BlockState.class
                || !DEFAULT_ADJACENT.get(state.getBlock().getClass())
                || state.getFluidState().getType() != Fluids.EMPTY
                || state.getFluidState().is(FluidTags.WATER)
                || !EmptyProvider.SUPPORTED
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || WalkNodeEvaluator.isBurningBlock(state)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
        cache.acceleratedNavigation$neighborStamp((generation << 1) | (potential ? 1 : 0));
        return potential;
    }

    private static boolean declaredBy(Class<?> type, String name, Class<?> owner, Class<?>... arguments) {
        try { return type.getMethod(name, arguments).getDeclaringClass() == owner; }
        catch (ReflectiveOperationException unavailable) { return false; }
    }

    private static final class EmptyProvider {
        private static final boolean SUPPORTED =
                declaredBy(Fluids.EMPTY.getClass(), "getFluidType", Fluid.class)
                && declaredBy(Fluids.EMPTY.getClass(), "getAdjacentBlockPathType", IForgeFluid.class,
                        FluidState.class, BlockGetter.class, BlockPos.class, Mob.class, BlockPathTypes.class)
                && Fluids.EMPTY.getFluidType() == ForgeMod.EMPTY_TYPE.get()
                && declaredBy(Fluids.EMPTY.getFluidType().getClass(), "getAdjacentBlockPathType", FluidType.class,
                        FluidState.class, BlockGetter.class, BlockPos.class, Mob.class, BlockPathTypes.class);
    }
}
