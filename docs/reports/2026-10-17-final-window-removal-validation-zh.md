# 最终窗口无消费工作删除验证

日期：2026-10-17。对应统一工作流程第4a步，旧编号T3a。状态：**有限结清**。机制和本轮功能验证通过，三对净性能筛选完成，DeepSeek最终共识为`ACCEPT_SCOPED`，本轮必须修改项无。

## 1. 改动与契约

普通宏观走廊的最终详细搜索直接使用请求最终目标及accuracy，不消费最终窗口的候选、成员集合或恢复候选。旧实现仍调用`prepareWindow`，在worker枚举窗口、创建集合和future，再经主线程回调排入最终搜索。

本轮仅修改`SegmentedNavigationService.nextSegment`的普通最终分支：

```java
work.corridor.component(destination);
queue(work,()-> {
    findSegment(work,Set.of(work.request.getTarget()),work.request.intent.accuracy());
    return null;
});
```

保留目的组件选择和`component`内的`Objects.requireNonNull`快照完整性检查。省去实际最终窗口计算及worker交接；保留最终搜索的独立scheduler任务，沿用500us启动门槛、haveTime、实时截止、公平顺序和当前tick额度，不把搜索并入`nextSegment`原子任务。

固定20ms/tick，正负余额不结转，无跨tick负债或偿债，不恢复历史预测和动态额度。scheduler源码未在本轮修改。原生详细搜索为同步调用；`awaitNative`只保存Supplier，由observe恢复，取消、替换、200tick规划TTL及关闭仍控制其生命周期。

中间窗口、共享分支、有限恢复、partial终态、真实到达和T2的最终段原range修复均保留。中间窗口helper/API仍有消费者，未作无关删除。现行导航契约中过期的“动态预算、保留负债”描述已更正。

## 2. 身份与方法

工作区已有未提交改动；本轮冻结基线包含已完成的T2修复。相对该冻结基线，61份生产源码仅上述一个Java文件不同。

| 身份 | SHA256 |
| --- | --- |
| B：`build-baseline-v2/artifacts/production.jar` | `0d3a13aab6e3289fe781dccf598a2ffe05bc8f74f33f2fef8f6a614eae6bf87c` |
| C：`build-candidate-v1/artifacts/production.jar` | `a6c494dabf78a1479b9fc5e620ab1f5a837a72e846ccc1d8c3438dab74ae3dcf` |
| 净测冻结reference probe | `48c7a13296f7dce289fbbec11258a0535dbd943b8731a479dae9f8f645e171d8` |
| 默认旧发行Jar，未替换 | `89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa` |

证据根目录为`.tmp/navigation-final-window-20261017/`。隔离构建并独立reobf，输出不覆盖。所有Java负载串行；启动及sceneReady后分别真实等待至少30秒。功能诊断与净性能隔离，净测不启用诊断agent、JFR或并行DeepSeek。

## 3. 契约与机制验证

- Gradle核心契约9项、Reverse integration契约3项分别通过；两套Node测试共47/47通过。净测全部结束后串行执行既有`verifyCoreContracts --args=macro-commit`，Committed macro snapshot契约4项通过，覆盖完整4096成员、不同section拒绝、已提交走廊成员保持及同版本边界恢复。
- B五场、C十二场及C等待中实际关服一场通过。每台有效控制JVM执行两轮预算契约、共120项断言，覆盖固定额度、不偿债、公平与完整计费。
- 普通零边首次/重复、跨section、宏观交付后取消/替换/改块、额度耗尽、离地等待恢复/取消/替换/TTL、最终Path发布后实际关服均覆盖。`macro-cancel`、`waiting-cancel`、`waiting-shutdown`为CANCELLED，`waiting-ttl`为预定UNAVAILABLE，这四项均无真实搜索或Path发布；`shutdown-final`在真实Path发布后正常取消。
- 额度耗尽场景记录宏观交付后当前tick余额不大于零，真实搜索发生于后续tick。搜索后的余额快照包含已执行的同步工作，不作为启动余额。
- 五个静态对应场景的真实搜索参数、原始节点序列完全相同；每场候选少一次最终窗口准备。中间段窗口保留。

| 诊断场景 | B首Path ms | C首Path ms | 窗口准备 B→C | 窗口worker独占观测分配 B→C |
| --- | ---: | ---: | ---: | ---: |
| 零边首次 | 144.906800 | 106.718601 | 1→0 | 154072→0 B |
| 零边重复 | 101.252499 | 51.342500 | 1→0 | 104536→0 B |
| cross8 | 102.402501 | 103.301499 | 2→1 | 117592→13128 B |
| 当前tick额度耗尽 | 100.742000 | 51.815899 | 1→0 | 104464→0 B |
| 最终Path后关服 | 101.726000 | 51.247600 | 1→0 | 104464→0 B |

这些是插桩机制观测，不能用于净性能估计。cross8首Path没有减少，不能声称所有路线首Path减半。

## 4. 功能回归

