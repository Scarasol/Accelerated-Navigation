import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = ".tmp/navigation-dynamic-budget-production-20261014";
const identities = [];
const read = file => {
  const bytes = readFileSync(file);
  identities.push({ path: file, sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase() });
  return JSON.parse(bytes.toString("utf8").replace(/^\uFEFF/, ""));
};
const sum = xs => xs.reduce((a, b) => a + b, 0);
const median = xs => {
  const values = xs.filter(Number.isFinite).sort((a, b) => a - b);
  if (!values.length) return null;
  const i = Math.floor(values.length / 2);
  return values.length % 2 ? values[i] : (values[i - 1] + values[i]) / 2;
};
const change = (candidate, baseline) => baseline ? (candidate / baseline - 1) * 100 : null;
const format = n => n == null ? "未完成观察" : Number(n.toFixed(3));
const table = (columns, rows) => `| ${columns.join(" | ")} |\n| ${columns.map(() => "---").join(" | ")} |\n`
  + rows.map(row => `| ${row.map(value => typeof value === "number" ? format(value) : value).join(" | ")} |`).join("\n") + "\n";
const compact = report => ({
  identities: report.identities, summaries: report.summaries,
  pairedTrials: report.comparisons.length,
  lost: sum(report.comparisons.map(c => c.lostSuccessActors.length)),
  gained: sum(report.comparisons.map(c => c.gainedSuccessActors.length)),
  differences: report.comparisons.map(c => ({
    candidate: c.candidate, reference: c.reference, distance: c.distance,
    concurrency: c.concurrency, repeat: c.repeat, commonSuccess: c.commonSuccess,
    lost: c.lostSuccessActors, gained: c.gainedSuccessActors,
    arrivalCandidateMs: c.pairedArrivalCandidateMs, arrivalBaselineMs: c.pairedArrivalReferenceMs,
    arrivalChangePercent: c.pairedArrivalChangePercent, percent: c.differencePercent
  })),
  formalEvents: report.rows.flatMap(row => row.events.map(event => ({
    run: row.run, distance: row.distance, concurrency: row.concurrency, ...event
  }))),
  allEvents: report.runs.map(run => ({ run: run.name, events: run.allEvents }))
});
const artifact = read(`${root}/formal-frozen-v2/identity.json`);
const formal = read(`${root}/final-five-repeat-comparison/comparison.json`);
const old = read(`${root}/formal-v1-weighted/comparison.json`);
const screen = read(`${root}/screen-two-rounds/comparison.json`);
const actor281 = read(`${root}/actor281-review-evidence.json`);
const supplements = {};
for (const name of ["cold", "continuous-hot", "continuous-cold", "load30", "load40"])
  supplements[name] = compact(read(`${root}/final-${name}-comparison/comparison.json`));
const checks = [];
for (const [name, resultFile] of Object.entries({
  speed: "diagnosis.json", boundaries: "diagnosis.json", wait: "wait-boundaries.json",
  replay: "partial-replay.json", "tick-load": "tick-boundary-load.json", lifecycle: "result.json"
})) {
  const run = `${root}/final-${name}`;
  const result = read(`${run}/${resultFile}`);
  const exit = read(`${run}/exit.json`);
  const launch = read(`${run}/launch-environment.json`);
  assert.equal(exit.exitCode, 0); assert.equal(exit.timedOut, false); assert.equal(result.failed, false);
  const assertions = result.checks ?? [];
  assert.ok(assertions.every(c => c.passed !== false));
  const shutdown = existsSync(`${run}/shutdown.json`) ? read(`${run}/shutdown.json`) : null;
  if (shutdown?.passed != null) assert.equal(shutdown.passed, true);
  if (shutdown?.failed != null) assert.equal(shutdown.failed, false);
  const budget = existsSync(`${run}/budget-controls.json`) ? read(`${run}/budget-controls.json`) : null;
  if (budget) assert.equal(budget.passed, true);
  checks.push({
    name, launch, exit, failed: result.failed, checks: assertions.length,
    checkNames: assertions.map(c => c.name), budget, shutdown,
    trials: result.trials ?? null, routes: result.routes ?? null, replays: result.replays ?? null,
    taskCount: result.tasks?.length ?? null,
    actualIntegratedPauseTested: result.actualIntegratedPauseTested ?? false
  });
}
const ledger = {
  schema: "FINAL_PRODUCTION_BUDGET_REVIEW_V1",
  status: "candidate validation; independent acceptance still required",
  scope: "dedicated server; actual IntegratedServer pause not tested",
  artifact, formal: compact(formal), oldTwoTick: compact(old),
  screen: compact(screen), actor281, supplements, checks, identities
};
const output = `${root}/final-review-ledger.json`;
assert.equal(existsSync(output), false, "preserve previous evidence");
writeFileSync(output, JSON.stringify(ledger, null, 2) + "\n");

