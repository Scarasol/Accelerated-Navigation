# 原版Navigation自动接入与分段Path方案

> 已废弃：本文及基于本文的旧 DeepSeek 共识不具备当前架构验收效力。本文仅保留历史记录；现行唯一契约为 [2026-10-13 原版详细寻路接入契约](2026-10-13-native-detail-navigation-contract-zh.md)。不得把本文作为冻结规范、并列方案或低优先级依据送审。

日期：2026-10-12。工程：`D:\Workspace\AcceleratedNavigation-forge-1.20.1`。

状态：依据当前Forge源码修订的接入规格，联合文本评审结论见[审查记录](../reports/2026-10-12-navigation-auto-integration-deepseek-consensus-zh.md)，尚未实现或取得运行资格。本方案替代[上一版显式业务入口方案](2026-10-12-segmented-navigation-integration-plan-zh.md)的接入范围、Path契约及控制权约定；分段同步、服务器共享约4ms软预算的[架构方向](2026-10-12-segmented-navigation-shared-budget-zh.md)保持。

用户要求自动接管原版Navigation调用，兼容 `moveTo`，逐段返回Path，整条任务结束前Navigation不能因一段耗尽而报告完成；随后补充“`canReach` 可以接入宏观寻路？”本轮具体化上述方向，没有修改生产源码或启动世界。

## 1. 修订后的目标

默认在原版Navigation入口自动接受移动意图，原版Goal/Brain仍调用已有的 `moveTo` / `createPath` / `stop`，不要求实体或模组主动调用新 `requestMove`，不清空或替换GoalSelector。现有Navigation对象保留，避免破坏Goal缓存的引用、地面导航类型判断和实体自定义能力；MoveControl继续负责逐节点运动、转向与跳跃。

一次全程任务保存最终目标、控制来源及请求代次，宏观层提供走廊，详细层在主线程按最多两个组件连接生成当前段Path；每段执行后从真实位置继续。同级后续阶段重新进入已有Scheduler，全服务器共用约4ms软预算，不在单次PathFinder内部暂停恢复。

自动接入分两层：原版调用入口自动兼容；适合现有地面宏观能力的任务使用分段后端。其他原版导航使用受调度的原版后端及对应执行适配，不能把飞行、游泳或攀墙强行解释成地面。显式BYPASS保留原版。自动接管是实施目标，不代表所有体型/门/流体能力或第三方override已经认证。

命令true成为同一服务的预览消费者，命令false保持宏观展示；显式 `requestMove` 可保留为辅助API，但不再是实体使用优化的前提。

## 2. 本轮实际源码依据

来源为本工程Forge 1.20.1-47.3.0、official 1.20.1依赖的实际sources.jar，SHA-256：
`5905C044CBF9D4CD0208F8878BFD7B586782C44366C277D6FBD70E4B075C0EB2`。
本轮从该jar提取114份导航、Path及消费者源码到 `.tmp/navigation-auto-integration-20261012/upstream`，按下表核对关键调用；不使用其他MC版本概括当前行为。

| 实际源码 | 已核对行为 | 接入含义 |
| --- | --- | --- |
| [PathNavigation](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/navigation/PathNavigation.java)，158–191行 | 坐标/实体moveTo先同步createPath，再调用moveTo(Path)；null会失败，零节点也会失败 | 自动入口必须在原始搜索前接受请求，等待Path不能直接走原版moveTo(Path) |
| 同文件，198–222、318–327行 | isDone为path为null或Path耗尽；tick两次判断后直接读下一节点；stop只清path | 全程完成状态与当前段可执行状态必须分开，单改isDone会造成null或越界 |
| [Path](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/level/pathfinder/Path.java)，15–29、40–46、105–124行 | nodes列表、target/reached/dist字段；isDone与节点索引有关；sameAs直接比较内部nodes | 不伪造下一节点；同坐标节点不能替代请求身份；动态元数据需显式实现 |
| [MeleeAttackGoal](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/goal/MeleeAttackGoal.java)，35–81、111–132行 | canUse先createPath，主要检查非null；start再moveTo(Path)；重算失败加惩罚 | 只拦moveTo漏掉首次搜索；PENDING不是失败，Forge惩罚分支需识别分段 |
| [AvoidEntityGoal](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/goal/AvoidEntityGoal.java)，50–91行 | 先createPath，后moveTo；tick改变速度；stop没有navigation.stop | 自动收养预备Path；速度更新延续到下一段；仅拦navigation.stop不足以结束失去控制的任务 |
| [MoveToTargetSink](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/behavior/MoveToTargetSink.java)，33–129行 | null查询会清WALK_TARGET；canReach影响不可达记忆；PATH引用随getPath更新 | 等待不能清目标或写不可达；Brain原生命周期仍保留 |
| [TargetGoal](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/goal/target/TargetGoal.java)，89–129行 | mustReach通过Path.getEndNode距目标判定并缓存 | 分段末端不是最终目标；需宏观到达查询适配，不能放一个假的终点Node |
| [AcquirePoi](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/behavior/AcquirePoi.java)，65–89、97–114行 | canReach为真后用Path.target占用POI；多目标集合附带validRange | 结构结果必须对应实际选定POI，PENDING不能抢票或进入失败退避 |
| [MoveThroughVillageGoal](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/goal/MoveThroughVillageGoal.java)，79–112行 | 临时改变开门能力、查询后恢复、扫描全部Path节点找门 | 查询时能力要快照；这种完整节点检查不能被空的等待Path自动满足 |
| [LongJumpToRandomPos](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/behavior/LongJumpToRandomPos.java)，132–155行 | createPath(target,0,8)用于排除8格范围内可走候选 | 无限距离宏观可达不能替代有界步行检验，PENDING也不能立即触发跳跃 |
| [Mob](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/Mob.java)，742–781行 | GoalSelector→Navigation.tick→customServerAiStep→MoveControl.tick | 导航执行作用域可与Goal请求区分；控制前还可能有其他运动指令 |
| [MoveControl](../../.tmp/navigation-auto-integration-20261012/upstream/net/minecraft/world/entity/ai/control/MoveControl.java)，41–116行 | MOVE_TO通常一次tick后转WAIT；还有STRAFE/JUMPING | 保留原控制器；不能为维持任务活跃而直接驱动最终目标 |

