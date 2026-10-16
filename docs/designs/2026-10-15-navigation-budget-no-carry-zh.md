# 导航预算取消跨 tick 偿债

日期：2026-10-15。用户随后明确要求删除全部方案外历史预测机制。当前生产代码已删除历史控制器、EWMA/P95、冷额度、额度增长和慢tick反馈；每tick固定20ms是本轮统一测量条件，保留实时截止、haveTime、完整实际计费、原子调用不可抢占及公平队列。当前主线见[闭包与骨架前置方案](2026-10-15-navmesh-prerequisites-plan-zh.md)，不再开展预算策略研究。

## 当前规则

每个正常 START 重新建立当前 tick 的额度。此前 tick 的正余额、超支负余额及 tick 间工作的余额不结转。完整计费仍保留；当前封套内、START 事件前已经发生的导航工作仍占用本 tick 额度。

```text
currentTickNavigation = totalAccounted(now) - chargeAtStart
balanceAfterRefill = grant - max(0, currentTickNavigation)
tokensNanos = balanceAfterRefill + activeElapsed(now)
```

当前 tick 中的原子调用超支仍可产生负余额，阻止该 tick 继续派发，并完整记录实际耗时。下一正常 START 按上述公式分配新额度，不用新额度偿还旧超支。尚未关闭、横跨 tick 的 account 继续按并集实际计费；通过封套边界快照排除此前工作，不能直接重置累计计费。

此前使用的32项窗口、EWMA/P95、前8样本冷额度、逐tick增长和慢tick后减半均已删除。每个正常START使用20ms新额度减本tick已发生导航计费；实时45ms目标减2ms固定尾部仍限制派发。固定参数并非最优参数或硬TPS保证。

## 与已完成研究的关系

[共享拓扑补充验证](../reports/2026-10-15-shared-topology-supplement-validation-zh.md)已结清容量修订、选定内存观察与 Base/Super 冷阶段对比，共享首版及补充取得有限 DeepSeek 共识，冷阶段没有稳定提速证据。[延后30秒冷请求对照](../reports/2026-10-15-cold-scheduler-delay-validation-zh.md)完成各4个独立 JVM，首 Path 等待均值559.0167/168.4308ms，第一次排队375.5962/2.3460ms，说明服务器启动状态显著参与了此前等待。

上述数据及审核对应保留跨 tick 偿债的旧生产包，不能当作本次修改后的性能或审核证据；冻结目录、原始数据及旧报告不改写。后续工作回到冷拓扑优化主线，不将删除偿债扩成新的预算策略研究。

验证只针对编译和已有预算回归中受影响的行为，包括同 tick 超支停止、下一 tick 新额度、零额度、完整计费及跨 START 的 account 边界。未提交、推送或部署。
