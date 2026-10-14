import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const output = resolve(root, process.argv[2] ?? ".tmp/navigation-dynamic-budget-20261013");
const targetMs = 45;
const maximumMs = 12;
const minimumStartMs = 0.5;
const historySize = 32;
const initialHistorySize = 8;
const modes = ["fixed4", "fixed8", "fixed12", "dynamic12"];
const costsMs = [0.11, 0.19, 0.08, 0.42, 0.95, 0.16, 0.65, 1.4, 0.23, 0.07, 0.3, 0.52];
const sha = bytes => createHash("sha256").update(bytes).digest("hex").toUpperCase();
const quantile = (values, fraction) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
};
const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
const clamp = (value, low, high) => Math.max(low, Math.min(high, value));

class Controller {
  constructor(mode) {
    this.mode = mode;
    this.budget = mode === "dynamic12" ? 4 : Number(mode.slice(5));
    this.balance = 0;
    this.history = [];
    this.ewma = 0;
    this.tailEwma = 0;
    this.previousFull = null;
  }

  begin() {
    const oldBudget = this.budget;
    const baseEstimate = this.history.length
      ? Math.max(this.ewma, quantile(this.history.map(sample => sample.base), 0.95))
      : 0;
    const tailReserve = Math.max(2, this.tailEwma,
      quantile(this.history.map(sample => sample.tail), 0.95) ?? 0);
    if (this.mode === "dynamic12") {
      let desired = clamp(targetMs - baseEstimate, 0, maximumMs);
      if (this.history.length < initialHistorySize) desired = Math.min(4, desired);
      this.budget = Math.min(desired, oldBudget + 1);
      if (this.previousFull !== null && this.previousFull > targetMs)
        this.budget = Math.min(this.budget, oldBudget / 2);
      assert(this.budget <= oldBudget + 1 + 1e-9);
    }
    this.balance = Math.min(this.budget, this.balance + this.budget);
    assert(this.balance <= this.budget + 1e-9);
    return { budget: this.budget, balanceAfterRefill: this.balance,
      baseEstimate, tailReserve, deadline: targetMs - tailReserve };
  }

  charge(amount) {
    assert(amount >= 0);
    this.balance -= amount;
  }

  finish(full, navigation, tailNonNavigation) {
    assert(full >= navigation);
    const base = full - navigation;
    const first = !this.history.length;
    this.ewma = first ? base : this.ewma * 0.8 + base * 0.2;
    this.tailEwma = first ? tailNonNavigation
      : this.tailEwma * 0.8 + tailNonNavigation * 0.2;
    this.history.push({ base, tail: tailNonNavigation });
    if (this.history.length > historySize) this.history.shift();
    this.previousFull = full;
  }
}

const stable = base => () => ({ pre: base - 0.8, tail: 0.8 });
const tasks = (count, release = () => 0, cost = id => costsMs[id % costsMs.length]) =>
  Array.from({ length: count }, (_, id) => ({ id, release: release(id), cost: cost(id) }));

