import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import assert from "node:assert/strict";
import { analyzeCampaign, loadConfig, median, percentile, toCsv } from "./analysis.mjs";

const toolRoot = path.dirname(fileURLToPath(import.meta.url));
const write = (file, data) => fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n");
const read = file => JSON.parse(fs.readFileSync(file, "utf8"));
const sha = "1".repeat(64);

function fixture(t, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "an-performance-"));
  t.after(() => {
    const resolved = path.resolve(root);
    assert.ok(resolved.startsWith(path.resolve(os.tmpdir()) + path.sep));
    assert.ok(path.basename(resolved).startsWith("an-performance-"));
    fs.rmSync(resolved, { recursive: true, force: true });
  });
  const variants = (options.variants ?? ["control", "candidate"]).map(id =>
    ({ id, resultVariant: `raw-${id}`, launchVariant: id, productionSha256: sha }));
  const config = {
    schema: "NAVIGATION_BENCHMARK_CAMPAIGN_V1", name: "Synthetic regression controls",
    runsRoot: "runs with spaces", minimumReadySeconds: 30, variants, referenceVariant: variants[0].id,
    scenarioFields: options.scenarioFields ?? ["distance", "concurrency"],
    scenarios: options.scenarios ?? [{ distance: 5, concurrency: 2 }, { distance: 37, concurrency: 2 }],
    repeats: options.repeats ?? [0, 1], warmups: [],
    deadlineMillis: [1, 100],
    runs: Array.from({ length: options.pairs ?? 3 }, (_, pair) => variants.map(variant =>
      ({ id: `session-${pair}-${variant.id}`, directory: `session-${pair}-${variant.id}`,
        variant: variant.id, pair: `replicate-${pair}` }))).flat(),
    identity: { productionMod: "production.jar", probeMod: "probe.jar",
      probeSha256: sha, templateSha256: sha, ignoredJvmArgPrefixes: ["-Doutput="] },
  };
  const configFile = path.join(root, "campaign.json");
  write(configFile, config);
  for (let runIndex = 0; runIndex < config.runs.length; runIndex++) {
    const run = config.runs[runIndex];
    const directory = path.join(root, config.runsRoot, run.directory);
    fs.mkdirSync(directory, { recursive: true });
    const serverStartedNanos = 1e12 + runIndex * 1e12;
    const ticks = [], trials = [];
    for (const scenario of config.scenarios) for (const repeat of config.repeats) {
      const trialIndex = trials.length;
      const batchStartNanos = serverStartedNanos + 31e9 + trialIndex * 60e9;
      const measurementEndNanos = batchStartNanos + 5e6;
      const actors = Array.from({ length: scenario.concurrency }, (_, slot) => ({
        tile: 0, slot, rotation: 0, routeKind: "flat", start: [0, 80, slot], goal: [scenario.distance, 80, slot],
        state: "SUCCEEDED", success: true, accepted: true,
        submitStartNanos: batchStartNanos + slot * 1000,
        submitEndNanos: batchStartNanos + slot * 1000 + 500,
        firstReadyNanos: batchStartNanos + slot * 1000 + 400,
        batchToReadyNanos: slot * 1000 + 400,
        physicalArrivalNanos: batchStartNanos + 1e6 + slot * 1000,
        batchToPhysicalArrivalNanos: 1e6 + slot * 1000,
        finishNanos: batchStartNanos + 2e6 + slot * 1000,
        nativeSearch: { calls: 1, wallNanos: 100, cpuNanos: 0, allocatedBytes: 32,
          nullPaths: 0, partialPaths: 0, reachablePaths: 1 },
      }));
      const serverSubmitTick = trialIndex * 2 + 1;
      ticks.push(
        { serverTick: serverSubmitTick, startNanos: batchStartNanos - 1000,
          endNanos: batchStartNanos + 1e6, fullNanos: 1e6 + 1000 },
        { serverTick: serverSubmitTick + 1, startNanos: batchStartNanos + 1e6 + 1000,
          endNanos: measurementEndNanos + 1000, fullNanos: 4e6 },
      );
      trials.push({
        ...scenario, variant: `raw-${run.variant}`, repeat, warmup: false, timedOut: false,
        serverStartedNanos, sceneReadyNanos: serverStartedNanos + 1e8,
        batchStartNanos, measurementEndNanos, batchFinishNanos: 5e6,
        serverSubmitTick, serverEndTick: serverSubmitTick + 1,
        totalActors: actors.length, successfulActors: actors.length, physicalArrivals: actors.length,
        pathReadyActors: actors.length, rejectedActors: 0, actors,
        batchAllReadyNanos: Math.max(...actors.map(actor => actor.batchToReadyNanos)),
        batchAllSuccessNanos: Math.max(...actors.map(actor => actor.finishNanos)) - batchStartNanos,
        processCpuNanos: 1000, allThreadAllocatedBytes: 1024, mainThreadCpuNanos: 500,
        mainThreadAllocatedBytes: 512, gcCount: 0, gcTimeMillis: 0, nativeCallsDelta: actors.length,
        segmentCallsDelta: actors.length, wholeCallsDelta: 0, serviceFailuresDelta: 0,
        recoveriesDelta: 0, chargedNanosDelta: 1000, batchSubmitNanos: actors.length * 1000,
        costStart: { nanoTime: batchStartNanos - 100, processCpuNanos: 0, allThreadAllocatedBytes: 0,
          mainThreadCpuNanos: 0, mainThreadAllocatedBytes: 0, gcCount: 0, gcMillis: 0 },
        costEnd: { nanoTime: measurementEndNanos + 100, processCpuNanos: 1000, allThreadAllocatedBytes: 1024,
          mainThreadCpuNanos: 500, mainThreadAllocatedBytes: 512, gcCount: 0, gcMillis: 0 },
      });
    }
    write(path.join(directory, "performance-comparison.json"), {
      schema: "CURRENT_PERFORMANCE_REFERENCE_V1", failed: false, complete: true,
      variant: `raw-${run.variant}`, repeats: config.repeats.length, serverStartedNanos,
      timeoutTicks: 100, postTerminalObservationTicks: 10, speedAmplifier: 1, addedLoadNanos: 0,
      submissionSpanTicks: 1, entity: "fixture", seed: 7, javaVersion: "fixture-java",
      availableProcessors: 2, maxHeapBytes: 1e6, routeKinds: ["flat"], trials,
    });
    write(path.join(directory, "full-ticks.json"), {
      schema: "REFERENCE_FULL_TICK_V1", truncated: false, incomplete: 0, samples: ticks,
    });
    write(path.join(directory, "exit.json"), {
      pid: 100 + runIndex, startedUtc: new Date(Date.UTC(2026, 0, 1, runIndex)).toISOString(),
      exitCode: 0, timedOut: false, concurrentJava: [],
    });
    write(path.join(directory, "shutdown.json"), { passed: true });
    write(path.join(directory, "launch.json"), {
      run: run.id, mode: "formal", variant: run.variant, diagnostic: false,
      mods: [{ name: "production.jar", sha256: sha }, { name: "probe.jar", sha256: sha }],
      templateManifestSha256: sha, jvmArgs: ["-Xmx1G", `-Doutput=${directory}`],
      java: "fixture-java", forgeArgs: "fixture-forge", os: "fixture-os", processors: 2, cpu: [{ name: "fixture" }],
    });
  }
  const change = (index, filename, mutate) => {
    const file = path.join(root, config.runsRoot, config.runs[index].directory, filename);
    const value = read(file);
    mutate(value);
    write(file, value);
  };
  return { root, config, configFile, change, analyze: () => analyzeCampaign(loadConfig(configFile)) };
}

