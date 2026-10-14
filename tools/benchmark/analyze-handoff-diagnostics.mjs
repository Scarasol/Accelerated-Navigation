import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const [runArg] = process.argv.slice(2), root = resolve(runArg);
const identities = [];
const read = file => {
  const bytes = readFileSync(resolve(root, file));
  identities.push({file, sha256:createHash("sha256").update(bytes).digest("hex")});
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const trace = read("handoff-diagnostics.json"), data = read("performance-comparison.json");
assert.equal(trace.truncated, false); assert.equal(data.failed, false); assert.equal(data.complete, true);
const watched = [...new Set(trace.searches.map(s => s.input.actor))];
const cases = [];
for (const trial of data.trials.filter(t => !t.warmup)) {
  for (const actor of watched.filter(a => a < trial.actors.length)) {
    const result = trial.actors[actor];
    const inside = row => row.actor === actor && row.distance === trial.distance
      && row.repeat === trial.repeat && (result.queryId
        ? row.query === result.queryId : row.tick >= trial.submitTick && row.tick <= trial.endTick);
    const searches = trace.searches.filter(s => inside(s.input));
    assert.equal(searches.length, result.nativeSearch.calls, "trace does not match actual native calls");
    const events = trace.events.filter(inside);
    const premature = events.filter(e => e.event === "recover-head" && !e.last
      && e.segmentReachable && !e.nativeStopped && e.windowCurrent && e.proofCurrent && !e.entryPresent && !e.contains);
    cases.push({distance:trial.distance, concurrency:trial.concurrency, repeat:trial.repeat, actor,
      success:result.success, state:result.state, calls:result.nativeSearch.calls,
      premature:premature.map(e => ({...e, elapsed:e.tick-e.doneSince})),
      events: result.success ? [] : events.filter(e => e.event.startsWith("settle") || e.event === "recover-head"
        || premature.some(p => e.tick >= p.tick-2 && e.tick <= p.tick+3)),
      searches:result.success ? [] : searches});
  }
}
const file = resolve(root, "handoff-analysis.json");
assert.equal(existsSync(file), false, "preserve prior analysis");
writeFileSync(file, JSON.stringify({identities,cases},null,2)+"\n");
console.log(JSON.stringify({cases:cases.length, failures:cases.filter(c=>!c.success).map(c=>({
  distance:c.distance, concurrency:c.concurrency, repeat:c.repeat, actor:c.actor, state:c.state,
  calls:c.calls, premature:c.premature.map(p=>({tick:p.tick,elapsed:p.elapsed,position:p.position}))
}))},null,2));
