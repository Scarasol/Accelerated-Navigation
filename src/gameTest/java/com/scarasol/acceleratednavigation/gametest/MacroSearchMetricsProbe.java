package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only snapshots of private search state captured by the terrain benchmark mixin. */
public final class MacroSearchMetricsProbe {

    private static final Map<MacroSearch.Metrics, Snapshot> SNAPSHOTS =
            new IdentityHashMap<>();

    private static boolean enabled;
    private static int diagnosticNodeBudget;
    private static volatile boolean queryTimingActive;
    private static QueryTiming queryTiming;

    private MacroSearchMetricsProbe() {
    }

    public static synchronized void beginQueryTiming() {
        queryTiming = new QueryTiming(System.nanoTime());
        queryTimingActive = true;
    }

    public static boolean queryTimingActive() {
        return queryTimingActive;
    }

    public static synchronized void recordRequestReturned() {
        if (queryTiming != null) {
            queryTiming.requestReturnedNanos = firstTimestamp(
                    queryTiming.requestReturnedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordResolveStart() {
        if (queryTiming != null) {
            queryTiming.resolveStarted(System.nanoTime(), false);
        }
    }

    public static synchronized void recordFallbackResolveStart() {
        if (queryTiming != null) {
            queryTiming.resolveStarted(System.nanoTime(), true);
        }
    }

    public static synchronized void recordEndpointFuturesReady() {
        if (queryTiming != null) {
            queryTiming.endpointFuturesReady(System.nanoTime());
        }
    }

    public static synchronized void recordResolveCallback() {
        if (queryTiming != null) {
            queryTiming.resolveCallback(System.nanoTime());
        }
    }

    public static synchronized void recordFlightStart() {
        if (queryTiming != null) {
            queryTiming.flightStartedNanos = firstTimestamp(
                    queryTiming.flightStartedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordSchedulerSubmit() {
        if (queryTiming != null) {
            queryTiming.schedulerSubmitNanos = firstTimestamp(
                    queryTiming.schedulerSubmitNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordSchedulerSubmitReturned() {
        if (queryTiming != null) {
            queryTiming.schedulerSubmitReturnedNanos = firstTimestamp(
                    queryTiming.schedulerSubmitReturnedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordSchedulerFutureComplete() {
        if (queryTiming != null) {
            queryTiming.schedulerFutureCompletedNanos = firstTimestamp(
                    queryTiming.schedulerFutureCompletedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordFlightCompleteCallback() {
        if (queryTiming != null) {
            queryTiming.flightCompleteCallbackNanos = firstTimestamp(
                    queryTiming.flightCompleteCallbackNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordRequestFinishEnter() {
        if (queryTiming != null) {
            queryTiming.requestFinishEnteredNanos = firstTimestamp(
                    queryTiming.requestFinishEnteredNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordRequestFinishReturn() {
        if (queryTiming != null) {
            queryTiming.requestFinishReturnedNanos = firstTimestamp(
                    queryTiming.requestFinishReturnedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordRequestFutureListenerComplete() {
        if (queryTiming != null) {
            queryTiming.requestFutureListenerCompletedNanos = firstTimestamp(
                    queryTiming.requestFutureListenerCompletedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordBenchmarkFutureObserved() {
        if (queryTiming != null) {
            queryTiming.benchmarkFutureObservedNanos = firstTimestamp(
                    queryTiming.benchmarkFutureObservedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordBenchmarkJoinComplete() {
        if (queryTiming != null) {
            queryTiming.benchmarkJoinCompletedNanos = firstTimestamp(
                    queryTiming.benchmarkJoinCompletedNanos,
                    System.nanoTime()
            );
        }
    }

    public static synchronized void recordMacroStepStart() {
        if (queryTiming != null) {
            queryTiming.stepStarted(System.nanoTime());
        }
    }

    public static synchronized void recordMacroStepEnd(String status,
                                                        boolean waitingForBuild,
                                                        boolean refining) {
        if (queryTiming != null) {
            queryTiming.stepEnded(
                    System.nanoTime(),
                    status,
                    waitingForBuild,
                    refining
            );
        }
    }

    public static synchronized void recordPendingDependencyPass() {
        if (queryTiming != null) {
            queryTiming.pendingDependencyPasses++;
        }
    }

    public static synchronized void recordDependencyCompletionBatch(int dependencies) {
        if (queryTiming != null && dependencies > 0) {
            queryTiming.dependencyCompletionBatches.add(dependencies);
        }
    }

    public static synchronized void recordCollisionShapeCall() {
        if (queryTiming != null) {
            queryTiming.collisionShapeCalls++;
        }
    }

    public static synchronized void recordCollisionFallbackCall() {
        if (queryTiming != null) {
            queryTiming.collisionFallbackCalls++;
        }
    }

    public static synchronized void recordStaticClassification(BlockState state,
                                                                int classification) {
        if (queryTiming == null) {
            return;
        }
        queryTiming.staticClassificationCalls++;
        if (classification < 0) {
            queryTiming.dynamicClassifications++;
        } else if (state.getBlock().getClass() == LiquidBlock.class) {
            queryTiming.liquidClassifications++;
        } else if ((classification & 1) == 0) {
            queryTiming.fullClassifications++;
        } else if ((classification & 8) != 0) {
            queryTiming.partialClassifications++;
        } else {
            queryTiming.openClassifications++;
        }
    }

    public static synchronized void recordPaletteUniform(int flags) {
        if (queryTiming == null) {
            return;
        }
        if ((flags & 1) == 0) {
            queryTiming.uniformFull++;
        } else if ((flags & 4) != 0 && (flags & 8) == 0) {
            queryTiming.uniformLiquid++;
        } else if ((flags & 1) != 0 && (flags & 8) == 0) {
            queryTiming.uniformOpen++;
        }
    }

    public static synchronized void recordQueryPark() {
        if (queryTiming != null) {
            queryTiming.park(System.nanoTime());
        }
    }

    public static synchronized void recordQueryWake() {
        if (queryTiming != null) {
            queryTiming.wake(System.nanoTime());
        }
    }

    public static synchronized void recordSchedulerSlice(long usedNanos) {
        if (queryTiming != null) {
            queryTiming.schedulerSlice(usedNanos);
        }
    }

    /** Captures the priority selected by the test-only scheduler return probe. */
    public static synchronized void recordSchedulerSelection(@Nullable Object request) {
        if (queryTiming != null) {
            queryTiming.schedulerSelection(readSchedulerPriority(request));
        }
    }

    public static synchronized void recordSchedulerTick(boolean haveTime) {
        if (queryTiming != null) {
            queryTiming.schedulerTick(haveTime);
        }
    }

    public static synchronized void recordSchedulerDrainStart() {
        if (queryTiming != null) {
            queryTiming.schedulerDrainStarted(System.nanoTime());
        }
    }

    public static synchronized void recordSchedulerDrainEnd() {
        if (queryTiming != null) {
            queryTiming.schedulerDrainEnded(System.nanoTime());
        }
    }

    public static synchronized OrchestrationSnapshot currentQueryTiming() {
        return queryTiming == null
                ? OrchestrationSnapshot.EMPTY
                : queryTiming.snapshot(System.nanoTime());
    }

    public static synchronized OrchestrationSnapshot finishQueryTiming() {
        if (queryTiming == null) {
            queryTimingActive = false;
            return OrchestrationSnapshot.EMPTY;
        }
        OrchestrationSnapshot snapshot = queryTiming.snapshot(System.nanoTime());
        queryTiming = null;
        queryTimingActive = false;
        return snapshot;
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

    public record OrchestrationSnapshot(double elapsedMillis,
                                        int parkCount,
                                        int wakeCount,
                                        int pendingDependencyPasses,
                                        BatchStats dependencyCompletionBatches,
                                        long collisionShapeCalls,
                                        FactsSnapshotStats facts,
                                        LatencyStats parkToWake,
                                        LatencyStats wakeToNextPark,
                                        SchedulerSnapshot scheduler,
                                        LifecycleSnapshot lifecycle,
                                        StepSnapshot steps) {
        private static final OrchestrationSnapshot EMPTY = new OrchestrationSnapshot(
                0.0D,
                0,
                0,
                0,
                BatchStats.EMPTY,
                0L,
                FactsSnapshotStats.EMPTY,
                LatencyStats.EMPTY,
                LatencyStats.EMPTY,
                SchedulerSnapshot.EMPTY,
                LifecycleSnapshot.EMPTY,
                StepSnapshot.EMPTY
        );
    }

    public record LifecycleSnapshot(double requestReturnedMillis,
                                    double resolveFirstStartMillis,
                                    double resolveLastStartMillis,
                                    int resolveAttempts,
                                    int fallbackResolveAttempts,
                                    double endpointFirstReadyMillis,
                                    double endpointLastReadyMillis,
                                    int endpointReadyEvents,
                                    double resolveCallbackFirstMillis,
                                    double resolveCallbackLastMillis,
                                    int resolveCallbacks,
                                    double flightStartMillis,
                                    double schedulerSubmitMillis,
                                    double schedulerSubmitReturnedMillis,
                                    double macroFirstStepMillis,
                                    double macroLastStepStartMillis,
                                    double macroLastStepEndMillis,
                                    double schedulerFutureCompleteMillis,
                                    double flightCompleteCallbackMillis,
                                    double requestFinishEnterMillis,
                                    double requestFinishReturnMillis,
                                    double requestFutureListenerCompleteMillis,
                                    double benchmarkFutureObservedMillis,
                                    double benchmarkJoinCompleteMillis) {
        private static final LifecycleSnapshot EMPTY = new LifecycleSnapshot(
                -1.0D, -1.0D, -1.0D, 0, 0,
                -1.0D, -1.0D, 0,
                -1.0D, -1.0D, 0,
                -1.0D, -1.0D, -1.0D,
                -1.0D, -1.0D, -1.0D,
                -1.0D, -1.0D, -1.0D, -1.0D,
                -1.0D, -1.0D, -1.0D
        );
    }

    public record StepSnapshot(int calls,
                               int completedCalls,
                               int runningReturns,
                               int succeededReturns,
                               int failedReturns,
                               int waitingForBuildReturns,
                               int refiningReturns,
                               double totalMillis,
                               LatencyStats durationMillis,
                               LatencyStats interStartMillis,
                               LatencyStats idleBetweenStepsMillis,
                               LatencyStats wakeToStepMillis,
                               LatencyStats stepEndToParkMillis) {
        private static final StepSnapshot EMPTY = new StepSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0.0D,
                LatencyStats.EMPTY,
                LatencyStats.EMPTY,
                LatencyStats.EMPTY,
                LatencyStats.EMPTY,
                LatencyStats.EMPTY
        );
    }

    public record SchedulerSnapshot(int ticks,
                                    int haveTimeTicks,
                                    int noTimeTicks,
                                    int drains,
                                    double drainMillis,
                                    LatencyStats drainDurationMillis,
                                    int slices,
                                    double chargedMillis,
                                    Map<String, SchedulerPrioritySnapshot> priorities) {
        private static final SchedulerSnapshot EMPTY =
                new SchedulerSnapshot(
                        0, 0, 0, 0, 0.0D,
                        LatencyStats.EMPTY,
                        0, 0.0D, Map.of()
                );
    }

    public record SchedulerPrioritySnapshot(int slices,
                                            double chargedMillis,
                                            double chargedPercent) {
    }

    public record LatencyStats(int samples,
                               double minimumMillis,
                               double p50Millis,
                               double p95Millis,
                               double maximumMillis) {
        private static final LatencyStats EMPTY = new LatencyStats(0, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    public record BatchStats(int samples,
                             int totalDependencies,
                             double meanDependencies,
                             int p50Dependencies,
                             int p95Dependencies,
                             int maximumDependencies) {
        private static final BatchStats EMPTY = new BatchStats(0, 0, 0.0D, 0, 0, 0);
    }

    public record FactsSnapshotStats(long staticClassificationCalls,
                                     long fullClassifications,
                                     long openClassifications,
                                     long partialClassifications,
                                     long liquidClassifications,
                                     long dynamicClassifications,
                                     long uniformFull,
                                     long uniformOpen,
                                     long uniformLiquid,
                                     long collisionShapeCalls,
                                     long collisionFallbackCalls) {
        private static final FactsSnapshotStats EMPTY = new FactsSnapshotStats(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );
    }

    private static final class QueryTiming {
        private final long startedNanos;
        private final List<Long> parkToWakeNanos = new ArrayList<>();
        private final List<Long> wakeToNextParkNanos = new ArrayList<>();
        private final List<Long> stepDurationNanos = new ArrayList<>();
        private final List<Long> interStepStartNanos = new ArrayList<>();
        private final List<Long> idleBetweenStepsNanos = new ArrayList<>();
        private final List<Long> wakeToStepNanos = new ArrayList<>();
        private final List<Long> stepEndToParkNanos = new ArrayList<>();
        private final List<Long> schedulerDrainDurationNanos = new ArrayList<>();
        private final List<Integer> dependencyCompletionBatches = new ArrayList<>();
        private long collisionShapeCalls;
        private long collisionFallbackCalls;
        private long staticClassificationCalls;
        private long fullClassifications;
        private long openClassifications;
        private long partialClassifications;
        private long liquidClassifications;
        private long dynamicClassifications;
        private long uniformFull;
        private long uniformOpen;
        private long uniformLiquid;
        private long lastParkNanos;
        private long lastWakeNanos;
        private long consumedWakeNanos;
        private long lastStepStartedNanos;
        private long lastStepEndedNanos;
        private long measuredParkStepEndNanos;
        private long activeSchedulerDrainStartedNanos;
        private int parkCount;
        private int wakeCount;
        private int pendingDependencyPasses;
        private int schedulerTicks;
        private int schedulerHaveTimeTicks;
        private int schedulerNoTimeTicks;
        private int schedulerDrains;
        private int schedulerSlices;
        private long schedulerChargedNanos;
        private final int[] schedulerPrioritySlices =
                new int[NavigationScheduler.Priority.values().length];
        private final long[] schedulerPriorityChargedNanos =
                new long[NavigationScheduler.Priority.values().length];
        private NavigationScheduler.Priority selectedSchedulerPriority;
        private long requestReturnedNanos;
        private long resolveFirstStartedNanos;
        private long resolveLastStartedNanos;
        private int resolveAttempts;
        private int fallbackResolveAttempts;
        private long endpointFirstReadyNanos;
        private long endpointLastReadyNanos;
        private int endpointReadyEvents;
        private long resolveCallbackFirstNanos;
        private long resolveCallbackLastNanos;
        private int resolveCallbacks;
        private long flightStartedNanos;
        private long schedulerSubmitNanos;
        private long schedulerSubmitReturnedNanos;
        private long macroFirstStepNanos;
        private long macroLastStepStartedNanos;
        private long macroLastStepEndedNanos;
        private long schedulerFutureCompletedNanos;
        private long flightCompleteCallbackNanos;
        private long requestFinishEnteredNanos;
        private long requestFinishReturnedNanos;
        private long requestFutureListenerCompletedNanos;
        private long benchmarkFutureObservedNanos;
        private long benchmarkJoinCompletedNanos;
        private int macroStepCalls;
        private int completedStepCalls;
        private int runningStepReturns;
        private int succeededStepReturns;
        private int failedStepReturns;
        private int waitingForBuildReturns;
        private int refiningReturns;

        private QueryTiming(long startedNanos) {
            this.startedNanos = startedNanos;
        }

        private void resolveStarted(long now, boolean fallback) {
            resolveFirstStartedNanos = firstTimestamp(resolveFirstStartedNanos, now);
            resolveLastStartedNanos = now;
            resolveAttempts++;
            if (fallback) {
                fallbackResolveAttempts++;
            }
        }

        private void endpointFuturesReady(long now) {
            endpointFirstReadyNanos = firstTimestamp(endpointFirstReadyNanos, now);
            endpointLastReadyNanos = now;
            endpointReadyEvents++;
        }

        private void resolveCallback(long now) {
            resolveCallbackFirstNanos = firstTimestamp(resolveCallbackFirstNanos, now);
            resolveCallbackLastNanos = now;
            resolveCallbacks++;
        }

        private void stepStarted(long now) {
            macroFirstStepNanos = firstTimestamp(macroFirstStepNanos, now);
            if (lastStepStartedNanos != 0L) {
                interStepStartNanos.add(Math.max(0L, now - lastStepStartedNanos));
            }
            if (lastStepEndedNanos != 0L) {
                idleBetweenStepsNanos.add(Math.max(0L, now - lastStepEndedNanos));
            }
            if (lastWakeNanos != 0L && lastWakeNanos != consumedWakeNanos) {
                wakeToStepNanos.add(Math.max(0L, now - lastWakeNanos));
                consumedWakeNanos = lastWakeNanos;
            }
            lastStepStartedNanos = now;
            macroLastStepStartedNanos = now;
            macroStepCalls++;
        }

        private void stepEnded(long now,
                               String status,
                               boolean waitingForBuild,
                               boolean refining) {
            if (lastStepStartedNanos != 0L) {
                stepDurationNanos.add(Math.max(0L, now - lastStepStartedNanos));
            }
            lastStepEndedNanos = now;
            macroLastStepEndedNanos = now;
            completedStepCalls++;
            if ("RUNNING".equals(status)) {
                runningStepReturns++;
            } else if ("SUCCEEDED".equals(status)) {
                succeededStepReturns++;
            } else if ("FAILED".equals(status)) {
                failedStepReturns++;
            }
            if (waitingForBuild) {
                waitingForBuildReturns++;
            }
            if (refining) {
                refiningReturns++;
            }
        }

        private void park(long now) {
            if (lastWakeNanos > lastParkNanos) {
                wakeToNextParkNanos.add(Math.max(0L, now - lastWakeNanos));
            }
            if (lastStepEndedNanos != 0L
                    && measuredParkStepEndNanos != lastStepEndedNanos) {
                stepEndToParkNanos.add(Math.max(0L, now - lastStepEndedNanos));
                measuredParkStepEndNanos = lastStepEndedNanos;
            }
            parkCount++;
            lastParkNanos = now;
        }

        private void wake(long now) {
            if (lastParkNanos > lastWakeNanos) {
                parkToWakeNanos.add(Math.max(0L, now - lastParkNanos));
            }
            wakeCount++;
            lastWakeNanos = now;
        }

        private void schedulerSlice(long usedNanos) {
            schedulerSlices++;
            schedulerChargedNanos += Math.max(0L, usedNanos);
            if (selectedSchedulerPriority != null) {
                int index = selectedSchedulerPriority.ordinal();
                schedulerPrioritySlices[index]++;
                schedulerPriorityChargedNanos[index] += Math.max(0L, usedNanos);
            }
            selectedSchedulerPriority = null;
        }

        private void schedulerSelection(@Nullable NavigationScheduler.Priority priority) {
            selectedSchedulerPriority = priority;
        }

        private void schedulerTick(boolean haveTime) {
            schedulerTicks++;
            if (haveTime) {
                schedulerHaveTimeTicks++;
            } else {
                schedulerNoTimeTicks++;
            }
        }

        private void schedulerDrainStarted(long now) {
            if (activeSchedulerDrainStartedNanos != 0L) {
                return;
            }
            activeSchedulerDrainStartedNanos = now;
            schedulerDrains++;
        }

        private void schedulerDrainEnded(long now) {
            if (activeSchedulerDrainStartedNanos == 0L) {
                return;
            }
            schedulerDrainDurationNanos.add(
                    Math.max(0L, now - activeSchedulerDrainStartedNanos)
            );
            activeSchedulerDrainStartedNanos = 0L;
        }

        private OrchestrationSnapshot snapshot(long now) {
            return new OrchestrationSnapshot(
                    nanosToMillis(Math.max(0L, now - startedNanos)),
                    parkCount,
                    wakeCount,
                    pendingDependencyPasses,
                    summarizeBatches(dependencyCompletionBatches),
                    collisionShapeCalls,
                    new FactsSnapshotStats(
                            staticClassificationCalls,
                            fullClassifications,
                            openClassifications,
                            partialClassifications,
                            liquidClassifications,
                            dynamicClassifications,
                            uniformFull,
                            uniformOpen,
                            uniformLiquid,
                            collisionShapeCalls,
                            collisionFallbackCalls
                    ),
                    summarize(parkToWakeNanos),
                    summarize(wakeToNextParkNanos),
                    schedulerSnapshot(),
                    lifecycleSnapshot(now),
                    stepSnapshot()
            );
        }

        private LifecycleSnapshot lifecycleSnapshot(long now) {
            return new LifecycleSnapshot(
                    elapsedMillis(now, requestReturnedNanos),
                    elapsedMillis(now, resolveFirstStartedNanos),
                    elapsedMillis(now, resolveLastStartedNanos),
                    resolveAttempts,
                    fallbackResolveAttempts,
                    elapsedMillis(now, endpointFirstReadyNanos),
                    elapsedMillis(now, endpointLastReadyNanos),
                    endpointReadyEvents,
                    elapsedMillis(now, resolveCallbackFirstNanos),
                    elapsedMillis(now, resolveCallbackLastNanos),
                    resolveCallbacks,
                    elapsedMillis(now, flightStartedNanos),
                    elapsedMillis(now, schedulerSubmitNanos),
                    elapsedMillis(now, schedulerSubmitReturnedNanos),
                    elapsedMillis(now, macroFirstStepNanos),
                    elapsedMillis(now, macroLastStepStartedNanos),
                    elapsedMillis(now, macroLastStepEndedNanos),
                    elapsedMillis(now, schedulerFutureCompletedNanos),
                    elapsedMillis(now, flightCompleteCallbackNanos),
                    elapsedMillis(now, requestFinishEnteredNanos),
                    elapsedMillis(now, requestFinishReturnedNanos),
                    elapsedMillis(now, requestFutureListenerCompletedNanos),
                    elapsedMillis(now, benchmarkFutureObservedNanos),
                    elapsedMillis(now, benchmarkJoinCompletedNanos)
            );
        }

        private StepSnapshot stepSnapshot() {
            return new StepSnapshot(
                    macroStepCalls,
                    completedStepCalls,
                    runningStepReturns,
                    succeededStepReturns,
                    failedStepReturns,
                    waitingForBuildReturns,
                    refiningReturns,
                    nanosToMillis(sum(stepDurationNanos)),
                    summarize(stepDurationNanos),
                    summarize(interStepStartNanos),
                    summarize(idleBetweenStepsNanos),
                    summarize(wakeToStepNanos),
                    summarize(stepEndToParkNanos)
            );
        }

        private double elapsedMillis(long now, long timestamp) {
            return timestamp == 0L
                    ? -1.0D
                    : nanosToMillis(Math.max(0L, timestamp - startedNanos));
        }

        private SchedulerSnapshot schedulerSnapshot() {
            Map<String, SchedulerPrioritySnapshot> priorities = new LinkedHashMap<>();
            for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                int slices = schedulerPrioritySlices[priority.ordinal()];
                if (slices == 0) {
                    continue;
                }
                long charged = schedulerPriorityChargedNanos[priority.ordinal()];
                priorities.put(priority.name(), new SchedulerPrioritySnapshot(
                        slices,
                        nanosToMillis(charged),
                        schedulerChargedNanos == 0L
                                ? 0.0D
                                : charged * 100.0D / schedulerChargedNanos
                ));
            }
            return new SchedulerSnapshot(
                    schedulerTicks,
                    schedulerHaveTimeTicks,
                    schedulerNoTimeTicks,
                    schedulerDrains,
                    nanosToMillis(sum(schedulerDrainDurationNanos)),
                    summarize(schedulerDrainDurationNanos),
                    schedulerSlices,
                    nanosToMillis(schedulerChargedNanos),
                    priorities
            );
        }
    }

    @Nullable
    private static NavigationScheduler.Priority readSchedulerPriority(@Nullable Object request) {
        if (request == null) {
            return null;
        }
        for (Field field : request.getClass().getDeclaredFields()) {
            if (field.getType() != NavigationScheduler.Priority.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(request);
                return value instanceof NavigationScheduler.Priority priority ? priority : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LatencyStats summarize(List<Long> values) {
        if (values.isEmpty()) {
            return LatencyStats.EMPTY;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return new LatencyStats(
                sorted.length,
                nanosToMillis(sorted[0]),
                nanosToMillis(percentile(sorted, 0.50D)),
                nanosToMillis(percentile(sorted, 0.95D)),
                nanosToMillis(sorted[sorted.length - 1])
        );
    }

    private static BatchStats summarizeBatches(List<Integer> values) {
        if (values.isEmpty()) {
            return BatchStats.EMPTY;
        }
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        int total = values.stream().mapToInt(Integer::intValue).sum();
        return new BatchStats(
                sorted.length,
                total,
                (double) total / sorted.length,
                sorted[(int) Math.floor((sorted.length - 1) * 0.50D)],
                sorted[(int) Math.floor((sorted.length - 1) * 0.95D)],
                sorted[sorted.length - 1]
        );
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long firstTimestamp(long current, long candidate) {
        return current == 0L ? candidate : current;
    }

    private static long sum(List<Long> values) {
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }
}
