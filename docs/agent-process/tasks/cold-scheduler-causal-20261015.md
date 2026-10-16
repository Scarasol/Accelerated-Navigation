# 启动期预算历史与负债研究

用户要求继续研究。新增源码、脚本、运行数据全部隔离在 `.tmp/cold-scheduler-causal-20261015/`，生产代码、正式测试树、参数及持久化范围保持；不提交、推送或部署。

## 当前执行

本轮研究验证及 DeepSeek 共识已结清。[完整数据报告](../../reports/2026-10-15-cold-scheduler-causal-validation-zh.md)。全部36个实际运行保留，进程已退出；生产代码、参数和持久化保持，没有提交、推送或部署。

`tools/run-campaign.ps1` 的22轮串行矩阵已完成，会话 `84305` 正常退出。固定候选包、Base 路线、第30 tick提交，4×4平衡顺序16个主要JVM，另3详细审计normal、3原v4参考。随后两轮接线及八轮同包持续期限补充完成，会话 `59939` 正常退出。原定方案、顺序、脚本和源码身份均保留，不覆盖。

新探针 `instrument-v2/probe.jar` SHA256 `B9A4C2626EA27C973083A15BACC6B2046D42B61592B948062BCEE641F64A5040`。生产包仍为 candidate-v5，SHA256 `2D9DF2FF444A397574F16D5DBC87E94276DE4B1F074DA42D9354AFBE437572E0`；冻结核对生产源码/class 273 项。源码/资源及构建入口 63 份已冻结，后续不可无版本修改探针源码。

四种研究干预：normal / 首次真实 queued END 单次负余额补零 debt / 仅首次派发前排除全局有效序号 1–8 的基础 p95 history / both。其他 EWMA、tail、previousFull、实际计费、实时截止、haveTime 和最小片门槛保持。外部信用独立记录，不能冒充 navigationNanos 减少。

## 已完成资格

- `instrument-v1` 详细接线通过；为补充累计计费和 inEnvelope 元数据，另冻结 v2，未将其混算矩阵。
- v2 `mechanisms-v2`：真实 Scheduler/Controller 固定时钟 79 控制通过，复用旧 `budget-controls.json` 的生产预算控制也通过。
- 固定时钟四组两次重演，首次派发为 normal42 / debt39 / history34 / both31 tick。时钟显式推进非导航 47.5ms、后续 4ms，真实原子调用再推进 0.2ms；另核 haveTime=false 和截止仍拒绝。不是墙钟性能结果。
- `pilot-normal-v2` / `pilot-both-v2` 各 11 原控制通过；原请求时间线读取 254 项、12 类损坏拒绝；研究读取 1,959 项、11 类损坏拒绝。
- 未变换生产 Controller 单独主机重放逐 ns 一致，两个试运行为 644 / 630 项。主机源码 `tools/ControllerReplay.java`，类输出 `host-classes`；使用已有完整 test runtime classpath，主机不装 Mixin。

## 接线初步发现

v1 详细审计：无请求的首 tick 导航维护等计费 8.8657ms，授额 4ms，余额 -4.8657ms。提交前债务已在，tick30 的 intent + move 又计费约 9.2396ms。栈在 outer account 关闭后采集，审计有额外开销，不能称纯生产 CPU。

v2 normal 首 Path 758.2040ms，首次排队 561.2875ms，tick42 派发。both 首 Path 714.7908ms，首次排队 60.6126ms，tick32 派发，但后继第二次排队 486.6048ms。原因证据：首次派发后按计划恢复原 p95，授额重新为零，新增实际工作债务继续保留。此结果证明首派发改善不等于整体修复；不能隐去后续等待。

## DeepSeek

实际 WSL `/usr/bin/dsh --profile headless`，provider `deepseek`，model `deepseek-v4.1-flash`。每包 ≤6,500 字节，明确只读本包、不追读工作区。`scheduler-plan-v1` 提出三项必改：有效记录定义、固定顺序、单次信用后续轨迹。修订方案 v2 5,137 字节 `ACCEPT_WITH_GATES`、本轮必须修改项无。

v2 门禁已写入[研究方案](../../designs/2026-10-15-cold-scheduler-causal-plan-zh.md)：核对 p95/EWMA 实际绑定、保留补零后的再次负债轨迹、说明固定时钟推进和实时截止控制、禁止外推生产修复。性能实验期间不并行模型审核。

## 剩余

本轮无待完成实验或审核。下一研究问题为启动历史恢复判据、持续过载保护、并发竞争和长时安全；固定排除前八记录及一次补零均未获生产批准，不能直接接入。

## 已完成结果

主矩阵normal/debt/history/both首Path等待均值为555.8735 / 571.2891 / 408.8169 / 405.2520ms。normal与both首次排队均值348.7620 / 12.9330ms，但第二次排队53.0944 / 235.4630ms；只提前首次派发会留下后继等待，debt仅有限单次剂量不能否定债务参与。

追加同新包transient/hold各4次，固定AB/BA/AB/BA，首Path等待均值481.7543 / 265.8248ms，第二次排队299.7054 / 74.9744ms。transient重复4为668.7800ms，全保留；tick32–38原p95>45ms、grant0，新增工作债务保持。结论仅为与持续干预一致，不称因果效应或稳定性能。两组实际信用剂量不同，不能称等剂量。

新包`hold-instrument-v1/probe.jar` SHA256 `7C35433DB9868DBEF474C360C0D0B2F693991F35CC4D6E67EDBE34EC5F7D95E6`；77个ZIP内容仅SchedulerResearch.class变化。新增原型、补充读取及源码全在`.tmp`，正式测试树未新增。

主矩阵及审计的研究读取10,660、时间线2,230、原Controller条件重放10,822；参考读取364。补充八轮3,756/898/4,270通过；独立31JVM单次信用账本4,085检查、4类损坏拒绝，19个有正信用JVM各一次有效注入，其余tick残差0。JS独立验过滤p95，原class在临时selected输入下choose后还原ring，再record真实输入，不能称原生过滤或未来动态反事实。

## 终审与收尾

八个现行包为：scheduler-plan-v2、scheduler-hold-plan-v1、scheduler-interventions-v2、scheduler-ledger-v2、scheduler-replay-v1、scheduler-main-evidence-v2、scheduler-hold-evidence-v2、scheduler-integration-v1。全部实际ACCEPT_WITH_GATES、必须修改项无，正常退出、无超时。每包独立且3,405–5,995字节。

计费v1首包遗漏原读取器已有归零断言，提出REQUEST_CHANGES；v2补齐完整生产者、原断言及新增只读逐tick信用残差等价核对，原必改已由DeepSeek明确结清。接线/实测首包的源码、轮次映射、观察延迟和完整分段门禁均补证；旧五包原件保留。

`closeout-v1.json`最终1,250项核对通过、记录1,617条身份，生产273项保持；36实验JVM与本轮dsh全部退出。settings SHA256仍为`071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858`。有效包映射为`review-effective-v1.json`，数据汇总`summary-v1.json`/`summary-hold-v1.json`，独立信用验证`credit-ledger-v1.json`。
