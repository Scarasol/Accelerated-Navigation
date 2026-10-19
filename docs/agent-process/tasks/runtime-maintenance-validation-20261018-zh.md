# 运行时维护前两项隔离验证

日期：2026-10-18。前置：
[方法与快照研究](../../reports/2026-10-18-runtime-state-optimization-methods-and-snapshot-design-zh.md)。
用户授权先结合27邻域与Base/Boundary输入讨论微调方案、考虑实现难度，
与DeepSeek达成实施验证方案共识，再验证前两项；期间自主推进。

证据根：`.tmp/runtime-maintenance-validation-20261018-v1/`。
生产分支`1.20.1`、HEAD `4b82ed6762b86298ec204637f92cf96c890689e7`。
不自动提交、推送、生产接入或发布。

## 范围

- A先只做单次失效调用内8槽父区集合查询缓存与空restart列表延迟分配。
  暂缓27位几何mask，不减少原27个Base检查、几何判断或validity写入。
- R先只合并连续同身份ALL_AIR事实段；int摘要按事实序列编号，保留原引用数、
  原对象次序、段前检查/原位标量回退和现行账目错误状态。
- 不拆Base/Boundary依赖，不细分section版本或引入反向索引，不改快照、
  当前性、预算、TTL、haveTime或真实请求参数。
- Base确实读取邻区事实；boundarySnapshot另外持有后续Boundary输入。
  不能据section存储独立性假定邻区变化只需刷新Boundary。

## 执行记录

1. `freeze-v1.json`核对旧封存62份生产输入，全匹配；main/test/诊断工具、
   build.gradle及默认Jar身份已冻结。B/A/R各从同一个main复制。
2. `deepseek-plan-v1.md`为6620 UTF-8字节，先发送方案审查；候选改动必须在共识后。
3. 基线`B/compiled-v2/checks-v1.json`通过110,300断言：
   10,935失效构型、992,717次实际volatile写日志及最终状态；
   9073引用差分场景（4963异常）、6048摘要/变换展开场景。
   日志代理只用于正确性诊断，JMH不加载该代理。
4. B受影响契约通过：Core9、Boundary recovery9、门集成、Worker7组、
   Shared stage99,951检查/41路线/643归属、Shared runtime1296检查。
   这是冻结基线验证，不是A/R通过。
5. 旧当前深trace复算`trace-opportunities-v2.json`：
   304格冷/暖请求至首Path和整场均无markLatestDerivedStale或invalidateHaloDependents。
   不能以该净场景判A无收益。准备期trial0未录制，不能推测其事件量。
   冷3364图事实walk仍是R机会；83292全部引用调用还含直接所有者，不能将
   50460图槽位的52.38%机会外推到所有引用调用。

技术失败保留：B/compiled-v1未含compileOnly mixinextras-common，编译失败；
补齐确切依赖后以compiled-v2重试。B/compiled-v2/contracts-v1因PowerShell标量
参数展开产生ClassNotFoundException，修正参数数组后以contracts-v2重试通过。
trace-opportunities-v1缺整场终点，已新名v2使用实际closedNanos；v1未用于判定。
这些不是候选功能或性能失败。

当前尚未获得生产接入或性能资格。后续执行结果须追加，不改写上述原始失败。

## 隔离实现与首轮筛选结果

正式材料：[前两项隔离验证报告](../../reports/2026-10-18-runtime-maintenance-first-two-validation-zh.md)。

- 实施方案首包901.623秒超时，原件保留；4211B短包785.775秒正常返回
  `ACCEPT_SCOPED`、必须修改项无，认可隔离实现验证。未发送极短草案不计实际审查。
- 共识之后才改A一文件、R两文件，生产62输入及默认Jar保持。
- 扩大int边界覆盖后，B/A/R的11,115失效构型及1,008,893真实volatile写日志、
  9073引用状态摘要与6048摘要展开一致。A查询300,105→89,775。
- 实际所有者账目各77项通过，包含airActive=0、airIdle=38的真实idle状态。
  初版连续排队tick合并未覆盖idle，新版逐tick等待，旧v1不作idle覆盖证据。
