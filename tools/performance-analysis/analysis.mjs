import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { fileURLToPath } from "node:url";

const hash = bytes => crypto.createHash("sha256").update(bytes).digest("hex");
const same = isDeepStrictEqual;
const sum = values => values.reduce((total, value) => total + value, 0);
const own = (value, key) => Object.hasOwn(value, key);
const requireValue = (condition, message) => { if (!condition) throw Error(message); };
const object = (value, name) => requireValue(value !== null && typeof value === "object"
  && !Array.isArray(value), `${name} must be an object`);
const text = (value, name) => requireValue(typeof value === "string" && value.trim().length > 0,
  `${name} must be a nonempty string`);
const number = (value, name, minimum = 0) => requireValue(Number.isSafeInteger(value) && value >= minimum,
  `${name} must be a safe integer >= ${minimum}`);
const boolean = (value, name) => requireValue(typeof value === "boolean", `${name} must be boolean`);
const array = (value, name) => requireValue(Array.isArray(value), `${name} must be an array`);
const digest = (value, name) => requireValue(typeof value === "string" && /^[a-f0-9]{64}$/.test(value),
  `${name} must be a lowercase SHA256`);
const unique = (values, name) => requireValue(new Set(values).size === values.length, `${name} contains duplicates`);
const readJson = file => JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
const scenarioKey = (config, trial) => JSON.stringify(config.scenarioFields.map(field => trial[field]));
const trialKey = (config, trial) => JSON.stringify([scenarioKey(config, trial), trial.repeat]);
const actorKey = actor => JSON.stringify([actor.tile, actor.slot, actor.rotation]);
const route = actor => [actor.tile, actor.slot, actor.rotation, actor.routeKind, actor.start, actor.goal];

export function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(sorted.length * quantile) - 1];
}

export function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function stats(values) {
  const present = values.filter(value => typeof value === "number" && Number.isFinite(value));
  return { median: median(present), min: present.length ? Math.min(...present) : null,
    max: present.length ? Math.max(...present) : null, count: present.length, missingCount: values.length - present.length };
}

