package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushStateAccess;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyStore", remap = false)
abstract class TopologyStoreFlushDiagnosticMixin implements TopologyStoreFlushAccess {

    @Shadow
    @Final
    private Object monitor;

    @Shadow
    @Final
    private Map<?, ?> loads;

    @Shadow
    @Final
    private Map<?, ?> pending;

    @Shadow
    @Final
    private Map<ResourceKey<Level>, Integer> dirtyChunksByDimension;

    @Shadow
    @Final
    private Set<ResourceKey<Level>> requestedFlushes;

    @Shadow
    @Final
    private Set<ResourceKey<Level>> queuedFlushes;

    @Shadow
    @Final
    private ArrayDeque<?> foreground;

    @Shadow
    @Final
    private ArrayDeque<?> background;

    @Shadow
    private long flushes;

    @Shadow
    private long flushFailures;

    @Shadow
    private boolean closing;

    @Unique
    private long acceleratedNavigation$probeGeneration;

    @Unique
    private long acceleratedNavigation$scheduledProbeGeneration;

    @Unique
    private long acceleratedNavigation$runningProbeGeneration;

    @Unique
    private ResourceKey<Level> acceleratedNavigation$probeDimension;

    @Unique
    private FlushOutcome acceleratedNavigation$probeOutcome = FlushOutcome.SUCCESS;

    @Unique
    private volatile FlushStage acceleratedNavigation$probeStage = FlushStage.IDLE;

    @Unique
    private boolean acceleratedNavigation$physicalFlushReached;

    @Unique
    private long acceleratedNavigation$flushesBeforeTarget;

    @Unique
    private long acceleratedNavigation$failuresBeforeTarget;

    @Unique
    private long acceleratedNavigation$targetFlushes;

    @Unique
    private long acceleratedNavigation$targetFlushFailures;