test("arbitrary run names, three variants, distances and repeats use independent JVM counts", t => {
  const f = fixture(t, { variants: ["control", "candidate", "alternate"] });
  const summary = f.analyze();
  assert.equal(summary.complete, true);
  assert.equal(summary.performanceVerdict, "NOT_EVALUATED");
  assert.equal(summary.rows.length, 36);
  assert.equal(summary.cells.length, 6);
  for (const cell of summary.cells) {
    assert.equal(cell.sampleCount, 3);
    assert.equal(cell.trialCount, 6);
    assert.equal(cell.metrics.processCpuNanos.count, 3);
  }
  assert.ok(summary.comparisons.every(item => item.pairCount === 3));
});

test("missing entire high-concurrency scenario cannot pass coverage", t => {
  const f = fixture(t, { scenarios: [{ distance: 8, concurrency: 2 }, { distance: 8, concurrency: 512 }], repeats: [0] });
  for (let i = 0; i < f.config.runs.length; i++)
    f.change(i, "performance-comparison.json", data => { data.trials = data.trials.filter(trial => trial.concurrency !== 512); });
  const summary = f.analyze();
  assert.equal(summary.complete, false);
  assert.equal(summary.coverage.expectedFormalTrials, 12);
  assert.equal(summary.rows.length, 0);
  assert.equal(summary.cells.length, 4);
  assert.ok(summary.issues.every(issue => issue.message.includes("coverage")));
});

