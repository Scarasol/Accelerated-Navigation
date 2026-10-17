# RawRows 接入后完整 tick 尾部的同步补采与机制定位

日期：2026-10-16。

本报告补充此前历史追溯报告。任务是使用现有工具主动补采，调查旧正式复杂场景完整 tick p99 从 **5.105850 增至 6.714900ms，增加 1.609050ms** 的原因，而不是以缺少历史记录结束调查。

**已经抓到共享锁等待、首次类加载和 GC 的同次执行证据，测得实体跟踪/移动开销，并确认不同 p99 点的任务分布及尾部排序会影响比较。现有工具能够定位这些执行成本；任务分布混淆与排序敏感性分别报告，不当作额外算法机制。本轮不能把它们按比例回填为旧四对运行的唯一原因，也没有证据把整个增量算作 RawRows 构图算法开销。旧正式门限 6.616435ms、FAIL 及未放行状态保持。**

## 1. 本轮实际补做了什么

完成 **24 台有效独立 JVM、384 次自然请求、1920 次逻辑详细搜索**，其中 14 台同步记录 JFR/WPR。另有 1 台在场景 ready 前因外部探针的 VerifyError 失败，作为基础设施失败完整保留，不计性能样本。修正探针异常处理器后重新冻结实验顺序，不按性能结果选删或补跑。

24 台均直接使用旧封存生产包与原场景包，没有重建生产：

| 对象 | SHA256 |
| --- | --- |
| 基线 B | `89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa` |
| RawRows 候选 C | `90eaacd3b8a701c076b64ae52c0a67275f4cad7aa23c89c579640fe44a776101` |
| 原场景 | `9d1c2cbe8347add10a0c264cd369f8f4fa8ff971db467901fa0249cd6cf78651` |

同一主机、同一 Java 可执行文件、同一配置、固定 seed，每台新 JVM/新世界。以**实际场景 ready** 为基准，在另一 tick 的 ready+29s 检查 16 个目标 geometry 的 Base/Super 均为 0，ready+30s 提交。实际提交延迟为 30.0079565–30.0981809s。每台均 16 个自然成功、80 次详细搜索、whole fallback 为 0，退出、清理及采样式进程重叠检查通过。采样式检查不能证明采样间隙内绝无外来活动。

所有新增探针、实验源码和数据均在 `.tmp`；没有把原型放进 `src/test`。工作区已有的其他生产、测试及文档修改保留，本轮未修改生产算法、提交、推送或发布。

## 2. 计时与工具的对应关系

验收计时继续使用原场景探针的**内层完整 tick**。窗口为 `endNanos >= submittedNanos && startNanos <= lastTerminalNanos`，每台独立按升序 `floor((N-1)*0.99)` 取 p99，本轮各台均为第 8 大。保留首次请求、GC、锁等待及所有慢 tick，不扣除暂停、不合并不同 JVM 或探针配置。

| 工具 | 本轮解决的问题 | 限制 |
| --- | --- | --- |
| JFR：采样、类加载、Java monitor、GC、I/O、编译等 | 找到方法栈、具体类、锁等待及暂停区间 | 编译区间重叠、反优化时间点不能直接量化其对主线程的代价 |
| WPR/ETW + WPA 独立导出 | 把 tick 分为 running、ready、blocked，并与 JFR 对照 | running 是操作系统线程运行墙钟，不等于纯算法 CPU；本轮 CSwitch 未录 native 栈 |
| 外部 ASM 阶段探针 | 采样没有命中时，直接记录游戏、导航、原生详细寻路等阶段的墙钟和次数 | 改变内存中字节码及 JIT；inclusive 存在嵌套，不能相加 |
| 薄方法探针 | 去掉 JFR/WPR/采样/trace mod，仍获得阶段分布 | 主线程预分配 primitive 数组、tick 中无事件对象/文件 I/O，但仍存在观察者影响 |
| HotSpot GC/safepoint 日志 | 只加载原两 mod 时，仍能判断 GC 是否真正落进完整 tick | safepoint 的 Total 反推边界为近似；不能提供类加载或 monitor 所有者 |

