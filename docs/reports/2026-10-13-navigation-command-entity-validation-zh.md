# /navigation 实体行走验收

日期：2026-10-13。

本次补充落实用户要求：详细寻路在 A 点生成接近默认体型的地面生物，赋予永久发光效果，真实行走至 B 点后移除，并防止远离玩家导致自然消失。随后按用户补充加入永久速度 II，以缩短人工验收行走时间。

架构唯一规范仍为 [现行详细寻路契约](../designs/2026-10-13-native-detail-navigation-contract-zh.md)。旧设计与旧审查不作为本次依据。本次只调整命令入口和临时实体生命周期，没有调整宏观算法、原版详细搜索、预算、搜索参数、恢复额度或成功判据。

## 实现和原因

| 修改位置 | 行为与原因 |
| --- | --- |
| `NavigationCommand` 的 `detailed=true` 分支 | 从虚拟实体逐段节点预览改为 `addFreshEntity` 后调用实际生物的原版 `Navigation.moveTo`。命令保存真实的 `NavigationRequestPath`，依据执行终态报告结果，不以查询可达代替到达。 |
| 新增 `NavigationTestMob` | 使用成年尸壳，体型约 0.6×1.95 格，原版 `GroundPathNavigation`、NodeEvaluator、移动控制器和物理 tick 保留。不设置 NoAI，不传送生物完成路段。 |
| 生物效果与存活 | 给予 `MobEffects.GLOWING`、`MobEffectInstance.INFINITE_DURATION`（1.20.1 中为 -1），不是有限时长或只有发光标签；另给予同样无限时长的 `MOVEMENT_SPEED`、amplifier=1，即速度 II，使用原版效果属性提升移动速度，不调整搜索参数。`setPersistenceRequired` 防距离消失；无敌保护避免验收途中被伤害打断。尸壳不怕阳光，关闭水下转化，和平难度下也不消失。 |
| 生物 Goal | 保留浮水；只有 `canOpenDoors=true` 时安装原版 `OpenDoorGoal`。不注册追击、攻击、闲逛和目标选择，避免其他 AI 替换命令任务。 |
| 开门能力 | 保留默认 `canOpenDoors=false` 与原可选参数。实际搜索读取 NodeEvaluator 的开门标志。已从本地 1.20.1 原版字节码确认：GroundPathNavigation 的 `canOpenDoors()` 返回 `canPassDoors`，因此不能据此决定是否安装开门 Goal。 |
| 生命周期 | 到达、失败、取消、替换、玩家离线或换维度、实体被移除、区块停止实体 tick、停服时，都停止导航并移除生物。`/navigation cancel` 取消当前任务或粒子展示。 |
| 世界保存和传送门 | 临时生物 `shouldBeSaved=false`，防止卸载或重启后留下普通尸壳；`canChangeDimensions=false`，防止传送门在其他维度重建不受会话管理的实体。 |

成功沿用实际导航服务的 `SUCCEEDED`：真实最后段可达，并且实体实际最终目标 accuracy 成立。当前命令沿用原版移动入口的 accuracy=1；终点仍经过原版地面归一化。原生部分路径耗尽仍报告失败，不改写成功。

## 加载边界

防距离自然消失不等于强制加载或持续实体 tick。详细模式要求起终点区块已加载、起点可运行实体；运行中当前实体所在区块停止 tick 会明确失败并清理。不新增区块票据，不修改全服模拟距离。游戏验收建议跟随生物，维持沿途区块运行。

`detailed=false` 继续执行原有宏观走廊粒子展示，`/navigation graph <distance>` 继续导出基础图。

## 当前速度 II 版本

生产实体已加入永久速度 II（`MOVEMENT_SPEED`，无限时长，amplifier=1）。当前正式包 SHA-256：`AEB9E255C89DCF0150F9863C343503EA45618B4D402A05E876E27C41F794D750`；独立测试包 SHA-256：`E946B0AE0A061E6462CD9E62B9A0CC7779CCF837AFF106599CADFF68531A0928`。发行场景均使用这两份新包，下节的未加速包结论不复用于当前包。

