import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {classifyJfrSample as source} from './jfr-classification.mjs';

const root=process.argv[2];
assert(root,'Usage: node tools/navigation-diagnostics/analyze-jfr.mjs RUN');
const read=name=>JSON.parse(fs.readFileSync(path.join(root,name),'utf8').replace(/^\uFEFF/,''));
const result=read('timeline.json');
const rows=fs.readFileSync(path.join(root,'jfr-samples.tsv'),'utf8').trimEnd().split(/\r?\n/);
const fields=rows.shift().split('\t');
const events=rows.map(line=>Object.fromEntries(line.split('\t').map((value,i)=>
  [fields[i],['nanos','weight','durationNanos','clockWidthNanos'].includes(fields[i])?Number(value):value])));
const top=(samples,key,value='weight')=>{
  const map=new Map();
  for(const sample of samples) {
    const label=key(sample),row=map.get(label)??{label,samples:0,total:0};
    row.samples++;row.total+=value?sample[value]:1;map.set(label,row);
  }
  return [...map.values()].sort((a,b)=>b.total-a.total).slice(0,25);
};
const trials=result.trials.map(trial=>{
  const active=events.filter(e=>e.nanos>=trial.submittedNanos&&e.nanos<=trial.closedNanos);
  const allocations=active.filter(e=>e.type==='jdk.ObjectAllocationSample');
  const monitors=active.filter(e=>e.type==='jdk.JavaMonitorEnter');
  const executions=active.filter(e=>e.type==='jdk.ExecutionSample');
  return {scene:trial.scene.name,allocationSamples:allocations.length,
    allocationWeightBytes:allocations.reduce((n,e)=>n+e.weight,0),
    allocationByThread:top(allocations,e=>e.thread),
    allocationBySource:top(allocations,source),
    mainAllocationBySource:top(allocations.filter(e=>e.thread==='Server thread'),source),
    allocationSites:top(allocations,e=>`${e.class}.${e.method}`),
    allocationClasses:top(allocations,e=>e.objectClass),
    mainAllocationSites:top(allocations.filter(e=>e.thread==='Server thread'),e=>`${e.class}.${e.method}`),
    workerAllocationSites:top(allocations.filter(e=>e.thread.startsWith('accelerated-navigation-topology-')&&
      !e.thread.endsWith('-io')),e=>`${e.class}.${e.method}`),
    executionSamples:executions.length,executionSites:top(executions,e=>`${e.thread}: ${e.class}.${e.method}`,null),
    monitorEnters:monitors.length,mainMonitorEnters:monitors.filter(e=>e.thread==='Server thread')
      .map(e=>({durationMs:e.durationNanos/1e6,owner:e.owner,monitorClass:e.objectClass,site:`${e.class}.${e.method}`,stack:e.stack})),
    clockAnchorWidthMaxNanos:Math.max(0,...active.map(e=>e.clockWidthNanos))};
});
const output={schema:'NAVIGATION_JFR_DIAGNOSIS_V1',diagnosticOnly:true,
  allocationWeightsAreSamplingEstimates:true,executionSamplesAreNotExactPhaseCpu:true,
  dataLossEvents:events.filter(e=>e.type==='jdk.DataLoss').length,trials,
  inputSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'jfr-samples.tsv'))).digest('hex')};
const destination=process.argv[3]??path.join(root,'jfr-analysis.json');
assert(!fs.existsSync(destination),'Preserve existing analysis');
fs.writeFileSync(destination,JSON.stringify(output,null,2)+'\n');
console.log(JSON.stringify(output.trials.map(t=>({scene:t.scene,samples:t.allocationSamples,
  top:t.allocationSites.slice(0,4),mainLocks:t.mainMonitorEnters.length})),null,2));
