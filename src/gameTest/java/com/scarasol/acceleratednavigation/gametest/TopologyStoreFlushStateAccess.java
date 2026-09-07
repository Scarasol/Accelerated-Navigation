package com.scarasol.acceleratednavigation.gametest;

/** Internal owner-side state used to share one observed flush count with diagnostics. */
public interface TopologyStoreFlushStateAccess {

    int acceleratedNavigation$activeFlushes();

    void acceleratedNavigation$releaseFlushAfterUncaughtError();
}
