# Spark、火焰图与运行时状态开销分析

日期：2026-10-18。

性质：侧边会话的证据分析与研究建议。用户本轮仅要求生成报告；本次只新增本文档，不修改生产源码、工作计划或配置，不执行 Git 写操作，不运行新的性能实验，不向 DeepSeek 提交审核。

**用户提出的“让宏观计算也采用快照，允许使用旧派生图以减少失效维护”设想，状态为：需要再次确认。** 尚未确定具体语义、实施范围和验证方案，不作为已采纳方案、实施授权或主线程执行指令。详见第六节。

## 一、主要结论

1. 既有优化确有局部效果，其中步骤4a删除无人消费的最终窗口，受控对照证据最明确；不能据此声称全部路线、CPU 和整体性能都改善。
2. 步骤6只结清了中间窗口临时坐标减量的隔离研究，候选未接入生产，整体净收益未证明。当前 `GroundSegmentWindow.java` 的原始字节哈希仍与该轮 B 一致，不能将本轮游戏表现归功于步骤6候选。
3. Worker 最终算法组合已有生产接入记录；本轮 JFR 能看到 `BoundaryPortBits.candidates`、`mayLink` 参与运行。算法参与运行与实际省下多少总成本，是两个不同结论。
4. 两个拓扑 worker 的模组活动调用栈样本中，运行时状态类占约52.9%，Base 构建约20.2%，Boundary 约12.1%。这里是 wall 模式下的样本分类，不是 CPU 时间分账，也不是锁等待占比。
5. 当前最明确的状态热点线索是 section 事件应用、派生图失效传播、构建前后管理，以及快照引用和缓存账目维护。回调上下文和队首缓存的优化没有消除这些业务。
6. 已完成并交给移动执行的宏观走廊已经按快照使用，后续世界变化不会主动撤销它。用户的新设想主要涉及尚未完成的宏观计算和缓存失效策略。
7. 本轮 `workerStaleResults=6`、`automaticStaleRetries=3`，没有证据证明大量陈旧重搜是主要开销。更值得调查的是失效传播和引用维护本身；其可省比例尚未量化。

## 二、原始资料与结论边界

### 2.1 原始资料

| 资料 | 位置 | 本次用途 |
| --- | --- | --- |
| Spark 报告 | `D:/Workspace/AcceleratedNavigation-forge-1.20.1/logs/nTgd0rKzHj.sparkprofile` | 主线程调用栈与负载背景 |
| IDEA JFR / 火焰图数据 | `C:/Users/Administrator/IdeaSnapshots/runClient_2026_10_18_001317.jfr` | 拓扑 worker 调用栈及分配采样 |
| 游戏日志 | `D:/Workspace/AcceleratedNavigation-forge-1.20.1/run/logs/latest.log` | 查询返回、卡顿和关闭统计 |
| 详细日志 | `D:/Workspace/AcceleratedNavigation-forge-1.20.1/run/logs/debug.log` | 导航日志与运行背景 |
| 当前源码、既有报告及交接记录 | 当前工作区 | 核对职责、接入状态与历史实验结论 |

Spark 的游戏展示时间为2026-10-18 00:14:17.776至00:21:59.625，Java 采样间隔4ms，仅采集 `Server thread`；解析后的总权重为461,768ms、29,638个节点，遍历权重守恒，使用的 schema 未出现未知字段。

JFR 来自同一游戏进程 PID 23132，配置为 `event=wall, interval=10ms, cstack=no, jfrsync=profile`。事件跨度为547.811秒：UTC时间2026-10-17 16:13:17.717至16:22:25.528，对应 IDE / 游戏展示的2026-10-18 00:13:17.717至00:22:25.528。这里明确区分原始 UTC 与展示时钟，不使用当前 shell 时区反推历史时间。