export function loadConfig(filename) {
  const configPath = path.resolve(filename);
  const raw = readJson(configPath);
  object(raw, "config");
  requireValue(raw.schema === "NAVIGATION_BENCHMARK_CAMPAIGN_V1", "Unsupported campaign schema");
  text(raw.name, "name");
  text(raw.runsRoot, "runsRoot");
  const config = { ...raw, configPath, configSha256: hash(fs.readFileSync(configPath)),
    runsRoot: path.resolve(path.dirname(configPath), raw.runsRoot),
    scenarioFields: raw.scenarioFields ?? ["distance", "concurrency"],
    warmups: raw.warmups ?? [], deadlineMillis: raw.deadlineMillis ?? [] };
  array(config.scenarioFields, "scenarioFields");
  config.scenarioFields.forEach(field => text(field, "scenario field"));
  unique(config.scenarioFields, "scenarioFields");
  for (const field of ["distance", "concurrency"])
    requireValue(config.scenarioFields.includes(field), `scenarioFields must include ${field}`);
  requireValue(!config.scenarioFields.some(field => ["variant", "repeat", "warmup"].includes(field)),
    "variant/repeat/warmup cannot be scenario dimensions");
  array(config.scenarios, "scenarios");
  requireValue(config.scenarios.length > 0, "scenarios cannot be empty");
  const checkScenario = (scenario, name) => {
    object(scenario, name);
    for (const field of config.scenarioFields) {
      requireValue(own(scenario, field), `${name} missing ${field}`);
      requireValue(["string", "number", "boolean"].includes(typeof scenario[field])
        && (typeof scenario[field] !== "number" || Number.isFinite(scenario[field])), `${name}.${field} must be scalar`);
    }
    number(scenario.distance, `${name}.distance`, 1);
    number(scenario.concurrency, `${name}.concurrency`, 1);
  };
  config.scenarios.forEach(scenario => checkScenario(scenario, "scenario"));
  unique(config.scenarios.map(scenario => scenarioKey(config, scenario)), "scenarios");
  array(config.repeats, "repeats");
  requireValue(config.repeats.length > 0, "repeats cannot be empty");
  config.repeats.forEach(repeat => number(repeat, "repeat"));
  unique(config.repeats, "repeats");
  array(config.warmups, "warmups");
  config.warmups.forEach(warmup => {
    checkScenario(warmup, "warmup");
    number(warmup.repeat, "warmup.repeat", Number.MIN_SAFE_INTEGER);
  });
  unique(config.warmups.map(warmup => trialKey(config, warmup)), "warmups");
  array(config.deadlineMillis, "deadlineMillis");
  config.deadlineMillis.forEach(deadline => number(deadline, "deadlineMillis", 1));
  unique(config.deadlineMillis, "deadlineMillis");
  requireValue(config.deadlineMillis.every(value => value <= Number.MAX_SAFE_INTEGER / 1e6),
    "deadlineMillis exceeds nanosecond integer precision");
  array(config.variants, "variants");
  requireValue(config.variants.length > 0, "variants cannot be empty");
  config.variants.forEach(variant => {
    object(variant, "variant");
    for (const field of ["id", "resultVariant", "launchVariant"]) text(variant[field], `variant.${field}`);
    digest(variant.productionSha256, "variant.productionSha256");
  });
  unique(config.variants.map(variant => variant.id), "variant ids");
  config.referenceVariant ??= config.variants[0].id;
  requireValue(config.variants.some(variant => variant.id === config.referenceVariant), "Unknown referenceVariant");
  array(config.runs, "runs");
  requireValue(config.runs.length > 0, "runs cannot be empty");
  for (const run of config.runs) {
    object(run, "run");
    for (const field of ["id", "directory", "variant", "pair"]) text(run[field], `run.${field}`);
    requireValue(config.variants.some(variant => variant.id === run.variant), `Unknown run variant ${run.variant}`);
    run.launchRun ??= run.id;
    text(run.launchRun, "run.launchRun");
    run.directory = path.resolve(config.runsRoot, run.directory);
  }
  unique(config.runs.map(run => run.id), "run ids");
  unique(config.runs.map(run => run.directory), "run directories");
  unique(config.runs.map(run => JSON.stringify([run.pair, run.variant])), "pair/variant");
  for (const pair of new Set(config.runs.map(run => run.pair)))
    requireValue(config.variants.every(variant => config.runs.some(run => run.pair === pair && run.variant === variant.id)),
      `Pair ${pair} must declare every variant exactly once`);
  object(config.identity, "identity");
  for (const field of ["productionMod", "probeMod"]) text(config.identity[field], `identity.${field}`);
  requireValue(config.identity.productionMod !== config.identity.probeMod, "Production and probe mod names must differ");
  for (const field of ["probeSha256", "templateSha256"]) digest(config.identity[field], `identity.${field}`);
  config.identity.ignoredJvmArgPrefixes ??= [];
  array(config.identity.ignoredJvmArgPrefixes, "identity.ignoredJvmArgPrefixes");
  config.identity.ignoredJvmArgPrefixes.forEach(prefix => text(prefix, "ignored JVM argument prefix"));
  number(config.minimumReadySeconds, "minimumReadySeconds");
  requireValue(config.minimumReadySeconds <= Number.MAX_SAFE_INTEGER / 1e9,
    "minimumReadySeconds exceeds nanosecond integer precision");
  return config;
}

const resultIdentityFields = ["schema", "repeats", "timeoutTicks", "postTerminalObservationTicks",
  "speedAmplifier", "addedLoadNanos", "submissionSpanTicks", "entity", "seed", "javaVersion",
  "availableProcessors", "maxHeapBytes", "routeKinds"];
const launchIdentityFields = ["java", "forgeArgs", "os", "processors", "cpu"];
const trialCounters = ["processCpuNanos", "allThreadAllocatedBytes", "mainThreadCpuNanos",
  "mainThreadAllocatedBytes", "gcCount", "gcTimeMillis", "nativeCallsDelta", "segmentCallsDelta",
  "wholeCallsDelta", "serviceFailuresDelta", "recoveriesDelta", "chargedNanosDelta", "batchSubmitNanos"];
const searchCounters = ["calls", "wallNanos", "cpuNanos", "allocatedBytes", "nullPaths", "partialPaths", "reachablePaths"];
const costCounters = { processCpuNanos: "processCpuNanos", allThreadAllocatedBytes: "allThreadAllocatedBytes",
  mainThreadCpuNanos: "mainThreadCpuNanos", mainThreadAllocatedBytes: "mainThreadAllocatedBytes",
  gcCount: "gcCount", gcTimeMillis: "gcMillis" };
const metrics = ["successRate", "physicalArrivalRate", "readyRate", "measurementWindowNanos",
  "allReadyNanos", "allPhysicalArrivalNanos", "lastObservedArrivalNanos",
  "conditionalArrivalP50Nanos", "conditionalArrivalP95Nanos",
  "conditionalReadyP50Nanos", "conditionalReadyP95Nanos", ...trialCounters,
  "searchCalls", "searchWallNanos", "searchCpuNanos", "searchAllocatedBytes",
  "searchNullPaths", "searchPartialPaths", "searchReachablePaths",
  "fullTickP95Nanos", "fullTickP99Nanos", "fullTickMaxNanos", "fullTickTotalNanos",
  "over50msTickRate", "over50msExcessNanos"];
