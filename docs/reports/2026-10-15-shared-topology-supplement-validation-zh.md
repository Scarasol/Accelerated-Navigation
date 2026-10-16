# 共享拓扑补充验证记录

日期：2026-10-15。状态：首版核心先通过后开展本轮；容量补充、内存与冷阶段有限验证及 DeepSeek 综合审核已结清，本轮必须修改项无。未批准稳定性能、全进程峰值或新算法。

本轮承接 [首版接入记录](2026-10-15-shared-topology-production-integration-zh.md)、[补充验证范围](../designs/2026-10-15-shared-topology-supplement-validation-plan-zh.md) 和 [额外冷启动方向报告](2026-10-14-navmesh-cold-start-additional-directions-zh.md)。持久化及旧异步 v5 保持暂停。动态预算正式验收没有由本轮代批。

## 已实施的容量修订

实际 Runtime 连续访问 64 个不同 section 后，第 65 个被拒绝；卸载全部原 section、旧 lease 全部失效后，64 个 Slot 仍占用限额，第 65 个继续被拒绝。当前没有 idle eviction。不能把“容器有上限”写成长期新区域访问策略已完成。

保留原容量，新增包内构造的 `SharedSectionCandidate.AdmissionRejectedException`，仅标记共享 capture、Slot、flight、初始基础行载荷和 server 发布请求这五处准入限额。消费者收到该类型进入现有宏观候选流程；普通执行器拒绝、服务不可用和其他失败保持原处理。没有把准入不足写成区域无路，没有扩大共享额度或转整条详细移动，也没有实施淘汰策略。

生产冻结 `candidate-v5` SHA256：`2D9DF2FF444A397574F16D5DBC87E94276DE4B1F074DA42D9354AFBE437572E0`。原生探针 `2A46047242A7ACED4B157D3F509B26D4803FD90518A93F5B9964019BA7CFB242`。生产源码与动态预算基线的职责分界保持。

`supplement-capacity-v1` Runtime 1,296、scratch 3,387、既有核心 12、补充 82 项通过；`native-v8` 15 场景/143 控制通过，11 次实际到达、铁门负候选 1、取消 1、通用拒绝 2，32 次真实搜索，整条详细回退增量为零。容量场景回退恰一次，实际到达，未创建共享锚点阶段。两类注入拒绝均命中钩子一次，UNAVAILABLE、nativeCalls=0、fallback=0。独立读取器 5,495 项通过，原数据 SHA256 `297A61DF838AB58CE8AEE165104D22B44D769D3E8305F02158D7AE7B8EC0346C`。

生产 Slot 反射降为 0，是同一槽容量判断的控制；隔离 Runtime 的 64/65 实验另证天然满容量拒绝。这不代表所有五处都逐一自然饱和。普通拒绝为合成故障注入，不称自然关服或自然执行器过载。

`lifecycle-v4` 128 控制、四条冻结原参数路线自然到达、关闭清理通过。当前字节的源码/class/jar、原世界输入、原参数及退出收尾 5,542 项通过；新测试包不能替换这些历史身份。

最终常规重建生产包 SHA256 `B5DB96C3948A28BA1DB51641960FAB253BBDEAC700F243291354CDDC3F6E8E55`，测试包 `D6377814DA17B4EBF53FF0E0D214AD55BE652B3E5977F47136C96D8D1743F927`。273 个 ZIP 项内容逐一核对，只有 Manifest 的 `Implementation-Timestamp` 改变，其余 Manifest 字段、class、资源和嵌套依赖内容一致；当前 main 源码/class 仍逐个匹配 candidate-v5。`current-release-v1/equivalence.json` 保存两版身份，不将原生命周期伪称新字节实跑。

最终新字节 `native-v9` 再次通过 15 场景/143 控制与 5,495 项独立读取，11 实际到达、两类注入拒绝行为正确、关闭资源清理通过；原结果 SHA256 `ADE4CA8A4E43404ED4EED361805CFC95EFD604813E71860F0AF09EEC9CF27DA7`。当前生产与测试编译、jarJar/reobfJarJar、普通/独立冷探针构建均成功。

DeepSeek 六个小包审核 producer、完整 consumer、实际故障控制、内存机制/数据和容量综合。`supplement-capacity-closeout-v1` 明确 `ACCEPT_WITH_GATES`、本轮必须修改项无。计划包的四项条件由实施与证据包结清，计划包本身不算实施通过。

## 对象内存与旧快照

使用实际生产 Runtime 类，由隔离 Host 驱动两个真实 topology worker。test-only ASM agent 捕获实际任务、LocalKernel、BuildScratch、Row、Evaluation、delta TreeMap 和持久 QueryScratch，按现有任务清理及线程退出释放观察根，不增加生产字段。

