# Worker 最终算法组合生产接入

日期：2026-10-17。

用户授权顺序为：先把已有未提交生产工作推送到 `1.20.1`，再接入最终算法组合，最后由 DeepSeek 审核。基线提交 `4b82ed6762b86298ec204637f92cf96c890689e7` 已推送并核对远端；随后才修改生产算法。本轮接入代码暂留工作区，未再次提交或推送。

证据根：`.tmp/production-worker-integration-20261017-v1/`。选型依据为[最终组合报告](2026-10-17-final-worker-algorithm-combination-zh.md)，原隔离证据保持。

## 接入范围

生产区八份算法源码与 `.tmp/final-worker-combination-20261017-v1/final-main/` 的选定内容一致，只在身份比较时归一化 CRLF。原先另外 54 份生产输入逐文件哈希保持。详见 `source-identity.json`。

| 部分 | 本轮接入 |
| --- | --- |
| 宏观依赖选择 | 稳定小数组 Top-K 仅用于 K≤16；更大 K 保留原完整排序 |
| 端口摘要与依赖剪枝 | GROUND 共享合法锚点行位图，按组件及原 inset/distance/dy 匹配；正反宏观扩展均在已捕获邻居拓扑、缺少 Boundary 时执行零拒绝 |
| Boundary 枚举 | GROUND 逐置位枚举；每个位置先精确碰撞，再按 descriptor 合并 |
| 碰撞 | 两通道使用不可变非空 Shape 行索引；保持 UNKNOWN、门、缺失与身份检查；合并私有轴对齐严格谓词的重复扫描 |
| 运行时 | 第二版每 runtime/每线程惰性回调上下文；锁内移交清空、锁外执行 |
| 执行器 | 固定 12 组队首缓存；原树、老化、kind/FIFO 与整组 BUILD 占用规则保持 |

VOLUME 不分配端口索引，`mayLink` 保守返回 true，Boundary 候选仍走原标量实现。原组件包围范围、Super 14 位潜在出口和必要精确碰撞/BFS 保留。没有加入额外 BVH、全局结果缓存、首版回调列表、窗口研究候选或短程整条原生试搜。

`build.gradle` 新增 `verifyWorkerAlgorithms`，并把它和原 `verifyCoreContracts` 接入 `check`。新增 `WorkerAlgorithmContracts` 不依赖 `.tmp`：验证排序及 Float 特殊值、独立 xyz 位图对照、组件隔离、完整 Boundary 标量对照、碰撞索引序列化重建、重入/异常/同步 future、队列全表 oracle 和实际并发任务执行。

## 正确性与构建

`build-and-contracts-v3.log`：`verifyCoreContracts verifyWorkerAlgorithms build jarJar reobfProductionProbeJar` 通过。七组新增契约、Core 9、Boundary recovery 9、macro commit 4、Door 28 及图导出通过。新增实际队列检查由四个生产者提交 600 项任务，逐项核对执行或取消恰好一次，并检查 BUILD/PREWARM 串行占用。

外部回归的生产 classpath 首先放置本轮 Gradle 输出，并核对 58 个生产 Java 类的实际加载来源。复用原 oracle 与固定语料，所有新输出写入本轮目录：

- 宏观 5,400 项及混合依赖/坐标 125 项通过。
- `open` 118,800 次、严格接触/退化线段 24,000 次；定位 4,096 次、查询 21,504 次及身份冲突拒绝 4 项通过。
- 独立行集合 515,840 项、GROUND 标签位 491,520 项通过。
- 2,520 组完整 Boundary 与封存标量 oracle 一致：873 边、2,898 band，包含 descriptor、能力、位置 word 与 lowerBound。
- 8,000 次同步 future 完成；1/2 线程×0/1/8 回调矩阵通过。
- 队列全表 oracle 30,000 次、四生产者补充 2,400 次选择比较通过。后者仍是 worker 被阻塞时的选择检查，实际执行由上述新长期契约另行覆盖。
- 26 组窗口及 26 个并发窗口通过。

权威汇总为 `integration-checks.json`。本轮没有重跑上一轮 JMH，也没有把旧服务器 CPU 数字转移给本轮接入。

## 保留的失败与校正

首次 Gradle 启动遇到本机 loopback 错误；使用已有诊断脚本相同的不存在 Unix socket 目录及 IPv4 参数后构建正常，没有修改项目代理或 DeepSeek 配置。

新长期测试首次把 Super Boundary 的距离写成 1，构造器按现有契约拒绝；已将测试数据改为 `CHILDREN_PER_AXIS`，没有更改生产规则。失败日志 `build-and-contracts.log` 保留。

外部检查的第一次汇总误把跨 JVM 的宏观聚合摘要哈希当作等价门，因完全并列依赖保留各自 Map 遍历顺序而失败。每个 JVM 内的 5,400 项原稳定排序对照实际上均通过；修正后的 `finalize-checks.mjs` 保留两个不同摘要，只对语义 oracle 及确定性 Boundary 输出作等价判定，未重跑以挑选摘要。原 `validation.log` 保留。

发行态生命周期首轮 F 与同源 B 都在相同断言失败：前面的原生导航控制已累积一次 `nativeWholeCalls`，而后续分段请求断言要求全局总数为零。B 使用封存 `build-clean-baseline-v1` 包，61 份输入与已推送基线逐项匹配，Jar 哈希亦核对通过。修正 `ProductionNavigationProbe` 为记录本次请求前后的 whole/segment 增量，同时继续要求当前请求不是 native backend、确实增加分段搜索、整条回退不增加。两份初始失败原件均保留。

