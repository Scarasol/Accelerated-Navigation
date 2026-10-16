# 本轮实服采样后的剩余拓扑研究排序

日期：2026-10-15。范围：结合用户已有 IDEA、spark、日志与前几轮原型，判断尚未接入的技术方向是否值得验证。仅做离线复算、源码核对和研究排序；没有修改生产代码、运行新的游戏实验、调用新的 DeepSeek 审核或恢复持久化。

**建议优先验证两项：构图中间产物的分层复用、Base/Super 消费者之间的边界连接复用。** 细粒度依赖作为第三顺位；section 内 tile/高度层和 Base 之前的骨架暂缓。当前资料支持这些问题值得小规模验证，不支持任何新候选已经提速或可以生产接入。

这是对原[额外方向报告](2026-10-14-navmesh-cold-start-additional-directions-zh.md)的重新排序，不修改此前封存结论。当前生产的事件处理、扫描读取、构图查表、Boundary scratch、桥接和解码优化由另一会话处理，本报告不将其重新列为新技术研究。

## 支持排序的数据

复用另一会话已经读取的原始 JFR、spark 解码和日志副本，另用本轮 `.tmp/remaining-topology-directions-20261015/analyze.mjs` 复算选定计数。四份原始输入与副本哈希一致，核对时 61 份生产文件与其分析快照一致。没有重采样或重跑基准。

| 观察 | 本轮实际值 | 对研究排序的意义 |
| --- | --- | --- |
| 两个 topology worker 的模组活动样本 | 943；Runtime 465、Base 213、独立 Boundary 92、宏观查询 88、Super 14、其他 71 | Base 22.6%、独立 Boundary 9.8%、Super 1.5%；优先考虑可复用的底层构建产物，不优先重写 Super 聚合 |
| Base 合法锚点阶段 | `deriveLegalAnchors` 140 个活动样本，Base 总类 213 个 | 事实到合法性表示之间存在值得研究的成本；不是证明某个中间缓存必然有效 |
| spark 扫描路径 | 4,076ms 累计采样权重，其中仅 48ms 经 `finishTick` | 本轮以恢复扫描为主，没有把局部编辑后的整块重建证明为主要问题 |
| 整次运行的后台工作 | completed build tasks=29,893，Base cache 最高图记账=234,709,658B | 必须同时考虑工作量与新增留存；任务数不是不同 section 数，图记账不是进程峰值 |
| 原先冷阶段 | 自动分支 64 Base/8 Super，强制 Base 14/0；减少工作没有稳定转化为首 Path 收益 | 不能仅凭“减少 Base 数/边界扫描数”决定候选成功；必须看真实 Path、后续到达及总成本 |

IDEA 是 wall 采样，spark 仅覆盖 Server thread。上述百分比不是 CPU 百分比、单次请求占比或关键路径比例，不能横向相加还原 1,193ms 的手动宏观请求。详细输入、口径与生产热点见[另一会话的联合分析](2026-10-15-production-hotspots-zh.md)，冷阶段原件见[前置验证报告](2026-10-15-navmesh-prerequisites-validation-zh.md)。

## 第一顺位：构图中间产物分层复用

对应原额外方向第 4 项，首轮只研究内存中的中间产物，不涉及落盘。

现有 facts/SectionGeometry 已共享，section 内 SharedRows 也已接入有限地面请求。新问题是：同一份事实被不同 GeometryKey、基础图和局部构图消费时，能否共享更靠近合法锚点生成的中间计算，减少重新扫描及形状分类，而不再保存一份完整锚点图。

`BaseClusterTopology.deriveLegalAnchors` 先生成开放/支撑行，再按尺寸侵蚀为 prism/legal mask；这些行目前属于构建 scratch。可研究把可复用部分提升为有界、不可变的中间层，让精确体型视图继续独立派生。这与另一个会话消除单次 build 内重复查表的工作具有不同的复用生命周期。

**不能直接假定这些行全部体型无关。** `volumeOpen → SectionGeometry.occupied` 使用精确 width、height 和开门条件。首轮应只分离确实体型无关的基础分类/位平面，精确形状、门与流体分支仍按原条件计算，并绑定中心及必要 halo 身份。缓存完整 legalMask 的同键副本也不能算收益：同键 Base 已经有缓存，新增一层若没有其他消费者就是重复存储。

首轮直接回答三个问题：

1. 在同一输入的多个真实构建条件之间，究竟有多少中间结果重复、是否真的被再次使用？
2. 共享后 legal/prism、组件、边、能力及 haloUsed 是否逐项等价，遇到 UNKNOWN、门、偶数尺寸和薄碰撞形状是否正确拒绝错误复用？
3. 把中间层建立、查询、输入校验、失效和新增留存一起计入，是否比完整重算更便宜？

先做一个小范围的复用机会与内核对照即可。若复用很少、精确例外占满收益，或新增留存抵消节省，就停止，不展开完整服务器矩阵。本轮采样尚未记录同事实跨 GeometryKey 的重复比例，不能先承诺命中率。

## 第二顺位：同输入边界连接跨消费者复用

对应共享拓扑补充中尚未实现的 Boundary/Super 扩展，首轮范围应明显小于“全面共享拓扑”。