    @Inject(
            method = "requestSave",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/scarasol/acceleratednavigation/topology/TopologyStore;"
                            + "scheduleFlushIfReadyLocked(Lnet/minecraft/resources/ResourceKey;)V",
                    shift = At.Shift.BEFORE))
    private void acceleratedNavigation$bindSaveRequest(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        if (acceleratedNavigation$probeStage != FlushStage.ARMED_REQUEST) {
            return;
        }
        synchronized (monitor) {
            if (acceleratedNavigation$probeStage == FlushStage.ARMED_REQUEST
                    && dimension.equals(acceleratedNavigation$probeDimension)) {
                acceleratedNavigation$probeStage = FlushStage.WAITING_FOR_FLUSH_TASK;
            }
        }
    }

    @Inject(method = "scheduleFlushIfReadyLocked", at = @At("RETURN"))
    private void acceleratedNavigation$bindScheduledFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        FlushStage stage = acceleratedNavigation$probeStage;
        if (stage != FlushStage.WAITING_FOR_FLUSH_TASK
                && stage != FlushStage.ARMED_BEFORE_FLUSH) {
            return;
        }
        synchronized (monitor) {
            if (acceleratedNavigation$isWaitingForTargetLocked(dimension)
                    && requestedFlushes.contains(dimension)
                    && queuedFlushes.contains(dimension)) {
                acceleratedNavigation$scheduledProbeGeneration =
                        acceleratedNavigation$probeGeneration;
            }
        }
    }

    private void acceleratedNavigation$afterTargetFlushDequeue(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        FlushStage stage = acceleratedNavigation$probeStage;
        if (stage != FlushStage.WAITING_FOR_FLUSH_TASK
                && stage != FlushStage.ARMED_BEFORE_FLUSH) {
            return;
        }
        synchronized (monitor) {
            if (acceleratedNavigation$scheduledProbeGeneration == 0L
                    || acceleratedNavigation$scheduledProbeGeneration
                    != acceleratedNavigation$probeGeneration
                    || !dimension.equals(acceleratedNavigation$probeDimension)) {
                return;
            }
            acceleratedNavigation$runningProbeGeneration =
                    acceleratedNavigation$scheduledProbeGeneration;
            acceleratedNavigation$scheduledProbeGeneration = 0L;
            if (acceleratedNavigation$probeStage == FlushStage.WAITING_FOR_FLUSH_TASK) {
                acceleratedNavigation$probeStage = FlushStage.BLOCKED_AFTER_DEQUEUE;
                acceleratedNavigation$waitAtBarrierLocked(
                        FlushStage.BLOCKED_AFTER_DEQUEUE);
            }
        }
    }

    @Inject(method = "runRequestedFlush", at = @At("HEAD"))
    private void acceleratedNavigation$beginRequestedFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        acceleratedNavigation$afterTargetFlushDequeue(dimension, callback);
        synchronized (monitor) {
            if (acceleratedNavigation$isRunningTargetLocked(dimension)) {
                acceleratedNavigation$physicalFlushReached = false;
                acceleratedNavigation$flushesBeforeTarget = flushes;
                acceleratedNavigation$failuresBeforeTarget = flushFailures;
            }
        }
    }

    @Inject(
            method = "runRequestedFlush",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/scarasol/acceleratednavigation/topology/TopologyStore;"
                            + "flush(Lnet/minecraft/resources/ResourceKey;)I",
                    shift = At.Shift.BEFORE))
    private void acceleratedNavigation$beforePhysicalFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        if (acceleratedNavigation$probeStage != FlushStage.ARMED_BEFORE_FLUSH) {
            return;
        }
        synchronized (monitor) {
            if (!acceleratedNavigation$isRunningTargetLocked(dimension)
                    || acceleratedNavigation$probeStage
                    != FlushStage.ARMED_BEFORE_FLUSH) {
                return;
            }
            acceleratedNavigation$physicalFlushReached = true;
            acceleratedNavigation$probeStage = FlushStage.BLOCKED_BEFORE_FLUSH;
            acceleratedNavigation$waitAtBarrierLocked(FlushStage.BLOCKED_BEFORE_FLUSH);
        }
    }

    @Inject(method = "runRequestedFlush", at = @At("RETURN"))
    private void acceleratedNavigation$finishRequestedFlush(
            ResourceKey<Level> dimension,
            CallbackInfo callback) {
        synchronized (monitor) {
            if (!acceleratedNavigation$isRunningTargetLocked(dimension)) {
                return;
            }
            if (acceleratedNavigation$physicalFlushReached) {
                acceleratedNavigation$targetFlushes =
                        flushes - acceleratedNavigation$flushesBeforeTarget;
                acceleratedNavigation$targetFlushFailures =
                        flushFailures - acceleratedNavigation$failuresBeforeTarget;
                if (acceleratedNavigation$probeStage != FlushStage.ABORTED) {
                    acceleratedNavigation$probeStage = FlushStage.COMPLETE;
                }
            } else if (acceleratedNavigation$probeStage != FlushStage.ABORTED) {
                acceleratedNavigation$probeStage = FlushStage.ARMED_BEFORE_FLUSH;
            }
            acceleratedNavigation$runningProbeGeneration = 0L;
        }
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void acceleratedNavigation$applyProbeOutcome(
            ResourceKey<Level> dimension,
            CallbackInfoReturnable<Integer> callback) {
        if (acceleratedNavigation$probeStage != FlushStage.RUNNING) {
            return;
        }
        FlushOutcome outcome;
        synchronized (monitor) {
            if (!acceleratedNavigation$isRunningTargetLocked(dimension)
                    || acceleratedNavigation$probeStage != FlushStage.RUNNING) {
                return;
            }
            outcome = acceleratedNavigation$probeOutcome;
            if (outcome == FlushOutcome.UNCAUGHT_ERROR) {
                acceleratedNavigation$probeStage = FlushStage.THROWING_UNCAUGHT_ERROR;
            }
        }
        if (outcome == FlushOutcome.IO_FAILURE) {
            callback.setReturnValue(1);
        } else if (outcome == FlushOutcome.UNCAUGHT_ERROR) {
            throw new IllegalStateException("test-only uncaught topology flush failure");
        }
    }

    @Inject(
            method = "runWorker",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    shift = At.Shift.AFTER))
    private void acceleratedNavigation$afterUncaughtTaskFailure(CallbackInfo callback) {
        if (acceleratedNavigation$probeStage != FlushStage.THROWING_UNCAUGHT_ERROR) {
            return;
        }
        synchronized (monitor) {
            if (acceleratedNavigation$probeStage == FlushStage.THROWING_UNCAUGHT_ERROR
                    && acceleratedNavigation$runningProbeGeneration
                    == acceleratedNavigation$probeGeneration) {
                acceleratedNavigation$runningProbeGeneration = 0L;
                acceleratedNavigation$probeStage = FlushStage.UNCAUGHT_ERROR_CAUGHT;
            }
        }
    }

    @Override
    public FlushObservation acceleratedNavigation$flushObservation() {
        synchronized (monitor) {
            return new FlushObservation(
                    acceleratedNavigation$probeGeneration,
                    requestedFlushes.size(),
                    queuedFlushes.size(),
                    acceleratedNavigation$probeDimension != null
                            && requestedFlushes.contains(acceleratedNavigation$probeDimension),
                    acceleratedNavigation$probeDimension != null
                            && queuedFlushes.contains(acceleratedNavigation$probeDimension),
                    ((TopologyStoreFlushStateAccess) this)
                            .acceleratedNavigation$activeFlushes(),
                    acceleratedNavigation$targetFlushes,
                    acceleratedNavigation$targetFlushFailures,
                    acceleratedNavigation$probeStage);
        }
    }

    @Override
    public long acceleratedNavigation$tryPrepareFlushProbe(
            FlushOutcome outcome,
            ResourceKey<Level> dimension) {
        synchronized (monitor) {
            if (!acceleratedNavigation$probeCanBeReplacedLocked()) {
                throw new IllegalStateException("a topology flush probe is already active");
            }
            if (!acceleratedNavigation$storeIsIdleLocked()) {
                return 0L;
            }
            acceleratedNavigation$probeGeneration++;
            acceleratedNavigation$scheduledProbeGeneration = 0L;
            acceleratedNavigation$runningProbeGeneration = 0L;
            acceleratedNavigation$probeDimension = dimension;
            acceleratedNavigation$probeOutcome = outcome;
            acceleratedNavigation$physicalFlushReached = false;
            acceleratedNavigation$targetFlushes = 0L;
            acceleratedNavigation$targetFlushFailures = 0L;
            acceleratedNavigation$probeStage = FlushStage.ARMED_REQUEST;
            return acceleratedNavigation$probeGeneration;
        }
    }

    @Override
    public void acceleratedNavigation$releaseFlushProbe() {
        synchronized (monitor) {
            if (acceleratedNavigation$probeStage == FlushStage.BLOCKED_AFTER_DEQUEUE) {
                acceleratedNavigation$probeStage = FlushStage.ARMED_BEFORE_FLUSH;
            } else if (acceleratedNavigation$probeStage
                    == FlushStage.BLOCKED_BEFORE_FLUSH) {
                acceleratedNavigation$probeStage = FlushStage.RUNNING;
            } else {
                throw new IllegalStateException(
                        "topology flush probe is not waiting at a barrier");
            }
            monitor.notifyAll();
        }
    }

    @Override
    public void acceleratedNavigation$abortFlushProbe() {
        synchronized (monitor) {
            if (acceleratedNavigation$probeStage == FlushStage.UNCAUGHT_ERROR_CAUGHT
                    && ((TopologyStoreFlushStateAccess) this)
                    .acceleratedNavigation$activeFlushes() > 0) {
                // The injected exception bypasses runRequestedFlush's RETURN hook. Release
                // that test-only observation before starting the next isolated scenario.
                ((TopologyStoreFlushStateAccess) this)
                        .acceleratedNavigation$releaseFlushAfterUncaughtError();
            }
            acceleratedNavigation$probeStage = FlushStage.ABORTED;
            monitor.notifyAll();
        }
    }

    @Override
    public void acceleratedNavigation$resetAbortedFlushProbe() {
        synchronized (monitor) {
            if (acceleratedNavigation$probeStage != FlushStage.ABORTED
                    || ((TopologyStoreFlushStateAccess) this)
                    .acceleratedNavigation$activeFlushes() != 0
                    || acceleratedNavigation$runningProbeGeneration != 0L) {
                throw new IllegalStateException(
                        "cannot reset an active or non-aborted topology flush probe");
            }
            acceleratedNavigation$probeStage = FlushStage.IDLE;
            monitor.notifyAll();
        }
    }

    @Unique
    private boolean acceleratedNavigation$isWaitingForTargetLocked(
            ResourceKey<Level> dimension) {
        return acceleratedNavigation$probeGeneration != 0L
                && dimension.equals(acceleratedNavigation$probeDimension)
                && (acceleratedNavigation$probeStage == FlushStage.WAITING_FOR_FLUSH_TASK
                || acceleratedNavigation$probeStage == FlushStage.ARMED_BEFORE_FLUSH);
    }

    @Unique
    private boolean acceleratedNavigation$isRunningTargetLocked(
            ResourceKey<Level> dimension) {
        return acceleratedNavigation$runningProbeGeneration != 0L
                && acceleratedNavigation$runningProbeGeneration
                == acceleratedNavigation$probeGeneration
                && dimension.equals(acceleratedNavigation$probeDimension);
    }

    @Unique
    private boolean acceleratedNavigation$probeCanBeReplacedLocked() {
        return acceleratedNavigation$probeStage == FlushStage.IDLE
                || acceleratedNavigation$probeStage == FlushStage.COMPLETE;
    }

    @Unique
    private boolean acceleratedNavigation$storeIsIdleLocked() {
        return ((TopologyStoreFlushStateAccess) this).acceleratedNavigation$activeFlushes() == 0
                && loads.isEmpty()
                && pending.isEmpty()
                && dirtyChunksByDimension.isEmpty()
                && requestedFlushes.isEmpty()
                && queuedFlushes.isEmpty()
                && foreground.isEmpty()
                && background.isEmpty();
    }

    @Unique
    private void acceleratedNavigation$waitAtBarrierLocked(FlushStage blocked) {
        while (acceleratedNavigation$probeStage == blocked && !closing) {
            try {
                monitor.wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                acceleratedNavigation$probeStage = FlushStage.ABORTED;
            }
        }
        if (closing && acceleratedNavigation$probeStage == blocked) {
            acceleratedNavigation$runningProbeGeneration = 0L;
            acceleratedNavigation$probeStage = FlushStage.ABORTED;
        }
    }
}
