import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

const root=path.resolve(process.argv[2]);
const prefix='com.scarasol.acceleratednavigation.';
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
function tsv(file) {
  const lines=fs.readFileSync(file,'utf8').trimEnd().split(/\r?\n/);
  const fields=lines.shift().split('\t');
  return lines.map(line=>Object.fromEntries(line.split('\t').map((value,index)=>[fields[index],value])));
}
function classify(stack,buildOnlyBase=false) {
  if(/TopologyService\.scanSection/.test(stack))return 'factScan';
  if(/NativeNavigationSearch\.|PathFinder\.m_77427_/.test(stack))return 'nativeSearch';
  if(/GroundSegmentWindow\./.test(stack))return 'window';
  if(/BoundaryGeometry\.|BoundaryPortBits\.|SuperClusterTopology\.boundaryLinks/.test(stack))return 'boundary';
  if((buildOnlyBase?/BaseClusterTopology\.build/:/BaseClusterTopology\./).test(stack))return 'base';
  if(/SuperClusterTopology\.build/.test(stack))return 'super';
  if(/SharedRows\.|RuntimeSharedTopology\./.test(stack))return 'shared';
  if(/MacroSearch\./.test(stack))return 'macroSearch';
  if(/RegionFacts|TopologyPersistence|TopologyStore|SectionFactsStore/.test(stack))return 'storage';
  if(/TopologyWorkerRuntime/.test(stack))return 'runtime';
  if(/TopologyTaskExecutor/.test(stack))return 'executor';
  return stack.includes(prefix)?'otherModule':'other';
}
function runtimePart(stack) {
  if(/markLatestDerivedStale|invalidateHaloDependents|invalidateDerived|retireBase|retireSuper/.test(stack))
    return 'invalidation';
  if(/changeFactReferences|changeTopologyFactReferences|pinFacts|unpinFacts|holdFacts|releaseHeldFacts|pinBase|releaseBasePin|pinSuper|releaseSuperPin|removeIdleFact/.test(stack))
    return 'references';
  if(/beginBuild|publish|prepareDemand|requestClusterDependency|beginSuperBuild|beginSuperRequest|completeSuperChildren/.test(stack))
    return 'buildManagement';
  if(/MacroQuery|MacroFlight|ResolveFlight|joinCanonical|joinResolve|requestPendingSections/.test(stack))
    return 'queryDependencies';
  if(/applySectionEvent|processSectionEvents/.test(stack))return 'eventApplication';
  if(/evict|Cache|Idle/.test(stack))return 'cacheManagement';
  return 'runtimeOther';
}
function totals(events,key,weight=()=>1) {
  const rows=new Map();
  for(const event of events) {
    const label=key(event);
    const row=rows.get(label)??{label,samples:0,weight:0};
    row.samples++;row.weight+=weight(event);rows.set(label,row);
  }
  return [...rows.values()].sort((a,b)=>b.weight-a.weight);
}
const samples=tsv(path.join(root,'user-jfr-v3/profile-samples.tsv')).map(row=>({
  ...row,epochMillis:Number(row.epochMillis),weight:Number(row.weight),
  category:classify(row.stack),runtimePart:runtimePart(row.stack),
}));
const worker=sample=>/^accelerated-navigation-topology-\d+$/.test(sample.thread);
const active=samples.filter(sample=>worker(sample)&&sample.type==='jdk.ExecutionSample');
const moduleActive=active.filter(sample=>sample.stack.includes(prefix));
const allocation=samples.filter(sample=>worker(sample)&&sample.type==='jdk.ObjectAllocationSample');
const runtime=moduleActive.filter(sample=>sample.category==='runtime');
const runtimeAlloc=allocation.filter(sample=>sample.category==='runtime');
const firstModule=stack=>stack.split(';').find(frame=>frame.startsWith(prefix))??'noModuleFrame';
const sumWeight=rows=>rows.reduce((sum,row)=>sum+row.weight,0);
function sampleStats(rows,key) {
  return totals(rows,key,row=>row.weight).map(row=>{
    const maximum=Math.max(...rows.filter(sample=>key(sample)===row.label).map(sample=>sample.weight));
    return {...row,maxSampleWeight:maximum,maxSampleShare:maximum/row.weight};
  });
}
const runtimeAllocSites=sampleStats(runtimeAlloc,sample=>firstModule(sample.stack));
const runtimeAllocSiteParts=sampleStats(runtimeAlloc,sample=>`${sample.runtimePart}:${firstModule(sample.stack)}`);
const invalidationAlloc=runtimeAlloc.filter(sample=>sample.runtimePart==='invalidation');
const invalidationAllocSites=sampleStats(invalidationAlloc,sample=>firstModule(sample.stack));
assert.equal(sumWeight(runtimeAllocSites),sumWeight(runtimeAlloc));
assert.equal(sumWeight(runtimeAllocSiteParts),sumWeight(runtimeAlloc));
assert.equal(sumWeight(invalidationAllocSites),sumWeight(invalidationAlloc));
assert.equal(sumWeight(totals(allocation,sample=>sample.category,sample=>sample.weight)),sumWeight(allocation));
const buildScopeCrosswalk=totals(moduleActive,sample=>`${sample.category}->${classify(sample.stack,true)}`);
const noModuleFrames=active.filter(sample=>!sample.stack.includes(prefix));
const profile={
  allWorkerExecutionSamples:active.length,moduleWorkerExecutionSamples:moduleActive.length,
  wallDenominators:{
    noModuleFrameSamples:noModuleFrames.length,
    noModuleFrameStacks:totals(noModuleFrames,sample=>sample.stack),
    observedRuntimeShareOfModule:runtime.length/moduleActive.length,
    observedRuntimeShareOfAllWorker:runtime.length/active.length,
    runtimeShareIfAllUnresolvedRuntime:(runtime.length+noModuleFrames.length)/active.length,
    note:'No module frame is unresolved ownership; unknown_Java/not_walkable frames are not assigned to runtime.',
  },
  classifierSensitivity:{
    alternative:'Only stacks containing BaseClusterTopology.build count as Base; all other rules unchanged. This is NOT the unavailable original side-chat classifier.',
    categories:totals(moduleActive,sample=>classify(sample.stack,true)),
    crosswalk:buildScopeCrosswalk,
    invalidationSamples:moduleActive.filter(sample=>classify(sample.stack,true)==='runtime'
      &&sample.runtimePart==='invalidation').length,
    sideChatOriginalCrosswalkAvailable:false,
  },
  byThread:totals(active,sample=>sample.thread),
  categories:totals(moduleActive,sample=>sample.category),
  runtimeParts:totals(runtime,sample=>sample.runtimePart),
  runtimeLeafSites:totals(runtime,sample=>firstModule(sample.stack)).slice(0,30),
  runtimeLeafStacks:totals(runtime,sample=>sample.stack.split(';').slice(0,9).join(';')).slice(0,18),
  allocationSamples:allocation.length,allocationSampleWeight:allocation.reduce((sum,row)=>sum+row.weight,0),
  allocationCategories:totals(allocation,sample=>sample.category,sample=>sample.weight),
  runtimeAllocationParts:totals(runtimeAlloc,sample=>sample.runtimePart,sample=>sample.weight),
  runtimeAllocationSites:runtimeAllocSites.slice(0,20),
  runtimeAllocationSitesAll:runtimeAllocSites,
  runtimeAllocationSiteParts:runtimeAllocSiteParts,
  invalidationAllocationSites:invalidationAllocSites,
  allocationConservation:{worker:sumWeight(allocation),runtime:sumWeight(runtimeAlloc),
    invalidation:sumWeight(invalidationAlloc),runtimeSiteTotal:sumWeight(runtimeAllocSites),
    runtimeSitePartTotal:sumWeight(runtimeAllocSiteParts),invalidationSiteTotal:sumWeight(invalidationAllocSites)},
  runtimeAllocationClasses:totals(runtimeAlloc,sample=>sample.objectClass,sample=>sample.weight).slice(0,15),
  minuteBuckets:totals(moduleActive,sample=>`${Math.floor(sample.epochMillis/60000)*60000}:${sample.category}`),
  sleepSamples:totals(samples.filter(sample=>worker(sample)&&sample.type==='profiler.WallClockSleeping'),
    sample=>sample.thread),
};

