package com.scarasol.acceleratednavigation.api;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A main-thread search that preserves all algorithm state between bounded
 * calls. Implementations must defer expensive initialization to {@link #step}
 * so setup is charged to the same scheduler budget as node expansion.
 *
 * <p>This contract publishes only a final result. An implementation must not
 * expose a best-so-far frontier path as if it proved reachability.</p>
 */
public interface ResumableSearch<R> {

    Status step(int expansionBudget, long deadlineNanos);

    Status status();

    @Nullable
    R result();

    /**
     * Removes a running search from scheduler rotation until external work is
     * ready. Returning {@code true} promises to invoke {@code wakeup} exactly
     * once, after this method returns, when another call to {@link #step} can
     * make progress. Implementations that never wait keep the default.
     */
    default boolean park(Runnable wakeup) {
        Objects.requireNonNull(wakeup, "wakeup");
        return false;
    }

    default void cancel() {
    }

    enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
