# 首次请求依赖闭包候选验证

日期：2026-10-15。**候选已经实际进入验证，但本轮未取得性能资格。** 冻结的135轮性能矩阵在第104个JVM触发停止门：103轮运行检查通过、1轮失败、31轮未执行。失败发生于auto基线首次派发之前；不能登记候选通过，也不能据此判定候选算法失败。DeepSeek已认可失败定位的边界与中止处理，必须修改项无。Portal/Boundary骨架尚未实现，不进入其验证或生产接入。

以上是本报告对应旧矩阵结束时的状态。用户后续明确授权的 Portal/Boundary 已另立[独立验证方案](../designs/2026-10-15-portal-boundary-skeleton-validation-plan-zh.md)及[恢复记录](../agent-process/tasks/portal-boundary-skeleton-20261015.md)，不续本轮旧矩阵、不改变本文失败或性能资格。

## 本轮实际改动

原型位于`.tmp/first-request-closure-20261015/`，没有将本轮新候选或探针放进正式生产源和正式test包。候选只对原本应走Super、当前端点父图冷且本请求尚未发布首条真实Path的情况，先使用现有Base搜索前沿；有效热父图仍用Super。第一条真实Path形成后，后续新宏观查询恢复自动选择，不拼接搜索状态。候选属于既有算法的策略复用，不是新搜索内核。

`Work.firstPathPublished`在普通publish和nativeWhole/nativeDecision共用的真实节点发布出口单调置true，经两层MacroRequest构造期final快照传给worker。Base达到原内部搜索上限后可一次新建Super；已走过该后备的waiter不得再回第三次Base，普通消费者仍保留原后备。每input attempt最多2次canonical flight选择，全请求最多3次；原一次stale重试只重置阶段计数，不重置总次数和tried。16384是内部MacroSearch的上限，不能宣称全请求visited上限。

预算固定20ms，只按当前tick实际截止执行。没有引入历史预测、跨tick偿债、tile、JVM调优或持久化改造；既有facts store照常运行。未提交、推送或部署。

## 进入性能前的正确性门

`correctness-v1.json`汇总28个功能JVM和10组82条worker断言，全部通过：

- 24个自然移动回归：flat、warm、stairs、door-open、partial、mutate、adopt、low、support、corridors各auto/candidate，以及same和短Base各两模式。全部有真实原版Path节点，partial按原契约EXHAUSTED，其余SUCCEEDED，没有整条原生回退。
- 2个直接发布复核：未采用prepare先生成真实Path、firstPathPublished置位，再adopt；后续新宏观请求coldFirstPath=false且恢复Super。两个实际发布出口的事件都在Server thread。
- 2个128实体八走廊控制：两模式各128个自然终态、8个不同goal-z、8个实际物理MacroQuery；没有把逻辑并发数当作独立冷查询数。
- 9组70条真实worker控制：冷热分支、共享取消、UNKNOWN恢复、失代一次重试/第二次STALE_WORLD、卸载重载和新loadIdentity、体型能力隔离、关闭与新请求拒绝，以及故障控制中的共享分流和stale后2+1 flight界限。
- 默认16384、无故障钩子长走廊补证12条：普通Base真实expandedNodes=16384；候选与普通共享Base，候选随后Super等待真实PENDING端点child，按真实section/chunk卸载事件使其不可用，拒绝第三次Base。两future各完成一次，资源归零。这是合成facts控制，Super腿未完成完整搜索，不是原生到达或性能样本。

所有自然移动请求在场景就绪真实30秒后发出，合成控制在服务器启动真实30秒后开始。最后四个补证JVM在生产关闭后直接核对logical/endpoint/physical/build/dependency/liveSearch/reference/factsBytes为0，scratch live及aliveWorkers为0；性能各诊断模式强制同一清理检查。

读取器`reader-controls-v5.json`拒绝57类损坏、通过3个正例。请求相关事实等待缺start仍拒绝；只有完整依赖关联后证明无关的arm前后台结束才排除，不补造等待持续时间。后继宏观请求以各自service request身份保存时间线，不能覆盖首次查询时间。

