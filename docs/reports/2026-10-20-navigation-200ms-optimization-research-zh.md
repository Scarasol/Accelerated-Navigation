# 导航额外延迟 200 ms：优化方向研究与官方 DeepSeek 意见

日期：2026-10-20，北京时间。范围：当前源码、有效历史数据、互联网原始资料及一次有效
DeepSeek 官方独立研究审查。未实施候选、构建或启动游戏测试，未改变正式测试接口或门槛，
未提交、推送或部署。旧报告、原始计时及失败分母保持。

## 建议

优先研究**宏观结果交付、纯准备任务和真实搜索准入之间的接续**，
其次考虑**至多一个后继窗口的不可变模板预取**；
普通走廊扩大阶段跨度放在后面。不再把位图、哈希或临时坐标的微秒级改动当作
消除几百毫秒延迟的主方案，也不承诺全部地形、每次请求都达到 200 ms。

用户关心的主目标仍是相对原生的实际到达增量，不把它悄悄替换为首 Path 的绝对时间。
首 Path、首次移动、实际到达和请求终态必须分开；原生未到达的路线不存在可相减的成功耗时。
本次尚未冻结中位/P95/max 中哪一项作为最终门槛，也未把 64 并发等同所有常见负载。

## 历史数据先排除了什么

沿用[计划起点及同期原生比较](D:/Workspace/AcceleratedNavigation-forge-1.20.1/docs/reports/2026-10-20-preplan-baseline-historical-comparison-zh.md)，
离线核验其 995 份封存证据。新脚本只从原实体时间戳和完整 tick 记录做诊断重分组。
N0 为 10 月 16 日同包原生 bypass，S9L 为 10 月 20 日候选；
完整测试工具不同、旧记录为预热、每版本只有一次运行，均不能作为受控因果证据。

### 单次详细搜索的适用边界

| 场景 | S9L 一次搜索 | 两次搜索 | 三次搜索 |
| --- | ---: | ---: | ---: |
| 8 格、64 实体 | 32 | 29 | 3 |
| 28 格、64 实体 | 0 | 50 | 14 |

8 格平地的八个实体全部只有一次真实详细搜索。它们没有后继窗口预取收益；
不能用多段流水线方案解释或消除这些实体的全部到达差额。
搜索次数不是完整宏观阶段记录，不反过来将所有一搜路线自动认定为零边走廊。

### 约 480 ms 的移动墙钟差不等于多等了 480 ms

以下为同一路线 tile0/slot0 的原始事件映射。映射到包含事件的完整 server tick，
不是按墙钟除以 50 ms 反推：

| 平地样本 | N0 首次移动至到达 | S9L 首次移动至到达 | N0 / S9L 跨过的 tick 数 |
| --- | ---: | ---: | ---: |
| 8 格 | 999.6201 ms | 1479.0097 ms | 30 / 30 |
| 28 格 | 5428.0468 ms | 5924.1889 ms | 119 / 119 |

两档平地各八条配对路线的移动 tick 跨度都相同。原生提交所在完整 tick 分别为
514.6686、530.8213 ms；28 格随后 tick 启动相对时间为
0、530.8780、539.9095、546.9449、555.4208 ms，呈现长 tick 后连续执行的节拍。
S9L 后续 tick 大致相隔 50 ms。

这些记录与原生长 tick 后追赶执行的解释相符，说明不能把约半秒墙钟差直接当作
候选新增的交接停顿或模拟移动变慢。它不是控制实验，没有证明该机制解释所有差额；
firstMove 是 END 观察，也不是精确运动起点。
不替换原始墙钟指标、不丢弃原生基线、不以 tick 相同宣布没有回退。
最大返回节点数相同也不证明完整路径或实际路程相同。

### 配对诊断与旧中位数之差是不同统计量

本次补充下列诊断，未改写旧报告：

