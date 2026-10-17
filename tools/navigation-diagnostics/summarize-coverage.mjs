import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {coverageScenes} from './analyze.mjs';

export function handoffs(trial) {
  const observations=trial.observations??[],output=[];
  for(const request of trial.requests) {
    const publications=request.steps.filter(s=>s.phase==='SegmentedNavigationService.publish');
    for(let i=1;i<publications.length;i++) {
      const previous=publications[i-1],next=publications[i];
      if(previous.queryId!==next.queryId)continue;
      const between=request.steps.filter(s=>s.startMs>=previous.endMs&&s.startMs<=next.startMs);
      const replan=between.some(s=>s.phase==='SegmentedNavigationService.replan');
      const tail=between.find(s=>s.phase==='SegmentedNavigationService.settleTail');
      const type=replan?'replan':next.cursor>previous.cursor?(tail?'tailAdvance':'normalAdvance'):'retry';
      const visible=observations.filter(o=>o.queryId===previous.queryId&&
        o.segmentPublication===previous.publication+1);
      const origin=trial.observationsOriginNanos;
      const ms=o=>o?(o.nanoTime-origin)/1e6:null;
      const entry=visible.find(o=>o.segmentEntry);
      const done=visible.find(o=>o.nodeCount>0&&o.nodeIndex>=o.nodeCount);
      const search=between.find(s=>s.phase==='SegmentedNavigationService.findSegment');
      const settle=tail??between.find(s=>s.phase==='SegmentedNavigationService.settle');
      const physical=between.find(s=>s.phase==='SegmentedNavigationService.physical'&&s.resultLabel==='true');
      const after=observations.filter(o=>o.queryId===next.queryId&&o.segmentPublication===next.publication+1);
      const baseline=after[0];
      const resumed=baseline?after.find(o=>o.position.some((v,j)=>Math.abs(v-baseline.position[j])>.05)):null;
      output.push({queryId:previous.queryId,type,oldPublication:previous.publication+1,
        newPublication:next.publication+1,oldCursor:previous.cursor,newCursor:next.cursor,
        oldPathLast:previous.last,oldPublicationMs:previous.startMs,
        destinationEntryObservedMs:ms(entry),stableAtDestinationEntry:entry?.stable??null,
        oldPathDoneObservedMs:ms(done),settleStartMs:settle?.startMs??null,
        physicalReadyMs:physical?.startMs??null,nextSearchMs:search?.startMs??null,
        nextPublicationMs:next.startMs,movementResumedObservedMs:ms(resumed),
        doneToSearchMs:done&&search?search.startMs-ms(done):null,
        entryToNextPublicationMs:entry?next.startMs-ms(entry):null,
        retriesBetween:between.filter(s=>s.phase==='SegmentedNavigationService.recover').length});
    }
  }
  return output;
}
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const root=process.argv[2],inputs=process.argv.slice(3);
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  assert(root,'Usage: node summarize-coverage.mjs NEW_OUTPUT ANALYSIS_JSON [...]');
  assert(inputs.length,'Usage: node summarize-coverage.mjs NEW_OUTPUT ANALYSIS_JSON [...]');
  assert(!fs.existsSync(root),'Preserve previous summary');
  const reports=inputs.map(read);assert(reports.every(r=>r.valid&&r.deepTrace),'Only valid traced runs');
  const trials=reports.flatMap(r=>r.trials);
  const cells=coverageScenes.map(scene=>{
    const matches=trials.filter(t=>t.scene.name===scene&&t.arrivalScopeValid!==false);
    assert(matches.length===1,`Need exactly one valid coverage cell: ${scene}`);
    return matches[0];
  });
  const details=cells.map(t=>{
    const r=t.requests[0],raw=read(path.join(reports.find(report=>report.trials.includes(t)).directory,'timeline.json'))
      .trials.find(x=>x.scene.name===t.scene.name);
    const steps=r.steps,observations=t.observations??[];
    const calls=name=>steps.filter(s=>s.phase===`SegmentedNavigationService.${name}`).length;
    const rows=t.phaseTotals;
    const cost=prefix=>{
      const selected=rows.filter(p=>p.name.startsWith(prefix));
      return {calls:selected.reduce((n,p)=>n+p.calls,0),exclusiveNanos:selected.reduce((n,p)=>n+p.exclusiveNanos,0),
        allocatedBytes:selected.reduce((n,p)=>n+p.allocatedBytes,0)};
    };
    return {scene:t.scene.name,state:r.state,arrived:r.arrivalMs!==null,firstReadyMs:r.firstReadyMs,
      firstMoveMs:r.firstMoveMs,arrivalMs:r.arrivalMs,elapsedMs:t.elapsedMs,
      queryReadyMs:r.queryReadyMs,adoptedMs:r.adoptedMs,requests:r.requestGenerations,
      handleReachableObservedMs:r.handleReachableObservedMs,
      decisionEndMs:r.steps.filter(s=>s.phase==='SegmentedNavigationService.nativeDecision').at(-1)?.endMs??null,
      apiCounts:t.apiCounts,entryCensus:t.entryCensus,searches:r.searches,partial:r.partialSearches,
      actualSearches:r.actualSearches,
      recover:calls('recover'),refresh:calls('refresh'),replan:calls('replan'),
      maxAttempt:Math.max(0,...steps.map(s=>s.attempt)),
      minObservedStride:Math.min(...steps.map(s=>s.stride).filter(v=>v!==null&&v>0)),
      maxRecoveryCount:Math.max(0,...steps.map(s=>s.recoveryCount??0)),
      targetDrifts:(t.actions??[]).filter(a=>a.name==='target-drift').length,
      actions:t.actions,sharedQualification:t.sharedQualification,qualifications:t.sharedQualificationInputs,
      costs:t.costs,mainStages:t.mainStages,workerStages:t.workerStages,
      selectedCosts:{nativeFind:cost('NativeNavigationSearch.find'),normalize:cost('NativeNavigationSearch.normalize'),
        capabilities:cost('NavigationCapabilities.'),proof:cost('NativeQueryProof.capture'),
        install:cost('SegmentedNavigationService.publish'),facts:cost('TopologyService.scanSection'),
        maintenance:cost('TopologyService.finishTick'),window:t.windowWorkerCosts},
      handoffs:handoffs({...t,observationsOriginNanos:raw.submittedNanos}),
      observations:observations.length,actualBaseBuilds:t.actualBaseBuilds,actualSuperBuildCalls:t.actualSuperBuildCalls,
      scope:t.scene.name==='native-stuck-recovery'?'Controlled immobilization; actual native stuck detection':
        ['ai-goalselector','brain-walk-target','poi-acquire'].includes(t.scene.name)?'Natural production AI entry':'Controlled API or world case'};
  });
  const byName=Object.fromEntries(details.map(d=>[d.scene,d]));
  const checks=[];
  const check=(name,passed)=>checks.push({name,passed:Boolean(passed)});
  const entity=byName['entity-stationary'],drift=byName['entity-drift'],ai=byName['ai-goalselector'];
  check('entity-target-intent',entity.requests.some(r=>r.entityTarget));
  check('three-real-target-drifts',drift.targetDrifts===3&&drift.replan>0);
  check('natural-goalselector-controller',ai.requests.some(r=>r.controller.includes('MeleeAttackGoal')));
  check('natural-brain-controller',byName['brain-walk-target'].requests.some(r=>r.controller.includes('MoveToTargetSink')));
  const only=byName['query-only-multi'],ready=byName['query-ready-adopt-multi'],pending=byName['query-pending-adopt-multi'];
  check('unadopted-full-target-query',only.apiCounts.nativeDecision>0&&only.requests.every(r=>!r.adopted&&r.targetCount===3)
    &&only.firstMoveMs===null&&only.state.startsWith('QUERY_'));
  check('ready-adoption-searches-both-phases',ready.apiCounts.nativeDecision>0&&ready.apiCounts.adopt>0&&ready.searches>=2
    &&ready.queryReadyMs!==null&&ready.adoptedMs>=ready.queryReadyMs);
  check('pending-adoption-skips-decision-search',pending.apiCounts.nativeDecision===0&&pending.searches>0
    &&pending.requests.some(r=>r.adopted&&r.targetCount===3));
  check('shared-qualification-pass',byName['shared-accuracy0'].sharedQualification.accepted>0);
  check('legacy-entry-is-separate',byName['legacy-createpath-move'].entryCensus.legacy>0
    &&byName['legacy-createpath-move'].requests.length===0);
  check('real-poi-business-resumed',byName['poi-acquire'].state==='POI_SELECTED'
    &&byName['poi-acquire'].entryCensus.poiBeforeQuery>0&&byName['poi-acquire'].entryCensus.poiValidSelection>0
    &&byName['poi-acquire'].firstMoveMs===null);
  check('dynamic-block-after-real-publication',byName['dynamic-block'].actions.some(a=>a.name==='blocks-after-publication'));
  const stuck=byName['native-stuck-recovery'];
  check('real-stuck-retry-shrink-refresh',stuck.entryCensus.nativeStopped>0&&stuck.recover>0
    &&stuck.maxAttempt>=2&&stuck.minObservedStride===1&&stuck.refresh>0);
  check('normal-handoff-witness',details.some(d=>d.handoffs.some(h=>h.type==='normalAdvance'&&h.destinationEntryObservedMs!==null)));
  const partialWitnesses=trials.filter(t=>t.requests.some(r=>r.partialSearches>0))
    .map(t=>({scene:t.scene.name,partial:t.requests.reduce((n,r)=>n+r.partialSearches,0)}));
  check('partial-witness',partialWitnesses.length>0);
  const reasons={};
  for(const d of details)for(const q of d.qualifications) {
    const label=q.reasons.length?q.reasons.join('+'):'accepted';
    reasons[label]=(reasons[label]??0)+1;
  }
  const burstLedger=trials.filter(t=>['burst8','burst28'].includes(t.scene.name)).map(t=>{
    const phases={};
    for(const [key,pattern] of Object.entries({
      pathFinder:/^PathFinder\./,nativeFindWrapper:/^NativeNavigationSearch\.find/,
      normalize:/^NativeNavigationSearch\.normalize/,capabilities:/^NavigationCapabilities\./,
      proof:/^NativeQueryProof\.capture/,install:/^SegmentedNavigationService\.publish/,
      physical:/^SegmentedNavigationService\.physical/,observe:/^SegmentedNavigationService\.observe/,
      factScan:/^TopologyService\.scanSection/,factRestore:/^TopologyService\.runOneRecovery/,
      macroComponents:/MacroQuery\.captureComponents/,window:/^GroundSegmentWindow\./
    })) {
      const rows=t.phaseTotals.filter(p=>pattern.test(p.name));
      phases[key]={entries:rows.reduce((n,p)=>n+p.calls,0),
        exclusiveNanos:rows.reduce((n,p)=>n+p.exclusiveNanos,0),
        allocatedBytes:rows.reduce((n,p)=>n+p.allocatedBytes,0)};
    }
    return {scene:t.scene.name,diagnosticOnly:true,phases};
  });
  const excludedCells=trials.filter(t=>t.arrivalScopeValid===false).map(t=>({
    scene:t.scene.name,reason:'Legacy arrival was measured against the unselected reference goal; use corrected replay'}));
  const summary={schema:'NAVIGATION_T1_COVERAGE_SUMMARY_V1',diagnosticOnly:true,
    performanceVerdict:'NOT_EVALUATED',scope:'Controlled entry census; per-scene denominators; no invented live-server weights',
    coverageComplete:checks.every(c=>c.passed),checks,excludedCells,partialWitnesses,sharedReasonCombinations:reasons,burstLedger,details,
    sources:inputs.map(file=>({path:path.resolve(file),sha256:hash(file)}))};
  fs.mkdirSync(root,{recursive:true});
  fs.writeFileSync(path.join(root,'coverage-summary.json'),JSON.stringify(summary,null,2)+'\n');
  const n=v=>v===null?'null':v.toFixed(3);
  fs.writeFileSync(path.join(root,'coverage-summary.md'),
    '# T1 受控入口与请求结构补测\n\n仅作诊断；逐场景分母，不代表实服入口权重。\n\n'+
    '| 场景 | 结果 | 首Path ms | 搜索/partial | recover/refresh/replan | 主线程分配 MiB |\n'+
    '| --- | --- | --- | --- | --- | --- |\n'+details.map(d=>
      `| ${d.scene} | ${d.state} | ${n(d.firstReadyMs)} | ${d.searches}/${d.partial} | ${d.recover}/${d.refresh}/${d.replan} | ${n(d.costs.mainAllocatedBytes/1048576)} |`).join('\n')+
    '\n\n'+checks.map(c=>`- ${c.passed?'通过':'未覆盖'}：${c.name}`).join('\n')+'\n');
  console.log(JSON.stringify({coverageComplete:summary.coverageComplete,checks,sharedReasonCombinations:reasons}));
  if(!summary.coverageComplete)process.exitCode=1;
}
