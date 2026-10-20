# 空闲补发按实际墙钟余量执行

日期：2026-10-19。用户要求同时取消墙钟截止前额外0.5ms门槛，
以及tick主体结束后空闲补发的预算余额限制。
证据根：`.tmp/scheduler-idle-slack-20261019-v1/`。

## 实现

仅新增一处生产文件改动：`NavigationScheduler.java`。
已有C拓扑三文件保持，不重做或回退。

- 常规END调度继续使用20ms额度及已有任务预算启动条件。
- 正常tick主体结束后，只要还有待办，就请求一次合并的空闲派发；
  已有任务不必等新的异步回调才获得补发机会。
- 原宏观/共享/窗口就绪回调仍可请求合并派发，沿用原队列、优先级、
  维度轮转、FIFO和生命周期；没有将原生搜索内联进回调。
- 空闲派发不再检查预算余额，包括零余额和负余额；仍完整计费，
  真实负余额不截断，下一正常tick重新建立20ms额度。
- 常规和空闲调度的墙钟条件均改为绝对截止前余量大于零；
  队列总检查和每任务检查均取消额外0.5ms提前量。
  常规预算门槛与墙钟门槛明确分开。
- 真正调用任务前再次核对截止时间。原截止为tick开始后43ms，
  恰好截止及之后不启动新任务；实时haveTime和暂停/异常保护保留。

同步原生调用仍无法中途抢占。43ms约束新任务启动，不保证已启动搜索
在43ms或50ms内完成。此前登记的迟到trampoline代次边界保持，
本轮没有宣称修复该独立事项。

## 验证范围

运行前固定`protocol.md`，保护155份其它生产/测试/诊断工具及默认JAR输入。
使用现有隔离构建和final-window runner，在全新模板副本中只运行一个游戏JVM：
先执行两遍更新后的fake-clock预算控制，再执行
`zero-edge8-first,cross8`两条真实移动路线。
保留原服务器/场景30秒条件、JVM参数及清理检查。

控制新增墙钟余量400us、100us、1ns，精确截止、截止后及haveTime检查期间
跨过截止的场景；覆盖普通END耗尽后的补发、补发中继续超过20ms、
零余额协作任务的依赖停放/唤醒、完整计费、outside记账和下一tick不结债。
fake-clock控制显式驱动ready，不向真实服务器队列投递模拟时钟任务；
真实游戏路线验证实际接线。

build-v1是补齐tick主体结束唤醒之前的中间构建，编译成功但未启动游戏；
build-v2包含最终实现和控制，编译及混淆成功。

唯一游戏JVM退出0，无超时或其它Java干扰，预算控制两遍共286条断言通过。
原公共`analyze-controls.mjs`核验两条路线技术有效，trace和完整tick检查通过，
关服记录全部通过：

| 路线 | 实际到达/终态 | 真实搜索 | 中间窗口 | 首Path ms |
| --- | --- | ---: | ---: | ---: |
| zero-edge8-first | 到达/SUCCEEDED | 1 | 0 | 116.5695 |
| cross8 | 到达/SUCCEEDED | 2 | 1 | 18.9395 |

最终搜索的range/accuracy保持原请求，无最终窗口和pending窗口。
真实trace共958事件、90次envelope结束、3次就绪请求；
这两条轻路线没有观察到来自envelope结束内部的就绪请求。
因此真实队列接线通过，但不能把本轮两场写成已现场复现“END预算耗尽后
自动唤醒”的运行见证；该结束触发点经源码核对，预算耗尽后的补发与记账
由fake-clock控制覆盖。原始`finish-wake-observations.json`保留零计数。

最终62份生产输入及预算控制源码与build-v2身份相同，
155份保护输入逐份哈希保持，包括C拓扑三文件及默认两JAR。
实际游戏生产JAR SHA-256：
`d346776261b3471dfa8176143a59e34c29f9e2a7f308ea1ef51bf996e2fb9f4f`。

本轮不作B/C性能对照，不能据此宣称首Path、CPU、分配或tick尾部已经改善，
也不以规则修改冲销此前C/B两轮净测的不利值。
默认`build/libs`两JAR保持；未提交、推送或部署。

证据包括`protocol.md`、`protected-inputs.json`、两份构建日志、
`build-v2/build-identity.json`、`controls-game-v1/controls-analysis.json`、
`budget-controls.json`、原trace/完整tick/launch/exit/shutdown，
以及最终`verification.json`和`seal.json`。
