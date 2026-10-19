import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

export function unionLength(intervals, from = -Infinity, to = Infinity) {
  const sorted = intervals.map(([a, b]) => [Math.max(a, from), Math.min(b, to)])
    .filter(([a, b]) => b > a).sort((a, b) => a[0] - b[0]);
  let total = 0, left, right;
  for (const [a, b] of sorted) {
    if (left === undefined) { left = a; right = b; }
    else if (a > right) { total += right - left; left = a; right = b; }
    else right = Math.max(right, b);
  }
  return total + (left === undefined ? 0 : right - left);
}
export function quantile(values, p) {
  const sorted = values.filter(Number.isFinite).toSorted((a, b) => a - b);
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)] : null;
}
export function traceIntegrity(statuses, events) {
  const errors=[];
  if(statuses.some(s=>s.depth||s.errors||s.dropped))errors.push('trace nesting/capacity invalid');
  if(events.some(e=>!Number.isFinite(e.start)||!Number.isFinite(e.end)||e.end<e.start||
    e.exclusive<0||e.exclusive>e.end-e.start||e.bytes<0||e.exclusiveBytes<0))
    errors.push('bad event span');
  return errors;
}
export const coverageScenes=['entity-stationary','entity-drift','ai-goalselector','brain-walk-target',
  'query-only-multi','query-ready-adopt-multi','query-pending-adopt-multi','shared-cold1','shared-warm1','shared-accuracy0',
  'legacy-createpath-move','poi-acquire','dynamic-block','native-stuck-recovery',
  'shared-accuracy1','shared-boundary1','shared-neighbor1','shared-obstacle1',
  'shared-drift1','shared-cancel1','shared-replace1','shared-range4','shared-range12',
  'shared-dynamic1','shared-pending-cancel1','shared-pending-replace1','shared-timeout1',
  'shared-low-budget0','shared-anchor-drift0',
  'shared-query-only-multi','shared-query-ready-adopt-multi','shared-query-pending-adopt-multi'];
