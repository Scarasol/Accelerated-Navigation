# 共享拓扑首版生产接入与补充验证

日期：2026-10-15。状态：接入前基线已推送；共享首版核心与本轮补充有限验证均完成并取得 DeepSeek 综合共识，本轮必须修改项无。共享改动仍在工作区。

## 用户授权与执行顺序

用户要求在离开期间持续完成以下工作，不需要阶段之间再次确认：

1. 持久化暂不考虑。先确认动态预算除验收外是否已接入；成立后将当前工作区代码推送到 `1.20.1`，再接入已经验证的共享拓扑部分。
2. 首版接入后验证实际新增链路，调用 WSL 原生 dsh 无头 DeepSeek 审核并达成共识。
3. 首版核心审核通过后，对后续补充进行验证并与 DeepSeek 达成共识。

DeepSeek 必须使用 provider `deepseek`、model `deepseek-v4.1-flash`。每次无头调用都是独立上下文；每包自带相关约束、代码与证据，控制大小，必要时分批并最后综合。不得把超时或带必须修改项的答复登记为通过。

## 接入前核查

当前分支 `1.20.1`，接入前 HEAD 为 `e45c945`。工作区已有动态预算控制器、完整 tick 虚调用 Mixin、START 补额、END 派发、实际时间计费与中间段末节点保护。源码上已完成接线；动态预算正式验收、生产默认值批准及部署状态仍未完成。

2026-10-15 接入前执行离线 `compileJava compileTestJava verifyCoreContracts jarJar reobfJarJar productionProbeJar reobfProductionProbeJar` 全部成功。核心输出为 Reverse integration 3 项、Core contracts 9 项通过。这些结果不是动态预算完整验收。

## 首版范围

迁移最新已经验证的 `scratch-v1` 中基础与局部行、门条件替换、不可变分块表示、有效 Slot 捕获复用、worker 独占查询数组及 Runtime 生命周期机制，接入正式调用链。

首版保持已验证的 GROUND、width<=1、heightCells>=2 范围；新增链路仍必须使用真实原版详细搜索及当前性门禁。跨 section 未改造部分沿用现有结构，不能因新候选缺失伪造原版无路或静默转整条执行。

首版接入及其增量验证与补充研究分开登记。组件/Boundary/Super 全面共享改造、进一步压缩及体型/专用导航扩展不能自动反写为已经通过的首版原型缺项。实际接入涉及的边界、版本、关闭与成本必须验证。

## 暂停范围

共享基础/局部差异落盘、旧异步持久化 v5 修复与缓存格式不在本次任务中。既有失败、超时、冻结源码和实验结果均保留；不修改用户世界，不回退其他工作区变动。

## 早期检查点（历史）

接入前构建已结束。基线 `da55e121c8775d32da74f843aaa27023d839ded0` 已推送至 `origin/1.20.1`，远端哈希一致；基线发行包冻结在 `.tmp/shared-topology-production-20261015/baseline-da55e12/`，SHA256 `FFC78BB8BAF05EC5697B700523268D4A7B0C005AFC29AD31D2FBC34919966EA3`。

生产已迁移五份冻结研究核心源码，并用包内 helper 代替原型反射及 Fixtures 依赖。新增正式服务发布入口及 `SharedSectionCandidate`。首版正式路由范围明确为同 section、accuracy=0、GROUND、width<=1、heightCells>=2；其他请求继续现有宏观链路，特殊不支持的门/流体输入明确回到原有宏观候选流程，不作整条详细执行回退。

共享候选使用最多四个连接的短段，由真实 PathFinder 生成节点；最终实际到达、partial、能力/输入失效保护保留。共享分支物理 native-stop 直接 EXHAUSTED，不自动两次物理重搜；中间真实节点正常消费后从实际位置继续，未重构现有组件/Boundary/Super。

生产/测试编译及发行构建成功；现有 Reverse integration 3 项、Core contracts 9 项通过，迁移后的 Runtime 1,285 项、scratch 3,387 项控制通过。运行证据为 `core-v1/runtime.json`、`core-v1/scratch.json`，故障注入线程 fatal 栈属于预定退出控制，测试结论 passed=true。

发行态 `native-v1`、`native-v2` 前十场景均取得预期终态，最后的小体型 Pig 被服务器以 DISCARDED 移除。第二轮入口诊断确认 removed=true、配置 automatic=true、policy=SOFT；性能模板 spawn-animals=false，不能将此当作共享实现失败或删除原件。增加 `shared-server.properties` 允许测试实体存活，`native-v3` 正在运行。全部早期退出及失败保持，尚未登记首版审核通过。

## 早期恢复检查点（历史）

`native-v3` 与 `native-v4` 各通过 11 场景、96 检查；v4 使用收紧后的首次共享搜索/恢复额度 12/8。已有非零额度任务继承原额度，不因接入共享而放大。`native-v5` 增加受控真实 native-stop bridge 与原版 stop 控制，通过 12 场景、111 检查；这是停止后清理和普通新请求恢复控制，不能称为自然物理停滞实验。

`native-v6` 将显式共享 API 的范围外拒绝统一为专用 `UnsupportedInputException` 并增加直接 API 控制，通过 12 场景、112 检查。独立读取器通过 5,392 项，10 项实际到达、1 项铁门正确负候选、1 项取消；31 次真实搜索、15 次共享请求。原始结果 SHA256 为 `E89CFE9F647EF668E1889B4D33E095524DC62CE271C68C88A7C8AB39B7A88DA1`。

