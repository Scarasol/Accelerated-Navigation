# 动态预算生产接入准备方案

日期：2026-10-14。状态：接入准备，已按首轮独立审核修订，等待修订稿复审；未实施生产代码修改，未部署。

用户已授权先推送当前代码，再准备生产接入，并明确不需要功能开关。已推送基线为 `fead3a154f7e9a1d8e5470fb331a013661a35007`，分支为 `origin/1.20.1`。派生拓扑持久化由另一对话负责，本轮不修改或提交其研究文件。

## 设计依据和修订范围

唯一现行架构依据是 [原版详细寻路接入现行契约](2026-10-13-native-detail-navigation-contract-zh.md)。废弃设计和旧审核不得进入本轮审查。

现行契约仍规定全服务器共享约 4ms/tick 软预算。本文明确提出对该条款的修订候选，不能把原型研究报告当作已经生效的生产规范：

- 全服务器共享动态软额度，冷控制器从 4ms 起步；候选范围为 0..12ms。
- 保留负债、正余额不跨 tick 累积、END 派发、wake 只入队、实际主线程区间计费和原子搜索不可抢占。
- 对特定正常中间段增加最多两 tick 的交接等待；保留真实到达、partial、native-stop、输入失效和所有既有恢复/超时规则。

12ms 和两 tick 是第一版生产候选参数，仍须以接入后的实际证据决定。通过门槛后，才把批准的预算和交接规则明确写入现行契约；不会让两个矛盾的规范同时生效。

不增加功能开关、运行时固定/动态模式选择或配置回退。冷启动限制、零额度和负债偿还是同一控制规则的正常状态。失败时修复候选或回退整个版本。

## 已有证据和适用边界

[动态预算原型报告](../reports/2026-10-13-dynamic-navigation-budget-prototype-zh.md)证明扩大额度能缩短本批高并发排队，尚未证明减少算法总工作量，也未证明低负载时预测器优于固定 12ms。

[交接根因研究](../reports/2026-10-14-dynamic-budget-handoff-root-cause-zh.md)确认：正常中间段原生节点已耗尽、实体尚在目的组件外时，快速 settle 提前触发恢复，额外搜索改变最终段起点。两 tick 在该批次消除了新增配对失败；最终 partial 的内部终止原因仍未完整重放。

原型结果不等于生产接入验收。速度 IV、不同能力、完整生命周期、暂停、持续请求、冷热拓扑和正式性能补证仍是生产发布条件。

## 修改位置与理由

| 位置 | 计划修改 | 原因和边界 |
| --- | --- | --- |
| `scheduler/NavigationBudgetController.java`，新增 | 用固定长度历史估计非导航工作和 END 尾部，选择额度；只含纳秒数和固定数组 | 把控制规则与 Minecraft 生命周期分开，便于确定性验证；不接触路径或实体语义 |
| `scheduler/NavigationScheduler.java` | 接收完整 tick 封套和实时 haveTime；动态补充、尾部截止、逐次启动检查、有界汇总 | 原版平均 tick 时间遗漏本项目 END 派发，普通 START 监听器也不能充当完整起点 |
| `mixin/ServerNavigationBudgetMixin.java`，新增；生产 Mixin 配置 | 包围 `MinecraftServer.runServer` 内实际的 `tickServer(BooleanSupplier)` 虚调用 | 计入 START 前的工作、所有 END 订阅者和 IntegratedServer 子类尾部；发行重混淆须定点验证 |
| `AcceleratedNavigation.java` | START 标记正常 tick；END 继续既有观察和派发顺序 | 暂停 tick 不补额度、不进入正常控制历史；普通事件仍是导航调度的唯一派发阶段 |
| `navigation/SegmentedNavigationService.java` | 将原型等待条件移入现有 settle，使用 Work 的 `doneSince` | 消除对低额度排队延迟的偶然依赖；不用反射，不增加新到达规则 |
| `src/test/java/an/production/ProductionBudgetChecks.java` | 更新直接入口测试，补完整封套、预测历史、负债、线程、异常、暂停及实时截止 | 现有“正余额固定等于 4ms”断言只覆盖冷控制器阶段，不可当作所有 tick 的契约 |
| 既有边界、速度、生命周期探针和 benchmark 工具 | 补等待期间故障注入、动态额度下真实路线、持续请求及正式重复 | 接入后的代码和 jar 必须独立验收；旧原型不能替新代码背书 |

