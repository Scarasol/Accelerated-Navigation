package com.scarasol.acceleratednavigation.topology;

/** Runtime operation exposed only to the formal benchmark control. */
public interface TopologyRuntimeFormalControlAccess {

    void acceleratedNavigation$clearCompletedCorridors();

    boolean acceleratedNavigation$allowsPrewarm();
}
