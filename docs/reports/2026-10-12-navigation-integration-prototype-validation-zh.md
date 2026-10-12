# 自动Navigation四个接入原型运行验证

日期：2026-10-12。授权：“好，继续验证”。承接[接线核对](2026-10-12-navigation-wiring-check-zh.md)及[最终接入规格](../designs/2026-10-12-navigation-auto-integration-plan-zh.md)。

四个定点原型已在隔离Forge服务器执行并通过。最终运行72项业务断言全部通过；独立检查变换类、映射和输入，178项检查通过。证明范围是这些入口和状态转换能够实际运行，不能扩大为整个详细寻路服务已实现、所有原版AI自动兼容或真实长路线完成。

## 1. 输入与实施边界

工作目录：`.tmp/navigation-integration-prototype-20261012`。Java17.0.18+8，Forge47.3.0，Minecraft1.20.1 official映射，Mixin0.8.5，MixinExtras Forge0.3.6。每次启动使用新建平坦世界；没有启动原存档、IDEA或客户端，没有Git提交/push、子代理或新增DeepSeek调用。

隔离模块复制当前12个生产拓扑Java文件和ResumableSearch，拓扑副本逐文件哈希相同；四个事实Mixin只改package，独立逐字对照通过。NavigationScheduler使用隔离修改副本。AcceleratedNavigation宿主仅提供LOGGER，没有生产@Mod或事件注册，启动的实际Mod是`an_navigation_probe`。不能把这个模块称为生产Mod整体运行。

新增RequestPath、Bridge、PoiAdapter、ProbeReachQueries与测试Suite，只有夹具登记的Navigation进入桥接。原版GoalSelector不自动选择测试Goal；测试明确调用原版WrappedGoal/Melee及AcquirePoi行为。Pig关闭自带Goal，Villager子类抑制自动Brain步骤，但保持自然实体物理、Navigation/MoveControl。没有强置onGround、禁重力、位置回放或伪造完整Path。

宏观查询复用实际TopologyService/Runtime/Executor，见证使用真实BaseGraphView。原型反射取得runtime以复用requestBaseGraph，没有新建worker。见证枚举与最终世界碰撞检查在共享主线程队列执行；生产方案拟把冻结图枚举放在既有worker，这一职责分布未在本轮实现。

## 2. 编译与实际变换

显式AP顺序保持：

```text
MixinExtrasAP
MixinObfuscationProcessorInjection
MixinObfuscationProcessorTargets
```

实际配置10个Mixin，生成9组/52条refmap：ProtoChunk只有构造器通配注入，不需要重映射条目。独立ASM读取编译类及服务器导出的9个Minecraft目标类，确认37个注入处理方法和12个Accessor/Invoker已存在于变换结果；逐条按实际TSRG和继承来源核对全部映射，合计178项通过。required/expect/allow及运行注入检查保持，未设置remap=false。

AcquirePoi的合成lambda不在javac成员模型中；Mixin处理器的TARGET_ELEMENT_NOT_FOUND类别实际是WARNING。第一次编译退出0，日志有5条此类警告，失败的是脚本误要求10组refmap。后续仅对该合成目标Mixin使用`@SuppressWarnings("target")`；实际bytecode、精确映射、运行required和Local捕获另外验证。不能将这一局部警告抑制当作静态目标验证通过。

执行中曾把乱码警告误读为编译错误，后已用处理器字节码和实际产物更正；此前第0项的编译0记录无需撤回。新脚本直接记录javac的LASTEXITCODE，原产物不覆盖。

关键运行命中包括：局部tick保护545次、全程isDone覆盖16次、原生stop标记1次、null start补done1次、Melee prepare.entity1次、Goal的五种委托、POI retry捕获9次、query捕获4次、保存批次和READY载荷恢复各1次。所有配置处理方法的变换存在不等于每个重载都在本轮业务中执行。

## 3. Ground与Path安全

真实Pig在第20 tick位于Y=81、onGround=true、tickCount=20、未移除，未关闭AI物理。直接调用实际GroundPathNavigation：

| 控制 | 结果 |
| --- | --- |
| 活动任务但当前path为null | Navigation.isDone=false，局部tick不读取节点 |
| PENDING请求Path，真实节点数为0 | Path.isDone=true，Navigation.isDone=false，tick安全 |
| 原版实际生成一节点Path，同tick消耗最后节点 | nextNodeIndex=1，Path结束而任务仍活动，第二次保护安全 |
| 原生卡住检测调用stop并置null | 不取消全程请求，随后局部保护安全 |
| 外部stop | 请求进入CANCELLED、释放当前路径，Navigation.isDone=true |
| Path父类列表、sameAs与协议 | 真实节点列表参与sameAs；序列化往返保留目标、reach、索引和节点 |

