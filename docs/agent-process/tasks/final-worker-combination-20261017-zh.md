# 最终 Worker 算法组合交接

日期：2026-10-17（Asia/Shanghai）。

用户要求合并前两轮算法验证，给出最终组合并与DeepSeek达成共识。本轮已形成8份隔离源码，算法选择范围以本交接与报告为准，保留旧候选和原件。完整报告：`docs/reports/2026-10-17-final-worker-algorithm-combination-zh.md`。

最终组合：

1. 稳定小数组Top-K仅K≤16；K>16回退原完整排序。
2. 保留组件包围范围及Super 14位出口；GROUND共享端口行位图按组件与原位移求交，支持Base依赖前零拒绝及完整Boundary置位枚举。
3. 两通道碰撞非空行位图与轴对齐严格谓词合并，精确Shape、门、身份、未知/缺失及必要BFS保留。
4. 第二版每线程惰性回调context。
5. 固定12组队首缓存，原树和调度语义保留。

VOLUME不分配端口索引数组、使用原标量Boundary候选枚举，`mayLink`保守true；不要恢复上一轮全通道端口索引。第一版惰性回调、堆、单独行扫描及额外复杂索引不在组合内。

证据根：`.tmp/final-worker-combination-20261017-v1/`；源码`final-main`；选择`selection.json`；验证`checks-summary.json`及`concurrency-checks.json`；组合成本`summary-v1.json`；共识`consensus.json`；封存`final-seal-v1.json`。

新组合通过宏观5,400+125、118,800次open与24,000严格谓词、定位/身份、515,840行集合、491,520 GROUND标签位、2,520组Boundary输出及运行时契约。额外回调矩阵与4生产者/2,400次队列oracle通过。

新JMH为B/P/F/F/P/B，30个测量fork：完整Boundary平地F/B -50.6%、F/P -32.0%；固定城市人工邻接 -57.0%/-48.8%；VOLUME F/P +0.1%、fork范围重叠。不能累加旧百分比，也不能转移旧服务器CPU收益。源码61输入和两个默认Jar未改，无新服务器/默认Jar构建。

DeepSeek两轮均ACCEPT_SCOPED、必须修改项无，最终`review-final-v2`明确同意锁定组合；进程正常退出，配置未改。回复与范围以该原件和`consensus.json`为准。未来生产验收缺口是实际worker/首Path、任务量、驻留/GC和锁内业务，不是尚未选出算法。