额外核对了原版飞行、水下、两栖、攀墙Navigation、RandomStroll/FollowOwner/FollowMob、Rabbit、ShootTongue、Sniffer、PrepareRam、YieldJobSite、VillagerMakeLove、SetClosestHomeAsWalkTarget、NearestBedSensor、InteractWithDoor及DebugPathCommand等消费者。源码提取数量不是运行认证数，也不证明未知模组使用者完全等价。

## 3. 任务、当前段与对外返回值

每个Navigation最多一个已激活移动任务；此外可有有界的未激活查询。`createPath`只是准备/查询，不能因此替换当前移动、驱动实体或把Navigation标成在移动。`moveTo`收养对应查询或创建移动任务时才取得该Navigation当前意图。

拟新增 `NavigationIntent` 保存导航对象、Mob/维度、控制来源及控制代次、generation、输入目标/最终解析目标、accuracy、区域参数、能力快照、速度及查询用途。宏观完成、窗口完成和段Path全部绑定同一身份；任务成功、失败、外部stop、控制结束、换维度/死亡及关闭都进入唯一终态。同目标去重仅在控制来源/控制代次、导航对象及有效请求参数相同时成立；新Goal接管同一坐标也建立新身份，旧Goal.stop不能取消它。

| 状态 | 对外Navigation.isDone | 当前Path.isDone/节点 | 原版执行 |
| --- | --- | --- | --- |
| 尚未moveTo的查询 | 不影响现有导航 | 查询句柄可能尚无节点 | 不移动 |
| 已接受，等待宏观/窗口/预算 | false | 无段节点时true、count=0 | 不读下一节点、不发导航运动目标 |
| 当前段可执行 | false | 按原版节点索引变化 | 原版followThePath/MoveControl |
| 当前段耗尽、等待实际接续/落地 | false | true | 停止读节点，有限观察和准备 |
| 最后段节点耗尽但真实到达未确认 | false | true | 按实际位置确认或有限恢复 |
| 全程成功、失败或外部取消 | true | 清理本任务Path | 允许原AI终结或发起下一意图 |

`isInProgress()`沿用 `!isDone()`。不能把“仍有任务”解释为“原版tick始终有下一节点”，也不能无限维持false以掩盖失败。Goal自己的距离、目标生死、时间限制、坐骑状态等结束条件继续生效。

受控 `moveTo`返回true表示请求已接受/仍在执行，不表示已经证明可达或已经开始移动；无效输入、队列容量拒绝及已知明确失败返回false。未获接纳的容量拒绝不替换当前任务。已知同步失败保持原重载差别：坐标moveTo原来会调用moveTo(null)清路，实体moveTo的null短路则保留旧路；显式moveTo(null)始终取消。新请求已经接受后替换旧任务，后来失败不自动复活旧意图。BYPASS或没有安全自动适配的自定义导航保持原版返回值，不额外把它们拒绝为“无路”。已接受后发生失败由任务终态/isDone表达。该返回语义需要原版AI代表验证，不能声称与同步即时寻路成功完全相同。

## 4. 分段请求Path的具体载体

拟新增 `NavigationRequestPath extends Path`，作为可由原版API传递的请求Path。它保留最终目标与请求身份，并只保存当前段真实节点，不保存全路线节点。

尚未取得段Path时，nodes为空；不添加原地假节点、不重复末端、不把宏观anchor当作Path终点。取得原版真实Path后，在服务器线程把当前段节点装入由该对象拥有的可变列表，并重置nextNodeIndex。原版advance、replaceNode、truncateNodes及实体偏移仍作用于真实当前段；后续段只对父类构造时传入的同一List做clear/addAll，不更换List引用，也不追加全部历史节点。

用这个真实列表传给Path父类，避免仅做delegate却让原版 `Path.sameAs` 看到始终空的内部列表。`getEndNode()`返回当前段真实末端，无段时null；它不伪装全程终点。显式覆写getTarget/canReach/getDistToTarget，不能沿用构造时的final快照。单目标等待时getTarget保留输入目标，解析后返回最终目标；多目标尚未选定时其占位值没有业务资格，已适配消费者只在就绪后读取选定目标。无真实末端时getDistToTarget为Float.MAX_VALUE，有段时按真实末端相对最终目标计算，不能把结构可达的残余距离写成0。

请求Path显式覆写writeToStream，写一个不改变原版线格式的当前段快照，包含动态target/reached、索引和真实节点；未选定目标的查询不发布调试快照。当前jar的DebugPackets.sendPathFindingPacket本身为空方法，但writeBrain仍有Path.writeToStream调用，不能以“平时没有调试包”为由漏掉序列化契约。没有原搜索debug集合时使用协议允许的空调试集合，不借助包私有setDebug或修改Path的final字段。

查询可以返回一个尚未就绪的请求Path，已知明确失败可返回null；原版中只用非null决定准备移动的调用者可由moveTo直接收养。立即检查末端、距离、canReach或全部节点的消费者必须查询就绪状态或通过下节适配，不能声称一个非null等待Path已经满足其原契约。

`getPath()`在任务激活后返回稳定的请求Path；Brain不因分段切换丢失PATH引用。未激活纯查询不改变getPath。外来普通Path在 `moveTo(Path,speed)` 也被自动接受；无对应查询元数据时，取消被它替换的受控任务，按原版立即赋值/trim/设置速度并登记控制归属，所需交付成本记账，不排队重新搜索，也不创造一个未证明的全程任务。此时isDone回到原节点判定；部分Path按原语义耗尽后结束。空外来Path的赋值及false返回保留原版行为，不能把它误收养成等待句柄。不能从末端猜测第三方完整任务并擅自重规划。受调度原版后端原本允许执行的非空部分Path也仍按其原语义执行/结束；地面分段的中间段才要求达到本段候选，不能把整个原版生态统一改成拒绝部分Path。

受控句柄再次moveTo仅更新速度/确认当前意图；过期、属于别的实体/维度/Navigation或已经终态的句柄不能复活。新目标即使节点sameAs，也要按请求身份替换，不能沿用已耗尽索引。内部发布段Path在raw作用域内处理trim和字段，不能再次走自动提交入口。

## 5. canReach接入宏观层

