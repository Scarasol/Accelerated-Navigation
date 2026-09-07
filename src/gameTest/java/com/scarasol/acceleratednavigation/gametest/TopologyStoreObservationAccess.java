package com.scarasol.acceleratednavigation.gametest;

/** Small read-only view of persistence activity used by both test consumers. */
public interface TopologyStoreObservationAccess {

    record StoreObservation(int requestedFlushes,
                            int queuedFlushes,
                            int activeFlushes) {
    }

    StoreObservation acceleratedNavigation$storeObservation();
}
