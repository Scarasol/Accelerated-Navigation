import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const root = resolve(workspace, process.argv[2] ?? ".tmp/navigation-dynamic-budget-20261013");
const sha = bytes => createHash("sha256").update(bytes).digest("hex").toUpperCase();
const read = path => {
  const bytes = readFileSync(path);
  return { path, sha256: sha(bytes), value: JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, "")) };
};
const percentile = (values, p) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)];
};
const median = values => {
  const v = [...values].sort((a, b) => a - b);
  return v.length % 2 ? v[Math.floor(v.length / 2)] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
};
const rate = (a, b) => b ? (a / b - 1) * 100 : null;
const ms = value => value === null || value < 0 ? null : value / 1e6;
const histogram = values => {
  const result = {};
  for (const value of values) result[value] = (result[value] ?? 0) + 1;
  return result;
};
const identity = actor => JSON.stringify([actor.start, actor.goal, actor.routeKind,
  actor.tile, actor.slot, actor.rotation, actor.straightDistance]);

const names = ["formal-fixed4", "formal-dynamic12", "formal-fixed8", "formal-fixed12",
  ...[10, 30, 40, 48].flatMap(load => [`load${load}-fixed4`, `load${load}-dynamic12`])];
const runs = [], missing = [], inputs = [], failureEvidence = [], over50Evidence = [];
for (const name of names) {
  if (!existsSync(resolve(root, name, "budget-ticks.json"))) { missing.push(name); continue; }
  const performance = read(resolve(root, name, "performance-comparison.json"));
  const budget = read(resolve(root, name, "budget-ticks.json"));
  const launch = read(resolve(root, name, "launch-environment.json"));
  const exit = read(resolve(root, name, "exit.json"));
  inputs.push(...[performance, budget, launch, exit].map(({ value, ...input }) => input));
  assert(!performance.value.failed && performance.value.complete);
  assert(!exit.value.timedOut && exit.value.exitCode === 0);
  const samples = budget.value.samples;
  const byTick = new Map(samples.map(sample => [sample.sequence, sample]));
  assert.equal(byTick.size, samples.length);
  let prior = null;
  for (const sample of samples) {
    assert(sample.grantNanos >= 0 && sample.grantNanos <= 12e6);
    assert(sample.balanceAfterRefillNanos <= sample.grantNanos);
    assert.equal(sample.balanceAfterRefillNanos - sample.finalBalanceNanos, sample.navigationNanos);
    assert(sample.fullNanos >= sample.navigationNanos);
    assert(sample.fullNanos >= sample.nativeReportedNanos);
    if (prior) {
      assert.equal(sample.balanceAfterRefillNanos,
        Math.min(sample.grantNanos, prior.finalBalanceNanos + sample.grantNanos - sample.outsideNavigationNanos));
      if (budget.value.mode === "dynamic12") {
        assert(sample.grantNanos <= prior.grantNanos + 1e6);
        if (prior.fullNanos > 45e6) assert(sample.grantNanos <= prior.grantNanos / 2);
      }
    }
    prior = sample;
  }
  const trials = performance.value.trials.filter(trial => !trial.warmup).map(trial => {
    const ticks = samples.filter(sample => sample.sequence >= trial.submitTick && sample.sequence <= trial.endTick);
    const activeTicks = ticks.filter(sample => sample.sequence < trial.endTick);
    const observed = performance.value.tickSamples.filter(sample =>
      sample.tick >= trial.submitTick && sample.tick <= trial.endTick);
    assert(ticks.length > 0);
    for (const sample of observed) {
      const full = byTick.get(sample.tick);
      assert(full && full.fullNanos >= sample.workNanos);
    }
    for (const sample of ticks.filter(row => row.fullNanos > 50e6)) {
      over50Evidence.push({ run: name, mode: budget.value.mode, addedLoadMs: launch.value.addedLoadMs,
        distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat,
        relativeTick: sample.sequence - trial.submitTick, atTrialCollectionEnd: sample.sequence === trial.endTick,
        sample });
    }
    return { ...trial, fullTickSamples: ticks,
      fullWorkP95Ms: ms(percentile(ticks.map(row => row.fullNanos), .95)),
      fullWorkP99Ms: ms(percentile(ticks.map(row => row.fullNanos), .99)),
      fullWorkMaxMs: ms(Math.max(...ticks.map(row => row.fullNanos))),
      fullOver50: ticks.filter(row => row.fullNanos > 50e6).length,
      activeWorkP95Ms: ms(percentile(activeTicks.map(row => row.fullNanos), .95)),
      activeWorkP99Ms: ms(percentile(activeTicks.map(row => row.fullNanos), .99)),
      activeWorkMaxMs: ms(Math.max(...activeTicks.map(row => row.fullNanos))),
      activeOver50: activeTicks.filter(row => row.fullNanos > 50e6).length,
      nativeReportedP95Ms: ms(percentile(ticks.map(row => row.nativeReportedNanos), .95)),
      omittedEndP95Ms: ms(percentile(ticks.map(row => row.fullNanos - row.nativeReportedNanos), .95)),
      grantP50Ms: ms(percentile(ticks.map(row => row.grantNanos), .5)),
      grantP95Ms: ms(percentile(ticks.map(row => row.grantNanos), .95)),
      minimumBalanceMs: ms(Math.min(...ticks.map(row => row.finalBalanceNanos))),
      controllerEntryP95Ms: ms(percentile(ticks.map(row => row.controllerEntryNanos), .95)),
      observedTailP95Ms: ms(percentile(ticks.map(row => row.measuredTailNanos), .95)),
      stopReasons: histogram(ticks.map(row => row.stopReason)),
      terminalHistogram: histogram(trial.actors.map(actor => actor.state)) };
  });
  runs.push({ name, mode: budget.value.mode, addedLoadMs: launch.value.addedLoadMs,
    mods: launch.value.mods, sampleCount: samples.length, trials });
}

