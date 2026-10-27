package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.navigation.NeighborPaletteSummary;
import com.scarasol.acceleratednavigation.navigation.NeighborSafety;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LinearPalette.class, HashMapPalette.class})
public abstract class NeighborPaletteMixin implements NeighborPaletteSummary {
    @Unique private long acceleratedNavigation$neighborEpoch;
    @Unique private int acceleratedNavigation$neighborSize;
    @Unique private boolean acceleratedNavigation$neighborPotential;

    @SuppressWarnings("unchecked")
    public boolean acceleratedNavigation$mayInfluenceNeighbor() {
        Palette<BlockState> palette = (Palette<BlockState>) this;
        long epoch = NeighborSafety.epoch();
        int size = palette.getSize();
        if (acceleratedNavigation$neighborEpoch != epoch || acceleratedNavigation$neighborSize != size) {
            boolean potential = palette.maybeHas(NeighborSafety.POTENTIAL);
            acceleratedNavigation$neighborPotential = potential;
            acceleratedNavigation$neighborSize = size;
            acceleratedNavigation$neighborEpoch = epoch;
        }
        return acceleratedNavigation$neighborPotential;
    }

    @Inject(method = "read", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void invalidate(net.minecraft.network.FriendlyByteBuf input, CallbackInfo callback) {
        acceleratedNavigation$neighborEpoch = 0;
    }
}
