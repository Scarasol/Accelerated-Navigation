# 构图中间产物复用轻量优化交接

日期：2026-10-16。

本轮已按用户要求停止。本文档用于交接当前状态，后续不得把临时原型或局部 JMH 结果视为生产接入。

## 当前结论

三个方向仍有不同程度的研究价值，但没有任何一个方向在本轮接入生产：

- `ComponentSnapshot` 按组件身份去重：证据最强，值得优先继续。
- `RawRows` 固定槽位 identity cache：正确性通过，但受控 JMH 没有稳定收益，当前不应接入。
- `RawRows.fill()` 几何映射预计算：正确性通过，受控 JMH 只有小幅、波动中的收益，当前只能保留为候选。

旧正式完整 tick p99 的 FAIL 不变：基线 `5.105850ms`，候选 `6.714900ms`，差值 `+1.609050ms`，门限 `6.616435ms`。

## 与另一个会话的冲突检查

另一个会话使用独立目录：

`.tmp/current-performance-reference-20261016/`

其工作是原生/加速范本测试，没有修改本轮三个优化方向的生产实现。本轮所有候选修改均放在独立 `.tmp` 目录，没有修改共享生产源码。

## 已完成文件

设计文档：

- `docs/designs/2026-10-16-intermediate-reuse-light-optimization-plan-zh.md`

该文档提出：

1. P0：`ComponentSnapshot` 按 `section + revision + channel + componentId` 去重；
2. P1：`RawRows` 有界 identity cache；
3. P1：`RawRows.fill()` 几何映射预计算和常见路径优化。

## P0：ComponentSnapshot 去重

临时原型目录：

`.tmp/intermediate-reuse-light-p0-20261016/`

候选只修改临时副本中的：

- `candidate/src/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java`

验证结果：

- 5 个 endpoint：候选 4 个唯一 snapshot，基线 5 个；
- 1024 个相同组件 endpoint：候选 1 个 snapshot，基线 1024 个；
- 三次重复 capture：
  - 候选：`5.2603 / 5.2066 / 6.1583ms`
  - 基线：`21.1326 / 22.7724 / 22.3911ms`
- CoreContracts：9 项通过；
- reverse integration contracts：3 项通过；
- SharedRuntime：1296 项通过。

P0 仍缺的门：

- 在同一 2380 输入语料上补最终图/输出哈希 0 差异；
- 增加同身份、多 endpoint、上下文混合时的只读别名测试；
- 增加容量边界和清理生命周期验证。

## P1：RawRows identity cache

临时原型目录：

`.tmp/intermediate-reuse-light-p1-cache-20261016/`

候选使用 64 个固定弱引用槽位、`Rows[]` 和 FIFO cursor，保留 identity 语义、弱 source、clear 和容量上限。

正确性：

- 2380 个构图输入 graph/raw-array/halo 哈希：0 差异；
- 64/80 source、FIFO、clear、GC 释放：通过；
- 后续补加了 equal-content source 不共享 identity slot、弱槽交错后的新 source 检查，但用户要求停止时尚未重新运行这版补充门。

受控 JMH 三轮随机顺序、含 baseline null-control 的汇总：

- single：
  - baseline 均值约 `38.206us`
  - candidate 均值约 `39.609us`
- complex：
  - baseline 均值约 `5857us`
  - candidate 均值约 `6069us`

当前判断：正确性通过，但没有稳定性能收益，候选还有轻微回退信号。不要接入生产。

## P1：fill 几何映射预计算

临时原型目录：

`.tmp/intermediate-reuse-light-p1-fill-20261016/`

候选使用 `BuildScratch` 复用 27 项 source index 映射，并以算术移位和按位 mask 替代固定 16 的 `floorDiv/floorMod`。

第一版每次 fill 新建映射数组，complex 明显变慢，已废弃；当前目录中保留的是 BuildScratch 复用数组版本。

正确性：

- 2380 个构图输入 graph/raw-array/halo 哈希：0 差异；
- door/exact/fluid/ground support/haloUsed 语料检查通过；
- scratch 连续复用检查通过。

受控 JMH 三轮随机顺序、含 baseline null-control 的汇总：

- single：
  - baseline 均值约 `38.763us`
  - candidate 均值约 `38.299us`
- complex：
  - baseline 均值约 `6166us`
  - candidate 均值约 `5803us`

结果方向略有改善，但误差和轮间波动仍较大，不能作为生产放行依据。

## DeepSeek 审核

送审目录：

`.tmp/intermediate-reuse-light-optimization-review-20261016/`

实际配置已由脚本核对为：

- provider：`deepseek`
- model：`deepseek-v4.1-flash`
- prompt：3761 UTF-8 bytes
- elapsed：约 83.5 秒

结论：`ACCEPT_WITH_GATES`。

DeepSeek 要求的门：

1. A 补 2380 语料等价性和只读别名；
2. B 补 identity/弱槽语义，并用统一、随机顺序、含 null-control 的 JMH；
3. C 补负坐标、固定 16 常量和 scratch 陈旧映射；
4. A+B+C 联合启用时补生命周期与清理验证；
5. 这些结果不能解除旧正式完整 tick FAIL，也不能直接放行生产。

用户要求停止时，B 的补充语义门已写入临时 harness，但尚未运行；不要把它写成通过。

## 生产状态

本轮没有修改、提交、推送或发布生产代码。工作区原有未提交改动保持原状，未回滚、未清理。

后续如需继续，应先由用户重新授权，并从 P0 的 2380 语料等价性和别名门开始；在此之前不要把任何临时目录中的候选类复制回 `src/main`。
