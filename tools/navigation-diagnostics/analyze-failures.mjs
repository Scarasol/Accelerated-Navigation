import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

const root=path.resolve(process.argv[2]);
const output=path.resolve(process.argv[3]??path.join(root,'failure-analysis.json'));
assert(!fs.existsSync(output),'Preserve prior analysis');
const read=name=>JSON.parse(fs.readFileSync(path.join(root,name),'utf8').replace(/^\uFEFF/,''));
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const timeline=read('timeline.json'), launch=read('launch.json'), exit=read('exit.json');
const shutdown=read('shutdown.json'), ticks=read('full-ticks.json');
const identity=JSON.parse(fs.readFileSync(path.join(launch.build,'build-identity.json'),'utf8').replace(/^\uFEFF/,''));
assert.equal(launch.buildIdentitySha256,hash(path.join(launch.build,'build-identity.json')));
assert.equal(hash(path.join(root,'mods/accelerated_navigation.jar')),identity.productionJarSha256);
assert.equal(launch.artifacts.find(a=>a.name==='production.jar').sha256,identity.productionJarSha256);
assert(timeline.complete&&!timeline.failure&&shutdown.passed&&exit.exitCode===0&&!exit.timedOut);
assert.equal(exit.concurrentJava.length,0);
assert(!ticks.incomplete&&!ticks.truncated);
for(let i=1;i<ticks.samples.length;i++)
  assert.equal(ticks.samples[i].serverTick,ticks.samples[i-1].serverTick+1);
assert(!fs.readFileSync(path.join(root,'transforms.txt'),'utf8').includes('ERROR '));
assert.equal(timeline.trials.length,timeline.scenes.length);
assert.deepEqual(timeline.trials.map(t=>t.scene.name),timeline.scenes.map(s=>s.name));
const searches=fs.existsSync(path.join(root,'failure-searches.json'))?read('failure-searches.json').searches:[];
const byQuery=new Map();
for(const search of searches) {
  assert(search.stateRestored);
  if(search.effectiveVisitedLimit!==undefined)
    assert.equal(search.effectiveVisitedLimit,Math.trunc(search.maxVisitedNodes*search.visitedMultiplier));
  const repeats=new Map();
  for(const replay of search.replays) {
    const key=`${replay.range}/${replay.regionOffset??search.regionOffset}`;
    if(repeats.has(key))assert.deepEqual(replay.path,repeats.get(key),'Replay order changed result');
    repeats.set(key,replay.path);
    if(replay.range===search.actualRange&&(replay.regionOffset??search.regionOffset)===search.regionOffset)
      assert.deepEqual(replay.path,search.actual,'Same-parameter replay differs from actual');
  }
  if(!byQuery.has(search.queryId))byQuery.set(search.queryId,[]);
  byQuery.get(search.queryId).push(search);
}
const scenes=timeline.trials.map(trial=>{
  assert(trial.submittedNanos-trial.sceneReadyNanos>=30e9);
  assert(trial.submittedNanos-trial.serverStartedNanos>=30e9);
  assert.equal(trial.actors.length,trial.scene.count);
  assert.equal(new Set(trial.actors.map(a=>`${a.tile}/${a.slot}`)).size,trial.actors.length);
  const actors=trial.actors.map(actor=>{
    const records=(actor.queryIds??[actor.queryId]).flatMap(q=>byQuery.get(q)??[]);
    const finals=records.filter(r=>r.last), last=finals.at(-1);
    const flip=last?.replays.find(r=>r.range===last.requestedRange&&r.path.canReach&&!last.actual.canReach);
    const equalLow=last?.replays.find(r=>r.equalRegionRadius!==undefined&&r.range===last.actualRange);
    const equalHigh=last?.replays.find(r=>r.equalRegionRadius!==undefined&&r.range===last.requestedRange);
    return {tile:actor.tile,slot:actor.slot,start:actor.start,goal:actor.goal,state:actor.state,
      arrived:actor.arrivalNanos>0,search:actor.search,queryIds:actor.queryIds,
      actualFinal:last?{range:last.actualRange,requestedRange:last.requestedRange,start:last.start,
        accuracy:last.accuracy,avoided:last.avoided,path:last.actual}:null,
      rangeFlip:!!flip,equalRegionRangeFlip:!!(equalLow&&equalHigh&&!equalLow.path.canReach&&equalHigh.path.canReach),
      reachableReplayLength:flip?.path.length??null,
      maxActualSearchRange:records.length?Math.max(...records.map(r=>r.actualRange)):null};
  });
  const states={};
  for(const a of actors)states[a.state]=(states[a.state]??0)+1;
  return {name:trial.scene.name,count:actors.length,arrived:actors.filter(a=>a.arrived).length,states,
    rangeFlips:actors.filter(a=>a.rangeFlip).length,equalRegionRangeFlips:actors.filter(a=>a.equalRegionRangeFlip).length,
    actors};
});
const result={schema:'NAVIGATION_FAILURE_DIAGNOSIS_V1',valid:true,diagnosticOnly:true,
  productionJarSha256:identity.productionJarSha256,replay:launch.replay===true,
  native:launch.native,searchRecords:searches.length,replayCount:searches.reduce((n,r)=>n+r.replays.length,0),
  scenes,inputHashes:Object.fromEntries(['timeline.json','launch.json','exit.json','shutdown.json','full-ticks.json']
    .concat(fs.existsSync(path.join(root,'failure-searches.json'))?['failure-searches.json']:[])
    .map(name=>[name,hash(path.join(root,name))]))};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({valid:true,searchRecords:result.searchRecords,replayCount:result.replayCount,
  scenes:scenes.map(({actors,...scene})=>scene)},null,2));
