import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const workspace=resolve(import.meta.dirname,"../..");
const root=resolve(workspace,".tmp/shared-topology-production-20261015");
const run=process.argv[2]??"supplement-capacity-v1";
if(!/^[a-z0-9-]+$/.test(run))throw Error("Safe run required");
const output=resolve(root,`analysis-${run}-owned.json`);
if(existsSync(output))throw Error("Preserve existing independent reading");
const raw=readFileSync(resolve(root,run,"supplement.json"));
const data=JSON.parse(raw),hash=bytes=>createHash("sha256").update(bytes).digest("hex").toUpperCase();
let checks=0;
function check(value,label){checks++;if(!value)throw Error(label);}
check(data.passed===true&&data.failure==="","actual observations succeeded");
check(data.samples.length===7,"complete controlled checkpoints");
for(const row of data.checks)check(row.passed===true,row.name);
for(const sample of data.samples) {
    for(const key of ["runtimeReachable","unionWithActualWorkerAndOldSnapshotRoots"]) {
        const value=sample[key];
        check(Object.values(value.bytesByClass).reduce((a,b)=>a+b,0)===value.bytes,"class bytes reconcile");
        check(Object.values(value.objectsByClass).reduce((a,b)=>a+b,0)===value.objects,"class objects reconcile");
        check(Object.keys(value.bytesByClass).every(type=>value.objectsByClass[type]>0),"same class denominator");
        check(value.bytes>sample.estimatedRowsBytes,"whole selected graph exceeds row payload");
    }
    check(sample.unionWithActualWorkerAndOldSnapshotRoots.bytes>=sample.runtimeReachable.bytes,"union monotonic");
}
const byName=new Map(data.samples.map(s=>[s.name,s]));
const peak=byName.get("actual-worker-old-snapshot-checkpoint");
check(peak.unionWithActualWorkerAndOldSnapshotRoots.objectsByClass[
    "com.scarasol.acceleratednavigation.topology.QueryScratch"]===2,"two actual worker scratch roots");
check(peak.unionWithActualWorkerAndOldSnapshotRoots.objectsByClass[
    "com.scarasol.acceleratednavigation.topology.LocalKernel"]>0,"active kernel included");
check(peak.temporaryRoots>0,"actual in-task transient capture");
check(byName.get("after-overlap-release").temporaryRoots===0,"existing task cleanup releases transient roots");
for(const name of ["64-live-slots","64-stale-slots-after-unload"])
    check(byName.get(name).metrics.slots===64,"capacity remains occupied at 64");
check(data.peakQualification.includes("upper observation"),"no continuous peak claim");
const pid=Number(data.jvmPid);
check(Number.isSafeInteger(pid)&&pid>0,"actual PID");
check(execFileSync("powershell.exe",["-NoProfile","-Command",
    `if (Get-Process -Id ${pid} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
    {encoding:"utf8"}).trim()==="dead","actual JVM exited");
const agent=resolve(workspace,"build/navigation-verification/shared-ownership-agent.jar");
const result={schema:"SHARED_SUPPLEMENT_INDEPENDENT_READER_V1",run,passed:true,checks,jvmPid:pid,
    rawSha256:hash(raw),agentSha256:hash(readFileSync(agent)),scope:data.scope,
    peakQualification:data.peakQualification,
    checkpoints:data.samples.map(s=>({name:s.name,runtimeBytes:s.runtimeReachable.bytes,
        unionBytes:s.unionWithActualWorkerAndOldSnapshotRoots.bytes,rowEstimate:s.estimatedRowsBytes,
        heapProxyBeforeWalk:s.heapProxyBeforeWalk,temporaryRoots:s.temporaryRoots,
        workerRoots:s.workerRoots,slots:s.metrics.slots})),
    performanceApproved:false,exclusiveProcessPeakApproved:false,idleEvictionImplemented:false};
writeFileSync(output,JSON.stringify(result,null,2)+"\n");
console.log(JSON.stringify(result));
