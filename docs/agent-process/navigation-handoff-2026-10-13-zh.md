# 详细寻路自动接入交接文档

日期：2026-10-13  
工程：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`  
Minecraft/Forge：`1.20.1 / Forge 47.3.0`  
当前分支：`1.20.1`  
当前提交：`ea1072f Integrate automatic navigation lifecycle`

这份文档用于把当前状态交给下一位执行者。本文只记录现状、证据、架构边界和后续工作顺序；生成本文时没有继续修改 Java 实现、没有重新运行生产验证，也没有把当前提交宣称为最终接入完成。

## 一、当前结论

当前提交已经完成一版原版 `Navigation` 生命周期接入，包含请求身份、分段 `Path` 载体、原版入口捕获、控制来源、取消、重算、部分路径终态、服务器关闭清理和共享预算接线。构建和发行字节码审计通过，绝大多数自动接入契约通过。

当前提交仍不能作为最终生产接入版本。原因不是生命周期框架没有工作，而是实现中还保留了已经被用户最终架构明确舍弃的“详细寻路前置资格判断”：

```text
宏观寻路完成
-> 直接把当前宏观候选交给真实原版详细 PathFinder
-> NodeEvaluator / 碰撞 / malus / 实体能力判断能否执行
-> 返回成功、partial 或失败
-> Navigation 执行当前真实 Path
```

当前实现实际上仍有一条额外链路：

```text
宏观候选
-> GroundAbilityGuard / qualifiedWindow / ability proof
-> 先判断区段是否“有资格”
-> 资格失败时切换 nativeWhole 或拒绝局部执行
-> 才可能进入真实详细 PathFinder
```

这条资格过滤链违反当前架构边界。旧方案已经失效，不能把旧文档称为“低优先级规范”或与新方案并列参考；后续审查必须把旧方案视为已废弃内容，并直接禁止这类前置过滤。

## 二、版本和工作区状态

已确认：

- `origin/1.20.1` 与当前提交同步。
- 最新提交为 `ea1072f Integrate automatic navigation lifecycle`。
- 当前工作区没有本轮新修改。
- 工作区有一个未跟踪文件 `forge-installer.jar.log`，它不是本次接入的一部分，未纳入提交，也不应在交接时误删。
- 本交接文档是本轮新增的记录文件。

交接时应先确认 `git status --short`，不要用清理命令处理未跟踪日志，也不要回退用户或前一阶段留下的改动。

## 三、用户最终确认的架构契约

### 3.1 宏观和详细的职责边界

宏观寻路只负责给出候选走廊、组件连接和必要的宏观当前性信息。宏观搜索完成后，当前候选必须直接进入真实的原版详细 PathFinder。

详细 PathFinder 使用真实实体上下文和原版规则决定：

- 身体碰撞；
- 节点是否可走；
- 方块和流体的原版规则；
- 路径类型和 malus；
- 实体能力、姿态、体型、步高和落差；
- 原版门、栅栏、水、危险方块等能力语义；
- 最终目标容差和 `Path.canReach()` 结果。

接入层不能在详细 PathFinder 之前再根据方块名称、走廊组件、支撑、身体邻域或自定义“能力证明”过滤一次。接入层只应负责把宏观候选交给真实详细后端，并维护任务身份、预算、加载安全和结果生命周期。

### 3.2 分段、共享预算和分片的关系

本方案采用的是“分段执行”和全服务器共享软预算，用来降低大量实体并发寻路造成的主线程尖峰。共享预算的当前约定约为每 tick 4 ms：

- 正余额最多补到约 4 ms；
- 负债保留到后续 tick；
- 受控任务在 END 阶段派发；
- wake 只负责重新入队；
- 原版单次 `PathFinder` 调用本身仍是原子调用，不声称能在调用中途硬切到 4 ms；
- 预算统计覆盖准备、真实搜索、结果发布和同步回调的实际主线程区间。

“分段”表示宏观候选被逐步交付给真实详细 PathFinder 并由原版 Navigation 执行当前段；它不是先做一套自定义方块资格证明。是否需要更细的 A* 分片，属于以后独立验证的问题，不能用来恢复已删除的资格过滤链。

### 3.3 Path 和 Navigation 的外部语义

`NavigationRequestPath` 只保存当前真实分段节点、最终目标、请求身份和状态。它不要求保存整条路线的所有节点。

外部语义必须保持：

- `Navigation.isDone()` 表示整个导航任务是否结束；
- 内部当前段耗尽时，不能因为段间等待就把整条任务判定完成；
- `Path.isDone()` 可以表示当前 `Path` 的节点已经耗尽；
- 最后一段才根据最终目标、`Path.canReach()` 和实体实际位置决定成功；
- `PENDING` 不能被伪装成同步查询的 `true` 或 `false`；
- 原版允许消费的 partial `Path` 必须保留其原始语义；
- 当前实现使用 `EXHAUSTED` 区分“原生部分路径耗尽但未达到最终目标”，不能把它改写成 `SUCCEEDED`。

### 3.4 原版 API 兼容

自动接入必须覆盖所有使用原版 `Navigation` API 的常规实体调用：

- `moveTo(BlockPos / Entity / x,y,z)`；
- `moveTo(Path, speed)`；
- `createPath(...)`；
- `stop()`；
- `recomputePath()`；
- `isDone()`、`getPath()`、`getTargetPos()`；
- 原版 Goal、Behavior、Brain、MoveToTargetSink、AcquirePoi 等已核对的调用链。

不能把“已识别调用者”误说成“所有原版调用者”。对无法安全改变同步返回语义的未知 `createPath` 调用，应明确保留 `LEGACY_SYNC_QUERY`，返回真实原版结果并单独统计；不能返回占位 `PENDING` Path 假装已经异步兼容。

## 四、当前已实现内容

### 4.1 核心类

主要实现位于：

- [`AutomaticNavigationBridge.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java)：原版入口、请求意图、控制来源、任务采用和状态桥接。
- [`SegmentedNavigationService.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java)：宏观候选、窗口、分段搜索、调度、恢复、终态和清理。
- [`NavigationRequestPath.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationRequestPath.java)：作为原版 `Path` 传递的请求载体，保存当前真实节点和状态。
- [`NavigationIntent.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationIntent.java)：目标、accuracy、范围和能力意图。
- [`NativeNavigationSearch.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NativeNavigationSearch.java)：原版详细搜索调用适配。
- [`NavigationCapabilities.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationCapabilities.java)：能力快照及原版参数映射。
- [`NavigationScheduler.java`](../../src/main/java/com/scarasol/acceleratednavigation/scheduler/NavigationScheduler.java)：共享预算和调度入口。

### 4.2 原版入口和生命周期

当前 Mixin 接入了：

- 原版 `Navigation` 的 `createPath`、`moveTo`、`stop`、`recomputePath`；
- `GroundPathNavigation`、飞行导航和墙体攀爬导航的局部路径与 `isDone` 行为；
- Goal/Behavior/Melee/Sink/POI 等已核对的控制来源；
- 原版路径序列化读回；
- 外来普通 `Path` 的采用、替换和取消；
- 控制代次，防止旧 Goal 的 stop 清理新任务；
- 服务器关闭时拒绝迟到回调和复活任务。

相关入口文件：

- [`AutomaticNavigationMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/AutomaticNavigationMixin.java)
- [`GroundNavigationMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/GroundNavigationMixin.java)
- [`FlyingNavigationMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/FlyingNavigationMixin.java)
- [`WallNavigationMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/WallNavigationMixin.java)
- [`NavigationGoalMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationGoalMixin.java)
- [`NavigationBehaviorMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationBehaviorMixin.java)
- [`NavigationMeleeMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationMeleeMixin.java)
- [`NavigationSinkMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationSinkMixin.java)
- [`NavigationPoiMixin.java`](../../src/main/java/com/scarasol/acceleratednavigation/mixin/NavigationPoiMixin.java)

### 4.3 任务状态和恢复

当前请求状态包括：

```text
PENDING
REACHABLE
UNREACHABLE
UNAVAILABLE
SUCCEEDED
EXHAUSTED
FAILED
CANCELLED
```

已实现或已验证的生命周期要点：

- 一个 `Navigation` 绑定一个活动请求；
- 新控制来源可以替换旧请求；
- 旧控制来源不能取消新任务；
- 同目标重发不会无条件饿死首次搜索；
- 移动实体目标支持位置更新和有界重规划；
- 普通重算尽量保留请求身份；
- 外来 `Path` 可以取消当前受控请求；
- `stop()`、目标替换、换维度、实体离线和服务器关闭会清理任务；
- 迟到 wake 和回调不会复活已关闭服务；
- partial Path 不会被自动包装成全程成功。

## 五、当前明确偏离新架构的代码

这一节是交接时最重要的检查清单。下一位执行者必须把它当成待修正项，而不是已验收功能。

### 5.1 `GroundAbilityGuard` 仍在详细搜索前做语义过滤

文件：

- [`GroundAbilityGuard.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/GroundAbilityGuard.java)
- [`SegmentedNavigationService.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java)

当前逻辑会检查走廊中的组件、支撑、身体空间、铁轨、粉雪、岩浆、火、模组方块等，并依据检查结果决定窗口是否有资格进入详细执行。

这不是原版 PathFinder 的结果，而是接入层在 PathFinder 之前做的第二套方块语义判断。即使它的判断在某些场景下看起来“更保守”，也仍然属于架构禁止的前置过滤。

必须删除或绕开的是：

- `qualifiedWindow(...)` 作为地面详细执行的准入门；
- 走廊中发现某类方块就拒绝整个窗口；
- 用组件或支撑扫描证明“这段必然可走”或“这段不值得交给 PathFinder”。

应保留的是：

- 区块是否已加载；
- 实际读取区域的身份和当前性；
- 任务请求是否仍然有效；
- 服务器是否正在关闭；
- 原版搜索是否返回成功、partial、无路或不可用。

### 5.2 `retainAbilityProof`、`requalifyWindow` 和 `executionReady`

当前执行阶段还会保存并重新验证 `ability proof`。当语义 revision 变化时，局部节点交付会暂停并重新跑资格判断，失败后可能切换到 `nativeWhole`。

这仍然是“详细寻路自己先证明区段资格”的架构。详细寻路应该直接重新调用真实 PathFinder，由原版 NodeEvaluator 处理变化；接入层只需在输入变化时让旧结果失效、暂停交付或重新排队。

后续修正时要区分：

- **允许保留**：区块加载状态、请求代次、目标身份、能力快照、读取版本、服务器生命周期；
- **必须移除**：方块语义 proof、走廊能力复核、以 proof 失败阻止真实 PathFinder；
- **必须重新验证**：窗口失效后是重新搜索当前候选、缩小候选、宏观刷新，还是最终返回不可用。

### 5.3 地面分段任务不能因资格失败切换整条原版寻路

在 `SegmentedNavigationService.prepareWindow` 和相关恢复逻辑中存在类似：

```java
work.segmented = false;
nativeWhole(work);
```

对明确不支持分段的特殊导航，原版后端回退可以作为兼容边界；但地面分段任务不能因为 `GroundAbilityGuard` 或 proof 失败就静默切换整条原版寻路。否则并发平滑目标被绕过，且失败原因无法区分为：

- 真实详细 PathFinder 无法找到路径；
- 宏观候选不足；
- 旧资格过滤链拒绝了本来应该交给 PathFinder 的输入。

### 5.4 自动接入覆盖面仍有限

`AutomaticNavigationBridge.supported()` 当前通过方法声明位置和标准类结构作限制，Bee 还存在匿名类硬编码例外。此实现不能支撑“所有使用原版 Navigation API 的实体自动兼容”的宽泛承诺。

下一轮必须把以下概念分开统计：

- 原版标准导航入口是否被接管；
- 自定义原版 Navigation 子类是否仍保持原版行为；
- 该导航是否获得分段详细后端；
- 该实体是否有特殊 tick、攀墙、飞行或水下语义；
- 未适配时是否明确记录为兼容回退。

不能通过增加一个实体例外把覆盖面问题改写成已解决。应先枚举当前 Forge 发行 jar 中的 Navigation 子类及实体内 override，再决定哪些能自动接管，哪些必须保留兼容回退。

### 5.5 未识别的同步 `createPath` 消费者仍是兼容边界

未适配的直接同步查询仍走 `LEGACY_SYNC_QUERY`。这是合理的兼容边界，但不能在报告中宣称“所有原版查询消费者已经进入分段详细后端”。

要求：

- 同步消费者继续收到真实原版结果；
- 不向同步调用者返回 `PENDING`；
- 不用宏观 `canReach` 代替需要真实 Path 的特殊查询；
- 统计该边界的调用数量和成本；
- 只有完成定点控制流适配后，才把某类消费者纳入异步等待。

## 六、验证证据和证据边界

### 6.1 已通过的接入控制

来源：[`2026-10-13-navigation-production-integration-validation-zh.md`](../reports/2026-10-13-navigation-production-integration-validation-zh.md)。

已通过的项目包括：

- 五类导航入口、参数和取消；
- 原版 `Path` 序列化读回；
- 纯查询不替换活动 `Path`；
- 控制来源代次；
- 旧 Goal.stop 不清理新任务；
- 外来 `Path` 所有权和替换；
- 原版 Melee 目标漂移；
- 原版 Brain Sink 到达；
- AcquirePoi 的实际异步等待、真实 POI 占票和无同步查询增量；
- 四个原版 Melee 同时执行；
- 正常重算保留任务身份；
- 当前窗口语义变化暂停局部执行后恢复；
- partial 原生 Path 未到最终目标时以 `EXHAUSTED` 结束；
- READY 语义变化和 200 tick TTL 拒绝收养；
- 服务器关闭后服务、记录和 scheduler pending 均为零。

发行驱动结果：

- 业务断言：97 项通过，1 项保留失败；
- 发行字节码审计：295 项；
- handler：60 个；
- mixin group：17 个；
- 资源清理门禁通过；
- 整体业务门禁因保留失败不为全绿。

### 6.2 路线结果

四条默认参数真实路线中：

- `stairs-forward`：自然到达；
- `stairs-backward`：自然到达；
- `city-forward`：自然到达；
- `city-backward`：在约 `(96,84,-264)` 的局部接续点失败。

`city-backward` 的失败表现是多个真实局部 Path 返回 partial，有限候选/恢复及一次宏观刷新耗尽后结束为 `UNAVAILABLE`。从该位置独立起步的短程对照可以自然到达城市终点，但这不能替代全程资格，因此该路线必须继续保留失败。

这条失败目前不能直接归因为“整个世界无路”，也不能直接归因为“当前原版 PathFinder 不可靠”。在移除旧资格过滤链并重新验证前，原因仍可能混合了：

- 旧资格过滤；
- 宏观候选窗口不足；
- 局部详细搜索接续失败；
- 真实方块/碰撞/能力限制；
- 有限恢复和一次刷新额度耗尽。

### 6.3 已有证据不能证明的事情

当前证据不能证明：

- 所有实体自定义 Navigation 都已自动覆盖；
- 所有 TargetGoal、LongJump、第三方同步查询都已获得异步等待语义；
- 所有复杂地形路线都能完整到达；
- 详细 PathFinder 的所有失败都是实体真实不可达；
- 当前地面接入已经符合最终架构；
- 长期服务器负载下的生产性能；
- 仅凭 DeepSeek 文本共识即可完成源码验收。

## 七、DeepSeek 审查为什么漏掉架构错误

旧的 DeepSeek `final3` 结论不能继续作为当前架构验收依据。送审提示词存在三个根本问题：

1. 把旧方案内容作为冻结规范，而旧方案事实上已经被用户明确舍弃。
2. 使用“用户最新架构决定优先于旧设计文档”这类表述，把已废弃方案错误地当成仍可比较的候选，而不是明确标记为失效。
3. 没有把“禁止在宏观交付后、真实详细 PathFinder 之前增加资格过滤”写成硬性否决条件，也没有要求逐一检查 `GroundAbilityGuard`、`qualifiedWindow`、`retainAbilityProof`、`requalifyWindow` 和 `executionReady`。

此外，提示词携带了上一轮审查结论，造成锚定。后续审查必须：

- 只以用户最终架构为规范；
- 明确写出旧方案已经失效；
- 列出禁止项；
- 要求对源码逐符号核对；
- 要求把“生命周期接入通过”和“架构符合通过”分开判定；
- 遇到资格过滤、整段回退或自定义 proof 时直接标为偏离；
- 不复用旧结论作为证据。

建议下一次送审开头直接使用以下判定规则：

```text
硬性规则：
宏观寻路完成后，当前宏观候选必须直接交给真实原版详细 PathFinder。
接入层不得依据方块类别、走廊组件、支撑、身体邻域、自定义能力证明或语义 revision，
在真实 PathFinder 之前阻止、替换或降级详细搜索。
区块未加载、请求取消、服务器关闭和身份失效属于生命周期/读取安全边界，
不属于详细可行性资格过滤。
只要源码存在上述前置过滤链，架构验收必须失败。
```

## 八、后续执行顺序

下一位执行者应按以下顺序处理，不能跳过第一项直接做性能测试或扩大实体覆盖。

### 第一步：冻结纠正后的架构契约

在新的审查材料中明确：

- 旧方案已废弃；
- 宏观结果直接进入真实详细 PathFinder；
- 原版 NodeEvaluator 是唯一详细可行性判断来源；
- 接入层保留身份、预算、加载状态、输入当前性和生命周期；
- 接入层删除方块语义资格过滤和 proof 门控。

### 第二步：移除地面详细执行前置过滤

重点处理：

- `GroundAbilityGuard`；
- `qualifiedWindow`；
- `retainAbilityProof`；
- `requalifyWindow`；
- `executionReady` 中与能力 proof 绑定的判断；
- 因资格失败而调用 `nativeWhole` 的地面分支。

修改时不要顺手改宏观拓扑算法、宏观走廊生成或用户未授权的其他宏观行为。此次纠正只针对接入边界和详细执行前置判断。

### 第三步：保留必要的接入保护

删除资格过滤后，仍必须保留：

- 请求代次和控制来源；
- 活动请求替换；
- `NavigationRequestPath` 状态；
- 区块加载和未知区域安全；
- 目标实体位置变化；
- 能力快照在原版查询期间的保存和恢复；
- 原版 `prepare/done` 配对；
- shared soft budget；
- END 调度；
- 取消、超时、关闭和迟到回调清理；
- `SUCCEEDED`、`EXHAUSTED`、`UNREACHABLE`、`UNAVAILABLE` 等终态区分。

这些是接入职责，不是详细可行性 proof。

### 第四步：重新运行 `city-backward`

重新验证时至少记录：

- 宏观走廊和窗口输入；
- 真实详细 PathFinder 每次返回的完整/partial/无路结果；
- 真实 PathFinder 的节点和目标状态；
- 是否因为区块未知、目标漂移、身份替换或恢复上限终止；
- 是否仍在 `(96,84,-264)` 附近失败；
- 失败是 `UNREACHABLE`、`UNAVAILABLE`、`EXHAUSTED` 还是 `FAILED`。

必须把“旧资格过滤导致的拒绝”与“真实详细搜索失败”分开报告。

### 第五步：重新送 DeepSeek 审查

只提交修正后的源码和新架构提示词。不能引用旧 `final3` 作为“已经验收”的证明。审查结果需要分别给出：

- 架构是否符合；
- 生命周期接入是否符合；
- 原版 API 覆盖面；
- 同步查询兼容边界；
- 真实路线结果；
- 仍未覆盖的实体或消费者。

### 第六步：再考虑生产准入

在以下条件全部满足前，不应称为最终生产接入：

- 前置资格过滤链已删除；
- 地面分段不再因 proof 失败切换整条原版寻路；
- `city-backward` 已经重新归因；
- DeepSeek 按新规范复审；
- 自动覆盖面和兼容回退矩阵重新核对；
- 业务门禁不再因为未解释的保留失败而保持 false。

## 九、关键文件索引

设计和验证：

- [`2026-10-12-navigation-auto-integration-plan-zh.md`](../designs/2026-10-12-navigation-auto-integration-plan-zh.md)
- [`2026-10-12-navigation-production-implementation-notes-zh.md`](../designs/2026-10-12-navigation-production-implementation-notes-zh.md)
- [`2026-10-12-navigation-auto-integration-deepseek-consensus-zh.md`](../reports/2026-10-12-navigation-auto-integration-deepseek-consensus-zh.md)
- [`2026-10-13-navigation-production-integration-validation-zh.md`](../reports/2026-10-13-navigation-production-integration-validation-zh.md)
- [`2026-10-12-navigation-wiring-check-zh.md`](../reports/2026-10-12-navigation-wiring-check-zh.md)

实现：

- [`SegmentedNavigationService.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java)
- [`AutomaticNavigationBridge.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java)
- [`NavigationRequestPath.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NavigationRequestPath.java)
- [`GroundAbilityGuard.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/GroundAbilityGuard.java)
- [`NativeNavigationSearch.java`](../../src/main/java/com/scarasol/acceleratednavigation/navigation/NativeNavigationSearch.java)
- [`NavigationScheduler.java`](../../src/main/java/com/scarasol/acceleratednavigation/scheduler/NavigationScheduler.java)
- [`ProductionNavigationProbe.java`](../../src/test/java/an/production/ProductionNavigationProbe.java)

临时审查证据：

- `.tmp/navigation-auto-review-deepseek-20261012/`
- `.tmp/segmented-navigation-prototype-20261012/`
- `.tmp/detailed-navigation-framework-20261011/`

## 十、交接时的最终判断

可以交接的内容是：一版已经提交并推送的原版 Navigation 生命周期接入，以及对应的发行环境验证证据。

不能交接为“已完成”的内容是：符合用户最终架构的详细寻路生产接入。当前实现仍需先删除详细 PathFinder 前的资格过滤和执行期 proof 门控，再重新归因路线失败，并重新送审。

本文件生成后不应继续自动推进实现、性能测试或 DeepSeek 审查，除非下一轮明确开始后续工作。
