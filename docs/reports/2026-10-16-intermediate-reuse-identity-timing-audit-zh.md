# 原始行复用测试身份与计时范围核对

日期：2026-10-16。响应“刚刚跑的代码是否与生产接入相同、其它生产模块是否影响计时”。本轮只读核对既有包、源码、运行记录和诊断数据，没有重新构建、启动服务器、修改生产代码或覆盖旧结果。

## 代码与实际加载身份

**刚完成的同版本重复性补测，使用的候选与生产接入阶段是同一个发行 JAR，也与旧正式复杂场景得到 6.714900ms 的候选相同。**

本轮独立复算 ZIP 条目与文件 SHA256，并逐台核对旧正式 16 台、补测实际运行 36 台，共 52 台 JVM 的实际 mods 文件、launch 身份记录和生产 common 配置；全部通过。36 台包括按原规则保留但不计入校准的四台无效块，不改变此前有效集。

| 身份 | 旧正式测试、刚完成补测及构建产物的关系 | SHA256 |
| --- | --- | --- |
| 基线生产包 | 两轮完全相同；当前默认 `build/libs` 发行文件也是此包 | `89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa` |
| 候选生产包 | 两轮完全相同；等于生产接入阶段 `production-build` 的构建产物 | `90eaacd3b8a701c076b64ae52c0a67275f4cad7aa23c89c579640fe44a776101` |
| 共同场景探针 | 两轮完全相同，作为独立 mod 加载 | `9d1c2cbe8347add10a0c264cd369f8f4fa8ff971db467901fa0249cd6cf78651` |

当前 HEAD 为 `3a04a23`，生产源码无未提交修改。当前 `src/main` 的 61 项文件同时匹配原生产接入的测前冻结和补测冻结。候选构建脚本仅重定向 build 输出目录、另建探针 sourceSet，没有替换 main 源码。

相对生产基线 `c7fa006`，`src/main` 只有 `BaseClusterTopology.java` 和 `TopologyWorkerRuntime.java` 两个文件变化。包的 69 个差异条目全部归于这两个源码所有者的类族及 Manifest；其它条目相同，候选生产包不含 `an/reuse/` 探针类。这是条目身份核对，不表示每个内部类都有逻辑修改。

需要区分源码与默认发行文件：**当前生产源码已经接入候选实现；本地默认 `build/libs/accelerated_navigation-0.1.0-all.jar` 仍是基线包。** 候选留存于研究目录，旧正式性能 FAIL 尚未放行，默认包没有替换。

## 计时包含的生产工作

**该 tick p99 测量的是完整 `MinecraftServer.tickServer` 的墙钟时间，包含其它生产模块和游戏更新；不能当作 RawRows 或 Base 构图的独占耗时。**

原场景探针 `TickMixin.measure` 包住 `runServer` 对 `tickServer` 的调用；统计选取提交至最后自然终态窗口相交的正常完成 tick。生产预算包装的 `beginTickEnvelope`/`finishTickEnvelope` 也在探针包围范围内，既有 JFR 调用栈确认了嵌套关系。

| 工作 | 与完整 tick 计时的关系 | 当前证据边界 |
| --- | --- | --- |
| `NavigationScheduler` 的 tick 处理、任务派发和同步详细搜索 | 计时范围内 | 旧诊断四台各 80 次同步搜索均在主线程完整 tick 内 |
| `SegmentedNavigationService` 的路径有效性检查、观察与推进 | 计时范围内 | 候选一台决定 p99 的 tick 采到 `GroundSegmentWindow.current → planningCurrent → executionReady` |
| `TopologyService.finishTick`、事实变化处理、持久化回调排空 | 计时范围内 | 持久化启用；现有记录没有该窗口或逐 tick 的成本拆分 |
| Minecraft 实体与区块更新、共同场景观察 | 计时范围内 | 不是导航构图专项计时 |
| worker 中的 Base 构图 | 与主线程并行，不将 worker 耗时直接相加到 tick | 仍可能经锁、CPU 竞争或任务到达时机间接影响主线程 |
| `server.tell(TickTask)` 的队列回调 | 是否计入取决于执行时刻 | 旧四台诊断每台的 16 个宏观结果回调均在所测 tick 区间外，不能将队列等待直接算作 tick 成本 |

