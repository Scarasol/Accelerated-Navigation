# S9 去重耗时不利的定向归因与补审

日期：2026-10-19。用户要求查明“去重为什么时间反而变长”，并指出上一轮没有送审。

**本轮得到的是阶段归属和局部净成本证据，尚未精确追溯旧不利样本的具体事件。** 按旧数据两个时钟的差值分解，distinct/mixed的总查询差额主要落在 `MacroQuery` 计时窗之外；独立诊断和无插桩局部重放中，有重复输入的capture净耗时、净分配均降低。不能继续只用“未定位，暂记波动”收口，也不能把窗外残差直接命名为排队、JIT或future交付。

本轮覆盖上一轮“停止扩测”的后继安排；[上一轮报告](2026-10-19-component-snapshot-dedup-isolation-zh.md)、协议、候选和原始净测保持。没有修改生产代码、默认JAR或S9源码，没有为净测重新跑历史基线。

证据根：`.tmp/component-snapshot-cause-20261019-v1/`；协议 `protocol.md`，局部重放补充 `replay-protocol.md`，最终数值 `cause-summary.json` / `diagnostic-summary.json`，JIT摘录 `jit-summary.json`。

## 先复用旧数据分解

原工具本来就保存了两个不同口径的时钟：

- `Q=queryWallNs`：请求调用前至 `future.get` 返回。
- `M=progress.wallNanos`：`MacroQuery.submittedNanos` 至 `finishMeasurement`。端点解析完成后才创建MacroQuery；该时钟在结果缓存重物化、future交付之前停止。
- `R=Q-M`：窗外前后两端的合计。旧记录不能进一步拆成前解析和后交付，R也不是直接测得的排队时间。

以下全为已有64次测量的均值差 `S9−C`，单位微秒，没有增加样本或剔除不利行。

| 旧样本 | 总查询Q变化 | MacroQuery内M变化 | 窗外R变化 |
| --- | ---: | ---: | ---: |
| distinct 1 | +189.597 | +9.580 | +180.017 |
| distinct 2 | +132.953 | −77.294 | +210.247 |
| mixed 1 | +31.708 | −87.042 | +118.750 |
| mixed 2 | +41.269 | −91.116 | +132.384 |

舍入可能产生0.001微秒加总差。distinct第一组约95%的总差额在R；另外三行R增加被M减少部分抵消。**这些是旧样本自身的代数分解，不能与“新诊断已经复现旧事件”混淆。** air原本更快，不纳入“旧不利”的泛化。

原 `progress.queueNanos` 从MacroQuery提交计到第一次物理worker启动。定义 `M−queueNanos` 为“首次物理worker启动至MacroQuery停止计时”的墙钟，包含后续执行及交付/依赖间隔，既不是CPU也不是纯slice耗时。上述四行该指标分别减少79.706、50.345、59.402、118.106微秒。

按切片数分组也复用了全部旧行：第一S9 JVM经常多一个切片，第二JVM近似相同；第二组distinct仍较慢，因此不能用“多一个切片”解释全部不利。完整分组及前后半窗在 `historical.json`，没有据分组筛掉样本。

### 排除接口和编译物串用

旧C与当前C+S的20份拓扑源码逐字节一致；离线入口只引用未变的Scheduler.Priority，不执行补发。额外用 `javap -c -p` 核对Runtime、MacroQuery、ResolveFlight、MacroFlight、Base、Super和ComponentSnapshot七个类；去掉常量池编号后，指令文本一致。没有发现当前参照偷偷换成不同算法或旧生产回退，历史数据继续有效。

## 补足缺失的阶段信息

旧净测缺少capture、构造器、slice及交付事件。只为填补该缺失，新增C/S9各一个诊断JVM，复用原工具字节、63section、原生请求接口、三个事实负载、24次预热+64次测量及512MiB堆；没有改range、能力、目标或缓存初态。

同一窄ASM agent观察capture、snapshot构造、slice、端点解析、MacroQuery创建/完成、恢复交付及Base/Super/Boundary构建。原工具的三个nanoTime读数增加只读锚点，返回值保持；记录线程分配/CPU和JIT、GC、safepoint。未增加游戏矩阵。

