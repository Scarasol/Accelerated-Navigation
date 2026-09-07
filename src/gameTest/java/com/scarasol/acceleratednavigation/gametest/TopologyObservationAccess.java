package com.scarasol.acceleratednavigation.gametest;

import java.util.Map;

/** Read-only production activity observation shared by benchmark consumers. */
public interface TopologyObservationAccess {

    Map<String, Long> acceleratedNavigation$snapshotMetrics();
}
