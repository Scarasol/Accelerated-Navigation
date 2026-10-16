# 当前生产代码的 IDEA、spark 与日志联合热点分析

日期：2026-10-15，北京时间。范围：用户已有生产运行的离线诊断；没有修改生产源码，没有启动 Minecraft 或新性能实验。

**当前优先优化的是区块事件发布与失效维护、恢复扫描的数据访问，以及现有 Base/Boundary 构建中的重复读取和分配。** 这些方向都有本轮采样和源码机制支持。宏观搜索循环和真实原版详细搜索的累计样本相对较少；本轮不足以承诺任何优化幅度，也不构成稳定性能验收。

## 输入与统计口径

| 输入 | 身份与覆盖 |
| --- | --- |
| IDEA 原始 JFR | `C:\Users\Administrator\IdeaSnapshots\runClient_2026_10_15_224538.jfr`，PID 45716；执行样本时间 22:45:39.506–22:49:19.378，约 220 秒 |
| spark | `logs/GvfnBA1rZY.sparkprofile`，22:47:39.699–22:48:49.163，共 69.464 秒；仅 Server thread，Java engine、4ms 配置间隔 |
| 日志 | `run/logs/latest.log`、`run/logs/debug.log`；与上述 JFR、spark 启动参数、时间及开发工程路径一致 |
| 源码 | 本轮分析开始记录 `src/main` 61 个文件的 SHA256；分析收尾全部未变 |

在线原始 spark 与本地文件逐字节相同，SHA256 为 `F186BA0F4F4ED2F7D1F37AE0937ABD2DEEAC01BAED84F7C6E34FD19A6806EC37`。

IDEA 使用 async-profiler 的 `event=wall,interval=10ms,cstack=no,jfrsync=profile`。其 37,809 个执行样本全部记录为 RUNNABLE；另有 97,957 条批量等待事件和 33,074 条分配采样，分别统计。它们不是精确 CPU 时间或对象计数。JFR 两块时间重叠，`jfr summary` 的 440 秒不能当作真实连续运行长度。

spark 全部 17,922 个栈节点可达，无重复引用和负残差；各树叶分类权重之和为 69,396ms，与线程根权重一致。父子 inclusive 权重重叠，不相加。`waitUntilNextTick` 中仍会执行任务，只有 park/sleep/wait 叶栈归入明确等待。

## 主线程热点

下表分母为 spark 中 **11,268ms 带模组栈的累计采样权重**，约占整个 Server thread 权重的 16.24%。这是本次运行的采样归属，不是单次寻路时间。

| 互斥类别 | 累计权重 | 占模组栈权重 |
| --- | ---: | ---: |
| 基础事实恢复/扫描 | 4,076ms | 36.2% |
| Runtime 发布、状态维护等 | 3,892ms | 34.5% |
| 分段导航控制与输入当前性保护 | 1,160ms | 10.3% |
| 真实原版详细搜索调用链 | 208ms | 1.8% |
| 其他桥接、调度、存储控制及命令 | 1,932ms | 17.1% |

### 1. 区块事件发布与邻区失效维护

`TopologyWorkerRuntime.publishSection` inclusive 权重为 **3,236ms**，其中经卸载调用链 1,708ms、加载调用链 1,084ms。它们是上表 Runtime 类别的子集。

[TopologyService.java:470](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:470) 在加载时为每个 section 发布状态；[卸载入口:515](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:515) 也逐 section 发布，再补 chunk unload 通知。[publishSection:485](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java:485) 在同一个 `runtimeLock` 中查建条目、合并事件、更新 latest 和队列。

工作线程处理事件时，[markLatestDerivedStale:574](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java:574) 遍历 27 个邻位，并反复构造 `ClusterKey`、`SectionPos`、`SuperOriginKey`；[invalidateHaloDependents:2533](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java:2533) 再遍历另外 26 个邻位。这两条路径分别命中 89、37 个 IDEA 执行样本，分配采样估计累计约 595MB、451MB。它们主要反映键对象、查询及失效维护的重复工作。

IDEA 的主线程还记录到 `publishSection` 下 148 个批量等待 sample 权重。结合锁内事件处理，值得优先调查竞争；但当前没有 monitor 等待时长事件，**不能把 3,236ms 全部宣称为锁等待，也不能将最长批次约 160ms 等同于一次持锁时间**。正常事件批次逐事件进入锁，不是整批始终持锁。

优化候选：减少邻位查找的临时对象；对同一事件重复命中的父 origin/父视图合并维护；减少加载、卸载过程的重复发布元数据工作。必须保留 loadIdentity、版本、UNKNOWN/UNLOADED、即时失效与迟到结果拒绝，不能简单跳过有效失效事件。