`candidate-routes-v1`无重放的8/28格墙体、门口、正反向共八场，512/512实际到达，均SUCCEEDED。910条真实段搜索记录无最终range偏移。显式同一平地8格控制的`range=4`按预期EXHAUSTED且未到达；`range=12`实际到达并SUCCEEDED。该失败控制保留在分母中，未伪造成功。

`candidate-entries-v1`五项真实入口均1/1实际到达、SUCCEEDED，最终窗口worker计算均为零：

| 场景 | 真实搜索 | 中间窗口 | 恢复 |
| --- | ---: | ---: | ---: |
| 实体目标三次漂移 | 9 | 8 | 1 |
| 多目标查询就绪后采用 | 3 | 1 | 0 |
| 多目标查询未就绪时立即采用 | 2 | 1 | 0 |
| accuracy=0共享资格 | 2 | 0 | 0 |
| 发布后动态改块 | 3 | 2 | 1 |

查询就绪后采用仍包含查询裁决及后续分段搜索，本轮没有把其结构变化冒充收益。漂移、动态改块保留实际恢复流程。

`baseline-super-v1`与`candidate-super-v1`均成功完成304格实际路线，均有34次真实Super构建、20次详细搜索、19次中间窗口，无partial或恢复。窗口总数20→19，最终窗口worker调用1→0，最终窗口独占观测分配142456→0字节。首Path为379.602300→382.130800ms，实际到达为69897.295100→69846.933500ms；这些插桩单次观测不构成净性能收益声明。

上述有效运行均正常退出、无外部Java干扰、关闭清理通过。汇总为`functional-summary-v1.json`，包含所读分析及退出记录的SHA256。

## 5. 净性能筛选

预先固定三个独立JVM配对，顺序B/C、C/B、B/C；每JVM预热8/28×64，再正式8×512及28×512各一次。主要观察8×512全JVM分配，28×512为复杂路线控制，同时报告真实到达、首Path/到达时延、完整tick、CPU及GC。使用同一冻结reference probe，无诊断agent、JFR或并行DeepSeek。

通用分析器核验6/6运行、12/12正式trial技术有效，6144/6144真实Path、成功及实际到达，零超时。每个版本/场景为3台独立JVM、1536个实体。生产包与probe在各运行目录的实际哈希也已另行核对。

下表为每台JVM的trial指标在3台间的中位数；每个trial保留512个实体分母，本轮全部到达而无缺失。时延分位数先在trial内计算，不将6144个实体合并为一个分位数。所有时延及完整tick单位为ms，分配单位为MiB。

| 指标 | 8×512 B | 8×512 C | 28×512 B | 28×512 C |
| --- | ---: | ---: | ---: | ---: |
| 全JVM分配 MiB | 472.837 | 416.237 | 1007.146 | 949.890 |
| 主线程分配 MiB | 395.791 | 385.219 | 922.021 | 922.632 |
| 进程CPU ms | 3343.750 | 3218.750 | 3328.125 | 3015.625 |
| 首Path P50 | 210.179 | 162.283 | 269.988 | 262.021 |
| 首Path P95 | 310.324 | 223.825 | 419.395 | 411.532 |
| 实际到达 P95 | 3352.718 | 3203.906 | 10054.049 | 10003.620 |
| 全体实际到达 | 5603.380 | 5503.208 | 13903.283 | 14003.817 |
| 完整tick P99 | 24.316 | 24.467 | 24.919 | 24.291 |
| 完整tick最大值 | 24.870 | 24.813 | 25.413 | 25.373 |
| 完整tick总工作 | 711.467 | 649.360 | 1434.039 | 1491.062 |
| GC次数 | 0 | 0 | 1 | 1 |
| GC时间 ms | 0 | 0 | 6 | 4 |

配对差值与各版本中位数之差是不同统计量。以下按同一pair计算C/B变化百分比：

| 指标 | pair 1 | pair 2 | pair 3 | 配对变化中位数 |
| --- | ---: | ---: | ---: | ---: |
| 8格全JVM分配 | -11.605% | -12.025% | -12.495% | -12.025% |
| 8格进程CPU | +12.808% | -3.738% | -14.097% | -3.738% |
| 28格全JVM分配 | +1.116% | -7.158% | -5.717% | -5.717% |
| 28格进程CPU | -10.329% | -8.962% | +3.965% | -8.962% |

8格首Path P95三对变化范围为-27.874%至-14.442%，到达P95为-4.514%至-2.950%，与分配一起呈一致改善方向；完整tick P99为-1.443%至+1.750%，CPU方向混合，不能称为稳定下降。28格分配、CPU、首Path及到达仍有混合变化；全体到达中位数及完整tick总工作中位数略有退化，不能声称复杂路线全面改善。

全部正式窗口超过50ms的完整tick均为零；各场保留原始inclusive区间中的提交前1个tick，没有重新选窗。GC样本很少：8格B为0–1次/0–4ms，C均为零；28格B为0–1次/0–6ms，C均1次/4–7ms，不能推出稳定GC收益。

原始输入为`runs/pair-*`，通用完整报告为`performance-analysis-v1/summary.json`及`summary.md`，精简配对表为`performance-screening-v1.json`；未因指标退化追加不变候选或挑选样本。

