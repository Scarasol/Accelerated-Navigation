# 中间产物复用针对性优化

- 用户授权：结合工具栈反馈继续针对性优化。
- 新方案：`docs/designs/2026-10-16-intermediate-reuse-targeted-plan-zh.md`。
- 原型：`.tmp/intermediate-reuse-targeted-20261016/`；旧轮 `.tmp/intermediate-reuse-20261015/` 封存不改写。
- 当前：本轮完成；packed保留为有效原型，admitted在本轮不优先，不接入生产。最终DeepSeek ACCEPT_WITH_GATES、本轮必须修改项无；478项证据已封存，测量源码/类68项、冻结生产输入61项零漂移。
- 已完成：同体型 `capture-same-v2` 为64次实际Base/13源/2worker，16实体自然成功；混合 `capture-mixed-v3` 为256次实际Base/13源/2worker，16实体自然成功。ready后分别30.29/30.33秒派发。全部为受控新场景，历史生产复用率仍未知。
- 正确性：58 fixture×21Geometry，另加320个捕获构建及身份/halo代次控制。packed为1548对图/19388断言；admitted为1552对图/19416断言。弱引用GC/FIFO/clear通过。1370图另以未改动四生产源在独立JVM中对照通过，跨JVM仅排除含枚举身份的signatureHex，同JVM仍逐字段检查签名。
- 留存：64共享条目含容器138520B，含平地源闭包802224B；64弱记录强可达7136B，clear后2488B；本次捕获最大单源闭包10520B，非通用最坏上限。
- 保留失败：两次Gradle loopback启动失败；首次隔离包漏带MixinExtras，未进实验；混合v2使用超出width≤1自动宏观门限的宽体型，转原生回退且EXHAUSTED，不进入正式结果。正式混合尺寸为.6×1.8/.9×1.4/.8×2.4/1×2.8，并增加支持及冷图断言。
- 测量边界：净计时源码用static final DIAGNOSTIC关闭逐格观察分支；JMH按捕获worker分别持缓存并包括建立/弱记录/晋升/淘汰/clear，原型源码和类绑定于measured-freeze-v1.json。旧classes-v1/首轮checks-v1保留，正式为classes-v2/checks-v2。
- 局部结果：packed相对基线，同体型64构建4214.549→1374.567us（-67.385%），混合256构建17294.016→5302.043us（-69.342%），单体型无复用43.983→39.583us（-10.004%）。legacy单次49.790us（+13.202%），admitted单次+1.485%、同体型+2.176%；两新变体过均值门，只选择三项占优的packed继续。
- 归因：每模式32000构建/192000阶段事件，丢失/失败/孤立ID=0；捕获64/256Consumption与阶段完全匹配。packed API flags调用0但另有packed字节读取，非零工作。两条平地轨迹exact=0不作精确碰撞性能证据。CPU粒度15.625ms只批量使用。
- 审核：首包3716B在900秒本地截止仍无结论，保留。拆为1934B位转置和2393B弱晋升后分别60/82秒返回ACCEPT_WITH_GATES；位审答复的row≤4095错误由2845B澄清包纠正为row≤255/最大字节2047，具体正确性门已关闭。局部性能包已送，端到端/最终共识未完成。
- 服务器：8/8运行、128实体自然成功，无whole回退且清理通过。两JVM首Path中位数均值同体型290.523→282.697ms（-2.694%），混合411.718→355.370ms（-13.686%），仅小样本观察。所有最大tick在派发tick，含反射冷图校验探针，不能归因为候选或认证净生产tick；未删这些tick。
- 性能审查与边界：性能包ACCEPT_WITH_GATES但列范围要求，已明确8次不是整服非劣/性能验收，不事后补阈值，GC归因未测，复杂性能/最坏内存/历史复用率留未来接入门。scope-amendment-v4 Stream error无最终答复，缩小v5后接受、本轮必须修改项无。最终6062B自足包已送，未回前不记最终共识。
- 最终审核：6个成功自足包，最大6062B；最终包28.3秒返回ACCEPT_WITH_GATES、本轮必须修改项无。旧性能包范围要求由范围修订及最终包确认关闭当前报告项，不冒充未来门已完成。见consensus-v1.json/evidence-manifest-v1.json及报告docs/reports/2026-10-16-intermediate-reuse-targeted-validation-zh.md。
- 后续接入门（本轮不声称已过）：整服数值非劣、GC归因、净tick（先移出冷图反射校验）、复杂几何/真实负载收益、复杂源最坏内存及更多独立JVM。
- 边界：生产不改动，不提交推送；不恢复持久化、历史预算、偿债、Portal 等支线。