JFR 有两个重叠 chunk，`jfr summary` 的累计1096秒不能当作实际观察时长。读取到76,978条 `jdk.ExecutionSample`、179,875条 `profiler.WallClockSleeping` 和115,357条 `jdk.ObjectAllocationSample`。没有可用于本次归因的 GC 暂停、MonitorEnter 或 Compilation 事件，不能据此确认锁等待、GC 或 JIT 是某段时延的主因。

### 2.2 身份与测量限制

- 这是用户的一次游戏运行观察，没有同场景、同测量壳的旧版对照。它能够定位当前剩余工作，不能单独得出优化前后的净收益。
- 当前工作区正在由主线程持续修改。报告生成时，步骤7的最新任务和修复报告已记录 Fv3；该状态不代表 Fv3 就是本轮早先 JFR 的运行版本。旧索引中仍有 Fv2 历史描述，不能覆盖任务正文的最新状态。
- 本轮采样栈证明端口位图代码实际运行，但没有重新完成整个采样进程的 Jar / 全部 class 身份认证。当前源码哈希只固定本次阅读版本，不能替代历史运行身份。
- 下述 wall 样本不等于 CPU 时间；分配采样权重不等于精确累计分配、存活内存或峰值占用。
- 模组 worker 活动样本只有858个，适合判断大类和调查方向，不适合给细小方法收益或稳定差异授予统计资格。
- 本次复用会话内已有解析结果，并重新核对原始文件哈希、worker 汇总、方法计数和源码；没有重跑游戏、构建或性能矩阵。

## 三、既有优化有没有实质效果

| 项目 | 已有证据支持的结论 | 不能据此声称的结论 |
| --- | --- | --- |
| 步骤4a：移除最终窗口 | 三对独立 JVM 的8格场景中，全 JVM 分配下降11.605%至12.495%，首 Path P95下降14.442%至27.874%；删除无人消费工作的机制明确 | CPU 稳定下降、复杂路线全面改善、正式整体性能通过 |
| 步骤4b：就绪接续 | 零边重复诊断中，首 Path 约51.3425ms降至4.4189ms，说明减少交付后的等待具备实际机制效果 | 单次诊断等于稳定总收益；8格 CPU 回归原因已确定 |
| 步骤4c：日志分级 | 既有报告显示详情消息文本明显减量；本轮详细日志中统计到19,614条导航日志，未见原昂贵详情消息 | 文本字节减少等于同比例 CPU 或全 JVM 分配减少 |
| 步骤5a / 5b | 5a搁置；5b尾部候选局部减搜成立，但资源结果混合、候选封存 | 它们已作为生产算法贡献本轮收益 |
| 步骤6：中间窗口分配 | 隔离候选局部减量成立，唯一资源对2048/2048到达；8格 CPU、首 Path P95和 GC 等保护项不利，整体净益未闭合 | 候选已接入生产；自然观察窗中全部分配差额均来自窗口 |
| Worker 最终组合 | 已接入稳定小数组 Top-K、GROUND 端口位图、碰撞行索引与谓词合并、第二版回调上下文和12组队首缓存；本轮见端口位图调用栈 | 旧隔离实验或 JMH 百分比可以直接转作本轮整场收益 |
| 步骤7 | 初始 accuracy=1 扩展候选因额外分段停止；当前共享切段修复继续演进，最新任务为 Fv3 | 本轮早先 JFR 已验证 Fv3，或者步骤7整体性能已结清 |

步骤6来源的直接核对：

- 当前 `GroundSegmentWindow.java` SHA256：`a6deef2dc3a18408128087024ba5f95406bdfd3cf0ae7f18797e73dafafc1bfc`。
- `.tmp/navigation-overhead-20261017/source-identity-v1.json` 记录的 B 为同一哈希，C 为 `79f69ac055789c0a9561da042a79be98783d7d6e1fbb377f642b8d9e751cb635`。
- Worker 接入报告也明确没有接入窗口研究候选。这与当前文件核对一致。