raw查询暂存并隔离当前path/targetPos/reachRange，避免当前请求句柄被原版缓存捷径作为查询结果。null getStart、getStart异常和邻居扩展异常三种控制均调用一次prepare/一次done，之后同一evaluator仍可寻路；最终累计nativeCalls=prepareCalls=doneCalls=10。异常时恢复raw作用域及profiler栈。未测试prepare部分执行后抛错、done自身抛错或profiler.pop自身抛错。

这里让任务保持活动用于检验局部段与全程状态分离；原型没有完成全程终态判定或段间推进，不能据此证明任务最终一定完成。

## 4. 原版准备与决策调用

MeleeAttackGoal由实际WrappedGoal委托，原始canUse内createPath(Entity,0)产生PENDING请求，没有内联搜索，也未改变正在执行的普通Path。start的原版moveTo(Path,speed)收养该请求，随后canContinueToUse仍成立；tick中同目标moveTo(Entity,speed)复用它，没有重复排队。

关闭派发期间等待两tick，没有搜索。允许END派发后，第26 tick的服务器线程只产生一次实际原版搜索，返回9个真实节点。旧Goal.stop不能取消后继Goal的新请求；旧Path句柄不能复活；Goal.stop即使不调用Navigation.stop也清理归属任务；Goal.start抛错后作用域及请求清理成立。

AcquirePoi通过真实lambda$create$6、getRandom前等待点、保存候选流和LocalRef结果恢复进入原成功分支：

- PENDING不更新重试时钟、不新增失败backoff、不占票、不写JOB_SITE。
- READY复用原候选批次及查询结果，原成功分支写入实际选定原POI。
- witness队列被1024个任务占满时，查询为UNAVAILABLE并释放；不按“不可达”写失败或占票。
- 显式取消查询后，下次行为执行释放记录；票据和失败表不变。

本轮READY由实际宏观查询及见证产生，没有手工设置业务成功或构造假终点节点。可达查询结果无节点，Melee移动请求保存实际短路径节点；两者用途不同，POI查询没有抢Navigation当前路径。

## 5. 共享4ms控制

BudgetTests使用确定性模拟时钟在真实服务器线程执行25个控制，结束后恢复System.nanoTime才进入世界业务。确定性数值不是实际CPU采样。

| 控制 | 观测 |
| --- | --- |
| 连续补充预算 | 正令牌上限4,000,000ns |
| 原子搜索执行7ms | 令牌-3ms，后续任务不启动；下一tick补至1ms后执行 |
| 搜索0.5ms、同步回调6.5ms | 同样记账7ms、负债-3ms并停止后续派发 |
| 搜索内嵌套account与同步回调嵌套account | 区间并集3ms，只扣一次，剩1ms |
| 准备阶段嵌套1.5ms加END搜索1ms | 总扣2.5ms，剩1.5ms |
| step/park/result异常及取消/回调 | 分别扣3/4/4ms，future异常完成、pending归零 |
| wake、haveTime=false、45ms余量耗尽 | wake仅入队；禁止派发时不调用step；下一允许END执行 |
| 取消后wake、同owner替换、1024容量、维度轮转、停止后取service | 不复活、旧请求取消、上限拒绝、轮转成立、停止后拒绝创建 |

隔离Scheduler在整个END调用外层记账，覆盖选择、执行、park、status/result发布及同步future回调；嵌套账段不重扣。准备、取消、POI恢复/见证和宏观查询回调也进入同一账本。扣账检查包含尚未闭合区间，避免等回调结束后才发现后续已过度派发。

检查发现原Scheduler的result发布在搜索step的异常保护之外，result抛错可能移除pending后遗留未完成future；隔离副本新增取消和异常完成，已通过对应控制。生产Scheduler未改；4ms正上限、wake仅入队、完整记账及关闭保护均待正式接入。

本轮验证这些原型入口和调度工作共享计时，未把整个生产TopologyService事实维护成本拆分纳入预算，也未实现未知同步查询兼容例外、所有业务及所有导航类的统一记账。因此不能声称整个生产导航服务的成本已完整受控。4ms仍是软预算；原子调用与回调可超支，不能据72个控制声称任意实体数量下每tick耗时硬限制为4ms。

