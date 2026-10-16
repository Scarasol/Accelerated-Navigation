# NavMesh 参考下的额外冷启动优化方向

日期：2026-10-14。状态：**研究结论；未修改生产源码，未接入生产，未进行新的性能宣称。**

2026-10-15 进度：[首次请求闭包与骨架前置报告](2026-10-15-navmesh-prerequisites-validation-zh.md)已完成60个独立JVM并取得DeepSeek READY_TO_VALIDATE。用户随后授权的[依赖闭包候选验证](2026-10-15-first-request-closure-validation-zh.md)已经实现并实际运行；先行正确性门通过，135轮交错矩阵于第104轮auto基线派发前失败后中止，103轮检查通过、1失败、31未跑。DeepSeek认可中止及未测值纠正，整体未取得性能资格，不能据此判候选算法失败。Portal骨架尚未实现且本轮不进入，本文后续方向不能视为已验证结论；重测须另列范围并新冻结。

同日后续：[Portal/Boundary 源锚点骨架](2026-10-15-portal-boundary-skeleton-validation-zh.md)已按用户新授权独立实现和验证。当前“完整Base后提取源坐标mask”与旧bbox在现组件/邻槽规则下等价，不能新增依赖剪枝；稀疏枚举性能仅部分样本，新27矩阵在第12轮C/plain移动阶段失败后中止，11完成/1失败/15未跑，未取得性能资格。DeepSeek最终认可封存该依赖缩减候选、不接入及忠实中止报告，必改无；不外推其他骨架/目标边界几何或Base之前构法，不恢复预算等支线，也不把旧“未实现”快照当当前状态。

本文承接 [NavMesh 冷图准备调查](2026-10-13-navmesh-cold-preparation-research-zh.md)、[派生拓扑懒惰升级研究方案](../designs/2026-10-14-derived-topology-lazy-upgrade-research-plan-zh.md) 及其验证记录。唯一生产规范仍是 [原版详细寻路接入现行契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)。旧设计、旧审核和已废弃的三 tick/四 tick方案不参与本研究。

本文只回答一个问题：在另一会话已开展的“Base 派生持久化、有界异步恢复、热点预热、共享表示和局部升级”研究之外，是否还有办法减少冷启动等待。Base 编码原型已验证，异步原型仍有审核及 future 结算问题；共享局部差异尚未实现持久化，不能把这些方向合并称为已经验证通过。可以继续研究首次请求依赖范围，再根据阶段计时决定是否做格式或构图内核改造。NavMesh 只提供分块、边界和构建流水线方面的参考，不能替换 Minecraft 的真实 `PathFinder`。

## 当前实现中的冷启动成本

当前实现以 16×16×16 section 为 Base 构建单位。`BaseClusterTopology.build` 依次完成合法锚点派生、组件标记、地面边生成和边界快照；`TopologyWorkerRuntime` 在进入构建前还会持有中心及邻区事实。当前 `TopologyTaskExecutor` 有两个 worker，但构建 permit 仍是单个，因此后台预热接纳数量不等于 Base 并行构建数量。

宏观查询只有进入 Super 分支时才存在这一层等待。`TopologyWorkerRuntime.joinResolved` 要求未强制 Base、端点父区最大 Chebyshev 距离至少为 2 个父区，且各端点父区的 8 个子 section 都已加载；否则采用现有 Base 图。短路线和不满足加载条件的跨 section 路线并不必然先等待完整 Super。实际创建 `SuperClusterTopology` 时仍须收集一个对齐的 2×2×2、共 8 个 Base 子图，随后建立连接并做强连通分量压缩。边界连接的按需缓存不能消除这项子图等待。

事实存储目前保存 `PackedFacts` 和 `SectionGeometry`。已有派生图持久化原型可以恢复 Base 成品，但其读取仍包含记录读取、摘要、解码、完整校验和 Java 对象创建；异步读取也没有证明真实服务器首条 Path 稳定变快。共享图和局部升级的原型也没有取得稳定优于紧凑生产表示的性能资格。

