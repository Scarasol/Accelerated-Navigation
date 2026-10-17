# 就绪接续与日志减量验证

日期：2026-10-17。对应统一流程第4b、4c步。状态：**有限结清**。生产实现、机制与本轮回归通过；DeepSeek最终共识`ACCEPT_SCOPED`，本轮必须修改项无。净测结果混合，尤其8格CPU及首Path退化，整体性能和发布资格尚未通过。

## 1. 生产改动

本轮基线已经包含3/T2的最终段原range修复与4a的最终窗口删除。相对该基线，仅两个生产Java文件改变，共75行新增、16行删除：

- `NavigationScheduler.java`：为正常tick结束后到达主线程的就绪依赖增加合并的派发请求，经`server.tell(new TickTask(...))`进入原队列drain。
- `SegmentedNavigationService.java`：仅共享结果成功、宏观走廊成功、中间窗口成功三个回调使用`queueReady`；另将高频完整详情从DEBUG移到受保护TRACE。

4b解决的是依赖已经完成、正常END drain也已经结束时，再等下一次tick END的停顿。原drain内部已有followup接续能力，本轮没有把所有queue都当成固定等待一tick，也没有直接内联搜索。

新派发复用原优先级、老化、维度轮转、FIFO和每任务独立准入，正常初始提交、恢复、observe及失败分支保持原流程。最终详细搜索仍为独立scheduler任务。相同tick内的多个就绪通知合并为一次trampoline；回调发生于drain内部时沿用原drain循环。

4c保留DEBUG的阶段、计数、range、可达性、节点数和失败原因；完整endpoints/connections、sections/destination/candidates、逐搜索位置/目标/末节点、目的组件进入以及恢复集合详情移到TRACE。昂贵详情getter及TRACE调用均在`isTraceEnabled()`内。没有改变搜索输入、决策或返回值。

## 2. 预算与计量边界

固定20ms/tick，正负余额不结转，无负债、偿债、动态额度或历史预测。就绪派发只在以下条件同时满足时使用原tick的剩余额度：

- 正常结束且具备START、END的有效envelope，END允许继续；
- 尚未进入下一个envelope、服务未关闭、pending非空；
- 原`tickStarted + 45ms - 2ms`绝对截止和当前余额均至少剩500us；
- 保存的实时`haveTime`仍为true。

提交trampoline时和真正执行时均检查。新envelope开始立即撤销旧窗口；不增加额度，不借下一tick。同步原生搜索仍可能不可中断地超额，实际耗时完整入账，后续任务停止，下一tick重新开始固定额度。

就绪drain位于`tickServer` envelope外，完整计入累计`chargedNanos`。紧接的下一次envelope正常形成样本时，其`outsideNavigationNanos`记录这些外部工作；若该envelope被跳过则没有对应样本，累计计费仍保留全部成本。本轮不改变既有的跳过样本口径，不追写已经完成的`TickSample`。因此已完成样本的完整tick、导航耗时、余额和停止原因只描述该envelope，不能单凭tick缩短证明总成本降低。净测另保留全JVM CPU、分配和真实墙钟时延；累计端点成本不伪装成共同固定墙钟窗口的精确成本。

## 3. 机制与功能

复用既有生产scheduler预算检查，新增fake-clock就绪控制，不写生产Work。每台控制JVM执行两轮，合计172条断言，涵盖就绪前提、同tick余额、任务与回调联合计费、样本不追写、outside账本、新额度、维度轮转/FIFO、取消、原子超额停止、不结债、新envelope撤销、500us截止、haveTime撤回/恢复、END=false、异常、线程检查及清理。172不是独立场景数；真实控制场景另覆盖实际`tell`派发。

先冻结只含4b的候选，再冻结4b+4c组合候选，分开核对机制，阶段末共同回归。三场对应搜索的range、accuracy、regionOffset、visitedMultiplier、起点、有序目标集合、可达性及原始Path节点序列完全相同；窗口数保持0、0、1。