`lifecycle-v1` 通过 129 检查、四条原参数路线自然到达；其包早于最新范围异常修订，不能当成最新包同字节验收。当前包的 `lifecycle-v2` 正在独立运行。

DeepSeek 原较大包 `candidate-v1-api-publication-1` 因追读完整文件而超时，无有效结论，所有原件保留。小包 `candidate-v2-bounded-v2-api-publication-1` 正常退出但要求修改：范围外异常已统一；其对请求集合并发性的疑问需用实际 `ConcurrentHashMap.newKeySet()` 声明和单线程新增约束补证。随后冻结新版本分批复审，不把单包退出或已有原型共识登记为整体接入通过。

## 首版核心终审结清

冻结 `candidate-v4` 为当前核心资格：生产 SHA256 `540A7A4BCF24DC2346EA9594C63C2A094B626CDFEC80698BE82762493C5A4CDF`，探针 `5F8A12885C36DE02BA6D8C06537F620703B47CBFBB192A3D671914D0465BC014`。审核发现去门会清除门格流体标记，已对该未支持输入使用专用异常；新增控制另复现原 `failBase` 释放 Slot 会将专用异常遮蔽为取消异常，修订为锁外交付原失败并检查兴趣、TTL、ownerOpen 和原事实 stamps。失败 `core-v3` 保持。

`core-v4` Runtime 1,296、scratch 3,387、现有核心 12 项通过。`native-v7` 12 场景/112 检查通过，独立读取器 5,401 项通过；原结果 SHA256 `3310DAEE2333BBFD68FF8697F10E8E8476861BBAAE68555C9675E009C2399F09`。`lifecycle-v3` 138 检查、四条原参数路线自然到达及关闭资源清理通过。源码/class/实际加载 jar/原世界输入/退出与原参数收尾 5,496 项通过。

旧 `candidate-v3` 34 小包均已结束，32 有有效结论，其中两个超时保持。对缺失片外声明/调用的意见分别补完整控制流复审：并发集合与 server 回调、固定累计上限与 partial、捕获错误与恢复计数、提交/完成适配、合法性 0/1/2、目标全身体积校验。实际两个生产源修订由 `candidate-v4` 的 air/failure 独立复审；行读取超时另以完整小包重审。原意见和答复不删除，不把旧源审批当成新源审批。

新候选实际服务器和核心证据两包均接受。`candidate-v4-integration-v1` 的 3,497B 终审正常退出，明确 `ACCEPT_WITH_GATES`、本轮必须修改项无。仅登记同 section、accuracy=0 的有限地面首版接入共识；保留 12/8、刷新一次、真实 partial/最终到达与当前性门禁。64 Slot 无 idle eviction、完整内存/长期扩区、性能、全面上层共享、持久化和动态预算正式验收均未扩大批准。settings 前后相同，实际 provider/model 与用户要求一致。

首版核心结清后开始 [补充验证范围](../../designs/2026-10-15-shared-topology-supplement-validation-plan-zh.md)。共享改动仍在工作区，未另行推送或部署。

## 本轮最终恢复点

补充按用户顺序在首版通过后执行，已结清容量、选定对象闭包/旧快照和现有 Base/Super 冷阶段观察。发现 64 Slot 占满后卸载不自动回收；五处共享准入改用专用类型转原宏观，通用拒绝仍 UNAVAILABLE，不改行语义、额度或整条详细执行规则。`candidate-v5` 六个容量/内存包接受，82 补充控制、Runtime 1,296/scratch 3,387/核心12、实际15场景143、四原路线与128生命周期、旧字节收尾5,542通过。

18 个串行独立 JVM（6阶段诊断/12不开阶段计时观察）、198控制、553独立读取与54原始grant核对通过。每轮24个actual facts指纹相同，精确体型Base尚冷；实际Base/Super/shared分支验证。预算可降0，首Path有明显波动，不能宣称稳定提速。未取得纯CPU/请求关键路径/未用图/全并发资格，没有实施portal/tile/新内核/上层全面共享。

最终生产 `B5DB96C3948A28BA1DB51641960FAB253BBDEAC700F243291354CDDC3F6E8E55`，常规探针 `D6377814DA17B4EBF53FF0E0D214AD55BE652B3E5977F47136C96D8D1743F927`。对candidate-v5的273个ZIP项及main源/class逐个核对，只有Manifest构建时间改变；current-release-v1保留两版身份。新字节native-v9再次143/5,495通过。生命周期保留原实际测试字节，内容等价迁移范围在终审明示。

四个冷审核、一个上下文补证及5,592B综合终审正常退出，实际deepseek/deepseek-v4.1-flash，settings未变。综合明确 `ACCEPT_WITH_GATES`、本轮必须修改项无。原scope“4..20ms”及step等待表述在独立更正中澄清，旧件保留。首版与补充共识不批准动态预算正式验收、全进程峰值、稳定性能、新算法或持久化；这些范围保持未批准/暂停。

当前详细结论、证据身份与复现入口见 [补充验证记录](../../reports/2026-10-15-shared-topology-supplement-validation-zh.md)。基线远端再次核对da55e12一致，共享改动未另推送或部署，用户世界未修改。