源码里还存在一个适合单独消融的构图内核问题：`BuildInput.flags`、`sourceFacts`、`atSection` 和边界面复制反复对最多 26 个 halo 偏移执行 `Arrays.binarySearch`。这类查找位于合法性扫描、碰撞检查和边生成的内层路径中，尚未被“持久化/预热/共享”方向覆盖。

## NavMesh 可借鉴的边界

Recast 的 tiled 示例把单个 tile 的构建边界向外扩展 `borderSize`，只取保证相邻 tile 正确连接所需的邻区几何；边界区域不生成最终多边形。示例还支持按位置重建或移除单个 tile，而不是每次重建整张网格。

Recast 的构建流水线明确分成 heightfield、compact heightfield、区域、contour、polygon mesh 和 detail mesh 等阶段；其源码把 monotone partition 标为最快，把 watershed 标为通常最慢，并指出 layer partition 适合中小 tiled navmesh。这里的价值是说明“中间产物和分块阶段”本身可以成为独立缓存或独立预算对象，不代表应把这些算法直接移植到 Minecraft。

Recast4j 的 `MeshSetWriter` 按 tile 写出数据，`MeshSetReader` 从 `ByteBuffer` 读取并逐 tile 加入 NavMesh；Detour TileCache 还保留压缩 tile 数据，并在 `update` 中一次处理一个待更新 tile，完成解压、障碍应用和 tile 重建。这些机制支持按 tile 延迟恢复、低分配读取和有界增量更新的研究方向，但不提供本项目的正确性或性能证据。

Unity 的官方增量更新接口也采用“记录输入源哈希，只重建发生变化的部分”的思路；同时它明确指出改变构建设置可能导致完整重建。这与本项目需要把事实、GeometryKey、算法版本和 halo 依赖分开记账的要求相符。

## 未被当前验证主线覆盖的方向

### 1. 首次请求依赖闭包，而不是等待完整 Super

**做法。** 首次请求只提交起点 section、终点 section、搜索前沿实际触及的 face/vertical band 和必要邻区；宏观搜索遇到缺失边界时，再按缺失的具体面提交下一个 section 或 boundary link。不要先等待完整 2×2×2 Super。可以保留现有 Super 作为热态或复杂路线的后备结构，但首条候选先使用小型依赖闭包。

**它和现有懒惰升级不同。** 懒惰升级解决的是“基础图没有当前能力连接时补哪些行”；本方向解决的是“冷启动第一次查询需要准备多少空间和多少上层结构”。即使能力不变，也可以少构建没有进入首条搜索前沿的 section。

**潜在收益。** 对当前实际进入 Super 分支且被额外子图准备阻塞的请求，有机会减少首请求的 Base 数量和 Super SCC 工作。现有 Base 分支已按搜索前沿请求依赖，短路线不能重复计算这项潜在收益。复杂路线也可能因失去聚合而展开更多 Base；收益和适用负载尚未测量，不能先认定大型整合包必然改善。

**正确性边界。** 依赖闭包只能决定哪些宏观候选尚未就绪，不能把“未加载”解释为“不可达”；所有候选仍交给真实原版 `PathFinder`。组件和 section 代次必须绑定，迟到的边界结果不能拼进新版本。

**首轮原型。** 给 `MacroSearch` 增加缺失依赖统计，按依赖类型分别计时：中心 Base、终点 Base、face boundary、Super child、端点解析。比较完整 Super 与依赖闭包的首条真实 Path、构建 section 数、未使用图字节和失败/重试次数。

### 2. Portal/Boundary 骨架优先，Super 延后压缩

**做法。** 从 Base 的组件边界和 face 上的候选入口生成很小的不可变 portal skeleton，只保存面、局部锚点区间、能力掩码和输入代次。第一次宏观查询先在 skeleton 上确定需要的 section/face；完整 SCC 聚合和 Super 压缩延后到同一走廊重复出现或搜索范围扩大时。

**为什么适合当前代码。** 项目已经有 `BoundaryLinks` 和 face slot 缓存，但当前 Super 仍要求 8 个 Base 子图全部就绪。新骨架不取代 BoundaryLinks，而是把“确定下一块需要什么”与“生成完整父图”分开。

