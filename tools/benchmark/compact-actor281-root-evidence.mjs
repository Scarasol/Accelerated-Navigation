import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const root = ".tmp/navigation-dynamic-budget-production-20261014";
const [oldRun = "actor281-offset-old-v2", newRun = "actor281-offset-final-v2",
  replayRun = "actor281-offset-replay-v2", outputName = "actor281-review-evidence.json",
  comparisonRun = "actor281-offset-comparison-v2"] = process.argv.slice(2);
const identities = [];
const read = path => {
  const bytes = readFileSync(`${root}/${path}`);
  identities.push({ path: `${root}/${path}`, sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase() });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const original = read("formal-d20-r3/performance-comparison.json").trials
  .find(t => !t.warmup && t.distance === 28 && t.concurrency === 512).actors[281];
const unperturbed = read("actor281-trace-old-v1/handoff-analysis.json");
const smallOffset = read("actor281-offset-old-v1/handoff-analysis.json");
const before = read(`${oldRun}/handoff-analysis.json`).cases[0];
const after = read(`${newRun}/handoff-analysis.json`).cases[0];
const oldTrace = read(`${oldRun}/handoff-diagnostics.json`);
const newTrace = read(`${newRun}/handoff-diagnostics.json`);
const oldData = read(`${oldRun}/performance-comparison.json`);
const newData = read(`${newRun}/performance-comparison.json`);
const pair = read(`${comparisonRun}/comparison.json`);
const replayPath = `${replayRun}/partial-replay.json`;
const replay = existsSync(`${root}/${replayPath}`) ? read(replayPath) : null;
assert.equal(oldTrace.truncated, false); assert.equal(newTrace.truncated, false);
assert.equal(oldData.complete, true); assert.equal(newData.complete, true);
const actor = data => data.trials.find(t => !t.warmup).actors[281];
const oldActor = actor(oldData), newActor = actor(newData);
assert.deepEqual(oldActor.initialPosition, newActor.initialPosition);
const first = trace => trace.searches.find(s => s.input.actor === 281 && s.input.distance === 28);
const input = search => Object.fromEntries(["position", "velocity", "onGround", "targets", "range", "accuracy", "multiplier", "avoided"]
  .map(k => [k, search.input[k]]));
const timeline = trace => trace.events.filter(e => e.actor === 281 && !e.last
  && (e.event === "settle-head" || e.event === "settle-return" || e.event === "recover-head"))
  .map(e => ({ ...e, elapsed: e.tick - e.doneSince }));
const signature = a => ({
  state: a.state, calls: a.nativeSearch.calls, partial: a.nativeSearch.partialPaths,
  reachable: a.nativeSearch.reachablePaths, maxNodes: a.maxPathNodes
});
const result = {
  schema: "ACTOR281_CONTROLLED_BOUNDARY_EVIDENCE_V1",
  originalFailureDirectTraceAvailable: false,
  originalFailure: original,
  originalObservationLimit: "The original five-run failure has terminal/native aggregate data, not its handoff timeline. Controlled reproduction supports the same mechanism but cannot uniquely prove that historical attempt's timing.",
  unperturbedObservedCases: unperturbed.cases.length,
  unperturbedFailures: unperturbed.cases.filter(c => !c.success).length,
  offset0022: smallOffset.cases.map(c => ({ success: c.success, calls: c.calls, premature: c.premature })),
  fixture: "actor281 initial z +0.03 blocks; other actors unchanged; production parameters untouched",
  oldLaunch: read(`${oldRun}/launch-environment.json`),
  newLaunch: read(`${newRun}/launch-environment.json`),
  initialPositionsEqual: true,
  firstInputsEqual: JSON.stringify(input(first(oldTrace))) === JSON.stringify(input(first(newTrace))),
  firstNodesEqual: JSON.stringify(first(oldTrace).result.nodes) === JSON.stringify(first(newTrace).result.nodes),
  originalSignature: signature(original), controlledSignature: signature(oldActor),
  signaturesEqual: JSON.stringify(signature(original)) === JSON.stringify(signature(oldActor)),
  originalVsControlledFinalPositionDifference: oldActor.finalPosition.map((n, i) => n - original.finalPosition[i]),
  before: { success: before.success, state: before.state, calls: before.calls, premature: before.premature },
  after: { success: after.success, state: after.state, calls: after.calls, premature: after.premature },
  oldTimeline: timeline(oldTrace), newTimeline: timeline(newTrace),
  oldSearches: oldTrace.searches, newSearches: newTrace.searches,
  pairedLost: pair.comparisons.reduce((n, c) => n + c.lostSuccessActors.length, 0),
  pairedGained: pair.comparisons.reduce((n, c) => n + c.gainedSuccessActors.length, 0),
  replay, identities
};
const output = `${root}/${outputName}`;
assert.equal(existsSync(output), false, "preserve original evidence");
writeFileSync(output, JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify({
  output, before: result.before, after: result.after,
  firstInputsEqual: result.firstInputsEqual, firstNodesEqual: result.firstNodesEqual,
  signaturesEqual: result.signaturesEqual, replayChecks: replay?.checks?.length ?? 0,
  originalFailureDirectTraceAvailable: false
}, null, 2));