const groups = [];
for (const run of runs) {
  const reference = runs.find(candidate => candidate.mode === "fixed4"
    && candidate.addedLoadMs === run.addedLoadMs);
  if (!reference) continue;
  assert.deepEqual(run.mods, reference.mods);
  const keys = new Set(run.trials.map(trial => `${trial.distance}/${trial.concurrency}`));
  for (const key of keys) {
    const trials = run.trials.filter(trial => `${trial.distance}/${trial.concurrency}` === key);
    const comparisons = trials.map(trial => {
      const original = reference.trials.find(row => row.distance === trial.distance
        && row.concurrency === trial.concurrency && row.repeat === trial.repeat);
      assert(original);
      assert.equal(trial.actors.length, original.actors.length);
      for (let i = 0; i < trial.actors.length; i++)
        assert.equal(identity(trial.actors[i]), identity(original.actors[i]));
      const pairs = trial.actors.map((actor, i) => [actor, original.actors[i]]);
      const ready = pairs.filter(([a, b]) => a.submitToReadyNanos > 0 && b.submitToReadyNanos > 0);
      const success = pairs.filter(([a, b]) => a.success && b.success);
      const newFailures = pairs.map(([a, b], index) => ({ a, b, index }))
        .filter(({ a, b }) => !a.success && b.success)
        .map(({ a, index }) => ({ index, routeKind: a.routeKind, state: a.state }));
      for (const failure of newFailures) {
        failureEvidence.push({ run: run.name, reference: reference.name, addedLoadMs: run.addedLoadMs,
          distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat, actor: failure.index,
          candidate: trial.actors[failure.index], baseline: original.actors[failure.index] });
      }
      return { repeat: trial.repeat, readyPairs: ready.length, arrivalPairs: success.length,
        readyP95Ms: ms(percentile(ready.map(([a]) => a.submitToReadyNanos), .95)),
        originalReadyP95Ms: ms(percentile(ready.map(([, b]) => b.submitToReadyNanos), .95)),
        arrivalP50Ms: ms(percentile(success.map(([a]) => a.submitToPhysicalArrivalNanos), .5)),
        originalArrivalP50Ms: ms(percentile(success.map(([, b]) => b.submitToPhysicalArrivalNanos), .5)),
        batchSubmitMs: ms(trial.batchSubmitNanos),
        originalBatchSubmitMs: ms(original.batchSubmitNanos),
        allReadyMs: ms(trial.batchAllReadyNanos),
        originalAllReadyMs: ms(original.batchAllReadyNanos),
        fullWorkP95Ms: trial.fullWorkP95Ms, fullWorkP99Ms: trial.fullWorkP99Ms,
        fullWorkMaxMs: trial.fullWorkMaxMs, fullOver50: trial.fullOver50,
        activeWorkP95Ms: trial.activeWorkP95Ms, activeWorkP99Ms: trial.activeWorkP99Ms,
        activeWorkMaxMs: trial.activeWorkMaxMs, activeOver50: trial.activeOver50,
        originalFullWorkP95Ms: original.fullWorkP95Ms,
        originalFullWorkP99Ms: original.fullWorkP99Ms,
        originalFullOver50: original.fullOver50,
        successCount: trial.successfulActors, originalSuccessCount: original.successfulActors,
        accepted: trial.actors.filter(actor => actor.accepted).length,
        originalAccepted: original.actors.filter(actor => actor.accepted).length,
        total: trial.totalActors, terminalHistogram: trial.terminalHistogram, newFailures,
        recoveredSuccesses: pairs.filter(([a, b]) => a.success && !b.success).length,
        nativeWallMs: ms(trial.actors.reduce((sum, actor) => sum + actor.nativeSearch.wallNanos, 0)),
        originalNativeWallMs: ms(original.actors.reduce((sum, actor) => sum + actor.nativeSearch.wallNanos, 0)),
        nativeCalls: trial.actors.reduce((sum, actor) => sum + actor.nativeSearch.calls, 0),
        originalNativeCalls: original.actors.reduce((sum, actor) => sum + actor.nativeSearch.calls, 0),
        navigationChargedMs: ms(trial.chargedNanosDelta),
        originalNavigationChargedMs: ms(original.chargedNanosDelta),
        observedTps: trial.observedTps, originalObservedTps: original.observedTps,
        peakHeapMb: trial.peakHeapBytes / 1e6, originalPeakHeapMb: original.peakHeapBytes / 1e6,
        gcCount: trial.gcCount, gcTimeMillis: trial.gcTimeMillis,
        mainAllocatedMb: trial.mainThreadAllocatedBytes / 1e6,
        originalMainAllocatedMb: original.mainThreadAllocatedBytes / 1e6,
        omittedEndP95Ms: trial.omittedEndP95Ms,
        grantP50Ms: trial.grantP50Ms, grantP95Ms: trial.grantP95Ms,
        minimumBalanceMs: trial.minimumBalanceMs, controllerEntryP95Ms: trial.controllerEntryP95Ms,
        observedTailP95Ms: trial.observedTailP95Ms, stopReasons: trial.stopReasons };
    });
    const p50 = field => comparisons.every(row => row[field] !== null)
      ? median(comparisons.map(row => row[field])) : null;
    const [distance, concurrency] = key.split("/").map(Number);
    groups.push({ run: run.name, mode: run.mode, addedLoadMs: run.addedLoadMs, distance, concurrency,
      repeats: comparisons.length, readyP95Ms: p50("readyP95Ms"),
      pairedBaselineReadyP95Ms: p50("originalReadyP95Ms"),
      readyP95ChangePercent: rate(p50("readyP95Ms"), p50("originalReadyP95Ms")),
      arrivalP50Ms: p50("arrivalP50Ms"),
      pairedBaselineArrivalP50Ms: p50("originalArrivalP50Ms"),
      arrivalP50ChangePercent: rate(p50("arrivalP50Ms"), p50("originalArrivalP50Ms")),
      allReadyMs: p50("allReadyMs"), allReadyChangePercent: rate(p50("allReadyMs"), p50("originalAllReadyMs")),
      batchSubmitMs: p50("batchSubmitMs"), batchSubmitChangePercent: rate(p50("batchSubmitMs"), p50("originalBatchSubmitMs")),
      fullWorkP95Ms: p50("fullWorkP95Ms"), fullWorkP95ChangePercent: rate(p50("fullWorkP95Ms"), p50("originalFullWorkP95Ms")),
      fullWorkP99Ms: p50("fullWorkP99Ms"), fullWorkMaxMs: Math.max(...comparisons.map(row => row.fullWorkMaxMs)),
      activeWorkP95Ms: p50("activeWorkP95Ms"), activeWorkP99Ms: p50("activeWorkP99Ms"),
      activeWorkMaxMs: Math.max(...comparisons.map(row => row.activeWorkMaxMs)),
      activeOver50: comparisons.reduce((sum, row) => sum + row.activeOver50, 0),
      over50: comparisons.reduce((sum, row) => sum + row.fullOver50, 0),
      originalOver50: comparisons.reduce((sum, row) => sum + row.originalFullOver50, 0),
      successful: comparisons.reduce((sum, row) => sum + row.successCount, 0),
      originalSuccessful: comparisons.reduce((sum, row) => sum + row.originalSuccessCount, 0),
      total: comparisons.reduce((sum, row) => sum + row.total, 0),
      newFailures: comparisons.reduce((sum, row) => sum + row.newFailures.length, 0),
      recoveredSuccesses: comparisons.reduce((sum, row) => sum + row.recoveredSuccesses, 0),
      mainAllocatedMb: p50("mainAllocatedMb"),
      mainAllocatedChangePercent: rate(p50("mainAllocatedMb"), p50("originalMainAllocatedMb")),
      peakHeapMb: p50("peakHeapMb"), peakHeapChangePercent: rate(p50("peakHeapMb"), p50("originalPeakHeapMb")),
      observedTps: p50("observedTps"), observedTpsChangePercent: rate(p50("observedTps"), p50("originalObservedTps")),
      nativeWallMs: p50("nativeWallMs"), nativeWallChangePercent: rate(p50("nativeWallMs"), p50("originalNativeWallMs")),
      nativeCalls: p50("nativeCalls"), nativeCallsChangePercent: rate(p50("nativeCalls"), p50("originalNativeCalls")),
      navigationChargedMs: p50("navigationChargedMs"),
      navigationChargedChangePercent: rate(p50("navigationChargedMs"), p50("originalNavigationChargedMs")),
      gcCount: comparisons.reduce((sum, row) => sum + row.gcCount, 0),
      gcTimeMillis: comparisons.reduce((sum, row) => sum + row.gcTimeMillis, 0),
      grantP50Ms: p50("grantP50Ms"), grantP95Ms: p50("grantP95Ms"),
      omittedEndP95Ms: p50("omittedEndP95Ms"), controllerEntryP95Ms: p50("controllerEntryP95Ms"),
      comparisons });
  }
}

