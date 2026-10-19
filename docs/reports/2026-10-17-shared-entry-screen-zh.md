# 步骤7：共享入口扩展首次机制筛选

日期：2026-10-17。用户授权根据数据推进步骤7。本轮验证一个隔离候选，触发预定停止条件后封存，**没有新增生产优化，也未完成步骤7的生产准入**。

证据根：`.tmp/shared-entry-20261017-v1/`。冻结协议为 `protocol.md`，当前生产输入和历史覆盖重算为 `input-freeze.json`，实际对照汇总为 `entry-checks.json`。

## 数据选择

复用步骤6的有效受控诊断，重读每次 `supports` 实参：

| 场景 | 资格检查调用数 | 仅因 accuracy 拒绝 |
| --- | ---: | ---: |
| warmup8 | 64 | 32 |
| warmup28 | 64 | 0 |
| burst8 | 512 | 256 |
| burst28 | 512 | 0 |

这些是受控路线的资格检查调用数，不是实服流量权重。三目标查询就绪后采用曾有一次额外查询裁决；立即采用和已测自然 Melee/Brain 没有这次裁决。现有数据不足以把所有 AI 查询作为重复搜索优化对象，因此本轮先验证共享入口，未修改查询采用逻辑。

## 基线和候选

B 为上一轮 worker 组合接入后的当前 **62份**生产输入，不能用已推送提交独自代表它。`baseline-source` 从当前工作区冻结；C 在独立 `candidate-source` 仅改：

- `SharedSectionCandidate.supports`：除原 accuracy=0，增加 accuracy=1；原同 section、width≤1、heightCells≥2 条件保持。
- `SegmentedNavigationService.sharedCandidate`：accuracy=1 的共享负候选或空锚点回到原宏观流程，不据精确目标不可达拒绝整个允许邻域。

共享仍查询归一化后的精确目标，不展开目标邻域。末段真实搜索保留原 accuracy=1、range、regionOffset、visitedMultiplier 和能力，中间段沿用原设计的 accuracy=0；没有新增整条原生回退或复用完整地面 Path。既有每四锚点切段逻辑未改，随后正是该逻辑暴露新增工作。

这两项是隔离设计及其源码范围，不能替代全部正确性验证。特别是负候选回退与允许邻域的真实功能尚未运行，不声称已证明完整性。

正式服务器使用 `build-baseline-v2` 和 `build-candidate-v1`，39份测量工具及 reference sources 逐项一致。最初 `build-baseline-v1` 构建成功，但随后分析器增加新场景名，故保留该构建、另建 v2；v1未用于服务器，不存在丢弃一次不利服务器结果。

实际发行包 SHA256：

- B：`590ff697363f216e9b30a0e41f5de99882238336c888b262dc7e795b2df5d938`
- C：`def1e209fd73d2ed265905fb32b03cff325faff04b2a83fa8ee6eb5ce7f57949`

## 已执行与停止

独立资格检查对 B/C 各16,800项通过，包含负坐标、section 边界、体型与非法/不同 accuracy；它只验证入口布尔条件。公共分析器相关17项 Node 测试通过。

两台服务器 JVM 按 B→C 串行，各运行一个同 section、accuracy=1 的8格请求。ServerStarted 和 sceneReady 后均真实等待至少30秒；关闭深阶段插桩和 JFR，保留相同观测壳、真实搜索统计、路径发布观察和完整 tick。公共分析器两边均 `valid=true`；两台正常退出、无超时或外部 Java，关服 records、active、scheduler pending 归零。

实际路线为 `(-156,81,-159) → (-148,81,-159)`，原 range=35、accuracy=1；两边都真实进入允许范围并最终 SUCCEEDED，无 partial 或 null。原始 scene 的 distance 字段沿用覆盖构造器的28，实际 actor 坐标明确为8格；本轮按相同实际坐标核对，不使用该元数据推断距离。

| 观察 | B | C |
| --- | ---: | ---: |
| 共享请求 / 共享分段 | 0 / 0 | 1 / 2 |
| 真实 PathFinder 搜索 | 1 | 2 |
| 首 Path | 82.1331ms | 106.8293ms |
| 首次进入实际 accuracy 范围 | 2991.0988ms | 2996.2503ms |
| 观测窗 | 3691.5643ms | 3699.1780ms |
| 全进程 CPU | 0.6250s | 2.0625s |
| 全线程分配 | 37,678,792B | 32,611,800B |
| GC 时间 | 0ms | 23ms |

B 只有一次最终发布，实际末节点 `(-149,81,-159)`。C 先发布 cursor=0、stageEnd=4、last=false 的中间段，末节点 `(-152,81,-159)`；再发布 cursor=4、stageEnd=8、last=true 的最终段，末节点同 B。两边 `nativeWholeCalls` 和 `nativeDecisionCalls` 均为0。

因此这里不是只扩大共享命中而保留原执行工作量：新增共享入口使该实际请求从一次搜索变为两次。协议已约定增加可避免工作或保护项不利时停止，故**停止这一具体候选，不接入生产**。

上述是每臂一个请求的冷初始化功能筛选，CPU/分配包含后台、实体执行与公共探针，Windows CPU 有量化影响。不能据其比值认定稳定回归、稳态收益或因果份额；分配下降也不能代替联合保护。停止选择不等于证明所有共享方案无价值。

原计划的 accuracy0、目标边界、精确目标阻塞但邻域允许、障碍、漂移、取消和替换控制尚未执行；新增控件仅保留供将来新范围使用。完整生命周期、512突发净测、A/A、C/N、JMH、长期内存均未运行，不能写成通过。未追加不变候选挑选结果。

## 审核和后续边界

DeepSeek 自包含审查返回 **`ACCEPT_SCOPED`、本轮必须修改项无**，认可停止并封存这个具体候选，不批准生产接入或全部第7步完成。实际 provider/model 为 `deepseek/deepseek-v4.1-flash`，8110字节、145.752秒，审查进程退出0、未超时、settings前后哈希相同。原 prompt/transmitted/answer/process/exit 保留于 `review-final-v1`。

通用调用器的 verdict 正则只接受行首直接出现英文判词，本次答复以“审核结论：ACCEPT_SCOPED”开头，因此原 `exit.json` 的 `conclusion=null`、包装器退出1。已读取完整答复及“本轮必须修改项：无”，将人工核对结论及解析器局限另存 `consensus.json`，没有修改原件或重发审查来选择结果。首次审查包准备因超过8500字节在本地被拒绝，压缩重复字段后才发送8110字节包；未发生额外模型调用。

非阻断建议已落实到记录：末段和中间段精度分别陈述，62份生产输入及原有发行包/探针逐项前后哈希列于 `consensus.json`，B/C测量探针ZIP各项内容比较列于 `probe-entry-identity.json`。未来若重开，还须处理新增共享范围的 generic error/timeout 回退与失效、取消清理；这些不能由本次停止审查代批。

生产62份输入与默认发行包、上一轮测试探针均逐项保持。工作区只新增可复用测量场景、共享计数及文档；本轮未提交、推送或部署，上一轮未提交的 worker 接入继续保留。最终封存见 `final-seal-v1.json`。

本轮仅结清这个共享入口候选的首次筛选；查询采用优化、共享执行策略重设计及实服覆盖仍未完成。不自动推进8，不恢复5a/5b或步骤6候选。若重开，应先说明如何避免新增分段及其实际收益上界，而不是继续原封不动放开资格。