所有528行输入/corridor hash、cost、endpoint/connection及witness与旧C对齐；每查询3次capture，C/S9分别9/5次构造，slice数与progress一致；事件嵌套、容量、异常、丢失及所有者关闭检查通过。该数据有插桩扰动，只作诊断；原工具 `diagnostic=false` 只是关闭其旧WorkCounters，**不意味着本轮外部agent数据是净测**，另有 `diagnostic/diagnostic-only.json` 明确登记。

| 负载 | capture wall C→S9，微秒/查询 | capture分配 C→S9，B/查询 |
| --- | ---: | ---: |
| air | 144.730→121.688 | 5432→3800 |
| distinct | 124.956→78.898 | 5432→3800 |
| mixed | 124.905→76.639 | 5432→3800 |

构造器内部的分配4752→2640B，外围独占分配680→1160B。外围是key、map、endpoint映射、snapshot对象头等合计，不能全部叫HashMap成本。它说明少构造的同时确有其它分配变化，capture合计净少1632B。

构造器探针本身C有9次、S9有5次，局部计时存在非对称扰动，因此追加下面一次无agent控制，避免把探针减量冒充算法收益。

本次诊断mixed的整个Q增加36.467微秒，其同一条时间线的差额为：

| 时间段 | S9−C，微秒/查询 |
| --- | ---: |
| 请求开始至首slice | +31.239 |
| 串行slice合计 | −58.916 |
| slice之间的间隔 | +69.220 |
| 末slice结束至future返回 | −5.077 |

四项使用不重叠区间，和Q逐行一致；上述均值仅有舍入差。slice间隔可包含依赖构建、owner工作和任务交付，不全是空等。并行构建区间不与slice重复相加。

同次诊断distinct反而减少185.677微秒，没有复现旧净测不利方向。因此，本次mixed事件只能说明“总耗时可以在capture更低时由其它区间增加”，不能用于追溯解释旧distinct/mixed的每个慢样本。

## 无插桩局部重放

使用现有JMH，先以原distinct场景走一次真实宏观查询，取该走廊及真实Base对象，关闭runtime并确认owner归零后重放。每版本每负载一个fork，3×1秒预热、3×1秒计量、单线程、512MiB堆、GC profiler及LogCompilation，未加agent。

- `actual`：真实走廊的9个不同component endpoint，对应5个组件。
- `unique`：从同一走廊只取每组件一个endpoint，共5端点/5组件，**仍强制 `refining=true`**。这是零重复时走分层查表路径的机制控制，**不是普通路线 `refining=false` 的成本**。

每操作包括真实MacroQuery构造、共同的反射接线和原capture；三段合成一次capture，少两个迭代器，不冒充原三段整查询。成员逐4096格验证，输入成员摘要与类来源一致；控制中不创建新的拓扑算法。

| 输入 | wall C→S9，微秒/操作 | 观测变化 | 分配 C→S9，B/操作 |
| --- | ---: | ---: | ---: |
| actual | 86.1249→46.9793 | −39.1456，−45.45% | 5904.506→4272.278 |
| unique强制表路径 | 48.8768→50.6374 | +1.7606，+3.60% | 3568.289→4016.300 |

有重复输入观察到局部净耗时、净分配下降；零重复强制表路径观察到额外448.011B，耗时点估计略增。**不能用两种输入的绝对差额做可加归因，精确拆出map/key耗时与扫描节省。**

JMH同fork三次迭代的99.9%误差约为21.44/17.44和15.40/16.18微秒，不是运行间置信区间。1.76微秒小于上述误差，不能宣称稳定退化；C两个fork测量窗GC均0，S9两个fork各1次，原值保留。只运行这一次局部控制，没有第二组。

### JIT线索

诊断中capture字节码C/S9为175/382字节，在appendSegment、stepSearch的C1编译中都因 `callee is too large` 拒绝内联，没有“仅S9失去内联”的对照。无agent重放中，两版snapshot构造都出现C2 `inline (hot)`，其它编译阶段的策略拒绝同样保留。

因此不能只凭方法变大断言它解释了旧0.13–0.19ms的distinct差额。这些日志也不代表没有保存JIT信息的旧净测状态；本轮没有测出key逐项CPU、编译器CPU或完整机器调度因果。

