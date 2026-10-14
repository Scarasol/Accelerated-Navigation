# 动态预算新增失败的交接根因研究

日期：2026-10-14。本轮从 10 月 13 日跨日进行。状态：独立研究原型，未修改生产寻路，未上线动态预算。唯一现行设计依据是 [原版详细寻路接入契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)；约 4ms/tick 仍是生产条款。废弃设计和以前的审核结论未作为本轮依据。

## 结论

已确认此前目标路线新增失败的直接触发链：**原版中间段节点耗尽时，实体仍在宏观目的组件外；快速派发把“尚未实际到达”立即变为恢复请求。** 随后的额外中间搜索改变了下一次最终搜索的起点，产生真实 partial，最终正确返回 EXHAUSTED。固定 4ms 的排队等待使实体先跨过边界，掩盖了这个时序问题。

这次确认的范围是额外中间搜索的来源及其对失败的因果作用。尚未用固定输入重放分别排除最终 partial 内部的距离、访问上限或邻居碰撞分支；不能宣称已确认其最底层搜索终止原因，也不能把水平速度本身当作确定根因。

一 tick 等待在本次 18 格目标路线有效，但 28 格仍失败。两 tick 有界等待在同 jar 的两轮 18/28 格、512 并发试验中，使四条目标路线的 8 个请求全部真实成功。相对同版本未修改交接的动态组，2,048 个正式请求新增成功 6 个、新增配对失败 0 个；原生调用由 4,675 降至 4,458，减少 **4.64%**，恢复由 371 降至 154，减少 **58.49%**。这些是诊断批次的调用数结果，不能代替正式时间、内存或 TPS 验收。

两 tick 是实验值，未证明适用于速度 IV、其他实体、跳落、持续请求或全部生产地形。当前建议是继续验证中间段的有界交接等待，并保留真实到达、原生停止与输入失效的分支；不能直接把两 tick 或动态 12ms 定为生产默认值。

## 分支证据

生产观察在 END 阶段、预算派发之前运行。`observe` 只有在当前 Path 未耗尽、正在最后节点、窗口和原生输入当前且实体已进入目的组件时，才建立经过记录。`physical` 判断地面、垂直稳定和执行期碰撞，不要求水平静止。`settle` 在没有经过记录且当前位置不属于目的组件时，排队 `recover`。

追踪版的 18 格 actor29，两个模式的首段目标、range、accuracy、multiplier 和真实节点一致，真实末节点均为 `(-17,81,-145)`：

| 时点 | 动态组实体位置 / 判定 | 实际动作 |
|---|---|---|
| tick 675 | z=-143.95339468，BlockPos z=-144；Path 已耗尽；physical=true；窗口与 proof 均 current；没有经过记录；contains=false；水平速度约 0.06672 | intermediateArrival=false，同一 END 的队列执行 recover，attempt 从 0 变为 1 |
| tick 676 | 实体已跨入目的组件 | 请求已处于恢复准备/排队，观察不撤销它 |
| tick 677 | z=-144.05654634，BlockPos z=-145，contains=true | 仍执行恢复目标 `(-18,81,-146)`，真实 reachable |
| tick 684 | 起点约 `(-17.10298,81,-144.95146)` | 搜索最终目标 `(-17,81,-155)`，真实 partial，末节点 `(-17,81,-153)` |

固定 4ms 的同一请求在 tick 772 首次记录节点耗尽，tick 927 才执行 settle；此时 z=-144.09689510，contains=true，水平速度为 0，直接推进最终段，并取得真实 reachable。155 tick 是这个诊断批次的排队事实，不是建议增加的固定等待。

该批动态 8 个目标请求中 7 个失败、1 个成功。7 个失败均有第一次 intermediateArrival=false、窗口/proof 当前、无经过记录、无原生 stop、由 `lambda$settle$33` 调用 recover 的完整链。成功请求因 settle 晚了两 tick，直接判定到达。原生停止不是这些目标失败的触发源；经过记录的代次错误和输入过期也没有出现在这些失败处。

发行对应的 Forge 47.3.0 源码显示 `followThePath` 使用节点接近阈值推进节点。节点耗尽与实体进入宏观目的组件不是同一个时刻。已从固定哈希源码 jar 提取 PathNavigation、WalkNodeEvaluator、Path、PathFinder 和 MoveControl，记录在 [源码身份](../../.tmp/navigation-budget-handoff-20261014-grace2/upstream-identities.json) 中。没有拿旧设计文档替代原生源码。

## 单变量原型

[HandoffTiming](../../tools/benchmark/budget-prototype/java/an/budget/HandoffTiming.java) 由测试 mod 的 [Mixin](../../tools/benchmark/budget-prototype/java/an/budget/mixin/BudgetHandoffTimingMixin.java) 在 settle 入口实现，默认 `unchanged`。没有编辑 `src/main`，没有改宏观拓扑、派生持久化、目标筛选、原版参数、accuracy、节点上限或恢复次数。

只有下面条件同时成立才延后原 settle：

