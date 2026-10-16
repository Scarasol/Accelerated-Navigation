# Portal/Boundary 骨架验证恢复

用户在旧闭包候选暂停后明确授权验证骨架，最终与 DeepSeek 共识。新方案为 `docs/designs/2026-10-15-portal-boundary-skeleton-validation-plan-zh.md`。新目录 `.tmp/portal-boundary-20261015`；生产/test 不改，不续旧矩阵、不恢复历史估计/偿债/持久化。

## 最终恢复点（北京时间 2026-10-15 23:07）

本轮研究收尾完成，正式报告为 `docs/reports/2026-10-15-portal-boundary-skeleton-validation-zh.md`。DeepSeek `final-v2` 于23:00:17正常33.400秒返回 ACCEPT_WITH_GATES/本轮必须修改项无，5472B，900秒截止未触发、settings SHA不变。共识：当前完整Base后提取的源坐标选择器与旧bbox在当前有效组件/允许邻槽上等价，不能新增依赖剪枝；停止作为冷依赖缩减主线扩展并封存，不接入生产。稀疏枚举有单次改善也有退化，部分格缺失/每格1JVM下无法判断可重复收益，不是性能被证伪，也不外推全部Portal方向。

数值/源码推理补证 `mask-domain-equivalence-v1.json` 通过：2通道×4096坐标×14槽×30能力，共3440640决定/114688 mask相等；这是独立算术模型，不是额外MC JVM/自然请求/性能样本。dependency-overlap-v1认可主干并列四前提，综合终审明确实际全部面带cell并集、局部坐标、外层旧判断和范围限制已落实。原码/运行原件保持冻结。

性能仍11完成/1失败/15未跑、2947完成逻辑/11物理、P性能实际4JVM，matrixPassed/performanceQualified=false。失败C/plain首Path后移动阶段2 UNAVAILABLE/510 REACHABLE，精确出口/谓词未测且未重放，不能归因host/debt/Portal。256/八走廊/第二重复未测，不能续旧矩阵或补零；未来申请资格须另立范围/冻结并先查失败。

flat-v1 REQUEST_CHANGES、flat-scope-v2及failure-v1的条件回复全部保留，未冒称其无必改；当前 final-v2 是对修改后的研究/中止报告收尾共识，不撤销性能资格未取得。新工具 `seal-final.mjs` 用于核 frozen sources/scripts/release、62份正式raw/exit、审核原件与候选源/算术脚本身份；`final-seal-v1.json` 为最终封存。只检查本轮Java进程退出，另一个工作区的Java不触碰。生产/test本轮未改，未提交推送。不要运行未执行的 `prepare-final-review.mjs` 旧模板；实际终审是 `prepare-closeout-review.mjs / final-v2`。

以下为完整历史执行记录，不代表仍有运行中的实验或待续矩阵。

exec80885 已在第12轮 `perf-v1-flat-512-closure-plain-r1` 探针失败后退出1；实际 Java PID3492 正常退出0、无本地超时。冻结27轮仅11完成、1失败、15未跑；不续跑、不替换失败，源码/脚本/发行仍按原冻结不动。完成轮共2947逻辑请求、11物理启动，Portal性能实际4个JVM；256、八走廊及第二重复尚无性能数据，功能512不能替代。

失败原件：骨架关闭的旧closure，512均取得真实首Path；停止快照2 UNAVAILABLE（index23/63）、510 REACHABLE，无已记录到达，macro physical started/succeeded/failed=1/1/0。726公共tick中270 SERVER_NO_TIME，最长连续155tick均512 pending，派发前余额14.344599..19.607499ms。plain无详细谓词事件，不能分清两个haveTime或精确UNAVAILABLE出口；与原planning TTL相容但不能写成已证明同因。全部本轮worker/facts/scratch零。另发现Java PID7500属其他工作区、创建在失败轮退出20秒之后，未触碰，不归因其负载。

summarize-performance.mjs 已写 performance-result-v1.json，重读原件/冻结/实际mod/串行窗，matrixPassed=false、performanceQualified=false。analyze-failure.mjs 写 failure-analysis-v1.json，只作失败定位不纳入性能分母。prepare-performance-review.mjs 已生成 performance-flat-v1(4584B)与performance-failure-v1(3779B)，尚未送这两个包；不生成空八走廊性能“通过”包。exec40010 正送 dependency-overlap-v1(4038B)，对GROUND同y组件、旧bbox与新坐标mask是否必然等价作独立反例审查；尚未回复前不得将其当共识。先等并阅读实际答复，再顺序审数据/中止及综合结论。

后续 prepare-final-review.mjs 需按实际11/1/15和缺失P/C格生成自足终审包；若超过6500B应压缩表/拆包。最终报告应明确这版完整Base后提取的源锚点骨架，不把它扩写成所有Portal方案；边界scanned从9216到576不含每GROUND Base新增4352槽提取，必须同时报告提取成本及新增21728图记账字节（平地14 Base）。当前尚无整体性能资格或最终共识，生产/test未改，未提交推送。

以下为准入与先行执行历史，冻结后不可将其中“正在顺序27轮”当当前状态。

16 功能 JVM 已全部通过，共 2,060 逻辑请求，四个 512 门完整，最短 ready 后 30.042592 秒。close-correctness.mjs 已重读原件并封存 correctness-evidence-v1。correctness-v1 3390B/25.465s 认可可有限比较但要求落实五项执行门；performance-plan-v1.json 已先声明完整 27 job/index/轮转/包/输入/脚本身份，correctness-v2 3062B/20.748s 明确 ACCEPT_WITH_GATES/本轮必须修改项无，逐项关闭准入前落实项；原 v1 mustFixesClear=false 保留，不冒称无必改。

