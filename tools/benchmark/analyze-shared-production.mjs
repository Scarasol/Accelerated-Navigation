import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const run=process.argv[2];
const core=process.argv[3]??"core-v2";
if(!/^[a-z0-9-]+$/.test(run??""))throw Error("Explicit native run required");
if(!/^[a-z0-9-]+$/.test(core))throw Error("Explicit safe core run required");
let checks=0;
function assert(value,label) { checks++;if(!value)throw Error(label); }
const json=path=>JSON.parse(readFileSync(resolve(root,path),"utf8").replace(/^\uFEFF/,""));
const raw=readFileSync(resolve(root,run,"shared-production.json"));
const result=JSON.parse(raw),exit=json(`${run}/exit.json`),launch=json(`${run}/launch-environment.json`);
assert(result.passed===true&&result.failed===false,"actual native result");
assert(exit.exitCode===0&&!exit.timedOut,"actual JVM exit");
assert(result.jvmPid===exit.pid,"host and actual JVM PID");
const names=["flat","reuse-flat","narrow","wood-open","wood-closed-detour","iron-negative",
    "input-change","cancel","replace",...(run==="native-v3"||run==="native-v4"?[]:["native-stop-resume"]),
    "cross-section","small-legacy",...(result.cases.some(row=>row.name==="capacity-fallback")
        ?["capacity-fallback","generic-rejection","unavailable-rejection"]:[])];
assert(result.cases.length===names.length,"complete declared native matrix");
let arrived=0,nativeCalls=0,sharedRequests=0;
for(let i=0;i<names.length;i++) {
    const name=names[i],row=result.cases[i];
    assert(row.name===name,"scenario order");
    const negative=name==="iron-negative"||name.endsWith("-rejection"),cancel=name==="cancel";
    const legacy=name==="cross-section"||name==="small-legacy";
    assert(row.state===(negative?"UNAVAILABLE":cancel?"CANCELLED":"SUCCEEDED"),`${name} terminal`);
    assert(legacy?row.sharedRequests===0:row.sharedRequests>0,`${name} declared branch`);
    if(!negative&&!cancel) {
        const goal=name==="replace"?[3,81,12]:[name==="cross-section"?28:name==="capacity-fallback"?44:12,81,7];
        assert(row.position.every((v,axis)=>Math.floor(v)===goal[axis]),`${name} actual final grid`);
        assert(row.arrived===true,`${name} arrival agreement`);
        assert(row.nativeCalls>0,`${name} native invocation`);
        arrived++;
        const trace=result.positions.filter(p=>p.case===name);
        assert(trace.length>0,`${name} trajectory present`);
        for(const sample of trace) {
            assert(sample.position.length===3&&sample.position.every(Number.isFinite),"finite real position");
            assert(Number.isInteger(sample.tick),"real server tick");
        }
    }
    assert(row.shared.flights===0&&row.shared.captures===0,`${name} settled worker publications`);
    if(name==="capacity-fallback")assert(row.sharedFallbacks===1&&row.sharedSegments===0,"actual typed capacity fallback");
    if(name.endsWith("-rejection"))assert(row.sharedFallbacks===0&&row.nativeCalls===0,"generic rejection does not fallback");
    nativeCalls+=row.nativeCalls;sharedRequests+=row.sharedRequests;
}
for(const control of result.checks)assert(control.passed===true,`control ${control.name}`);
const coreRuns=[];
for(const file of [`${core}/runtime.json`,`${core}/scratch.json`]) {
    const data=json(file);assert(data.passed===true,`${file} migrated production controls`);
    for(const control of data.checks)assert(control.passed===true,`${file} ${control.name}`);
    coreRuns.push({file,checks:data.checks.length,jvmPid:data.jvmPid,
        sha256:createHash("sha256").update(readFileSync(resolve(root,file))).digest("hex").toUpperCase()});
}
assert(launch.probe==="shared","actual runner selection");
assert(launch.mods.length===2,"production and test mod only");
const summary={schema:"SHARED_PRODUCTION_INDEPENDENT_READER_V1",run,passed:true,checks,
    actualArrivals:arrived,correctNegative:1,cancelled:1,nativeCalls,sharedRequests,
    nativeChecks:result.checks.length,nativeJvmPid:result.jvmPid,coreRuns,
    rawSha256:createHash("sha256").update(raw).digest("hex").toUpperCase(),
    scenarios:result.cases.map(({name,state,ticks,sharedRequests,sharedSegments,nativeCalls})=>
        ({name,state,ticks,sharedRequests,sharedSegments,nativeCalls})),
    persistenceApproved:false,performanceApproved:false,
};
writeFileSync(resolve(root,`analysis-${run}.json`),JSON.stringify(summary,null,2)+"\n");
console.log(JSON.stringify(summary));
