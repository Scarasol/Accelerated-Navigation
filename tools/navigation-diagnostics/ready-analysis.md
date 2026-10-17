# 就绪派发诊断

沿用已构建的agent和`run.ps1`，不修改生产算法。示例只选择两场预热与8格突发：

```powershell
./tools/navigation-diagnostics/run.ps1 -Build BUILD -Output NEW_RUN -Suite burst -Scene 'warmup8,warmup28,burst8' -Jfr
node tools/navigation-diagnostics/analyze-ready.mjs NEW_RUN NEW_SUMMARY.json
node --test tools/navigation-diagnostics/ready-analysis.test.mjs
```

先复用`analyze.mjs`验证运行、身份、场景、30秒等待、事件完整性与清理。
按同线程真实嵌套关系统计`drainReady`下的队列drain、operation回调、段搜索和PathFinder调用。
同时保存每次派发的明细，不用时间重叠将其他线程或同级调用归入派发。

`requestCalls`包括合并、条件不满足等所有通知尝试；它与`readyCalls`的差不是纯合并数。
`skippedBeforeDrain`表示已执行派发回调但未进入队列drain；`withoutOperationCallbacks`
还包含进入drain但没有调用operation的情况。operation回调可能因生命周期失效而提早返回。
没有PathFinder调用也可能是在提交异步窗口，不能自动认作无用工作。

`requestExclusiveNanos`与`readySchedulerShellNanos`是插桩墙钟时间，后者包含ready方法与其
队列drain的独占时间，不含嵌套业务方法。它们不是净增量CPU，也不覆盖server任务队列的全部成本。
`readyInclusiveNanos`包含业务工作，不能与独占阶段重复相加。
本工具只作机制诊断，不提供性能准入。净测必须另行关闭agent和JFR。
