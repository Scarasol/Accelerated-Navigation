import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { analyze, quantile } from './analyze.mjs';

export const counters = ['processCpuNanos', 'allThreadAllocatedBytes', 'mainThreadCpuNanos',
  'mainThreadAllocatedBytes', 'gcCount', 'gcMillis'];
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

export function resourceDelta(start, end) {
  for (const sample of [start, end]) {
    for (const key of ['nanoTime', ...counters])
      assert(Number.isSafeInteger(sample?.[key]) && sample[key] >= 0, `Invalid counter: ${key}`);
  }
  assert(end.nanoTime >= start.nanoTime, 'Resource timestamps go backwards');
  return Object.fromEntries(counters.map(key => {
    assert(end[key] >= start[key], `Resource counter goes backwards: ${key}`);
    return [key, end[key] - start[key]];
  }));
}

export function fixedResources(trial) {
  const samples = [trial.costStart, ...trial.costSamples, trial.costEnd];
  samples.forEach((sample, i) => resourceDelta(samples[Math.max(0, i - 1)], sample));
  const point = sample => sample ? {
    atNanos: sample.nanoTime,
    elapsedMs: (sample.nanoTime - trial.submittedNanos) / 1e6,
    ...resourceDelta(trial.costStart, sample)
  } : null;
  return [1, 5].map(seconds => {
    const deadline = trial.submittedNanos + seconds * 1e9;
    const lower = samples.filter(sample => sample.nanoTime <= deadline).at(-1);
    const upper = samples.find(sample => sample.nanoTime >= deadline);
    return { seconds, covered: Boolean(lower && upper), lower: point(lower), upper: point(upper) };
  });
}

function manifest(files) {
  return files.map(file => [file.path.replaceAll('\\', '/'), file.sha256])
    .sort(([a], [b]) => a.localeCompare(b));
}

export function checkPair(b, c) {
  for (const run of [b, c]) {
    assert(run.launch.suite === 'coverage' && run.launch.deepTrace === false &&
      run.launch.omitObservations === true && run.launch.jfr === false &&
      run.launch.native === false && run.launch.replay === false &&
      run.launch.tailChecks === false && run.launch.tailAction === 'none',
    'Expected an unmodified lightweight coverage screen');
  }
  for (const key of ['scene', 'navigationLogLevel', 'javaSha256', 'templateManifestSha256',
    'serverPropertiesSha256', 'cityWorld'])
    assert.deepEqual(b.launch[key], c.launch[key], `Pair input differs: ${key}`);
  const normalizedArgs = run => run.launch.args.map(arg => arg.replaceAll(run.launch.build, '<build>')
    .replaceAll(run.directory, '<run>'));
  assert.deepEqual(normalizedArgs(b), normalizedArgs(c), 'JVM options differ');
  for (const key of ['tools', 'referenceSources'])
    assert.deepEqual(manifest(b.identity[key]), manifest(c.identity[key]), `Measurement sources differ: ${key}`);
  assert.equal(b.identity.budgetChecksSha256, c.identity.budgetChecksSha256, 'Budget checks differ');
  assert.deepEqual(b.raw.scenes, c.raw.scenes, 'Declared scenes differ');
  assert(Date.parse(b.exit.endedUtc) <= Date.parse(c.exit.startedUtc), 'Expected sequential B then C');
}

function loadRun(directory) {
  directory = path.resolve(directory);
  const launch = read(path.join(directory, 'launch.json'));
  const analysis = analyze(directory);
  assert(analysis.valid, `Public analysis rejected ${directory}: ${analysis.errors.join('; ')}`);
  const raw = read(path.join(directory, 'timeline.json'));
  const ticks = read(path.join(directory, 'full-ticks.json'));
  const identity = read(path.join(launch.build, 'build-identity.json'));
  const exit = read(path.join(directory, 'exit.json'));
  return { directory, launch, analysis, raw, ticks, identity, exit };
}