test("duplicate trial cannot compensate for missing scenario or repeat", t => {
  const f = fixture(t);
  f.change(0, "performance-comparison.json", data => { data.trials[1] = structuredClone(data.trials[0]); });
  const summary = f.analyze();
  assert.equal(summary.complete, false);
  assert.match(summary.issues[0].message, /duplicates/);
});

test("functional timeout retains zero success and resource costs in all JVM samples", t => {
  const f = fixture(t, { repeats: [0], scenarios: [{ distance: 8, concurrency: 2 }] });
  f.change(1, "performance-comparison.json", data => {
    const trial = data.trials[0];
    trial.timedOut = true;
    trial.successfulActors = trial.physicalArrivals = 0;
    trial.batchAllSuccessNanos = -1;
    trial.processCpuNanos = 9000;
    trial.allThreadAllocatedBytes = 8192;
    trial.costEnd.processCpuNanos = 9000;
    trial.costEnd.allThreadAllocatedBytes = 8192;
    for (const actor of trial.actors) {
      actor.success = false;
      actor.state = "TIMEOUT";
      actor.physicalArrivalNanos = 0;
      actor.batchToPhysicalArrivalNanos = -1;
    }
  });
  const summary = f.analyze();
  assert.equal(summary.complete, true);
  assert.equal(summary.functional.timedOutTrials, 1);
  const cell = summary.cells.find(cell => cell.variant === "candidate");
  assert.deepEqual(cell.metrics.successRate, { median: 1, min: 0, max: 1, count: 3, missingCount: 0 });
  assert.equal(cell.metrics.processCpuNanos.count, 3);
  assert.equal(cell.metrics.processCpuNanos.max, 9000);
  assert.equal(cell.metrics.allThreadAllocatedBytes.max, 8192);
  assert.equal(cell.metrics.allPhysicalArrivalNanos.missingCount, 1);
  assert.equal(cell.metrics.conditionalArrivalP50Nanos.missingCount, 1);
  const row = summary.rows.find(row => row.timedOut);
  assert.equal(row.measurementWindowNanos, 5e6);
  assert.equal(row.allPhysicalArrivalNanos, null);
  assert.equal(row.lastObservedArrivalNanos, null);
});

test("one failed repeat cannot disappear from a JVM completion metric", t => {
  const f = fixture(t);
  f.change(1, "performance-comparison.json", data => {
    const trial = data.trials[0], actor = trial.actors[0];
    trial.successfulActors--;
    trial.physicalArrivals--;
    trial.batchAllSuccessNanos = -1;
    actor.success = false;
    actor.state = "EXHAUSTED";
    actor.physicalArrivalNanos = 0;
    actor.batchToPhysicalArrivalNanos = -1;
  });
  const summary = f.analyze();
  const cell = summary.cells.find(cell => cell.variant === "candidate" && cell.scenario.distance === 5);
  assert.equal(cell.metrics.allPhysicalArrivalNanos.count, 2);
  assert.equal(cell.metrics.allPhysicalArrivalNanos.missingCount, 1);
  assert.equal(cell.metrics.processCpuNanos.count, 3);
});

test("observation window is distinct from real all-arrival and terminal completion time", t => {
  const f = fixture(t);
  const row = f.analyze().rows[0];
  assert.equal(row.measurementWindowNanos, 5e6);
  assert.equal(row.allPhysicalArrivalNanos, 1001000);
  assert.equal(row.lastObservedArrivalNanos, 1001000);
});

