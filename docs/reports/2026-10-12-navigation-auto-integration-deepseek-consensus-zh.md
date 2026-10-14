# Navigation自动接入方案DeepSeek联合共识

> 已失效的设计共识：本报告基于已经废弃的旧设计，不具备当前架构验收效力。原始讨论仅保留为历史事实；当前实现必须独立按 [2026-10-13 原版详细寻路接入契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)送审，禁止继承本文的通过结论。

日期：2026-10-12。目标工程：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`。

结论：Codex与DeepSeek已对[完整接入规格](../designs/2026-10-12-navigation-auto-integration-plan-zh.md)达成设计共识，无未解决的实质设计分歧。共识支持进入第0项接线核对和四个定点原型，不代表实现、运行、性能或实体覆盖已经通过。

用户指定WSL中dsh无头调用、provider为deepseek而非deepseek-official、模型4.1flash，并授权补充论据反复审查到共识。本轮实际使用`dsh --profile headless`，核实既有默认配置为`deepseek/deepseek-v4.1-flash`；没有更换模型或修改设置。

## 达成一致的设计

- 保留实体实际Navigation对象及MoveControl，自动兼容原版moveTo和已适配的createPath→moveTo，不要求实体或模组改用新API。
- 宏观走廊引导主线程原版局部同步搜索，初始每段最多两个连接；请求Path只持当前真实节点，父类同一List原地更新。对外Navigation.isDone表达全程，Path.isDone及导航内部判断保护真实当前段，含null和同tick耗尽。
- 全服务器共享约4ms/tick软预算，正余额最多4ms，负债保留；受控搜索在END派发，wake只入队。目标准备、搜索、trim/发布及同步完成回调按区间并集记账，不暂停恢复单次PathFinder，也不声称4ms硬上限。
- 普通Path.canReach使用最终目标的宏观结构结果及合法终点见证；TargetGoal私有canReach、LongJump的8格步行等保留原版有界真实结果，部分Path的既有判定不被抹掉。
- 原版决策消费者通过显式就绪载荷和定点控制流适配等待，不靠boolean false表示等待。AcquirePoi固定选定原POI目标；待定批次不写失败退避；LongJump最后一个已取出的待定候选仍计入继续条件。
- 任务/查询/子owner及控制代次有界，同目标新控制来源建立新身份；旧Goal.stop不能清新任务。能力flag/malus保存、临时应用、恢复；原版查询隔离受控Path复用捷径，不抢活动路径。
- 特殊Navigation按其原执行行为分别适配；未取得资格的消费者、未知同步查询及未知override明确兼容回退。LEGACY_SYNC_QUERY同步返回真实结果并扣同一预算，但不享有END排队的平滑保证，单列覆盖与成本。

## 审查与分歧处理

每次调用都提供当轮完整方案与必要实际源码；后续轮附原意见及Codex纠正。要求DeepSeek只作文本评审，禁止它调用工具、执行代码、读写文件或联网。各轮原始答复、推理输出、输入快照与退出记录保留，未覆盖前轮。

| 有效轮次 | 过程与结论 | 退出/耗时 |
| --- | --- | --- |
| [初评](../../.tmp/navigation-auto-review-deepseek-20261012/deepseek-answer-initial.txt) | 接受架构方向，要求补清局部判断、PENDING状态、终点见证、能力回灌、多目标及记账 | 0，无超时，285.757秒 |
| [修订复审](../../.tmp/navigation-auto-review-deepseek-20261012/deepseek-answer-revised.txt) | 接受七项源码纠正及修订；剩四个同步决策的具体截获/恢复点未闭合 | 0，无超时，86.448秒 |
| [接线复审](../../.tmp/navigation-auto-review-deepseek-20261012/deepseek-answer-closure.txt) | 确认四点闭合；新增有效意见：LongJump末候选移除后canStillUse会提前终止 | 0，无超时，97.781秒 |
| [最终确认](../../.tmp/navigation-auto-review-deepseek-20261012/deepseek-answer-final-retry.txt) | 确认末候选继续条件、AcquirePoi不可用释放和TargetGoal调用阶段均闭合，明确无实质异议 | 0，无超时，38.430秒 |

Codex没有直接接受以下错误推断，而是补实际源码再审；DeepSeek第二轮明确接受纠正：

- WallClimber不是仅path为null才回退，非null但耗尽也进入原攀墙分支；修订增加fallback-ready并禁止首次PENDING提前攀墙。
- ResumableSearch.park已要求回调在方法返回后调用，不能把违反该契约的同步回调当成已证实缺陷；本方案详细原子任务不park。
- Scheduler.submit已有同键remove/cancel，首评的相反推断基于未展示的源码；补完整源码后撤回。
- 坐标moveTo在null时清旧路径，Entity重载则短路保留旧路径；不把所有失败统一解释为取消。
- AcquirePoi的5候选上限及批次validRange最大值是其实际源码，不普遍截断其他Set输入；选定POI位置与邻近站立见证分开。
- 当前sendPathFindingPacket为空方法，但writeBrain仍调用Path.writeToStream；最终规格保留动态同格式序列化。

有效意见则全部并入规格，尤其第6.1节明确AcquirePoi触发lambda、Sink清目标前保护、TargetGoal写缓存前保护、LongJump候选/计数/继续条件和READY恢复。细项对照见[问题核对表](../../.tmp/navigation-auto-review-deepseek-20261012/issue-ledger.md)。

最终确认原文：

> 双方对本完整最终候选设计已达成共识，无未解决的实质设计分歧。

另一次`final`启动在进入模型审查前被CLI拒绝：多段文本的一段以减号开头，被识别为选项，退出1，无有效答复。已保留`deepseek-process-final.txt`及退出记录，仅修复参数分块的前导换行，以`final-retry`重送同一方案；不把该失败算作有效审查。送审材料准备时一个ShootTongue源码路径错误亦在模型调用前纠正。

## 版本与核对

最终完整方案SHA-256：
`5f3dfef4e1fd02a8c1ab97d4d9627f1e5cc9656ec3be3598c67cada4fa666d0f`。

最终确认绑定这个完整版本，收尾没有再修改该规格。原始源来自当前Forge47.3.0 official1.20.1 sources.jar，SHA-256：
`5905C044CBF9D4CD0208F8878BFD7B586782C44366C277D6FBD70E4B075C0EB2`。

最终附件包含14份实际Forge源码与3份当前生产源码，逐文件身份保存在各轮identities。收尾核对脚本检查附源与jar一致、生产附件未变、完整方案与送审快照/哈希一致、四次有效调用退出及非空答复、文档本地链接；结果写入`.tmp/navigation-auto-review-deepseek-20261012/final-verification.json`。额外target目录调用者核对由Codex完成，未作为DeepSeek独立核对或穷尽覆盖证明。

## 接下来与证据边界

第0项先核对当前字节码、合成lambda/局部捕获、required注入和调用阶段。之后分别建立四个小闭环：Ground空节点/null/同tick耗尽；Melee或Avoid准备型加AcquirePoi决策型；完整预算/回调/END；多POI串行owner/见证/固定占票目标。未覆盖的类型维持明确兼容回退，再增量纳入其他消费者。

既有132条分段链和并发预算结果复用，无需因文本评审重做已有效的分段/分片对照。真实行走仍有两个已知高度不匹配，历史真实执行0条完整路线未改为通过；未知能力、特殊导航、真实Goal/Brain接入及正常服务器背景负载仍待验证。

本轮只修改方案、审查/恢复文档和临时送审材料；没有实施生产导航、构建或启动游戏，没有新增Java性能采样，没有提交或push，用户原有生产改动保留。DeepSeek共识不是运行可靠性证明。
