# 冷首请求时间线验证

用户授权继续验证首请求预算、排队、构建与发布，并在验证后分批送 DeepSeek 达成共识。本轮原型已从正式测试树迁回 `.tmp/cold-request-causal-20261015/`，三个正式入口与本轮前冻结副本逐字节一致。生产包仍为冻结 da55e12 / candidate-v5；持久化保持暂停。

## 进度

| 阶段 | 状态 |
| --- | --- |
| 请求、future、调度、Path 装入时间线探针 | 已实现 |
| 基线 Super / 新共享分支接线试运行 | `pilot-v4` 两轮通过，读取 231 项，12 类损坏输入均拒绝 |
| 两版 × 三路线 × 30/100 tick × 两次重复 | `matrix-v1` 24 轮通过，264 控制、读取 2,654 项、12 类损坏拒绝 |
| 同包关闭阶段/时间线控制 | 6 轮通过，66 控制、读取 174 项 |
| 原型隔离、更正正式入口及新包接线 | 67 个 ZIP 项内容相同；新包两轮接线、读取 217 项及 12 类损坏拒绝通过；正式 testClasses 编译通过 |
| 分批 DeepSeek 审核及综合共识 | 六个现行专题/综合包通过，必须修改项无；scheduler v3 撤回误判，evidence v4 完成离群轮与关闭口径补证，integration v2 综合接受 |
| 文件身份、审核传输、settings、进程收尾 | `closeout-cold-causal-v1.json` 737 项通过、804 条文件身份，全部实验和审核进程已退出 |

执行器：`.tmp/cold-request-causal-20261015/tools/run-cold-timeline-campaign.ps1`、`run-cold-timeline-controls.ps1`。独立读取器同目录 `analyze-cold-timeline.mjs`、`analyze-cold-timeline-controls.mjs`。原 30 轮实际执行源码保存在 `before-migration/`，不改写历史 manifest。完整数据表见[验证报告](../../reports/2026-10-15-cold-request-timeline-validation-zh.md)。

证据根 `.tmp/shared-topology-production-20261015/`。当前探针冻结 `cold-timeline-instrument-v4`，SHA256 `1A23AFEFFA378D636931AB6ACD0525720BD9FF82FEEE550BA011746B8166A122`，273 项现有生产源码/class 身份核对通过。矩阵原计划及脚本身份在 `campaign-cold-timeline-matrix-v1.json`，控制列表另记 `campaign-cold-timeline-controls-v1.json`。严格使用新目录，不覆盖既有结果。

## 试运行失败记录

- v1 shared-base：新增 Mixin 回调只捕获部分参数，实际注入要求完整参数签名，已修正；不是生产缺陷。
- v2 shared-base：11 控制通过，读取 128 项；基线 Super 因缺少新共享 API，Mixin 在找目标方法阶段失败，`require=0` 不足以跳过缺失方法。未执行排在其后的 v2 shared-same。
- v3 baseline-super：ModLauncher 不支持请求未变换字节码，探针 plugin 初始化失败；改用受支持的默认字节码读取入口。
- v4 baseline-super / shared-same：11+11 控制通过，原始退出成功、PID 已退出。首 Path 分别 870.9714 / 464.1329ms；这是接线资格试运行，不混入计划矩阵。

失败目录、构建记录和冻结源码均保留。v4 同包观察仍有探针开销；额外 6 轮只关闭阶段与时间线，公共 full-tick/dispatch 仍保留，不能称零开销或精确估计测量成本。

迁移后探针 `0196819B`，逐项内容等同 v4；两轮新包接线首 Path 568.0605 / 413.1306 ms，不混入主矩阵。隔离构建配置的两次失败保持于隔离目录，最终 `build-v3.log` 成功。恢复入口的 `restored-test-build.log` 成功，正式输出无本轮时间线 class/resource。

## 现行审核与主要结果

实际 WSL `/usr/bin/dsh --profile headless`，provider/model `deepseek/deepseek-v4.1-flash`。现行六包为 scheduler-v3 / futures-v1 / partition-v1 / inputs-v1 / evidence-v4 / integration-v2，分别 4,075 / 5,469 / 6,459 / 5,192 / 4,922 / 5,853 字节，全部 ACCEPT_WITH_GATES、必须修改项无。settings 始终为 `071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858`。映射 `review-effective-cold-causal-v1.json` 只选择现行轮次，最终收尾另逐一检查有效答复、实际传输及退出。

原调度 owner/资格守卫两条疑点通过真实提交点补证撤回，未改正确生产语义。数据包补证说明共享 Super 较慢轮总差 229.5220 ms，其中首次排队差 228.8298 ms；较慢轮保留。关闭六轮仅共享版，不能与基线相减估计探针开销；该误用比较已明确撤回。HTTP 500、连接错误、未完成/主动终止的空答复全部保留且不计通过，详见报告。

30 tick 各格首次排队均值约 312–434 ms，100 tick 约 1–8 ms；实际 queued 停派发的零授额为 4–9 次与 0 次。观察支持继续查启动期负债和派发条件，但 100 tick 也改变 JIT/预热/负载，不能当作预算单变量。没有据此修改生产预算、代批验收或宣称共享稳定收益。本轮没有提交、推送或部署。

## 结论边界

互斥服务边界可还原首请求墙钟，但候选异步段不是纯 CPU，也不是每个 Base/Super 图构建的关键路径。30/100 tick 对照同时改变 JVM、后台预热和负载状态，不能当成仅改变预算的因果干预。样本仅单实体固定平地，不批准稳定性能、预算参数、新算法或全面共享。
