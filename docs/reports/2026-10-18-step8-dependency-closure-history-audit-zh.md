# 第8步依赖闭包历史与当前实现核查

日期：2026-10-18。范围：按用户要求核对历史研究、结构化结果与当前源码。
本轮没有修改生产代码、启动新的游戏或性能实验、重开旧矩阵或取得新的算法审核。

## 结论

**依赖闭包已经做过前置测量、候选实现、功能验证及部分性能实测，
不能把第8步表述为尚未研究。** 当前准确状态是：旧闭包策略未接入，
旧源端骨架停止；后来不同构型的两侧端口位图已经接入，但没有消除冷 Super
的完整八子图依赖。当前版本是否仍以可避免的冷依赖成本为主，尚缺专门归因。

因此本轮先完成历史及源码核查，不直接恢复旧闭包策略或再实现旧骨架，
也不按旧矩阵追加样本。第8步整体性能和发行资格仍未取得。

## 已经研究过什么

| 日期与研究 | 实际做法和证据 | 结论与接入状态 |
| --- | --- | --- |
| 10月15日前置测量 | 60个独立JVM、13,842个逻辑请求；事实暖、精确体型派生图冷；比较同section、短Base、长路线自动Super和同长路线强制Base | 长路线自动构建64 Base/8 Super，强制Base为14/0；支持研究结构差异，不是优化已通过 |
| 10月15日首次请求闭包候选 | 冷父图且未发布首条真实Path时先用现有Base前沿；热父图保留Super；有界后备及取消、版本、关闭控制 | 28个功能JVM及82条worker断言通过；性能135轮计划实际运行104轮，103轮检查通过、1失败、31未跑；候选性能实际35个JVM，未取得性能资格，未接入 |
| 10月15日Portal/Boundary源锚点骨架 | 完整Base生成后提取全部合法源锚点和组件14邻槽mask，并改为稀疏边界枚举 | 源端mask与旧包围盒规则重叠，不能新增依赖剪枝；27轮性能计划11完成、1失败、15未跑，停止并封存，未接入 |
| 10月17日最终worker组合及接入 | 两侧合法锚点位图按位移匹配，并按当前组件标签筛选；配合碰撞非空行索引等局部算法 | 已接入当前生产源码。可在两侧Base已捕获时省部分BASE_BOUNDARY依赖；邻居Base未知时仍须请求BASE_CLUSTER |

原始入口：
[前置报告](2026-10-15-navmesh-prerequisites-validation-zh.md)、
[闭包候选报告](2026-10-15-first-request-closure-validation-zh.md)、
[源锚点骨架报告](2026-10-15-portal-boundary-skeleton-validation-zh.md)、
[最终组合](2026-10-17-final-worker-algorithm-combination-zh.md)、
[生产接入](2026-10-17-worker-algorithm-production-integration-zh.md)。

### 闭包候选确实减少了什么

前置汇总中，同长路线单请求三次独立JVM均为：

| 指标 | 自动Super | 强制既有Base |
| --- | --- | --- |
| 新建Base / Super | 64 / 8 | 14 / 0 |
| 未被宏观直接展开的Base | 59 | 9 |
| 单请求依赖等待并集，ms | 140.755 / 177.804 / 181.097 | 47.980 / 53.377 / 48.699 |
| 实际Path装入p50，三JVM中位数，ms | 425.632 | 218.059 |

这证明旧实现存在可改变的依赖结构及该场景的等待差异，但不证明当前版本同样耗时，
也不证明稳定端到端收益。**59个未展开Base仍参与Super聚合，不是59个无用图。**
前置审计没有发现同时缺少展开、捕获及聚合用途的目标体型已构建Base。
要少构建这些图，实际改变的是首次请求采用的宏观表示或策略，不是删除已证实无消费者的工作。

闭包性能阶段还观察到首段详细目标从x16改为x32，首段真实原生搜索工作也随之改变。
完成的平地512/detail对照里，Base构建和Boundary工作下降，
首Path前原生搜索累计wall却从约173/168ms变为281/290ms。
这些阶段有嵌套、并发及暂停影响，不能相加为关键路径或精确CPU；
同样不能把64到14直接换算成总成本下降比例。

### 两轮中止原因不能混淆

- 闭包矩阵第104轮失败在auto基线首次派发前：512 enqueue，但dispatch、
  物理宏观查询及原生搜索均为0；连续约200tick记录SERVER_NO_TIME，仍有导航余额。
  与既有规划TTL出口一致，但未区分两个haveTime谓词的来源，不是候选搜索失败。
- 骨架矩阵第12轮失败在closure开启、骨架关闭的C臂首Path之后：
  512个请求均已有首Path，清理前2个UNAVAILABLE、510个REACHABLE。
  plain缺少精确失败出口及谓词证据，原因未定，不能归因Portal。

两轮都未通过完整性能门，不能写为已通过；基线中止也不能写为候选算法已证伪。
旧DeepSeek共识认可按门停止、封存和结论边界，没有批准生产性能。
后来的调度和worker接入不能自动证明这两次旧失败已经修复。

### 旧源端mask为何不值得重做

旧骨架仅取全部源cell坐标和能力阈值，没有加入目标合法锚点、支撑或碰撞信息。
在当时GROUND同y组件和VOLUME直邻规则下，它与旧包围盒谓词等价：
84组编译比较中，旧允许的20,456次判断，新增剪枝为0；
独立算术补证覆盖3,440,640次决定、114,688个mask。