JFR 与原 `System.nanoTime()` 的关联使用目标 JVM 内直接夹界及内外 tick 顺序交集，不使用 UTC。14 台有效同步录制的 bridge 宽度均为 200ns，QPF 为 10MHz、nanoTime 步长为 100ns。原始 ETL 的目标线程切换逐条与独立 WPA 导出对账；JFR/ETL 记录损失均为 0，tick 状态分区闭合。Java monitor 事件和 ETW 状态边界采用不同记录语义，不应要求二者时长逐位相同。

另在同一 Java 可执行文件上实测：GC 日志 `timenanos` 的开始、结束位于 `System.nanoTime()` 包围显式 GC 的夹界内。由此在原两 mod 对照中直接关联 GC 与原 tick，无需额外 trace mod。不能用“GC 在请求窗口内”替代“GC 与某个 tick 相交”。

## 3. 已定位的共享锁链

40 阶段组候选第 2 台的 **tick603 完整耗时 10.0895ms**：

| 同次执行证据 | 时长或内容 |
| --- | --- |
| ETW blocked | 9.2472ms |
| JFR 主线程 monitor 进入等待 | `TopologyWorkerRuntime.endServerTick`，9.2667ms |
| 方法对应的锁 | 冻结源码中该方法唯一的锁为 `runtimeLock` |
| previousOwner | 线程 93，`accelerated-navigation-topology-1` |
| previousOwner 同一等待区间内 ClassLoad 并集 | 7.2841ms |
| 类加载栈 | `prepareSearchGraph → SuperTopologyGraph` 构造，并经 `publishSuperCluster → runtimeTransition/runRuntimeTransition` |

冻结源码的 `runtimeTransition` 在 `synchronized(runtimeLock)` 内执行 continuation。这里的异步计算并不保证主线程永不等待：工作线程在共享临界区执行图准备，主线程 tick 收尾也要进入这把锁。

随后追加的四台 2×2 对照，在 `prepareSearchGraph` 入口对实际对象的 `runtimeLock` 执行 `Thread.holdsLock`，四台各一次、均为 true。**这是入口持锁见证，不是全程 monitor 获取/释放测量；previousOwner 字段也不能单独证明其连续持锁的完整时长。** 类加载与等待是重叠维度，不相加。

为了检查锁现象是否只存在于 40 阶段 ASM，另补两台**没有 javaagent**、ready 后才启动 JFR 的运行：

| 版本 | p99 / tick | `endServerTick` monitor 等待 | ETW blocked |
| --- | --- | ---: | ---: |
| B | 18.5772ms / 609 | 17.7812ms | 17.6481ms |
| C | 16.9219ms / 604 | 15.3679ms | 15.4482ms |

这直接证明锁等待不限于 40 阶段 ASM 组，且两版都可能出现。它们仍带 common trace mod、JFR 和 WPR；实际 JFR attach 在 ready+22.2846205s/+21.6272941s，B attach 自身约 1.597s，改变了窗口前的运行状态。因此本组绝对成绩受观测配置影响，不能当作原两 mod 的净成绩，也不能说已完全排除观察者诱导。

## 4. 首次类加载的定向干预，以及没有成立的结论

同一探针/helper 的四台 2×2 对照，只把五个指定类的 `Class.forName(name, false, loader)` 移到原 telemetry arm 前。不初始化类、不构图，29s 的目标图冷态检查仍全部通过。

| 版本与开关 | graph 方法墙钟 ms | 内部全部 ClassLoad 并集 ms | 五指定名并集 ms | 完整 tick p99 ms |
| --- | ---: | ---: | ---: | ---: |
| B off | 10.1065 | 8.6872 | 8.4069 | 4.0731 |
| C off | 6.9228 | 6.0671 | 5.8952 | 4.1059 |
| C on | 1.6383 | 0.3209 | 0 | 4.8840 |
| B on | 1.3924 | 0.2037 | 0 | 6.5027 |

五类是 `SuperTopologyGraph`、`MacroSearch$ExactEndpoint`、`SuperTopologyGraph$CapturedSuper`、`SuperBoundaryCacheKey`、`SuperClusterTopology$CrossingIndex`。

**明确成立：首次图准备阶段承载了这些类的加载，提前加载可将指定加载移出该阶段。明确不成立：整体成本消失、主线程锁等待随之下降或完整 p99 改善。** 本四台没有捕获主线程等待与 graph 方法跨度相交，不能拿它证明等待改善。预加载本身已实测，C 为 8.6214ms、B 为 10.3541ms，成本移到了请求窗口前。每格只有一台，也不能把方法时长差当稳定因果效应量。

