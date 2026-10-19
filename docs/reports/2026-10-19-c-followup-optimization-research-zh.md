# C 后续优化方式：端口空行与边界快照复制

日期：2026-10-19。范围：复用历史数据、只读源码及联网研究。
本轮没有修改生产或隔离候选，没有编译、启动 Java/JMH/Minecraft 或新增性能测量。

## 判断

还有可继续研究的优化，建议按以下顺序独立推进：

1. **首选：在 GROUND Boundary 构建中，先判断源端口行是否为空，再进入方向、距离和高度差循环。**
2. **第二项：消除 BuildInput 边界快照中的中转数组及重复防御性复制。**

两项都针对 C 中仍然存在的实际工作，不新增全局缓存、图级引用账目或有效性缓存。
它们不是已经测得的提速，也没有证据证明能完全消除冷 air 的回退。
第一项涉及稀疏行循环，第二项涉及冷构建分配，应分别形成候选，不能先叠加再猜贡献。

用户随后提醒：历史候选有因其它实现或验证缺陷而封存的情况，不能因此否定方法。
据此更正：上面的顺序只比较本轮展开的两处局部改动，
**不表示它们已经胜过全部历史方法**。历史方法应按具体封存原因重新评估，
不能因“已经试过或封存”直接排除。

## 当前数据决定的目标

以[接口对齐后的直接 C/B 复测](2026-10-19-interface-aligned-c-baseline-validation-zh.md)为当前依据：

| 负载 | C 相对原生产 B | 对下一步的含义 |
| --- | --- | --- |
| 冷 air | 查询均值 +1.01%/+3.71%，一对 P95 +15.49% | 优先降低冷构建工作；不能用热 air 的收益代替 |
| 冷 distinct | 查询均值 -15.85%/-17.04% | 保留 C 的组件构图减量，控制新增固定开销 |
| 冷 mixed | 查询均值 -17.13%/-25.36% | 同上；完整 CPU 第一对 +12.00% 仍保留 |
| 冷完整分配 | 六组下降 14.00% 至 14.59% | 可以进一步找复制冗余，但少分配不等于必然更快 |
| 热 air | CPU 三对下降 30.21% 至 44.14% | 不宜在没有归因的情况下先撤销 R2 的 run 路径 |
| 热 distinct | 第二对 CPU +4.52%、耗时 +2.82%，另两对改善 | 不把外层分支、JIT 或某个对象视为已确定根因 |

冷 air 完整 CPU 的 +20.83%/+44.00%、同期编译与 GC 原值保留。
现有短窗口有大量单次 CPU 零读数，不能据此确认稳定算法效应，
也不能把不利值全部解释成编译、GC 或噪声。
air/distinct/mixed 使用同一平地几何，只改变空气事实的共享身份。

## 首选：Boundary 源空行提前返回

### 源码依据

C 的 `SuperClusterTopology.boundaryLinks` 已在参数检查后跳过空组件端点。
两个图都非空时，仍依次进入：

```text
inset → v 行 → direction → distance → dy
    → BoundaryPortBits.candidates(...)
```

`candidates` 内先调用 `source.faceRow(face, inset, v)`；
若源行为 0，返回 0。对给定 face/inset/v，该源行不依赖 direction/distance/dy，
并且 `BoundaryPortBits` 发布后不修改。
因此一次源行为空，足以证明整个后续内层循环不产生任何候选。

“端口行”指 section 边界带上一行合法导航起点的位置位图；
“空行”指没有任何合法起点，不是把空气、未知几何或障碍检查忽略。
平地上较高位置可能是空气，却没有地面导航起点；跳过的是从这些位置发起连接的尝试。
从有效起点发出的跳跃、下落及跨边界连接仍执行完整检查。

