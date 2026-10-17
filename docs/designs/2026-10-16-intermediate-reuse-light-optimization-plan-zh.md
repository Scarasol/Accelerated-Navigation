# 构图中间产物复用轻量优化方案

日期：2026-10-16。

本方案根据 2026-10-16 的中间产物复用诊断结果整理，目标是提出可控、局部、容易回退的下一步优化。当前只形成设计，不修改生产算法、不改变调度预算、不调整锁边界、不引入持久化，也不把诊断探针放进生产包。

## 1. 当前证据与优化目标

已有对照显示：

- `Base` 构图阶段明显缩短，`anchors` 阶段的累计墙钟约减少 86%；
- 详细寻路次数没有增加，候选没有因复用而多做一批详细搜索；
- 复杂场景重复校准为基线 `4.682913ms`、候选 `4.751888ms`，差值 `+0.068975ms`；
- 低干扰两 mod 对照为基线 `4.1918ms`、候选 `4.6969ms`，差值 `+0.5051ms`；
- 非 `anchors` 残差仍有增加，现阶段不再继续拆分其具体来源；
- 旧正式完整 tick p99 从 `5.105850ms` 到 `6.714900ms`，增加 `1.609050ms`，门限 `6.616435ms`，该验收结果继续保持 FAIL。

因此下一步只针对“复用已经生效后仍然存在的固定工作”做轻量削减。优化成功的判断是：保持功能和生命周期契约不变，同时降低候选在非 `anchors` 路径中的额外工作。任何单项改动都必须独立对照，不能用扣除 GC、锁等待、实体更新时间或探针成本的方式制造通过。

## 2. 优先级

### P0：按组件身份去重 `ComponentSnapshot`

位置：

- `src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java`
- `src/main/java/com/scarasol/acceleratednavigation/topology/MacroSearch.java`

当前 `completedComponents` 以完整 `ComponentEndpoint` 作为 key。不同 endpoint 可能指向同一个 section、revision、channel 和 component id，导致同一组件重复创建 `ComponentSnapshot`。`ComponentSnapshot` 构造函数会扫描整个组件的 4096 个 cell，重复构造会把已经完成的宏观结果转成重复的固定工作。

建议：

1. 引入只描述组件身份的内部 key：
   `section + revision + channel + componentId`。
2. 先以组件身份建立唯一的 `ComponentSnapshot`；
3. 最终仍建立 `ComponentEndpoint -> ComponentSnapshot` 的结果映射，保持 `Corridor.component(endpoint)` 的现有调用契约；
4. 继续保留 `MAX_QUERY_VISITED_NODES` 的上限和已有的 section、revision、channel、componentId 一致性校验。

功能不变量：

- 相同组件身份最多扫描一次；
- 不同 revision、channel、section 或 component id 绝不能共享快照；
- `contains(BlockPos)` 的结果与当前实现逐格一致；
- `Corridor` 的 endpoint、connection、cost、snapshot 生命周期和关闭顺序不变；
- 完成组件数的限制仍按 endpoint 结果规模执行，不能因为去重绕过安全上限。

最小验证：

- 对同一组件包含多个 endpoint 的 corridor，断言只发生一次 4096-cell 扫描；
- 对不同 revision、channel、section、component id 的组合，断言不会误共享；
- 逐格比较旧实现和新实现的 `contains` 结果；
- 使用现有复杂场景和低干扰场景各做一组 AB/BA 对照，保留完整 tick p99、构图阶段耗时、详细寻路次数和自然终态。

### P1：把 `RawRows` 改为有界 identity cache

位置：

- `src/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java`

当前 `RawRows` 使用 `WeakHashMap<PackedFacts, Rows>`、`WeakReference` 队列以及每次新 source 的弱引用清理。该缓存只被单个串行 `BuildScratch` 使用，已有 `MAX_SOURCES = 64` 和 `clearCachedRows()` 清理边界，弱引用管理本身可能带来额外的 map、队列和 GC 工作。

建议：

1. 保留按 source identity 命中的语义；
2. 使用固定容量为 64 的 identity cache，优先采用简单数组槽位或 `IdentityHashMap` 加显式 FIFO；
3. 保留 source 超过容量时的最老条目淘汰；
4. 保留 `clearCachedRows()`，并确保 worker 结束或复用边界仍能清空；
5. 不保留对 source 的跨 `BuildScratch` 强引用。