**关键风险。** 不能持久化或长期依赖易变的 component ID。骨架键应使用带 section/face/局部锚点和事实代次的稳定身份；组件重标、门状态变化、邻区卸载和版本替换必须使骨架失效。骨架命中只能提供宏观候选顺序，不能提前宣称 `canReach`。

**优先级。** 先测量现有 Base/Super 分支的实际等待，再决定是否引入骨架。由完整 Base 生成的骨架仍需等待源 Base，不能消除该 Base 的首次构图成本；若希望先于 Base 可用，必须另行设计从事实或边界带生成骨架的流程。没有与 worker 数量或查表优化的同口径对照，不能判定哪个收益更高。

### 3. 空间分块：section 内 tile 或高度层的按需构建

**做法。** 在现有 16³ section 之下引入小 tile、面带或高度层，把 `legalMask`、组件扫描和边生成限制在起点/终点及其 border 范围。跨 tile 只发布带代次的 portal；未访问 tile 保持 UNKNOWN。

**NavMesh 启发。** Recast 的 tile 构建把最终 tile 与扩展 border 分开，只从相邻输入取必要的边界带；它还提供单 tile build/remove。对应到 Minecraft，需要把 3D section 的垂直层、楼梯和跳跃跨层关系纳入 border，而不能照搬平面 tile。

**收益与代价。** 该方向有机会把一次 4096 格扫描变成若干小块，降低首请求工作；但 tile 数增多会扩大索引、版本、边界和持久化元数据，路线穿越大量小 tile 时也可能反而变慢。它属于高复杂度方向，不应在没有依赖闭包数据前实现。

### 4. 中间构建产物缓存：缓存“扫描结果”，而不是只缓存最终 Base

**做法。** 在 `PackedFacts/SectionGeometry` 与 `BaseClusterTopology` 之间增加可复用的紧凑产物，例如：

- 体型无关的列/层可行走 bitplane；
- 只包含必要 border 的开放/碰撞掩码；
- 组件扫描前的侵蚀输入；
- face portal 候选、连续边界 run 和高度区间。

恢复后先重建当前 GeometryKey 的轻量视图，再决定是否生成完整 Base。第一版只选一个主基准 `1.0×2.0`，不保存实体对象、原版 `Path` 或组件句柄。

**和已在验证的派生图持久化的区别。** 当前主线保存的是最终 Base/局部差异；本方向保存的是更靠前、可被多个 GeometryKey 或多个 tile 复用的构建阶段结果。它可能使用同一个 cache root，但格式、失效依赖和收益问题独立。

**适用条件。** 只有当阶段计时显示合法锚点派生、碰撞/门判断或组件标记占首条 Path 等待的主要部分时才值得做。若 Base 构建本身很快，解码中间产物反而会增加冷启动。

**验证。** 对同一 facts、GeometryKey 和 27 halo，分别测无中间产物、只恢复 border mask、恢复完整 `legalMask/prismMask` 三种模式；记录解码字节、对象分配、构图时间、首条 Path、全部未用产物和输入变化后的失效率。

### 5. 低分配、分段恢复格式

**做法。** 将目前“读 chunk、解码所有 section、创建对象”改为带索引的定长/分段记录：先读 header 和 section 索引，只恢复首个依赖闭包需要的记录；压缩块延迟解压；恢复后的数据使用 packed primitive arrays 或只读 `ByteBuffer` 视图，避免一次性创建完整 Java 对象树。

**它和异步 I/O 不同。** 异步只改变等待发生在哪个线程；本方向减少读取、解压、复制、对象创建和 GC 总量。若阶段数据证明 `read/decode/validate` 占比高，它比再增加 I/O worker 更有针对性。

**风险。** `MappedByteBuffer` 生命周期、Windows 文件删除/替换、压缩块边界、损坏记录隔离和关闭清理都需要单独处理。缓存必须仍是可丢弃数据；基础 facts 失败和派生缓存失败不能混成同一错误。

### 6. 依赖图和边界带的细粒度失效

**做法。** 分别研究中心加最多 26 个 halo 的恢复等待、临时引用和发布依赖，避免把它们当成同一集合。对 facts 使用 section 内分块指纹或 Merkle/dirty bit，而不是每次对全部 4096 格和所有 halo 做完整摘要。先用几何和 border 规则给出保守依赖，再在构建完成后记录实际使用依赖。