| 当前检查 | 结果 |
| --- | --- |
| `server-speed` 的真实命令/实体/物理 | 87 项业务断言通过，409 ticks、约 54.18 秒；进程退出 0，无超时。 |
| 无限 Glowing、发光轮廓和 Speed II | duration=-1、speed amplifier=1；201 ticks 的整个平地任务均保留两种效果，所有生存 tick 均开启实际发光轮廓标志。 |
| 移动属性 | 原版基础值约 0.23、带速度 II 后约 0.322；不调整 Navigation 速度倍率和寻路参数。 |
| 真实平地到达 | `(3,81,3)`→目标 `(42,81,3)`；脚部实际到达 `(41,81,3)`，accuracy=1；174 ticks 有自然位移，最大单 tick 位移约 0.2238 格。任务总 ticks 从未加速试验的 343 减为 201；仅描述该夹具，不作为长期性能结论。 |
| 开门和生命周期 | 实际木门穿越、关闭开门参数失败不伪成功、距离消失对照、和平、取消/替换/离线/维度/实体移除/区块/重载/停服全部通过。 |
| 当前包原导航生命周期回归 | `server-command-speed-regression`：130 项业务断言通过，1316 ticks、约 95.10 秒，退出 0、无超时；RouteStart=4，没有重新验证完整城市/楼梯路线。原驱动的 POI 观察次数随异步时序变化，因此两次回归断言计数不同。 |
| 发行和架构边界 | 295 项打包/Mixin 检查通过，60 handler、17 组；当前字节码无废弃资格扫描类/方法。 |
| 独立证据核对 | `verification-speed-accepted.json` 共 51 项全部通过；源码、当前两包与本轮原始运行证据身份绑定。 |

当前速度 II 版本的 DeepSeek 独立验收 `speed-acceptance` 已完成：provider=`deepseek`、model=`deepseek-v4.1-flash`，245.358 秒，退出 0、无超时。只提供现行契约、当前源码和当前速度版本运行证据，没有提交旧设计或此前 DeepSeek 答复，未修改模型配置。

结论：满足本轮新增功能，没有现行架构契约的生产阻断项或生命周期阻断缺陷，可以进入该功能范围的人工游戏验收。验收绑定当前 AEB9E255 生产包及 E946B0AE 测试包，87/51/130/295 各项计数与原始证据一致。原始全文：[速度 II 版本独立验收](../../.tmp/navigation-command-entity-20261013/deepseek-answer-speed-acceptance.txt)。

审查有一项非阻断的测试记录建议：`speed-two-retained-for-entire-walk` 的最终展示计数复用了 `glowingTicks`，可改为单独的速度检查计数。实际每个 tick 都检查 Speed II 的存在、无限时长和 amplifier=1，任何失败立即使场景失败；当前通过结果能证明效果全程保留，该建议不影响生产行为或通过有效性。此轮保留已验收的源码和测试包身份，不把展示计数误写成另一项独立验证。

审查完成后再次核对 59 项源码/构建/契约身份、13 份原始运行证据和两包 SHA-256，均无漂移。文档中的审查结论在审查完成后补入，不参与已运行包的身份。

## 未加速版本记录（历史）

生产包与独立测试包构建通过；核心契约检查 9 项通过，其中包含逆向 3 项，不合计成 12 项。独立门控与命令参数检查 28 项通过。

`server-isolated` 在标准 Forge 1.20.1-47.3.0 发行服务器上直接执行已注册命令，退出 0、无超时，636 ticks、约 61.34 秒，73 项业务断言全部通过。使用 Forge FakePlayer 作为有权限的玩家命令来源，真实世界、实体 tick、原版 Navigation 和 Mixin 都运行。测试副本隔离了 9 个被冻结地图中的其他生物，避免它们的自主导航污染本测试的全局资源断言。