两版其它模块代码及实际配置相同，但其运行成本、任务到达时机及与候选的相互作用不保证相同。共同探针在两版都启用，也不能据此证明探针交互完全等量。

## 既有诊断能确认到哪一步

下表来自先前的对称 JFR 诊断，两对 AB/BA，含额外共同诊断探针；不是旧正式净矩阵或刚完成的补测成绩。单位 ms。

| JVM | 决定 p99 的 tick | 完整 tick | 其中同步详细搜索 | 剩余墙钟 | 同 tick 并行 Base 构图重叠 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 基线 r1 | 698 | 4.3370 | 1.9192 | 2.4178 | 0 |
| 候选 r1 | 609 | 4.4949 | 0 | 4.4949 | 0 |
| 基线 r2 | 699 | 4.6388 | 2.1017 | 2.5371 | 0 |
| 候选 r2 | 609 | 4.5246 | 0 | 4.5246 | 0 |

候选 r1 的 p99 tick 采到上述分段路径有效性检查栈，证明其它生产工作参与尾部；稀疏采样不支持将整段 4.4949ms 都归给该函数。候选 r2 该 tick 没有采样，不能套用 r1 的归因。“剩余墙钟”也不是其它模块的纯 CPU 时间，还可能含观察、等待和线程抢占。

旧诊断还在四台的 tick602 记录到 `TopologyWorkerRuntime.publishRequestEvent` 的 monitor 等待，说明主线程会受同步影响；这些并非决定各台 p99 的 tick，不能据此解释旧正式均值增加。

**因此已经确认计时混合了其它工作，尚未定位旧正式复杂场景 5.105850→6.714900ms、增加 1.609050ms 的具体原因。** 不据此认定缓存有错、缓存无关或旧差异全是噪声，也不撤销旧性能门。

## 预热、持久化与其它 mod

52 台日志均可核对，发现的 mod IDs 仅 `accelerated_navigation`、`an_reuse_verification`、Forge、Minecraft 和 MixinExtras；实际 mods 目录共同只有生产包与场景探针。没有 Spark 或其它额外玩法 mod 加入这两轮实验。

刚完成的 36 台在提交快照时，worker 的 queued/running 各项均为零；请求窗口前后 completed.prewarms 增量全部为零。只能据此说明没有观察到该窗口继续完成预热任务，不能说 JVM 生命周期中从未预热，或排除所有其它并行工作。

持久化没有关闭。示例 `aa-complex-b5-candidate-y` 停服摘要包含 `writeRequests=541`、`recordsWritten=541`；这是全生命周期累计，不能当成请求窗口写入次数。原 JFR 配置也未启用 `jdk.FileWrite`，现有证据无法量化持久化在该窗口或各个慢 tick 的成本，不能断言它导致旧回退。

## 审计入口

本轮新增工具与结果仅位于 `.tmp/intermediate-reuse-identity-timing-20261016/`。复查旧生产 798 项、旧诊断 144 项、补测 762 项，共 1,704 项封存文件，全部零漂移；21 项补测测前输入核对通过。旧封存文档及数据保持只读。本轮结论是身份与计时范围核对，没有新增性能验收结论。

- [源码、包与 ZIP 条目核对](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-identity-timing-20261016/payload-audit.json)
- [52 台加载身份、生产配置及计时证据](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-identity-timing-20261016/records-audit.json)
- [原封存清单与生产源码状态只读复查](D:/Workspace/AcceleratedNavigation-forge-1.20.1/.tmp/intermediate-reuse-identity-timing-20261016/seals-audit.json)
