import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const result=JSON.parse(readFileSync(resolve(root,"analysis-cold-matrix-v3.json")));
const output=resolve(root,"cold-budget-qualification-v1.json");
if(existsSync(output))throw Error("Preserve qualification");
const observations=[];
let checks=0;
function check(value,label){checks++;if(!value)throw Error(label);}
for(const row of result.observations) {
    const data=JSON.parse(readFileSync(resolve(root,row.run,"cold-topology.json")));
    const full=JSON.parse(readFileSync(resolve(root,row.run,"full-ticks.json")));
    const matched=full.samples.filter(t=>t.endNanos>=data.submittedNanos&&t.startNanos<=data.firstPathNanos);
    check(matched.length===row.grantMs.length,"original request tick count");
    check(matched.every((t,i)=>t.grantNanos/1e6===row.grantMs[i]),"original actual grants");
    check(matched.every(t=>t.grantNanos>=0&&t.grantNanos<=20e6),"actual valid 0..20ms policy");
    const sum=phase=>(row.phase[phase]?.sumInclusiveNanos??0)/1e6;
    observations.push({run:row.run,backend:row.backend,route:row.route,diagnostic:row.diagnostic,
        requestWindowTicks:matched.length,zeroGrantTicks:matched.filter(t=>t.grantNanos===0).length,
        minGrantMs:Math.min(...row.grantMs),maxGrantMs:Math.max(...row.grantMs),
        firstPathWaitMs:row.waitMs,baseBuilds:row.observedBaseBuilds.length,bodyBaseBuilds:row.observedBaseBuilds.filter(b=>b.matchingBody).length,
        baseInclusiveMs:sum("base"),legalInclusiveMs:sum("legal"),labelsInclusiveMs:sum("labels"),
        edgesInclusiveMs:sum("edges"),boundaryInclusiveMs:sum("boundary"),
        superInclusiveMs:sum("super"),macroStepInclusiveMs:sum("macro-step"),nativeInclusiveMs:sum("native"),
        maxFullTickMs:Math.max(...row.fullTickMs)});
}
const qualification={schema:"COLD_BUDGET_QUALIFICATION_V1",passed:true,checks,observations,
    correction:"matrix-v3 scope shorthand 4..20ms is imprecise; actual unchanged controller starts at 4ms, caps at 20ms, and may throttle to 0ms. Observations without phase profiling retain full-tick/dispatch instrumentation and pre-submit cache inspection.",
    conclusion:"observed phase wall intervals do not identify pure CPU or a critical dependency path; zero grants and dispatch contribute to server first-path wait; two repeats per route/backend do not establish stable performance benefit",
    fullRestartFactsColdMeasured:false,completeMovementSpeedupApproved:false,
    dynamicBudgetAcceptanceApproved:false,newAlgorithmApproved:false};
writeFileSync(output,JSON.stringify(qualification,null,2)+"\n");
console.log(JSON.stringify({checks,zeroGrantRuns:observations.filter(o=>o.zeroGrantTicks>0).length,
    observations:observations.map(({run,zeroGrantTicks,minGrantMs,maxGrantMs})=>({run,zeroGrantTicks,minGrantMs,maxGrantMs}))}));
