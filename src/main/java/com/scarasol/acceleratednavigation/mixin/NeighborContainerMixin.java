package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.NeighborPaletteSummary;
import com.scarasol.acceleratednavigation.navigation.NeighborSafety;
import java.util.function.Predicate;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PalettedContainer.class)
public abstract class NeighborContainerMixin {
    @WrapOperation(method = "maybeHas",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/Palette;maybeHas(Ljava/util/function/Predicate;)Z"),
            require = 1, expect = 1, allow = 1)
    private boolean potential(Palette<?> palette, Predicate<?> predicate, Operation<Boolean> original) {
        if (predicate != NeighborSafety.POTENTIAL) return original.call(palette, predicate);
        Class<?> type = palette.getClass();
        if (type == LinearPalette.class || type == HashMapPalette.class)
            return ((NeighborPaletteSummary) palette).acceleratedNavigation$mayInfluenceNeighbor();
        if (type == SingleValuePalette.class) return original.call(palette, predicate);
        return true;
    }
}
