# 导航时间线诊断工具

固定工具入口，与 `tools/performance-analysis` 的离线端到端汇总分开。这个工具用于解释当前导航版本的启动等待、准备成本及请求结构，不给生产性能 PASS。

复用已有工具栈：JDK 17.0.18+8、ASM 9.7、Forge 47.3.0 运行库、当前修正版生产 Jar、参考世界模板、完整 tick/真实 Path 发布/PathFinder 公共探针、Node 内置模块。JFR 使用同一 JDK 的 consumer API，不安装额外分析器。

## 执行

在项目根目录运行。每个输出目录必须不存在；构建与 Java 负载串行。

```powershell
./tools/navigation-diagnostics/build.ps1 -Output .tmp/my-timeline/build
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-timeline/build -Output .tmp/my-timeline/single -Suite single
node tools/navigation-diagnostics/analyze.mjs .tmp/my-timeline/single
```

`-Suite` 支持 `single`、`burst`、`supplement`、`coverage`。`-Native` 是同包 bypass，`-NoTrace` 关闭深阶段观测，`-Jfr` 增加诊断录制。每次新服务端启动及场景落地完成后，均真实等待至少 30 秒。所有场景、失败、干扰、退出与原始 tick 保留。

`-NavigationLogLevel DEBUG|TRACE`只调整诊断运行中导航logger的级别；默认DEBUG，TRACE用于验证详情仍可获取。构建、运行和分析入口可反复复用，每轮只换配置与新输出路径。

### 失败根因与修复验证

`-Suite failures` 重放8/28格墙体、门口的全部tile/slot、额外反向路线、显式range及取消/替换控制。`-Replay`只允许与该suite、`-NoTrace`和加速后端组合；它在每次实际段搜索后做交错参数重放及相同区域半径对照。重放搜索从公共PathFinder计数中分离，额外成本不用于性能比较。原始真实搜索与重放的节点、起点、参数、节点额度及距离截断计数保存在`failure-searches.json`。

```powershell
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-t2/build-baseline -Output .tmp/my-t2/replay -Suite failures -Scene 'wall8-all,door8-all,wall28-all,door28-all' -NoTrace -Replay
node tools/navigation-diagnostics/analyze-failures.mjs .tmp/my-t2/replay
./tools/navigation-diagnostics/build.ps1 -Output .tmp/my-t2/build-candidate -CurrentSource
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-t2/build-candidate -Output .tmp/my-t2/candidate -Suite failures -NoTrace
```

默认构建仍校验既有冻结基线身份。`-CurrentSource`在新输出目录先冻结当前`src/main`，隔离编译并重混淆带依赖生产包；逐文件源码身份与实际包哈希写入`build-identity.json`，不替换默认发行包。`analyze.mjs`和`analyze-failures.mjs`按launch绑定的构建身份及实际加载包校验，不再把所有新候选强制视为旧包。

`-SourceRoot`必须配合`-CurrentSource`，可指定已经冻结、含`java/`和`resources/`的生产源码目录，用于构建只差目标改动的对照包。实际输入仍复制到新构建目录并逐文件记录身份；不能通过复用输出目录覆盖旧包或旧证据。

必须另跑关闭重放的独立JVM，验证真实移动及partial终态。`-NoTrace`只关闭深阶段追踪，失败套件仍有上述诊断记录；这些运行都不是净性能准入。

### 最终窗口与生命周期

`-Suite final-window`诊断普通最终段是否还生成无人消费的窗口，并覆盖宏观交付后取消、替换、改块、当前tick额度耗尽、真实`canUpdate=false`等待的恢复/取消/替换/200tick TTL，以及最终Path发布后的实际关服。默认最后运行`shutdown-final`；等待中关服须单独选择`-Scene waiting-shutdown`。

```powershell
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-final/build -Output .tmp/my-final/controls -Suite final-window
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-final/build -Output .tmp/my-final/waiting-shutdown -Suite final-window -Scene waiting-shutdown
```

探针在`MacroRequest.completeWorker`交付后的主线程边界调用正常导航/世界API；只读反射生产Work。等待场景通过测试实体无重力离地制造真实条件，准备阶段仍自然落地。搜索记录取自真实同步搜索返回之后，余额快照可包含该次不可中断搜索的超额，不能据此反推任务启动时余额；额度耗尽场景另外断言搜索必须发生在后续tick。每台控制JVM运行两轮既有预算契约，覆盖固定20ms、不结转正负余额、公平和完整计费。

