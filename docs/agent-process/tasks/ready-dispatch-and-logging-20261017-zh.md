# 第4b、4c步：就绪接续及日志减量

日期：2026-10-17。状态：**4b、4c有限结清**。按用户授权简化流程并复用审查代码，保持生产候选可分别核对。正式报告：`docs/reports/2026-10-17-ready-dispatch-and-logging-validation-zh.md`。

基线为已结清3/T2及4a的当前生产源码，生产Jar SHA256为`a6c494dabf78a1479b9fc5e620ab1f5a837a72e846ccc1d8c3438dab74ae3dcf`；本轮证据根目录`.tmp/navigation-ready-logging-20261017/`，改动前冻结源为`baseline-source/`。保留已有dirty worktree，不提交推送，不替换默认旧发行Jar。

4b仅对宏观、共享或中间窗口依赖就绪的回调发出合并的主线程派发请求；经过server.tell trampoline后使用原队列、优先级、维度轮转和独立500us准入，不直接内联搜索。只在正常结束tick的原43ms截止之前，使用该tick现存20ms额度；不增加额度，不结转正负余额，不引入负债/偿债。新tick envelope开始立即撤销旧就绪窗口；暂停、异常、END无时间、耗尽、关闭及截止后均不派发。

就绪派发位于tickServer envelope以外，完整计入累计chargedNanos。紧接的下一envelope正常采样时记录outsideNavigationNanos；若该envelope被跳过则没有对应样本，累计计费仍完整保留。不修改已完成TickSample，也不改变既有跳过样本口径。完整tick性能不能代替全JVM CPU/分配与墙钟时延。

4c将完整走廊/成员候选/逐搜索及恢复详情移至TRACE，DEBUG保留简短事件、计数和失败原因，昂贵详情getter置于isTraceEnabled内。冻结4b与4b+4c两个源码版本，机制分开核对，阶段末共同回归。

验证复用现有build/run/analyze及final-window控制，扩展既有预算契约的就绪窗口、截止、haveTime、公平、取消、异常和计费断言。先做温热零边及跨section机制，再以组合候选覆盖生命周期、range、查询采用/共享及Super。阶段末小范围净C/B筛选，不为每项小改动重跑完整矩阵；无正式A/A、非劣或C/N资格。

DeepSeek调用从本轮提取为公共工具，保留自包含输入、禁止外部工具、设置哈希、原始输出、退出与超时记录。阶段末综合审查，实际阻断才修改及复测，文字问题集中澄清。

## 完成结果

相对本轮基线仅改两个生产文件，75行新增、16行删除。组合生产Jar SHA256为`959fc6a72b62bf213c2c85a88c79941dea0cb8829785614374f99dee1364b2f0`，当前源码与其冻结输入一致；默认旧Jar未替换。

仅4b与基线三场真实搜索输入/目标/原始节点相同，温热零边首Path 51.3425→4.4189ms、cross8 103.301499→21.033801ms；只作为插桩机制证据。仅4b与组合候选同样保持语义，三场DEBUG消息正文减少79.86%–85.08%，TRACE详情可用。

每台控制JVM两轮共172条预算断言通过；12项生命周期及等待中实际关服、两场墙门128/128、显式range正反控制、入口4/4、Super304实际到达通过。Core 9项、Reverse integration 3项分别通过，Node 49/49通过；沿用4a未改宏观模块的契约证据，不重复矩阵。

阶段末只跑一对B/C独立JVM，2/2运行、4/4正式trial技术有效，2048/2048实际到达，零超时。8格分配-1.137%，但CPU+18.090%、首Path P50+23.306%、P95+5.030%；28格分配-1.394%、CPU-5.991%。结果混合，无正式A/A、统计非劣或C/N资格，不能宣布整体性能通过。

唯一最终DeepSeek审查`deepseek-final-v1`为`ACCEPT_SCOPED`，本轮必须修改项无；退出0、未超时、settings哈希不变。允许按本轮范围结清，8格回归、未覆盖路线/恢复日志及正式性能/发布门槛保留给后续阶段。文本认可不作为独立实测认证。

证据见根目录的`functional-summary-v1.json`、`performance-analysis-v1/`、两份mechanism comparison、`deepseek-final-v1/`及`seal-v1.json`。失败构建、旧TRACE配置和首次Gradle启动失败保留并排除。后续第5步尚未开始。
