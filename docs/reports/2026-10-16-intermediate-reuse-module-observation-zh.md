# 原始行复用的模块与系统针对性观测

日期：2026-10-16。针对“其它生产模块是否影响计时”完成阶段与系统观测。**已量化本轮分段检查、详细搜索、拓扑收尾、持久化回调和线程状态，捕获到类加载及其文件读取参与尾部；没有定位旧正式增加 1.609050ms 的因果。生产源码没有修改，旧正式 FAIL 和发布状态保持。**

## 范围与执行身份

主观测位于 `.tmp/intermediate-reuse-module-observation-20261016/`，条件系统补采位于 `.tmp/intermediate-reuse-module-system-20261016/`。原生产、诊断、重复性校准目录及封存文档只读。

生产两包和原场景探针均沿用封存文件，没有重新构建生产包。基线 SHA256 为 `89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa`，候选为 `90eaacd3b8a701c076b64ae52c0a67275f4cad7aa23c89c579640fe44a776101`，共同场景探针为 `9d1c2cbe8347add10a0c264cd369f8f4fa8ff971db467901fa0249cd6cf78651`。

新增独立 Java agent，在类加载时对内存中的方法插入观测，探针代码没有进入 `src/main`、`src/test` 或生产包。**同一个生产 JAR 不表示诊断运行就是无插桩的生产执行。** 两版主观测使用相同 agent、helper、场景及 JFR 配置。ASM 9.7 使用已有本地依赖，没有升级 Forge 依赖。

整方法出口覆盖正常返回和传播异常，保留原有 catch 的优先级。高频检查使用 ThreadLocal 原生数组记录嵌套阶段，按外层 tick 汇总调用次数、inclusive/exclusive 墙钟、最大调用及失败；大于等于 200µs 的调用另保留时间区间。实际转换的方法及描述符随每台记录保存。自检包括嵌套、内部捕获及传播异常、long 返回，栈错误为零；简单方法测试约为开启 58–67ns/调用、关闭 2–10ns/调用，只是局部参考，不是生产探针开销认证。

本机此前实测 ThreadMXBean CPU 计数最小正步进为 15.625ms，本轮没有用“墙钟减 CPU”来归因几毫秒 tick 的等待。

主观测测前冻结 26 项，运行顺序为 B诊断1、C诊断1、C关闭阶段汇总、B关闭阶段汇总、C诊断2、B诊断2。两对带阶段观测顺序为 AB/BA；两个关闭汇总的控制仍保留 agent 插桩和共同 JFR。

之后按预定的系统诊断条件，另冻结 19 项，运行一台独立候选，增加 ClassLoad/ClassDefine 与 WPR。这台不进入六台主观测的版本比较。

**七台共 112 个请求全部自然成功，每台 80 次详细搜索，whole 回退为零，停止清理通过。** 实际场景 ready 后 30.0118913–30.0214728 秒提交，29 秒另一 tick 核验目标 Base/Super 冷态。每台请求窗口有 622 或 623 个完整 tick，包含首搜和慢 tick，p99 均为第 8 大；没有删除或混池 tick。该冷态不表示 JVM、所有类或原始行缓存都未使用。

七台启动前均观察 Windows java/javaw 空闲至少十秒，运行中每秒监视未检出另一 Windows Java；不据此宣称消除所有系统干扰。全部 JFR DataLoss 为零、观测栈错误为零，26 类目标边界均成功接入。旧 1,704 项封存文件复核零漂移，61 项生产源文件匹配原冻结，默认发行包仍为基线。

## 主观测结果

以下均为带阶段观测的诊断数据，单位 ms；不替代原净验收。

| 诊断对 | 基线 p99 | 候选 p99 | 候选减基线 |
| --- | ---: | ---: | ---: |
| AB 第一对 | 5.8665 | 5.4421 | -0.4244 |
| BA 第二对 | 4.7602 | 3.9491 | -0.8111 |
| 两台均值 | 5.31335 | 4.69560 | -0.61775 |

关闭阶段汇总的单控制为基线 4.9778ms、候选 5.1569ms。其方向与两对带观测诊断不同，不能省略。每版仅一台控制，不能据此准确测出探针开销，也不能把控制值相减后修正成绩；它们的模块耗时是未测，不是零。两个控制与旧净矩阵的配置也不同。

本轮两对均未复现旧候选增加，但没有证明版本等效、旧数据错误或旧差异只是噪声。原正式 **5.105850→6.714900ms、增加 1.609050ms、门限 6.616435ms** 的 FAIL 保留。

## 决定 p99 的模块成本