const conditionalMetrics = new Set(["allReadyNanos", "allPhysicalArrivalNanos", "lastObservedArrivalNanos",
  "conditionalArrivalP50Nanos", "conditionalArrivalP95Nanos",
  "conditionalReadyP50Nanos", "conditionalReadyP95Nanos"]);

function validateTicks(ticks) {
  requireValue(ticks.schema === "REFERENCE_FULL_TICK_V1", "Unsupported full-tick schema");
  requireValue(ticks.truncated === false && ticks.incomplete === 0, "Truncated or incomplete full-tick recording");
  array(ticks.samples, "ticks.samples");
  const index = new Map();
  let previous = null;
  for (const sample of ticks.samples) {
    for (const field of ["serverTick", "startNanos", "endNanos", "fullNanos"]) number(sample[field], `tick.${field}`);
    requireValue(!index.has(sample.serverTick), `Duplicate server tick ${sample.serverTick}`);
    requireValue(sample.endNanos - sample.startNanos === sample.fullNanos, "Tick duration/timestamps disagree");
    if (previous) {
      requireValue(sample.serverTick > previous.serverTick, "Full ticks out of order");
      requireValue(sample.startNanos >= previous.endNanos, "Full ticks overlap or clock moved backwards");
    }
    index.set(sample.serverTick, sample);
    previous = sample;
  }
  return index;
}

function validateActor(actor, trial) {
  object(actor, "actor");
  for (const field of ["tile", "slot", "rotation"]) number(actor[field], `actor.${field}`);
  text(actor.routeKind, "actor.routeKind");
  text(actor.state, "actor.state");
  for (const field of ["start", "goal"]) {
    array(actor[field], `actor.${field}`);
    requireValue(actor[field].length === 3 && actor[field].every(Number.isSafeInteger), `Invalid actor.${field}`);
  }
  boolean(actor.success, "actor.success");
  boolean(actor.accepted, "actor.accepted");
  for (const field of ["submitStartNanos", "submitEndNanos", "firstReadyNanos", "physicalArrivalNanos", "finishNanos"])
    number(actor[field], `actor.${field}`);
  requireValue(actor.submitStartNanos >= trial.batchStartNanos
    && actor.submitEndNanos >= actor.submitStartNanos
    && actor.submitEndNanos <= trial.measurementEndNanos, "Invalid actor submission interval");
  for (const [absolute, relative] of [
    ["firstReadyNanos", "batchToReadyNanos"], ["physicalArrivalNanos", "batchToPhysicalArrivalNanos"],
  ]) {
    number(actor[relative], `actor.${relative}`, -1);
    requireValue(actor[absolute] === 0 ? actor[relative] === -1
      : actor[absolute] >= actor.submitStartNanos && actor[absolute] <= trial.measurementEndNanos
        && actor[absolute] - trial.batchStartNanos === actor[relative], `${absolute}/${relative} disagree`);
  }
  requireValue(actor.finishNanos >= actor.submitStartNanos && actor.finishNanos <= trial.measurementEndNanos,
    "Invalid actor finish timestamp");
  requireValue(!actor.success || actor.physicalArrivalNanos > 0 && actor.firstReadyNanos > 0,
    "Successful actor has no physical arrival or real Path");
  object(actor.nativeSearch, "actor.nativeSearch");
  for (const field of searchCounters) number(actor.nativeSearch[field], `nativeSearch.${field}`);
  requireValue(actor.nativeSearch.calls === actor.nativeSearch.nullPaths
    + actor.nativeSearch.partialPaths + actor.nativeSearch.reachablePaths, "Native search outcome counts disagree");
  if (own(actor, "firstObservedPathNanos")) {
    number(actor.firstObservedPathNanos, "actor.firstObservedPathNanos");
    requireValue(actor.firstObservedPathNanos === 0 || actor.firstReadyNanos > 0,
      "Observed nonempty Path without a publication timestamp");
  }
}