本轮没有正式同壳A/A、统计非劣或最终C/N资格。分析器的complete/technicalValid只表示数据齐全且技术有效，不代表性能PASS；累计端点成本也不伪装为共同固定墙钟窗口的精确成本。

残余功能验证边界：缺成员异常为静态核对，未专门故障注入；实时读取同一`final work.request`的目标/intent并非新增行为；宏观交付后改块仅一例受控证据，不外推所有陈旧但完整快照。没有增加当前世界快照新鲜度门。

## 6. DeepSeek与原始记录

采用WSL原生`/usr/bin/dsh --profile headless`，provider为deepseek，model为deepseek-v4.1-flash。自包含文本包，禁止工具/读文件/执行/联网；保留prompt、实际transmitted、answer、process及exit，检查settings哈希不变和900秒超时。

| 轮次 | 结论 | 作用 |
| --- | --- | --- |
| `01-method` | REQUEST_CHANGES | 要求澄清快照契约、分支隔离、等待生命周期和预算准入 |
| `02-method-closure` | READY_FOR_INTEGRATION_VALIDATION | 保留独立搜索准入后，允许进入运行验证，必须修改项无 |
| `03-final-validation` | ACCEPT_SCOPED | 审查当前窄diff及全部机制、功能、净测结果，允许4a有限结清 |
| `04-final-clarification` | ACCEPT_SCOPED | 确认缺成员异常仅静态核对、实时读取非新增、改块仅一例受控证据 |
| `05-metric-correction` | ACCEPT_SCOPED | 撤回04中的28格分配笔误，维持有限结清，无代码或测试必改 |

04将“28格全体到达及完整tick总工作中位数略退化”误写成“分配中位数略退化”；05明确撤回。正确分配是B=1007.146MiB、C=949.890MiB，配对变化中位数-5.717%，三对方向混合。原始答复均保留，正式结论采用第五节实际数据，不采纳该笔误。

最终轮正常退出码0，未超时，settings SHA256前后相同；原始prompt/transmitted/answer/process/exit均保留。最终prompt SHA256为`6c39d6b5b983ab1fbc2ccb54f7e116471b74c2504e68c39f28c425609edd56c2`，answer为`1642c9c0f265337be5f42ac3b65e1a11a03cfda9d235e8b0fc83e882a4e99b18`。

DeepSeek进行的是自包含文本复审，没有独立读取仓库、重算哈希或运行实验。本地实现和原始记录的核验由本轮工具完成；不把文本认可写成独立实测认证。双方最终共识：允许4a按本报告范围结清，本轮必须修改项无；不结清4b/4c，不预批总体性能。

原始技术无效`baseline-smoke-v1`保留：初版测试壳重复注册tick观测器，关服时重复消费记录导致异常及shutdown记录缺失。修正测试壳后使用新构建/新目录；该轮从所有机制、功能及性能结论排除。B控制采用补全worker分配字段的`controls-analysis-v2.json`，旧汇总保留。

## 7. 结论边界

普通最终分支的无人消费计算和worker往返已删除，真实搜索输入及独立调度准入保留，本轮功能回归通过。8格主观察场景的三对净测分配和条件P95呈一致改善方向，但CPU与复杂路线指标混合，正式性能门仍未完成。DeepSeek最终确认4a可有限结清，无本轮必改项。

4b已就绪阶段接续、4c日志减量、后续路线优化及正式A/A/C/N仍未执行。不提交、不推送、不回滚已有工作区，不替换默认发行包。

## 8. 证据索引

以下路径均相对证据根目录`.tmp/navigation-final-window-20261017/`：

| 证据 | 路径 |
| --- | --- |
| B/C冻结源、工具身份及独立生产包 | `build-baseline-v2/`、`build-candidate-v1/`，各自`build-identity.json` |
| 核心及Node契约 | `core-contracts.log`、`node-tests.log`、`macro-commit-contracts.log` |
| B控制有效汇总 | `baseline-controls-v2/controls-analysis-v2.json` |
| C控制及等待中实际关服 | `candidate-controls-v1/controls-analysis.json`、`candidate-waiting-shutdown-v1/controls-analysis.json` |
| 静态机制配对 | `mechanism-summary.json` |
| 墙门/range、真实入口、Super304 | `candidate-routes-v1/`、`candidate-entries-v1/`、`baseline-super-v1/`、`candidate-super-v1/` |
| 功能汇总 | `functional-summary-v1.json` |
| 预定净测配置与全部六台原始结果 | `performance-config.json`、`runs/pair-*` |
| 净测完整及精简配对汇总 | `performance-analysis-v1/`、`performance-screening-v1.json` |
| 五轮原始审查 | `deepseek-{prompt,transmitted,answer,process,exit}-<round>.*` |
| 封存及身份断言 | `seal.mjs`、`seal-v1.json` |

封存清单记录原始结果、所有审查、当前61份生产源、报告及包身份的SHA256，并断言当前源匹配C、B/C仅一个生产文件不同、默认旧Jar未变。原始无效运行和旧汇总仍保留。封存不代表正式性能准入。
