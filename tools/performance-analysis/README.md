# 导航基准结果校验与汇总

独立、无日期的离线分析入口，使用 Node.js 内置模块，无需 npm 安装。当前环境验证为 Node.js 22。

适用于本项目 `CURRENT_PERFORMANCE_REFERENCE_V1` 导航记录与 `REFERENCE_FULL_TICK_V1` 完整 tick 记录。运行名称、版本数、JVM 配对数、距离、并发、重复次数和附加场景维度都由配置声明。其他历史探针格式需要明确适配，工具不猜测缺失字段。

## 使用

已有冻结世界/运行库环境下，可用共享runner运行当前构建的独立JVM，无需复制每轮日期脚本：

```powershell
./tools/performance-analysis/run.ps1 -Run pair-1-baseline -Build .tmp/my-stage/build-baseline -OutputRoot .tmp/my-stage -Variant baseline
./tools/performance-analysis/run.ps1 -Run pair-1-candidate -Build .tmp/my-stage/build-candidate -OutputRoot .tmp/my-stage -Variant candidate
```

runner复用`.tmp/current-performance-reference-20261016`的固定模板和公共probe，记录实际包/硬件身份、完整tick、退出及Java干扰，启动和场景就绪各真实等待30秒。正式负载固定为预热8/28×64及8/28×512各一次；`-Mode formal`表示记录格式，不是统计准入。每台运行串行执行，输出不得存在，分析仍需显式声明矩阵。`-Variant native`使用同包bypass，身份须另在配置中声明。

在仓库根目录执行：

```powershell
node tools/performance-analysis/analyze.mjs --config tools/performance-analysis/examples/reference.json --out .tmp/performance-analysis/reference
```

输出目录必须尚不存在。修改输入或重新汇总时使用新的输出目录，旧报告和原始数据不会被覆盖。配置内的 `runsRoot` 相对配置文件解析，各个 `directory` 相对 `runsRoot` 解析；`--config` 和 `--out` 相对当前工作目录解析，也接受绝对路径。

`examples/reference.json` 对应已有六台参考实验。现有数据仅第一对有效，所以这个示例目前应退出 **1**，报告 `2/6` 个有效 run、`12/36` 个正式 trial。这表示工具正确检出了未完成实验，不是工具运行失败，也没有新启动服务器。

后续实验应使用本目录入口。原日期目录中的分析器、汇总、封存和实验脚本保留作历史材料，不会自动调用这个新入口；后续 runner/封存步骤须显式指向新配置和新输出，不能继续用旧 `summary.complete` 作为新工具的证明。

退出码：

| 值 | 含义 |
| --- | --- |
| 0 | 声明的全部运行/场景/重复齐全，并通过技术校验 |
| 1 | 缺少或存在技术无效运行；仍输出有效子集和全部排除理由 |
| 2 | 配置、命令行或输出错误 |

功能性超时是正式结果，保留其成功率和成本，不会单独造成技术无效或退出 1。报告始终标记 `performanceVerdict=NOT_EVALUATED`；本工具不自动宣称功能或性能准入通过。

## 文件

| 文件 | 用途 |
| --- | --- |
| `analyze.mjs` | 命令行入口，写入新的报告目录 |
| `analysis.mjs` | 配置、输入校验、统计及格式化，无导入时写文件副作用 |
| `analysis.test.mjs` | 合成独立运行、损坏记录和命令行回归 |
| `examples/reference.json` | 当前已冻结实验的可直接执行示例 |

每个 run 需要 `launch.json`、`exit.json`、`performance-comparison.json`、`full-ticks.json`、`shutdown.json`。输入 JSON 支持 UTF-8 BOM。运行目录不会被扫描成“存在什么就算什么”，只有配置明确声明的运行和场景才参与。

## 配置

示例展示了全部配置项，以下规则供新实验使用：

- `schema` 固定为 `NAVIGATION_BENCHMARK_CAMPAIGN_V1`，`name` 描述当前实验范围。
- `variants` 声明报告中的 `id`、探针实际写入的 `resultVariant`、启动记录的 `launchVariant` 及各版本预期 `productionSha256`。例如原生 bypass 的原始名称是 `vanilla-same-runtime`。支持单版本或多版本，以及各版本使用不同的冻结生产包。
- `runs` 声明 `id`、`directory`、`variant`、`pair`。名称不要求 `pair-N-*` 模式。每个配对必须恰好声明每个版本一次，进程不能被重复算作独立 JVM。若报告 `id` 不等于 `launch.json.run`，另外声明 `launchRun`。
- `scenarios` 列出每台 JVM 必须覆盖的正式场景，`repeats` 显式列出重复编号。组合必须逐项齐全且唯一，重复 trial 不能补偿缺场景。
- `scenarioFields` 默认是 `["distance","concurrency"]`。如需区分冷暖，可增加 `cacheState`，并在每个场景和探针 trial 中写入它；`distance`、`concurrency` 仍必须存在。
- `warmups` 显式列出预热场景及其 `repeat`，没有预热写空数组。预热同样校验，但不进入正式统计。`minimumReadySeconds` 同时约束 ServerStarted 和 sceneReady 后的真实时间。
- `identity` 声明生产/探针 mod 文件名、预期探针 SHA256 和世界模板 SHA256；还会比较所有其他 mod、Java/Forge、硬件记录、共同 JVM 参数和探针负载参数。身份校验依据启动记录中的声明值与配置，不会重新读取历史服务器的 Jar 来证明当时加载字节。
- `ignoredJvmArgPrefixes` 只列共同参数比较时允许变化的参数，例如输出路径、原生 bypass 开关和每台进程的 socket 路径。其他实验控制参数仍参与严格比较。
- `deadlineMillis` 可列共同墙钟截止点，输出各点首 Path 和物理到达比例。记录在截止前结束且存在未达结果时，比例为 `null`；已观察数量单列，不假设未观察的未来结果。