function normalizeTrial(config, run, trial, result, ticks) {
  object(trial, "trial");
  const variant = config.variants.find(item => item.id === run.variant);
  requireValue(trial.variant === variant.resultVariant, "Trial variant disagrees with configured run");
  boolean(trial.warmup, "trial.warmup");
  boolean(trial.timedOut, "trial.timedOut");
  number(trial.repeat, "trial.repeat", Number.MIN_SAFE_INTEGER);
  for (const field of config.scenarioFields) requireValue(own(trial, field), `Trial missing scenario dimension ${field}`);
  for (const field of ["serverStartedNanos", "sceneReadyNanos", "batchStartNanos", "measurementEndNanos",
    "batchFinishNanos", "serverSubmitTick", "serverEndTick", "totalActors", "successfulActors",
    "physicalArrivals", "pathReadyActors", "rejectedActors", ...trialCounters]) number(trial[field], `trial.${field}`);
  requireValue(trial.serverStartedNanos === result.serverStartedNanos, "Trial/server start timestamp mismatch");
  requireValue(trial.batchStartNanos - trial.serverStartedNanos >= config.minimumReadySeconds * 1e9
    && trial.batchStartNanos - trial.sceneReadyNanos >= config.minimumReadySeconds * 1e9, "Ready-time gate failed");
  requireValue(trial.sceneReadyNanos >= trial.serverStartedNanos, "Scene ready precedes server start");
  requireValue(trial.measurementEndNanos > trial.batchStartNanos
    && trial.measurementEndNanos - trial.batchStartNanos === trial.batchFinishNanos, "Measurement window/timestamps disagree");
  requireValue(trial.batchSubmitNanos <= trial.batchFinishNanos, "Submission exceeds measurement window");
  object(trial.costStart, "trial.costStart");
  object(trial.costEnd, "trial.costEnd");
  for (const field of ["nanoTime", ...Object.values(costCounters)]) {
    number(trial.costStart[field], `costStart.${field}`);
    number(trial.costEnd[field], `costEnd.${field}`);
  }
  requireValue(trial.costStart.nanoTime <= trial.batchStartNanos
    && trial.costEnd.nanoTime >= trial.measurementEndNanos, "Cost snapshot interval does not cover measurement");
  for (const [reported, source] of Object.entries(costCounters))
    requireValue(trial[reported] === trial.costEnd[source] - trial.costStart[source],
      `${reported} disagrees with cost snapshots`);
  array(trial.actors, "trial.actors");
  requireValue(trial.actors.length > 0 && trial.actors.length === trial.concurrency
    && trial.actors.length === trial.totalActors, "Actor count disagrees with concurrency/totalActors");
  trial.actors.forEach(actor => validateActor(actor, trial));
  unique(trial.actors.map(actorKey), "Actor identities");
  const ready = trial.actors.filter(actor => actor.firstReadyNanos > 0);
  const arrived = trial.actors.filter(actor => actor.physicalArrivalNanos > 0);
  const success = trial.actors.filter(actor => actor.success);
  for (const [field, actual] of [
    ["pathReadyActors", ready.length], ["physicalArrivals", arrived.length],
    ["successfulActors", success.length], ["rejectedActors", trial.actors.filter(actor => !actor.accepted).length],
  ]) requireValue(trial[field] === actual, `${field} counter disagrees with actors`);
  const allReady = ready.length === trial.actors.length ? Math.max(...ready.map(actor => actor.batchToReadyNanos)) : null;
  number(trial.batchAllReadyNanos, "trial.batchAllReadyNanos", -1);
  requireValue(trial.batchAllReadyNanos === (allReady ?? -1), "batchAllReadyNanos disagrees with actors");
  number(trial.batchAllSuccessNanos, "trial.batchAllSuccessNanos", -1);
  const allSuccess = success.length === trial.actors.length
    ? Math.max(...success.map(actor => Math.max(actor.physicalArrivalNanos, actor.finishNanos))) - trial.batchStartNanos : -1;
  requireValue(trial.batchAllSuccessNanos === allSuccess, "batchAllSuccessNanos disagrees with actors");
  requireValue(trial.serverEndTick >= trial.serverSubmitTick, "Reversed trial tick interval");
  const count = trial.serverEndTick - trial.serverSubmitTick + 1;
  requireValue(count <= ticks.size, "Full-tick sample gap");
  const selected = [];
  for (let tick = trial.serverSubmitTick; tick <= trial.serverEndTick; tick++) {
    requireValue(ticks.has(tick), `Full-tick sample gap at ${tick}`);
    selected.push(ticks.get(tick));
  }
  requireValue(selected.some(sample => sample.startNanos <= trial.batchStartNanos && sample.endNanos >= trial.batchStartNanos)
    && selected.some(sample => sample.startNanos <= trial.measurementEndNanos
      && sample.endNanos >= trial.measurementEndNanos), "Trial clock does not match full-tick envelope");
  const durations = selected.map(tick => tick.fullNanos);
  const arrivalValues = arrived.map(actor => actor.batchToPhysicalArrivalNanos);
  const readyValues = ready.map(actor => actor.batchToReadyNanos);
  const lastArrival = arrivalValues.length ? Math.max(...arrivalValues) : null;
  const scenario = Object.fromEntries(config.scenarioFields.map(field => [field, trial[field]]));
  return {
    run: run.id, pair: run.pair, variant: run.variant, scenario, repeat: trial.repeat, timedOut: trial.timedOut,
    batchStartNanos: trial.batchStartNanos, measurementEndNanos: trial.measurementEndNanos,
    costStartNanos: trial.costStart.nanoTime, costEndNanos: trial.costEnd.nanoTime,
    serverSubmitTick: trial.serverSubmitTick, serverEndTick: trial.serverEndTick,
    actors: trial.actors.length, success: success.length, physicalArrivals: arrived.length, ready: ready.length,
    rejected: trial.rejectedActors,
    successRate: success.length / trial.actors.length, physicalArrivalRate: arrived.length / trial.actors.length,
    readyRate: ready.length / trial.actors.length, measurementWindowNanos: trial.batchFinishNanos,
    allReadyNanos: allReady, allPhysicalArrivalNanos: arrived.length === trial.actors.length ? lastArrival : null,
    lastObservedArrivalNanos: lastArrival,
    conditionalArrivalP50Nanos: percentile(arrivalValues, .5), conditionalArrivalP95Nanos: percentile(arrivalValues, .95),
    conditionalReadyP50Nanos: percentile(readyValues, .5), conditionalReadyP95Nanos: percentile(readyValues, .95),
    ...Object.fromEntries(trialCounters.map(field => [field, trial[field]])),
    ...Object.fromEntries(searchCounters.map(field =>
      [`search${field[0].toUpperCase()}${field.slice(1)}`, sum(trial.actors.map(actor => actor.nativeSearch[field]))])),
    fullTickCount: selected.length, fullTickP95Nanos: percentile(durations, .95), fullTickP99Nanos: percentile(durations, .99),
    fullTickMaxNanos: Math.max(...durations), fullTickTotalNanos: sum(durations),
    preSubmissionFullTicks: selected.filter(sample => sample.endNanos < trial.batchStartNanos).length,
    postObservationFullTicks: selected.filter(sample => sample.startNanos > trial.measurementEndNanos).length,
    over50msTickCount: durations.filter(duration => duration > 50e6).length,
    over50msTickRate: durations.filter(duration => duration > 50e6).length / durations.length,
    over50msExcessNanos: sum(durations.map(duration => Math.max(0, duration - 50e6))),
    states: Object.fromEntries([...new Set(trial.actors.map(actor => actor.state))].map(state =>
      [state, trial.actors.filter(actor => actor.state === state).length])),
    deadlines: config.deadlineMillis.map(milliseconds => {
      const deadline = milliseconds * 1e6;
      const readyCount = readyValues.filter(value => value <= deadline).length;
      const arrivalCount = arrivalValues.filter(value => value <= deadline).length;
      return { milliseconds, observedReady: readyCount, observedArrivals: arrivalCount,
        readyRate: trial.batchFinishNanos >= deadline || readyCount === trial.actors.length ? readyCount / trial.actors.length : null,
        physicalArrivalRate: trial.batchFinishNanos >= deadline || arrivalCount === trial.actors.length
          ? arrivalCount / trial.actors.length : null };
    }),
  };
}

