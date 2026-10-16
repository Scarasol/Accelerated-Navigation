# 原型验证的性能诊断工具栈

日期：2026-10-15。用户要求改进验证方法，并确认不限于 JFR、spark 的工具组合。当前环境为 Windows、Temurin Java 17.0.18。本文确定下一轮研究使用的工具及证据规则，不改变候选算法、生产源码或已冻结的历史实验。

**选定组合：JFR + 自定义阶段事件作为主诊断；JMH 1.37 做局部内核对照；现有端到端探针作最终复核。** ThreadMXBean 与工作计数辅助归因，WPR/WPA 按需深入系统层，spark 用于游戏现场快速观察。工具按问题选择，不在每轮同时全部开启。

## 工具职责

| 工具 | 定位 | 应回答的问题 | 不能单独证明 |
| --- | --- | --- | --- |
| JDK JFR、jcmd、jfr CLI | 主诊断和自动采集 | 热点栈、分配、GC 暂停、锁竞争、park、文件/网络事件、JIT 等是否与慢阶段重合 | 采样占比不是单请求精确 CPU；没有事件也未必没有成本 |
| 自定义 JFR 阶段/关联事件 | 请求与实际工作之间的时间线 | 哪个请求等待哪个 flight/build；任务什么时候入队、执行、发布；哪个具体阶段耗时 | 耗时不自动等于 CPU；共享工作不能按消费者数量重复计算 |
| JMH 1.37 | 从原型抽取的局部内核对照 | 同输入下扫描、形状判定、构图中间产物或边界内核的耗时/吞吐与分配是否改善 | 整服首次请求、tick 和自然到达收益 |
| ThreadMXBean、原生 long 计数 | 可选低层补证 | 工作线程累计 CPU、分配字节、实际扫描/碰撞次数、唯一输入与复用命中 | CPU 计数支持不等于亚毫秒精度；wall-CPU 差值不能直接命名为锁等待 |
| WPR + WPA | 按需的 Windows 系统层诊断 | 线程在运行、Ready 等 CPU、阻塞之间如何分布；系统 I/O 是否参与延迟 | 不能单凭系统栈完整解释 Java JIT 方法；需要与 JFR/线程身份对齐 |
| spark | 实服观察与交叉检查 | 哪些 tick/线程栈值得调查，现场行为是否与隔离实验一致 | 默认采集范围不能代表全部 worker；不能由主线程栈推断后台构图成本 |
| jdk.jfr.consumer 自动读取、现有 IDEA 查看器 | 数据提取与可视化 | 自动产出每工作单元/每阶段明细、热点、暂停及缺测检查，并按需看火焰图 | 图片本身不是完整原始数据或因果证明 |

JDK Mission Control 可作为需要深入查看标准 JFR 事件时的可选界面，不是运行验证的必装依赖。自动读取及结论生成不依赖用户手动打开 IDEA 或导出图片。

上游 async-profiler 的官方维护平台为 Linux/macOS，当前 Windows Java 主线不选择它。若以后另立真实 Linux JVM 对照，它可作为 CPU/wall/分配/锁分析工具；不能把切换系统后获得的数字直接拼入 Windows 对照。收费的深度插桩工具暂不作为必要依赖。

## 已完成的本地工具检查

工具目录：`.tmp/profiling-toolchain-20261015/`。

- Java、javac、jcmd、jfr 为本机 Temurin 17.0.18；WPR/WPA 已存在，读取了可用配置，未执行本任务的正式系统采样。
- 已生成 `diagnostic.jfc`。Java 执行采样周期 10ms、native 方法采样 20ms；分配采用 JFR sample 事件。锁、park、sleep、文件/网络长事件门槛设为 2ms，safepoint begin 为 1ms，GC 暂停与数据丢失事件开启。
- 自定义 `an.prototype.Phase` 使用 0ns 门槛、关闭该事件的栈采集。独立 Java 工具检查实际写出并读回 1 个带 workId/阶段/持续时间的事件，另读回 sleep 事件，未出现 DataLoss。
- Thread CPU time 与线程累计 allocated bytes 接口支持检查通过。尚未据此认证本机短阶段 CPU 计数的精度，后续须在具体实验中校准。
- JMH 1.37 的 core、annotation processor，以及 jopt-simple 5.0.4、commons-math3 3.6.1 已放入独立 deps 目录；按 Maven Central 提供的 SHA1 核对，并记录本地 SHA256，没有加入生产 Gradle。
- 显式初始化 `GCProfiler` 和 `JavaFlightRecorderProfiler` 的检查通过，JFR profiler 使用 `debugNonSafePoints=false`，不自动增加该 JVM 选项。

这些只是工具接线与依赖检查。没有运行 Minecraft，没有执行 JMH benchmark，没有产生候选性能数据，也没有证明新的实验 runner 已完成全部集成。`toolchain-verification-v1.json`、`smoke-result.json` 和 `selected-profilers-v2.txt` 保留实际结果。

准备过程保留两项记录：第一次请求旧制品的 SHA512 旁文件返回 404，随后改用仓库实际发布的 SHA1 并额外记录 SHA256；首次 `JMH -lprof` 全量枚举会初始化系统 profiler，xperf 启动因现有 NT Kernel Logger 返回 0xb7 而失败，未停止已有会话。后续采用 `CheckJmhProfilers.java`，仅检查选定 gc/jfr，不再把全量 profiler 枚举当无副作用的清单命令。见 `setup-failures.json`、`profiler-inventory-note.json`。旧检查源码和输出原样保留。

## 三种测量模式

### 诊断模式