| 场景 | 双方到达的配对数 | 逐实体起点，到达差中位 | 共同批次起点，到达差中位 |
| --- | ---: | ---: | ---: |
| 8 格混合 | 64/64 | +555.8605 ms | +252.8235 ms |
| 28 格混合 | 50/64 | +331.6649 ms | +91.9308 ms |
| 8 格平地 | 8/8 | +370.6409 ms | +206.4580 ms |
| 28 格平地 | 8/8 | +60.6722 ms | +38.1162 ms |

这里先按相同 tile/slot/路线逐对相减，再取分位；旧表的 +555.2148/+355.5716 ms
及共同起点 +207.4742/+241.4176 ms 是各版本独立中位数之差，二者不应互换。
28 格原生 14 个失败仍保留在总分母，配对的 50 条不能代表全体成功完成。
不能将各阶段差额的中位数相加成端到端中位数。

## 第一优先：减少不必要的准备交接

代码入口为：

- [queue 与 followup](D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java:316)。
- [宏观结果交付](D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java:415)。
- [零边/最终段分支](D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java:499)。
- [真实详细搜索](D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java:546)。

目前普通宏观结果交付后先排 `nextSegment`；零边分支完成少量状态准备后，
再为真实最终搜索单独排队。调度器能在同一 tick 执行后继任务，
所以这不是“两次入队固定多等 100 ms”的证据。

首个值得收窄的代码候选是：

1. 仅对已经完成宏观、适用零边分支的请求，在既有主线程结果交付账目内完成
   不含真实搜索的状态准备，减少纯准备任务的入队与唤醒。
2. 真实 `findSegment` 仍作为原调度器任务独立准入，保持优先级、公平队列、
   生命周期、实际参数和完整计费；不内联原生搜索。
3. 不以距离、同 section 或组件标签提前绕过宏观，不新增整条原生试搜。
   涉及实体动态目标时，目标采样时机必须单独证明等价，不能因为优化静态测试而扩大适用面。

这是本轮本地分析进一步收窄的候选，精确实现尚未单独送审或验证。
其主要价值是减少首段就绪链路中的任务，不是降低 PathFinder 算法复杂度。
缺失证据是当前 S9L 中这些纯准备任务实际阻挡关键路径多久。
若历史事件表明它们已经连续完成，或主要时间在宏观构建/实体执行，
该候选收益可能很小，应停止而不是扩大预算。

剩余 callback/admission 诊断必须以当前源码为准。
10 月 19 日旧诊断中的 0.5 ms 启动余量与 idle 预算停止规则已经被移除，
不能把其 43 ms 队列等待原样算作当前仍可获得的收益。

## 第二优先：最多一个后继窗口模板预取

该方向在[10 月 13 日分析](D:/Workspace/AcceleratedNavigation-forge-1.20.1/docs/reports/2026-10-13-speed-four-segment-handoff-analysis-zh.md)中曾提出，
当时[第一阶段实现](D:/Workspace/AcceleratedNavigation-forge-1.20.1/docs/reports/2026-10-13-speed-four-phase1-validation-zh.md)没有采用预取。
这是有历史来源但尚未实现的方向，不是已取得净收益的旧候选。

当前 [GroundSegmentWindow.capture](D:/Workspace/AcceleratedNavigation-forge-1.20.1/src/main/java/com/scarasol/acceleratednavigation/topology/GroundSegmentWindow.java:54)
从已提交宏观成员快照枚举候选，工作的一部分不依赖未来实体位置，适合提前做；
最终实际 section 集与候选排序仍依赖交接时真实位置。

建议设计为：

- 当前真实 Path 正常执行时，只准备下一个确实需要的普通中间窗口，
  每请求至多一个待办或就绪模板；零边、最终段和没有后继窗口的路线跳过。
- 模板只含同一已提交 corridor 的不可变成员/transition 派生数据及候选。
  以请求代次、corridor 对象身份、cursor/stageEnd、stride、profile 绑定。
- 预取状态独立于 `work.preparing`，不得暂停正在执行的路径观察。
  交接时重新形成实际起点所需 sections、检查原四 section 上限、按原规则排序。
