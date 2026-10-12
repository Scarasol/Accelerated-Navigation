# Navigation自动接入第0项接线核对

日期：2026-10-12。工程：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`。授权：用户在DeepSeek共识后要求“好，进行核对”。

结论：当前字节码支持共识方案中的关键截获、取消和恢复位置，没有发现要求重新选择分段架构的冲突。35项既定字节码断言通过；扩展后的8组Mixin、23个诊断处理方法，通过66项方法/调用点/refmap对照，得到36条正确映射。**这些是静态接线及隔离注解处理器结果，尚未实际应用Mixin变换或启动世界。**

发现一项必须落实的构建要求：当前版本组合下，诊断编译自动发现处理器时会漏掉WrapOperation映射，尽管javac退出0。显式先加载MixinExtrasAP后，全部映射生成。实际Gradle配置读取受本机已有loopback错误阻断，因此不能把这一发现扩大为“当前生产Gradle已经确认存在同样故障”，也不能称完整构建已通过。生产接线时需要固定处理器初始化顺序并核对最终refmap和重混淆产物。

本轮只写隔离诊断及核对/恢复文档，保留用户既有生产改动，没有实现自动导航、升级依赖、启动游戏或新增性能采样。没有子代理、新DeepSeek调用或Git提交/push。

## 1. 依据与证据

[最终共识规格](../designs/2026-10-12-navigation-auto-integration-plan-zh.md)保持只读，SHA256：
`5f3dfef4e1fd02a8c1ab97d4d9627f1e5cc9656ec3be3598c67cada4fa666d0f`。

实际目标为本工程Forge47.3.0、official1.20.1映射jar：
`build/fg_cache/net/minecraftforge/forge/1.20.1-47.3.0_mapped_official_1.20.1/forge-1.20.1-47.3.0_mapped_official_1.20.1.jar`，
SHA256：`61EFADCE6369E445A1DC1159ED293DBD138F00E20122984468E16FF90B882AEF`。这不是游戏启动后经过全部coremod/Mixin变换的字节码。

工具沿用Java17、Mixin0.8.5及MixinExtras0.3.6，classpath复用既有隔离接入记录。没有重新生成MC依赖，也没有拿另一版本源码代替当前字节码。

| 证据 | 实际结果及边界 |
| --- | --- |
| [计划](../../.tmp/navigation-wiring-check-20261012/plan.md) | 第0项范围；共识规格和生产只读 |
| [ASM检查](../../.tmp/navigation-wiring-check-20261012/bytecode-audit.json) | 遍历7110个Minecraft类，35项断言；10个Navigation子类、6个TargetGoal调用者及6处同名navigation字段写入 |
| [方法清单](../../.tmp/navigation-wiring-check-20261012/method-inventory.json) | 16个关键类的方法描述符、访问标记、调用/字段和LVT范围，含逐类字节哈希 |
| [导航反汇编](../../.tmp/navigation-wiring-check-20261012/navigation-bytecode.txt)、[消费者反汇编](../../.tmp/navigation-wiring-check-20261012/consumer-bytecode.txt)、[继承及Brain反汇编](../../.tmp/navigation-wiring-check-20261012/override-and-brain-bytecode.txt) | 当前jar的javap原件；指令偏移只适用于该身份 |
| [最终诊断源码](../../.tmp/navigation-wiring-check-20261012/HookDescriptorProbe.java) | 8个未注册的Mixin、23个处理方法；多数方法仅验证签名，未实现业务 |
| [有序编译refmap](../../.tmp/navigation-wiring-check-20261012/hooks-lifecycle-ordered/probe-refmap.json)及[对照结果](../../.tmp/navigation-wiring-check-20261012/hooks-lifecycle-ordered/refmap-audit.json) | 编译0；8组/36条映射，66项对照全通过 |
| [自动发现对照](../../.tmp/navigation-wiring-check-20261012/hooks-lifecycle-auto/refmap-audit.json) | 同样编译0，但只生成6组/16条映射；检查器退出1、准确识别22项缺失 |
| [收尾核对](../../.tmp/navigation-wiring-check-20261012/final-verification.json) | 输入、原共识附件、能力、产物身份及本报告链接的核对；不将未运行项目填成通过 |

## 2. Navigation入口、节点与继承

PathNavigation自身有8个createPath重载。签名为`(DDDI)Path`的坐标重载是final；字节码注入仍可截获，不能依赖Java子类覆写它。两个protected共同入口为`(Set, int, boolean, int)Path`及`(Set, int, boolean, int, float)Path`。实际moveTo重载为`(double,double,double,double)boolean`、`(Entity,double)boolean`和`(Path,double)boolean`。

实施时要同时覆盖业务入口与共同原始入口，并用raw/请求作用域避免重入重复提交。Ground的BlockPos/Entity重载在进入base之前执行目标处理，不能只拦共同findPath而漏掉这些前置扫描；Flying和WallClimber的Entity重载也要按各自语义处理。完整描述符列于方法清单，重载不能只写一个模糊方法名。

| 位置 | 当前字节码事实 | 接线约束 |
| --- | --- | --- |
| PathNavigation.tick()V | 两次Navigation.isDone，调用偏移22、159；另有一次直接Path.isDone | 仅替换两次Navigation判断为当前真实path的局部状态；第二次重新读取，防advance末节点或stop置null后继续读节点 |
| FlyingPathNavigation.tick()V | 独立两次Navigation.isDone，偏移22、150 | 要有Flying自身适配；base注入不能代替 |
| WallClimberNavigation.tick()V | 一次Navigation.isDone，偏移1；随后分支调用super.tick或使用pathToPosition | 局部Path耗尽可进入原攀墙分支；首次等待仍须由独立fallback-ready拦住。单纯Redirect该判断不足以完整保护 |
| doStuckDetection(Vec3)V、timeoutPath()V | 各一处直接stop()V | 只在这两个调用点记录段卡住原因；其他外部stop仍取消全程 |
| Path四个动态方法 | getTarget、canReach、getDistToTarget、writeToStream均可覆写 | 请求Path可持最终元数据；父类nodes仍须使用同一个真实可变List |

全jar的10个Navigation子类包括5个标准类型，以及Bee匿名Flying、Turtle、Frog、Strider、Warden匿名类型。Bee的tick在未授粉时调用Flying.super.tick；Turtle没有本轮筛选的入口/tick覆写，Frog、Strider、Warden另有createPathFinder覆写。这只确定继承路径，不证明它们的evaluator/流体/体型已获得地面分段资格。

6处同名字段写入不能描述成“6次实体导航替换”：FollowMobGoal/FollowOwnerGoal各一处是Goal缓存字段；实际Mob导航写入包括Mob构造、Drowned.updateSwimming的两个分支及Drowned合成access$002。Drowned切换必须比较对象身份，仅在旧新对象不同且旧对象存在任务/查询时结束旧身份；反复赋同一对象不能每tick取消。未知第三方override仍按规格明确回退，不以本jar枚举冒称全模组覆盖。

## 3. AcquirePoi恢复位置

实际业务入口是静态boolean合成方法`lambda$create$6`，完整描述符：

```text
(ZLorg/apache/commons/lang3/mutable/MutableLong;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;Ljava/util/Optional;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;J)Z
```

候选查询调用`findPathToPois(Mob,Set)Path`唯一，偏移172。此时slot12为候选Set；MutableLong、ServerLevel、PathfinderMob分别是参数slot1、6、7。恢复身份使用实际闭包记录与实体/Navigation/queryId，不能仅用坐标或全局布尔。

确定的恢复顺序：

1. 首次仍由原业务门槛、重试时间和POI筛选产生批次；在findPathToPois之前截获。PENDING保存批次/accuracy，取消本次lambda剩余执行并返回true，不进入成功占票或失败退避。
2. 后续在唯一ServerLevel.getRandom调用之前检查已存在记录。该点位于baby/初始化/时间门槛之后，却早于新重试时间、筛选及path判断；PENDING返回true而不重复抽取重试时间。业务门槛或记录身份失效则释放。
3. READY在原允许的业务轮次恢复：候选扫描的WrapOperation使用保存批次的stream，后续原limit(5)/collect仍成立，避免重新执行会markAttempt的候选predicate；findPathToPois的WrapOperation直接提供已完成结果，不再提交搜索。本轮验证这两处调用的描述符及映射，也验证Set的可变局部引用签名。
4. READY结果固定getTarget为选中的原POI；消费前核对当前记忆/type/空间。仍由原成功分支用该目标占票。明确全批无路才对保存批次执行原失败分支。
5. UNAVAILABLE/CANCELLED释放查询、批次和child owner；跳过失败分支，后续按原重试机制重新取得候选，不能永久保留一个无结果的记录。

READY业务消费轮次仍允许原重试时间更新；限制的是等待期间重复更新或重复筛选，不取消原有时间门槛。这个状态恢复尚未执行，不能以空探针推定POI占票等业务已通过。

合成lambda在实际TSRG中有映射：`lambda$create$6`为`m_257163_`，`findPathToPois`为`m_217097_`。最终refmap已经正确生成；不能因为Java注解处理器看不到合成方法就使用remap=false。

## 4. Sink、TargetGoal与LongJump

| 消费者 | 准确截获与恢复 | 保留语义 |
| --- | --- | --- |
| MoveToTargetSink | 业务`tryComputePath(Mob,WalkTarget,long)boolean`的唯一speedModifier PUTFIELD之后，已经写path/speed但尚未写不可达记忆；`checkExtraStartConditions(ServerLevel,Mob)boolean`有两次eraseMemory，ordinal0才是WALK_TARGET，WalkTarget局部为slot4 | PENDING句柄返回true供start收养；UNAVAILABLE启动返回false但在删目标前取消该分支。READY按同一WALK_TARGET身份更新CANT_REACH；不误拦真正到达或确定失败清理 |
| TargetGoal | `canAttack(LivingEntity,TargetingConditions)boolean`唯一私有canReach调用之前；条件/限制已检查，reachCache尚未写1/2 | PENDING取消外层canAttack并返回false，缓存仍EMPTY；此时私有canReach内的随机缓存期限也尚未抽取。READY按真实末端XZ平方距离≤2.25计算、再写缓存，不重复原搜索 |
| LongJump | `pickCandidate(ServerLevel,Mob,long)void`的`createPath(BlockPos,int,int)Path`之前；PossibleJump slot6、BlockPos slot7、跳跃向量slot9 | 保存已取出的候选及向量，PENDING取消pickCandidate；不能用null或boolean false把等待解释成“可以跳” |

Sink有LivingEntity桥接重载，注入选择Mob业务描述符，不能同时对桥接与业务重复提交。其canStillUse/tick的READY消费与真正停止仍待状态原型验证。

全jar6个TargetGoal.canAttack调用者均位于canUse：HurtByTargetGoal、OwnerHurtByTargetGoal、OwnerHurtTargetGoal、Fox内类、TraderLlama内类和Vex内类。当前TargetGoal.canContinueToUse没有调用该私有可达判定。异步等待仅在已识别SELECT阶段开放；未知外部或继续使用阶段仍走LEGACY_SYNC_QUERY。纯宏观结果不替换这个真实末端判定，原生部分Path仍可能通过。

LongJump.canStillUse中有List.isEmpty和Optional.isEmpty各一次。只包候选List调用，将其结果变为“原列表为空且没有有效待定候选”，不改其他条件或冷却分支；最后已移出的待定候选不会因此使行为停止。

LongJump.tick的findJumpTries递减在调用pickCandidate之前，所以等待/就绪恢复必须在tick HEAD处理，不能只在pickCandidate HEAD拦。READY可步行时，消费待定记录，再通过shadow/调用桥接进入原pickCandidate循环，当前tick不额外递减；READY真实null或不可达部分Path时，设置保存的chosenJump及消费时刻prepareJumpStart，然后结束本tick；UNAVAILABLE只允许有界重试并将合法候选归还。原200tick时限、初始位置、水状态、尝试上限仍生效。

CallbackInfo取消只能退出当前方法，不能“恢复到旧调用内部的goto/continue”。因此需上述显式状态和原选取入口桥接，不能靠一个CIR或异常模拟暂停栈。LongJump的私有落点检查、重新碰撞核对及原子预算费用仍需在原型实现中落实。

## 5. Goal/Brain作用域及原始搜索

现有MixinExtras0.3.6实际含WrapOperation、Local、Share和LocalRef；不含WrapMethod、Expression。现阶段不需要升级库来实现关键调用作用域。

WrappedGoal的canUse、canContinueToUse、start、tick、stop各有一处原Goal委托。五个调用均已诊断编译及映射，生产用WrapOperation在original.call外建立方法阶段/控制代次并try/finally收尾。不能仅用HEAD/TAIL，因为异常时TAIL不会运行；stop的finally还要按捕获的旧控制代次释放，即使旧Goal.stop抛异常，也不得清新Goal任务。诊断源码仅canUse展示了作用域配对，其余是签名探针，不声称生命周期功能已实现。

Behavior的tryStart、tickOrStop、doStop在当前字节码为final；其中checkExtraStartConditions/start、canStillUse/tick、stop五个虚调用均已编译及映射。包这些委托可覆盖Mob业务桥接的实际调用并配对异常退出，保留原status/超时逻辑。AcquirePoi是OneShot静态业务lambda，不因适配Behavior就自动获得覆盖，使用独立闭包身份。

原始搜索包装仍须满足共识规格：临时隔离受控path/targetPos/reachRange，应用请求能力flag/malus，再于finally恢复；没有单次搜索内部暂停。

PathFinder公开findPath在prepare之后getStart为null时直接返回，跳过done；异常亦没有整体finally。私有搜索的ProfilerFiller.push/pop只在正常路径配对。原始搜索适配要跟踪实际prepare/done和profiler进入/退出，仅补缺失清理，不重复正常done/pop。现有WrapOperation可包外层PathFinder调用及这些内层调用；本轮只核对原控制流，尚未编译或执行完整清理适配。

## 6. 注解处理器核对与失败保留

| 轮次 | 实际结果 |
| --- | --- |
| initial-failed | 未启用mappingTypes=tsrg，FG3 searge环境未建立，编译失败。仅为诊断脚本问题，原件保留 |
| mapped | 启用TSRG后编译0；11个初始处理方法的普通映射存在，但MixinExtrasAP显式排在Mixin处理器之后，WrapOperation映射缺失 |
| extras-first | 保持初始探针，改为先加载MixinExtrasAP，编译0且8组映射生成 |
| lifecycle-auto | 扩展至23处理方法，不指定processor顺序，编译0但只6组/16条；对照检查退出1、22项缺失 |
| lifecycle-ordered | 相同23处理方法，显式extras-first，编译0；8组/36条、66项对照全通过 |

原因由当前库源码和对照支持：MixinExtrasAP在静态初始化中registerInjectors，而MixinObfuscationProcessorInjection取支持注解及处理时读取InjectionInfo注册表。晚初始化会使额外注入注解未被正确处理。诊断脚本显式顺序为：

```text
com.llamalad7.mixinextras.ap.MixinExtrasAP,
org.spongepowered.tools.obfuscation.MixinObfuscationProcessorInjection,
org.spongepowered.tools.obfuscation.MixinObfuscationProcessorTargets
```

最终有序轮仍有4条AcquirePoi合成lambda的Java模型警告，对应两Inject/两WrapOperation；字节码已确认方法存在、全部映射正确。使用@Dynamic标明依据，没有关闭target validator，也没有设置remap=false。AP成功不证明运行时Local捕获、注入组合顺序或required命中。

Gradle只读配置探针及一次IPv4重试均在项目配置前因`Unable to establish loopback connection`退出1；未执行compileJava或写入生产输出。保留[首次日志](../../.tmp/navigation-wiring-check-20261012/processor-config.log)和[重试日志](../../.tmp/navigation-wiring-check-20261012/processor-config-ipv4.log)。本轮因此未取得实际compileJava的解析后processorPath，不能称Gradle配置/完整构建核对通过。隔离结果已足以确定可行的处理器顺序，正式构建还需验证其实际应用。

## 7. 本轮完成与下一步

| 项目 | 状态 |
| --- | --- |
| 第0项的实际方法、局部变量、关键控制流、继承清单及可用注入能力 | 已核对，具体位置与恢复方式形成 |
| 代表诊断处理方法的Java签名、实际调用次数和TSRG/refmap | 有序配置下已通过，自动发现不足已识别 |
| 生产Gradle实际处理器应用及最终打包/refmap | 未通过，配置读取受环境阻断；不能以本轮替代 |
| Mixin实际变换、required/allow命中、Local捕获和组合顺序 | 未运行 |
| DecisionQuery实现、PENDING/READY/UNAVAILABLE业务恢复 | 未实现/未运行 |
| 原Goal/Brain接入、真实行走、繁忙服务器背景性能 | 未验证；既有失败与限制仍保留 |

下一阶段仍是四个小闭环：Ground的null/空节点/同tick耗尽；Melee或Avoid准备型加AcquirePoi决策型；完整预算及同步回调/END门控；多POI串行owner、合法见证及固定占票目标。开始实际变换前固定处理器加载并检查refmap完整；新增Mixin保持required，失败不得默默开放异步用途。

其他消费者和特殊Navigation在取得对应资格前保持明确兼容回退。随后解决已知真实落点、生产接线和原AI背景验证。复用既有132条分段链及并发预算证据，不因此次接线调查重做可靠性或分段/分片选择；普通canReach的宏观结构语义、真实分段Path和约4ms共享软预算均保持。