Instrumentation 浅尺寸加身份去重字段遍历，分母为该 Host 下的 Runtime 可达字段，加列出的实际工作根与仍在用旧 rows。包含事实、Geometry、Base、容器、future、任务和 scratch；排除线程、类元数据、canonical 维度 key、诊断 hook。这里不是整服导航服务/世界对象图。union 也含用于聚合根的测试 List 壳，临时对象保留至 task cleanup，故属于有额外诊断持有开销的控制点观察；不是连续峰值，也不能证明整个生产服务的内存上界或捕获所有栈中间对象。

| 控制点 | Runtime 字段字节 | 含实际工作根与旧快照并集字节 | 行估计字节 |
| --- | ---: | ---: | ---: |
| flat 27 facts | 1,242,120 | 1,275,040 | 38,544 |
| door 27 facts + air | 1,396,464 | 1,429,384 | 35,004 |
| 并发前 | 943,928 | 976,848 | 38,108 |
| 一 worker 阻塞、另一发布、新旧 rows 同存 | 944,864 | 1,942,208 | 38,544 |
| 释放旧工作后 | 944,360 | 1,010,120 | 38,544 |
| 64 有效 Slot | 3,939,184 | 4,004,944 | 2,093,568 |
| 卸载后的 64 失效 Slot | 3,943,896 | 4,009,656 | 2,093,568 |

并发控制点实际计入 LocalKernel、BuildScratch、两个 QueryScratch 和 128 个临时观察根。旧 rows 与新发布 rows 是不同对象及版本；放行后临时根为零，关闭后 worker 根为零。heap proxy 在遍历前读取，是独立整进程量，不能与上述并集相加。

82 控制及独立逐类字节/对象对账 156 项通过，JVM 已退出。原数据 SHA256 `52406AC806CAAB83A4938E048465C46324E795F251884535B9FD6FA399BF8FDB`。DeepSeek 两包接受上述有限口径。32 MiB 仍是行载荷门限，不能称完整拥有内存限额。

## 冷阶段实验

18 个串行独立 JVM：6 个阶段诊断（两版 × 三条路线）与 12 个不开阶段计时的观察（两版 × 三路线 × 两次）。执行顺序交错，实际启动/退出时间窗不重叠；性能观察期间没有本任务实验 JVM 或 DeepSeek 审核并行。两版同一个独立冷探针 SHA256 `7213E35EA747B2A4DE3AD50EB2ED7CFDDB3AE49F918EAD74509114FB8FE9AB60`。

基线为已推送的 `da55e12` 发行包，SHA256 `FFC78BB8BAF05EC5697B700523268D4A7B0C005AFC29AD31D2FBC34919966EA3`；新包为 `candidate-v5`。两版同 flat 种子、新世界、成年尸壳 `.6×1.95`、accuracy=0、不开门，从 `(3,81,7)` 分别到 `(12,81,7)`、`(28,81,7)`、`(72,81,7)`。

保留现有预热；提交前实际持锁只读检查请求体型匹配的已发布 Base 数为零，24 个父区子 section 的实际 facts 指纹跨全部运行相同且已就绪。共享新分支另核对提交前 shared baseBuilds=0。两个 Super 诊断实际命中 canonical hierarchical=true 并发生 Super 构建；短 Base 和同 section 原路线 canonical=false，新同 section 实际走共享。

这是事实已准备、请求体型派生图尚冷的首请求，不是磁盘 facts 恢复或整服零预热启动。只测提交到 END 首次发现真实 Path 装入，不测整条自然移动到达时间。共享同 section 第一条为短锚点段，旧宏观可以直接搜索更远；不是相同详细搜索终点的 CPU 对照。

| 路线 | 基线两次首 Path 等待 ms | 共享版两次首 Path 等待 ms |
| --- | --- | --- |
| 同 section | 704.8024 / 510.9608 | 601.9110 / 510.4233 |
| 短程 Base | 561.6341 / 561.4784 | 610.9440 / 755.9391 |
| 实际 Super | 661.6291 / 805.2345 | 612.6711 / 1008.4922 |

下表是诊断请求窗口内观测到的区间总和；存在嵌套，不能横向相加当作关键路径。构建数量也不能自动称“未使用图”。

| 诊断路线 | 实际 Base 次数 | Base inclusive ms | Boundary inclusive ms | Super 次数 / inclusive ms | 首次真实 PathFinder ms |
| --- | ---: | ---: | ---: | --- | ---: |
| 基线同 section | 1 | 1.6935 | 0 | 0 / 0 | 7.5589 |
| 共享同 section | 1 基础条件 | 0.8587 | 0 | 0 / 0 | 7.1197 |
| 基线短 Base | 5 | 4.9449 | 3.9949 | 0 / 0 | 8.3788 |
| 共享版短 Base | 5 | 5.5351 | 4.4380 | 0 / 0 | 7.6058 |
| 基线 Super | 64 | 20.9136 | 30.4278 | 8 / 28.4359 | 9.2127 |
| 共享版 Super | 64 | 22.0505 | 32.5430 | 8 / 30.9228 | 9.2029 |

