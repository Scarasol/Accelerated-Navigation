import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const root = resolve(workspace, ".tmp/navigation-dynamic-budget-20261013-guards");
const inputs = [];
const read = path => {
  const bytes = readFileSync(path);
  inputs.push({ path, sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase() });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const load = mode => {
  const dir = resolve(root, `trace-${mode}`);
  const performance = read(resolve(dir, "performance-comparison.json"));
  const traces = read(resolve(dir, "budget-path-traces.json"));
  const ownership = read(resolve(dir, "budget-thread-checks.json"));
  const exit = read(resolve(dir, "exit.json"));
  assert(ownership.passed && !performance.failed && performance.complete);
  assert(exit.exitCode === 0 && !exit.timedOut);
  return { performance, traces };
};
const reference = load("fixed4");
const candidate = load("dynamic12");
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function searches(run, trial, actor) {
  const result = run.traces.searches.filter(row => row.input.tick >= trial.submitTick
    && row.input.tick <= trial.endTick && same(row.input.initial, actor.start)
    && same(row.input.finalTarget, actor.goal));
  assert.equal(new Set(result.map(row => row.input.query)).size, 1);
  assert.equal(result.length, actor.nativeSearch.calls);
  return result.map((row, index) => ({ ordinal: index + 1, tick: row.input.tick,
    tickOffsetMs: row.input.tickOffsetNanos / 1e6, position: row.input.position,
    velocity: row.input.velocity, horizontalSpeed: Math.hypot(row.input.velocity[0], row.input.velocity[2]),
    onGround: row.input.onGround, targets: row.input.targets, range: row.input.range,
    accuracy: row.input.accuracy, multiplier: row.input.multiplier,
    nullPath: row.nullPath, canReach: row.canReach, nativeTarget: row.target,
    nodes: row.nodes, endNode: row.nodes.at(-1) ?? null }));
}
const pairs = [];
for (const trial of candidate.performance.trials.filter(row => !row.warmup)) {
  const baseline = reference.performance.trials.find(row => !row.warmup
    && row.distance === trial.distance && row.concurrency === trial.concurrency && row.repeat === trial.repeat);
  assert(baseline);
  for (let index = 0; index < trial.actors.length; index++) {
    const a = trial.actors[index], b = baseline.actors[index];
    assert(same(a.start, b.start) && same(a.goal, b.goal));
    if (a.success || !b.success) continue;
    pairs.push({ distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat,
      actor: index, routeKind: a.routeKind, start: a.start, goal: a.goal,
      candidateState: a.state, baselineState: b.state, candidateFinal: a.finalPosition,
      baselineFinal: b.finalPosition, candidateSearches: searches(candidate, trial, a),
      baselineSearches: searches(reference, baseline, b) });
  }
}
const result = { schema: "BUDGET_FAILURE_NATIVE_SEARCH_TRACE_V1", source: fileURLToPath(import.meta.url),
  inputs, scope: "second prototype; additional diagnostic runs; not included in first prototype performance comparison",
  pairs };
writeFileSync(resolve(root, "trace-analysis.json"), JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify({ pairs: pairs.length, routes: pairs.map(row => ({
  distance: row.distance, repeat: row.repeat, actor: row.actor,
  candidate: row.candidateSearches.map(search => ({ ordinal: search.ordinal, tick: search.tick,
    position: search.position, horizontalSpeed: search.horizontalSpeed, reachable: search.canReach,
    end: search.endNode })),
  reference: row.baselineSearches.map(search => ({ ordinal: search.ordinal, tick: search.tick,
    position: search.position, horizontalSpeed: search.horizontalSpeed, reachable: search.canReach,
    end: search.endNode }))
})) }, null, 2));
