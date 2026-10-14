import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const [accPath, vanillaPath, outputPath] = process.argv.slice(2);
if (!outputPath) throw new Error("Usage: node analyze-performance.mjs accelerated.json vanilla.json output-dir");
const files = [accPath, vanillaPath].map(path => {
  const text = readFileSync(resolve(path), "utf8");
  const data = JSON.parse(text);
  assert.equal(data.schema, "high-concurrency-v2");
  assert.equal(data.failed, false);
  assert.equal(data.complete, true);
  return { path: resolve(path), sha256: createHash("sha256").update(text).digest("hex"), data };
});
const median = xs => {
  if (!xs.length) return null;
  const a = [...xs].sort((x, y) => x - y);
  const i = Math.floor(a.length / 2);
  return a.length % 2 ? a[i] : (a[i - 1] + a[i]) / 2;
};
const p95 = xs => !xs.length ? null : [...xs].sort((x, y) => x - y)[Math.ceil(xs.length * .95) - 1];
const sum = xs => xs.reduce((a, b) => a + b, 0);
const ms = n => n == null ? null : n / 1e6;
const pct = (a, v) => a == null || v == null || v === 0 ? null : (a / v - 1) * 100;
const trials = files.map(f => f.data.trials.filter(t => !t.warmup));
const key = t => `${t.distance}/${t.concurrency}/${t.repeat}`;
const nativeSum = (t, name) => sum(t.actors.map(a => a.nativeSearch[name]));
assert.equal(trials[0].length, trials[1].length);
const baseline = new Map(trials[1].map(t => [key(t), t]));
const pairs = trials[0].map(a => {
  const v = baseline.get(key(a));
  assert.ok(v, `No baseline for ${key(a)}`);
  assert.equal(a.actors.length, a.concurrency);
  assert.equal(v.actors.length, v.concurrency);
  for (const trial of [a, v]) {
    assert.equal(new Set(trial.actors.map(actor => JSON.stringify([actor.start, actor.goal]))).size, trial.concurrency);
    assert.equal(new Set(trial.actors.map(actor => actor.routeKind)).size, 8);
    assert.ok(trial.mainThreadAllocatedBytes >= 0);
  }
  const matched = [];
  const physical = [];
  for (let i = 0; i < a.actors.length; i++) {
    const aa = a.actors[i], vv = v.actors[i];
    for (const property of ["start", "goal", "routeKind", "tile", "slot", "rotation"])
      assert.deepEqual(aa[property], vv[property], `Input mismatch ${key(a)} actor ${i} ${property}`);
    const band = aa.straightDistance;
    assert.ok(a.distance === 8 ? band < 12 : a.distance === 18 ? band >= 12 && band < 24 : band >= 24 && band <= 32);
    if (aa.success && vv.success) matched.push([aa, vv]);
    if (aa.submitToPhysicalArrivalNanos >= 0 && vv.submitToPhysicalArrivalNanos >= 0) physical.push([aa, vv]);
  }
  return { a, v, matched, physical };
});
const actorMedian = (t, field) => median(t.actors.map(a => a[field]).filter(n => n >= 0));
const actorP95 = (t, field) => p95(t.actors.map(a => a[field]).filter(n => n >= 0));
const summary = (ts, matched, side) => ({
  requested: sum(ts.map(t => t.totalActors)),
  success: sum(ts.map(t => t.successfulActors)),
  successRate: 100 * sum(ts.map(t => t.successfulActors)) / sum(ts.map(t => t.totalActors)),
  physicalRate: 100 * sum(ts.map(t => t.physicalArrivals)) / sum(ts.map(t => t.totalActors)),
  readyRate: 100 * sum(ts.map(t => t.pathReadyActors)) / sum(ts.map(t => t.totalActors)),
  rejected: sum(ts.map(t => t.rejectedActors)),
  batchSubmitMs: ms(median(ts.map(t => t.batchSubmitNanos))),
  readyP50Ms: ms(median(ts.map(t => actorMedian(t, "submitToReadyNanos")))),
  readyP95Ms: ms(median(ts.map(t => actorP95(t, "submitToReadyNanos")))),
  batchReadyP95Ms: ms(median(ts.map(t => actorP95(t, "batchToReadyNanos")))),
  allReadyMs: ts.every(t => t.batchAllReadyNanos >= 0) ? ms(median(ts.map(t => t.batchAllReadyNanos))) : null,
  physicalP50Ms: ms(median(matched.map(xs => median(xs.map(pair => pair[side].submitToPhysicalArrivalNanos))))),
  movingP50Ms: ms(median(matched.map(xs => median(xs.map(pair => pair[side].firstMoveToPhysicalArrivalNanos))))),
  finishP50Ms: ms(median(matched.map(xs => median(xs.map(pair => pair[side].submitToFinishNanos))))),
  allSuccessMs: ts.every(t => t.batchAllSuccessNanos >= 0) ? ms(median(ts.map(t => t.batchAllSuccessNanos))) : null,
  batchWindowMs: ms(median(ts.map(t => t.batchFinishNanos))),
  successThroughputPerSec: median(ts.map(t => t.successfulActors * 1e9 / t.batchFinishNanos)),
  tps: median(ts.map(t => t.observedTps)),
  workP50Ms: ms(median(ts.map(t => t.workP50Nanos))),
  workP95Ms: ms(median(ts.map(t => t.workP95Nanos))),
  workP99Ms: ms(median(ts.map(t => t.workP99Nanos))),
  workMaxMs: ms(median(ts.map(t => t.workMaxNanos))),
  submissionTickMs: ms(median(ts.map(t => t.submissionTickWorkNanos))),
  over50msTicks: sum(ts.map(t => t.over50msTicks)),
  ticks: sum(ts.map(t => t.measuredTicks)),
  heapPeakMB: median(ts.map(t => t.peakHeapBytes)) / 1e6,
  heapDeltaMB: median(ts.map(t => t.peakHeapDeltaBytes)) / 1e6,
  mainAllocatedMB: median(ts.map(t => t.mainThreadAllocatedBytes)) / 1e6,
  submitAllocatedMB: median(ts.map(t => t.submissionAllocatedBytes)) / 1e6,
  nativeAllocatedMB: median(ts.map(t => nativeSum(t, "allocatedBytes"))) / 1e6,
  nativeCalls: median(ts.map(t => nativeSum(t, "calls"))),
  nativeTotalMs: ms(median(ts.map(t => nativeSum(t, "wallNanos")))),
  nativePerRequestMs: ms(median(ts.map(t => nativeSum(t, "wallNanos") / t.totalActors))),
  nativeCpuMs: ms(median(ts.map(t => nativeSum(t, "cpuNanos")))),
  nodes: median(ts.map(t => t.peakPublishedNodes)),
  nativeMaxNodes: median(ts.map(t => Math.max(...t.actors.map(a => a.nativeSearch.maxReturnedNodes)))),
  baseCacheMB: median(ts.map(t => t.topologyEnd.worker.baseCache.retainedBytes)) / 1e6,
  corridorCacheKB: median(ts.map(t => t.topologyEnd.worker.corridorCache.retainedBytes)) / 1e3,
  recoveries: sum(ts.map(t => t.recoveriesDelta)),
  wholeCalls: sum(ts.map(t => t.wholeCallsDelta)),
  gcCount: sum(ts.map(t => t.gcCount)),
  gcMillis: sum(ts.map(t => t.gcTimeMillis)),
  states: ts.flatMap(t => t.actors).reduce((counts, a) => {
    counts[a.state] = (counts[a.state] ?? 0) + 1;
    return counts;
  }, {})
});
const rows = [];
for (const distance of [8, 18, 28]) for (const concurrency of [128, 256, 512]) {
  const group = pairs.filter(p => p.a.distance === distance && p.a.concurrency === concurrency);
  if (!group.length) continue;
  const matched = group.map(p => p.matched);
  const a = summary(group.map(p => p.a), matched, 0);
  const v = summary(group.map(p => p.v), matched, 1);
  const differences = Object.fromEntries(Object.keys(a).filter(k => typeof a[k] === "number" || a[k] === null)
    .map(k => [k, pct(a[k], v[k])]));
  rows.push({ distance, concurrency, repeats: group.length, matchedSuccess: sum(matched.map(x => x.length)),
    accelerated: a, vanilla: v, differencePercent: differences, successDifferencePoints: a.successRate - v.successRate });
}
const kinds = [];
for (const kind of files[0].data.routeKinds) {
  const aa = trials[0].flatMap(t => t.actors.filter(a => a.routeKind === kind));
  const vv = trials[1].flatMap(t => t.actors.filter(a => a.routeKind === kind));
  const a = aa.filter(a => a.success).length, v = vv.filter(a => a.success).length;
  kinds.push({ kind, requested: aa.length, acceleratedSuccess: a, vanillaSuccess: v,
    acceleratedPercent: 100 * a / aa.length, vanillaPercent: 100 * v / vv.length,
    differencePoints: 100 * (a - v) / aa.length });
}
mkdirSync(resolve(outputPath), { recursive: true });
const metrics = { sources: files.map(({ path, sha256 }) => ({ path, sha256 })),
  requestedPerVariant: sum(trials[0].map(t => t.totalActors)), validatedInputPairs: sum(trials[0].map(t => t.totalActors)),
  uniqueRoutesEveryTrial: true, eightTerrainKindsEveryTrial: true,
  rows, kinds };
