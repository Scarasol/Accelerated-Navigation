# 就绪接续拆分对照补充

日期：2026-10-17。状态：**对照有限结清，归因未定；DeepSeek为`ACCEPT_SCOPED`，本轮必须修改项无。**

这是`navigation-performance-handoff-20261017-zh.md`和4b/4c验证报告之后的新补充；旧报告、旧封存不改写。用户认为4b更可能造成此前8格CPU+18.1%，授权拆分对照，要求复用4a历史；若仅4b已能说明原因则跳过组合。

先运行仅4b，8格CPU相对历史4a仅+3.015%，主线程CPU+36.111%，未能确定原因；追加一次独立8格突发诊断后仍不能定因，因此补原定的一台组合净测。总计新增两台净测、一台独立诊断，不新跑4a、不扩展矩阵、不追加相同候选重复挑结果。

本轮R/C净测2/2 JVM、4/4正式trial技术有效，2048/2048 Path、SUCCEEDED并实际到达。8格C/R：CPU+2.439%、主线程CPU−8.163%、首Path P50+22.372%、P95−0.804%。28格C/R：CPU+4.412%、主线程CPU+26.087%、首Path P95+12.350%，均保留。

8格诊断832次通知仅8次实际ready回调，全部执行队列operation及真实搜索，共16次PathFinder，无已执行空派发；通知与ready调度独占wall合计0.345594ms。它不包含全部server任务队列/JIT间接成本，也不是CPU增量。JFR只有41个执行样本，不能解释旧562.5ms总CPU差值。

此前+18.1%未复现，根因未定；不能宣布是抖动、4b无成本或4c必然改善。没有根据本轮n=1数据改算法。8格主线程、组合P50与28格新增退化继续开放；不增加预算或负债/偿债，不改默认发行包，不提交推送。第5步尚未开始。

最终复审`deepseek-final-v1`退出0、未超时、settings哈希不变，双方认可按“归因未定，新增证据已完整保留”结清，不追加本轮大矩阵。历史B主线程偏低与R/C搜索/恢复次数不同均作为混杂保留，正式A/A、非劣、C/N和发布门槛未通过。旧封存224项哈希全部保持不变，新证据独立封存。

正式补充报告：`docs/reports/2026-10-17-ready-dispatch-ablation-zh.md`。
新证据根目录：`.tmp/navigation-ready-ablation-20261017/`。
新增可复用分析工具：`tools/navigation-diagnostics/analyze-ready.mjs`，配套`ready-analysis.test.mjs`及`ready-analysis.md`；复用现有运行器、完整校验、JFR提取和DeepSeek复审入口。