function simulate(mode, workload, profile, { coldController = false, horizon = 240 } = {}) {
  const controller = new Controller(mode);
  if (!coldController) {
    for (let tick = -64; tick < 0; tick++) {
      const p = profile(tick);
      controller.begin();
      controller.charge(0.3);
      controller.finish(p.pre + p.tail + 0.3, 0.3, p.tail);
    }
  }
  const input = workload.map(task => ({ ...task })).sort((a, b) => a.release - b.release || a.id - b.id);
  const queue = [];
  const records = [];
  const completions = [];
  const completedIds = new Set();
  let cursor = 0, wall = 0, lastFinish = null;
  for (let tick = 0; tick < horizon; tick++) {
    const p = profile(tick);
    while (cursor < input.length && input[cursor].release <= tick) {
      queue.push({ ...input[cursor++], releasedWall: wall });
    }
    const control = controller.begin();
    const mandatory = 0.3;
    controller.charge(mandatory);
    let elapsed = p.pre + mandatory;
    let dispatched = 0, charged = mandatory, stopReason = "EMPTY";
    const starts = [];
    while (queue.length) {
      if (p.haveTimeUntil !== undefined && elapsed >= p.haveTimeUntil) {
        stopReason = "SERVER_NO_TIME"; break;
      }
      if (controller.balance < minimumStartMs) { stopReason = "BALANCE"; break; }
      if (control.deadline - elapsed < minimumStartMs) { stopReason = "HEADROOM"; break; }
      const task = queue.shift();
      starts.push({ id: task.id, elapsed, balance: controller.balance });
      assert(elapsed < control.deadline);
      assert(controller.balance >= minimumStartMs);
      assert(!completedIds.has(task.id));
      if (p.haveTimeUntil !== undefined) assert(elapsed < p.haveTimeUntil);
      elapsed += task.cost;
      charged += task.cost;
      controller.charge(task.cost);
      completions.push({ id: task.id, tick, readyWaitMs: wall + elapsed - task.releasedWall });
      completedIds.add(task.id);
      lastFinish = wall + elapsed;
      dispatched++;
    }
    const full = elapsed + p.tail;
    controller.finish(full, charged, p.tail);
    // Model callback work between tickServer invocations is billed as debt.
    controller.charge(p.outsideNavigation ?? 0);
    records.push({ tick, ...control, pre: p.pre, tail: p.tail, full, charged,
      outsideNavigation: p.outsideNavigation ?? 0,
      balance: controller.balance, dispatched, runnable: queue.length,
      dependencyWaiting: input.length - cursor, stopReason, starts });
    wall += Math.max(50, full + (p.outsideNavigation ?? 0));
    if (completedIds.size === input.length) break;
  }
  const waits = completions.map(row => row.readyWaitMs);
  const fullTicks = records.map(row => row.full);
  return { mode, total: workload.length, completed: completedIds.size,
    remaining: workload.length - completedIds.size,
    complete: completedIds.size === workload.length,
    completionMs: completedIds.size === workload.length ? lastFinish : null,
    readyWaitP50Ms: quantile(waits, 0.5), readyWaitP95Ms: quantile(waits, 0.95),
    workP95Ms: quantile(fullTicks, 0.95), workP99Ms: quantile(fullTicks, 0.99),
    workMaxMs: Math.max(...fullTicks), over50Ticks: fullTicks.filter(value => value > 50).length,
    debtMinMs: Math.min(...records.map(row => row.balance)),
    averageBudgetMs: mean(records.map(row => row.budget)),
    measuredTicks: records.length, records };
}

const checks = [];
function check(name, operation) {
  operation();
  checks.push({ name, passed: true });
}

