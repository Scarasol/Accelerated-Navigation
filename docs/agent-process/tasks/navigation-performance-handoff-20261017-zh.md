# 导航性能优化交接记录

日期：2026-10-17。  
交接原因：当前会话停止工作，后续由其他会话接手。  
项目：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`  
分支：`1.20.1`  
已知 HEAD：`3a04a23`  
工作区：已有多项未提交修改，不能使用 `git reset --hard`、`git checkout --` 或回滚用户/前一会话修改。

本记录是当前工作的状态基准。后续会话应先阅读本文件，再阅读路线图、T1 报告和 T2 报告。T2 已经完成根因定位、生产窄修、独立验证和 DeepSeek 最终共识；不要重复把已结清的 T2 当成未验证假设，也不要把 T2 结果扩大解释为整体性能通过。

2026-10-17接手流程同步：后续统一使用本记录的1–10及4a/4b/4c、5a/5b编号，旧T0–T9仅作历史映射，详见第二节。当前方向及步骤6进度以第十八节为准：保留4b/4c、5a搁置、5b尾部候选封存且不接入生产。用户明确选择先进入6，覆盖此前楼梯优先顺序；关机后恢复并完成最终DeepSeek复审，6本轮窗口分配隔离研究已有限结清、未接入生产，稳定净收益未证实。第九、十节及第十三节实际完成度是各自交接时的历史状态；5a后续尝试和停止见第十四节，第十六节是5b上一会话的停止记录，不要求重跑已结清工作。

## 一、总体目标

目标是让当前加速导航在保持原版目标语义、到达正确性和复杂路线优势的前提下，超过同包原版寻路的总体性能。

“超过原版”必须分别回答：

- 是否更快得到真实 Path；
- 是否更早实际到达；
- 是否减少服务器长 tick；
- 是否降低包括 worker、主线程和 GC 在内的总成本。

当前比较约定：

- `B`：本阶段冻结的当前加速基线；
- `N`：同包原生 bypass；
- `C`：逐项候选修复或优化。

`C/B` 和 `C/N` 必须分别报告。局部搜索更快、tick 尖峰更低或成功率偶然提高，都不能单独证明整体超过原版。

各阶段B须写清实际源码/Jar身份，不把不同时期的B视为同一版本，也不把逐阶段百分比相加。5a拟以保持4b/4c的当前组合为B，已核对61份生产文件与对应冻结源一致，生产Jar SHA256为`959fc6a72b62bf213c2c85a88c79941dea0cb8829785614374f99dee1364b2f0`；执行前再次冻结/核验。默认旧发行Jar仍不代表当前源码。

## 二、完整 1–10 工作计划

| 步骤 | 历史标识 | 工作 | 当前状态 |
| --- | --- | --- | --- |
| 1 | T0 | 修复分析器、场景完整性、超时分母、到达时间、包身份、固定窗口和技术有效性 | 通用分析器已交付；正式协议、同壳A/A校准和性能门待补，须在正式验收前完成 |
| 2 | T1 | 补时间线和成本账本，定位首Path、分配、段间等待和真实入口结构 | **已完成**受控诊断；不代表实服权重或正式净性能 |
| 3 | T2 | 复现墙体/门口少到达并完成最终段原range窄修 | **已完成**；最终DeepSeek为`ACCEPT_WITH_GATES` |
| 4a | T3a | 删除最终窗口冗余计算及worker交接 | **有限结清**，见第十一节；保留中间窗口与独立详细搜索准入 |
| 4b | T3b | 合并已就绪依赖交付后的主线程接续 | **有限结清并保留**，见第十二、十三节；不因净测混合回滚，不先改跨tick身份边界 |
| 4c | T3附加日志候选，无单独旧T编号 | 高频DEBUG详情移入受保护TRACE | **有限结清**，见第十二节；与4b均未取得整体性能或发布资格 |
| 5a | 新增T4a | 短程一次原生详细搜索，中长程保留宏观分段 | **价值评估后搁置**；不恢复直线阈值试搜或净测，见第十五节，未取得优化准入 |
| 5b | 原T4，拆分后T4b | 减少段间停顿和无收益的重复详细搜索 | **本轮尾部隔离研究有限结清并封存**；机制成立、资源混合，未接入生产/未取得性能资格，见第十七节及[报告](../../reports/2026-10-17-segment-tail-handoff-validation-zh.md)；其他段间问题未自动解决 |
| 6 | T5 | 按实测份额优化外围分配、事实扫描或发布锁 | **本轮窗口分配隔离研究有限结清，ACCEPT_SCOPED**；资源混合、候选封存且未接入生产，不代表扫描/锁已全面优化，见第十八节 |
| 7 | T6，含T6a/T6b | 扩大共享实际入口覆盖，检查AI查询后采用的重复成本 | 入口诊断已完成，优化未开始；按采用时机分类，保持完整目标集合语义 |
| 8 | T7 | 在冷长路线确受构图依赖限制时缩小闭包、按需构建或研究边界骨架 | 已有诊断和候选排序；新生产优化未开始，不续旧失败矩阵 |
| 9 | T8 | 有条件推进快照去重，fill后置；固定弱槽缓存停止推进 | 低优先级条件队列；只有实际成本份额足够才继续 |
| 10 | T9 | 组合验收，分别报告C/B及C/N，明确适用范围与发行结论 | **未开始**；先补齐步骤1剩余协议/校准，不能由阶段结清自动批准发布 |

当前执行顺序（用户选择6后更新）：

**保留2、3及4a/4b/4c成果，5a搁置，5b尾部候选和6窗口候选封存 → 保留原楼梯独立缺口，按授权和成本筛选7→8→9 → 补齐1的正式协议/同壳A/A校准 → 10组合验收 → 按授权处理生产接入与发行。**

5b本轮候选已完成研究且未接入生产，不再作为默认待跑项；其他段间问题需有
新问题与收益证据才另立窄候选。完整执行顺序和进入条件如下：

1. **保持已结清成果。** 保留2、3及4a/4b/4c，5a继续搁置，5b尾部候选只保留
   独立副本与证据；不因有限结清重跑旧矩阵，也不将阶段结论当成整体性能通过。
2. **用户指定的6本轮研究已有限结清。** 成本诊断、隔离实现、功能验证、
   唯一资源对及重启后阶段末DeepSeek复审均已完成；候选隔离封存，
   不重跑本对、不追加不变候选挑结果，不自动接入生产或推进7。
   **原楼梯正向失败作为独立待办保留。** 复用正向UNAVAILABLE、反向到达的原件，
   必要时冻结当前B，按`/navigation -23 100 -198 -37 100 -195 false`及反向
   定向复现，区分宏观、局部目标、partial、执行和恢复。先形成根因结论，
   再决定窄修；不新增编号、不恢复5a整条试搜、不用整条原生回退补成功率。
3. **6之后按授权和7→8→9默认次序做成本筛选。** 先用当前保留4a/4b/4c版本的账本，
   关键份额不足才补必要定向诊断。6本轮只选窗口分配，7为共享入口及
   查询采用，8只在冷长路线真实依赖主导时推进，9为有条件快照去重、fill后置。
   这不是四项必做清单；收益上界不足就记录理由跳过，变更排序须有证据。
4. **补齐步骤1的正式验收剩余项。** 冻结计量及清理边界、失败/超时分母、
   同壳A/A、交错配对、B/C/N身份及主要效应/非劣门；可以服务于必要窄诊断，
   但必须在步骤10正式比较前完成，不用5b的实体累计或清理前快照冒充完整账本。
5. **执行步骤10组合验收。** 只纳入有准入依据的实现，不自动加入5b封存候选；
   分别报告C/B、C/N及真实到达、首Path、CPU/分配/GC、tick和tick启动间隔，
   保留4b/4c已知不利观测、跨tick身份边界及遗漏覆盖。
6. **依据验收结论和授权处理生产/发行。** 有限结清不自动批准生产接入、
   默认Jar替换、提交、推送或部署。

4b跨tick身份目前仍为独立条件事项，不插入默认下一项；若出现新的可复现
正确性故障，另记证据并调整优先级。此前“只同步顺序、6未开始”的状态已被
用户进入6的授权和第十八节的实际执行替代。

第 3 步已经结清；不要回到 T2 重新寻找已被证实的 `range` 根因，除非新代码改变了 `findSegment` 或导航参数契约。

每项候选按“冻结问题/契约/判据 → 冻结B/C身份 → 窄实现 → 机制与功能 → 预定小型净测 → 阶段集中回归及DeepSeek审查 → 记录范围结论”推进。具体尺度、停止条件、工具和正式验收要求以路线图的“每个候选的执行流程”“实验与验收策略”为准。小改动不逐项重跑完整矩阵，不覆盖失败、不追加不变候选挑结果；5a验证通过不自动授权写入生产区。

## 三、T1 已完成的关键事实

完整报告：

`docs/reports/2026-10-17-navigation-t1-coverage-completion-zh.md`

主要证据：

- 温热低负载首 Path 约 100ms，其中约 95–99ms 发生在结果交付后再次等待 scheduler 接续；
- 512 实体突发中，最终窗口主体每个场景约 50.1MiB 直接分配；
- 三目标就绪后采用：1 次查询裁决 + 2 次分段搜索；立即采用：2 次分段搜索；
- 自然 Melee、Brain、AcquirePoi、实体漂移、动态改块、原生 stuck/recovery 均已有受控入口覆盖；
- 自然 Brain 到达目标格后被 STOP 清理，不能自动归为导航失败；
- 真实受控失速触发了 nativeStopped、recover、缩段、刷新和 partial；
- 本轮 12 个可用覆盖场景、14 项覆盖检查、8 项 Node 测试通过，3 份 JFR 无 DataLoss；
- 生产算法和默认发行包在 T1 诊断期间没有修改。

T1 尚未证明：

- 实服入口权重；
- 稳定净性能；
- 正式 C/B 或 C/N；
- 所有长路线和所有动态场景在候选修改后的回归。

路线图：

`docs/designs/2026-10-16-navigation-performance-roadmap-zh.md`

## 四、T2 根因、修复和最终状态

完整报告：

`docs/reports/2026-10-17-navigation-t2-failure-root-cause-fix-zh.md`

### 根因

原始 `SegmentedNavigationService.findSegment` 对最终段和中间段统一使用：

```java
localRange(work, targets)
```

`localRange` 的规则是按当前起点到局部目标的直线距离计算，再限制到 `[16, 96]`。

原请求的 `range` 在入口处已经从 `Attributes.FOLLOW_RANGE` 捕获并保存到 `NavigationIntent.range()`。本轮失败样本中原请求 range 为 35，但最终段实际变成了 16、18 或 19。

PathFinder 的 range 不是单纯的“目标最大距离”。它会限制：

- 从起点展开的搜索半径；
- `walkedDistance` 的累计路径长度；
- `PathNavigationRegion` 的构造半径（约为 `range + regionOffset`）。

因此墙体/门口路线虽然目标直线距离不远，绕行探索仍可能被低 range 提前截断，返回 partial，随后最终进入 `EXHAUSTED`。

### 修复

当前代码为：

```java
float range = work.last
        ? work.request.intent.range()
        : localRange(work, targets);