三次失败原件保留：contracts-v2的Java模块split package、contracts-v3遗漏逐section卸载事件的fixture、default-limit-v6中间父图fixture未触发端点后备且同步控制触发watchdog。修正后另建目录复验，没有重写失败结果。旧接线、功能及诊断包重复轮不进入性能分母。

## 冻结矩阵与实际分母

使用WSL原生`/usr/bin/dsh --profile headless`，provider=`deepseek`、model=`deepseek-v4.1-flash`。无头每包独立完整摘要，<=6500字节，900秒本地截止，未修改settings。

`code-scope-v1`接受首Path/冷热/final快照范围；`code-fallback-v1`要求修复第三次Base；`code-fallback-v2`接受修正并解除阻断。`default-limit-v1`以3854字节、377.073秒确认默认上限门。`correctness-v1`以6079字节、33.518秒给出`ACCEPT_WITH_GATES / 本轮必须修改项：无`，认可进入有限性能比较，明确512正确性未过不得进入Portal或生产。本轮执行了这一停止门，没有把先前准入认可当作性能通过。

`performance-freeze-v1.json`预冻结135个顺序独立服务器JVM：平地auto/base/candidate×detail/off/plain×1/128/256/512×3共108；另有八走廊512对应27，单列报告。模式和诊断在每个位置跨三重复轮转，strata轮转。源码、发行、读取器、世界输入和facts均校验SHA256；功能轮未混入该分母。

实际窗口为`2026-10-15T10:20:30.834Z`至`2026-10-15T12:58:54.069Z`。前两重复90轮完整，第三重复13轮通过，下一轮失败即停止。各模式分母如下：

| 模式 | 运行检查通过JVM | 失败JVM | 未执行JVM | 通过轮的逻辑请求 |
| --- | ---: | ---: | ---: | ---: |
| auto | 34 | 1 | 10 | 9,734 |
| 强制既有Base | 34 | 0 | 11 | 9,734 |
| candidate | 35 | 0 | 10 | 10,246 |
| 合计 | 103 | 1 | 31 | 29,714 |

失败轮另外提交512个请求，不并入29,714个完成样本。103轮共229次公共物理查询启动，与独立canonical joins核齐；平地每JVM共享1个、八走廊每JVM8个，不能把逻辑请求当作独立冷样本。候选性能轮实际运行35个JVM，已经不是前置准备。

场景就绪后最短30.040948秒才请求；facts已就绪，目标GeometryKey的Base/Super冷。这是服务器运行后的首次寻路对照，不是服务器开机或磁盘冷恢复。独立复核重新读取103个完成样本，验证原始输入、实际jar、冻结源码与脚本、JVM串行时序、真实Path、自然终态和关服资源；34个完成detail另作阶段与真实native队列关联。`auditPassed=true`只表示这项封存核验成功，`matrixPassed=false`、`performanceQualified=false`。

## 第104轮的停止原因

`perf-v1-flat-512-auto-detail-r3`进程正常退出0、没有本地超时，但探针返回`passed=false`，原始错误为`terminal without Path 0 UNAVAILABLE`。冻结读取器明确拒绝该轮；没有`analysis.json`合格记录，没有补跑替换或继续其余31轮。

该轮事实哈希、冷图状态、发行包和故障钩子关闭均符合冻结条件，场景就绪30.352483秒后才提交。完整原始事件显示：

- 512个请求和512个enqueue的唯一owner、sequence逐个对齐。
- `dispatch=0`、策略记录0、canonical joins 0、物理查询启动0、原生搜索0、真实Path安装0；没有请求进入初始化或宏观分支。
- 公共与trace的202个完整tick样本内容逐个一致，全部记为`SERVER_NO_TIME`，每tick grant/refill均20ms。
- 其中连续200tick的队列始终为512，派发前余额为14.890899–19.496001ms，随后清空。提交到最后完整tick观测约11,993.187ms。
- 既有`PLANNING_TTL=200`，排队超过200个游戏tick即以`UNAVAILABLE`结束；现有事件与这条派发前超时路径一致。调度器先检查传入`haveTime`与实时supplier，任何一个为false即记录`SERVER_NO_TIME`。本轮没有区分两个谓词哪一个拒绝，也没有证明其上游原因。
- navigation收尾计数`failures=512`、`nativeCalls=0`、`records=0`；worker及facts引用、scratch和worker线程均清零。探针未保存逐请求最终状态快照，不能把汇总512扩写成512条同因的独立终态证明。

