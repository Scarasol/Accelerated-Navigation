# 导航性能优化交接记录

日期：2026-10-17。  
交接原因：当前会话停止工作，后续由其他会话接手。  
项目：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`  
分支：`1.20.1`  
已知 HEAD：`3a04a23`  
工作区：已有多项未提交修改，不能使用 `git reset --hard`、`git checkout --` 或回滚用户/前一会话修改。

本记录是当前工作的状态基准。后续会话应先阅读本文件，再阅读路线图、T1 报告和 T2 报告。T2 已经完成根因定位、生产窄修、独立验证和 DeepSeek 最终共识；不要重复把已结清的 T2 当成未验证假设，也不要把 T2 结果扩大解释为整体性能通过。

2026-10-18用户要求先核实第8步及依赖闭包历史，本轮已完成只读源码/证据核查，
见第二十七节及[核查报告](../../reports/2026-10-18-step8-dependency-closure-history-audit-zh.md)。
闭包历史已实现并实测，当前两侧位图也已接入；不可当作全新方向重复实施。
本轮未启动新候选或JVM，当前冷长路线可避免成本仍待归因，未取得步骤8整体资格。

2026-10-18步骤7续验状态见第二十六节：本轮有限结清，生产Fv3保留，
E入口扩展隔离停止，精确Base分量构型L评估后不实现。HEAD仍为`4b82ed6`，
F/E各17控制、查询采用补证及唯一资源对已完成，最终DeepSeek共识必改无。
稳定性能与发行未通过，不自动进入8；下方旧HEAD和阻塞说明保留其历史范围。

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
| 8 | T7 | 在冷长路线确受构图依赖限制时缩小闭包、按需构建或研究边界骨架 | 历史闭包已实现并部分实测、旧源端骨架停止；两侧位图已接入。当前八子图屏障仍在，本轮历史/源码核查完成，待当前冷长路线归因，不续旧矩阵，见第二十七节 |
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

## 二十三、最终算法组合生产接入

用户新授权先推送当前生产工作，再接入最终组合并由 DeepSeek 审核。本轮已完成：
基线 `4b82ed6762b86298ec204637f92cf96c890689e7` 先推送并核对 `origin/1.20.1`，
然后八份最终算法源码接入 `src/main`；CRLF 归一化后与第二十二节最终候选一致，
原另外 54 份生产输入未变。新接入尚未再次提交、推送或部署。

实际 Gradle 构建、默认 Jar/探针、七组长期契约、完整 Boundary/碰撞/宏观/运行时/
队列/窗口回归通过，58 个生产 Java 类加载来源已核对。发行态平地交接 13 场景、
130 项及关闭归零通过。修正生命周期探针的计数污染后，F/B 分别通过 87/95 项前置检查，
随后均在原楼梯正向 `(-22,78,-196)` UNAVAILABLE，目标 `(-37,101,-195)`；
两者同一错误、24 次分段搜索。完整第一条路线未完成，后续三条未执行，不能声称四路线通过。

DeepSeek 首轮五包 `REQUEST_CHANGES` 原件保留。补齐实际前置校验、邻接/有向 incoming
语义、全部严格碰撞调用点、每 JVM 排序原件、队首全表及真实续跑后，
Boundary/geometry 复核 `ACCEPT_SCOPED`，最终整体接入复核
**`ACCEPT_WITH_GATES`、本轮必须修改项无**。七次均退出 0、未超时、配置未改；
认可有界代码接入进入本地提交和评审，原楼梯、完整路线、实际 worker/首 Path/内存/GC、
远端 CI 与发布部署门禁继续保留。本轮不转移旧 JMH 或服务器百分比作为净收益。

报告：[生产接入](../../reports/2026-10-17-worker-algorithm-production-integration-zh.md)；
任务：[接入交接](worker-algorithm-production-integration-20261017-zh.md)。
新证据根 `.tmp/production-worker-integration-20261017-v1/`，身份/验证/共识/封存依次见
`source-identity.json`、`integration-checks-v2.json`、`live-checks.json`、
`consensus.json` 和 `final-seal-v1.json`。原最终候选 267 份封存文件逐项核对未变。
第二十二节“未接入/默认 Jar 未改”是当时研究阶段的历史状态，本节为当前接入状态。

## 二十四、步骤7共享入口首次筛选：增加分段，具体候选停止

用户授权根据数据进入步骤7。当前B冻结本地worker组合接入后的62份生产输入，
C仅在`.tmp/shared-entry-20261017-v1/candidate-source`增加同section accuracy=1共享资格，
并让该范围的负候选回原宏观流程；没有改生产源码或默认Jar。

历史受控8格突发256/512次资格检查只因accuracy拒绝，28格为0，不能当实服权重。
B/C各16,800项资格检查及公共Node17项通过。同测量工具两台独立JVM各一个8格请求
均实际到达、退出及关闭归零，但原宏观B只需一次最终搜索，C沿每四锚点规则切出
两个分段，真实搜索1→2、首Path82.1331→106.8293ms，CPU/GC观测不利。
单请求冷态数据不证明稳定回归或净收益，分配下降也不能独自取得准入。

按运行前冻结的机制门停止这个直接扩入口的具体候选；边界、负候选回退、障碍、
漂移、取消、替换等剩余控制和512净测未执行，不包装为已通过。
本轮不是第7步生产优化完成，也不是整个共享方向被证伪。查询采用分支未改，
不自动推进8，不恢复5a/5b或步骤6候选。DeepSeek停止审查`ACCEPT_SCOPED`、
必须修改项无，145.752秒、模型退出0、未超时、配置未改。中文判词前缀未被通用
正则识别，原包装器退出1；原`conclusion=null`保留，完整答复人工核对结论单列。
末段accuracy1、中间accuracy0及场景distance元数据与实际8格坐标分别记录，
不把未测功能或单次资源变化当性能通过。封存见新证据根`final-seal-v1.json`。

报告：[筛选结果](../../reports/2026-10-17-shared-entry-screen-zh.md)；
任务：[恢复记录](shared-entry-screen-20261017-zh.md)。

## 二十五、共享路径切段修复，步骤7续验受外部运行阻塞

2026-10-18按用户要求修复并继续。证据根沿用
`.tmp/shared-entry-fix-20261017-v1/`。B冻结62份当前生产输入，F只改原共享切段，
E另在隔离副本探索accuracy1及错误/负候选/执行失效回原宏观；两项决定分开。

B发行态8格accuracy0实际复现两次搜索、两个共享段，先第4格再终点，SUCCEEDED且
精确到达，原range35等输入、无整条原生回退及关闭归零通过。F构造时计算一次
同Y水平单位步后缀，只有剩余步数严格小于原range才合并到终点；其余保留旧切段。
两份Fv2源码已接入生产，62份归一化后与冻结Fv2逐项相同，另外60份保留原worker状态。

F/E隔离构建和各151,979断言、217路线、3,653普通Base组件归属证明通过，
资格oracle各16,800项；F受影响核心和公共Node17项通过。
首版`<= range`的oracle与实现共享错误边界；实际PathFinder复现8步/range8合并不可达、
旧4+4均可达。Fv2改为`<`，保留旧反例与源码/包。永久SharedStageContracts纳入
Gradle check，新增44次真实原生循环验证严格range，共91,134检查、41路线、
643组件归属通过；Core、worker七组及完整build/check成功，33秒。
首版151,979检查不代表原生range已验证，编译/契约不是性能样本。

Fv2/Ev2发行包重建，测量39工具/reference sources与B相同。首次DeepSeek审查
902.476秒超时、无最终判词/批准，进程收取、配置未改；修正版另送v2包。

F的15场景发行控制在启动前等待外部IDE Minecraft/Gradle，满10分钟后退出1；
未启动服务器、未终止用户进程或放宽负载门。原目录保留，阻塞记录为
`runtime-block-v1.json`；待外部Java退出后使用新目录续验。尚无F/E发行功能或
512突发资源对，不声称实测搜索2→1、稳定提速或准确CPU归因。E继续隔离，
查询采用未改，不进入8，原楼梯独立缺口保留。本轮不提交、推送或部署。

报告：[切段修复](../../reports/2026-10-18-shared-stage-fix-zh.md)；
任务与续验命令：[恢复记录](shared-stage-fix-20261018-zh.md)。

## 二十六、步骤7续验有限结清：保留F，E隔离，L暂不实现

2026-10-18用户离开期间按授权自主推进，并与DeepSeek结清取舍。
生产仍为Fv3，62份输入匹配`fix-source-v3`，原worker改动保留；
HEAD仍为`4b82ed6`，未提交、推送、部署或进入8。前节v2和外部Java阻塞均已由
本轮续验取代，其失败原件不覆盖。

Fv2获审后仍被真实低预算反例推翻；Fv3补Work永久拒绝锁存、失败合并不发布、
经原scheduler回旧4锚点、终点匹配和旧段数+一次失败的槽位保护。
永久99,951检查、41夹具路线、643组件归属及47次真实原生循环和完整构建通过。
12是分段搜索计数上限，完整查询裁决另计；原预算和请求语义保留。

F/E各17实服控制均技术有效、功能核验通过：各14正例严格到达、2预期取消、
range4预期EXHAUSTED。普通accuracy0由B两次搜索降为F/E一次；
低预算0先负合并不发布、再旧4+4到达，共3次；轻微终点漂移保留两段并追实际+9。
E的冷暖accuracy1、邻域可达/超时及发布后动态回退均实际观察。
F这些accuracy1控制走原宏观，不能据此宣布accuracy0发布后动态恢复已全覆盖。

查询v1检查器误把跨section夹具当共享并按目标数分类，原失败保留，v2纠正分析。
新增真正同section三目标夹具，使用独立相同工具B/F cohort，
原17控制与burst仍用原controls-v2包。39工具+10参考共49输入，
两cohort只差探针新场景及分析器列表；各cohort的测量Jar条目内容一致。
跨section B/F实际均1/3/2；同section B1/3/2→F1/2/1，
完整目标裁决和采用时序保留，全部已采用移动严格到达，未采用无移动。

唯一F→E burst对含预热2304/2304成功到达，正式突发2048/2048。
8格E相对F的P95观测-19.80%、CPU-1.81%、分配+20.31%；
普通宏观物理搜索173→101、BUILD任务821→785、CONTROL203→52，
同时新增56共享底座，末共享留存账目2,107,668字节、flight0。
28格无共享命中，CPU+17.08%、P95+7.21%仅为控制观察，不归因E。
全部行、固定窗真实界及活动窗口tick保留；无A/A、交错顺序或稳定因果结论，
F/E比较也不能代替F/B净收益。

DeepSeek F终审8059B/105.389s `ACCEPT_SCOPED`、必改无，认可有界保留。
E首轮8299B/105.526s `REQUEST_CHANGES`的目标/双回退两疑问，
补交实际normalize/select/getTarget/publish及queued/preparing调用链后，
8064B/91.233s复核明确撤回，`ACCEPT_SCOPED`、必改无，
仅同意E隔离停止、不授予代码/发行资格。两份原件同时保留。
直接利用精确体型Base分量的后继L，5708B/13.970s价值审查同意本轮不实现：
已有同分量快路、canonical flight和缓存，仍需捕获/所有权/交付生命周期，
当前可避免成本不足以支持新实现；主线程准备/物化仍未归因，不夸大7.3%非主线程
分配占比为L全部收益硬上界。四次审查进程均退出0、未超时、settings不变。

本轮7按当前成本/性价比有限结清，不代表全共享方向无价值或步骤7整体生产性能完成。
未来重开须先有真实入口份额及可避免准备/物化成本证据，再冻结新窄协议，
不续旧E矩阵。F的发布后动态/完整恢复和正式性能、E的归一化+nativeStopped组合与
飞行中取消继续保留为条件；原楼梯缺口独立。未改快照语义，也不自动进入8。

报告：[切段修复与续验](../../reports/2026-10-18-shared-stage-fix-zh.md)；
证据根`.tmp/shared-entry-fix-20261017-v1/`，决策`decision-step7-v3.json`，
封存`final-seal-v3.json`，恢复[当前任务](shared-stage-fix-20261018-zh.md)。

## 二十七、第8步依赖闭包历史与当前源码核查

2026-10-18用户要求先核实当前情况，重点检查闭包是否已经研究。
本轮复核文档、结构化结果、旧审核原文及当前生产调用链；
只新增核查记录和同步索引，没有修改生产、运行新JVM或取得新DeepSeek审核。

历史不是空白：10月15日前置60 JVM/13,842请求；闭包候选28功能JVM通过后，
性能135计划实际104、103检查通过/1基线派发前失败/31未跑，候选35 JVM；
源端骨架27计划实际12、11完成/1骨架关闭C臂首Path后失败/15未跑。
两轮均未取得性能资格，旧失败不能归因候选或视为已经修复。
旧源端mask与bbox等价，新增依赖剪枝为0，停止当前构型；
不等于目标边界信息或Base前摘要均无效。

当前八份worker源码归一化哈希8/8匹配生产接入冻结。Base已有按需前沿、
16个live依赖上限和两侧端口位图；位图需邻居Base已捕获，
只能省后续Boundary。自动Super选择仍按距离及世界可用性，
冷父图仍请求全部八个Base；历史coldFirstPath/firstPathPublished闭包策略未接入。
旧64 Base/8 Super对14/0是历史测量；59个未直接展开Base参与父图聚合，
不能算成59个无用构建，也不能当作当前版本数量或收益比例。

现有Spark/JFR中runtime约52.9%、Base约20.2%、Boundary约12.1%为858个worker
模组活动wall样本分类，非CPU账本，也未隔离当前冷长路线。Super自身少量样本
不能排除其Base/Boundary间接依赖成本；runtime占比也不能全部归因闭包。
当前没有足够证据立即重开旧策略或实现新骨架。
下一项若继续，只先补当前代码冷长路线及温热重复的对象身份/关键路径归因，
区分构建、捕获、展开、聚合、走廊及后台工作，再判断可省份额。
不续旧135/27矩阵、不重做旧mask、不改变快照、haveTime、TTL或预算。

本轮核对前置summary SHA256仍与旧final-audit绑定值一致；
旧344,842审计项及全部功能/性能数字仅引用，不冒称本轮重跑。
Fv3状态及发布前条件保持。步骤8只完成本次历史/源码核查，整体尚未结清。
详见[完整核查报告](../../reports/2026-10-18-step8-dependency-closure-history-audit-zh.md)。

## 二十八、第8步当前成本归因

2026-10-18用户要求结合侧边分析及原日志/Spark/JFR进行成本归因。
证据根`.tmp/step8-cost-attribution-20261018-v1/`；正式
[归因报告](../../reports/2026-10-18-step8-cost-attribution-zh.md)。
本轮只改诊断工具和文档，62份生产输入保持，不改变当前性、已提交快照、
预算、TTL、haveTime及真实参数，不提交、推送或替换默认包。

独立读取原JFR，两个worker活动样本921、模组858；新互斥口径runtime469、
Base187、Boundary103。runtime细分失效175、引用81、事件64、构建管理62、
依赖61、其他26；与侧边454/runtime的分类规则不同，共同支持“约一半运行时”。
wall样本不是CPU；失效分配采样权重2,067,537,944字节，占全部worker约50.17%，
不是精确分配或可省上界。原profile没有请求身份，不能分账给那次1165ms冷查询。

当前同路线304格冷/暖各真实30秒等待，深诊断两场到达、各20次真实原生搜索。
冷首Path393.4822ms、新物理宏观1；暖7.8086ms、完成缓存命中1、新启动0。
冷304 Base、38已申请父图entry、36完成Super；288 Base为聚合输入，
另外16属于两个已申请但未完成父区，不能当无消费者。Base捕获60/展开20/最终快照20，
父区直接展开18；未直接展开不等于可避免。旧64/8不作当前收益对照。

Boundary1852次=内部1440+跨Super392+跨Base20；1832个有向Base对象对，
20次重复，其后续调用插桩wall3.1765ms。不以这次重复量为由优先增加全局缓存，
未来不同查询的复用未测。921活动样本中63无法归属，469/runtime占858个可识别模组
样本54.66%、全部worker样本50.92%；另一明确Base分类口径下失效仍175。
最终分配分析按runtime子类×分配位置闭合，原全runtime站点不混入失效子账。
位图零拒绝50无无位图对照，不宣称净收益。MacroQuery依赖配对等待并集
262.7297ms，含3个缺completion观察者的保守上界350.8000ms，只涵盖该依赖范围。
worker首Path前独占wall跨线程412.8679ms，不与等待/首Path相加。
引用实际调用首Path前97,782次；46,548只是独占计时大于零的span数，
不是全部调用。深诊断反射、CPU/分配读数和引用留存显著侵入，阶段时间不作生产占比。

第一次NoTrace两场均到达但关服探针重新获取已停止scheduler，缺shutdown.json，
包装器退出1，技术无效保留。只修探针保存引用并LOWEST读取关闭指标，重建后62生产输入
相同、276 ZIP项仅manifest构建时间不同；新目录NoTrace重试技术有效、到达及关闭通过。
冷/暖首Path251.3998/5.9151ms，全自然窗CPU4875/1718.75ms；
深诊断3921.875/1859.375ms，不能将首Path差额全归观察者或宣称稳定收益。
无深阶段时图数未测，不把空trace当未构建。

有界归因完成；下一窄方向建议先量事件批内唯一受影响视图/父级、
重复访问、真实退役/重建和引用活动/空闲转换，再选择一个保持当前性的等价减量候选。
重复失效可省份额尚未证明，不自动实现延迟失效或进行中旧快照查询，
不续旧闭包/骨架矩阵。步骤8优化及整体性能仍未结清。
DeepSeek首轮118.483秒给出六项补账/澄清，未视为最终通过；修正后8465B自足包
92.993秒返回ACCEPT_WITH_GATES、必须修改项无，只认可进入下一项有界计量。
两次模型退出0、未超时、settings不变。当前62份生产输入与两次冻结一致，
四原profile/日志哈希保持，公共分析器9项及diff检查通过；
两有效JVM共4次实际到达/80次真实原生搜索。最终`final-seal-v1.json`，
原始分析修正版本及技术失败均保留，必要进程已收取。

## 二十九、运行时成熟方法与宏观快照性价比研究

2026-10-18用户要求结合成熟项目、论文和经典结构寻找方案并与DeepSeek讨论。
证据根`.tmp/runtime-state-methods-20261018-v1/`；正式
[研究报告](../../reports/2026-10-18-runtime-state-optimization-methods-and-snapshot-design-zh.md)。
本轮只新增研究脚本、离线模型和文档，无生产算法修改、游戏/JMH、新默认包、
提交或推送。62生产输入仍匹配上一轮冻结，不改变快照、当前性、TTL、预算或真实参数。

10份原件均HTTP200并记录SHA256，涉及Recast、LLVM小集合、Bazel/rustc依赖求值、
Levanoni/Petrank引用更新消除、LazySP、D* Lite、Linux RCU和SQLite WAL。
这些支持方法原则，不能代替项目收益或生命周期证明。

推荐顺序为A局部查找减量→Bw-R保序事实段→可选B0字节类别账目。
A为每geometry的27位依赖mask、单次失效调用的8槽父区集合缓存和条件延迟分配。
保留全部27个Base/视图检查、父级查表、原volatile validity递增次数和顺序，
只缓存同维度同一map的集合指针，不合并事件或版本写。
8来自连续3×3×3邻域和2×2×2父区对齐，不是任意27个坐标的普遍结论。

旧游戏JFR站点分配类型补账为ClusterKey450,549,040、
SectionPos256,962,072、SuperOriginKey217,077,104采样权重；
不能将全站点936,193,896都算作8槽缓存可省。原Jar尚缺完整来源认证，
不转作当前源码净分配。858顶层wall样本及runtime469子类分别闭合，
175失效/81引用属于469内部；另63无法归属不视为锁等待。

当前深trace原件身份核对后，冷首Path前3364次图事实遍历共有50,460槽位，
任意同身份分组16,778组，33,682重复均为uniform-air；
保持不同对象原顺序，只合并相邻重复为24,030段，可减少26,430局部调用，
即52.38%，保留原66.75%机会中的78.47%。暖180槽位为72段。
首版建议仅ALL_AIR连续段，不按内容hash合并，也不增加完整图租约。
当前API所有槽位共享同一idle/active delta；加权计数保留原始数值。
每段写前long预检，非法或溢出段在原位置走原scalar，禁止部分写后重放；
摘要、guard查询及新增留存可能抵消收益，没有实测候选CPU/内存改善。

2560几何/8640父区mask、676字节转换、972静态图租约状态及170145正常加权模型通过；
新8748连续段模型含4946合并/3802写前回原，覆盖普通负数/溢出失败位置和部分状态。
均为独立数学模型，非生产差分、并发、OOM或全生命周期证明。
B1完整租约暂缓，独立测绝对图类别转换及消费者成本，不能利用Bw-R后分母变化造收益。
C保最新语义的按需维护为后继，D为LazySP冷依赖独立专项，不恢复旧闭包矩阵。

宏观S0已提交走廊/暖缓存已有。S1-L为按发现次序固定共享旧图的混时局部视图，
S1-M为统一查询时点且可读取历史事实的强快照；前者基础设施可能更便宜，
不能只按全图复制/MVCC成本否决，但都改变语义，当前默认不实施。
Boundary须配对确切对象、共享halo、geometry/碰撞/端口/方向/版本及缺失事实；
旧负结果须最新刷新，旧正结果仍需真实详细搜索及恢复。
“不主动撤销已提交走廊”不等于永久有效。容量/原TTL/加载身份/释放继续保留。
尚缺宏观查询寿命、相关变化重叠、作废工作及旧版本字节账，69秒移动不是查询寿命；
不按少数陈旧次数再假定十倍成本制造1.3%收益上界。

DeepSeek前两轮8398B/296.632秒、8460B/196.616秒均ACCEPT_WITH_GATES但有必须修改，
原件保留，不当作共识。采纳失败前缀和内存门，将任意分组收窄为保序连续段；
纠正其嵌套计数分母、flag影响delta及每walk重复构造分组等误读。
7864B研究复审902.278秒超时，无最终回答，包装器退出1、子进程退出0且timedOut=true；
原件保留，只作一次5762B短包技术重试，139.546秒正常返回
ACCEPT_SCOPED、本轮必须修改项无。其完整答复明确只认可候选筛选和研究排序，
全部implementation/concurrency/lifecycle/performance门仍未通过。
四轮settings均未改，三个有效答复均退出0、未超时；超时不包装成完成审查。
最终`final-seal-v1.json`核对62生产输入、10外部原件、旧trace/原profile、
模型、四轮实际发送/回答/退出/配置及当前文档，旧归因seal保持。
研究脚本语法及diff检查通过；无生产代码变化，未重跑游戏或完整生产测试。
步骤8优化及生产性能资格仍未结清，本轮只解决方法选择与证据边界。

## 三十、运行时维护前两项隔离验证

2026-10-18按用户授权，结合最近两轮邻区依赖讨论，考虑实现难度后先与DeepSeek
达成实施验证方案共识，再分别实现A/R。见
[任务](runtime-maintenance-validation-20261018-zh.md)与
[报告](../../reports/2026-10-18-runtime-maintenance-first-two-validation-zh.md)，
证据根`.tmp/runtime-maintenance-validation-20261018-v1/`。

Base会读取邻区事实/碰撞/体型/支撑/门，且另持Boundary halo；
不是只有Boundary可能失效。保留27候选与当前stamp/整个section版本，
暂缓Base/Boundary拆分、细版本和反向索引；27位几何mask也暂缓，
首版只做A调用内8槽父区Set缓存与restart延迟分配。
R用事实序列int摘要，只合并连续ALL_AIR，保留原始数字及失败前缀，
未知/非法/溢出段写前回标量，不实施B0或图级租约。

冻结62当前生产输入及默认Jar，B/A/R同源；初审6620B超时无判词，
4211B技术短包785.775秒ACCEPT_SCOPED、必须修改项无后才改候选。
A仅1份隔离源码、R仅2份。11,115失效构型/1,008,893真实volatile写序及最终状态、
9073引用场景（4963异常）、6048摘要变换/展开均与B匹配；
真实所有者账目各77项，含多pin、旧图、卸载重载、idle及关闭归零。
Core9/Boundary9/门、Worker7组、Shared99951/41/643和runtime1296均过。
初次编译缺依赖、PowerShell参数展开、tick合并未覆盖idle及旧trace终点问题
均保留原件并新名修正，未冒充候选失败或已完成覆盖。

首轮JMH真实方法共36fork：
A每次分配少约864B，但空/稀疏/稠密耗时只-7.13%/-2.25%/-7.27%，
未过预注册10%时间目标，停止当前版扩测；restart延迟没有独立性能证据。
R空气/混合/无重复引用耗时-88.46%/-46.24%/+4.53%，
纯空气分配12→24B，其他约24B不变；构造控制+11.51%/+2.98%/+1.71%。
这些是局部热fixture耗时，不是生产CPU。

另预注册R完整API三对6 JVM，air进程CPU +11.11%/-5.56%/0%，
原筛选失败保留；主线程约-40%不能替换主指标。所有者/分配/关闭已核，
R同期编译140–592ms。一次另立JIT诊断显示R编译490/0/0ms、
进程CPU703/516/328ms，B编译12/7/0ms、CPU750/703/547ms。
XML有既有调用链/lambda及fixture分层/OSR任务；
编译过渡是明确干扰，不能把490ms算成新引用函数独占CPU或将诊断当性能通过。

3650B取舍复审23.101秒ACCEPT_SCOPED、必须修改项无，完整答复认可：
当前停R偏早，应另立成熟JMH按时间预热的完整操作测量，保持进程CPU主指标、
独立fork及原失败；未过门即停，过门也最多单对304冷/暖侦察。
执行前已冻结`api-jmh-protocol-v1.md`，三对/12个fork、每halo独立fork，
3×1秒warm、4×1秒measure、进程CPU/操作+GC+编译计量，当时进入执行。
原304请求窗无A失效调用，不能用其评价A。

当前没有新游戏运行或生产资格；隔离B游戏Jar仅完成构建。
62生产输入与两份默认Jar保持，未改预算/TTL/haveTime/参数/快照/当前性，
未提交、推送或接入生产。步骤8整体未结清，不恢复旧闭包/骨架矩阵。

后续实际完成的热态API JMH中，R1 air进程CPU/操作三对均改善38.96%–61.84%，
但distinct +20.01%/+45.02%/-31.16%，控制门未过，未跑游戏。
分配约768B不变、测量编译2–6ms，48迭代CPU归一化核对通过；
不将控制波动强行解释成helper因果。
仅再立R2恢复与B逐字相同的标量函数，辅助账目方法只服务加权段。
DeepSeek2655B/30.673秒同意这一低成本修订，必须修改项无，
要求R1封存，R2再不过则停止，不再换形态/扩样并保留控制噪声缺口。
R2所有实际差分、77项owner、六组契约再过，源码证明仅该段恢复、摘要不变；
源码验证器LF/CRLF误截取失败与v2修正保留，未变候选以凑过。
使用逐字节同一JMH harness完成独立B/R2三对/12fork：
air进程CPU/操作-11.04%/-36.54%/-9.53%，distinct +15.59%/+8.97%/+7.09%，
分配近乎不变，编译4–8ms，48迭代归一化核对通过。
主/控制门未全过，按预定停止本轮，不再R3或扩样，不运行游戏侦察，
未取得生产或稳定性能资格。B同源码控制本身也有较大跨fork波动，
不构成正式A/A校准，不能作废不利样本或证明helper因果。
本轮可按隔离实现/正确性/有限成本筛选范围收尾，步骤8整体仍未完成；
最终4657B/43.706秒DeepSeek复审ACCEPT_SCOPED、必须修改项无，
认可有限停止、失败保留及未测边界；控制波动不能反证算法无效。
3对×2变体×2halo为每轮12测量JVM，6个JMH主进程不作独立样本；
A是三种fixture各2fork，未采其“三对”计数误读。
17份活动脚本语法与diff检查通过，封存`final-seal-v1.json`；
62生产输入/两份默认Jar不变，全部原失败/超时及未发送草案均保持。

## 三十一、R2基础上的派生图结构分支交接

2026-10-18用户要求继续以旧R2为依据扩大收益，并询问是否已经尝试修改
派生图结构。本轮先验证一个最小的R2运行时执行分支，不能把它误写成派生图
结构优化，也不能把它接入生产。

候选D从旧R2干净复制而来，证据根：
`.tmp/runtime-air-run-amplification-20261018-v2/`。
它只把`visitFactRuns`到事实引用更新之间的有序循环移入私有
`changeTopologyFactRuns` helper，增加`BuildInput`三个只读访问器；单元素直接
调用原scalar，多元素继续调用原weighted helper。原`visitFacts`、scalar、
weighted helper、`applyFactReferences`、事实顺序、失败前缀、owner数字和生命周期
均保持。它不是D的生产接入，也不是对D继续叠加的结构分支。

正确性和保护检查：

- 926个保护输入哈希保持；
- 11,115个失效构型、9,073个引用场景、6,048个摘要变换和77项真实owner账目通过；
- Core、Boundary recovery、Door、Worker、Shared stage、Shared runtime六组契约通过；
- 新增16,382个有序run差分，覆盖长度1至12全部空气位图、首槽、尾槽、满27槽、
  孤立空气、跨非空气边界、负数、溢出和后续异常；
- 旧R2与D的计数、字节账、map状态、异常类型及完整失败前缀一致；
- 冷worker查询的端点、连接数、代价和排序后的组件位图在各变体内部64/64一致，
 关闭后无owner残留。

完整冷查询每次建立新runtime，发布同一63个section版本，确认Base/Super/corridor
缓存为空，从`(2,81,8)`查询到`(77,81,8)`，覆盖依赖构建、Boundary、层次搜索、
组件快照和关闭账目；每模式24次warmup加64次测量，顺序为`O,D`与`D,O`。
D相对O：

- air wall均值两对为`-13.04%/-9.52%`，但中位数为`-2.63%/+4.97%`；
- distinct wall均值为`-3.71%/+4.92%`，process CPU为`+26.47%/+11.54%`；
- mixed wall为`-6.41%/+0.94%`，没有对应的任务量、依赖等待或展开工作量下降；
- readyGraph辅助热态同样不一致，distinct两对wall为`+5.55%/+2.67%`。

结论：D的air均值下降无法在中位数和执行顺序中稳定复现，distinct存在明确
退化；它只作为诊断件封存，不接入生产，不继续验证B′跳段遍历或`ALL_AIR`
专用引用cell。DeepSeek复核包`followup-packet.md`为3372字节，固定
`deepseek/deepseek-v4.1-flash`，28.723秒返回`ACCEPT_SCOPED`，本轮必须修改项无，
同意停止这条仅减少引用循环常数项的路线。

后继会话必须区分三条线：

1. R2仍是“10%那版”的性能基线，但R2本身也只是air改善、distinct退化，
   并非所有场景都下降；
2. D是从R2派生的独立隔离诊断件，不能与R2或生产代码混合；
3. 下一项若继续，应从干净R2另建独立的派生图结构候选，目标是减少真实
   Base/Boundary构建或依赖闭包工作量；不要在D上叠加，也不要恢复已停止的
   图级引用聚合路线。新候选必须先定义不变量和完整cold worker验证，再决定是否实现。

本节完成交接，不修改生产源码、默认JAR、预算、TTL、haveTime、快照或当前性，
不提交、不推送。步骤8优化和整体性能资格仍未结清。

## 三十二、组件结构驱动的构图减量与一次针对性调整

2026-10-19用户授权先完成机会计数、隔离候选正确性、完整冷worker对照，
随后依据首版正面wall/分配结果，追加授权在同版上针对性调整一次。
正式[报告](../../reports/2026-10-19-component-build-shortcuts-validation-zh.md)，
证据根`.tmp/component-build-shortcuts-20261019-v1/`。

O为第三十一节所述干净R2，C从O独立复制，未使用D。
C只在零/单组件时省略Base局部边枚举、空组件任一端时生成等价空Boundary；
两端各一组件仍正常连接。合法锚点、组件、halo/stamp、引用原始数字和发布当前性保持。
独立I诊断的air/distinct/mixed各2次，每次12零+12单组件Base，
可省98,304格位和96,768界内移动候选；148 Boundary中114涉及空端点，
可省120,960/140,544候选迭代。只代表该75格平地构型，不是CPU份额。

O/C/C2每版46输入×8体型/通道、368源图构型，23,184规范记录逐项一致；
各3,014,656标签读取含复用scratch后的旧图复核，20,608 Boundary调用、
1,840参数拒绝。C及C2各91,668原维护断言、77真实owner和六组契约通过。
源码差分限定C只改两图类，C2相对C只改Base；999保护输入及原C冻结保持。

首版有效四JVM O,C/C,O，各模式24 warm+64测，C相对O：
air wall均值-11.99%/-8.43%、中位数-16.31%/-6.39%；
distinct均值-14.50%/-17.90%，mixed-19.39%/-17.29%；
全部分配下降13.49%–15.15%。完整CPU air-9.68%/-6.33%，
distinct-2.50%/-41.86%，mixed+2.94%/-26.83%。
mixed第一对531.250→546.875ms，多15.625ms；原严格门未全过，判定及不利值保留。
用户随后允许C2，不追认原门通过。

C2利用已有合法锚点mask识别空图，跳过组件种子遍历，并只计算一次统一标签。
六新JVM O,C,C2/C2,C,O的三臂对照仍各模式24 warm+64测：
C相对本组O全部wall均值/中位数及分配仍下降；
C2/C air均值+21.70%/+7.54%、中位数+18.83%/+7.48%，
distinct均值-2.26%/+1.07%，mixed-0.74%/-0.05%，未达追加预定选择规则。
部分短窗口完整CPU累计为0，比例及相关几何均值为null，不解释为-100%或CPU收益。
最终保留隔离C，C2不采用，不继续C3；未确定C2退步的具体编译/布局因果。

正确性分析器一次误要求未加载runtime类，性能未开始前修正、候选未改；
首四性能JVM关闭后存活线程分配计数回落，全记录负值，原件保留。
仅一次四JVM技术重试改用JDK累计全线程分配，权威`cold-v2/`；
追加三臂CPU零值比例分析修正，不重跑JVM，权威`c2-cold-analysis-v2.json`。
原失败、原分析及两个协议均保留，没有混池、删样本或事后改判旧门。

本轮未新增DeepSeek审核、Minecraft实体路线、稳定CPU、A/A、C/N或生产资格。
生产、默认Jar、旧R2/D、预算/TTL/haveTime/快照/当前性保持，无提交推送部署。
本轮验证及用户追加的一次调整完成；步骤8整体仍未结清。
后续若申请实际路线或CPU资格，应另立完整计量窗协议，不续本轮短窗口挑结果。

## 三十三、C 与原生产基线 B 的接口对齐复测

2026-10-19用户指出此前C冷宏观查询与B历史热API没有对齐，
明确授权对齐接口再测一次。本轮复用完全相同的旧benchmark/profiler及冷查询字节码，
未修改B/C接口、候选实现或fixture，未测R2第三臂。
正式[报告](../../reports/2026-10-19-interface-aligned-c-baseline-validation-zh.md)，
证据根`.tmp/interface-aligned-baseline-20261019-v1/`。

B为`runtime-maintenance-validation-20261018-v1/B/compiled-v3/main`，
C为`component-build-shortcuts-20261019-v1/C/compiled/main`。
两版共同依赖/支持类，只替换生产main/resource；C无R2或B生产main回退。
1474冻结保护文件前后保持，B/C各257冻结class核对；
热12fork各62个实际生产类、冷4JVM各122个类来源均匹配。

热API为已就绪图`requestBaseGraph().join→componentCount→close`，27section及anchor；
B,C/C,B/B,C，各模式3×1秒warm、4×1秒measure，Java17/512MiB、同一CPU/GC/编译profiler。
12个测量fork、123,826,210操作、48 CPU归一化检查通过。
air CPU -30.21%/-44.14%/-40.85%，wall -30.52%/-42.50%/-40.84%；
distinct CPU -9.82%/+4.52%/-6.95%，wall -7.58%/+2.82%/-5.64%。
air第一对分配744.020→768.014B/操作，+3.22%；distinct第三对约-24B/操作，
两者保留且未确定对象消除等具体因果，不将GC次数直接当每操作成本。

冷工具为同一63section、75格、每次新runtime及空派生缓存、静默并关闭的完整查询；
B,C/C,B，air/distinct/mixed各24warm+64测，768测量及288预热的完整输入、
走廊成员摘要/代价/端点/连接一致，全部关闭引用归零断言通过。
直接C/B：distinct均值-15.85%/-17.04%，mixed-17.13%/-25.36%，
但air均值+1.01%/+3.71%、完整均值+0.86%/+4.71%；
第一对P95+15.49%，第二对中位数+3.57%。六组完整分配下降14.00%至14.59%。
完整CPU air+20.83%/+44.00%、mixed第一对+12.00%，其余下降，
仍有每组42至52个单次零读数，不解释成稳定CPU因果，也不删除不利值。
air同期编译和GC较高，但没有逐方法归因，不据此解释全部回退。

当前用这份C/B实测回答基线问题，不再用旧R2/B的distinct退步代替C，
也不把旧C/R2全部正向结果推成C/B全部正向。原历史结果、门失败和封存保留。
本轮对齐复测完成，未改算法、无新增技术失败或择优重跑；
未运行游戏或接入生产，不继续C2/C3或追加样本，无提交推送。
步骤8整体、稳定性能和发行资格仍未取得；后继若继续应针对本次air冷查询等
实际不利负载另定研究范围。

## 三十四、现有端口位图的空行外提验证

2026-10-19用户先要求结合数据和网络确定方法、不直接验证；
随后询问起点判定成本，并确认“已用位图储存、O(1)开销，确实值得验证”。
本轮只验证端口空行外提，不实施第二项BuildInput边界快照复制方案。
正式[报告](../../reports/2026-10-19-boundary-row-gate-validation-zh.md)，
证据根`.tmp/boundary-row-gate-20261019-v1/`。

C为冻结组件构图候选，P从C复制，仅在GROUND `boundaryLinks`的v行入口、
方向/距离/dy循环之前增加 `faceRow(...) == 0` 的continue。
原内层candidates、VOLUME、碰撞、输入/引用/失效/owner及全部图字段不变。
源码差分限制一文件、一条判断；不是另建位图，也不是先前碰撞按x行定位原型。

C/P各23,184规范记录逐项一致，包含20,608 Boundary调用、1,840参数拒绝；
新增flat/city/dense三输入42个完整边界输出一致。
EAST面flat/city各45/48空行，候选循环576→36，新增48外层判断；
dense为强制全格GROUND_OPEN的人工压力输入，16组件、0/48空行，
循环仍576次并多48判断，不能包装为真实地形分布。
P的77 owner及Core、Boundary recovery、Door、Worker、Shared stage、
Shared runtime六组契约通过，复用原C未改引用维护的旧差分证据。

局部JMH C,P/P,C，三输入各独立fork，3×1秒warm/4×1秒measure，
12fork/12,080,622操作：flat耗时-6.07%/-8.48%，CPU-5.71%/-12.38%；
city耗时-28.09%/-27.83%，CPU-31.96%/-29.18%；
dense耗时+2.77%/+0.59%，CPU+2.87%/+1.46%，分配接近不变。
这些是完整Boundary生成的局部证据，不是整查询收益。

同一原ComponentColdProbe/classes-v3、63sections、75格、新runtime/空派生缓存，
C,P/P,C四JVM，各模式24warm+64测，768测量/288预热全部输入、
完整走廊成员/代价/端点/连接一致，关闭引用归零。
P/C查询均值：air+8.52%/+19.92%，中位数+8.21%/+22.00%、P95+17.14%/+12.63%；
distinct均值+0.60%/+0.77%，mixed-1.89%/-3.37%，后者第二对中位数+3.41%。
完整CPU air-2.91%/+82.19%、distinct+9.09%/+29.73%、mixed-20%/+66.67%；
各组仍有41至56个单次零读数，保留原累计但不外推稳定CPU或根因。
第一对air quick/waits/expansion增加58，第二对air这些与build均相同，
不能把全部回退解释成任务变多、编译、GC或噪声。

局部门通过，冷控制/收益门未过，`screen.promising=false`、`selected=C`。
当前P不采用，保留其局部收益及全部不利值，不将封存等同方法永久无效。
没有发现接口、oracle或类来源错误，也没有完成完整查询回退的具体归因。
不自动追加helper改写、第二候选或相同测试；没有新的P/B测量，
不将P/C与第三十三节C/B百分比连乘；旧B/R2/C/C2/D和原门保持。

1519保护输入、346候选/工具冻结输入前后保持；
16测量JVM类来源与48 CPU归一化通过，无技术失败/择优重跑。
无生产、默认JAR、预算/TTL/快照修改，无DeepSeek、Minecraft、提交或推送。
本轮验证完成并封存，步骤8整体、稳定CPU、实际路线及发行资格仍未取得。

## 三十五、C 接入条件审查、生产基线提交与源码接入

2026-10-19用户先要求送审DeepSeek，强调无头会话无上下文、须控制材料大小；
三份自包含包分别审查runtime、construction和evidence，实际7,802/8,006/8,296字节，
均正常退出并明确必须修改项无。结论为两份`ACCEPT_SCOPED`及
`READY_FOR_INTEGRATION_VALIDATION`，只认可受控源码/构建/游戏验证，
不批准默认发行或实服部署。详见
[审查报告](../../reports/2026-10-19-c-production-readiness-deepseek-review-zh.md)。

用户随后授权“先将当前生产区提交到git，然后开始接入”。
先在`1.20.1`创建本地基线提交
`fe9b080bea627f7af6f06881981402d84604fdc7`，
标题`Save production navigation baseline before C integration`，
保存此前57份生产/测试/工具/文档变化；无关`forge-installer.jar.log`未纳入且未改。
本轮没有push或部署。

之后只接入冻结C完整三文件差分：Base的canonical ALL_AIR段摘要及零/单组件内部边短路，
Super的空端点Boundary空结果，Runtime的long预检加权引用及原scalar回退。
相对B为80增/2删，包含继承R2的实现，未纳入P/C2/D。
62份生产输入归一化后匹配冻结C，其余59份生产输入字节相同。
正式[接入报告](../../reports/2026-10-19-c-production-integration-zh.md)，
证据根`.tmp/c-production-integration-20261019-v1/`。

实际当前Gradle `check build jarJar`及reobf通过；12项核心/反向接入、
7组worker、99,951共享阶段检查通过。
复用原字节码工具并加载实际`build/classes/java/main`：
23,184完整图记录/20,608 Boundary/1,840拒绝、91,668维护断言、
77真实owner及1,296共享运行时检查通过。
四驱动分别核对24/55/63/82个实际生产类来源，257生产编译类及新JAR身份冻结。
初次Gradle回环连接失败、一次类日志CRLF解析失败均记录；
分别使用既有JVM参数重试构建、修正解析器复核，没有修改算法或择优重跑测试。

游戏前冻结精确四个串行JVM：B/C冷热304格，C/B的8格及28格各64并发。
沿用原探针、路线、速度II、2GiB/G1、固定预算、30秒就绪及90秒请求超时，
关闭深插桩，使用世界副本；候选直接加载实际集成混淆包。
B/C探针agent/helper JAR逐字节一致，全部起点/目标/设置对齐，
260总请求无遗漏，两侧各130/130到达且终态全部成功。
活动引用/事实字节、关闭service/scheduler和完整拓扑缓存/任务/在途账目归零；
四次退出均0、未超时、无其它Java干扰，进程均结束。

性能不全面改善，C/B：

- 冷304格首Path237.953→239.931ms（+0.83%），CPU-2.83%、分配-1.76%。
- 暖304格首Path4.869→5.597ms（+14.94%），CPU+29.82%、分配+2.68%。
- 8格并发首Path中位+0.06%、P95-13.01%，CPU+3.31%、分配+1.73%。
- 28格并发首Path中位+19.27%、P95+10.06%，CPU-6.93%、分配+11.87%，
  tick P99 4.136→10.775ms（+160.53%）。

原始累计、每秒值、GC、完整tick/相邻tick间隔及全部不利值保留，
不归噪声或确定GC/JIT因果，不追加性能复跑或新改法。
成本计量止于终态后至少十tick，显式移除实体/关服在后；
关服后CPU/分配计数null，不称完整进程成本闭合。
每套只有一对B/C，仅结清源码接入及本轮有限构建/功能回归，
稳定性能、C/N、步骤8整体及发行资格仍未取得，旧筛选门不追认通过。

实际集成完整包SHA-256为
`9a9abceb4b56dbc00d4ba865e2b1d9ffd4ac6978c72f88ffb38afb5d4cb4413c`，
位于本轮`artifacts/accelerated_navigation-0.1.0-all.jar`。
原`build/libs`两份默认JAR保持；1,978项原保护集合按三文件白名单核对，
最终封存`final-seal-v1.json`。接入改动保留在工作区，未作第二次提交、push或部署。

## 三十六、C 集成游戏原样一次复跑及 DeepSeek 复审

2026-10-19用户要求“原样再跑一次测试，然后再送审”。
本轮复用第三十五节实际B/C生产包、probe/agent/helper、原runner和审计器，
不修改实现、不重编译；仅使用新输出及世界副本，测试参数、实际起点目标和原顺序相同。
先冻结复跑协议，新增四个串行JVM：
B/C的冷热304格，随后C/B的8格和28格各64并发。
正式[报告](../../reports/2026-10-19-c-integration-unchanged-repeat-deepseek-zh.md)，
证据根`.tmp/c-production-integration-repeat-20261019-v1/`。

2,500项冻结输入前后核对，包括原轮382证据、全部当前源码/测试/工具、
两build/JAR身份和23份模板世界文件。B游戏源码62/62匹配冻结B；
跨轮所有launch除输出/socket路径外，设置/Java/构建/探针/路线完全一致。
四JVM退出0、未超时、无其它Java干扰，B/C各130/130到达且终态全部成功。
活动引用/事实字节、终态请求及关服缓存/任务/在途账目清零。
两轮总计8JVM/520请求、B/C各260/260到达；不把批内64请求当独立重复。

C/B首轮/复跑：

- 冷304格CPU-2.83%/-19.84%、分配-1.76%/-1.25%，首Path+0.83%/-7.51%。
- 暖304格CPU+29.82%/-32.20%、首Path+14.94%/-61.08%，分配+2.68%/+0.01%。
- 8格并发CPU+3.31%/-15.77%、首Path中位+0.06%/-19.89%，P95-13.01%/-19.11%。
- 28格并发CPU-6.93%/-19.20%，Path中位+19.27%/-18.37%，
  P95+10.06%/+23.66%，tick P99+160.53%/-53.69%。

主要大回退方向反转，不能判定稳定退化，也不判为纯噪声。
28格Path P95本次127.449→157.597ms，绝对多约30.148ms，是两轮同向不利项。
复跑8/28格累计分配虽-1.15%/-4.22%，每秒仍+0.35%/+0.57%，
因为计量窗短1.50%/4.76%；累计与每秒原值均保留。
两轮CPU均值比四场景为-10.35%/-10.03%/-7.20%/-13.72%，仅作描述，
分位均值不冒充合池分位，n=2、固定顺序、共享预热和小样本尾部限制仍在。
成本窗仍止于显式实体移除/关服之前，关服后CPU/分配为null。

完整复跑结束后，按无上下文约束分三包经原WSL dsh headless送审：

| 包 | UTF-8字节 | 秒 | 结论 | 必改 |
| --- | ---: | ---: | --- | --- |
| runtime | 8364 | 93.194 | ACCEPT_SCOPED | 无 |
| construction | 8354 | 35.429 | ACCEPT_SCOPED | 无 |
| evidence | 7032 | 38.328 | ACCEPT_SCOPED | 无 |

实际`deepseek/deepseek-v4.1-flash`，三次正常退出、未超时；
prompt/实际transmitted/packet相同，配置前后哈希均保持`071db565...1858`。
代码包未发现所给片段的具体接入缺陷；结果包认为无具体必须回滚/修改C的依据，
可保留源码集成，但不批准稳定性能或发行；无功能失败不代表性能通过。
结果审查原文的“幅度小”不作为本地对全部指标的概括，
28格P95、暖分配和8/28格每秒分配等同向不利均继续保留。
不假称DeepSeek独立核验仓库/执行实验，不自动追加第三轮或新改法。
若继续，先另定一个可检验问题和有界协议，不为凑通过追加样本。

原轮报告/数据不改写，三份当前状态文档只追加新记录并保留全部旧行。
一次只读命令语法错误及8966B构图草稿被本地上限拦截均留记录，
没有游戏技术重跑或失败样本替换；最终封存`final-seal-v1.json`。
本轮原样一次复跑及复审结清，生产源码/原默认两JAR保持，
无新提交/push/部署；步骤8整体、稳定性能、C/N和发行资格仍未取得。

## 三十七、C 集成后 28 格尾延迟机制诊断

2026-10-19用户在逐项不利值分析后要求“寻找原因”。
固定新的窄问题及协议，只新增C/B两个串行诊断JVM，各保留原8→28格64并发。
原生产/探针JAR直接复用，预算/TTL/优先级/路线不改；
隔离agent/helper增加只读调度状态及少量阶段钩子，开既有profile JFR。
正式[报告](../../reports/2026-10-19-c-integration-tail-cause-diagnosis-zh.md)，
证据根`.tmp/c-integration-tail-cause-20261019-v1/`。

B/C各128/128到达、全部SUCCEEDED，trace嵌套/容量和退出/清理通过。
24,098/24,025事件及3,022/3,078预算状态无丢失；运行前后579保护输入、
21诊断输入核对，之后三份状态文档只增加本节记录并保留原件。
生产源码、原默认两JAR和旧净测保持；仅诊断agent/helper重新编译。

已确认共同机制：异步结果/窗口就绪后还要主线程交付及软任务预算执行，
截止余量或预算不足会使部分请求跨到下一tick。
C actor24窗口worker回调在场景提交后54.5121ms开始，
主线程回调65.7141→65.7232ms，搜索109.1258ms开始、Path109.6796ms安装。
中间主线程交付等11.202ms，预算队列等43.403ms，窗口计算约0.110ms。
C在42.2985ms因HEADROOM停止，余额2.3937ms、余量0.3971ms、pending5；
后续补发中actor26同步搜索69.3943→73.5092ms，耗4.1149ms，
73.515ms余额-1.3281ms、pending7，随后新tick恢复20ms额度再执行。
同步调用不能中途抢占；负余额不代表本轮加预算或启用跨tick偿债。
B同样在70.1004ms因BALANCE停止，pending16。

本轮28格P95 B114.8982/C107.6327ms（C低6.32%），未复现旧不利方向。
只能确认等待阶段及停止条件，不能追溯补齐旧两轮缺失事件，
不把旧+10.06%/+23.66%归为纯波动或认定已修复。
实际Base均34、Boundary81/83、快速切片均198；
旧两轮额外切片并非C每次固定成本，尚未证明解释旧差额。
本轮相关首Path阶段无GC/主线程长monitor事件；两侧各4个原生栈反优化事件，
均无可直接归属耗时，不据此给旧测量建立GC/JIT因果。

首次离线JFR读取未处理null线程名，部分输出保留；
修正离线读取器另写v2，没有游戏技术重跑。
本轮只结清该窄诊断，无C必须修改项、外部审查或发行资格。
冷路线最大tick间隔/行走阶段中位数仍缺新归因；
后续若实施，应针对主线程往返与同步搜索预算竞争另列范围，
不扩大预算、不自动重开历史窗口候选或继续游戏。

## 三十八、空闲补发取消预算余额限制和墙钟0.5ms提前量

2026-10-19用户明确要求两项一起修改：
常规调度保留预算，主体结束后空闲补发按实际墙钟余量执行，预算只记账；
同时取消截止前额外0.5ms门槛。此授权修订此前“所有派发都受20ms限制”的口径。
报告：[空闲补发调整](../../reports/2026-10-19-scheduler-idle-slack-zh.md)。
证据根：`.tmp/scheduler-idle-slack-20261019-v1/`。

生产新增改动仅`NavigationScheduler.java`：
常规END保留20ms额度和预算准入；补发不以零/负余额拒绝任务，
沿用原43ms绝对截止、haveTime、生命周期及公平队列。
每次真正启动前重查墙钟余量，正余量允许，恰好截止或之后停止；
正常tick主体结束有pending时主动请求合并补发，不必等下一异步回调。
原生同步调用仍不可抢占，完整实际成本、负余额和outside记账保持，
下一正常tick恢复20ms，不引入历史预测或跨tick偿债。

build-v1为补齐结束触发点前的成功中间构建，未启动游戏；
build-v2隔离构建、probe和生产混淆成功，实际生产JAR为`d3467762...fb9f4f`。
只新增一个真实游戏JVM，既有预算控制两遍共286条断言通过；
zero-edge8-first和cross8均到达并SUCCEEDED，真实搜索1/2次、中间窗口0/1。
原range/accuracy、trace、完整tick、正常退出及关闭清理通过，无其它Java干扰。
本轮958 trace事件中没有结束内部发起的ready请求，不宣称该自动触发分支已有真实
预算耗尽运行见证；结束接线经源码核对，补发零/负预算等行为由fake-clock控制验证。
保留该观察，不自动追加游戏。

最终62生产输入和控制源码与build-v2一致，155保护输入逐份哈希保持；
已有C拓扑三文件、默认两JAR和历史结果不变。
现行接入契约、旧预算说明、工作计划及任务索引同步。
本轮为行为调整和有限功能回归，无B/C净测，不宣称旧P95回退已解决、
整体性能提升或发行资格；未外部送审、提交、push或部署。

## 三十九、当前调度首 Path 对照及后续测试规则修订

2026-10-19承接用户“这一轮改动后再测一次，看看和基线的数据如何”，
完成[本轮比较报告](../../reports/2026-10-19-scheduler-idle-baseline-comparison-zh.md)。
证据根`.tmp/scheduler-idle-baseline-20261019-v1/`。
四个串行JVM按旧顺序跑完：B冷热304格、C+S冷热304格、
C+S和B各自8→28格64并发。沿用旧测试接口、探针/agent/helper字节、
runner、世界模板、设置和实际路线，复用已构建JAR，没有新构建或技术重跑。

运行期间用户明确指出有效历史B可复用，本轮重跑没有必要；接受该修正。
用户随后补充新版本必须对齐旧版本测试、非必要不加测试项。
两条现行规则已写入根`AGENTS.md`、任务索引及路线图：

1. 后续先核对版本身份、测试接口、场景参数、工具与计量口径；
   有效历史基线及其它有效数据直接复用。只有相关变化使历史数据失效，
   或所需数据缺失时，才补测对应版本/场景并记录原因。
   候选变更不自动使基线失效，不为形成新配对重跑有效基线，
   不默认重跑完整矩阵；用户明确要求的复跑按该次授权执行。
2. 新版本默认沿用旧版本的测试接口、场景、参数、工具及计量口径。
   只有旧测试不合理或确有必须新增的测试项时才调整/补充，
   记录理由、差异及历史数据是否仍有效。非必要不增加测试项，
   不因版本更新自行扩展矩阵；修正旧测试只补测受影响部分。

从本轮已启动测试结束后执行上述规则，覆盖旧文档默认复跑安排。
本轮未发现原B接口或场景失效，不追认重跑B为必要，也不改写冻结协议。
所有游戏结束后只做离线分析、文档记录及封存，没有追加第五个JVM。

当前候选标为C+S：旧C三项拓扑改动加第三十八节调度调整；
相比旧C只有Scheduler不同，相比B恰好四个生产文件不同。
62份当前生产输入、401项冻结输入及相同工具/实际路线核验通过。
当前生产JAR仍为`d3467762...fb9f4f`，原B为`6ecaf406...17946`。
旧C不能冒充本轮候选，也不能把C+S对B的累计差额全部归于调度。

首Path指每实体提交到第一条真实原生Path装入，本次B→C+S：

| 场景 | 中位数ms | 变化 | P95 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| 冷304格 | 196.1322→200.6381 | +2.30% | 同单次值 | +2.30% |
| 暖304格 | 10.7515→10.4582 | -2.73% | 同单次值 | -2.73% |
| 8格64并发 | 161.5263→154.2311 | -4.52% | 200.3666→167.9036 | -16.20% |
| 28格64并发 | 51.8088→53.8175 | +3.88% | 101.3978→74.4613 | -26.57% |

全计量窗CPU依次+15.68%/+55.15%/-20.00%/+17.14%，
分配-0.94%/+0.20%/-2.40%/-5.42%，累计及每秒值均在报告保留。
28格到达P95增加249.6678ms、max增加650.8614ms；
8格tick P99/max增加7.15%、冷tick P99增加11.09%。
完整tick不包含空闲补发，其下降不能单独证明总成本降低；
相邻tick间隔、>50ms次数及累计超额、GC、到达分位全部保留。

B/C+S各130/130实际到达且终态全部SUCCEEDED，首Path无缺失，
两侧搜索数及partial数相同，原公共审计及关闭资源清理通过。
全部退出0、未超时，无Java进程残留。
成本计量仍在显式移除实体及关服前结束，关服后CPU/分配为null。
每个场景每版仅一JVM，64实体不是独立复测。
旧C两轮28格P95+10.06%/+23.66%继续保留；本轮方向反转
不能冲销旧证据或证明其机制已修复，也不合并成同一候选的重复。

三份状态文档原文连续保留，新增报告及`AGENTS.md`纳入`final-seal.json`。
本轮比较与规则记录结清，无稳定性能、步骤8整体或发行资格；
没有新源码修改、默认JAR替换、外部送审、提交、push或部署。

## 四十、ComponentSnapshot 分层去重隔离验证

2026-10-19用户授权对第9步候选进行隔离验证。
报告：[ComponentSnapshot去重隔离验证](../../reports/2026-10-19-component-snapshot-dedup-isolation-zh.md)。
证据根`.tmp/component-snapshot-dedup-20261019-v1/`，候选S9只复制当前Runtime，
按section/signature/channel/componentId在分层恢复中惰性复用不可变快照；
普通路线不创建附加查找表，命中仍验证captured图身份，
16384完整endpoint上限、完整映射及按endpoint保守计费保持。
成功/取消/worker失败均释放查询表；key不保留Base图。

真实普通/分层路线复用旧AuditChecks字节码和历史预期，snapshot分别4→4、9→5，
映射4/9、cost3/4、witness0/3及全4096格成员摘要保持。
原macro-commit4项、boundary-recovery9项和新增28720条控制通过，
覆盖错误身份命中拒绝、别名、不同身份、负坐标/全成员、重物化、
16384上限、查询隔离及成功/取消/失败清理。
首次控制为file URI字符串形式误报，修成Path比较后只重试该项，原日志保留。

当前20份拓扑源码与旧C逐字节相同，离线入口只引用未变的Scheduler.Priority，
不执行空闲补发，故历史C数据仍有效。直接复用旧同接口C/B，
未重跑历史基线或建立新三方配对，只新增两个S9性能JVM。
沿用原ComponentColdProbe字节、场景、24预热+64测量和完整关闭计量窗，
共144预热/384计量；全部528行input/corridor hash、cost、endpoint、
hierarchical及3段witness与历史C对齐，静默及关闭所有者检查通过。

相对C的查询wall均值，air−7.43%/−15.02%，
distinct+6.48%/+4.57%，mixed+1.10%/+1.51%；
完整分配仅−0.505%至+0.510%，新增表/key/查找成本不隐去。
第一air的中位/P95略高，也不将其均值改善全归于去重。
第一JVM air累计进程CPU为0，分负载CPU不可用于收益判定；
第二JVM air/distinct CPU原观测+12.96%/+8.33%、mixed−32.76%保留。
旧air有GC且JIT/切片状态不同，未定位计量异常或整体差额的具体代码根因，
按用户此前口径暂记波动，不扣除不利值、不追加复跑。

相对原B，六行查询wall−6.49%至−24.24%、完整分配−13.62%至−14.46%，
这是包含C的累计效果，不证明S9自身收益；CPU没有全面降低。
快照机制减量44.44%成立，整体增量收益未确认。
本项有限结清，保留隔离原型、暂不接入、不扩服务器矩阵；
可以进入后续阶段范围梳理，不自动组合或推进生产。

714保护输入及实际加载类来源核对通过，最终分析summary-v2，
离线解析CRLF修正只重做分析，无Java净测重跑。
三份旧状态文档原件备份，报告/候选/输入/数据封存final-seal.json，
生产源码及默认两JAR保持，无Java进程残留。
本轮无游戏首Path/到达/tick增量结论、外部送审、提交、push或部署。

## 四十一、S9 去重耗时不利的阶段归因与补审

2026-10-19用户指出必须寻找“去重为什么时间反而变长”，并指出第四十节遗漏送审。
据此继续[定向归因与补审](../../reports/2026-10-19-component-snapshot-cause-and-review-zh.md)，
新证据根`.tmp/component-snapshot-cause-20261019-v1/`。
旧S9、原报告/协议/净测保持，新授权覆盖上节停止扩测及“未定位便推进”的安排。

先复用旧数据而非新跑净测：Q为请求至future返回，M为MacroQuery创建至finishMeasurement，
M不含前端点解析及后缓存重物化/future交付。按同一旧样本Q−M分解，单位us：

| 旧样本 | Q总差S9−C | M内差 | 窗外残差 |
| --- | ---: | ---: | ---: |
| distinct1 | +189.597 | +9.580 | +180.017 |
| distinct2 | +132.953 | −77.294 | +210.247 |
| mixed1 | +31.708 | −87.042 | +118.750 |
| mixed2 | +41.269 | −91.116 | +132.384 |

窗外前/后无法从旧记录再分，不全叫排队；air原本更快，不泛化为全场景不利。
旧第一JVM常多一个worker切片，第二近似相同，不能把全部差额归于切片增加。
20份拓扑源码与Priority身份有效，7个相关类规范化javap指令相同，历史基线继续复用。

旧数据缺分阶段事件，因此只补C/S9各一个诊断JVM，沿原工具/场景/24+64，
同窄ASM观察capture/构造/slice/解析/创建/交付及构建，保存JIT/GC/safepoint。
全部528行输入/结果hash、cost/endpoint/witness对齐，构造9/5、capture均3，
slice与progress一致，嵌套/丢失/关闭检查通过。该数据有agent扰动，不能当净测；
原工具diagnostic=false只表示旧WorkCounters关闭，目录另标diagnostic-only。
capture分配5432→3800B，distinct124.956→78.898us、mixed124.905→76.639us。
本次mixed整体+36.467us，首slice前+31.239、slice合计−58.916、
slice间隔+69.220、末slice到future−5.077us，逐行不重叠区间闭合。
本次distinct反而−185.677us，不能追溯冒充旧慢样本根因。

构造器探针数不同，故按协议补一次无agent局部JMH控制，3x1s预热/3x1s计量，
真实走廊9端点/5组件，C86.1249→S946.9793us/op、分配5904.506→4272.278B/op。
同走廊选5唯一endpoint，仍强制refining=true，C48.8768→S950.6374us，
分配3568.289→4016.300B。后者是分层表结构控制，不是普通路线成本；
1.7606us差小于同fork误差，不能认定稳定退化，也不能可加拆分map/扫描耗时。
每版本每负载一个fork，C测量GC0、S9各1次；无第二组、游戏或新完整净测配对。
诊断C1两版capture都被调用方拒绝内联，无agent两版snapshot都有C2 inline(hot)，
不能凭方法变大断言仅S9失去内联或它解释全部旧差额。

DeepSeek首包6928B/279.193秒REQUEST_CHANGES；按源码补身份/当前性/取消/重物化不变量后，
代码包5217B/308.507秒ACCEPT_WITH_GATES、必须修改项无，仅限本轮范围。
结果包5912B/153.817秒ACCEPT_WITH_GATES但三项文句必改：
限定distinct/mixed、改称两时钟代数分解、说明unique强制表路径及非可加成本。
全部采纳，再发4553B最终包，185.328秒ACCEPT_SCOPED、必须修改项无；
四次调用均退出0、未超时、settings前后哈希保持，首轮拒绝及所有修改意见保留。
不是生产或全面并发批准，不把条件性反例当成已确认代码缺陷。

本轮取得窗内/窗外分解及局部净成本证据；旧窗外具体前解析/后交付、
某次GC/JIT/线程调度事件仍未闭合，不再仅因“未定位”宣布纯波动或全面结案。
820旧输入及原S9保持，三份状态原文备份，新结果/审查封存final-seal.json。
无生产源码/默认JAR修改、提交、push或部署；S9继续隔离。

## 四十二、S9 直接影响窗口补全与单轮验证

2026-10-20按会话日期记录；原测量机器UTC为2026-10-19T16:02:01Z至16:02:20Z，
未修改原时间戳。用户新授权：补全候选直接影响窗口，运行一轮，只分析窗内，
窗外按波动处理、间接影响不做处理。该分析范围覆盖上节要求继续追溯旧窗外事件的安排，
旧结论与原件保留，不将范围取舍写成已排除候选窗外因果。

[报告](../../reports/2026-10-20-component-snapshot-direct-window-zh.md)，
新证据根`.tmp/component-snapshot-direct-window-20261019-v1/`。
W起于MacroFlight.start中的NEW MacroQuery之前，
止于最终请求future.complete调用返回之后，覆盖分配/零初始化/MacroQuery构造、
去重捕获、清理、缓存、重物化、WorkerResult创建和结果交付。
这是候选变化触及的冷成功查询路径，不是整条请求的所有执行：
代表请求选择与端点解析在前，不读取候选字段或去重结果；后续消费者和缓存重放不扩测。
W含异步间隔，是墙钟不是CPU；get可能先于complete返回，
不裁掉这段，以原关闭后的时钟包住W。hook未保存complete的boolean，
唯一完成者为固定成功fixture的路径前提，不宣称并发终态true逐条实测。

旧记录缺少这两个边界，不能重建W；旧基线和正确性证据仍有效，
本轮仅补缺失口径的C/S9各一JVM，不重跑B或完整历史矩阵。
沿用原ComponentColdProbe字节、原生接口、63section和air/distinct/mixed顺序，
每负载24预热+64测量、512MiB堆；仅这一轮，144预热/384测量。
全部528行输入/结果hash、cost/endpoint/connection、状态/witness及关闭检查通过。
相同窄agent不插桩每个snapshot/capture/slice；每查询起止、最终future和四锚点各一次，
顺序与边界完整。两版10类/26已知调用点剥离后，全部原方法指令、
分支目标、异常表、字段和方法结构保持，实际加载来源对齐。

| W窗内，ms | C均值 | S9均值 | 均值变化 | 中位变化 | P95变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| air | 5.354856 | 4.303084 | −19.64% | −8.61% | −30.99% |
| distinct | 3.147814 | 3.152159 | +0.14% | −3.38% | −4.66% |
| mixed | 3.171675 | 3.079422 | −2.91% | −3.12% | −6.60% |

distinct最大值4.4274→8.0029ms、最小值也升高，不删极值、不把所有窗内不利归为波动。
其+4.345us均值符号对S9单个最大值敏感；算术敏感性另存，不替换完整64条结果。
air C最大29.2128ms全部保留，不把其均值19.64%都算作去重本身收益。
distinct主体段均值+7.872us，MacroQuery构造−0.248、清理后区段−3.278us，净+4.345us；
mixed构造点估计仍+0.472us，不给稳定含义，清理后区段三负载均下降。
后段包含缓存/重物化，不能全部叫future交付；三段逐行加总W，不作map/扫描CPU分账。
旧+133至190us来自Q而非M；旧M两组distinct+9.580/−77.294us。
旧净测与新W口径/插桩/批次不同，不由本轮判定旧回退消失或解释旧差额。

首版agent编译类型表达式错误，测试JVM尚未启动；失败源码/日志保持，
修正副本身份另记，随后只跑这一轮。6566B方法/结果初审要求收紧四项口径，
已修正文句并以6259B独立包复核，154.260秒返回ACCEPT_SCOPED、本轮必须修改项无。
两次调用均退出0、未超时、配置保持；原异议及澄清保留，不代批生产。
956旧保护输入、实际修正工具、528行、10类指令及两次审查封存final-seal.json。

本轮限定测量完成，窗内观察支持保留S9隔离候选，不授稳定非劣、全面提速或生产资格。
窗外按用户指定口径视为波动，不再据其继续扩测；间接影响不处理。
生产、默认JAR、原S9及旧净测/封存保持，无接入、提交、push或部署。

## 四十三、S9L 小容量索引降低去重附加成本

2026-10-20用户授权在本轮S9基础上尝试降低索引成本，
[报告](../../reports/2026-10-20-component-snapshot-small-index-zh.md)，
证据根`.tmp/component-snapshot-small-index-20261020-v1/`。
原S9、生产及默认JAR保持，新候选S9L只改隔离Runtime副本。

查询按需创建私有ComponentSnapshotIndex，8唯一组件以内用Object[16]
保存已有endpoint/snapshot引用，完整比较section/revision/channel/componentId，
不另建key/节点；第9唯一项构造成功后一次转原HashMap，复用前8份快照并释放数组。
大表get/miss put仍共用同一key；MacroQuery仍一根索引引用，
普通路线、snapshot成员构造、命中校验、16384完整endpoint上限、
完整映射、保守计费及终态置空保持。未引入全局缓存或Base引用字段。

原AuditChecks普通4/4和分层9/5、成员hash/cost/witness保持，
macro-commit4、boundary-recovery9及73829断言通过。
新增8/9边界、无效第9项拒绝不污染、升级保留快照对象、释放数组、
small/map身份拒绝与成功/取消/失败清理；原16384容量控制实际执行。

优先复用有效历史S9/C窗口和S9局部数据，不重跑参照。
仅新增一个S9L全窗JVM，沿相同工具/agent字节、原生接口、63section、
air/distinct/mixed顺序及每负载24预热+64计量、512MiB。
新264行（72预热/192计量）和复用528行身份/结果/锚点/关闭检查通过；
5变换类剥离13个已知探针后原方法指令、分支/异常表和字段结构保持。
W起止不变，只分析窗内，窗外按用户口径视为波动、间接影响不处理。

| W相对历史S9 | 均值ms，S9→S9L | 均值变化 | 中位变化 | P95变化 |
| --- | ---: | ---: | ---: | ---: |
| air | 4.303084→3.439756 | −20.06% | −24.44% | −18.93% |
| distinct | 3.152159→2.228036 | −29.32% | −26.81% | −21.20% |
| mixed | 3.079422→2.069203 | −32.81% | −33.09% | −30.55% |

三负载min/max及三个阶段均值也下降；保留全部64条及极值，不作逐请求配对或稳定推断。
复用C作累计背景的均值变化−35.76%/−29.22%/−34.76%，不全归本次索引。

沿旧无agent局部JMH字节/真实输入/3x1s预热+3x1s计量，只补S9L actual/unique各1fork：
actual9/5耗时46.9793→44.7218us，分配4272.278→3800.265B（净−472.013，−11.05%）；
unique5/5仍强制refining=true，耗时50.6374→45.3780us，
分配4016.300→3672.268B（净−344.032，−8.57%），仍较C多约104B。
时间点估计均小于同fork误差，不宣称稳定加速；snapshot.variant=S9为旧工具dedup开关，
真实S9L身份以overlay来源/hash和execution.role证明，不冒充S9版本。
小表输入未触发第9项升级，只有该分支功能证据，无大量唯一组件性能资格。
局部数微秒变化不能解释W约863–1010us全部差额；W含异步间隔，未精确分账因果。

DeepSeek代码7236B/407.959秒ACCEPT_WITH_GATES、必须修改项无；
已据源码核对primitive/enum、record和key规则、原查询串行与终态清理前提。
结果7368B/250.122秒ACCEPT_SCOPED、必须修改项无，仅认可有限净分配证据及隔离保留。
两次退出0、未超时、settings保持，问答与原意见保留。
1021旧保护输入、原S9/生产/JAR、工具、数据、5类指令及审查封存final-seal.json，
三份状态旧文档备份。无编译或测试失败，没有额外调阈值/复跑。

本轮降低小规模索引成本的目标取得局部净分配直接证据，
S9L为后续优先保留隔离候选，S9继续作为有效参照；稳定性能、生产或发行未授权。
无生产接入、默认JAR替换、提交、push或部署。

## 四十四、S9L 接入条件审查、远端检查点及生产源码集成

2026-10-20用户新授权“送审DeepSeek，看是否具备接入生产条件，如果通过，
就push当前生产区到github，然后开始接入”。
见[生产接入报告](../../reports/2026-10-20-component-snapshot-production-integration-zh.md)，
证据根`.tmp/component-snapshot-production-integration-20261020-v1/`。
本节更新上节隔离保留的当前状态，原阶段结论与封存保留。

本次完整P→S9L同时包含S9成员快照去重和S9L小表索引，
只改Runtime，当前C构图及idle调度作为接入前生产检查点保留。
送审前核对1021旧保护输入和135旧证据，冻结62生产输入、默认两JAR及状态文档。
8198B代码包195.339秒返回ACCEPT_WITH_GATES；补齐相等/哈希、不可变内容、
消费者与终态路径前提后，8498B决策包97.802秒返回READY_FOR_INTEGRATION_VALIDATION；
两包本轮必须修改项无。实际deepseek/deepseek-v4.1-flash，不改配置。

先提交当前生产/既有测试/规则/相关历史报告20文件：
`f5cd1a71615fbefc609bf89d8394b5da0de245d1`，
tree `3610e5d8cfd3a97211dde7681752f03371fb6541`，
标题Save current production before component snapshot integration。
普通push origin HEAD:refs/heads/1.20.1，远端由4b82ed6前进至f5cd1a7，
旧本地fe9b080也随祖先推送；ls-remote核验精确一致后才编辑生产。
无force；forge-installer.jar.log未提交/修改，隔离候选和产物不进检查点。

Runtime净74增/1删，归一化文本与冻结S9L完全一致，
其余61生产输入原字节保持，历史性能所用其余源码组合也对齐。
没有改项目build.gradle、既有测试或fixture，没有为了集成再调候选。
实际Gradle check/build/jarJar约33秒通过，18任务执行/2up-to-date；
Core9+3反向控制、Worker7组（含门28）、Shared99951/41路线/643证明通过。
对实际Gradle main输出复用原macro-commit4、boundary-recovery9、73829快照断言，
普通4endpoint/4snapshot及分层9endpoint/5snapshot完整JSON与候选一致。
审查要求补充48条不同实例但相等SectionPos在small/map命中控制，也在实际main通过；
此为关闭具体身份前提的必要正确性补证，无新性能场景。
五次原工具所加载生产类123/129/130/103/23个均来自build/classes/java/main，
无旧生产overlay；62个Runtime相关class/880方法的结构、指令、分支/异常表
与冻结候选一致，忽略debug元数据。实际259生产class、资源和源码另行冻结。

stale必须准确表述：正常/取消/普通失败清映射且索引null；
worker标记stale返回FAILED后同样终态清理，但failSearch遇stale异常
直接restartStaleSearch后complete，不显式清旧completedComponents或新增索引。
complete移除旧flight、清waiters并断waiter.flight，重试新建query不复用索引；
执行器出队、currentTask移除和事件批次deferredSearchResumes清空已核源码。
static helper无新增全局根、不持图/外部回调；旧query内部引用环可整体回收。
两份决策/结果审查接受此源码可达性论证，不要求改候选；不保证返回瞬间GC。
没有专项动态触发stale异常，原boundary日志stale=0不能冒充触发证据。

有效历史性能继续复用，本轮新性能运行0，未重跑B/C/S9/S9L。
每个参照60项窗内统计59降1升：distinct清理后至complete段max
C205.8/S9 84.8/S9L307.1us，相对C+49.22%、相对S9+262.15%，原因未定位；
不能丢到窗外或称纯future交付。完整W五统计和阶段均值下降仍只为非配对观察，
不全归索引。unique分配仍较C多约104B，>8唯一组件性能未测。
B无扩窗W，不冒用旧时钟作当前同口径基线。用户窗外/间接影响范围保持。

实际完整混淆包SHA256
`0dd8c5f6369e24866508eb5eb7af68947bf1b3c242cc1f2b9318a7276458c4e6`，
产物仅在本轮artifacts，默认build/libs两JAR保持。
6104B实际接入结果包173.132秒返回ACCEPT_SCOPED、本轮必须修改项无。
三次退出0、无超时、配置前后保持；原prompt/transmitted/answer/exit保留，
final-seal核对旧原件/接入前备份与新源码/编译类/工具/功能/审查证据。

本轮按授权完成接入条件审查、GitHub检查点、精确生产源码接入及既定构建/功能验收。
新接入和本轮状态文档留工作区，未经另授权不再次commit/push；
没有默认JAR替换、实服部署、新游戏或性能矩阵，不授稳定性能或默认发行资格。

最终封存首检发现旧Gradle隔离main目录257个class缺失（本轮实际生产编译后被清理）。
从既有scheduler build-v2/artifacts/hierarchy.jar按原freeze逐条先验SHA256后恢复，
257文件全部恢复原始字节，仅补缺失文件、不重编译/重测、不改旧归档或冻结原件；
historical-classes-recovery.json记完整来源和哈希，随后重新执行全封存。
本轮实际class另存frozen-production，避免后续Gradle清理破坏其不可变证据。

## 四十五、S9L 接入后先审单轮游戏并复用历史 B

2026-10-20用户要求“先送审deepseek，然后跑一轮测试，和基线数据进行对比”。
见[游戏比较报告](../../reports/2026-10-20-component-snapshot-production-game-baseline-zh.md)，
证据根`.tmp/component-snapshot-production-game-20261020-v1/`。
本轮不改生产或重新构建，直接用上节实际Gradle完整混淆JAR，
SHA256为`0dd8c5f6369e24866508eb5eb7af68947bf1b3c242cc1f2b9318a7276458c4e6`。

先发6898B自包含方案，实际DeepSeek 127.531秒返回ACCEPT_WITH_GATES、必改无；
先核前轮474证据/62生产输入、原B实际加载身份、Java/工具/23模板文件。
新薄汇总器启动前复算8条历史B/C+S首Path/到达/终态/tick/成本，均与旧值一致。
原公共analyzer字节不改，717输入及实际执行脚本冻结，失败判定预先记录。
历史OS/背景负载不能证明完全相同，不作同时配对；该限制不以源码身份替代。

主参照仅复用最近有效scheduler-idle-baseline-20261019-v1的B-closure/B-concurrent，
B原生产JAR为`6ecaf406a0ede293e45dcb1f8f5359daa28a3c951de39a9ce8fb0ece3dc17946`。
同历史轮C+S只为次要背景，不重跑、不混合别轮或挑最好B。
沿原runner/probe-agent-helper精确字节、2GiB/G1、速度II、30秒就绪、
90秒请求超时、1500秒进程上限、模板副本和原路线/实体顺序，NoTrace无JFR。

只新增两个串行S9L JVM：冷→暖304各1实体，8格→28格各64并发；
304是路线规模，并发只两档，不测8..28全范围。
尝试130/有效130/缺失0/失败0/超时0/替换0/第二轮0，全部实际到达且SUCCEEDED。
两进程均退出0、无外部Java，原公共分析和身份/路线/参数匹配通过，
终态owner与shutdown清理通过。没有技术失败、补样或新增场景。

| 对历史B | 首Path中位 B→S9L ms | 变化 | P95变化 | 窗内CPU总量变化 | 分配总量变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 冷304 | 196.1322→204.0350 | +4.03% | +4.03% | -8.01% | -1.12% |
| 暖304 | 10.7515→10.4544 | -2.76% | -2.76% | -7.35% | +0.44% |
| 8格64并发 | 161.5263→195.9129 | +21.29% | +5.37% | -19.02% | -1.83% |
| 28格64并发 | 51.8088→52.2138 | +0.78% | -24.82% | -5.71% | -6.58% |

8格首Path P95 200.3666→211.1288ms，28格101.3978→76.2355ms；
8格实际到达P95+47.6334ms、max+45.7539ms，请求终态P95+44.0827ms；
28格实际到达max+601.5149ms/+5.87%，请求终态max+500.5263ms/+4.81%。
完整tick及间隔不利原值也保留，不能用CPU或P95冲销。
CPU/分配/GC为各场景窗内进程增量costEnd-costStart，源计数器为进程累计。
冷S9L CPU原计数81890.625→86015.625ms，暖87093.750→89062.500ms，
各窗增量4125/1968.75ms，不是进程累计计数倒退；窗速率另列。
成本至终态后至少10tick，移除/关服在后，post-shutdown CPU/分配未知；
完整tick envelope不含idle补发，同时保留相邻tick启动间隔和>50ms分母。

对历史C+S仅作非配对背景：8格首Path中位/P95+27.03%/+25.74%，
CPU/分配+1.22%/+0.58%；28格中位-2.98%、P95+2.38%，不作索引单独归因。
304各n=1，64并发不是64次独立复测，不据单轮认定稳定或确定代码回退根因。
本轮游戏不新增W探针、不以全程值做索引因果分账；窗外按既定范围视为波动，
间接原因不调查。旧distinct窗内后段max相对C+49.22%、相对S9+262.15%，
unique仍多104B及>8唯一组件性能/stale异常动态覆盖未取得等限制保持。

结果初审6752B/174.606秒REQUEST_CHANGES，要求澄清窗内增量、补全身份/分母
及历史C+S/两档范围；全部采纳，仅改表述，无新测试或计量变化。
8331B自包含修正包173.285秒返回ACCEPT_SCOPED、本轮必须修改项无，无需重跑。
三次退出0、未超时、settings前后保持；final-seal核717输入、历史原件和新证据。
原意见和所有prompt/transmitted/answer/exit保留；旧封存原件不覆盖。
本轮既定测试与历史比较完成，正负数据并存；不授稳定性能、默认发行或部署资格。
生产源码、默认JAR和接入前f5cd1a7检查点保持；新接入仍留工作区，
本轮无新构建、算法修改、复跑、扩测、commit、push或部署。

## 四十六、Boundary 行位图选择器方案与官方审查

2026-10-20用户要求“根据上面的谈话，列出一份修改方案送审”。
当前[修改方案](../../designs/2026-10-20-boundary-row-selector-plan-zh.md)，
[审查记录](../../reports/2026-10-20-boundary-row-selector-plan-review-zh.md)，
材料`.tmp/boundary-bit-selection-plan-20261020-v1/`。
本轮没有授权实施或测试，生产仍保留S9L，不恢复已停止的S1阶段合并。

用户要求选举本身低成本，不能以预选答案读取冒充O(1)；
点须合法且质量足够，“任取最低置位”已否决。
初稿FULL_LAZY还尝试省窗口列表和恢复邻域，首个8025B官方请求
在32768 reasoning tokens后length且无最终正文，仅为技术失败，原件保留。
恢复65536输出上限、收敛研究提示后，7819B有效审查225.975秒REQUEST_CHANGES：
要求整数BlockPos/原运算与旧等待证据、完整目标盒门、比较器一致性，
并撤下复杂惰性兼容和main展开风险。

只读核对Service、NavigationRequestPath及本地mapped_official source jar，
确认a/t整数、distSqr左结合double、.05字面量和等待闭包保存原targets。
65536完整盒门内误差差额<2^-18，同行同侧非零步长≥1/20；
原double+XYZ比较两头，行top8及全局八项比较器保持一致。
源jar hash和源码摘录另留材料，不冒充已执行的功能测试。

修订V3为selector-only：保留原worker正常候选、恢复邻域、列表/日志/关闭和队列，
从原合法去重列表额外O(N)构建64long目标位图和4long非空行摘要，
约544B原始位存储另计头/对象/元数据；没有宣称省原候选及恢复分配。
旧调用点进行行内两头位选，跨R行选原评分下最多八个不同点。
单行O(1)、完整O(8R)、R≤256，整体严格O(1)仍未取得；
稀疏R可能等于N，2048输出尝试不等于2048CPU指令。
普通Boundary/attempt0/空失败集合及完整目标盒数值门才用新选择器，
否则直接复用已准备旧列表，无新增worker等待或导航拒绝。
调用点opt-in布尔透传落实索引范围，旧prepare/capture接口保留默认false；
具体代码仍须后续审查，资格变化或取消造成的无用索引成本不得漏记。

8133B自包含复审281.457秒正常返回ACCEPT_SCOPED、本轮必须修改项无；
只认可该方案及后续有限评估，不批准实施/性能/生产或200ms达标。
对方要求公平比较无索引heap8，承认小N/稀疏可能不利；
此建议不自动新增三方游戏矩阵，不以S1 bundle失败否定独立heap8。
官方每次先核models，deepseek-flash显示DeepSeek-V4.1-Flash、支持max，
没有单列1017不可变指纹；thinking enabled/max，不改共享provider配置。

68个生产输入/默认制品/构建配置冻结核对；全部prompt/transmitted/response/answer/exit、
首次失败和拒绝保留，final-seal记录文档及问答身份。
本轮只新增方案/审查文档并更新索引，未改生产或默认JAR，
无新构建、Java/游戏/性能测试、有效历史基线重跑、commit、push或部署。
后续若获准，先原full-sort必要差分/代码审核，继续历史优先和接口对齐，
不依据位运算名称或设计通过宣称实际净收益。

## 四十七、恢复累计要求的宏观准备与一次选点方案

2026-10-21用户指出上节V3没有包含“宏观寻路时一并准备、选点并消除重复排队”，
随后要求重新修改方案、检查整段连续会话遗漏，不能仅局部补丁。
另明确指出若worker选好八点后主线程仍重选，前一轮选点就成为重复工作。
当前入口为[完整V5方案](../../designs/2026-10-21-macro-prepared-segment-plan-zh.md)。
本节覆盖上节的当前工作范围，不改写旧方案和审查原件。

承认并纠正两项范围错误：selector-only漏掉排队合并；
用户允许质量足够、可以不是最优，却自行升级成实时最优八点。
V5由宏观worker在最终corridor完成阶段准备连接正常/恢复数据及本请求八点，
一次交付后正常首段仅为详细寻路准入一次；后续段使用已准备数据，
交接/推进/安装/一次搜索在同一获准任务内，不再提交正常准备worker、
纯nextSegment/final转调或正常主线程二次选点。

实际详细Path仍在主线程、从执行时实体真实位置搜索；
worker只根据冻结起点和路线参考点选终点，不预测并提前计算Path。
第一连接参考请求起点，其后参考上一连接已选全部最多八点、采用各参考评分最小值；
缺前一候选时只以原源组件anchor作评分参考，终点仍须来自本连接合法集合。
后续位置不同不重选；这改变旧实时评分输入，不能冒称原top8完全等价。
正常最多八点供一次搜索；实际失败保持原额度/缩段/刷新和attempt规则：
attempt>0从recovery按原实时评分取1，缩段清零后normal删失败目标取最多8。
正常attempt0且failedTargets为空只消费worker八点，不重选。

方案含累计要求对照表，明确普通/分层、共享飞行、缓存重放、请求八点隔离、
原列表/关闭契约、实际section上限、改块前后语义、取消与数据所有权。
原常规预算、43ms实际截止/haveTime、idle零负余额可补发、不恢复0.5ms提前量保持；
不引入S1主线程枚举、每八项完整检查、准备暂停状态机或抢队。

完整路线准备会提前支付CPU/分配/恢复生成，可能拖慢首Path及增加未消费数据，
必须全部计入直接影响窗、活动持有和原缓存限额，不能称为免费。
首版保留完整列表并共享物理查询数据，不用FULL_LAZY把物化搬回主线程。
严格整连接O(1)仍未解决：索引构建O(N)、单行O(1)、完整八点O(8KR)，K≤8、R≤256，
预选结果读取不是选举。路线参考点质量、净成本和200ms目标同样未闭合。

历史S9L/B/计划前基线/原生N0按身份和口径分别复用，
仅补失效或缺失部分，不为了配对重跑，不改公共计时或自行扩矩阵。
保留8/28各64并发混合地形、304冷暖单实体、原生失败分母、两种起点、
CPU/分配/首Path/详细搜索完成/到达/终态及全部不利值；
不能把局部机制通过、同参考点oracle一致或中位改善当作整体200ms完成。

用户随后授权“送审然后开始核验”。官方1017映射deepseek-flash/max方案复审
ACCEPT_WITH_GATES、无必改项；selector/data/navigation/runtime四包原provider代码复审
同为有条件通过、无必改项，不授性能或生产资格。
隔离最新v5已通过check/jarJar、53,112项prepared检查（14,000 oracle）及332项
真实runtime控制；BASE/分层、共享与请求独立八点、缓存重放、零边、stale有限重试、
阻塞取消后替换和停服控制已覆盖，不冒称覆盖main TTL/canUpdate/预算。
旧v3额外改变恢复行为已撤回，撤回前v3a游戏观测不用于最终v5资格。
原游戏取消/替换三场景、main八场景及286预算检查通过；
原trace中24次派发/5次实际搜索、每次派发最多1次，不外推为全部恢复/物理分支证明。
旧final-window预算helper的500us/idle余额假设已失效，仅独立控制包
复用20261019已验证的两class，性能工具原字节保持。
最终v5两个同接口轻量JVM完成，130/130到达；直接复用有效历史S9L/B，不重跑参照。
对S9L：冷首Path+139.9631ms，28格CPU+40.91%/分配+14.47%；
8格首Path中位−11.69%，但逐tile/slot台阶路线到达最大增量+1153.5158ms。
所有正反数据和逐路线差保留，不把各版分位差当逐路线差，也不以搜索次数相同证明路径相同。
结果7609B/66.509秒ACCEPT_SCOPED、无必改；不采用审查者把B/S9L差额套作原生200ms判定。
直接窗、详细搜索完成时刻、活动持有和200ms目标仍未闭合，当前不支持接入。
本轮有限核验结清，候选保留隔离；如继续先定位前置准备及异常路线，不自动扩矩阵或复跑基线。
68项生产/默认JAR冻结保持，65项候选与v5实际制品一致，最终证据封存`seal-v5.json`。
最新结果见[本轮隔离核验报告](../../reports/2026-10-21-macro-prepared-segment-isolated-verification-zh.md)。
未接入生产、替换默认JAR、commit/push/部署；旧V3的ACCEPT_SCOPED不移作新架构批准。

## 四十八、按实际段入口预选的候选S

2026-10-21继续在最终v5上修正实际(cursor,end)对应选点，
worker为每cursor准备一/两连接两组，仍一次交付、主线程不正常重选。
只有PreparedGroundRoute和Service两项变化，保留原评分与单步第一项guide，
不是生产接入。完整[上一轮S报告](../../reports/2026-10-21-stage-target-quality-isolated-validation-zh.md)。
构建、原53196 prepared检查、409真实运行时控制及两原游戏JVM完成，
130/130到达，代码/数据原provider审查通过并封存248项。
对v5，8格原异常台阶到达−1151.0255ms，但28格两反向台阶
+1748.8214/+1550.1975ms；首段C也由2→16，不能归为相同输入的纯选点效果。
冷首Path对生产仍+133.3844ms，各场CPU不利及全部逐路线保留。
S继续隔离，不授净收益、200ms或生产资格；旧历史有效，不自动重跑参照。

## 四十九、连续会话汇总与质量/距离候选Q

用户授权结合最近全部讨论形成修改方案，在上一候选上修改验证。
[统一方案](../../designs/2026-10-21-portal-quality-distance-plan-zh.md)以表格逐项对照会话，
[完整结果](../../reports/2026-10-21-portal-quality-distance-isolated-validation-zh.md)记录全部正反数据。
基于S隔离副本，66源码/资源中6项变化；宏观worker同次准备/选点，
主线程只消费冻结目标和跨度。新增完整有向配对、平面八邻接前后缀代价、
best+4质量带、32格软界、空间分散最多八备选和预选一/两连接，
原range/失败恢复/取消/预算/idle规则保持，不搬回主线程、不新增正常排队。
位图继续使用，但完整选举不是O(1)，预测代价不等于原生实际走法。
新增Q有界，未知/不支持/超限整请求回退原S，取消不能转回退。

官方1017当前对应deepseek-flash/max第七次方案审查8206B/268.376秒
ACCEPT_WITH_GATES、必须修改项无；前六次拒绝及解析失败原件保留。
代码五包按原provider返回ENTITLEMENT_ERROR、无判词；数据8456B也同样失败，
未越权用官方接口审代码/数据，不声称最终外审共识。
方案通过后完成隔离本地检查：实际check/jarJar、原26/18/14000/53196通过，
新1686模型检查/203bank/432图传播、409真实worker控制/99边/176bank通过。
S/Q21组同输入回放完成；质量计算增加缓存选点成本和分配，
39连接合成输入缓存wall102.867→1648.452μs/次，不冒充真实304或稳定性能。

仅新增Q原两游戏JVM，130/130到达且SUCCEEDED，退出0/无超时/无并行Java；
原公共分析、身份/路线、终态和关服归零通过，直接复用S/v5/S9L/B共8历史JVM。
304冷暖均pair-coverage回退，距离场未执行；8格32/64、28格64/64启用Q。
对S，冷暖首Path−3.3807/−2.7938ms，但8格中位/P95+29.0291/+40.9355ms，
28格到达P95+202.7276ms，台阶slot3/7+1100.7772/+1751.0907ms。
四场分配均增；304CPU+53.75/+57.43%，8/28CPU−1.95/−9.30%，不把总量当算法因果。
部分28格路线第二段2→4缩为2→3、3→4，多一次搜索；
秒级不利两条路线反而3→2搜索，目标偏移及后续节点变化保留。
28格首段C变化4/64、8格0/64，旧无新指纹/路径坐标，不作完整同输入因果宣称。

Q未取得净收益、200ms或生产资格，保持隔离证据，不采纳接入。
下一步先研究固定走廊近似与原生section范围走法、台阶代价及缩段权衡，
不直接加配对上限、调阈值、主线程重选或反复入队，不自动复跑/扩测。
权限恢复后先复用冻结代码/数据包补审，不为送审重跑有效历史。
生产68项与S旧248项证据保持；v5旧655项在活协调文档更新前核对，
这三个文档原字节/hash另存coordination-before，旧seal/报告/游戏不改。
无生产接入、默认JAR替换、commit/push/部署。

## 五十、下一出口引导轻量选点L：有限验证完成，保留研究

2026-10-21用户要求寻找更轻算法，随后授权验证。
先完成[研究与方法复审](../../designs/2026-10-21-lightweight-target-selection-research-zh.md)，
再完成[L隔离验证](../../reports/2026-10-21-lightweight-target-isolated-validation-zh.md)。
L为本轮制品名，C2仅为研究方法名，不与历史C/C2版本混用。
从上一候选S建立，不继承Q的全走廊距离场/配对/后缀或32格缩段：
原接受分支记录F，下一候选宏出口最多三代表，两段欧氏评分，
完整C一遍固定八槽后按最佳分+1过滤。偶数主链bank2推进、奇数备用不反向覆盖。
宏观worker一次准备/选点交付、主线程消费冻结结果，不增加正常入队往返；
原native范围、两连接默认跨度、四section、恢复/stale/取消/缓存/预算/idle43保持。

隔离`.tmp/lightweight-target-validation-20261021-v1/`，
66份输入仅四项变化，包含一个既有DEBUG行追加mode。
实际JAR `41ef43255786e7ff05e985a2cd117a73df30da2bbc67b415f3f494faa21440a8`。
官方max方法复审ACCEPT_SCOPED、必改无；代码四包及数据一包原provider
均ENTITLEMENT_ERROR，无判词，未切换官方审代码/数据。
初次测试比较器编译失败只改测试，v2 check/jarJar、
26窗口/14000旧oracle/53178prepared、新26457项1008bank、
409真实worker控制通过。旧失败原件保持。

局部21组仅跑L，复用原S/Q同输入回放。
合成39连接缓存S/Q/L为102.867/1648.452/334.305微秒，
完整准备5590.038/5763.294/8605.263微秒；
比Q轻不等于超过S，全部正反样本及分配保留。
只新增L两个原场景JVM，S/Q/v5/S9L/B共10个有效历史直接复用，130/130到达。
304实际37条组件边、20次搜索；日志macro39不是组件边数。
8格32/64使用预选窗口，另32final；28格64/64使用，无整请求S回退。
对S，冷/暖首Path-126.1994/-4.6169ms；
8格中位/P95 -17.8043/-15.5980ms，28格+4.4421/-10.0421ms。
到达8格中位/P95 +0.6270/-51.1096ms，
28格-1.5076/-299.2749ms，但39/64逐路线更慢，最大+152.7045ms。

28格搜索142→148、partial4→10、搜索分配6866816→12185056B。
六条single-wall/doorway/corner首段0→2 partial后由原逻辑同段重搜，
不是32格主动缩段；执行结构及分配增量不能全称波动。
尚缺完整旧C/目标坐标，不能只凭现有数据区分评分、三代表、1格带各自责任。
对生产历史S9L，两条台阶仍+1501.196/+1550.6637ms，
这部分S已存在，不归L新增，也不称本阶段已解决。
L对S四场CPU下降，但冷/28分配增加，对S9L8/28 CPU +17.47/+50%；
整场未定位差额按未归因波动登记，不作模块CPU因果。
tick保护项混合，完整直接影响窗和纯详细搜索完成时刻仍缺。
原生N0历史旧壳限定比较：同到达路线增量中位8/28为+558.0937/+331.9622ms，
不满足200ms阶段目标，28原生14失败也不伪造对应增量。

结论：本轮有限验证完成，L保留研究，不接入生产、不推进最终验收。
下一窄方向先看六条新增partial的目标组和质量带是否过窄，
先复用已存数据，不自动改阈值/追加第二轮/扩矩阵或重跑基线。
生产68项、S248及研究32保持；Q420项在活文档更新前核对，
三个活文档原字节另存coordination-before，旧seal/报告/游戏不改。
代码与数据外审未完成，不把方案通过代作代码、性能或生产批准。

## 五十一、L六条新增partial及搜索分配离线定位

2026-10-21用户要求继续定位并同时解释搜索分配升高，分配不是主要性能项。
[本轮报告](../../reports/2026-10-21-lightweight-target-partial-allocation-diagnosis-zh.md)
只复用S/L原28格64并发数据和源码；无新游戏JVM、候选修改、接入或提交推送。
执行前核对L725项、生产68项；三个活协调文档原字节/hash
另存`.tmp/lightweight-target-diagnosis-20261021-v1/coordination-before/`，
旧seal/候选/报告/游戏原件保持，后续检查须使用相应快照映射。

六条原C计数16、section3，阶段均0→2 partial后原恢复同段成功、再final。
实际仅两条组件连接，macro4不是组件边数；首double bank使用实际start/最终goal，
下一出口三代表及后续预测入口没有参与该首段。
1格硬带可缩目标覆盖，同时通过原localRange公式改变native实际range；
六条均下降约0.17–0.41格，不能凭此认定range是唯一/主要原因。
缺实际C/选中点坐标，C计数相同也不等于完整输入一致。
六个条件平面行模型的范围与日志吻合，但明确不是实际捕获点集或反事实验证。

S→L搜索分配6866816→12185056B，增加5318240B/+77.45%。
新增partial六条净增3650112B/68.63%，原partial四条613920B，
两版无partial54条1054208B；后两组调用次数保持，不全归新增六次搜索。
原生PathFinder线程分配边界排除worker位图/选点对象及外层proof/恢复排序；
搜索内部探路及重复准备是相容机制，尚无扩展节点/对象类型分账。
场景分配净增5320344B，搜索数值覆盖99.96%，其它模块仍可能正负抵消。
总搜索wall47.5738→45.1215ms，六条组+11.1452ms被其它组抵消；L场景GC0。
分配保持次要；六条两条到达更快、四条更慢，不能只看调用数或内存判净收益。

下一窄建议保留L评分/完整C一遍前八，只去掉末尾best+1硬过滤；
原范围公式、调度、两连接、四section、恢复/取消/所有权/预算/idle43保持。
尚未实施、审查或验证该修订，不承诺会改善全部路线。
原provider代码/数据权限失败仍未恢复，无新外审判词，不换官方代审。
后续先复用有效历史，只补必要缺失信息；不为次要分配另起对象池/缓存工程，
首Path、到达和额外重搜优先，200ms阶段目标仍未完成。

## 五十二、L8撤硬过滤：六次额外partial消失，收益仍混合

2026-10-21用户明确撤掉硬过滤，本轮小改动不需送审。
已在L隔离副本完成[L8修改与原场景单轮验证](../../reports/2026-10-21-lightweight-target-no-band-validation-zh.md)，
目录`.tmp/lightweight-target-no-band-20261021-v1/`，作为最新隔离候选。
66份源码/资源仅LocalTargetSelector一项变化：
删除末尾best+1及nonfinite-band检查，全部交付已经选出的前八；
不足八点全部交付，评分、平局、位图/三代表和单遍八槽保持。
正常worker同次交付/主线程不重选，原两连接、四section、range公式、
恢复/取消/stale/所有权/预算/idle43保持。生产68项和默认JAR未改。

实际Gradle JAR `bd5d3fa5dbc94fffb5156e6574354dbda754cb2d8f60bacb559543da30fbd90e`。
check/jarJar、原26窗口/14000排序参照/53178prepared、
26457局部检查1008bank及409真实worker控制通过。
局部独立排序oracle仅同步撤带并要求min(8,C数量)，没有扩场景矩阵。
仅新增L8原两游戏JVM，复用L/S/S9L/B共8历史JVM，130/130到达且SUCCEEDED。
两进程退出0/无超时/无并行Java，公共分析/关服资源缓存引用归零通过。
离线审计首版误要求旧启动记录列出构建helper，按原三运行工具口径修正，
原异常保留、游戏未重跑。实际预选窗口冷/暖1/1，8格32/64，28格64/64。

28格六条新增partial全部消失，范围恢复S日志数值；
总调用148→142、partial10→4、reachable仍138，与S结构数相同。
搜索分配12185056→6806000B/-44.14%，比S少60816B，
但搜索wall45.1215→57.5217ms，整场CPU1546.875→2156.250ms/+39.39%。
六条搜索wall少9.8314ms，其余58条多22.2316ms，分配不替代延迟评价。
门口/转角四条对L到达快48.4958/99.0297/99.0756/148.6862ms；
墙体slot2/6反慢545.2413/251.4725ms，相对S仅-3.7988/+1.9998ms。
另外台阶slot1/5也慢441.2516/299.3975ms，不只两条墙体不利。

8格首Path中位/P95对L-8.0228/-21.1131ms，28格+4.2673/+38.1403ms；
到达中位/P95对L：8格-1.5617/-1.6754ms，28格+43.0749/+441.2516ms。
28格33/64到达更慢，最大逐路线+545.2413ms，全部正反数据保留。
对生产S9L，28格首Path中位/P95+14.5402/+39.9427ms，
原台阶slot0/4到达仍+1542.4660/+1595.2159ms，阶段缺口保持。
四场GC0；整场未定位差额按既定口径暂记波动，不抹去实际路径/搜索变化。

本轮改动与核验完成，L8保持隔离，不宣称整体净收益、稳定性能或200ms达标。
用户免审本轮，外审调用0，不伪造判词也不补判旧L审查通过。
未接入生产、替换默认包、提交推送、追加轮次或扩矩阵。
旧L725项、定位11项和生产68项核对，三个活协调文档原字节/hash另存L8目录，
旧报告/游戏/源码/封存保持，各自历史按快照映射检查，最终seal记录全部证据。

## 五十三、L8原样第二轮：有波动，也有重复到达回退

2026-10-21用户要求再跑一轮判断是否波动，已完成
[原样复跑报告](../../reports/2026-10-21-lightweight-target-no-band-unchanged-rerun-zh.md)。
目录`.tmp/lightweight-target-no-band-rerun-20261021-v1/`，
L8r只标记复跑，不是新代码；直接复用L8原game-build-v1及相同JAR，
`bd5d3fa5dbc94fffb5156e6574354dbda754cb2d8f60bacb559543da30fbd90e`。
无修改、构建、外审或基线复跑；只新增304冷暖、8/28并发原两个JVM，
历史L8首轮/L/S/S9L/B共10JVM直接复用，原工具/参数/计量/30秒门保持。
130/130到达且SUCCEEDED，两进程退出0/无超时/无其它Java；
公共分析、关服资源/缓存/引用归零核对通过。

相对硬过滤版L，28格首Path中位+4.2673→-13.6686ms，
P95+38.1403→-1.9174ms，到达中位+43.0749→-12.6204ms；
整场CPU+39.39→+4.04%，搜索wall+12.4002→+0.3717ms。
8格首Path/到达改善也转为不利；复跑8格1次/13ms GC，未据此解释全部时差。
冷/暖304整场CPU增幅仍+67.25/+91.26%，两轮同向但仍未定位单项代码因果。
因此计时波动存在，不能把首轮幅度认定稳定；也不能把所有不利消去。

28格到达P95对L +441.2516→+442.8224ms，
请求终态P95 +445.5964→+448.3355ms。
墙体2/2、2/6到达 +545.2413/+251.4725ms
复现为+544.3892/+250.2824ms，相对S仅-4.6509/+0.8097ms。
台阶4/1、4/5分别+441.2516/+299.3975→+442.8224/+287.1966ms；
斜线1/1、1/3约+247→+239ms。
两轮相对L超200ms的28格路线恰同这六条，保留为可复现不利，
不声称已有统计稳定性或确切目标坐标/完整路径因果。
复跑28格15/64到达比L慢（首轮33/64），少数尾部没有随整体计时改善反向。

原六条额外partial在两轮均消失，总搜索142、partial4、reachable138；
六条调用/可达性/range/返回节点数签名一致，不能当完整路径身份。
门口和转角四条到达收益也重复；28格搜索分配首轮6806000、复跑7011888B，
均比L12185056B低，但复跑比S多145072B，不再称分配稳定优于S。
复跑对S28格首Path中位/P95 -9.2265/-11.9595ms，
到达P95+143.5475ms，最大逐路线增量也143.5475ms。
对生产S9L台阶slot0/4仍+1498.4173/+1536.1513ms，旧阶段门不变。

本轮授权的一次复跑完成，两个L8轮次分别保留，不池化实体或取有利轮次，
不追加第三轮/扩矩阵，不认定全面净收益、原生200ms或生产资格。
若继续定位，优先解释重复到达差，而非继续不变复跑；本轮未改算法。
原602项在更新活文档前核对，68生产/66候选及原build保持，
三个活文档原字节/hash另存新coordination-before，原报告/数据/源码/seal不改。
最终seal记录599原路径+3快照映射；无接入、提交推送或部署。

## 五十四、L8重复到达延迟：实际交接点偏移与台阶宏观分叉

2026-10-21按用户“好，你进行定位”完成
[到达延迟定位报告](../../reports/2026-10-21-lightweight-target-arrival-delay-diagnosis-zh.md)。
新目录`.tmp/lightweight-target-arrival-diagnosis-20261021-v1/`，
先核对前轮80项、生产68项及66候选输入两份副本，
三个活协调文档原字节另存coordination-before。
复用L/L8/L8r/S/S9L五个历史burst JVM，不重跑完整基线矩阵。
因旧DEBUG确缺实际目标坐标，只补L、L8各一个原8→28格64并发诊断，
原build/JAR/runner/工具及30秒门保持，已有日志调TRACE、deepTrace仍关，
无重建或算法修改，256/256到达、两进程退出及资源清理通过。
诊断计时单独保留，不替换原两轮、不混算新分位数。

历史复跑对L：斜线1/1、1/3最终搜索反早约64ms，到达却晚239ms；
台阶4/1最终搜索只差约1.32ms、到达晚442.82ms，
4/5最终搜索早113.62ms、到达晚287.20ms。
该时钟叠加只是毫秒日志近似，不作纳秒分账。
六条搜索wall差仅+0.0575/+0.1251/-2.2588/-2.4196/+0.1405/+0.3057ms，
不能解释百毫秒到达差，主要额外时间位于后续行走/原生跟随。

坐标诊断复现六条原阶段签名，但也证明签名不能代替路线几何：
斜线两版宏观端点/完整C/实际起点一致，L交付列表是L8前缀，
实际终点分别从z=-142/-133变为-141/-132，末段3→4节点。
L8实际采用新增第4/6名；两段评分高于最佳1.063284格，
离线捕获输入回放精确复现原目标列表和硬带排除关系。
墙体同宏观/C/起点，1/3点→8点后两条都用第八名(-64,81,-128)，
L原partial→恢复到x=-60/-61附近，而L8成功跨界后需走回最终目标，末段1→4节点。
因此少六次恢复搜索与更晚经过目标范围可以同时发生。
墙体2/2 L首次到达6901.3834/终态7552.4069ms，
L8r7445.7726/7599.2386ms，首次到达+544.3892但终态仅+46.8317ms；
原探针锁存第一次进入目标一格范围，恢复行走可能先经过、后继续收尾，不改该旧口径。

台阶捕获的L先绕z=-145侧，L8绕z=-128侧，宏观结构代价均4，
第一组五个候选本已不同，不能写成同组删过滤导致换边。
源码选点发生在宏观走廊完成之后；同请求没有回写宏观路线的链路。
宏观相同g保留先到前驱、依赖唤醒集合及正常openSet无几何总序，
允许等结构代价路线受次序影响；未捕获具体依赖序列，不指定哪次事件触发。
按直接影响范围不把这项上游分叉全部归撤过滤。
第二组C坐标相同但规划参考/实际起点不同，L8用第八名(32,81,-131)，
L用(32,81,-141)，末段4→6节点。
同L8捕获输入套原硬带会排除该点，但没有跑反事实原生搜索，
不能量化绕行侧与目标扩大的各自责任比例。

核心机制是原生“到任一目标”不接收两段评分/后续代价，
评分第一不保证被采用，交付组内靠后点也须有足够后续质量。
下一项优先控制整组交接点的后续行走质量，同时保留可达备选；
不直接恢复已致六partial的best+1，不恢复主线程重选/重复worker往返，
目标组与实际range耦合仍须保留，不把位图/排序成本当本次到达主因。
台阶宏观等代价选择独立记录；本轮没有实施后继算法。
主证据coordinate-analysis-v2.json纠正首版跨参考评分口径，首版原件保留不作结论。

外审0，无新DeepSeek判词、生产接入、默认包替换或提交推送。
冷暖304 CPU、旧台阶slot0/4秒级缺口、原生200ms和稳定性能门保持。
最终seal按77原路径+3协调快照核对前轮80项，生产68及候选两份输入保持；
本轮完成有界定位，不宣称六条精确责任比例或完整物理路径身份。

## 五十五、L8E目标/范围分离：六partial仍重现，停止候选

2026-10-21用户授权继续优化，完成
[L8E隔离筛测](../../reports/2026-10-21-target-quality-range-envelope-validation-zh.md)及
[方案](../../designs/2026-10-21-target-quality-range-envelope-plan-zh.md)。
从L8建立三份源码改动：明确恢复best+1实际质量目标，
targets接口仍为完整L8前八，normalGoals为子集，仅local正常首试分开输入native/range。
宏观worker同次交付、主线程不重选、暂停续行保持两集合，
原S/shared/final/失败恢复、两连接/四section/预算/idle43规则不扩改。
PreparedGroundRoute每请求生成，不新增缓存或旧bank迁移。

方法首审REQUEST_CHANGES后采纳保留旧接口/同输入核对建议，
复审5560B/164.968秒文本为“裁决：ACCEPT_WITH_GATES”，只允许一次隔离筛测。
客户端前缀解析失败，保留原conclusion=null回执，另存文本判读不伪造成功。
实际编译参数流+原runtime契约检查替代其live fake native建议，明确不是逐字同测试形式；
正常暂停续行保留两集合，真正失败恢复仍原3arg。
原provider三代码包5909/7518/8284B及数据4803B全ENTITLEMENT_ERROR，无判词或生产批准。

最终v2 check/jarJar、26窗口/14000oracle/53178prepared、
34655局部1008bank、409真实worker及9参数流检查通过。
初次参数检查误认其它lambda、首游戏探针按名字匹配重载失败均保留；
首JVM完成0场景不计性能。仅改名3arg转发为findOrdinarySegment，
实际4arg保留findSegment名称，不改原探针/计量/算法，重新构建检查。
最终JAR为72b1eda72c1a169056a6cb7be109a8bc82c3b430ec6ce5b13d667e59d63e1020，
源码身份candidate-inputs-v2/review-source-v2/sources-final-v2。

只跑一个有效原8→28格各64并发JVM，六历史burst JVM直接复用，128/128到达且SUCCEEDED。
退出0、公共analyzer/资源缓存引用归零/无并行Java通过，GC0，窗内prewarm完成增量0。
墙体2/2、2/6、门6/0、6/4、角7/2、7/6的原六额外partial全部重现，
首试range逐条等于L8r日志值，搜索142→148、partial4→10、reachable138保持。
不能只靠保留完整range挽救旧质量带，也不声称已证明唯一native失败原因。

28格对L8r首Path中位/P95 +37.9472/+36.7333ms，
到达中位/P95/max +61.4440/-93.3472/+664.1832ms，53/64更晚、四条>200ms。
原六到达不利均改善51–495ms，但两条台阶4/2、4/6反慢2704.0864/2507.9626ms，
首段6→13节点且range变化；缺完整坐标，不全额归本次目标带或抹作纯计时噪声。
28格搜索分配7011888→18709672B/+166.83%，六异常路线增5716184B，其余58增5981600B。
整场CPU1390.625ms，对L8r-13.59%、对S9L+34.85%，不作选点器因果收益。
8格对两轮L8计时方向不同，全部正反数据和生产S9L/调度B比较保留。
这些mod参照不是原生N0，不替代用户原生200ms目标判定。

按预定机制门停止L8E，不补304、不扫阈值、不刷轮次；L8继续为隔离研究起点。
后继应改善合法备选组的可行走质量，不能恢复旧硬带加宽range作为已验证净优化。
本轮未实施另一新算法，不接入生产、不替换默认JAR、不提交推送，不关闭200ms等旧门。
前轮83项以80原路径+3协调文档原字节快照映射核对，生产68及旧L8两份66输入保持。
最终seal保存源码、构建、原失败、审查、有效游戏、全比较和本轮文档。

## 五十六、目标组质量后继研究：条件式首段代表点A2

2026-10-21用户要求继续研究方案，不急着验证；已完成
[研究方案](../../designs/2026-10-21-target-set-quality-next-research-zh.md)，
证据目录`.tmp/target-set-quality-research-20261021-v1/`。
L8继续为研究起点，L8E停止，不因后继研究改写旧筛测或复活普遍best+1硬带。
完整C连续不能证明原生approach相同，墙体连续16点中只取中心曾丢掉可达备选；
本轮不采用普遍C/F连通聚类、完整距离场/后缀传播或32格估值缩段。

A2仅是方法草案编号，未生成候选。限定local成功、E=2、cursor0/attempt0、
failedTargets空、特定GROUND profile及两个同高单位水平boundary变换。
q为同一原首double bank的第一项且属于原full8/C1，r为冻结起点，g为最终目标；
三者同高，包围矩形双轴包含端点宽度2–64，单行/单列保持L8。
矩形section全部来自精确V0/V1/V2，每行完整成员覆盖，不借其它组件补洞。
两接口按整个平面先算完整跨界格对，0或>16直接不适用；
全部未拒绝格对核对C/F/唯一变换，另核对共享面完整16位置的F/C双射。
这只是成员和已接受接口的精确谓词，不是原生可达、惩罚或最优证明。
斜线仅是二维几何动机，未回放位图资格；墙体退化矩形为明确排除对象。

worker同次冻结可选单点和完整原八点，不对全部bank增加成对元数据。
真正native派发及await后只增加O(1)方块位置相等检查：
位置仍为r才消费单点，否则在请求有效时消费预存完整八点；不重选、不排序或再派worker。
实际采用集合须同步segmentTargets；取消/stale/owner/profile/version无效仍走原清理重规划，
不能拿同样失效资源回退L8。单点失败只走原恢复，没有隐藏的第二次八点搜索。
原full8继续作为实际位置range参考，冻结输入/公式而非worker提前算数值range。
其它bank、共享/final/S、两连接/四section、预算和idle43规则保持。

新增成本明确包含原L8完整C/top8、最多256矩形成员行切片、
32跨界格对、另32共享面映射位置、坐标/取消/元数据/可选Set及派发分支。
现有ComponentSnapshot没有primitive行接口，未来需包内只读接口，
不暴露数组/复制平面/建BFS或缓存。完整选举不是O(1)，本轮没有测量净收益。
未来若实施，保留旧探针识别的方法名，避免L8E重载兼容问题，不改工具掩盖不对齐。

按约定官方deepseek-flash/max只审方法并提出独立替代：
四包8436/8408/8467/8313B，判词拒绝/拒绝/拒绝/ACCEPT_SCOPED；
末轮169.203秒，“本轮必须修改项：无”。调用前models核对，
API别名不提供不可变1017日期指纹；无配置改动或凭据落盘。
第三轮“裁定：”原解析失败回执保留conclusion=null，正文明确拒绝，未重写原件；
末轮新客户端只修判词前缀。未采纳第三轮将墙体负例当正例而放宽退化门的建议。
末轮认可契约闭合，但只接纳有限研究假设，没有可达/最优/性能/生产证明。
其可选clamp96检查不另加：本契约同高矩形坐标距r至多63√2<90且q属于同一范围集合，
不能仅由96封顶产生所提几何超界；仍不声称evaluator起点或实际路径完全相同。
原provider代码/数据审核本轮0，不以官方方法通过替代。

一手论文/作者文章/项目文档成功下载7、失败2，原字节及HTTP/hash保留；
HPA猜测PDF404、Recast raw源码失败不冒称读过，Recast改读官方类文档。
未找到明显优于A2且满足现边界的轻量方法，不等于不存在其它方案。
本轮源码修改/构建/fixture/回放/微基准/游戏/新基线均0，
未统计历史资格频率，不自动进入实施验证，不宣称全局200ms或生产资格。
前轮1349项按1346原路径+3协调文档原字节快照映射核对，
生产68项及L8两份66输入保持；无生产接入、默认包替换、提交或推送。

## 五十七、当前生产与原生：三档正态背景占用的完整并发矩阵

2026-10-21按用户补跑要求及“每个负载都测64/128/256”澄清，完成
[完整压力对照报告](../../reports/2026-10-21-modpack-background-pressure-production-native-zh.md)。
目录`.tmp/modpack-pressure-comparison-20261021-v1/`，每档15/25/35ms各测三档并发，
每档并发均沿用原8/28格八类人工地形；总18组细分对照、36场、5376尝试。
六游戏JVM串行，15按N/P、25按P/N、35按N/P；
每格仅一次观察，“三轮”是三档负载，不是三次重复平均。
标准差未指定，执行前说明用5ms；固定种子20261021按场景派生，
负值置零、无上尾截断、主线程忙等。正式序列在就绪后重置，
暖场不影响正式采样，两端共同前缀逐项相同。

只复制并扩展隔离测试工具，当前生产62份src/main冻结后实际构建，
含原有未提交TopologyWorkerRuntime修改，不接入L8/L8E/A2。
实际JAR为6cb03d458854f996b4350c383d7f1839f2ccf6330114d91f9ce30c6d5ba0fd8a。
两端同包，N使用husk bypass，模组后台维护仍在，不是卸载模组的纯原版。
Java17、2GiB/G1、速度II、DEBUG/NoTrace、原模板及30秒真实就绪门保持，
最多90秒观察与原10tick尾窗保持。背景负载不是新复杂地形或真实整合包。
每场实体起终点组合唯一，但宏观合并/地图/走廊缓存保持，
每档896次宏观成功请求只有194次实际宏观搜索，不能当独立冷请求。
旧历史缺本次压力组合且用户明确要求，才新增两端各3JVM；其它有效历史不重跑。

生产三档各896/896实际到达，总2688/2688；
原生分别802/896、801/896、802/896，总2405/2688。
原生28格三档均50/64、99/128、207/256到达；
8格256并发有2/3/2未到达，64/128并发全部到达。
原生首次isDone终态可能早于尾窗首次到达，
故未到达按arrivalNanos计算，不把EXHAUSTED直接算物理失败；
两端终态语义不同，只分开保留，不作共同搜索完成时间。

18场整批首Path P95全部更晚，增加56.43–687.36ms；
共同到达路线的单请求到达时差中位/P95逐场保留，
P95全部>200ms，最大单路线35/28/256为+7105.89ms。
CPU总量18/18上升+6.27%至+106.58%，分配18/18上升+26.30%至+82.20%。
自然观察窗不同且含注入/行走/worker/JIT，不能当纯寻路CPU；
但固定首1秒15场P下界>N上界、2场相反、1重叠，
固定首5秒9个共同覆盖场景7升/1降/1重叠，不能把全部CPU差归观察更久。
完整tick最大值18/18下降，P95仍11/18上升；
相邻tick最大间隔4场反升，最大+10.34ms；所有不利保留。
功能到达和尖峰收益成立于本次观察，不判全面性能通过、200ms或稳定发行完成。

5239条正式注入均在完整tick内、连续、无截断/不完整tick，
两端路线/地形/工具/实际生产JAR/Java/世界及共同负载前缀全部对齐。
35ms档目标超过43ms有N37/P62次，实际次数相同，所有忙等均未超过50ms。
原性能探针在tick结束读取计数，负载在START读取，编号相差1；
首次后处理误用等号关联导致断言失败，原脚本/失败/部分分析保留，
核对全量时间戳后只修START+1映射并输出analyses-v2，未改原始工具或重跑游戏。
六进程退出0、30秒门、无其它Java干扰、服务/调度清理通过，无遗留Java。

原工具、默认JAR和68生产输入保持；本轮只新增测试工具副本、报告和协调记录，
无外审、不替代原provider代码/数据审核，无候选接入、提交推送或部署。
前轮64项按61原路径+3协调文档原字节快照核对，更早1349项按已有映射核对；
本轮seal保存完整证据。A2仅研究状态保持，不自动实施、扩矩阵或追加复跑。

## 五十八、有界突发与取消主线程延后调度：隔离验证完成

2026-10-21按用户要求并列验证B有界突发与I取消主线程延后调度，完成
[报告](../../reports/2026-10-21-scheduler-bounded-burst-immediate-validation-zh.md)及
[方案和审查修订](../../designs/2026-10-21-bounded-burst-immediate-dispatch-plan-zh.md)。
证据目录`.tmp/scheduler-burst-direct-20261021-v1/`，
最终比较`comparison-v2.json`、`summary-v2.json`、`tables-v2.md`；
初版比较、失败、审查和构建均保留，不覆盖旧原件。

B只改隔离调度器，常规20ms/43ms/haveTime及原公平顺序保持。
真正runnable队首连续等候50ms、距上次开窗至少10tick时，
可使用原tick起点后75ms机会窗口，同tick回调只复用原截止线，完整记账。
75ms只是停止启动新任务的线，已开始同步原子工作不可抢占；三档开窗4/5/21次。
I取消END/idle等待、额度、haveTime和墙钟准入，主线程提交/唤醒后立即执行，
保留依赖挂起、重入队列、请求身份、取消关闭、异常与计费；详细A*仍在主线程。

初版I三档每档多896次原生决策，已由业务代码与关服计数定位：
move内prepare时立即初始化，此时尚未adopt，原生决策先跑，随后再启动宏观分段。
不是不可解释波动，不能把错误接入结果作为正确立即执行的必然成本。
通用TimeAccount边界修正被审查拒绝，保留但未跑游戏。
最终I2仅另改隔离Service移动入口，先adopt再初始化，查询入口原语义保留；
显式主线程、adopt中嵌套查询、初始化先后及一次性门均加入并检查。
最终构建build-I2-v6，JAR
`4db5b4f89ad0f5239ad9f755ba0b54df4511d221dd7cb8f21143c0b51d2bd0ce`。
三档有效I2的nativeDecisionCalls=0、failures=0，无AN_I2_GATE错误，清理通过。
这评价的是立即执行与必要接入修正组合，不把全部差值因果归到调度器函数。

P/N六历史JVM身份、接口、路线、负载及计量核对有效，全部直接复用，不补跑基线。
沿用15/25/35ms标准差5ms、种子20261021、负值置零/无上尾截断的主线程忙等，
原30秒就绪、Java17/2GiB/G1、速度II、DEBUG/NoTrace、90秒观察/10tick尾窗，
每JVM顺序64×8、64×28、128×8、128×28、256×8、256×28保持。
每场起终点组合唯一，但原宏观合并、缓存共享和预热仍在，非独立冷请求。
每版本每格一次观察，不是多次平均，不声称统计稳定或真实整合包全覆盖。

实际新增十JVM/60场/8960尝试：B三组、问题I三组、有效I2三组，
另I2/25ms首组因外部IntelliJ/CityEditor构建违反独占条件，被原runner拒收。
原结果和拒收回执完整保留；确认外部构建结束且守护进程空闲后仅停止该PID，
用同构建/参数补25ms整组，35ms仍首次，B/15ms/P/N等不重跑。
有效新增九JVM/8064尝试，最终B/I2六JVM/5376尝试，各2688/2688实际到达。

对P的18格：首Path整批P95 B12降6升、I2 9降9升；
同路线到达时差中位B14降4升/I2 13降5升，P95 B5降13升/I2 9降9升；
CPU总量B9降9升/I2 3降15升，范围分别-18.09%至+23.30%/-11.37%至+213.45%。
35ms/256/28首Path P95 P562.90/B619.11/I2 910.61ms，
对N共同路线到达增量中位P508.37/B503.60/I2 1428.67ms，
CPU P11843.75/B11765.63/I2 18265.63ms。N该格仅207/256到达，保留全分母。
完整正反数据及逐实体时差均保留，不用两端各自中位相减代替同路线比较。

I2在15ms六场首Path P95均改善，但25/35ms CPU合计69687.50/76265.63ms，
P为35718.75/49046.88ms；同窗其它线程差为+23875.00/+21203.13ms。
固定首1秒14场CPU明确更高，不能全归观察更久。
事件批次230→525/259→529是定位线索，缺逐线程/任务CPU，不证明唯一原因。
物理宏观搜索均194，搜索/构建未膨胀，正式窗预热完成均0，不能归因多跑预热。
除初版已定位业务错误外，未锁定差值按本轮运行差异保留，不作必然退化判定或抹去不利。

两候选必要新增固定容量drain时间戳账本，每drain两次时钟读取，成本包含在结果内；
原工具保持，P/N没有账本，新增extent缺值明确保存，不因此重跑历史。
完整tick、相邻间隔、tick起点至最后所属调度结束的extent及包络外工作均保存。
B三档extent最大75.36/99.42/97.99ms、无>100ms；I2 82.97/224.84/235.44ms，
高两档>75ms最长连续8/9样本。恢复到五个连续<=50ms的定义和尾部均保留，
不将单tick倒数冒称长期TPS，也不将extent内间隙全部算作CPU busy。

官方flash/max方法共七轮，前四轮末轮ACCEPT_WITH_GATES/必改无；
I2第七轮有条件允许隔离游戏，原mustFixesClear=false保留，
开跑门由源码/字节码检查和实际游戏门闭合，不改写判词或称生产批准。
B60、I2 36调度检查及B原生产286项均为两遍合计，I2另19项编译后检查，
不冒称真实服务器异常注入全覆盖。原provider两代码包及7721B最终数据包
均ENTITLEMENT_ERROR，无代码/数据通过结论，未改用官方方法provider替代。

本轮结论：B有温和保留价值但当前参数不足直接替换生产；I2高压观察不通过。
200ms是全项目目标，尚未关闭，不作为本分支必须独立完成的唯一门。
本轮验证结清；下一步宜优先定位I2其它线程CPU及事件批处理，再决定是否重新约束执行，
不自动追加游戏、扫参数或开始新候选。生产68项、默认JAR及原工具保持，
原有TopologyWorkerRuntime脏改未动；无接入、提交推送或部署。
旧1020项按1017原路径和三活协调文档更新前原字节快照核对，
最终seal-v1.json封存本轮构建、源码、原始有效及无效游戏、审查与文档。

## 五十九、调度异常三项定位：同步提交闭合，旧CPU异常仍缺归属

2026-10-22按用户“确定三点都是什么导致的”完成
[定位报告](../../reports/2026-10-22-scheduler-three-cause-diagnosis-zh.md)，
目录`.tmp/scheduler-cause-diagnosis-20261022-v1/`。
先核对前轮1497项和68生产项，三个活文档更新前原字节另存。
不改候选/生产源码，不重建JAR，不重跑P/N/B。

第一项由代码闭合：测试逐实体调用moveTo，I2在submit返回前drainImmediate，
可执行当前及其它已就绪工作，阻挡下一实体提交；不是额外主动等待。
35/256/28原整批提交跨度P2.0079ms、I2 72.0049ms。
立即执行改变提交/执行交错，不消除计算本身；不擅自为测试改成先统一入队。

第二项发现前轮回答漏用已有search.cpuNanos字段，补算全部12历史JVM、18路线对照。
35/256/28搜索CPU P78.125→I2 484.375ms，wall82.9651→461.1991ms，
558调用保持，逐路线调用数和最大返回节点数256/256相同，分配仅约+1.72%。
这不等于完整几何/展开节点一致，但不能仅用线程等待解释CPU也增加。
Windows15.625ms量化只作场景聚合证据，不对单次小搜索精确分账；
旧35六场安全点约10.76ms，不能解释约1秒搜索增量。

第三项旧高压进程已结束，缺逐线程CPU/调用栈，无法从旧总计反推具体线程。
只补一个build-I2-v6同冻结JAR的35ms诊断JVM，原六场顺序、负载/种子、30秒门保持。
复制原runner，仅诊断允许JFR并标记diagnosticOnly，原runner禁JFR规则保持；
新增约1秒OS线程CPU记录与JFR执行/编译/时钟锚，不把诊断值当无采样性能复跑。
896/896到达、exit0、无其它Java、nativeDecisionCalls=0、failures=0、原清理通过。

本次35/256/28搜索wall86.3140/CPU93.75ms，提交跨度13.7765ms，
同JAR未重现旧五倍量级。正式六场其它线程CPU合计10156.25ms，旧为28296.875ms。
本次首次请求前进程CPU113078.125ms，旧35为427312.5ms；
不据此删除旧不利结果、平均混算或宣称代码稳定净收益。
本次可确认JIT编译线程CPU显著、拓扑worker较小：
请求前可观测编译CPU52703.125ms，两个worker296.875ms。
正式窗内部采样编译至少2015.625ms，含边界块3750ms；
同扩大边界worker484.375ms。OS漏边界/已退出线程和JFR扰动均明确保存，
不把未分配CPU补给JIT，不把编译事件wall当CPU，不把原生睡眠样本数换CPU秒数。
30秒墙钟门不保证JIT稳定，JIT与模组预热区分；旧事件批次增加不作为CPU因果证明。

第一项根因闭合；第二项实际CPU增加得到确认，但底层原因仍未唯一定位；
第三项仅取得本次正常运行分账，旧额外21.20秒无法回填归属。
JIT/整个JVM执行状态是优先怀疑方向，不能命名成已证实原因或纯波动。
本轮完成有限定位，不宣称三根因全解决；若继续确定，需要在异常现场同步捕获，
不自动追加游戏、扫参数、改算法或扩大基线矩阵。
生产/默认包/原工具及原脏改保持，无接入、提交推送或新增外审。
历史1497项按1494原路径和三协调文档快照核对，新原件和报告见seal-v1.json。

## 六十、需求纠正：只取消预算U，保留worker与原派发，重测对B75/P

2026-10-22用户明确“本轮测试作废，重新跑取消预算系统，与75ms及基线比较”。
此前I/I2把取消预算扩大成submit内同步drain，I2还改了Service初始化次序，
不符合该需求。**I/I2及其后续profile定位作废用于预算取消的方案评价**，
所有旧源码/数据/报告/审查原件保留；旧CPU异常不再作为U的证据。
75ms有界突发B、当前生产P、同包原生N仍有效，不默认重跑。
新[完整报告](../../reports/2026-10-22-no-budget-original-dispatch-validation-zh.md)，
目录`.tmp/scheduler-no-budget-20261022-v1/`，`supersession.json`记录作废范围。

U从生产复制，只改隔离Scheduler：
删除tokens、补额扣额、最小起步余额、43/75ms截止、haveTime和搜索步数/时间额度，
submit仍只入队返回，wake仍回主线程只入队；
END与原server.tell(TickTask)就绪入口派发，不在提交栈里直接执行。
worker两线程、宏观与窗口准备及Service源码保持，未采用I2初始化修改。
account保留实际耗时观测，不影响准入；兼容预算字段为0，非伪造零执行成本。
优先级/维度轮转、容量、取消替换、身份、关闭和异步依赖保护保持。

76项两遍stub控制通过，包括无haveTime/超过75ms仍派发与submit不立即执行；
隔离构建、原两探针selfcheck、900000负载确定性检查通过。
沿用原压力接口、8类地形、15/25/35ms均值/5ms标准差/20261021种子、
每档64×8→64×28→128×8→128×28→256×8→256×28顺序、30秒就绪门和90秒观察。
仅U新增三个JVM/18场/2688尝试，B/P/N九历史JVM复用，基线重跑0；
不是三次重复平均。U2688/2688实际到达，原生决策0、失败0、exit0及清理通过。
路线、注入共同前缀、tick START+1映射、环境/源码/JAR身份均核对。

对P/B：整批首Path P95均18降0升；
自身提交首Path中位对P18降，对B16降2升（15/8/64 +19.20、25/8/64 +32.92ms）。
实际到达时差逐路线相减后取分位：中位对P18降、对B17降1升（25/8/64 +2.07ms）；
P95对P14降4升（最大+13.37ms）、对B11降7升（最大+109.10ms）。
不以两端中位相减替代路线差值，不据中位改善称每条都提前。
35/256/28首Path P95 P562.90/B619.11/U114.45ms；
对N共同路线到达增量中位508.37/503.60/138.08ms，共同样本207/256，
N未到达49条保留，P/B/U均256/256到达。

CPU逐格对P7降11升、对B6降11升1平，最大升幅+13.87%/+26.27%；
18自然窗总量U111953.125ms，对P+1.24%/B+2.21%，不是重复平均。
CPU速率对P13场升/对B12场升，固定首1秒对两者均7场成本界明确升，
不能全部以观察时长差解释，也不推断唯一JIT/线程因果。
分配总量对P-3.27%/B+0.54%；对B25/8/256仍+35.42%，全部不利保留。
U每档1700搜索/22partial，P1700/1704/1704、B1702/1702/1704；
物理宏观每档194保持，无旧I/I2额外原生决策。

同B保留drain时间戳账本，原工具不改；P/N缺extent不补测。
U完整tick最大41.88/53.02/63.41ms，但相邻间隔最大134.53/151.83/117.94ms，
extent最大134.51/151.81/117.92ms，不能把包络外执行漏掉。
单次drain最大77.42/117.33/79.69ms；extent并非全部CPU busy，原始跨度保留。
相邻间隔最大对P17升1降、对B18场全升，>100ms间隔三档1/1/3次，B/P均0。
U extent>75ms三档6/6/6次、最长连续2/2/1，均按原五连≤50ms口径观测到恢复；
相邻间隔口径35ms仍有一次未在窗内观测到恢复，不混为永久失败。

U对N同路线到达增量中位16/18场≤200ms，例外25/8/64 229.02、35/8/64 207.70ms；
到达时差P95全部>200ms（353.01–2644.11ms），整体尾部目标未完成。
本轮正确需求验证结清，U值得保留为后继研究，但非全面/稳定/生产通过；
下一若处理准入，优先取舍已实录的单次drain峰值与到达尾部，不恢复旧I/I2。
不自动加轮次、调参数或接入生产，无新外审或旧审查覆盖新候选的声明。
前59项及更早1497项原件/三文档原字节快照核对，生产68及默认包/原脏改保持。
最终seal-v1.json封存，本轮无提交、推送或部署。

## 六十一、详细搜索两候选：1017共识、隔离筛测与停止

2026-10-22用户要求继续和1017讨论，确定两种方法进行隔离验证。
完整[方案](../../designs/2026-10-22-detail-two-candidates-isolation-plan-zh.md)、
[报告](../../reports/2026-10-22-detail-two-candidates-isolation-validation-zh.md)。
本节覆盖此前两轮三算法讨论后的实际推进，不把草案A当默认实施。

官方deepseek-flash/max四轮独立自含包6765/5567/5367/4463B：
前三轮REQUEST_CHANGES，逐步补清终止、去掉冗余虚拟终点/多候选，
明确原BinaryHeap私有sift无法覆写，采用自持堆存储/公有操作适配；
第四轮80.456秒ACCEPT_SCOPED、本轮必须修改项无，仅认可有限隔离筛测。
模型列表无独立1017日期ID，实际使用deepseek-flash别名，指纹及原件保留。

最终两项从U独立建立，不叠加：
T严格原f优先，仅同f按最终目标平方距离/xyz；
QG原目标组加几何终端偏置qi，原1.5/start例外/首目标停止/partial保持。
只影响已adopt正常中间地面多目标段，查询/最终/单点/恢复等排除；
不改worker、选点/分段、原生邻接、range/visited，不新增队列/缓存/长边。
保留原PathFinder循环及探针，额外上下文/适配成本一并计入。
相对U各四份输入变化，彼此只有模式常量不同；没有生产接入。

4152/4153真实类图控制、两个隔离构建/原探针及900000负载检查通过。
仅新增T/QG各一个35ms原pressure六场JVM，64/128/256×8/28顺序保持，
U35/P35/N35有效历史直接复用；原接口/工具/参数/逐路线/负载前缀核对。
无新增15/25/304或基线，1792/1792实际到达、退出和关服通过，
每格一次，不是重复平均或稳定性能证明。

对U汇总：搜索wall275.31→368.66/482.48ms，
CPU250→312.5/468.75ms，分配48.99→49.94/81.58MiB；
搜索1700→1702/1746，partial22→24/68。
T首Path P95五升一降、到达时差P95五升一降；
QG对应两项均六场上升。中位小幅有利及全窗CPU有利也保留，
不掩盖自然窗长度/背景占用差异，不归为详细算法净收益。

原DEBUG逐请求核对：新增partial全在首次正常中间段，
T的2条为256×8拐角；QG墙14/门14/拐角18，共46次，随后各多恢复一次。
同阶段/range可见reachable→partial，但缺逐次完整目标集与展开停止理由，
不宣称已完成全部反事实因果证明；不能把额外partial全当计时波动。
T 256×8另有完整tick346.7564ms、drain303.9683ms，
该场已计量详细搜索合计仅38.4947ms；无覆盖GC/safepoint日志，
没有JFR/TRACE分账，剩余尖峰来源未知，不归因比较器、worker或JIT。

代码两个独立包及数据一个包均走原provider，全部ENTITLEMENT_ERROR，
没有审查判词，不以官方方法批准代替代码或数据审查。
本轮有限筛测结清，两具体候选停止，保留源码和证据，不接入生产、
自动复跑、扫参数、提交推送；不推广为所有同分/宏观启发/新详细算法无效。
生产68项、默认制品及原TopologyWorkerRuntime脏改保持。
封存`.tmp/detail-two-candidates-20261022-v1/seal-v1.json`。

## 六十二、按用户授权清理旧稳定迭代数据

用户明确要求“直接删除隔离验证中已经稳定迭代的数据，保留结论和源码即可”。
已清理旧分段派发/宏观预选/目标质量/轻量选点及范围迭代的运行原始数据、
旧世界/JFR和构建二进制，1252文件、513120294字节（489.35MiB）。
9673保留文件SHA-256前后核对，源码、配置、脚本、结论/比较和审查保持。
生产、本轮候选与当前有效U/P/N压力证据、原工具和模板未清理。
原拟build迁移未实施，原runner与D盘游戏目录保持。

旧报告的“原件保留”声明以[清理报告及逐文件清单](../../reports/2026-10-22-stable-isolation-data-cleanup-zh.md)
为准。旧seal仍记录当时身份，不再承诺已删原件可复算；
后续按具体缺失字段判断历史是否失效，不自动重跑仍有效基线。

## 六十三、T/QG回退及347ms尖峰：1017讨论与下一步

用户要求不将多场负收益轻率归为波动，判断实现、结构或方法问题，
与1017讨论后拿出下一步。本轮只读复用原源码、字节码和历史数据，
完整[报告与方案](../../reports/2026-10-22-detail-candidates-regression-1017-next-plan-zh.md)。
没有新增游戏、构建、候选实现、生产修改、提交或推送。

范围澄清：T/QG各仅35ms六场，15/25/35ms是U的历史范围。
T新增热循环h包装，即使其helper固定NaN仍original.call；
作用域外也有新增包装/ThreadLocal计数。U原邻点过滤包装不算新增。
业务资格在搜索入口一次，未发现每节点完整身份/版本/预算防御链；
字节码Object[]/拆箱不等于JIT后必然实际分配，性能份额未分清。
QG新增46partial、T新增2partial均首次正常中间段并随后恢复，
不是计时波动；但NoTrace缺完整目标集/展开/退出原因，未唯一闭合根因。

新从原timeline取得包住T303.9683ms drain的395.9104ms采样窗，
main/process CPU均增78.125ms、main分配3339848B、GC次数/时间增0；
整场搜索wall仅38.4947ms。可反驳尖峰全由主线程算重解释，
不能将wall-CPU直接叫锁等待，也未唯一定位日志、宿主抢占或其它停顿。
复算及输入哈希存`.tmp/detail-two-candidates-next-analysis-20261022-v1/evidence.json`，
生产68项前后保持。

官方deepseek-flash/max两轮6377/6570B，154.899/69.324秒完整返回；
当前模型列表无独立1017日期ID，别名非不可变版本证明。
首轮过强断言经第二轮纠正：updateBest用真实距离本身非代码错误；
+6/+12/+24是更多实体复现，非单请求随并发/range增长证明；
原生已有类型/碰撞缓存，不能笼统再缓存；空后端不要求到达时间相同。
讨论仅方法及结构成本，不代替原provider正式代码/数据审计。

下一方案：冻结T/QG，不调参、不自动重做clean两项。
先精确列S相对U的diff并复用固定输入fixture，
S保留旧Context/全局h包装/邻点ThreadLocal计数/外层包装，算法用原堆和原h。
若后续获准，只新增S一个35ms原六场JVM，U/T/QG/P/N有效历史直接复用，
不补15/25、不重跑U、不扩四版本。S用于量级归因，不线性分账；
差异含混则记未分清并停。尖峰账本/栈诊断与S主计时分开，
再现才独立补缺失数据，未再现保持未知，不扩矩阵追峰。
后续询点方向先静态核对cache miss重复纯查询，无热点或已有缓存全覆盖即停；
不自动切JPS/Theta/门户图，不承诺200ms。本轮仅成案，后继验证尚未启动。

## 六十四、直接清理T冗余并验证，方向规则专项结构审查

用户最新授权清冗余测试，并强调启发部分重点在结构审查。
本轮直接建立TC，替代第六十三节S优先建议；没有运行S或修改QG。
完整[报告](../../reports/2026-10-22-t-cleanup-structural-review-validation-zh.md)、
目录`.tmp/detail-t-cleanup-20261022-v1/`。

TC从T复制，仅Mixin/helper/模式三份源码变化：
删除全部无用h包装、逐邻点ThreadLocal/计数、同f计数与未启用QG代码；
保留原同f→最终G²→xyz比较、堆结构、资格/Context和边界堆恢复。
Service与T逐字节相同，worker/派发/选点/分段及搜索限制未改。
编译字节码和堆去统计归一化核对通过，游戏768次适用边界回执。

官方1017所用flash/max，7402B独立具体代码/原循环结构包，
149.870秒正常完整返回，未提供本轮跑分，非全项目代码/数据或生产批准。
有限f及原作用域下未见比较、changeCost/remove、sift或恢复的确定硬错；
要求核实clear后不复用旧Node，已核原NodeEvaluator.prepare清nodes表和禁止重入。
核心结构风险：G不等于当前段Ti，朝G同f偏好可能先走墙后/楼层死路；
有限节点/range、closed与首命中意味着不改f主序也可能改变路径/partial。
这不是“几何=已证过拟合”，但宏观连通资产不能支持其普遍收益主张。

隔离构建、原探针双自检、900000负载序列及4152原控制通过。
仅新增TC一个35ms原六场，U/T/P/N四历史复用，无新15/25或基线；
896/896实际到达、原生决策0、failures0、exit0及原analyzer/清理通过，
无其它Java干扰，每格一次，非平均。

U/T/TC搜索wall275.3112/368.6632/338.6186ms，
CPU250/312.5/359.375ms，分配48.9931/49.9435/55.3952MiB。
TC对T wall-8.15%，CPU+15%/分配+10.92%；对U为+22.99%/+43.75%/+13.07%。
对U首Path整批P95及同路线到达差P95均五升一降；
对T分别四降两升、一降五升，所有负收益保留。
自然窗CPU下降但窗长/背景不同，不反推详细算法收益。
搜索/partial为1700/22，与U逐路线无计数差，旧T两次额外partial未复现；
缺逐次完整输入，不能据此认为包装修复了方向规则。
旧347ms尖峰未复现，TC完整tick82.7862、间隔117.7257、drain72.0961ms，
仍不将旧峰唯一归包装或纯波动。当前具体规则无净收益依据，停止生产化，
不继续调参/自动复跑/接入，不外推所有详细算法无效。

初次启动因原3GiB磁盘门退出，未创建游戏JVM。
按用户“优先删除无用数据”删除旧停止分支174份world，
3993文件/1845534398B，4995保留文件哈希通过；
只删世界副本，源码/日志/时间线/结论/当前历史保持，无迁移、无改测试门。
旧世界保留声明以本轮清单为准，其它历史不自动失效。
生产68项、默认包及原TopologyWorkerRuntime脏改保持，无提交推送部署。

## 六十五、详细寻路源码与NavMesh研究：下一单项候选

按用户要求与1017讨论，关注性能开销，先读源码和多份一手文献，
同时研究NavMesh如何完成详细寻路。本轮[研究记录](../../reports/2026-10-22-detail-query-navmesh-1017-research-zh.md)、
[具体方案](../../designs/2026-10-22-detail-native-overhead-next-plan-zh.md)；
仅研究定方案，无候选实现、构建、游戏、接入、提交或推送。

实际阅读本项目NativeNavigationSearch/Capabilities/RegionMixin、
真实含Forge钩子的WalkNodeEvaluator、原region/Mob、
几何采样与宏观资产，以及原搜索探针。
固定Lithium1.20.1-0.11.4五份实现、Recast/Detour1.6.0关键流程，
取得并读完Polyanya2017、JPS2011两篇原文；原件保存并记录commit/hash。
HPA原文未成功取得，不冒称已读。

原生已有位置类型/AABBcache，Forge钩子可依赖世界/位置；
组件位图和1/16量化碰撞几何不是原生精确动作图或凸NavMesh。
Detour是多边形搜索后沿公共边漏斗提取拐点，另取高度/offmesh动作；
Recast构网及TileCache更新有真实成本，固定channel拉直非全图最优。
Polyanya在给定平面凸网、连续欧氏代价上搜root+interval；
单次网需一致、请求间可更新，online不等于免构网。
JPS依赖均匀无向格对称剪枝，单纯加长边不获得其全部收益。
没有新的项目证据证明可直接换入、全地形又大幅提速的后端。

新切点：每次find为finally还原构造previous完整能力record、
EnumMap及Map.copyOf，但previous只被apply，实际只用四开关与全部malus。
下一最小候选仅用请求私有四boolean+float[m]替代该临时表达。
原intent完整不可变能力、公共getter（含载具有效值）/全部setter、
当前evaluator、原异常/RAW/profiler/proof/navigation还原保持。
不跳过同值/默认键，不修载具行为，不池化、不加每节点检查，
不改堆/启发式/选点/分段/worker/派发。仍有O(m)读写和数组分配，
setter装箱保留，JIT后收益未测，不能称零成本或200ms主方案。
Map旧迭代无序与自定义setter副作用/异常部分还原是具体实现审查点。

读取合并暂缓：虽然可省known分支第二次chunk定位，
提前return会绕后续region注入，份额/JIT及兼容收益未闭合。
NavMesh长期保留分层凸patch+完整portal区间+独立有向动作资产，
平面均匀部分才研究Polyanya；不自动构网或用连续最短冒称原生代价最优。

官方flash/max三轮7651/8097/4966B，
109.089/97.511/31.993秒完整返回，别名不提供不可变1017日期证明。
第二轮收回无依据3%/5%门、冷前N及5100语料重放；
第三轮明确撤回将旧unknown先于高度误读为I新缺陷的意见，生产旧契约未改。
最终认可临时previous精简的有限实验边界，无已证收益、代码/数据或生产批准。

原search探针只包PathFinder，previous捕获/还原在窗外，
以后必须结合完整find外围与原drain/整场CPU/分配/首Path/到达。
必要新增外层观测先说明历史缺字段，不以改口径重跑所有基线。
如后续获实现授权，只一个独立变更、最小必要等价/异常控制、
原35ms六场一次筛测，复用有效U/P/N，含混即暂停/不保留，不自动扩轮次。
生产68项及相关历史43项保持，原脏改保留。
目录`.tmp/detail-query-navmesh-research-20261022-v1/`，封存`seal-v1.json`。

## 六十六、继续确定主方案：安全否定摘要，previous仅附带

用户明确临时还原数据精简不足以独立成为候选，要求继续研究主方案，
把精简捎带到下一候选。本节替代第六十五节“独立精简筛测”的执行安排；
旧文档、讨论和封存原件保留，不倒改历史结论。
完整[研究及1017记录](../../reports/2026-10-22-detail-structural-candidate-research-zh.md)、
[主方案](../../designs/2026-10-22-detail-neighbor-safety-summary-plan-zh.md)。
本轮0候选实现、0构建、0游戏，无新增基线/接入/提交/推送。

新主项：原生得到WALKABLE后最多24格的邻域危险扫描，
若能用实际详细region中活section的状态证明整片邻域无影响，则整段跳过。
这不是再缓存已被完整anchor cache覆盖的最终type，
也不把宏观连通/量化几何直接当原生合法移动证明。
不改堆/h/malus/普通邻点/目标/分段/worker/派发，不新造图或队列往返。
原previous四开关+primitive数组精简仅附带，生命周期和公共读写边界保持。

已从当前47.3.0映射source jar提取25份源码，
核默认block adjacent、EMPTY完整Fluid/FluidType链、原XYZ首命中及MutablePos末态、
实际region/chunk/section读取链、palette append/read/copy及底层数组clone。
非空节利用原maybeHas，所有相关palette安全才跳；危险/未知/缺失完整原函数一次。
空节原LevelChunk返回标准AIR/EMPTY，须认证实际返回状态；debug世界关闭快路。
原hasChunk/unknown次序保持，探测自身不设置unknown，不部分跳过混合邻格。

摘要放Linear/Hash palette本体，正负均缓存，key本对象+size+tags代次；
palette.read头清、copy新对象未初始化，扩容换对象，追加改size。
SingleValuePalette.copy共享自身，因此不缓存摘要、实时查value分类；
Global恒true直接慢路，不扫注册表。不另加container/section各层重复失效，
不为命中率擅自维护逐改块危险计数。
状态分类首次同步完成后同代次稳定，provider/tags变化换代次，
快路只在主线程查询且读取链受支持时开启；共享分类需完整发布。
未知全局Mixin无法靠反射识别，不宣称任意整合包100%兼容或增加每节点字节码检查。

交叉复用Lithium/Detour/Polyanya/JPS既有一手资料，
新增读完RSR作者2011-09-01全文并存官方JSON。
RSR/Block A*会议PDF未取得，不冒称已读；RSR内部动作附着口/认证/fanout仍未闭合，
不勉强启缩图。1017第一轮新单格type缓存+27格认证位图因重复现有cache且缺复用证据未采纳。
T/QG/TC停止结论保持，NavMesh长期资产方向保留，不自动构网。

官方flash/max三轮8371/8122/7064B，
161.014/150.169/105.225秒完整返回，均未改配置或生产。
第二轮撤回“5100搜索合计CPU937.5ms减单请求200ms得737ms”的口径错误，
及单格缓存最多24格即实际收益的过强推论；未设3%/5%门。
第三轮认可palette本体摘要进入实现审查，不要求多owner重复失效；
明确不能宣称内存更小、性能量级或生产通过。
其读取链条件后由本地补核落实debug/空节分支，这些具体补充未冒称已代码送审。

主项冷O(p)状态分类，热1..8节摘要/存活探测；失败要付探测加原扫描，
palette废弃危险项可长期压低命中，两类biome palette也承担字段。
类加载/tags/read/冷分类/作用域外包装均计成本，实际份额和净收益尚未测。
原search窗口覆盖主摘要、不含previous还原；后续要结合drain/整场CPU分配/首Path/到达，
不把窗口外精简归为主搜索改善，不承诺本分支完成项目200ms。
后续若获准，只一个主候选、最小必要新语义控制、原35ms六场一次；
U/P/N有效历史优先复用，不补15/25或冷热矩阵，不为新配对重跑基线。
生产68项、相关历史43项和前轮研究原件保持，原TopologyWorkerRuntime脏改保留。
目录`.tmp/detail-structural-candidate-research-20261022-v1/`，封存`seal-v1.json`。

## 六十七、NS邻域摘要与临时对象精简隔离验证

用户授权隔离验证，并再次明确临时对象精简必须一并实现。
2026-10-23完成[本轮报告](../../reports/2026-10-23-detail-neighbor-safety-isolated-validation-zh.md)，
目录`.tmp/detail-neighbor-safety-20261023-v1/`。
NS从冻结U/main复制，相对U仅13文件变化：
三个现有Java、Mixin列表及九份helper/interface/Mixin。
worker/Service/调度/宏观/能力业务record逐字节保持，P生产及默认包未改。

主项按第六十六节方案接原生24格危险邻域否定摘要：
真实region引用、非空节palette、空节AIR/EMPTY、debug关闭，
Linear/Hash正负摘要与size/tags/read失效，Single实时读、Global慢路；
默认provider/EMPTY/WATER标签完整覆盖，包含EMPTY被加入WATER的情况。
仅主线程原Walk evaluator query prepare绑定、finally撤销，
无新逐邻点ThreadLocal/预算链/计数或逐改块维护。
附带previous改为四boolean+float数组，全量公共读写/当前evaluator/
载具有效malus/异常/RAW/proof清理保持，仍有数组和setter装箱，不称零分配。

独立真实Forge/Mixin正确性JVM90项全过，
验证安全调用确实不执行原扫描、慢路只调用一次、类型及位置副作用、
跨节/缺失/高度/关闭重绑定、read/append/resize/copy/异常、真实FIRE/流体tag刷新，
以及还原全部malus/开关/float位/当前evaluator/载具/异常RAW及region清理。
测试mod独立命名空间，私有API反射仅在测试；计数Mixin未进正式性能包。
真实导出字节码、原探针双自检和900000负载序列控制通过。
控制与计时使用同一JAR：
`62f9a35e366b25c45360785678cc3b318e31cea8d2336df1c9b36dedda3b3a38`。

只新增一个35ms原六场性能JVM，U/P/N三历史复用，无新基线、15/25或冷热矩阵；
另一个正确性JVM不混计时。原模板/Java/八类地形/逐实体路线/负载种子与共同前缀/
30秒就绪/90秒观察/10tick尾窗/DEBUG/NoTrace/无JFR均对齐。
896/896到达、原生决策0、failures0、exit0、原analyzer及退出清理过，无其它Java干扰。
每格一次观察，不称重复平均。

对U搜索wall六降275.3112→178.8867ms（-35.02%），
CPU250→171.875ms（-31.25%），分配48.9931→45.9887MiB（-6.13%）。
CPU仍三降一升两平，256/8为0→15.625ms；分配五降一升，128/8+22.44KiB。
自然窗CPU48828.125→51515.625ms（+5.50%），分配-2.13%；
首Path整批P95三降三升，同路线到达差中位三降三升，P95六升+6.48至+64.76ms。
完整tick最大63.4061→70.6123ms，间隔117.9359→101.6663，
drain79.6889→56.7160；单格峰值负项和固定首1/5秒CPU界均保留。

搜索/partial1700/22→1704/26，新增在256/8的tile23/31、slot2/6四条corner。
均首个正常中间段partial后range16恢复，unknown=false，没有实体最终失败。
与P逐实体partial计数全部相同；阶段输出指纹对U848/896、对P861/896相同。
缺逐次精确起点/完整目标集/展开/退出原因，不能由历史P也出现证明唯一原因，
也不把计数差当纯计时波动。本轮仅复用日志定位，无追加游戏或改恢复。

对P搜索wall/CPU/分配合计-31.31%/-26.67%/-2.60%，
首Path及共同到达时差中位/P95六降，但多数延迟收益U原有，不归本轮；
CPU+5.03%、分配-5.26%，完整tick峰六场都比P高。
对N搜索三项-33.20%/-31.25%/-36.55%，但自然窗CPU/分配+77.74%/+58.36%，
共同到达增量中位82.22–195.74ms，P95仍408.39–2604.78ms；N仅802/896到达。
不宣称全项目200ms完成。

自然窗多2687.5ms CPU，主线程多2218.75、其它线程多468.75，搜索反而少78.125；
自然窗长度和35ms硬占用/游戏维护也在内，缺函数/线程采样，未唯一归代码/JIT或纯波动。
主摘要在search窗内，previous捕获/还原及全局包装其它消费者不全在内，
不能将search窗当全部直接影响或单独分出临时精简的收益。

最终代码7283/8341/6318B与数据7560B均原deepseek provider因ENTITLEMENT_ERROR失败，
无正文判词，配置哈希保持，未切官方1017代代码/数据审查。
本地90控制及原方法共识不能代替外审。
结论：保留有搜索成本收益证据的NS隔离候选；
外层负项和外审缺口不支持全面净收益/生产通过，不自动复跑、调参或接入。
生产68、相关历史43、U母版源码和前轮研究封存原件保持，原脏改保留，
无提交、推送、部署或数据清理。封存`seal-v1.json`。

## 六十八、长分段隔离验证及后续raw降本决策

2026-10-23按用户“先验证长路径，再与1017讨论继续降本”的授权，
完成[方案](../../designs/2026-10-23-detail-longpath-cost-validation-plan-zh.md)及
[完整报告](../../reports/2026-10-23-detail-longpath-cost-isolated-validation-zh.md)。
隔离`.tmp/detail-longpath-cost-20261023-v1/`，生产/默认包/原脏改保持。

L复制NS冻结71份源码，仅Service和GroundSegmentWindow变化：
普通非恢复2→4连接，余下不超过2保持；实际section集合上限9，
原2/1仍四节门。final/shared/range/节点限额/最多8目标及排序/
原生邻接/worker/派发保持，不加逐段proof.current。
长段准备/搜索/执行失败按原限额计账，本请求永久回原2/1；
失败partial不发布、不推进cursor，重规划不重新启4。
方法两包5582/4810B官方flash/max完整，纠正逐段证明扫描和200ms分支门。
当前公开别名无独立1017日期ID，指纹e72d3c7f663d3e1383b00b3884cb4501，不称不可变版本。

L原35ms/sigma5/seed20261021六场一个新JVM，每格一次，
NS/U/P/N四份有效历史直接复用，无重跑配对基线或15/25矩阵。
896/896物理到达、SUCCEEDED、service failures0/nativeDecision0，
原analyzer/身份/路由/负载共同前缀/清理门通过。
对NS搜索1704→1666（-2.23%），partial26→24，
wall178.8867→182.8239ms（+2.20%），CPU171.875→156.25（-9.09%，仅一个量化单位），
搜索分配45.9887→72.0057MiB（+56.57%）。
自然窗CPU51515.625→61718.75ms（+19.81%），分配+30.26%，窗长+33.82%。
自然窗含35ms硬占用、游戏和行走，不能全部归A*；28格三场首5秒CPU界均下降。
额外26.0169MiB搜索分配中，832个未扩长请求就多22.9458MiB（约88.20%），
50成功扩长多0.9735，14扩长后恢复多2.0975；不将全部分配唯一归跨度/JIT/外部波动。

实际64/896请求采用>2连接，64次均canReach且无unknown/partial，
14次后来执行恢复（64/28两次，128/28六次，256/28六次）。
DEBUG完整覆盖1666次搜索，回退后未重试4；未动态覆盖长段空/partial/unknown失败控制。
64/28台阶tile4slot1：NS三搜索9447.0420ms到达；
L首0→4为26节点可达，约5.75秒后recover，再原0→2、2→4和final，
四搜索15345.0231ms到达，晚5897.9811ms。
128/28台阶最大晚10850.6605ms；256/28最大晚10836.8234ms。
已确认额外长段执行和回退链，不按纯波动处理；
无完整节点/原生停止栈，尚未唯一定位具体物理失败原因，不称A*计算不足。
首Path整批P95对NS四降二升，同路线到达差P95两降四升；
完整tick最大70.6123→74.5496、相邻起点间隔101.6663→128.1481、
drain56.7160→85.3860ms，不混为单次搜索。

NS/U缺304有效历史，仅补NS原closure单实体平地冷暖一JVM，
再L一JVM，旧S9L(P20)历史复用作背景，不称NS母版。
首次NS启动在计时前因旧探针夹带生产refmap遮盖NS新增映射失败；
只在复制品删外来资源，另41条目SHA相同，工具/计量代码及原件不变。
修正NS-v2与L有效，冷暖各一次，未丢弃有效性能结果。
冷暖每场详细搜索20→11，9次四连接段均成功，合计18/18无回退；
最大节点17→33、range40.55764、实际最多3节。
冷/暖搜索wall29.9840→26.1227、4.8502→4.7802ms，
到达68796.1246→68344.2687、68600.3931→68150.3417ms，
提前451.8559/450.0514ms；首Path分别晚25.4035/8.0201ms。
实测本次平地跨度成本可承受及少接续收益，不能外推全程一次搜索或并发复杂地形。
因此不普遍启用L、不扫stride3、不在L叠候选；NS继续隔离保留。

后续raw分类按状态缓存方法首包6875B耗尽输出无正文，回执保留，
收窄3461B完整返回建议先定位。仅补原35ms/128实体/28格一场只读D诊断，
D复制NS加三源码/Mixin清单，原结果/读取/钩子顺序不改；
128/128到达、284搜索、原analyzer及计数闭合门通过，不纳性能对照。
raw93355次：空气75375（80.74%）/石头17980，只有OPEN/BLOCKED，
block hook93355、实际fluid读取/fluid hook/LAND各17980，嵌套/未闭合0。
潜在静态资格全部覆盖仅本场空气/石头，尚非全fluid/isAir/Mixin纯度证明。
1458个每64次系统采样累计320100ns，均值219.55ns，外推20.50ms；
是带观察钩子的wall，不能当真实CPU份额/净提速上限。
过滤后的单场首次搜索与原第四场JIT上下文不同，146.0174ms插桩搜索不作性能对比；
24135状态预枚举390.36ms是诊断成本，不充当未来懒缓存冷成本。

最终3719B官方包完整返回，双方收敛本轮不立即实施raw缓存主候选，
保留条件后备；不把20.50ms当严格收益上限。
继续研究节点展开调用点重复读取/碰撞/支撑及raw次数，
同时闭合NS外层CPU与台阶长段执行机制；没有已通过的保守扩长资格，
不自动新增启发式/几何阈值或矩阵。200ms仍是项目整体目标。
L代码5804B/数据5159B均原provider订阅权限拒绝，无通过判词，
未切官方代代码/数据审查；D诊断也无外审通过，方法/本地门不代生产准入。
结尾核对生产68、历史43、NS源码71及旧冻结原件保持，
全部源码/失败有效运行/讨论/分析/回执保留；无提交推送、生产接入或清理。

## 六十九、后续降本研究与跨节点计时停止定位

2026-10-23按用户“继续研究”完成
[完整报告](../../reports/2026-10-23-detail-next-cost-stuck-research-zh.md)及
[下一修正方案](../../designs/2026-10-23-waypoint-timeout-carry-fix-plan-zh.md)。
隔离`.tmp/detail-next-cost-research-20261023-v1/`，不实现修复或接入生产。

先核对前轮366份封存证据、生产68项/历史43项/NS源码71份；
提取实际Forge47.3.0映射source JAR的12份一手源码，身份见本轮freeze。
继续读WalkNodeEvaluator/BlockBehaviour及固定Lithium源码，
交叉核对官方1.20.1分支PathNavigation补丁；不将移动分支当不可变版本。

新结构假设：原`doStuckDetection`换next时重算limit却不清timer，
短段publish反而频繁reset；原timeoutPath先清再stop，旧recover又合并两种stop，
因此旧日志不能唯一定位。100tick进展停止与Service的40tick落地TTL需分开。
两轮官方flash/max自含6249/4523B均完整，指纹与前轮相同，保护文件未改。
第一轮建议只补一次停止分支诊断；第二轮仅讨论已由本地核对的观察事实，
不是代码/数据审核。没有独立1017日期ID，原provider外审缺口未由官方替代。

LD只读复制冻结L，加两个诊断源码/Mixin清单/Service两个观察调用；
原path、timer、stop及恢复逻辑保持。仅原pressure28-n64一场，
35ms/sigma5/seed20261021，Scene过滤为旧接口。
不重跑基线/原六场或扩矩阵；额外观察分配及首场景JIT使其不能作性能对照。
构建/原900000负载控制/analyzer/精确64路线/退出清理通过；
64/64物理到达且SUCCEEDED、138搜索/138发布、3794事件，
service failures0/nativeDecision0、exit0，全部非临时search上下文。

8长段发布中2执行恢复；4次waypointTimeout、0次100tick停止。
tile4反向台阶slot1/5为0→4长段，slot3/7是原0→2段；
26/27节点、index21/22，在真实索引前进1且next坐标变化的同tick立即超时。
新节点等待0tick，旧timer89/92tick，新limit21.120176、3倍阈值63.360529tick；
当前距next约0.340035格且onGround，100tick进展检查在13/17tick前通过。
入口观察在reset之前，旧计时是这4次即时超时谓词成立的必要部分；
stop后2tick实际recover、nativeStopped=true。
另4次无nativeStop恢复，经DEBUG核对为墙/门原短段reachable=false的partial端点，
没有未分类恢复。分析初版保留，最终用stuck-analysis-v2.json。

本场旧两条长段失败路线再次复现，但其余128/256的12次未逐条诊断；
不能外推旧14次全部同因、修正后必能完成或已获CPU/到达收益。
不需要为本场直接机制结论再复跑LD；下一须做单变量修正与物理完成验证。

计算方向：floor重复但两参静态shape已有缓存、普通maxY很短，
新坐标缓存需付资格/查找/失效成本；没有足够份额支持主候选。
对角提前否决会漏共享Node.type/costMalus/closed及动态钩子，未证等价不做。
reconstructPath尾加reverse只作附带；raw复用仍条件后备，
不把旧插桩wall估计作真实CPU份额或收益上限。

方法共识：下一从NS建单变量候选，在原next变化分支、原阈值判断前，
只对执行中的NavigationRequestPath清timer；无逐tick Map/新guard/对象分配，
不复制方法，不改同节点累计、limit/3倍阈值、100tick进展、
LANDING_TTL、publish、取消/替换、partial及恢复限额。
先验证真实阻挡与低进展仍终止，再决定独立扩长，
不直接在L叠修复掩盖分配异常；NS外层CPU/L分配异常继续保留。
200ms仍为项目整体目标，不给本分支强设收益门。
本轮仅成案，生产68项、默认包、原脏改、旧候选及历史保持，
无修复实施、生产接入、提交推送或清理；本轮证据封存seal-v1.json。

## 七十、NS上的节点计时修复与隔离验证

2026-10-23按用户“进行修复”落实
[方案](../../designs/2026-10-23-waypoint-timeout-carry-fix-plan-zh.md)，
[完整报告](../../reports/2026-10-23-waypoint-timeout-fix-isolated-validation-zh.md)。
隔离`.tmp/waypoint-timeout-fix-20261023-v1/`，不接入生产或扩长。

NT从冻结NS复制，71份源码中仅AutomaticNavigationMixin变化：
原doStuckDetection的next变化else内唯一bottomCenter调用处，
ModifyArg原参返回，仅对NavigationRequestPath清timeoutTimer。
原cached已赋值，limit与比较未执行；同节点无新调用。
无新增实例状态/逐tick表/缓存或身份链，
同节点累计、limit公式、3倍阈值、100tick进展、TTL、恢复限额不改；
Service/Window/原2或1连接分段/8目标/原生A*/worker/派发按SHA保持。
初版Inject字节码暴露CallbackInfo分配，正式性能前改最终无回调对象钩子；
最终变换字节码确认单调用且无新增new。

最终独立Forge/Mixin25控制通过，含节点变化/同节点/非项目/
100tick/partial/publish/clear/同intent复用/不同intent替换/取消。
真实石墙+实体物理，未入实体表控制实体每server tick tick一次，
原60tick阈值后第61tick由waypoint超时停止，未穿墙或抵达。
NS原90邻域及还原控制对应实现未变复用；新控制不进性能包。
首次控制编译接口cast错误、首次运行误判同intent复用均保留，
仅改控制；一次控制启动在Java空闲门前拒绝，无新JVM，门未放松。
最终控制JAR与正式性能JAR身份一致。

仅新增NT原35ms/sigma5/seed20261021六场一个JVM，
64/128/256各8/28格每格一次；NS/U/P/N四历史直接复用，
无新基线、15/25或304矩阵。900000负载控制、原analyzer、路线/负载前缀、
身份及退出清理通过；896/896物理到达且SUCCEEDED，
nativeDecision0、failures0、exit0，mods仅生产+原计量。

对NS搜索1704→1686，partial26→22，恢复40→22；
28格台阶三场各少2/6/6次reachable执行后恢复，共14条请求。
这14条不是前轮L14次长段失败的同一集合，不据数量相同外推L通过；
本轮无>2连接，DEBUG完整覆盖1686搜索。
另4条256/8 corner首次中段partial消失，缺完整查询输入，
不能全部归timer修改。
搜索wall178.88674→123.4408ms（-30.99%），CPU171.875→93.75ms（量化）；
新增代码在执行窗、A*未改，少18次搜索不能解释所有wall下降，不称内核加速31%。
搜索分配45.988739→51.030342MiB（+10.96%），
878个搜索/partial/恢复计数不变请求多5.497978MiB，
14台阶变化组-0.066986、4corner组-0.389389，净+5.041603；
计数同不证明输入/展开同，不唯一归JIT/宿主或timer。

自然窗CPU51515.625→43687.5ms（-15.20%）、分配-8.45%，窗长合计-10.10%；
包含硬占用/游戏/行走，不全算timer独占收益。
逐格首Path整批P95四降二升（+16.3859/+1.8135ms），
同路线到达差中位四降二升、P95两降四升（最大+63.8146ms）。
请求终态差中位最大+44.3177、P95最大+63.415701ms；
固定64/28的1/5秒CPU界均升，128/28的5秒分配界升等负项完整保留。
完整tick最大70.6123→70.5783，相邻间隔101.6663→96.734，
drain56.716001→50.9442ms；逐格tick最大五降一升，间隔四降二升。

秒级到达延后共10条28格台阶，晚1502.73至1810.84ms：
tile4slot0/4跨三并发六条计数不变，首段7→12节点、末段8/9→10；
tile12slot3/7跨128/256四条虽恢复1→0，首段8→11、末段5→9。
首次range也已不同；四条的23节点中段含旧恢复窗口反而缩短约0.29~0.31秒。
有明确路径/交接差异，不能算清timer检查耗时1.5秒，也不能定性纯计时波动；
缺完整目标集合/当前位置/节点坐标，未唯一定位上游来源，不自动再跑或叠选点候选。
结果诊断v2覆盖全部10条，首版4条分析原件保留。

相对当前P首Path P95及到达中位六降，P95五降一升+0.2983ms，
但搜索分配六升、tick最大五升、间隔最大六升，CPU四降一平一升；
包含U/NS前序变化，不全归本修复。
N仅802/896到达；NT共同成功路线到达增量中位58.93~158.43ms，
P95仍361.12~2627.03ms，不称整体200ms验收通过；NT tick最大对N六降。
不同成功集合自然CPU不当公平单位成本对比。

代码初版4354B、最终4085B、数据5778B均原provider订阅权限拒绝，
无正文/通过判词，配置SHA保持，未切官方做代码/数据审核。
本轮逻辑修复及本地针对性验证完成，保留NT隔离版；
生产68/默认包/原脏改/NS/L及有效历史保持，无接入、提交推送或清理。
不自动启动扩长；后续若做，必须以修正母版明确身份，保留路径和分配未定位项。
全部成功/失败、源码/构建/分析/外审回执封存seal-v1.json。

## 七十一、宏观等代价绕行与交接输入来源确认

2026-10-23按用户“根据当前数据确定下一步方向”“进行确认”完成
[路线选择差异来源确认](../../reports/2026-10-23-route-choice-source-confirmation-zh.md)。
隔离`.tmp/route-choice-confirmation-20261023-v1/`，不改生产或NS/NT业务源码。

先复用前轮NT/NS十条台阶秒级延后与DEBUG：
tile4slot0/4三档共六条，在取八点之前完整候选已经16→2，
所以不能归为同全集内原生采用了不同目标；
tile12slot3/7的四条全集计数同16但range不同，仍缺完整坐标。
旧NT/NS/U/P/N性能全部保留有效，不为配对重跑基线或原六场矩阵。

仅因缺宏观/目标坐标，使用原NS/NT现成最终JAR各补一个
原pressure28-n128、35ms/sigma5/seed20261021诊断JVM，
原模板、路线、工具/计量与30秒门保持，开启既有Navigation TRACE。
无构建/插桩/业务变化；单场过滤和TRACE改变运行历史及观察成本，
两场不进入性能对照，不替换历史基线，不把新计时作修复收益。
各128/128到达、NS284/NT278搜索；原analyzer、变换、身份、路线、
退出/清理检查通过，实际原JAR SHA在报告/冻结记录中。

本场tile4slot0/4：
两版实际起点相同，起终点z=-134，
NS绕北侧section(1,5,-8)，NT绕南侧(1,5,-10)，宏观结构代价均4。
首交接(31,81,-128)/(31,81,-145)，
第二交接(15,81,-129)/(15,81,-144)；
slot0节点7/17/8对12/17/10，slot4为7/17/9对12/17/10。
宏观绕行侧变化先于目标选举，南侧首末段更长已有几何支持。
本场NS全集2，旧NS全集16，不能宣称完整复现旧NS输入。
128对中12对宏观几何不同且首全集坐标不同，
其余116对未见首全集坐标变化；宏观变化本身不等于实际走法一定更差。

tile12slot3/7本次也换宏观中间组件，
但全集NS16/NT13，两版首段均11节点，实际首交接同(15,81,-112)，
中段均23节点/同(16,81,-90)，NS有一次恢复而NT无。
未重现旧首段8→11/全集16→16签名，
不将这两次新观察当旧四条1.5秒增量的完整归因，未继续追加追跑。

全部288组正常attempt0非最终搜索按实际坐标重算前八与range一致，
证明现有选择器按原规则工作，不证明评分最优。
源码确认MacroSearch等g保留先到前驱，section级h无实际绕行几何，
Window按段末宏观连接枚举候选；不同来边可直接改变全集。
依赖就绪/堆同分等具备顺序影响条件，但具体触发源未唯一定位。
共享键按结构组件建立，复用只重建精确首尾、不重选中间走廊；
后续几何择路方案须兼顾共享成本，不能默认按精确坐标拆键。

下一主方向是宏观等结构代价路线质量，先研究轻量几何择路，
而非再次只调末端前八、普遍硬过滤或恢复旧完整距离场。
纯坐标固定平局只稳定结果，不保证选到较短路线；尚未选定最终算法。
保留NT母版和搜索分配不确定项，未启四连接或叠选点候选，
未证明旧L14次恢复全部消失。200ms仍为整体目标。
本轮无模型外审/通过判词；生产68/历史43/NS71、默认包、
原脏改及旧封存证据保持，无接入、提交推送或清理。

### 第七十一节同日补充：输入与因果边界修正

用户指出完整输入固定的确定性算法不应无故改变输出。
据此追加[因果边界修正](../../reports/2026-10-23-route-choice-input-boundary-correction-zh.md)，
覆盖上文“宏观质量优化已确定为下一主方向”的结论，原数据和封存原件不改。

NS/NT宏观相关源码保持相同，仍只相差节点计时修复。
前轮对齐了接口、模板清单、起终点、负载等显式条件，
但没有证明实际组件候选/能力/拓扑邻接与边代价、依赖交付批次和顺序、
搜索方向/层级、共享走廊命中和来源等完整输入及运行状态相同。
存在影响机制不等于已证明本次变化；不能用“异步”代替输入差异取证，
跨进程revision值也不能直接证明地形内容不同。

历史六条全集16→2、新诊断绕行差异和288组选择复核继续成立；
输出及下游输入变化不足以确诊宏观算法质量问题或NT直接导致回退。
下一步应先定位完整有效输入/运行状态从哪里分叉，
相关条件固定后再判断实现或选路策略是否需改。
轻量几何择路降为待研究可能，不再作为已确定下一候选。
旧性能数据保留观测效力，归因缺口不自动使历史基线失效；
本补充无新游戏/构建/业务改动/模型调用/生产接入，原证据保持。

## 七十二、宏观通道分叉的依赖交付输入定位

2026-10-23按用户“那你继续找呀”，完成
[宏观依赖交付输入定位](../../reports/2026-10-23-macro-dependency-input-cause-zh.md)。
本轮继续查输入/状态分叉，没有改宏观评分或详细选点。
隔离`.tmp/macro-input-cause-20261023-v1/`，ND复制冻结NT，
仅MacroSearch/Runtime观测调用和新增MacroInputTrace三文件，
不写业务状态，但诊断开销会改变时序，不能作性能对比。

历史性能/基线保持有效复用，仅补完整输入缺口：
原35ms/sigma5/seed20261021、pressure28-n128、原接口/工具/模板/路线/30秒门，
两场TRACE/NoTrace，各128/128、原analyzer/退出/清理通过。
第一场台阶都走南侧，第二场捕获查询30同起终输入但通道不同。
每场焦点6物理搜索、16请求、10共享跟随、0完成缓存命中，
观测无error/overflow，未扩大压力/实体/距离矩阵。

查询30实际(19,81,-85)→(19,81,-113)，原算法使用反向图；
能力、起终组件、weight1.25、slack1、上限及分支相同。
共同18份Base、24份边界内容逐项相同，Base仅排除跨JVM不稳定signatureHex；
source/halo fingerprints、revision、标签、metadata、边/能力/成本和位图均比较。
第二场额外探索区域较多，不声称捕获完整世界图。
BASE_BOUNDARY (1,5,-7)→(1,5,-8) NORTH在第一场第7批、第二场第4批available交付，
随后同代价宏观通道不同。

冻结原NT未插桩MacroSearch共36次回放，全部复现输入记录对应结果；
固定已观测part图后，原调度逐次边ID/目标/成本/次序、依赖/part/次序、
completed/skipped mask及展开序列一致，未知完整part直接拒绝。
仅将查询30该边界7→4批，就改变中间组件链；
查询8仅将南侧边界(1,5,-10)→(1,5,-9) SOUTH从4→5批，
台阶路线就从section(1,5,-10)改走(1,5,-8)。
两项各3对稳定改变，成本均4，节点ID、已知图/h/端点/算法保持。
回放只验证搜索几何，边界payload用局部描述替代，不冒充详细碰撞/到达验证。

明确机制：续行只消费已就绪邻接，未要求竞争通道同步交付；
等g不替换既有前驱，故批次变化足以影响通道。
用户指出宏观不管逐格绕台阶，这个边界成立；
这里变化的是跨section组件通道，不是固定通道中的微观台阶方块。

本轮已取得机制的单变量因果证据，但没恢复旧十条慢请求的完整批次，
也没证明NT节点计时修复是旧批次变化原因，不能全归旧1.50~1.81秒，
更不能恢复“宏观质量退化已确诊/下一步必须改选路”的旧结论。
固定结果与改善实际行走质量仍需分别判断；本轮未选新算法或实施修复。
初分析手工把第7批数成第6批，断言失败后依据原记录修正，原数据未改。
本地检查完成，未调用外部模型/宣称外审通过。
生产68/历史43/NS71、前轮非协调证据及原脏改保持，
无生产接入、提交推送、默认包替换或清理。

## 七十三、下一详细寻路候选NT-L4研究

2026-10-23按用户“继续研究详细寻路，确定下一个候选”，完成
[研究记录](../../reports/2026-10-23-detail-next-candidate-research-zh.md)与
[NT-L4方案](../../designs/2026-10-23-detail-nt-long4-candidate-plan-zh.md)。
本轮仅研究定方案，无候选实现、构建、游戏或新增性能数据。
用户暂停宏观平局/选路质量方向，上一节输入批次机制结论保留。

选定NT-L4：复制冻结NT，仅迁用旧L的Service/Window两文件扩段差异。
普通非最终、首次attempt、原stride2且剩余>2时最多跨4连接；
连接不等于section，长窗理论9节，短窗仍原4节门。
候选仍worker枚举、主线程原排序取最多8个备选终点。
原生邻接/碰撞/1.5权重/visited/节点限额、final/shared、worker及派发保持。
range保持指原公式min96/max16/最远目标+8和上限不变，
实际目标变远可扩大range、region矩形、展开量与proof，不能声称查询成本不增。
长段失败或原执行恢复沿旧限额回原2/1，同请求含replan永久禁用扩段，
不发布失败partial、不推进cursor，不加proof全扫/额外预算/每tick守卫/新几何门。
既有发布链已交付NavigationRequestPath，继续覆盖NT计时修正，无需新增标记。

重新核对旧L：压力仅64/896请求扩段，64次可达但14次执行恢复，
部分晚5.8至10.9秒；304冷暖各20→11搜索、18长段无恢复、
到达早451.86/450.05ms，支持有限重试价值，不保证复杂并发或全程一次A*。
后续计时诊断只定位旧L其中2次，NT移除的14条台阶恢复不是同集合。
50条无长段恢复请求合计少50搜索约旧1704次的2.9%，不当随机因果或当前收益预测；
旧L分配+56.57%中88.20%在未扩段请求，NT自身+10.96%，均未唯一定位，
不声称timer修复自动解决，也不当纯波动。

本轮读实际Forge47.3.0七份源码、固定Lithium/旧研究及HPA*/Detour相关一手材料；
宏观连通资产不包含精确原生动作图/组件内距离，不直接套新全局引擎。
holder缓存仅省一次map.get且须保留live ticket，Load/Unload不足布尔缓存失效；
raw空气早返、floor已有静态shape缓存；proof消费者未闭合；
精确平方距离/sqrt仅小假设，reverse仅附带，未凑成新主候选或实施。

官方deepseek-official实时发现deepseek-flash，名称V4.1-Flash，
无独立1017日期ID；max两自含包8449/7179B，HTTP200/stop/完整返回，
指纹e72d3c7f663d3e1383b00b3884cb4501，保护文件未改。
1017认为没有更好且证据闭合的实质单查询候选，NT-L4是干净的有限交互验证，
不能包装性能胜利；本地采纳有限资格，不称大幅降本或生产审查通过。
官方只讨论方法，原provider代码/数据权限失败不由本轮替代。

后续最小安排：NT-L4原35ms/sigma5/seed20261021六场，仅新增候选，
NT为直接历史对照，NS/L/U/P/N有效历史复用，不重跑配对基线。
NS90/NT25按覆盖和身份复用，仅补长段partial/unknown/空结果等新回退及组合控制，
控制不进正式计时；304如需当前候选长路线结论才补缺失NT冷暖并测同接口候选，
旧NS/L只作背景，不自动追加矩阵。
保留需查询减少转为完整成本或实际到达的值得保留改善，
同时检查正确性/恢复/分配/tick；不设3%/5%或200ms分支门。

证据`.tmp/detail-next-candidate-research-20261023-v1/`。
先验生产68/历史43/NS71、冻结NT71及前轮原证据；
协调文件更新前快照单列，旧seal和非协调原件保持，本轮重新封存。
未改生产/旧候选/默认包/原脏改，无提交、推送、接入或清理。

## 七十四、NT-L4详细扩段隔离验证

2026-10-23按用户“好，进行隔离验证”，完成
[NT-L4验证报告](../../reports/2026-10-23-detail-nt-long4-isolated-validation-zh.md)。
隔离`.tmp/detail-nt-long4-20261023-v1/`，NTL从冻结NT复制71份，
仅Service/Window两文件逐字等于旧L；生产及旧候选不改。
选定计时/NativeNavigationSearch/Region/调度class及Mixin清单与NT逐字相同，
production/timeline-probe无本轮控制探针。

构建及原900000负载控制通过。独立实际Forge/Mixin控制52断言、退出0：
普通最多4/剩3选3/recovery不扩段，受控原生null/empty/noEnd/partial/
unknown-partial/发布未加载，实际Service不发布、不推进、原限额一次记账，
长段永久回原2/1，真实replan保留标志。
实际Service发布仍NavigationRequestPath，变换NT钩子清旧timer，
真实石墙60tick阈值第61停。曾读unknown但有可达结果仍沿母版语义接受。
控制在next/window/macro后续边界截断，不冒充完整异步窗口故障；
长窗准备异常/空候选回调仅静态复核，NS90/NT25按身份及范围复用。

仅NTL原35ms/sigma5/seed20261021、64/128/256各8/28六场一个性能JVM，
NT/NS/U/P/N五有效历史复用，无新基线/15/25/304。
原analyzer、Java/模板/路线/负载共同前缀、退出清理门通过，
896/896物理到达且SUCCEEDED、nativeDecision0/failures0/exit0，watchdog未超时。
NTL JAR875e04763e7a1c9e2adcaae983389b241bb2706f0013931ae9cc8f6d413c1747，
NT直接历史JARd69edb5373621f705464371dceaa76324e2f7cc7d321e69bc7998b334314e3a8。

对NT搜索1686→1622，partial/恢复22/22保持。
实际长段64（22跨3、42跨4）、64可达、0长段执行恢复；
性能最多5section/29返回节点/range42.43835，未覆盖理论9节上界。
64条每条少一次搜索，59到达提前5延后，中位-1054.74ms、P95+42.01、
最大+104.20、最大提前4107.63ms；54台阶全提前，10对角五提前五延后。
该组搜索wall-0.4772ms/分配+0.795433MiB，返回节点合计1958→1665，
不是展开节点数或少走293步，收益不全归少一个tick排队。
旧L曾恢复14条对应路线本轮均扩段无长段恢复，相对NT均提前396~2665ms，
不据此倒推旧14失败全部计时同因或独占修复。

总search wall123.4408→151.6726ms(+22.87%)，CPU93.75→109.375(+一个15.625量化单位)，
分配51.030342→68.694916MiB(+34.62%)，六场wall及分配全升。
832未扩段请求搜索/恢复不变，贡献wall+28.709ms、分配+16.869141MiB约95.50%增量，
不唯一归扩段、JIT或纯波动；按实际行为分组不冒充随机因果。
自然窗CPU43687.5→39796.875(-8.91%)、分配-3.12%、窗长-9.01%，
包含硬占用/行走/维护，不全作寻路计算收益。

首Path整批P95三降三升（最大+7.96ms），到达差中位五降一升、P95一降五升；
请求终态最大延后3137.84ms。
256/8的tile23slot1/5均未扩段，搜索各2/恢复0保持，却晚3040.96/2986.44ms；
完整候选11→9，返回首/末节点5+6或5+5→9+12，range17/18→22.32/22.14。
有明确输入/路径内容差异，触发源未唯一定位，不当单纯相同路径计时抖动，
也未用前轮交付批次机制冒充本次因果，不新增诊断JVM。
该场自然CPU+45.98%/分配+39.17%，64/8自然CPU+4.50%，固定窗负项见报告。
tick全局最大70.5783→59.2448，间隔96.7340→97.1482，
drain50.9442→54.8031；逐格tick三降三升、间隔一降五升、drain两降四升。

相对P首Path和到达中位六降，P95五降一升+0.35ms，包含前序收益；
搜索分配/tick最大/相邻间隔六升。N仅802/896到达，
共同成功路线增量中位57.30~188.70ms，P95仍275.08~645.56，不称全体200ms达标。
本轮无NT304，不把旧NS/L冷暖外推当前候选。

代码8218B/数据8385B送原provider均ENTITLEMENT_ERROR，无正文/通过判词、配置SHA保持；
数据初稿10226B本地大小门拒绝未发，仅缩范围/显示精度后发送，原分析不改。
没有切官方代做代码/数据审查。
保留NT-L4有限隔离成果：扩段可行及接续收益得到支持，整体搜索计算总成本下降尚未达标，
不接生产、不自动扩8或叠新缓存；若需长路线结论才补缺NT304母版与候选。
生产68/历史43/NS71/NT71、前轮非协调原件及原脏改保持，
协调更新前快照单列，本轮全部源码/构建/控制/计时/分析/外审回执封存。
无提交、推送、默认包替换或清理。第七十三节“仅成案”由本轮有限实施覆盖。

## 七十五、NT-L4原样复跑与负向定位

2026-10-23按用户“再跑一次看看是不是波动，如果负向持续确定一下负向的根因”，
完成[复跑及定位报告](../../reports/2026-10-23-detail-nt-long4-rerun-cause-zh.md)。
只新增一个原35ms六场候选性能JVM，原冻结build直接复用，无重新编译/性能基线复跑。
NT/NS/U/P/N五历史及NS/NTL原控制按身份复用，正式896/896到达，原分析/退出清理通过。
搜索1686→1622、partial/恢复仍22/22，64次长段全可达、0长段恢复；
扩段组59提前5延后，中位-1062.66ms、最大+103.97ms，wall合计-2.6972ms。

总search wall123.4408→134.4204ms(+8.89%)，CPU93.75→125ms（15.625量化），
搜索分配51.030342→69.351410MiB(+35.90%)；832未扩段请求贡献95.61%分配增量。
自然窗CPU-8.76%、分配-3.65%、长度-9.05%，非纯搜索归因；
全局tick最大58.6454、相邻间隔97.5903、drain39.0999ms。
首Path P95四降二升最大+2.41ms，到达差P95两降四升最大+1.68ms；
两corner最大约3秒及固定窗分配/局部峰值负项均保留，详见报告。

256/8 tile23slot1/5正式仍晚3040.81/2987.26ms、搜索2/恢复0且未扩段。
历史缺分配栈及通道坐标，补四诊断JVM，仍原六场顺序，明确不作性能比较：
NT/NTL各一TRACE+启动JFR/LogCompilation，NTL一DEBUG+LogCompilation/首场后JFR，
再一NTL完全原启动/第3场后JFR；均896/896、退出清理通过，无JFR DataLoss。
NT诊断走原候选长通道，NTL诊断走历史NT短通道，结构代价同2，
分别经section(8,5,-4)/(9,5,-5)，交接点(136,81,-65)/(143,81,-70)；
对应可见路径摘要逐项匹配，长版搜索仅0.0890/0.0660ms，秒级主要为绕行执行。
本轮未还原历史两条全部available批次，前轮批次机制不冒充本次完整触发证明。
该场自然窗78→117tick，多39tick硬占用wall+1300.65ms，
进程CPU+1390.625ms，search+0.90ms/drain并集-0.71ms；
不把wall和CPU硬减为精确分账，不称A*或派发秒级膨胀。

四诊断搜索分配约50~52MiB，但搜索数也有变化，禁止替换正式负项或与之平均。
最后一次启动参数归一化输出目录后与正式完全相同，artifact身份/模板/Java等匹配，
附加任何JFR前四场已为23.360016MiB，对NT23.530472、正式复跑31.489883；
128/8单目标平地由正式20800/首轮20912B恢复14272B，与NT一致。
说明高分配不是该JAR必然状态，也不能说全因启动采样；
低态编译记录有Region回调及包装数组消除，但未捕获高态，
分配点/对应展开量尚未闭合，不唯一归JIT、CallbackInfo或纯计时波动。
不再用完整矩阵追跑碰状态，后继若继续应针对高态分配取证。

保留有限扩段候选，整体计算降本/生产准入未通过；未恢复宏观质量改造或改业务。
本轮没有新外审通过判词，原provider权限失败状态不由官方替代。
生产68/历史43/NS71/NT71、前轮非协调原件及原脏改保持；
协调更新前快照单列，证据`.tmp/detail-nt-long4-rerun-20261023-v1/`另封存，
无提交、推送、默认包替换、接入或清理。

## 七十六、换线现象的历史起点核查

2026-10-23承接用户“先查一下为啥最近开始才出现换线”，完成
[历史核查报告](../../reports/2026-10-23-route-change-onset-history-audit-zh.md)。
只分析17个既有JVM，每个896请求，逐场起终点及DEBUG搜索覆盖核对；
无新性能/诊断JVM、构建或业务改动，不重跑有效基线。

17份实际游戏JAR与launch身份逐项匹配，整个MacroSearch类族及
TopologyWorkerRuntime类族均与旧P35逐字节相同。
P三压力档同JAR、B三档同JAR，Java/模板/原工具保持；
不同压力不是完整输入相同的复跑，不用来估计发生率。
10月21日P15与P25有24条首窗口摘要不同；
例128/28 tile4slot0全集16/2、首节点12/7，均宏观6连接。
同B在256/8 tile23slot1/5：15/35ms为9全集、首末9+12节点、约5秒，
25ms为11全集、5+6或5+5节点、约2秒。
这些早于NS/NT/扩段；旧DEBUG缺坐标，只证摘要/目标集分叉，不声称恢复旧完整通道。

同两条35ms历史：P5360.51/5259.76ms，U5148.44/5002.50，
NS2057.55/1952.80，NT2105.28/2011.82，
NTL复跑5146.09/4999.08。
最近相对短摘要NT出现约3秒负项，长摘要却与旧P/U一致；
不据此更换基线消除当前负项，也不把短摘要旧收益全部归于A*计算提速。
此前没有把旧值明确定位并报告为通道/目标分叉，不能说用户早已被告知。

Git核对部分邻接等待及同g前驱保留早在导入已有，部分parts/批量唤醒也早于近期候选。
前轮依赖批次单变量回放仍是有效机制证据；
本次历史具体批次未录，不能唯一归取消预算、NS、timer、扩段或其它时序因素。
也不能证明最近频率增加；共享/完成走廊复用可让多个请求采用同一结果，
但未定位焦点旧请求的具体共享来源。
搜索wall/分配增量继续单独未定位，绕行不能代替其根因。

生产68/历史43/NS71/NT71/工具44及前轮213非协调原件核对保持；
协调更新前快照与新分析另封存于`.tmp/route-change-onset-20261023-v1/`。
无外审、生产接入、默认包替换、提交推送或清理。

## 七十七、剔除换线路线后的条件性能

2026-10-23按用户“刨除换线的线路，整体性能如何”完成
[条件分析](../../reports/2026-10-23-route-filtered-performance-zh.md)。
只读历史NT、NT-L4首轮及复跑；不补测、不改业务、不替换完整896请求结果。
两端同时剔除256/8 tile23slot1/5确认换线请求，剩894。
复跑搜索1682→1618、partial均22；wall123.3206→134.2726ms(+8.88%)、
分配50.983681→69.218163MiB(+35.77%)，CPU93.75→125ms保留量化限制。
两剔除请求仅贡献wall增量0.0276ms、分配增量0.086586MiB，不能解释整体计算负项。
到达658提前236延后，中位-0.9525/P95+1.3573/最大+103.9679ms，
首Path自身提交差中位+1.0607/P95+5.3167/最大+14.4638ms。

64实际扩段仍59提前5延后，到达差中位-1062.6592ms；
搜索192→128、wall11.0276→8.3304ms(-24.46%)、分配+18.64%。
827未扩段且全部DEBUG摘要相同，搜索1484不变，
wall+12.08%、分配+37.35%；摘要相同不冒充完整宏观几何/输入/展开量相同。
再保守剔3条未扩段但摘要变动的diagonal首请求，剩891，
wall+8.80%、分配+35.76%、最大到达回退仍103.9679ms。
首轮894集合wall+22.86%、分配+34.48%、最大到达回退104.2008ms，
不平均两轮消除负项。

条件集合到达表现良好、有限扩段有价值，但整体计算降本仍未成立。
无>200ms回退仅针对该集合相对NT，不代表对原生的项目目标已完成。
整场CPU/共享工作/tick峰值不可按请求简单扣除，没有伪造剔后整场值。
生产准入/外审状态保持，证据`.tmp/route-filter-performance-20261023-v1/`；
生产68/历史43/NS71/NT71及前轮非协调原件保持，无新游戏/外审/接入/提交推送/清理。

## 七十八、剔除换线后的成本增量位置

2026-10-23按用户“能确定去掉换线后内存分配和搜索总耗时增加在哪吗”，
复用原894请求集合完成[位置分析](../../reports/2026-10-23-route-filtered-cost-location-zh.md)。
未扩段830贡献wall+13.6492ms、分配+17.430077MiB，
扩段64贡献-2.6972ms/+0.804405MiB；95.59%分配增量在未扩段。
827未扩段同DEBUG摘要仍+13.5345ms/+17.406662MiB，
摘要相同不证明完整输入或展开量相同，事后分组不当因果随机估计。
858/894分配增加，八类地形净分配均升，28格三场占78.02%。

首场64/8贡献wall+6.3693ms约58.16%；
平地tile0slot2从0.189→22.9621ms、26288→1464920B，
单墙tile2slot5从18.574→0.205ms、1468232→29688B。
两条相反的约1.44MB分配差合计仅88B，wall净+4.4041ms约40.21%总增量。
是一次性成本落点转移的线索，缺正式栈不确诊初始化/JIT/探针，
两条保留在正式合计，剩余仍wall净+6.5479ms，不据此删除负项。

现有计量为实际PathFinder调用及注入链，不直接含宏观队列、选点、
调用前region、能力还原或返回后proof本身；不排除它们通过输入/状态间接影响。
已有ZIP证明原生/邻域代码与探针保持，但不能据此排除调用分布或运行态变化。
128/8单目标平地一次调用14272→20800B，原启动诊断附加JFR前又14272B；
四旧诊断均约50~52MiB，没有正式约69MiB高态分配栈。
因此已定位请求/场景/计量范围，具体分配语句、调用链增量和展开量仍未闭合，
不能将低态BlockPos/回调/JIT记录冒充高态归因。
本轮只读后处理，无新游戏/构建/业务改动/外审/接入/提交推送/清理；
证据`.tmp/route-filter-cost-location-20261023-v1/`，生产与旧证据保持。

## 七十九、单次搜索节点缓存命中精简

2026-10-23按用户“这种一次性缓存没问题，研究并与1017形成方案后验证”，
完成[隔离报告](../../reports/2026-10-23-detail-node-cache-hit-isolated-validation-zh.md)及
[单表方案](../../designs/2026-10-23-detail-node-cache-hit-plan-zh.md)。
NCH复制冻结NTL，仅新增一个NodeEvaluator Mixin及清单项：
原hash查原nodes，值不同于defaultReturnValue时早返，否则原computeIfAbsent。
保留prepare.clear、原hash碰撞/null/default语义，不新缓存、不跨查询共享Node。
不改通行类型/碰撞表、目标、A*、窗口、worker或派发；
基类也影响飞行/游泳继承调用，没有全部整合包Mixin兼容资格。

官方1017两轮6266/4532B完整返回，选择A+单节点表候选而非两表混改。
方法资格不代替代码/数据审查；不采用两历史包络宽度作统计门。
原版/候选/实际转换字节码同Int2ObjectFunction重载，
命中返回在factory求值前，无CallbackInfo/Operation，纠正前讨论IntFunction误称。
构建及900000压力序列控制通过；实际Forge37项覆盖身份、hash碰撞、
BlockPos委托、prepare新Node、两evaluator、非null默认值及存null/default对象，
地面/飞行/游泳各一条真实PathFinder完整路径一致。异常/OOM未注入，不称测试通过。
正式旧276条目和探针45条目逐字保持，控制不进性能。

仅一个原35ms/sigma5/seed20261021六场候选JVM，复用两NTL及NT/NS/U/P/N，
没有新旧基线配对、15/25/304或同JVM复跑。896/896到达，搜索1626、
partial/服务恢复26/26，均比两NTL多4；64实际扩段全可达、0扩段恢复。
搜索wall140.2777ms，对NTL首轮151.6726少7.51%，对复跑134.4204高4.36%；
CPU140.625ms，高于109.375/125ms，保留Windows15.625ms粒度限制。
搜索分配51.382813MiB，对两NTL少25.20%/25.91%，888/896请求下降，
但旧同JAR低态诊断已有50至52MiB，未取得本轮分配点/展开量，不认全部由factory节省。
自然窗CPU39890.625ms，对两轮高0.24%/0.08%，分配少4.40%/3.88%；
完整tick/间隔/drain最大59.9256/101.7382/60.5116ms，负项保留。
相对复跑首Path自身配对差中位+2.5754/P95+45.7554ms；
到达差中位+1.2764/P95+52.0247/最大+3152.4753ms，终态中位+1.4428/P95+48.8167ms。
首场64/8全部64条到达延后，中位+52.0175ms，不以全局小差掩盖。

额外四次partial均256/8 corner tile23/31 slot2/6，首候选全集4→6，
首次partial后原恢复重搜，每条多一次搜索/partial/recover。
共15条DEBUG摘要变化，tile23slot1/5转短约早3秒，tile31slot1/5反向约晚3.1秒，
台阶也有range/末段节点变化，完整896全部保留。
已定位分支/摘要，不假称冻结完整输入或证明本轮全部异步触发因果；
不把这些秒级差计为缓存独占收益或纯波动。
分析初版误以仅长段执行恢复0标总恢复，v2按所有recover事件纠正26/22，
原初版留存并标superseded，不改原始测量。

原provider代码3577B/数据8058B均订阅权限错误，无通过判词，不切官方代审。
本版分配信号保留，整体提速及生产不通过，停止主动扩测，不自动叠其它缓存、
扩矩阵或调启发式救结果；不声称缓存路线整体没有价值或高态根因已闭合。
生产68项、原脏改、默认包和有效历史保持，无接入、提交推送或清理。
证据`.tmp/detail-cache-hit-20261023-v1/`另封存，协调更新前快照保留。

## 八十、NCH四次新增partial直接原因

2026-10-23按用户“先确认partial的原因”，完成
[直接原因报告](../../reports/2026-10-23-node-cache-partial-direct-cause-zh.md)。
只定位相对两轮正式NTL多出的四条256/8 corner tile23/31 slot2/6，
不外推其它22次partial同因，也不重做性能验收。
复用七个历史JVM，旧未改缓存NT及同一NTL包诊断均曾四条partial；
NTL original-late四条完整DEBUG窗口/搜索/恢复摘要与NCH相同。
旧TRACE显示四/六点是不同方向出口，结构代价仍2，不是同组多选两点或前八截断。
NCH当次依赖交付批次及坐标未录，宏观分叉具体触发仍有限制，不冒充完整输入已还原。

新只读探针固定旧TRACE的四组真实起点/六目标/range、原模板副本、
封存NCH实际包及Forge原生PathFinder；一个成功诊断JVM记录36次搜索，
每组实际NativeNavigationSearch1次及原版/候选getNode各四种控制。
原节点方法和候选完整路径、观测计数全部一致，实际外层与纯入口原设置也一致。
四条展开100/98/100/103远小于560，径向拒绝0、unknown=false；
累计长度拒绝176/179/176/188，最后均待搜索节点耗尽。
访问上限提高100000或只扩大region均仍partial；
相同宽region仅range改35全部可达，展开28/30/28/30，不作为推荐业务参数或性能对照。

直接机制是原生PathFinder先无条件覆盖邻点walkedDistance，
后判断新g/前驱；更差候选被拒时，前驱/g不变，累计长度却可被改大并继续传播。
slot2真实可达路径13.071068格，最后一步按19.656853裁掉(range19.401754)；
slot6真实12.071068格，按18.656853裁掉(range18.440306)，均虚增约6.585785。
实际记录最后目标边拒绝、返回前缀及源码/转换字节码写序；
未逐写记录最初覆盖来自何父节点，不伪造该细节。
因此不归缓存语义错误、访问上限、未知块、四连接扩段或纯计时波动。

下一窄修复研究应考虑临时候选长度仅在接受前驱时同步写回，
保留原范围策略；g一般含malus，不能简单替代长度。
本轮未实施修复、未外审、无新性能/基线JVM；首次小型诊断导出Class失败，
修正导出后第二次成功，失败源码/JAR/日志保留。
原NCH整体提速/生产未通过与全部负项保持，不自动恢复宏观优化或扩矩阵。
旧NCH314及NTL复跑213非协调封存项、生产/默认包/原脏改保持；
协调前快照与证据另封存`.tmp/node-cache-partial-cause-20261023-v1/`。
无接入、提交推送或清理。

## 八十一、raw搜索累计距离与接受前驱同步修复

2026-10-25执行用户“全部DeepSeek工作交由1017，先形成共识再修复”的授权，
完成[隔离修复报告](../../reports/2026-10-25-native-walked-distance-fix-isolated-validation-zh.md)及
[方案](../../designs/2026-10-25-native-walked-distance-fix-plan-zh.md)。
本日起AGENTS持久规则：DeepSeek方法讨论、方案/代码/数据审查等全部交由官方1017/max，
直到用户另行通知；覆盖旧按角色分流provider的约定。
官方模型发现使用deepseek-flash，返回名DeepSeek-V4.1-Flash，无独立1017日期ID；
实际别名/指纹/请求/响应均记录，不宣称别名是不可变日期版本。

NWD复制冻结NCH，仅新增NativeWalkedDistanceMixin和清单项。
候选长度先存临时值，原range条件读临时值，仅接受新前驱时同步写walkedDistance与cameFrom；
只作用项目raw查询，非raw保留原生语义，不用含malus的g替代几何长度。
三处精确FIELD Redirect及HEAD均唯一命中，父walked读取保留，
没有全循环Overwrite、每邻点回调/集合；helper和分支不是零开销。
范围公式/上限、访问上限、region、目标、权重、heap、闭集、宏观、worker、
调度和其它缓存均保持；不据此获得一般长度约束图的全局最优/完备承诺。

方法5228B正常返回形成共识；代码首审7100B因推理耗尽无正文失败，
第二包6102B正常返回ACCEPT、无MUST_FIX，失败响应保留。
实际包旧276条目保持，仅manifest/refmap/清单变并新增一个类；
探针44条目相同，附带refmap只增本Mixin映射。原900000负载序列检查通过。
独立Forge控制两个JVM正常退出，106项后补同instance与异常复用，最终116项通过。
四原捕获输入在原range下完整可达，slot2/6长度13.071068/12.071068格，
逐节点walked等于几何前缀；真实地面/飞行/游泳路径保持。
合成图覆盖较差g拒绝、超range更好g拒绝、changeCost接受、malus及严格范围边界等，
不冒充世界邻接/性能样本或全部整合包兼容。

仅新增NWD一个原35ms/sigma5/seed20261021、64/128/256各8/28格六场性能JVM；
复用八个历史性能JVM，未重跑有效基线或扩15/25/304矩阵。
896/896到达，NCH1626→1600搜索，26/26 partial/服务恢复→0/0；
对齐旧记录逐条确认26条均3→2搜索、1→0 partial与recover，
是计数观测，不宣称旧其它22条已经逐写还原与四焦点相同的污染链。
搜索wall140.2777→110.5274ms(-21.21%)，六场全降；
分配51.382813→37.837990MiB(-26.36%)，首64/8反升0.177025MiB；
CPU140.625→62.5ms、四降一升一平，Windows15.625ms量化限制保留。
自然窗CPU39890.625→40968.750ms，分配931.313103→980.880508MiB，
窗43179.8020→46406.0191ms；保留总量上升，不直接称单位时间回归，
粗归一CPU/分配下降也不作为受控降本证据。

对NCH首Path配对中位-4.1844/P95+37.4423/最大+43.4096ms；
到达569早327晚，中位-1.3286/P95+57.5852/最大+4489.7305ms，
终态中位-0.9555/P95+52.7125/最大+4504.2237ms。
128/28与256/28台阶六条晚约4.0~4.5秒，首次range38/39→41.54102/42.43835，
详细搜索前输入已变，不能以同跨度/候选数量证明完整输入相同；
具体依赖批次未捕获，不唯一归修复，也不作纯波动。
256/8 tile23slot1/5仍有9/11候选与9+12/5+5或6节点的旧通道变化形态，晚约3.1秒。
原四partial焦点tile23slot2/6早546.0560/303.2456ms，
tile31slot2/6却晚399.3876/247.0280ms；旧partial后恢复可能转附近目标，
少搜索不能保证相同走法与更早到达。
完整tick/间隔/drain最大55.5620/109.6996/62.7979ms，
对NCH分别-4.3636/+7.9614/+2.2863ms，所有峰值负项保留。

对历史生产P，893提前3延后，到达差中位-250.3919/P95-46.7796/最大+200.4470ms，
是多轮累计改动上下文，不能归本修复独占。
对原生仅802共同到达，差中位+116.6882/P95+411.0743/最大+4410.2756ms；
NWD另有94条到达，未到达不当0ms，不宣称整体200ms目标完成。
补充分析初版null到达问题由v2修正，P/N缺失drain当0问题再由v3修正，
旧件保留标失效，以fix-analysis-v3.json为准；正式comparison/summary及原测量未改。

数据7879B由1017正常返回，无算术MUST_FIX，认可有限修复结论；
按其口径意见用现有记录补逐请求及六场CPU值，不假称其独立看过本地文件或重跑。
本轮完成raw定点候选实现及隔离正确性验证，未证明稳定整体提速，
生产准入证据仍不足，无接入、默认包替换、提交推送或清理。
生产业务、原脏改与旧非协调证据保持；AGENTS仅用户授权规则追加，
旧前文和hash保留，routing-policy-transition.json明确例外，不能宣称它字节未改。
本轮证据`.tmp/walked-distance-fix-20261025-v1/`另封存，不覆盖旧NCH全部负项。

## 八十二、NWD秒级延后与条件集合分析

2026-10-25按用户“确定4.49秒原因，同时确定除开路线变化的数据”完成
[定位报告](../../reports/2026-10-25-nwd-route-delay-filtered-diagnosis-zh.md)。
直接复用NCH/NWD原正式35ms六场及旧NT/NTL TRACE，不重跑有效基线或新增性能矩阵。
原正式DEBUG缺逐节点路径，必要补两原包、同模板固定输入诊断JVM，各8查询共16，
不跑宏观或128实体行走、不作新性能基线；业务/候选/生产和默认包未改。

焦点128/28 tile12slot5，起点(16,81,-114)、目标(16,81,-86)，均两搜索、零partial/recover。
到达7257.6467→11747.3772ms(+4489.7305)，search0.2300→0.1688ms，
首Path39.5339→77.3138ms；首次range39→42.43835、末段3→15节点。
原localRange在详细搜索前按目标距离计算，首次输入已变；
同场完整tick/间隔/drain最大均下降，不支持秒级搜索或单次派发尖峰解释。
提交到达145→235tick，首移动到达144→233tick。
37.7799ms首Path差+1706ms首末搜索日志间隔差+2745.9506ms近似后段残差=4489.7305ms；
后段包含行走/接续/观察及日志publish边界，不作纯行走剖面。
首Path在tick主体外idle drain合法；首次分析body覆盖断言失败，改前最近tick起点映射，
无原数据修改，不把它当原tick记录缺失。

旧TRACE近侧目标x16/z-90..-83、远侧x31/z-90..-83逐项核对。
以slot1/5真实起点及对应旧末段起点重放，NCH/NWD每组完整节点序列均相同。
slot5近侧首25节点/25.656854格、末3节点/2格；
远侧首仍25节点但31.870057格、末15节点/15.656854格，range完全吻合正式摘要。
两段返回几何和多19.870057格，不等于实际行走总距离；原宏观批次/完整坐标/轨迹未恢复。
因此确认“目标/返回结构变化”及“旧包也能生成兼容的长路线”，
路线变化是强候选机制，尚不能严格确认它主导原4.49秒的份额；
不排除修复通过前序时序间接改输入，收紧中途“主因已确定”的表述。

两端按scene/tile/slot/start/goal剔同请求，规则只看结构不按慢多少挑选。
宏观DEBUG最终目标/endpointCount/connectionCount对896条全相同，完整组件链未录。
首window/search摘要改变28条，余868：搜索1568→1544、partial24→0，
wall135.5485→107.0715ms(-21.01%)、分配49.382736→36.055878MiB(-26.99%)，
到达中位-1.2750/P95+57.4726/最大+399.3876ms，553早315晚。
剩余两大延后256/8 tile31slot2/6为399.3876/247.0280ms，
是旧partial后恢复改附近目标与新直接走原目标的执行差，不称噪声。

全window/search/recover摘要改变58条，余838：搜索1484不变，
wall111.9729→103.3122ms(-7.73%)、分配36.449249→33.798676MiB(-7.27%)。
首Path差中位-4.1021/P95+37.3730/最大+43.4096ms；
到达535早303晚，中位-1.2624、均值+4.8223、P95+57.4668、最大+108.0904ms；
终态中位-0.8784/P95+52.6598/最大+138.5872ms。
条件内搜索wall五场降、一场128/28反升1.6682ms；
首64/8分配反升0.177025MiB，186请求wall/63请求分配上升。
平地tile0slot4增加24.3345ms/1444136B，slot0反降22.2278ms/1438224B，
仅作一次性成本落点线索，不确诊JIT/初始化，冷点均保留。

严格58剔除包含26修复及6条到达提前的返回节点变化，不能称只删除负项，
也不能用治疗后条件选择来估计整体因果收益；同摘要不证明路径坐标相同。
26修复组另存：78→52搜索，wall22.4099→3.1078ms，分配12.177391→1.841019MiB，
到达14早12晚，中位-3.3184/P95+247.0280/最大+399.3876ms。
自然窗CPU/共享工作/tick峰值不能按请求扣除；
128/28窗多1695.2830ms/34硬占用tick，hard wall多1178.1750ms、CPU多1453.1250ms；
256/28窗多1647.2707ms/33tick，hard wall多1151.7063ms、CPU多734.3750ms。
两场search和drain并集均下降，hard wall与CPU不直接相减作精确分账，整场原负项完整保留。

1017/max数据包8414B完整返回，认可机制和条件分析价值、反对升级为原场主导份额已确定。
CPU疑问经源码与原记录复核：PathFinder per-mob begin/end当前线程计数，
被A剔28条两边全为0，故子集/全量记录和相同合法；
全量仅NCH8条/NWD4条非零，粒度15.625ms，不据此称精确CPU收益，主条件表移除CPU。
三项时间公式完整列明；保留首Path/到达均值与尾部负项，不据中位略早称全面改善。
本轮仅完成有界定位与条件分析，未证明稳定整体提速、原生200ms或生产准入，
不为还原已结束原轨迹反复跑矩阵。
原NWD335非协调封存项、生产保护链与其它历史保持；
证据`.tmp/nwd-route-delay-diagnosis-20261025-v1/`另封存，无接入/提交推送/默认包替换/清理。

## 八十三、108毫秒延后的详细路径与执行定位

2026-10-25按用户“定位这100ms的原因”完成
[执行定位报告](../../reports/2026-10-25-nwd-108ms-execution-cause-zh.md)。
焦点为前轮全部阶段摘要相同838条中的256/28 single-wall tile18slot6，
起点(-61,81,-46)，最终目标(-61,81,-74)。
原正式到达6762.5756→6870.6660ms(+108.0904)，search0.1651→0.1246ms；
首Path38.6048→31.4980ms(-7.1068)，首Path至末搜索94→97tick，
末搜索至观察到达41→40tick，提交至到达135→137tick。
两次搜索日志间隔差156ms、近似后段残差-40.8028ms，
三项合计108.0904ms；名义2*50ms加8.0904ms节奏/相位残差，
不是额外100ms A*工作或独立8ms算法成本，搜索wall下降不支持这种归因。
末搜索tick映射在±2ms锚点偏移下稳定，原整次drain仅0.3091→0.3280ms。

历史性能与基线保持有效、直接复用；原日志缺完整节点/移动/完成派发边界，
故仅补5个必要诊断JVM，不新增性能样本或重跑矩阵：
两原包各一个原35ms/256/28场景执行诊断，两个小JVM各4次固定输入raw查询，
以及修复后固定焦点朝向为修复前实际朝向的一次执行诊断；修复前诊断直接复用。
执行诊断加只读探针、略去前五场，第三场还固定朝向，
不能拿其firstPath或到达wall替换正式性能值。
原包/原探针字节身份保持，候选业务源码和生产未改。

新诊断的首段起点精确同(-60.5,81,-45.5)、八目标顺序同、
range27.416489、20节点；修复前绕东侧，交接点(-57,81,-65)，
修复后沿西侧x=-65绕行后转入(-64,81,-65)。
两次末段仍9节点，原所有window/search摘要可保持相同而实际路径不同；
838条只能称同摘要条件集合，不能称已经排除完整换线。
两原包固定同起点/八目标/两朝向，旧均东、新均西，完整节点跨版本不同；
东侧末段输入和西侧末段输入各自回放时，两包完整9节点路径相同，
这不代表两次实际末段输入或路径相同。
累计距离修复通过父节点存储长度影响后继range接受，可改变已可达查询的选路；
该复现不需宏观时序解释，也不是新改宏观算法。

修复前、修复后、修复后固定相同朝向：
首搜索至首段完成94/97/97tick，末搜索至观察到达41/40/40tick；
首次搜索至到达135/137/137tick。
完成至末搜索均0tick，完成观察至搜索0.8740/0.7956/0.5798ms，
其中含其它实体的服务观察，不是独占队列延迟。
stable>90、grounded、无待窗口/待搜索；末段nextSegment不准备worker窗口，
无预算drain同次继续处理settle→nextSegment→findSegment。

同朝向新路径墙角第32/33tick末均Z=-50.699999988，post-Z速度0；
第32tick Z位移-0.088323，第33tick Z位移0，第34tick恢复-0.100169，
之后速度重建。冻结墙体x=-64..-58/z=-52，墙面余量吻合，
支持碰撞裁剪推断；未记录collision标志/具体调用，
不把两个tick说成全部不移动、不把所有首段+3tick唯一归给该角。
首段观测位移20.485239/94tick→20.602496/97tick，
平均每tick0.217928→0.212397；末段8.960833/41→8.650523/40，
终止观察位置不同，不作全程几何更短的证明。
返回节点首段几何20.656854→21.071068、末段9.656854→9.242641，
总和同30.313708格，不能只用总路长解释运动时间。

1017/max两轮自含8062/4525B完整复核，采纳MUST_FIX：
区分firstPath与首段执行、tick名义量与算法成本；
同摘要不证明同路径；末段同输入跨版本相同不代表实际末段相同；
局部碰撞归因是推断，原历史轨迹未记录，不能说已唯一还原原108.0904ms。
已复现的是详细路径改变与该场景执行tick差，
它是当前证据下与原108ms最一致的候选机制，不宣称原历史逐毫秒已唯一定位。
保留正确性修复是工程选择，不由单条数据自动批准生产或回退raw语义。
全896结果、原自然窗负项及生产准入未取得状态保持。
证据`.tmp/nwd-108ms-cause-20261025-v1/`另封存，分析v1/v2及三版探针包保留；
无生产/候选业务修改、接入、默认包替换、提交推送或清理。

## 八十四、相对原生P95尾部的时间定位与交接质量假设

2026-10-26按用户“最大延后暂按换线算，重点关注P95的411.07ms原因”完成
[定位报告](../../reports/2026-10-26-native-p95-arrival-delay-diagnosis-zh.md)。
最大暂按换线是工作假设，不改变正式统计或将历史通道差异升级为已证事实。
直接复用原35ms/sigma5/seed20261021的64/128/256并发各8/28格六场，
原生802/896、本轮896/896实际到达，只配802共同到达，不把原生94缺失记成0。
每场一次，无新性能JVM、新基线或矩阵复跑。

逐请求差中位+116.6882ms，P95最近秩rank762为256/8门口tile6slot4，
起点(93,81,-126)，目标(93,81,-134)。
原生首Path0.2092→候选16.6120ms、首动25.0379→49.4539ms；
首动至到达2573.9969→2960.6552ms，到达2599.0348→3010.1091ms。
24.4160+386.6583=411.0743ms，首动后约占94.1%；不叠加首Path这个不同分解轴。
search累计wall0.2009→0.0587ms、次数1→2、partial/恢复两边零。
提交至到达52→60tick、首动至到达52→59tick；
400ms仅名义8*50，实测事件相位差10.3204ms、tick起点节奏相对名义偏差0.7539ms，
三项只作时间记账，不标成算法工作。
两次搜索结果日志733ms墙钟间隔与首段执行重叠，不是CPU或队列等待；
末次搜索近似映射15tick后、随后45tick到达，±2ms锚点偏移不变。

P95及以上41条全部首动后增量大于首动前增量，
首动后增量最小257.8854ms、首动前增量最大200.1931ms。
各自候选PathFinder累计wall最多0.436ms、最大增量0.023ms，
只能排除自身搜索计算直接解释，不能排除共享工作、观测、碰撞或接续影响。
139条到达延后>200ms，其中125条首动后增量更大，14条不满足；均原生1→候选2搜索。
分布为斜线49、转角29、门口27、两级台阶18、单墙9、交错墙7。
192条候选一次搜索P95为143.5191/最大147.6121ms；610条两次搜索P95为469.6194ms。
两组地形/长度不同，不作受控消融或分段次数因果证明。
仅描述性去掉12条>1秒，余790条P95仍398.0940ms，不取代全量或证明这些均为换线。

原生历史没有完整节点/移动轨迹，因此不能恢复当次连通线或严格分账。
仅因这些必要数据缺失补两原包诊断：原生一个、本轮候选一个，
原35ms/256/8单场景和原正式业务/探针JAR，加同一个只读观察mod。
观察既有门口tile6slot4、斜线tile9slot1、转角tile7slot6，
START及END前后记录path节点/索引/done、position/velocity、queued/preparing/stable、
组件进入证据、horizontalCollision。无导航状态修改，精确初始XYZ同，yaw只记录未控制。
省略其它五场、初始化历史和观察负载不同，诊断wall不替正式性能。

三例原生节点11/8/11，候选4+10/6+4/4+10；
节点边长和12.071068→13.242641、8.242641→9.242641、12.071068→13.242641格。
首动至到达52→59、34→41、52→59tick，三例均+7；
首动至观察到达的采样移动距离11.098248→11.972729、
7.307648→8.667531、11.098691→11.972639格。
原生门口从起点立即斜向绕障碍，候选先沿x93直走到z-129的跨界点后再绕；
转角是平移情形，斜线同样先直走临时目标后再斜向。
斜线原生返回(-97,81,-115)，候选(-97,81,-116)，同目标/accuracy1但接近不同。
返回边长和不等于实际总距离，采样区间到达点不同，不能用距离除速度精确解释7tick。
门口/转角collision标志1→3tick、斜线0→0；三例无整tick静止，
不把collision等同完全停住，也不唯一归因端口或碰撞。

三处第一段done时均grounded、stable13/22/13，组件进入证据存在，
没有queued/preparing/waitingSearch，同tick END后第二段已发布。
观察到发布2.9150/1.1826/2.9587ms含其它实体服务，不能称每请求独占队列耗时或零成本。
末段不准备worker窗口，observe→settle→nextSegment→findSegment就绪链由无预算drain继续处理。
stable不是每段结束后重新等3tick。
这些只限制三处诊断的跨tick等待，不外推所有历史请求。

当前选点仍是起点平方距离+0.05*终点平方距离的最多八点；
原生多目标搜索不计算到最终目标的整条剩余路线，局部交接目标与整体走法可能冲突。
三例结果与这种路径质量损失假设一致，不称已隔离端口因果或恢复历史P95机制。
生产及修复前候选同三例已有相同window/search摘要；
本轮对生产到达仍早98.7173/93.1336/98.7596ms，
对修复前候选仍早1.6252/1.4696/1.5753ms，不归新修复独占。

1017/max8455/4297B两轮完整审查，采纳：
时间域定位与算法成本区分；名义tick与实测工作区分；
三例未控yaw/碰撞/末端混杂，只称与交接质量假设一致；同tick不等于零排队；
最后跨界直接指向最终目标只列待验证假设，先核对range/visited/validation及最终目标语义。
官方模型发现实际deepseek-flash/DeepSeek-V4.1-Flash，无独立1017日期ID；
请求与实际指纹保留，不冒称不可变日期版本，不取得生产批准。
本轮完成有界定位，不开始新候选、不宣称稳定P95已达标，不再补游戏矩阵。
证据`.tmp/native-p95-delay-20261026-v1/`另保存，原正式数据及生产保护链保持；
无生产/候选业务修改、默认包替换、接入、提交、推送或历史清理。

## 八十五、最后跨界段直接搜索最终目标的隔离修复

2026-10-26按用户“好，你修复一下”，在累计距离修复后的冻结候选上完成
[最后目标合并修复](../../reports/2026-10-26-final-target-merge-isolated-validation-zh.md)，
对应[修改计划](../../designs/2026-10-26-final-target-merge-fix-plan-zh.md)。
问题不是只有8格路线才发生：普通组件分支即使本段覆盖所有剩余宏观连接，
仍先到最后端口再另搜最终目标。此前扩段合并中间连接有效，但没有合并这个末尾步骤。

仅改隔离`SegmentedNavigationService.java`：保留最多四连接及原section门；
stageEnd已覆盖全部剩余连接时，使用真实最终目标、原accuracy/range，
不准备最后端口window、不枚举/排序端口、不追加最终段。
中间段及shared/非地面分支保持，不无条件把所有路线扩成整条搜索。
未发布合并查询实际失败可否决一次，保留attempt/stride/failedTargets和执行恢复预算，
真实搜索仍计数、不扩大原上限；回原cursor端口方式，请求内不再合并。
已发布合并路径用committed标志保证实际失败从当前位置重搜终点，
不回旧cursor端口；新宏观重规划才清committed，不提前推进宏观cursor。
最终仍须原physical/finalArrival，未伪造组件到达或目标可达证明。

方案1017/max5023B，采纳未发布否决与执行后恢复分开；
代码9105B首审唯一阻塞猜测updateEntityTarget依赖last，
实际函数及request/Accessor源码无这种读取，3466B专项明确撤销并ACCEPT。
中间10307B复核耗尽65536token、无正文，失败保存、不算通过。
独立真实Forge一个JVM、78断言通过，含真实range3失败/35成功、
受控失败/取消/发布后恢复/物理终点判定和八连接中间段保留。
抽象走廊与受控返回如实标注；退回控制到原端口准备入口，不称全地形端到端证明。
首轮控制编译包内API错误只修夹具，失败日志保留，业务源码未再改。
新包只改变服务及Work类和manifest，277其它条目、45探针条目保持。

仅补新候选一个原35ms/sigma5/seed20261021六场性能JVM，
64/128/256并发各8/28格，共896请求；旧八版本有效历史复用，零新基线。
启动和场景30秒门、接口/完整场景/起终点/Java/模板/负载公共前缀一致，
无其它Java干扰、完整关闭及服务清理通过。每场一次，不是多轮平均。
896/896实际到达；896独立query ID逐条calls=1，搜索次数1600→896，
704跨组件末段合并均可达，零partial、零合并否决、零执行恢复。
这组性能没有需要保留中间段的请求，不外推所有长路线不再分段。

同一802原生共同到达集合，原生到达值逐条保持：
延后中位116.6882→60.5981ms、P95 411.0743→138.5555ms、
最大4410.2756→217.3150ms；50早752晚，17条>200ms全在首64/8，
该场自身P95仍208.5789ms，不能只报全局低于200。
原411ms焦点256/8门口tile6slot4由3010.1091→2663.4684ms，
相对同一原生2599.0348ms现在+64.4336ms；搜索两次→一次。
原生94未到达不记零，不推断这些实体的原生延迟，不按换线删负项。

对母版全896：702早194晚，中位-52.8612、差值P95+1.8396、
P99+46.2349、最大+54.4529ms；首Path差中位+5.5260/P95+16.7786ms。
对当前生产全896：892早4晚，中位-350.7266/P95-54.5330/最大+1.1701ms。
802内X=母版-原生、Y=本轮-同一原生、Z=本轮-母版，逐条Z=Y-X；
X/Y的P95前后下降不等于Z的P95为负，802内Z P95仍+1.9698ms。

全部负项保持：搜索wall110.5274→123.1280ms(+11.40%)，四场升两场降；
搜索分配37.837990→55.011742MiB(+45.39%)，六场全升。
完整tick最大55.5620→58.7236ms，六场峰值均升；
tick起点间隔最大109.6996→96.9645ms，但六场五升一降；
drain最大62.7979→49.2974ms，不能混为单次搜索成本。
64/28自然窗CPU仍增加312.500ms。
总自然窗CPU40968.750→31109.375ms、分配980.880508→718.034096MiB，
同时窗长46406.0191→33924.7969ms，含硬占用/行走，不当等工作量纯导航降本。
搜索CPU62.5→125ms原值保留，只有4→8个actor各计15.625ms；
四历史/当前版本都有actor CPU>wall，结合原探针读数顺序属粗计数限制，
不称CPU真实翻倍，不因该现象重跑基线或伪造并发搜索。

按实际合并query ID分层：直接704条629早75晚，
到达中位-54.2858/P95+1.3675/最大+54.0067ms，
wall+11.4306ms、分配+15.427994MiB；未合并192条另保留，
分层不是随机消融，不能唯一分账共享调度或运行态。
生产分配净增+7.797264MiB按未舍入六场值闭合，展示末位允许舍入差。
生产704个请求共少808次调用，不把请求数与调用数混写，也不全归本修复。

数据1017/max9274/7193B两轮，首审混淆前后候选与相对原生集合、
混用数组列及CPU计量后，补同802身份/代数恒等式、全精度分配等式、
原CPU探针和四版本粗粒度读数、704/192分层予以澄清。
专项接受有限结论，条件性要求的896调用直方图、独立query和17尾部清单，
均由原记录写入final-review-evidence，无新增JVM或测试值修改。
本轮完成末段合并修复及单轮有限验证，不认定全请求≤200ms、稳定性能或生产准入。
证据`.tmp/final-target-merge-20261026-v1/`封存，源码/构建/原数据/失败审查均保留。
生产业务、原worker脏改、默认包及有效历史保持，无接入、提交、推送或清理。
