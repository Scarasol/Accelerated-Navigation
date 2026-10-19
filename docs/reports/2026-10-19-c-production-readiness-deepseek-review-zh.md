# C 生产源码接入条件：DeepSeek 分批审查

日期：2026-10-19。用户要求先送审 DeepSeek，并强调无头对话没有历史上下文、须控制材料大小。
本轮仅送审、源码核查和证据封存，没有实施生产接入。

## 结论

**DeepSeek 同意 C 进入受控的源码集成、构建和真实游戏验证阶段；当前不批准默认发布或实服部署。**
三包均正常退出、未超时，并明确写明“本轮必须修改项：无”。
P 的空行外提继续作为可选研究候选，不随本次建议接入；C2、D 等其它隔离候选排除。

这是所提交源码片段、报告数据和接入方案范围内的文本审查。
DeepSeek 没有读取仓库、运行实验或验证实际游戏到达；没有因此取得稳定性能或发行资格。
本轮也未修改原筛选门、将失败追认通过，或删除任何不利样本。

## 版本及完整接入范围

- B：冻结的原生产基线。送审前核对当前 `src/main` 的 62 份输入，归一化后均与 B 相同。
- R2：B 加连续 canonical ALL_AIR 引用段处理。
- C：R2 加零/单组件 Base 内部边短路，以及任一端零组件的 Boundary 空结果短路。
- P：C 再加一条端口空行外提判断，不属于本次建议接入的 C。

**C 相对 B 是三个 Java 文件、80 行新增和 2 行删除，包含继承自 R2 的实现。**
不能只把 C 后加的两处构图短路作为完整生产接入范围。

| 文件 | 实际变化 |
| --- | --- |
| BaseClusterTopology.java | 连续空气槽摘要/保序段访问、BuildInput 账面增加 8B、零/单组件内部边短路 |
| SuperClusterTopology.java | 原参数检查后对空组件端点生成等价 BoundaryLinks |
| TopologyWorkerRuntime.java | 带 long 预检的加权引用更新、原标量回退及段分派 |

其它生产文件和资源保持。建议只应用这三个功能差分，保留所有既有工作，不整树替换。

## 分批方式及实际回执

复用 `tools/review/deepseek-review.mjs`，经 WSL `/usr/bin/dsh --profile headless`，
实际 provider/model 均为 `deepseek/deepseek-v4.1-flash`。
每包独立提供版本定义、相关代码或数据、范围、限制和待审问题；
明确禁止工具、读文件、执行、联网或写入，不要求模型追读仓库。

| 包 | 实际 UTF-8 字节 | 时长秒 | 结论 | 必须修改项 |
| --- | ---: | ---: | --- | --- |
| runtime | 7802 | 109.413 | ACCEPT_SCOPED | 无 |
| construction | 8006 | 80.375 | ACCEPT_SCOPED | 无 |
| evidence | 8296 | 64.990 | READY_FOR_INTEGRATION_VALIDATION | 无 |

前两包独立审查实际代码；最后一包重新提供必要上下文、直接 C/B 正反数据、
P 两轮结果、未通过的历史门、前两包结论范围和新增静态补证，审查接入决策。
每包小于调用器 8500B 上限；输出、错误流、退出、实际传输和哈希均保留。
三次退出码均为 0、`timedOut=false`、`mustFixesClear=true`，原配置没有修改。

配置前后 SHA-256 均为：
`071db56517e8f97ccde780095f71e5b264a1c7bc41694499cf81af7c2aca1858`。

## 主要审查意见

runtime 包认可：在最多 27 槽、canonical 单例和既有锁约束下，段访问保持身份及原顺序；
支持的四种引用增量具有单调性，安全终值预检允许合并；
不安全、异常或溢出条件回退原标量，保留异常时的写入前缀。
没有发现可给出反例的确定缺陷，但不作穷尽并发或 OOM 证明。

construction 包认可：零/单组件不可能生成跨组件内部边；
空端点 Boundary 的数组形状与原空结果相同，参数校验顺序保留。
它指出最初片段未直接展示 `BoundaryGeometry` 构造器副作用和 VOLUME 空掩码的证明。

主代理核对实际源码后，将以下补证随最后一包送审：

- 两个构造器只赋值，初始化器仅为私有空数组和私有 map，没有外部验证或写入。
- VOLUME 源组件小于 0 时跳过；目标组件小于 0 时在碰撞和 mask 写入之前返回。
- GROUND/VOLUME 共用 `addBoundaryMask` 和 `BoundaryLinks.from`；
  实际零组件图所有 `componentAt` 均返回 -1，因此空端不产生 mask。

最后一包明确认可上述摘录基本补齐静态缺口，仍限定为文本推断。

证据审查认可 C 的热 air、冷 distinct/mixed 和分配收益足以支持进入集成验证，
同时保留冷 air 均值、P95、CPU，以及热 distinct 的不利值。
R2/B 控制门、原 C/R2 CPU 门仍未通过；新 C/B 结果是新的决策输入，不能追溯改判。
答复中的“噪声”描述不构成本地已定位的因果，也不用于删除或扣减不利数据。

## 同意进入的下一阶段与尚缺事项

当前同意的是固定 C 后做最小源码集成及验证。后续执行仍需有对应授权和冻结协议，
本次“先送审”不自行启动实施、游戏、提交、推送或部署。

1. 维持 B 的回滚身份，核对并应用完整三文件功能差分，不夹带 P/C2/D。
2. 对实际接入后的源码执行构建、受影响契约及图/生命周期回归，核对候选 JAR 类身份。
3. 游戏运行前冻结有限 B/C 协议，覆盖冷暖长路线和并发请求；
   保留实际到达/终态、失败与超时分母、原参数、完整清理边界、
   首条原生 Path、CPU/分配/GC、tick 与相邻 tick 间隔，区分基线既有失败。
4. 在集成检查及游戏证据复核前，默认/发行 JAR 和实服部署保持待定。

稳定 CPU、同壳 A/A、整体性能资格和 C/N 尚未取得。
C/N 不作为本次窄源码集成的前置批准，不能据现有 C/B 声称优于原版；
后续正式声明及发行验收应按冻结协议分别判定。

## 身份和证据

送审前后核对：1519 份保护输入、346 份候选/工具冻结输入、
原空行外提的 410 份证据、复跑的 22 份证据，以及本轮额外冻结的 125 份文件均保持。
额外冻结包含当前源码、构建文件、调用器、前两包及关键结果身份。
三包 `prompt.txt`、`transmitted.txt` 与源材料逐字节相同；
prompt/answer/exit、provider/model、配置前后哈希及结论读取均通过核验。

证据根：`.tmp/c-production-readiness-review-20261019-v1/`。

- `runtime-packet-v1.txt`、`construction-packet-v1.txt`、`evidence-packet-v1.txt`
- `runtime-review-v1/`、`construction-review-v1/`、`evidence-review-v1/`
- `verify-before.json`、`verify-after.json`、`audit-v1.json`
- `verify.mjs`、`audit.mjs`、`seal.mjs`、`final-seal-v1.json`

本轮三个审核进程均已结束；没有新 Java/JMH/游戏运行，没有生产代码或默认 JAR 变化。
