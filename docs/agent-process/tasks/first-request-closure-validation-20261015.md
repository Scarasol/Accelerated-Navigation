# 首次请求依赖闭包候选验证

2026-10-15 用户授权开始验证；前置及DeepSeek READY_TO_VALIDATE已结清，原件在`.tmp/navmesh-prerequisites-20261015/`，不重写旧summary。

本轮目录`.tmp/first-request-closure-20261015/`。**当前状态：性能矩阵按停止门中止，未取得性能资格；不是候选通过，也不是候选算法失败。** 预冻结135 JVM，实际103轮运行检查通过、1轮auto基线派发前失败、31轮未执行；候选实际运行35个性能JVM。DeepSeek两个独立包认可中止处理及未测值纠正，必须修改项无，最终证据为performance-abort-v2.json和performance-abort-review-seal-v2.json。不得恢复旧矩阵凑满135，不进入Portal或生产；当前恢复点见末节。

候选只对原应Super且父图冷、首Path尚未发布的本请求使用既有Base前沿；热父图保持自动分支。首Path后后续新宏观请求回自动选择，不混拼搜索状态。搜索上限失败可单次新建Super，该候选waiter拒绝第三次Base，普通消费者保留原后备。仅策略复用，不称新算法。Portal骨架尚未加入。

所有新算法和探针在.tmp；原生产源及正式test包本轮不改。场景就绪真实30秒后执行。先确定性正确性门，之后同冻结平衡交错顺序的auto/base/candidate与detail/off/plain性能比较，覆盖1/128/256/512逻辑并发；后继等待和真实到达必须记录，详细诊断不能代批真实收益。

