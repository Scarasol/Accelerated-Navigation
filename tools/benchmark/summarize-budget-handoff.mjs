import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const workspace = process.cwd();
const roots = [
  ".tmp/navigation-budget-handoff-20261013-trace",
  ".tmp/navigation-budget-handoff-20261013-grace",
  ".tmp/navigation-budget-handoff-20261014-grace2"
];
const inputs = [];
const load = path => {
  const bytes = readFileSync(resolve(workspace, path));
  inputs.push({ path, sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase() });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const compactEvent = e => {
  if (!e) return null;
  const keys = ["sequence", "event", "tick", "doneSince", "result", "position", "block", "velocity",
    "cursor", "stageEnd", "attempt", "stride", "recoveryCount", "segmentPublication",
    "last", "nativeStopped", "segmentReachable", "stable", "windowCurrent", "proofCurrent",
    "windowSection", "windowComponent", "containsCurrentBlock", "entryPresent", "entry", "pathDone"];
  return Object.fromEntries(keys.filter(k => e[k] !== undefined).map(k => [k, e[k]]));
};
const batches = roots.map(root => {
  const analysis = load(`${root}/handoff-analysis.json`);
  return { root, inputHashes: analysis.inputs, pairs: analysis.pairs,
    runs: analysis.runs.map(run => ({ name: run.name, launch: run.launch, totals: run.totals,
      trials: run.trials, handoffDeferrals: run.handoffDeferrals,
      routes: run.routes.map(r => {
        const firstDone = r.firstHandoffObservations.find(e => e.pathDone);
        const firstDecision = r.branchEvents.find(e => e.event === "intermediateArrival-decision"
          && e.segmentPublication === 1);
        const initialBody = r.branchEvents.find(e => e.event === "physical-decision");
        const next = firstDone ? r.firstHandoffObservations.filter(e => e.tick > firstDone.tick
          && e.tick <= firstDone.tick + 3 && e.event === "observe-return") : [];
        return { distance: r.distance, repeat: r.repeat, actor: r.actor, success: r.success,
          state: r.state, finalPosition: r.finalPosition, calls: r.calls,
          nativeStops: r.nativeStops.length, firstDone: compactEvent(firstDone),
          firstBodyDecision: compactEvent(initialBody), firstArrivalDecision: compactEvent(firstDecision),
          nextObservations: next.map(compactEvent),
          graces: r.branchEvents.filter(e => e.event.endsWith("-deferred")).map(compactEvent),
          recoveries: r.recoveries.map(e => ({ ...compactEvent(e), stack: e.stack })),
          searches: r.searches.map(s => ({ ordinal: s.ordinal, tick: s.tick, position: s.position,
            velocity: s.velocity, targets: s.targets, range: s.range, accuracy: s.accuracy,
            multiplier: s.multiplier, canReach: s.canReach, nodeCount: s.nodes.length, end: s.end }))
        };
      }) }))
  };
});
const final = batches.at(-1);
const original = final.runs.find(r => r.name === "dynamic-unchanged");
const candidate = final.runs.find(r => r.name === "dynamic-grace2");
assert(original && candidate);
const delta = (a, b) => (b - a) / a * 100;
const compared = final.pairs.filter(p => p.baseline === original.name && p.candidate === candidate.name);
const summary = {
  reference: original.name, candidate: candidate.name,
  requestsPerMode: original.totals.requests,
  newFailures: compared.filter(p => p.direction === "NEW_FAILURE").length,
  newSuccesses: compared.filter(p => p.direction === "NEW_SUCCESS").length,
  successes: [original.totals.successes, candidate.totals.successes],
  nativeCalls: [original.totals.nativeCalls, candidate.totals.nativeCalls],
  nativeCallsChangePercent: delta(original.totals.nativeCalls, candidate.totals.nativeCalls),
  recoveries: [original.totals.recoveries, candidate.totals.recoveries],
  recoveriesChangePercent: delta(original.totals.recoveries, candidate.totals.recoveries),
  // Diagnostic batches are deliberately kept apart from previous performance measurements.
  noPerformanceClaim: true, allFirstInputsAndNodesMatched: true,
  balanceConservationPassed: true
};
const sensitivityRuns = ["fixed4-no-trace", "dynamic-grace2-no-trace"].map(name => {
  const directory = `${roots.at(-1)}/${name}`;
  const performance = load(`${directory}/performance-comparison.json`);
  const launch = load(`${directory}/launch-environment.json`);
  const exit = load(`${directory}/exit.json`);
  const ownership = load(`${directory}/budget-thread-checks.json`);
  const budget = load(`${directory}/budget-ticks.json`);
  assert(performance.complete && !performance.failed && !exit.timedOut && exit.exitCode === 0 && ownership.passed);
  assert.equal(launch.trace, false);
  assert.deepEqual(launch.mods, final.runs[0].launch.mods);
  assert.equal(budget.mode, launch.mode);
  assert.equal(budget.handoff, launch.handoff);
  for (let i = 1; i < budget.samples.length; i++) {
    const a = budget.samples[i - 1], b = budget.samples[i];
    assert.equal(b.balanceAfterRefillNanos,
      Math.min(b.grantNanos, a.finalBalanceNanos - b.outsideNavigationNanos + b.grantNanos));
  }
  const formal = performance.trials.filter(t => !t.warmup);
  return { name, launch, trials: formal.map(t => ({
    distance: t.distance, repeat: t.repeat, requests: t.totalActors, successes: t.successfulActors,
    states: t.actors.reduce((counts, a) => ({ ...counts, [a.state]: (counts[a.state] ?? 0) + 1 }), {}),
    nativeCalls: t.nativeCallsDelta, wholeCalls: t.wholeCallsDelta, recoveries: t.recoveriesDelta,
    watched: (t.distance === 18 ? [29, 285] : [25, 281]).map(actor => ({ actor,
      state: t.actors[actor].state, calls: t.actors[actor].nativeSearch.calls }))
  })), performance };
});
const sensitivityPairs = [];
for (const trial of sensitivityRuns[1].performance.trials.filter(t => !t.warmup)) {
  const reference = sensitivityRuns[0].performance.trials.find(t => !t.warmup && t.distance === trial.distance
    && t.repeat === trial.repeat && t.concurrency === trial.concurrency);
  assert(reference);
  for (const [actor, b] of trial.actors.entries()) {
    const a = reference.actors[actor];
    assert.deepEqual(a.start, b.start);
    assert.deepEqual(a.goal, b.goal);
    if (a.success !== b.success) sensitivityPairs.push({ distance: trial.distance, actor,
      baselineState: a.state, candidateState: b.state, direction: a.success ? "NEW_FAILURE" : "NEW_SUCCESS" });
  }
}
const sensitivity = {
  runs: sensitivityRuns.map(({ performance, ...run }) => run),
  newFailures: sensitivityPairs.filter(p => p.direction === "NEW_FAILURE"),
  newSuccesses: sensitivityPairs.filter(p => p.direction === "NEW_SUCCESS").length,
  pairs: sensitivityPairs,
  repetitions: 1, interpretation: "No per-route traces; additional instrument sensitivity check, not formal performance acceptance."
};
const identity = file => createHash("sha256").update(readFileSync(resolve(workspace, file))).digest("hex").toUpperCase();
const sameProductionAndProbe = batches.flatMap(b => b.runs).every(r =>
  r.launch.mods.filter(m => m.name !== "budget-prototype.jar").every(m =>
    final.runs[0].launch.mods.some(f => f.name === m.name && f.sha256 === m.sha256)));
assert(sameProductionAndProbe);
const result = { schema: "DYNAMIC_BUDGET_HANDOFF_STUDY_EVIDENCE_V1", date: "2026-10-14",
  normativeDesign: "docs/designs/2026-10-13-native-detail-navigation-contract-zh.md",
  sameProductionAndProbe, source: "tools/benchmark/summarize-budget-handoff.mjs",
  sourceSha256: identity("tools/benchmark/summarize-budget-handoff.mjs"),
  inputs, summary, batches, sensitivity,
  limitations: [
    "Only speed II, 18/28-block routes, 512 concurrent requests, two repeats; no production acceptance.",
    "Early broad diagnostics changed scheduling and produced extra UNAVAILABLE states; all retained.",
    "Native-stop/partial/stale exclusion branches of the grace were read, not independently fault-injected here.",
    "The exact internal reason for the final partial has not been isolated by native-input replay.",
    "Grace 2 is an experimental bound, not a speed-independent optimum."
  ] };
const fullStudy = JSON.stringify(result, null, 2) + "\n";
writeFileSync(resolve(workspace, `${roots.at(-1)}/study-evidence.json`), fullStudy);
const groupPairs = pairs => {
  const groups = new Map();
  for (const p of pairs) {
    const key = `${p.baseline}/${p.candidate}`;
    if (!groups.has(key)) groups.set(key, { baseline: p.baseline, candidate: p.candidate,
      newSuccesses: 0, newFailures: [] });
    const group = groups.get(key);
    if (p.direction === "NEW_SUCCESS") group.newSuccesses++;
    else group.newFailures.push(p);
  }
  return [...groups.values()];
};
const review = {
  schema: "HANDOFF_REVIEW_SELECTED_RAW_EVIDENCE_V1",
  fullStudyPath: `${roots.at(-1)}/study-evidence.json`,
  fullStudySha256: createHash("sha256").update(fullStudy).digest("hex").toUpperCase(),
  selection: "All trial/state totals and all watched route outcomes; selected detailed timelines. Full raw data remains separate.",
  summary, sameProductionAndProbe,
  sensitivity: { ...sensitivity, pairs: undefined },
  batches: batches.map((b, index) => ({ root: b.root, analysisInput: inputs[index],
    comparisons: groupPairs(b.pairs),
    runs: b.runs.map(r => ({ name: r.name, launch: r.launch, totals: r.totals, trials: r.trials,
      handoffDeferralsIncludingWarmup: r.handoffDeferrals,
      routes: r.routes.map(t => ({ distance: t.distance, repeat: t.repeat, actor: t.actor,
        state: t.state, calls: t.calls, nativeStops: t.nativeStops,
        firstArrival: compactEvent(t.firstArrivalDecision),
        recoveries: t.recoveries.length, deferredTicks: t.graces.map(g => g.tick) })) }))
  })),
  details: [
    { batch: 0, run: "branch-fixed4", distance: 18, actor: 29 },
    { batch: 0, run: "branch-dynamic12", distance: 18, actor: 29 },
    { batch: 1, run: "dynamic-grace1", distance: 28, actor: 25 },
    { batch: 2, run: "dynamic-unchanged", distance: 28, actor: 25 },
    { batch: 2, run: "dynamic-grace2", distance: 28, actor: 25 },
    { batch: 2, run: "dynamic-grace2", distance: 18, actor: 29 }
  ].map(spec => ({ ...spec, timeline: batches[spec.batch].runs.find(r => r.name === spec.run).routes
    .find(t => t.distance === spec.distance && t.actor === spec.actor && t.repeat === 0) })),
  limitations: result.limitations
};
writeFileSync(resolve(workspace, `${roots.at(-1)}/review-evidence.json`), JSON.stringify(review, null, 2) + "\n");
console.log(JSON.stringify(summary, null, 2));