### 2. 恢复扫描的读取与中间表示

[scanSection:731](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:731) 每次扫描 4096 格，经 `LevelChunk.getBlockState`、section 和 palette 逐格读取，再分类、打包几何和计算指纹。

4,076ms 扫描权重中：

| 扫描子路径 | inclusive 权重 | 占扫描权重 |
| --- | ---: | ---: |
| `PalettedContainer.get` 读取链 | 1,928ms | 47.3% |
| `collisionSample` | 928ms | 22.8% |
| `SectionGeometry.fromCells` | 452ms | 11.1% |
| `Block.isShapeFullBlock` | 276ms | 6.8% |

表中最后一项包含在 `collisionSample` 内，不能再相加。本轮仅 48ms 扫描权重经过 `finishTick`；扫描几乎都来自恢复，而不是局部修改后的全 section 重扫。IDEA 中 129 个扫描活动样本有 128 个位于第一次远距离传送之后、第二次传送之前。

[collisionSample:1366](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:1366) 已复用空、整格和流体的 `CollisionSample`，`SectionGeometry` 也已有常见 Cell 单例。因此旧报告中的“先补这些对象复用”已经不是当前待实现项。

优化候选：在恢复入口定位当前 `LevelChunkSection`，减少重复世界坐标/section 定位；核对 palette 顺序读取与安全快路径；减少 `Cell[4096]` 中间数组及后续重复遍历。动态碰撞仍须使用原 getter、原位置和实际形状；单纯改成 section API 仍要支付 palette 解码，不能把整条 1,928ms 都当作可消除成本。

### 3. 恢复任务的预算覆盖边界

[queueRecoveryNotice:655](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:655) 将 `runOneRecovery` 交给 [queueServer:1126](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java:1126)，后者提交普通 `TickTask`。这条入口没有 `NavigationScheduler.submitSoft` 或直接 `account()`，spark 实际也大量记录到它在 `waitUntilNextTick` 执行任务的路径中运行。

因此，**调度器的固定 20ms 额度及 `navigationNanos` 不能代表所有导航辅助任务的完整主线程成本**。这不证明它独自造成了本轮长 tick，也不自动要求改变预算策略；后续优化对照应同时观察完整主线程及恢复队列，避免只在局部指标中得到改善。

## 后台工作线程热点

两条拓扑 worker 共 943 个带模组栈的 IDEA 活动样本：

| 互斥类别 | 样本数 | 占两 worker 模组样本 |
| --- | ---: | ---: |
| Runtime 状态、事件、引用和调度维护 | 465 | 49.3% |
| Base 构图 | 213 | 22.6% |
| 独立边界连接构建 | 92 | 9.8% |
| 宏观搜索调用链，包含图当前性检查 | 88 | 9.3% |
| Super 构图，包含内部边界工作 | 14 | 1.5% |
| 其他 | 71 | 7.5% |

**Base 构图的具体热点**是 [deriveLegalAnchors:334](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java:334)，命中 140/213 个 Base 样本；其中可见重复 `flags`、`sourceFacts`、`floorDiv/floorMod`、halo 二分查找。外层已经取得 flags，[volumeOpen:975](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java:975) 又读取一次；完整碰撞行生成也会再次调用。可以优先研究循环内复用已解析的 facts/局部索引及合法位行，保持 haloUsed 和几何语义一致。

**边界碰撞的具体热点**是 [BoundaryGeometry.open:55](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/BoundaryGeometry.java:55)：73 个活动样本，分配采样估计累计约 731MB。现有实现构造 `Rect` 列表、两个 `TreeSet<Double>`、stream，以及局部 BFS 数组。可以复用构建 scratch、用原生数组排序去重 cut、减少装箱和短命对象；需保持严格不等式、木门能力、UNKNOWN、跳跃/下落碰撞结果逐项等价。该调用既可来自独立边界，也可位于其他父调用内，不能将 73 与上表各项直接相加。

**原生输入保护及桥接**是下一层候选：[NativeQueryProof.current:29](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/NativeQueryProof.java:29) 的 spark 权重 388ms，多个局部执行/观察入口重复验证区块；[AutomaticNavigationBridge.delegate:40](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java:40) 在 scope 清空时移除 ThreadLocal，后续调用会再次创建上下文。先核对可复用的访问和上下文；不能仅按 tick 缓存 proof，因为同 tick 内也可能发生输入变化。

## 现有事实存储