早期接线和功能样本在v1保留；warm完成走廊缓存可零新物理查询，必须有公共completedCacheHits增量。历史构建仓库解析失败日志保留，后续使用offline缓存构建。同包控制曾放probe jar导致contracts-v2 split package，现真正编译源为contracts-main，probe排除com/**；contracts-v3曾遗漏逐section UNLOADED的真实事件顺序，失败原件保留，后按真实顺序复验。

DeepSeek code-scope-v1以3888B、71.014秒接受范围；code-fallback-v1以3777B、37.223秒要求修复Base→Super→Base。现已修正、复验并在后续包解除阻断，不能再沿用旧v3“全请求4次”表述。实际provider=deepseek、model=deepseek-v4.1-flash，WSL原生dsh headless、每包<=6500B、900秒本地截止、未改settings。所有旧包与失败原件保留。

## 先行恢复记录（历史）

以下保留各阶段当时的代码、控制与执行状态；“正在执行”等历史措辞不能用来恢复旧矩阵。最终发行见下一节，当前中止状态见末节。

当前候选源码为plain-main-v4/instrumented-main-v4。每input attempt最多2次canonical flight选择，全请求最多3次；原一次stale重试仅重置阶段计数，不重置总次数和triedDeferredParent。缓存命中不计新flight，16384仅是每个内部MacroSearch上限，不能声称全请求visited<=32768。

contracts-v4-1及最终cap3的contracts-v5-1均9组70断言通过；v5在启动后30.0041543秒执行。包含真实共享flight、候选拒绝第三次Base/普通waiter独立结算及实际失代后attempt1两flight、attempt2一flight共3次。故障钩子默认关闭，不得进入性能样本。真正编译控制源为contracts-main，src/com旧副本被Gradle排除。

DeepSeek code-fallback-v2以5851B自足摘要、59.367秒正常给出ACCEPT_WITH_GATES/必须修改项无，明确解除v1后备阻断。仍要求默认上限无故障钩子复验、prepare→adopt、复杂几何自然移动和真实发布主线程回归，不批准候选或性能通过。所有送审原件保留。

functional-v2.json为24轮计划，前5轮auto flat、candidate flat、auto warm、candidate warm、auto stairs通过。余19轮尚未运行。warm零物理查询有公共completedCacheHits增量佐证。台阶4个无start事实结束为arm前无关后台等待，读取器完整关联请求依赖后才排除，不补造时间；请求相关缺失start仍拒绝。reader-controls-v2为47类损坏拒绝及2正例通过。

default-limit-v6-1失败原件保留：普通Base确到默认16384内部上限，但fixture只造成中间父图不可用，未命中端点parentFallbackPending；同步合成控制另堵主线程触发watchdog。这不等于算法缺陷或通过。修正控制在非路线端点角落先AVAILABLE再PENDING，等真实Super需求后按section UNLOADED→chunk unload事件使端点不可用；后台执行控制，真实服务器继续tick，不改watchdog、预算或JVM参数。

plain-v9和trace/probe-v7已构建并复制release，上一发行保留release-v6。default-limit-v7-1正在执行。最新探针door-open方向已改EAST，该场景尚无已完成用例受影响。全部新候选与探针在.tmp，正式生产及test本轮未改。

## 正确性结清与性能开始（历史）

default-limit-v7-1通过12断言，普通Base实际expandedNodes=16384，候选joins为共享Base→Super，真实端点PENDING child卸载后拒绝第三次Base，两future各一次、资源归零。DeepSeek default-limit-v1以3854B、377.073秒正常ACCEPT_WITH_GATES/必须修改项无，仅确认默认上限门；不把Super腿等待端点当作完成完整Super搜索的预算证据。

functional-v2前5与functional-v3余19共24功能JVM全部通过。partial终态EXHAUSTED，其余自然SUCCEEDED，包含真实改块后候选恢复Super、prepare→adopt后coldFirstPath=false且hierarchical=true。补充publication-v8-auto/candidate-adopt两轮，加入直接nativeDecision真实节点发布遥测，主线程归属和latch明确通过。corridor-control-v8-auto/candidate-128两轮均128实体自然结束、8个实际物理查询，核全部8个goal-z。功能耗时不进性能分母；其中四个补证与最后几轮旧功能并行，只有正确性用途。

最终候选逻辑仍plain-main-v4；诊断副本instrumented-main-v5只补直接发布出口事件。探针ServerStopped LOWEST在生产关服后直接检查logical/endpoint/physical/build/dependency/liveSearch/reference/factsBytes为0，scratch live及aliveWorkers为0；这四轮最终包均通过。reader-controls-v5为57类损坏拒绝、3正例通过。package-audit-v8为259主包class、8probe class，差异只在原九个诊断源及内部类，plain无Trace hook、无历史controller。plain仍保留各模式共同的policy记录和探针观察，不是完全无插桩正式包。

close-correctness.mjs生成correctness-v1.json：28功能JVM、10组82条worker断言通过，3次失败原件保留（contracts-v2 split package、contracts-v3 fixture卸载顺序、default-limit-v6 fixture/watchdog）。此前接线和诊断包复验另保留，不能加入性能分母。DeepSeek correctness-v1以6079B自足摘要送审，900秒本地截止，正在运行exec70900。

performance-freeze-v1.json已预冻结135个独立顺序服务器JVM：平地auto/base/candidate×detail/off/plain×1/128/256/512×3共108；八独立走廊512对应27，单列不互相外推。每九组合精确位置跨三重复各见三种mode和diagnostic；strata轮转。脚本sources/release/facts/输入properties/gates哈希固定，逐run前与最终汇总均核对。性能始终单服务器，无与功能轮重叠。performance.ps1支持StartIndex/StopAfter且跳过项必须已有analysis。

最终release（亦保留release-v8）SHA256：

- plain.jar：2648ECFC891B116CF22EDEB94574618623772A21AA6D0C8C19DE56AFDACFAA7B
- trace.jar：54BA8F6436603C49436F85E04C084634D8ED6218E70E5F2743F9EB10A7FDCE3A
- probe.jar：574847D38BB013B16A74346CF9996F4CF29E6AD4F855CC31CF80BDD3A634A4CF

DeepSeek correctness-v1已正常33.518秒结束，ACCEPT_WITH_GATES/必须修改项无，明确足以进入有限矩阵。仍需每JVM逐轮检查，512失败不得进入Portal或生产结论。exec70900已结束。最前9个平地count1/r1组合已全部通过，exec26198正常结束。当前exec79814运行performance.ps1 -StartIndex 9，顺序执行剩余126轮；不重写freeze、不改候选/读取器/冻结脚本。若任何运行或读取器失败，先保留原件分析，不能跳过或假通过。summarize-performance.mjs要求完整135轮且无失败才生成performance-summary-v1.json。

冻结后新添的独立后处理脚本不影响发行/执行：analyze-first-path-stages.mjs通过span父链关联GeometryKey匹配Base/Super task及请求关联Boundary/MacroQuery，只裁剪到全部请求各获首Path的截止；Super内部Boundary也归入inclusive Boundary，嵌套值不可相加。完整矩阵后运行--all生成first-path-stages-audit-v1.json。

**当前测量纠正，不能遗漏：** 冻结analyze.mjs的firstNativeDispatchFromSubmissionNanos/nativeQueueWaitNanos实际取首次query-owner派发；本源码首次派发是initialize，不是详细搜索。没有改冻结代码或旧数据。真实native-search/dispatch/enqueue/budget事件完整，独立stage后处理按真实native-search的最近全局dispatch、精确enqueue序号和owner、无中间budget-tick边界匹配真正详细队列；n512 auto/candidate均512请求匹配通过。stage-reader-controls-v1.json新增6类损坏拒绝、1正例，且绑定后处理源码SHA，后续若修改该源码须新版本复验。不可使用旧字段做详细排队归因。用户已被告知并致歉。首Path、到达、tick、建图主指标不受此命名纠正影响。

**本轮实际发现，尚非三重复结论：** flat自动首Path终点x16、11..14节点；Base/candidate首段x32、27..30节点（单实体固定起点为14对30）。未改分段代码，宏观corridor差异自然改变详细首段目标。首个512/detail自动nativeCalls=2560（每实体5），candidate=1536（每实体3）；首次真实native-search累计173.327ms对281.2058ms。candidate该轮首Path p95=1542.2142ms，比auto1203.9689ms慢，原件保留；到达p95约32.5s对32.4s。不能将全部差异归因父图，更不能称相同详细工作量下净节省。源代码、实际原件已核对，不进行分段或预算临时调参。

完整矩阵后的顺序必须是：①summarize-performance.mjs生成冻结原口径performance-summary-v1.json（135全通过才可）；②analyze-first-path-stages.mjs --all生成45个detail阶段及真实native/队列审计；③correct-performance-queues.mjs生成performance-summary-v2.json，保留V1哈希，旧firstNativeQueueMs改名initializationQueueMs，真正详细队列由stage审计覆盖；主指标不变；④audit-performance-mechanism.mjs读V2，核canonical joins/公共计数、waiter界限、8走廊、首Path节点/端点/全程native次数；⑤prepare-performance-reviews.mjs读V2和两审计，生成7个自足包（三诊断各低/高负载及八走廊）。每包<=6500字节，900秒本地截止；尚未生成/发送。超限继续拆，不放宽。原前置summary-v1不改，新的性能V1亦不覆盖。

当前前两重复90/135轮已结束，仍无性能运行或读取器失败，exec79814继续第三重复。第一重复flat512的后半组合出现明显波动，auto/plain p95=1700.8151、base/detail=1983.3857、candidate/off=1880.6061ms；不可选用较早的好结果，残留顺序/主机效应未排除。corridors512首重复auto/detail p95=1461.0166、candidate/detail=1703.5888ms；建图64/7对20/0，均8实际物理查询。第二重复后段单实体也明显波动：candidate/off 735.7635、auto/plain 1239.627、base/off 808.8732、candidate/plain 831.0866、auto/detail 428.2925ms，全部运行和读取器通过，原样保留；第三重复未完，不输出稳定收益或Portal结论。

新增独立送审准备脚本prepare-performance-reviews-v2.mjs，原v1保留且未运行。V2覆盖全部135轮，每包独立自足，超过6500字节仅按负载/诊断拆分，不放宽限制、不根据结果挑样本；完整数据后生成performance-review-packets-v2.json，再逐包送审。新增report-performance.mjs只在V2摘要及两审计完成后提取逐JVM原数和报告表，三JVM中位数仅描述、不合并实体样本。上述新脚本不在运行冻结集合中，也没有改变冻结源码/执行/读取器；尚未生成性能摘要和新送审原件。

上文是中止前的历史执行记录；“继续矩阵/生成完整summary/分包”的原定下一步已因第104轮失败撤销。完整performance-summary-v1/v2、45 detail审计及完整矩阵审核包均未生成；原前置summary-v1未改。以下为唯一当前恢复点。

## 第104轮中止与最终封存

exec79814在perf-v1-flat-512-auto-detail-r3探针失败后exit1，已退出。该服务器进程正常exit0、无本地超时，但probe passed=false，错误为terminal without Path 0 UNAVAILABLE。512请求均enqueue且owner/sequence逐个核齐，dispatch/policy/canonical joins/physicalSearchesStarted/nativeCalls/installed均0；没有进入初始化或宏观分支。202个公共/trace完整tick逐一致，全SERVER_NO_TIME、grant/refill均20ms；连续200tick pending=512且派发前余额14.890899..19.496001ms，随后原PLANNING_TTL=200的排队超时路径结算。没有区分两个haveTime谓词或其上游原因，不能断言主机负载、预算偿债或候选算法有错。收尾failures=512、records=0、worker/facts/scratch资源零；逐请求最终状态快照未录，不能把汇总失败数扩写逐条同因。

close-performance-abort.mjs重新读取103完成样本，全部冻结源码/执行/读取器/发行/facts/实际jar/串行时序/资源/229公共physical计数与canonical joins通过；34完成detail复用原已验证阶段读取器关联真实native队列。失败轮被冻结读取器明确拒绝，未补analysis，也未运行余31轮。performance-abort-v1.json记录auditPassed=true但matrixPassed=false、performanceQualified=false，performance-abort-stages-v1.json仅34完成detail；208份preflight/exit原件SHA保留。29,714完成逻辑请求，另512失败轮提交；auto34通过+1失败、base34通过、candidate35通过。候选确已实际验证，不能再称仅做前置；整体也不能称验收完成。

第三重复256/base/detail p95=6145.3929、256/candidate/plain=7964.8585ms原样保留。flat512/plain三重复auto为1700.8151/898.4942/1391.3802ms，candidate为885.4724/850.6074/1482.7482ms；首Path并非每次改善，但该切片三次自然到达p95均较早。不能删除好坏任一类样本，也不据部分数据登记稳定收益。

finalize-abort-scope.mjs生成performance-abort-v2.json，V1及首轮seal不动：off/plain共69个完成JVM缺少详细事件，旧空数组展平产生的successorRequests=0改null；34完成detail实际后继service请求数均0且保留范围标签，不表示后续详细搜索没有排队。V2显式列原阶段数据中的实际native队列/派发/工作；主Path/到达/tick/图/公共计数、分母和失败决定完全未改。未测null不得在导出或汇总回填成0。

WSL原生dsh独立自足审查：performance-abort-v1为6462B、63.654s；performance-abort-scope-v2为3920B、18.145s。均实际deepseek/deepseek-v4.1-flash、900s本地截止、exit0无超时、settings SHA前后相同；两包均ACCEPT_WITH_GATES/本轮必须修改项无，实际回复已读。共识仅为中止、归因限制及未测值纠正，不是完整性能/Portal/生产通过。

seal-performance-abort-review.mjs和seal-performance-abort-scope-v2.mjs最终核冻结源码/发行与208原件未变、主指标未改、69未测null/34实际0范围、余31目录仍不存在、完整performance-summary-v1/v2仍未生成。最终performance-abort-review-seal-v2.json已写入，auditPassed=true且matrixPassed/performanceQualified均false。执行会话26198、79814及两送审会话51798、77203全部退出，无本轮服务器继续运行。

预备的prepare-performance-reviews-v2.mjs、report-performance.mjs、review-performance.ps1未用于完整矩阵输出；performance-review-packets-v2.json未生成，不使用模板中的“135全部通过”声明。实际中止审查另用独立包，完整矩阵guard未放宽。

后续若继续，须另列最小范围并新冻结：补逐请求终态与两个haveTime谓词区分，明确基线派发条件后重测；不能续旧矩阵凑满135，不能先改预算/TTL/haveTime救结果。本轮不进入Portal；既有Base mayExit/mayEnter及代次绑定BoundaryLinks不是新骨架，未实现不能写为已证明无效。正式报告已更新原始对照、阶段、慢样本、失败与局限；未修改本轮冻结候选、正式生产/test、持久化、历史估计、预算、tile或JVM，未提交推送部署。