参考：[4a报告](2026-10-17-final-window-removal-validation-zh.md)、[4b/4c报告](2026-10-17-ready-dispatch-and-logging-validation-zh.md)、[拆分对照](2026-10-17-ready-dispatch-ablation-zh.md)、[步骤6报告](2026-10-17-window-allocation-validation-zh.md)、[最终组合](2026-10-17-final-worker-algorithm-combination-zh.md)、[生产接入](2026-10-17-worker-algorithm-production-integration-zh.md)、[步骤7最新任务](../agent-process/tasks/shared-stage-fix-20261018-zh.md)。

## 四、本轮 worker 成本结构

### 4.1 互斥调用栈分类

`accelerated-navigation-topology-1` 有478个活动样本，其中模组444个；`topology-2` 有443个，其中模组414个。合计921个，模组858个，其他63个。

对模组样本，分类器先识别事实扫描、原生详细搜索、存储、窗口、Boundary、Base / Super 构建、共享行查询和 MacroSearch；余下带 `TopologyWorkerRuntime` 的归入 runtime，独立执行器和其他模组栈另列。同一个样本只进一个桶。

| 分类 | 样本 | 占858个模组样本的比例 |
| --- | ---: | ---: |
| 运行时状态及下游组织工作 | 454 | 52.91% |
| Base 构建 | 173 | 20.16% |
| Boundary | 104 | 12.12% |
| MacroSearch | 54 | 6.29% |
| 独立执行器工作 | 43 | 5.01% |
| 其他模组工作 | 12 | 1.40% |
| 窗口 | 8 | 0.93% |
| 共享行查询 | 7 | 0.82% |
| Super 构建 | 2 | 0.23% |
| 存储 | 1 | 0.12% |

runtime 的52.91%不等于 `runtimeTransition` 方法自身耗时、纯状态赋值或锁等待。宏观依赖维护的一部分也落在这里，不能把 MacroSearch 的6.29%理解成宏观依赖的全部成本。

### 4.2 分配采样

两个 worker 的分配采样权重合计4,120,919,928字节。其中 runtime 为2,931,863,864字节，约71.15%；窗口为155,939,760字节，约148.7MiB、3.78%。

这说明窗口之外仍有较大的组织和对象分配来源，但不能将71.15%全部归因于某个集合、某次 pin/unpin 或失效传播。采样权重也不是这些对象的最终留存量。

## 五、“运行时状态处理”具体包含什么

以下行号对应本次读取的 `TopologyWorkerRuntime.java`。各方法样本为包含子调用的计数，存在嵌套，不能相加。

| 方法或阶段 | 样本 | 具体内容 |
| --- | ---: | --- |
| `processSectionEvents`，671行 | 275 | 移交批次、应用事件、处理请求及事实反馈、tick清理、恢复搜索和再次调度 |
| `applySectionEvent`，859行 | 195 | 检查身份与版本、合并变更、替换事实、决定派生结果失效和唤醒等待者 |
| `markLatestDerivedStale`，583行 | 136 | 检查中心及周围27个位置，对受影响视图和 Super 父级推进有效性标记 |
| `beginBuild`，2996行 | 48 | 核对需求、检查事实、固定中心及邻居输入、记录指纹和版本戳 |
| `changeFactReferences`，1280行 | 41 | 更新快照活动/空闲引用及内存账目 |
| `invalidateHaloDependents`，2542行 | 36 | 检查26个邻居候选，退役确实受影响的图并使相关结果失效 |
| `changeTopologyFactReferences`，3590行 | 33 | 遍历图持有的事实快照，逐份更新引用 |
| `removeIdleFact`，2397行 | 31 | 从空闲缓存及对应内存账目移除快照 |
| `MacroQuery.requestPendingSections`，6158行 | 30 | 捕获就绪依赖、请求缺失依赖、处理不可用结果和并发上限 |
| `holdFacts`，2299行 | 24 | 为构建保留事实快照并维护引用 |
| `requestClusterDependency`，1293行 | 24 | 查询缓存、合并构建需求、登记等待者并准备工作 |
| `publish`，3041行 | 23 | 校验构建结果、替换缓存、释放输入、完成等待者和执行淘汰 |

