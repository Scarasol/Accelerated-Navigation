# 步骤7：共享路径切段修复与入口扩展续验

日期：2026-10-18。用户授权“修复这个问题，然后继续推进”。本轮续接此前
`.tmp/shared-entry-fix-20261017-v1/`，保留其创建日期和全部原件。

本轮将两项决定分开：F只修复原共享路径多切一段，E另验证accuracy=1入口扩展。
不能因E不利就撤掉已证明的F修复，也不能将F成立当作E通过。

## 最终状态与恢复边界

2026-10-18用户离开期间的步骤7续验已有限结清，详见
[完整报告](../../reports/2026-10-18-shared-stage-fix-zh.md)。
当前生产**Fv3**，62份输入仍匹配`fix-source-v3`；原worker未提交改动保留。
E保持`expanded-source-v3`隔离、停止扩样；直接精确Base分量候选L只做价值评估，
本轮不实现。HEAD仍为`4b82ed6`，没有提交、推送、部署或自动进入8。

- F/E各17控制通过：各14严格正例、2取消、range4预期EXHAUSTED。
  F普通accuracy0搜索2→1；低预算失败合并不发布、旧4+4到达，共3次；
  小漂移0保留两段并搜索实际新目标。
- 跨section查询B/F均1/3/2；同section查询B1/3/2→F1/2/1。
  完整三目标裁决、采用时机、严格到达、未采用不移动及关闭核验通过。
  v1检查器错误假设的失败保留，v2纠正分析；新夹具使用独立同工具cohort。
- 唯一F→E burst结束：含预热共2304/2304到达。8格P95观测-19.80%，
  但分配+20.31%、CPU仅-1.81%；28格无共享命中，其不利CPU/P95仅作控制观察。
  单对没有稳定或因果性能资格，不能用F/E比较代替F/B性能。
- DeepSeek F终审`ACCEPT_SCOPED`；E首轮`REQUEST_CHANGES`的两项疑问经实际源码
  补证后明确撤回，复核`ACCEPT_SCOPED`认可隔离停止。L价值审查同意本轮不实现。
  三项最终共识必改均无，4个进程均退出0、未超时、配置未改；原拒绝保留。

证据根仍为`.tmp/shared-entry-fix-20261017-v1/`，
最终入口`decision-step7-v3.json`、`final-seal-v3.json`。
不要重跑下面历史命令或过时`seal-fix.mjs`（指向Fv2）。
未来重开先取得真实入口权重及可避免准备/物化成本证据，另冻结新窄协议。
F的accuracy0发布后动态/完整恢复与正式性能，E的归一化+nativeStopped及飞行中取消
等条件继续保留；原楼梯缺口独立，不将本轮标为步骤7整体生产性能完成。

## 续验恢复过程（历史）

2026-10-18磁盘阻塞补充：`controls-baseline-v2`在首次`TIMELINE_SUBMIT`之前，
由运行器磁盘保护终止（exitCode=-1/timedOut=true，约46秒，无外部Java）。
不是路线失败，原目录及退出记录保留。随后按用户授权清理成熟旧数据，
D盘可用空间恢复至约20.87 GiB；当前修复根完整保留，源码/证据哈希与依赖后核通过。
详见[清理记录](../../reports/2026-10-18-mature-data-cleanup-zh.md)。
清理后使用**新目录`controls-baseline-v3`**完成B控制，两个场景均技术有效、
SUCCEEDED且真实accuracy0到达，各2次真实搜索，`functional-verification.json`通过；
构建仍为`build-baseline-controls-v2`，原v2磁盘中止目录不覆盖。
续验身份后核为每套62份生产输入，39份工具及10份参考源码，共49份测量输入不变；
三个测量Jar逐项内容一致（helper5、agent79、probe42项）。
Fv3的17控制已完成，公共及功能检查全部通过：14个正例严格到达、2个预期取消、
range4预期EXHAUSTED。普通accuracy0为1次真实搜索；低预算0先拒绝合并且不发布，
再按旧4+4两段到达，共3次搜索；终点轻微漂移0保留两段，最后搜索实际新目标。
E的17控制也已完成，公共及功能检查通过；accuracy1冷暖均1次搜索，
邻域可达/超时/动态改块回原宏观已实际观察。
查询初轮B运行技术有效，但v1检查器错误地把原跨section场景当共享，并按目标数
分类搜索；原失败保留。使用`protocol-query-adoption-v2.md`和新v2检查器纠正分析，
另在独立同工具B/F cohort补3个真正同section的多目标采用夹具。
跨section B/F均为1/3/2次搜索，已采用者严格到达；新同section B/F为1/3/2→1/2/1，
两者均保留完整三目标裁决与实际采用时序，所有已采用移动严格到达。
新cohort的39工具+10参考及Jar条目内容B/F一致，只有两个工具文件相对原cohort变化。
原17控制和预定burst仍用原controls-v2包，不跨cohort比较资源。
唯一F→E burst随后完成，比较器按`protocol-burst-amendment-v3.md`记录已有
宏观任务/缓存账目，以区别减少工作与转移成本。最终审核与取舍见顶部。
用户随后授权离开期间持续推进步骤7，
按实际数据与DeepSeek共识自主决策，遇难解问题或明确缺乏继续探索价值时留档停止；
不由此自动进入8、提交或推送。