for (const [name, file, mutate, pattern] of [
  ["wrong result arm", "performance-comparison.json", data => { data.variant = "other"; }, /variant identity/],
  ["wrong trial arm", "performance-comparison.json", data => { data.trials[0].variant = "other"; }, /Trial variant/],
  ["wrong production jar", "launch.json", data => { data.mods[0].sha256 = "2".repeat(64); }, /Mod identity/],
  ["wrong probe jar", "launch.json", data => { data.mods[1].sha256 = "2".repeat(64); }, /Mod identity/],
  ["wrong template", "launch.json", data => { data.templateManifestSha256 = "2".repeat(64); }, /template identity/],
  ["missing ready timestamp", "performance-comparison.json", data => { delete data.trials[0].sceneReadyNanos; }, /sceneReadyNanos/],
  ["numeric string", "performance-comparison.json", data => { data.trials[0].processCpuNanos = "0"; }, /safe integer/],
  ["null numeric value", "performance-comparison.json", data => { data.trials[0].allThreadAllocatedBytes = null; }, /safe integer/],
  ["short ready interval", "performance-comparison.json", data => { data.trials[0].sceneReadyNanos = data.trials[0].batchStartNanos; }, /gate failed/],
  ["wrong actor count", "performance-comparison.json", data => { data.trials[0].actors.pop(); }, /Actor count/],
  ["wrong arrival count", "performance-comparison.json", data => { data.trials[0].physicalArrivals = 0; }, /counter disagrees/],
  ["wrong latency", "performance-comparison.json", data => { data.trials[0].actors[0].batchToReadyNanos++; }, /disagree/],
  ["missing tick", "full-ticks.json", data => { data.samples.splice(1, 1); }, /sample gap/],
  ["duplicate tick replaces hole", "full-ticks.json", data => { data.samples[1] = structuredClone(data.samples[0]); }, /Duplicate server tick/],
  ["truncated ticks", "full-ticks.json", data => { data.truncated = true; }, /Truncated/],
  ["incomplete ticks", "full-ticks.json", data => { data.incomplete = 1; }, /incomplete/],
  ["external Java", "exit.json", data => { data.concurrentJava = [{ pid: 999 }]; }, /interference/],
  ["runner timeout", "exit.json", data => { data.timedOut = true; }, /runner timed out/],
  ["cleanup failure", "shutdown.json", data => { data.passed = false; }, /cleanup/],
]) test(`rejects ${name}`, t => {
  const f = fixture(t);
  f.change(0, file, mutate);
  const summary = f.analyze();
  assert.equal(summary.complete, false);
  assert.match(summary.issues[0].message, pattern);
});

test("pair matching detects changed route input and excludes that run", t => {
  const f = fixture(t);
  f.change(1, "performance-comparison.json", data => { data.trials[0].actors[0].goal[0]++; });
  const summary = f.analyze();
  assert.equal(summary.complete, false);
  assert.equal(summary.runs.includes(f.config.runs[1].id), false);
  assert.match(summary.issues[0].message, /route inputs/);
});

test("shared settings drift cannot be mixed into the same comparison", t => {
  const f = fixture(t);
  f.change(1, "launch.json", data => { data.jvmArgs[0] = "-Xmx2G"; });
  assert.match(f.analyze().issues[0].message, /settings identity/);
});

test("one JVM cannot be counted twice under different run names", t => {
  const f = fixture(t);
  const first = read(path.join(f.root, f.config.runsRoot, f.config.runs[0].directory, "exit.json"));
  f.change(2, "exit.json", data => { data.pid = first.pid; data.startedUtc = first.startedUtc; });
  assert.match(f.analyze().issues[0].message, /Same JVM/);
});

test("zero reference costs produce no infinite relative change", t => {
  const f = fixture(t);
  for (let i = 0; i < f.config.runs.length; i++)
    if (f.config.runs[i].variant === "control") f.change(i, "performance-comparison.json", data => {
      for (const trial of data.trials) {
        trial.processCpuNanos = 0;
        trial.costEnd.processCpuNanos = 0;
      }
    });
  assert.equal(f.analyze().comparisons[0].metrics.processCpuNanos.relativeChange.count, 0);
});

test("unobserved future deadline is null, not a fabricated zero rate", t => {
  const f = fixture(t);
  f.change(1, "performance-comparison.json", data => {
    const trial = data.trials[0], actor = trial.actors[0];
    actor.success = false; actor.physicalArrivalNanos = 0; actor.batchToPhysicalArrivalNanos = -1;
    trial.successfulActors--; trial.physicalArrivals--; trial.batchAllSuccessNanos = -1;
  });
  const row = f.analyze().rows.find(row => row.run === f.config.runs[1].id);
  const deadline = row.deadlines.find(deadline => deadline.milliseconds === 100);
  assert.equal(deadline.observedArrivals, 1);
  assert.equal(deadline.physicalArrivalRate, null);
});

test("BOM JSON is accepted; unknown dimensions, duplicate declarations and missing arm are rejected", t => {
  const f = fixture(t);
  fs.writeFileSync(f.configFile, "\uFEFF" + JSON.stringify(f.config));
  assert.equal(f.analyze().complete, true);
  const bad = structuredClone(f.config);
  bad.scenarioFields = ["distance", "concurrency", "cacheState"];
  write(f.configFile, bad);
  assert.throws(() => loadConfig(f.configFile), /cacheState/);
  bad.scenarioFields = ["distance", "concurrency"];
  bad.runs.pop();
  write(f.configFile, bad);
  assert.throws(() => loadConfig(f.configFile), /every variant/);
});

