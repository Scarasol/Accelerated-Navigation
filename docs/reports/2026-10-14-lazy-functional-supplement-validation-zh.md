# 懒惰派生图功能与生命周期补测

日期：2026-10-14。当前状态：**修订后 13 个限定功能场景、独立证据核对及 DeepSeek 复审通过；本轮必须修改项为无。整体研究、持久化及生产验收仍未完成。**

用户接受先补关键正确性测试，再进行限定原型审核。本轮新增证据根 `.tmp/lazy-functional-supplement-20261014/`，当前功能证据为 `supplement-v6`。此前所有原件保留，水域执行特性按用户决定暂不处理。压缩、性能、持久化、存储布局与动态预算不在本轮范围。

## 1. 功能结果

| 场景 | 终态 | 本轮证据 |
| --- | --- | --- |
| step-jump | SUCCEEDED | 原生一格跳上，实际到达高一格平台 |
| stairs | SUCCEEDED | 真实楼梯几何，原生节点及实体执行到达 |
| slab | SUCCEEDED | 半砖过渡到平台，保持原精度到达 |
| drop-three | SUCCEEDED | 实际下降三格；空中交接等待原导航可搜索后继续 |
| jump-gap-negative | EXHAUSTED | 宏观跳跃候选存在，本场景 Husk 原生搜索返回 partial；两次恢复后失败 |
| partial-final | EXHAUSTED | 最后段真实 partial 有多节点、发生实际位移；未到终点，零恢复，正确失败 |
| partial-intermediate-exhausted | EXHAUSTED | 中间真实 partial 执行后两次恢复耗尽，未伪报成功 |
| capability-door-change | SUCCEEDED | 执行中撤销开门权限，暂停旧段，精确替换门影响行，绕行到达 |
| capability-malus-change | SUCCEEDED | WALKABLE malus 变化、宏观几何不变；完整能力快照使旧 Path 失效，重搜后到达 |
| request-replace | SUCCEEDED | 请求、控制代次与目标同时替换；新目标实际到达，旧候选/Path 拒绝 |
| cancel-late | CANCELLED | 取消后旧节点停止，三个服务器 tick 后的迟到交付拒绝并清理 |
| neighbor-change-cross-section | SUCCEEDED | 演员仍在 chunk 0 时变更 chunk 1；真实搜索区域证明过期，暂停、一次刷新、跨区到达 |
| native-recompute | SUCCEEDED | 主动调用真实导航 recomputePath 入口，暂停、重新调用真实搜索；输入仍当前时也拒绝较早的原生段交付 |

合计 9 个实际到达、3 个正确失败负控制、1 个正确取消。288 条驱动检查全部成立，`functionalPassed=true`。负控制通过表示正确保留失败，不能称三项实体实际到达。

48 次真实详细搜索均登记，未登记搜索 0。其中 7 次是真实 `canReach=false` 的 partial。节点、终点和标记均由原版返回，没有构造 Path、改写 canReach、扩大 accuracy、执行中传送或整条执行回退。

台阶、楼梯、半砖、落差及深沟场景均直接复用各场景允许开门基础所包含的能力掩码，没有评估新的目标行。撤销开门权限后总共生成 148 个目标条件出边行，其中 62 行在门读域内精确替换基础；空行也覆盖基础行。这里只证明被测条件下的候选与执行，不认证所有实体能力的物理单调性。

深沟为一格宽、五格深。宏观 `jumpClass=1` 可以提供跨两锚点连接，但普通 Husk 的实际原导航在该场景不能完成水平跨隙。原型直接执行真实 partial 并按原限额结束，不能把宏观候选当作 Husk 具备这种跳跃能力。

## 2. 交接、当前性与生命周期

原参数保持 accuracy=0、visited multiplier=1、region offset=8；range 等于实际 FOLLOW_RANGE。最多两次恢复、一次宏观刷新、180 tick 阶段与 600 tick 场景上限保持。新增宿主 JVM 看门狗为 480 秒，只保护整次测试运行，不改变寻路参数或恢复额度。