- A/R受影响Core/Boundary/门/Worker/Shared契约全部通过，
  `mechanism-gate-v1.json`交叉核对实际状态、旧/新图pin与关闭。
- `local-screen-v1.json`：A每次分配少约864B，耗时只降2.25%至7.27%，
  未过预注册10%时间门，停止这一版扩测；restart延迟分配未单独测，不记性能。
- R真实方法JMH空气-88.46%、混合-46.24%、无重复+4.53%；
  纯空气分配约12→24B，其他约24B不变，构造控制+11.51%/+2.98%/+1.71%。
  这是受控热fixture耗时，不是游戏CPU。
- 完整API另预注册三对，6 JVM/12窗全部所有权与关闭通过，
  air进程CPU +11.11%/-5.56%/0%，未过门。原
  `api-cost-screen-v1.json`的`STOP_CURRENT_R_PILOT_EXPANSION`保持。
  主线程约降40%不能事后替换进程CPU主指标；编译和distinct控制波动明显。
- 针对上述矛盾，另预注册B/R各一次JIT诊断，未改算法：
  R连续三段编译490/0/0ms，进程CPU703/516/328ms；
  B编译12/7/0ms，进程CPU750/703/547ms。
  XML显示既有调用链、lambda及fixture循环的真实编译任务；
  这是明确的编译过渡证据，非490ms新引用函数独占CPU或稳定热态性能通过。
- 3650B取舍包已送`deepseek-cost-review-v1`，讨论是否另立成熟JMH完整API热态测量，
  再按结果决定仅一对真实路线侦察。旧API失败不改判，不续样直到显著。
  23.101秒正常返回ACCEPT_SCOPED、必须修改项无，认为当前停R偏早；
  后继已冻结`api-jmh-protocol-v1.md`并完成，不改原失败主指标或判定。

已构建隔离B游戏Jar（17任务/31秒），未启动新游戏JVM。
没有预算/TTL/参数/当前性/快照变化，也没有提交、推送或生产接入。

## R1控制未过与R2单次修订

成熟JMH完整API三对/12fork已完成，air进程CPU/操作-38.96%/-48.68%/-61.84%，
distinct +20.01%/+45.02%/-31.16%，原始48迭代归一化核对通过。
分配约768B不变、测量编译2–6ms；主门过、控制未过，不进入游戏。
`api-jmh-screen-v1.json`保留false，不以此前JIT解释冲销控制差异。

仅提出一次低复杂度R2，恢复R1曾抽成helper的标量方法，与B逐字一致，
helper仅服务加权段。2655B/30.673秒DeepSeek认可，必须修改项无，
强调当前控制噪声大、不能证明helper因果；若R2仍不过，停止本轮，不再换形态/扩样。
旧R1/main、所有筛选和工具快照保留；R2新目录同源复制后修改。
`r2-mechanism-gate-v1.json`证明只有该段差异，B标量源码完全相等；
R2真实差分/摘要/77项owner及六组契约再过，同壳12fork已完成。
源码验证器LF/CRLF边界误截取的失败与新名v2通过保留，不是候选失败。
没有新的游戏运行或生产接入。

R2 air进程CPU/操作-11.04%/-36.54%/-9.53%，distinct +15.59%/+8.97%/+7.09%，
主/控制门均未全过，分配近乎不变、编译4–8ms，48迭代归一化核对通过。
按事先约定停止本轮，不再R3或追加样本，不运行游戏侦察；
保留真实账目正确及局部/air热态收益，不能称算法完全无效或生产性能通过。
六个同源B distinct fork亦有明显跨fork波动，控制噪声未正式A/A校准，
不能据此作废不利样本或证明helper因果。步骤8整体仍未结清。
最终4657B/43.706秒DeepSeek复审ACCEPT_SCOPED、必须修改项无，
认可有限停止，未批准生产性能。采纳控制噪声不能反证算法无效、
明确3对×2变体×2halo=12测量JVM；其把A三fixture称三对的表述已按实际2fork澄清。
17份活动脚本语法与diff检查通过，封存`final-seal-v1.json`；
生产62输入、两份默认Jar保持。本轮有限收尾，不表示步骤8整体完成。
