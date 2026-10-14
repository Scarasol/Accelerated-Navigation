import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const root = resolve(process.argv[2]);
const identities = [];
const read = path => {
  const raw = readFileSync(resolve(root,path));
  identities.push({path,sha256:createHash("sha256").update(raw).digest("hex")});
  return JSON.parse(raw.toString("utf8").replace(/^\uFEFF/,""));
};
const old = read("handoff-trace-full-v1/handoff-analysis.json");
const next = read("handoff-trace-grace3/handoff-analysis.json");
const oldTrace = read("handoff-trace-full-v1/handoff-diagnostics.json");
const nextTrace = read("handoff-trace-grace3/handoff-diagnostics.json");
const oldResult = read("handoff-trace-full-v1/performance-comparison.json");
const nextResult = read("handoff-trace-grace3/performance-comparison.json");
const comparison = read("grace3-trace-comparison/comparison.json");
const delta = read("grace3-artifact-delta.json");
const replay = read("partial-replay-v1/partial-replay.json");
const key = c => `${c.distance}/${c.concurrency}/${c.repeat}/${c.actor}`;
const failed = old.cases.filter(c=>!c.success);
const corrected = failed.map(c=>{
  const n = next.cases.find(n=>key(n)===key(c)); assert.ok(n);
  return {key:key(c),before:c,after:n};
});
let sameFirstInputs = 0, sameFirstNodes = 0;
const differentFirst = [];
for (const c of old.cases) {
  const trial = data => data.trials.find(t=>t.distance===c.distance&&t.concurrency===c.concurrency&&t.repeat===c.repeat&&!t.warmup);
  const search = (trace,t) => trace.searches.find(s=>s.input.actor===c.actor&&s.input.distance===c.distance
    &&s.input.repeat===c.repeat&&s.input.tick>=t.submitTick&&s.input.tick<=t.endTick);
  const a=search(oldTrace,trial(oldResult)), b=search(nextTrace,trial(nextResult));
  assert.ok(a&&b);
  const input = s=>Object.fromEntries(["position","targets","range","accuracy","multiplier","onGround","avoided"].map(k=>[k,s.input[k]]));
  let inputs = JSON.stringify(input(a))===JSON.stringify(input(b));
  let nodes = JSON.stringify(a.result.nodes)===JSON.stringify(b.result.nodes);
  if(inputs)sameFirstInputs++;if(nodes)sameFirstNodes++;
  if(!inputs||!nodes)differentFirst.push({key:key(c),inputs,nodes});
}
const summary={identities,changedClasses:delta.changedClasses,
  pairedTrials:comparison.comparisons.length,validatedInputPairs:comparison.summaries.filter(s=>s.mode==="dynamic20").reduce((n,s)=>n+s.requested,0),
  lost:comparison.comparisons.reduce((n,c)=>n+c.lostSuccessActors.length,0),
  gained:comparison.comparisons.reduce((n,c)=>n+c.gainedSuccessActors.length,0),
  watchedCases:old.cases.length,sameFirstInputs,sameFirstNodes,differentFirst,
  corrected,replay};
writeFileSync(resolve(root,"grace3-consensus-evidence.json"),JSON.stringify(summary,null,2)+"\n");
console.log(JSON.stringify({pairedTrials:summary.pairedTrials,validatedInputPairs:summary.validatedInputPairs,
  lost:summary.lost,gained:summary.gained,watchedCases:summary.watchedCases,sameFirstInputs,sameFirstNodes,differentFirst},null,2));
