# 构图中间产物分层复用

用户授权：按已确认的 JFR/JMH/工作计数/端到端工具栈验证本方向。

- 范围：研究原型；不接入生产、不提交、不推送。
- 方案：`docs/designs/2026-10-15-intermediate-reuse-validation-plan-zh.md`。
- 目录：`.tmp/intermediate-reuse-20261015/`。
- 已完成：独立候选；50 fixture×21 Geometry 共 1050 对图和 6315 断言；独立未改动原始生产类对照；36 行内核+36 完整 Base+6 归因控制 JMH fork；双方各 8000 build/48000 JFR 阶段、CPU 精度校准、分配及 Instrumentation 留存。
- 结果：普通完整 Base 单体型 +10.6%，两体型 -0.6%（近乎持平），四体型 -26.4%；四体型纯跨体型留存相对每体型重建 -16.4%。通用首用即建缓存未过推广成本门。
- 未执行：Minecraft 冷首次/Path/到达/tick 和真实生产复用率；因局部通用成本门未过，不展开端到端矩阵。不修改生产，不自动切下一项。
- 审核：两个代码专题、综合终审、2467B 澄清包均完成，ACCEPT_WITH_GATES/必须修改项无。确认实服复用率是 null/未知，0 仅为端到端样本数；EXACT 逐格检查，只有门整体回退。WSL 原生 dsh/deepseek/deepseek-v4.1-flash，四包均小于6500B、本地截止900秒，设置未变。
- 状态：本版原型的局部研究已收尾并与 DeepSeek 共识；不表示完整分层复用、生产或端到端验收完成。`consensus-v1.json` 保存结论与全部审核哈希。
- 报告：`docs/reports/2026-10-16-intermediate-reuse-validation-zh.md`；数据 `.tmp/intermediate-reuse-20261015/local-results-v1.json`。
- 历史工具自检与 Portal 数据不计入本轮性能分母。