上述 Java 路径以 `src/main/java/com/scarasol/acceleratednavigation/` 为根。只改变调度计时、额度和交接时机，不修改宏观算法、持久化、真实 PathFinder 参数、目标容差、节点限额或恢复上限。

## 计时和控制规则

统一用单调时钟纳秒。完整封套从真实 tick 虚调用前开始，到该调用正常返回结束；它是 tick 工作窗口，不把睡眠或两次 START 的间隔称为 MSPT。

候选规则：

```text
target = 45ms
maximumGrant = 12ms
initialGrant = 4ms
historyLength = 32 个有效 tick
warmupLength = 8 个有效 tick
EWMA alpha = 0.2

baseEstimate = max(EWMA(nonNavigation), P95(nonNavigation))
desired = clamp(target - baseEstimate, 0, maximumGrant)
grant = min(desired, previousGrant + 1ms)
if 有效历史少于 8 个:
    grant = min(grant, 4ms)
if 上一个有效 tick 的完整工作窗口 > 45ms:
    grant = min(grant, previousGrant / 2)

tailReserve = max(2ms, EWMA(nonNavigationTail), P95(nonNavigationTail))
deadline = tickStart + target - tailReserve
balance = min(grant, previousBalance + grant)
```

非导航工作等于完整封套用时减去封套内导航计费区间的并集。尾部历史只记录本调度器 END 派发结束后、完整封套返回前的非导航工作；不能重复扣除导航区间。

封套采用显式 `inEnvelope`、`startSeen`、`endSeen` 和 `completedNormally` 状态，不用 `tickStartedNanos==0` 作为状态哨兵；拒绝嵌套封套、重复 START/END 和跨线程入口。有效样本必须是同一个封套内恰好一次 START、一次 END，且原虚调用正常返回。IntegratedServer 暂停不调用正常 START/END，因此不满足有效样本条件。

完整封套入口先建立计时并计算本 tick 候选额度，但不改变已经提交的 `previousGrant`。仅实际出现正常 START 时提交选额并补充一次额度；高优先级 START 监听器在此前产生的导航计费仍保留。缺少 START 不补额，也不通过反复暂停积累额度扩张。异常返回或缺少正常 END 时不更新历史；已发生的计费和负债不撤销。finally 总是清理封套状态，使用入口已取得的 scheduler 引用，关闭后不能通过 `forServer` 复活服务。

P95 采用 nearest-rank：有效样本数为 `n` 时，升序数组下标为 `ceil(0.95*n)-1`；`n=0` 返回 0，不访问负下标。非导航和尾部只使用同一批有效样本，32 项满后覆盖最旧项。尾部前 8 个有效样本采用 `max(2ms, 已观测尾部EWMA, 已观测尾部P95)`，立即采纳较大的已知尾部，不等预热结束才保护；前 8 个样本的额度仍最多 4ms。历史不能预测尚未出现的突发。

派发前同时检查余额、当前实时截止和原始 `haveTime` supplier；每次启动重新读取 supplier。预测失败不能推迟实际的余额或截止检查。没有余量时不强行给最低额度；长原子调用实际超支后保留负债并停止后续派发。45ms 目标和 12ms 上限都是启动前软控制，原子调用或未知尾部仍可能令实际工作超过 50ms，不提供硬保 20 TPS 的承诺。

现有嵌套 `account()` 的并集计费继续覆盖提交、准备、搜索、发布和同步回调。跨封套 account 明确采用边界快照，不选择另一个未定分支：

```text
activeElapsed(now) = accountingDepth == 0 ? 0 : max(0, now-accountingStarted)
totalAccounted(now) = chargedNanos + activeElapsed(now)
available(now) = tokensNanos - activeElapsed(now)

START refill:
    newAvailable = min(grant, available(now) + grant)
    tokensNanos = newAvailable + activeElapsed(now)

tickNavigation = totalAccounted(tickEnd) - totalAccounted(tickStart)
tailNavigation = totalAccounted(tickEnd) - totalAccounted(drainEnd)
```

正常外层 account 关闭时仍只扣一次该并集实际时长；START 补额已把尚未关闭的消耗纳入正余额上限，不能用裸 `tokensNanos` 绕过它。边界快照是在同一主线程、同一 now 下读取，不人为关闭或重新开启已有 account。需在测试中跨 START、END 和两个封套验证守恒。内部 long 累计差值按有限时间区间相减，展示计数饱和不能替代实际扣费。