修正后 F/B 的生命周期运行分别通过 87/95 项检查，然后均在历史 `stairs-forward` 路线以 UNAVAILABLE 停止，实际位置均为 `(-22,78,-196)`，目标为 `(-37,101,-195)`；两者错误文本相同，均有 24 次分段搜索、15 次前置到达。八项检查数差异全部来自异步 POI 等待期间逐 tick 重复的 `poi-pending-does-not-activate-navigation`，不是八个缺测场景。原路线均未完成第一条，后续未执行。这不是“四条原路线通过”，也不能由该单对证明所有路线非劣。该原有楼梯缺口见[历史停止记录](2026-10-17-short-native-isolation-stopped-zh.md)。

发行态平地交接探针通过 13 个场景、130 项检查，包含五组末节点进入/微小位置偏移、超时、原生停止、世界变化、取消、替换、重算、窗口关闭及关服。`wait/shutdown.json` 确认导航记录、活动任务、调度请求归零。四个生命周期 JVM 和交接 JVM 均正常退出、未超时；过程退出 0 与生命周期探针功能失败分别保留。权威功能汇总为 `live-checks.json`。

## 审核与边界

DeepSeek 首轮分五包审核，均返回 `REQUEST_CHANGES`。全部原件保留为 `review-{macro,port-index,boundary-enumeration,geometry,runtime-queue}-v1/`，不能写成首轮通过。除完整楼梯路线门禁外，意见中还包含因 diff 未展示既有上下文、以及包内措辞不够明确而提出的证明要求。本轮没有据此把有向边改成物理反向、合并相反方向 key 或新增 GROUND 纯垂直移动。

已完成下列补证，并纳入最终复核：

- 修正后的汇总程序实际执行成功两次，第二份为 `integration-checks-v2.json`。额外新建生产 JVM 保存 600 组、5,400 项 Top-K 原始输入、原稳定流和实际输出，约 13 MB；独立读取器按浮点位值重建 4,200 组有效输出、86,192 个候选 key，全部一致。见 `macro-trace.json`、`macro-trace-verified.json` 及 `finalize-v2.log`。不要求既有比较器新增跨 JVM 全并列确定性。
- 展示 `boundaryLinks` 在通道分支前已经存在的空值、几何一致及邻接校验；新增 14 组负例对照两实现的 28 次异常拒绝。
- 展示垂直邻居必须同 x/z、水平邻居支持 yShift 的实际规则；四个水平方向显式验证“向上并向前”属于水平面加 yShift=1。GROUND 距离至少 1 原已存在于标量枚举，VOLUME 仍保留纯垂直标量连接。
- 展示 incoming 搜索枚举原有 A→B 的前驱语义；反向搜索与正向使用相同原始有向 key，物理 B→A 是另一条边。已有真实运行时单向下落、依赖等待、版本恢复和反向不可达契约再次通过，另加直接 key 等价/不等价断言。
- 展示 `segmentClear` 全部调用为 L 形轴对齐段或四邻居 BFS；新增十项内部端点、严格相切、退化点和负坐标检查。碰撞 mask 由不可变 cells/palette 唯一推导，全部 4,096 格及序列化重建已检查。
- 原回调容器确为 `List<Runnable>`/`ArrayList`；旧 `ThreadLocal.set` 的 set 是赋值操作。补测同一 Runnable 注册两次执行两次。展示执行器 take/promote/repriority/continuation 的实际 helper 调用顺序，foreground 只有三个结构修改点；新增长期检查逐次核对全部 12 组缓存队首，并执行 64 次真实 continuation。

补充仅修改测试和证据，八份生产算法源码保持封存选择；对应 `review-supplement-contracts*.log` 保留每次有新增检查时的实际执行。最后一次 `review-supplement-contracts-v4.log` 七组契约及 recovery/commit/Door 全部通过。

DeepSeek 补充语义审查 `review-semantics-v2` 返回 `ACCEPT_SCOPED`、必须修改项无，明确撤回此前关于前置校验消失、反向搜索方向和私有斜线调用的静态发现，并确认其他 Boundary/geometry 可达实现中未发现必须修改项。

最终整体接入复核 `review-integration-final-v3` 返回 **`ACCEPT_WITH_GATES`，本轮必须修改项无**。它确认运行时回调、执行器缓存及续跑的实际调用路径和新证据支持本次有界代码接入，未发现新的可达实现缺陷或证据缺口；首轮五份 `REQUEST_CHANGES` 在本范围内由补证与再审查覆盖，原件仍保留。

最终结论仅同意进入本地提交与代码评审阶段。楼梯正向真实失败、完整四路线资格、实际 worker/首 Path/内存/GC，以及后续提交推送、远端 CI 和发布部署仍是独立门禁；没有获得全局发布批准。

七次审查均进程退出 0、未超时、配置哈希未变，实际 provider/model 为 `deepseek/deepseek-v4.1-flash`。选择、原件与执行身份见本轮 `consensus.json`；原最终候选证据目录内 267 份封存文件逐项核对未变。本轮最终封存见 `final-seal-v1.json`，包含当前生产输入、测试、文档、审查原件和关键失败/成功结果。

本轮构建了新的默认普通 Jar、all Jar 和测试探针，身份见 `integration-checks.json`。源码接入、局部等价、发行态功能与整体性能分别陈述；实际 worker 净收益、首 Path 非劣、内存/GC、历史楼梯缺口和正式部署资格不由本轮局部算法检查代批。