这是auto基线的派发前阻断，不能解释成候选搜索或父图构建失败。等待期仍有当前tick预算，不能解释成跨tick偿债。没有修改`haveTime`、TTL、预算、JVM参数或读取器来继续旧矩阵。

## 保留的部分观测

以下为首条真实Path“观察”p95，单位ms，向量按r1/r2/r3。`未运行`和`失败`均不计为0。下表节选常用负载及反例；全部15格和135个计划项的状态保存在`performance-abort-v2.json`，不选择好样本代替完整矩阵。

| 场景 / 并发 / 诊断 | auto | 强制Base | candidate |
| --- | --- | --- | --- |
| 平地 / 1 / plain | 279.896 / 1239.627 / 未运行 | 192.368 / 183.371 / 未运行 | 185.461 / 831.087 / 未运行 |
| 平地 / 128 / plain | 527.891 / 1858.076 / 未运行 | 461.133 / 657.098 / 未运行 | 554.092 / 575.225 / 未运行 |
| 平地 / 256 / plain | 663.235 / 852.925 / 990.753 | 668.029 / 844.811 / 2445.875 | 657.007 / 907.621 / 7964.859 |
| 平地 / 512 / plain | 1700.815 / 898.494 / 1391.380 | 912.381 / 1352.375 / 未运行 | 885.472 / 850.607 / 1482.748 |
| 平地 / 512 / detail | 1203.969 / 1183.212 / 失败 | 1983.386 / 1010.781 / 1938.679 | 1542.214 / 1531.507 / 未运行 |
| 八走廊 / 512 / plain | 1404.029 / 794.793 / 未运行 | 1299.175 / 812.022 / 未运行 | 1544.861 / 818.879 / 未运行 |
| 八走廊 / 512 / detail | 1461.017 / 950.997 / 未运行 | 1483.990 / 1050.148 / 未运行 | 1703.589 / 1002.746 / 未运行 |

平地512/plain的candidate首Path两次较快、第三次较慢；该切片三次自然到达p95均较早：

| 模式 | 自然到达p95，ms | 全程完整tick最大值，ms |
| --- | --- | --- |
| auto | 32876.676 / 32143.159 / 32585.795 | 116.142 / 82.049 / 98.854 |
| candidate | 31958.065 / 31933.238 / 32444.662 | 61.934 / 78.848 / 96.418 |

这些改善是真实保留的部分观测，不能因为中止而删除；同样保留256/plain的7964.859ms慢样本。它们不足以越过整轮停止门，不能登记稳定收益或生产资格。完整tick覆盖提交到自然终态与关服，包含移动阶段，不是首Path独占成本。103个完成JVM中的完整tick最大值为469.531ms；失败轮提交所在tick另有527.530ms，二者分母不同。

## 阶段工作与测量边界

完成detail中，平地auto首Path前及全程均64 Base/8 Super，Base/candidate均14/0；八走廊auto两轮分别64/7、62/6，Base/candidate为20/0。图的`retainedBytes`只是图记账，不能解释为进程内存或同时驻留峰值。

平地512/detail两完成重复的阶段累计墙钟如下，单位ms：

| 阶段 | auto r1 / r2 | candidate r1 / r2 |
| --- | --- | --- |
| Base build | 55.643 / 61.550 | 20.066 / 28.629 |
| Boundary，含Super内部 | 42.364 / 39.444 | 8.891 / 11.949 |
| Super build | 46.996 / 44.729 | 0 / 0 |
| 各请求自己首Path前原生搜索累计 | 173.327 / 167.748 | 281.206 / 289.501 |

阶段按匹配GeometryKey的构建任务和请求关联的Boundary/MacroQuery父链筛选，裁剪到各请求均获首Path的窗口。Boundary与Super等inclusive阶段重叠，不能相加当作关键路径；CPU计数有量化，不能据短阶段CPU精确归因。上表说明减少图工作与更快首Path不能画等号，不证明未实现骨架的收益或无效。

