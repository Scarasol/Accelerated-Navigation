# 服务器就绪30秒后的冷请求对照

用户提出将请求推迟到启动30秒后，检查此前的服务器启动历史影响，同时澄清负债。生产、参数、正式测试树及既有冻结证据保持，所有新增原型和数据在`.tmp/cold-scheduler-delay-20261015/`。

固定candidate-v5、同平地/成年Husk/24事实/Base路线、精确体型图冷态。两组均为normal：不改p95、不加信用、不清债、不改变haveTime/截止/实际计费。

early在第30 tick提交；delayed在测试场景搭建完成并记录readyNanos后，实际单调墙钟达到30秒且至少30 tick时提交。记录ServerStarted入口、场景就绪、提交的单调时间，保留实际submittedTick；不能把30 tick、600 tick或进程启动时刻当30秒。输出确认提交前目标体型图未发布、同24事实。提前启动的区块/事实准备和原有预热保持，其缓存及JVM状态变化是该时机干预的一部分，不称只移除历史的单变量。

同一个新探针先两轮接线，然后各四个独立JVM，固定early/delayed、delayed/early、early/delayed、delayed/early。主分母8，不复用旧包early替代新同包对照，资格另列。JVM串行，实验期间无模型审核。

新包相对上一轮hold探针只允许ColdTopologyProbe.class、SchedulerResearch.class变化：前者增加墙钟门槛、时间元数据，将请求超时改为相对提交tick；后者将记录上限600增为4096，完整保留约600个启动tick。两组同SHA、相同记录开销。未在30秒仍合格则失败保留，不按结果改阈值或删样本。

独立读取完整真实TickSample，逐tick重建原32环/EWMA/p95/choice/账本；normal必须零外部信用、selectedP95始终raw。核对首次真实queued END记录及dispatch的实际tick，可同tick；不能继承上一轮“补零后下一tick派发”的假设。主机仍加载未装Mixin的冻结生产Controller重放，对normal无需替换p95。12类时间线及研究读取损坏拒绝保留。

每轮报告总等待、三次排队、候选异步、详细寻路至装入、装入后观察、实际提交延迟与tick、提交前余额、首次queued END余额、p95/EWMA/授额、实际新工作负债及请求窗口full tick。30秒后若估计/授额/余额仍异常，照实报告，不宣布启动干扰已消失。

若延后组排队缩短且提交前grant与余额恢复，仅支持启动状态与本次首请求等待相关，结合此前机制证据解释；不能拆出JIT、缓存、事实预热、早期慢tick的独立贡献。不证明高并发、长期安全、全路线或生产参数问题。若仍慢，按分段检查后续等待。审核每包独立不超过6500 UTF-8字节，完成后与DeepSeek确认有限范围。

## 执行前资格门禁

方案`delay-plan-v1`为ACCEPT_WITH_GATES、必须修改项无。两轮接线和主要运行均须：进程退出0且无超时；原冷请求11项控制全通过；telemetry无错误、无未关闭区间、无截断；同24事实指纹、目标精确体型发布数0；真实query/owner/sequence/future完整、真实详细Path且whole fallback为0；完整tick连续、selected始终raw、信用为0、原Controller逐ns重放；时间线和研究读取及损坏拒绝全通过。接线两种时机同新SHA，任一失败不进入主要分母，失败原件保留。主要执行前冻结顺序、源码与脚本身份，期间不可改写。

结果同时列ServerStarted→ready→提交单调差值，delayed的ServerStarted→提交至少30秒，超出的场景搭建耗时单独说明。四次原始值及最小/最大描述区间保留，不作显著性或因果断言。
