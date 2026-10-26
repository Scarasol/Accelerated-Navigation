# 长距离详细寻路前的计算成本空间复核

日期：2026-10-23。

范围：用户提出详细搜索足够便宜后，可扩大单次路径长度，最终一次生成完整路径；
本轮据此重新评估计算性能是否可以验收，并继续只读研究下降空间。
未实现、构建、启动游戏、补跑基线或调用外部模型。

## 1. 判断

当前短段搜索已有成本改善，但尚不足以结束计算成本研究，
或宣布已经具备长距离、全程详细搜索的性能条件。
这不是已经测得长距离搜索不达标，也不以项目到达增量200ms目标倒推单次搜索门槛。

NS原35ms六场相对U搜索wall/CPU/分配合计下降35.02%/31.25%/6.13%。
这些是原分段跨度下的一次观察，不能证明扩大长度后的展开量与完整查询成本。
自然窗CPU增加5.50%的原因仍未闭合，不把它直接认定为新实现的计算退步。

后续顺序保持：继续研究计算成本下降空间，再把获得的余量用于更长路径。
减少计算成本可能间接帮助减少交接和局部终点选择；
不能仅用当前短段搜索耗时很小，就排除这种结构收益。

评价还必须检查降本幅度是否足够，而不只检查扩大范围后成本是否增长。
“相对原版有所改善”“改善值得保留”和“余量足以支撑目标跨度”是不同结论。
NS的35.02%搜索wall降幅，在工作量与成本线性、其它成本不变的简化条件下，
对应相同原搜索耗时可承受约1.54倍工作量；31.25%搜索CPU降幅对应约1.45倍。
这些仅是已有成本比例的算术换算，不是展开量、路径长度或完整查询的实测扩展能力。
分段合并还会减少重复准备，也可能增加无关展开，不能据此推出可将跨度增加54%。
现有幅度值得保留，但尚不能据此认定足以支持大幅扩长或全程一次成型，
也不能反向宣称已测得该幅度必然不够。
后续成案需结合目标跨度、实际新增工作和完整成本判断余量，
不凭空规定一个提速百分比，也不把200ms整体到达目标当作本分支计算门槛。

## 2. 已确认的成本边界

现有search统计包住原PathFinder，不覆盖整个NativeNavigationSearch.find。
临时能力捕获/还原、PathNavigationRegion构造和返回时NativeQueryProof.capture
均不能自动计入当前搜索收益。
完整查询必须区分准备、节点展开与询点、回溯和外围收尾；
目前没有足够现成分账把剩余CPU唯一排到这些函数。

原PathNavigation围绕起点按range与offset创建读取区域。
PathNavigationRegion先获取覆盖矩形内所有chunk列引用。
扩大半径时，水平矩形面积增长；实际准备成本取决于区块数和后续检查，
不能假设只与最终路径节点数成正比。

NS的NativeQueryProof.capture每次遍历region内chunk槽位，
为每项创建Entry和WeakReference，再保留列表；超过256项返回null。
这既是可能随范围增长的工作，也是未来扩长时必须核对的现有限制，
不能仅删上限或取消证明便宣称支持长距离。

## 3. 优先继续研究的主方向

### 3.1 按状态复用可证明静态的原始路径类型

原生pathTypesByPosCache缓存某个坐标处、结合实体尺寸和能力计算的完整锚点类型。
但首次求值及不同坐标仍会进入getBlockPathTypeRaw。
该函数重复读取状态，经过方块钩子、标签、流体钩子、方块分类和可寻路判断。

值得进一步成案的是：对可证明只由BlockState和标签语义决定的原始结果，
研究按状态复用；保留实际世界取块和下方支撑、邻域危险、实体能力等后续流程。
它与新增坐标类型缓存、额外27格认证位图不同，不能混同此前已暂缓的方案。
NS减少邻域扫描，尚未自动消除这条原始分类链。