1. 已采用的分段任务，正常可达的中间段；不是最后段、partial 或原生 stop。
2. 当前原生 Path 仍是本请求，真实节点已耗尽且非空。
3. `gameTime - doneSince` 小于本实验的 1 或 2 tick，不重置 doneSince，也不延长原 TTL。
4. 原窗口和实际读取 proof 仍 current；没有经过记录，实体当前仍不在目的组件。

延后只取消这一次 settle。下一 END 由原观察流程重新入队，再执行原 physical/intermediateArrival；一旦进入组件立即使用原到达判断。超过等待界限继续原恢复；输入失效继续原重新搜索路径，原生 stop、最后段和 partial 继续原分支。整个任务的 isDone、原生节点和终态规则没有另写替代判定。

一 tick 版的 28 格 actor25：首次耗尽时 z=-143.91729755；下一 tick z=-143.98401930，仍属于 z=-144，因此原 settle 又进入恢复；再下一 tick z=-144.02044938，才进入目的组件。这给出继续测试两 tick 的直接证据，而不是按成功率试放宽参数。

额外控制状态是有限字段缓存，不新增每实体历史或搜索。复用的 current/contains 检查最多涉及已有 4 个窗口 section 和 256 个 proof 项，不增加寻路的渐近复杂度。逐项诊断记录仍会随采样数增长，仅用于有限研究批次；不能把它当作生产有界仪表。

## 版本和结果隔离

各批生产 jar 和路线探针完全相同。原型版本改变时使用新 EvidenceRoot，冻结新 jar；没有复用首轮动态预算的旧原型包。每个版本都保留预热、正式试验、原始失败、退出状态、两个实际跨线程拒绝检查和计费余额守恒核对。

| 批次 / 原型 SHA-256 | 同版本比较 | 正式请求与结果 |
|---|---|---|
| 追踪版 / `EAEB08C871BCEB8E053EFAA6DCB23766BF1E3F5477482624F1D7AD6A679C17A7` | 固定4 vs 动态；各 2,048 请求 | 目标路线成功 8/8 vs 1/8；动态新增目标配对失败 7 个。固定4另有 158 个 UNAVAILABLE |
| 一 tick 版 / `2E2044F0E3BBE1C83B8DA8B9D863A3E62AD8BB17E3D2A5C2B2B2E3E88FE47BBE` | 动态 unchanged vs grace1；各 2,048 请求 | 1,960 vs 1,964 成功；配对新增成功 4、失败 0；28 格四个目标请求仍失败 |
| 两 tick 版 / `EA5A4F96C6B0DD24A4095F007962B0534FFAE59F952757F72DAD9E84769D0A03` | 固定4、动态 unchanged、动态 grace2；各 2,048 请求 | 动态 unchanged 1,962 成功，grace2 1,968；六个本批新增失败全部消除，目标路线成功 8/8 |

第一批 7 个、一 tick 批的 8 个未修改交接目标失败、两 tick 批的 6 个属于独立试验，不相加，也不拿它们改写前轮正式性能批的失败数。

两 tick 版正式逐场景结果：

| 距离 / 重复 | 固定4 成功 / UNAVAILABLE / EXHAUSTED | 动态 unchanged 成功 / EXHAUSTED | 动态 grace2 成功 / EXHAUSTED |
|---|---|---|---|
| 18 / 0 | 480 / 0 / 32 | 478 / 34 | 480 / 32 |
| 28 / 0 | 364 / 144 / 4 | 503 / 9 | 504 / 8 |
| 28 / 1 | 330 / 178 / 4 | 503 / 9 | 504 / 8 |
| 18 / 1 | 480 / 0 / 32 | 478 / 34 | 480 / 32 |

这里固定4的 28 格出现了 322 个 UNAVAILABLE；这些失败完整保留。不能将全批成功数差异归为交接修复，也不能把调用更少的固定4解释为算法更省，因为它有请求未执行完整路径。非目标请求没有逐项 finish 分支记录，不能仅凭 UNAVAILABLE 枚举确认每个都是 TTL。

被追踪的四条目标路线首段输入和节点一致性、所有 actor 起终点对齐、实际调用数、没有整条执行回退以及余额守恒均由 [分析程序](../../tools/benchmark/analyze-budget-handoff.mjs) 核对。首段节点一致性不扩展到未追踪的其余实体。[各批汇总和分支证据](../../.tmp/navigation-budget-handoff-20261014-grace2/study-evidence.json) 保存其输入哈希；原始逐 tick、分支和真实节点文件仍保留在各 run 目录。

## 关闭追踪的敏感性检查

为确认高追踪开销下的结果边界，使用相同两 tick 版 jar、相同生产与路线输入，关闭逐项追踪，另跑固定4和动态 grace2 各一轮 18/28 格、512 并发。它只检查仪表敏感性与目标路线，不能替代五次独立正式性能重复；结果单独登记，不混入上述两轮追踪比较。

| 距离 | 固定4 成功 / UNAVAILABLE / EXHAUSTED | 动态 grace2 成功 / UNAVAILABLE / EXHAUSTED |
|---|---|---|
| 18 | 480 / 0 / 32 | 480 / 0 / 32 |
| 28 | 345 / 163 / 4 | 504 / 0 / 8 |

