import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {handoffs} from './summarize-coverage.mjs';

const directory=path.resolve(process.argv[2]??'');
assert(process.argv[2], 'Usage: node analyze-handoff.mjs RUN_DIRECTORY [ANALYSIS_JSON] [NEW_OUTPUT_JSON]');
const read=name=>JSON.parse(fs.readFileSync(path.join(directory,name),'utf8').replace(/^\uFEFF/,''));
const analysisFile=path.resolve(process.argv[3]??path.join(directory,'analysis.json'));
const analysis=JSON.parse(fs.readFileSync(analysisFile,'utf8').replace(/^\uFEFF/,''));
const raw=read('timeline.json'),ticks=read('full-ticks.json');
assert(analysis.valid,'Public analyzer must first establish technical validity');
const ms=(value,origin)=>value?(value-origin)/1e6:null;
const point=o=>o?{ms:o.ms,position:o.position,nodeIndex:o.nodeIndex,nodeCount:o.nodeCount,
  pathEnd:o.pathEnd??null,target:o.selectedTarget??null,entityTargetPosition:o.entityTargetPosition??null,
  stable:o.stable,onGround:o.onGround,entry:o.segmentEntry,nativeStopped:o.nativeStopped,
  publication:o.segmentPublication,cursor:o.cursor,stageEnd:o.stageEnd}:null;
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1]:null;
const details=analysis.trials.map((trial,i)=>{
  const original=raw.trials[i],actor=original.actors[0],origin=original.submittedNanos;
  assert.equal(trial.scene.name,original.scene.name);
  const observations=(original.observations??[]).map(o=>({...o,ms:ms(o.nanoTime,origin)}));
  const steps=trial.requests.flatMap(r=>r.steps??[]);
  const publications=steps.filter(s=>s.phase==='SegmentedNavigationService.publish');
  const primaryQuery=publications[0]?.queryId??null;
  const logicalQueries=[...new Set(steps.map(s=>s.queryId).filter(Boolean))].map(queryId=>{
    const own=steps.filter(s=>s.queryId===queryId);
    return {queryId,primary:queryId===primaryQuery,
      segmentSearches:own.filter(s=>s.phase==='SegmentedNavigationService.findSegment').length,
      tailSettleCalls:own.filter(s=>s.phase==='SegmentedNavigationService.settleTail').length,
      recoveries:own.filter(s=>s.phase==='SegmentedNavigationService.recover').length,
      publications:own.filter(s=>s.phase==='SegmentedNavigationService.publish').length};
  });
  const selected=ticks.samples.filter(t=>t.startNanos>=origin&&t.startNanos<=actor.terminalNanos);
  const full=selected.map(t=>t.fullNanos/1e6);
  const intervals=selected.slice(1).map((t,j)=>(t.startNanos-selected[j].startNanos)/1e6);
  return {scene:trial.scene.name,actors:trial.actors,arrived:trial.arrived,state:actor.state,
    firstReadyMs:ms(actor.firstReadyNanos,origin),firstMoveMs:ms(actor.firstMoveNanos,origin),
    arrivalMs:ms(actor.arrivalNanos,origin),terminalMs:ms(actor.terminalNanos,origin),
    intentAccuracyArrivalMs:ms(actor.intentAccuracyArrivalNanos,origin),
    arrivalToTerminalMs:actor.arrivalNanos?(actor.terminalNanos-actor.arrivalNanos)/1e6:null,
    observationWindowMs:trial.elapsedMs,search:actor.search,costs:trial.costs,
    mainCpuNanos:original.costEnd.mainThreadCpuNanos-original.costStart.mainThreadCpuNanos,
    gcCount:original.costEnd.gcCount-original.costStart.gcCount,
    gcMillis:original.costEnd.gcMillis-original.costStart.gcMillis,
    ticks:{count:full.length,p99Ms:percentile(full,.99),maxMs:Math.max(...full),
      over50:full.filter(n=>n>50).length,totalOver50Ms:full.reduce((n,v)=>n+Math.max(0,v-50),0),
      startIntervalP99Ms:percentile(intervals,.99),startIntervalMaxMs:Math.max(...intervals)},
    nativeStops:analysis.deepTrace?(trial.entryCensus?.nativeStopped??null):null,
    nativeStopEvents:(trial.nativeStopEvents??[]).map(stop=>{
      const startMs=ms(stop.nanoTime,origin);
      const previous=publications.filter(p=>p.startMs<=startMs).at(-1);
      return {startMs,tick:stop.tick,precedingPublication:previous?{
        queryId:previous.queryId,publication:previous.publication+1,last:previous.last,
        cursor:previous.cursor,publishedMs:previous.startMs,elapsedSincePublishMs:startMs-previous.startMs}:null};
    }),
    recoveries:analysis.deepTrace?steps.filter(s=>s.phase==='SegmentedNavigationService.recover').length:null,
    tailSettleCalls:analysis.deepTrace?steps.filter(s=>s.phase==='SegmentedNavigationService.settleTail').length:null,
    firstEntry:point(observations.find(o=>o.segmentEntry)),
    firstStop:point(observations.find(o=>o.nativeStopped)),
    firstLastNode:point(observations.find(o=>o.nodeCount>0&&o.nodeIndex===o.nodeCount-1)),
    handoffs:handoffs({...trial,observationsOriginNanos:origin}),
    logicalQueries,
    requests:trial.requests.map(r=>({queryId:r.queryId,state:r.state,
      generations:r.requestGenerations,actualSearches:r.actualSearches})),
    fixedWindows:trial.fixedWindows};
});
const report={schema:'NAVIGATION_HANDOFF_ANALYSIS_V1',directory,analysisFile,technicalValid:true,
  deepTrace:analysis.deepTrace,observationsOmitted:read('launch.json').omitObservations??false,
  scope:'Controlled scenes; terminal and first physical arrival are distinct. No statistical performance verdict.',
  observationScope:'END observations can miss Path completion and same-tick handoff; missing done observation is not proof it never completed.',
  arrivalScope:'arrivalMs uses the historical coverage Manhattan<=1 recorder; intentAccuracyArrivalMs separately uses the adopted request accuracy. Both are physical position observations, not a success verdict.',
  details};
if(fs.existsSync(path.join(directory,'tail-checks.json')))report.injectedControls=read('tail-checks.json');
const output=path.resolve(process.argv[4]??path.join(directory,'handoff-analysis.json'));
assert(!fs.existsSync(output),'Preserve previous handoff analysis');
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({technicalValid:report.technicalValid,deepTrace:report.deepTrace,
  scenes:details.map(d=>({scene:d.scene,state:d.state,arrivalMs:d.arrivalMs,terminalMs:d.terminalMs,
    searches:d.search.calls,nativeStops:d.nativeStops,recoveries:d.recoveries,tailSettleCalls:d.tailSettleCalls}))}));
