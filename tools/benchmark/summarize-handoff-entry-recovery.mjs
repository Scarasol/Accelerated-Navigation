import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const root = ".tmp/navigation-handoff-actual-entry-20261014";
const output = `${root}/mechanism-evidence-v1.json`;
assert.equal(existsSync(output), false, "preserve prior evidence");
const identities = [];
const sha = bytes => createHash("sha256").update(bytes).digest("hex").toUpperCase();
const read = path => {
  const bytes = readFileSync(path);
  identities.push({ path, sha256: sha(bytes) });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const checks = (run, file) => {
  const launch = read(`${root}/${run}/launch-environment.json`);
  const exit = read(`${root}/${run}/exit.json`);
  const result = read(`${root}/${run}/${file}`);
  assert.equal(exit.exitCode, 0);
  assert.equal(exit.timedOut, false);
  assert.equal(result.failed, false);
  assert.ok((result.checks ?? []).every(c => c.passed));
  assert.equal(launch.mods.find(m => m.name === "accelerated_navigation.jar").sha256, candidate.candidateSha256);
  return { launch, result };
};
const candidate = read(`${root}/candidate-v1/identity.json`);
assert.equal(sha(readFileSync(`${root}/candidate-v1/actual-entry.jar`)), candidate.candidateSha256);
assert.equal(candidate.unaffectedEntriesByteIdentical, true);
assert.equal(candidate.productionTestEntries, 0);
for (const source of candidate.sources) assert.equal(sha(readFileSync(source.path)), source.sha256);
const wait = checks("entry-wait-v2", "wait-boundaries.json");
assert.equal(wait.result.complete, true);
assert.equal(wait.result.checks.length, 128);
const boundaries = checks("entry-boundaries-v1", "diagnosis.json");
assert.equal(boundaries.result.checks.length, 59);
const speed = checks("entry-speed-v1", "diagnosis.json");
assert.equal(speed.result.trials.length, 24);
const segmented = speed.result.trials.filter(t => t.mode === "segmented");
assert.equal(segmented.length, 8);
assert.ok(segmented.every(t => t.state === "SUCCEEDED" && !t.timedOut && t.recoverAfter === t.recoverBefore));
const replay = checks("entry-replay-v1", "partial-replay.json");
assert.equal(replay.result.checks.length, 14);
const lifecycle = checks("entry-lifecycle-v1", "result.json");
assert.deepEqual(lifecycle.result.routes.map(r => r.route),
  ["stairs-forward", "stairs-backward", "city-forward", "city-backward"]);
const shutdown = read(`${root}/entry-lifecycle-v1/shutdown.json`);
assert.equal(shutdown.passed, true);
assert.equal(shutdown.serviceRejected, true);
assert.equal(shutdown.schedulerRejected, true);
assert.equal(shutdown.navigation.records, 0);
assert.equal(shutdown.navigation.active, 0);
assert.equal(shutdown.navigation.foreignPaths, 0);
assert.equal(shutdown.scheduler.pendingRequests, 0);
const crowdLaunch = read(`${root}/entry-crowd-v1/launch-environment.json`);
const crowdExit = read(`${root}/entry-crowd-v1/exit.json`);
const crowd = read(`${root}/entry-crowd-v1/performance-comparison.json`);
const analysis = read(`${root}/entry-crowd-v1/handoff-analysis.json`);
const trace = read(`${root}/entry-crowd-v1/handoff-diagnostics.json`);
assert.equal(crowdLaunch.mods.find(m => m.name === "accelerated_navigation.jar").sha256, candidate.candidateSha256);
assert.equal(crowdExit.exitCode, 0);
assert.equal(crowd.complete, true);
assert.equal(crowd.failed, false);
assert.equal(trace.truncated, false);
assert.equal(analysis.cases.length, 16);
assert.ok(analysis.cases.every(c => c.success && c.calls === 2 && c.premature.length === 0));
const formal = crowd.trials.filter(t => !t.warmup);
const actor = formal.find(t => t.distance === 28 && t.concurrency === 512).actors[281];
assert.equal(actor.success, true);
const oldRoot = ".tmp/navigation-dynamic-budget-production-20261014/actor281-offset-final-v2";
const oldTrace = read(`${oldRoot}/handoff-diagnostics.json`);
const oldData = read(`${oldRoot}/performance-comparison.json`);
const oldActor = oldData.trials.find(t => !t.warmup).actors[281];
assert.equal(oldActor.success, false);
assert.equal(oldActor.state, "EXHAUSTED");
const first = data => data.searches.find(s => s.input.actor === 281 && s.input.distance === 28 && s.input.repeat === 0);
const inputFields = ["position", "velocity", "onGround", "targets", "range", "accuracy", "multiplier", "avoided"];
for (const field of inputFields) assert.deepEqual(first(oldTrace).input[field], first(trace).input[field], field);
assert.deepEqual(first(oldTrace).result.nodes, first(trace).result.nodes);
const timeline = trace.events.filter(e => e.query === actor.queryId && !e.last
  && ["follow-last-node-held", "follow-last-node-advance"].includes(e.event));
assert.ok(timeline.some(e => e.event === "follow-last-node-held" && !e.contains && !e.pathDone));
assert.ok(timeline.some(e => e.event === "follow-last-node-advance" && e.contains && e.entryPresent));
assert.ok(timeline.every(e => e.failedTargets.length === 0));
const initialFixture = read(`${root}/entry-wait-v1/wait-boundaries.json`);
assert.equal(initialFixture.failed, true);
const finalChecks = {
  waitScenarios: [...new Set(wait.result.checks.map(c => c.scenario))],
  waitAssertions: wait.result.checks.length, boundaryAssertions: boundaries.result.checks.length,
  speedTrials: speed.result.trials.length, segmentedSpeedSuccesses: segmented.length,
  segmentedSpeedRecoveries: speed.result.navigation.recoveries,
  replayAssertions: replay.result.checks.length, lifecycleAssertions: lifecycle.result.checks.length,
  routes: lifecycle.result.routes,
  shutdown: { records: 0, active: 0, foreignPaths: 0, pendingRequests: 0, lateRequestsRejected: true }
};
const result = {
  schema: "ACTUAL_ENTRY_HANDOFF_MECHANISM_EVIDENCE_V1",
  candidate, checks: finalChecks,
  formalCrowd: formal.map(t => ({ distance: t.distance, concurrency: t.concurrency,
    requested: t.actors.length, successful: t.actors.filter(a => a.success).length,
    failedStates: t.actors.filter(a => !a.success).map(a => a.state) })),
  focusedCases: analysis.cases.map(({ events, searches, ...rest }) => rest),
  actor281: { fixture: "initial z +0.03 blocks; dedicated-server functional run",
    firstInputsEqual: true, firstNodesEqual: true,
    before: { state: oldActor.state, calls: oldActor.nativeSearch.calls },
    after: { state: actor.state, calls: actor.nativeSearch.calls },
    timeline },
  retainedFixtureFailure: { run: "entry-wait-v1", failure: initialFixture.failure,
    correction: "The first freeze fixture set getSpeed to zero. Probe v2 retains nominal speed and only blocks propulsion; production jar is unchanged.",
    nativeStopBranch: "Automatic native stuck/timeout stop and recovery observed; exact stop call site was not separately instrumented." },
  limitations: [
    "The controlled offset fixture does not uniquely reproduce the historical actor281 failure.",
    "Diagnostic probe versions differ across modes; all runs use the same frozen production candidate.",
    "Functional servers overlapped on the host. This is not a formal performance or timing comparison.",
    "The prior three-tick five-repeat performance results are not acceptance evidence for this new candidate.",
    "Real IntegratedServer pause and a dedicated 600-tick zero-progress fixture remain untested.",
    "No release, commit, push or deployment approval is asserted."
  ],
  identities
};
writeFileSync(output, JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify({ output, candidateSha256: candidate.candidateSha256,
  checks: finalChecks, focusedCases: analysis.cases.length,
  actor281Before: result.actor281.before, actor281After: result.actor281.after }, null, 2));
