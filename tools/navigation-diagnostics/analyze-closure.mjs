import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {analyze,unionLength} from './analyze.mjs';

const directory=path.resolve(process.argv[2]);
const destination=path.resolve(process.argv[3]??path.join(directory,'closure-analysis.json'));
const read=file=>JSON.parse(fs.readFileSync(path.join(directory,file),'utf8').replace(/^\uFEFF/,''));
function tsv(file,numeric=true) {
  const lines=fs.readFileSync(path.join(directory,file),'utf8').trimEnd().split(/\r?\n/);
  const fields=lines.shift().split('\t');
  return lines.map(line=>Object.fromEntries(line.split('\t').map((value,index)=>
    [fields[index],numeric&&/^-?\d+$/.test(value)?Number(value):value])));
}
const publicAnalysis=analyze(directory);
assert(publicAnalysis.valid,JSON.stringify(publicAnalysis.errors));
const timeline=read('timeline.json');
const phases=fs.readFileSync(path.join(directory,'phases.txt'),'utf8').trimEnd().split(/\r?\n/);
const events=tsv('events.tsv').map((event,index)=>({...event,index,name:phases[event.phase],children:[]}));
const threads=new Map(tsv('trace-status.tsv',false).map(row=>[Number(row.thread),row.name]));
const objects=new Map();
for(const row of tsv('objects.tsv',false)) {
  const id=Number(row.id),object=objects.get(id)??{id,class:row.class,fields:{}};
  if(!row.field)object.label=row.value;
  else object.fields[row.field]=row.kind==='ref'?{ref:Number(row.value)}:row.value;
  objects.set(id,object);
}
const object=id=>objects.get(id);
const ref=(id,field)=>object(id)?.fields[field]?.ref??0;
const scalar=(id,field)=>object(id)?.fields[field];
const label=id=>object(id)?.label??'';
const items=id=>Object.entries(object(id)?.fields??{}).filter(([key])=>key.startsWith('item'))
  .map(([,value])=>value.ref);