```

即：

- 最终段使用请求捕获的原始 range；
- 中间段继续使用局部 range；
- 不修改 PathFinder、accuracy、visited multiplier、节点额度、恢复上限、20ms scheduler 软预算；
- 不增加整条原生 fallback；
- 不把 partial 改写成成功。

入口处 `FOLLOW_RANGE` 的实际来源仍在：

`src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java`

关键方法：

- `range(PathNavigation)`：读取 `mob.getAttributeValue(Attributes.FOLLOW_RANGE)`；
- `movementIntent(...)`：将该值写入 `NavigationIntent.range()`；
- `NativeNavigationSearch.find(...)`：把显式 range 传给原版 `createPath`/PathFinder。

### T2 证据

冻结基线：

- 8 格墙体：56/64 到达，8 个最终 partial；
- 8 格门口：52/64 到达，12 个最终 partial；
- 28 格墙体：64/64；
- 28 格门口：56/64 到达，8 个最终 partial。

同区域半径 43 的反事实对照：

- range 16/18/19 失败；
- range 35 可达；
- 失败搜索实际 pop 65–135 个节点，低于 `maxVisitedNodes=560`；
- 记录到 101–243 次 `walkedDistance` 范围拒绝。

因此排除了“查询区域太小”和“节点额度耗尽”。

### T2 候选验证

候选构建：

`.tmp/navigation-t2-failure-fix-20261017/build-candidate-v2/`

候选生产包 SHA256：

`0dbb9aed2106966282a2a8229247b984c67f1e11d8ebf4c80adc70db3504eb4e`

独立无重放候选结果：

- `wall8-all`：64/64；
- `door8-all`：64/64；
- `wall28-all`：64/64；
- `door28-all`：64/64；
- 4 场景共 256 个实体；
- shutdown 后 `records=0`、`active=0`、`failures=0`；
- 无外部 Java 干扰。

显式小范围控制使用同一个平地 8 格场景：

- 起点 `(-156,81,-147)`；
- 目标 `(-148,81,-147)`；
- `range=4`：实际 final range=4，15 次 pop，60 次范围拒绝，`EXHAUSTED`；
- `range=12`：实际 final range=12，`SUCCEEDED` 并真实到达。

这证明最终段没有被 `localRange` 的最小值 16 放大。

生命周期控制：

- `cancel-pending`：0 次搜索后 `CANCELLED`；
- `cancel-published`：1 次搜索后 `CANCELLED`；
- `replace-pending`：2 个请求代次，替换后成功。

验证输出：

- `.tmp/navigation-t2-failure-fix-20261017/candidate-core-v1/`
- `.tmp/navigation-t2-failure-fix-20261017/candidate-range-v2/`
- `.tmp/navigation-t2-failure-fix-20261017/candidate-controls-v1/`
- `.tmp/navigation-t2-failure-fix-20261017/baseline-replay-v1/`
- `.tmp/navigation-t2-failure-fix-20261017/baseline-equal-region-v2/`

### T2 结论

步骤3（旧T2）已结清，当时允许进入步骤4（旧T3），后续步骤4a/4b/4c状态见第十一、十二节；其后的历史计划曾进入5a，当前顺序以第二节为准。步骤3的结论范围仅包括短程墙体/门口和本轮28格门口覆盖，不外推到所有路线、实服权重或整体性能。

## 五、DeepSeek 调用方法和结论

使用的是 WSL 原生无头调用：

```bash
/usr/bin/dsh --profile headless "<完整自包含审查提示>"
```

实际 provider/model：

- provider：`deepseek`
- model：`deepseek-v4.1-flash`

每轮审查要求：

- 只使用提示包内文本；
- 禁止工具调用、文件遍历、执行命令和联网；
- 提示包自包含；
- 保存原始 prompt、实际 transmitted prompt、answer、process stderr/stdout 和 exit JSON；
- 记录 provider、model、退出码、超时状态、提示哈希、答复哈希；
- 检查 dsh settings SHA256 前后不变；
- 本地超时上限 900 秒；
- 不把无结论、超时或配置变化的轮次算作共识。

调用脚本模板：

`.tmp/navigation-t2-failure-fix-20261017/review.mjs`

本轮审查轮次：

1. `01-method`：返回 `REQUEST_CHANGES`。要求补充 range/region 解耦、节点额度和范围截断计数、独立 JVM 真实执行、显式小 range 控制。
2. `02-method-closure`：返回 `READY_FOR_INTEGRATION_VALIDATION`，本轮必须修改项为无；确认窄修设计和实验方法可进入运行验证。
3. `03-final-validation`：返回 `ACCEPT_WITH_GATES`，要求明确 range4/range12 的场景和候选实际 range 来源。
4. `04-final-clarification`：返回 `ACCEPT_WITH_GATES`，确认两项澄清已关闭阻断，可以结清 T2 并进入 T3，本轮必须修改项为无。

相关文件：

- `.tmp/navigation-t2-failure-fix-20261017/deepseek-prompt-01-method.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-answer-01-method.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-prompt-02-method-closure.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-answer-02-method-closure.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-prompt-03-final-validation.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-answer-03-final-validation.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-prompt-04-final-clarification.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-answer-04-final-clarification.txt`
- `.tmp/navigation-t2-failure-fix-20261017/deepseek-exit-04-final-clarification.json`

## 六、工具栈

固定工具和运行环境：

- Windows PowerShell；
- JDK `17.0.18+8`；
- Minecraft `1.20.1`；
- Forge `47.3.0`；
- Gradle 离线构建；
- ASM `9.7`，用于诊断 agent；
- Node.js 内置模块和 Node test runner；
- JFR consumer API，使用同一 JDK；
- WSL 原生 `dsh`，DeepSeek 文本审查；
- 固定世界模板、冻结 server.properties、完整 tick 记录；
- 生产 PathFinder / Path 发布公共探针；
- 诊断工具目录 `tools/navigation-diagnostics/`。

不需要安装额外 profiler。JFR 只用于来源定位，不作为精确阶段分配或 CPU 账本。

## 七、关键源码和工具路径

生产导航：

- `src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java`
- `src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java`
- `src/main/java/com/scarasol/acceleratednavigation/navigation/NativeNavigationSearch.java`
- `src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationIntent.java`
- `src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationRequestPath.java`
- `src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationAccess.java`
- `src/main/java/com/scarasol/acceleratednavigation/mixin/NativeNavigationSearchMixin.java`

诊断入口：

- `tools/navigation-diagnostics/README.md`
- `tools/navigation-diagnostics/build.ps1`
- `tools/navigation-diagnostics/run.ps1`
- `tools/navigation-diagnostics/analyze.mjs`
- `tools/navigation-diagnostics/analyze-failures.mjs`
- `tools/navigation-diagnostics/analysis.test.mjs`
- `tools/navigation-diagnostics/probe/an/production/TimelineScenarioProbe.java`
- `tools/navigation-diagnostics/probe/an/production/FailureSearchTelemetry.java`
- `tools/navigation-diagnostics/probe/an/production/mixin/FailureSearchMixin.java`
- `tools/navigation-diagnostics/probe/an/production/mixin/PathFinderFailureMixin.java`
- `tools/navigation-diagnostics/resources/an_failure.mixins.json`

冻结基线：

- `.tmp/current-performance-reference-20261016/frozen/`
- `.tmp/current-performance-reference-20261016/frozen/identity.json`
- `.tmp/current-performance-reference-20261016/frozen/accelerated_navigation.jar`
- `.tmp/current-performance-reference-20261016/review.mjs`

前序报告：

- `docs/designs/2026-10-16-navigation-performance-roadmap-zh.md`
- `docs/reports/2026-10-17-navigation-startup-and-preparation-diagnosis-zh.md`
- `docs/reports/2026-10-17-navigation-t1-coverage-completion-zh.md`
- `docs/reports/2026-10-17-navigation-t2-failure-root-cause-fix-zh.md`
- `docs/designs/2026-10-13-native-detail-navigation-contract-zh.md`

## 八、常用命令

构建冻结基线诊断工具：

```powershell
./tools/navigation-diagnostics/build.ps1 `
  -Output .tmp/navigation-t2-failure-fix-20261017/build-baseline
```

