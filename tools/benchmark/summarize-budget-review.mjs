import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const identities = [];
const read = relative => {
  const bytes = readFileSync(resolve(workspace, relative));
  identities.push({ path: relative, sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase() });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const first = ".tmp/navigation-dynamic-budget-20261013";
const second = ".tmp/navigation-dynamic-budget-20261013-guards";
const a = read(`${first}/runtime-analysis.json`);
const b = read(`${second}/runtime-analysis.json`);
const model = read(`${first}/evidence.json`);
const traces = read(`${second}/trace-analysis.json`);
const over = read(`${first}/over50-evidence.json`);
const source = read(`${first}/tick-source.json`);
assert(a.completeFourModes);
assert.equal(a.runs.length, 4);
assert(a.runs.every(run => run.formalTrials === 18));
assert.equal(a.groups.reduce((sum, row) => sum + row.total, 0), 21504);
assert.equal(b.runs.length, 8);
assert.equal(model.checks.length, 9);
assert.equal(model.results.length, 20);
let threadAssertions = 0;
const guardRuns = [...b.runs.map(run => run.name), "trace-fixed4", "trace-dynamic12"];
for (const name of guardRuns) {
  const checks = read(`${second}/${name}/budget-thread-checks.json`);
  const exit = read(`${second}/${name}/exit.json`);
  const launch = read(`${second}/${name}/launch-environment.json`);
  assert(checks.passed && checks.checks.length === 2);
  assert(exit.exitCode === 0 && !exit.timedOut);
  assert.equal(launch.mods.find(mod => mod.name === "accelerated_navigation.jar").sha256,
    a.runs[0].mods.find(mod => mod.name === "accelerated_navigation.jar").sha256);
  assert.equal(launch.mods.find(mod => mod.name === "production-probe.jar").sha256,
    a.runs[0].mods.find(mod => mod.name === "production-probe.jar").sha256);
  threadAssertions += checks.checks.length;
}
const compact = row => {
  const { comparisons, ...values } = row;
  return { ...values, readyPairs: comparisons.reduce((sum, trial) => sum + trial.readyPairs, 0),
    arrivalPairs: comparisons.reduce((sum, trial) => sum + trial.arrivalPairs, 0),
    accepted: comparisons.reduce((sum, trial) => sum + trial.accepted, 0),
    terminalHistograms: comparisons.map(trial => ({ repeat: trial.repeat, histogram: trial.terminalHistogram })),
    pairedFailures: comparisons.map(trial => ({ repeat: trial.repeat, actors: trial.newFailures })) };
};
const result = {
  schema: "DYNAMIC_BUDGET_REVIEW_LEDGER_V1",
  scope: "research prototype; not a normative design or production acceptance",
  assertions: { fourModesComplete: true, formalTrials: 72, formalRequests: 21504,
    initialFullTickSamples: a.runs.reduce((sum, run) => sum + run.sampleCount, 0),
    modelBoundaryChecks: 9, syntheticScenarios: 20, syntheticVariants: 80,
    secondVersionLoadRuns: 8, secondVersionTraceRuns: 2, rejectedOffThreadEntries: threadAssertions,
    tracePairedRegressions: traces.pairs.length },
  limitations: ["two formal repeats; one per extra load", "fixed mode run order",
    "no complete lifecycle or integrated-server acceptance", "new timing guards also in fixed4",
    "cold topology and continuous runtime load not tested", "per-stage ready queue vs dependency wait not fully split",
    "V1 performance cannot certify final V2 code; V2 load and trace evidence separate",
    "harness collection-end output causes late-tail outliers"],
  versionOneRuns: a.runs, versionOneResults: a.groups.map(compact),
  versionTwoLoadRuns: b.runs, versionTwoLoadResults: b.groups.map(compact),
  modelSummary: model.results.map(({ name, summary }) => ({ name, summary })),
  pinnedTickSource: source,
  initialOver50Events: over,
  diagnosticTraces: traces.pairs.map(row => ({
    ...row,
    candidateSearches: row.candidateSearches.map(({ nodes, ...search }) => search),
    baselineSearches: row.baselineSearches.map(({ nodes, ...search }) => search)
  })),
  inputIdentities: identities
};
writeFileSync(resolve(workspace, first, "review-ledger.json"), JSON.stringify(result, null, 2) + "\n");
const selectedFields = ["mode", "addedLoadMs", "distance", "concurrency", "repeats",
  "readyP95Ms", "pairedBaselineReadyP95Ms", "readyP95ChangePercent",
  "arrivalP50Ms", "pairedBaselineArrivalP50Ms", "arrivalP50ChangePercent",
  "batchSubmitMs", "batchSubmitChangePercent", "fullWorkP95Ms", "fullWorkP99Ms",
  "activeWorkP95Ms", "activeWorkP99Ms", "activeWorkMaxMs", "activeOver50",
  "successful", "originalSuccessful", "total", "newFailures", "recoveredSuccesses",
  "mainAllocatedChangePercent", "peakHeapChangePercent", "nativeWallChangePercent",
  "nativeCallsChangePercent", "navigationChargedChangePercent", "observedTps",
  "grantP50Ms", "grantP95Ms", "controllerEntryP95Ms"];
const reduce = row => Object.fromEntries(selectedFields.map(field => [field, row[field]]));
const brief = { ...result,
  versionOneResults: a.groups.map(reduce),
  versionTwoLoadResults: b.groups.map(row => ({ ...reduce(row),
    terminalHistograms: row.comparisons.map(trial => trial.terminalHistogram) })),
  modelSummary: result.modelSummary.filter(row =>
    ["stable-10ms-512", "stable-30ms-512", "stable-40ms-512", "stable-48ms-512",
      "continuous-16-per-tick-512", "dependency-arrivals-512", "unexpected-tail-512",
      "long-atomic-512"].includes(row.name))
};
writeFileSync(resolve(workspace, first, "review-brief.json"), JSON.stringify(brief, null, 2) + "\n");
console.log(JSON.stringify(result.assertions, null, 2));