const rows = [];
for (const baseline of formal.summaries.filter(s => s.mode === "baseline-fead3a1")) {
  const candidate = formal.summaries.find(s => s.mode === "dynamic20-grace3"
    && s.distance === baseline.distance && s.concurrency === baseline.concurrency);
  assert.ok(candidate);
  const paired = formal.comparisons.filter(c => c.distance === baseline.distance && c.concurrency === baseline.concurrency);
  rows.push({ baseline, candidate, paired });
}
const candidateRows = formal.summaries.filter(s => s.mode === "dynamic20-grace3");
const report = [
  "# 动态预算最终候选比较与验收记录",
  "",
  "日期：2026-10-14。状态：最终20ms/三tick包的测试记录，尚待独立发布验收；未部署。",
  "",
  "唯一现行架构依据是2026-10-13《原版详细寻路接入现行契约》。旧方案、旧审核不参与判断。初始12ms/两tick接入准备稿只记录研究起点；20ms与三tick分别是本轮修订候选。",
  "",
  "## 版本、输入和统计口径",
  "",
  `最终发行包SHA256：\`${artifact.artifacts.find(a => a.name === "dynamic20-grace3.jar").sha256}\`。真实固定4ms基线为fead3a1，正式比较共用冻结公共只读探针315F47D748BDD1A8143C8B37283ACB3E324018A7A8DE0A9BAF7EDB024D2B4BD5。最终包的228个生产类与已审核三tick研究包字节码一致。`,
  "",
  "每模式五个独立JVM、8,960个正式请求，18/28格×128/256/512；成人尸壳、速度II、无自主目标、关闭实体推挤，同一seed、8类地形、不同起终点，逐actor核对输入。没有模拟生产中的实体推挤和全部AI负载。复用本轮已经完成的五次冻结基线，随后顺序完成五次最终候选；没有再重跑基线，故仍有运行时间顺序和宿主机负载变化的局限。未与历史不同仪表的数据混算。补充场景采用另一冻结诊断探针，各场景内仍保持两模式同仪表，单次重复，单列报告。cold表示新JVM、新场景、跳过寻路预热，首批18格/512并发；后续批次已有缓存，不能称作每批完全冷输入。首次提交发生在启动40tick后，也不冒充控制器前8个有效tick的冷额度实测。",
  "",
  "完整tick包围实际tickServer虚调用，包含同步交付、回调、GC及测试收尾，不含睡眠、队列等待或tick间任务。正式窗为submitTick至endTick，包含收尾，所有真实尖峰均保留。以下P50/P95/P99为五次重复各自分位数的中位数；百分比为(候选/基线−1)×100%。实际到达仅使用两边都成功的逐actor配对，再取每次中位数及五次中位数。失败不进入到达时间指标，但在终态表完整报告。",
  "",
  "## 首Path等待和实际到达",
  "",
  table(["距离", "并发", "首Path P50基线/候选 ms", "变化%", "首Path P95基线/候选 ms", "变化%", "配对到达基线/候选 ms", "变化%"],
    rows.map(({ baseline: b, candidate: c, paired: p }) => [
      b.distance, b.concurrency, `${format(b.readyP50Ms)}/${format(c.readyP50Ms)}`, change(c.readyP50Ms, b.readyP50Ms),
      `${format(b.readyP95Ms)}/${format(c.readyP95Ms)}`, change(c.readyP95Ms, b.readyP95Ms),
      `${format(median(p.map(x => x.pairedArrivalReferenceMs)))}/${format(median(p.map(x => x.pairedArrivalCandidateMs)))}`,
      change(median(p.map(x => x.pairedArrivalCandidateMs)), median(p.map(x => x.pairedArrivalReferenceMs)))
    ])),
  "## 提交和累计工作",
  "",
  table(["距离", "并发", "API提交P50基线/候选 µs", "变化%", "累计导航计费基线/候选 ms", "变化%", "原生搜索墙钟变化%", "主线程分配变化%"],
    rows.map(({ baseline: b, candidate: c }) => [
      b.distance, b.concurrency, `${format(b.apiSubmitP50Us)}/${format(c.apiSubmitP50Us)}`, change(c.apiSubmitP50Us, b.apiSubmitP50Us),
      `${format(b.navigationMs)}/${format(c.navigationMs)}`, change(c.navigationMs, b.navigationMs),
      change(c.nativeWallMs, b.nativeWallMs), change(c.mainAllocatedMB, b.mainAllocatedMB)
    ])),
  "API提交时间、提交到首Path和实体实际到达是不同指标。额度增加主要减少排队，不代表详细算法总工作量降低。主线程分配含探针；Windows CPU计时粒度粗，全部原始CPU/分配/调用/恢复及wake到派发指标保存在comparison.json，不把零CPU样本解释成无成本。",
  "",
  "## 完整tick和掉刻",
  "",
  table(["距离", "并发", "P99基线/候选 ms", "最大基线/候选 ms", ">50ms基线/候选", ">100ms基线/候选", "候选最长连续", "候选事件/千tick", "窗口TPS基线/候选"],
    rows.map(({ baseline: b, candidate: c }) => [
      b.distance, b.concurrency, `${format(b.fullP99Ms)}/${format(c.fullP99Ms)}`, `${format(b.fullMaxMs)}/${format(c.fullMaxMs)}`,
      `${b.over50}/${c.over50}`, `${b.over100}/${c.over100}`, c.maxConsecutive, c.eventsPer1000Ticks, `${format(b.tps)}/${format(c.tps)}`
    ])),
  `候选正式窗合计${sum(candidateRows.map(s => s.over50))}个>50ms tick、${sum(candidateRows.map(s => s.over100))}个>100ms tick。事件率按总事件/总tick计算，不能用五次重复事件率中位数掩盖稀少事件。每次连续超时事件独立寻找后续首次连续五个≤50ms tick，观察不足时记未完成。全部事件的完整时间、导航计费、额度及恢复记录保存在审核账本。`,
  "",
  "允许偶发一两个慢tick，不主动放大45ms工作窗口，也不取消haveTime/实时截止。不能把单个tick耗时的倒数称作TPS；没有玩家体感实验，不能承诺玩家无感。",
  "",
  "## 空间",
  "",
  table(["距离", "并发", "heap峰值基线/候选 MB", "变化%", "基础缓存基线/候选 MB", "走廊缓存基线/候选 KB", "发布节点峰值基线/候选"],
    rows.map(({ baseline: b, candidate: c }) => [
      b.distance, b.concurrency, `${format(b.heapPeakMB)}/${format(c.heapPeakMB)}`, change(c.heapPeakMB, b.heapPeakMB),
      `${format(b.baseCacheMB)}/${format(c.baseCacheMB)}`, `${format(b.corridorCacheKB)}/${format(c.corridorCacheKB)}`,
      `${format(b.publishedNodesPeak)}/${format(c.publishedNodesPeak)}`
    ])),
  "heap峰值包含世界、测试结果保留、JVM和GC时机，不能等同生产寻路净空间。控制器保持32项固定历史、一个排序scratch和上一tick快照；逐tick/逐派发/轨迹只在隔离测试mod中有界保留。",
  "",
  "## 终态及旧失败",
  "",
  `最终候选相对基线新增配对成功${ledger.formal.gained}个，新增配对失败${ledger.formal.lost}个。两tick旧候选新增配对失败${ledger.oldTwoTick.lost}个，其原始结果仍保留。基线本身已有EXHAUSTED等失败，不以总体净成功增加代替新增失败门槛。`,
  "",
  table(["距离", "并发", "基线成功/请求", "候选成功/请求", "新增成功", "新增失败"],
    rows.map(({ baseline: b, candidate: c, paired: p }) => [
      b.distance, b.concurrency, `${b.success}/${b.requested}`, `${c.success}/${c.requested}`,
      sum(p.map(x => x.gainedSuccessActors.length)), sum(p.map(x => x.lostSuccessActors.length))
    ])),
  "actor25已有直接轨迹与真实partial重放：两tick用尽时仅差不足0.001格进入组件，过早恢复改变最终搜索起点，原生路径长度限制令开放集耗尽。三tick保留一个自然物理tick，未修改range、accuracy、节点或恢复限额。",
  "",
  `actor281另有独立受控边界实验：不偏移的${actor281.unperturbedObservedCases}个观测请求和+0.022格起点实验均未失败；+0.03格的结果为旧两tick${actor281.before.state}/${actor281.before.calls}次搜索，新三tick${actor281.after.state}/${actor281.after.calls}次搜索。新旧起点相同，首原生输入相同=${actor281.firstInputsEqual}，首真实节点相同=${actor281.firstNodesEqual}；受控旧终态/原生聚合签名与历史actor281一致=${actor281.signaturesEqual}。固定输入重放${actor281.replay?.checks?.length ?? 0}项检查，其完整节点和终止分支记录保留。+0.03格是诊断输入，未改生产参数，不混入正式性能样本。`,
  "",
  "actor281历史原始失败没有交接轨迹，多次原输入追加追踪没有再失败。独立受控复现支持相同提前恢复机制，但不唯一证明历史失败的时序；这一限制明确提交最终审核，不能用actor25替代，也不能用净成功增加自动消除门槛。",
  "",
  "## 冷热、持续与真实实体负载",
  ""
];
for (const [name, data] of Object.entries(supplements)) {
  report.push(`### ${name}`, "", `单次配对矩阵：新增成功${data.gained}、新增失败${data.lost}。`,
    "", table(["模式", "距离", "并发", "首PathP50 ms", "首PathP95 ms", "完整tickP99 ms", "最大 ms", ">50ms", "成功/请求"],
      data.summaries.map(s => [s.mode, s.distance, s.concurrency, s.readyP50Ms, s.readyP95Ms,
        s.fullP99Ms, s.fullMaxMs, s.over50, `${s.success}/${s.requested}`])),
    "", table(["距离", "并发", "首PathP50变化%", "首PathP95变化%", "配对到达变化%", "导航计费变化%", "heap峰值变化%"],
      data.differences.map(d => [d.distance, d.concurrency, d.percent.readyP50Ms,
        d.percent.readyP95Ms, d.arrivalChangePercent, d.percent.navigationMs, d.percent.heapPeakMB])), "");
}
report.push("## 生命周期和边界", "",
  table(["检查", "业务断言", "场景数", "进程秒", "结果"],
    checks.map(c => [c.name, c.checks, c.trials?.length ?? c.routes?.length ?? c.replays?.length ?? c.taskCount ?? 0,
      c.exit.seconds, c.failed ? "失败" : "通过"])),
  "关闭归零和迟到拒绝由最终生命周期与真实等待故障验证。tick-load包含10/30/40/48ms基础负载、60ms真实原子调用、未知12ms END尾部，以及实际服务端事件循环中的10/40/60ms tick间任务。合成缺事件检查不等同真实IntegratedServer暂停，本轮交付范围限已验证专用服务器。",
  "",
  "## 证据与发布条件", "",
  "详细数据、SHA256和全部事件：`.tmp/navigation-dynamic-budget-production-20261014/final-review-ledger.json`；正式明细：`final-five-repeat-comparison/comparison.json`。测试执行器为`tools/benchmark/run-final-budget-validation.ps1`。运行命令、实际mod身份、退出状态和原始日志逐目录保留。",
  "",
  "最终发布还需DeepSeek按现行架构及明确修订候选验收，并明确判断actor281历史时序证据限制是否影响交付。审核通过后才更新唯一现行契约，提交一个可整体回退的版本。不加运行时开关，不修改派生拓扑研究，不宣称已上线。", "");
const reportPath = resolve("docs/reports/2026-10-14-dynamic-budget-production-validation-zh.md");
assert.equal(existsSync(reportPath), false, "preserve previous report");
writeFileSync(reportPath, report.join("\n"));
console.log(JSON.stringify({
  output, reportPath, lost: ledger.formal.lost, gained: ledger.formal.gained,
  supplementLost: Object.fromEntries(Object.entries(supplements).map(([name, data]) => [name, data.lost]))
}, null, 2));