| 插桩场景 | 4a基线首Path ms | 仅4b首Path ms | 4b+4c首Path ms | 中间窗口 |
| --- | ---: | ---: | ---: | ---: |
| 零边首次 | 106.718601 | 60.155000 | 68.312200 | 0 |
| 零边重复 | 51.342500 | 4.418900 | 11.170501 | 0 |
| cross8 | 103.301499 | 21.033801 | 13.965001 | 1 |

这些是单次插桩机制观测，不作为净性能估计，也不能把组合候选与仅4b的时间差归因于日志减量。就绪drain调用计数包括被重新检查后跳过的调用，不等同于实际启动搜索次数。

仅4b与组合候选对应query的DEBUG消息正文UTF-8字节分别为1415→285、1415→285、3654→545，约减少79.86%、79.86%、85.08%。计数排除logger时间/线程前缀，包含每行换行，不混入整个服务器日志；这只是消息文本减量，不是总分配降幅。

组合候选12个生命周期场景及单独的等待中实际关服均通过，包括宏观交付后取消/替换/改块、额度耗尽、真实离地`canUpdate=false`等待恢复/取消/替换/200tick TTL、最终Path发布后实际关服。`macro-cancel`、`waiting-cancel`、`waiting-shutdown`、`waiting-ttl`均无搜索或Path发布；`shutdown-final`在真实Path发布后关服。替换后旧代次无搜索；额度耗尽后的搜索发生在后续tick。TTL按预期UNAVAILABLE，取消与关服按预期CANCELLED，正常成功场景实际到达。最终range与accuracy保持原请求。

无重放回归选取`wall8-all`与`door28-all`，共128/128实际到达并SUCCEEDED；显式range4按预期EXHAUSTED且未到达，range12实际到达并SUCCEEDED。234条真实搜索记录通过最终range核验。本轮没有重跑4a的全部八场墙门矩阵。

| 真实入口 | 实际到达 | 真实搜索 | 中间窗口 | 恢复 |
| --- | ---: | ---: | ---: | ---: |
| 实体目标漂移 | 1/1 | 9 | 8 | 1 |
| 查询就绪后采用 | 1/1 | 3 | 1 | 0 |
| 查询未就绪时立即采用 | 1/1 | 2 | 1 | 0 |
| accuracy=0共享 | 1/1 | 2 | 0 | 0 |

Super304实际到达并SUCCEEDED，20次详细搜索、19次中间窗口、35次真实Super构建，零恢复，最终窗口worker调用仍为零。它验证长路线与中间窗口保留，不构成净性能样本。上述运行正常退出、清理通过，runner未记录外部Java干扰。

`combined-trace-v2`使用显式TRACE sink，核验宏观、窗口、原生搜索和目的组件进入详情仍可获得；DEBUG中的对应详情缺席。TRACE使用独立日志配置，只验证详情可用，不用于性能比较；未为恢复TRACE消息另做故障注入。

阶段末`verifyCoreContracts`通过，Core 9项与Reverse integration 3项分别报告。原Macro提交契约4项在4a已通过，本轮相关生产模块未变，复用其证据，不重复运行。

## 4. 身份与流程

证据根目录：`.tmp/navigation-ready-logging-20261017/`。保留既有dirty worktree，不提交、推送或回滚，不替换默认发行Jar。

| 身份 | SHA256 |
| --- | --- |
| B：已结清4a的生产Jar | `a6c494dabf78a1479b9fc5e620ab1f5a837a72e846ccc1d8c3438dab74ae3dcf` |
| 仅4b的生产Jar | `84f4711c098fdee59ece34edf557e05f68f73d88ea62080d51a37e6871aea023` |
| C：4b+4c生产Jar | `959fc6a72b62bf213c2c85a88c79941dea0cb8829785614374f99dee1364b2f0` |
| 公共净测reference probe | `48c7a13296f7dce289fbbec11258a0535dbd943b8731a479dae9f8f645e171d8` |
| 默认旧发行Jar | `89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa` |

构建及服务器串行，启动和sceneReady后各真实等待至少30秒，旧结果拒绝覆盖。小改动先做定向机制检查，阶段末共同做关键回归、一次核心契约和一对独立JVM净性能筛选。无正式A/A、统计非劣或C/N资格，不宣称整体性能通过。