**当前代码依据。** `demandFactsReady` 已按 `requiresHalo` 决定哪些已加载邻区需要恢复并等待；`beginBuild` 则遍历最多 26 个邻区，对当前可用事实临时 pin，并由 `buildInput` 捕获更广的事实。发布后的 `boundarySnapshot` 和 stamps 再按几何与实际使用记录依赖。第一步可验证缩小临时引用集合的效果；它不自动减少已有恢复等待。是否可以缩小等待或发布依赖，仍需独立证明门、支撑、碰撞及边界连接完整性。源码中的事实哈希优化也不能当作已测得的主要冷启动成本。

**风险。** 依赖过窄会造成错误复用，尤其是门、低顶、偶数宽度、垂直边界和 `EXACT_REQUIRED`。第一版应只做计时和拒绝性对照，不能先把缺失依赖当作安全可复用。

### 7. 构图热循环专用化

**做法。** 先不改变图语义，只做可逆的内核优化：

- 为 27 个 halo code 预建直接索引表，消除内层 `Arrays.binarySearch`；
- 在一次 build 中缓存 `PackedFacts`、`SectionGeometry`、门形状和边界面；
- 为 `1.0×2.0` 主基准预计算侵蚀/行移位掩码；
- 将 `edgeMap` 的初始容量和边方向表按 section 规模预估；
- 把阶段计时拆成 `flags/volumeOpen/groundOpen`、组件标记、边生成和边界快照。

**预期。** 这是低到中复杂度、正确性风险较低的方向，收益上限取决于 JFR/阶段计时；它不会减少需要构建的 section 数，因此不应替代依赖闭包或分块研究。

### 8. 世界运行期间的增量烘焙

**做法。** 在区块稳定、保存、卸载或服务器低负载阶段，用最低优先级提前完成最终 Base、portal skeleton 或中间产物；重启时只需恢复已经完成的热点。每次只处理一个有界 tile/section，服务器停止时允许未完成。

**价值。** 它不降低总 CPU，但能把重启后的首次计算转移到世界已经运行的时间段。对经常重启、玩家活动区域稳定的大型整合包可能比启动后盲目预热更有效。

**边界。** 必须绑定事实版本和 load identity；不能因保存阶段生成过某图就跳过当前性检查，也不能在低负载判断错误时挤占主线程 tick。它与最终图持久化共享存储机制，但研究问题是“何时构建”，不是“如何编码”。

## 排序和决策门

| 方向 | 可能减少的冷启动部分 | 未验证的收益假设 | 实现复杂度 | 正确性风险 | 建议 |
| --- | --- | --- | --- | --- | --- |
| 依赖闭包 + portal 骨架 | 实际 Super 分支的额外子图等待 | 待测；Base 路径可能已较省 | 中高 | 中高 | 先对照现有 Base/Super |
| 细粒度依赖/边界带失效 | 临时 pin；其余需依赖证明 | 待测 | 中 | 高 | 与第一项同批计时 |
| 中间构建产物缓存 | 合法性扫描、侵蚀、组件前置阶段 | 中高 | 中高 | 中 | 阶段占比达到门槛后做 |
| 分段/零拷贝恢复 | 解码、复制、对象分配、GC | 中 | 中高 | 中 | 只有 decode 占比高才做 |
| section 内 tile/高度层 | 单次扫描和边生成范围 | 高但不确定 | 高 | 高 | 作为第二阶段原型 |
| 构图热循环专用化 | 每个 section 的 CPU | 低到中 | 低 | 低 | 可先做小型消融 |
| 世界运行期间增量烘焙 | 重启后的首次构建 | 高（感知时间） | 中 | 中 | 依赖存储主线后做 |
| JVM/CDS/类加载 | JVM 初始化 | 低到中 | 中 | 低 | 最后测 |

## 建议的研究顺序