### 5.1 事件与派生图失效

section 事件不只代表玩家改了一个方块，还包含加载、恢复、卸载和事实状态变化。`applySectionEvent` 会过滤旧事件、合并增量，并区分同版本事实重新载入与真正使派生结果过期的变化。

需要失效时，流程可能包括：

```text
事实身份、内容或状态变化
  → 更新邻域视图及 Super 有效性
  → 退役旧缓存图和 Boundary
  → 迁移尚未完成需求的等待者
  → 准备新构建、唤醒事实等待者
  → 在新结果就绪后恢复搜索
```

遍历27个位置不意味着每次重建27张图。需要调查的是相邻事件是否重复检查相同邻域、相同 Super 父级，以及哪些维护可以批量合并。

### 5.2 构图前后生命周期

这里的“构图”具体指 `BaseClusterTopology.build` 从事实快照和实体几何生成 Base 派生拓扑。其外围为：

```text
runtimeTransition(beginBuild)
  → 锁外执行 Base 构图算法
  → runtimeTransition(publish)
```

构建前固定输入、记录版本；发布时检查 generation、指纹、halo 和版本戳。过期结果释放后重新准备；有效结果替换缓存、完成等待者。Base 算法本体已单独分类，不能把整个 `build` 都当作状态开销。

### 5.3 依赖交接和引用管理

宏观依赖需要登记请求、等待、捕获并固定缓存结果，再把结果接入当前搜索图。完成、取消、失败或替换时都要结清计数和所有权。

图的引用变化可能遍历多份事实快照；图从空闲转为活动、再转回空闲也会更新对应内存统计。因此这部分不只是一个计数器的加减。

### 5.4 共同入口和执行器

`runtimeTransition` 出现432个包含子调用的样本，是众多状态命令的共同入口。它负责执行锁内命令、检查预热准入，并把回调移交到锁外。火焰图中的宽度不能单独证明锁竞争。

第二版惰性回调上下文优化了入口的容器分配；固定12组队首缓存优化了任务选择。二者都没有消除 section 事件、依赖交接、引用维护和构建发布的业务。

本轮日志记录97,241个已完成 CONTROL 批次、43,486个 BUILD 任务、49,511个 QUICK_SEARCH 切片和10,881个 PREWARM 任务。它们不是唯一图数或完整寻路次数，不能直接互相换算。

## 六、用户快照设想：需要再次确认

### 6.1 原始设想与状态

用户提出：能否像详细寻路执行已有结果那样，让宏观层也快照化，减少旧 Base、Boundary、Super 的失效和等待者处理；考虑到寻路速度较快，世界变化带来的影响是否有限。

**状态：需要再次确认。**

这段描述尚未确定是只改变失效维护时机，还是允许尚未完成的宏观计算忽略世界版本变化。两者影响的语义和正确性范围不同。报告只记录问题和备选方向；实施前需要再次向用户确认具体范围，不根据本报告自动修改当前契约或启动候选。

### 6.2 当前已经做到的部分

现行契约规定：

- 已完成并交给移动执行的宏观结果保留自己的组件成员快照。
- 后续世界更新不能主动撤销该宏观结果，也不能用旧组件编号重新解释当前图。
- 每段真实详细寻路读取当前世界；真实搜索或执行失败后进入既有有限恢复。

源码见 `GroundSegmentWindow.java:15`、`:40`，以及 `SegmentedNavigationService.java:130`、`:749`。当前的 `GroundSegmentWindow.current()` 检查窗口是否关闭，不检查后续世界版本。

因此，“宏观完成后不因改块全部重来”已经实现，不能把它再次登记成全新的预期收益。

### 6.3 尚未做到的部分

尚未完成的宏观计算仍受当前性约束：

- `runWorkerSlice` 在切片中检查已使用 section 的版本，过期则清理并以 `STALE_WORLD` 结束。
- 完成交付还会检查版本，可能安排有限重试。
- 缓存入口要求图与当前视图和事实版本匹配。
- 构建发布、Boundary 和 Super 聚合也有输入相容性检查。

