# T1 完整诊断与覆盖结清

日期：2026-10-17。状态：T1按原计划允许的受控AI口径完成。核心时间线、成本链、入口及请求结构均有见证；14项覆盖检查通过。生产算法未修改，T2修复、T3优化和净性能准入尚未执行。

首轮结论和原始口径见 [启动与准备诊断](2026-10-17-navigation-startup-and-preparation-diagnosis-zh.md)。本次补测记录在 `.tmp/navigation-t1-completion-20261017`，固定工具仍在 `tools/navigation-diagnostics`。

## 完成矩阵

| 原T1要求 | 对应证据 | 结论与边界 |
| --- | --- | --- |
| 提交到首次调度、宏观、窗口、搜索、发布、首次移动 | 首轮single及关闭深插桩对照；本轮逐请求steps | 温热短程95–99ms主要是主线程已交付结果后的调度等待 |
| 段结束、目的组件进入、稳定落地、下段准备、恢复移动 | 本轮observations、physical返回值、handoffs | 正常推进、重试、重规划分开；END观察是采样边界 |
| PathFinder与外围包装分开 | NativeNavigationSearch、normalize、能力capture/apply、proof、publish及primitive独占账本 | 不重复累加嵌套包络，不将wall当CPU |
| 窗口、宏观结果、事实、维护、执行观察 | 首轮burstLedger、Base/Super及快照身份；本轮动态改块和JFR | 当前高价值减量仍是最终窗口和就绪接续 |
| 正常分段、partial、候选重选、缩段、刷新 | 单实体/突发旧失败；本轮受控失速 | 真正触发原生停滞、attempt=2、stride=1、refresh=1及partial |
| 实体目标漂移 | entity-drift | 同一queryId下3次漂移、3次replan，最终到达 |
| 坐标、实体、查询采用、多目标、POI入口 | 首轮坐标；本轮自然GoalSelector、Brain、AcquirePoi及受控API | 各入口独立给分母，不用人工场景数量推断实服权重 |
| 共享资格通过/拒绝与原因 | sharedParameters实参及supports真实返回值交叉核验 | 同时不满足的条件保留；校验调用数不冒充请求数 |
| Base/Boundary/Super实际工作、flight合并、worker队列 | 首轮single/burst/独立Super，区分实际build与任务kind | 沿用首轮核实结果；不把所有BUILD任务叫Base构建 |
| 诊断有效性及未知项 | 进程身份、完整tick、干扰、关服清理、自检、JFR、文件封存 | 诊断完成不等于性能PASS，后续根因和适用范围明确登记 |

## 本轮样本与修正

3个独立JVM技术有效：`brain-poi-v4`有2场，`entries-v4`有10场，`legacy-v5`有1场。其中旧legacy cell的到达目标归属错误，标记`arrivalScopeValid=false`并排除，由独立复测替代；完成矩阵共12个可用场景，每场一个执行主体。原始13场全部保留。

另有3个准备阶段技术无效run：`coverage-v1`、`brain-poi-v2`、`brain-poi-v3`。旧壳禁用了NPC，补测早期又错误使用NoAI阻止自然落地；均已修正。coverage仅在新的运行副本中允许NPC，保留自然物理tick，提交时启动测试行为；实际server.properties哈希随launch封存。原冻结配置未改。

服务端会重写配置，启动输入和结束文件分别核验。本轮启动文本由原冻结配置作已声明的NPC开关替换后重建，与三份既有launch哈希逐字节一致；重建文件独立保存，结束配置原件不修改。工具后续运行直接保存启动副本。

所有有效Java负载串行，启动及sceneReady后分别真实等待至少30秒；无其他Java进程干扰。三份JFR在服务端测量全部结束后串行提取，无DataLoss。关服后导航记录和scheduler pending均为0。