const is=(id,suffix)=>object(id)?.class.endsWith(suffix);
function geometry(id) {
  const fields=object(id)?.fields??{};
  return JSON.stringify(Object.fromEntries(Object.entries(fields).filter(([,value])=>typeof value==='string')
    .sort(([a],[b])=>a.localeCompare(b))));
}
function identityKey(id) {
  if(!id)return '';
  const fields=object(id)?.fields??{};
  return `${fields.kind??''}:${label(ref(id,'position'))}:${label(ref(id,'target'))}:${fields.face??''}`;
}
function subtract(start,end,children) {
  let cursor=start;
  const result=[];
  for(const child of children.toSorted((a,b)=>a.start-b.start)) {
    if(child.start>cursor)result.push([cursor,child.start]);
    cursor=Math.max(cursor,child.end);
  }
  if(cursor<end)result.push([cursor,end]);
  return result;
}
for(const thread of new Set(events.map(event=>event.thread))) {
  const stack=[];
  for(const event of events.filter(event=>event.thread===thread).sort((a,b)=>a.start-b.start||a.depth-b.depth)) {
    while(stack.length&&stack.at(-1).end<=event.start)stack.pop();
    if(stack.length) {
      const parent=stack.at(-1);
      assert(event.end<=parent.end,`crossed spans ${parent.name} / ${event.name}`);
      parent.children.push(event);
      event.parent=parent;
    }
    stack.push(event);
  }
}
function category(name) {
  if(/workerEnqueue|workerSelected|serverEnqueue|actorBind|searchParameters|sharedParameters/.test(name))return 'tracePoints';
  if(/BaseClusterTopology\./.test(name))return 'baseKernel';
  if(/SuperClusterTopology\.boundaryLinks|BoundaryPortBits\./.test(name))return 'boundaryKernel';
  if(/SuperClusterTopology\.build/.test(name))return 'superKernel';
  if(/markLatestDerivedStale|invalidateHaloDependents/.test(name))return 'invalidation';
  if(/changeFactReferences|changeTopologyFactReferences|holdFacts|releaseHeldFacts|pinBase|releaseBasePin|pinSuper|releaseSuperPin/.test(name))
    return 'references';
  if(/processSectionEvents|applySectionEvent/.test(name))return 'eventApplication';
  if(/beginBuild|prepareDemand|buildInput|captureBaseStamps|publish|buildSuperCluster|buildBaseBoundaryLinks|buildSuperBoundaryLinks|beginSuperBuild|beginSuperRequest|completeSuperChildren|beginSuperBoundaryChildren/.test(name))
    return 'buildManagement';
  if(/TopologyGraph|MacroSearch\.step/.test(name))return 'macroExpansion';
  if(/MacroQuery|MacroFlight|MacroRequest|ResolveFlight|joinCanonical|joinResolve|requestClusterDependency|requestSuperCluster|requestBaseBoundaryLinks|requestSuperBoundaryLinks|requestMacroQuery|finishCorridors|replayCompletedCorridor/.test(name))
    return 'dependencyAndDelivery';
  if(/runtimeTransition/.test(name))return 'runtimeOther';
  if(/GroundSegmentWindow/.test(name))return 'window';
  if(/PathFinder|NativeNavigationSearch|NativeQueryProof|NavigationCapabilities/.test(name))return 'nativeSearch';
  if(/scanSection|runOneRecovery|drainFactDemand|finishTick|drainPersistence/.test(name))return 'factsAndMaintenance';
  if(/NavigationScheduler/.test(name))return 'scheduler';
  if(/SegmentedNavigationService|NavigationRequestPath|AutomaticNavigationBridge/.test(name))return 'navigation';
  if(name==='workerRun')return 'workerOther';
  return 'other';
}
function costs(rows,from,to) {
  const groups=new Map();
  for(const event of rows) {
    const intervals=subtract(event.start,event.end,event.children);
    const exclusive=unionLength(intervals,from,to);
    if(!exclusive)continue;
    const key=category(event.name),row=groups.get(key)??{category:key,calls:0,exclusiveWallNanos:0,
      fullyContainedExclusiveBytes:0,fullyContainedExclusiveCpuNanos:0,boundarySpans:0};
    row.calls++;row.exclusiveWallNanos+=exclusive;
    if(event.start>=from&&event.end<=to) {
      row.fullyContainedExclusiveBytes+=event.exclusiveBytes;
      row.fullyContainedExclusiveCpuNanos+=event.exclusiveCpu??0;
    } else row.boundarySpans++;
    groups.set(key,row);
  }
  return [...groups.values()].sort((a,b)=>b.exclusiveWallNanos-a.exclusiveWallNanos);
}
const trials=timeline.trials.map((trial,index)=>{
  const rows=events.filter(event=>event.trial===trial.trial);
  const select=pattern=>rows.filter(event=>pattern.test(event.name));
  const firstPath=trial.actors[0].firstReadyNanos,from=trial.submittedNanos,to=trial.closedNanos;
  const workerRows=rows.filter(event=>/^accelerated-navigation-topology-\d+$/.test(threads.get(event.thread)??''));
  const baseBuilds=select(/^BaseClusterTopology\.build\(/).filter(event=>event.result);
  const superBuilds=select(/^SuperClusterTopology\.build\(/).filter(event=>event.result);
  const aggregateInputs=new Set(superBuilds.flatMap(event=>items(event.b)));
  const requestedParents=select(/^TopologyWorkerRuntime\.beginSuperRequest/).map(event=>({
    entry:event.b,origin:label(ref(event.a,'origin')),geometry:geometry(ref(event.a,'geometry')),
  }));
  const endpointInputs=new Set(select(/^TopologyWorkerRuntime\$ResolveFlight\.resolveOnWorker/)
    .flatMap(event=>items(event.a).map(id=>ref(id,'topology'))).filter(Boolean));
  function parentContains(parent,base) {
    const numbers=value=>[...value.matchAll(/(?:x|y|z)=(-?\d+)/g)].map(match=>Number(match[1]));
    const origin=numbers(parent.origin),section=numbers(base.section);
    return origin.length===3&&section.length===3&&parent.geometry===base.geometry
      &&section.every((value,axis)=>value>=origin[axis]&&value<origin[axis]+2);
  }
  const capturedBases=new Set(select(/TopologyGraph\.(captureInitialTopology|attachTopology)/)
    .filter(event=>!event.name.startsWith('TopologyWorkerRuntime$SuperTopologyGraph'))
    .map(event=>event.name.includes('.attachTopology')?event.b:ref(event.result,'topology')).filter(Boolean));
  const expanded=new Set(select(/^TopologyWorkerRuntime\$TopologyGraph\.expandInto/)
    .filter(event=>is(event.a,'MacroSearch$ComponentEndpoint'))
    .map(event=>`${label(ref(event.a,'section'))}:${scalar(event.a,'revision')}`));
  const finalComponents=new Set(select(/^MacroSearch\$ComponentSnapshot\./)
    .map(event=>event.b).filter(Boolean));
  const expandedParentOrigins=new Set(select(/^TopologyWorkerRuntime\$SuperTopologyGraph\.expandInto/)
    .filter(event=>is(event.a,'MacroSearch$AggregateEndpoint')).map(event=>label(ref(event.a,'origin'))));
  const queryIds=new Set(rows.filter(event=>is(event.self,'TopologyWorkerRuntime$MacroQuery')).map(event=>event.self));
  const queryProfiles=[...queryIds].map(id=>({id,hierarchical:scalar(id,'hierarchical'),
    profile:ref(id,'profile'),fields:object(ref(id,'profile'))?.fields}));
  const graphRows=baseBuilds.map(event=>({
    id:event.result,section:label(ref(event.result,'section')),signature:scalar(event.result,'signature'),
    geometry:geometry(ref(event.result,'geometry')),end:event.end,
    usedBySuperAggregation:aggregateInputs.has(event.result),
    capturedByBaseGraph:capturedBases.has(event.result),
    directlyExpanded:expanded.has(`${label(ref(event.result,'section'))}:${scalar(event.result,'signature')}`),
    finalComponentSnapshot:finalComponents.has(event.result),
    endpointResolutionInput:endpointInputs.has(event.result),
  }));
  for(const graph of graphRows)
    graph.requestedParentOrigins=[...new Set(requestedParents.filter(parent=>parentContains(parent,graph)).map(parent=>parent.origin))];
  const byGeometry=new Map();
  for(const graph of graphRows) {
    const row=byGeometry.get(graph.geometry)??{geometry:graph.geometry,built:0,beforeFirstPath:0,
      aggregateInputs:0,requestedParentInputs:0,endpointInputs:0,captured:0,expanded:0,finalSnapshots:0,noObservedConsumer:0};
    row.built++;if(graph.end<=firstPath)row.beforeFirstPath++;
    if(graph.usedBySuperAggregation)row.aggregateInputs++;
    if(graph.requestedParentOrigins.length)row.requestedParentInputs++;
    if(graph.endpointResolutionInput)row.endpointInputs++;
    if(graph.capturedByBaseGraph)row.captured++;
    if(graph.directlyExpanded)row.expanded++;
    if(graph.finalComponentSnapshot)row.finalSnapshots++;
    if(!graph.requestedParentOrigins.length&&!graph.endpointResolutionInput&&!graph.capturedByBaseGraph&&!graph.directlyExpanded&&!graph.finalComponentSnapshot)
      row.noObservedConsumer++;
    byGeometry.set(graph.geometry,row);
  }
  const waiting=new Map(),waits=[];
  for(const event of select(/MacroQuery\.(requestDependency|completeDependencyRequest)/).sort((a,b)=>a.start-b.start)) {
    const key=`${event.self}:${identityKey(event.a)}`;
    if(event.name.includes('.requestDependency')) {
      if(!waiting.has(key))waiting.set(key,event);
    } else if(waiting.has(key)) {
      const start=waiting.get(key);waiting.delete(key);
      waits.push({query:event.self,key:identityKey(event.a),kind:scalar(event.a,'kind'),
        start:start.start,end:event.start});
    }
  }
  const stage=select(/TopologyService\$MacroRequest.completeWorker|MacroFlight.complete\(|NativeNavigationSearch.find|NavigationRequestPath.publish/);
  const waitByKind={};
  for(const kind of new Set(waits.map(wait=>wait.kind))) {
    const values=waits.filter(wait=>wait.kind===kind);
    waitByKind[kind]={completed:values.length,unionBeforeFirstPathNanos:unionLength(values.map(wait=>[wait.start,wait.end]),from,firstPath),
      unionFullTrialNanos:unionLength(values.map(wait=>[wait.start,wait.end]),from,to)};
  }
  const rootWorkerSpans=workerRows.filter(event=>event.depth===0);
  const boundaryOwners={};
  const boundaryPairs=new Map();
  for(const event of select(/^SuperClusterTopology\.boundaryLinks/).sort((a,b)=>a.start-b.start)) {
    const ancestors=[];
    for(let parent=event.parent;parent;parent=parent.parent)ancestors.push(parent.name);
    const owner=ancestors.some(name=>/^SuperClusterTopology\.build/.test(name))?'insideSuperBuild'
      :ancestors.some(name=>/buildSuperBoundaryLinks/.test(name))?'betweenSupers'
      :ancestors.some(name=>/buildBaseBoundaryLinks/.test(name))?'betweenBases':'other';
    boundaryOwners[owner]=(boundaryOwners[owner]??0)+1;
    const key=`${event.a}:${event.b}`;
    const pair=boundaryPairs.get(key)??{source:event.a,target:event.b,calls:0,owners:[],
      inclusiveWallNanos:0,exclusiveWallNanos:0,laterInvocationWallNanos:0};
    if(pair.calls)pair.laterInvocationWallNanos+=event.end-event.start;
    pair.calls++;
    if(!pair.owners.includes(owner))pair.owners.push(owner);
    pair.inclusiveWallNanos+=event.end-event.start;
    pair.exclusiveWallNanos+=unionLength(subtract(event.start,event.end,event.children));
    boundaryPairs.set(key,pair);
  }
  assert.equal([...boundaryPairs.values()].reduce((sum,pair)=>sum+pair.calls,0),
    select(/^SuperClusterTopology\.boundaryLinks/).length,'ordered Base pair accounting must close');
  const referenceEvents=workerRows.filter(event=>category(event.name)==='references');
  const referenceMethods=[...new Set(referenceEvents.map(event=>event.name))].map(name=>({
    name,calls:referenceEvents.filter(event=>event.name===name).length,
    callsBeforeFirstPath:referenceEvents.filter(event=>event.name===name&&event.start<firstPath).length,
  }));
  for(const event of superBuilds)assert.equal(items(event.b).length,8,'Super must bind eight input identities');
  const workerUnionByThread=[...new Set(workerRows.map(event=>event.thread))].map(thread=>({
    thread:threads.get(thread),wallNanos:unionLength(rootWorkerSpans.filter(event=>event.thread===thread)
      .map(event=>[event.start,event.end]),from,to)}));
  const totalExclusive=costs(workerRows,from,to).reduce((sum,row)=>sum+row.exclusiveWallNanos,0);
  assert.equal(totalExclusive,workerUnionByThread.reduce((sum,row)=>sum+row.wallNanos,0),'worker wall partition must close');
  return {
    scene:trial.scene,technicalValid:true,state:trial.actors[0].state,arrived:trial.actors[0].arrivalNanos>0,
    firstPathMs:firstPath?(firstPath-from)/1e6:null,arrivalMs:trial.actors[0].arrivalNanos?(trial.actors[0].arrivalNanos-from)/1e6:null,
    publicCosts:publicAnalysis.trials[index].costs,search:trial.actors[0].search,
    topologyBefore:trial.topologyBefore,topologyAfter:trial.topologyAfter,
    uniqueBuiltBases:new Set(baseBuilds.map(event=>event.result)).size,
    uniqueBuiltSupers:new Set(superBuilds.map(event=>event.result)).size,
    uniqueRequestedParentEntries:new Set(requestedParents.map(parent=>parent.entry)).size,
    directlyExpandedParentOrigins:[...expandedParentOrigins],
    superInputs:superBuilds.map(event=>({super:event.result,origin:label(event.a),children:items(event.b),
      directlyExpanded:expandedParentOrigins.has(label(event.a)),
      containsFinalComponent:items(event.b).some(id=>finalComponents.has(id))})),
    baseUsageByGeometry:[...byGeometry.values()],baseObjects:graphRows,queryProfiles,
    boundaryCalls:select(/^SuperClusterTopology\.boundaryLinks/).length,
    boundaryCallsByOwner:boundaryOwners,
    boundaryIdentityPairs:{
      key:'ordered source and target Base object identities; compatible immutable section positions fix face',
      unique:boundaryPairs.size,
      repeatedCalls:[...boundaryPairs.values()].reduce((sum,pair)=>sum+pair.calls-1,0),
      laterInvocationWallNanos:[...boundaryPairs.values()].reduce((sum,pair)=>sum+pair.laterInvocationWallNanos,0),
      multiplicities:Object.fromEntries([...new Set([...boundaryPairs.values()].map(pair=>pair.calls))]
        .sort((a,b)=>a-b).map(count=>[count,[...boundaryPairs.values()].filter(pair=>pair.calls===count).length])),
      repeated:[...boundaryPairs.values()].filter(pair=>pair.calls>1),
    },
    zeroPortRejects:select(/^BoundaryPortBits\.mayLink/).filter(event=>label(event.result)==='false').length,
    dependencyWaits:waits,dependencyWaitsByKind:waitByKind,
    requestsWithoutCompletionObservations:[...waiting].map(([key,event])=>({key,start:event.start})),
    dependencyWaitUnionBeforeFirstPathNanos:unionLength(waits.map(wait=>[wait.start,wait.end]),from,firstPath),
    dependencyWaitUpperBoundBeforeFirstPathNanos:unionLength([...waits.map(wait=>[wait.start,wait.end]),
      ...[...waiting.values()].map(event=>[event.start,firstPath])],from,firstPath),
    workerBeforeFirstPath:costs(workerRows,from,firstPath),
    workerAfterFirstPath:costs(workerRows,firstPath,to),
    workerFullTrial:costs(workerRows,from,to),workerUnionByThread,
    referenceMethods,
    referenceInvocationCounts:{
      fullTrial:referenceEvents.length,
      beforeFirstPath:referenceEvents.filter(event=>event.start<firstPath).length,
      positiveExclusiveWallBeforeFirstPath:costs(workerRows,from,firstPath)
        .find(row=>row.category==='references')?.calls??0,
    },
    mainBeforeFirstPath:costs(rows.filter(event=>threads.get(event.thread)==='Server thread'),from,firstPath),
    mainFullTrial:costs(rows.filter(event=>threads.get(event.thread)==='Server thread'),from,to),
    stageTimeline:stage.map(event=>({phase:event.name,self:event.self,startMs:(event.start-from)/1e6,endMs:(event.end-from)/1e6})),
  };
});
const output={schema:'STEP8_CURRENT_CLOSURE_ATTRIBUTION_V1',diagnosticOnly:true,technicalValid:true,
  stablePerformanceQualified:false,productionSemanticsChanged:false,
  limitations:['One current-source JVM; cold/warm is an ordered diagnostic, not an independent performance comparison.',
    'CPU counters are platform-quantized; fine-phase zero and ratios are not CPU proofs.',
    'Wall is partitioned within each traced thread; parallel threads and dependency waits overlap.',
    'Boundary-crossing spans have no time-localized allocation or CPU assignment.',
    'Object references and reflection perturb allocation and retention; noObservedConsumer is not proof of useless work.',
    'Cost table calls count spans with positive exclusive wall overlap; referenceInvocationCounts includes zero-duration spans.',
    'Graph labels are read after execution; use immutable identity fields only.'],
  productionJarSha256:read('launch.json').artifacts.find(row=>row.name==='production.jar').sha256,
  inputSha256:Object.fromEntries(['events.tsv','objects.tsv','phases.txt','timeline.json','trace-status.tsv'].map(file=>
    [file,crypto.createHash('sha256').update(fs.readFileSync(path.join(directory,file))).digest('hex')])),
  trials};
fs.writeFileSync(destination,JSON.stringify(output,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(trials.map(trial=>({scene:trial.scene.name,state:trial.state,firstPathMs:trial.firstPathMs,
  bases:trial.uniqueBuiltBases,supers:trial.uniqueBuiltSupers,byGeometry:trial.baseUsageByGeometry,
  workerBeforeFirstPath:trial.workerBeforeFirstPath})),null,2));
