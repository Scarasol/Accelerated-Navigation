# Base 派生拓扑持久化原型验证

日期：2026-10-13。状态：离线编码及隔离 Runtime 原型已验证，DeepSeek 最终审核为 `ACCEPT_WITH_GATES`，本轮原型必须修改项为无。未接入生产，未获得真实服务器冷启动性能资格。

用户本轮范围是派生拓扑持久化研究，以原型验证为主，验证后通过 WSL 无头对话交 DeepSeek 审核。动态预算由另一个对话完成。本轮不修改调度器、预算、预热策略、搜索算法、生产存储或游戏世界，不调用子代理。

唯一规范为 [现行详细寻路契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)。此前冷图报告只作为问题背景；旧设计、旧审查不作为本轮验收规范。

## 当前判断

可以把 Base 的不可变组件和局部边保存下来，在当前基础事实准备完成后读取并重新绑定边界事实，通过既有 Runtime 发布门禁交给宏观消费者。原型验证了这条路径的可行性。

本轮没有证明缓存会改善真实服务器的首次 Path 等待。读取、完整输入摘要计算和解码验证并非零成本；简单图直接构建可能更快。下一轮应先验证有界异步 I/O 和完整冷启动首次路径，再决定生产接入。Super 图、跨区段连接成品、宏观走廊、实体 Path 和 NodeEvaluator 均不持久化。

## 源码核对与接入位置

| 核对项 | 当前行为 | 持久化含义 |
| --- | --- | --- |
| `TopologyStore` | schema=7、facts algorithm=7；保存 section version、PackedFacts、SectionGeometry | 保留基础事实文件与格式，派生缓存使用独立文件 |
| `BaseClusterTopology.build` | 生成 4096 格组件标签、组件元数据、局部 CSR 边、能力掩码、原始 float 成本位、halo stamps | 这些是首轮保存的 Base 成品 |
| `boundaryFacts` | 持有当前中心及相关 halo 的不可变 PackedFacts；BoundaryGeometry 随后仍读取它们 | 不能只恢复标签和边；恢复必须重绑当前事实，不重复保存整份邻区几何 |
| `incomingEdges` | 根据 outgoing CSR 懒构建，已预留缓存计费 | 从恢复的 outgoing 重新生成；不保存可变懒状态 |
| `signature` | 包含 `geometry.hashCode()`；GeometryKey 内含 Channel 枚举 | 不作为跨 JVM 身份，恢复时按当前对象重新计算 |
| `beginBuild` / `publish` | 捕获事实与 stamps，核对 demand、generation、halo、当前性后发布 | 缓存结果复用同一发布流程，不能绕过输入保护 |
| `SectionStamp` | 保留本进程 LatestFacts 对象身份、loadIdentity，以及缺失依赖 | 不序列化旧对象或旧 loadIdentity；新 JVM 必须重新捕获 |
| Base 内存额度 | `MAX_BASE_RETAINED_BYTES` 管理空闲图、空闲事实和关联引用，活动事实另有计费 | 128MiB 不是全进程或所有活动图硬上限；缓存解码与 I/O 在途资源需要额外有界管理 |

生产接入建议新增包内校验工厂及独立派生存储适配，不修改事实 schema。本轮为避免生产改动，原型通过反射调用现有私有构造器；生产方案不依赖反射。

## 持久身份和内容

原型键采用固定顺序二进制编码再 SHA-256，包含：

- 独立实验世界 namespace、完整维度 ID、section 三轴坐标。
- 派生格式版本、派生算法版本和事实算法版本；原型的派生版本均为 1。
- Channel 名称、widthCells/heightCells、width/height 的原始 float 位、fluid/door 标记。
- 中心和全部 26 个 halo 槽的存在性；存在时包含 revision、现有事实 fingerprint 和规范事实内容 SHA-256。

规范事实摘要计算包含 PackedFacts 字节和现有 SectionGeometry 编码。本轮直接计算全部捕获事实摘要，属于保守实验：未用 halo 的变化也会造成安全未命中。生产是否按实际依赖缩减摘要、在不可变事实创建时缓存摘要，要独立计时并保持 missing 依赖和 `hasDoors` 的完整影响。

不能只用 section revision 或现有 64 位 fingerprint 批准磁盘结果。完整摘要用于内容绑定，当前 stamps 才负责运行期身份。世界文件复制、恢复与模组变化仍需通过当前事实恢复和算法版本确认，缓存存在本身不证明地形当前。

生产建议使用每世界派生目录 namespace 与显式算法签名。所有实际影响 Base 构建的常量/策略必须纳入派生算法版本维护，包括结构步高、跳距、落差、标签次序、门/流体规则、边排序和能力位布局。MovementKey 当前只从 Base 已生成的能力掩码选择结果，因此不需要按每个 MovementKey 重复存 Base；未来若它参与 Base 构建必须升级身份。