宏观corridor自然改变了详细首段：平地auto到x16、11–14节点，Base/candidate到x32、27–30节点；分段代码未改。512/detail首轮全程原生调用auto2560次、candidate1536次，但首Path前原生墙钟反而173.327ms对281.206ms。因此比较是同最终目标的端到端行为，不是在相同详细工作量下纯隔离父图准备的消融；调用变少也不等于总CPU变少。

冻结读取器旧`firstNative`队列字段实际取到初始化派发，不能用作详细搜索排队归因。原始事件保留，独立后处理按真实native-search前最近全局dispatch、精确enqueue序号/owner和无中间budget边界关联；6类损坏拒绝及1正例通过，34个完成detail均已复核。上述平地512两轮的真实详细队列p95分别为auto 515.911 / 531.420ms、candidate 1093.567 / 1027.649ms。旧值不混入这些数据，此前命名错误已向用户说明并致歉。

后继宏观service请求计数仅在34个完成detail中实际测得为0，不能推断后续详细搜索没有排队，也不能外推复杂路线。V1把off/plain空详细事件数组展平后的占位计数记成0；V2将这69轮的后继请求数及未测详细时间明确设为`null`。V1保留，主Path、到达、tick、建图、公共计数及失败状态不变；没有生成要求135轮完整通过的`performance-summary-v1/v2.json`。

plain去除生产Trace hook，但仍保留共同policy记录和探针，不能称完全无插桩正式包。各格最多3个独立JVM，残余主机/顺序效应未排除，不批准纯插桩开销、稳定收益幅度或因果瓶颈排序。

## 本轮共识与后续门

`performance-abort-v1`以6462字节、63.654秒正常返回`ACCEPT_WITH_GATES / 本轮必须修改项：无`；`performance-abort-scope-v2`以3920字节、18.145秒独立复核未测值纠正，同样返回该结论。实际provider/model为`deepseek / deepseek-v4.1-flash`，两包900秒本地截止均未触发，settings哈希前后相同。共识限于派发前定位的边界、中止处理及未测值口径，不是完整性能认可；208份原始结果/退出文件与冻结源码/发行身份已封存，V2另核主指标和失败决定没有变化。

当前决定是维持中止：不续旧矩阵凑满135、不替换失败、不进入Portal或生产，也不根据这次基线阻断宣判候选算法失败。Portal尚未实现；既有TopologyGraph已经在请求邻Base前调用mayExit/mayEnter并使用代次绑定BoundaryLinks，不能复制改名后登记为新骨架验证。

DeepSeek给出的后续最小范围是：若重测，另列范围并新冻结，补逐请求终态快照及两个haveTime谓词的区分，明确基线派发条件后再评候选。本轮没有执行这个补充，没有扩大到历史预测、偿债或预算调参。当前完成的是候选实现、先行正确性门、部分性能实测和一次按门槛中止的处理，不是整个方案验证通过。

关键证据均在`.tmp/first-request-closure-20261015/`：`performance-abort-v2.json`为纠正后的部分数据，`performance-abort-review-seal-v2.json`为本轮最终封存；`performance-abort-v1.json`及首轮seal均保留，`performance-abort-stages-v1.json`保留34个完成detail阶段，两份`deepseek-answer-performance-abort-*.txt`保留实际审查回复。

最终发行SHA256：

| 包 | SHA256 |
| --- | --- |
| plain | `2648ECFC891B116CF22EDEB94574618623772A21AA6D0C8C19DE56AFDACFAA7B` |
| trace | `54BA8F6436603C49436F85E04C084634D8ED6218E70E5F2743F9EB10A7FDCE3A` |
| probe | `574847D38BB013B16A74346CF9996F4CF29E6AD4F855CC31CF80BDD3A634A4CF` |

`package-audit-v8.json`核259个主包class、8个probe class，plain/trace差异只限九个诊断源及其内部类，plain无Trace hook、无已删除的历史controller。上一轮前置`summary-v1.json`的SHA仍为`B9DB2D6155150684295102978A41A2DFEDCFE599700CD657480495B50B91B010`，未改写。