function trialResources(run, index) {
  const trial = run.raw.trials[index];
  const analysis = run.analysis.trials[index];
  const ms = time => time ? (time - trial.submittedNanos) / 1e6 : null;
  const measuredTicks = run.ticks.samples.filter(tick =>
    tick.endNanos >= trial.submittedNanos && tick.startNanos <= trial.closedNanos);
  const durations = measuredTicks.map(tick => tick.fullNanos / 1e6);
  const intervals = measuredTicks.slice(1).map((tick, i) =>
    (tick.startNanos - measuredTicks[i].startNanos) / 1e6);
  return {
    scene: trial.scene,
    elapsedMs: (trial.closedNanos - trial.submittedNanos) / 1e6,
    startSnapshotLeadMs: (trial.submittedNanos - trial.costStart.nanoTime) / 1e6,
    endSnapshotLagMs: (trial.costEnd.nanoTime - trial.closedNanos) / 1e6,
    naturalResources: resourceDelta(trial.costStart, trial.costEnd),
    fixedWindows: fixedResources(trial),
    actorCount: trial.actors.length,
    arrived: analysis.arrived,
    states: analysis.states,
    actors: trial.actors.map(actor => {
      const search = actor.search;
      for (const key of ['calls', 'wallNanos', 'cpuNanos', 'allocatedBytes', 'nullPaths',
        'partialPaths', 'reachablePaths', 'maxReturnedNodes'])
        assert(Number.isSafeInteger(search[key]) && search[key] >= 0, `Invalid search aggregate: ${key}`);
      assert.equal(search.calls, search.nullPaths + search.partialPaths + search.reachablePaths,
        'Search outcomes do not add up to calls');
      return { tile: actor.tile, slot: actor.slot, start: actor.start, goal: actor.goal,
        state: actor.state, queryIds: actor.queryIds, requestGenerations: actor.requestGenerations,
        searchAggregate: search, firstReadyMs: ms(actor.firstReadyNanos),
        firstMoveMs: ms(actor.firstMoveNanos), arrivalMs: ms(actor.arrivalNanos),
        intentAccuracyArrivalMs: ms(actor.intentAccuracyArrivalNanos),
        terminalMs: ms(actor.terminalNanos) };
    }),
    ticks: { count: measuredTicks.length, p99Ms: quantile(durations, .99),
      maxMs: Math.max(...durations), over50: durations.filter(value => value > 50).length,
      excessOver50Ms: durations.reduce((sum, value) => sum + Math.max(0, value - 50), 0),
      startIntervalP99Ms: quantile(intervals, .99), startIntervalMaxMs: Math.max(...intervals) }
  };
}

export function compareResources(bDirectory, cDirectory) {
  const b = loadRun(bDirectory), c = loadRun(cDirectory);
  checkPair(b, c);
  const sourceB = new Map(manifest(b.identity.productionSources));
  const sourceC = new Map(manifest(c.identity.productionSources));
  assert.deepEqual([...sourceB.keys()], [...sourceC.keys()], 'Production input file set differs');
  const sourceChanges = [...sourceB].filter(([name, hash]) => sourceC.get(name) !== hash)
    .map(([name, hash]) => ({ path: name, baselineSha256: hash, candidateSha256: sourceC.get(name) }));
  const describe = run => ({ directory: run.directory,
    productionJarSha256: run.identity.productionJarSha256,
    buildIdentitySha256: run.launch.buildIdentitySha256, startedUtc: run.exit.startedUtc,
    endedUtc: run.exit.endedUtc, pid: run.exit.pid,
    sources: ['launch.json', 'exit.json', 'timeline.json', 'full-ticks.json', 'shutdown.json']
      .map(name => ({ name, sha256: sha(path.join(run.directory, name)) })) });
  const trials = b.raw.trials.map((trial, i) => {
    const baseline = trialResources(b, i), candidate = trialResources(c, i);
    assert.deepEqual(baseline.scene, candidate.scene, 'Trial order differs');
    assert.deepEqual(baseline.actors.map(a => [a.tile, a.slot, a.start, a.goal]),
      candidate.actors.map(a => [a.tile, a.slot, a.start, a.goal]), 'Actor routes differ');
    return { scene: trial.scene.name, baseline, candidate,
      naturalDifference: Object.fromEntries(counters.map(key => {
        const before = baseline.naturalResources[key], after = candidate.naturalResources[key];
        return [key, { baseline: before, candidate: after, delta: after - before,
          percent: before > 0 ? 100 * (after / before - 1) : null }];
      })) };
  });
  return { schema: 'NAVIGATION_HANDOFF_RESOURCES_V1', technicalValid: true,
    verdict: 'SCREEN_ONLY_NO_PERFORMANCE_QUALIFICATION',
    searchScope: 'Per-actor aggregate over actual PathFinder calls, including all request generations. Per-call records are not retained.',
    cpuScope: 'Windows thread CPU can be quantized to 15.625ms; zero is not proof of zero work. No search CPU ratio is inferred.',
    windowScope: 'Natural resources use the actual start/end snapshots. Fixed windows retain bounding samples without interpolation.',
    lifecycleScope: 'The coverage end snapshot precedes stop/consume/discard and server shutdown. These costs are excluded. Scene resources include background and diagnostic work, not isolated logical-request costs.',
    measurementScope: 'Same measurement source manifests; Jar entry content identities must also be checked separately when archive hashes differ.',
    baseline: describe(b), candidate: describe(c), productionSourceChanges: sourceChanges, trials,
    analyzerSha256: sha(fileURLToPath(import.meta.url)) };
}