使用小范围、明确输入的原型或服务器场景，启用 JFR 与任务/阶段关联。要同时覆盖 Server thread、实际 topology worker 与相关 IO/JIT/GC 线程，而不是只录主线程。

时间线至少关联以下节点：

1. 请求提交、加入共享 flight，以及实际唯一工作单元的身份。
2. worker 入队、开始执行与依赖开始/结束。
3. 中间产物建立/命中、合法锚点、局部边、Boundary 或候选本轮真正修改的阶段。
4. 后台完成、主线程发布入队与实际发布。
5. 真实详细搜索入队/执行、首条真实 Path、自然终态及清理。

使用 runId、requestId、flightId/workId、父工作 ID 和输入身份清单建立关联。Base/边界身份继续包含 GeometryKey、section 及原 loadIdentity/revision/halo/validity；不引入跨代组件身份。高频事件字段优先使用原生 ID，不为每次扫描分配 Map 或长字符串。

事件放在任务和阶段边界，扫描次数、实际碰撞次数、命中/未命中等用原生计数，窗口结束后汇总。不要逐 cell、逐边写 JFR 事件。异常出口也关闭阶段，并记录失败；采样开关不得改变算法、预算和交付语义。

### 内核模式

只有当假设可在不改变语义的前提下抽取为局部计算时，才使用 JMH。直接调用本轮原型中的真实内核，不为适配基准另写简化算法。

使用独立 fork、明确的 warmup/measurement、固定输入集合与结果消费，防止死代码消除、常量折叠和模式间 JIT 画像混合。记录实际 JMH 参数与各 fork 分布；重复调用次数不当作独立 JVM 数。

先测未附加深度 profiler 的内核对照，再按问题单独附加 gc 或 jfr。构建、查找、输入校验、例外路径、命中与未命中成本都应纳入所选操作定义；不能只测缓存命中而排除缓存建立与失效。

JMH 的 gc 指标不等于对象留存或进程峰值。研究新增缓存内存时，沿用已有 Instrumentation 与身份去重的对象根测量；只有发生无法解释的留存问题时，才另考虑 heap dump。

### 端到端模式

通过正确性与局部成本门后，使用新的、小范围服务器对照复核首 Path、后续移动、自然到达、完整 tick、失败、资源清理与图/任务数量。

保持独立 JVM、场景就绪真实 30 秒、固定输入与预算。冷图首次访问与 JMH 的热内核数据分别登记。最终比较关闭详细阶段追踪及深度 profiler；诊断运行单列，或另设同范围开销对照，不能把插桩运行与未插桩运行直接相减当净收益。

不续历史中止矩阵，不用调 JVM、预算或 TTL 救结果。若候选不能减少其声称的实际工作，或局部成本已抵消收益，就停止该候选，不先扩到大并发矩阵。

## 数据处理与结论规则

- 新读取器必须读取本轮真正需要的 GC 暂停、锁、park、IO、自定义关联等事件。旧 `ProfileRead` 只把执行、批量等待、分配三类事件写入 observations，不能只换一个 JFR 文件就声称已覆盖所有新诊断。
- 每轮记录实际启用事件、门槛、采样周期、线程覆盖、JFR/源码/包/PID 身份及 DataLoss。未启用、门槛过滤、缺关联与真实零值分别表示。
- 2ms 门槛面向长事件。若假设是大量短 IO/短锁的累计成本，另用有界诊断配置降低门槛，不能由零事件推出零成本。10ms 栈采样未命中的短函数，使用阶段事件与 JMH 补证。
- GC 暂停按根事件及区间并集计算；不把嵌套 GC 层、Base/Boundary inclusive 阶段重复相加。共享 work 计一次，消费者等待分别保留，后台无关工作不能排他归给某个请求。
- 阶段持续时间、采样热点、CPU、分配与端到端延迟分别报告。看到 wall 大于 CPU，只能提出等待/调度等假设，需用具体事件或 WPR 补证。
- 工具自检的数字永远不进入候选性能分母。诊断只能支持定位及机制判断，最终收益仍由无深度插桩的同输入对照支持。

## 应用于下一轮候选

中间产物分层复用首先记录：唯一 facts/halo 输入、不同 GeometryKey 消费关系、可复用普通部分与精确形状例外数量，以及产物建立、查询、失效、实际新增字节。JFR 定位成本，JMH 比较完整操作；没有实际复用机会就进入下一项。

边界连接复用首先记录：同一对当前 Base/GeometryKey/方向的唯一键及重复消费者、原缓存命中、实际几何计算次数和新增驻留。不能由某个热点栈推算命中率。

下一轮的执行顺序确定为：**小范围诊断定位和工作计数 → 正确性及必要的局部内核对照 → 小范围端到端复核**。工具栈确认完成，具体候选的事件接入、完整新读取器和实际测量留在该候选的新范围内实施。

## 一手依据

- OpenJDK 17 `jdk.jfr.Event` API：自定义字段、begin/end/commit、enabled/threshold/shouldCommit。
- Oracle Java 17《Troubleshoot Performance Issues Using Flight Recorder》：CPU/同步/IO/GC 诊断、事件门槛及测量开销限制。
- OpenJDK JMH 1.37 官方 Forking、DeadCode 示例与 gc/jfr profiler 源码；JFR profiler 默认可能增加 DebugNonSafepoints，本工具选择明确关闭。
- spark 官方 Command Usage：线程选择、Java sampler、采样间隔及本地保存能力。
- Microsoft WPR 与 CPU Analysis：ETW 采集以及 CPU Usage (Precise) 的 Running/Ready/Waiting 分析。
- async-profiler 官方 README：能力与维护平台；未以其替换当前 Windows 主诊断。
