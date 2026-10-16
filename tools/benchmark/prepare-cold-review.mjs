import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const topic=process.argv[2],frozen="cold-instrument-v3";
const source=file=>readFileSync(resolve(root,frozen,"sources",file),"utf8").replaceAll("\r\n","\n");
const read=file=>JSON.parse(readFileSync(resolve(root,file),"utf8").replace(/^\uFEFF/,""));
function cut(file,start,end) {
    const text=source(file),a=text.indexOf(start),b=text.indexOf(end,a);
    if(a<0||b<a)throw Error("Missing source");
    return text.slice(a,b)+"\n";
}
let prompt=`独立无上下文小包审核，不用工具不追读。中文250字内ACCEPT_WITH_GATES/REQUEST_CHANGES，明确“本轮必须修改项：无”或具体缺陷。共享首版核心和容量增量已综合通过；本轮仅补充冷阶段诊断及方向资格，不批准性能/全并发/宏观新算法/持久化。真实PathFinder/partial/实际到达及预算逻辑不变。测试probe为独立jar，两版本用同一probe，只载共同方法的计时Mixin，通用性能full tick与dispatch仍在，所谓不开阶段计时不是零测量开销。
`;
if(topic==="intervals") {
    prompt+=`以下计时保存原start/end/thread，Base大区间内legal/labels/edges为嵌套inclusive；Super内部boundary同样嵌套。worker queue记录enqueued到实际takeNext；没有请求依赖ID，不能当本请求关键路径。宏观缺图等待在多次step之间；单次step墙钟仍含锁争用/调度/子调用，与其他worker区间重叠，不能当纯CPU或简单相加。Metadata反射错误记录，reader要求errors空、openIntervals0、无截断，失败不批准。旧signature只查public导致构建异常的失败保留；本修订getDeclaredMethod+accessible，成功实验另证。不登记为生产缺陷。
`+source("src/test/java/an/production/ColdTopologyTelemetry.java")
        +source("src/test/java/an/production/mixin/ColdBaseStagesMixin.java");
}
if(topic==="cold-identity") {
    prompt+=`每次新JVM/新固定flat种子世界，start(3,81,7)→same(12,81,7)/Base(28,81,7)/Super(72,81,7)，成人Husk .6x1.95、accuracy0、不开门。加载端点对齐父区所有8子section，实际hierarchical通过私有joinCanonical布尔钩子检查，不以距离猜测。请求前30ticks，事实已准备；这是派生图冷态首请求，不是磁盘facts恢复/整服零预热首实体。保留正常PREWARM，body精确geometry与prewarm 1x2不同。持锁只读实际Runtime clusters/views确认请求体型已发布Base数0，对24父区子section actual facts fingerprints逐一跨所有run比对。新共享分支还检查sharedBefore.baseBuilds0及请求后实际shared build，预热的普通Base不冒充shared basis。
runtimeLock只读扫描在计时提交前，不改变生产策略。固定同一个初始4ms/最高20ms策略，实际允许降到0；每tick grant实际记录，不声称固定额度。仅测首条真实Path装入；自然到达由candidate-v5真实15场景/四路线另证。
`+cut("src/test/java/an/production/ColdTopologyProbe.java","    private static Object field(","    @SubscribeEvent public void start(")
        +source("src/test/java/an/production/mixin/ColdRuntimeStagesMixin.java");
}
if(topic==="evidence") {
    const result=read("analysis-cold-matrix-v3.json");
    prompt+=`已完成18个串行独立JVM：6阶段诊断(2版本x3路线)，12不开阶段计时观察(2版本x3路线x2次，顺序交错)。全部同生产两冻结jar/同probe，24实际facts逐一相同、geometry严格相同、matching Base0；actual JVM PID/退出/时间窗无重叠；source/class/分母另收尾。阶段诊断3路线实际分别Base/Base/Super，当前same实际shared，两个Super均有canonical=true和真实Super build。12观察少样本且仅一实体平地，不能性能批准，未128/256/512/长期扩区。full-tick及dispatch共用测量仍有，所有观察非完全无探针。
等待是server提交到END首次发现真实Path，包含scheduler/server派发。阶段区间inclusive，不是纯CPU；宏观缺图等待在多次step之间，区间内仍可能有锁争用/调度/子调用，不把区间简单加成总等待，也不认定Base是CPU瓶颈。read/decode未测、facts事先准备。以下摘要支持这种有限结论吗？
同section共享首Path为短段锚点、旧macro首Path可以直接更远，节点/详细搜索目标不同；这不是相同详细搜索终点的CPU对照，不推出整条自然到达时间更短。
先前简写4..20ms不准确，实际策略可以降到0。全部请求窗口存在零授额tick，首Path等待不能只归因于构图。旧scope字段简写保留，资格更正另以budget qualification读原始full-ticks登记。
`+JSON.stringify({checks:result.checks,probe:result.probeSha256,baseline:result.baselineSha256,
        candidate:result.candidateSha256,observations:result.observations.map(o=>({
            route:o.route,backend:o.backend,diagnostic:o.diagnostic,waitMs:o.waitMs,
            branch:o.actualBranch,grantMin:Math.min(...o.grantMs),grantMax:Math.max(...o.grantMs),
            zeroGrants:o.grantMs.filter(x=>x===0).length,firstPathNodes:o.firstPathNodes,
            bodyBuilds:o.observedBaseBuilds.filter(b=>b.matchingBody).length,
            superBuilds:o.phase.super?.count??0}))})+"\n";
}
if(topic==="directions") {
    prompt+=`本轮验证方向决策，不把后续新算法当首版缺项：①已实证实际Super路线需要收集当前Base及边界/父图；短Base与same并不等完整Super。当前没有按request ID跟踪“构建但未用图”，不能据inclusive时间推出依赖闭包/portal净收益。②由完整Base派生portal不能提前于该Base，若要先于Base需新事实/边界带表示及稳定身份；component ID不能跨重标/门/卸载复用。③tile/高度层当前无实现，需要跨tile border覆盖step/jump/drop/体型和 UNKNOWN，不能先批准。④27 halo binarySearch在内核热循环确实存在，但观察有嵌套区间、背景预热和step间依赖等待；两次平地首Path波动不能决定直接表/中间缓存收益，不接入新内核。⑤细粒度halo恢复等待/临时pin/发布stamps是不同集合，未经等价证明不缩依赖。⑥持久化、分段恢复、中间产物落盘、世界增量烘焙保持暂停；没read/decode实测，不判断缓存格式优先。⑦完整Boundary/Super共享和支持范围扩大均无首版生产实现/身份矩阵，本轮只登记依赖和拒绝边界，不能将设计接受写成已实现。
这些是经当前源码和18JVM小样本排除越权结论后的补充验证结清：容量与选定内存闭包已有实施/数据共识；冷阶段观察完成，未来算法资格未取得，研究收益未知。请核对是否有必须现在改代码的问题，或只需保留明确门禁。不以未实现所有新算法判为首版不完整。
`;
}
if(topic==="identity-followup") {
    prompt+=`复核上一包三个非阻断意见，完整实际上下文如下，不需改生产或重跑已通过18轮：
1. endpoint-resolve mark的detail=true是“事件发生”标记，不是hierarchical字段。reader仅phase==="canonical"取布尔；canonical标记来自实际方法boolean hierarchical参数。因此resolve标记不能被解读为Super=true，六诊断已分别读为base/shared/base/super/base/base。
2. graphColdState已执行views.get(geometry)，geometry是intent.profile.geometry(GROUND)精确record，含width=.6/height=1.95；原生产views.computeIfAbsent(geometry,key->new ViewEntry(this,key))，ViewEntry保存该geometry。view.topology!=null在此精确key取值后计数，不是遍历其他body的topology。多计失效同key只会保守拒绝冷态，不会用另体型冒充。
3. begin/end内ENABLED来自coldStages，false立即返回，12观察没有这些阶段区间；full tick/dispatch和提交前只读检查仍有开销，报告已明确不是零探针开销。
24是每一轮actual facts section的数量(6x2x2)，18是JVM运行数，分母不同；reader原始每轮检查24键且无absent，逐个指纹跨18轮相同。same baseline为canonical=false/10nodes终点12，新共享5nodes终点7，是短段firstPath，不同详细目标，不外推完整移动提速。实际策略可降0，54原始tick grant更正已过。请确认前三条应撤回为代码上下文误解，以及分母门控已清；保留其他性能/范围门禁。
`+cut("src/test/java/an/production/ColdTopologyProbe.java","    private Map<String,Object> graphColdState(","    @SubscribeEvent public void start(")
        +source("src/test/java/an/production/mixin/ColdRuntimeStagesMixin.java");
}
if(!["intervals","cold-identity","evidence","directions","identity-followup"].includes(topic))throw Error("Explicit cold topic required");
const revision=["intervals","directions","cold-identity"].includes(topic)?"v2":"v1";
const round=`supplement-cold-${topic}-${revision}`,target=resolve(root,`deepseek-prompt-${round}.txt`);
if(existsSync(target))throw Error("Preserve cold review");
if(Buffer.byteLength(prompt)>6500)throw Error(`Oversize ${Buffer.byteLength(prompt)}`);
writeFileSync(target,prompt);
console.log(JSON.stringify({round,bytes:Buffer.byteLength(prompt)}));
