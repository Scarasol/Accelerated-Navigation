package com.scarasol.acceleratednavigation.topology;

final class CpuMoveCapabilities {
    static final long[] VALUES = new long[18];
    static {
        for (int dy = -4; dy <= 1; dy++)
            for (int horizontal = 1; horizontal <= 3; horizontal++)
                VALUES[(dy + 4) * 3 + horizontal - 1] = BaseClusterTopology.supportingCapabilities(
                        Math.max(0, dy), horizontal - 1, Math.max(0, -dy));
    }
    private CpuMoveCapabilities() {}
}