B on 的 p99 tick866 为 6.5027ms，GC 3.5970ms、ETW blocked 3.6950ms，调度 drain 为 5.8151ms，其中四次详细寻路仅 1.3290ms。另一个 tick693 为 20.3849ms、blocked 16.9087ms，没有已记录 GC/Java 等待，仍存在**未命名的 native 阻塞**。本轮没有为它编造原因，也没有宣称穷尽所有尾部。

## 5. 其他模块如何进入完整 tick

40 阶段组各台自己的 p99，已经分别落在不同工作分布：

| 运行 | p99 ms | 游戏 level ms | 调度 drain ms | 详细搜索次数 / ms |
| --- | ---: | ---: | ---: | --- |
| B1 | 4.3634 | 1.6179 | 2.6155 | 4 / 1.7685 |
| C1 | 6.8306 | 4.5905 | 1.6000 | 1 / 1.1914 |
| C2 | 6.0980 | 4.3953 | 1.1208 | 1 / 0.6843 |
| B2 | 4.5317 | 1.3607 | 3.0592 | 4 / 2.0718 |

这些是墙钟、包含嵌套：详细搜索在 drain 内，不能把各列相加。C1/C2 的实体 tick 分别为 3.3839/3.3978ms，其中移动为 1.0634/0.9869ms；这些并非都属于 RawRows 构图。主线程没有观测到 Base 构图调用。

四台 tick610 均记录到实体跟踪 `sendChanges` 内 `PosRot` 首次类加载，时长为 2.7163–3.9770ms。这是同次调用路径与 JFR 事件支持的具体成本。

为减少记录开销，另跑四台薄探针：原两个 mod、无 trace mod/JFR/WPR/采样，只有外部方法边界。按主线程预分配数组记录 inclusive/exclusive/count/max，关服时输出 CSV。各 tick 唯一 root 包围、exclusive 记账闭合、异常和容量错误为 0；每台 public/private 原生入口及 `findSegment` 都为 80，嵌套入口不重复计为逻辑搜索。

第一对两版的 p99 **都落在 tick610**：

| 阶段 | B1 ms | C1 ms |
| --- | ---: | ---: |
| 原完整 tick | 7.2162 | 4.4180 |
| 游戏 level | 6.9660 | 4.0626 |
| `sendChanges` | 3.2318 | 0.7766 |
| 实体 tick | 2.5078 | 2.0165 |
| 其中实体移动 | 0.6652 | 0.4680 |

两版完整 tick 差 2.7982ms，而 `sendChanges` 差 2.4552ms，差异主要落在实体跟踪路径，方向与旧均值相反。**薄探针没有录类加载，不能把这台的跟踪差直接冒认为已测得的 PosRot 加载。** 前述 JFR 是另一组独立机制证据。

薄探针第二对为 B2 3.7962ms/tick609、C2 4.5861ms/tick697。C2 的该 tick 有三次详细寻路 1.7135ms，`findSegment` 为 2.1372ms、drain 为 2.7395ms；B2 的 p99 tick 没有详细搜索。不同 p99 点不能当成同任务的直接开销对比。

全请求窗口中，相同 80 次逻辑调用的墙钟也保留，避免只挑一条 p99：

| 运行 | public 原生详细调用总 ms | `findSegment` 总 ms | `findSegment` 中原生调用之外 ms |
| --- | ---: | ---: | ---: |
| B1 | 84.8556 | 97.6517 | 12.7961 |
| C1 | 72.3388 | 82.7171 | 10.3783 |
| C2 | 70.6719 | 81.0984 | 10.4265 |
| B2 | 65.0239 | 73.5049 | 8.4810 |

这些总量包含首次加载、等待和观察者影响，不是纯算法 CPU，也不能用均值判定版本等效。窗口及结果推进稍有差异，但四台 `windowCurrent` 均为 29352 次、`nativeProofCurrent` 均为 28952 次，没有“候选多做了一批检查”的计数证据。四台主线程 `baseBuild`/`anchors` 调用均为 0。

原场景观察器本身也进入完整 tick：四台最大单次 `scenarioObserve` 分别为 16.7067、8.8398、9.8187、9.9904ms。这些原本就在正式完整 tick 口径里，均保留，不借扣除场景开销制造通过。

