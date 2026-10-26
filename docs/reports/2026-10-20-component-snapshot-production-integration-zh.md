# S9L 生产源码接入与集成验收

日期：2026-10-20。用户授权先送审 DeepSeek，审查通过后先推送当前生产区到
GitHub，再开始接入。本轮接入的是完整的生产 P→S9L 差分，包含 S9 快照去重
和 S9L 小容量索引，不是只替换 S9 的索引容器。

生产源码接入、实际 Gradle 构建和受影响功能回归已通过。
当前生产检查点已推送，新接入保持工作区修改供复核；
最后一份 DeepSeek 结果复核返回 ACCEPT_SCOPED，本轮必须修改项无。
本轮不据此取得稳定性能、默认发行或实服部署资格。

证据根：`.tmp/component-snapshot-production-integration-20261020-v1/`。
原 S9、S9L 候选与旧性能/审查封存原件均保留。

## 审查与执行顺序

通过既有 `tools/review/deepseek-review.mjs`，经 WSL 无头接口发送独立自包含材料。
实际 provider/model 为 `deepseek/deepseek-v4.1-flash`，没有修改配置。

| 包 | UTF-8 字节 | 时长秒 | 结论 | 必须修改项 |
| --- | ---: | ---: | --- | --- |
| 完整生产差分代码 | 8198 | 195.339 | ACCEPT_WITH_GATES | 无 |
| 数据、补证及接入决策 | 8498 | 97.802 | READY_FOR_INTEGRATION_VALIDATION | 无 |
| 实际接入结果复核 | 6104 | 173.132 | ACCEPT_SCOPED | 无 |

前两份回执均正常退出、未超时，明确同意先建立远端回滚点，再执行最小源码接入
和实际构建/功能验收，不批准默认发行或部署。没有将退出码 0 单独当作批准。
组包时先修正换行边界和长度校验，实际发出的材料均未超过 8500 字节。
结果复核同样正常退出、未超时，认可上述有限结清，明确保留 stale 可达性论证与
直接异常路径未动态实测的区别。三次配置前后 SHA-256 均为
`071db56517e8f97ccde780095f71e5b264a1c7bc41694499cf81af7c2aca1858`。

代码审查要求核对 SectionPos 的相等/哈希、成员快照不可变性、消费者身份和
终态释放路径。实际 mapped Minecraft JAR 的字节码显示，SectionPos 继承 Vec3i，
不覆盖 equals/hashCode；Vec3i 按 x/y/z 比较，哈希为 `(y + z * 31) * 31 + x`。
补充 48 条正确性断言确认不同实例但相等的 SectionPos，在小表与晋升后的
HashMap 均命中同一快照且表大小保持。此项是关闭审查前提所需的补证，
未新增性能场景、改变原测试接口或使历史性能数据失效。

实际 ComponentSnapshot 只持 final section 和私有 final cells，构造不读取
endpoint id/anchor；生产消费者仅查询成员或清理自身引用，不依赖每个 endpoint
拥有不同的快照对象。小表的四字段比较与不可变 record key 语义一致。

## Git 检查点

分支 `1.20.1`，接入前将当前 C 构图、idle 补发调度、已有测试、AGENTS 规则
和相关历史报告共 20 文件提交：

- 提交：`f5cd1a71615fbefc609bf89d8394b5da0de245d1`。
- 标题：`Save current production before component snapshot integration`。
- tree：`3610e5d8cfd3a97211dde7681752f03371fb6541`。
- 正常推送 `origin HEAD:refs/heads/1.20.1`，未 force。
- `ls-remote` 核对远端分支精确等于该提交后，才开始修改生产源码。

远端由 `4b82ed6` 前进至 `f5cd1a7`，此前本地 `fe9b080` 也随祖先历史推送。
无关 `forge-installer.jar.log` 未提交、未修改。新接入及本轮记录没有再次提交推送。

## 实际差分与身份

生产仅修改 `TopologyWorkerRuntime.java`，净 74 行新增、1 行删除：

- 新增查询私有的四字段 key 和 ComponentSnapshotIndex。
- MacroQuery 惰性持有一根索引引用；普通非 refining 路线保持逐 endpoint 构造。
- 8 个唯一身份内保存已有 endpoint/snapshot 引用，第 9 项成功后一次迁移 HashMap，
  复用原快照并释放小数组。
- 原完整 endpoint 映射、16384 上限、拓扑校验、保守计费及两个终态清理点保持。

62 份生产输入逐项核验：Runtime 归一化文本与冻结 S9L 相同，
其余 61 份原始字节不变；并与历史性能使用的其余生产源码组合对齐。
未夹带其它隔离候选，未修改项目 build.gradle、既有测试或 fixture。

实际 Gradle 编译产生 259 个生产 class。复用原 ASM 规范化方法，对
62 个 Runtime 相关 class、880 个方法核对字段/方法结构、指令、分支目标和
异常表，均与冻结 S9L 相同，忽略调试元数据。此为静态身份核验，没有新增性能样本。

## 构建与功能

实际当前生产源码执行 `check build jarJar`，使用项目原 Gradle 任务与已有本机
socket/IPv4 参数，外部 init 脚本只重定向 archive 输出并导出 classpath。
构建约 33 秒成功，18 个任务执行、2 个 up-to-date。

