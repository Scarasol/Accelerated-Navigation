package com.scarasol.acceleratednavigation.navigation;

import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Checks unrepresented block semantics around the actual corridor's standing cells. */
final class GroundAbilityGuard {
    private GroundAbilityGuard() {}
    static boolean supports(ServerLevel level,SectionPos section,BaseClusterTopology graph,
            Set<Integer> components,NavigationCapabilities capabilities) {
        var chunk=level.getChunkSource().getChunkNow(section.x(),section.z());
        if(chunk==null||components.isEmpty())return false;
        int index=level.getSectionIndexFromSectionY(section.y());
        if(index<0||index>=chunk.getSectionsCount())return false;
        int height=(int)Math.ceil(capabilities.height());
        BlockPos.MutableBlockPos point=new BlockPos.MutableBlockPos();
        for(int cell=0;cell<4096;cell++) {
            int x=BaseClusterTopology.x(cell),y=BaseClusterTopology.y(cell),z=BaseClusterTopology.z(cell);
            if(!components.contains(graph.componentAt(x,y,z)))continue;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=height;dy++) {
                point.set(section.minBlockX()+x+dx,section.minBlockY()+y+dy,section.minBlockZ()+z+dz);
                if(!level.hasChunkAt(point)||!safe(level.getBlockState(point)))return false;
            }
        }
        return true;
    }
    private static boolean safe(BlockState state) {
        if(!state.getBlock().getClass().getName().startsWith("net.minecraft."))return false;
        return !state.is(Blocks.FIRE)&&!state.is(Blocks.SOUL_FIRE)&&!state.is(Blocks.MAGMA_BLOCK)
                &&!state.is(Blocks.LAVA)&&!state.is(Blocks.LAVA_CAULDRON)
                &&!state.is(Blocks.CACTUS)&&!state.is(Blocks.SWEET_BERRY_BUSH)&&!state.is(Blocks.WITHER_ROSE)
                &&!state.is(Blocks.POWDER_SNOW)
                &&!(state.getBlock() instanceof BaseRailBlock)
                &&(!(state.getBlock() instanceof CampfireBlock)||!state.getValue(CampfireBlock.LIT));
    }
}
