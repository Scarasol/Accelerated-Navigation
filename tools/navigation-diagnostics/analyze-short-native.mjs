import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { traceIntegrity } from "./analyze.mjs";

export function checkTrial(t, variant) {
  const issues=[], check=(ok,message)=>{if(!ok)issues.push(`${t.name}: ${message}`);};
  const end=t.requests.at(-1)?.state;
  const cancelled=["cancel-pending","waiting-cancel","shutdown-published"].includes(t.name);
  const unavailable=["forced-unavailable","waiting-ttl"].includes(t.name);
  const expected=t.name==="query-only"?"REACHABLE":cancelled?"CANCELLED":
    unavailable?"UNAVAILABLE":t.name==="range4"?"EXHAUSTED":"SUCCEEDED";
  check(t.accepted,"request rejected");
  check(end===expected,`state ${end}, expected ${expected}`);
  if(expected==="SUCCEEDED")check(t.arrived,"no physical arrival");
  if(expected==="SUCCEEDED")
    check(t.position.reduce((n,p,i)=>n+Math.abs(p-t.goal[i]),0)<=1,"final physical position differs");
  const whole=t.searches.filter(s=>!s.segmented&&s.adopted);
  const decisions=t.searches.filter(s=>!s.adopted);
  for(const id of new Set(whole.map(s=>s.queryId)))
    if(variant==="candidate")check(whole.filter(s=>s.queryId===id).length<=1,"repeated whole movement search");
  for(const s of t.searches) {
    if(!s.segmented||s.last) {
      check(s.range===s.requestedRange,"original range changed");
      check(s.accuracy===s.requestedAccuracy,"original accuracy changed");
    }
    check(s.wallNanos>=0&&s.cpuNanos>=0&&s.allocatedBytes>=0,"invalid search costs");
  }
  if(["cancel-pending","waiting-cancel","waiting-ttl"].includes(t.name))
    check(t.searches.length===0,"cancelled/waiting request searched");
  if(t.name==="replace-pending") {
    check(t.requests.length===2&&t.requests[0].state==="CANCELLED","replacement identity missing");
    check(t.searches.every(s=>s.queryId!==t.requests[0].queryId),"old generation searched");
  }
  if(t.name==="query-only")
    check(decisions.length===1&&!whole.length&&!t.arrived&&t.macroCalls+t.sharedCalls===0,"pure query moved or routed");
  if(["query-pending","query-waiting"].includes(t.name))check(!decisions.length,"PENDING did a query search");
  if(["query-ready","query-mixed"].includes(t.name))check(decisions.length===1,"READY query decision missing");
  if(t.name==="query-waiting")check(t.actions.some(a=>a.name==="decision-wait-observed"),"initialization wait unobserved");
  const long=["radius17","middle28","super304","query-mixed"].includes(t.name);
  if(long) {
    check(!whole.length,"long/mixed request used whole movement search");
    check(t.macroCalls+t.sharedCalls>0,"macro route not observed");
  }
  if(variant==="candidate") {
    if(["flat8","cross8","radius16","range12","query-pending","query-waiting","query-ready","replace-pending","waiting-resume"].includes(t.name)) {
      check(whole.length===1,"short attempt missing");
      check(t.macroCalls+t.sharedCalls+t.windowCalls===0,"unexpected macro/window preparation");
    }
    if(t.name==="range4"||t.name==="forced-null") {
      check(whole.length===1&&t.macroCalls+t.sharedCalls>0,"unique upgrade missing");
      if(t.name==="range4")check(!whole[0]?.actualCanReach,"small-range negative control became reachable");
    }
    if(t.name==="forced-unavailable")
      check(whole.length===1&&!t.macroCalls&&!t.sharedCalls&&!t.arrived,"unavailable upgraded or moved");
    if(t.name.startsWith("stairs-")) {
      check(whole.length===1,"stair initial short attempt missing");
      check(whole[0]?.capabilities.openDoors===false,"door capability changed");
      if(!whole[0]?.actualCanReach)check(t.macroCalls+t.sharedCalls>0,"stair partial not upgraded");
    }
    if(["entity-drift","dynamic-block","real-stuck"].includes(t.name)) {
      check(whole.length===1,"initial dynamic short search missing");
      check(t.actions.some(a=>a.name===(t.name==="real-stuck"?"real-nativeStopped":t.name)),"dynamic event missing");
      check(t.macroCalls+t.sharedCalls>0,"dynamic upgrade missing");
    }
  }
  if(t.name.startsWith("stairs-")) {
    const forward=[[-23,100,-198],[-37,100,-195]];
    const raw=t.name==="stairs-forward"?forward:forward.toReversed();
    check(JSON.stringify([t.originalStart,t.originalGoal])===JSON.stringify(raw),"original stair coordinates changed");
  }
  return issues;
}