function readRun(config, run, sources) {
  const read = name => {
    const file = path.join(run.directory, name);
    const bytes = fs.readFileSync(file);
    sources.push({ run: run.id, file, bytes: bytes.length, sha256: hash(bytes) });
    return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
  };
  const exit = read("exit.json");
  const launch = read("launch.json");
  const result = read("performance-comparison.json");
  const rawTicks = read("full-ticks.json");
  const shutdown = read("shutdown.json");
  for (const [name, value] of Object.entries({ exit, launch, result, rawTicks, shutdown })) object(value, name);
  requireValue(exit.exitCode === 0 && exit.timedOut === false, "Process failed or runner timed out");
  array(exit.concurrentJava, "exit.concurrentJava interference log");
  requireValue(exit.concurrentJava.length === 0, `External Java interference: ${exit.concurrentJava.length} observations`);
  number(exit.pid, "exit.pid", 1);
  requireValue(typeof exit.startedUtc === "string" && Number.isFinite(Date.parse(exit.startedUtc)), "Missing/invalid process start identity");
  requireValue(shutdown.passed === true, "Shutdown/cleanup validity failed");
  requireValue(result.schema === "CURRENT_PERFORMANCE_REFERENCE_V1", "Unsupported benchmark result schema");
  requireValue(result.complete === true && result.failed === false, "Result incomplete or probe failed");
  const variant = config.variants.find(item => item.id === run.variant);
  requireValue(result.variant === variant.resultVariant && launch.variant === variant.launchVariant, "Run/result variant identity mismatch");
  requireValue(launch.run === run.launchRun && launch.mode === "formal" && launch.diagnostic === false,
    "Launch run/mode identity mismatch or diagnostic recording");
  array(launch.mods, "launch.mods");
  unique(launch.mods.map(mod => mod.name), "launch mod names");
  for (const [name, expected] of [
    [config.identity.productionMod, variant.productionSha256], [config.identity.probeMod, config.identity.probeSha256],
  ]) requireValue(launch.mods.find(mod => mod.name === name)?.sha256 === expected, `Mod identity mismatch: ${name}`);
  requireValue(launch.templateManifestSha256 === config.identity.templateSha256, "World template identity mismatch");
  array(launch.jvmArgs, "launch.jvmArgs");
  launch.jvmArgs.forEach(argument => text(argument, "JVM argument"));
  const resultIdentity = {};
  const launchIdentity = {};
  for (const field of resultIdentityFields) {
    requireValue(own(result, field) && result[field] !== null, `Missing result identity ${field}`);
    resultIdentity[field] = result[field];
  }
  for (const field of launchIdentityFields) {
    requireValue(own(launch, field) && launch[field] !== null, `Missing launch identity ${field}`);
    launchIdentity[field] = launch[field];
  }
  requireValue(result.repeats === config.repeats.length, "Result repeats differs from declared repeat count");
  number(result.serverStartedNanos, "result.serverStartedNanos");
  array(result.routeKinds, "result.routeKinds");
  result.routeKinds.forEach(kind => text(kind, "result.routeKinds[]"));
  unique(result.routeKinds, "result.routeKinds");
  const otherMods = launch.mods.filter(mod => ![config.identity.productionMod, config.identity.probeMod].includes(mod.name))
    .sort((a, b) => a.name.localeCompare(b.name));
  const identity = {
    result: resultIdentity, launch: launchIdentity, otherMods,
    jvmArgs: launch.jvmArgs.filter(argument => !config.identity.ignoredJvmArgPrefixes.some(prefix => argument.startsWith(prefix))),
  };
  const ticks = validateTicks(rawTicks);
  array(result.trials, "result.trials");
  const expected = config.scenarios.flatMap(scenario => config.repeats.map(repeat =>
    trialKey(config, { ...scenario, repeat })));
  const expectedWarmups = config.warmups.map(warmup => trialKey(config, warmup));
  const formal = result.trials.filter(trial => trial.warmup === false);
  const warmups = result.trials.filter(trial => trial.warmup === true);
  requireValue(formal.length + warmups.length === result.trials.length, "Missing/invalid warmup flag");
  const checkCoverage = (trials, keys, name) => {
    const actual = trials.map(trial => trialKey(config, trial));
    unique(actual, `${name} cells`);
    requireValue(same([...actual].sort(), [...keys].sort()), `${name} scenario/repeat coverage mismatch`);
  };
  checkCoverage(formal, expected, "Formal");
  checkCoverage(warmups, expectedWarmups, "Warmup");
  const allRows = result.trials.map(trial => normalizeTrial(config, run, trial, result, ticks));
  requireValue(result.trials.every(trial => trial.actors.every(actor => result.routeKinds.includes(actor.routeKind))),
    "Actor routeKind is absent from result.routeKinds");
  const intervals = result.trials.map(trial => [trial.batchStartNanos, trial.measurementEndNanos]).sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < intervals.length; i++)
    requireValue(intervals[i][0] > intervals[i - 1][1], "Trial measurement windows overlap");
  const routes = new Map(formal.map(trial => [trialKey(config, trial),
    trial.actors.map(actor => ({ key: actorKey(actor), route: route(actor) }))
      .sort((a, b) => a.key.localeCompare(b.key)).map(actor => actor.route)]));
  return { run, identity, routes, directory: fs.realpathSync(run.directory),
    process: JSON.stringify([launch.os, exit.pid, new Date(exit.startedUtc).toISOString()]),
    rows: allRows.filter((row, index) => !result.trials[index].warmup) };
}