tick 外导航工作直接消耗同一余额，单独记录，不能减入任何 tick 的非导航样本。非导航和尾部均使用边界快照计算导航并集；不将外层 account 最后归零时才增加的整个 `chargedNanos` 差值错误归给最后一个 tick。

32 项固定数组复用排序 scratch；选择额度为固定 O(32 log 32)，每次启动新增检查 O(1)。不改变队列公平、维度轮转、优先级老化或每次搜索的复杂度。

## tick 间任务、落后与暂停

现有原型没有完整测量等待阶段的非导航任务。接入前须独立验证：

- tick 间导航回调仍计费且不污染 tick 内非导航估计。
- `waitUntilNextTick` 中真正执行的任务与睡眠分别记录；不得把完整等待时长加入工作历史。
- 原生计划已经落后时，保留原始 `haveTime` 的限制，观察追赶 tick 的额度、实际启动和等待结果。不能宣称预测器解决所有落后问题。
- IntegratedServer 暂停时若不产生正常 START/END，禁止派发和补额，忽略该样本；恢复后沿用有效历史和负债。

第一版不盲目添加全事件循环拦截。若复现实测表明仅 tick 封套加原生 haveTime 对 tick 间忙碌缺乏保护，须先定点补控制方案并重新审核，不能靠改统计口径排除问题。

## 有界交接等待

只在下列条件全部成立时，延后本次原 settle：

1. 已采用、非终态的分段任务，当前 Path 仍是本请求，真实节点非空且已耗尽。
2. 当前段为正常 reachable 中间段；不是最后段、partial 或 native-stop。
3. `doneSince` 已建立，当前游戏 tick 与其差值在 `[0, 2)`；不重置起点、不延长 TTL。
4. 原窗口和实际读取 proof 仍 current，`segmentEntry==null`，实体当前仍在目的组件外。第一版严格复用已验证原型的“没有记录”条件，不扩大为允许忽略一个已有的无效记录。

上述条件必须在 settle 实际执行时重查；执行入口先使用既有 valid 规则确认 records/active 中仍是本 Work、实体/Navigation/控制代次未替换、请求未取消/终止、服务器未关闭。重新核对当前 Path、输入和 LANDING_TTL 后才能延后；不只依赖 enqueue 时的状态。失效时继续既有取消、重搜索或恢复分支。

下一 END 继续原观察和 settle；进入组件后立即走原到达判断，到达不得靠等待次数批准。已有经过记录继续由原 intermediateArrival 的 request/publication/nativeProof/windowRevision/destinationComponent 身份规则判断，等待逻辑不批准旧记录。两 tick 用完继续原恢复；physical 不成立仍遵守原稳定和 LANDING_TTL 规则，不额外延长这些限制。

输入变化、取消、替换、重算、关闭以及真实 native-stop 优先按现有分支处理。排队后须重新核对输入，不能只相信 enqueue 前的条件。保留终态 EXHAUSTED，不以放宽 accuracy、改 Path.canReach 或整条回退掩盖失败。

## 生产仪表

生产只保留一个控制器的 32 项历史、复用 scratch、当前/上一 tick 快照以及累计数值统计；不保留逐实体轨迹、每 tick 无限增长列表或常态 JSON 输出。

汇总至少区分：无任务、无额度/负债、实时余量不足、原生无时间、停放依赖、启动阈值不足；`pendingRequests` 不能被标成纯预算排队。展示用 long 累计计数在上限饱和，不采用随次数变大的任意精度整数；当前/上一 tick 快照固定覆盖。生产额外状态不随总运行时长增长。展示饱和与预算内部实际扣费分离。

测试 mod 可以有界采样、输出详细证据，但不得代替生产计时或再覆盖其预算/交接方法。`an_budget_prototype` 不与新生产候选包同装。

每批记录实际生产/探针 jar、源码及输入哈希，正常退出状态和关闭归零。不同版本结果分开保存，不能把旧原型成功数并入新生产验收。

## 既有测试的具体适配

