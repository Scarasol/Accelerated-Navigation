# 动态预算生产接入准备记录

日期：2026-10-14。范围：先推送当前代码，完成无功能开关的生产接入准备。生产动态预算尚未实现，未部署。

## 已完成

接入前基线已提交并推送到 `origin/1.20.1`：

```text
fead3a154f7e9a1d8e5470fb331a013661a35007
Save native navigation fixes and dynamic budget research baseline
```

远端分支核对一致。该提交保存现行详细寻路修复、真实命令实体、速度 IV 第一阶段修复、性能/动态预算原型和研究报告，共 64 个文件。派生拓扑持久化的四份独立研究文件和安装日志没有纳入本次提交，没有删除或回退。

离线 `compileJava compileTestJava` 和 `jarJar/reobfJarJar` 均 BUILD SUCCESSFUL。Windows Java 的 Unix socket 回环问题通过本进程 `JAVA_TOOL_OPTIONS` 指定不存在的 socket 临时目录处理，没有修改项目构建配置。

已冻结该基线的发行 jar：

- 文件：`.tmp/navigation-dynamic-budget-production-20261014/baseline-fead3a1/accelerated_navigation-0.1.0-all.jar`。
- SHA-256：`3AE2573E5F1E9407F36EE8BED143B4D721799EDDF066707144B80F80C0C6451A`。
- Git tree：`ec3ac8143c601df9aebd41382b5be2759e334b36`。
- 构建参数：Minecraft 1.20.1、Forge 47.3.0、Java 17.0.18。
- 已检查不含预算原型或 production 探针类，保留 jarJar 依赖元数据。
- [包与源码身份](data/2026-10-14-dynamic-budget-preparation/baseline-identity.json)已保存；包本体只在本地存档，没有发布到服务器。

## 无开关接入方案

[具体方案](../designs/2026-10-14-dynamic-budget-production-integration-plan-zh.md)已经落到修改位置、控制公式、等待谓词、测试适配、发布门槛和回退动作。

第一组修改是固定空间的预算控制器、完整 tick 虚调用封套、动态补额/负债/实时截止及有界汇总。第二组修改把最多两 tick 的已验证等待移入现有 settle；正式候选同包包含两组，保留真实到达与 partial/native-stop/输入当前性。

不增加功能开关或运行时额度模式。0..12ms 和两 tick 仍是候选参数；现行契约继续保留约 4ms，直到新代码的发布验收通过才明确修订。发布后发现问题，停服换回上述固定旧包；本轮没有持久化格式变化，也不删除世界或缓存。

## DeepSeek 定案

两轮均使用现有 headless 配置，provider 为 deepseek、model 为 deepseek-v4.1-flash，没有改设置或输出密钥。唯一规范附件是 2026-10-13 现行契约，SHA-256 为 `09A52D68F404AC25EA24E278AD2006E863C32DF7CD631C680121AD82E08FE49A`。没有提交废弃设计或前轮审核。

首轮 109.363 秒正常退出，判定 **NEEDS_REVISION**。要求把以下未决细节固定：显式封套与样本状态、跨封套 account 的守恒方式、P95/尾部预热、settle 执行期检查、12ms 下的预算和边界探针，以及有界汇总。

修订稿 218.278 秒正常退出，判定 **READY_TO_IMPLEMENT**，必须修改项“无”。确认边界快照与补额守恒、严格等待谓词、测试适配、局部同口径对照和整版回退，允许开始第一版实现。两轮退出码均 0、未超时。

复审还提醒实施时明确零历史 EWMA 初始化，以及验证真实暂停。已核对固定哈希源码 jar：IntegratedServer 第 82–89 行真正暂停分支不调用 super.tickServer；正常分支及其子类尾部在虚调用封套内。`haveTime=false` 是原生时间条件，不等同暂停，不擅自把它变成另一种暂停判定。实际单机暂停、恢复和落后测试仍未完成。

证据：

- [最终审核原文](data/2026-10-14-dynamic-budget-preparation/deepseek-answer-dynamic-budget-production-plan-final-20261014.txt)。
- [最终退出状态](data/2026-10-14-dynamic-budget-preparation/deepseek-exit-dynamic-budget-production-plan-final-20261014.json)与[附件身份](data/2026-10-14-dynamic-budget-preparation/deepseek-identities-dynamic-budget-production-plan-final-20261014.json)。
- [首轮审核原文](data/2026-10-14-dynamic-budget-preparation/deepseek-answer-dynamic-budget-production-plan-20261014.txt)及对应退出/身份文件，完整保留修订过程。
- [最终送审方案快照](data/2026-10-14-dynamic-budget-preparation/reviewed-plan.md)：SHA-256 `D9DC7FD0128485FFD5ED1BD60D7FD0C436D8810B687141925AB741FD973767D9`。
- [固定版本源码节选](data/2026-10-14-dynamic-budget-preparation/upstream-envelope-excerpts.json)：原始 sources.jar SHA-256 `5905C044CBF9D4CD0208F8878BFD7B586782C44366C277D6FBD70E4B075C0EB2`。

## 下一步与证据边界

接入准备完成。下一步按方案开始两组实现，更新受影响的测试，冻结新的生产候选包，完成确定性控制、发行态 Mixin、生命周期、速度 II/IV 和真实路线验收，再做局部同口径的至少五次乱序性能重复与最终 DeepSeek 验收。

历史固定 4ms 正式性能数据继续复用为背景。新候选百分比只使用受影响范围内的新仪表配对对照，不重复整个旧矩阵，也不把旧仪表数字当作新代码同口径测试结果。

本轮 `src/main`、`src/test` 和 benchmark 源码相对 `fead3a1` 无变化。构建通过仅说明基线可构建；READY_TO_IMPLEMENT 仅批准方案实施。没有把准备、原型结果、历史路线成功或方案审查写成新生产代码验收通过。
