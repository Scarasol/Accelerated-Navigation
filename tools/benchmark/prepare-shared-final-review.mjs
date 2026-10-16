import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const read=file=>JSON.parse(readFileSync(resolve(root,file),"utf8").replace(/^\uFEFF/,""));
const evidence=read("supplement-closeout-before-final-v1.json");
const round="supplement-integration-v1",target=resolve(root,`deepseek-prompt-${round}.txt`);
if(existsSync(target))throw Error("Preserve integrated review");
const prompt=`独立无上下文综合收尾，禁止工具/追读；中文350字内ACCEPT_WITH_GATES/REQUEST_CHANGES，明确“本轮必须修改项：无”或具体问题。本次用户顺序：暂停持久化→确认动态预算接入→推1.20.1基线→接入已验证首版→核心审核通过后验证补充并达成共识。不是批准所有后续新算法。
已执行：动态预算完整tick/START补额/END派发/实际计费已接入；正式验收未代批。基线da55e121c8775d32da74f843aaa27023d839ded0已push且远端再核一致。首版candidate-v4核心整体接受，无必改项。有限同section/accuracy0/受支持GROUND/width<=1/heightCells>=2，其他仍现有Base/Super；真实PathFinder/partial/最终实际accuracy/取消/版本/TTL/关闭/锁外交付原失败保留。共享新任务12/8、刷新一次，不改动态预算。真实门格FLUID不支持与失败被retire遮蔽的修复已核心复审。
随后容量82控制复现：64不同section后第65拒绝，卸载失效slot仍64，无idle eviction。candidate-v5五处共享准入限额仅类型化AdmissionRejected，消费只该类型及Unsupported回原macro；通用拒绝/服务不可用仍UNAVAILABLE，禁止写负缓存/扩大额度/转整条详细执行。六容量/内存/综合包已接受，无必改项；slot0反射控制同真实64/65分支，注入各命中1，但不称五处逐一自然饱和。
新Runtime1296/scratch3387/既有核心12通过；native-v8十五场景143检查，11自然到达、铁门负候选1、取消1、通用拒绝2，whole0；lifecycle-v4 128控制、四条冻结原参数路线自然到达、关闭records/active/foreign/pending0，旧字节收尾5542。首版与补充失败/超时原件保留。
内存：实际生产Runtime类，由隔离Host驱动真实worker；Instrumentation身份去重，Runtime可达字段+列出的任务/LocalKernel/BuildScratch/Row/Evaluation/delta/QueryScratch/真实旧rows。排除线程/元数据/维度key/hooks，未包含实际整服service/world，并不是全部栈中间对象。临时根持至cleanup、union含测试聚合List壳，是该选定闭包有诊断开销的控制点观察，不证明全进程峰值或生产内存上界。27flat约1.28MB/door约1.43MB union；双worker旧rows控制约1.94MB，释放临时根0；64slots约4.0MB；32MiB只行预算。82控制/156逐类对账通过，独立heap proxy不相加。两包已接受有限口径。
冷阶段：18个独立JVM串行，6阶段诊断+12不开阶段计时观察(每版/路线两次)。同一独立probe，同源输入/精确geometry，每轮24个父区子section facts逐键一致，matching published Base0；实际分支baseline same/base=false、两Super=true，新same=shared。初始4ms/最高20ms策略实际可降0，18请求都有4–10零grant tick，54原始tick更正通过。仅事实已准备的派生图冷首请求；非磁盘恢复/整服零预热。full tick/dispatch及提交前检查仍有。same首Path旧10nodes到12，新5nodes到7，不能当同终点CPU或整条移动对照。
18轮198控制/553独立核对通过。pure CPU/关键依赖/未使用图未测；嵌套并行interval不相加，缺图等待在step之间，wall仍含锁/调度；两次首Path波动不能证明稳定提速。四冷包和完整上下文补证都已接受；resolve标记误当hierarchical/忽略views.get(geometry)/ENABLED误读三项非阻断意见已复审明确撤回。24是每轮facts数，18是JVM数，分母已清。
依赖闭包/portal/tile/27halo表/细粒度依赖/全面Boundary/Super/扩范围没有实施或性能资格；portal从完整Base生成不能早于Base，缩依赖需单独等价；本轮不实施这些新算法，收益未证保留门禁。持久化/分段恢复/烘焙与旧异步v5暂停。下一调查是零授额/排队/发布与依赖关联，不改参数或擅自上新算法。
最终常规构建成功，产物SHA变化仅Manifest Implementation-Timestamp；273ZIP项逐个内容比对、其他Manifest字段一致、main源/class与candidate-v5逐个哈希一致。当前新字节native-v9同15场景143及reader5495再次通过，actual loaded jar/PID/退出核对。旧lifecycle明确保留旧字节身份，通过内容等价迁移有限代码资格，没伪称重跑。共享代码没有另push/部署、原用户世界未改。
独立收尾${evidence.checks}项通过，全部有效审核正常exit0无超时、包<=6500B、实际deepseek/deepseek-v4.1-flash、settings未改。你是否接受本轮有限实施与补充验证结清，保留上述未批准范围？
${JSON.stringify({productionTested:evidence.productionSha256,currentRelease:evidence.currentReleaseSha256,
    currentProbe:evidence.currentProbeSha256,coldProbe:evidence.coldProbeSha256,
    reviewedPackets:evidence.reviews.map(row=>({round:row.round,bytes:row.promptBytes})),
    coldRuns:evidence.coldRuns,currentNative:evidence.currentNativeChecks})}
`;
if(Buffer.byteLength(prompt)>6500)throw Error(`Oversize ${Buffer.byteLength(prompt)}`);
writeFileSync(target,prompt);
console.log(JSON.stringify({round,bytes:Buffer.byteLength(prompt)}));