- `ProductionBudgetChecks` 通过新的封套入口和正常 START/END 驱动测试。固定 4ms 的三个精确余额断言只保留在全新冷控制器的确定性场景；另用独立预热历史验证 12ms 稳态、零额度和突发收缩。每种场景按当次实际 grant 推导扣费与余额，同时检查正余额不累计、负债不清零、同步回调及嵌套并集只计一次。
- 原子超支测试分别超过冷额度和稳态额度，不能把 5.7ms 工作放进 12ms 后仍期望停止后续任务。另测 `haveTime` 首次允许、第二次启动前撤销，实时截止在任务间到达，以及一个 account 横跨补额/封套边界的守恒。
- 测试结束恢复 clock、余额、charged、accountingDepth/started、封套/阶段标志、supplier、END 快照和控制器全部历史/EWMA/选额状态。替换为全新测试控制器后须在 finally 恢复原控制器实例；任务全部清理，再验证同 JVM 第二次运行。这个隔离只用于测试，不成为生产模式或开关。
- `HandoffBoundaryProbe.holdSettle` 保持 HIGHEST END 优先级，严格早于项目 NORMAL END 派发。替换固定 10ms 阻塞为按当次实际余额和 grant 注入的已计费主线程工作，目标耗时至少 20ms 且大于进入该区间前的可用正余额；结束后直接断言余额低于最小启动阈值。后续仍必须观察真实 settle 已排队且未执行，再改变输入；若未观察到此前提，测试失败，不算通过。该负载仅存在隔离测试服务器。

## 实施和验证顺序

1. 已完成：提交并推送 `fead3a1` 基线；离线编译生产和测试源码通过。保留基线发行 jar、哈希和源码身份，供直接换包回退。
2. 本轮准备：由 DeepSeek 独立审核本文、现行契约和当前相关源码，明确审核是方案级，不是发布批准。
3. 第一组实现：有界控制器、完整封套、额度/截止接线和生产汇总。先做确定性预算测试及发行态 Mixin 检查。
4. 第二组实现：交接等待直接进入现有 settle，补等待期间的边界事件。正式候选包须同时包含两组修改，再运行真实路线，避免把尚未修复交接的快速派发包当作发布版本。
5. 使用新冻结证据根验证同一个生产候选 jar：完整生命周期、速度 II/IV、不同体型/能力、楼梯/跳落/错位墙和四条原参数长路线；最终 partial 做固定输入重放，保留真实失败。
6. 只补影响范围内的性能证据：128/256/512、多样起终点和混合路线、持续请求、冷热拓扑分开；至少五次独立重复、打乱运行顺序。补 10/30/40/48ms 附加负载、长原子调用、未知 END 尾部、tick 间任务和暂停/恢复。
7. DeepSeek 只对新候选源码、明确修订后的契约和新证据做最终验收。通过后提交一个完整、可回退的生产集成版本，再准备发行。

复用历史固定 4ms 正式性能数据作为历史背景，不重复整个旧矩阵。新候选的性能百分比不能直接以旧仪表的历史数据为分母：仅对受改动影响的路线补新仪表、同输入、同采样规则的固定 4ms 配对对照；这些局部对照也按至少五次乱序正式重复执行。对照使用独立基线测试包和只测量的仪表，不在新生产包加入运行时额度开关。若4ms对照使用新计时保护，必须标为“同计时规则的4ms对照”，不能冒充未改基线。只测量的公共仪表不覆写候选生产预算或交接。

对比至少报告 API 提交、提交到首 Path、实际到达、完整任务计费、调用/恢复、分配/峰值、TPS 和 >50ms tick；到达时间使用配对共同成功请求，不把失败当成更快完成。

## 回退和交付

生产发布采用完整 jar 替换，不增加功能开关。`fead3a1` 是接入前代码保存点；已用其源码完成 `jarJar/reobfJarJar`，固定基线 jar 的 SHA-256 为 `3AE2573E5F1E9407F36EE8BED143B4D721799EDDF066707144B80F80C0C6451A`，本地保存于 `.tmp/navigation-dynamic-budget-production-20261014/baseline-fead3a1/`。现场回退使用这份固定旧 jar，停止服务器后替换，再正常启动。不删除世界、缓存或拓扑文件；本轮不引入持久化格式变化。

候选实现单独提交，不混入派生拓扑持久化提交。必要时可以对本次集成提交执行 revert，保留其他提交；不使用 reset、强推或工作区覆盖来回退用户工作。

准备阶段的交付是已推送基线、可复用的旧包身份、这份具体接入方案和独立方案审核。尚未修改 `src/main`，尚未完成新候选发布验证，不把准备完成写成生产上线完成。