| 检查 | 实际结果 |
| --- | --- |
| Gradle 编译、check/build、jar/reobfJar、jarJar/reobfJarJar | 通过 |
| Core | 9 项及 3 项反向检查通过 |
| Worker | 7 组通过，含门相关 28 项控制 |
| Shared | 99,951 检查、41 fixture 路线、643 组件证明通过 |
| 原 macro-commit / boundary-recovery | 4 / 9 项通过 |
| 原 SnapshotChecks | 73,829 条断言通过 |
| 相等但不同实例 SectionPos | 48 条补证通过 |
| 原真实路线 | 普通 4 endpoint/4 snapshot；分层 9 endpoint/5 snapshot |

真实路线的完整 JSON、成员 hash、cost、witness 与保留 S9L 逐项一致。
五次受影响控制按实际 259 类清单核对加载来源，分别加载
123/129/130/103/23 个生产类，全部来自 `build/classes/java/main/`；
原工具目录仅提供 harness，没有以旧生产 overlay 回退。
本轮没有构建或功能测试失败，也没有新游戏或性能运行。

## stale 生命周期边界

普通成功、取消和失败由 finishOnOwner/failFromWorker 清映射并置空索引。
不能将此概括成“所有 stale 都经过两个清理点”：

- worker 标记 stale 后返回 FAILED，后续会走 finishOnOwner。
- failSearch 遇 stale 异常直接 restartStaleSearch 后 complete(null,null)，
  不显式清原 completedComponents 或新增索引。

后一条路径中 complete 移除 macroFlights，清 waiters 并令 waiter.flight=null，
重试建立新 flight/query，不复用旧索引。执行器任务先出队，finally 移除 currentTask；
无 continuation 时转 DONE。事件批次的 deferredSearchResumes 在结束/失败/关闭时清空。
查询内部回调可成环，但 static helper 没有新增全局根，不持图或外部回调，
原 endpoint 映射本来就持有这些端点和快照。

前置决策包已明确接受旧 query 整体丢弃的处理，不要求为了显式置空修改候选。
这是源码可达性论证，不保证 complete 返回瞬间已 GC，也没有 stale 异常路径专项
动态实测。原边界恢复日志中的 stale=0 不用于冒充已触发该分支。

## 性能复用及限制

源码组合、接口、工具、场景、参数和计量均对齐，继续复用
[S9L 历史报告](2026-10-20-component-snapshot-small-index-zh.md)与原始数据；
本轮性能新增运行数为 0，未重跑有效 B/C/S9/S9L。

S9L 相对 C 的完整 W 均值 air/distinct/mixed 为 −35.76%/−29.22%/−34.76%；
相对 S9 为 −20.06%/−29.32%/−32.81%。W 是包含异步间隔的墙钟，
历史非同轮配对，不将全部差额归因于索引，不认定稳定加速。
B 缺该完整 W，不能将旧 B 查询时钟当作同口径基线。

每个参照的 60 项窗内统计为 59 降、1 升。不利值完整保留：
distinct 清理后至 complete 返回区段最大值 C 205.8、S9 84.8、S9L 307.1 微秒；
相对 C +101.3 微秒/+49.22%，相对 S9 +222.3 微秒/+262.15%。
该段含缓存和重物化，属于窗内，原因未定位；其均值以及完整 W 的五项统计仍下降。
窗外按用户口径视为波动，间接影响不处理，不作因果排除证明。

局部 actual/unique 净分配相对 S9 少约 472/344 B/op；unique 强制 refining，
不是普通路线成本，且仍较 C 多约 104 B/op。时间点估计小于同 fork 误差。
超过 8 个唯一组件仅有功能证据，无大表性能资格；本轮不自行扩测试矩阵。

## 产物与封存

本轮完整混淆包位于证据根 `artifacts/accelerated_navigation-0.1.0-all.jar`，
SHA-256：`0dd8c5f6369e24866508eb5eb7af68947bf1b3c242cc1f2b9318a7276458c4e6`。
普通包 SHA-256：
`5e036218b22eb625d09dc9ebdbf45307b41d9d88e397e6660f10f8aab18bc2f0`。
两份默认 `build/libs` JAR 保持原哈希。

`freeze.json`、`checkpoint.json`、`source-identity.json`、`checks-audit.json`、
`bytecode/comparison.tsv` 和三份审查的 prompt/transmitted/answer/exit 记录分别保存
接入前身份、远端检查点、实际源码、类来源、功能结果及审查原件。
`final-seal.json` 核对 1021 份旧保护输入和 135 份旧证据文件，
其中获准变更的生产 Runtime 与状态文档通过接入前备份核对历史原值；
旧封存原件不覆盖。实际 259 份生产 class、资源及 62 份源码另复制到
`frozen-production/`，保留后续历史复用所需的不可变产物。

首次最终封存被 257 份缺失历史 class 拦截：本轮实际生产编译后，
上一轮 Gradle 隔离 main 输出目录已被清理。没有重编译或重测历史版本，
而是从上一轮已有的 `artifacts/hierarchy.jar` 读取对应条目，先将全部条目
与原 freeze SHA-256 核对，再仅恢复缺失文件；257 份均恢复原始字节，
原归档和冻结记录保持。过程见 `historical-classes-recovery.json`。
这次证据恢复没有改变候选、当前生产或任何性能样本，恢复后重新执行完整封存。

本轮源码接入与既定构建/功能验收完成；新接入未再次提交推送，
默认 JAR 未替换、未部署，没有为获得新配对重跑有效历史性能数据。
