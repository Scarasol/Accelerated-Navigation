package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.TopologyStoreObservationAccess;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushStateAccess;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Common read-only persistence observation for formal and refresh runs. */
@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyStore", remap = false)
abstract class TopologyStoreObservationMixin
        implements TopologyStoreObservationAccess, TopologyStoreFlushStateAccess {

    @Shadow @Final private Object monitor;
    @Shadow @Final private Set<ResourceKey<Level>> requestedFlushes;
    @Shadow @Final private Set<ResourceKey<Level>> queuedFlushes;

    @Unique
    private int acceleratedNavigation$activeFlushes;

    @Inject(method = "runRequestedFlush", at = @At("HEAD"))
    private void acceleratedNavigation$beginObservedFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        synchronized (monitor) {
            acceleratedNavigation$activeFlushes++;
        }
    }

    @Inject(method = "runRequestedFlush", at = @At("RETURN"))
    private void acceleratedNavigation$finishObservedFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        synchronized (monitor) {
            acceleratedNavigation$activeFlushes--;
        }
    }

    @Inject(
            method = "runWorker",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    shift = At.Shift.AFTER))
    private void acceleratedNavigation$releaseObservedFlushAfterTaskFailure(
            CallbackInfo callback) {
        synchronized (monitor) {
            if (acceleratedNavigation$activeFlushes > 0) {
                acceleratedNavigation$activeFlushes--;
            }
        }
    }

    @Override
    public StoreObservation acceleratedNavigation$storeObservation() {
        synchronized (monitor) {
            return new StoreObservation(requestedFlushes.size(), queuedFlushes.size(),
                    acceleratedNavigation$activeFlushes);
        }
    }

    @Override
    public int acceleratedNavigation$activeFlushes() {
        synchronized (monitor) {
            return acceleratedNavigation$activeFlushes;
        }
    }

    @Override
    public void acceleratedNavigation$releaseFlushAfterUncaughtError() {
        synchronized (monitor) {
            if (acceleratedNavigation$activeFlushes > 0) {
                acceleratedNavigation$activeFlushes--;
            }
        }
    }
}