公共复用入口：

- `tools/navigation-diagnostics/analyze-controls.mjs`：控制场景、身份与预算检查。
- `tools/navigation-diagnostics/compare-controls.mjs`：明确指定场景的真实输入/节点对照及导航消息字节。
- `tools/performance-analysis/run.ps1`及原通用分析器：每轮仅换构建、配置和新输出路径。
- `tools/review/deepseek-review.mjs`：统一WSL headless文本审查、超时、设置/输入/答复哈希、原始输出与结论解析。

公共分析器两套47项加审查工具2项，Node合计49/49通过。未为每个小日志改动复制完整验收矩阵。

保留且排除的尝试：

- `build-ready`、`build-ready-v2`分别因Java局部名冲突和探针缺import构建失败，修正后在新目录构建；从未作为有效运行样本。
- `combined-trace-v1`中Windows绝对路径被Log4j解释为URL协议，专用sink未创建；控制台虽有详情，仍不采用该轮TRACE配置验证。runner改为文件URI并检查sink非空，使用新目录`combined-trace-v2`通过。生产源码与Jar未改变。
- `core-contracts.log`为Gradle回环连接启动失败，尚未执行测试。沿用现有构建脚本的socket配置后，`core-contracts-v2.log`中实际契约通过。

## 5. 净性能筛选

预先声明一对独立JVM，顺序B→C；每台预热8/28×64，再正式8×512与28×512各一次。公共冻结probe、世界、JVM参数及路线输入相同，无诊断agent、JFR或并行DeepSeek。所有运行正常退出、清理通过，runner未记录外部Java干扰；实际加载生产与probe Jar哈希另行核对。

通用分析器核验2/2运行、4/4正式trial技术有效，2048/2048实际Path、SUCCEEDED及物理到达，零超时。每版本/距离只有1台独立JVM、512个实体，没有统计重复；数据齐全不等于性能通过，分析器保持`performanceVerdict=NOT_EVALUATED`。

| 指标 | 8×512 B | 8×512 C | 28×512 B | 28×512 C |
| --- | ---: | ---: | ---: | ---: |
| 全JVM分配 MiB | 418.265 | 413.509 | 949.971 | 936.733 |
| 主线程分配 MiB | 386.602 | 384.273 | 921.971 | 908.436 |
| 进程CPU ms | 3109.375 | 3671.875 | 3390.625 | 3187.500 |
| 累计charged差值 ms | 183.974 | 196.351 | 278.007 | 277.106 |
| 首Path P50 ms | 168.347 | 207.583 | 258.941 | 224.440 |
| 首Path P95 ms | 256.864 | 269.785 | 372.220 | 369.806 |
| 实际到达P95 ms | 3302.263 | 3254.539 | 10002.984 | 9853.840 |
| 全体实际到达 ms | 5502.909 | 5504.374 | 14003.987 | 13854.239 |
| 完整tick P99 ms | 24.818 | 24.640 | 24.457 | 24.176 |
| 完整tick最大值 ms | 31.589 | 25.245 | 25.372 | 24.612 |
| 完整tick总工作 ms | 693.731 | 665.159 | 1456.472 | 1452.749 |
| GC次数 | 1 | 1 | 1 | 1 |
| GC时间 ms | 6 | 4 | 7 | 5 |

本次8格C/B全JVM分配-1.137%，但CPU+18.090%、首Path P50+23.306%、P95+5.030%、charged+6.727%；实际到达P95-1.445%，全体到达+0.027%。28格全JVM分配-1.394%、CPU-5.991%、首Path P50-13.324%、P95-0.649%、到达P95-1.491%、全体到达-1.069%。完整tick P99分别-0.717%、-1.146%，四场均无超过50ms的完整tick。

8格CPU与首Path退化是本次真实观察，不能因单次温热机制改善而忽略，也不能无证据归因于抖动。只有一个配对，不能确定稳定退化、稳定收益或将净测差异分别归因于4b/4c；没有为改善结果追加不变候选。尤其4b可能把工作移到envelope外，完整tick下降不能抵消CPU或charged上升的事实。