构建当前源码候选：

```powershell
./tools/navigation-diagnostics/build.ps1 `
  -Output .tmp/navigation-t2-failure-fix-20261017/build-candidate `
  -CurrentSource
```

运行 T2 失败套件：

```powershell
./tools/navigation-diagnostics/run.ps1 `
  -Build .tmp/navigation-t2-failure-fix-20261017/build-candidate `
  -Output .tmp/navigation-t2-failure-fix-20261017/candidate `
  -Suite failures -Scene 'wall8-all,door8-all,wall28-all,door28-all' -NoTrace
```

运行基线反事实：

```powershell
./tools/navigation-diagnostics/run.ps1 `
  -Build .tmp/navigation-t2-failure-fix-20261017/build-baseline `
  -Output .tmp/navigation-t2-failure-fix-20261017/replay `
  -Suite failures `
  -Scene 'wall8-all,door8-all,wall28-all,door28-all' `
  -NoTrace -Replay
```

分析失败记录：

```powershell
node tools/navigation-diagnostics/analyze-failures.mjs `
  .tmp/navigation-t2-failure-fix-20261017/candidate
```

Node 测试：

```powershell
node --test tools/navigation-diagnostics/analysis.test.mjs
```

生产核心契约：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=2 `
  --console plain verifyCoreContracts
