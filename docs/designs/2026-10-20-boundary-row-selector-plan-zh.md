# Boundary 行位图选择器修改方案

日期：2026-10-20。版本：V3，仅替换选择器。
状态：DeepSeek 1017 对应官方别名、max 复审 ACCEPT_SCOPED，本轮必须修改项无。
批准范围仅限方案及后续有限评估，不是实施、性能或生产批准。
本轮仅方案与外审，未授权实施或测试。
材料：`.tmp/boundary-bit-selection-plan-20261020-v1/`。

## 结论和限制

从当前 S9L 独立建立后继，不恢复 S1 阶段调度合并。
保留现有 worker 窗口准备、完整候选列表及恢复邻域，
只增加合法候选的行位图索引，替换正常 Boundary 的全量排序。
保持原评分、X/Y/Z 同分顺序和最多八个不同备选点，不能退成任取一点。

**单行选举为 O(1)，完整选点为 O(8R)，R 是非空行数，最多 256。
严格整连接 O(1) 尚未取得，不能把固定网格常数当作已解决该要求。**
稀疏时 R 可能等于候选数 N；本案只是可审查的小范围性能候选，
不是已证明优于原方法，也不承诺到达增量压入 200 ms。

首次有效审查要求修改的 FULL_LAZY 路线已经撤下。
不修改列表接口，不延后恢复生成，不改变关闭后读取，不新增快照保留、
worker 回补物化或 main 上的全量展开，避免把成本迁移冒充消除。

## 改动范围

预计涉及 `GroundSegmentWindow`、独立的纯选择器 helper、
`SegmentedNavigationService` 的正常选点调用、
`TopologyService` 的请求局部布尔透传及必要正确性控制。
不改 `TopologyWorkerRuntime`、构图语义、component 身份、共享拓扑、调度器，
不新增 checkpoint、跨 tick 状态机、队列优先权或预算检查链。

适用条件：

- 普通 corridor 当前末边为 Boundary，attempt 为 0，failedTargets 为空。
- 输入仍是原合法候选的不可变、去重列表。
- 全部候选位于同一个 destination section，且通过下述数值门。

LocalTransition、shared、final、恢复、无索引、越界、门不满足均使用已有列表和旧排序。
不重新捕获地形、不拒绝导航、不重新派发 worker、不增加额外等待。
旧正常候选／恢复集合已经准备完成，fallback 可直接复用。
空集合保持原行为，不拿一个不存在的置位解码。

## 源码依据

当前生产：

- `SegmentedNavigationService:532` 使用 `p.distSqr(mob.blockPosition())`
  与 `.05*p.distSqr(request.getTarget())`，再按 X、Y、Z 排序。
- `NavigationRequestPath:24/69` 的 target 字段和 getTarget 返回值均为 BlockPos。
- 当前映射的 `Entity.blockPosition()` 返回 BlockPos，不是 Vec3。
- `findSegment:545` 先保存 targets 再 awaitNative；等待闭包保留这批 targets，
  唤醒后不重选。本案保持同样时机。

本地 Forge 1.20.1-47.3.0 mapped_official source jar 核对：

```java
public double distSqr(Vec3i point) {
    return this.distToLowCornerSqr(
        (double) point.getX(), (double) point.getY(), (double) point.getZ());
}
public double distToLowCornerSqr(double x, double y, double z) {
    double dx = (double) this.getX() - x;
    double dy = (double) this.getY() - y;
    double dz = (double) this.getZ() - z;
    return dx * dx + dy * dy + dz * dz;
}
```

变量名为便于阅读而替换，运算顺序保持。
source jar SHA256：
`5905c044cbf9d4cd0208f8878bfd7b586782c44366c277d6fbd70e4b075c0eb2`。
完整旧选择器和类型摘录见材料中的 `source-evidence.md`。

## 一、worker 内增建集合索引

窗口依旧按原方法完整枚举、源／目标成员校验、LinkedHashSet 去重，
并生成正常列表及原恢复邻域，全部保留。