对普通地面移动任务，推荐 `canReach` 表示“当前宏观查询已得到通向最终解析目标的结构走廊”。它不必等待全部详细段，也不取当前段原版 `Path.canReach()` 作为全程值。原版局部Path的canReach仍供详细服务检查这一段是否成功。

这是显式采用宏观结构可达语义，不是宣称与一次完整原版PathFinder的reached标记逐项相同。宏观走廊不能证明完整节点已产生、路线在原版有限搜索距离内、实体已经实际到达或未来世界不变化；后续详细失败仍可使移动失败并触发恢复。

内部结果必须是 `PENDING`、`REACHABLE`、`UNREACHABLE`、`UNAVAILABLE/STALE/ERROR`。只有同请求身份、匹配实际能力、起终点解析合法且完成最终当前性校验的宏观结果才能发布REACHABLE；宏观anchor回落没有物理终点见证时不能据此批准最终目标。合法见证按原请求accuracy/POI validRange判断，可以位于允许的邻近站立位置，不要求床/工作点自身格一定能站立。宏观未表达的硬禁止能力/特殊约束回到对应原版查询，不能丢掉它们再发布true。未加载、恢复失败、上限或陈旧不是确定无结构路线。

Path.canReach仍是boolean：就绪可达为true；其他状态为false。但原版消费者的适配必须先区分PENDING/不可用，不能让这个false进入失败缓存、删WALK_TARGET、POI退避、不可达目标名单或立即决定跳跃。没有已就绪结果时延后本次业务判定，不阻塞服务器线程join。

拟新增resolveReachWitness：既有worker用当前BaseGraphView中的实际可站立数据枚举终点见证，筛选原请求accuracy允许的位置，且见证必须属于所选目标组件、该能力图中从当前起点结构可达；最终服务器线程在共享预算内核对来源签名、加载状态、实际支撑/身体空间及硬能力。见证记录输入目标、解析目标、实际站立/节点坐标映射、组件身份和能力，不作为Path.getEndNode的假节点；找不到合法见证不能仅凭anchor回落发布REACHABLE。它证明图上的结构连接，不产出全程详细节点。能力或位置映射无法表达时改用原版查询，映射本身仍须定点资格。

多目标查询保留原集合及传入accuracy；AcquirePoi在当前源码先取整个批次各POI validRange的最大值作为一次createPath的accuracy，接入必须保留这一实际输入，不能悄悄改成逐POI更小的容差。该消费者已有limit(5)，适配器不再缩小它；其他Set查询不统一截断为5或8。目标按请求创建时距离、坐标作确定性排序，串行尝试，选择第一个有合法结构见证的目标，不宣称复现原版最短节点数的多目标择优。

每个已接纳queryId分配一个服务管理的独立UUID child owner，并绑定导航/维度/generation/候选索引。串行子查询复用该owner；不存在同一owner同时提交多个候选。每次回复核对索引，选中后取消残余请求/释放临时pin并固定getTarget；取消、TTL、换维度或控制失效亦收尾。一次只派发一个候选，候选总量受输入容量限制；超过容量、worker拒绝、未加载或截止仍未检查完都为UNAVAILABLE，不能把未检查目标记为UNREACHABLE。AcquirePoi就绪消费前重验目标身份/type、当前记忆条件和票据空间；占票依据固定的目标，不另用anchor或集合首项。

TopologyService当前只有单goal入口，拟增加上述有界reach适配，复用已有MacroRequest/worker/完成缓存，不新建搜索线程。所选目标、见证及结构proof作为句柄元数据，原MacroRequest.future契约保留。

普通结构查询复用已有宏观结果和其最终校验；缓存按请求/目标/能力及已有来源身份管理，PENDING去重，已失效输入重新查询，不另建永久世界级Path缓存。已完成Path的canReach和原版一样是计算结果，不能据getter本身承诺每tick全路线重新校验；移动执行仍核对当前窗口及实际状态。

以下查询仍需要有界原版结果或实际节点，进入同一个主线程预算队列：

| 查询用途 | 处理 |
| --- | --- |
| 普通目标选择、POI结构可达、普通移动的全程可达 | 使用宏观REACHABLE和真正选定的最终目标，业务等待适配 |
| TargetGoal.mustReach以末端近目标判定 | 默认保留受预算调度的原版createPath(Entity,0)及真实末端XZ平方距离≤2.25，允许原版部分Path满足此判定；PENDING不写reachCache。此私有canReach不同于普通Path.canReach，不用纯结构结论替换 |
| LongJump的8格步行检验、明确给定有限搜索距离的探测 | 保留原版range/accuracy/节点限额的真实结果；宏观下界过大可拒绝，但下界小不能证明有界可达 |
| ShootTongue等根据末端残余距离决策 | 使用真实有界末端；不能把结构可达的距离写成0 |
| MoveThroughVillage启动前扫描全部节点找门 | 保留原版元数据查询或补等价的段发布门处理；首选前者保证既有逻辑，不用空节点跳过门检查 |

受调度的少量原版查询也可能原子超出4ms，应记录其用途与耗时。原型中的分段收益不能自动转为这些查询的性能证据；若它们成为尾部热点，再针对实际类型处理。

查询用途由已核对的有限调用作用域明确标记，分为可收养的准备型与等待后决策的查询型；不能用全局布尔或猜调用栈分类。未识别的第三方直接同步createPath默认走LEGACY_SYNC_QUERY：保留原版同步结果、参数及部分路径返回，不返回PENDING假装同步结果，也不join队列。它的原子主线程成本扣同一服务器预算，但因同步返回要求不能延后启动，是明确统计的兼容例外，不享有END排队的平滑保证。第三方直接moveTo仍能自动受控，无需注册新API；第三方create→move若未识别查询契约则保留此原版查询与外来Path执行，分别报告覆盖。

## 6. 入口与原版AI自动适配