function markdown(report) {
  const n = value => value.toFixed(3);
  const ms = value => n(value / 1e6);
  const mib = value => n(value / 1048576);
  const table = ['# Handoff Resource Screen', '',
    'One sequential B/C pair, two controlled scenes. No stable performance or production verdict.',
    'PathFinder values are actor aggregates, not per-call records. CPU zeros are below resolution.',
    'Natural snapshots exclude subsequent stop/consume/discard and server shutdown; scene costs also include background and diagnostic work.', '',
    '| Scene | Arm | Calls | Search wall ms | Search CPU ms | Search MiB | Natural ms | Process CPU ms | All MiB | Main CPU ms | Main MiB | GC count/ms |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |'];
  for (const trial of report.trials) {
    for (const [arm, data] of [['B', trial.baseline], ['C', trial.candidate]]) {
      const s = data.actors[0].searchAggregate, r = data.naturalResources;
      table.push(`| ${trial.scene} | ${arm} | ${s.calls} | ${ms(s.wallNanos)} | ${ms(s.cpuNanos)} | ${mib(s.allocatedBytes)} | ${n(data.elapsedMs)} | ${ms(r.processCpuNanos)} | ${mib(r.allThreadAllocatedBytes)} | ${ms(r.mainThreadCpuNanos)} | ${mib(r.mainThreadAllocatedBytes)} | ${r.gcCount}/${r.gcMillis} |`);
    }
  }
  table.push('', 'Fixed windows below show actual bounding sample times and counters, not interpolated values.', '',
    '| Scene | Arm | Window s | Sample bounds ms | Process CPU bounds ms | All allocation bounds MiB | Main CPU bounds ms | Main allocation bounds MiB | GC count bounds | GC ms bounds |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const trial of report.trials) {
    for (const [arm, data] of [['B', trial.baseline], ['C', trial.candidate]]) {
      for (const window of data.fixedWindows) {
        const bound = (key, format) => [window.lower, window.upper]
          .map(sample => sample ? format(sample[key]) : 'missing').join(' .. ');
        table.push(`| ${trial.scene} | ${arm} | ${window.seconds} | ${bound('elapsedMs', n)} | ${bound('processCpuNanos', ms)} | ${bound('allThreadAllocatedBytes', mib)} | ${bound('mainThreadCpuNanos', ms)} | ${bound('mainThreadAllocatedBytes', mib)} | ${bound('gcCount', String)} | ${bound('gcMillis', String)} |`);
      }
    }
  }
  return table.join('\n') + '\n';
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [b, c, output] = process.argv.slice(2);
  assert(b && c && output && process.argv.length === 5,
    'Usage: node compare-handoff-resources.mjs B_DIRECTORY C_DIRECTORY NEW_OUTPUT_DIRECTORY');
  assert(!fs.existsSync(output), 'Preserve previous comparison');
  const report = compareResources(b, c);
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, 'resources.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  fs.writeFileSync(path.join(output, 'resources.md'), markdown(report), { flag: 'wx' });
  console.log(JSON.stringify({ technicalValid: true, verdict: report.verdict,
    productionSourceChanges: report.productionSourceChanges, scenes: report.trials.map(t => t.scene) }));
}
