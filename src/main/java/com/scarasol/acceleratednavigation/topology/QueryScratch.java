package com.scarasol.acceleratednavigation.topology;

/** Two BFS arrays owned by one worker; busy callers get a temporary exclusive pair. */
final class QueryScratch {
    final Thread owner = Thread.currentThread();
    final int[] parent = new int[4096], queue = new int[4096];
    boolean busy;
    long fallbacks;

    QueryScratch acquire() {
        requireOwner();
        if (busy) {
            fallbacks++;
            var temporary = new QueryScratch();
            temporary.busy = true;
            return temporary;
        }
        busy = true;
        return this;
    }

    void release() {
        requireOwner();
        if (!busy) throw new IllegalStateException("scratch is not acquired");
        busy = false;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("query scratch belongs to another thread");
    }
}