这一思路承接 2026-10-17 已采用的两端合法锚点位图，
并非新发明的端口算法。现有 `candidates` 内已经有空行早返；
本次具体建议只是把这个判断提到外层循环。
核对本轮涉及的 10 月 17 日最终组合和当前 C 源码，均仍在内层调用 `candidates`。
未发现该外提改动本身已完成独立验证的记录，不据此宣称所有历史原型都从未做过它。
10 月 17 日未纳入组合的“单独行定位”处理的是碰撞几何按 x 行分段解析，
与这里的合法起点行不同，不能把那项负结果直接套用。

建议第一版只在 `boundaryLinks` 的 v 循环入口增加这个判断，
保留原 `candidates`、`mayLink` 和精确碰撞实现。
这会让有候选行仍经过原筛选，不需要同时重写多个调用方。
不先新增端口摘要、预扫描整个面、缓存结果或改变循环顺序。

源码入口：

- `.tmp/component-build-shortcuts-20261019-v1/C/main/java/com/scarasol/acceleratednavigation/topology/SuperClusterTopology.java:316`
- `.tmp/component-build-shortcuts-20261019-v1/C/main/java/com/scarasol/acceleratednavigation/topology/BoundaryPortBits.java:47`

### 历史数据能够支持多少

[C 构图报告](2026-10-19-component-build-shortcuts-validation-zh.md)的六次机会诊断均记录：
148 次 Boundary 中，114 次有空组件端点；
140,544 次候选循环中，120,960 次属于这些空端点。

所以，C 完成整图短路后，该固定构型仍对应 **34 次非空端点调用、19,584 次候选循环**。
这是从原计数减去 C 已跳过部分得到的静态剩余量，不是本轮新运行的计数。

该 fixture 的 flat 只在本地 y=1 有 GROUND_OPEN，非空图处在同一 section 高度，
实际相邻非空连接为水平面。按现有最大距离 3、dy 为 -4 至 1：

```text
每次水平 Boundary：16 行 × (3 + 2 + 1) 个距离 × 6 个 dy = 576 次
只枚举唯一非空高度行：1 × (3 + 2 + 1) × 6 = 36 次
34 次调用的结构推算：19,584 → 1,224 次内层候选循环
```

这里的约 93.75% 是**该平地构型中剩余候选循环的静态减量**，
不是 Boundary CPU、整查询耗时或城市负载的改善比例。
仍有每次 Boundary 最多 48 次源行入口判断；非空行还会执行原 `candidates`。
精确碰撞调用及最终成功边数量不会因为这个判断而减少。
HotSpot 也可能已经完成部分循环提升，因此源码减量不保证对应比例的机器工作减少。

### 语义与成本边界

- 只在既有不可变端口行确实为 0 时跳过；不以空气身份、组件数为 1 或 bbox 代替。
- 源行非空后，所有 target 行对齐、组件标签、门、体型、落差及严格碰撞判断保持。
- 不省略源/目标参数、几何及邻接检查；VOLUME 继续原标量分支。
- 被跳过部分原本不调用 `addBoundaryMask`，不会触发 `BoundaryGeometry` 的懒惰解析；
  因而不应减少原本会发生的几何身份冲突检查。
- 不改变图、事实、stamps、owner、依赖闭包及 retainedBytes。
- 稠密 GROUND 行会多一次入口判断；必须保留这种潜在不利，不能只看平地。

LLVM 官方资料说明了把循环不变量移到循环外的通用方法，
也提醒循环分支专用化可能膨胀代码。本方案只增加一个空行门，
不复制整套 Boundary 方法，更不能据 LLVM 文档推断 HotSpot 当前已做或未做什么。
来源见末节。

## 第二项：BuildInput 边界快照只创建最终数组

### 源码依据

C 的 `BuildInput.boundarySnapshot` 当前为四类数组分别执行：

1. 按原 halo 数 n 创建临时数组；
2. 选取 m 个边界成员，再 `Arrays.copyOf(..., m)`；
3. 调用 `BuildInput` 构造，构造内再次 `clone()`。