允许这些计算继续读取旧版本，属于对现行契约“未完成宏观计算和新查询受世界版本约束”的调整，不是简单删除几个检查的等价优化。

### 6.4 两条可区分的研究方向

| 方向 | 可能改变什么 | 预期收益来源 | 当前状态 |
| --- | --- | --- | --- |
| R1：延迟失效维护、按需检查 | 新查询仍要求当前输入；事件先更新版本或脏标记，部分退役与重建推迟到使用时处理 | 减少事件时重复遍历、重复状态转换和未被消费的维护 | 分析建议；未实现、未证明收益 |
| S1：进行中的宏观查询持有旧版本 | 查询或构建在世界更新后仍可使用其固定的派生图版本完成 | 减少当前性扫描、被打断的计算和重试 | 用户设想；**需要再次确认** |

R1也必须保留查询、发布、取消、卸载和回收的正确性。只保留原始 revision、漏掉必要 halo 或视图版本，不能视为等价的延迟失效实现。

S1不应通过复制整个城市图来默认实现。需要先定义按查询保留哪些事实和派生图版本、后续新依赖怎样取得相容版本，以及有多少旧版本可以同时驻留。

### 6.5 必须弄清的正确性问题

1. **旧图误报可通。** 世界新建障碍后，当前详细搜索可以发现阻挡，但仍可能增加无效分段、恢复和失败，不能保证实际到达不受影响。
2. **旧图遗漏新通路。** 世界拆墙或打开连接后，旧宏观图可能不给候选；详细寻路没有机会发现新通路，不能仅靠“最终由详细寻路检查”兜底。
3. **跨版本连接。** Boundary 必须对应其源、目标的具体 Base 版本，Super 必须对应相容的子图。只保证各对象不可变，不等于组合起来语义一致。`attachBoundary` 已按图对象身份检查，不能直接撤掉。
4. **生命周期。** 世界卸载重载、请求取消替换、实体能力变化、目标变化及关闭，不能与普通方块版本变化混为一类。是否允许旧几何数据参与查询，与是否允许继续操作已失效请求，是不同问题。
5. **内存和回收。** 继续持有旧版本会延后回收，可能抵消分配或 CPU 收益；复制快照本身也可能引入成本。当前已有不可变对象及 pin 机制，新方案要说明具体替换哪些维护，而不是再加一份快照。

### 6.6 “寻路快，所以影响有限”尚未成立

短查询通常缩短与相关变化重叠的机会，但不能保证发生变化后的影响很小，也不能把局部计算时间等同于提交到交付的异步时长。

本轮同一路线日志记录冷返回1165ms、温热返回7ms和9ms。这只是该路线的宏观返回观察，可能包含缓存命中，不是实体到达时延，也不是所有查询的时长分布。

关闭统计为：

| 指标 | 记录值 |
| --- | ---: |
| `physicalSearchesStarted` | 2,377 |
| `workerStaleResults` | 6 |
| `automaticStaleRetries` | 3 |
| `staleRetryExhaustions` | 3 |
| `successfulStaleRetries` | 0 |
| `recoveredSections` | 24,324 |

这些计数不证明所有变化都只影响6次，也不能精确量化每次重试的成本。但它们不支持“当前大量时间消耗在陈旧重搜”的断言。事实恢复、缓存失效和版本维护仍可能有独立成本。

本报告的倾向是先调查 R1 所针对的即时失效和重复引用转换；S1保持待确认，不预先承诺可以省下52.9%的运行时样本份额。

## 七、与当前工作计划的关系

