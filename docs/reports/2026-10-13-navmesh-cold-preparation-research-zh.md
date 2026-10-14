# NavMesh 参考与冷图准备优化

日期：2026-10-13。状态：源码和一手资料调查；未实施生产修改、未运行新的冷启动性能测试。

现行规范仅为 [原版详细寻路接入契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)。本文的参考史只解释技术来源，不恢复旧方案或旧审查的规范地位。

## 参考关系的补充

NavMesh/Recast 已经是项目的参考之一：

- [10 月 6 日构图技术调查](2026-10-06-jump-construction-method-research-zh.md) 比较 Unity NavMesh Link、Recast4j JumpLinkBuilder 和边界连接。
- [10 月 7 日构图候选调查](2026-10-07-construction-optimization-next-options-zh.md) 核对 Recast4j 紧凑高度列和边界提取。

因此上一轮报告把 NavMesh 仅写成以后另做研究的候选，遗漏了既有参考背景。当前生产代码使用自有 section 组件、边界连接与宏观走廊；未发现 Recast/Detour 依赖或直接调用。参考这些机制，不等于已经采用多边形 NavMesh 作为详细搜索引擎。

本轮更适合继续借鉴其构建、保存、加载、分块更新策略，优化已有宏观准备链。详细可行性和实际节点仍由真实原版 PathFinder 决定。

## 三种等待必须分别测量

| 情况 | 实际成本 | 优化着力点 |
|---|---|---|
| 重启后的冷图 | JVM 初始化、事实恢复、内存派生图缺失 | 派生图持久化、针对性预热 |
| 未访问区域或新几何视图首次请求 | 基础事实获取、当前几何视图构建及邻域依赖 | 热点排序、按真实 GeometryKey 预热、有界分块构建 |
| 图已准备但请求集中到来 | 端点解析、宏观查询、详细搜索派发与发布排队 | 端点复用、现有共享缓存、动态预算独立试验 |

之前的 128/256/512 正式对照经过两个预热场景，没有清空缓存；不能把其秒级首条 Path 等待全部解释为冷图构建。两个预热场景本身也不构成严格的独立 JVM 冷启动对照。

现有原始加速数据第一预热场景末的累计 `readRequests=0`、`recoveredSections=1184`；说明该运行没有提供重启后磁盘读取命中的测量，不能据它估计派生图持久化的收益。恢复计数是运行累计值，不代表全部发生在该场景计时内。[快照证据](../../.tmp/navigation-cold-preparation-research-20261013/evidence.json) 记录原始 JSON 哈希和前三轮累计计数；[提取脚本](../../.tmp/navigation-cold-preparation-research-20261013/evidence.mjs) 不生成新的性能样本。

## 已核实的准备链

