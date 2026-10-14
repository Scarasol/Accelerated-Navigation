import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(process.argv[2]);
const names = process.argv.slice(3);
assert(names.length >= 1);
const inputs = [];
inputs.push({ path: new URL(import.meta.url).pathname,
  sha256: createHash("sha256").update(readFileSync(new URL(import.meta.url))).digest("hex").toUpperCase() });
const read = path => {
  const content = readFileSync(path);
  inputs.push({ path, sha256: createHash("sha256").update(content).digest("hex").toUpperCase() });
  return JSON.parse(content.toString("utf8").replace(/^\uFEFF/, ""));
};
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const runs = names.map(name => {
  const directory = resolve(root, name);
  const performance = read(resolve(directory, "performance-comparison.json"));
  const handoffs = read(resolve(directory, "budget-handoff-traces.json"));
  const paths = read(resolve(directory, "budget-path-traces.json"));
  const launch = read(resolve(directory, "launch-environment.json"));
  const ticks = read(resolve(directory, "budget-ticks.json"));
  const exit = read(resolve(directory, "exit.json"));
  const ownership = read(resolve(directory, "budget-thread-checks.json"));
  assert(performance.complete && !performance.failed && !exit.timedOut && exit.exitCode === 0 && ownership.passed);
  assert.equal(ticks.mode, launch.mode);
  if (ticks.handoff) assert.equal(ticks.handoff, launch.handoff);
  for (let i = 1; i < ticks.samples.length; i++) {
    const before = ticks.samples[i - 1], after = ticks.samples[i];
    assert.equal(after.balanceAfterRefillNanos,
      Math.min(after.grantNanos, before.finalBalanceNanos - after.outsideNavigationNanos + after.grantNanos));
  }
  const routes = [];
  for (const trial of performance.trials.filter(t => !t.warmup)) {
    for (const [actor, result] of trial.actors.entries()) {
      const events = handoffs.events.filter(event => same(event.initial, result.start)
        && same(event.finalTarget, result.goal) && event.tick >= trial.submitTick && event.tick <= trial.endTick);
      if (!events.length) continue;
      assert.equal(new Set(events.map(e => e.query)).size, 1);
      const query = events[0].query;
      const searches = paths.searches.filter(s => s.input.query === query);
      assert.equal(searches.length, result.nativeSearch.calls);
      routes.push({ distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat, actor,
        start: result.start, goal: result.goal, state: result.state, success: result.success,
        finalPosition: result.finalPosition, calls: result.nativeSearch.calls,
        searches: searches.map((s, i) => ({ ordinal: i + 1, ...s.input,
          horizontalSpeed: Math.hypot(s.input.velocity[0], s.input.velocity[2]),
          canReach: s.canReach, nodes: s.nodes, end: s.nodes.at(-1), nullPath: s.nullPath })),
        branchEvents: events.filter(e => !e.event.startsWith("observe")),
        // Preserve every sample around the first real path's exhaustion and handoff.
        firstHandoffObservations: events.filter(e => e.event.startsWith("observe") && e.segmentPublication === 1
          && (e.pathDone || e.nodeIndex >= e.nodeCount - 1)),
        entryObservations: events.filter(e => e.entryPresent && e.event === "observe-return")
          .filter((e, i, list) => i === 0 || e.segmentPublication !== list[i - 1].segmentPublication),
        nativeStops: events.filter(e => e.event.startsWith("native-stop")),
        recoveries: events.filter(e => e.event === "recover-head") });
    }
  }
  const formal = performance.trials.filter(t => !t.warmup);
  return { name, launch, routes, handoffDeferrals: ticks.handoffDeferrals ?? 0, totals: {
    trials: formal.length, requests: formal.reduce((n, t) => n + t.totalActors, 0),
    successes: formal.reduce((n, t) => n + t.successfulActors, 0),
    recoveries: formal.reduce((n, t) => n + t.recoveriesDelta, 0),
    nativeCalls: formal.reduce((n, t) => n + t.nativeCallsDelta, 0),
    wholeCalls: formal.reduce((n, t) => n + t.wholeCallsDelta, 0)
  }, trials: formal.map(t => ({ distance: t.distance, concurrency: t.concurrency, repeat: t.repeat,
    requests: t.totalActors, successes: t.successfulActors,
    states: t.actors.reduce((counts, a) => ({ ...counts, [a.state]: (counts[a.state] ?? 0) + 1 }), {}),
    nativeCalls: t.nativeCallsDelta, recoveries: t.recoveriesDelta, wholeCalls: t.wholeCallsDelta })),
  performance };
});
const pairs = [];
for (let candidateIndex = 1; candidateIndex < runs.length; candidateIndex++) {
  const candidate = runs[candidateIndex];
  for (const baseline of runs.slice(0, candidateIndex)) {
    assert.deepEqual(candidate.launch.mods, baseline.launch.mods);
    for (const route of candidate.routes) {
      const reference = baseline.routes.find(r => r.distance === route.distance && r.concurrency === route.concurrency
        && r.repeat === route.repeat && r.actor === route.actor);
      assert(reference);
      assert.deepEqual(route.searches[0].targets, reference.searches[0].targets);
      assert.deepEqual(route.searches[0].nodes, reference.searches[0].nodes);
      assert.equal(route.searches[0].range, reference.searches[0].range);
      assert.equal(route.searches[0].accuracy, reference.searches[0].accuracy);
      assert.equal(route.searches[0].multiplier, reference.searches[0].multiplier);
    }
    for (const trial of candidate.performance.trials.filter(t => !t.warmup)) {
      const reference = baseline.performance.trials.find(t => !t.warmup && t.distance === trial.distance
        && t.concurrency === trial.concurrency && t.repeat === trial.repeat);
      assert(reference);
      for (const [actor, b] of trial.actors.entries()) {
        const a = reference.actors[actor];
        assert(same(a.start, b.start) && same(a.goal, b.goal));
        if (a.success === b.success) continue;
        pairs.push({ baseline: baseline.name, candidate: candidate.name, distance: trial.distance,
          concurrency: trial.concurrency, repeat: trial.repeat, actor, start: a.start, goal: a.goal,
          baselineState: a.state, candidateState: b.state,
          direction: a.success ? "NEW_FAILURE" : "NEW_SUCCESS" });
      }
    }
  }
}
const result = { schema: "BUDGET_HANDOFF_ROOT_CAUSE_DIAGNOSTIC_V1", inputs,
  diagnosticOnly: true, runs: runs.map(({ performance, ...run }) => run), pairs };
writeFileSync(resolve(root, "handoff-analysis.json"), JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify({ newFailures: pairs.filter(p => p.direction === "NEW_FAILURE"),
  newSuccesses: pairs.filter(p => p.direction === "NEW_SUCCESS").length,
  runs: runs.map(run => ({ name: run.name, totals: run.totals, handoffDeferrals: run.handoffDeferrals,
  routes: run.routes.map(route => ({ distance: route.distance, repeat: route.repeat, actor: route.actor,
    state: route.state, calls: route.calls, nativeStops: route.nativeStops.length,
    entries: route.entryObservations.length,
    decisions: route.branchEvents.filter(e => e.event === "intermediateArrival-decision")
      .map(e => ({ tick: e.tick, doneSince: e.doneSince, result: e.result,
        publication: e.segmentPublication, entry: e.entryPresent, contains: e.containsCurrentBlock, block: e.block })),
    graces: route.branchEvents.filter(e => e.event.endsWith("-deferred")).map(e => e.tick),
    recoveries: route.recoveries.length
  })) })) }, null, 2));
