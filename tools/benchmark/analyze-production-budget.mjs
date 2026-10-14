import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const [rootArg, outputArg, ...names] = process.argv.slice(2);
if (!names.length) throw new Error("Usage: node analyze-production-budget.mjs root output-name run ...");
const root = resolve(rootArg);
const identities = [];
const read = file => {
  const path = resolve(root, file), bytes = readFileSync(path), text = bytes.toString("utf8").replace(/^\uFEFF/, "");
  identities.push({ path, sha256: createHash("sha256").update(bytes).digest("hex") });
  return JSON.parse(text);
};
const sum = xs => xs.reduce((a, b) => a + b, 0);
const quantile = (xs, p) => {
  const sorted = [...xs].sort((a, b) => a - b);
  return sorted.length ? sorted[Math.ceil(sorted.length * p) - 1] : null;
};
const median = xs => {
  const sorted = xs.filter(Number.isFinite).sort((a, b) => a - b);
  if (!sorted.length) return null;
  const i = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[i] : (sorted[i - 1] + sorted[i]) / 2;
};
const delta = (a, b) => a == null || b == null || b === 0 ? null : (a / b - 1) * 100;
const ms = n => n == null ? null : n / 1e6;
function events(samples, recoverySamples = samples) {
  const rows = [];
  for (let i = 0; i < samples.length; i++) {
    if (samples[i].fullNanos <= 50e6) continue;
    const start = i;
    while (i + 1 < samples.length && samples[i + 1].tick === samples[i].tick + 1
        && samples[i + 1].fullNanos > 50e6) i++;
    const event = samples.slice(start, i + 1), end = event.at(-1);
    let recovery = null, streak = 0;
    for (const sample of recoverySamples.filter(s => s.tick > end.tick)) {
      streak = sample.fullNanos <= 50e6 ? streak + 1 : 0;
      if (streak === 5) { recovery = (sample.endNanos - end.endNanos) / 1e6; break; }
    }
    rows.push({ startTick: event[0].tick, endTick: end.tick, consecutive: event.length,
      maxMs: Math.max(...event.map(s => s.fullNanos)) / 1e6, recoveryMs: recovery,
      fullMs: event.map(s => s.fullNanos / 1e6),
      navigationMs: event.map(s => s.navigationNanos / 1e6),
      grantsMs: event.map(s => s.grantNanos / 1e6) });
  }
  return rows;
}
const runs = names.map(name => {
  const launch = read(`${name}/launch-environment.json`);
  const exit = read(`${name}/exit.json`);
  assert.equal(exit.exitCode, 0); assert.equal(exit.timedOut, false);
  const data = read(`${name}/performance-comparison.json`);
  const telemetry = read(`${name}/full-ticks.json`);
  const dispatch = read(`${name}/dispatch-timings.json`);
  assert.equal(data.complete, true); assert.equal(data.failed, false);
  assert.equal(telemetry.truncated, false); assert.equal(dispatch.truncated, false);
  const mode = launch.productionJar.match(/((?:dynamic\d+|fixed4)(?:-grace\d+)?)\.jar$/)?.[1] ?? "baseline-fead3a1";
  return { name, mode, launch, data, ticks: telemetry.samples, dispatch: dispatch.samples,
    allEvents: events(telemetry.samples), formal: data.trials.filter(t => !t.warmup) };
});
const rows = runs.flatMap(run => run.formal.map(trial => {
  assert.equal(new Set(trial.actors.map(a => JSON.stringify([a.start, a.goal]))).size, trial.concurrency);
  assert.equal(new Set(trial.actors.map(a => a.routeKind)).size, 8);
  const ticks = run.ticks.filter(s => s.tick >= trial.submitTick && s.tick <= trial.endTick);
  assert.equal(ticks.length, trial.endTick - trial.submitTick + 1, "full-tick window differs");
  const ev = events(ticks, run.ticks);
  const dispatch = run.dispatch.filter(s => s.tick >= trial.submitTick && s.tick <= trial.endTick);
  const duration = (ticks.at(-1).endNanos - ticks[0].startNanos) / 1e9;
  return { run: run.name, mode: run.mode, distance: trial.distance, concurrency: trial.concurrency,
    repeat: trial.repeat, requested: trial.totalActors, success: trial.successfulActors,
    ready: trial.pathReadyActors, physical: trial.physicalArrivals, timedOut: trial.timedOut,
    states: trial.actors.reduce((r, a) => (r[a.state] = (r[a.state] ?? 0) + 1, r), {}),
    readyP50Ms: ms(median(trial.actors.map(a => a.submitToReadyNanos).filter(n => n >= 0))),
    readyP95Ms: ms(quantile(trial.actors.map(a => a.submitToReadyNanos).filter(n => n >= 0), .95)),
    apiSubmitP50Us: median(trial.actors.map(a => a.submitCallNanos)) / 1000,
    apiSubmitP95Us: quantile(trial.actors.map(a => a.submitCallNanos), .95) / 1000,
    submissionActiveMs: sum(trial.actors.map(a => a.submitCallNanos)) / 1e6,
    batchSubmitMs: ms(trial.batchSubmitNanos), batchMs: ms(trial.batchFinishNanos),
    tps: (ticks.length - 1) / Math.max(1e-9, (ticks.at(-1).startNanos - ticks[0].startNanos) / 1e9),
    fullP50Ms: ms(quantile(ticks.map(t => t.fullNanos), .5)),
    fullP95Ms: ms(quantile(ticks.map(t => t.fullNanos), .95)),
    fullP99Ms: ms(quantile(ticks.map(t => t.fullNanos), .99)),
    fullMaxMs: Math.max(...ticks.map(t => t.fullNanos)) / 1e6,
    over50: ticks.filter(t => t.fullNanos > 50e6).length,
    over75: ticks.filter(t => t.fullNanos > 75e6).length,
    over100: ticks.filter(t => t.fullNanos > 100e6).length,
    measuredTicks: ticks.length, wallSeconds: duration, eventsPer1000Ticks: ev.length * 1000 / ticks.length,
    eventsPerMinute: ev.length * 60 / duration, events: ev,
    navigationMs: sum(ticks.map(t => t.navigationNanos)) / 1e6,
    outsideNavigationMs: sum(ticks.map(t => t.outsideNavigationNanos)) / 1e6,
    grantP50Ms: ms(median(ticks.map(t => t.grantNanos))),
    maxPending: Math.max(...ticks.map(t => t.pending)),
    maxRunnable: Math.max(...ticks.map(t => t.runnable)),
    maxParked: Math.max(...ticks.map(t => t.parked)),
    wakeDispatchP50Ms: ms(median(dispatch.map(d => d.wakeToDispatchNanos))),
    wakeDispatchP95Ms: ms(quantile(dispatch.map(d => d.wakeToDispatchNanos).filter(Number.isFinite), .95)),
    nativeCalls: trial.nativeCallsDelta, recoveries: trial.recoveriesDelta, wholeCalls: trial.wholeCallsDelta,
    nativeWallMs: sum(trial.actors.map(a => a.nativeSearch.wallNanos)) / 1e6,
    nativeCpuMs: sum(trial.actors.map(a => a.nativeSearch.cpuNanos)) / 1e6,
    baseCacheMB: trial.topologyEnd.worker.baseCache.retainedBytes / 1e6,
    parentCacheMB: trial.topologyEnd.worker.parentCache.retainedBytes / 1e6,
    corridorCacheKB: trial.topologyEnd.worker.corridorCache.retainedBytes / 1000,
    publishedNodesPeak: trial.peakPublishedNodes,
    heapPeakMB: trial.peakHeapBytes / 1e6, mainAllocatedMB: trial.mainThreadAllocatedBytes / 1e6,
    nativeAllocatedMB: sum(trial.actors.map(a => a.nativeSearch.allocatedBytes)) / 1e6 };
}));
const modes = [...new Set(runs.map(r => r.mode))];
assert.equal(new Set(runs.map(r => r.launch.mods.find(m => m.name === "production-probe.jar").sha256)).size,
  1, "different common instruments must be analyzed separately");