当前生产是**Fv3**，62份源码与`fix-source-v3`一致，仅两份相对B有变化。
v1的range等号和v2的低访问预算都已被实际PathFinder反例推翻，原件保留。
Fv3使用严格`<`，合并失败不发布partial，设置Work内永久的`sharedMergeRejected`，
按原scheduler重新排原4锚点段；保留12次真实分段搜索上限，尝试合并前预留旧段数加一次
失败的槽位；实际终点必须仍匹配末锚点，否则直接保留旧段。未承诺每种未来恢复序列等价。
生产Gradle/Core/worker/build/check33秒通过，永久**99,951检查/41路线/643组件归属**，
含47次真实原生搜索循环。E同步此修复但仍在`expanded-source-v3`隔离。

首次DeepSeek超时；Fv2审核为ACCEPT_WITH_GATES/193.341秒/必须修改项无，
随后发现低预算反例，因此不能转移为Fv3批准。
当时草案`review-fix-packet-v3.txt`已过时；本轮实际发送为`review-fix-final-v3.txt`。

原客户端退出后，Gradle状态确认32176为IDLE，已用正常`gradlew --stop`停止空闲daemon，
未强停用户客户端或忙碌构建。随后`controls-baseline-v1`发生短暂外部Java21784，
整轮技术无效，见`characterization.json`；低预算0实际SUCCEEDED/2搜索，
漂移0两条原生Path均可达却EXHAUSTED，目标实体中心阻挡跟随者进入精确目标方块。
这个功能结果独立保留，不因外部Java而抹去。新夹具只将目标中心从同一目标块内+.5
改为+.9，方块目标、accuracy0/range35等输入不变，详见`protocol-controls-v2.md`。

以下是续验开始时的历史安排，现已完成，不再据此派发运行。
当时测量探针revision6；续验专用全源码构建为：
`build-baseline-controls-v2`、`build-fix-controls-v2`、`build-expanded-controls-v2`。
**不要再用下面历史v1/v2发行包。** 三套构建完成后执行`freeze-controls-v2.mjs`，
冻结39份工具与10份参考源码（共49份测量输入）及实际Jar，然后严格串行：

1. B：`controls-baseline-v2`，仅`shared-low-budget0,shared-anchor-drift0`，确认原流程可达。
2. F：`functional-fix-v3`，下面17个场景。
3. E：`functional-expanded-v3`，相同17个场景。
4. 只有功能门通过才运行预定唯一F→E burst轻量对；最终DeepSeek审核实际Fv3代码和结果。

每个运行后先`analyze.mjs RUN`，再`verify-functional.mjs RUN`（独占创建，勿重跑同目录）。
外部Java/真正功能失败如实保留；不要不变扩样选数据。最终不提交推送部署，不进8。

## 前序证据

- B为上一轮worker接入后的62份生产输入，F/E分别冻结完整源码并构建发行包。
  三套构建39份测量工具和reference sources相同，见`input-freeze.json`及各
  `build-*/build-identity.json`。
