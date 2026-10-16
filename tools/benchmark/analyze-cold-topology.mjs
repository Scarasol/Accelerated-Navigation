import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const campaign=process.argv[2];
if(!/^[a-z0-9-]+$/.test(campaign??""))throw Error("Safe campaign required");
const runs=process.argv.slice(3);
if(!runs.length||runs.some(run=>!/^[a-z0-9-]+$/.test(run)))throw Error("Explicit runs required");
const output=resolve(root,`analysis-cold-${campaign}.json`);
if(existsSync(output))throw Error("Preserve cold result");
const json=file=>JSON.parse(readFileSync(resolve(root,file),"utf8").replace(/^\uFEFF/,""));
const hash=bytes=>createHash("sha256").update(bytes).digest("hex").toUpperCase();
const baseline="FFC78BB8BAF05EC5697B700523268D4A7B0C005AFC29AD31D2FBC34919966EA3";
const candidate="2D9DF2FF444A397574F16D5DBC87E94276DE4B1F074DA42D9354AFBE437572E0";
const observations=[];
let checks=0;
function check(value,label){checks++;if(!value)throw Error(label);}
let profile,input,facts,probe;
const executionWindows=[];
for(const run of runs) {
    const data=json(`${run}/cold-topology.json`),exit=json(`${run}/exit.json`);
    const launch=json(`${run}/launch-environment.json`),ticks=json(`${run}/full-ticks.json`);
    const production=launch.mods.find(m=>m.name==="accelerated_navigation.jar").sha256;
    const probeHash=launch.mods.find(m=>m.name==="production-probe.jar").sha256;
    check(production===baseline||production===candidate,"frozen actual production");
    check(hash(readFileSync(resolve(root,run,"mods/accelerated_navigation.jar")))===production,"actual copied jar");
    check(hash(readFileSync(resolve(root,run,"mods/production-probe.jar")))===probeHash,"actual probe");
    probe??=probeHash;check(probe===probeHash,"identical diagnostic/observation probe");
    check(data.passed&&!data.failed,"real first path");
    check(exit.exitCode===0&&!exit.timedOut&&exit.pid===data.jvmPid,"actual identity and exit");
    executionWindows.push([Date.parse(exit.startedAt),Date.parse(exit.exitedAt)]);
    check(execFileSync("powershell.exe",["-NoProfile","-Command",
        `if (Get-Process -Id ${Number(exit.pid)} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
        {encoding:"utf8"}).trim()==="dead","JVM terminated");
    for(const row of data.checks)check(row.passed,row.name);
    profile??=data.profile;input??=data.input;facts??=JSON.stringify(data.graphColdBefore.selectedFactFingerprints);
    check(profile===data.profile&&input===data.input,"same actor/world specification");
    check(facts===JSON.stringify(data.graphColdBefore.selectedFactFingerprints),"same actual selected facts");
    check(Object.keys(data.graphColdBefore.selectedFactFingerprints).length===24,"loaded selected parent children facts");
    check(!Object.values(data.graphColdBefore.selectedFactFingerprints).includes("absent"),"ready selected facts");
    check(data.graphColdBefore.matchingPublishedViews===0,"actual requested-body graph cold");
    check(data.firstPathNodes>0&&data.navigation.nativeCalls>0&&data.navigation.nativeWholeCalls===0,"genuine segmented path");
    check(!data.telemetry.truncated&&!ticks.truncated,"bounded instruments complete");
    check(data.telemetry.errors.length===0,"instrument metadata reads succeeded");
    const start=data.submittedNanos,end=data.firstPathNanos;
    const events=data.telemetry.events.filter(e=>e.start>=start&&e.start<=end);
    const before=data.preSubmitTelemetry.events;
    const matchingBefore=before.filter(e=>e.phase==="base"&&e.geometry===data.geometry);
    check(matchingBefore.length===0,"no matching pre-submit builds");
    const canonical=events.filter(e=>e.phase==="canonical").map(e=>e.detail);
    const shared=data.sharedAfter.baseBuilds>0&&data.navigation.segmentCalls>0&&data.route==="same"&&production===candidate;
    if(data.telemetry.diagnostic) {
        check(data.telemetry.openIntervals===0,"successful bounded intervals close");
        if(data.route==="super"){
            check(canonical.includes("true"),"actual Super canonical branch");
            check(events.some(e=>e.phase==="super"),"actual Super builds");
        }else if(!shared)check(canonical.length>0&&canonical.every(value=>value==="false"),"actual Base canonical branch");
        if(shared)check(canonical.length===0&&data.sharedBefore.baseBuilds===0,"actual fresh shared branch");
    }
    const phase={};
    for(const name of new Set(events.map(e=>e.phase))) {
        const spans=events.filter(e=>e.phase===name&&e.end!=null);
        phase[name]={count:events.filter(e=>e.phase===name).length,
            sumInclusiveNanos:spans.reduce((n,e)=>n+e.end-e.start,0),
            maxInclusiveNanos:Math.max(0,...spans.map(e=>e.end-e.start)),
            overlapWithRequestNanos:spans.reduce((n,e)=>n+Math.max(0,Math.min(end,e.end)-Math.max(start,e.start)),0)};
    }
    const requestTicks=ticks.samples.filter(t=>t.endNanos>=start&&t.startNanos<=end);
    check(requestTicks.length>0,"full tick envelope matched");
    observations.push({run,backend:production===baseline?"baseline":"shared",route:data.route,
        diagnostic:data.telemetry.diagnostic,pid:data.jvmPid,rawSha256:hash(readFileSync(resolve(root,run,"cold-topology.json"))),
        waitMs:data.firstPathWaitNanos/1e6,matchingPreSubmitBuilds:matchingBefore.length,
        actualBranch:data.telemetry.diagnostic?(shared?"shared":canonical.includes("true")?"super":"base"):"uninstrumented",
        firstPathNodes:data.firstPathNodes,firstPathCanReach:data.firstPathCanReach,
        observedBaseBuilds:events.filter(e=>e.phase==="base").map(e=>({
            section:e.section,geometry:e.geometry,sourceFingerprint:e.sourceFingerprint,
            nanos:e.end-e.start,matchingBody:e.geometry===data.geometry})),
        grantMs:requestTicks.map(t=>t.grantNanos/1e6),fullTickMs:requestTicks.map(t=>t.fullNanos/1e6),
        phase,worker:data.workerAtFirstPath});
}
executionWindows.sort((a,b)=>a[0]-b[0]);
for(let i=0;i<executionWindows.length;i++) {
    check(executionWindows[i].every(Number.isFinite),"recorded launch/exit wall times");
    if(i)check(executionWindows[i][0]>=executionWindows[i-1][1],"serial actual JVM windows");
}
const result={schema:"TOPOLOGY_FIRST_PATH_INDEPENDENT_READER_V1",campaign,passed:true,checks,probeSha256:probe,
    baselineSha256:baseline,candidateSha256:candidate,profile,input,observations,
    scope:"single adult .6x1.95 Husk, accuracy0, fixed fresh flat inputs; same dynamic 4..20ms policy, actual grant reported; diagnostic phase costs separated from uninstrumented first-path observations",
    performanceApproved:false,concurrencyMatrixApproved:false,dependencyCriticalPathApproved:false};
writeFileSync(output,JSON.stringify(result,null,2)+"\n");
console.log(JSON.stringify({campaign,checks,observations:observations.map(({run,waitMs,actualBranch})=>({run,waitMs,actualBranch}))}));