for (const mode of modes)
  assert.equal(new Set(runs.filter(r => r.mode === mode).map(r =>
    r.launch.mods.find(m => m.name === "accelerated_navigation.jar").sha256)).size, 1,
  "production version changed within a group");
const summaries = [];
for (const mode of modes) {
  for (const distance of [...new Set(rows.map(r => r.distance))]) {
    for (const concurrency of [...new Set(rows.map(r => r.concurrency))]) {
      const group = rows.filter(r => r.mode === mode && r.distance === distance && r.concurrency === concurrency);
      if (!group.length) continue;
      const summary = { mode, distance, concurrency, trials: group.length };
      for (const key of ["readyP50Ms", "readyP95Ms", "apiSubmitP50Us", "apiSubmitP95Us", "submissionActiveMs",
        "batchSubmitMs", "batchMs", "tps", "fullP50Ms", "fullP95Ms",
        "fullP99Ms", "navigationMs", "nativeCalls", "recoveries", "nativeWallMs", "nativeCpuMs",
        "heapPeakMB", "mainAllocatedMB", "nativeAllocatedMB", "baseCacheMB", "parentCacheMB", "corridorCacheKB",
        "publishedNodesPeak", "eventsPer1000Ticks", "eventsPerMinute",
        "wakeDispatchP50Ms", "wakeDispatchP95Ms"]) summary[key] = median(group.map(g => g[key]));
      for (const key of ["requested", "success", "ready", "physical", "over50", "over75", "over100", "measuredTicks"])
        summary[key] = sum(group.map(g => g[key]));
      summary.maxConsecutive = Math.max(0, ...group.flatMap(g => g.events.map(e => e.consecutive)));
      summary.eventCount = sum(group.map(g => g.events.length));
      summary.eventsPer1000Ticks = summary.eventCount * 1000 / summary.measuredTicks;
      summary.eventsPerMinute = summary.eventCount * 60 / sum(group.map(g => g.wallSeconds));
      summary.fullMaxMs = Math.max(...group.map(g => g.fullMaxMs));
      summary.recoveryP95Ms = quantile(group.flatMap(g => g.events.map(e => e.recoveryMs)).filter(Number.isFinite), .95);
      summaries.push(summary);
    }
  }
}
const comparisons = [];
for (const mode of modes.slice(1)) {
  const refs = runs.filter(r => r.mode === modes[0]), candidates = runs.filter(r => r.mode === mode);
  for (let runIndex = 0; runIndex < Math.min(refs.length, candidates.length); runIndex++) {
    const reference = refs[runIndex], candidate = candidates[runIndex];
    for (const ct of candidate.formal) {
      const rt = reference.formal.find(t => t.distance === ct.distance && t.concurrency === ct.concurrency && t.repeat === ct.repeat);
      assert.ok(rt, "missing paired trial");
      const common = [], lost = [], gained = [];
      for (let i = 0; i < ct.actors.length; i++) {
        const ca = ct.actors[i], ra = rt.actors[i];
        for (const key of ["start", "initialPosition", "goal", "routeKind", "tile", "slot", "rotation"])
          assert.deepEqual(ca[key], ra[key], `paired input mismatch ${key}`);
        if (ca.success && ra.success) common.push([ca, ra]);
        if (!ca.success && ra.success) lost.push(i);
        if (ca.success && !ra.success) gained.push(i);
      }
      const cr = rows.find(r => r.run === candidate.name && r.distance === ct.distance && r.concurrency === ct.concurrency && r.repeat === ct.repeat);
      const rr = rows.find(r => r.run === reference.name && r.distance === rt.distance && r.concurrency === rt.concurrency && r.repeat === rt.repeat);
      const result = { candidate: candidate.name, reference: reference.name, distance: ct.distance,
        concurrency: ct.concurrency, repeat: ct.repeat, commonSuccess: common.length,
        lostSuccessActors: lost, gainedSuccessActors: gained,
        pairedArrivalCandidateMs: ms(median(common.map(p => p[0].submitToPhysicalArrivalNanos))),
        pairedArrivalReferenceMs: ms(median(common.map(p => p[1].submitToPhysicalArrivalNanos))) };
      result.pairedArrivalChangePercent = delta(result.pairedArrivalCandidateMs, result.pairedArrivalReferenceMs);
      result.differencePercent = Object.fromEntries(["readyP50Ms", "readyP95Ms", "apiSubmitP50Us", "apiSubmitP95Us",
        "submissionActiveMs", "batchSubmitMs", "batchMs", "tps",
        "fullP95Ms", "fullP99Ms", "navigationMs", "nativeCalls", "recoveries", "nativeWallMs",
        "nativeCpuMs", "heapPeakMB", "mainAllocatedMB", "nativeAllocatedMB", "baseCacheMB", "parentCacheMB",
        "corridorCacheKB", "publishedNodesPeak"].map(k => [k, delta(cr[k], rr[k])]));
      comparisons.push(result);
    }
  }
}
const output = resolve(root, outputArg);
assert.ok(!existsSync(resolve(output, "comparison.json")), "preserve prior analysis; use a fresh output name");
mkdirSync(output, { recursive: true });
const report = { schema: "PRODUCTION_BUDGET_COMPARISON_V1", identities,
  instrumentation: "full virtual tick call; includes synchronous publication, callbacks, GC, and test cleanup; excludes idle sleep and queue waiting",
  validSamples: "normal return plus one START and END; no pause/exception; formal submitTick..endTick inclusive",
  recovery: "each maximal >50ms event independently searches the next five consecutive <=50ms ticks; later spikes reset the recovery streak; no event deleted",
  runs: runs.map(r => ({ name: r.name, mode: r.mode, launch: r.launch, allEvents: r.allEvents })),
  rows, summaries, comparisons };