1. **先补阶段计时，不改行为。** 每个首请求记录实际 Base/Super 分支、facts 读取/恢复、等待中心 Base、`BaseClusterTopology.build` 分段、BoundaryLinks、Super child wait、Super build、端点解析、宏观搜索、主线程发布和真实首条 Path。并行阶段保留时间区间和请求依赖，不将各阶段墙钟简单相加。固定同一预算、世界、GeometryKey、预热时机、端点和实体参数；真实冷态使用新 JVM 与冻结世界副本，不用热图时间代替冷启动。
2. **先比较现有 Base 与 Super，再决定依赖闭包和 portal skeleton 原型。** 覆盖同 section、现有短程 Base、实际满足 Super 门槛的跨父区路线，以及 128/256/512 并发。统计请求真正等待的子图、被构建但未使用的图和搜索展开；如引入新骨架，它属于宏观结构研究，需另行明确授权及契约范围，不将“只改调度”视为自动保证算法不变。
3. **根据计时分流。** `Super/Boundary` 占比高，继续骨架；`Build` 占比高，做中间产物和热循环；`read/decode/validate` 占比高，做分段/零拷贝恢复；facts/pin 占比高，做细粒度依赖。
4. **最后再考虑 section 内 tile。** 只有前述方案仍显示单个 16³ 构建过大，才引入新的空间粒度和边界身份。
5. **把世界运行期间烘焙作为大型整合包策略。** 它需要最终缓存格式和失效语义稳定后再做，不能用预热计时替代真正重启对照。

## 结论

除派生拓扑持久化、热点预热和共享/局部升级主线外，可以研究“实际依赖闭包 → portal/Boundary 骨架 → 按需 Base/Super”的分阶段准备。是否减少首次请求对象数量，取决于当前是否进入 Super、哪些子图真正阻塞，以及新增骨架是否仍依赖完整 Base；尚不能判定它是收益最高的方向。所有宏观结果仍只提供候选，真实可达性由原版 `PathFinder` 决定。

第二层方向是中间构建产物和低分配恢复，分别针对构图 CPU 与解码/GC。构图热循环专用化可以作为低风险小消融先做；section 内 tile、高度层和完整 NavMesh 迁移应后置，除非阶段计时证明当前粒度仍是主要瓶颈。

本轮只完成文档、源码核查和外部资料研究，没有新增性能数据、没有修改生产代码、没有提交或推送，也没有把任何候选方向登记为生产资格。

## 2026-10-14 后续核查更正

本次核查更正了持久化状态、Super 选择门槛、halo 恢复等待与临时 pin 的区别，以及未测量收益的排序口径。Recast/Detour/Recast4j 与 Unity 的原始引用可以支持其分块、恢复和增量更新机制，但不能证明本项目会获得对应性能收益；Recast4j 的 `ByteBuffer` 接口也不等同于已经实现零拷贝或按需恢复。

源码与外部引用身份、对应位置保存在 `.tmp/navmesh-cold-report-review-20261014/verification-v2.json`；Node 网络读取失败的首版记录 `verification-v1.json` 保留，PowerShell 读取结果另存，不将网络失败误判为资料内容不成立。本次没有新增性能实验或宏观算法实现，建议下一步先做现有 Base/Super 与冷启动依赖的阶段计时。

## 外部一手资料

- Recast tiled navmesh 示例及按 tile 构建/移除：<https://github.com/recastnavigation/recastnavigation/blob/main/RecastDemo/Source/Sample_TileMesh.cpp>
- Recast tile border、邻区输入和构建阶段：<https://raw.githubusercontent.com/recastnavigation/recastnavigation/main/RecastDemo/Source/Sample_TileMesh.cpp>
- Recast4j 按 tile 保存：<https://raw.githubusercontent.com/recast4j/recast4j/master/detour/src/main/java/org/recast4j/detour/io/MeshSetWriter.java>
- Recast4j 从 `ByteBuffer` 读取并加入 tile：<https://raw.githubusercontent.com/recast4j/recast4j/master/detour/src/main/java/org/recast4j/detour/io/MeshSetReader.java>
- Detour TileCache 的压缩 tile、逐 tile 更新和重建：<https://raw.githubusercontent.com/recastnavigation/recastnavigation/main/DetourTileCache/Source/DetourTileCache.cpp>
- Unity 官方增量 NavMesh 更新与输入哈希：<https://docs.unity3d.com/ScriptReference/AI.NavMeshBuilder.UpdateNavMeshDataAsync.html>