三格下降中，当前段节点可能耗尽而实体仍在空中。适配器等待真实 GroundPathNavigation 的 `canUpdatePath` 恢复，再从落地后的实际位置发起下一段查询。等待期间不交付旧节点、不增加一次无效原生调用；等待与随后搜索沿用阶段时限。

完成状态分别处理整项任务与当前原生段。等待输入、能力更新或落地时，外部 `Navigation.isDone` 保持 false；原版跟随器在自身 tick 内继续使用原生段的完成语义，避免对空 Path 执行 followPath。最终未实际到达的 partial 以 EXHAUSTED 结束。

每个候选和真实段绑定几何版本、请求身份、控制代次、目标、GeometryKey、MovementKey 及完整 NavigationCapabilities，后者含体型、pose、step、drop、evaluator、门/流体/围栏标志及全部 malus。实际原生搜索区证明来自冻结 NativeNavigationSearch，而非另建的观察区域。

五项分别实际尝试旧候选入口，共 5 次拒绝：两个能力变化、取消和替换按完整身份变化拒绝，邻区变化按 `planCurrent=false` 撤销拒绝，后者 stamp 可以仍相同。不能将它们称为五次同质身份变化。

六项共 12 次旧 Path 拒绝，含即时与三个真实服务器 tick 后的迟到尝试：能力/取消/替换贡献 8 次身份拒绝，邻区贡献 2 次实际区域 INPUT 拒绝，新增重算贡献 2 次原生交付代次 SUPERSEDED 拒绝。拒绝没有增加搜索、调用 moveTo、替换当前 Path 或消费旧节点；计数不能作为所有机制重复验证十二次。

INPUT 事件包含 proof 是否存在/current，以及直接读取 proof 中预期 chunk 身份/版本与实际 chunk 身份/版本得到的差异。独立读取器要求存在版本或身份差异，并与变更前后的真实邻区证明过期事件对应。全部 48 次新查询的 proof current=true、差异为空也被核对，避免“恒拒绝”混入成功证据。

重算测试主动调用真实导航的公开入口，实测一次 `NATIVE_RECOMPUTE_QUEUED`，下一 tick 重新调用真实 PathFinder，且没有宏观刷新。每次原生查询与撤销递增交付代次，避免输入仍当前、请求 stamp 相同时，较早的真实段通过迟到交付替换新段。这个场景验证入口和交付门禁，**不认证物理停滞自动触发**。

相同节点序列的恢复还暴露了 `moveTo` 的 `sameAs` 行为：它会保留旧 Path 对象。隔离桥接先停止旧段，再交付本次真实搜索产生的 Path，保证新输入证明绑定新对象；原生节点值在安装前后保持。冻结生产类没有为此修改。

迟到交付由主线程延迟触发，用于验证同一门禁实际拒绝旧结果；**没有 worker 线程或生产异步 Runtime 并发发布认证**。目标身份变化由驱动触发；没有扩大成全部 Goal、Brain、POI 生命周期通过。

## 3. 证据与版本

独立 Node 读取器 2,328 项核对通过。Windows 与 WSL 各重新运行同一个读取器，结果一致；这不是 4,656 个新增场景。它读取完整逐 tick 位置与生命周期记录，重新计算位移、最终目标格、查询的实际起点、原生末节点与 partial、恢复计数、暂停/迟到游标、证明差异及完成状态；读取生产图标签和 CSR 能力掩码，独立核对候选连接。

组件连接核对不等于逐 primitive 几何行独立重实现；完整候选与目标构图的布尔对照仍由 Java 驱动进行。报告保持这一区分。

冻结依赖来自此前 `door-reuse-validation-20261014/native-v2`，只使用独立 javac、重映射、发行副本与新临时世界。原有 48 个导航、调度、Mixin 类逐字节相同；新增同包桥接仅在隔离副本内。边界保护文件无变动。未修改生产源码、共享构建、另一对话预算、用户存档，未提交、推送或委派。

