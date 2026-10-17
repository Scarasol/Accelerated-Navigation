import { test } from "node:test";
import assert from "node:assert/strict";
import { readyDispatches } from "./analyze-ready.mjs";

const phases = [
  "NavigationScheduler.requestReadyDispatch()V",
  "NavigationScheduler.drainReady()V",
  "NavigationScheduler.drain(JI)V",
  "SegmentedNavigationService.lambda$queue$5(LWork;Ljava/util/function/Supplier;)Ljava/lang/Object;",
  "PathFinder.m_77427_(...)LPath;",
  "SegmentedNavigationService.nextSegment(LWork;)V",
  "SegmentedNavigationService.lambda$queue$6(LWork;Ljava/lang/Object;Ljava/lang/Throwable;)V",
];
const event = (phase, depth, start, end, thread = 1) => ({
  phase, depth, start, end, thread, tick: 1, exclusive: 1,
});

test("ready work uses ancestry rather than coincident times on other threads or siblings", () => {
  const result = readyDispatches([
    event(1, 0, 10, 100), event(2, 1, 11, 99), event(3, 2, 12, 90),
    event(4, 3, 15, 20), event(4, 0, 15, 20, 2),
    event(4, 0, 101, 102), event(0, 0, 103, 104),
  ], phases);
  assert.equal(result.readyCalls, 1);
  assert.equal(result.enteredQueueDrains, 1);
  assert.equal(result.operationCallbacks, 1);
  assert.equal(result.nativeSearches, 1);
  assert.equal(result.requestCalls, 1);
  assert.equal(result.readySchedulerShellNanos, 2);
});

test("a gated callback and a queue drain without an operation remain distinguishable", () => {
  const result = readyDispatches([
    event(1, 0, 0, 1), event(1, 0, 10, 20), event(2, 1, 11, 19),
  ], phases);
  assert.equal(result.readyCalls, 2);
  assert.equal(result.skippedBeforeDrain, 1);
  assert.equal(result.enteredQueueDrains, 1);
  assert.equal(result.withoutOperationCallbacks, 2);
});

test("a task that requests an async window is work even without a native search", () => {
  const result = readyDispatches([
    event(1, 0, 0, 10), event(2, 1, 1, 9), event(3, 2, 2, 8),
    event(5, 3, 3, 7), event(6, 2, 8, 8),
  ], phases);
  assert.equal(result.withOperationCallbacks, 1);
  assert.equal(result.withNativeSearches, 0);
  assert.equal(result.dispatches[0].nextSegments, 1);
  assert.equal(result.operationCallbacks, 1);
});