export function qualificationReasons(accuracy, width, height, start, goal) {
  const reasons=[];
  if(accuracy!==0)reasons.push('accuracy');
  if(width>1)reasons.push('width');
  if(Math.ceil(height)<2)reasons.push('height');
  if(start.some((v,i)=>Math.floor(v/16)!==Math.floor(goal[i]/16)))reasons.push('section');
  return reasons;
}
export function queryReadyBeforeAdoption(actor) {
  return Boolean(actor.queryReadyNanos&&(!actor.adoptedNanos||actor.queryReadyNanos<=actor.adoptedNanos));
}
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function tsv(file, numeric = true) {
  const lines = fs.readFileSync(file, 'utf8').trimEnd().split(/\r?\n/), keys = lines.shift().split('\t');
  return lines.map(line => Object.fromEntries(line.split('\t').map((v, i) =>
    [keys[i], numeric && v !== '' && /^-?\d+$/.test(v) ? Number(v) : v])));
}
export function category(name) {
  name=name.split('(')[0];
  if (/CoverageScenarioProbe\./.test(name)) return 'diagnosticProbe';
  if (/NavigationScheduler\./.test(name)) return 'scheduler';
  if (/NavigationPoiAdapter\./.test(name)) return 'poiAdapter';
  if (/AutomaticNavigationBridge\./.test(name)) return 'entryBridge';
  if (/BaseClusterTopology\./.test(name)) return 'base';
  if (/SuperClusterTopology\.boundaryLinks|buildBaseBoundaryLinks|buildSuperBoundaryLinks/.test(name)) return 'boundary';
  if (/SuperClusterTopology\.build|buildSuperCluster/.test(name)) return 'super';
  if (/GroundSegmentWindow/.test(name)) return 'window';
  if (/PathFinder\./.test(name)) return 'pathFinder';
  if (/NativeNavigationSearch|NativeQueryProof|NavigationCapabilities/.test(name)) return 'nativeWrapper';
  if (/scanSection|runOneRecovery|drainFactDemand|persistDecision|drainPersistence|applySectionEvent|processSectionEvents|finishTick/.test(name))
    return 'factsAndMaintenance';
  if (/runtimeTransition/.test(name))return 'runtimeState';
  if (/MacroSearch|MacroQuery|MacroFlight|MacroRequest|requestMacroQuery|ResolveFlight|joinResolve|joinCanonical|prepareDemand|beginBuild/.test(name))
    return 'macroAndDependencies';
  if (/SegmentedNavigationService|NavigationRequestPath/.test(name)) return 'navigationService';
  if (name === 'workerRun') return 'workerOther';
  return 'other';
}
function summarize(events, phases) {
  const totals = {};
  for (const e of events) {
    const name = phases[e.phase], key = category(name);
    const row = totals[key] ??= { calls: 0, exclusiveNanos: 0, allocatedBytes: 0, failures: 0 };
    row.calls++; row.exclusiveNanos += e.exclusive; row.allocatedBytes += e.exclusiveBytes; row.failures += e.failed;
  }
  return Object.fromEntries(Object.entries(totals).sort((a, b) => b[1].exclusiveNanos - a[1].exclusiveNanos));
}
export function analyze(directory) {
  const result = read(path.join(directory, 'timeline.json'));
  const launch = read(path.join(directory, 'launch.json')), exit = read(path.join(directory, 'exit.json'));
  const shutdown = read(path.join(directory, 'shutdown.json')), ticks = read(path.join(directory, 'full-ticks.json'));
  const errors = [];
  if(result.schema!=='NAVIGATION_TIMELINE_V1'||ticks.schema!=='REFERENCE_FULL_TICK_V1')errors.push('unsupported schema');
  const buildIdentity=read(path.join(launch.build,'build-identity.json'));
  if(sha(path.join(launch.build,'build-identity.json'))!==launch.buildIdentitySha256||
      launch.artifacts.find(a=>a.name==='production.jar')?.sha256!==buildIdentity.productionJarSha256||
      sha(path.join(directory,'mods/accelerated_navigation.jar'))!==buildIdentity.productionJarSha256)
    errors.push('unexpected production identity');
  if(fs.readFileSync(path.join(directory,'transforms.txt'),'utf8').includes('ERROR '))errors.push('transform failed');
  if (!result.complete || result.failure || !shutdown.passed || exit.exitCode !== 0 || exit.timedOut
      || exit.concurrentJava.length) errors.push('run identity/completion/interference invalid');
  if (result.trials.length !== result.scenes.length) errors.push('missing scenes');
  let expected={
    single:['same8-first-use','same8-repeat','cross8-first-use','cross8-repeat','cross28-first-use','cross28-repeat','single-wall8','doorway8'],
    burst:['warmup8','warmup28','burst8','burst28'],
    supplement:['doorway8-known-slots','super304','ai-melee-query-adopt'],
    closure:['super304-cold','super304-warm'],
    coverage:coverageScenes,
    failures:[8,28].flatMap(d=>[`wall${d}-all`,`door${d}-all`,`wall${d}-reverse`,`door${d}-reverse`])
      .concat(['explicit-range4','explicit-range12','cancel-pending','cancel-published','replace-pending'])
  }[launch.suite];
  if(launch.scene) {
    const selected=launch.scene.split(',');
    if(!selected.every(name=>expected?.includes(name)))errors.push('unknown scene filter');
    expected=expected?.filter(name=>selected.includes(name));
  }
  if(!expected||JSON.stringify(result.trials.map(t=>t.scene.name))!==JSON.stringify(expected))errors.push('declared suite coverage/order invalid');
  const phases = fs.readFileSync(path.join(directory, 'phases.txt'), 'utf8').trimEnd().split(/\r?\n/);
  const events = tsv(path.join(directory, 'events.tsv'));
  const statuses = tsv(path.join(directory, 'trace-status.tsv'));
  errors.push(...traceIntegrity(statuses,events));
  for (const event of events) {
    if (!phases[event.phase] || event.end < event.start || event.exclusive < 0 ||
        event.exclusive > event.end - event.start || event.bytes < 0 || event.exclusiveBytes < 0)
      errors.push('bad event span');
  }
  const objects = new Map();
  for (const row of tsv(path.join(directory, 'objects.tsv'), false)) {
    const object = objects.get(Number(row.id)) ?? { id: Number(row.id), class: row.class, fields: {} };
    if (!row.field) object.label = row.value;
    else object.fields[row.field] = row.kind === 'ref' ? { ref: Number(row.value) } : row.value;
    objects.set(object.id, object);
  }
  const ref = (id, key) => objects.get(id)?.fields[key]?.ref ?? 0;
  const scalar = (id, key) => objects.get(id)?.fields[key];
  const requestObjects=new Map([...objects.values()].filter(o=>o.class.endsWith('.NavigationRequestPath'))
    .map(o=>[o.fields.queryId,o]));
  const macroObjects=new Map();
  for(const o of objects.values())if(o.class.endsWith('TopologyService$MacroRequest')) {
    const owner=scalar(ref(o.id,'key'),'owner');
    if(!macroObjects.has(owner))macroObjects.set(owner,[]);
    macroObjects.get(owner).push(o);
  }
  const requestFor = e => {
    for (const id of [e.self, e.a, e.b]) {
      const object = objects.get(id);
      if (object?.class.endsWith('SegmentedNavigationService$Work')) return ref(id, 'request');
      if (object?.class.endsWith('.NavigationRequestPath')) return id;
    }
    return 0;
  };
  const eventName = e => phases[e.phase];
  const phaseCache=new WeakMap();
  const startsWith = (es, prefix) => {
    if(!phaseCache.has(es))phaseCache.set(es,new Map());
    const cache=phaseCache.get(es);
    if(!cache.has(prefix))cache.set(prefix,es.filter(e=>eventName(e).startsWith(prefix)));
    return cache.get(prefix);
  };
  const reaches = (id, target, seen = new Set(), depth = 0) => {
    if (id === target) return true;
    if (!id || seen.has(id) || depth > 3) return false;
    seen.add(id);
    return Object.values(objects.get(id)?.fields ?? {}).some(value => value?.ref && reaches(value.ref,target,seen,depth+1));
  };
  const eachTrial = result.trials.map(trial => {
    const from = trial.submittedNanos, to = trial.closedNanos;
    if (from - trial.sceneReadyNanos < 30e9 || from - trial.serverStartedNanos < 30e9)
      errors.push(`30s gate: ${trial.scene.name}`);
    if (trial.actors.length !== trial.scene.count) errors.push(`actor count: ${trial.scene.name}`);
    if(new Set(trial.actors.map(a=>`${a.tile}:${a.slot}`)).size!==trial.actors.length)errors.push('duplicate actor route');
    const spans = events.filter(e => e.trial === trial.trial);
    const eventsByRequest=new Map();
    for(const e of spans) {
      const id=requestFor(e);
      if(id){if(!eventsByRequest.has(id))eventsByRequest.set(id,[]);eventsByRequest.get(id).push(e);}
    }
    const mains = new Set(statuses.filter(s => s.name === 'Server thread').map(s => s.thread));
    const main = spans.filter(e => mains.has(e.thread)), worker = spans.filter(e => !mains.has(e.thread));
    if(result.deepTrace&&startsWith(spans,'PathFinder.').length!==trial.actors.reduce((sum,a)=>sum+a.search.calls,0))
      errors.push(`PathFinder hook/count mismatch: ${trial.scene.name}`);
    const phaseTotals=phases.map((name,id)=>{
      const selected=spans.filter(e=>e.phase===id&&e.start>=from&&e.end<=to);
      return {name,category:category(name),calls:selected.length,
        mainCalls:selected.filter(e=>mains.has(e.thread)).length,
        exclusiveNanos:selected.reduce((n,e)=>n+e.exclusive,0),
        allocatedBytes:selected.reduce((n,e)=>n+e.exclusiveBytes,0)};
    }).filter(p=>p.calls).sort((a,b)=>b.allocatedBytes-a.allocatedBytes);
    const measuredTicks = ticks.samples.filter(t => t.endNanos >= from && t.startNanos <= to);
    if (ticks.incomplete || ticks.truncated || !measuredTicks.length) errors.push('full ticks invalid');
    for (let i = 1; i < measuredTicks.length; i++)
      if (measuredTicks[i].serverTick !== measuredTicks[i - 1].serverTick + 1) errors.push('missing tick');
    const actualBuilds = startsWith(spans, 'BaseClusterTopology.build').filter(e =>
      !startsWith(spans, 'BaseClusterTopology.build').some(child =>
        child !== e && child.thread === e.thread && child.depth > e.depth && child.start >= e.start && child.end <= e.end));
    const queued = spans.filter(e => e.phase === 1);
    const selected = spans.filter(e => e.phase === 2);
    const executed = spans.filter(e => e.phase === 0);
    const taskQueue = executed.map(e => {
      const q = queued.find(q => q.self === e.self && q.detail === e.detail);
      const s = selected.find(s => s.self === e.self && s.detail === e.detail);
      return { task: e.self, sequence: e.detail, kind: objects.get(e.b)?.label,
        enqueued: q?.start ?? null, selected: s?.start ?? null, start: e.start, end: e.end,
        queueNanos: q ? e.start - q.start : null, selectionToRunNanos: s ? e.start - s.start : null };
    });
    const requests = trial.actors.map(actor => {
      const observedEntries=(actor.queryIds??[actor.queryId]).map(id=>requestObjects.get(id)).filter(Boolean);
      const navigationIds=new Set(spans.filter(e=>eventName(e)==='actorBind'&&
        observedEntries.some(o=>o.id===e.a)).map(e=>e.self));
      const discovered=launch.suite==='coverage'?startsWith(spans,'SegmentedNavigationService.prepare(')
        .filter(e=>navigationIds.has(e.a)).map(e=>objects.get(e.result))
        .filter(o=>o?.class.endsWith('.NavigationRequestPath')):[];
      const entries=[...new Map([...observedEntries,...discovered].map(o=>[o.id,o])).values()];
      const createdAt=id=>startsWith(spans,'SegmentedNavigationService.prepare(')
        .filter(e=>e.result===id).reduce((n,e)=>Math.min(n,e.start),Infinity);
      if(launch.suite==='coverage')entries.sort((a,b)=>createdAt(a.id)-createdAt(b.id));
      const entry=entries.find(o=>o.fields.queryId===actor.queryId);
      const related = (actor.kind==='legacy-createpath-move'
        ?spans.filter(e=>/^NativeNavigationSearch\.find|^searchParameters$/.test(eventName(e)))
        :entries.flatMap(o=>eventsByRequest.get(o.id)??[])).sort((a,b)=>a.start-b.start);
      const init = startsWith(related, 'SegmentedNavigationService.initialize')[0];
      const firstSearch = startsWith(related, 'SegmentedNavigationService.findSegment')[0];
      const firstPublish = startsWith(related, 'SegmentedNavigationService.publish')[0];
      if(result.deepTrace&&!result.vanillaSameRuntime&&actor.firstReadyNanos&&actor.kind!=='legacy-createpath-move'&&
        !startsWith(related,'SegmentedNavigationService.publish').some(e=>e.start<=actor.firstReadyNanos&&e.end>=actor.firstReadyNanos))
        errors.push(`publication belongs to an untracked request: ${trial.scene.name}`);
      const windows = startsWith(related, 'SegmentedNavigationService.prepareWindow');
      const intent=entry?objects.get(ref(entry.id,'intent')):null;
      const actualSearches=related.filter(e=>eventName(e).startsWith('NativeNavigationSearch.find')&&
        eventName(e).includes('Ljava/util/Set;Z)')).map(e=>{
        const parameters=related.find(p=>p.phase===5&&p.depth===e.depth+1&&p.thread===e.thread&&p.start>=e.start&&p.end<=e.end);
        const bits=Buffer.alloc(4);
        if(parameters)bits.writeInt32BE(parameters.detail2);
        return {startMs:(e.start-actor.submitStartNanos)/1e6,wallMs:(e.end-e.start)/1e6,
          targets:objects.get(e.b)?.label,accuracy:parameters?.detail??null,
          range:parameters?bits.readFloatBE():null};
      });
      if(result.deepTrace&&!result.vanillaSameRuntime&&actor.accepted&&actor.queryId&&!entry)
        errors.push('missing request identity');
      const macroOwners = entries.map(o=>o.fields.macroOwner);
      const services = macroOwners.flatMap(owner=>macroObjects.get(owner)??[]);
      const macroDeliveries = spans.filter(e => eventName(e).startsWith('TopologyService$MacroRequest.completeWorker')
        && services.some(s => s.id === e.self)).sort((a,b) => a.start-b.start);
      const firstMacro = macroDeliveries[0];
      const macroEnqueue = firstMacro ? startsWith(spans,'TopologyService.queueServer').filter(e =>
        reaches(e.a,firstMacro.self) && e.start <= firstMacro.start).sort((a,b) => a.start-b.start).at(-1) : null;
      const firstNext = startsWith(related,'SegmentedNavigationService.nextSegment')[0];
      const windowTimeline = windows.map(w => {
        const capture = startsWith(spans,'GroundSegmentWindow.capture').find(e =>
          e.thread===w.thread && e.start>=w.start && e.end<=w.end);
        const future = capture?.result;
        const compute = startsWith(spans,'GroundSegmentWindow.lambda$capture').find(e =>
          eventName(e).endsWith(')V') && e.a===future);
        const callbacks = related.filter(e => eventName(e).startsWith('SegmentedNavigationService.lambda$prepareWindow$') &&
          e.b===future);
        const workerCallback = callbacks.find(e => !mains.has(e.thread));
        const mainCallback = callbacks.find(e => mains.has(e.thread));
        const search = startsWith(related,'SegmentedNavigationService.findSegment').find(e => e.start >= w.start);
        const elapsed = (a,b) => a !== undefined && b !== undefined ? (b-a)/1e6 : null;
        return { future, publication:w.detail, last:w.detail2>=281474976710656,
          prepareStartMs:(w.start-actor.submitStartNanos)/1e6,
          computeStartMs:compute?(compute.start-actor.submitStartNanos)/1e6:null,
          futureCallbackMs:workerCallback?(workerCallback.start-actor.submitStartNanos)/1e6:null,
          mainCallbackStartMs:mainCallback?(mainCallback.start-actor.submitStartNanos)/1e6:null,
          mainCallbackEndMs:mainCallback?(mainCallback.end-actor.submitStartNanos)/1e6:null,
          prepareToComputeMs:elapsed(w.start,compute?.start),
          computationToFutureCallbackMs:elapsed(compute?.start,workerCallback?.start),
          futureCallbackToMainMs:elapsed(workerCallback?.start,mainCallback?.start),
          mainCallbackToSearchMs:elapsed(mainCallback?.end,search?.start) };
      });
      const steps = related.filter(e => /SegmentedNavigationService\.(initialize|existingMacroCandidate|prepareWindow|nextSegment|findSegment|publish|recover|refresh|replan|settle|settleTail|physical|recordDestinationEntry|finish|nativeDecision|searchNative)\(/.test(eventName(e)))
        .map(e => ({ phase: eventName(e).split('(')[0], thread: e.thread, tick: e.tick,
          requestId:requestFor(e),queryId:scalar(requestFor(e),'queryId'),resultLabel:objects.get(e.result)?.label??null,
          startMs: (e.start - actor.submitStartNanos) / 1e6, endMs: (e.end - actor.submitStartNanos) / 1e6,
          exclusiveMs: e.exclusive / 1e6, allocatedBytes: e.exclusiveBytes, publication: e.detail,
          cursor: e.detail2 & 65535, stageEnd: Math.floor(e.detail2 / 65536) & 65535,
          attempt: Math.floor(e.detail2 / 4294967296) & 65535, last: e.detail2 >= 281474976710656,
          stride:e.workState===undefined?null:e.workState&65535,
          recoveryCount:e.workState===undefined?null:Math.floor(e.workState/65536)&65535,
          refreshes:e.workState===undefined?null:Math.floor(e.workState/4294967296)&65535,
          nativeStopped:e.workState===undefined?null:e.workState>=281474976710656 }));
      return { tile: actor.tile, slot: actor.slot, kind: actor.kind, queryId: actor.queryId, state: actor.state,
        queryIds:entries.length?entries.map(o=>o.fields.queryId):(actor.queryIds??[actor.queryId]),
        requestGenerations:launch.suite==='coverage'?entries.map(o=>{
          const observed=actor.requestGenerations?.find(g=>g.queryId===o.fields.queryId);
          const intent=objects.get(ref(o.id,'intent'));
          return {...observed,queryId:o.fields.queryId,state:o.fields.state,accuracy:Number(intent?.fields.accuracy),
            preparedAtMs:Number.isFinite(createdAt(o.id))?(createdAt(o.id)-actor.submitStartNanos)/1e6:null,
            adopted:o.fields.adopted==='true',discoveredViaPrepareReturn:!observed,
            targetCount:observed?.targetCount??(objects.get(ref(intent?.id,'targets'))?.label?.match(/\bx=/g)?.length??null),
            controller:observed?.controller??'same-tick request; see entry scope'};
        }):actor.requestGenerations??null,
        queryReadyMs:queryReadyBeforeAdoption(actor)?(actor.queryReadyNanos-actor.submitStartNanos)/1e6:null,
        handleReachableObservedMs:actor.queryReadyNanos?(actor.queryReadyNanos-actor.submitStartNanos)/1e6:null,
        adoptedMs:actor.adoptedNanos?(actor.adoptedNanos-actor.submitStartNanos)/1e6:null,
        firstReadyMs: actor.firstReadyNanos ? (actor.firstReadyNanos - actor.submitStartNanos) / 1e6 : null,
        firstMoveMs: actor.firstMoveNanos ? (actor.firstMoveNanos - actor.submitStartNanos) / 1e6 : null,
        arrivalMs: actor.arrivalNanos ? (actor.arrivalNanos - actor.submitStartNanos) / 1e6 : null,
        submitToInitializeMs: init ? (init.start - actor.submitStartNanos) / 1e6 : null,
        initializeToFirstSearchMs: init && firstSearch ? (firstSearch.start - init.start) / 1e6 : null,
        firstSearchToPublishMs: firstSearch && firstPublish ? (firstPublish.end - firstSearch.start) / 1e6 : null,
        requestedRange:intent?.fields.range??null,requestedAccuracy:intent?.fields.accuracy??null,actualSearches,
        macroEnqueueMs:macroEnqueue?(macroEnqueue.start-actor.submitStartNanos)/1e6:null,
        macroMainDeliveryStartMs:firstMacro?(firstMacro.start-actor.submitStartNanos)/1e6:null,
        macroMainDeliveryEndMs:firstMacro?(firstMacro.end-actor.submitStartNanos)/1e6:null,
        macroDispatchMs:firstMacro&&macroEnqueue?(firstMacro.start-macroEnqueue.start)/1e6:null,
        macroDeliveryToNextSegmentMs:firstMacro&&firstNext?(firstNext.start-firstMacro.end)/1e6:null,
        windowTimeline,
        windows: windows.length, recoveries: steps.filter(s => s.phase.endsWith('.recover')).length,
        searches: actor.search.calls, partialSearches: actor.search.partialPaths, steps };
    });
    const costs = { processCpuNanos: trial.costEnd.processCpuNanos - trial.costStart.processCpuNanos,
      allAllocatedBytes: trial.costEnd.allThreadAllocatedBytes - trial.costStart.allThreadAllocatedBytes,
      mainAllocatedBytes: trial.costEnd.mainThreadAllocatedBytes - trial.costStart.mainThreadAllocatedBytes };
    const samples = [trial.costStart, ...trial.costSamples, trial.costEnd];
    const fixedWindows = [1, 5].map(seconds => {
      const at = from + seconds * 1e9;
      const before = samples.filter(s => s.nanoTime <= at).at(-1), after = samples.find(s => s.nanoTime >= at);
      const delta = s => s ? { atNanos: s.nanoTime, elapsedMs: (s.nanoTime - from) / 1e6,
        processCpuNanos: s.processCpuNanos - trial.costStart.processCpuNanos,
        allocatedBytes: s.allThreadAllocatedBytes - trial.costStart.allThreadAllocatedBytes } : null;
      return { seconds, covered: Boolean(after), lower: delta(before), upper: delta(after) };
    });
    const qualifications=spans.filter(e=>eventName(e)==='sharedParameters').map(e=>{
      const point=id=>{
        const label=objects.get(id)?.label??'';
        const match=label.match(/x=(-?\d+), y=(-?\d+), z=(-?\d+)/);
        return match?match.slice(1).map(Number):null;
      };
      const start=point(e.a),goal=point(e.b);
      const width=Number(scalar(e.self,'width')),height=Number(scalar(e.self,'height'));
      const reasons=start&&goal?qualificationReasons(e.detail,width,height,start,goal):null;
      if(!reasons||!Number.isFinite(width)||!Number.isFinite(height))errors.push('missing shared qualification input');
      const parent=startsWith(spans,'SharedSectionCandidate.supports').find(p=>
        p.thread===e.thread&&p.depth+1===e.depth&&p.start<=e.start&&p.end>=e.end);
      const actual=objects.get(parent?.result)?.label;
      if(!parent||actual!==String(reasons?.length===0))errors.push('shared qualification input/outcome mismatch');
      return {accuracy:e.detail,width,height,start,goal,reasons};
    });
    return { trial: trial.trial, scene: trial.scene, actors: trial.actors.length,
      arrivalScopeValid:trial.scene.kind!=='legacy-createpath-move'||result.coverageProbeRevision>=2,
      arrived: requests.filter(r => r.arrivalMs !== null).length,
      states: Object.fromEntries([...new Set(requests.map(r => r.state))].map(s => [s, requests.filter(r => r.state === s).length])),
      firstReadyMedianMs: quantile(requests.map(r => r.firstReadyMs), .5),
      firstReadyP95Ms: quantile(requests.map(r => r.firstReadyMs), .95),
      elapsedMs: (to - from) / 1e6, costs, fixedWindows,
      tickP99Ms: quantile(measuredTicks.map(t => t.fullNanos / 1e6), .99),
      phaseTotals,
      nativeStopEvents:result.deepTrace?startsWith(spans,'SegmentedNavigationService.nativeStopped(')
        .map(e=>({nanoTime:e.start,tick:e.tick,navigationObjectId:e.a})):null,
      observations:trial.observations??null,actions:trial.actions??null,
      entryCensus:result.deepTrace?Object.fromEntries([
        ['bridgePrepare','AutomaticNavigationBridge.prepare('],['bridgeMove','AutomaticNavigationBridge.move('],
        ['bridgeMovePath','AutomaticNavigationBridge.movePath('],['legacy','NativeNavigationSearch.legacy('],
        ['poiBeforeQuery','NavigationPoiAdapter.beforeQuery('],['poiValidSelection','NavigationPoiAdapter.validSelection('],
        ['nativeStopped','SegmentedNavigationService.nativeStopped('],
        ['replan','SegmentedNavigationService.replan('],['refresh','SegmentedNavigationService.refresh(']
      ].map(([key,prefix])=>[key,startsWith(spans,prefix).length])):null,
      sharedQualificationInputs:qualifications,
      apiCounts:result.deepTrace?Object.fromEntries(['prepare','move','adopt','nativeDecision','searchNative'].map(name=>
        [name,startsWith(spans,`SegmentedNavigationService.${name}(`).length])):null,
      sharedQualification:result.deepTrace?{
        accepted:spans.filter(e=>eventName(e).startsWith('SharedSectionCandidate.supports')&&objects.get(e.result)?.label==='true').length,
        rejected:spans.filter(e=>eventName(e).startsWith('SharedSectionCandidate.supports')&&objects.get(e.result)?.label==='false').length}:null,
      windowWorkerCosts:result.deepTrace?Object.fromEntries([false,true].map(final=>{
        const selected=spans.filter(e=>eventName(e).startsWith('GroundSegmentWindow.lambda$capture')&&
          eventName(e).endsWith(')V')&&Boolean(e.b)===final&&e.start>=from&&e.end<=to);
        return [final?'final':'intermediate',{calls:selected.length,
          exclusiveNanos:selected.reduce((n,e)=>n+e.exclusive,0),
          allocatedBytes:selected.reduce((n,e)=>n+e.exclusiveBytes,0)}];
      })):null,
      mainStages: summarize(main.filter(e=>e.start>=from&&e.end<=to), phases),
      workerStages: summarize(worker.filter(e=>e.start>=from&&e.end<=to), phases),
      crossingCostSpansExcluded:spans.filter(e=>e.start<from||e.end>to).length,
      mainUnionMs: unionLength(main.map(e => [e.start, e.end]), from, to) / 1e6,
      mainBeforeFirstReadyUnionMs: trial.actors.length === 1 && trial.actors[0].firstReadyNanos
        ? unionLength(main.map(e => [e.start, e.end]), from, trial.actors[0].firstReadyNanos) / 1e6 : null,
      actualBaseBuilds: result.deepTrace?actualBuilds.length:null,
      actualBaseBuildsByKind:result.deepTrace?Object.fromEntries(['BUILD','PREWARM'].map(kind=>[kind,actualBuilds.filter(e=>
        executed.some(parent=>parent.thread===e.thread&&parent.start<=e.start&&parent.end>=e.end&&objects.get(parent.b)?.label===kind)).length])):null,
      actualSuperBuildCalls: result.deepTrace?startsWith(spans, 'SuperClusterTopology.build').length:null,
      boundaryBuildCalls: result.deepTrace?startsWith(spans, 'SuperClusterTopology.boundaryLinks').length:null,
      workerTaskCounts: Object.fromEntries([...new Set(taskQueue.map(t => t.kind))].map(k => [k, taskQueue.filter(t => t.kind === k).length])),
      workerQueueP95Ms: quantile(taskQueue.map(t => t.queueNanos === null ? null : t.queueNanos / 1e6), .95),
      taskQueue, requests,
      selectedPhases: spans.filter(e => /TopologyService\$MacroRequest.completeWorker|TopologyService.queueServer|GroundSegmentWindow.lambda.*\)V|MacroFlight.complete\(/.test(eventName(e)))
        .map(e => ({ name: eventName(e), thread: e.thread, self: e.self, a: e.a, b: e.b,
          startMs: (e.start-from)/1e6, endMs: (e.end-from)/1e6 })) };
  });
  const output = { schema: 'NAVIGATION_TIMELINE_ANALYSIS_V1', directory: path.resolve(directory),
    valid: !errors.length, errors: [...new Set(errors)], diagnosticOnly: true,
    deepTrace: result.deepTrace, vanillaSameRuntime: result.vanillaSameRuntime,
    eventCount: events.length, phases, statuses, trials: eachTrial,
    analyzerSha256:sha(fileURLToPath(import.meta.url)),
    sources: ['timeline.json','events.tsv','objects.tsv','phases.txt','trace-status.tsv','launch.json','exit.json','full-ticks.json','shutdown.json']
      .map(name => ({ name, sha256: sha(path.join(directory, name)) })) };
  return output;
}
export function markdown(result) {
  const n = value => value == null ? 'null' : value.toFixed(3);
  return `# 当前导航时间线诊断\n\n技术有效：${result.valid}。深阶段插桩：${result.deepTrace}。仅作归因，不作性能准入。\n\n` +
    `| 场景 | 到达 | 首 Path 中位 ms | 首 Path p95 ms | 全窗 CPU ms | 全窗分配 MiB | 实际 Base | Super 调用 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n` +
    result.trials.map(t => `| ${t.scene.name} | ${t.arrived}/${t.actors} | ${n(t.firstReadyMedianMs)} | ${n(t.firstReadyP95Ms)} | ${n(t.costs.processCpuNanos/1e6)} | ${n(t.costs.allAllocatedBytes/1048576)} | ${t.actualBaseBuilds} | ${t.actualSuperBuildCalls} |`).join('\n') +
    '\n\n嵌套阶段按线程取独占时间与独占分配。worker 并行区间不能相加冒充端到端时延。阶段含诊断开销；不将 wall−CPU 解释为锁等待。固定窗给累计量前后采样界，不伪造精确 1s/5s 采样。\n';
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = process.argv[2];
  assert(directory, 'Usage: node tools/navigation-diagnostics/analyze.mjs RUN');
  const result = analyze(directory);
  const destination=process.argv[3]??directory;
  if(destination!==directory){assert(!fs.existsSync(destination),'Preserve previous output');fs.mkdirSync(destination,{recursive:true});}
  for (const name of ['analysis.json','analysis.md'])
    assert(!fs.existsSync(path.join(destination,name)), 'Preserve previous analysis');
  fs.writeFileSync(path.join(destination,'analysis.json'),JSON.stringify(result,null,2)+'\n');
  fs.writeFileSync(path.join(destination,'analysis.md'),markdown(result));
  console.log(JSON.stringify({valid:result.valid,errors:result.errors,trials:result.trials.map(t=>({
    name:t.scene.name,arrived:t.arrived,firstReady:t.firstReadyMedianMs,base:t.actualBaseBuilds}))}));
  if (!result.valid) process.exitCode=1;
}
