# 详细询点主候选研究及1017讨论

日期：2026-10-22。

范围：用户认为临时还原数据精简不足以独立成为候选，要求继续确定主方案，
把精简捎带到下一候选。本轮只研究和成案，无实现、构建、游戏或新增性能数据。

## 1. 决定

下一主候选为
[跳过已证明无影响的原生邻域扫描](../designs/2026-10-22-detail-neighbor-safety-summary-plan-zh.md)。
使用当前详细region中实际section的调色板，对原生24格危险检查给出安全否定摘要：
整片邻域不会改变输入类型时，跳过整个循环；否则完整原函数一次。

临时 `previous` 四开关/primitive malus数组精简仅附带，不独立筛测。
旧第六十五节及其封存记录是当时研究结论，
本轮新方案和主交接第六十六节替代其中“独立下一候选”的安排。
未改写旧讨论包、源码原件或旧封存文件。

该主项有明确的整段询点工作可删，值得进入实现审查；
命中率、冷成本及净收益尚未测，不能宣布实质量级已通过或项目200ms目标完成。
主搜索仍为原生算法，没有用“换算法名称”替代实际减少工作。

## 2. 源码证据

本项目核对了：

- `LocalKernel`、`BaseClusterTopology`、事实采样和几何组织；
  宏观成员/连通不是原生完整精确动作图。
- `NativeNavigationSearch`、prepare/done注入、`NavigationRegionMixin`、
  `NavigationRegionSnapshot`及原计量边界。
- chunk/section更新钩子；已有修订机制不能自动充当任意新详细缓存的失效证明。
- 既有原生完整锚点类型和AABB缓存；新方案不能再缓存同一层并重复计算收益。

实际当前依赖来源为本地：

```text
forge/1.20.1-47.3.0_mapped_official_1.20.1/
forge-1.20.1-47.3.0_mapped_official_1.20.1-sources.jar
```

归档SHA-256：
`5905c044cbf9d4cd0208f8878bfd7b586782c44366c277d6fbd70e4b075c0eb2`。
提取的25份源码保存在本轮 `references/` 并由封存清单记录哈希。
主要新事实：

1. `WalkNodeEvaluator` 的危险邻域是XYZ循环24格，方块/流体钩子先于原生危险分支。
   无命中时MutablePos停在中心+(1,1,1)，不能忽略位置副作用。
2. 默认 `IForgeBlock` 相邻钩子只检查浆果/燃烧，不依赖世界位置；
   未知覆盖即使返回null也可能有副作用，不能据一次采样缓存。
3. EMPTY流体真实链经Fluid和ForgeHooks到显式
   `adjacentPathType(null)` 的 `ForgeMod.EMPTY_TYPE`；
   不是仅凭 `isEmpty` 猜测钩子为空。
4. `PalettedContainer.maybeHas` 直接调用palette；
   Linear/Hash遍历调色板成员，Global恒true。
   Linear/Hash新增状态为追加/扩容，copy创建独立数组/底层映射。
   已进一步读 `CrudeIncrementalIntIdentityHashBiMap.copy`，
   确认keys、values、byId均clone，没有把“调用copy”直接当深拷贝证明。
5. `PalettedContainer.read` 可复用旧Data/palette，同size替换内容；
   摘要必须覆盖palette原地read。SingleValuePalette.copy返回自身，
   因而单值palette不缓存摘要，实时读value后查状态分类。
6. `TagsUpdatedEvent` 用于标签依赖失效；首次分类与摘要延迟构建，
   不在reload时扫全世界。
7. 最后一轮1017指出要核实实际读取链，随后本地补核发现：
   debug世界的LevelChunk可能合成方块，不能使用section存储证明；
   正常空section的实际返回为标准AIR/EMPTY，需要认证这些实际返回状态。
   这两项具体细节是末轮回复后的源码补充，不冒称已将其代码送审。

方案相应关闭debug快路，空节认证实际AIR/EMPTY，
非空节才查调色板。真实读取链未知时完整慢路，
不用多加container/section各层失效或每节点完整防御链替代证明。

## 3. 一手资料及未采纳方向

复用前轮固定版本Lithium、Recast/Detour源码及已读Polyanya/JPS全文，
来源和原件身份见
[前轮研究](2026-10-22-detail-query-navmesh-1017-research-zh.md)。

本轮重点重读Lithium固定commit
`1316ab1aafd888bf9881e924f29928f653ad2308`
的 `PathNodeCache`：
其section危险摘要可跳过邻域扫描；
这提供切点参考，不提供本项目Forge兼容证明或性能倍数。
新方案没有照搬它的空节直接安全、静态钩子假设或逐块计数维护。

另取得并读完RSR作者2011-09-01说明：
`Rectangular Symmetry Reduction`。
官方WordPress JSON响应保存为 `references/rsr-author-20110901.json`，
SHA-256：
`707b31a0742c165504a4e1986dbf2360c4f409d7d113c85fb7238376e4c503f2`。
它说明均匀代价格的矩形内部裁剪、边界宏边和在线插点，
也明确4邻与8邻边集合不同、效果依地形而变。