保留原始inclusive tick区间，每场均有提交前1个完整tick，未重新选窗。两组各仅一次GC，不推出稳定GC收益。原始成本为各trial累计端点差值，不冒充共同固定墙钟窗口的精确成本。

温热单体就绪等待缩短、DEBUG文本减量及本轮功能正确性已建立；512突发的总体性能仍未通过正式验收。正式同壳A/A、非劣协议及C/N资格保持开放，留待后续阶段统一处理。

## 6. 文本审查与结论

使用公共`tools/review/deepseek-review.mjs`，WSL原生`dsh --profile headless`，provider/model为`deepseek/deepseek-v4.1-flash`。自包含提示8453 UTF-8字节，禁止工具、读文件、执行和联网，保留prompt/transmitted/answer/process/exit。唯一最终审查轮`deepseek-final-v1`正常退出码0，用时197.951秒，未超时，settings SHA256前后相同。

结论为`ACCEPT_SCOPED`，本轮必须修改项无。DeepSeek认可本packet范围内的ready派发、三个交付点及日志分级没有实际阻断，可结清4b/4c并进入第5步；明确不等于正式性能通过、稳定总体收益或可发布。原子搜索可能超额，截止门约束新任务启动，不把文字审查解释为硬抢占保证。

双方共识保留8格CPU/首Path退化为后续正式性能门槛，不用28格改善抵消。后续路线或恢复改动应补齐相关路线回归与恢复日志验证；本轮未重跑八场全部路线、未单独注入恢复TRACE的边界保留。正式A/A、非劣、C/N和发行产物验收仍开放，按后续大阶段统一处理，不为本次相同候选重复挑选净测。第5步尚未开始。

审查prompt SHA256为`dd4965a5bdf92e2b4862153752eb86a4e721e96e8b0cb70d82d523292a2ada61`，answer为`d23d47389c632ffe29b5b4b953b15e80e26ebb4f099651e7bc1e2eed8ee02170`。DeepSeek没有独立读取仓库、重算哈希或运行实验；它是自包含文本复审，本地运行、源码和身份核验仍以本轮原始记录为准。

## 7. 证据索引

以下路径相对`.tmp/navigation-ready-logging-20261017/`：

| 证据 | 路径 |
| --- | --- |
| 本轮基线、仅4b及组合冻结源 | `baseline-source/`、`ready-source-v2/`、`build-combined-v1/production-source/` |
| 有效隔离构建与身份 | `build-ready-v3/`、`build-combined-v1/`，各自`build-identity.json` |
| 仅4b机制及与4a对照 | `ready-mechanism-v1/ready-analysis.json`、`ready-mechanism-comparison-v1.json` |
| 组合控制、等待中实际关服 | `combined-controls-v1/`、`combined-waiting-shutdown-v1/` |
| 日志分离对照 | `logging-mechanism-comparison-v1.json` |
| 墙门/range、入口、Super | `combined-routes-v1/`、`combined-entries-v1/`、`combined-super-v1/` |
| 有效TRACE | `combined-trace-v2/navigation.log`及其launch/exit/controls-analysis |
| 功能集中汇总 | `functional-summary-v1.json` |
| Node与核心契约 | `node-tests.json`、`core-contracts-v2.log` |
| 预定净测及全部原始记录 | `performance-config.json`、`runs/pair-1-*` |
| 完整性能汇总 | `performance-analysis-v1/summary.json`、`summary.md`、`trials.csv`、`cells.csv` |
| 最终自包含审查 | `deepseek-packet-v1.txt`、`deepseek-final-v1/` |
| 最终源码、工具、报告与身份封存 | `seal-v1.json` |

封存断言当前全部61份生产源匹配组合候选、相对本轮基线仅两个生产文件改变、两台净测实际生产/probe Jar身份匹配、默认旧发行Jar未变。保留失败构建、TRACE旧配置和核心启动失败记录；封存不提升性能或发布资格。