对已经合法去重的正常列表多扫一遍，在 worker 构建：

1. destination section 的 4096 位目标占用图，64 个 long，512 B。
2. 行内轴按 face 选：N/S/U/D 为 X，E/W 为 Z；另外两轴编码 256 行，
   每行 16 位。
3. 四个 long 的非空行摘要，32 B；目标按位合并，不重复计入名额。

这是集合索引，没有提前计算任意 a/t 的最优点或 top8。
构建时间 O(N)，新增约 544 B 原始位存储，另计对象头、元数据和查询临时对象。
不存 4096 项的 BlockPos 引用数组，不复制原列表，不新增全图／组件缓存。

worker 通过 long 差值校验每个点属于 destination section。
不满足时不生成快索引，保留旧完整窗口。
窗口虽然可以覆盖四个 section，目标索引只允许一个 destination section。
由原候选列表构建，合法集合与旧算法完全相同，不删除成员验证。
现有 capture 接口不传 attempt／failedTargets，不能假装 worker 已知其值。
为落实已审适用范围，准备调用点冻结 `buildBoundaryIndex` 布尔值并透传：
仅正常 Boundary、attempt0、空 failedTargets 时为真。
旧 prepare/capture 签名保留、默认 false，通过重载增加 opt-in，
不让其它调用和恢复窗口默认付出索引成本。
主线程使用时仍检查本轮适用条件；准备后资格失配、取消或迟到造成的
未使用索引成本同样计入。这是已审范围的具体落实方式，需后续代码审核，
不是新增调度状态机或查询身份规则。

## 二、原调用时机下的行内选举

在原正常选点调用开始，读取不可变整数 `BlockPos a/t`，
不读取 worker 旧位置，不改变 awaitNative 后持有已选 targets 的行为。
现有流程在同一次主线程调用中执行评分，没有实体 tick 插入排序。

原评分的实数恒等式：

```text
S(p) = |p-a|² + (1/20)|p-t|²
     = (21/20)|p-q|² + (1/21)|a-t|²
q = (20a+t)/21
```

行内只有 u 改变，另外两轴固定。令 u=0 的世界坐标为 o：

```text
Q = 20*a[u] + t[u] - 21*o[u]       // long
pivot = clamp(floorDiv(Q,21),-1,15)
```

先 floorDiv 和夹取，再转 int；不能用截断除法或先转 int。
所有规范行的行内轴与该轴世界原点相同，Q、pivot 及左右区域掩码
每次查询只计算一次，不在每行重复除法。
将行掩码按 pivot 分为左右两侧：

- 左侧最高置位、右侧最低置位分别是该侧的最优头。
- 至多比较两个头，使用原 double 评分及 X/Y/Z 完整比较器。
- 输出较优者，只清本地 int 掩码中的该位，再取两侧新头。
- 每行至多输出八项；空位图、pivot=-1/15、移位边界显式处理。

单行一次选举是固定次数位运算与比较；不逐点评分生成距离掩码，
不读取预先选出的答案，不改变共享索引。

## 三、跨行完整保留原 top8

扫描四字摘要，只处理 R 个非空行。
每行按同一旧比较器输出至多八项，合入容量八的固定选择器，最后排序最多八项。
若满八项时下一真实头已劣于最差保留项，可停止该行；所有比较必须一致。

全局前八中的点一定属于自己那行的前八：
否则本行已有八个不同目标在其之前。
目标位图按位置去重、行之间不重叠，不存在 band 重复占用名额。

查询为 O(8R)，最多 2048 次逐行输出尝试，另计头比较、八项合并、
摘要扫描及最终排序，不能把 2048 当作 CPU 指令数量。
原方法 full-sort 为 O(N log N)，但在小 N／稀疏 R 下本案可能更慢。

## 四、数值与同分证明

数值门覆盖 destination section 完整盒的三轴两端点与 a、t，
全部最大绝对差不超过 65536，用 long 比较；
不只检查 a-t、盒原点或索引中偶然取出的一个点。
盒跨出整数坐标域、未知类型、异常位置或不满足门时直接旧排序。

