import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resourceDelta, fixedResources, checkPair } from './compare-handoff-resources.mjs';

const sample = (nanoTime, value) => ({ nanoTime, processCpuNanos: value,
  allThreadAllocatedBytes: value * 10, mainThreadCpuNanos: value,
  mainThreadAllocatedBytes: value * 5, gcCount: 0, gcMillis: 0 });

test('fixed windows retain real bounds and all resource counters', () => {
  const trial = { submittedNanos: 1e9, costStart: sample(1e9 - 100, 100),
    costSamples: [sample(1.9e9, 200), sample(2.15e9, 250), sample(5.9e9, 400)],
    costEnd: sample(6.05e9, 450) };
  const [one, five] = fixedResources(trial);
  assert.equal(one.lower.elapsedMs, 900);
  assert.equal(one.upper.elapsedMs, 1150);
  assert.equal(one.lower.allThreadAllocatedBytes, 1000);
  assert.equal(one.upper.mainThreadAllocatedBytes, 750);
  assert.equal(one.upper.mainThreadCpuNanos, 150);
  assert.equal(five.lower.elapsedMs, 4900);
  assert.equal(five.upper.elapsedMs, 5050);
  assert.equal(five.covered, true);
});

test('natural early completion is not a measured five second window', () => {
  const trial = { submittedNanos: 1e9, costStart: sample(1e9, 100),
    costSamples: [sample(2e9, 200)], costEnd: sample(3e9, 300) };
  const [one, five] = fixedResources(trial);
  assert.equal(one.lower.atNanos, one.upper.atNanos);
  assert.equal(five.covered, false);
  assert.equal(five.upper, null);
});

test('missing, backwards or out of order resource samples are rejected', () => {
  assert.throws(() => resourceDelta(sample(1, 100), { ...sample(2, 200), mainThreadCpuNanos: -1 }));
  assert.throws(() => resourceDelta(sample(1, 100), sample(2, 99)));
  assert.throws(() => resourceDelta(sample(3, 100), sample(2, 200)));
  assert.throws(() => resourceDelta(sample(1, 100), { ...sample(2, 200), gcMillis: undefined }));
  assert.throws(() => fixedResources({ submittedNanos: 0, costStart: sample(0, 0),
    costSamples: [sample(2, 2), sample(1, 1)], costEnd: sample(3, 3) }));
});

const run = (directory, build, startedUtc, endedUtc) => ({
  directory, launch: { suite: 'coverage', deepTrace: false, omitObservations: true, jfr: false,
    native: false, replay: false, tailChecks: false, tailAction: 'none', scene: 'entity-stationary',
    navigationLogLevel: 'DEBUG', javaSha256: 'java', templateManifestSha256: 'world',
    serverPropertiesSha256: 'properties', cityWorld: false, build,
    args: [`-javaagent:${build}/agent.jar=${build}`, `-Doutput=${directory}`, '-Xmx2048m'] },
  identity: { tools: [{ path: 'tool.java', sha256: 'tool' }],
    referenceSources: [{ path: 'measure.java', sha256: 'measure' }], budgetChecksSha256: 'budget' },
  raw: { scenes: ['entity-stationary'] }, exit: { startedUtc, endedUtc }
});
const pair = () => [
  run('D:\\b', 'D:\\build-b', '2026-10-17T01:00:00Z', '2026-10-17T01:02:00Z'),
  run('D:\\c', 'D:\\build-c', '2026-10-17T02:00:00Z', '2026-10-17T02:02:00Z')
];

test('equivalent options normalize only run and build locations', () => {
  const [b, c] = pair();
  assert.doesNotThrow(() => checkPair(b, c));
  c.launch.args.push('-XX:StartFlightRecording=profile');
  assert.throws(() => checkPair(b, c), /JVM options differ/);
});

test('measurement changes, scene changes and nonsequential arms cannot pass', () => {
  for (const alter of [
    c => { c.identity.tools[0].sha256 = 'changed'; },
    c => { c.identity.referenceSources[0].sha256 = 'changed'; },
    c => { c.launch.scene = 'query-pending-adopt-multi'; },
    c => { c.launch.tailAction = 'cancel'; },
    c => { c.exit.startedUtc = '2026-10-17T01:01:00Z'; }
  ]) {
    const [b, c] = pair();
    alter(c);
    assert.throws(() => checkPair(b, c));
  }
});