- 平地路线从 `(3,81,3)` 到目标 `(42,81,3)`，实际脚部到达 `(41,81,3)`，符合原版 accuracy=1；336 个 tick 观察到自然移动，最大单 tick 位移约 0.1142 格，没有逐段传送。整个 343 ticks 的任务均保留无限时长 Glowing。
- 最近玩家初始距生物约 247 格，普通尸壳在相同条件下通过原版 `checkDespawn` 消失，命令生物存活；和平难度下继续自然完成路线。
- 一格宽、两格高的封闭木门通道中，启用开门时实际打开门并到达目标附近；关闭开门参数的任务失败并清理，没有错误报告成功。
- 新请求替换、显式取消、玩家离线、玩家换维度、外部实体移除、未加载起点拒绝、远处区块停止实体 tick、重新加载后无残留生物，以及有活动任务时真实停服均通过。
- 命令场景实际详细搜索 6 次，整条兼容执行 0 次、虚拟预览完成 0 次。关服后 navigation records/active 和调度待办均为 0，迟到服务访问被拒绝。

当前生产包另跑 `server-command-regression`，1294 ticks、约 89.50 秒，退出 0、无超时，118 项原 Navigation/Goal/Brain/POI、实际输入失效、原生 partial、延迟查询采纳及关闭控制通过。该回归明确使用 `RouteStart=4`，没有重跑四条完整城市/楼梯长路线，不把此前版本的长路线证据算作当前包的新测试。295 项发行打包和实际 Mixin 检查通过，覆盖 60 个 handler、17 组 Mixin；当前字节码检查未发现废弃资格扫描类/方法。

命令结果首先经过 40 项独立读取检查；加入当前回归和发行审计绑定后，单独的 `verification-isolated-accepted.json` 共 47 项全部通过。原始 40 项文件保留，不覆盖。

未加速历史包 SHA-256 `F14D9169445CD4C69F1C51F02DAC7894F0358A1AE17BE658F28F641D7CA7E194`。

未加速历史测试包 SHA-256 `73D3ABC8C105BBE893C746B8C33A352EF04B46C9FF7C5DCFFBA5285E0A7E3E09`。两个历史发行场景加载的包身份一致。

未加速版本的 DeepSeek 审查 `acceptance` 已完成：provider=`deepseek`、model=`deepseek-v4.1-flash`，168.352 秒，退出 0、无超时，未发现必须修复项，确认可以进入本功能范围的人工游戏验收。该结论只对应未加速历史包，不作为加入速度 II 后的新包验收。原始输入、输出和终态保留；加入速度后的审查只提交新的源码与新的运行证据，不提交此历史审查。

证据根：`.tmp/navigation-command-entity-20261013/`。测试驱动单独包装为验证 mod，未加入生产包。测试只运行在复制出的隔离世界，没有修改用户游戏存档。

前序试跑均保留：

- `server-initial`：验证代码错误地使用了原版 `GroundPathNavigation.canOpenDoors()` getter；修正验证读取 NodeEvaluator，且生产实体仅在启用时安装开门 Goal。
- `server-door-corrected`：测试报告尝试序列化完整 MobEffectInstance，触发 Java 17 Optional 反射限制；改为记录实际持续时间，未更改效果行为。
- `server-walking`：自然行走和永久效果通过；全局零记录检查被冻结地图中其他生物导航干扰，测试驱动随后隔离非验收生物。此轮不计为完整通过。

## 游戏使用

安装 `build/libs/accelerated_navigation-0.1.0-all.jar`。在原本可站立的脚部位置指定 A、B，例如地面方块 y=80 时使用 y=81：

```text
/navigation 3 81 3 42 81 3 true
/navigation 3 81 12 25 81 12 true true
/navigation cancel
```

第一条使用默认不打开关闭木门；第二条允许实际打开木门。铁门仍由原版规则决定。命令需要玩家来源和至少 2 级权限。
