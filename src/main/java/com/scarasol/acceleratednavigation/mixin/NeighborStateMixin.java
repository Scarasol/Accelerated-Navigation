package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.navigation.NeighborStateStamp;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockState.class)
public abstract class NeighborStateMixin implements NeighborStateStamp {
    @Unique private volatile long acceleratedNavigation$neighborStamp;
    public long acceleratedNavigation$neighborStamp() { return acceleratedNavigation$neighborStamp; }
    public void acceleratedNavigation$neighborStamp(long stamp) { acceleratedNavigation$neighborStamp = stamp; }
}