| 拟接入点 | 明确责任 |
| --- | --- |
| PathNavigation.moveTo(x,y,z,speed) / moveTo(Entity,speed) | 在原同步搜索前捕获目标意图，去重/替换，接受后立即返回；实体目标保留身份及最新位置 |
| PathNavigation.moveTo(Path,speed) | 收养请求Path；外来Path按来源执行；null取消旧任务并返回false；同请求只更新速度 |
| createPath所有重载及共同内部入口 | 捕获输入、准确度、范围、regionOffset、upward、maxVisitedMultiplier和有效能力；准备查询，不自动移动 |
| GroundPathNavigation公开createPath | 在原目标列处理前捕获，预算内进行原版一致的加载安全归一化，避免前置扫描绕过队列 |
| Navigation.isDone/isInProgress/getTargetPos | 对外表达全程生命周期和最终目标，绝不把阶段目标暴露成新的业务目标 |
| Navigation.tick内部局部状态检查 | 用当前段是否有有效下一节点判定；原版步进/碰撞/MoveControl保持 |
| recomputePath/shouldRecomputePath | 受控任务转同一队列，合并重复，保留原版重算节流；不重新同步长搜索 |
| setSpeedModifier、stop及导航对象变化 | 速度延续；外部stop终结全程；原版段卡住转有限恢复；对象替换清理旧任务 |
| WrappedGoal调用作用域与stop | 覆盖canUse/canContinueToUse/start/tick/stop，自动记录控制来源及控制代次；Goal结束时取消其任务，即使Goal.stop没调用navigation.stop |
| MoveToTargetSink和纯查询消费者 | 提供PENDING适配、Brain记忆规则、受限查询用途和生命周期；无需逐个实体注册 |

MeleeAttackGoal、AvoidEntityGoal一类“先create再move”使用预备请求Path，moveTo时收养并提升已有查询优先级。Forge的Melee失败惩罚分支必须跳过PENDING及中间段末端，不能因末端未到最终目标持续加罚。RandomStroll/Panic/Follow/Tempt/Breed等直接moveTo自动进入服务。

MoveToTargetSink在PENDING时保留WALK_TARGET，进入受控等待，已有行为时间/目标失效/stop条件仍有效；首次Path可执行后使用原PATH逻辑。宏观结构失败与等待分开处理CANT_REACH记忆。阶段变更保持请求Path身份；任务失败或取消后由原业务结束清理。

AcquirePoi、YieldJobSite、VillagerMakeLove、SetClosestHomeAsWalkTarget、NearestBedSensor、TargetGoal、Sniffer以及Ram/LongJump/ShootTongue等立即消费可达/末端的调用，按照用途表自动适配查询就绪。它们不是靠boolean false延后：适配器先读取显式就绪/接纳状态，只有已就绪结果才进入原决策分支。PENDING、容量拒绝及UNAVAILABLE不消耗失败配额、重置候选或抢资源；明确结构失败或已完成原版失败才走原失败分支。

拟新增有界PendingDecision记录，键为Mob/Navigation、消费者实例或lambda所属闭包身份、控制代次/queryId；保存原候选集合/位置、能力、创建时间、已发生的随机/批缓存更新和查询用途。AcquirePoi的OneShot局部集合需迁入这个记录，就绪后在原业务重试时机续用，不能靠重新运行过滤器找回；NearestBed/Home同样续用已写缓存的原批次。LongJump保存已取出的当前跳跃候选及它的8格步行查询，就绪前不继续消费或确认跳跃，也不延长原Behavior时限。ShootTongue/MoveThroughVillage保留真实结果后再决定原末端/门逻辑。记录随消费、TTL、候选失效、控制结束/死亡/换维度/关闭释放，不再由完成回调直接占POI或触发跳跃。

上述消费者的状态载体及作用域是自动接入的一部分。适配器尚未取得资格时，该用途默认走LEGACY_SYNC_QUERY并明确计数；不把未经适配的原版决策消费者暴露给空PENDING句柄。无需逐实体注册，未知第三方同步判定按上一节的兼容策略处理，不能承诺任意同步契约同时获得异步等待收益。

DoorInteractGoal及Brain的InteractWithDoor继续读取真实段Node；Path.isDone在段间等待为true，保护下一节点访问。开门记忆与控制生命周期保留，分段切换不清掉DOORS_TO_CLOSE；补一个跨段开/关门代表。原版debugpath构造临时GroundPathNavigation并立即报告，不能因PENDING输出成功/失败；临时诊断导航需明确采用延后完整结果或原诊断旁路，不能把它登记为Mob正在执行的Navigation。

原版AI运行时已经通过GoalSelector持有MOVE互斥。本服务观察/继承它，不能要求额外手工lease，也不因一次未激活查询抢MOVE。WrappedGoal.canUse阶段查询、start/tick阶段移动、stop阶段终结通过有限调用作用域自动记录；作用域用try/finally清理，不通过逐tick调用栈猜来源。

Brain的MoveToTargetSink采用行为实例与WALK_TARGET身份；其他已适配的Behavior.start/tick/stop、BehaviorControl/OneShot闭包使用各自有限作用域，PrepareRamNearestTarget的直接stop亦核对归属，不能假定所有Brain请求都来自MoveToTargetSink。未识别的外部moveTo按Navigation最新意图负责，未知外部stop保持显式取消。旧Goal/Behavior作用域中的stop只清属于该来源及控制代次的路径；stop方法内部原清字段也必须受归属保护，不能先清新路径再在尾部检查。Goal自己的优先级替换和结束不会因服务持有最终目标而被覆盖。

### 6.1 决策查询的确定接线契约

内部适配器使用DecisionQuery/queryId取得结果，pollDecision返回显式PENDING、READY、UNAVAILABLE或CANCELLED，只有READY带业务可消费的结果。READY的宏观载荷区分结构可达/明确无路，原版载荷保留真实Path或一次已完成的原版null；原版部分Path不因canReach=false丢弃。容量拒绝、超时和未加载为UNAVAILABLE，不能伪装READY(null)。此接口只供自动适配器使用，原版方法签名及实体调用方式保持。

原型与最终接线选用“END队列执行、消费者跨调用延后”，不是让TargetGoal/LongJump在普通模式inline搜索。资格尚未成立时，该用途明确切换LEGACY_SYNC_QUERY，保留原版同步分支并统计例外。一个用途在一次请求中不能既声明已异步排队又期待同步真实末端；模式随queryId固定，迁移或取消后才改变。