稀疏Boundary枚举曾有局部改善，但另加完整Base后的提取成本，未证明净收益。
此结论只淘汰该源端选择器，不能推广为所有骨架或位运算方案无效。
10月17日接入的两侧位图加入了目标合法位置，因此不是旧选择器的重复包装；
它仍不能以端点匹配直接证明移动过程可通。

## 当前源码实际做到哪里

本轮逐项读取生产调用链，并将八份worker算法源码按CRLF归一化后，
与接入时`source-identity.json`的冻结SHA256比较，**8/8一致**。

| 当前实现 | 源码位置与含义 |
| --- | --- |
| Base按搜索前沿请求 | `TopologyWorkerRuntime.expandBoundary`：先mayExit；缺邻居时请求BASE_CLUSTER；两侧已捕获且缺Boundary时才调用mayLink。正反扩展均有对应筛选 |
| 两侧位图只省后续Boundary | `BoundaryPortBits.mayLink`读取两份Base的合法锚点行和组件标签；没有Base前可用的独立摘要 |
| 依赖队列已有上限与选择 | `MacroSearch.pendingDependencies`按原优先规则取前K；runtime最多16个live依赖。这限制同时请求量，不等于整条路线最多构建16张图 |
| 自动选择Super仍不考虑父图冷热 | `joinResolved`按父区距离及世界可用性选择分层；`shouldUseSuperGraph`阈值为2个父区单位。没有旧候选的coldFirstPath/firstPathPublished策略字段 |
| 一个冷Super仍完整请求八子图 | `beginSuperRequest`遍历2x2x2的八个child；`completeSuperChildren`等待全部完成，再聚合 |
| Super跨父区仍可能扩展完整父图依赖 | `expandParentBoundary`先检查14槽潜在出口；缺邻父图则请求SUPER_CLUSTER，缺连接则请求SUPER_BOUNDARY |
| 事实引用范围与发布依赖不是一回事 | `beginBuild`临时hold中心和可用邻区；`buildInput`捕获邻区事实，build stamps按requiresHalo选取。发现引用范围更宽，不能直接删减而不证明几何和生命周期等价 |

源码：
[Super子图请求](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L1408)、
[分层选择](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L4247)、
[依赖获取](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L6158)、
[Super扩展](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L6795)、
[Base扩展](../../src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java#L7433)、
[两侧位图](../../src/main/java/com/scarasol/acceleratednavigation/topology/BoundaryPortBits.java#L67)。

## 当前证据能否支持立即再做闭包

**尚不能支持直接重开旧方案或开始新实现。** 结构上的八子图屏障仍在，
但缺少带当前版本身份、按长路线冷暖划分的关键路径和可避免成本账本。

10月18日[Spark/JFR分析](2026-10-18-spark-runtime-state-and-snapshot-analysis-zh.md)
提供了当前剩余热点线索：858个worker模组活动wall样本里，
runtime约52.9%、Base约20.2%、Boundary约12.1%、Super构建约0.23%。
这些不是CPU分账，也没有按冷长路线隔离。Super构建自身样本少，
不等于其触发的Base、Boundary、引用维护和等待少；runtime占比高，
也不等于这些成本都由过宽闭包造成。该运行没有完整历史Jar/class身份认证，
不能拿它直接替代当前源码的冻结实验。

步骤7的最新F/E资源对仅覆盖8/28格场景，并非本次冷Super闭包对照；
worker接入的13个平地交接场景也不能提供该成本分账。

后续若继续第8步，最小新取证应只针对当前代码的冷长路线及对应温热重复，
先记录同一物理宏观查询实际请求、构建、捕获、展开、聚合和最终走廊的对象身份，
以及首Path前后、请求结束后的剩余构建与维护。Boundary任务、唯一图、
逻辑请求和canonical共享flight必须分别计数；阶段按真实等待并集及独占成本归因。
已有公开探针包含`super304`路线，可复用场景，但它本身不具备完整闭包归因字段。
工具须按当前调用链适配并冻结，不能直接运行旧插桩生产副本充当当前基线。

只有证明当前仍有足够可避免工作，再选择窄候选：
延后完整父图、Base前的保守边界摘要，或临时事实引用减量。
这三项均未在本轮实现或获准接入。若重新申请旧closure策略性能资格，
还须先处理其已有基线失败的精确出口和谓词证据；不续135/27旧矩阵。
不借本核查调整快照语义、haveTime、TTL、预算、线程数或缓存容量。

## 本轮核验记录

- 重新读取前置`summary-v1.json`与`final-audit-v1.json`：
  60 JVM、13,842请求；当前汇总SHA256仍为
  `B9DB2D6155150684295102978A41A2DFEDCFE599700CD657480495B50B91B010`，
  与旧审计绑定值一致。旧审计记录344,842项检查，本轮未重跑这些检查。
- 重新读取闭包`performance-abort-v2.json`及review seal，
  确认103完成/1失败/31未跑、候选35 JVM及performanceQualified=false。
- 重新读取骨架`performance-result-v1.json`、`mask-domain-equivalence-v1.json`
  及DeepSeek终审原文，确认11完成/1失败/15未跑、算术补证范围和封存决定。
- 重新读取worker接入`source-identity.json`、`live-checks.json`，
  当前八份源码归一化哈希全部匹配；原四路线门仍为false，不能写为已通过。
- 未重跑上述JVM、功能或性能检查；未向DeepSeek发送新包。
  `.tmp`原件未修改。本次只新增核查报告并同步工作索引，
  旧seal继续表示各轮封存时的文档和源码快照，不代表本次文档新增也已经旧seal审核。