事实 IO 线程有 287 个模组活动样本，其中读取 150、写入 132。[decodeChunk:530](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyStore.java:530) 命中 141 个活动样本，分配估计累计约 1.12GB；压缩 native 叶栈 66 个活动样本。读取解压、几何解码和指纹均有成本。

[DecodedChunk.resolve:902](/D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/TopologyStore.java:902) 需要恢复缓存块的全部弱引用事实；任一失效便丢弃这次缓存命中资格。这是重复整块解码的机制候选，本轮没有实际重解码次数，不能直接宣称已证明放大倍数。

日志整次服务器生命周期记录 66,943 次 section 读取请求，56,333 FOUND、10 MISSING、10,600 readFailures。readFailures 包括格式损坏/不支持和 IO_FAILURE，且 section 请求不等于物理读盘次数，不能全部归为磁盘故障。存储优化在此仅指已运行的基础事实存储，不涉及恢复暂停中的派生拓扑持久化方案。

## 查询、日志与环境

本轮手动命令只有一次：`(-23,100,-198) → (-31,107,-122)`，不能开门，22:47:45.350 记录 SUCCESS，提交到终态 1,193ms。该值包含队列、事实/图准备、查询与结果发布，不是 A* 计算了 1,193ms。

近似请求窗口内 IDEA 模组活动样本为：Runtime 19、Super 14、Base 13、事实读取 8、边界 6、其他 6、搜索 4、分段控制 1。窗口两侧各增加 100/250ms 后，上述模组计数不变。存在其他自动导航，不能把窗口内全部工作排他归属给该手动请求，也不能按样本比例拆出其各阶段精确毫秒。

随后 22:47:53.575、22:48:14.187、22:48:31.851 分别传送到 x=1000、2000、0。整次运行 debug 日志记录 190 条宏观候选、307 条原生分段结果和 14 条输入失效；自然生物的自动导航也在运行。最终 191 次宏观成功、20 次失败不是 191 个实体自然到达；182 次物理搜索与逻辑请求的分母也不同。

spark 最近一分钟 tick 中位 15.69ms、均值 20.94ms、p95 50.60ms、最大 207.76ms；五分钟滚动值 p95 52.93ms、最大 451.13ms。五分钟值不局限于本轮 69 秒采样。日志 22:47:21 的 2,986ms 落后发生在手动 spark 窗口之前，不能用该窗口唯一归因。

本轮 JVM 使用 IDEA 运行代理和两种采样，但启动参数没有 JDWP，spark 树中没有旧报告的 CaptureStorage 热点，不能照搬旧的调试器归因。采样末系统物理内存已用约 96.4%；Windows swap 指标偏高但不证明实际换页。JFR 缺少 GC 暂停事件，spark 173 次年轻代 GC 是启动以来汇总，不能解释某个具体长 tick。分配估计中模组累计约 5.94GiB，是周转压力，不是同时存活内存。

## 建议实施顺序

1. **事件/失效维护**：减少邻区键对象与重复父视图处理，核对发布临界区。验证加载/卸载、失代、同版本事实恢复、取消及关闭契约，并观测主线程 publish、worker 状态维护和分配。
2. **恢复扫描读取**：减少逐格定位和几何中间数组/重复遍历，保留动态形状语义。用完整 facts/geometry 指纹和逐格结果对照，再观察传送后的完整 tick 与恢复耗时。
3. **Base 的重复 facts 访问**：先优化已有构图内核的数据访问，不更换搜索策略。重点比较合法锚点、局部边、能力掩码、haloUsed 和版本信息。
4. **Boundary 的装箱与 scratch**：原生数组表示 cut/障碍与复用局部搜索数组；保留几何正反例及边界连接逐字段对照。
5. **桥接和事实解码**：作为后续独立小项；先量化重复 proof/上下文创建与解码次数，再决定缓存变更范围。

上述顺序按本轮覆盖到的成本和源码机制排列，不是收益保证。单次用户运行没有受控优化前后基线；任何加速结论须由相同输入和正确性约束下的新对照支持。

## 可复算产物

离线产物在 `.tmp/production-hotspots-20261015/`：

- `input-identities.json`、`source-identities.json`、`online-identity.json`：四份输入、生产文件及线上/本地身份。
- `metadata.json`、`observations.json.gz`、`jfr-extra.json`：原始 JFR 的结构化读取。
- `spark-decoded.json`、`spark-summary.json`：spark schema 解码和树权重核对，未知 protobuf 字段为零。
- `jfr-summary.json`、`log-summary.json`、`details.json`：线程、类别、调用链、分配、请求及传送窗口统计。
- `analyze.py`、`details.py` 及复用读者：可重算脚本；全部 69 项原件/副本/生产文件身份检查通过。
