import { test } from "node:test";
import assert from "node:assert/strict";
import { checkTrial } from "./analyze-short-native.mjs";

const search=(segmented=false)=>({queryId:"a",adopted:true,segmented,last:true,range:35,requestedRange:35,
  accuracy:1,requestedAccuracy:1,wallNanos:2,cpuNanos:1,allocatedBytes:8});
const trial=()=>({name:"flat8",accepted:true,arrived:true,requests:[{queryId:"a",state:"SUCCEEDED"}],
  position:[8,81,0],goal:[8,81,0],searches:[search()],macroCalls:0,sharedCalls:0,windowCalls:0,actions:[]});
test("a terminal success cannot conceal a missing physical arrival",()=>{
  const t=trial();t.arrived=false;
  assert(checkTrial(t,"candidate").some(s=>s.includes("physical arrival")));
});
test("a second short search cannot masquerade as one logical request",()=>{
  const t=trial();t.searches.push(search());
  assert(checkTrial(t,"candidate").some(s=>s.includes("repeated whole")));
});
test("a near selected target cannot erase a far original target",()=>{
  const t=trial();t.name="query-mixed";t.searches.unshift({...search(),adopted:false});
  assert(checkTrial(t,"candidate").some(s=>s.includes("long/mixed")));
});
test("small range must remain visible even after an upgrade",()=>{
  const t=trial();t.name="range4";t.requests[0].state="EXHAUSTED";
  t.macroCalls=1;t.searches[0].range=4;t.searches[0].requestedRange=4;
  t.searches[0].actualCanReach=false;
  t.searches.push({...search(true),requestedRange:4});
  assert(checkTrial(t,"candidate").some(s=>s.includes("original range changed")));
});