已复核Forge默认getBlockPathType仍调用state.isBurning(level,pos)。
因此只确认外层方法为默认实现还不够，还须核对内层provider。
默认isBurning仅比较FIRE/LAVA，但第三方覆盖可能依赖世界或位置。
isPathfindable也可能覆写；默认LAND分支使用碰撞满方块判断，
其中静态shape缓存与动态shape路径必须分清。

研究要求是证明所跳过的整条链没有动态依赖和可观察副作用，
而不是在假世界调用成功一次就当作纯函数。
未知钩子、动态shape和未闭合的读取链保持原流程；
标签刷新、首次分类、失败探测和状态字段内存都算成本。
目前只确认存在不同坐标重复分类的机制，没有命中率或收益份额，
尚未决定它已达到下一主候选的实施条件。

### 3.2 合并读取路径中的重复定位

NavigationRegionMixin的unknown检查先定位region内chunk并检查实时加载状态；
检查通过后原PathNavigationRegion.getBlockState/getFluidState再执行区块定位。
源码上的重复已确认，是否被JIT消去及所占份额未知。

继续研究能否复用定位结果、改善读取布局，同时保留原未知区块语义与支持的注入链。
此前暂缓原因仍有效：直接在HEAD快返或旁路section读取可能跳过其它Mixin。
不通过扩大方法覆盖或假定所有读取钩子无副作用来省略这一问题。

本轮复用了此前已冻结的Lithium1.20.1源码进行对照。
它展示了状态分类复用、扁平chunk布局和section读取的工程切点，
不提供本项目Forge链的等价证明或净收益倍数。
本轮联网检索/取回未返回可用正文，没有新增论文或新版本结论。

## 4. 只能附带评估的精简

当前NativeQueryProof.current的项目内消费者是决策有效性检查，
涉及尚未接管请求的adopt和POI业务判断；
分段交接保存的nativeProof主要还参与对象身份匹配。

值得检查已接管移动阶段是否仍需要每次采集完整证明，
或能以明确用途按需采集。
这不是现已证明可以直接删除：还需闭合公共decisionCurrent行为、
prepare到adopt、POI、预览、原生whole和交接事实生命周期。
不能只看到一个调用点没用到current就删掉整类证明。

即使可以删除部分采集，当前没有其耗时份额。
它与之前临时previous精简一样，只作附带项研究，
不能凭表达冗余单独宣称一个主候选量级。

## 5. 当前不启动的工作与后续依据

原生已有完整锚点类型和AABB碰撞缓存，不再以笼统“加缓存”重复计算收益。
T/QG/TC的具体停止结论保持，不为本次成本研究重调几何方向或启发系数。
宏观位图不能直接代替原生完整动作和动态碰撞判断。

本轮没有生成最终实施方案或取得1017方法共识；
后续成案仍需列清可删工作、资格证明、冷/热成本和实际消费者，
不能将本地源码复核说成代码、数据或生产审查通过。
成本研究不自行改变分段长度，也不自行开始测试。
如需要新的诊断，先核对现有记录具体缺少什么，只补必要部分。
有效U/P/N/NS历史保持复用，不默认扩展矩阵或重新配对基线。

主要源码依据：

- NS/main的NativeNavigationSearch、NativeQueryProof、NavigationRegionMixin、
  SegmentedNavigationService、NavigationPoiAdapter、NavigationRequestPath。
- 前轮冻结的Forge47.3.0 WalkNodeEvaluator、NodeEvaluator、PathNavigationRegion、
  BlockBehaviour、IForgeBlock、IForgeBlockState、FluidType、ForgeMod等源码。
- 前轮冻结的Lithium commit
  `1316ab1aafd888bf9881e924f29928f653ad2308`：
  AbstractBlockStateMixin、PathNodeCache和ChunkCacheMixin。

前置结果见[NS隔离报告](2026-10-23-detail-neighbor-safety-isolated-validation-zh.md)，
前轮源码和文献身份见[结构研究](2026-10-22-detail-structural-candidate-research-zh.md)
及[NavMesh研究](2026-10-22-detail-query-navmesh-1017-research-zh.md)。