function aggregateRuns(config, rows) {
  const cells = [];
  for (const variant of config.variants) for (const scenario of config.scenarios) {
    const trials = rows.filter(row => row.variant === variant.id
      && scenarioKey(config, row.scenario) === scenarioKey(config, scenario));
    const runIds = [...new Set(trials.map(trial => trial.run))];
    const jvms = runIds.map(id => {
      const ownRows = trials.filter(row => row.run === id);
      return {
        run: id, pair: ownRows[0].pair, trialCount: ownRows.length,
        values: Object.fromEntries(metrics.map(metric => {
          const numbers = ownRows.map(row => row[metric]);
          // Missing outcomes must not disappear when repeats are reduced to one JVM sample.
          return [metric, numbers.some(value => value === null) ? null : sum(numbers) / numbers.length];
        })),
      };
    });
    for (const jvm of jvms) {
      const ownRows = trials.filter(row => row.run === jvm.run);
      for (const [rate, count] of [["successRate", "success"], ["physicalArrivalRate", "physicalArrivals"], ["readyRate", "ready"]])
        jvm.values[rate] = sum(ownRows.map(row => row[count])) / sum(ownRows.map(row => row.actors));
      jvm.values.over50msTickRate = sum(ownRows.map(row => row.over50msTickCount)) / sum(ownRows.map(row => row.fullTickCount));
    }
    const aggregated = Object.fromEntries(metrics.map(metric =>
      [metric, stats(jvms.map(jvm => jvm.values[metric]))]));
    cells.push({
      variant: variant.id, scenario, sampleCount: jvms.length, trialCount: trials.length, runs: runIds,
      timedOutTrials: trials.filter(trial => trial.timedOut).length,
      actors: sum(trials.map(trial => trial.actors)), success: sum(trials.map(trial => trial.success)),
      physicalArrivals: sum(trials.map(trial => trial.physicalArrivals)), ready: sum(trials.map(trial => trial.ready)),
      conditionalMetrics: [...conditionalMetrics], metrics: aggregated, jvms,
      deadlines: config.deadlineMillis.map(milliseconds => ({
        milliseconds,
        ...Object.fromEntries(["readyRate", "physicalArrivalRate"].map(metric => [metric, stats(runIds.map(id => {
          const values = trials.filter(trial => trial.run === id)
            .map(trial => trial.deadlines.find(deadline => deadline.milliseconds === milliseconds)[metric]);
          return values.some(value => value === null) ? null : sum(values) / values.length;
        }))])),
      })),
    });
  }
  return cells;
}