const spark=read(path.join(root,'user-spark/spark-decoded.json'));
const sparkInclusive=new Map(),sparkLeaf=new Map(),sparkCategories=new Map();
let nodeCount=0,negative=0;
for(const thread of spark.threads) {
  const seen=new Set(),flat=thread.children;
  function visit(node,stack,index) {
    if(index!==undefined){assert(!seen.has(index));seen.add(index);nodeCount++;}
    const name=node.class_name?`${node.class_name}.${node.method_name}`:'';
    const current=name?[...stack,name]:stack;
    const own=(node.times??[]).reduce((sum,value)=>sum+value,0);
    const children=node.children_refs??[];
    const childrenTotal=children.reduce((sum,child)=>sum+(flat[child].times??[]).reduce((n,v)=>n+v,0),0);
    const exclusive=own-childrenTotal;
    if(exclusive < -0.01)negative++;
    const joined=current.join(';'),category=classify(joined);
    sparkCategories.set(category,(sparkCategories.get(category)??0)+exclusive);
    const moduleFrames=current.filter(frame=>frame.startsWith(prefix));
    if(moduleFrames.length) {
      const leaf=moduleFrames.at(-1);
      sparkLeaf.set(leaf,(sparkLeaf.get(leaf)??0)+exclusive);
      for(const frame of new Set(moduleFrames))
        sparkInclusive.set(frame,(sparkInclusive.get(frame)??0)+exclusive);
    }
    for(const child of children)visit(flat[child],current,child);
  }
  visit(thread,[]);
  assert.equal(seen.size,flat.length);
}
assert.equal(negative,0);
const sorted=map=>[...map].map(([label,weight])=>({label,weight})).sort((a,b)=>b.weight-a.weight);
const totalSpark=spark.threads.reduce((sum,thread)=>sum+thread.times.reduce((n,v)=>n+v,0),0);
assert(Math.abs([...sparkCategories.values()].reduce((a,b)=>a+b,0)-totalSpark)<0.01);
const output={
  schema:'STEP8_EXISTING_PROFILE_ATTRIBUTION_V1',diagnosticOnly:true,
  caveats:['Wall-mode execution samples are not CPU accounting.',
    'Exclusive categories use ordered stack rules; category definitions are explicit in this script.',
    'Allocation weights are sampling estimates, not exact totals or retained memory.',
    'No request identity links exist in the user recording; global maintenance cannot be assigned to the cold query.'],
  sourceSha256:{
    spark:sha(path.resolve('logs/nTgd0rKzHj.sparkprofile')),
    jfr:sha('C:/Users/Administrator/IdeaSnapshots/runClient_2026_10_18_001317.jfr'),
    latest:sha(path.resolve('run/logs/latest.log')),debug:sha(path.resolve('run/logs/debug.log')),
  },
  jfr:profile,
  spark:{startEpochMillis:spark.metadata.start_time,endEpochMillis:spark.metadata.end_time,
    threads:spark.threads.map(thread=>thread.name),sampleWeightMs:totalSpark,nodeCount,
    categoriesMs:sorted(sparkCategories),moduleInnermostMs:sorted(sparkLeaf).slice(0,25),
    moduleInclusiveMs:sorted(sparkInclusive).slice(0,25)},
};
const destination=path.resolve(process.argv[3]??path.join(root,'existing-observation.json'));
fs.writeFileSync(destination,JSON.stringify(output,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({workerSamples:active.length,moduleSamples:moduleActive.length,
  categories:profile.categories,runtimeParts:profile.runtimeParts,
  runtimeAllocationParts:profile.runtimeAllocationParts,spark:output.spark.categoriesMs},null,2));
