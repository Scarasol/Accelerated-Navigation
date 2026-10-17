import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { traceIntegrity } from "./analyze.mjs";

const [variant, directory, outputName="controls-analysis.json"] = process.argv.slice(2);
assert(["baseline","candidate"].includes(variant));
assert(/^[a-z0-9-]+\.json$/.test(outputName));
const read = name => JSON.parse(fs.readFileSync(path.join(directory,name),"utf8").replace(/^\uFEFF/,""));
const hash = file => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const tsv = name => {
  const [header,...lines]=fs.readFileSync(path.join(directory,name),"utf8").trim().split(/\r?\n/);
  const keys=header.split("\t");
  return lines.map(line=>Object.fromEntries(line.split("\t").map((value,i)=>[keys[i],Number(value)])));
};
const result=read("timeline.json"), launch=read("launch.json"), exit=read("exit.json");
const shutdown=read("shutdown.json"), ticks=read("full-ticks.json"), budget=read("budget-controls.json");
const identity=JSON.parse(fs.readFileSync(path.join(launch.build,"build-identity.json"),"utf8").replace(/^\uFEFF/,""));
assert.equal(result.schema,"FINAL_WINDOW_LIFECYCLE_V1");
assert(result.complete&&!result.failure&&shutdown.passed);
assert.equal(exit.exitCode,0);assert.equal(exit.timedOut,false);assert.equal(exit.concurrentJava.length,0);
assert.equal(hash(path.join(launch.build,"build-identity.json")),launch.buildIdentitySha256);
assert.equal(hash(path.join(directory,"mods/accelerated_navigation.jar")),identity.productionJarSha256);
assert.equal(hash(path.join(directory,"mods/timeline-probe.jar")),launch.artifacts.find(a=>a.name==="timeline-probe.jar").sha256);
assert(!ticks.truncated&&ticks.incomplete===0);
assert(budget.passed&&budget.passes===2&&budget.checks.length>80);
const names=launch.scene ? launch.scene.split(",") : ["zero-edge8-first","zero-edge8-repeat","cross8",
  "macro-cancel","macro-replace","macro-block","budget-exhausted",
  "waiting-resume","waiting-cancel","waiting-replace","waiting-ttl","shutdown-final"];
assert.equal(result.trials.length,names.length);
assert.deepEqual([...result.trials.map(t=>t.name)].sort(),[...names].sort());
const events=tsv("events.tsv"), statuses=tsv("trace-status.tsv");
assert.deepEqual(traceIntegrity(statuses,events),[]);
assert(!fs.readFileSync(path.join(directory,"transforms.txt"),"utf8").includes("ERROR "));
for(let i=1;i<ticks.samples.length;i++)
  assert.equal(ticks.samples[i].serverTick,ticks.samples[i-1].serverTick+1);
const phases=fs.readFileSync(path.join(directory,"phases.txt"),"utf8").trimEnd().split(/\r?\n/);
const summary=[];
for(const [index,trial] of result.trials.entries()) {
  assert(trial.submittedNanos-trial.sceneReadyNanos>=30e9);
  assert(trial.submittedNanos-trial.serverStartedNanos>=30e9);
  assert(trial.accepted);
  const end=trial.generations.at(-1);
  assert(end);
  const cancelled=["macro-cancel","waiting-cancel","waiting-shutdown","shutdown-final"].includes(trial.name);
  const exhausted=trial.name==="waiting-ttl";
  assert.equal(end.state,cancelled?"CANCELLED":exhausted?"UNAVAILABLE":"SUCCEEDED",trial.name);
  if(!cancelled&&!exhausted)assert(trial.arrivalNanos>0,trial.name);
  const final=trial.searches.filter(s=>s.last);
  for(const search of final) {
    assert.equal(search.range,search.requestedRange);
    assert.equal(search.accuracy,search.requestedAccuracy);
    assert.equal(search.shared,false);
    assert.equal(search.window,variant==="baseline");
    assert.equal(search.pendingWindow,false);
    assert(search.account.chargedNanos>=0);
  }
  if(["macro-cancel","waiting-cancel","waiting-shutdown","waiting-ttl"].includes(trial.name)) {
    assert.equal(trial.search.calls,0,trial.name);
    assert.equal(trial.firstReadyNanos,0,trial.name);
  } else assert(final.length>0,trial.name);
  if(trial.name.includes("replace")) {
    assert.equal(trial.generations.length,2);
    assert.equal(trial.generations[0].state,"CANCELLED");
    assert(trial.searches.every(s=>s.queryId!==trial.generations[0].queryId));
  }
  if(trial.name==="macro-block") {
    const action=trial.actions.find(a=>a.kind==="macro-block");
    assert(action&&action.searchCallsBefore===0);
    assert(trial.searches.every(s=>s.nanoTime>=action.nanoTime&&s.committedIdentityRetained&&s.refreshes===0));
  }
  if(trial.name==="budget-exhausted") {
    const action=trial.actions.find(a=>a.kind===trial.name);
    assert(action&&action.balanceAfter<=0);
    assert(trial.searches.every(s=>s.serverTick>action.serverTick));
  }
  if(trial.name.startsWith("waiting-"))
    assert(trial.actions.some(a=>a.kind==="waiting-search-observed"));
  const prepares=events.filter(e=>e.trial===index+1&&phases[e.phase]?.startsWith("SegmentedNavigationService.prepareWindow("));
  const windowWork=events.filter(e=>e.trial===index+1&&phases[e.phase]?.startsWith("GroundSegmentWindow.lambda$capture"));
  const readyDrains=events.filter(e=>e.trial===index+1&&phases[e.phase]?.startsWith("NavigationScheduler.drainReady("));
  summary.push({name:trial.name,state:end.state,arrived:trial.arrivalNanos>0,
    firstPathMs:trial.firstReadyNanos?(trial.firstReadyNanos-trial.submittedNanos)/1e6:null,
    searches:trial.search.calls,finalSearches:final.length,windowPreparations:prepares.length,
    windowPreparationBytes:prepares.reduce((n,e)=>n+e.exclusiveBytes,0),
    windowWorkerBytes:windowWork.reduce((n,e)=>n+e.exclusiveBytes,0),
    windowWorkerNanos:windowWork.reduce((n,e)=>n+e.exclusive,0),
    readyDrains:readyDrains.length,readyDispatchExclusiveNanos:readyDrains.reduce((n,e)=>n+e.exclusive,0),
    actualSearches:trial.searches});
}
const output={variant,valid:true,diagnosticOnly:true,productionSha256:identity.productionJarSha256,
  budgetChecks:budget.checks.length,trials:summary,
  sources:Object.fromEntries(["timeline.json","launch.json","exit.json","shutdown.json","full-ticks.json",
    "budget-controls.json","events.tsv","trace-status.tsv"].map(name=>[name,hash(path.join(directory,name))]))};
fs.writeFileSync(path.join(directory,outputName),JSON.stringify(output,null,2)+"\n",{flag:"wx"});
console.log(JSON.stringify({variant,valid:true,budgetChecks:output.budgetChecks,
  trials:summary.map(({actualSearches,...rest})=>rest)}));