原型文件头含 magic、format、压缩前/后长度、输入 SHA-256、原始图载荷 SHA-256；随后是 level 5 DEFLATE 数据。标签先用每格 ushort，恢复为现有 SimpleBitStorage 或 ZeroBitStorage，保留 uniform baseLabel 语义。该选择便于校验，本轮不比较压缩算法或编码方案。

解码先检查长度和摘要，再校验组件数/标签、由标签重算的 anchor 与包围盒、CSR 单调性/首尾、目标范围/次序、能力位、float 有限性、halo 次序和实际输入、boundary 依赖完整性及尾数据。图构造成功后仍必须经过 Runtime 当前性发布；校验不承担详细寻路可行性判断。

## 离线编码原型

证据目录：`.tmp/derived-topology-persistence-research-20261013/codec-v2/`。

原型直接编译本轮未修改的 BaseClusterTopology、BoundaryGeometry、IncomingEdges 和 SectionGeometry 源码，使用本地 Java 17.0.18 与 Forge 1.20.1-47.3.0 映射依赖。没有替换项目 build/classes 输出。

样本共 27 个：

- 固定城市事实载荷的 11 个竖向 section，每个使用 0.6×1.95 和 1.4×2.7 两种精确体型，共 22 图。
- 全实心地面、全空气 VOLUME、平地、下方 halo 支撑、带方向/能力边的台阶，共 5 图。

城市载荷是既有 `city-packed.raw` 事实输入，38,525 字节，SHA-256 `AFABE20E8562D462B4A5D3D5CA85D99D7917D78984C136D08090C654CB58FE77`。它只提供一列及相邻竖向事实，水平邻区缺失，不能当作完整城市几何或完整路线验证。只复用数据，不复用旧方案或旧通过结论。

种子 JVM 写文件，另起 5 个 JVM 读取同一批文件，每个逐格比较 4096 标签及全部原始边、能力、float 位、halo、boundary，并核对反向索引和当前事实对象绑定。种子 182 项断言、每个恢复 JVM 162 项断言通过；这些重复数不能合计为同样数量的独立场景。

20 项种子负控制包含截断/尾数据、格式/输入/校验和错误、过大长度、合法校验和下的坏组件/标签/元数据/CSR、世界/维度/精确体型不同、中心同版本内容变化，以及 halo 由缺失到存在、由存在到缺失、revision 或内容变化。保留原始结果，不自动将缓存拒绝映射成无路。

27 份文件合计 9,487 字节，最大单份 2,096 字节。这是图载荷压缩结果，不含已有事实文件、文件系统簇占用、目录索引、共享事实留存或生产磁盘总成本，不能外推容量比例。

### 跨 JVM 签名反例

对 JVM 启动前的对象 identity hash 分配做有控制的扰动，六个进程中的 Channel identity hash 和相同平地图 `runtimeSignature` 均出现不同值，而固定顺序编码的磁盘 SHA-256 键一致，恢复图在各自进程内与正常构建签名一致。

这证明现有 signature 只能作本进程身份，不能写入文件后要求下次原值相等。原型保存稳定摘要，恢复时经原构造器计算当前 signature；不修改生产 signature 算法，不复用旧走廊或组件句柄。

### 诊断成本

计时为每 JVM 27 个图的墙钟相加，单位 ms：

| 恢复 JVM | 本 JVM 顺序 | 正常构建 | 读取、输入摘要、校验及恢复 |
| --- | --- | ---: | ---: |
| 1 | 构建后恢复 | 52.75 | 24.12 |
| 2 | 恢复后构建 | 38.37 | 35.93 |
| 3 | 构建后恢复 | 47.28 | 21.71 |
| 4 | 恢复后构建 | 38.25 | 40.40 |
| 5 | 构建后恢复 | 60.11 | 28.53 |

基础事实已经解析，BuildScratch 创建在计时外；恢复包含 ReadAllBytes、当前事实规范摘要、SHA、解压、完整校验和构造，比较在计时外。5 个新 JVM 每个样本只有一次，不是每个样本独立新 JVM；有 JIT 顺序和 OS 文件缓存影响，未控制物理磁盘冷读、GC 或 CPU。第 4 次恢复总成本高于构建，因此不报告生产提速百分比或稳定速度优势。

## 实际 Runtime 隔离原型

证据目录：`.tmp/derived-topology-persistence-research-20261013/runtime-v2/`。