| 现有项目 | 对本问题的覆盖 | 尚未完成或未确定的部分 |
| --- | --- | --- |
| 步骤6 | 原范围包括外围分配、扫描和发布锁；本轮实际只做中间窗口坐标减量 | 不能据此宣布运行时状态优化完成，也不自动重开已封存候选 |
| 步骤7 | 共享入口、切段和查询采用可能间接减少重复工作 | 最新修复仍在续验；查询采用分支未改，不借本报告接管 |
| 步骤8 | 减少不必要的冷依赖及完整 Base / Boundary / Super 构建，可连带减少管理工作 | 新生产优化未开始，先量真实冷依赖和未消费工作 |
| 步骤9 | 快照去重可能减少结果物化与留存 | 不等于全面优化事实 pin/unpin 或版本维护 |
| Worker 探索交接后续 | 已明确继续定位锁内状态命令和重复依赖组织 | 尚无独立验证过的事件失效 / 引用转换专项方案 |

本报告不修改1至10的工作顺序，不把 R1 或 S1登记为已授权的新步骤。当前主线程任务和后续决策仍以最新任务记录及用户明确指示为准。

如果未来决定开展窄验证，首先需要取得：每批唯一受影响视图/父级数、重复访问量、失效后未再消费的对象数、引用状态转换次数，以及这些工作的 wall / CPU / 分配成本。再分别比较维护总量与查询延迟，避免只把工作从事件处理搬到下一次请求。

## 八、证据身份及源码索引

### 8.1 原始文件 SHA256

| 文件 | 字节数 | SHA256 |
| --- | ---: | --- |
| `logs/nTgd0rKzHj.sparkprofile` | 5,602,958 | `0a27dbcbc10436180fc3285c69ddf831817ced39284d24b70451aedadf23e88a` |
| `C:/Users/Administrator/IdeaSnapshots/runClient_2026_10_18_001317.jfr` | 18,954,172 | `4fb9716ae16b1a5d6df3ae431229e0f87dc46acdc435a6f279b8f7afe1649189` |
| `run/logs/latest.log` | 40,057 | `e7a8ed80de5a302d56bbf248bed7884afe62e3c0cfe93702f81dc2bd1f09d806` |
| `run/logs/debug.log` | 5,032,001 | `4d8db96fda780049752263ebb2b2bd616a7ad73658dbb5c02b34d1c58219c6e6` |

### 8.2 本次阅读源码 SHA256

下表路径均位于 `D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/`。这是阅读时的当前源码身份，不是采样时完整运行身份。

| 文件 | SHA256 |
| --- | --- |
| `topology/TopologyWorkerRuntime.java` | `f8c283c79d207b190eb1116c26cc994a0ffad0118f9844b49207b88f2eb7dac8` |
| `topology/GroundSegmentWindow.java` | `a6deef2dc3a18408128087024ba5f95406bdfd3cf0ae7f18797e73dafafc1bfc` |
| `navigation/SegmentedNavigationService.java` | `bdb861d7a465ca884c3866837253bd24888e424f2eab429f18eeb360df626bc7` |
| `topology/BaseClusterTopology.java` | `68325bda1c58a8fe27cdf7e4db5fef328cda3f535e361569ff9868cd1c6f34d3` |
| `topology/MacroSearch.java` | `22a9656b9ae017595494a04167f921b7a03ef8f95d0e1e4883350f8021019e55` |
| `topology/TopologyTaskExecutor.java` | `7f39eee2b505d31aa10a24589f5f632f8a2e09f345b8ebf10aa9250075856e69` |

源码定位：

- [运行时入口](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L364)、[失效标记](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L583)、[事件应用](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L859)。
- [邻居失效](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L2542)、[构建与发布](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L2976)、[图引用维护](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L3590)。
- [完成交付检查](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L5157)、[搜索切片](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L5640)、[Boundary版本配对](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L7577)。
- [已提交宏观结果契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)、[完整工作计划](../designs/2026-10-16-navigation-performance-roadmap-zh.md)、[算法探索后续方向](../agent-process/tasks/navigation-performance-handoff-20261017-zh.md#二十算法独立审核后的实现与验证)。

本文没有取得新的性能资格或算法审核结论。用户快照设想继续保持“需要再次确认”。