## 6. GC 与排序为什么影响 p99

40 阶段 C1 的 tick1141 为 7.2543ms，其中 GC 暂停 5.3959ms，ETW blocked 5.4862ms，暂停落在 `Entity.move` 方法跨度内。它排第 7 大，使第 8 大仍落在早期 tick609 的 6.8306ms；第 9 大是 5.3369ms。**GC 可以不发生在 p99 tick 自身，却通过上方秩位影响 p99。** 这不表示实体移动算法本身执行了 5.3959ms。

C2 的锁等待 tick603 排第 5 大；该台第 8 大为 6.0980ms、第 9 大为 4.3074ms。这里只解释排序关系，不删除该 tick、不减去等待重算成绩，也不声称实际消除等待后一定得到第 9 大的数值。

最后四台仅原两个 mod，均没有 trace mod/JFR/WPR/agent，只共同增加低容量 GC/safepoint 日志：

| 运行 | p95 ms | p99 ms / tick | 请求窗口 GC | 与完整 tick 相交 GC |
| --- | ---: | --- | ---: | ---: |
| B1 | 1.6060 | 4.0901 / 698 | 0 | 0 |
| C1 | 1.6707 | 4.8502 / 698 | 0 | 0 |
| C2 | 1.8014 | 4.5436 / 697 | 1 | 0 |
| B2 | 1.9747 | 4.2935 / 605 | 0 | 0 |

C2 的 5.736ms GC 发生在 tick 间隙，不能算作某个完整 tick 的耗时。C1 早期 tick609 有约 0.9682ms 的 `ICBufferFull` safepoint，但它不是 p99 tick698。四台未见 tick 相交 GC，仍有候选较高的两对结果，因此**GC 不能作为全部版本差的解释**。

该组两对差为 +0.7601/+0.2501ms，均值 B4.1918/C4.6969ms、差 +0.5051ms。它没有阶段记录，不能拿另一配置的薄探针分解这个 +0.5051ms；也不能用更低新成绩替换旧 FAIL。日志及原场景计时仍是观察者，样本数也不足以声明等效。

## 7. 回到旧正式 +1.609050ms

原八台 tick 与身份再次只读复核，原数据未变：

| 配对 | B p99 ms | C p99 ms | 差 ms | 对总均值差的贡献 ms |
| --- | ---: | ---: | ---: | ---: |
| 1 | 4.8973 | 4.5078 | -0.3895 | -0.097375 |
| 2 | 6.7085 | 8.4194 | +1.7109 | +0.427725 |
| 3 | 3.9189 | 7.6940 | +3.7751 | +0.943775 |
| 4 | 4.8987 | 6.2384 | +1.3397 | +0.334925 |
| 均值 | 5.105850 | 6.714900 | +1.609050 | +1.609050 |

第 2/3 对合计贡献 1.3715ms。旧 C2 的第 8 大是 tick603 8.4194ms、第 9 大 6.5356ms；旧 C3 有 tick918 43.1083ms 位于更高秩，第 8 大落在 tick610 7.6940ms、第 9 大为 tick698 4.6566ms。旧 B3/C3 的首次路径可见分布相同：607/608/609 分别 1/12/3 个，不能只用这一对的首次完成批次不同解释回退。

两处邻秩间隔按四台均值权重计算：`[(8.4194-6.5356)+(7.6940-4.6566)]/4 = 1.2303ms`，约为旧差的 76.4613%。**这是顺序统计量的敏感性，不是“76% 已证明是波动”，不是因果比例，也不是删掉两条记录后的新分数。**

本轮比历史盘点推进的地方，是实际补出了共享锁、首触类加载及 GC 的同次机制证据，并分别量化游戏阶段、任务分布与秩位敏感性。完整 tick 差不能直接用作构图函数变慢的证据，同样施加探针也仍可能有版本与探针交互。它们尚不能证明旧 tick603 就是本轮锁等待、旧 tick918 就是 GC，或为旧 +1.609050ms 分配唯一比例。

因此不将“正常波动”作为已证实结论，不将此轮称为 RawRows 无回退/等效、已经修复或生产放行。接下来若考虑改动，需要围绕已测得的共享临界区及具体阶段另立干预验证；单纯继续追加没有阶段信息的整组成绩，不能完成因果归属。

