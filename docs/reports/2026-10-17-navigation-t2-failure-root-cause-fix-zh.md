# T2 短程失败根因定位与窄修

日期：2026-10-17。状态：根因已由受控反事实和独立候选执行确认，窄修已实现并通过本轮覆盖；不代表正式净性能准入。

## 根因

最终段真实搜索错误地复用了 `localRange(work, targets)`。这个公式按当前实体到最终目标的直线距离计算，并将范围限制在 16 到 96；它适合中间段的局部端点，却不适合最终目标的原生搜索参数。

原生 PathFinder 的 `range` 同时限制搜索扩展中的 `walkedDistance`。因此即使返回的 partial 路径几何长度小于 16，搜索仍可能在不同探索顺序下被范围条件提前截断。T1 中记录的请求原 range 为 35，而短程最终段实际使用 16；28 格门口残余使用过 18 或 19。

## 证据

冻结基线的无重放失败结果：

| 场景 | 到达 | 最终段失败数 | 最终段实际 range |
| --- | ---: | ---: | --- |
| 8 格墙体 | 56/64 | 8 | 16 |
| 8 格门口 | 52/64 | 12 | 16 |
| 28 格墙体 | 64/64 | 0 | — |
| 28 格门口 | 56/64 | 8 | 18 或 19 |

同一起点、目标、`accuracy=1`、`visitedMultiplier=1`、`regionOffset=8`、`avoided` 和 PathFinder 额度的交错 A/B/B/A 重放共覆盖 28 个最终 partial。固定查询区域半径为 43 时，低 range 仍失败、高 range 35 可达；失败搜索实际弹出 65–135 个节点，额度为 560，并记录 101–243 次 `walkedDistance` 范围拒绝。因此已排除查询区域过小和节点额度耗尽。

## 修复

`SegmentedNavigationService.findSegment` 现在仅对最终段使用 `work.request.intent.range()`；中间段继续使用原 `localRange`。没有修改原版 PathFinder、accuracy、visited multiplier、节点额度、恢复上限、调度预算或整条原生回退。

显式小范围也保留原值。两个控制都使用同一个平地 8 格场景：起点 `(-156,81,-147)`、目标 `(-148,81,-147)`，目标距离为 8 格。`range=4` 的最终段真实记录为 `request.intent.range=4`、实际搜索 range=4，并以 `EXHAUSTED` 结束；`range=12` 的最终段真实记录为 `request.intent.range=12`、实际搜索 range=12，并到达。后者不是基线失败构型中的“更小范围反而成功”，而是同一平地路线的显式参数控制，用来证明最终段没有被 `localRange` 的最小 16 放大。

候选核心回归还逐条记录了请求参数：基线失败最终段为 `requestedRange=35` 而 `actualRange=16/18/19`；候选修复后所有对应最终段均为 `requestedRange=35`、`actualRange=35`。`localRange` 的规则仍是中间段按“起点到候选端点距离 + 8”计算，再限制到 `[16,96]`；它不再参与最终段的 range 选择。

## 独立验证

当前源码隔离编译并重新混淆的候选 JVM 中：

- `wall8-all`：64/64 到达；
- `door8-all`：64/64 到达；
- `wall28-all`：64/64 到达；
- `door28-all`：64/64 到达；
- 4 个场景共 256 个实体，`records=0`、`active=0`、`failures=0`，服务正常退出；
- `cancel-pending` 和 `cancel-published` 均保持 `CANCELLED`；
- `replace-pending` 产生两个请求代次，替换后成功；
- 核心契约测试通过：9 项 Core contracts、3 项 Reverse integration contracts；
- 分析器 Node 测试 8/8 通过。

实际到达按实体 `blockPosition` 到原目标的 Manhattan 距离不超过原 `accuracy` 判定；原生 partial 未被改写为成功。

## DeepSeek 共识

第一轮方法审查返回 `REQUEST_CHANGES`，要求补充 range 与查询区域解耦、节点额度与距离截断计数、独立 JVM 真实执行及显式小 range 控制。上述要求已补齐。

第二轮修订方法审查返回 `READY_FOR_INTEGRATION_VALIDATION`，本轮必须修改项为无。最终实现证据已再次送审，结果文件位于 `.tmp/navigation-t2-failure-fix-20261017/`。

最终澄清审查返回 `ACCEPT_WITH_GATES`，本轮必须修改项为无；DeepSeek确认两项证据澄清已关闭阻断，可以结清 T2 并进入 T3。T3仍需独立处理净性能、扩展覆盖和最终段参数来源回归断言。

## 边界

本轮结清的是墙体、门口及 28 格门口短程失败的 range 根因和窄修。实服入口权重、正式 C/B 与 C/N、净性能、所有长路线及 T3 优化仍未判定。

证据目录：`.tmp/navigation-t2-failure-fix-20261017/`。候选生产包 SHA256：`0dbb9aed2106966282a2a8229247b984c67f1e11d8ebf4c80adc70db3504eb4e`。