双方的四个目标请求均成功且各执行两次原生搜索。逐 actor 配对没有新增失败，新增成功 159；该差异包含固定4未完整执行的请求，不能归为交接等待的独立收益。关闭追踪后固定4仍有 163 个 UNAVAILABLE，因此不能仅归咎于逐项追踪开销；其排队、主线程计费与超时来源仍需另行归因。这里没有无追踪的动态 unchanged 组，不据此计算 grace2 的独立性能效果。

本轮共 9 个独立 JVM、32 个正式场景、16,384 个正式请求，另有 18 个 64 实体预热场景。三种原型版本及无追踪批分别登记，18 个实际跨线程入口拒绝检查全部通过。原生搜索、回退计数、余额守恒和请求终态保留在各自版本，不拼成一个生产验收。

历史固定4的正式性能数据继续作为性能基线。本轮重跑固定4用于补历史数据缺少的交接分支与同包辅助对照；交接等待的主要因果对照是同版本动态 unchanged 与 grace2。后续只针对改动影响的路线或仪表变化补测，不重复完整性能矩阵。

## 生产接入仍需验证

本轮完成的是目标失败触发链和有界等待的因果原型。下面仍是接入前条件：

- 速度 IV、不同体型/导航能力、跳落、持续提交、128/256 并发、其他地形及冷热拓扑，确认两 tick 的适用边界，或研究按运动状态结束的有界等待。
- 对 native-stop、真实 partial、窗口/proof 失效、取消替换和关闭分别注入事件；本轮延迟排除条件主要有源码证据，不能声称全部独立回归通过。
- 对最终 partial 做同位置、同参数的原生输入重放，分别测起点变化、运动状态与实际终止上限。
- 补有界生产仪表、至少五次乱序重复、单次原子操作长耗时与 tick 间负载、完整生命周期和暂停验证。

## 复现

```powershell
.\gradlew.bat --offline -I tools/benchmark/budget-prototype/prototype.gradle budgetPrototypeJar reobfBudgetPrototypeJar
.\tools\benchmark\run-budget-prototype.ps1 -EvidenceRoot '.tmp/navigation-budget-handoff-new' -Run 'dynamic-unchanged' -Mode dynamic12 -Handoff unchanged -Trace -Distances '18,28' -Concurrency '512' -Repeats 2
.\tools\benchmark\run-budget-prototype.ps1 -EvidenceRoot '.tmp/navigation-budget-handoff-new' -Run 'dynamic-grace2' -Mode dynamic12 -Handoff grace2 -Trace -Distances '18,28' -Concurrency '512' -Repeats 2
node tools/benchmark/analyze-budget-handoff.mjs .tmp/navigation-budget-handoff-new dynamic-unchanged dynamic-grace2
```

必须使用新的证据根测试新构建；运行器会拒绝没有交接实验 Mixin 的旧冻结包，并核对实际执行模式与启动请求。本文链接的历史证据根已冻结，不应覆盖。

## DeepSeek 独立审核

DeepSeek v4.1 Flash 返回 **CONSENSUS_WITH_GATES**，本轮必须修改项为“无”。确认交接触发链、正常中间段的有界等待边界、同版本配对与百分比、失败保留和版本分离；同意继续隔离研究。历史固定4性能结论可复用，本轮固定4只补分支与同包辅助对照。

审核没有批准生产上线、把两 tick/12ms 定为默认值或更新冻结设计。最终 partial 内部终止原因、速度 IV 和其他实体、事件注入、完整生命周期、冷热拓扑及正式性能仍按上文门槛处理。

首次完整附件审核在 481.92 秒超时，没有最终判定，未记为通过；原记录保留。随后精简为全部状态总数和目标路线结果、代表性真实轨迹、当前源码及现行契约，119.402 秒完成，退出 0、未超时。两轮都没有附旧设计或任何此前审核；首次超时内容也没有作为第二次审核依据。

送审后只追加本节登记，没有修改原型源码、数据或已审核结论。首段节点一致性只覆盖四条被追踪路线，未扩大为全实体逐搜索一致性；审核原文也保留这个证据边界。

- [审核原文](../../.tmp/navigation-speed-root-20261013/deepseek-answer-dynamic-budget-handoff-compact-20261014.txt)
- [退出状态与提示词哈希](../../.tmp/navigation-speed-root-20261013/deepseek-exit-dynamic-budget-handoff-compact-20261014.json)
- [送审附件身份](../../.tmp/navigation-speed-root-20261013/deepseek-identities-dynamic-budget-handoff-compact-20261014.json)
- [精简审核证据](../../.tmp/navigation-budget-handoff-20261014-grace2/compact-review-evidence.json)
- [完整验证账本](../../.tmp/navigation-budget-handoff-20261014-grace2/verification-ledger.json)
- [首次超时记录](../../.tmp/navigation-speed-root-20261013/deepseek-exit-dynamic-budget-handoff-root-20261014.json)