| 源码控制位置 | PENDING/UNAVAILABLE时 | READY时与恢复位置 |
| --- | --- | --- |
| AcquirePoi.create中实际触发OneShot的lambda，候选集合生成后、findPathToPois调用之前 | 已接纳PENDING捕获集合/聚合accuracy/闭包身份，创建PendingDecision；取消该次lambda剩余执行并返回true，整段path.canReach及失败退避分支不执行。UNAVAILABLE释放不能完成的查询/批次记录，跳过失败分支，按原重试时机重取候选，不保存空未来结果。不是把findPathToPois改为null后仍落入else | 在同一lambda业务门槛和原重试时间允许后、重新过滤/生成候选之前取回PENDING的原记录；原记忆前提/type/空间失效则取消。READY可达使用其请求Path进入原成功分支；READY明确全批无路才进入原JitteredLinearRetry分支 |
| MoveToTargetSink.tryComputePath，调用createPath并写path/speed后、不可达记忆分支之前 | PENDING的已接纳准备句柄赋给this.path，返回true，让原checkExtraStartConditions/start收养、保存WALK_TARGET/PATH；跳过CANT_REACH写入。UNAVAILABLE保持目标，延后本次启动 | 在checkExtraStartConditions/canStillUse/tick的业务检查前消费同一WALK_TARGET身份结果：结构可达清CANT_REACH，确定失败写原时间记忆后按原行为结束；已达到目标按原reachedTarget规则处理。已接受后的失败不自动复活旧任务或循环启动随机候选 |
| TargetGoal.canAttack的新目标选择调用，目标条件/限制已通过、reachCache==0且将调用私有canReach前 | 用独立决策queryId提交真实createPath(Entity,0)，取消本次canAttack剩余执行并返回false；保持EMPTY_REACH_CACHE，不写2，也不提前抽取reachCacheTime随机数。不是无条件改写任何canAttack调用 | 同一位置消费原版真实结果，按实际末端XZ平方距离≤2.25判断，保留部分Path可通过；此时才写1或2及原随机缓存时限。跳过私有canReach再次搜索；此前目标条件每次仍正常检查 |
| LongJumpToRandomPos.pickCandidate，createPath(blockpos,0,8)调用前 | 保存已移出的PossibleJump、计算出的向量及原位置；取消本次pickCandidate，不设置chosenJump/prepareJumpStart。等待后续tick不再抽候选或重复扣findJumpTries，但原Behavior时限/初始位置/目标合法性条件照常终止 | tick/pickCandidate开始处先处理待定候选；重验实际起点/落点和既有行为条件。真实原版Path.canReach=true则走原continue；READY(null)或真实部分Path不可达才按原逻辑选择跳跃，prepareJumpStart取就绪结果消费时刻。UNAVAILABLE不作为可跳证明 |

MoveToTargetSink.checkExtraStartConditions还须在eraseMemory(WALK_TARGET)前检查本次显式UNAVAILABLE；未达目标而仅因未接纳/不可用返回false时取消剩余分支并返回false，保留目标，不让父方法先删后补。READY失败/原生失败及真正已达目标仍按其原结束规则，不能一律拦住清理。

LongJump.canStillUse在原候选存在条件中增加同一行为/实体代次仍有效的待定候选：chosenJump != null || !jumpCandidates.isEmpty() || hasLivePendingJump。只扩展这一子条件，保留initialPosition、findJumpTries>0、水状态及Behavior原200tick上限；tick中等待分支先于findJumpTries递减。因getJumpCandidate已移除的最后一个候选不会仅因PENDING令行为终止。READY可步行后消费该候选并回原选取循环；真正失效/TTL/停止释放它，UNAVAILABLE按有界重试把仍合法候选交回候选集合，不能直接证明可跳。未达原业务终止条件时，PENDING不执行原失败冷却或清LOOK_TARGET；原时限/位置/尝试上限真正触发时仍正常终止。

TargetGoal的返回false延后仅开放于已核对的新目标选择/canUse作用域；WrappedGoal作用域同时记录方法阶段。若canContinueToUse或未识别的外部上下文调用这个判定，默认LEGACY_SYNC_QUERY保持原即时判定，不能因等待false丢当前目标。第0项须核对当前jar的调用者及实体内override，不假设方法只能由findTarget调用。

AcquirePoi READY请求Path的getTarget明确返回选中的原POI位置，见证站立位置仅在reachWitness元数据内；原成功分支读取getTarget和take的同一blockpos无需另改成见证坐标。发布READY前固定所选目标，之后到消费结束不改指向其他候选。未选定的句柄不会进入成功分支，也不会序列化。

以上采用当前Mixin可取消的定点注入/必要的局部控制替换，不抛控制流异常、不靠把PENDING的canReach设成true或false跳分支。AcquirePoi合成lambda及局部变量、TargetGoal缓存写点、LongJump计数位置的实际字节码描述符和捕获须在“第0项接线核对”确认；required失败不得继续开启该用途。ShootTongue/MoveThroughVillage及其他待定批次消费者在完成同类控制流资格前保持LEGACY_SYNC_QUERY，之后在失败写入/门扫描之前延后，不能先全局返回PENDING再补救。

## 7. 执行安全、重复请求与原版语义

### 7.1 两种isDone必须分离

对外逻辑为“没有活动全程任务则原版状态，否则任务终态”。局部可执行谓词固定为path != null && !path.isDone()，每个判断点重新读取path，不能沿用tick HEAD的快照。原版导航tick内两处判断均替换；followThePath刚advance掉最后节点，或doStuckDetection刚stop置null后，第二处必须立即停止，既防越界也防空指针。

| 调用点 | 受控判据/动作 |
| --- | --- |
| PathNavigation.tick首次/再次isDone | 均取上述局部可执行谓词；第二次重新检查实际path |
| FlyingPathNavigation.tick首次/再次isDone | 独立两点同样适配；不能仅注入base |
| WallClimberNavigation.tick分支 | 当前真实Path未耗尽则原super.tick；等待首次搜索时不进入攀墙；原版搜索已返回null或Path耗尽后，由独立fallback-ready且pathToPosition非null进入原攀墙执行 |
| 原moveTo(Path)的isDone/零节点检查 | 请求句柄由收养入口处理；普通外来Path原判据及返回语义保留 |
| recomputePath及延迟重算 | 活动任务合并入同一队列，保持20tick节流；终态受控身份禁止再次提交 |
| Rabbit/Door/Brain等外部节点/任务读取 | Path.isDone是当前段；Navigation.isDone是全程，不全局换成局部值 |