来源定位：

```text
https://harablog.wordpress.com/2011/09/01/rectangular-symmetry-reduction/
https://public-api.wordpress.com/rest/v1.1/sites/harablog.wordpress.com/posts/slug:rectangular-symmetry-reduction
https://github.com/CaffeineMC/lithium/blob/1316ab1aafd888bf9881e924f29928f653ad2308/src/main/java/me/jellysquid/mods/lithium/common/ai/pathing/PathNodeCache.java
```

RSR的2011论文会议是SARA，作者文章列题为
`Path Symmetries in Uniform-cost Grid Maps`。
Block A*定位到AAAI2011
`Block A*: Database-Driven Search with Applications in Any-Angle Path-Planning`，
DOI `10.1609/aaai.v25i1.7813`。
这两篇PDF本轮未成功取得，不冒称读过全文；
元数据只用于定位，不作为工程收益或正确性依据。
网页检索工具没有返回可用正文，实际新依据是作者官方响应和本地映射源码。

本轮比较后未列为下一主项的方向：

- RSR式缩图：上层合法水平偏移后降落可落到矩形内部，
  只保边框不够；对角、目标精度、有限搜索及partial、
  认证/动作附着口/fanout成本尚未闭合。暂缓，不用大名称勉强成案。
- 新“最终单格类型缓存+27格认证位图”：普通脚格已受完整锚点缓存覆盖，
  再建认证位图缺复用证据，不采纳1017第一轮的过强收益推论。
- 整套NavMesh：精确凸域/公共边/有向动作资产仍缺，
  长期方向保留，不恢复全门户点对或全距离场构造。
- T/QG/TC：历史停止结论不变，不调排序/终端偏置去救本次询点方案。

## 4. 1017三轮讨论

延续用户授权的 `deepseek-official` 方法讨论分工。
每轮实时models查询 `deepseek-flash`，
thinking enabled、`reasoning_effort=max`；没有修改共享provider配置或保存凭据。
列表仍无独立日期1017模型ID；别名和fingerprint不证明不可变日期版本。

| 轮次 | 自含包UTF-8字节 | 耗时秒 | 结果 |
| --- | ---: | ---: | --- |
| 1 | 8371 | 161.014 | 比较缩图/询点，回复提出E-B，但有收益及计量错误 |
| 2 | 8122 | 150.169 | 撤回错误；认可邻域安全摘要切点，补provider/失效/副作用边界 |
| 3 | 7064 | 105.225 | 认可palette本体缓存进入实现审查，限定源码/线程/读取链条件 |

三轮均HTTP200、stop、`DISCUSSION_COMPLETE`，
返回 `deepseek-flash`，
fingerprint `e72d3c7f663d3e1383b00b3884cb4501`；
完整正文已逐轮阅读。每包自含项目事实、当前方案及上轮反驳，
不假设无头对话带历史。

实质纠正：

- 首轮把U的5100次搜索合计CPU937.5ms与单请求到达增量200ms相减，
  得出“必须省737ms”，口径错误；第二轮明确撤回。
- 首轮单格缓存“能省24格”的表述忽略已有完整锚点缓存，
  第二轮撤回，未据此实施E-B。
- 首轮要求矩形宏边必逐步新增range检查的说法过强；
  在整个域已在range内、非负边且展开留在域内的前提下，
  不能把该检查当必然新增成本。原生复杂动作/有限搜索风险仍存在。
- 第三轮接受把摘要放在palette本体，不再要求各owner层重复失效。
  仍提醒字段也会出现在biome palette，不能无测量宣称内存更小。
- provider必须无返回贡献且无副作用；未知慢路。
  分类首次同步完成后本代次稳定，安全结论与代次要完整发布。
  本地主线程查询/共享BlockState分类的线程边界写入方案。

最终共识是“有限候选资格，可进入后续实现审查”，
不是正确性全证明、净收益、代码/数据或生产批准。
原provider代码/数据审计分工保持，未用本轮方法讨论冒充正式代码审查。

## 5. 计量及交付边界

没有新增游戏、性能观察或新基线。
后续实施若获准，只在U隔离母版建立一个主候选，
附带previous精简，沿用原35ms六场一次筛测，
有效U/P/N历史先核身份、接口、场景和计量后复用。
不补15/25、不建冷热矩阵、不为形成新配对重跑基线。

主摘要在原search窗口内，previous捕获/还原在窗外；
必须结合原drain、整场CPU/分配、首Path与同路线到达判断，
不能把窗口外附带精简当作主搜索改善。
不新增主观3%/5%门，不按200ms倒推本分支节省量。
必要诊断先说明历史缺字段，不把热计数脚手架叠入正式计时。

本轮目录 `.tmp/detail-structural-candidate-research-20261022-v1/`，
含源码、作者原文、三包及全部调用回执、旧协调文档副本、生产身份和封存脚本。
生产68项、相关历史43项及前轮研究原件核对保持，
原 `TopologyWorkerRuntime` 脏改保留。
仅新增方案/研究材料并更新索引和主交接，无接入、提交、推送或清理。
