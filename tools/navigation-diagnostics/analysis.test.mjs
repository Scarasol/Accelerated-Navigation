import { test } from 'node:test';
import assert from 'node:assert/strict';
import { unionLength, quantile, traceIntegrity, qualificationReasons, category, queryReadyBeforeAdoption } from './analyze.mjs';
import { handoffs } from './summarize-coverage.mjs';
import { classifyJfrSample } from './jfr-classification.mjs';

test('nested and parallel intervals are counted as a union', () => {
  assert.equal(unionLength([[0, 10],[2, 8],[5, 15],[20, 21]]),16);
  assert.equal(unionLength([[0, 10],[2, 8],[5, 15],[20, 21]],3,12),9);
  assert.equal(unionLength([],3,12),0);
});
test('missing latency does not become zero', () => {
  assert.equal(quantile([null,undefined,NaN],.5),null);
  assert.equal(quantile([1,2,null,3],.5),2);
  assert.equal(quantile([1,2,3],.99),3);
});
test('overflow and unfinished spans cannot pass technical validity',()=>{
  assert.deepEqual(traceIntegrity([{depth:0,errors:0,dropped:1}],[]),['trace nesting/capacity invalid']);
  assert.deepEqual(traceIntegrity([{depth:1,errors:0,dropped:0}],[]),['trace nesting/capacity invalid']);
  assert.deepEqual(traceIntegrity([], [{start:20,end:0,exclusive:0,bytes:1,exclusiveBytes:1}]),['bad event span']);
});
test('qualification reasons preserve overlapping blockers and negative section boundaries',()=>{
  assert.deepEqual(qualificationReasons(1,.6,1.95,[-1,81,0],[0,81,0]),['accuracy','section']);
  assert.deepEqual(qualificationReasons(0,.6,1.95,[-16,81,0],[-1,81,0]),[]);
  assert.deepEqual(qualificationReasons(0,1.1,1,[0,81,0],[0,81,0]),['width','height']);
});
test('handoffs keep retries and normal advances separate without crossing request generations',()=>{
  const publish=(queryId,startMs,cursor,publication)=>({phase:'SegmentedNavigationService.publish',
    queryId,startMs,endMs:startMs+1,cursor,publication,last:false});
  const trial={observationsOriginNanos:1e9,observations:[
    {queryId:'a',segmentPublication:1,nanoTime:1.01e9,segmentEntry:true,stable:3,nodeCount:3,nodeIndex:2},
    {queryId:'a',segmentPublication:1,nanoTime:1.02e9,segmentEntry:true,stable:4,nodeCount:3,nodeIndex:3}
  ],requests:[{steps:[publish('a',0,0,0),
    {phase:'SegmentedNavigationService.findSegment',startMs:30},
    publish('a',40,2,1),publish('a',60,2,2),publish('b',80,0,0)]}]};
  const rows=handoffs(trial);
  assert.equal(rows.length,2);
  assert.equal(rows[0].type,'normalAdvance');
  assert.equal(rows[0].doneToSearchMs,10);
  assert.equal(rows[0].entryToNextPublicationMs,30);
  assert.equal(rows[1].type,'retry');
});
test('tick envelopes remain scheduler work instead of fact maintenance',()=>{
  assert.equal(category('NavigationScheduler.finishTickEnvelope(Z)V'),'scheduler');
  assert.equal(category('TopologyService.finishTick()V'),'factsAndMaintenance');
  assert.equal(category('NavigationPoiAdapter.validSelection(...)Z'),'poiAdapter');
});
test('tail retirement is distinguished even when END observation misses the same-tick entry',()=>{
  const trial={observations:[],requests:[{steps:[
    {phase:'SegmentedNavigationService.publish',queryId:'a',startMs:0,endMs:1,cursor:0,publication:0,last:false},
    {phase:'SegmentedNavigationService.settleTail',queryId:'a',startMs:20,endMs:21,cursor:0},
    {phase:'SegmentedNavigationService.findSegment',queryId:'a',startMs:22,endMs:23,cursor:2},
    {phase:'SegmentedNavigationService.publish',queryId:'a',startMs:24,endMs:25,cursor:2,publication:1,last:true}
  ]}]};
  const [row]=handoffs(trial);
  assert.equal(row.type,'tailAdvance');
  assert.equal(row.settleStartMs,20);
  assert.equal(row.destinationEntryObservedMs,null);
  assert.equal(row.oldPathDoneObservedMs,null);
});
test('an adopted segment becoming reachable is not an earlier query decision',()=>{
  assert.equal(queryReadyBeforeAdoption({queryReadyNanos:150,adoptedNanos:1}),false);
  assert.equal(queryReadyBeforeAdoption({queryReadyNanos:50,adoptedNanos:51}),true);
  assert.equal(queryReadyBeforeAdoption({queryReadyNanos:50,adoptedNanos:0}),true);
});
test('JFR class transformation beneath a production caller is not steady navigation work',()=>{
  assert.equal(classifyJfrSample({class:'com.scarasol.navigation.AutomaticNavigationBridge',
    stack:'java.lang.reflect.Field.getInt;org.spongepowered.asm.mixin.transformer.MixinPreProcessorStandard.transform;AutomaticNavigationBridge.prepareAllowed'}),
    'classLoadingAndTransform');
  assert.equal(classifyJfrSample({class:'com.scarasol.navigation.AutomaticNavigationBridge',
    stack:'java.lang.Class.getMethod;AutomaticNavigationBridge.prepareAllowed'}),'navigationFramework');
});