- 当前Fv2新增一次计算的平地后缀索引；仅当剩余路径每步同Y、水平相邻且步数严格小于
  原range时直接选择终点。其余保留每4锚点切段。共享资格、真实Path验证、
  最终目标和原accuracy/range/regionOffset/能力不变。
- 首版F/E各151,979项隔离断言、217条实际夹具共享路线、3,653个普通Base组件
  归属证明通过；各16,800项资格oracle通过。F的Core、macro-commit、
  boundary-recovery、Door回归及相关Node17项通过。
- 原生产B的`original-accuracy0-v1`发行复现技术有效：8格路线SUCCEEDED且真实
  accuracy=0到达，实际搜索2次、共享段2次，先到第4格再到第8格，末段range=35。
  无整条原生回退，关服records/active/pending归零。
- Fv2两份源码已接入当前生产，62份输入归一化后与`fix-source-v2`逐项相同；
  原worker未提交改动保留。新增不依赖临时城市数据的永久
  `SharedStageContracts`及Gradle `verifySharedStages`，纳入`check`。
- 修正版实际生产Gradle `verifySharedStages`通过91,134项检查、41条夹具路线和643个
  组件归属检查；Core、worker七组契约及完整`build/check`成功，33秒。该编译/契约
  检查不采集资源性能结论；当时外部用户客户端仍在运行。

重要纠正：F/E v1的`<= range`及其同边界oracle漏掉原生严格range。
冻结Fv1类直接调用实际PathFinder证明：8步/range8合并不可达，旧4+4两段均可达。
Fv2改为`<`，永久测试新增距离5..15的44次真实原生循环/66断言，原反例保留在
`native-range-repro-v1`。首版检查不作为原生边界证明。完整v2输入、包和修正协议为
`input-freeze-v2.json`、`build-fix-v2`、`build-expanded-v2`、`protocol-v2.md`。
39份测量工具及reference sources与B保持相同。旧v1源码/包不覆盖。

DeepSeek v1调用902.476秒超时，进程已收取，配置未改，没有最终判词或批准；
针对实际Fv2和新原生回归另行发送v2包。

## 待结清

发行功能对照采用冻结的15场景，随后仅在功能通过时运行预定F→E burst轻量对。
`functional-fix-v1`首次启动被外部IDE Gradle/Minecraft客户端占用门拦住，
等待10分钟后退出1，服务器尚未启动，见`runtime-block-v1.json`。
尚不能当作发行修复验证或性能结果。没有终止用户进程，也不放宽并行负载门。
日志与目录保留；恢复时使用新目录，不覆盖失败原件。

恢复入口：

```powershell
& tools/navigation-diagnostics/run.ps1 `
  -Build .tmp/shared-entry-fix-20261017-v1/build-fix-controls-v2 `
  -Output .tmp/shared-entry-fix-20261017-v1/functional-fix-v3 `
  -Suite coverage `
  -Scene 'shared-cold1,shared-warm1,shared-accuracy0,shared-boundary1,shared-neighbor1,shared-obstacle1,shared-drift1,shared-cancel1,shared-replace1,shared-range4,shared-range12,shared-dynamic1,shared-pending-cancel1,shared-pending-replace1,shared-timeout1,shared-low-budget0,shared-anchor-drift0' `
  -NoTrace
```

随后对E使用`build-expanded-controls-v2`和`functional-expanded-v3`。依次运行本证据根的
`analyze.mjs RUN`、`verify-functional.mjs RUN`；其结果文件采用独占创建，
同目录不要重复执行。功能失败是结果，不能改记技术无效后挑样。
先检查正例SUCCEEDED及`intentAccuracyArrivalNanos>0`、旧代取消、真实搜索输入、
generic错误与动态回退、无整条原生回退及关闭清理。pending取消/替换场景是
初始化前操作，不夸大为共享flight阻塞中取消。

E仍只在隔离副本，不接入生产。CPU剩余差额尚未归因，Java存活线程CPU之和
不能冒充全进程CPU或准确JIT/GC分账。原楼梯缺口独立保留。不提交、推送、
部署或自动进入步骤8。
