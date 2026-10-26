# 详细寻路源码、NavMesh文献与1017讨论

日期：2026-10-22。

范围：按用户要求先读源码和多份一手文献，再与1017讨论性能开销，
确定下一方案，并研究NavMesh如何完成详细寻路。
没有实现候选、构建、启动游戏或新跑基线。

## 1. 最终结论

下一最小候选确定为：
[仅精简详细搜索临时能力还原数据](../designs/2026-10-22-detail-native-overhead-next-plan-zh.md)。
对 `NativeNavigationSearch.find` 中只用于finally还原的 `previous`，
用四开关与primitive malus数组代替完整能力record及两层Map。
请求intent本身的完整能力、公共读写、原生搜索和生命周期保持。

这项选择基于已确认的临时表达冗余，以及不在节点热循环增加工作，
不是已经测得的热点排名或性能胜利。双方认可有限实验资格，
不批准代码、数据或生产，不承诺项目200ms目标。

“读取合并”暂缓；新语义缓存、T/QG重调参和整套NavMesh均不作为下一实现项。
目前没有项目证据支持一个能大幅压缩详细搜索、又可直接适配全部现有规则的新后端。
不把它说成“A*已经最好”或“其它算法已被证明无效”。

## 2. 本项目源码核对

主要阅读对象：

- `NativeNavigationSearch`、`NativeNavigationSearchMixin`：
  RAW作用域、能力临时应用和还原、原生prepare/done、失败节点、
  profiler、proof及异常清理。
- `NavigationCapabilities`及原生Mob：
  capture构造EnumMap并Map.copyOf；apply只用四开关和malus；
  getter可能继承载具有效值，setter写自身map。
- `NavigationRegionMixin`、原生 `PathNavigationRegion`：
  旧unknown保护与known分支原region读块会重复定位chunk。
- 含Forge钩子的 `WalkNodeEvaluator`：
  搜索内路径类型/AABB缓存；floor、身体尺寸、递归台阶、落差、门水、
  closed/对角约束与block/fluid/adjacent路径类型钩子。
- `SectionGeometry`、`TopologyService`的碰撞采样、
  `MacroSearch`的组件/边界快照、`GroundSegmentWindow`及 `LocalKernel`。
- 原探针 `PerformancePathFinderMixin`：
  搜索CPU/分配窗不覆盖NativeNavigationSearch外围的能力捕获和还原。

本轮的新切点不是取消既有保护，而是：
临时previous被当成完整不可变业务能力快照构造，但之后只用来apply。
它的物理字段与Map契约在这个用途下没有消费者。
intent的完整能力则仍被physicalMatches、宏观资格及请求身份等使用，不能裁减。

读取路径存在重复路由，是源码事实；
重复部分是否占明显CPU、是否已被JIT消去、改写是否值得，不是已知事实。
原生已有缓存，不能笼统提出“加缓存”就宣称减少重复计算。

原生源码副本：

- `.tmp/navigation-budget-handoff-20261014-grace2/upstream/net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java`
- `.tmp/detailed-navigation-framework-20261011/upstream/PathNavigationRegion.java`
- `.tmp/detailed-navigation-framework-20261011/upstream/Mob.java`

## 3. 一手参考及交叉判断

原件保存于 `.tmp/detail-query-navmesh-research-20261022-v1/references/`。
网页浏览获取未返回可用内容，随后通过GitHub官方API及论文出版方地址取得原件；
不伪称浏览器已检索到其它材料。
HPA*原文的两个作者站点地址未成功取得，本轮不把它作为读过的依据。

### Lithium：先把询点和读取做便宜

固定 `mc1.20.1-0.11.4`，
commit `1316ab1aafd888bf9881e924f29928f653ad2308`。
已读 `PathNodeCache`、`BlockStatePathingCache`、
`LandPathNodeMakerMixin`、`AbstractBlockStateMixin`、`ChunkCacheMixin`。

它在BlockState初始化时尝试取得静态路径类型，动态/未能确定时回退；
用section危险摘要减少邻域扫描；扁平chunk引用并直接读section。
这证明成熟项目的优化不必从替换A*开始，
但不证明这些优化在本项目中的CPU份额或净收益。

本项目Forge钩子接收世界、位置和当前类型，不能静态按state一概替代。
直接section读取也可能绕过现有未知区块规则及其它注入；
Fabric版本的实现不能直接当Forge语义等价证明。