```

## 九、原T3接手建议（历史安排，现已有限结清）

以下是原交接时的安排。4a/4b/4c后续均已有限结清，当前接手不重跑此清单；
第十三节进入5a的安排也已成为历史，当前顺序以第二节及第十七节为准。
保留原建议以解释当时的验证顺序：

1. 先复核 T2 报告、T2 evidence 目录和当前源码 diff；
2. 保留最终段 `actualRange == request.intent.range` 的诊断回归断言；
3. 先做 T3a 最终窗口减量的契约核对；
4. 覆盖最终段取消、替换、关闭、目标漂移、动态改块和 partial；
5. 以一个主要收益场景和一个退化控制做机制验证；
6. 再做 T3b 已就绪阶段接续，保留固定 20ms 软预算、截止和公平约束；
7. 高频 DEBUG 详情格式化减量单独成候选，不和调度改动混测；
8. 只有候选机制成立后才做独立 JVM 的 C/B 小范围筛选；
9. 不要把 T2 的成功率修复直接写成性能收益；
10. 不要修改默认旧发行包，除非后续会话明确完成正式候选验收。

原交接时T3尚未获得DeepSeek共识；后续4a已用独立实现、实测和五轮自包含审查获得`ACCEPT_SCOPED`，见第十一节。4b/4c随后按各自机制及阶段末组合回归另行验证，并取得单轮最终`ACCEPT_SCOPED`，见第十二节；没有复用T2或4a的结论作为批准。

## 十、原交接会话停止状态（历史）

本次交接完成后，当前会话不再执行代码修改、构建、实验或 DeepSeek 调用。没有提交、推送或回滚工作区。

## 十一、后续推进：4a有限结清

后续会话按用户授权完成最终窗口无消费者计算及worker交接删除；保留最终搜索独立scheduler准入、固定20ms/tick、正负余额不结转，无负债/偿债；中间窗口、共享、T2原range与真实到达契约不变。

正式报告：`docs/reports/2026-10-17-final-window-removal-validation-zh.md`。任务记录：`docs/agent-process/tasks/final-window-removal-20261017-zh.md`。证据根目录：`.tmp/navigation-final-window-20261017/`。

机制、取消/替换/等待/TTL/真实关服、墙门正反512/512、真实入口5/5及Super304对照通过。Super保留19次中间窗口，只删最后一次。三对净测6/6独立JVM、12/12正式trial技术有效，6144/6144到达；8格全JVM分配三对下降11.605%/12.025%/12.495%，CPU、tick及28格指标混合。无正式A/A、统计非劣或C/N，不宣称整体性能通过。

DeepSeek最终轮`05-metric-correction`为`ACCEPT_SCOPED`，本轮必须修改项无，允许4a有限结清；此前审查中的覆盖表述及28格分配笔误已澄清。保留缺成员异常未故障注入、宏观交付后改块仅一例受控证据等边界。原始审查及无效运行均保留，默认旧发行Jar未替换。

本节结清时下一项为4b，后续完成情况见第十二节；5–10不因4a自动结清。第十节描述的是原交接会话的停止状态，以上为后续推进事实。

## 十二、后续推进：4b、4c有限结清

用户授权小改动适当简化、复用审查代码后，完成就绪接续与高频日志减量。相对已结清4a基线只改`NavigationScheduler`与`SegmentedNavigationService`，75行新增、16行删除；当前组合生产Jar SHA256为`959fc6a72b62bf213c2c85a88c79941dea0cb8829785614374f99dee1364b2f0`。默认旧发行Jar未替换。

成功的宏观、共享和中间窗口回调合并请求主线程trampoline，复用原队列、500us独立准入、优先级/FIFO/维度轮转、实时haveTime和原43ms截止；只花原tick剩余20ms额度，不结转正负余额，无负债/偿债。新envelope撤销旧窗口。envelope外成本始终计入累计charged，紧接下一envelope正常采样时记outside，不追写旧sample；若下一envelope跳过，则没有对应sample，但累计计费仍完整保留。

DEBUG保留摘要，完整走廊、成员、搜索与恢复详情移入受保护TRACE。仅4b温热零边首Path 51.3425→4.4189ms、cross8 103.301499→21.033801ms，搜索输入及原始节点相同；组合候选保持对应语义，DEBUG正文减少79.86%–85.08%。以上为机制证据，不作净性能收益。

集中回归通过：每台控制JVM两轮172条预算断言、12项生命周期加等待实际关服、两场墙门128/128、range4反例/range12正例、真实入口4/4、Super304。Core 9项与Reverse integration 3项分别通过，Node 49/49通过。未重跑4a全部八场路线，恢复TRACE未单独注入，相关边界保留。

仅一对净测，2/2独立JVM、4/4正式trial技术有效，2048/2048实际到达。8格全JVM分配-1.137%，但CPU+18.090%、首Path P50+23.306%、P95+5.030%；28格分配-1.394%、CPU-5.991%。结果混合，不用完整tick下降掩盖envelope外成本，不因不利数据追加相同候选挑结果。正式A/A、统计非劣及C/N未完成。

最终DeepSeek自包含文本审查`deepseek-final-v1`为`ACCEPT_SCOPED`，本轮必须修改项无，退出0、未超时、settings哈希不变。双方同意4b/4c仅按当前实现与回归范围结清，允许进入第5步，不构成稳定总体性能或发布批准。8格CPU/首Path回归及遗漏覆盖保留到后续阶段；第5步尚未开始。

正式报告：`docs/reports/2026-10-17-ready-dispatch-and-logging-validation-zh.md`。任务记录：`docs/agent-process/tasks/ready-dispatch-and-logging-20261017-zh.md`。证据根目录：`.tmp/navigation-ready-logging-20261017/`。

后续复用`tools/navigation-diagnostics/analyze-controls.mjs`、`compare-controls.mjs`、`tools/performance-analysis/run.ps1`及`tools/review/deepseek-review.mjs`；按新配置/输出目录运行，保留原始失败及旧审查，不复制日期版脚本。小改动先定向验证，阶段末集中回归/审查；默认不为每项重跑完整净测矩阵。

## 十三、当前会话最终决策：保留4b，短程分流只做隔离验证

本节是原会话停止前的交接结论。后续5a实际推进以第十四节为准；本节保留当时状态。

### 4b/4c 的生产状态

- 用户已明确：如果4b已经接入生产，则保留，不回滚。
- 当前工作区中的4b就绪接续与4c日志减量实现继续作为生产候选/生产区现状保留；不得因为8格净测出现CPU和首Path回归就直接删除。
- 4b的收益目前难以用短实验稳定测出。天然多线程交接时序可能导致一次测试没有发生有效合并，因此已有一对净测只能记录为混合结果，不能据此证明4b无价值，也不能据此证明4b有稳定收益。
- 后续不再为4b追加零散的单次净测。若最终阶段仍需判断稳定性，应在预先冻结的A/A与交错配对协议中估计波动，再决定是否保留或调整；在此之前不改4b生产代码。
- 已知4b边界仍保留：`0.5ms`是启动门槛而非执行上限；接续原生搜索不可抢占，可能跨越当前tick剩余时间；`readyDeadlineNanos`等窗口字段的跨tick身份边界属于后续可单独硬化的问题，当前没有证据证明它已经造成故障，不得借此回滚4b。

### 短程分流验证的范围

用户已确认下一项验证方向：

> 短程请求尝试一次原生详细寻路；中长程继续使用宏观分段。验证先不接入生产。

该验证应作为隔离候选（暂记为路线图5a）处理：

- 候选源码必须放在`.tmp`下的独立源码副本或独立构建输入中；
- 不修改`src/main/java`中的生产实现，不替换默认发行Jar，不修改当前4b/4c生产候选；
- 不把“8格”直接硬编码成长期生产阈值。应先冻结可解释的短程判定规则，并记录显式`range`反例和正例；
- 短程候选需要跳过宏观走廊、窗口准备和宏观交接，只提交一次真实原生搜索；保留原始`range`、`accuracy`、`visitedMultiplier`、能力、目标集合、动态实体和取消/替换语义；
- `partial`、`null`、`unavailable`、取消、目标漂移、动态改块和关闭后的终态必须明确，不得添加无界宏观回退，也不能用降低成功率换取速度；
- 中长程至少保留一个28格/中程控制和一个304格Super长路线，证明它们仍走宏观分段；
- 验证顺序应是“机制/功能控制 → 小型净测”，不启动完整验收矩阵。净测至少包含单实体短程、512实体8格并发和28格中程退化控制；只有在机制成立后才考虑扩大样本。

### 当前会话实际完成度

- 已阅读当前路线图和`SegmentedNavigationService`关键分流链路，确认短程入口位于`initialize`/`macroCandidate`之前可设计候选。
- 尚未创建短程候选源码副本。
- 尚未构建短程候选Jar。
- 尚未运行短程机制、功能或性能验证。
- 本次停止前没有修改生产Java、默认发行包、现有证据目录或审查脚本；中断的读取命令没有产生文件写入。

### 接手执行入口

接手者应先阅读本节、路线图5a部分及以下现有报告，再开始任何候选编辑：

- `docs/designs/2026-10-16-navigation-performance-roadmap-zh.md`
- `docs/reports/2026-10-17-ready-dispatch-and-logging-validation-zh.md`
- `docs/reports/2026-10-17-final-window-removal-validation-zh.md`
- `docs/reports/2026-10-17-navigation-t2-failure-root-cause-fix-zh.md`

候选构建和实验应复用：

- `tools/navigation-diagnostics/build.ps1 -CurrentSource -SourceRoot ...`
- `tools/navigation-diagnostics/run.ps1`
- `tools/navigation-diagnostics/analyze.mjs`
- `tools/performance-analysis/run.ps1`
- `tools/performance-analysis/analyze.mjs`

每次新实验都必须使用新的`.tmp`输出目录，冻结实际Jar/源码身份，保留技术无效运行和原始日志；不要覆盖4b/4c已有证据。若候选尚未完成机制和功能验证，停止在候选阶段，不得写入生产区。

## 十四、5a执行与短直线长绕行控制

用户确认执行5a后，追加原路线`/navigation -23 100 -198 -37 100 -195 false`。此路线是原楼梯正向，反向亦须验证；旧57为宏观连接数，不是57格或当前结果。

证据根为`.tmp/navigation-short-native-20261017/`，已冻结61份生产输入、默认Jar、baseline-source及candidate-source；候选只修改独立副本的SegmentedNavigationService，生产4b/4c保持。尝试时方法及逐用例预期见`protocol.md`和`method-packet-v4.txt`。四轮方法审查均未取得批准；第三轮答复是REQUEST_CHANGES，但固定解析器未识别其“结论：”前缀，不能误当共识。v4异议的本地源码核对及取舍另存，不冒充批准。

已停止候选曾以初始化原完整归一目标集到实际脚部格均不超过16格作为首次尝试资格；完整Path真实执行，partial/null等唯一升级原宏观。用户随后质疑这是否重新引入已舍弃的搜索，当前不再把此方案作为默认推进方向。16格直线半径不足以识别实际短程，先整条试搜再宏观会给长绕行请求增加搜索成本。不能以此代替对实际路线结构的分流判断。

B隔离构建v3与C构建v1成功。B城市两向诊断技术有效：正向17次原宏观分段搜索、UNAVAILABLE/未到达；反向36次分段搜索、SUCCEEDED/到达。B没有整条原生搜索，“正向/反向”仅为路线方向标签。C城市运行在用户质疑方案后由本会话主动中止，部分日志保留，无完整功能结论。所有净测、完整控制和阶段回归未运行，任务未结清。详见[诊断及停止记录](../../reports/2026-10-17-short-native-isolation-stopped-zh.md)。生产61份输入及默认Jar未变，4b/4c未动。

## 十五、5a继续探索价值评估

用户要求先判断方向是否值得继续。只读核对后决定：**当前搁置5a独立分流主线，不继续构建、运行旧候选或启动净测。** 见[价值评估](../../reports/2026-10-17-short-native-feasibility-assessment-zh.md)。

直线/同section/同组件不保证实际短程；完整结构路径又需要查询成本。当前零边路线已经只有一次详细搜索且无最终窗口，潜在收益只剩前置准备，不能沿用4a/4b之前的50.1MiB或约95ms估算。短直线长绕行可能把节省变成额外整条搜索及升级成本，当前没有覆盖率和误分流账本支撑继续投入。

保留“减少简单请求准备开销”的目标，后续优先核实原楼梯分段失败，再沿5b及步骤6、7按实际成本筛选；本次不直接修改这些模块。只有发现现成、无需额外搜索的有效信息，并证实当前剩余成本与覆盖率值得优化，才另提窄范围候选，不默认恢复16格方案。此决策是研究优先级取舍，不冒充性能证伪或优化完成。没有新实验或DeepSeek调用，生产及发行包保持。

## 十六、5b 当前会话交接：停止于 B 轻量筛选

本节为上一会话的历史停止状态，接手后的完成情况及计量澄清见第十七节。

本会话按用户授权继续5b尾部交接隔离验证，随后按用户要求停止工作，剩余步骤由其他会话接手。生产源码、默认发行Jar、4b/4c及61份生产输入均未改动；没有提交或推送。

已完成并可复核：

- 候选真实替换控制 `candidate-tail-replace-v1` 的 `tail-checks.json` 为 6/6 通过；旧请求取消后调用陈旧尾部回调，没有触发旧请求原生搜索。公共 `analyze.mjs` 已确认技术有效，`analyze-handoff.mjs` 显示该场景主请求3次搜索、2次尾部结算调用，最终状态 `SUCCEEDED`。这只是生命周期控制，不是性能结论。
- 第二批 DeepSeek 独立无头审查 `review-batch2-metrics` 返回 `ACCEPT_SCOPED`，耗时20.713秒，超时为 false，settings 前后哈希相同，必须修改项无。审查认可以 PathFinder wall/CPU/分配、全进程/全线程/主线程资源、GC为主要指标，同时保留自然结束累计和固定1秒/5秒窗口；首Path、到达、终态、tick和搜索次数只作保护/解释。
- B 轻量筛选已启动并完成：`.tmp/navigation-segment-handoff-20261017/screen-b-v1`，coverage、`entity-stationary,query-pending-adopt-multi`、`NoTrace`、`OmitObservations`，两场均由runner报告完成。尚未对该目录运行公共分析器，不能把它写成已审计的资源结论。

尚未完成：

- C 轻量筛选尚未运行；因此没有 B/C 资源对照。
- B 的 `analysis.json`、PathFinder原始资源汇总及正式5b报告尚未生成。
- 不得把此前深诊断中“主请求3→2”直接写成净收益，也不得因实体终态拖尾约0.30–0.35秒直接否定候选。自然结束累计成本与共同固定窗口必须分开报告。

### DeepSeek 无头审查交接注意事项

`tools/review/deepseek-review.mjs` 使用 WSL 原生 `/usr/bin/dsh --profile headless`，当前配置必须保持 `provider=deepseek`、`model=deepseek-v4.1-flash`。无头会话没有本地会话上下文，也不应假定它能读取仓库；每个提示包必须自包含，明确背景、当前契约、实际代码语义、问题范围和已知缺口。禁止依赖上一包的记忆。

提示包限制为不超过8500 UTF-8字节；建议一次只审少量具体问题，要求中文短答和固定首行/末行。超时可以分批提交，但每包都要重复必要背景、禁止工具/联网/文件遍历，并使用新的输出目录保留 `prompt.txt`、`transmitted.txt`、`answer.txt`、`process.txt`、`exit.json`。900秒超时、无结论或提示范围外的长篇回答都不能当作批准；退出码0也不能单独视为通过，必须读取实际答复并检查 `conclusion`、`mustFixesClear`、`timedOut` 和 settings 哈希。

已知审查记录：`review-batch1-contract` 为 `ACCEPT_SCOPED`，只认可历史proof身份与当前裁决分离、不得用 `proof.current()` 主动撤销已提交走廊，也未发现直接伪造最终成功；`review-batch2-metrics` 为上列资源口径审查。早期 `review-method-v2` 达到900秒超时，不能称批准；早期审查中把世界版本撤销走廊、把段 `arrivals` 当最终成功等前提已明确否定。

接手者下一步按顺序执行：先读取本节、`segment-tail-handoff-20261017-zh.md`、`.tmp/navigation-segment-handoff-20261017/protocol.md`及两个DeepSeek包；再对 `screen-b-v1` 运行公共分析器并读取原始资源字段；最后在同样参数、独立新目录运行 C。C 完成后才决定是否生成有限报告；不得追加同候选样本挑结果，不得修改生产区。

## 十七、5b接手完成：有限结清，候选不接入生产

正式报告：[5b尾部交接隔离验证](../../reports/2026-10-17-segment-tail-handoff-validation-zh.md)。
证据根仍为`.tmp/navigation-segment-handoff-20261017/`，生产区61份输入、
默认发行Jar及4b/4c保持，未提交或推送。

本轮完成：

- 公共分析器审计B，并按预定唯一一对完成`screen-c-v1`；两个独立JVM、
  四条场景记录均技术有效、SUCCEEDED且按各自accuracy真实到达，无外部Java干扰。
- 资源账本为`resource-screen-v2`，v1原件保留；静止实体搜索3→2，
  PathFinder累计分配885288→859784字节，但全场分配59019792→59663152字节。
  多目标控制搜索2→2、搜索分配相同，全进程CPU281.250→578.125ms。
  没有把“少一次搜索”或静止实体全进程CPU下降直接解释为整体净收益。
- 补齐协议已有的漂移、改块、受控失速三场C功能覆盖，`candidate-dynamic-v1`
  三场技术有效并到达；21项后核通过，保留三次replan、原生恢复、缩段、刷新及1次partial。
  该补测在唯一资源对之后完成，不倒写为筛选前全部完成。
- 自然尾保护47/47、真实取消5/5、替换6/6仍为复核的既有证据；替换场景
  **整场3次搜索=旧请求1次+新请求2次**，纠正第十六节“主请求3次搜索”的措辞。
  旧回调实际调用后没有新增旧请求搜索。
- 新增公共离线资源比较器及5项正反例测试，相关Node测试合计62/62通过；
  源码/Jar内部身份核对保存在`identity-audit-v1.json`。

计量边界必须保留：

- 轻探针每次真实PathFinder边界计数，但落盘只有实体所有请求代次的累计值，
  **没有逐次wall/CPU/分配明细**，不能把累计差额完全归给被省掉的那次搜索。
- 自然窗costEnd取于终态后10tick、stop/consume/discard之前，
  **不含随后实体清理及关服成本**；场景资源包含后台、移动和探针，不是请求独占账本。
- 固定1秒/5秒仍给真实前后采样界。静止实体尾部约12.2秒才交接，
  固定窗此前已有CPU差异，不触发尾部交接的控制也变化；单对顺序B→C、
  启动相隔24.24分钟、无A/A，因果和统计结论均不足。
- 真实accuracy到达与终态分开：静止实体终态本对晚约350.401ms，
  首次accuracy到达晚约1.608ms，不能据终态直接否定局部减搜，也不能声称提前到达。
  最终段native stop尚存，恢复partial不冒充最终partial耗尽专项覆盖。

阶段末自包含DeepSeek `review-final-v1`为`ACCEPT_SCOPED`、必须修改项无；
8295字节，31.450秒，退出0、未超时、settings前后哈希相同，
实际答复及prompt/transmitted/answer哈希已核验。接受范围仅为本轮研究有限结清
及停止决定，不是生产或正式性能批准；前期拒绝、超时及原始五件套均保留。

**后续决定：封存这个尾部候选，不追加零散净测、不接入生产。**
它证明了当前范围内少一次同段重试，尚未证明整场净收益；5b其他段间问题、
原楼梯正向缺口及未覆盖的墙门/小range/Super304/大并发/尾回调等待时TTL或关服/
最终partial耗尽等保留。后续按6–9的实际成本另选窄问题，5a继续搁置，
步骤1正式协议/同壳A/A及步骤10组合验收仍未完成。

## 十八、步骤6：关机后恢复完成，本轮研究有限结清

用户明确“进入6的优化”，覆盖此前楼梯优先顺序；随后因关机要求整理中止，
在最终DeepSeek复审前暂停。用户重启后授权继续，已核验身份并完成最终复审。
正式[报告](../../reports/2026-10-17-window-allocation-validation-zh.md)、
[任务记录](window-allocation-20261017-zh.md)及证据根
`.tmp/navigation-overhead-20261017/`共同记录实际范围。

B复用`navigation-segment-handoff-20261017/build-baseline-v2`，Jar SHA256：
`dda2ffd579f40a143c0e9f666f390a875a029cd4a9f1878335607dca41c4af7f`。
C仅在独立源码中改变`GroundSegmentWindow`，Jar SHA256：
`365fa1cf29ea6223d5fdf17701b3e0766bf9f437a1ef1128c045eeacb5b2a929`。
调用独占MutableBlockPos仅作游标，写入集合仍immutable，顺序/去重/成员快照保持。
生产61份输入、4a/4b/4c及默认Jar未变；重启身份复核为`resume-audit-v1.json`。

已完成：

- 当前B深诊断及JFR；正式8/28格窗口准备仅占总分配2.795%/0.602%，
  没有正式扫描调用或阈值以上主线程锁等待见证，不据此排除冷事实或短锁。
- B/C各26组顺序用例和26个并发窗口输出逐字节相同；Core 9项（含Reverse 3项）、
  macro-commit 4项、172预算断言、五场生命周期控制通过。
- 动态四场到达及18项修正后核通过，检查器1正3负通过；
  墙8/门28各64/64，小range4真实partial耗尽、range12到达；
  Super304保留19中间窗口/20搜索。相关Node测试51/51通过。
- 唯一B→C轻量对2/2独立JVM、4/4正式trial技术有效、2048/2048到达。
  8/28格分配-25.958%/-5.746%，但8格CPU+3.687%、首Path P95+18.850%，
  GC时间5→13ms/6→15ms。观测窗短约1.936/1.000秒，分配差额主体为主线程，
  不能归因窗口直接减量。诊断归一窗口分配下降77.08%/17.63%不作稳定净效应。

失败夹具编译`checks-v1`与空选择误通过的`dynamic-checks-v1.json`保留，
仅采用修正后的v2证据；人工多band夹具不当真实地形。无A/A、正式C/N或统计非劣。
所有8台服务器JVM已退出，本轮恢复不重跑既有资源对。
阶段末自包含DeepSeek `review-final-v1`为`ACCEPT_SCOPED`，本轮必须修改项无；
8427 UTF-8字节，60.664秒，退出0、未超时、settings前后哈希相同，
prompt/transmitted/answer身份及五件套已核验。答复中的8格prepare总分配下降
76.5%与报告按窗归一77.08%口径不同；人工夹具实际已运行，真实地形覆盖不外推。

**步骤6仅按本轮隔离研究有限结清。** 局部减量机制成立、已覆盖功能通过，
整场保护项混合、稳定净收益未证明；候选保留且不接入生产、不追加同候选零散净测，
不自动进入7。最终封存`final-seal-v1.json`保留源码/Jar、原始记录、报告及审查身份。
扫描/锁/冷事实、自然AI全入口、楼梯、4b跨tick身份和步骤1/10缺口仍独立保留。

## 十九、数据结构与算法评估：范围闭合，候选未实现

用户要求评估Boundary位图粗筛、局部AABB/BVH/区间索引和宏观依赖Top-K，
并参考导出的城市基础图。评估报告为
[2026-10-17-algorithm-structure-evaluation-zh.md](../../reports/2026-10-17-algorithm-structure-evaluation-zh.md)，
独立证据根为`.tmp/algorithm-structure-evaluation-20261017/`。
本节补充明确的继续/暂停判断，避免把“值得测量”误读为“值得直接实现”。

### 当前判断

| 方向 | 当前决定 | 继续条件 |
| --- | --- | --- |
| P0测量 | **必须继续** | 先取得真实D分布、Boundary.open轨迹和关闭诊断后的Java/server基线 |
| Boundary位图粗筛 | **有条件继续，候选优先级最高** | 按扫描量/查询量加权的“确定为空”命中率足够高，且零误放行 |
| 宏观依赖Top-K | **暂缓实现，保留低风险原型** | 真实D的p95约达到64，并且排序成本在宏观阶段占比足够高 |
| 局部AABB/BVH/区间索引 | **当前暂停** | 矩形数p95、查询次数和索引摊销证据同时达标 |
| 四叉树、kd树、全局空间哈希、R-tree、额外HPA*、JPS | **当前淘汰** | 只有出现新的数据形态或边界问题才重新立项 |
| 相对Boundary结果memoization | **保留为次级调查** | 先证明查询级重复、完整缓存键和可失效性 |

这里的“候选优先级最高”只表示Boundary位图最值得先测，**不表示已经批准实现或接入生产**。

### 城市图证据范围

`admitted-graphs-v1.json`包含1370条快照、65个唯一section坐标，范围为
`x=-2..5、y=0..5、z=-2..3`；另有23种geometry、49个sourceFingerprint、
8509个组件、14638条出边和7289个halo引用。它是局部快照语料，不是完整城市。
组件标签没有`-1`只能说明标签覆盖，不代表自由空间密度；导出也不能提供每格Shape盒分布。
因此当前不能从城市图直接推导空扫命中率、矩形数p95或真实依赖D分布。

### 必须先完成的P0

1. 对每次`MacroSearch.pendingDependencies`记录D、等待节点总数、完全并列次数、排序耗时和分配。
2. 对每次`BoundaryGeometry.open`记录扫掠格数、矩形数、point/segment/cut/BFS查询数、线性扫描耗时和确定空命中率。
3. 在关闭或降低诊断影响的Java/server受控运行中取得wall、CPU、分配、GC和p50/p95基线。
4. 保留正确性oracle：Boundary零误放行、局部索引查询顺序/BFS并列顺序、Top-K完全并列稳定顺序。

### 候选语义约束

- 位图只能让“确定为空”短路；FULL、UNKNOWN、部分覆盖、门状态、revision和跨section扫掠必须保守回退。
- Top-K必须加入encounter ordinal，覆盖D<16、D=16、D=17、完全并列和重复键。
- 局部索引只允许在单次`open`的不可变矩形数组上复用；小矩形数保留线性扫描，禁止全局动态BVH。
- 任何候选出现结果/顺序差异、误放行、缓存失效无法证明、净收益低于噪声或分配明显回退，立即停止。

### DeepSeek最终共识

第一轮独立审查`review-v2`返回`REQUEST_CHANGES`，要求撤回固定A→B→C顺序、
收紧位图语义、补Top-K稳定并列键，并把D/Boundary/Java-server证据设为前置门。
修正后第二轮`review-v3`返回`ACCEPT_SCOPED`，退出码0、未超时、settings哈希未变化，
必须修改项为无。共识范围仅是“评估门控已经闭合”；D/Boundary实测、oracle、候选实现、
Java/server净测和生产集成仍未完成。

生产源码、默认Jar、既有4a/4b/4c、5b和步骤6候选均未因本评估改变。

## 二十、算法独立审核后的实现与验证

用户随后授权继续探索宏观依赖、Boundary及运行时状态成本，并允许验证其他算法。
当前算法状态以本节、[独立审核](../../reports/2026-10-17-algorithm-structure-independent-audit-zh.md)、
[本轮报告](../../reports/2026-10-17-worker-algorithm-exploration-zh.md)及
[新交接](worker-algorithm-exploration-20261017-zh.md)为准。第十九节保留为历史记录，
不再将其“候选未实现”、Node来源的64硬门或Boundary BFS内部顺序约束作为继续前提。

本轮完成八个隔离原型，组合为稳定小数组Top-K、不可变几何非空行位图、
轴对齐碰撞谓词合并、每线程惰性回调上下文和固定分组队首缓存。
真实城市正向D P95为95、Boundary约13.2%进入BFS，与平地差异明显。
第一版运行时惰性列表的并发退化被保留并淘汰，没有因少分配而采用。

宏观/几何/队列差分、Core 9项、boundary-recovery 9项、macro-commit 4项及Door集成通过。
完整BoundaryLinks平地/选定非空城市列夹具约-29.5%/-5.7%；城市列是人工重复邻接，
没有重新包装成真实城市覆盖或路线功能证明。

两种目的的B/C对照分别保留。第一对整场指标混合，8格首Path P95 +13.6%；
追加边界计数对中，正式8/28格计算worker CPU观察到-23.1%/-30.8%、
worker分配-11.5%/-5.0%，但64实体预热CPU增加，且切片数量变化、CPU量化仍在。
两对各自两臂均正式1024/1024到达；尚无A/A、C/N或统计性能批准。
城市插桩B两向仍UNAVAILABLE、未到达，功能缺口保留。

证据根`.tmp/worker-algorithm-exploration-20261017-v1/`，组合源码`combined-main`，
仅改变5份隔离生产输入，Jar SHA256为
`ce7746d3279d08f69d036238cc3418f7b4523976cf79121f850443d1e63fdbbd`。
61份生产源码及两个默认Jar保持；组合不接入默认生产实现。
后续优先继续定位锁内状态命令及重复依赖组织，不将局部纳秒收益解释为runtimeState已解决。

## 二十一、边界候选位置位图求交

用户继续提出六方向开口位，并补充按组件的边界位置求交。本轮选择共享合法锚点行位图，
结合原组件标签按inset/distance/dy对齐，验证了完整Boundary置位枚举以及Base正向/反向
宏观依赖前的组件级零拒绝。见[报告](../../reports/2026-10-17-boundary-port-bitmaps-zh.md)
和[交接](boundary-port-bitmaps-20261017-zh.md)。

515,840项行集合、737,280项标签位、2,520组完整Boundary输出一致，运行时恢复、
反向、macro-commit与Door契约通过。正式60个JMH测量fork中，完整Boundary平地/固定城市
重复邻接夹具约-21.0%/-45.6%。位置索引约多1 KiB/Base，构建成本与分配原件保留。
DeepSeek第二轮ACCEPT_SCOPED仅限局部核验范围；首轮异议与失败夹具同样保留。

本轮证据根`.tmp/boundary-port-bits-20261017-v1/`，没有叠加第二十节候选，没有实服对照，
没有将组件夹具剪枝比例解释成线上依赖减少或整体worker收益。两侧未知/过期数据不能
当作零，非零端点匹配仍调用精确碰撞。生产输入及默认Jar保持原身份。

## 二十二、两轮算法合并后的最终组合

用户要求固定最终算法组合并与DeepSeek达成共识。当前算法范围以
[最终报告](../../reports/2026-10-17-final-worker-algorithm-combination-zh.md)与
[最终交接](final-worker-combination-20261017-zh.md)为准。

组合为稳定小数组Top-K（K≤16，大K回退原排序）、GROUND组件端口位置位图求交及依赖前
零拒绝、碰撞非空行位图、轴对齐严格谓词合并、第二版每线程惰性回调context、
固定12组队首缓存。保留既有组件包围范围/Super 14位出口。VOLUME保留原候选枚举，
不分配端口索引数组；不能把第二十一节的全通道原型范围直接当成最终范围。

8份隔离源码通过宏观、碰撞、完整Boundary、恢复/反向/提交/Door及并发检查；
本轮30个JMH测量fork中，完整Boundary平地/固定城市人工邻接F/B约-50.6%/-57.0%，
相对上一轮组合约-32.0%/-48.8%。VOLUME与上一轮范围重叠，不宣称统计非劣。
不相加历史百分比，不把旧服务器数字转移给本组合。

证据根`.tmp/final-worker-combination-20261017-v1/`，选择`selection.json`，
共识与最终复核见`consensus.json`及`review-final-v2`，两轮均ACCEPT_SCOPED、必须修改项无。
最终明确同意锁定组合，共识限定最终候选算法选择；
无新实服运行或默认Jar集成。61份生产输入和两个默认Jar保持原身份。
