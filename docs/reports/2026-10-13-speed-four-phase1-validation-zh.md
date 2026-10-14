# 速度 IV 分段交接第一阶段：实现与验收

日期：2026-10-13。唯一规范是 [现行详细寻路契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)。旧设计、旧审查和此前通过结论均不作为本轮验收依据。

## 当前状态

第一阶段生产实现已完成并通过独立验收。正式发行态速度对照、经过后的失效边界、完整生命周期、四条原参数路线和发行/Mixin 审计均通过。DeepSeek 最终返回 `ROOT_CAUSE_CONFIRMED / CONSENSUS`，无必须修改项。

## 修改及原因

生产修改集中在 [SegmentedNavigationService.java](../../src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java)：

| 修改 | 原因与行为 |
| --- | --- |
| 保存当前中间段的 `SegmentDestinationEntry` | 原版节点推进后仍有水平残余位移，落地稳定时实体可能已经滑出刚经过的阶梯。保存实际进入组件的事实，使交接不再只依赖后来的位置。 |
| 绑定 request、发布序号、窗口修订/组件、原生读取 proof、tick、原节点和实际位置 | 同一个请求/Path 载体会复用，宏观刷新也会重置 cursor。仅比较 cursor 或句柄不足以识别当前段。 |
| 中间段交接接受有效记录或真实当前位置命中 | 只在真实可达段正常耗尽后、现有物理落地条件下推进宏观游标；下一段仍从当时实体真实位置调用原版 PathFinder。 |
| 原生 stuck-stop 清理记录并拒绝中间段交接 | 被停止的路径不是自然耗尽，不能用停止前的经过事实跳过原有有限恢复。 |
| 两个中间段进度分支统一检查窗口和原版输入当前性 | 原版读取的输入可能在 `settle` 已排队、尚未派发时发生变化。窗口几何没变也不能继续消费过期的进度。 |
| 新搜索、发布、换段、重规划、输入失效和关窗清理记录 | 取消、替换及关闭通过关窗一并清理；迟到回调继续通过原有请求身份保护拒绝。 |

记录只用于执行后的中间进度，不进入候选的搜索前资格判断。不伪造 `canReach`、不修改真实节点、不把 partial 转为成功。最后段 `finalArrival`、accuracy、`EXHAUSTED`、原生搜索参数、恢复上限、宏观算法和共享预算保持现行语义。

测试新增 [HandoffBoundaryProbe.java](../../src/test/java/an/production/HandoffBoundaryProbe.java)，通过已有生产探针按测试属性选择。它在隔离世界中等待尸壳真实运动产生经过记录，再施加失效事件；不向生产增加测试等待或反射。形成排队区间的 10ms 账本等待只存在于探针。

本阶段没有实现下一窗口预取，也没有调整稳定计数。经过记录已经消除了本次局部对照中的恢复；目前没有证据要求增加第二阶段。

## 全新发行态证据

### 速度对照

运行目录：[server-phase1-speed-final](../../.tmp/navigation-speed-root-20261013/server-phase1-speed-final/diagnosis.json)。

- 两个真实楼梯，各进行原版短段、原版连续路径、分段执行三种模式。
- 速度 II、IV 各重复两次，交换效果等级的测试顺序；24 次运行全部正常耗尽或完成，无超时。
- 8 次分段执行全部 `SUCCEEDED`，每次恢复增量均为 0。
- 北侧楼梯速度 II、IV 的反向 tick 均为 0。西侧路线有转弯，探针没有以反向 Z 统计其折返，不能据该字段宣称西侧反向 tick 已测为 0。
- 西侧原版 continuous 四次真实返回 `canReach=false`；`NATIVE_DONE` 只表示真实 partial 正常耗尽，不代表到达最终目标。
- 导航记录、活动任务、foreign path 和失败均为 0；分段实际调用原版搜索 32 次，没有整条原版执行回退。

### 经过后的失效

运行目录：[server-phase1-boundary-final](../../.tmp/navigation-speed-root-20261013/server-phase1-boundary-final/diagnosis.json)。

7 类场景、58 项断言全部通过：

1. 原生停止清空经过记录、拒绝交接，从未推进的当前游标执行真实恢复搜索。
2. 实际原版输入变化后，即使旧经过曾有效也拒绝交接，并对当前段重新搜索。
3. 当前节点耗尽、`settle` 确实在预算队列中等待时改变输入，仍不能消费过期记录；重新搜索后游标保持当前段。
4. 取消后请求与记录清理。
5. 替换后旧请求取消，旧记录清理，新请求具有不同身份。
6. 重算保留请求身份，但旧段经过事实失效，重新调用真实搜索。
7. 关闭当前窗口立即拒绝交接，宏观刷新后重新搜索并保留请求身份。