check("idle credit cannot accumulate", () => {
  const c = new Controller("fixed4");
  for (let tick = 0; tick < 100; tick++) {
    c.begin(); assert.equal(c.balance, 4); c.finish(10, 0, 0.8);
  }
});
check("zero grant preserves debt", () => {
  const c = new Controller("dynamic12");
  c.begin(); c.charge(9);
  c.finish(60, 9, 0.8);
  const debt = c.balance;
  assert.equal(c.begin().budget, 0);
  assert.equal(c.balance, debt);
});
check("atomic operation and callback retain debt and stop further starts", () => {
  const r = simulate("fixed4", tasks(2, () => 0, id => id === 0 ? 5.7 : 0.1), stable(10),
    { coldController: true });
  assert.equal(r.records[0].dispatched, 1);
  assert(Math.abs(r.records[0].balance + 2) < 1e-9);
  assert.equal(r.records[1].dispatched, 1);
});
check("live headroom prevents starting under already high load", () => {
  const r = simulate("dynamic12", tasks(10), stable(48), { horizon: 20 });
  assert.equal(r.completed, 0);
  assert(r.records.every(row => row.dispatched === 0 && row.budget === 0));
});
check("cold controller starts at four then expands slowly", () => {
  const r = simulate("dynamic12", tasks(512), stable(10), { coldController: true });
  assert.equal(r.records[0].budget, 4);
  assert(r.records.slice(0, 8).every(row => row.budget === 4));
  assert.equal(r.records[8].budget, 5);
});
check("current haveTime is rechecked between operations", () => {
  const r = simulate("fixed12", tasks(128),
    () => ({ pre: 9.2, tail: 0.8, haveTimeUntil: 12 }));
  assert(r.records.some(row => row.stopReason === "SERVER_NO_TIME"));
});
check("outside tick navigation is billed but excluded from base estimate", () => {
  const r = simulate("dynamic12", tasks(128),
    () => ({ pre: 9.2, tail: 0.8, outsideNavigation: 3 }));
  assert(r.records.every(row => Math.abs(row.baseEstimate - 10) < 1e-9));
  const first = r.records[0];
  assert(Math.abs(first.balance - (first.balanceAfterRefill - first.charged - 3)) < 1e-9);
});
check("single long atomic operation can exceed fifty", () => {
  const r = simulate("dynamic12", tasks(128, () => 0, id => id === 0 ? 50 : 0.2), stable(10));
  assert(r.over50Ticks > 0);
  assert.equal(r.records[0].dispatched, 1);
  assert(r.records[0].balance < 0);
});
check("unpredicted END tail can exceed fifty", () => {
  const r = simulate("dynamic12", tasks(512), tick => ({
    pre: 29.2, tail: tick === 0 ? 16 : 0.8 }));
  assert(r.over50Ticks > 0);
  assert(r.records[1].budget <= r.records[0].budget / 2);
});

const scenarios = [];
for (const base of [10, 30, 40, 48]) {
  for (const concurrency of [128, 256, 512])
    scenarios.push({ name: `stable-${base}ms-${concurrency}`, tasks: tasks(concurrency),
      profile: stable(base) });
}
scenarios.push(
  { name: "cold-controller-512", tasks: tasks(512), profile: stable(10),
    options: { coldController: true } },
  { name: "continuous-16-per-tick-512", tasks: tasks(512, id => Math.floor(id / 16)),
    profile: stable(10) },
  { name: "dependency-arrivals-512", tasks: tasks(512, id => Math.floor(id / 64) * 8),
    profile: stable(10) },
  { name: "load-step-30-to-48-to-30", tasks: tasks(512), profile: tick =>
    ({ pre: tick >= 6 && tick < 14 ? 47.2 : 29.2, tail: 0.8 }) },
  { name: "unexpected-tail-512", tasks: tasks(512), profile: tick =>
    ({ pre: 29.2, tail: tick === 0 ? 16 : 0.8 }) },
  { name: "long-atomic-512", tasks: tasks(512, () => 0, id => id === 0 ? 50 : costsMs[id % costsMs.length]),
    profile: stable(10) },
  { name: "have-time-cutoff-512", tasks: tasks(512),
    profile: () => ({ pre: 9.2, tail: 0.8, haveTimeUntil: 12 }) },
  { name: "outside-navigation-512", tasks: tasks(512),
    profile: () => ({ pre: 9.2, tail: 0.8, outsideNavigation: 3 }) }
);

const results = scenarios.map(scenario => {
  const rows = modes.map(mode => simulate(mode, scenario.tasks, scenario.profile, scenario.options));
  const reference = rows[0];
  const summary = rows.map(({ records, ...row }) => ({
    ...row,
    completionChangeVsFixed4Percent: row.completionMs !== null && reference.completionMs !== null
      ? (row.completionMs / reference.completionMs - 1) * 100 : null,
    readyP95ChangeVsFixed4Percent: row.readyWaitP95Ms !== null && reference.readyWaitP95Ms !== null
      ? (row.readyWaitP95Ms / reference.readyWaitP95Ms - 1) * 100 : null
  }));
  return { name: scenario.name, taskCostMs: { total: scenario.tasks.reduce((sum, task) => sum + task.cost, 0),
    min: Math.min(...scenario.tasks.map(task => task.cost)), max: Math.max(...scenario.tasks.map(task => task.cost)) },
    options: scenario.options ?? {}, summary, traces: rows };
});

