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