function comparisons(config, cells) {
  const result = [];
  for (const scenario of config.scenarios) for (const variant of config.variants) {
    if (variant.id === config.referenceVariant) continue;
    const reference = cells.find(cell => cell.variant === config.referenceVariant
      && scenarioKey(config, cell.scenario) === scenarioKey(config, scenario));
    const candidate = cells.find(cell => cell.variant === variant.id
      && scenarioKey(config, cell.scenario) === scenarioKey(config, scenario));
    const pairs = candidate.jvms.flatMap(jvm => {
      const baseline = reference.jvms.find(other => other.pair === jvm.pair);
      if (!baseline) return [];
      return [{
        pair: jvm.pair, run: jvm.run, referenceRun: baseline.run,
        metrics: Object.fromEntries(metrics.map(metric => {
          const current = jvm.values[metric], base = baseline.values[metric];
          return [metric, {
            difference: current === null || base === null ? null : current - base,
            relativeChange: current === null || base === null || base === 0 ? null : current / base - 1,
          }];
        })),
      }];
    });
    result.push({ variant: variant.id, referenceVariant: config.referenceVariant, scenario,
      pairCount: pairs.length, pairs,
      metrics: Object.fromEntries(metrics.map(metric => [metric, {
        difference: stats(pairs.map(pair => pair.metrics[metric].difference)),
        relativeChange: stats(pairs.map(pair => pair.metrics[metric].relativeChange)),
      }])) });
  }
  return result;
}

export function analyzeCampaign(config) {
  const issues = [], sources = [], accepted = [];
  const seenDirectories = new Set(), seenProcesses = new Set();
  for (const run of config.runs) {
    try {
      const current = readRun(config, run, sources);
      requireValue(!seenDirectories.has(current.directory), "Run directory reused through an alias");
      requireValue(!seenProcesses.has(current.process), "Same JVM process reused as an independent run");
      if (accepted.length) {
        requireValue(same(current.identity, accepted[0].identity), "Shared runtime/probe/settings identity mismatch");
        for (const [key, actors] of current.routes)
          requireValue(same(actors, accepted[0].routes.get(key)), `Paired route inputs disagree for ${key}`);
      }
      accepted.push(current);
      seenDirectories.add(current.directory);
      seenProcesses.add(current.process);
    } catch (error) {
      issues.push({ run: run.id, code: "INVALID_RUN", message: error.message });
    }
  }
  const rows = accepted.flatMap(run => run.rows);
  const cells = aggregateRuns(config, rows);
  const coverage = {
    expectedRuns: config.runs.length, acceptedRuns: accepted.length,
    expectedFormalTrials: config.runs.length * config.scenarios.length * config.repeats.length,
    acceptedFormalTrials: rows.length,
    expectedCells: config.variants.length * config.scenarios.length,
    cellsWithData: cells.filter(cell => cell.sampleCount > 0).length,
    expectedJvmSamplesPerCell: config.runs.length / config.variants.length,
    complete: accepted.length === config.runs.length,
  };
  return {
    schema: "NAVIGATION_BENCHMARK_SUMMARY_V1", generatedAtUtc: new Date().toISOString(),
    name: config.name, configPath: config.configPath, configSha256: config.configSha256,
    analyzer: ["analyze.mjs", "analysis.mjs"].map(name => {
      const file = fileURLToPath(new URL(name, import.meta.url));
      return { file, sha256: hash(fs.readFileSync(file)) };
    }),
    complete: coverage.complete && issues.length === 0,
    technicalValid: issues.length === 0, performanceVerdict: "NOT_EVALUATED", coverage,
    expectedRuns: config.runs.map(run => run.id), runs: accepted.map(item => item.run.id), issues,
    functional: { timedOutTrials: rows.filter(row => row.timedOut).length,
      actors: sum(rows.map(row => row.actors)), successfulActors: sum(rows.map(row => row.success)),
      physicalArrivals: sum(rows.map(row => row.physicalArrivals)), readyActors: sum(rows.map(row => row.ready)) },
    conventions: {
      trialPercentiles: "nearest rank over observed outcomes; conditional latency fields explicitly named",
      jvmAggregation: "pooled rates and arithmetic mean per-trial metrics within each JVM; missing outcome makes that JVM metric null",
      cellAggregation: "median/min/max across independent JVM samples; no timeout exclusion",
      comparisons: "paired JVM differences and relative changes; zero reference has null relative change",
      allPhysicalArrivalNanos: "only defined if every actor physically arrived; observation window is separate",
      deadlines: "null rate if the recording ended before deadline and not every actor reached that outcome",
      complete: "declared run/scenario/repeat coverage and technical validation; not functional/performance approval",
      fixedWindowCosts: "not inferred from cumulative endpoint counters; additional probe data required",
      fullTicks: "declared inclusive server tick interval; pre/post-observation full ticks counted separately",
    },
    sources, rows, cells, comparisons: comparisons(config, cells),
  };
}