源码入口：
<https://github.com/CaffeineMC/lithium/tree/1316ab1aafd888bf9881e924f29928f653ad2308/src/main/java/me/jellysquid/mods/lithium>

### Detour/Recast：小图搜索后，沿公共边提取具体路径

固定 `v1.6.0`，
commit `6dc1667f580357e8a2154c28b7867bea7e8ad3a7`。
核对 `DetourNavMeshQuery.cpp` 的findPath、findStraightPath、getPolyHeight，
及构网示例和TileCache重建代码。

1. findPath对多边形连接图搜索，邻接位置采用公共边中点并使用filter代价，
   返回多边形序列。
2. findStraightPath沿该序列的公共边维护漏斗左右边界，输出必要拐点；
   off-mesh连接有独立标记。
3. 地面高度另由多边形/detail信息求取，不能把二维拉直直接当完整三维动作。
4. Recast构建要过滤支撑/高度、按半径收缩、分region、轮廓、poly及detail。
   TileCache对障碍修改仍会重建受影响tile的region/contour/poly和nav数据。

因此它的主要优势来自可直接穿越的凸几何资产，以及更小的搜索表示，
不是免费知道连通，也不是换掉A*名字。
固定走廊的拉直不能保证全局几何最短，更不自动最小化原生malus动作代价。

源码：
<https://github.com/recastnavigation/recastnavigation/blob/6dc1667f580357e8a2154c28b7867bea7e8ad3a7/Detour/Source/DetourNavMeshQuery.cpp>
及同commit的 `RecastDemo/Source/Sample_SoloMesh.cpp`、
`DetourTileCache/Source/DetourTileCache.cpp`。

### Polyanya：在整个凸网中搜索连续区间

Cui、Harabor、Grastien，IJCAI2017，
《Compromise-free Pathfinding on a Navigation Mesh》，496–502页，全文阅读。

它的搜索状态是root和可见边区间，在凸多边形之间投影推进，
以可采纳几何下界排序，并以root history处理冗余和终止。
与先固定channel再拉直不同，它在符合论文条件的整个mesh中寻找欧氏最短路。

关键条件是平面凸网、连续可见移动和欧氏长度。
mesh需要作为输入；online不代表免构网。
单次查询需一致的mesh，可在查询间更新；不要求世界永不改变。
原文末尾仍将单源多目标扩展列为进一步研究，
不能把最多八个目标的工程接口当作已由原文实现和证明。

论文：
<https://www.ijcai.org/proceedings/2017/70>
原PDF：<https://www.ijcai.org/proceedings/2017/0070.pdf>。
本地PDF SHA-256：
`03de634fcd4ffb8747ac0c652a0ff35fc4124f73c734609e2a84d9b6f8748960`。

相对Lazy Theta*，它更贴近“在已认证几何域和公共边上减少逐格搜索”的资产方向，
但现在缺的正是这些精确几何/代价/动作资产。
这不是目前值得直接换上的通用后端。

### JPS：收益来自可证明的对称剪枝

Harabor、Grastien，AAAI2011，
《Online Graph Pruning for Pathfinding On Grid Maps》，1114–1119页，全文阅读。

论文针对无向均匀代价八邻格，直移1、对角sqrt(2)，
通过支配/强制邻点及跳点规则去掉中间展开。
保留普通邻接再附加长边，并没有取得论文的完整对称剪枝收益。
扫描、插入、精化成本仍需计入，不能只比展开数。

论文：
<https://ojs.aaai.org/index.php/AAAI/article/view/7994>
原PDF：<https://ojs.aaai.org/index.php/AAAI/article/download/7994/7853>。
本地PDF SHA-256：
`702a0a48aadbb8c14448295371323215866b935728815e329a668ed85af5adaf`。

上述资料互相支持的判断是：
减少搜索工作往往要先有合适表示和规则条件；预处理/构建不免费；
已有连通不能替代真实合法动作与最短代价。
它们没有给本项目提供可直接套用的性能倍数。

## 4. NavMesh能复用什么、还缺什么

