import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const topic=process.argv[2];
const candidate="candidate-v5",prefix="src/main/java/com/scarasol/acceleratednavigation/";
const source=file=>readFileSync(resolve(root,candidate,"sources",file),"utf8").replaceAll("\r\n","\n");
function excerpt(file,start,end) {
    const text=source(file),a=text.indexOf(start),b=text.indexOf(end,a);
    if(a<0||b<a)throw Error("Missing complete excerpt");
    return `${file}\n${text.slice(a,b)}\n`;
}
const read=file=>JSON.parse(readFileSync(resolve(root,file),"utf8").replace(/^\uFEFF/,""));
let prompt=`独立无上下文审核，不用工具不追读。只审本包，中文250字内给ACCEPT_WITH_GATES/REQUEST_CHANGES，明确“本轮必须修改项：无”或具体bug。首版同section/accuracy0/GROUND/width<=1/heightCells>=2已整体通过；本轮是补充，持久化暂停，不批准性能、全进程峰值或扩大范围。真实PathFinder/partial/最终到达，禁止整条详细执行回退；预算12/8及刷新一次不变。
`;
if(topic==="capacity-consumer") {
    prompt+=`容量实验64个不同有效Slot后第65个拒绝；卸载令所有旧lease失效，slots仍64，无idle eviction。保留64/1024/32MiB，采用包内构造专用AdmissionRejectedException回现有macro。该异常仅五处：captures/slots/flights/initial bytes/service publications；关闭和普通worker拒绝仍通用异常。完整消费如下，不写负缓存或构造无路，回退计数只该分支。异常原失败交付已由核心复审接受。
`+excerpt(prefix+"topology/SharedSectionCandidate.java","    public static final class UnsupportedInputException","    private final RuntimeSharedTopology.Result source;")
        +excerpt(prefix+"navigation/SegmentedNavigationService.java","    private void sharedCandidate(","    private void prepareWindow(");
}
if(topic==="capacity-producers") {
    prompt+=`专用异常extends RejectedExecutionException且包内构造。仅五个共享准入限额出口，不混普通executor、关闭和事实错误。槽64/flight1024/行载荷32MiB等限额不扩大；首版原失败结算锁外交付保留。下面全部五处实际出口邻域，消费只识别UnsupportedInput或AdmissionRejected回原macro；其余UNAVAILABLE。核心1296/scratch3387及容量82控制通过，第65个实际typed cap，与maxSlots=0同一判断。
`;
    for(const file of [prefix+"topology/RuntimeSharedTopology.java",prefix+"topology/TopologyService.java"]) {
        const lines=source(file).split("\n");
        for(let i=0;i<lines.length;i++)if(lines[i].includes("new SharedSectionCandidate.AdmissionRejectedException"))
            prompt+=`${file}:${i+1}\n${lines.slice(Math.max(0,i-7),i+6).join("\n")}\n`;
    }
}
if(topic==="capacity-evidence") {
    const native=read("native-v8/shared-production.json"),owned=read("analysis-supplement-capacity-v1-owned.json");
    prompt+=`当前冻结production 2D9DF2FF444A397574F16D5DBC87E94276DE4B1F074DA42D9354AFBE437572E0；native-v8实际加载相同jar，15场景143控制通过，11实际到达/铁门负候选1/取消1/通用拒绝2，whole增量0。槽限额反射降为0用于天然生产同一槽容量判断；实验结束恢复64。另实际Runtime 64/65及卸载后同typed拒绝已经实跑82控制。无idle eviction；未声称所有五类生产饱和都逐一灌满。当前生命周期另审。测试故障注入不是自然关闭，但核心已有实际关闭控制。
`;
    for(const name of ["capacity-fallback","generic-rejection","unavailable-rejection"])
        prompt+=JSON.stringify(native.cases.find(row=>row.name===name))+"\n";
    prompt+=source("src/test/java/an/production/mixin/SharedSubmissionRejectionMixin.java")
        +source("src/test/java/an/production/mixin/SharedUnavailableRejectionMixin.java")
        +JSON.stringify(owned.checkpoints.filter(row=>row.slots===64))+"\n";
}
if(topic==="owned-mechanism") {
    prompt+=`测量分母是Instrumentation.getObjectSize的身份去重Runtime可达字段+实际worker工作根+实际仍在用旧rows。排除线程、元数据、canonical维度key、诊断hook，含facts/Geometry/容器/future/任务/scratch；不是整个service/world。agent实际捕获task/LocalKernel/BuildScratch/Row/Evaluation/queryImpl TreeMap/持久QueryScratch；按现有task cleanup释放暂存、thread退出释放scratch。暂存到cleanup使控制点成为上界观测，非连续/独占全进程峰值；heap proxy遍历前取，单列不相加。两worker之一阻塞、另一发布新rows，held实际旧rows有版本差。82控制及独立逐类对账通过。只审机制边界。
`+source("src/test/java/com/scarasol/acceleratednavigation/topology/SharedOwnedSize.java")
        +excerpt("src/test/java/com/scarasol/acceleratednavigation/topology/SharedOwnershipAgent.java",
            "    public static void capture(","    static long shallow(");
}
if(topic==="owned-evidence") {
    const result=read("analysis-supplement-capacity-v1-owned.json");
    prompt+=`agent用ASM在实际worker中捕获构造的LocalKernel/BuildScratch/Row/Evaluation/QueryScratch、queryImpl的delta TreeMap及实际task；不改生产字段。异常和正常现有task finally remove清临时根，thread finally清scratch。这个闭包覆盖列出的工作根，不证明所有可能逃逸到栈的中间对象或全进程。flat27facts、door27+air、真实old snapshot/双worker、64/65/卸载实验。只验证数据及结论口径；行预算不能称完整拥有内存限额，无idle eviction是真实观察；本轮必要修订另包审核。独立reader对bytesByClass和objectsByClass逐项求和，union>=runtime，worker数/释放/退出，已通过。
`+JSON.stringify(result)+"\n";
}
if(topic==="capacity-closeout") {
    const closeout=read("closeout-candidate-v5-bounded-v3.json");
    prompt+=`补充容量修订的整体结清，首版candidate-v4已有独立综合通过。candidate-v5只新增包内专用AdmissionRejectedException以及五处共享准入拒绝的类型化和consumer回existingMacro计数；五处producer和完整consumer已分别ACCEPT_WITH_GATES、无必改项。预算、真实partial、最终actual accuracy、当前性、锁外原失败交付、支持范围不变。原macro对容量请求正常执行，禁整条详细回退。
cap-evidence包已接受，门控已核对：15场景143检查，capacity fallback1/真实到达/无共享stage，generic/unavailable注入各命中1且UNAVAILABLE/native0/fallback0；whole始终0。maxSlots反射0与真实64/65是同类型同判断；测试注入不是自然执行器饱和或自然关服，故不声称所有五处逐一饱和。
Runtime1296/scratch3387/既有核心12/补充82通过；旧失败保留。当前新包lifecycle-v4的128控制全过，四条冻结原路线/原参数自然到达，关闭records/active/foreignPaths/pending全部0，关闭服务/调度器拒绝新请求。字节/原输入/PID/退出收尾5542通过。现probe为随后独立冷诊断另编测试包，生产jar始终当前candidate-v5，不复用旧测试class冒充当前冷探针。
完整拥有观察的两包接受，限定Runtime可达+列出的实际worker/旧snapshot工作根，聚合根的测试List壳也在union分母内，所以是有轻微诊断持有开销的上界；非栈所有中间对象、整个导航service/world或全进程连续峰值。64失效slot不idle回收，实际约4MB union控制点，仅32MiB行预算；本轮不称完整内存限额，不做扩容。无需idle eviction也能回原macro继续移动，这一有限修订是否可接受？
`+JSON.stringify({production:closeout.productionSha256,probe:closeout.probeSha256,checks:closeout.checks,
        runs:closeout.runs.map(row=>({run:row.run,rawSha256:row.rawSha256,checks:row.checks,
            pid:row.pid,exit:row.exit,routes:row.routes}))})+"\n";
}
if(!["capacity-consumer","capacity-producers","capacity-evidence","owned-mechanism","owned-evidence","capacity-closeout"].includes(topic))
    throw Error("Explicit supplement topic required");
const round=`supplement-${topic}-v1`,target=resolve(root,`deepseek-prompt-${round}.txt`);
if(existsSync(target))throw Error("Preserve review");
if(Buffer.byteLength(prompt)>6500)throw Error(`Oversize ${Buffer.byteLength(prompt)}`);
writeFileSync(target,prompt);
console.log(JSON.stringify({round,bytes:Buffer.byteLength(prompt)}));