即一次边界快照源码上创建 **12 个数组**，最终仅保留其中 4 个。
这与旧 `BaseGraphView` 导出时复制版本戳列表是不同位置。
同类裁剪再复制也出现在 runtime 的 `buildInput` 捕获入口；
不建议第一版同时改这两个位置或顺带改 `withFacts`。

建议先只处理私有 `boundarySnapshot`：

- 第一遍按原几何条件计数，并完成原有 `used` 标记；
- 若确实没有删除任何 halo 成员，可研究直接复用原不可变 `BuildInput`；
- 需要截取时，创建 4 个精确长度的最终数组，再按原顺序填充；
- 通过私有内部构造持有这些新数组，不再次 clone；
- 保留面向调用方的现有防御性复制入口，以及原校验和字段计算。

这把需要截取的路径从 12 个数组降为 4 个，少 8 个中转数组及两轮成员复制；
但增加一次最多 26 项的筛选遍历。这里指源码分配机会，
不是已经测得的 JVM 分配字节或耗时。
不据此承诺减少整个查询多少分配，也不保证胜过 JVM 对旧代码的优化。

源码入口：

- `.tmp/component-build-shortcuts-20261019-v1/C/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java:806`
- 同文件 `:903` 的 `boundarySnapshot`。
- `.tmp/component-build-shortcuts-20261019-v1/C/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java:2540`
  为后置的捕获入口，首版不纳入。

### 所有权条件

私有持有入口只接受本类内部新建、填完后不再修改或向外暴露的数组；
不能把调用方仍可修改的数组或可复用 `BuildScratch` 数组直接交给已发布图。
偏移、事实身份和顺序、revision、fingerprint、hasDoors、airSlots、
`haloUsed` 和 `usedStamps` 的集合必须保持。
直接返回原输入只适用于筛选后成员完全相同的情况，不能扩大边界事实集合。
仍按已有表示计入留存；不通过调低 retainedBytes 制造分配收益。

OpenJDK 17 的 `listFromTrustedArray` 明确采用“调用方不再持有数组引用，
内部可直接持有”的规则，避免再次防御性复制；
Protobuf 的 `UnsafeByteOperations` 则说明后续修改共享底层数组会破坏不可变性。
本项目借鉴的是这个所有权边界，不调用 JDK 内部 API，也不引入 Protobuf 依赖。

## 历史尝试应按具体封存原因判断

先前将以下方向统一列为“本轮排除”过于笼统。
封存的是具体实现和已有证据的适用范围，不自动等于方法无效。
若缺陷来自测试或其它模块，应核对它是否影响目标方法的现有证据；
若来自候选实现，应判断能否局部修复；若只是未证明整体收益，应保留局部收益及限制。
不把尚未定位的其它成本当作已经证实的外部原因，也不因此删除原不利测量。

| 方向 | 实际证据与当前边界 |
| --- | --- |
| 再拆 run helper、singleton 提前分支 | D 正确性通过，但完整查询和控制收益不一致；停止的是该执行形态，不是证明所有段遍历改法无效 |
| 空图跳组件扫描、统一标签只扫一次 | C2 正确性通过、air 相对 C 回退；保留该具体实现不采用的判断，回退根因尚未确定 |
| 版本戳列表共享、current 流改循环 | 已有 24 fork，分配减量成立但计时不一致；仍有可复用机制证据，不能仅因封存就否定不可变数据共享 |
| RawRows 固定弱槽缓存 | 具体实现有 FIFO 反例；另一个补测的 oracle 也错误，不能将错误测试归为缓存几何错误；这些均不等于否定全部有界身份缓存 |
| RawRows fill 映射预计算 | 旧差分通过、局部计时有改善及波动，缺完整路径净收益；属于已有候选，不是本轮新发现 |
| ComponentSnapshot 去重 | 旧真实分层路径有快照实例 9→5，普通路径 4→4，另有验证范围不匹配；仍需结合当前重复率和普通路径额外 map 成本判断，不能因封存排除 |
| 图级引用聚合 | 按用户投入约束停止，整体不利成本未定位；方法未被证明无效，但既有停止条件仍有效，不凭局部收益重开 |
| ALL_AIR 专用 cell、有效性缓存 | 需分别核对旧实现和失效/所有权维护面；本轮没有完成足以统一排名的审计，不以封存一概否定 |
| 认为 cold 首次 refs 缺失就会走 scalar fallback | 这项推断与实际 helper 不符：预检成功会创建 refs 再加权应用，不能用这个错误前提提出候选 |
| 只保留 B 加 C 的两处构图修改 | 可作为将来的组件消融；会移除 R2 段合并，能否保留当前热 air 收益未知，现有证据不足以决定优劣 |