当前 Base 前沿已有代次绑定的 BoundaryLinks 缓存；不能把它重新实现后当成新方案。新的具体机会在于：`SuperClusterTopology.addDirectedBoundary` 会直接调用 `boundaryLinks(source,target,face)`，先生成包含能力掩码的边界记录，再按 MovementKey 过滤。该构建接口本身没有 MovementKey 参数。可验证同一对当前 Base、同一 GeometryKey 和方向的结果，能否在 Base 前沿与不同父图能力视图之间复用。

这项候选复用的是已经计算出的目标组件、碰撞与 band 结果，不是上一版 Portal 的源坐标 mask。上一版只能重复旧 bbox 的选择信息；这里尝试避免重复的实际边界几何计算。独立 Boundary 有 92 个 worker 活动样本，是值得检查的成本，但这些样本不是重复计算次数。

首轮不跨体型，不改 SCC，不扩大共享导航的空间范围，不以 component ID 跨代关联。键与发布必须绑定实际 source/target Base 身份、GeometryKey、方向、两侧当前性及原完整依赖，结果随拥有者清理；仅凭 signature 数值相同不足以复活旧结果。

首轮分别比较一次使用、多 MovementKey 消费和 Base/Super 两类消费者，核对全部 band/能力/掩码、取消、失代与卸载重载。记录实际重复键、碰撞调用、构建耗时和新增驻留，原已有缓存命中也计入基线。若真实输入中几乎没有重复键，或原缓存已覆盖主要重复，就停止；不由“Super 少量样本”扩成重写整个父图的理由。

## 第三顺位：细粒度依赖与局部失效

原额外方向第 6 项仍有研究价值，但本轮不宜升为第一主线。

新研究应是“内部产物与具体边界带分别记录依赖，输入只改变无关部分时复用已证明未变的数据”，而不是另一会话正在处理的邻区键去分配、事件去重和临界区优化。现有代码已经有 requiresHalo、haloUsed、发布 stamps 和 Boundary 代次，不能把已有能力描述为完全没有。

此前前置数据证实 Super 的 64 demand 共临时 pin 1,728 次、发布 stamps 960，存在 768 次 pin 不在最终发布集合；这只是缩减临时引用的机会，不能推成可以省掉 768 个恢复等待或安全缩窄发布依赖。

本轮大量工作来自传送和真实加载/卸载，必要的 loadIdentity 变化仍须使旧 lease 失效；单纯细化 revision 无法消掉这些必要工作。第一步只应检查“边界实际未变却导致多少重算”，并在局部编辑负载下验证。比例低就不推进。即使 payload 可复用，也应以新代次正确绑定，不能只改旧 stamp 让失效对象继续被消费。

## 暂缓与封存

| 剩余方向 | 当前决定 | 理由 |
| --- | --- | --- |
| 当前完整 Base 后的源坐标 Portal 骨架 | 维持封存 | 已证明选择器与旧 bbox 等价，不能新增依赖剪枝；稀疏枚举稳定收益仍未定 |
| 首请求冷 Super 改 Base 策略 | 保留原部分证据，暂不续测 | 确实减少构图，但部分矩阵中止、详细首段工作量不同，尚无稳定收益资格；本轮新采样不能补齐旧矩阵 |
| Base 之前从事实生成、包含目标几何的骨架 | 暂缓 | 与封存候选不同且未被证伪，但需要新的连通与完整性设计；先判断中间产物能省多少实际工作 |
| section 内 tile/高度层 | 后置 | 首次访问工作量可能减少，但 border、额外边界、索引和失效成本尚无对照；不直接跳到复杂空间重构 |
| 完整 Boundary/Super 跨体型共享 | 后置 | 首版生产只支持有限 section 内 GROUND；通用能力单调性曾有反例，先做上面同 GeometryKey 的窄范围复用 |
| 派生持久化、分段恢复格式、世界运行期间烘焙 | 保持暂停 | 用户此前暂不考虑持久化；当前生产事实解码的热点由另一会话处理，不据此恢复旧异步原型 |
| 换搜索引擎、扩大线程、JVM/CDS、预算历史估计 | 不列为下一项 | 现有资料不足以把它们列为本轮有依据的新主线；不扩展用户已停止的支线 |

当前两项优先候选均未实现，也未送新审核。本轮交付是技术选择与最小验证范围。建议下一轮先选择中间产物分层复用，用小范围原型尽快回答“可复用多少、结果是否相同、总成本是否下降”；未过这个门就进入边界连接复用，不再以大矩阵或准备工作代替候选验证。

## 证据

- 本轮离线复算：`.tmp/remaining-topology-directions-20261015/evidence-v1.json`，原始输入、复算来源哈希、线程分母与限制均保留。
- 新实服分析：`.tmp/production-hotspots-20261015/`，只读复用，未修改其产物。
- 历史闭包：[候选验证报告](2026-10-15-first-request-closure-validation-zh.md)；历史 Portal：[骨架验证报告](2026-10-15-portal-boundary-skeleton-validation-zh.md)，原封存不变。
- 已接入边界：[共享拓扑补充记录](2026-10-15-shared-topology-supplement-validation-zh.md)及 `SharedSectionCandidate.supports`；当前生产事实与行共享不列为新成果。
- 关键源码：`BaseClusterTopology.deriveLegalAnchors/BuildInput.volumeOpen`、`SectionGeometry.occupied`、`SuperClusterTopology.boundaryLinks/addDirectedBoundary`、`TopologyWorkerRuntime.beginBuild/markLatestDerivedStale`。