原始结果为`FINAL_WINDOW_LIFECYCLE_V1`，与通用时间线schema不同；通用控制分析入口为`node tools/navigation-diagnostics/analyze-controls.mjs candidate RUN_DIRECTORY`。`baseline`要求最终window存在，`candidate`要求不存在，指窗口机制而非每轮版本名称。结果包括预算断言、实际输入/节点、窗口和就绪派发次数，输出已存在时拒绝覆盖；可用第三个参数另命名汇总。原4a日期目录中的脚本保留为历史证据。本套件属于机制和功能诊断，不能当作净性能样本。

两个有效控制汇总可用公共比较器核对明确指定场景的搜索参数、原始节点和窗口数，同时统计对应query的导航日志消息字节。时间戳、query身份和计费快照不要求相等；日志字节只衡量消息文本，不当作总分配或性能估计。输出拒绝覆盖：

```powershell
node tools/navigation-diagnostics/compare-controls.mjs BEFORE/controls-analysis.json AFTER/controls-analysis.json zero-edge8-first,zero-edge8-repeat,cross8 NEW_COMPARISON.json
```

`-Scene` 可选择该suite中的一个或多个场景，例如 `-Suite supplement -Scene ai-melee-query-adopt`，或 `-Suite coverage -Scene 'brain-walk-target,poi-acquire'`。输出按suite定义的顺序运行，未知名称会拒绝。真实AI按导航器跟踪请求代次，旧句柄取消不等于移动终止；首Path必须对应已跟踪请求的实际发布区间。coverage分析另以同一导航器的prepare返回身份补全在同tick内创建和替换、未被END采样看到的请求。

## 入口与恢复覆盖

`coverage` 包含实体追踪/三次漂移、自然GoalSelector、真实村民Brain、三种多目标查询采用时机、accuracy=0共享资格控制、外部legacy查询后移动、真实AcquirePoi、发布后改块及受控失速。

```powershell
./tools/navigation-diagnostics/run.ps1 -Build .tmp/my-timeline/build -Output .tmp/my-timeline/coverage -Suite coverage -Jfr
node tools/navigation-diagnostics/analyze.mjs .tmp/my-timeline/coverage
node tools/navigation-diagnostics/summarize-coverage.mjs .tmp/my-timeline/coverage-summary .tmp/my-timeline/coverage/analysis.json
```

也可将多个不同场景子集分到串行独立JVM，再把各自有效的analysis.json传给汇总器；每个预定场景必须恰好有一份有效记录。功能失败仍进入账本，技术无效运行单独保留。汇总检查未通过时不得称覆盖完整。

可另传入已有的有效single/burst分析，作为partial见证及完整阶段成本账本的来源；没有实际partial不能伪造该分支覆盖。`coverage`限定加速入口，不能与`-Native`组合使用。

旧压测壳设置了`spawn-npcs=false`。coverage仅修改新运行副本为`spawn-npcs=true`，并在launch记录实际配置哈希，否则村民会在提交前被移除。准备等待时保持自然物理tick，GoalSelector和Brain中的测试行为在提交时启动；不使用会阻止落地的NoAI准备冻结。

服务端会重写server.properties，启动输入保存为launch-server.properties，exit另记结束配置哈希。不能要求两个文件逐字节相同。早期未保存启动副本的记录须由冻结配置重建，并与已有launch哈希完全匹配后才可用于输入身份核验。

受控失速只固定测试实体travel中的位置，继续运行真实导航器的stuck/timeout；不直接调用recover、refresh或nativeStopped，也不改Work状态。观察到真实refresh后解除固定。它用于覆盖重试、缩段、刷新及清理，不用于估计正常负载的故障频率或时延。

所有入口按场景单独给分母；人工安排各一例不能产生实服流量权重。POI选择、只查询的裁决与物理到达分别报告。逐tick的目的组件进入/Path结束/恢复移动是采样观察边界，不能冒充其精确发生时间。测试观察自身另列diagnosticProbe阶段，净性能测试不启用该套件。

外部多目标Path必须按实际`Path.getTarget()`观察到达。旧coverageProbeRevision<2的legacy cell保留原件并标记`arrivalScopeValid=false`；覆盖汇总排除该cell，要求有修正后的独立记录。进程/身份/计量技术有效与某项指标的归属有效性分别声明。

汇总支持独立新输出目录，适合保留分析器修订前的结果：

```powershell
node tools/navigation-diagnostics/analyze.mjs .tmp/my-timeline/single .tmp/my-timeline/analysis-v2
node --test tools/navigation-diagnostics/analysis.test.mjs
```

完成所有服务器测量后再执行 JFR 提取，避免新的 Java 分析进程干扰服务端：

