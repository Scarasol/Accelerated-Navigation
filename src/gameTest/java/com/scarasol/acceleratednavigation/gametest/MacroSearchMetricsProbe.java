package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.topology.MacroSearch;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/** Test-only snapshots of private search state captured by the terrain benchmark mixin. */
public final class MacroSearchMetricsProbe {

    private static final Map<MacroSearch.Metrics, Snapshot> SNAPSHOTS =
            new IdentityHashMap<>();

    private static boolean enabled;
    private static int diagnosticNodeBudget;

    private MacroSearchMetricsProbe() {
    }

    public static synchronized void beginStage(int nodeBudget) {
        SNAPSHOTS.clear();
        enabled = true;
        diagnosticNodeBudget = nodeBudget;
    }

    public static synchronized void endStage() {
        enabled = false;
        diagnosticNodeBudget = 0;
        SNAPSHOTS.clear();
    }

    public static synchronized int effectiveNodeBudget(int productionBudget) {
        return diagnosticNodeBudget <= 0
                ? productionBudget
                : Math.max(productionBudget, Math.min(8_192, diagnosticNodeBudget));
    }

    public static synchronized void capture(MacroSearch.Metrics metrics,
                                            int visitedNodeLimit,
                                            int discoveredNodes,
                                            int openNodes,
                                            int blockedNodes) {
        if (!enabled) {
            return;
        }
        SNAPSHOTS.put(metrics, new Snapshot(
                visitedNodeLimit,
                discoveredNodes,
                openNodes,
                blockedNodes
        ));
    }

    @Nullable
    public static synchronized Snapshot snapshot(@Nullable MacroSearch.Metrics metrics) {
        return metrics == null ? null : SNAPSHOTS.get(metrics);
    }

    @Nullable
    public static Snapshot combine(@Nullable Snapshot first, @Nullable Snapshot second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new Snapshot(
                first.visitedNodeLimit + second.visitedNodeLimit,
                first.discoveredNodes + second.discoveredNodes,
                first.openNodes + second.openNodes,
                first.blockedNodes + second.blockedNodes
        );
    }

    public record Snapshot(int visitedNodeLimit,
                           int discoveredNodes,
                           int openNodes,
                           int blockedNodes) {
    }
}