## 送审与代码核查

使用已有 `tools/review/deepseek-review.mjs` 的WSL无头接口，实际 `deepseek/deepseek-v4.1-flash`，每包自包含且小于8500B；原提示、实际transmitted、answer、process及exit均保存。

首包6928B、279.193秒为 `REQUEST_CHANGES`，原异议没有删除。补充源码不变量后，代码包5217B、308.507秒返回 `ACCEPT_WITH_GATES`、**本轮必须修改项：无**，仅认可有限范围，未给生产并发资格。

首轮涉及的源码事实核对：

- signature是64位FNV，不能声称数学唯一。实际当前性还依赖 `SectionStamp.current(): source.get()==expected`；发布时，身份、状态或实际内容变化会更新LatestFacts对象，等价重发/同版本恢复有专门短路，不能泛称“每次发布都变身份”。
- Base由确定性build构造，再验证generation、输入指纹和中心/halo stamps后发布。TopologyGraph的captured类型固定为Base；旧图保留自己的旧stamps，不能随便把新事实stamp贴到旧图上。
- stopSearch对RUNNING只置取消；只有QUEUED成功移除才走queued清理。completeWorker和stopSearch的状态转移由同一runtimeLock互斥，不能把锁内的 `searchTask=UNTRACKED` 与后续finish拆成任意并发交错；runWorkerSlice返回/抛出后才走完成处理。
- endpoint.revision为primitive long、channel为enum、componentId构造拒绝负值。snapshot数组在对象内创建、无公开导出及修改入口；重物化不替换component endpoint。已有真实5唯一身份/9映射和逐格oracle，不只检查映射非空。

代码审查列出的全面发布原子性、所有终态latch及更广覆盖等门，仍不能写成本轮已经穷举证明；同时不将审查的条件性假设误写成仓库已确认缺陷。本轮没有新增生产修复或把有限审查转换为发行批准。

结果初审5912B、153.817秒为 `ACCEPT_WITH_GATES`，但有三项必须修正文句：限定distinct/mixed；将“已定位”改为两时钟代数分解；说明unique强制表路径且不能作可加成本解释。已全部采纳，另以4553B最终包复核，185.328秒返回 **`ACCEPT_SCOPED`、本轮必须修改项：无**，只认可有限归因措辞和数值关系。

四次调用均退出0、未超时，settings前后SHA256均为 `071db56517e8f97ccde780095f71e5b264a1c7bc41694499cf81af7c2aca1858`，实际prompt与transmitted身份一致。首轮拒绝及结果初审必须修改项继续保留，不把“退出0”当成首轮通过。审查未访问仓库或独立执行实验，不构成生产、稳定性能或全面并发批准。

## 本轮结论

按两个时钟的差值分解，**distinct/mixed的旧总差额主要落在MacroQuery计时窗之外**；窗外内部的前解析/后交付仍无法再分，具体事件未闭合。独立诊断及无插桩局部重放观察到capture及有重复输入的局部净成本下降；零重复强制表路径有额外分配，耗时点估计略增但未稳定。

不能将差额精确拆成map/key与扫描成本的可加贡献，不能把窗外全称排队，也不能宣布旧差额已由某次GC/JIT/调度精确解释或纯波动。上一轮“未定位就结束并推进后阶段”的表述由本轮更窄的状态覆盖：**阶段分解和局部净成本已取得证据，旧具体事件仍未闭合。**

S9继续隔离，没有稳定整查询收益、生产接入或发行资格。本轮只补缺失归因数据及审查，没有重做有效历史净测配对，不追加游戏或完整服务器矩阵。

## 原件与收尾

初次局部javac启动因长classpath在PowerShell下失败，尚未执行benchmark；改用javac参数文件及新的classes-v2后完成。原空目录/编译日志保留，没有替换不利性能结果。一次rg把通配符作为Windows字面路径的只读错误保留，不影响数据。9684B综合草案未发送，拆成5217B代码和5912B结果包，未通过改工具绕过大小限制。

原820份保护输入、候选和旧数据保持；三份状态文档只按本轮记录追加/更新，原文另有备份。新数据、工具、审查、报告和状态在 `final-seal.json` 封存；生产和默认JAR保持，无提交、push或部署。