配置用于事先定义“应当有什么”。工具不会从现存数据反推期望矩阵，也不会为缺失实验自动缩小范围。

## 校验

校验包含完整场景/重复/预热覆盖，原始版本与启动身份，实体数与唯一身份，逐 actor 计数与时间戳，成本差值与原始快照，完整 tick 唯一性、顺序、区间覆盖，以及外部 Java 干扰和关闭清理。

共同设置和路线输入以配置顺序中第一台通过本地校验的 JVM 为比较锚点；后续运行须一致。生产包、探针包和模板另有配置中的明确预期哈希。配对路线按 tile/slot/rotation 匹配，再核对起终点和 routeKind，不依赖数组顺序。整个 run 有坏数据时整台排除，不择优留下其中的快场景。

原始 `serverSubmitTick..serverEndTick` 为完整 tick 的声明区间。旧探针可能包含提交前一个 tick；本工具保留该历史统计范围，另外输出 `preSubmissionFullTicks`、`postObservationFullTicks`，并验证声明区间确实包含提交和结束时刻。不会为了改变 p99 而静默改选 tick。

## 统计口径

- **超时与失败：** 全部有效 trial 都进入成功率、到达率、CPU、分配和 tick 统计；零成功和零成本不会被当作缺失。进程级超时、崩溃或记录损坏是技术无效，单列保留。
- **独立样本：** 同一 JVM 的多个 repeat 不冒充多台 JVM。率按全部 repeat 的总分母计算；其他指标取每个 trial 指标的算术均值，再按独立 JVM 给出中位数、范围和贡献数。其中 tick/时延分位数的 JVM 值是 trial 分位数的均值，不是合并原始样本后的分位数。
- **条件时延：** trial 内 p50/p95 使用 nearest rank，只对已观察到首 Path/到达的 actor 计算，同时保留全体分母。某个 repeat 完全没有该时延，整台 JVM 的该指标为 `null`，贡献数和缺失数明确报告。
- **三种时间：** `measurementWindowNanos` 是记录观察窗，包含收尾；`lastObservedArrivalNanos` 是最后一个已到达者；`allPhysicalArrivalNanos` 只有该 trial 全体真实到达才有值。后端全体 SUCCEEDED 不是这三项的替代品。
- **配对比较：** 相同 `pair` 的 JVM 按场景计算差值和相对变化；分母为零或结果缺失时相对变化为 `null`。没有自动置信区间或性能门，不能把一个配对的改善叫作稳定优势。
- **计量限制：** CPU 保留计数器分辨率，搜索 wall 不等于 CPU。仅凭现有累计端点计数不能重建共同固定时间窗的 CPU/分配，本工具不插值伪造这些数据。

## 输出

| 文件 | 内容 |
| --- | --- |
| `summary.json` | 完整结果、每台 JVM、条件/缺失贡献数、配对变化、截止点、技术问题和来源哈希 |
| `trials.csv` | 每个正式 trial 的指标及时间/成本边界 |
| `cells.csv` | 每个版本/场景的独立 JVM 汇总指标 |
| `summary.md` | 中文可读概览；未完成也保留无数据 cell |

`complete` 表示全部**已声明的有效数据**覆盖齐全；`technicalValid` 表示没有被排除的声明运行；`coverage` 给出应有与实际有效数量。`functional` 单列成功、到达和超时，不参与伪造“技术无效”。各指标的 `count/missingCount` 与 `sampleCount/trialCount` 分开。

输出保留配置、分析器代码及已读取输入文件的 SHA256。分析器不能独立证明日志未被人为伪造，也不替代实验预注册或发行包封存。

## 验证

```powershell
node --test tools/performance-analysis/analysis.test.mjs
```

覆盖整组场景漏检、重复场景、超时成功率/成本遗漏、不同场景维度、JVM 重复计数、版本/包/模板混用、路线漂移、快照差值损坏、缺失/重复/截断 tick、条件时延、观测截止不足、CSV 转义和输出保护。
