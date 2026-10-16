# Boundary S+H 生产源码接入记录

日期：2026-10-16。已将审计候选接入 `1.20.1` 的生产源码并生成发行包。当前版本的接线正确性检查通过；五对真实服务器 A/B 的总体性能判定为 **INCONCLUSIVE**，预注册性能发布门尚未通过。本次提交保留接入，不认定为已完成性能发布验收。

## 提交与范围

- 接入前先将原有生产区改动提交为 `fafdf8b1aa856ff083b2554ec11614a52ffad2da`，作为本轮冻结基线。
- 生产算法只修改 `BoundaryGeometry.java`：障碍矩形、cuts、visited 和 queue 使用实例 primitive scratch；paired `at()` 的 miss 解析提取为 `resolveSection()`。
- 源文件 SHA-256 为 `621937c48165557a0b94d94406c7f3b7144af801a8449a14de5a37b0687814b8`，与 DeepSeek 最终审计的 H 完全一致。未接入 C2，也未改预算、Portal 或 Base flags 缓存。
- helper 仍归构建或单次 Evaluation 所有，同一实例串行使用；scratch 不进入发布结果。

## 当前代码正确性

`testClasses`、Reverse 集成契约 3 项、Core 契约 9 项及 `jarJar` / reobf 均通过。

针对当前 Base、LocalKernel、SharedRows 和真实 BoundaryLinks，两版分别运行：

- 240 组完整 BoundaryLinks 对照，比较描述符、能力与 masks；每版 3516 条 LocalKernel 行。
- SharedRows 创建、查询、合并、旧结果保持和共享结果上的并发独立请求。
- 每版 4096 次直接 section 查找、21504 次碰撞查询及四组身份冲突，覆盖冷 map miss、null 缓存、负坐标与缺失 halo。
- H 额外执行 6716 次 scratch 不变式查询，共 45815 个断言；两版语义输出完全相同。

有效结果位于 `.tmp/boundary-production-integration-20261016/checks-v2`。最初的 mixed facts 夹具含被移除门内的 fluid，SharedRows 预期拒绝；后续检查显式核验该拒绝，两版一致。保留初次失败记录，未为通过检查修改生产算法。

清理增量构建遗留的旧 `BoundaryGeometry$Rect.class` 后冻结 `candidate-clean-v1`。相关源码、测试源码、资源及其余类字节与已检查候选完全一致，复用身份记录为 `current-checks-reuse-identity.json`。发行包内容对照仅有 Boundary 类变化、旧 Rect 类移除和 Manifest 构建时间变化。

## 工具与真实场景

所有新探针、脚本、输入冻结和原始证据均在 `.tmp/boundary-production-integration-20261016`，不进入生产包。

诊断使用 Java 17 JFR、自定义 worker 阶段事件和 Boundary 调用计数。有效诊断为 `diagnostic-candidate-v3`：冷阶段 24 次 local / 285 次 paired 调用，burst 阶段 16 次 local / 1149 次 paired 调用；448 个 JFR worker 事件的任务数与分配字节和 ThreadMXBean 计数完全吻合，JFR DataLoss 为 0。

正式测量使用相同 `probe-release-v3`，关闭 JFR 与 Boundary 反射计数，保留两版共同的 worker 时间、分配计数和 full-tick 探针。场景为固定种子平地、关闭木门与绕行墙，成年 Husk 不具备开门能力；一条冷请求后提交 128 条 burst 请求。

五对顺序沿用审计冻结方案，每个 arm 启动新 JVM 和独立世界，串行运行。十台服务器均验证：

- 从 ServerStarted 到正式起点实际等待 30.302–30.476 秒，从场景就绪等待 30.006–30.018 秒；目标体型图等待期间保持冷态。
- 共 1290 条请求取得首个真实 Path，没有整段回退、服务失败或异常终止；关闭后服务记录与调度队列清空。
- 每台 219 个完整正式 tick，共 2190 个；只保留完全落在 burst 阶段内的 tick，排除启动期与边界重叠 tick。全部样本保留，最大正式 tick 39.768ms。
- 启动记录中均无其他 Java 进程，两个版本使用同一配置和同一探针 hash。

这里的延迟定义为提交到首个非空 Path 被观测到；固定 220 tick 窗口结束时所有 burst 请求仍为 REACHABLE、实体仍在移动，尚未记录物理到达。该场景未验证走完全程的延迟。

## 预注册性能判定

统计单位为独立服务器配对，共五对。比值取五对 H/B 的几何均值；以配对为单位 bootstrap 10000 次，seed 20261016，分位数使用线性插值。下表列出的下界和上界分别为单侧 95% 界限，不作为双侧 95% 区间。

| 指标 | H/B 估计 | 单侧 95% 下界 | 单侧 95% 上界 | 判定 |
| --- | ---: | ---: | ---: | --- |
| 冷首次 Path 延迟 | 1.0585 | 0.9906 | 1.1754 | INCONCLUSIVE |
| burst 请求延迟 p50 | 0.9952 | 0.9899 | 0.9999 | PASS |
| burst 请求延迟 p95 | 1.0032 | 0.9242 | 1.0901 | INCONCLUSIVE |
| worker 执行时间 / 请求 | 1.0417 | 0.9758 | 1.1142 | INCONCLUSIVE |
| worker 分配字节 / 请求 | 0.9796 | 0.9738 | 0.9857 | PASS |
| 正式 tick p95 | 1.0104 | 0.9299 | 1.1010 | INCONCLUSIVE |

上述比值上限均为 1.05。超过 50ms 的正式 tick 比例，两版都为 0；H-B 估计与上下界均为 0，低于预设 0.01，判定 PASS。

worker 分配量在此场景下降约 2.04%。冷请求、burst p95、worker 时间和 tick p95 的上界未通过容忍线，其下界也未达到注册的 FAIL 条件。因此总体为 INCONCLUSIVE；尚不能确认这些指标无超限回退，也不能据此声称整服提速。独立启动波动与全部慢样本均保留，没有追加挑选有利结果的重跑。

## 发行身份与证据

- 基线发行包 SHA-256：`778642db2694a4326e74e35b4ee853547860492a2aaefd41d8512f298597b6f4`。
- 本轮候选发行包 SHA-256：`89729984515814b19066eac3929e4665e5dc5a6e0b4b3b809edbef8b3f0fb5aa`。
- 正式共同探针 SHA-256：`43d2df21e6a4cd3fd66aa9e820bd365e781b7a54b3ab325261c741fbece5ecb3`。
- 候选包位于 `build/libs/accelerated_navigation-0.1.0-all.jar`，与正式测量的冻结候选一致。
- 主要证据：`formal-protocol.md`、`formal-input-seal.json`、`formal-summary.json`、`formal-matrix.log`、`release-payload-audit.json`、`evidence-seal.json`；每个 `formal-p*-*` 目录保留启动身份、原始日志、请求结果、full-ticks 和实际加载的 Mixin 导出类。

诊断失败尝试也保留：v1 在加载阶段发现探针与 Mixin 包冲突；v2 暴露 Minecraft 重写 properties 及清理请求改变最终快照的记录问题。均在 `.tmp` 修复后使用新冻结探针重新诊断，未混入正式成绩。旧审计和原型封存材料未修改。本轮没有推送远端或发布发行版本。