历史依据：
[独立交接审核](2026-10-16-latest-handoffs-independent-audit-zh.md)区分了 FIFO 实现缺陷、
错误测试 oracle、P0 实际重复与验证范围；
[Worker 算法探索](2026-10-17-worker-algorithm-exploration-zh.md)及
[最终组合](2026-10-17-final-worker-algorithm-combination-zh.md)区分了碰撞行定位与端口位图；
[版本戳复用研究](2026-10-18-derived-stamp-sharing-research-zh.md)保留局部分配收益和混合计时；
[图级引用停止决策](2026-10-18-graph-reference-route-stop-decision-zh.md)是投入取舍而非无效性证明。

冷 air 的相对回退尚未有逐方法因果闭环。
本轮推荐的是继续减少有源码依据的冷路径工作，
不是声称上述两处已经被证明是 air 回退根因。

## 下一步的明确范围

本轮只确定方案，**不执行验证**。
若选择本轮两处局部改动，先从冻结 C 独立做端口空行门，第二项另做独立候选。
若要决定包含历史候选的整体最优顺序，应先按上述具体原因复核，
本轮两项尚未获得相对所有历史方法的优先资格。
后续评价继续采用已经对齐的 B/C 接口：C 用于判断新增改动是否有效，
B 用于判断结果是否仍高于原生产基线；不必重新加入 R2 三方矩阵。
冷查询与热 readyGraph 分开，原正反数据都保留，
不能用空行循环比例、少数组数量或编译/GC解释替代完整查询结果。

## 本轮联网核查

2026-10-19 实际 HTTPS 读取下列官方原件；
网页检索工具没有返回可用内容，raw.githubusercontent.com 请求遇到 DNS 错误，
随后通过 GitHub HTML 的代码行及 LLVM 官方页面读取成功。
这是源码/文档研究，没有把网页示例当成本项目的实测收益。

1. OpenJDK 17，固定 tag `jdk-17+35`，commit
   `dfacda488bfbe2e11e8d607a6d08527710286982`。
   `ImmutableCollections.java:195–220` 的可信数组规则、
   `:663` 的 ListN 构造。与前轮不同时点缓存的 JDK17u master 原件分开。
   来源：`https://github.com/openjdk/jdk17u/blob/dfacda488bfbe2e11e8d607a6d08527710286982/src/java.base/share/classes/java/util/ImmutableCollections.java`
2. LLVM Passes 官方文档，读取 `licm` 及 `simple-loop-unswitch` 两节。
   支持循环不变量提升及控制代码膨胀的设计依据，不是 Java17 编译行为证据。
   来源：`https://llvm.org/docs/Passes.html#licm-loop-invariant-code-motion`
   和 `https://llvm.org/docs/Passes.html#simple-loop-unswitch-unswitch-loops`。
3. Google Protocol Buffers 官方源码，
   本轮 main 对应 commit `1d8382d5bdbd06af4e60e4d4e4d53241bfa5dbe8`。
   `UnsafeByteOperations.java:18–39` 的不可变底层缓冲要求、
   `:44–51` 的直接持有入口。仅转述其规则，没有复制大段原文。
   来源：`https://github.com/protocolbuffers/protobuf/blob/1d8382d5bdbd06af4e60e4d4e4d53241bfa5dbe8/java/core/src/main/java/com/google/protobuf/UnsafeByteOperations.java`
