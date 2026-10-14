import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const [rootArg, outputName, ...names] = process.argv.slice(2);
assert.ok(rootArg && outputName && names.length, "Usage: root fresh-output run ...");
const root = resolve(rootArg), identities = [];
const read = file => {
  const path = resolve(root, file), bytes = readFileSync(path);
  identities.push({ path, sha256: createHash("sha256").update(bytes).digest("hex") });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const sum = xs => xs.reduce((a, b) => a + b, 0);
const median = values => {
  const xs = values.filter(Number.isFinite).sort((a, b) => a - b), i = Math.floor(xs.length / 2);
  return xs.length ? xs.length % 2 ? xs[i] : (xs[i - 1] + xs[i]) / 2 : null;
};
const percentile = (values, p) => {
  const xs = values.filter(Number.isFinite).sort((a, b) => a - b);
  return xs.length ? xs[Math.ceil(xs.length * p) - 1] : null;
};
const positive = xs => xs.filter(n => Number.isFinite(n) && n >= 0);
const divide = (value, divisor) => value == null ? null : value / divisor;
const ms = n => divide(n, 1e6);
const change = (candidate, reference) =>
  candidate == null || reference == null || reference === 0 ? null : (candidate / reference - 1) * 100;
function events(samples, allSamples) {
  const result = [];
  for (let i = 0; i < samples.length; i++) {
    if (samples[i].fullNanos <= 50e6) continue;
    const first = i;
    while (i + 1 < samples.length && samples[i + 1].tick === samples[i].tick + 1
        && samples[i + 1].fullNanos > 50e6) i++;
    const episode = samples.slice(first, i + 1), last = episode.at(-1);
    let streak = 0, recoveryMs = null, previousTick = last.tick;
    for (const row of allSamples.filter(row => row.tick > last.tick)) {
      streak = row.tick === previousTick + 1 && row.fullNanos <= 50e6
        ? streak + 1 : row.fullNanos <= 50e6 ? 1 : 0;
      previousTick = row.tick;
      if (streak === 5) { recoveryMs = (row.endNanos - last.endNanos) / 1e6; break; }
    }
    result.push({ startTick: episode[0].tick, endTick: last.tick, length: episode.length,
      maxMs: ms(Math.max(...episode.map(row => row.fullNanos))), recoveryMs,
      fullMs: episode.map(row => ms(row.fullNanos)),
      navigationMs: episode.map(row => ms(row.navigationNanos)),
      grantMs: episode.map(row => ms(row.grantNanos)) });
  }
  return result;
}
const runs = names.map(name => {
  const launch = read(`${name}/launch-environment.json`), exit = read(`${name}/exit.json`);
  const data = read(`${name}/performance-comparison.json`), ticks = read(`${name}/full-ticks.json`);
  const dispatch = read(`${name}/dispatch-timings.json`), shutdown = read(`${name}/shutdown.json`);
  const host = read(`${name}/host-before.json`);
  assert.equal(exit.exitCode, 0); assert.equal(exit.timedOut, false);
  assert.equal(data.complete, true); assert.equal(data.failed, false);
  assert.equal(ticks.truncated, false); assert.equal(dispatch.truncated, false);
  assert.equal(shutdown.passed, true);
  assert.ok(launch.comparisonMode, "Explicit mode required; never infer a current jar as an old baseline");
  assert.equal(launch.vanilla, launch.comparisonMode === "vanilla");
  assert.equal(data.variant, launch.vanilla ? "vanilla-same-runtime" : "accelerated");
  assert.equal(host.otherJava.length, 0, "Concurrent Java process compromises isolated performance evidence");
  assert.equal(new Set(ticks.samples.map(row => row.tick)).size, ticks.samples.length);
  assert.equal(launch.handoffTrace, false, "Detailed handoff tracing is not a performance instrument");
  return { name, mode: launch.comparisonMode, launch, exit, host, data,
    ticks: ticks.samples, dispatch: dispatch.samples, allEvents: events(ticks.samples, ticks.samples),
    formal: data.trials.filter(row => !row.warmup) };
});
assert.equal(new Set(runs.map(run =>
  run.launch.mods.find(mod => mod.name === "production-probe.jar").sha256)).size, 1);
assert.equal(new Set(runs.map(run => run.launch.propertiesSha256)).size, 1);
const modes = [...new Set(runs.map(run => run.mode))];
for (const mode of modes)
  assert.equal(new Set(runs.filter(run => run.mode === mode).map(run =>
    run.launch.mods.find(mod => mod.name === "accelerated_navigation.jar").sha256)).size, 1);
const rows = runs.flatMap(run => run.formal.map(trial => {
  assert.equal(new Set(trial.actors.map(actor => JSON.stringify([actor.start, actor.goal]))).size, trial.concurrency);
  assert.equal(new Set(trial.actors.map(actor => actor.routeKind)).size, 8);
  for (const actor of trial.actors) {
    const distance = Math.sqrt(sum(actor.start.map((coordinate, i) => (coordinate - actor.goal[i]) ** 2)));
    assert.ok(Math.abs(distance - actor.straightDistance) < 1e-9);
    assert.ok(trial.distance === 8 ? distance < 12 : trial.distance === 18
      ? distance >= 12 && distance <= 24 : distance >= 24 && distance <= 32);
    assert.ok(actor.submitCallNanos >= 0);
    if (actor.success) assert.ok(actor.submitToPhysicalArrivalNanos >= 0);
  }
  const ticks = run.ticks.filter(row => row.tick >= trial.submitTick && row.tick <= trial.endTick);
  assert.equal(ticks.length, trial.endTick - trial.submitTick + 1);
  const dispatch = run.dispatch.filter(row => row.tick >= trial.submitTick && row.tick <= trial.endTick);
  const episode = events(ticks, run.ticks);
  const states = trial.actors.reduce((counts, actor) =>
    (counts[actor.state] = (counts[actor.state] ?? 0) + 1, counts), {});
  return {
    run: run.name, mode: run.mode, distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat,
    requested: trial.totalActors, success: trial.successfulActors, ready: trial.pathReadyActors,
    physical: trial.physicalArrivals, rejected: trial.rejectedActors, timedOut: trial.timedOut, states,
    apiSubmitP50Us: divide(median(trial.actors.map(actor => actor.submitCallNanos)), 1000),
    apiSubmitP95Us: divide(percentile(trial.actors.map(actor => actor.submitCallNanos), .95), 1000),
    submissionActiveMs: ms(sum(trial.actors.map(actor => actor.submitCallNanos))),
    batchSubmitMs: ms(trial.batchSubmitNanos), batchObservationMs: ms(trial.batchFinishNanos),
    allReadyMs: trial.batchAllReadyNanos < 0 ? null : ms(trial.batchAllReadyNanos),
    readyP50Ms: ms(median(positive(trial.actors.map(actor => actor.submitToReadyNanos)))),
    readyP95Ms: ms(percentile(positive(trial.actors.map(actor => actor.submitToReadyNanos)), .95)),
    batchReadyP95Ms: ms(percentile(positive(trial.actors.map(actor => actor.batchToReadyNanos)), .95)),
    tps: (ticks.length - 1) / Math.max(1e-9, (ticks.at(-1).startNanos - ticks[0].startNanos) / 1e9),
    fullP50Ms: ms(percentile(ticks.map(row => row.fullNanos), .5)),
    fullP95Ms: ms(percentile(ticks.map(row => row.fullNanos), .95)),
    fullP99Ms: ms(percentile(ticks.map(row => row.fullNanos), .99)),
    fullMaxMs: ms(Math.max(...ticks.map(row => row.fullNanos))),
    submitTickMs: ms(ticks[0].fullNanos),
    over50: ticks.filter(row => row.fullNanos > 50e6).length,
    over75: ticks.filter(row => row.fullNanos > 75e6).length,
    over100: ticks.filter(row => row.fullNanos > 100e6).length,
    measuredTicks: ticks.length, wallSeconds: (ticks.at(-1).endNanos - ticks[0].startNanos) / 1e9,
    events: episode,
    navigationMs: ms(sum(ticks.map(row => row.navigationNanos))),
    outsideNavigationMs: ms(sum(ticks.map(row => row.outsideNavigationNanos))),
    grantP50Ms: ms(median(ticks.map(row => row.grantNanos))),
    grantMinMs: ms(Math.min(...ticks.map(row => row.grantNanos))),
    grantMaxMs: ms(Math.max(...ticks.map(row => row.grantNanos))),
    maxPending: Math.max(...ticks.map(row => row.pending)),
    maxRunnable: Math.max(...ticks.map(row => row.runnable)),
    maxParked: Math.max(...ticks.map(row => row.parked)),
    wakeDispatchP50Ms: ms(median(dispatch.map(row => row.wakeToDispatchNanos))),
    wakeDispatchP95Ms: ms(percentile(dispatch.map(row => row.wakeToDispatchNanos), .95)),
    nativeCalls: sum(trial.actors.map(actor => actor.nativeSearch.calls)),
    serviceNativeCalls: trial.nativeCallsDelta, recoveries: trial.recoveriesDelta, wholeCalls: trial.wholeCallsDelta,
    nullPaths: sum(trial.actors.map(actor => actor.nativeSearch.nullPaths)),
    partialPaths: sum(trial.actors.map(actor => actor.nativeSearch.partialPaths)),
    nativeWallMs: ms(sum(trial.actors.map(actor => actor.nativeSearch.wallNanos))),
    nativeCpuMs: ms(sum(trial.actors.map(actor => actor.nativeSearch.cpuNanos))),
    nativeAllocatedMB: sum(trial.actors.map(actor => actor.nativeSearch.allocatedBytes)) / 1e6,
    mainAllocatedMB: trial.mainThreadAllocatedBytes / 1e6, heapPeakMB: trial.peakHeapBytes / 1e6,
    heapDeltaMB: trial.peakHeapDeltaBytes / 1e6, gcCount: trial.gcCount, gcTimeMs: trial.gcTimeMillis,
    baseCacheMB: trial.topologyEnd.worker.baseCache.retainedBytes / 1e6,
    parentCacheMB: trial.topologyEnd.worker.parentCache.retainedBytes / 1e6,
    corridorCacheKB: trial.topologyEnd.worker.corridorCache.retainedBytes / 1000,
    publishedNodesPeak: trial.peakPublishedNodes,
    kinds: [...new Set(trial.actors.map(actor => actor.routeKind))].map(kind => {
      const actors = trial.actors.filter(actor => actor.routeKind === kind);
      return { kind, requested: actors.length, success: actors.filter(actor => actor.success).length,
        states: actors.reduce((counts, actor) => (counts[actor.state] = (counts[actor.state] ?? 0) + 1, counts), {}) };
    })
  };
}));
const summaries = [];
for (const mode of modes) for (const distance of [...new Set(rows.map(row => row.distance))])
  for (const concurrency of [...new Set(rows.map(row => row.concurrency))]) {
    const group = rows.filter(row => row.mode === mode && row.distance === distance && row.concurrency === concurrency);
    if (!group.length) continue;
    const row = { mode, distance, concurrency, trials: group.length };
    const totals = ["requested", "success", "ready", "physical", "rejected", "over50", "over75", "over100", "measuredTicks"];
    const maxima = ["fullMaxMs", "maxPending", "maxRunnable", "maxParked", "grantMaxMs"];
    for (const key of Object.keys(group[0]))
      if (typeof group[0][key] === "number" && !["distance", "concurrency", "repeat"].includes(key))
        row[key] = totals.includes(key) ? sum(group.map(item => item[key]))
          : maxima.includes(key) ? Math.max(...group.map(item => item[key])) : median(group.map(item => item[key]));
    row.successRatePercent = row.success / row.requested * 100;
    row.maxConsecutive = Math.max(0, ...group.flatMap(item => item.events.map(event => event.length)));
    row.eventCount = sum(group.map(item => item.events.length));
    row.eventsPer1000Ticks = row.eventCount * 1000 / row.measuredTicks;
    row.eventsPerMinute = row.eventCount * 60 / sum(group.map(item => item.wallSeconds));
    row.recoveryP95Ms = percentile(group.flatMap(item => item.events.map(event => event.recoveryMs)), .95);
    row.states = group.reduce((states, item) => {
      for (const [state, count] of Object.entries(item.states)) states[state] = (states[state] ?? 0) + count;
      return states;
    }, {});
    row.kinds = group[0].kinds.map(({kind}) => {
      const items = group.map(item => item.kinds.find(entry => entry.kind === kind));
      return {kind, requested:sum(items.map(item => item.requested)), success:sum(items.map(item => item.success))};
    });
    row.runRange = Object.fromEntries(["readyP95Ms", "batchReadyP95Ms", "nativeWallMs", "navigationMs", "mainAllocatedMB", "fullP99Ms"].map(key =>
      [key, [Math.min(...group.map(item => item[key])), Math.max(...group.map(item => item[key]))]]));
    summaries.push(row);
  }
const paired = [];
for (const referenceMode of modes.filter(mode => mode !== "dynamic20")) {
  const references = runs.filter(run => run.mode === referenceMode);
  const candidates = runs.filter(run => run.mode === "dynamic20");
  assert.equal(references.length, candidates.length, "Pair equal JVM repetitions explicitly");
  for (let i = 0; i < references.length; i++) for (const trial of candidates[i].formal) {
    const other = references[i].formal.find(row => row.distance === trial.distance &&
      row.concurrency === trial.concurrency && row.repeat === trial.repeat);
    if (!other) continue;
    const candidateRun = candidates[i], referenceRun = references[i];
    for (const key of ["speedAmplifier", "submissionSpanTicks", "coldStart", "addedLoadMs", "seed", "heapMb"])
      assert.equal(candidateRun.launch[key], referenceRun.launch[key], `Different paired load: ${key}`);
    assert.equal(trial.actors.length, other.actors.length);
    const common = [], lost = [], gained = [];
    for (let actor = 0; actor < trial.actors.length; actor++) {
      const candidate = trial.actors[actor], reference = other.actors[actor];
      for (const key of ["start", "initialPosition", "goal", "routeKind", "tile", "slot", "rotation"])
        assert.deepEqual(candidate[key], reference[key], `Different paired actor input: ${key}`);
      if (candidate.success && reference.success) common.push([candidate, reference]);
      else if (candidate.success) gained.push(actor);
      else if (reference.success) lost.push(actor);
    }
    const candidateRow = rows.find(row => row.run === candidateRun.name && row.distance === trial.distance &&
      row.concurrency === trial.concurrency && row.repeat === trial.repeat);
    const referenceRow = rows.find(row => row.run === referenceRun.name && row.distance === trial.distance &&
      row.concurrency === trial.concurrency && row.repeat === trial.repeat);
    const row = { candidate: candidateRun.name, reference: referenceRun.name, referenceMode,
      distance: trial.distance, concurrency: trial.concurrency, repeat: trial.repeat,
      commonSuccess: common.length, lostSuccessActors: lost, gainedSuccessActors: gained,
      differencePercent: {} };
    for (const key of ["submitToPhysicalArrivalNanos", "firstMoveToPhysicalArrivalNanos", "submitToFinishNanos"]) {
      row[key] = { candidateMs:ms(median(positive(common.map(pair => pair[0][key])))),
        referenceMs:ms(median(positive(common.map(pair => pair[1][key])))) };
      row[key].changePercent = change(row[key].candidateMs, row[key].referenceMs);
    }
    for (const key of Object.keys(candidateRow))
      if (typeof candidateRow[key] === "number" && typeof referenceRow[key] === "number")
        row.differencePercent[key] = change(candidateRow[key], referenceRow[key]);
    paired.push(row);
  }
}
const comparisons = [];
for (const referenceMode of modes.filter(mode => mode !== "dynamic20"))
  for (const candidate of summaries.filter(row => row.mode === "dynamic20")) {
    const reference = summaries.find(row => row.mode === referenceMode && row.distance === candidate.distance &&
      row.concurrency === candidate.concurrency);
    if (!reference) continue;
    const group = paired.filter(row => row.referenceMode === referenceMode && row.distance === candidate.distance &&
      row.concurrency === candidate.concurrency);
    assert.equal(group.length, candidate.trials, "Missing formal pairs");
    const row = { referenceMode, distance:candidate.distance, concurrency:candidate.concurrency, pairs:group.length,
      commonSuccess:sum(group.map(item => item.commonSuccess)),
      lost:sum(group.map(item => item.lostSuccessActors.length)), gained:sum(group.map(item => item.gainedSuccessActors.length)),
      successDifferencePoints:candidate.successRatePercent-reference.successRatePercent, differencePercent:{} };
    for (const key of Object.keys(candidate))
      if (typeof candidate[key] === "number" && typeof reference[key] === "number")
        row.differencePercent[key] = change(candidate[key], reference[key]);
    for (const key of ["submitToPhysicalArrivalNanos", "firstMoveToPhysicalArrivalNanos", "submitToFinishNanos"]) {
      row[key] = { candidateMs:median(group.map(item => item[key].candidateMs)),
        referenceMs:median(group.map(item => item[key].referenceMs)) };
      row[key].changePercent = change(row[key].candidateMs, row[key].referenceMs);
    }
    comparisons.push(row);
  }
const output = resolve(root, outputName);
assert.ok(!existsSync(output), "Preserve prior analysis; use a fresh output");
mkdirSync(output, { recursive:true });
writeFileSync(resolve(output, "comparison.json"), JSON.stringify({
  schema:"ACTUAL_ENTRY_PERFORMANCE_COMPARISON_V1", identities,
  scope:"Dedicated server; one serial Java process; fixed probe; full virtual tick including test cleanup; all native partials and failures retained",
  units:"ms; MB=1,000,000 bytes; CPU quantized by ThreadMXBean; heap includes probe retention and GC timing",
  summary:"Median of per-trial statistics; counts summed; full max is the maximum; percentages use summary candidate/reference-1",
  cold:"Fresh JVM/world and first navigation request, no route warmup; controller has normal startup tick history",
  arrival:"Identical actors successful in both modes only; native reachable result and actual accuracy; no failed actor removed from success/resource statistics",
  runs:runs.map(({ name, mode, launch, exit, host, allEvents }) => ({ name, mode, launch, exit, host, allEvents })),
  rows, summaries, paired, comparisons
}, null, 2) + "\n");
const columns = ["mode","distance","concurrency","trials","success","requested","batchSubmitMs","batchReadyP95Ms",
  "nativeWallMs","fullP95Ms","fullP99Ms","fullMaxMs","over50","over100","maxConsecutive","tps",
  "mainAllocatedMB","heapPeakMB","nativeCalls","recoveries"];
writeFileSync(resolve(output, "tables.md"), `| ${columns.join(" | ")} |\n| ${columns.map(() => "---").join(" | ")} |\n` +
  summaries.map(row => `| ${columns.map(key => typeof row[key] === "number" ? row[key].toFixed(3) : row[key] ?? "N/A").join(" | ")} |`).join("\n") + "\n");
writeFileSync(resolve(output, "comparison.csv"), columns.join(",") + "\n" +
  summaries.map(row => columns.map(key => row[key] ?? "").join(",")).join("\n") + "\n");
console.log(JSON.stringify({ output, modes, runs:runs.length, formalTrials:rows.length,
  requestsByMode:modes.map(mode => ({mode, requested:sum(rows.filter(row => row.mode === mode).map(row => row.requested))})),
  paired:paired.length, lost:sum(paired.map(row => row.lostSuccessActors.length)),
  gained:sum(paired.map(row => row.gainedSuccessActors.length)) }, null, 2));