完整 tick 和 dispatch 计时在两种模式都保留，提交前还有缓存检查；“不开阶段计时”不等于零探针开销。Base 子阶段、Super 内部 Boundary 和 worker 并发区间不能重复累加；worker queue 未带请求依赖 ID，不能归为本请求独占关键路径。宏观缺图等待在多次 step 之间，单次 step 墙钟仍可能含锁争用/调度。

每个请求窗口都出现 4–10 个零授额 tick。策略是初始 4ms、上限 20ms，实际允许降到 0；两版固定相同策略，不固定实际每 tick 授额。旧 reader scope 中“4..20ms”简写保留，`cold-budget-qualification-v1.json` 从原始 full-ticks 做 54 项独立核对并更正。不能把几百毫秒首请求等待全归为构图 CPU，也不能据此代批动态预算验收。

18 轮、198 控制及独立读取器 553 项通过，无计时元数据错误、未闭合区间或截断。全原始输出、完整 tick、dispatch、源码冻结与 PID/退出在 `.tmp/shared-topology-production-20261015/`。旧探针 Mixin 兼容性失败和签名反射失败两轮保持，不计资格。

## 后续方向的资格结论

| 方向 | 本轮验证结论 |
| --- | --- |
| 首请求依赖闭包 / portal | 已验证实际 Base/Super 分支和构建数量；未取得依赖关键路径或净收益证据。由完整 Base 派生骨架不能先于该 Base；若要从事实提前生成，须另设计稳定边界身份。 |
| 27 halo 直接查表 / 构图专用化 | 源码存在热循环查找，但单实体两次重复不足以证明收益；本轮不改内核，不以区间相加决定瓶颈。 |
| 中间构建产物 / section 内 tile | 当前没有新实现；需要保守 border、跨 tile 能力/体型、版本与 UNKNOWN 等价证明，不能倒写为首版缺项。 |
| 细粒度依赖 | 恢复等待、临时 pin 和发布 stamps 是不同集合；未证明可以缩小，原依赖保持。 |
| Boundary/Super 全面共享与范围扩展 | 尚无对应生产身份矩阵及实现，不从有限地面首版自动扩大批准。 |
| 持久化、分段恢复、增量烘焙 | 按用户要求暂停；本轮未测 read/decode，不决定存储格式收益或恢复旧异步主线。 |

当前数据不支持稳定提速宣称。下一项有证据依据的调查是零授额、排队、server 发布和请求依赖关联；不是直接实施 portal/tile 或改预算参数。容量修订已经接入；其他新算法没有借本轮研究结论接入生产。

## 审核与交付身份

所有审核使用 WSL 原生 `/usr/bin/dsh --profile headless`，实际 `deepseek/deepseek-v4.1-flash`。无头每包独立上下文，限 6,500 字节，保存原请求、实际传输、答复、超时及退出。settings SHA256 前后均为 `071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858`。

四个冷阶段包已接受：区间机制、冷态与输入身份、实测摘要、后续方向。身份包的三条非阻断意见经完整上下文复审明确撤回：endpoint-resolve 的 true 是事件标记，层级只读 canonical 的真实参数；缓存先按精确 geometry key 取值；ENABLED=false 时阶段计时返回，但公共探针仍有开销。每轮 24 个 facts 与 18 个 JVM 的分母已结清。`supplement-closeout-before-final-v1.json` 的 461 项核对通过，综合终审另登记。

`supplement-integration-v1` 5,592 字节综合包正常退出、无超时，明确 `ACCEPT_WITH_GATES`、本轮必须修改项无，接受本轮有限实施与补充验证结清。内存 Host/诊断闭包、冷态分母、当前构建时间戳等价及新字节实跑均在终审中明示，不通过设计共识扩大实现或性能资格。六个容量/内存包、四个冷阶段包、一个完整上下文补证及综合包共 12 个有效补充包；首版终审另列。失败、超时与未发送旧提示保持。

`supplement-closeout-complete-v1.json` 最终 468 项源码/class/实际文件/审核传输/settings/退出核对通过，含 13 个有效审核记录（首版 1、补充 12）。settings 最后再次实读哈希一致，本任务实验 JVM 均已退出。`git diff --check` 通过；行尾转换提示不表示检查失败。

基线远端 `origin/1.20.1` 再次核对为 `da55e121c8775d32da74f843aaa27023d839ded0`。共享及补充代码保留在当前工作区，未额外推送或部署；用户世界未作为新冷实验输入或被修改。