const evidencePath = ".tmp/navigation-performance-high-20261013/high-v2-accelerated/performance-comparison.json";
const evidenceBytes = readFileSync(resolve(root, evidencePath));
const evidence = JSON.parse(evidenceBytes);
const samples = evidence.tickSamples;
const formalIndexes = new Set(evidence.trials.map((trial, index) => trial.warmup ? null : index).filter(index => index !== null));
const formalSamples = samples.filter(sample => formalIndexes.has(sample.trial));
const historical = {
  path: evidencePath, sha256: sha(evidenceBytes),
  kind: "OBSERVATIONAL_EXISTING_FIXED4_RUN_NOT_REPLAY_INPUT",
  allSamples: samples.length, formalSamples: formalSamples.length,
  fields: Object.keys(samples[0]),
  samplesWithPending: formalSamples.filter(sample => sample.pending > 0).length,
  pendingWorkP95Ms: quantile(formalSamples.filter(sample => sample.pending > 0).map(sample => sample.workNanos / 1e6), 0.95),
  missingCausalInputs: ["fullTickWork", "perTickNavigationCharge", "budget", "balance",
    "runnable", "dependencyWaiting", "perOperationCost", "lateEndTail"],
  cannotInfer: ["budget contribution to latency", "dynamic production performance", "complete server MSPT"]
};

mkdirSync(output, { recursive: true });
const document = {
  schema: "DYNAMIC_BUDGET_RESEARCH_MODEL_V1",
  scope: "DETERMINISTIC_SYNTHETIC_SCHEDULER_MODEL_NOT_MINECRAFT_BENCHMARK",
  sourceSha256: sha(readFileSync(fileURLToPath(import.meta.url))),
  constants: { targetMs, maximumMs, minimumStartMs, historySize, initialHistorySize, alpha: 0.2,
    growthMsPerTick: 1, minimumTailReserveMs: 2, mandatoryNavigationMs: 0.3,
    warmupTicks: 64, horizonTicks: 240, tickIntervalMs: 50, costsMs },
  exclusions: ["entities and pathfinding", "priority and dimension fairness", "TTL and cancellation",
    "GC and JVM effects", "controller execution overhead", "intertick non-navigation tasks",
    "dependence of operation cost on mode", "measured production cost distribution"],
  sharedGuards: "All four modes share the new full tick envelope, historical tail reserve, per-start haveTime and debt accounting. fixed4 is a model quota reference, not an exact reproduction of current production.",
  checks, historical, results
};
writeFileSync(resolve(output, "evidence.json"), JSON.stringify(document, null, 2) + "\n");
const compact = results.map(({ name, summary }) => ({ name, summary }));
writeFileSync(resolve(output, "summary.json"), JSON.stringify(compact, null, 2) + "\n");
console.log(JSON.stringify({ output, checksPassed: checks.length, scenarios: scenarios.length,
  variants: scenarios.length * modes.length, historical }, null, 2));
for (const result of results.filter(row => [
  "stable-10ms-512", "stable-30ms-512", "stable-40ms-512", "stable-48ms-512",
  "continuous-16-per-tick-512", "dependency-arrivals-512", "unexpected-tail-512", "long-atomic-512"
].includes(row.name))) {
  console.log(JSON.stringify({ name: result.name, rows: result.summary.map(row => ({
    mode: row.mode, completed: row.completed, completionMs: row.completionMs,
    changePercent: row.completionChangeVsFixed4Percent, p95: row.readyWaitP95Ms,
    maxTick: row.workMaxMs, over50: row.over50Ticks
  })) }));
}