功能不变量：

- `PackedFacts` 只按对象身份命中，不改成内容相等；
- 同一 source 在同一缓存生命周期内仍只生成一份 `Rows`；
- 容量上限仍为 64；
- cache clear 后不得返回旧 source 的行；
- 不改变 `packedRow`、`compact`、row ready 位图和最终几何结果。

最小验证：

- 运行 source 数为 1、64、65 和重复 source 的单元/基准输入；
- 检查命中、淘汰、clear 后重建和 retained bytes；
- 保留已有 JMH 配置，完整覆盖建立、查找和 clear；
- 在真实场景中只比较候选与当前基线，不把缓存实现变化和其它优化混在同一次提交里。

### P1：预计算 `RawRows.fill()` 的几何映射并拆出常见快路径

位置：

- `src/main/java/com/scarasol/acceleratednavigation/topology/BaseClusterTopology.java:1199`

当前 `fill()` 在每个 extended row、每个 source chunk 组合中重复执行 `floorDiv/floorMod`、source chunk 边界计算、`Arrays.binarySearch(input.offsets, ...)`、局部 mask 计算以及 door/exact 分支判断。对于同一 `BuildInput` 和 `GeometryKey`，这些几何关系在一次 fill 内是稳定的。

建议分两步实施：

1. 在一次 `fill()` 开始时预计算 row 到 source chunk 的映射、`from/to`、local mask、shift 和 source 索引；
2. 对没有门、没有 exact occupancy 的常见路径使用无门快速分支；保留门或 exact occupancy 的完整路径，避免改变特殊几何语义。

功能不变量：

- extended row 的遍历顺序不变；
- fluid、ground support、door fallback、exact occupancy 和缺失 halo 的处理结果不变；
- `scratch.haloUsed` 的标记集合不变；
- 中心 section `y == 0` 的 lower halo 推导规则不变；
- 仅缓存本次 fill 可复用的映射，不扩大 source 或 body 的生命周期。

最小验证：

- 对普通无门几何、带门几何、exact occupancy、缺失 halo 和跨 chunk 边界分别做结果逐行比较；
- 断言 `volumeRows`、`groundRows`、`erodedRows`、`fullCollisionRows` 和 `haloUsed` 与旧实现一致；
- 先跑现有 JMH，再跑真实复杂场景；
- 若快速路径出现任何结果差异，保留预计算映射，撤回快速分支，不扩大改动范围。

## 3. 实施顺序与验收门

实施顺序固定为：

1. P0 `ComponentSnapshot` 去重；
2. 重新跑正确性和最小真实场景对照；
3. P1 `RawRows` 有界 identity cache；
4. 重新跑缓存基准和真实场景对照；
5. P1 `fill()` 映射预计算；
6. 重新跑几何矩阵和真实场景对照。

每一项都单独形成候选包和结果记录。只有在前一项的正确性、自然终态、清理和结果契约通过后，才进入下一项；不得把三项合并后再凭总成绩猜测收益。

工程筛选门：

- 结果正确性、自然终态或清理失败：立即停止该项；
- 真实场景的详细寻路次数不得增加；
- 完整 tick p99 不得以单次异常样本判断，至少保留配对 JVM 的中位数和原始尾部；
- 复杂场景候选若仍高于基线，只记录差值，不通过删掉慢 tick 或扣除共同探针重算；
- 未达到门限时，只允许继续做局部轻量优化，不改变验收门、不调预算、不引入债务或预加载替代。

## 4. 明确排除项

本方案暂不处理：

- 持久化和跨进程缓存；
- 调度、预算、偿债或队列策略；
- `runtimeLock` 范围调整；
- Portal/Boundary 骨架；
- 预加载类或把成本移到请求窗口之前；
- 继续追查旧 p99 尾部的具体干扰来源；
- 将诊断探针、JFR/WPR 或测试辅助代码植入生产区。

完成三项轻量优化并通过独立对照后，再决定是否值得进入生产接入评审。当前文档本身不构成生产放行结论。