停服后记录、活动任务和 scheduler pending 全部为 0。修改前的停止边界失败保存在 [server-phase1-boundary-before](../../.tmp/navigation-speed-root-20261013/server-phase1-boundary-before/diagnosis.json)，没有覆盖为通过。

### 发行和覆盖边界

[发行/Mixin 审计](../../.tmp/navigation-speed-root-20261013/artifact-phase1-final.json)：295 项通过，17 组、60 个 handler。实际发行变换、映射和包中排除测试类均核对通过。

[覆盖与派发审计](../../.tmp/navigation-speed-root-20261013/coverage-phase1-final.json)：废弃资格类及相关方法不存在，真实搜索派发边界保持。标准类别的已有生命周期覆盖不等于第三方实体、特殊 override 和所有同步消费者均完成异步适配。

### 完整路线与生命周期

最终正式发行态运行目录：[server-speed-phase1-release-final](../../.tmp/navigation-native-detail-fix-20261013/server-speed-phase1-release-final/result.json)。

142 项业务断言和四条路线全部通过：

- `stairs-forward`：2683 ticks，真实落点 `(-36.5013,101,-195.5098)`。
- `stairs-backward`：2332 ticks，真实落点 `(-22.4417,101,-198.5517)`。
- `city-forward`：4068 ticks，真实落点 `(84.9309,95,-263.1621)`。
- `city-backward`：4103 ticks，真实落点 `(-22.4499,101,-198.5435)`。

四条路线均使用真实分段 Path，运行期没有地面整条原版回退；生命周期还覆盖了真实输入变化、原版 Goal/Brain/POI、partial 执行与 `EXHAUSTED`、取消、替换、重算和关闭清理。停服时 records、active、foreign path、scheduler pending 和拓扑缓存均归零，迟到 service/scheduler 请求均被拒绝。

路线参数来自冻结 `stages.json` 的事实输入，不来自旧设计：

| 路线 | 原起点 | 原终点 |
| --- | --- | --- |
| stairs-forward | (-23,100,-198) | (-37,100,-195) |
| stairs-backward | (-37,100,-195) | (-23,100,-198) |
| city-forward | (-23,100,-198) | (83,94,-264) |
| city-backward | (83,94,-264) | (-23,100,-198) |

两条 backward 路线按输入共用终点。验收核对的是各自真实起点、完整段序列、归一化目标、真实最终落点和 accuracy，不能把相近的终点误判为同一条路线。

完整运行采用冻结的四个 region 和路线输入，当前 `level.dat` 的隔离副本按既有 Metadata 工具归一化，源输入运行前后核对哈希。旧启动脚本因元数据不再匹配历史哈希而停止，保留该次准备目录；没有将其当作路线失败或改写旧冻结身份。

## 独立验收

第一轮全新实现审查 [phase1-acceptance](../../.tmp/navigation-speed-root-20261013/deepseek-answer-phase1-acceptance.txt) 正常退出，返回 `ROOT_CAUSE_CONFIRMED / NEEDS_CHANGE`，指出原生停止和排队期间输入失效两个阻断项。两项均已修正，并由 58 项正式发行态边界断言补证。

最终独立验收 [phase1-final](../../.tmp/navigation-speed-root-20261013/deepseek-answer-phase1-final.txt) 只附现行契约、最终源码和本轮新证据，未附旧设计或旧审查，正常退出并返回：

```text
ROOT_CAUSE_CONFIRMED
CONSENSUS
```

DeepSeek 明确确认无必须修改项；剩余建议是扩大非阻断覆盖（外部 stop、同 tick 多实体竞争和长期预算饥饿），不属于本阶段实现门槛。

独立验证汇总：[verification-phase1-final.json](../../.tmp/navigation-speed-root-20261013/verification-phase1-final.json)，86 项独立检查全部通过。

## 成本与限度

每个活动任务最多保存一个经过记录，记录更新及身份比较为常数操作。窗口与原版输入当前性沿用已有有限检查，原版读取 proof 最多包含 256 个 chunk；未新增全路径扫描、额外详细预搜索或扩大搜索上限。

这证明本次楼梯问题和已测生命周期的第一阶段修复，不证明任意速度、任意地形、全实体或长期并发性能。四条原参数完整路线也不能升级为速度 IV 的全地形保证。原版调用仍是原子调用，共享 4ms/tick 是软预算。当前没有证据要求第二阶段预取，因此本轮保持未实现。