1. [TopologyService.loadChunk](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java#L409) 登记已加载区块，处理生成事实、空 section、磁盘读取或恢复，再交付 worker。
2. [TopologyStore](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyStore.java#L528) 当前保存 schema/事实算法版本、section 版本、PackedFacts 和 SectionGeometry；`SectionRecord` 只包含 version 与 facts，不是构建完成的 Base/Super 图。
3. [TopologyWorkerRuntime.build](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L2938) 获取不可变事实输入、调用 BaseClusterTopology.build，再检查输入当前性后发布。内存派生视图在该进程中保留和淘汰。
4. [预热](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L3283) 使用 DEFAULT_GEOMETRY，最多 8 个已接纳工作；存在前台工作时停止后台预热准入。section 列表从最高索引向下推进，不按玩家或实体位置排序。
5. [TopologyTaskExecutor](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyTaskExecutor.java#L14) 有两个 worker，但构建 permit 只有一个；Base 构建共用 BuildScratch。8 个预热准入不等于 8 个并行构建。
6. [ResolveFlight](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L4321) 以完整起终点对合并端点解析；解析后才查询按组件集合、GeometryKey、MovementKey 等组织的走廊缓存。已有拓扑构建依赖合并、macro flight 合并和成品走廊缓存，不能把这些机制当作缺失功能重做。

DEFAULT_GROUND 的尺寸为 0.6×1.95，GeometryKey 还包括精确尺寸、通道、流体和开门标记。因此不能直接断言本次成年尸壳与默认几何错配；应记录实际请求 key 和预热 key 后再判断。

## NavMesh 的可借鉴机制

### 成品保存和恢复

Recast4j 官方 MeshSetWriter 保存 NavMesh 参数和各 tile 的 MeshData；MeshSetReader 读取后建立 NavMesh 并添加 tile。该流程支持复用已经构建的成品，不要求每次恢复都从原几何重新体素化和生成多边形。

对应到本项目，可以研究独立的 Base 派生图缓存：保存组件标签、组件元数据、边和能力掩码等不可变产物，首次恢复时验证输入后复用，避免再次执行完整 BaseClusterTopology.build。先做 Base，不同时扩展 Super、走廊和全部长期缓存。

缓存命中、输入当前、真实可走通是三个不同判定。缓存产物仅供已有宏观拓扑提供候选，接入层不得据其标签、能力掩码或组件资格在真实详细搜索前拒绝候选。命中与未命中两种情况最终都须执行真实原版 PathFinder，缓存不能替代其详细可行性结论。

这是冷图优化候选，不是现有功能，也没有本项目速度证据。读取、解压、校验、重新绑定边界事实的总成本可能超过简单区域的直接构建，接受与否看请求到首条真实 Path 的墙钟对照。

关键边界：

- 缓存身份包含世界/维度/section、GeometryKey、派生算法与格式版本、所有实际参与构建的配置，以及中心和 halo 的事实版本/内容指纹。
- 当前基础事实仍须由现有加载与版本机制确认；不能仅因磁盘缓存存在就把世界输入认定为当前。
- 重启后的 loadIdentity 必须重新绑定，不能沿用上一次进程的身份或跨进程内存引用。
- 恢复应保留边界几何/事实依赖，可以重新绑定当前已确认的事实；不能只保存组件编号而丢失边界输入。
- 校验失败、过期、文件缺失或损坏时退回正常宏观构建，不把缓存失效判成无路，不把已分段任务切成整条原版回退。
- 原型初值：单条派生记录最多 8MiB、派生缓存 I/O 待办最多 8 个、暂存解码数据最多 16MiB、每世界派生文件留存最多 128MiB；恢复后的视图纳入现有 Base 128MiB 总留存上限。超过缓存额度就不缓存并继续正常构建，不减少路径请求的既有能力或恢复额度。这些是待压测的资源边界，不是现行已实现参数。
- 区块/halo 修改、事实版本变化、算法/配置变化和加载身份更换使恢复结果失效。每次读取绑定该次任务 generation、GeometryKey 和中心/halo stamps；并发重建或替换发生后，旧读取不得覆盖新视图。
- 取消/替换相应请求时释放其读取订阅和句柄；有其他请求共享同一构建依赖时只释放该请求的份额。区块卸载使在途结果失效并清理绑定引用；重新加载使用新 loadIdentity。
- 关闭时停止接纳，清空/取消待办和引用，迟到 I/O、构建和发布回调必须拒绝交付。原型退出门槛要求服务记录、缓存订阅和待办归零；不能因恢复缓存复活已取消或关闭任务。
- 发布仍由现有当前性与生命周期通道确认，不另开绕过身份检查的磁盘直达发布入口。

持久化主要针对地图已访问、事实可信且再次恢复的情况。世界第一次生成、数据不存在时仍要构建，不能承诺消除首次计算。

### 分块和按需准备

Recast 的 tiled navmesh 支持运行时重建。Unity 官方增量 NavMesh 更新会比较输入来源与构建配置的 hash，只重建变化部分；更改构建参数可能导致全面重建。

我们已经按 section/GeometryKey 管理派生视图与输入失效。下一步优先改善准备次序，而非从零新增“分块缓存”：

- 在已经加载的区域中，按实际玩家/实体所在地的高度和距离给预热 section 排序，优先当前及相邻需要的输入，不从高空、山顶或整片非空区域盲目向下准备。
- 优先实际请求中频繁出现的 GeometryKey；只保留少量热门 key，并按内存/构建成本限制，不能把仅 ceil 尺寸相同的不同实体强行共用视图。
- 首次请求出现后，沿用已有前台需求提升和构建合并，优先满足实际依赖；不简单放开所有后台预热争抢 worker。
- 不为预热强制加载远处区块，不暂停输入失效事件，不跳过邻域版本检查。

原型资源初值：全服务器最多 2 个热门 GeometryKey（含默认 key），保留最多 8 个已接纳预热工作和现行单构建 permit；每 tick 最多新增一个预热构建，预热 worker 实际执行的滚动 1 秒软额度先取 50ms，超支停止后续后台准入直到窗口有余额。单次构建仍不可抢占。新增热点预热的空闲视图留存最多 16MiB，同时计入现有 Base 128MiB 总上限；按数量和字节淘汰，不因达到上限拒绝前台正常需求。主线程准备和发布仍遵守现行共享 4ms 软预算。所有数值均为待实验初值，不宣称最优或已有收益。

预热主要把准备提前或改善次序，不一定减少总 CPU，也可能延长服务器启动。必须同时报告“服务端启动/可接受请求时间”和“实体首次路径时间”，避免只移动计时起点。

### 构建并行与端点复用

分块结构使并行构建值得研究，但当前单 permit 与共享 BuildScratch 是实际限制。以下隔离是前置硬约束：每个构建独占 BuildScratch、输入快照句柄/stamps/代次和结果发布上下文，不得共享可变任务状态；底层已冻结事实可以受控共享，但引用份额和生命周期分别管理，不能并发改写。发布仍通过统一 runtime 所有权和当前性检查，同一视图的代次竞争只能有一个有效结果，不能由两个 worker 无保护地同时写入。

满足这些约束后，才在隔离原型比较 1/2 个同时构建，并记录 scratch/输入留存峰值。不能只调大 worker/permit，不承诺扩线程一定更快。

端点解析可以研究按单独起点/终点进行有界复用，减少不同起终点组合中的重复准备。现有 RawQueryKey 是完整点对，而成品走廊缓存的 key 需要端点先解析成组件集合。缓存需绑定实际坐标、GeometryKey、相关解析策略、输入 stamps/负载身份，保留不同方向和重物化结果；若尚未证明能共享，则继续现有解析。它主要优化热图与重复端点，并不能代替首次区域构建。

本轮不建议通过放大 voxel/cellSize、降低几何精度或删减能力来换首次速度；这些会改变候选质量或连通性，需要独立研究。

## 建议的最小顺序

1. 先补首次路径的阶段计时：事实读取/恢复、base 构建及排队、端点解析、macro 搜索、窗口准备、主线程派发和发布。区分前台真实依赖与后台预热。
2. 优先验证热点/高度预热排序和真实 GeometryKey 覆盖，这是较小的准备策略改动；报告提前准备的额外成本。
3. 独立原型验证 Base 派生图持久化，重点比较同一世界重启后的端到端首次请求；保留缺失、坏数据、版本变化和 halo 修改反例。
4. 若构建等待仍占主要时间，再评估 1/2 构建并行；若热图端点等待占比高，再评估端点拆分复用。

动态预算属于另一项受控变量，先用相同现行 4ms 预算比较上述策略，再单独叠加动态预算，避免把限流解除误算为 NavMesh 冷图机制的收益。

## 冷图验证矩阵

- 新 JVM、相同世界、仅基础事实已落盘，RAM 派生图为空。
- 新 JVM、相同世界、基础事实和候选派生图均已落盘。
- 同 JVM、已加载但派生视图尚未准备的区域首次查询。
- 同 JVM、同区域热图查询。
- 独立副本中的基础事实缺失/失效，不修改生产存档；世界生成时间另计。

保持相同几何、实体、目标、加载边缘、预算和发布语义。提交时机需固定：加载后立即、允许固定预热窗口后分别测，不能将充分预热的一组与立即提交的一组混比。至少五次独立重复，交换顺序，首个请求与 128/256/512 并发分开报告。

记录 JVM/服务器准备时间、加载时间、到首条真实 Path 的 P50/P95、CPU/分配、I/O 字节和读写时间、准备的有用/未用图数量、MSPT 尖峰、实际到达和成功率。记录 OS 文件缓存条件，不能把普通进程重启冒称物理磁盘冷读。

收益必须来自这个矩阵；现有高并发热运行数据没有证明上述策略已提速。

## 一手资料

查阅日期 2026-10-13。外部能力不是本项目性能证据：

- Recast4j MeshSetWriter：`https://raw.githubusercontent.com/recast4j/recast4j/main/detour/src/main/java/org/recast4j/detour/io/MeshSetWriter.java`
- Recast4j MeshSetReader：`https://raw.githubusercontent.com/recast4j/recast4j/main/detour/src/main/java/org/recast4j/detour/io/MeshSetReader.java`
- Recast 官方 tiled mesh FAQ：`https://recastnav.com/md_Docs_2__3__FAQ.html`
- Recast 构建配置与精度：`https://recastnav.com/structrcConfig.html`
- Unity 官方增量更新 API：`https://docs.unity3d.com/ScriptReference/AI.NavMeshBuilder.UpdateNavMeshDataAsync.html`

## 独立审查

DeepSeek v4.1 Flash 对修订后的完整冷图候选给出 `CONSENSUS_WITH_GATES`，必须修改项为“无”。同意阶段计时、热点/高度与真实几何视图预热、Base 成品持久化的最小顺序；构建并行和端点拆分需先由阶段占比决定。

共识保留上述身份/生命周期/资源边界和冷图验证矩阵，不代表实现完成或性能通过。复审只提供现行契约及当前源码/证据，没有旧设计或旧审查；送审版本以身份记录的 SHA-256 为准，本节在送审后登记结果。

- [最终审查原文](../../.tmp/navigation-speed-root-20261013/deepseek-answer-navmesh-cold-research-final.txt)
- [退出与提示词哈希](../../.tmp/navigation-speed-root-20261013/deepseek-exit-navmesh-cold-research-final.json)
- [送审附件身份](../../.tmp/navigation-speed-root-20261013/deepseek-identities-navmesh-cold-research-final.json)

这里的持久化、预热和并行方案尚未实施；没有新增冷启动性能样本或收益百分比。