攀墙fallback包含“非null但耗尽”的原版路径情形，不能仅改为path == null。该类型首次搜索的accuracy保留原moveTo(Entity)的0；真实攀墙到达、原fallback结束或受控取消再结束全程。纯查询不激活fallback；pathToPosition及残留控制指令只在本任务拥有时清理。

Path.isDone继续表示当前节点耗尽，不改成全程未完成。Rabbit等实体直接检查Path.isDone后读取下一节点，保留这个契约才能在等待段时安全。纯查询Path未激活时也不能影响原版执行。

用有范围的raw/执行作用域区分原始局部搜索、段发布和原版导航步进；base tick的定点调用替换与末段耗尽控制均需实际字节码核对。局部状态替换只作用于导航方法中已核对的判断点，不能在整个Mob执行作用域把isDone统一改为局部值，否则重入的外部消费者仍会误判全程结束。Flying覆盖tick，WallClimber还在无Path时驱动攀墙，不能假定一个base注入自动覆盖它们。

### 7.2 stop、重规划与运动

外部 `Navigation.stop()`、moveTo(null)、控制来源结束或新目标明确替换取消全程任务。原版doStuckDetection/timeoutPath内已核对的直接stop调用点属于段停止，记录恢复意图；不把整个Mob作用域内的任意重入stop视为卡住恢复。受控终态清理其拥有的path/targetPos、reachRange、hasDelayedRecomputation及相关超时/攀墙状态，回调和recompute入口再核对身份，防止下一tick复活；未受控原版导航不一律改变其stop字段语义。

请求接受时getTargetPos保持非null的最终解析目标；内部段搜索对targetPos/reachRange的写入不能覆盖全程值。StrollThroughVillage等会比较getTargetPos与业务目标；值若变成阶段目标会提前退出。段参数、当前段目标及局部reached单独存放。

重复相同目标moveTo更新速度而不取消重新排队；setSpeedModifier也更新任务速度。实体目标以实体身份为主，最新位置用于下一次派发/下一段；必要偏离触发有节流的重规划。不能每次Goal重发moveTo就增加generation、丢掉宏观结果并排回队尾，否则追逐永远没有首次Path。

最终目标真正更换时产生新generation，旧窗口/结果拒绝；同一实体目标的小幅移动按意图位置更新处理，不每tick摧毁当前仍有效的段。发布前核对最新输入；实体目标死亡/换维度或最终合法性失效时明确结束/恢复。

原版MoveControl保留。等待时导航不再下发下一节点；若确需清理上一段残留MOVE_TO，只清理由该导航最后发布且仍未被其他控制覆盖的指令，不清STRAFE/JUMPING、不锁位置/速度/重力。Mob在navigation.tick后还调用customServerAiStep；外部新运动不能被误删。不能把最终目标直接交MoveControl绕过路径碰撞。

### 7.3 参数与到达

坐标/实体/集合入口保留原始目标形式及accuracy、搜索范围和能力意图。移动默认accuracy按原版moveTo的1；createPath传0或其他值继续保留，不能全部改成1。Ground原本的目标列归一化在预算内按已加载状态执行，输入位置、解析位置和宏观终点分别记录，不再使用上一版仅接受脚部格的外部契约。

能力快照必须包含实际体型、姿态、步高/落差、evaluator种类、开/穿门、漂浮/翻栅栏及malus，包括FollowOwner的水malus。MoveThroughVillage在查询后恢复门标记，这些查询意图不能被延迟派发时的新值抹掉；读取实际NodeEvaluator能力，不把Ground公开getter默认等同于底层同名flag。

每Navigation设置原子搜索占用保护，查询/移动串行使用其同一个PathFinder/NodeEvaluator。执行前保存当时实际flags与Mob malus，把已捕获查询flag/malus临时应用；raw原版搜索和必要清理后在finally恢复执行前值，即使null、异常或取消亦配对。同一Navigation重入搜索只延期或报告UNAVAILABLE，不在仍占用evaluator时另开原版调用；未知同步重入也不能偷偷破坏正在运行的搜索。当前物理体型/姿态/步高不伪改为历史值，已变化则作废/重捕获并检查资格。原版prepare/done有getStart为null及异常的缺配对出口，包装需跟踪实际prepare/done和profiler进入/退出，补缺失清理而不重复done；外层能力finally不能代替这项检查。

原版真实查询在raw适配器中还要避免createPath的当前Path复用捷径返回受控中间段或空句柄：搜索期间暂存/隔离本导航受控path及targetPos/reachRange等副作用，取得真实结果后恢复活动任务字段；原版返回的完整/部分Path作为查询载荷，不直接改getPath或抢当前意图。外来原生Path的正常复用可以保留。raw作用域只避免自动捕获与受控缓存污染，不绕过加载安全、能力清理或计时。

现有地面原型的16384节点、512范围和8目标是诊断参数，不是所有原版请求的公共默认值。实际接入明确区分调用者给定的有界查询与宏观分段局部范围，保持业务accuracy/能力，不用全局增加FOLLOW_RANGE或改实体属性扩大支持。

阶段完成需要真实稳定位置、合法支撑/身体空间及可接续的当前组件；Path耗尽仅触发复核。保留已知两落点问题：楼梯目标(-22,95,-212)落地Y=96、城市反向目标(90,93,-262)落地Y=95。Node高度、Path执行水平偏移和实际支撑不能混为一个坐标；最终到达按调用者accuracy及业务位置/实体目标规则核对，不因宏观canReach=true就结束。

中间阶段按实际进入目标组件推进仍为定点候选，需证明真实归属与下一段可接续；最终目标不能只用同组件替代。原型位置回放、强置onGround、禁重力不进入实体生产执行。

## 8. 原版导航覆盖与降级矩阵