| 资产 | 可复用部分 | 尚不能代替 |
| --- | --- | --- |
| 组件成员位图/行位运算 | 定位区域、枚举候选、几何派生输入 | 凸性、净空、完整真实邻接 |
| SectionGeometry | 几何捕获和分块组织 | 精确实体上下文、原生路径类型/动态钩子 |
| 边界方向/位移/mask | 连接识别、派生区间的线索 | 连续可穿越公共边、最短局部路 |
| 现有worker/版本基础 | 纯数据派生和局部重建的组织基础 | 免费新增构网和失效维护 |
| 真实能力快照 | 身体与动作条件输入 | 单一宏观profile下的通用mesh |

值得保留的长期方案是分层凸patch、完整portal区间、
独立有向台阶/跳落动作，平面均匀部分再讨论Polyanya。
这不是恢复旧全门户配对：凸域内希望直接利用几何，不为所有点对先跑详细A*。

但同一个连通组件可能有洞、弯折、多楼层、门、水、惩罚；
现有几何按1/16保守量化且可能旧于世界，采样又不等同所有实体上下文。
必须先解决构建规模、半径/高度、更新、复杂动作及原Path执行适配。
如果仍要逐格认证并在失败后从头跑原生，要把双付成本记全。
本轮不构网、不启用混合后端、不用连续最短宣称原生离散最优。

## 5. 1017三轮讨论和纠正

使用用户授权的 `deepseek-official`。
每轮实时GET models，选择 `deepseek-flash`，thinking enabled、max。
列表没有独立日期1017 ID，名称为DeepSeek-V4.1-Flash；
别名/fingerprint不能证明不可变的日期版本。
未修改共享provider配置，不将凭据写入讨论文件。

| 轮次 | 自含包UTF-8字节 | 耗时秒 | 作用 |
| --- | ---: | ---: | --- |
| 1 | 7651 | 109.089 | 源码/两论文/成熟实现，独立比较读取、缓存、NavMesh |
| 2 | 8097 | 97.511 | 补完整region方法及临时还原数据新切点，比较方案 |
| 3 | 4966 | 31.993 | 纠正旧unknown顺序误读，确认最终边界 |

三轮均HTTP200、finish_reason=stop、正文有DISCUSSION_COMPLETE。
返回model为deepseek-flash，
fingerprint为 `e72d3c7f663d3e1383b00b3884cb4501`；
均已实际阅读正文，不以传输成功充当方案通过。
每轮无前文上下文，包内带入事实、上一轮摘要及明确反驳。

实质变化：

- 第一轮推荐读取合并；第二轮补源码及新切点后，
  最终改为先考虑临时还原数据，读取合并暂缓。
- 第一轮自设5%/3%收益门、冷前N次和5100“语料重放”没有依据。
  第二轮明确撤回；旧日志不是完整可重放世界，单次也不提供可靠CI。
  最终不新增这些规则或场景。
- 第二轮误将旧HEAD的unknown先于高度检查视为提案新缺陷。
  第三轮给出旧生产源码，1017明确撤回。
  本轮未据此宣布生产bug或改动unknown契约。
- 原始回复关于新增内存的近似、通用模组等价等表述，
  以最终方案的完整成本和具体兼容边界为准。

最终无原则冲突：临时previous精简具备有限实验资格，
没有测得收益，不承诺200ms，不代替代码/数据或生产审查。
普通代码/数据审查仍按用户原provider分工，不擅自以官方调用代替。

## 6. 数据边界及本轮产物

T/QG/TC的历史结论保持；U的5100次合计只用于量级背景，
不能由均值推单请求峰值或证明外层能力快照占比。
本轮没有新的性能数值。

后续若获实现授权：
一个临时还原数据变更、最小必要等价/异常控制、
原35ms六场一次筛测，U/P/N有效历史直接复用。
新外层观测若确有必要，先记录历史具体缺失，不默认重跑整个基线。
收益含混就暂停或不保留，不自动加轮次。

原件目录：
`.tmp/detail-query-navmesh-research-20261022-v1/`。
包含固定版本源码、两PDF、三份自含讨论包、专用客户端、
每轮models/request/transmitted/response/answer/exit、
协调文档更新前副本及 `seal-v1.json`。

生产68项和相关历史43项核对保持，原TopologyWorkerRuntime脏改保留。
本轮只新增研究材料、方案/报告并更新任务索引和主交接第六十五节；
没有生产源码、默认JAR或旧实验实现修改，没有提交、推送或数据清理。
