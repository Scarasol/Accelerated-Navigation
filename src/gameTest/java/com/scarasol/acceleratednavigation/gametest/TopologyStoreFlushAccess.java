package com.scarasol.acceleratednavigation.gametest;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Test-only controls for one isolated persistence flush diagnostic. */
public interface TopologyStoreFlushAccess {

    enum FlushOutcome {
        SUCCESS,
        IO_FAILURE,
        UNCAUGHT_ERROR
    }

    enum FlushStage {
        IDLE,
        ARMED_REQUEST,
        WAITING_FOR_FLUSH_TASK,
        BLOCKED_AFTER_DEQUEUE,
        ARMED_BEFORE_FLUSH,
        BLOCKED_BEFORE_FLUSH,
        RUNNING,
        COMPLETE,
        THROWING_UNCAUGHT_ERROR,
        UNCAUGHT_ERROR_CAUGHT,
        ABORTED
    }

    record FlushObservation(long generation,
                            int requestedFlushes,
                            int queuedFlushes,
                            boolean targetRequested,
                            boolean targetQueued,
                            int activeFlushes,
                            long targetFlushes,
                            long targetFlushFailures,
                            FlushStage stage) {
    }

    FlushObservation acceleratedNavigation$flushObservation();

    long acceleratedNavigation$tryPrepareFlushProbe(
            FlushOutcome outcome,
            ResourceKey<Level> dimension);

    void acceleratedNavigation$releaseFlushProbe();

    void acceleratedNavigation$abortFlushProbe();

    void acceleratedNavigation$resetAbortedFlushProbe();
}