const summary = { schema: "DYNAMIC_BUDGET_FORGE_PROTOTYPE_ANALYSIS_V1",
  completeFourModes: names.slice(0, 4).every(name => runs.some(run => run.name === name)),
  sourceSha256: sha(readFileSync(fileURLToPath(import.meta.url))), inputs, missing,
  scope: "prototype; same frozen production and probe jars; model quota baseline shares new guards; two repeats are not a production performance acceptance",
  runs: runs.map(({ trials, ...run }) => ({ ...run, formalTrials: trials.length })),
  groups };
writeFileSync(resolve(root, "runtime-analysis.json"), JSON.stringify(summary, null, 2) + "\n");
writeFileSync(resolve(root, "failure-evidence.json"), JSON.stringify(failureEvidence, null, 2) + "\n");
writeFileSync(resolve(root, "over50-evidence.json"), JSON.stringify(over50Evidence, null, 2) + "\n");
const format = value => value === null ? "N/A" : value.toFixed(2);
const change = value => value === null ? "N/A" : value.toFixed(2) + "%";
const table = [
  "计时为 tickServer 调用封套，包含 START/END 与本次前置仪表/人工负载，排除 tick 间等待及封套采样后的历史更新/序列化元数据。到达的固定4参考使用该行双方共同成功 actor，自比较固定4行的中位数不作为其他行的配对基线。",
  "",
  "| 模式 | 额外负载 ms | 距离 | 并发 | 首 Path P95 ms / 变化 | 到达 P50 候选 / 配对固定4 ms / 变化 | tickServer封套 P95 ms / 变化 | >50ms | 成功数 | 新增配对失败 |",
  "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
  ...groups.map(row => `| ${row.mode} | ${row.addedLoadMs} | ${row.distance} | ${row.concurrency} | ${format(row.readyP95Ms)} / ${change(row.readyP95ChangePercent)} | ${format(row.arrivalP50Ms)} / ${format(row.pairedBaselineArrivalP50Ms)} / ${change(row.arrivalP50ChangePercent)} | ${format(row.fullWorkP95Ms)} / ${change(row.fullWorkP95ChangePercent)} | ${row.over50} | ${row.successful}/${row.total} | ${row.newFailures} |`)
].join("\n");
writeFileSync(resolve(root, "runtime-table.md"), table + "\n");
console.log(JSON.stringify({ runs: summary.runs, groups: groups.length, missing, completeFourModes: summary.completeFourModes }, null, 2));
console.log(table);
