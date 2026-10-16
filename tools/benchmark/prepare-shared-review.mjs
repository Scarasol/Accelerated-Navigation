import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";

const workspace=resolve(import.meta.dirname,"../..");
const root=resolve(workspace,".tmp/shared-topology-production-20261015");
const candidate=process.argv[2];
const campaign=process.argv[3]??"bounded-v2";
if(!/^[a-z0-9-]+$/.test(candidate??""))throw Error("Explicit frozen candidate required");
if(!/^[a-z0-9-]+$/.test(campaign))throw Error("Safe review campaign required");
const frozen=resolve(root,candidate,"sources");
const topology="src/main/java/com/scarasol/acceleratednavigation/topology/";
const navigation="src/main/java/com/scarasol/acceleratednavigation/navigation/";
const text=path=>readFileSync(resolve(frozen,path),"utf8").replaceAll("\r\n","\n");
function method(path,signature) {
    const source=text(path),start=source.indexOf(signature);
    if(start<0)throw Error(`Missing ${signature}`);
    const open=source.indexOf("{",start);
    let depth=0,quote="",comment="";
    for(let i=open;i<source.length;i++) {
        const c=source[i],next=source[i+1];
        if(comment==="line") { if(c==="\n")comment="";continue; }
        if(comment==="block") { if(c==="*"&&next==="/"){comment="";i++;}continue; }
        if(quote) { if(c==="\\"){i++;continue;}if(c===quote)quote="";continue; }
        if(c==="/"&&next==="/"){comment="line";i++;continue;}
        if(c==="/"&&next==="*"){comment="block";i++;continue;}
        if(c==='"'||c==="'"){quote=c;continue;}
        if(c==="{")depth++;
        if(c==="}"&&--depth===0) {
            const line=source.slice(0,start).split("\n").length;
            return `${path}:${line}\n${source.slice(start,i+1)}\n`;
        }
    }
    throw Error(`Unclosed ${signature}`);
}
const service=navigation+"SegmentedNavigationService.java";
const runtime=topology+"RuntimeSharedTopology.java";
const rows=topology+"SharedRows.java";
const storage=topology+"RowStorage.java";
const kernel=topology+"LocalKernel.java";
const executor=topology+"TopologyTaskExecutor.java";
const units=[
    ["api-publication","字段：private final Set<CompletableFuture<SharedSectionCandidate>> sharedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();\n"
        +"新增请求只在requireServerThread后的server线程发生；跨线程只能移除已完成/取消请求，不能新增。调用方先supports(actualAccuracy)，false直接用旧macro，不调用共享API。\n"
        +text(topology+"SharedSectionCandidate.java")
        +method(topology+"TopologyService.java","public CompletableFuture<SharedSectionCandidate> requestSharedSection(")],
    ["route",method(service,"private void macroCandidate(")+method(service,"private void sharedCandidate(")],
    ["segments",method(service,"private void nextSegment(").split("if(work.cursor>=work.edges.size())")[0]
        +"\n// remaining branch is unchanged legacy corridor preparation\n}\n"
        +method(service,"private void findSegment(")],
    ["current",method(service,"public boolean executionReady(")+method(service,"private boolean planningCurrent(")
        +method(service,"private boolean intermediateArrival(")+method(service,"private void rescheduleStaleInput(")],
    ["settlement",method(service,"private void settle(")+method(service,"private void recover(")],
    ["cleanup",method(service,"private void replan(")+method(service,"private void closeWindow(")
        +method(service,"private void cancelShared(")+method(service,"private void finish(")],
    ["request",method(runtime,"private CompletableFuture<Result> request(")],
    ["admission",method(runtime,"private void admit(")+method(runtime,"private void cancelWaiter(")],
    ["basis",method(runtime,"private void launchBase(")+method(runtime,"private void failBase(")
        +method(runtime,"private void retire(")],
    ["publication",method(runtime,"private void finish(")],
    ["runtime-close",method(runtime,"void close()")+method(runtime,"private void run(")
        +method(topology+"TopologyWorkerRuntime.java","void beginStopping()")],
    ["air",method(rows,"static BaseClusterTopology.BuildInput airInput(")
        +method(rows,"static BaseClusterTopology.PackedFacts airFacts(BaseClusterTopology.PackedFacts facts,")
        +method(topology+"BaseClusterTopology.java","BuildInput withFacts(")],
    ["basis-rows",method(rows,"static SharedRows create(BaseClusterTopology.BuildInput raw, boolean packed)")
        +method(rows,"boolean inherits(")+method(rows,"boolean sensitive(")+method(rows,"private boolean legal(")
        +method(rows,"private RowStorage row(")],
    ["known-query",method(rows,"Outcome query(Query query, QueryScratch scratch, QueryHook hook)")
        +method(rows,"private List<Integer> known(Query query, QueryScratch active, QueryHook hook)")],
    ["local-query",method(rows,"private Outcome queryImpl(")],
    ["merge",method(rows,"SharedRows merge(")],
    ["row-storage",method(storage,"RowStorage merge(")+method(storage,"private static Block pack(")],
    ["row-read",text(storage).slice(0,text(storage).indexOf("RowStorage merge("))+"\n}\n"],
    ["local-anchors",text(kernel).slice(0,text(kernel).indexOf("private void preparePrism("))+"\n}\n"],
    ["local-edges",method(kernel,"private void preparePrism(")+method(kernel,"private void putPrism(")
        +method(kernel,"Row outgoing(")],
    ["scratch",text(topology+"QueryScratch.java")+method(executor,"QueryScratch currentQueryScratch()")
        +method(executor,"private void run()")+method(executor,"private void runTasks()")],
];
const rules=`独立代码审查。本次无头对话无历史，只审附带片段。不要调用工具或追读文件；需补上下文时列出具体接口，不推测实现。
用户授权共享首版接入，持久化暂停。基线da55e12已push；动态预算0..20ms候选已接线、验收未结清，本轮不改。
范围：GROUND,width<=1,heightCells>=2,同section,accuracy=0；其余沿现有Base/Super。宏观只给候选，真实PathFinder产出真实节点/partial，最终成功须真实可达且实际最终accuracy到达。禁止人工前置通行资格过滤和地面整条执行回退。
接口契约：sharedTransition持runtimeLock；sharedAfter/finishFuture锁外回调。Work仅server线程访问，sharedInterest是AtomicBoolean。queueServer排回server；关停主动取消待发布future。ownerOpen在beginStopping置false；输入stamps是原Runtime原子当前性身份。每任务独占BuildScratch；worker查询数组独占、重入临时数组。已发布rows/legal/evaluated不可变。
共享段至多4连接，中间真实节点耗尽后从实际位置继续；物理native-stop直接EXHAUSTED。现有参数和上限不扩大。scope外输入只转原宏观候选。
仅找片内实际bug/竞态/语义回归，不把持久化或后续全面共享列为必修。输出中文200字以内：ACCEPT_WITH_GATES或REQUEST_CHANGES；明确“本轮必须修改项：无”或具体问题。非阻断建议另列。不要修改文件。
`;
const packets=[];
for(const [name,source] of units) {
    const common=rules+`\n冻结候选 ${candidate}；审查单元 ${name}。本包代码如下：\n`;
    const budget=4500-Buffer.byteLength(common)-200;
    const chunks=[];let chunk="";
    for(const line of source.split("\n")) {
        if(Buffer.byteLength(chunk+"\n"+line)>budget&&chunk) {chunks.push(chunk);chunk="";}
        chunk+=line+"\n";
    }
    if(chunk)chunks.push(chunk);
    for(let i=0;i<chunks.length;i++) {
        const round=`${candidate}-${campaign}-${name}-${i+1}`;
        const prompt=common+`\n本包为该单元第${i+1}/${chunks.length}片，只审核片内逻辑；其余片会独立审查并综合。\n`+chunks[i];
        const target=resolve(root,`deepseek-prompt-${round}.txt`);
        if(existsSync(target))throw Error("Preserve prior prompt");
        if(Buffer.byteLength(prompt)>4500)throw Error(`Oversize ${round}`);
        writeFileSync(target,prompt);
        packets.push({round,unit:name,part:i+1,parts:chunks.length,bytes:Buffer.byteLength(prompt),
            sha256:createHash("sha256").update(prompt).digest("hex").toUpperCase()});
    }
}
writeFileSync(resolve(root,`review-packets-${candidate}-${campaign}.json`),JSON.stringify(packets,null,2)+"\n");
console.log(JSON.stringify({candidate,packets:packets.length,maxBytes:Math.max(...packets.map(p=>p.bytes))}));
