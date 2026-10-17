import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { analyze } from "./analyze.mjs";

export function readyDispatches(events, phases) {
  const name = event => phases[event.phase] ?? "";
  const starts = (event, prefix) => name(event).startsWith(prefix);
  const selected = prefix => events.filter(event => starts(event, prefix));
  const requests = selected("NavigationScheduler.requestReadyDispatch(");
  const ready = selected("NavigationScheduler.drainReady(");
  const rows = new Map(ready.map(event => [event, {
    tick: event.tick, start: event.start, end: event.end,
    exclusiveNanos: event.exclusive, inclusiveNanos: event.end - event.start,
    schedulerShellNanos: event.exclusive, queueDrains: 0, operationCallbacks: 0,
    nextSegments: 0, segmentSearches: 0, nativeSearches: 0,
  }]));
  const threads = Map.groupBy(events, event => event.thread);
  for (const thread of threads.values()) {
    const stack = [];
    for (const event of thread.toSorted((a, b) => a.start - b.start || a.depth - b.depth)) {
      while (stack.length && stack.at(-1).depth >= event.depth) stack.pop();
      const parent = stack.findLast(candidate => rows.has(candidate));
      if (parent) {
        assert(parent.start <= event.start && parent.end >= event.end, "Invalid ready ancestry");
        const row = rows.get(parent);
        if (starts(event, "NavigationScheduler.drain(")) {
          row.queueDrains++;
          row.schedulerShellNanos += event.exclusive;
        }
        if (/^SegmentedNavigationService\.lambda\$queue\$\d+\(.*Ljava\/util\/function\/Supplier;\)Ljava\/lang\/Object;$/.test(name(event)))
          row.operationCallbacks++;
        if (starts(event, "SegmentedNavigationService.nextSegment(")) row.nextSegments++;
        if (starts(event, "SegmentedNavigationService.findSegment(")) row.segmentSearches++;
        if (starts(event, "PathFinder.")) row.nativeSearches++;
      }
      stack.push(event);
    }
  }
  const dispatches = [...rows.values()];
  const sum = (items, key) => items.reduce((total, item) => total + item[key], 0);
  return {
    queueReadyCalls: selected("SegmentedNavigationService.queueReady(").length,
    requestCalls: requests.length,
    readyCalls: dispatches.length,
    enteredQueueDrains: dispatches.filter(row => row.queueDrains > 0).length,
    skippedBeforeDrain: dispatches.filter(row => row.queueDrains === 0).length,
    withOperationCallbacks: dispatches.filter(row => row.operationCallbacks > 0).length,
    withoutOperationCallbacks: dispatches.filter(row => row.operationCallbacks === 0).length,
    withNativeSearches: dispatches.filter(row => row.nativeSearches > 0).length,
    operationCallbacks: sum(dispatches, "operationCallbacks"),
    segmentSearches: sum(dispatches, "segmentSearches"),
    nativeSearches: sum(dispatches, "nativeSearches"),
    requestExclusiveNanos: sum(requests, "exclusive"),
    readySchedulerShellNanos: sum(dispatches, "schedulerShellNanos"),
    readyInclusiveNanos: sum(dispatches, "inclusiveNanos"),
    dispatches,
  };
}

function main() {
  const [directory, output] = process.argv.slice(2);
  assert(directory && output, "Usage: node analyze-ready.mjs RUN NEW_OUTPUT.json");
  assert(!fs.existsSync(output), "Preserve previous output");
  const validated = analyze(directory);
  assert(validated.valid && validated.deepTrace, validated.errors.join("; ") || "Deep trace required");
  const phases = validated.phases;
  const [header, ...lines] = fs.readFileSync(path.join(directory, "events.tsv"), "utf8").trimEnd().split(/\r?\n/);
  const keys = header.split("\t");
  const events = lines.map(line => Object.fromEntries(line.split("\t").map((value, index) => [keys[index], Number(value)])));
  const trials = validated.trials.map(trial => ({
    scene: trial.scene,
    ...readyDispatches(events.filter(event => event.trial === trial.trial), phases),
  }));
  const result = {
    schema: "NAVIGATION_READY_DISPATCH_ANALYSIS_V1", valid: true, diagnosticOnly: true,
    timingsAreWallTimeWithInstrumentation: true, incrementalCpuNotMeasured: true,
    noNativeSearchDoesNotImplyNoUsefulWork: true,
    productionSha256: JSON.parse(fs.readFileSync(path.join(directory, "launch.json"), "utf8").replace(/^\uFEFF/, ""))
      .artifacts.find(artifact => artifact.name === "production.jar").sha256,
    sources: validated.sources,
    analyzerSha256: crypto.createHash("sha256").update(fs.readFileSync(fileURLToPath(import.meta.url))).digest("hex"),
    trials,
  };
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + "\n", { flag: "wx" });
  console.log(JSON.stringify(trials.map(({ dispatches, ...trial }) => trial), null, 2));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