function main() {
  const [variant,directory,outputName="short-native-analysis.json"]=process.argv.slice(2);
  assert(["baseline","candidate"].includes(variant));
  assert(/^[a-z0-9-]+\.json$/.test(outputName));
  const read=name=>JSON.parse(fs.readFileSync(path.join(directory,name),"utf8").replace(/^\uFEFF/,""));
  const hash=file=>crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
  const result=read("timeline.json"),launch=read("launch.json"),exit=read("exit.json"),ticks=read("full-ticks.json");
  const identity=JSON.parse(fs.readFileSync(path.join(launch.build,"build-identity.json"),"utf8").replace(/^\uFEFF/,""));
  assert.equal(result.schema,"SHORT_NATIVE_CONTROLS_V1");
  assert(result.complete&&!result.failure&&read("shutdown.json").passed);
  assert.equal(exit.exitCode,0);assert(!exit.timedOut&&!exit.concurrentJava.length);
  assert.equal(hash(path.join(launch.build,"build-identity.json")),launch.buildIdentitySha256);
  assert.equal(hash(path.join(directory,"mods/accelerated_navigation.jar")),identity.productionJarSha256);
  assert.equal(hash(path.join(directory,"mods/timeline-probe.jar")),launch.artifacts.find(a=>a.name==="timeline-probe.jar").sha256);
  assert(!ticks.truncated&&ticks.incomplete===0);
  for(let i=1;i<ticks.samples.length;i++)assert.equal(ticks.samples[i].serverTick,ticks.samples[i-1].serverTick+1);
  assert.equal(result.trials.length,result.scenes.length);
  assert.deepEqual(result.trials.map(t=>t.name),result.scenes);
  if(launch.scene)assert.deepEqual(result.scenes.toSorted(),launch.scene.split(",").toSorted());
  const budget=read("budget-controls.json");assert(budget.passed);
  const tsv=name=>{
    const [header,...rows]=fs.readFileSync(path.join(directory,name),"utf8").trimEnd().split(/\r?\n/);
    return rows.map(row=>Object.fromEntries(row.split("\t").map((v,i)=>[header.split("\t")[i],Number(v)])));
  };
  assert.deepEqual(traceIntegrity(tsv("trace-status.tsv"),tsv("events.tsv")),[]);
  assert(!fs.readFileSync(path.join(directory,"transforms.txt"),"utf8").includes("ERROR "));
  for(const t of result.trials) {
    assert(t.submittedNanos-t.sceneReadyNanos>=30e9&&t.submittedNanos-t.serverStartedNanos>=30e9);
    assert.equal(t.pathFinderCalls,t.searches.length,"missing actual search records");
  }
  const issues=result.trials.flatMap(t=>checkTrial(t,variant));
  const output={variant,technicallyValid:true,functionalPassed:issues.length===0,diagnosticOnly:true,issues,
    productionSha256:identity.productionJarSha256,trials:result.trials.map(t=>({
      name:t.name,state:t.requests.at(-1)?.state,arrived:t.arrived,searches:t.searches.length,
      shortSearches:t.searches.filter(s=>s.adopted&&!s.segmented).length,
      macroCalls:t.macroCalls,sharedCalls:t.sharedCalls,windowCalls:t.windowCalls,
      firstPathMs:t.firstPathNanos?(t.firstPathNanos-t.submittedNanos)/1e6:null,
      arrivalMs:t.arrivalNanos?(t.arrivalNanos-t.submittedNanos)/1e6:null,
      pathFinderCpuNanos:t.searches.reduce((n,s)=>n+s.cpuNanos,0),
      pathFinderAllocatedBytes:t.searches.reduce((n,s)=>n+s.allocatedBytes,0)
    })),sources:Object.fromEntries(["timeline.json","launch.json","exit.json","shutdown.json","full-ticks.json",
      "budget-controls.json","events.tsv","trace-status.tsv"].map(n=>[n,hash(path.join(directory,n))]))};
  fs.writeFileSync(path.join(directory,outputName),JSON.stringify(output,null,2)+"\n",{flag:"wx"});
  console.log(JSON.stringify(output));
  if(issues.length)process.exitCode=1;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main();