门内两个平方距离 A、D 不超过 3×2^32，
各平方及和均是可由 double 精确表示的整数。
0.05 字面量相对实数 1/20 的误差小于 2^-58；
乘系数误差小于 3×2^-26，乘法舍入不超过 2^-24，
加法舍入不超过 2^-20；每个分数误差小于 2^-19，
两点误差差额小于 2^-18。

同行相邻实数评分差乘 20 为：

```text
42*(o[u]+u) + 21 - 40*a[u] - 2*t[u]
```

这是奇整数，不为零。同一侧远离精确有理中心时严格增长，
非零相邻分差至少 1/20，大于舍入误差，故每侧仍按旧评分严格有序。
跨侧实际同分／近分仍运行旧 double 与 XYZ 决胜。
行合并、停行和全局选择器全部复用同一完整比较器。

不以整数 `20*A+D` 替代旧 key。
无对象 helper 必须复现 `((dx*dx+dy*dy)+dz*dz)`、
`.05D` 乘法及外层加法的顺序；未证明前使用原 BlockPos 方法，
并如实计临时对象。数学证明不代替后续实现的差分测试。

## 五、生命周期与完整成本

原 candidates、nearbyCandidates、日志、顺序、不可变性、close 后读取全部不变。
不引入惰性 API，不保留新的组件或世界引用，不在 main 展开准备数据。
索引只含原始数组及 section 坐标，属于窗口；close 可以释放可选索引引用，
不改变原列表的关闭契约。已经捕获的不可变索引不得被原地清零。
无全局根、Work、runtime、future 或 callback 引用。

仍沿用原任务身份、取消／迟到结果和发布检查；不增加每行完整业务校验链。
数值门及 fallback 只切换选择器，不额外排队。
原正常／恢复准备的成本保留，新增索引与最终目标 BlockPos 的成本另外记账，
不声称本案减少全部候选／恢复邻域分配。

替代方法须比较：原列表直接扫描、容量八的选择器，
可避免新位图但仍逐点评分 O(N log 8)。
此前 S1 同时使用 heap8 不代表该独立算法被证伪；
同样不代表独立使用已经有性能收益。此次只作方案比较，不增加第三方测试矩阵。

## 六、后续门禁（本轮不执行）

方案复审只决定是否值得继续有限评估，不代批实施、性能或生产。
若之后获准，先以旧 full-sort 作 oracle，复用原 fixture 和生命周期接口。
只补当前实现必要的：行两头、0/1/7/8/9、密集与稀疏、多 band 目标重复、
六面映射、负坐标、盒角数值门、跨侧旧浮点同分和旧等待时机控制。
原窗口正常／恢复列表和 close 控制不变，不改测试定义来迎合候选。

核对历史 S9L 版本、接口、场景、参数、工具和口径后优先复用有效数据，
只补失效或缺失部分，非必要不扩矩阵，不重跑基线凑配对。
直接影响窗包含 worker 索引增建、原准备和异步等待、交付、查询、实际搜索、
真实触及的恢复及清理。保留 CPU／分配、首 Path、搜索完成、到达／终态、
成功分母与全部不利值；窗外按用户既定范围处理。

若等价性不成立、索引／查询净成本不利或收益不足，则停止该候选，
不能以位运算名称、局部复杂度或约 544 B 索引换取无证据的性能结论。

## 外审结论

首次有效审查 REQUEST_CHANGES 后撤下 FULL_LAZY；
8133 B 自包含修订包正常返回 ACCEPT_SCOPED，必须修改项无。
对方认可整数类型、数值门、同行单调、跨行 top8、原等待／关闭语义的设计级闭合，
明确完整构建／查询不等于 O(1)。
稀疏／小 N 可能输给无索引 heap8，后续应作公平的算法成本比较，
不自动新增 baseline 或三方游戏矩阵。
详见[审查记录](../reports/2026-10-20-boundary-row-selector-plan-review-zh.md)。