| 版本 | 保留的结果 |
| --- | --- |
| supplement-v1 | 前三项到达；落差交接在空中提前搜索，实际区域证明缺失，驱动失败并完整停止 |
| supplement-v2 | 将整项 isDone 引入原版 tick 时，空 Path 跟随导致引擎异常；没有最终 result.json，不计通过 |
| supplement-v3 | 前四项到达；相同节点恢复保留旧 Path 对象，被实际交付检查捕获；引擎异常，不计通过 |
| supplement-v4 | 12 项预期终态通过；259 条驱动检查；尚未补旧候选入口实测，不作为最终证据 |
| supplement-v5 | 12 项通过，264 条检查、2,160 项读取器核对；首审列出必须修改项，不能当作审核放行 |
| supplement-v6 | 13 项通过，288 条检查；补 proof 差异与原生交付代次、重算 API 阳性场景；2,328 项独立核对 |

六个 JVM 均已退出。v2/v3 保存完整引擎崩溃报告和退出记录；即使进程退出码为 0，缺少最终结果也不能计通过。v6 有正常 ServerStopped 证据、退出 0、无宿主超时，全部迟到引用在场景结束前清理。清单增加工作区相对来源定位，解决首审 WSL 读取器不能访问 Windows 绝对来源路径的问题。

## 4. 审核与后续门槛

限定审核配置：WSL 无头，provider=`deepseek`，model=`deepseek-v4.1-flash`。唯一规范是现行原版详细寻路契约；送审材料不附旧设计或旧审核答复作为依据。

首审 `supplement-v5` 285.208 秒、退出 0、无超时，标题为 ACCEPT_WITH_GATES，但正文列有必须修改项，**未作为放行**。16 份规范/附件身份与 settings 保持。修订后新增证明明细与读取器条件、重算入口、交付代次，并校正覆盖和计数。

复审 `supplement-v6` 30.013 秒、退出 0、无超时，明确返回 ACCEPT_WITH_GATES 及“本轮必须修改项：无”。17 份规范/附件身份保持，settings 前后 SHA 相同，实际 provider/model 为 `deepseek / deepseek-v4.1-flash`。仅本轮限定功能原型获接受，不升级为整体研究完成。

最终收尾读取器 63 项通过，核对规范/附件及完整原始证据身份、结论与“无必须修改项”同时成立、配置未改、功能结果及进程关闭。原始答复以“审查结论”标签给出结果，读取器做格式兼容，未修改答复。记录分别为 `deepseek-answer-supplement-v6.txt` 和 `closeout-supplement-v6.json`。生产/后续门槛继续保持，不以审核文字中的局部描述替代原始场景坐标或读取器实际覆盖。

本轮没有触发 `NATIVE_STOP_RETRY`，物理 stuck-stop 及该恢复分支**未验证**；两次恢复的阳性计数仅证明 intermediate partial 分支。MC 的 doStuckDetection 调用 stop，公开 recomputePath 是另一入口，不把两者误写为同一链条。

源文件保留了上一轮 `doorMatrix()`、`mutate()`、water 诊断及 `input-change/door-on-shortcut/door-off-detour/door-external-close/door-cross-section/big-basis-detour` 等不在当前 CASES 的方法或分支。它们本轮不运行，不继承补测资格；门矩阵长度为 0。此前 64 门矩阵及水域记录属于各自冻结版本，不能并入当前 13 场景。

邻区跨界场景确实建立 west/east 两个 MixedDoorGraph，并检查 BoundaryGeometry；基础连接已存在，目标行生成 0、门替换 0。它验证跨界输入与执行，不验证“邻区变化与门替换升级在同一场景同时发生”。

生产 Runtime 并发与异步发布、真实调用方全面接入、上层图、专用导航、TTL/服务器关闭竞争及性能仍在资格范围之外。空间退化、压缩与持久化尚未解决；旧持久化 v5 future 缺陷未修复，本轮没有复用它。

复现必须使用新的运行目录：

```powershell
& ./.tmp/lazy-functional-supplement-20261014/run.ps1 -Run supplement-new
& ./.tmp/lazy-functional-supplement-20261014/audit.ps1 -Run supplement-new
node .tmp/lazy-functional-supplement-20261014/verify.mjs supplement-new
node .tmp/lazy-functional-supplement-20261014/summarize.mjs supplement-new
```