- 真实详细搜索仍从当时实体位置、在主线程与原调度准入下进行，
  不提前搜索或拼接预测位置的 Path。
- 不匹配或未就绪时走原准备流程；预取失败不使当前执行失败，不增加恢复额度。
  取消、替换、重规划、关窗和停服有明确资源释放，迟到结果不能挂回旧请求。
- 同时限制投机任务数量、内存和 worker 份额，不挤占必需查询。
  数据结构先复用现有不可变快照和小型候选容器；不引入全走廊提前枚举或全局无界缓存。

**不能按当前世界或当前图修订否决这个已提交 corridor 派生的模板。**
此处校验的是模板是否仍属于当前请求的同一已提交输入与阶段；
新查询使用新世界数据是另一职责。模板也不能变成新的可达性/能力资格过滤。

收益来自把准备及其异步交付从段间空档移到行走期间，不是仅省枚举的 CPU。
旧诊断有窗口请求至 worker 完成约 0.110 ms、后续主线程交付及搜索准入等待更长的样本，
但它既不是当前 S9L 的收益估计，也不保证预取消掉一次完整 tick。
8 格一搜路线没有该收益；先前窗口准备变快可能影响后来的最终到达，
不能将“最终段没有窗口”误说成“最终物理到达绝不受益”。

## 第三优先：普通走廊有界合段

若现有记录表明多次实际交接或绕行是主要开销，再研究普通 corridor 的阶段跨度，
限制在现有 sections、原局部 range、原能力与恢复规则内。
当前 shared Fv3 已有合段，这里不能把相同机制重新当作新成果。

其风险高于前两项：更大的真实搜索可能增加探索、partial 与恢复；
宏观连接少、几何端点近也不提供实际短路程保证。
本轮不设新的“直线距离小于某值”资格，不预定扩大 stride、
删除稳定条件或跳过真实节点。楼梯正向已知失败保持独立功能缺口，
不能以优化几百毫秒的名义掩盖它。

## 互联网项目和论文的取舍

| 原始资料 | 可借鉴的点 | 本项目的边界 |
| --- | --- | --- |
| Sturtevant/Buro，PRA*，AAAI 2005 | 将移动与后续局部细化重叠，复用高层计划 | 论文实验为静态二维模型，不给出 Minecraft 200 ms 保证；本项目仍须先取得已提交宏观结果 |
| Baritone，PathingBehavior.planAhead | 行走期间安排后继准备，明确取消与衔接状态 | 其路径拼接与移动模型不同，不能照搬预测起点 Path 复用 |
| Recast/Detour，PathQueue/PathCorridor | 有界排队、分阶段准备、沿实际位置维护走廊 | 本项目原生调用不可抢占；不能直接改成 Detour 式可切片搜索，也不能以 NavMesh 可见性过滤取代原版规则 |

原始来源：

- https://cdn.aaai.org/AAAI/2005/AAAI05-221.pdf
- https://raw.githubusercontent.com/cabaletta/baritone/master/src/main/java/baritone/behavior/PathingBehavior.java
- https://raw.githubusercontent.com/recastnavigation/recastnavigation/main/DetourCrowd/Source/DetourPathQueue.cpp
- https://recastnav.com/classdtPathCorridor.html

本轮不优先引入 JPS、D* Lite、CH 或替换整套寻路器：
固定栅格剪枝、增量修复和预处理的适用前提需要另行证明，
不能直接覆盖这里的三维能力、malus、动态世界及已提交宏观语义。
这不是证明这些算法无价值，而是当前尚未定位到需要替换搜索核心才能解决的主要成本。

## DeepSeek 官方调用与独立意见

用户本轮临时授权 `deepseek-official`。官方文档将 `deepseek-flash`
映射到 `DeepSeek-V4.1-Flash-1017`；实际模型列表只提供
`deepseek-flash`、`deepseek-v4-pro`，没有单独的 1017 ID。
请求及响应均为 `deepseek-flash`，不是证明底层不可变版本身份的独立日期 ID。

