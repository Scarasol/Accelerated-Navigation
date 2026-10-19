# Worker 最终算法生产接入交接

日期：2026-10-17。

用户授权先推送已有生产修改，再接入最终组合并由 DeepSeek 审核。基线 `4b82ed6` 已推送 `origin/1.20.1`；新接入尚未再次提交、推送。详细记录见[生产接入报告](../../reports/2026-10-17-worker-algorithm-production-integration-zh.md)。

八份算法源码已接入，内容与上一轮最终候选在 CRLF 归一化后相同；另外 54 份生产输入未变。GROUND 端口行位图、K≤16 稳定 Top-K、碰撞非空行与严格谓词、第二版回调上下文、12 组队首缓存保持最终选型；VOLUME 无端口索引、保守 mayLink 和标量 Boundary 枚举保持。

新增不依赖临时目录的七组长期契约及 Gradle 检查入口。构建、Core/recovery/commit/Door、58 个生产类来源、5,400+125 宏观、118,800+24,000 碰撞、515,840 行集合、491,520 GROUND 标签及 2,520 组完整 Boundary 对照均通过；同步 future、并发回调矩阵、队列 oracle、600 项实际任务/取消及窗口回归通过。

证据根 `.tmp/production-worker-integration-20261017-v1/`，关键原件为 `source-identity.json`、`integration-checks-v2.json`、`live-checks.json`、`build-and-contracts-v3.log`。首次测试距离错误、跨 JVM 摘要门错误及初始生命周期 B/F 断言污染均保留。修正探针后 F/B 分别有 87/95 项检查通过，随后均在楼梯正向同一位置 `(-22,78,-196)` UNAVAILABLE；检查数差异全部来自异步 POI 等待的逐 tick 重复检查。不能写成完整四路线成功。独立平地交接 13 场景、130 项检查及关闭归零通过；五个服务器 JVM 均退出。

DeepSeek 首轮五包均 `REQUEST_CHANGES`，完整保留。补充每 JVM 原始排序及独立还原、邻接/方向/key/异常负例、严格碰撞调用点、全部队首与真实续跑证据后，`review-semantics-v2` 返回 `ACCEPT_SCOPED`；最终 `review-integration-final-v3` 返回 **`ACCEPT_WITH_GATES`、本轮必须修改项无**。七次均退出 0、未超时、配置不变。最终同意有界代码接入进入本地提交和评审，不批准楼梯功能、整体性能、远端 CI 或部署。

最后长期契约为 `review-supplement-contracts-v4.log`：全部 12 组队首×4,000 次变异、600 项实际任务/取消、64 次真实 continuation、重复 Runnable 等检查通过。额外生产 JVM 的 5,400 项排序原件和 4,200 组独立还原见 `macro-trace*.json`。最终身份/共识为 `consensus.json`、`final-seal-v1.json`；原候选 267 份封存文件保持。新接入和文档仍未再次提交、推送。