## 6. 多POI与真正见证

场景三个原POI：`(6,81,12)`、`(12,81,12)`、`(20,81,12)`，每点初始1张空票。最近点周围3×3、Y81至82为石头；宏观anchor可以回落到距离2位置，但没有accuracy=1内的合法支撑/身体位置。

实际保存3候选；同一独立child owner依次提交候选0、1，最大在途数1，移动owner的另一个MacroRequest未被取消。候选0不能仅凭结构anchor批准；候选1在目标侧实际组件0获得`(12,81,11)`见证。检查Base签名、view.current、同组件、距离1、当前支撑高度及实体身体碰撞空间；恢复前再查POI存在与空票。

最终JOB_SITE为原目标`(12,81,12)`，票据为`[1,0,1]`，不是把邻近见证作为工作点。成功消耗后保存记录与两份已开见证view立即释放；容量拒绝控制又释放一份。全程openedViews=closedViews=3；取消及服务关闭没有遗留记录或活动Path。

这里仅覆盖固定平坦地面、当前体型/能力及同section见证，不证明跨section accuracy、所有malus/门/水能力、动态失效、TTL和全局查询容量策略。

## 7. 原件、失败与收尾

所有轮次保留，不覆盖失败或把失败改成通过：

| 轮次 | 实际结果 |
| --- | --- |
| build-first | javac0，有5条合成方法警告；9组refmap被误写的10组门槛拒绝 |
| build-target-checked / server-first | 实际Mixin加载；22项中ground控制失败。夹具关闭动物/NPC及NoAI；另遗漏AT引发TopologyCompression非法访问与关闭超时 |
| build-fixtures / server-fixtures | AT问题消失；实体仍因spawn开关被移除，ground控制失败，tickCount=0 |
| build-actors / server-actors | 开关允许动物/NPC、游戏规则禁止自然刷怪；自然物理成立，154tick、66项通过 |
| build-accounting / server-accounting | 补回调超支、准备共享账及三种异常，增加原型回调账段；148tick、72项通过、Java退出0、27.97秒 |
| verification-accounting | 独立ASM/refmap178项通过；业务/事件身份、输入/源码及生产边界核对通过 |

最终生产/配置/权威规格/输入39个保护文件无漂移；编译28个源码快照相符；本次冻结的243个运行文件无漂移。该冻结包含启动参数和classpath清单，不声称逐个冻结所有外部依赖jar。

世界关闭时事实转移、worker请求/引用/任务和持久化队列归零；没有非预期查询异常、写入失败或关闭超时。日志仍有既有userdev环境的8个Forge coremod映射错误，以及默认flat生成参数缺layers记录；25个预算控制故意产生的3条异常日志有对应断言，不计作未处理业务失败。环境限制保留，不等同正式发行包干净启动。测试Java进程已结束。

主要原件：

- [运行结果](../../.tmp/navigation-integration-prototype-20261012/server-accounting/result.json)、[启动退出](../../.tmp/navigation-integration-prototype-20261012/server-accounting/exit.json)、[冻结输入](../../.tmp/navigation-integration-prototype-20261012/server-accounting/freeze.json)。
- [变换/映射独立检查](../../.tmp/navigation-integration-prototype-20261012/verification-accounting/transformation-audit.json)、[收尾核对](../../.tmp/navigation-integration-prototype-20261012/verification-accounting/verification.json)。
- 各轮src快照、编译日志、启动参数、stdout/stderr、真实世界和Mixin导出类均在同一临时目录保留。

最终规格SHA256仍为`5f3dfef4e1fd02a8c1ab97d4d9627f1e5cc9656ec3be3598c67cada4fa666d0f`，未修改。第0项运行时变换/Local组合的缺口已由这些定点运行补上；132条旧分段链及已有并发结论复用，不重跑分片选择。

## 8. 剩余工作

本阶段四个定点闭环完成。下一实体验证仍应优先处理已知楼梯目标`(-22,95,-212)`实际落地Y96、城市反向目标`(90,93,-262)`实际落地Y95，以及从真实位置进入下一段和最终完成判定。既有真实完整路线失败结论不撤回。

随后按最终规格接入生产、确认实际Gradle处理器和打包refmap，再验证正常GoalSelector/Brain、特殊Navigation、动态受阻/换维度/死亡、未知同步查询及繁忙服务器完整tick。生产接线、整个详细寻路服务、全实体覆盖与背景性能验收均未完成。