下表列出四台各自决定 p99 的 tick。window/proof/drain 是同 tick 的对应方法调用墙钟合计；详细搜索使用去除嵌套重复后的 exclusive。单位 ms。

| JVM | tick | 完整 tick | windowCurrent | nativeProofCurrent | 详细搜索 exclusive | persistenceDrain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| B1 | 610 | 5.8665 | 0.1790 | 0.2739 | 0 | 0.0001 |
| C1 | 698 | 5.4421 | 0.0779 | 0.2854 | 2.3818 | 0.0004 |
| B2 | 698 | 4.7602 | 0.0671 | 0.2191 | 2.2318 | 0.0005 |
| C2 | 611 | 3.9491 | 0.4658 | 0.3247 | 0 | 0.0004 |

这说明分段有效性检查确实参与完整 tick，且不同 tick 的详细搜索工作不同；不能仅凭 p99 的差值归因于构图。`tickEnvelope` 的 exclusive 仍分别有 5.3284、1.5329、1.2224、3.0203ms，包含未插桩游戏/Forge 工作及探针开销，不能将它命名为某个生产模块或纯 CPU。

五台实际启用模块记录的 JVM，在请求窗口没有观察到 TopologyStore 的 readChunk/writeChunk/writeChunkRecord/flush 调用；四台主诊断的 persistenceDrain 全窗口合计分别为 B1 0.2277、C1 0.1527、B2 0.1930、C2 0.2821ms。持久化服务仍启用，这些记录仅限定本轮窗口，不能推广为生产持久化永远无成本。七台请求窗口的预热完成任务增量均为零，不表示生命周期中从未预热。

探针同时覆盖了 NativeNavigationSearch.find 的 public 入口及其 private 重载，每台 160 个方法入口对应原场景记录的 80 次逻辑详细搜索。inclusive 有嵌套，不能相加或称为 160 次独立搜索；exclusive 的扣除关系逐 tick 核验通过。

## 构图阶段保留的正反信号

每台请求窗口均有 64 次 Base 及 64 次 anchors。下表为调用墙钟合计，单位 ms。

| JVM | Base | anchors | Base 减 anchors |
| --- | ---: | ---: | ---: |
| B1 | 24.1728 | 12.9664 | 11.2064 |
| C1 | 15.1503 | 2.2173 | 12.9330 |
| B2 | 22.8203 | 10.3158 | 12.5045 |
| C2 | 15.1212 | 2.0545 | 13.0667 |

候选的 Base 与 anchors 合计在两对都更短；Base 包含 anchors，收益不能相加。这是带观测的阶段证据，不能当作净提速比例。

反向信号仍存在：非 anchors 残差由基线均值 11.85545ms 增至候选 12.99985ms，增加 1.14440ms，两对分别增加 1.7266/0.5622ms。具体原因仍未分解，不宣称所有构图子阶段都更快，也没有证明该后台残差解释了主线程旧 p99。

## 类加载和系统状态

主观测 B1 的 tick918 为 8.2699ms，其中同线程两次 fastutil JAR FileRead 的墙钟并集为 6.8304ms，调用链来自类加载，不是拓扑存储。它不是该台的 p99 tick，不能直接解释旧正式差值；FileRead 的墙钟也不能单独区分磁盘、缓存或线程抢占。

独立系统补采只运行候选一台，PID 12080、主线程 OS ID 8108。WPR 使用唯一自有 instance，捕获 CSwitch/ReadyThread/ProcessThread/CpuConfig，正常保存和停止，没有停止他人的会话。ETL 事件和缓冲丢失均为零，WPA 实际导出 75,835 条该进程和线程的切换记录。

原始 ETL 由 WPA 全量读取。另一个原生 header 读取器只读取首 buffer 后主动停止，ProcessTrace 返回 1223 是该读取器的预期停止，不是 ETL 丢失。

通过 Windows OpenJDK 17 的 QPC 时钟实现与 ETW 原始 trace-header QPC 对齐，频率均为 10MHz，起点位于自有启动前后 QPC 夹界中。JFR 与 ETW 的 UTC 桥存在约 -2.0653ms 的偏差，没有用 UTC 对齐单个 tick。QPC 量化为 100ns，最窄 JFR 夹界为零不表示零误差。

WPA 有 34 条缓存 Last Switch-Out 未随即刻同线程切换更新。按相同线程实际 Switch-In/Next Switch-Out 的顺序重建间隙，保留原始 CSV；待调度包含 Ready/Standby/DeferredReady，其它状态另列，避免将运行时间重复算成等待。622 个完整 tick 均可由运行、待调度、阻塞和其它间隙覆盖，最大数值残差 2.82×10⁻⁸ms。这是区间一致性检查，不能单独证明因果或对齐。没有大于等于 10ms 的主线程 JFR park 可作额外交叉核验，明确为不可用。