writeFileSync(resolve(output, "comparison.json"), JSON.stringify(report, null, 2) + "\n");
const columns = ["mode", "distance", "concurrency", "trials", "success", "requested", "readyP50Ms", "readyP95Ms",
  "fullP95Ms", "fullP99Ms", "fullMaxMs", "over50", "over100", "maxConsecutive", "eventsPer1000Ticks",
  "recoveryP95Ms", "tps", "navigationMs", "nativeCalls", "recoveries"];
writeFileSync(resolve(output, "tables.md"), `| ${columns.join(" | ")} |\n| ${columns.map(() => "---").join(" | ")} |\n`
  + summaries.map(r => `| ${columns.map(k => typeof r[k] === "number" ? +r[k].toFixed(3) : r[k] ?? "N/A").join(" | ")} |`).join("\n") + "\n");
console.log(JSON.stringify({ output, cells: summaries.length, pairedTrials: comparisons.length,
  lost: sum(comparisons.map(c => c.lostSuccessActors.length)),
  gained: sum(comparisons.map(c => c.gainedSuccessActors.length)),
  requestsPerMode: modes.map(mode => ({mode, requested: sum(summaries.filter(s => s.mode === mode).map(s => s.requested))})),
  spikes: summaries.map(s => ({mode:s.mode, distance:s.distance, concurrency:s.concurrency,
    over50:s.over50, over100:s.over100, eventsPer1000Ticks:s.eventsPer1000Ticks, fullMaxMs:s.fullMaxMs})) }, null, 2));