官方资料：

- https://api-docs.deepseek.com/quick_start/pricing
- https://api-docs.deepseek.com/guides/thinking_mode
- https://api.deepseek.com/models

原公共 review 工具硬编码旧 provider/model，本轮保持其文件和共享设置不变，
只在本次隔离目录使用官方 HTTP 客户端。凭据仅用于临时进程的 Authorization，
未写进提示、请求 JSON、报告或配置；临时环境变量在进程结束后清理。
共享 settings SHA-256 前后均为
`071db56517e8f97ccde780095f71e5b264a1c7bc41694499cf81af7c2aca1858`。

| 项目 | 记录 |
| --- | --- |
| 自包含提示 | 8170 UTF-8 字节；禁止外部工具/读文件/联网，要求独立提案和审查 |
| 参数 | thinking enabled，reasoning_effort=max |
| 最高输出参数 | 模型列表公布 393216；有效重试据此设置，实际输出远小于上限 |
| 有效调用时间 | 北京时间 2026-10-20 11:37:11 至 11:40:29，197.860 秒 |
| 返回 | HTTP 200，finish_reason=stop，ACCEPT_SCOPED，本轮必须修改项无 |
| 用量 | prompt 2053，completion 34498，其中 reasoning 30516 |

此前第一次请求为 HTTP 200 但最终文本为空，保留无效记录，不算审查结论。
其客户端未保存完整 response/usage，不能确定空文本具体原因。
随后保持同一提示和 max 推理，提高输出上限并补全回执记录，取得上述有效回答。
它不是两次认可或两份独立投票。

DeepSeek 独立提出：

1. 优先研究已就绪必需搜索的依赖交接，将其作为排序改进候选。
2. 下一窗口模板预取只有条件性、局部收益，不能独自解决一搜路线。
3. 普通走廊合段应后置；先区分计时原点、失败分母与模拟 tick 节拍。

本地采纳其问题排序和收益边界，但不照抄全部建议：

- 不直接将“关键搜索优先”接入生产，它会改变既有优先级/FIFO/公平顺序，
  且所有请求都可能同为关键。第一优先候选先减少纯准备任务，不增插队通道。
- 不接受模板消费时按新世界/新图修订撤销已提交输入的建议；
  transition 和成员应来自同一不可变 corridor，而非临时重查当前图。
- “final 不枚举窗口”只能说明该阶段没有直接预取收益，不排除此前接续影响最终到达。
- 已采用的 C 图剪枝与未采用的 empty-row P/air-run 等必须分开，
  不能将第 8 阶段的全部方向都写成应关闭。
- adopted ground move 直接走宏观执行，未收养的查询才先做真实 native decision；
  不为每次移动凭空添加一条“native decision→再次搜索”的成本链。

因此 ACCEPT_SCOPED 仅表示独立文本审查认可研究范围，不是本地最终细化方案、
实现、200 ms 达标或发布批准。完整原答保留，未为取得一致结论改写其意见。

## 下一步与停止条件

先复用现有逐请求、逐段记录，分别核对一搜平地和多段路线的首段交付、
真实搜索准入、段间无节点空档与恢复。现有 S9L 轻量记录不能重建的阶段明确列缺失；
不把旧预算规则下的诊断当成当前 S9L 的精确时间账本。

若缺少实际排队证据，暂不实现第一候选；若多段空档主要不是窗口准备/交付，
不实现第二候选；若合段增加搜索和恢复，就保持原跨度。
只有缺失的必要证据才另行提出最窄诊断，不默认复跑基线或扩大矩阵。
本轮不执行这些诊断或候选测试。

证据根：`.tmp/latency-200ms-research-20261020-v1/`。
`historical-diagnostic.json` 保存配对、逐类、事件映射与输入身份；
两版官方客户端、两次调用目录、原始资料、技术说明及本报告独立封存于
`final-seal.json`。已有封存和生产源码不变。