export function toCsv(rows) {
  const columns = [...new Set(rows.flatMap(row => Object.keys(row)))];
  const escape = value => {
    const rendered = value == null ? "" : typeof value === "object" ? JSON.stringify(value) : String(value);
    return /[",\r\n]/.test(rendered) ? `"${rendered.replaceAll('"', '""')}"` : rendered;
  };
  return [columns.map(escape).join(","), ...rows.map(row => columns.map(column => escape(row[column])).join(","))].join("\n") + "\n";
}

export function toMarkdown(summary) {
  const show = value => value === null ? "NA" : Number(value.toFixed(4)).toString();
  const label = value => JSON.stringify(value).replaceAll("|", "\\|");
  return [
    `# ${summary.name.replaceAll("\n", " ")}`,
    "",
    `数据完整且技术有效：${summary.complete}；有效运行：${summary.runs.length}/${summary.expectedRuns.length}。`,
    `性能判定：${summary.performanceVerdict}。数据完整不代表功能或性能通过。`,
    `功能性超时 trial：${summary.functional.timedOutTrials}，其成功率与资源成本均保留。`,
    "",
    "同 JVM 重复 trial 的率按总分母计算，其他指标取 trial 均值，再按独立 JVM 取中位数及范围。时延为已观察结果的条件统计，缺失计数单列。",
    ...(summary.rows.some(row => row.preSubmissionFullTicks || row.postObservationFullTicks)
      ? ["旧记录声明的 tick 区间包含观测窗外完整 tick；本工具保留原区间，每行 preSubmissionFullTicks/postObservationFullTicks 标出数量。"] : []),
    "",
    "| 后端 | 场景 | JVM数 | 超时trial | 到达/总数 | 到达率中位数 | 首Path条件p50 ms | 全批到达条件 ms（JVM数） | CPU ms | 分配 MiB | 完整tick p99 ms |",
    "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ...summary.cells.map(cell => {
      const metric = (name, divisor = 1) => cell.metrics[name].median === null ? "NA" : show(cell.metrics[name].median / divisor);
      return `| ${cell.variant.replaceAll("|", "\\|")} | ${label(cell.scenario)} | ${cell.sampleCount} | ${cell.timedOutTrials}`
        + ` | ${cell.physicalArrivals}/${cell.actors} | ${metric("physicalArrivalRate")} | ${metric("conditionalReadyP50Nanos", 1e6)}`
        + ` | ${metric("allPhysicalArrivalNanos", 1e6)} (${cell.metrics.allPhysicalArrivalNanos.count}/${cell.sampleCount})`
        + ` | ${metric("processCpuNanos", 1e6)}`
        + ` | ${metric("allThreadAllocatedBytes", 2 ** 20)} | ${metric("fullTickP99Nanos", 1e6)} |`;
    }),
    "",
    "## 无效或缺失运行",
    "",
    ...(summary.issues.length ? summary.issues.map(issue => `- ${label(issue.run)}：${issue.message}`) : ["无。"]),
    "",
    "输入文件 SHA256、完整指标及计数、条件/缺失时延、配对差异见 summary.json。未测量的共同固定窗口 CPU/分配不会由端点计数插值生成。",
    "",
  ].join("\n");
}