复制当前 TopologyWorkerRuntime，仅把一处 Base 构建调用改为 `PrototypeRuntimeCache.loadOrBuild(dimension, ...)`；其余需求合并、当前性、发布、宏观查询、引用和关闭代码保持。独立读取器按完整文本确认此差异。依赖使用当前生产编译类，临时 Runtime 与 adapter 优先加载，没有改写生产输出。

存储在构建 worker 内同步读取，在 `beginBuild` 后捕获的真实输入上核对文件；命中和正常构建均回到既有 `publish`。故意暂停已恢复结果的测试门只存在 adapter。同步 I/O 占用单构建 permit 是原型局限，不是生产调度设计；未实现 I/O 队列额度和读超时。

种子 JVM 32 项断言，另一个恢复 JVM 46 项断言全部通过：

| 场景 | 结果 |
| --- | --- |
| 进程重启 | 缓存命中，无新增 Base build；旧 loadIdentity=10 改为当前 77 |
| 恢复后、发布前 halo 修改 | 旧结果被发布门拒绝；以当前 revision=6 构建/发布 |
| 原缺失邻区加载 | 旧 view 失效；新事实进入 boundary，旧记录不能批准 |
| 取消 | 等待中的订阅取消；迟到恢复不复活它 |
| 共享订阅取消一个 | 剩余订阅取得同一次恢复，取消不会终止其他消费者 |
| 中心卸载/重载 | 同 revision、新 loadIdentity=88；旧订阅被拒绝，新订阅绑定新身份 |
| 关闭 | 受阻的恢复与后续请求被拒绝，worker 退出 |
| 坏文件 | checksum 失败后正常 Base 构建并发布 |
| I/O 路径冲突 | 读取和写入失败，普通图仍成功发布 |
| 不同精确体型 | 默认体型文件不能满足较大体型，正常构建新图 |
| 真实宏观消费者 | 两个恢复 Base 图被现有 requestMacroQuery 消费，跨边界走廊成功，期间无新增 Base 构建 |
| 无派生文件 | 普通 Base 构建保持成功 |

恢复运行计数：hit=10、miss/build=6、readFailures=4、writeFailures=1。失败计数均来自预定旧输入或故障反例，不是业务断言失败。12 类场景关闭后，activeReferences、activeFactsBytes、Base/Super/corridor 空闲留存、buildDemands、dependencyConsumers 均为零。

这一部分运行真实隔离 worker Runtime 和宏观搜索，未启动 Forge 世界、实体物理或详细 PathFinder；不能据跨边界宏观成功宣称实体最终到达。

## 独立核对与原件

Node 独立读取器从文件头、DEFLATE、摘要、组件标签、元数据和 CSR 重新解析 27 份记录，核对 5 个 JVM 键、进程退出、输入及源码哈希、Runtime 清理和单处 adapter 差异。共 92 项检查通过，见 `.tmp/derived-topology-persistence-research-20261013/verification.json`。

读取器初次误将 readFailures 预期为 3，实际为 4；目录冲突反例同时产生读取及写入失败。修正仅作用于读取器预期，原型与原始结果不变，说明保存在 `reader-initial-count-correction.md`。

codec 初版及 Runtime 初版也保留，最终证据分别绑定 `codec-v2`、`runtime-v2`，不混用初版计数。全部 8 个最终 JVM 正常退出 0；没有挂起服务器进程。4 个直接编译 Base 源文件和 5 个相关生产文件的哈希核对无变化，输入源未变。动态预算没有参与实验。

## 生产方案的剩余门槛