test("standard median and nearest-rank percentile have distinct documented meanings", () => {
  assert.equal(median([10, 20]), 15);
  assert.equal(percentile([10, 20], .5), 10);
  assert.equal(median([]), null);
  assert.equal(toCsv([{ value: 'a,"b"\nc', missing: null }]), 'value,missing\n"a,""b""\nc",\n');
});

test("resource deltas must agree with raw snapshots", t => {
  const f = fixture(t);
  f.change(0, "performance-comparison.json", data => { data.trials[0].processCpuNanos++; });
  assert.match(f.analyze().issues[0].message, /cost snapshots/);
});

test("repeat rates use all actors and resource means retain every trial", t => {
  const f = fixture(t, { repeats: [0, 1, 2] });
  f.change(1, "performance-comparison.json", data => {
    const trial = data.trials[0];
    trial.successfulActors = 0;
    trial.batchAllSuccessNanos = -1;
    trial.processCpuNanos = trial.costEnd.processCpuNanos = 4000;
    for (const actor of trial.actors) actor.success = false;
  });
  const cell = f.analyze().cells.find(cell => cell.variant === "candidate" && cell.scenario.distance === 5);
  const jvm = cell.jvms.find(jvm => jvm.run === f.config.runs[1].id);
  assert.equal(jvm.values.successRate, 2 / 3);
  assert.equal(jvm.values.processCpuNanos, 2000);
});

test("scenario dimension order does not change cell membership", t => {
  const f = fixture(t);
  f.config.scenarios = f.config.scenarios.map(({ distance, concurrency }) => ({ concurrency, distance }));
  write(f.configFile, f.config);
  assert.ok(f.analyze().cells.every(cell => cell.sampleCount === 3));
});

test("additional declared dimensions separate cold and warm cells without changing the analyzer", t => {
  const f = fixture(t, {
    scenarioFields: ["distance", "concurrency", "cacheState"],
    scenarios: [
      { distance: 8, concurrency: 2, cacheState: "cold" },
      { distance: 8, concurrency: 2, cacheState: "warm" },
    ],
  });
  const summary = f.analyze();
  assert.equal(summary.complete, true);
  assert.equal(summary.cells.length, 4);
  assert.ok(summary.cells.every(cell => cell.sampleCount === 3));
});

test("declared warmup cannot be omitted or quietly counted as formal data", t => {
  const f = fixture(t);
  f.config.warmups = [{ distance: 3, concurrency: 2, repeat: -1 }];
  write(f.configFile, f.config);
  assert.ok(f.analyze().issues.every(issue => /Warmup.*coverage/.test(issue.message)));
});

test("legacy preceding tick is counted explicitly without changing the declared tick window", t => {
  const f = fixture(t);
  for (let i = 0; i < f.config.runs.length; i++) {
    f.change(i, "performance-comparison.json", data => { data.trials[0].serverSubmitTick = 0; });
    f.change(i, "full-ticks.json", data => {
      const originalStart = data.samples[0].startNanos;
      data.samples.unshift({ serverTick: 0, startNanos: originalStart - 5000, endNanos: originalStart - 1000, fullNanos: 4000 });
    });
  }
  const summary = f.analyze();
  assert.equal(summary.complete, true);
  assert.equal(summary.rows[0].preSubmissionFullTicks, 1);
  assert.equal(summary.rows[0].fullTickCount, 3);
});

test("CLI writes reports, preserves old outputs and signals incomplete data", t => {
  const f = fixture(t, { pairs: 1 });
  const output = path.join(f.root, "output");
  const invoke = out => spawnSync(process.execPath, [path.join(toolRoot, "analyze.mjs"),
    "--config", f.configFile, "--out", out], { encoding: "utf8" });
  const good = invoke(output);
  assert.equal(good.status, 0, good.stderr);
  assert.equal(read(path.join(output, "summary.json")).complete, true);
  assert.ok(fs.existsSync(path.join(output, "trials.csv")));
  const preserved = fs.readFileSync(path.join(output, "summary.json"), "utf8");
  assert.equal(invoke(output).status, 2);
  assert.equal(fs.readFileSync(path.join(output, "summary.json"), "utf8"), preserved);
  f.change(0, "full-ticks.json", data => { data.samples.pop(); });
  const badOutput = path.join(f.root, "invalid-output");
  assert.equal(invoke(badOutput).status, 1);
  assert.equal(read(path.join(badOutput, "summary.json")).complete, false);
});
