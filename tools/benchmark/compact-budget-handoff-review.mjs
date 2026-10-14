import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const root = ".tmp/navigation-budget-handoff-20261014-grace2";
const bytes = readFileSync(`${root}/review-evidence.json`);
const full = JSON.parse(bytes);
const flags = e => e ? {
  event: e.event, tick: e.tick, doneSince: e.doneSince, result: e.result,
  position: e.position, block: e.block, velocity: e.velocity,
  last: e.last, nativeStopped: e.nativeStopped, segmentReachable: e.segmentReachable,
  windowCurrent: e.windowCurrent, proofCurrent: e.proofCurrent,
  contains: e.containsCurrentBlock, entry: e.entryPresent, pathDone: e.pathDone
} : null;
const result = {
  schema: "COMPACT_HANDOFF_REVIEW_EVIDENCE_V1",
  sourcePath: `${root}/review-evidence.json`,
  sourceSha256: createHash("sha256").update(bytes).digest("hex").toUpperCase(),
  fullStudyPath: full.fullStudyPath,
  fullStudySha256: full.fullStudySha256,
  summary: full.summary,
  verificationScope: {
    firstNativeInputsAndNodes: "Only the four watched routes, two requests per distance/trial; not all 512 actors.",
    startsAndGoals: "All paired actors in all formal trials.",
    modesCompared: "Same frozen jar within each batch; older versions not pooled.",
    preservation: "Real nodes, partial and EXHAUSTED retained; no whole movement fallback.",
    inputReplay: "Not run; exact internal cause of the final partial remains open.",
    threadChecks: 18,
    formalRequests: 16384
  },
  batches: full.batches.map(b => ({
    root: b.root, analysisInput: b.analysisInput, comparisons: b.comparisons,
    runs: b.runs.map(r => ({
      name: r.name, prototypeSha256: r.launch.mods.find(m => m.name === "budget-prototype.jar").sha256,
      mode: r.launch.mode, handoff: r.launch.handoff ?? "unchanged",
      totals: r.totals, trials: r.trials,
      watchedColumns: ["distance", "repeat", "actor", "state", "nativeCalls", "nativeStops",
        "firstArrivalResult", "firstArrivalWindowCurrent", "firstArrivalProofCurrent", "firstArrivalHasEntry"],
      watchedRows: r.routes.map(t => [t.distance, t.repeat, t.actor, t.state, t.calls, t.nativeStops,
        t.firstArrival?.result, t.firstArrival?.windowCurrent, t.firstArrival?.proofCurrent, t.firstArrival?.entryPresent])
    }))
  })),
  representativeTimelines: full.details.slice(0, 5).map(d => ({
    batch: d.batch, run: d.run, actor: d.actor, distance: d.distance,
    firstDone: flags(d.timeline.firstDone),
    body: flags(d.timeline.firstBodyDecision),
    firstArrival: flags(d.timeline.firstArrivalDecision),
    nextObservations: d.timeline.nextObservations.map(flags),
    graces: d.timeline.graces.map(flags),
    recoveries: d.timeline.recoveries.map(e => ({ ...flags(e), source: e.stack
      .find(line => line.includes("lambda$settle")) })),
    searches: d.timeline.searches
  })),
  noTraceSensitivity: {
    runs: full.sensitivity.runs.map(r => ({ name: r.name, trace: r.launch.trace, trials: r.trials })),
    newFailures: full.sensitivity.newFailures, newSuccesses: full.sensitivity.newSuccesses,
    repetitions: 1
  },
  limitations: full.limitations
};
writeFileSync(`${root}/compact-review-evidence.json`, JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify({ bytes: Buffer.byteLength(JSON.stringify(result)) }));
