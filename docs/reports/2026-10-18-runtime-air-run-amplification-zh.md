# R2 连续空气段执行放大验证

日期：2026-10-18。

本轮按用户要求回到旧“连续空气段合并第二版”（R2），目标是减少
`TopologyWorkerRuntime` 从 `BuildInput.visitFactRuns` 到事实引用更新之间的
执行交接成本。所有改动都在
`.tmp/runtime-air-run-amplification-20261018-v2/D` 隔离副本内，未修改生产源码、
默认 JAR、生产输入或当前工作区接入代码。

## 选择的候选

候选 D 将已有的有序 run 循环放入独立的私有
`changeTopologyFactRuns` helper：

- `BuildInput` 只增加 `factAirSlots`、`factSlotCount`、`factAtSlot` 三个只读访问器；
- 单元素空气段直接调用原 `changeFactReferences`；
- 多元素空气段继续调用原 `changeFactReferenceRun`；
- 原 `visitFacts`、原标量引用更新、加权 helper 和 `applyFactReferences` 保持不变；
- 不增加字段、数组、引用 cell、缓存、依赖选择或所有权语义。

它实际尝试删除的是 run 路径中的一次通用 `ObjIntConsumer` 交接和单元素进入
加权 helper 后再回退的路径。JIT 可能已经内联这些调用，因此没有预先假定收益。

## 正确性结果

以下检查全部通过：

- 926 个保护输入哈希在测量前后不变；
- 11,115 个失效构型；
- 9,073 个引用状态场景，其中 4,963 个异常、负数、溢出和失败前缀场景；
- 6,048 个摘要构造、替换和展开场景；
- 77 项真实 owner 生命周期账目；
- Core、Boundary recovery、Door、Worker、Shared stage、Shared runtime 六组契约；
- 新增 16,382 个有序 run 差分场景，包括长度 1 至 12 的全部空气位图、
  首槽、尾槽、满 27 槽、孤立空气、跨非空气边界和后续异常；
- 旧 R2 与 D 的最终引用计数、字节账、map 状态、异常类型及完整前缀一致。

完整冷查询中，三种输入模式的 endpoint 数量、连接数、代价和排序后的组件位图
在各变体内部 64/64 一致，worker 关闭后无残留 owner。

## 完整 worker 冷查询

每次测试创建新 runtime，发布相同的 63 个 section 版本，确认 Base、Super 和
corridor 派生缓存为空，然后从 `(2,81,8)` 查询到 `(77,81,8)`。查询覆盖依赖
构建、Boundary、层次搜索、结果组件快照和关闭账目。每个模式 24 次 warmup 加
64 次 measured，执行顺序为 `O,D` 与 `D,O` 两对。

D 相对 O 的结果如下：

| 模式 | wall mean（两对） | wall median（两对） | process CPU mean（两对） | 分配 |
|---|---:|---:|---:|---:|
| air | -13.04%、-9.52% | -2.63%、+4.97% | +9.72%、0% | -0.53%、-0.78% |
| distinct | -3.71%、+4.92% | -3.80%、+5.78% | +26.47%、+11.54% | 约 0%、-0.21% |
| mixed | -6.41%、+0.94% | -6.30%、+1.99% | -29.41%、-30.77% | -0.37%、-0.62% |

Windows 进程 CPU 对单个小查询存在量化，不能从单个 CPU 百分比推断收益。
任务数量、依赖等待和展开工作量没有对应的结构性减少；没有 GC 事件。

既有 readyGraph 热态辅助结果也不稳定：air wall 为 -2.14%、+1.54%，distinct
为 +5.55%、+2.67%，mixed 为 +0.14%、-3.23%。因此局部 air 均值不能替代完整
查询结论，也不能取得生产性能资格。

## 复核和决定

DeepSeek 自足复核包 `followup-packet.md` 为 3,372 字节，固定
`deepseek/deepseek-v4.1-flash`，28.723 秒返回 `ACCEPT_SCOPED`，本轮必须修改项无。
它的建议是停止这条只削减常数项的路线：

- D 只作为诊断件封存，不进入生产；
- 不继续启动 B′跳段遍历；
- 不启动 `ALL_AIR` 专用引用 cell，因为它会把额外身份分支扩散到所有 scalar
  入口，并引入 map 镜像一致性维护；
- 若未来必须继续，只能单独验证 B′，并要求 air 的均值和中位数同向、distinct
  的 wall/CPU 退化不超过 3%。

本轮因此不再扩样、不改生产、不提交或推送。D 的源码、编译身份、正确性输出、
冷查询输出、readyGraph 输出、分析脚本、顾问包和复核原件都保留在
`.tmp/runtime-air-run-amplification-20261018-v2`。后续研究应回到能减少
Base/Boundary 实际构建或依赖闭包工作量的结构性候选，而不是继续压缩引用更新
循环的常数项。
