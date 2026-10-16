# 首请求闭包与骨架的全部前置

当前状态：**本轮前置已结清。60个独立JVM全部通过；DeepSeek数据完成后的唯一重试在47.448秒正常返回，明确READY_TO_VALIDATE，本轮必须修改项无。新闭包/骨架尚未开始验证。**

用户最后一轮明确要求：所有前置做完，删除历史估计等方案外内容，全部测试在启动后30秒，取得DeepSeek明确可以开始方案验证的许可。必须持续执行到结清；不得把准备或有限观测报告成方案验证完成。

最新补充指示：上游疑似失能，全部本地前置数据完成后只再试一次；若仍返回504，暂缓送审，不继续发送其他分包。此时应报告“本地前置完成、外部审核暂缓、尚无放行、新方案未开始”，不能冒充完整共识。

方案：`docs/designs/2026-10-15-navmesh-prerequisites-plan-zh.md`。全部新原型在`.tmp/navmesh-prerequisites-20261015/`。

当前已做：删除生产NavigationBudgetController和全部历史估计/额度反馈，固定20ms/tick、45ms实时目标减2ms固定尾部，保留haveTime、实际计费、当前tick门槛，不结转余额。已维护ProductionBudgetChecks；新mod在sceneReady实际30秒后才运行控制和请求；正式ColdTopologyProbe亦增加30秒门槛及相对超时。所有旧冻结数据保留、不复跑旧干预。

独立Trace记录器在新`.tmp`；instrument.mjs机械生成诊断生产源码副本，不修改原拓扑语义。v1/v2生成失败目录保留，v3..v6接线及构建成功。当前build.gradle选择instrumented-main-v7，九个生产文件仅副本插入Trace/现有forceBase测试选择。v7将input-stamp与实际发布点topologyStamps分开，增加临时pin释放和图GeometryKey，防止将输入捕获集合误称最终发布集合。probe公共完整tick不依赖详细Trace开关。最新build-probe-v5/build-trace-v5已成功，发行在release/plain.jar、trace.jar、probe.jar；release-v6保留旧发行。正式测试入口30秒门槛及旧预测断言清理的testClasses编译通过。

已实现analyze/run/campaign、reader-controls/freeze/closeout。v5五轮及v6七轮接线均通过；ready>=30秒、120预算控制/2遍、真实Path和关闭资源通过。super512逻辑512合并物理1。v5 CPU读取错误地假设短区间CPU<=墙钟，主机15.625ms计数粒度否定该假设，已修并保留原始pilot。v6读取器33类损坏拒绝通过。

重要修正：v6捕获的是输入stamps，不是最终发布集合，正式v1矩阵在两轮完成、第三轮执行时主动终止。全部保留：freeze-v1/campaign-main-v1/campaign-abort-v1、原运行目录、tools-frozen-v1及release-v6；不得混入最终分母。v7重新接线后冻结freeze-v2、campaign-main-v2(48)/controls-v2(12)，目录名main-v2/control-v2。v7读取器新增实际发布图身份/临时pin释放拒绝，用reader-controls-v3；包审计package-audit-v2；最终summary-v1只对应v2矩阵。

DeepSeek原生WSL `/usr/bin/dsh --profile headless`，实际deepseek/deepseek-v4.1-flash；单包<=6500B。前四包240秒超时均无结论。用户提醒超时是本地限制不能据此认定服务故障；当前脚本默认900秒(可配置240..1800)，每包独立任务摘要和约束。code-v1(2499B)、measurement-v1(3614B)、next-stage-v1(3841B)均约380..382秒后上游504，dsh自行exit1，timedOut=false，并非本地900秒到期。settings不改；超时/504均不是通过。

v7功能接线wiring-v7-super-1/512通过，期间有模型审核，明确不参加性能分母。已取得35类损坏拒绝、实际发布stamps/pin释放闭合，package-audit-v2通过，freeze-v2已冻结。后续正式性能48+12必须等待上述模型调用全部退出；如有效答复提出必改先处理。失败无结论不阻止用户授权的前置测量，但不能开始闭包/骨架方案验证或冒充DeepSeek许可。

最新执行：正式main-v2(48)、control-v2(12)全部结束，60独立JVM、13,842逻辑请求通过，最短sceneReady至提交30.0472535秒。正式服务器窗口2026-10-15T07:18:20.449Z..08:11:43.481Z，无模型审核交叠，已无实验Java。会话50479退出；末尾汇总曾误拒发行JSON字段顺序，修复后又定位后台其他体型图被全局套用实体体型的误拒。读取器改按唯一文件名/SHA比较；图对象身份始终核对，实体关联依赖才额外核目标GeometryKey，原件未修改。reader-controls-v6有40类损坏拒绝及2正例通过。closeout已通过，summary-v1仅对应正式v2；独立final-audit-v1再核60轮身份、10,764条详细请求、装入/观察分解、实际依赖/图及八子图屏障通过。晚加入共享flight按带时间关联边计算实际接入后的等待，全物理查询历史另列。

本地正式报告已写入`docs/reports/2026-10-15-navmesh-prerequisites-validation-zh.md`，包含16格主矩阵、开销对照、结构计数及阶段/等待限制。summary-v1 SHA256为B9DB2D6155150684295102978A41A2DFEDCFE599700CD657480495B50B91B010；审核后保持不变。数据结清后只发送一个5521字节自足包final-prerequisites-retry-v1，900秒本地截止，实际deepseek/deepseek-v4.1-flash。此次服务恢复，47.448秒正常exit0、timedOut=false、settings未改；明确READY_TO_VALIDATE及本轮必须修改项无。WSL进程pid464和exec会话30134均退出；没有发送其他五包。原始prompt/transmitted/answer/process/exit及review-seal-v1保留，seal核包/答复/summary身份、前置已通过且与性能JVM无交叠。仅为自足摘要所列范围的准入审查，不是逐行代码审查、生产批准或稳定性能资格。

本轮没有剩余前置工作。下一阶段边界见`docs/designs/2026-10-15-first-request-closure-portal-validation-plan-zh.md`，候选尚未开始：首候选复用既有Base前沿作冷父图延后策略，不冒称新搜索；先过复杂几何/版本失效/UNKNOWN/共享取消/真实到达等正确性门，再以同一冻结平衡交错矩阵对Super/Base/候选补齐off/plain，测后继等待、到达和完整tick，排查顺序效应。只后移等待或复杂路恶化即停性能资格。未提交推送部署；不做持久化/tile/JVM支线。