writeFileSync(resolve(outputPath, "metrics.json"), JSON.stringify(metrics, null, 2) + "\n");
const fields = Object.keys(rows[0].accelerated).filter(k => typeof rows[0].accelerated[k] === "number" || rows[0].accelerated[k] === null);
const csvHeader = ["distance", "concurrency", "matchedSuccess", ...fields.flatMap(k => [k + "_accelerated", k + "_vanilla", k + "_difference_percent"])];
const csv = [csvHeader.join(","), ...rows.map(r => [r.distance, r.concurrency, r.matchedSuccess,
  ...fields.flatMap(k => [r.accelerated[k] ?? "", r.vanilla[k] ?? "", r.differencePercent[k] ?? ""])].join(","))].join("\n");
writeFileSync(resolve(outputPath, "comparison.csv"), csv + "\n");
const number = n => n == null ? "N/A" : n.toFixed(2);
const cell = (r, k) => `${number(r.accelerated[k])} / ${number(r.vanilla[k])} (${r.differencePercent[k] == null ? "N/A" : (r.differencePercent[k] >= 0 ? "+" : "") + number(r.differencePercent[k]) + "%"})`;
const tables = [
  ["Admission and publication", ["batchSubmitMs", "readyP50Ms", "readyP95Ms", "batchReadyP95Ms", "nativeTotalMs"]],
  ["Physical movement and outcome", ["physicalP50Ms", "movingP50Ms", "finishP50Ms", "successRate", "allSuccessMs"]],
  ["Ticks and concurrency", ["tps", "workP95Ms", "workP99Ms", "workMaxMs", "submissionTickMs"]],
  ["Space", ["mainAllocatedMB", "nativeAllocatedMB", "heapPeakMB", "heapDeltaMB", "nodes"]]
].map(([name, ks]) => `## ${name}\n\n| d | N | ${ks.join(" | ")} |\n|---|---|${ks.map(() => "---").join("|")}|\n`
  + rows.map(r => `| ${r.distance} | ${r.concurrency} | ${ks.map(k => cell(r, k)).join(" | ")} |`).join("\n")).join("\n\n");
writeFileSync(resolve(outputPath, "tables.md"), tables + "\n");
console.log(JSON.stringify({ validatedPairs: metrics.validatedInputPairs, rows: rows.length,
  successA: sum(rows.map(r => r.accelerated.success)), successV: sum(rows.map(r => r.vanilla.success)), kinds }, null, 2));