## 8. 全部新运行与反向结果

下表只汇总成绩以核对完整性，**各行探针配置不同，禁止混池**。

| 配置与配对 | B p99 ms | C p99 ms | C-B ms |
| --- | ---: | ---: | ---: |
| 无 agent，同步 JFR/WPR，1 | 5.0390 | 4.3793 | -0.6597 |
| 无 agent，同步 JFR/WPR，2 | 5.5264 | 6.4193 | +0.8929 |
| 最小观测，保留 trace mod | 5.1146 | 4.2166 | -0.8980 |
| 40 阶段，同步 JFR/WPR，1 | 4.3634 | 6.8306 | +2.4672 |
| 40 阶段，同步 JFR/WPR，2 | 4.5317 | 6.0980 | +1.5663 |
| 41 阶段，不预载 | 4.0731 | 4.1059 | +0.0328 |
| 41 阶段，预载五类 | 6.5027 | 4.8840 | -1.6187 |
| 无 agent，延迟 JFR | 18.5772 | 16.9219 | -1.6553 |
| 原两 mod，只加运行时日志，1 | 4.0901 | 4.8502 | +0.7601 |
| 原两 mod，只加运行时日志，2 | 4.2935 | 4.5436 | +0.2501 |
| 原两 mod，薄方法探针，1 | 7.2162 | 4.4180 | -2.7982 |
| 原两 mod，薄方法探针，2 | 3.7962 | 4.5861 | +0.7899 |

离线解析修正及一次探针基础设施失败均保留原件和修订 provenance，没有为修正解码而丢弃有效服务器运行。磁盘压缩采用 NTFS 透明压缩，已压缩的 102 个证据文件逻辑字节 SHA256 前后相同，没有删除或迁移原记录。

## 9. 独立审核与证据

前两包 DeepSeek 分别审查 40 阶段结果及锁/类加载干预，均为 `ACCEPT_WITH_GATES`、本轮必须修改项无；本报告保留其入口持锁、单格对照、观察者、反向结果及历史归因限制。锁审核称“预载未计量”不符合原 `preload.json`：本报告使用其中明确的 nanoTime 起止夹界给出窗口前成本，未把它说成被消除。

最终综合包 **6398 UTF-8 字节**，通过 WSL 原生 `dsh --profile headless`，provider=`deepseek`、model=`deepseek-v4.1-flash`，本地期限 900s；实际 92.046s 正常返回 **`ACCEPT_WITH_GATES`，本轮必须修改项：无**，配置哈希前后相同。每包独立自足，不依赖无头会话上下文，不让审核模型读取额外文件。审核基于包内摘要，不构成独立读取原始文件或复现实验。

综合共识只接受有界诊断：观测配置内确实捕获上述现象，不能外推为净版本效应或旧增量的唯一归因；入口持锁不能冒称连续持锁测量；保留反向成绩、未命名阻塞及全部慢 tick；原正式 FAIL 不变。本报告按建议将执行机制、工作负载混淆、统计敏感性区分表述。综合包省略了邻秩加权的展开公式，本报告第 7 节补全 `/4` 算式及对应原始数值，不扩大其因果含义。

- [24 台身份、冷态、自然结果、完整计时与录制审计](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-late-20261016/audit-all-v1.json)
- [最终综合数据与输入哈希](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-late-20261016/synthesis-v1.json)
- [旧八台原秩位与均值贡献](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-late-20261016/old-rank-bridge.json)
- [同步无 agent 六台结果](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-causal-20261016/results-v1.json)
- [40 阶段四台及同次等待/GC](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-stage-v2-20261016/results-v1.json)
- [锁内准备源码及方法边界](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-production-20261016/baseline/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java:359)
- [GC 与 nanoTime 同钟实测](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-late-20261016/gc-clock-check/selfcheck.json)
- [阶段审核](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-lock-20261016/deepseek-answer-live-results-stage.txt)
- [锁与预载审核](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-lock-20261016/deepseek-answer-live-results-lock.txt)
- [最终综合审核](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-lock-20261016/deepseek-answer-live-results-final.txt)
- [最终综合审核参数、耗时及配置哈希](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-lock-20261016/deepseek-exit-live-results-final.json)
- [主要证据与最终报告 SHA256 封存](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-live-late-20261016/evidence-seal-v1.json)