| 系统补采 tick | 完整 tick ms | 运行状态 ms | 待调度 ms | 阻塞 ms | ClassLoad 墙钟并集 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 607，最长 tick | 45.9768 | 34.2089 | 1.6822 | 10.0854 | 30.5581 |
| 610，另一慢 tick | 7.3136 | 6.6326 | 0.1011 | 0.5799 | 2.4323 |
| 698，决定 p99 | 5.0346 | 4.6750 | 0.1012 | 0.2584 | 0 |

**ClassLoad 与线程状态是两个重叠维度，不能相加。** 运行状态也含探针和中断等成本，不是纯 Java 算法 CPU。最长 tick 的 ClassLoad 包含 PathNavigationRegion 单次 17.1704ms；tick610 记录到 ClientboundMoveEntityPacket$PosRot 的 2.4323ms。类加载成本包含框架和外部 agent 处理，不能将这些值当成无插桩生产净成本。

这台 p99 tick 中，详细搜索 exclusive 为 2.0973ms、windowCurrent 0.0657ms、nativeProofCurrent 0.2330ms、persistenceDrain 0.0004ms，其余仍有未完整命名的执行工作。现有工具已能区分阶段和系统状态，但不等于所有执行成本都已定位。

ready 后等待三十秒并不会预先执行所有首次寻路、首次移动的类加载路径；本轮保持这些首次请求成本，未据结果增加预热或删除慢 tick。

## 审计与审查

独立 `audit.json` 重读七台原始 ticks，按降序秩复算 p99，并核对实际生产/场景包、jvm.args、配置、mod IDs、冻结早于启动及旧封存；全部通过。实际加载的 mod IDs 仅生产 mod、原场景探针、Forge、Minecraft 和 MixinExtras。系统补采之外的六台没有 WPR，单台系统诊断不进入版本均值。

派生汇总早期把关闭模块的未测字段写成零，旧 `results.json` 仅作错误派生记录保留，不能再引用其中控制模块的零值；有效入口是 `results-v2.json`，已改为 null。版本状态另有 `derived-results-index.json` 固化，原始记录和冻结主分析未改。首次 WPA 导出只有折叠的进程汇总，展开后才得到逐切换 CSV；两次导出保留。缺少可选 MyPresets 文件的 exporter 提示保留，实际退出码、逐切换数据与身份/算术检查通过。

DeepSeek 方法包 3,602B，87.308 秒正常返回 READY_TO_VALIDATE，本轮必须修改项无。结果包 5,621B，35.783 秒正常返回 **ACCEPT_WITH_GATES，本轮必须修改项：无**。

两轮均为 WSL 原生 `dsh --profile headless`，实际 provider=`deepseek`、model=`deepseek-v4.1-flash`，900 秒本地期限，每包自足并禁止工具与额外文件读取，设置哈希前后相同。共识只针对包内有限观测归档，不是原始记录的独立复现或生产发布审批。

归档落实八项边界：诊断不等于无探针生产执行；反向残差保留；控制缺测为 null；时钟/缓存 switch-out 修正及无 park 交叉核验限制保留；类加载与系统状态不相加；第七台隔离；不扣探针开销或误读调用/预热/存储计数；身份、原始数据与封存审计随档。区间相关及分解不作逐 tick 因果归因，更不补认旧净差异的根因。

本轮没有生产修复、默认包替换、提交、推送或发布。当前证据支持保留原始行复用的阶段收益，并把其它模块、类加载和系统状态作为明确的观测维度；不支持猜测性修改 RawRows 或其它生产模块来“修复”尚未复现的旧差异。

## 证据入口

- [六台主观测结果与缺测修正](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-observation-20261016/results-v2.json)
- [七台独立身份与原始指标审计](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-observation-20261016/audit.json)
- [系统时间线与类加载分解](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-system-20261016/system-analysis-v2.json)
- [时钟桥与导出边界说明](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-system-20261016/platform-clock-notes.md)
- [主观测冻结](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-observation-20261016/prelaunch-freeze-v1.json)
- [系统补采冻结](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-system-20261016/prelaunch-freeze-v1.json)
- [有限归档共识](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-observation-20261016/consensus-v1.json)
- [主观测封存清单](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-observation-20261016/evidence-manifest-v1.json)
- [系统补采封存清单](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-module-system-20261016/evidence-manifest-v1.json)