```powershell
& 'C:/Program Files/Java/jdk-17.0.18+8/bin/java.exe' -cp .tmp/my-timeline/build/classes/check an.trace.JfrExtract .tmp/my-timeline/burst/server.jfr .tmp/my-timeline/burst/jfr-samples.tsv
node tools/navigation-diagnostics/analyze-jfr.mjs .tmp/my-timeline/burst
```

## 尾部交接资源筛选

`coverage -NoTrace -OmitObservations`关闭逐tick反射观察，仍保留入口跟踪、
真实PathFinder计数、100ms资源采样和完整tick。仅用于预定的小范围筛选，
不是无探针生产负载。轻探针按实体累计其全部请求代次的PathFinder调用；
没有保存逐次wall/CPU/分配明细，不能把累计差额归给某一次搜索。

先分别运行公共分析器，再比较同参数、串行B→C的两个独立JVM：

```powershell
node tools/navigation-diagnostics/compare-handoff-resources.mjs B_RUN C_RUN NEW_COMPARISON_DIRECTORY
node --test tools/navigation-diagnostics/handoff-resources.test.mjs
```

比较器重新核验公共技术有效性、JVM参数、测量源码清单、世界/配置、
场景顺序和实体路线，拒绝混入深插桩、JFR及尾部故障注入。输出保留
PathFinder实体累计、自然结束累计资源、固定1秒/5秒的真实前后采样界、
主线程CPU/分配、全进程CPU、全线程分配、GC和tick；缺失与倒退计数拒绝。
coverage的自然结束快照在实体stop/consume/discard之前，不含后续实体清理及关服；
场景资源包含后台与探针工作，不是单一请求独占资源。
不同Jar归档哈希的内部项内容仍须另行核对，不能只凭源码相同断言二进制相同。
Windows短搜索CPU的零值保留，不计算搜索CPU提速比例；单对结果不判
稳定净性能、统计非劣、普适性或生产资格。

## 观测口径

- 每线程预分配 primitive 数组；记录全部选定阶段，不丢弃短调用。对象身份在退出后统一编号，避免 identityHashCode 碰撞。阶段具有线程、场景、server tick、单调起止时间、返回身份与请求/段身份。
- 选定方法通过 ASM 插桩，嵌套计时按线程取独占时间和独占分配；跨线程或跨阶段等待使用真实边界，不能累加包含关系、并行时间或 wall−CPU 推断锁等待。
- worker 的 enqueue、selection、run 以 Task 身份与 sequence 配对。BUILD 任务还包括窗口工作，不能当成实际 Base/Super 构建数。
- 保留宏观主线程交付、窗口 future 回调、主线程窗口回调、调度接续及真实 Path 发布。窗口计算完成到回调起点的边界与整个 worker 方法返回边界不同，允许回调早于 worker 方法返回。
- 公共探针提供全 JVM CPU/分配和 100ms 目标间隔的端点采样。固定 1s/5s 窗给实际前后采样界；无法重建精确截止点，不插值伪造。
- JFR 使用 `an.trace.Clock` 把记录中的时间与 nanoTime 对齐，另报锚点时间宽度。分配权重和执行采样只定位来源，不是精确阶段分配或 CPU。
- JFR按完整堆栈识别classLoadingAndTransform，包括首次Mixin及诊断ASM转换，不能按下层生产调用者的名字归成稳定热点。原始allocationSites仍保留调用点名称，来源分类见allocationBySource。
- 缓冲区满时停止相应记录并累计 dropped，保留原流程；该次诊断技术无效。正常嵌套、异常退出及强制容量不足均有独立自检。

## 已知边界

当前工具明确依赖 `.tmp/current-performance-reference-20261016` 中冻结的源码、生产包、探针与模板，以及此前已确认的本地 Gradle 依赖和 Forge 运行库。源码/Jar 身份改变时需要重新适配，不能声称适用于任意模组版本。构建输出保留工具源码和编译后的 helper/agent/probe；不覆盖默认发行包或生产源码。

深插桩会占用时间、增加初始化成本并保留事件引用，可能改变 JIT、GC、队列及对象寿命。关闭深插桩的同壳对照仍有 agent 调用和公共探针开销。诊断值不混入净性能比较。

`single` 的 first-use 表示该 JVM 中此前没有主动提交过这条路线；不保证全世界图和事实都冷。事实来自固定持久化模板，生产后台预热照常运行；是否实际构建由事件和计数证明。`repeat` 是同 JVM 重复，不能冒充独立 JVM 样本。

`supplement` 的 AI 场景使用真实 `MeleeAttackGoal` 和 `WrappedGoal` 的 canUse/start/tick/stop，但环境受控且目标静止。它验证真实入口调用链，不代表实服入口分布。静态失败路线只定位失败阶段，动态改块与修复属于后续任务。
