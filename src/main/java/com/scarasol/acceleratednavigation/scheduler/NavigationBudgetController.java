package com.scarasol.acceleratednavigation.scheduler;

import java.util.Arrays;

/** Bounded history of actual tick work; it never owns requests or path rules. */
public final class NavigationBudgetController {
    public static final long TARGET_NANOS = 45_000_000L;
    public static final long MAX_GRANT_NANOS = 20_000_000L;
    public static final long INITIAL_GRANT_NANOS = 4_000_000L;
    private static final long GROWTH_NANOS = 1_000_000L;
    private static final long MIN_TAIL_NANOS = 2_000_000L;
    private static final int HISTORY_SIZE = 32;
    private static final int WARMUP_SIZE = 8;

    private final long[] base = new long[HISTORY_SIZE];
    private final long[] tails = new long[HISTORY_SIZE];
    private final long[] scratch = new long[HISTORY_SIZE];
    private int count;
    private int cursor;
    private double baseEwma;
    private double tailEwma;
    private long previousFull;
    private long previousGrant = INITIAL_GRANT_NANOS;

    public record Choice(long grantNanos, long baseEstimateNanos, long tailReserveNanos) {}

    public Choice choose() {
        long estimate = Math.max((long) baseEwma, percentile(base));
        long tail = Math.max(MIN_TAIL_NANOS, Math.max((long) tailEwma, percentile(tails)));
        long desired = Math.max(0L, Math.min(MAX_GRANT_NANOS, TARGET_NANOS - estimate));
        if (count < WARMUP_SIZE) desired = Math.min(desired, INITIAL_GRANT_NANOS);
        long grant = Math.min(desired, previousGrant + GROWTH_NANOS);
        if (previousFull > TARGET_NANOS) grant = Math.min(grant, previousGrant / 2);
        return new Choice(grant, estimate, tail);
    }

    public void commit(Choice choice) {
        previousGrant = choice.grantNanos();
    }

    public void record(long fullNanos, long navigationNanos, long nonNavigationTailNanos) {
        if (fullNanos < 0 || navigationNanos < 0 || navigationNanos > fullNanos
                || nonNavigationTailNanos < 0 || nonNavigationTailNanos > fullNanos - navigationNanos)
            throw new IllegalArgumentException("invalid tick accounting");
        long nonNavigation = fullNanos - navigationNanos;
        baseEwma = count == 0 ? nonNavigation : baseEwma * .8 + nonNavigation * .2;
        tailEwma = count == 0 ? nonNavigationTailNanos : tailEwma * .8 + nonNavigationTailNanos * .2;
        base[cursor] = nonNavigation;
        tails[cursor] = nonNavigationTailNanos;
        cursor = (cursor + 1) % HISTORY_SIZE;
        count = Math.min(HISTORY_SIZE, count + 1);
        previousFull = fullNanos;
    }

    public int sampleCount() { return count; }

    private long percentile(long[] values) {
        if (count == 0) return 0L;
        System.arraycopy(values, 0, scratch, 0, count);
        Arrays.sort(scratch, 0, count);
        return scratch[(95 * count + 99) / 100 - 1];
    }
}