| 实际导航 | 默认自动路径 | 需要保留的特性 |
| --- | --- | --- |
| 原版GroundPathNavigation及无相关override的继承者 | 能力可由宏观地面事实表达时分段；否则受调度原版后端 | 目标归一化、门/水/栅栏/malus、避阳trim与真实体型 |
| WaterBoundPathNavigation | 受调度原版水下后端；水下分段另证 | SwimNodeEvaluator、海豚breaching、水中位置/控制 |
| AmphibiousPathNavigation | 受调度原版两栖后端；不伪用纯地面走廊 | 水陆混合能力及getGroundY/getTempMobPos |
| FlyingPathNavigation | 受调度原版飞行后端及其独立tick适配 | FlyNodeEvaluator、三维运动、自己的两次isDone检查 |
| WallClimberNavigation | 原版搜索及无Path时攀墙fallback的专用自动适配 | moveTo(Entity)的true/fallback、pathToPosition、无节点时仍可能合法运动 |
| 实体内自定义Navigation或第三方override | 能证明调用受支持入口则自动；否则明确保留原版/配置BYPASS并报告覆盖 | 不替换未知evaluator或吞掉额外移动行为 |

“受调度原版后端”仍经过同一任务/预算入口，不要求业务改调用方法；局部搜索原语及执行保持该类原版逻辑。它不是宣称这些导航已经有地面分段收益，单调用仍可能超软预算。专用执行适配取得资格前保留该类原版/明确BYPASS，不能一部分拦入口而遗漏其tick语义。

本轮实际检查还存在Bee、Turtle、Drowned等原版实体自定义导航/切换路径。实施前按当前jar的override清单补对应入口覆盖检查，不能只凭 `instanceof GroundPathNavigation` 放行WallClimber等额外语义。任务绑定实际Navigation对象；导航字段更换时先终结旧对象任务/查询并释放引用，不把旧evaluator/Path移给新对象，后续原AI调用在新对象重新接受意图。自动接入覆盖与分段资格分开统计，原版回退亦需记录，不能将回退实体计成分段成功。

## 9. 线程、资源、窗口与预算

Service每服务器一个；业务状态、请求Path、实际实体访问和原版PathFinder均在服务器线程。宏观/小窗口组装复用现有TopologyService/Runtime/TopologyTaskExecutor，worker只读不可变图，不调用实时Navigation/MoveControl。无服务器线程join、无第二份详细worker/预算队列。

`MacroRequest.future()`保留；拟新增reach元数据与 `prepareGroundSegment`。窗口按当前位置及最多两个组件连接获取少量BaseGraphView，最多4个不同区段；检查ComponentEndpoint.revision实际为Base.signature，不能与section事实version直接比较。boundary候选消费band/mask；local候选需当前组件数据，不用anchor替代。

业务窗口传播priority；当前requestBaseGraph的BACKGROUND默认保留给导出。Base视图就绪后在既有worker枚举，不能在runtimeLock内扫4096格；正常/取消/异常/迟到完成均关闭临时pin。请求只持当前窗口及真实当前段，不pin整条走廊。

未激活查询与活动移动分别有queryId/控制身份。TopologyService以维度+owner替换请求，不能让一个可达性探测使用同实体owner偷偷取消移动宏观请求；查询child使用独立有限owner，收养后转同一业务身份并取消冗余child。

Navigation最多一个已提交详细原子任务，准备查询只保存有界意图和宏观句柄。建议每导航最多2个未激活查询、活动与查询总量共1024；PendingDecision、子owner和已完成未消费结果计入同一记录上限，不另开无限map。同输入去重，有明确TTL/控制来源结束清理。pure查询不得替换活动Path；不能无限创建每次canUse的句柄。容量/TTL拒绝为UNAVAILABLE而非无路；失败缓存不因此更新。容量只是保护，不是1024实体吞吐认证。

每阶段新submitSoft，提交单元是一整个不可抢占原版findPath；不是可恢复A*，不依赖MAX_EXPANSIONS_PER_SLICE把它限制到64节点。原版有界查询亦用同一Scheduler，按任务用途记录。既有submitStrict/ResumableSearch接口保持，为其已有调用者服务，不由本方案复制或退役；源码已在submit中取消同键旧任务，不能再声称缺失替换。

START额度拟为tokens=min(4ms,tokens+4ms)，正余额不积累至当前6ms，负债保留；受控任务只在END派发，45ms余量和haveTime条件保留。wake只入队，不在其他时刻另开详细派发窗口。ResumableSearch.park当前已约定回调必须在方法返回后触发；保留这个契约，验证正常异步唤醒、取消后唤醒及END门控，不把违反契约的同步回调假设当作当前已证实缺陷。本方案原子详细调用不park。

原子预算计入目标归一化/候选准备、真实状态检查、Region/evaluator/search/done、Path节点交付/trim、发布和future.complete触发的同步回调。调度的一次派发从takeNext起覆盖step、park/重排或终态发布到回调返回，在最外层finally扣一次；调度扫描/队列维护余量亦计入该次drain账，无派发也不免账。嵌套记账只计区间并集，不能再次扣同一elapsed；扣账后重新检查tokens及deadline，超支停止后续派发并带负债。

回调不直接开新详细搜索或递归drain，只校验身份、标记就绪/提交后续有界工作；外部观察、请求接受/清理、外来Path交付及就绪回调主线程成本同样记账。必要立即取消/清理不能因零额度延后，记录实际成本；候选枚举/碰撞复核等可延期重工作进入END队列。LEGACY_SYNC_QUERY按前述策略inline扣账，单列绕过延后保证的成本。保持唯一服务器时间所有者，不复制PathFinder循环做强行抢占。

有限恢复仍为更换候选→缩一连接→至多一次宏观刷新，调用/重绑上限不因同目标重发或刷新自动清零；规划等待与物理无进展分别计时。明确失败后对外isDone=true，并遵循原AI的重试节流；不能每tick自动创建新的失败全程任务。

未知区域防护沿用原子读取作用域，但绑定实际Region；block/fluid/collision读取一致保守，不把EmptyLevelChunk当空气、不强载补路。目标归一化、候选/trim亦检查加载状态。服务异常清理上下文及引用，并核对原版prepare/done与profiler配对，不能仅凭外层finally宣称原内部异常清理完整。

停止时先拒绝新自动请求/查询，终结任务并清桥接，再关闭拓扑和Scheduler；迟到回调、wake和forServer不能复活业务。Path及控制字段仅在身份仍归本任务时清理；未激活查询超时单独释放。

## 10. 拟实施模块

