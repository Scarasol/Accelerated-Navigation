# 服务器就绪30秒后的冷请求对照

用户提出延后30秒检查服务器启动影响，并询问负债含义。研究按[方案](../../designs/2026-10-15-cold-scheduler-delay-plan-zh.md)进行；所有新增原型与证据在`.tmp/cold-scheduler-delay-20261015/`，生产、正式测试树、参数与既有冻结记录保持。

当前：研究和结果审核已结清，[完整报告](../../reports/2026-10-15-cold-scheduler-delay-validation-zh.md)。方案`delay-plan-v1`已ACCEPT_WITH_GATES、必改项无；provider=deepseek、model=deepseek-v4.1-flash，实际3,642字节。执行前资格门禁已补入方案。构建通过、77个ZIP内容仅两个外层class变化，`instrument-v1`冻结63份源码/资源/入口，生产273项保持。两轮接线及8轮AB/BA/AB/BA主矩阵全部完成，会话`7602`正常退出。不可覆盖campaign-v1.json、原始结果及冻结sources。主分母同新包各4JVM，实际ready单调时钟0/30秒，不把600tick代替30秒。

探针SHA256：`D8309F849DB3C72C3F123630301F4DC7D4E745CAA36C0BEE195045BE3D3AE60B`。生产仍candidate-v5、`2D9DF2FF444A397574F16D5DBC87E94276DE4B1F074DA42D9354AFBE437572E0`。

两轮接线均原11控制。early第30tick、ready后1.4816131s，等待521.2338ms；delayed第601tick、ready后30.0273495s、ServerStarted后30.3009582s，等待124.7853ms。两组实际目标体型均冷、同24facts。延后组提交前余额19.996ms、原grant20ms、p95 .8814ms；早组-3.3931ms/grant0/p95 49.2368ms。信用都为0，不计主要分母。

接线时间线220、研究7,768、原Controller主机重放574/8,456项通过，时间线12/研究15类损坏拒绝。normal不替换p95，原class逐ns完整重放真实输入。新增读取器支持实际submittedTick和首次真实queued END，可同tick派发。

无p95干预、无信用注入、保留真实负债及所有计费。读取约600启动tick要求将隔离记录上限600增至4096，旧冻结源码不改。延后组仍须24事实相同、精确体型派生图冷；请求超时相对submittedTick。

## 实测与结清

八轮early首Path为523.6765/572.1962/617.2873/522.9067ms，delayed为169.1445/213.4065/121.6323/169.5400ms；均值559.0167/168.4308ms，第一次排队375.5962/2.3460ms。early实际ready后约1.48s、第30tick；delayed实际30.0308–30.0398s、第601tick。delayed的ServerStarted→提交30.2837–30.3422s，额外约.25–.31s来自场景搭建。

early提交前余额-2.2235/-6.1246/-10.5634/-4.7418ms、grant均0；delayed均约19.99ms、grant20ms，原p95 .8574–1.0066ms。全部normal原计费、无信用、无历史替换、精确目标体型图冷及同24facts。延后同时改变JVM/事实/缓存/历史与账本，只作该时机条件下描述，不称历史单变量因果或调度CPU重。

延后组仍有真实原子工作新债务；delayed1在tick604导航计费38.609ms、grant12ms，final=-26.609ms。delayed2在tick601余额正但HEADROOM拒绝一次，tick602首次派发。四延后窗口各一次超45ms，最大48.5133ms，不能称30秒后所有tick快或长期安全。

主矩阵原控制88、时间线894/研究31,156/原Controller主机重放36,218通过，12/15类损坏全拒绝。两接线另22原控制及220/7,768/9,030核对。全部启动tick保留，normal原class重放无需改p95。

结果审核`delay-evidence-v1`4,102字节，实际ACCEPT_WITH_GATES、必须修改项无，正常退出无超时。方案和结果包均独立无上下文，真实传输保持，settings SHA仍`071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858`。`closeout-v1.json`最终600项、679身份核对通过；10JVM与本轮dsh退出，性能与模型时窗不重叠。

本轮无剩余实验或审核。后续评估寻路自身冷态应分别报告服务器启动和服务器已运行一段时间，明确图冷态及预算状态；高并发、长期安全及生产参数需另案。生产、正式测试树、持久化保持，未提交推送部署。