seal-correctness.mjs 已依据 v2 写 correctness-v1.json；freeze-performance.mjs 已写 performance-freeze-v1.json，绑定声明清单与源码/脚本/发行/输入/facts/读取器。exec80885 正运行 performance.ps1，顺序 27 独立 JVM。仅这一性能会话在跑，所有功能/控制/审核服务器已退出。任何运行/读取失败即停止，不能改冻结源码或读取器救结果。性能结束后先 summarize-performance.mjs 重读原件，再 prepare-performance-review.mjs 生成自足数据包；若>6500B只能拆分，不能放宽。此时没有性能结果结论或最终共识，不能使用预备模板推定27全通过。

以下保留代码与先行正确性背景；其旧“正在跑512/尚未开始性能”描述已由本节更新。

最终算法 plain/trace 固定在 release-v2，release-v3 主包逐字节复制，仅 probe 增加共同 portalBefore/portalAfter 计数快照；所有最终功能轮用 release-v3。267 主包 class/8 probe class 发行审计通过，plain 无 Trace/历史 controller，差异限九个原诊断源及内部类。

equivalence-v2 通过 84 组 fixture/Geometry、27,582 项字段比较、11,820 真实边能力检查和 1,610 参考 band；六面正例检查已修正为逐面实际非空。bbox 允许 20,456 次而骨架额外剪枝 0 次，不能声称减少邻区依赖。worker-contracts-v2 为 9 组 70 断言，default-limit-v2 为 12 断言，均真实启动至少 30 秒后执行并通过。

DeepSeek：scope-v1 4396B 因自行遍历过多源码触发本地 900 秒截止，无结论，不是 504。之后所有包明确禁止工具遍历、仅审核自足片段：scope-v2 3039B/61.847s READY_TO_VALIDATE；code-masks-v1 5297B/95.185s、code-enumeration-v1 4774B/71.753s、code-gates-v1 3190B/16.178s 均 ACCEPT_WITH_GATES/必须修改项无。代码门槛为 step1/jump3/drop4、slot<=1023、十四槽、final 字段和原发布、boundaryWork 只计数无预算分支，已补包关闭。均原生 WSL dsh、deepseek/deepseek-v4.1-flash、900 秒截止和 settings SHA 不变。

functional-v3.json 的 12 个单实体场景全部通过；exec71183 正顺序执行末四个 auto/portal × flat/corridors 的 512 正确性门，尚未完成前不写整体通过。全部真实自然终态，partial=EXHAUSTED，其余 SUCCEEDED。exec27674 控制批已退出。功能/控制可重叠，仅正确性用途；性能要求全部退出且顺序独立 JVM。

reader-controls-v1 29 类损坏拒绝和 2 正例通过；曾发现宏观 owner 关联缺失的读取漏洞，已加实际 service 事件 owner 集校验并重验。functional-v2-portal-flat 原始成功但旧读取器错误要求 SUCCEEDED 后仍保留 Path 节点；实际终态清空节点，读取器已区分首 Path 正例与最终终态，原件不改，另轮 v3 完整功能正在跑。不要把这次读取器修正当算法修复或性能数据。

后续工具：close-correctness.mjs 要求全部 16 功能 +3 控制、发行/读取器/代码共识齐备才写 correctness-evidence-v1 并准备正确性包；取得独立 correctness-v1 回复后 seal-correctness.mjs 写准入 seal；freeze-performance.mjs 才冻结 27 JVM；performance.ps1 遇任何运行/读取失败立即停，不补跑替换；summarize-performance.mjs 重新读取全部实际结果并保留失败/未跑。性能还未执行，不能根据功能耗时作性能结论。

以下为早期历史接线记录。

当前实现：完整 Base 持有稀疏六面源锚点和十四邻槽能力候选掩码；mayExit 先保留旧 bbox，再查掩码；BoundaryLinks 仅替换源锚点枚举，目标组件、碰撞与 band 生成复用原逻辑。没有独立缓存/future/pin，代次仍由原 CapturedBase/SectionStamp/validity 约束。不能宣称省源 Base 构图，必须与 closure 单独比较新增骨架效果。

已完成临时源码及带正反例的逐字段等价性控制编译。首次准备脚本因替换匹配两处而停止，修正匹配数后完成；未启动任何实验，非算法失败。两次 Gradle 启动因本机 Java Unix-domain loopback 错误而退出；仅对构建进程使用与原实验 runner 相同的不存在 socket 路径后成功，未调整实验 JVM/预算。

release-v1 保存第一版 trace/probe。equivalence-v1 正在执行全六面/十四邻槽/三十能力位的旧新 BoundaryLinks 比较；场景/控制真实 30 秒门保持。该接线控制不进入性能分母。方案送审 scope-v1 4396 字节，原生 WSL dsh，deepseek / deepseek-v4.1-flash，900 秒截止，尚待实际答复。不得预写共识。

新诊断脚本 `tools/instrument-extra.mjs` 计划在最终冻结前补两个 haveTime 谓词的拒绝点，保持原短路次数，并在 finish 清理前保存全部请求终态。性能尚未开始，27 JVM 只是计划。先检查等价性和审查回复，处理必改项，再跑真实 worker/自然移动/512正确性门。