| 场景 | 实际业务/执行结果 | PathFinder调用 | recover / refresh / replan |
| --- | --- | --- | --- |
| entity-stationary | 到达 | 3 | 1 / 0 / 0 |
| entity-drift | 到达，3次目标漂移 | 9 | 1 / 0 / 3 |
| ai-goalselector | 自然MeleeAttackGoal到达 | 3 | 1 / 0 / 0 |
| brain-walk-target | 曾进入准确目标格，随后行为STOP取消清理 | 1 | 0 / 0 / 0 |
| query-only-multi | 三目标完整查询可达，未采用、未移动 | 1 | 0 / 0 / 0 |
| query-ready-adopt-multi | 就绪后采用并到达 | 3 | 0 / 0 / 0 |
| query-pending-adopt-multi | 立即采用并到达 | 2 | 0 / 0 / 0 |
| shared-accuracy0 | 同section共享控制到达 | 2 | 0 / 0 / 0 |
| legacy-createpath-move | 原生选中近目标，实际到达 | 1 | 0 / 0 / 0 |
| poi-acquire | 两个真实POI候选，原业务选中有效票据；无移动 | 1 | 0 / 0 / 0 |
| dynamic-block | 发布后设置9个障碍块，恢复后到达 | 3 | 1 / 0 / 0 |
| native-stuck-recovery | 受控失速后解除，最终到达；有1次真实partial | 8 | 6 / 1 / 1 |

受控失速仅改变测试Husk的travel以固定位置，生产导航器仍真实运行stuck/timeout；没有直接调用nativeStopped、recover、refresh或改Work。6次原生停滞通知后已覆盖重选、缩段及刷新，看到refresh后解除固定。这类约43秒的活动窗不是正常实服失速分布。

## 入口与采用的结论

自然GoalSelector的17次move调用只形成2个句柄：accuracy=0旧句柄在同tick内采用后取消，accuracy=1新句柄执行到达；没有nativeDecision搜索。逐tick观察只看到新句柄，最终分析已通过同导航器prepare返回身份找回旧句柄，并按准备时间排序。不能把17次move当作17次新请求。

真实Brain的MoveToTargetSink采用accuracy=0句柄，实际进入目标格后行为STOP清理，最终句柄状态CANCELLED。业务到达和句柄终态分别保存，不将这类取消自动归成寻路失败。

真实AcquirePoi经过beforeQuery、异步完整目标集裁决、validSelection及原业务恢复，选中`(-140,81,-159)`的FISHERMAN票据。1次真实搜索，没有adopt或移动；不能把POI成功等同于物理到达。

三目标受控API验证了采用时机的区别：

- 只查询：1次完整目标集搜索。
- 就绪后采用：1次查询裁决，加上2次真实分段搜索，合计3次；首执行Path约150.109ms。
- 立即采用：初始化前已采用，跳过查询裁决，2次分段搜索；首执行Path约101.503ms。

这证明重复工作在就绪后采用入口确实存在，也证明它不是所有AI查询的统一成本。以上是同诊断壳的机制样本，尚未证明净收益。已采用句柄后来进入REACHABLE是段结果，分析器不再把该时刻叫采用前查询就绪。

外部同步createPath→moveTo(Path)走legacy/foreign路径，未创建服务管理的查询句柄。原生在两个合法候选中选择近目标，修正后的独立复测按Path.getTarget观察，实际到达。旧cell的EXHAUSTED是探针目标归属错误，不是生产回退证据。

共享控制只有1个逻辑请求，但触发2次supports检查，两次通过。其余检查中，9次同时不满足accuracy和section条件，3次仅不满足section条件；这些是按实参重建的重叠阻断条件，且与实际布尔返回值一致，不是实服入口比例。查询裁决、POI和legacy未进入宏观共享资格，不能算作共享拒绝。

## 成本链补全

下表复用首轮两个有效512突发的全部选定阶段，单位为“独占wall合计ms / 独占分配MiB”。机器表在`summary/coverage-summary.json`的`burstLedger`；各阶段细项仍可回到原始事件。