1. 独立派生缓存应以当前不可变事实和完整输入身份查找。可使用完整 key 摘要命名，避免较旧异步写覆盖新输入的记录；prototype 当前单个 section/geometry 槽会造成安全未命中，但不适合作为并发写的最终设计。
2. 事实存储失败和派生缓存失败必须区别：只有派生失败时正常构建，不能触发基础事实的 PERSISTENCE_UNAVAILABLE、无路、地面整条回退或恢复额度变化。
3. 必须加入独立有界异步 I/O。试验初值为单记录原始/压缩载荷各最多 8MiB、在途请求最多 8、暂存解码最多 16MiB、派生文件每世界最多 128MiB；这些尚未实现。decode 需先预留字节份额，活动图与共享事实继续按原 runtime 计费，不能把 Base 空闲 128MiB 当成活动图硬上限。
4. 读取先释放构建 permit，完成后通过控制事件重校验 demand/generation/stamps。不能在 runtimeLock 下读盘、解压、散列或阻塞；不能让并行解码共享 BuildScratch、边数组或发布上下文。
5. 每个读订阅、输入 pin、解码字节 reservation 和写快照引用必须有明确释放者。取消/替换只移除本请求份额，所有订阅消失后取消或丢弃结果；关闭后禁止迟到发布。
6. 存储采用同目录唯一临时文件及原子提交；写失败保持现有有效条目或正常未命中。读长度和摘要必须有界；原子 move 不支持时本轮建议跳过写入，不通过非原子覆盖提高命中。持久文件的清理只在专用 cache root 内由存储所有者执行。
7. 世界 namespace、显式算法版本与精确 GeometryKey 校验需落实。optional halo 的 PENDING/RECOVERY_FAILED/UNLOADED 不能一律冒充同一种“缺失”，初版建议只缓存允许状态的完整快照，其他状态正常构建并不缓存。
8. 补完整服务器阶段计时及真实冷启动对照。相同固定预算下，至少 5 次独立重复、交换顺序；只基础事实落盘对比事实和派生图落盘，并增加无缓存、坏缓存、中心/halo 修改、相同版本重新加载、两个几何 key、取消/关闭和持续负载。首请求与 128/256/512 并发分别报告。
9. 冷图测试必须同报 JVM/服务器准备、事实恢复、Base 排队/构建/缓存等待、发布和首条真实 Path 的 P50/P95，以及实体到达/成功率、MSPT 尖峰、I/O、分配及资源峰值。缓存准备成本与写入成本也保留；不能把现有热运行排队全归因于冷图。
10. 命中与未命中最终都要交给真实原版 PathFinder。完整路线、原生 partial/EXHAUSTED 和输入失效保护保持；动态预算在另一项实验中独立比较。

## 复现

在工程根目录运行，使用全新的输出名称，原件不覆盖：

```powershell
& ./.tmp/derived-topology-persistence-research-20261013/run.ps1 -Run codec-new
& ./.tmp/derived-topology-persistence-research-20261013/run-runtime.ps1 -Run runtime-new
```

Runtime 脚本当前绑定最终 codec-v2 类；Node 读取器绑定本轮正式两目录，复现时应显式调整输入目录并保留原验证文件。脚本使用本机 Java 和工程现有依赖，不能作为跨机器开箱复现承诺。

## DeepSeek 审核

已通过 WSL `/usr/bin/dsh --profile headless` 完成独立审核；实际配置为 provider=`deepseek`、model=`deepseek-v4.1-flash`，没有修改设置或输出密钥。仅提交现行契约、本轮源码及证据，没有附旧方案、旧审核或此前模型答复。

首轮 `prototype-review` 达到 8 分钟时限，`timedOut=true`，约 481.577 秒，没有有效最终结论，不计为审核通过；原始提示、推理、终态完整保留。第二轮 `prototype-final` 减少重复附件并扩大时限，正常退出 0、无超时，实际 111.405 秒。

最终结论为 `ACCEPT_WITH_GATES`，明确“本轮原型必须修改项：无”。编码往返与输入身份、隔离 Runtime 发布/当前性/清理，以及报告计数与性能限制在本轮范围通过。审查分别区分了原型正确性、证据强度和后续生产门槛，没有将其升级为详细 PathFinder、真实到达或服务器冷启动性能通过。

原文：[最终审核答复](../../.tmp/derived-topology-persistence-research-20261013/deepseek-answer-prototype-final.txt)；[退出与提示词身份](../../.tmp/derived-topology-persistence-research-20261013/deepseek-exit-prototype-final.json)；[送审附件身份](../../.tmp/derived-topology-persistence-research-20261013/deepseek-identities-prototype-final.json)。

最终 prompt SHA-256 为 `0384025A74636401CECCB4C391FB423B8F4624CA3F3EA8CA0D6A552DD2D30255`；唯一契约 SHA-256 为 `09A52D68F404AC25EA24E278AD2006E863C32DF7CD631C680121AD82E08FE49A`。审核结束后、写入本节之前，16 份送审附件哈希全部一致。原型、原始运行和代码身份保持；本节及恢复记录在审核后登记结果。

审核指出独立读取器仍使用同次运行的本地输入与摘要，属于不同实现的交叉核对，不是外部不可篡改证明；本轮也没有这项承诺。此证据限制保留。

后续设计额外补充同键读取合并/写入去重、每世界及全局磁盘额度、cache root 所有权、世界复制/回滚/删除后的 namespace 规则，以及断电/崩溃反例。审核原文建议文件和目录 fsync；具体平台支持与持久性承诺应在下一轮明确：派生图是可丢弃缓存，崩溃后可以未命中，必须保证坏条目不发布为有效图，不能把它升格为基础事实的可靠存储责任。

本轮结论允许继续开展有界异步 I/O 与真实服务器冷启动原型，不表示当前持久化已可接入生产。动态预算仍由另一对话负责。