| 模块，尚未存在的标为新增 | 责任 |
| --- | --- |
| 新增navigation/AutomaticNavigationBridge | 原版入口、raw/执行/控制作用域、任务采用/替换、对外与内部状态 |
| 新增navigation/SegmentedNavigationService | 宏观/窗口/段搜索、任务与查询代次、真实接续、恢复和清理 |
| 新增navigation/NavigationRequestPath及意图/就绪数据 | 原版Path签名载体、当前真实nodes、最终目标与结构可达元数据 |
| 新增按原版类型选择的NativeNavigationSearch适配 | 同步原版搜索原语，真实能力/有界参数，不复制搜索循环 |
| 新增topology/GroundSegmentWindow；修改TopologyService/Runtime | 小窗口及reach查询元数据、优先级、pin、最终当前性校验 |
| 修改NavigationScheduler/AcceleratedNavigation | 唯一预算、完整计时、派发时机、观察及停止 |
| 新增Navigation入口/局部tick/Mob执行作用域Mixin | 捕获moveTo/createPath，安全分离isDone，保留实际Navigation对象 |
| 新增WrappedGoal及MoveToTargetSink/查询消费者定点适配 | 自动控制生命周期、PENDING不触发业务失败、特殊查询契约 |
| 新增Region读取适配、必要MoveControl命令来源桥接 | 加载安全及只清理本导航残留指令 |
| 修改NavigationCommand、mixins清单及必要能力策略 | true预览、false回归、原版类型自动选择、BYPASS |

以上按责任划分，不要求每个消费者都造独立框架，也不在本轮升级Mixin依赖。实际注入方法描述符、继承override和MixinExtras现有能力须在实现前对应到当前字节码；required注入不能用“没命中就算回退”掩盖覆盖缺失。

## 11. 定点确认、实施与最小验证

| 阶段 | 具体工作与完成条件 |
| --- | --- |
| 本轮源码细化 | 自动入口、请求Path、两种isDone、宏观canReach、原版消费者及类型矩阵已明确；不称运行通过 |
| 第0项接线核对 | 按6.1锁定显式就绪载荷、AcquirePoi合成lambda/局部变量和取消返回及UNAVAILABLE释放、Sink清目标前保护、TargetGoal写缓存前保护及新目标/继续使用调用阶段、LongJump最后待定候选/继续条件/计数恢复；核对当前字节码/Mixin能力，未命中不开放异步用途 |
| 兼容性定点原型 | 先四个小闭环：Ground的null/空节点/同tick耗尽；Melee或Avoid准备型加AcquirePoi决策型；完整预算及回调/END；多POI串行owner/见证/选定目标。未覆盖类型保持兼容回退，再增量确认Brain/特殊导航/受限查询，不一次铺开全部Mixin |
| 实体落点原型 | 复用已知两失败定位支撑/目标映射，四方向单实体再8实体；不靠传送、强置状态或放宽容差 |
| 生产接线 | 按模块表接入原版入口及查询消费者、类型适配与唯一预算；编译/现有契约/受影响控制 |
| 实际接入验证 | 原Goal/Brain发起任务并走完全程；动态目标、受阻、替换、取消、区块不可用和关闭正确 |
| 完整背景负载 | 正常AI与动态宏观/区块活动，计入END尾部；分别报告自动接入/分段/原版回退、查询/移动成本及等待 |

最小新增控制：

- 未激活createPath不改变当前导航；Melee/Avoid的create→move链无旧同步搜索旁路，同输入去重。
- 首段等待及段間耗尽Navigation.isDone=false，Path.isDone=true；最后节点advance后同tick不读越界节点，Rabbit读取安全。
- 普通canReach取最终宏观结果，PENDING不写失败；TargetGoal保留原版有界真实末端判定及部分Path语义；多POI仅选中结果占票，NearestBed/Home的待定批次不被缓存过滤掉。
- LongJump保留8格限制，最后一个待定候选计入canStillUse，不因等待耗尽候选或额外扣尝试，原时限/位置/水状态仍可结束；ShootTongue真实末端距离，结构可达不抹掉这两个语义。
- MoveToTargetSink不因等待丢WALK_TARGET/PATH；真正取消/失败结束；原Behavior时限仍生效。
- Goal.stop没调用Navigation.stop时也清理其任务；旧Goal.stop不清新Goal路径；native段卡住与外部stop区分。
- 同控制代次重复moveTo和改速度不饿死首次搜索；同目标的新Goal接管建立新身份；移动实体目标、小范围位置更新与真正更换目标分别处理。
- 坐标/集合/实体accuracy、门临时flag、水malus、归一化和trim无能力偷换；getTargetPos不泄漏阶段目标；跨段门记忆及外来部分Path语义保留。
- 五类原版Navigation及实体内override按矩阵核对，显式回退不计成分段认证。
- 4ms正余额/负债、同步回调区间并集记账、END唤醒门控、跨维度/阶段公平、查询owner不碰撞、容量/TTL与停止零遗留；未知同步查询及未取得资格的消费者明确回退计数。

既有132条离线分段链和并发预算证据复用；不重做已有效的分段/分片对照或Store/几何全矩阵。新增自动入口/Path/Goal/Brain契约必须增量验证，不能把原型静止回放和手工requestMove当成自动接入通过。当前开发环境的8个coremod映射错误在接入运行前核对，不直接升级为生产资格。

## 12. 成本与当前未决

新增常驻成本为有界查询/活动任务、当前窗口、一个当前段nodes列表及控制/就绪元数据；不缓存完整全程节点。宏观查询数量需去重，尤其多POI与反复canUse；保留实际分配、GC/JIT和单调用尾部，不从记录中扣掉。共享预算平滑多数启动时机，不保证任意单次原版探测硬限制为4ms。

当前待定点锁定：请求Path空节点及同tick切换的注入安全；宏观终点与原业务accuracy/能力对应；PENDING消费者的状态保存；五类导航及原版实体override的受控执行；已知真实落点与恢复参数。它们是自动接入的具体实现/资格项，不要求重新选择分段架构。

当前设计结论：接管Navigation的任务生命周期并逐段交付真实Path；对外保持全程进行中，内部始终按真实当前段安全执行；普通canReach消费宏观结构结果，特殊有界/节点查询保留其含义。下一动作是上述定点原型，随后生产实现与接入验证。