| 阶段 | 8×512 | 28×512 |
| --- | --- | --- |
| PathFinder，836/1116次真实搜索 | 63.573 / 12.606 | 151.669 / 39.277 |
| NativeNavigationSearch.find外围，已扣选定嵌套阶段 | 7.050 / 0.559 | 8.595 / 0.823 |
| normalize | 0.404 / 0.028 | 0.266 / 0.033 |
| 能力capture/apply | 6.463 / 4.461 | 4.975 / 5.601 |
| NativeQueryProof.capture | 0.756 / 1.300 | 0.915 / 2.818 |
| publish/Path安装包络 | 1.935 / 0.327 | 2.488 / 0.704 |
| physical检查 | 1.581 / 0.943 | 2.343 / 1.278 |
| observe主体，扣选定嵌套阶段 | 11.755 / 1.791 | 31.687 / 5.630 |
| 宏观组件快照captureComponents | 0.752 / 0.037 | 0.867 / 0.047 |
| GroundSegmentWindow各选定阶段合计 | 20.338 / 56.374 | 27.870 / 56.019 |
| scanSection / runOneRecovery | 0 / 0 | 0 / 0 |

find有嵌套重载，方法进入次数不能当真实搜索次数。window合计含最终、中间及枚举等阶段；约50.1MiB的最终窗口主体是其中可单独定位的子集。worker并行wall合计不等于端到端时延。分配仍含诊断、初始化等影响，不将上述值称为生产精确净成本。

本轮动态改块补到了真实维护：活动窗内1次scanSection，独占wall约1.027ms、分配31768字节，随后原生停滞和恢复到达。这说明动态事实链有实际见证，但一个小改块场景不能证明大规模事实扫描不重要。

本轮CoverageScenarioProbe.observe/track/action自身另列diagnosticProbe，例如漂移场景主线程已观测分配约5.49MiB。不得把这些观察成本算成生产框架固有开销。首次路径有约19MiB级find外围包络，JFR完整堆栈显示大量Mixin/ASM类转换；prepareAllowed名下的部分大样本也属于首次类加载，已分类为classLoadingAndTransform，不能据调用者名声称稳定反射热点。

## 段间时间线

正常多目标推进的例子：立即采用场景约5200.105ms首次观察到目的组件进入，5400.760ms观察到旧Path已结束，5450.129ms开始下次真实搜索，5499.997ms观察到下一发布后的移动。已结束到下次搜索约49.370ms；组件进入到发布约250.647ms还包含旧Path末节点行走，不能全叫空等。

实体追踪另有值得后续回查的现象：静止目标和自然Melee样本均已记录目的组件进入，之后约3.35秒才通过原生停滞、恢复及重发继续；漂移路线末段对应约8.35秒。旧Path并未被观察为正常结束。仅据此可定位到实体执行/换段边界，不能宣称这些秒数是worker排队或详细搜索，更不能在T1直接判定根因。

此发现补充T2/T4的控制场景。优化仍须保留目的组件证明、落地和原生执行语义，不能直接删掉等待检查。

## 结论与下一步

T1已形成完整的受控入口覆盖、关键路径、成本排序、失败阶段与未知项清单。优先顺序保持：T2静态短程partial及换段控制 → T3a最终窗口减量 → T3b就绪接续 → 高频DEBUG详情减量；T6按采用时机和实际入口分类，不能统一假设重复完整搜索。

首轮墙体/门口仍是“真实最终详细搜索partial→EXHAUSTED”。range=35到实际16的差异尚未做参数差分，根因与修复归T2。实服请求权重需要实服遥测，本轮采用原计划允许的受控AI，不给虚构的生产占比。复杂混合体型、正式C/B和C/N、稳定尾部及净提速属于后续验证。

最终分析在`analysis-final-v2/`，此前分析版本保留。8项Node测试、Java正常/异常/容量不足自检、14项覆盖检查均通过；三份JFR无DataLoss，未记录到默认10ms阈值以上的主线程JavaMonitorEnter，不据此排除短锁。生产61份src/main文件与原冻结候选一致，实际生产Jar仍为`a4f03abd7ecde578bd4a88d71cea59b4453d9d420899d91e98937f4b74089c33`。

`evidence-seal.json`关联本轮原始记录、有效/无效分类、排除cell、构建源码、最终工具快照及文档；首轮206份封存文件通过原件或匹配哈希的旧文本副本重新核验。没有提交、推送或修改默认旧发行包。
