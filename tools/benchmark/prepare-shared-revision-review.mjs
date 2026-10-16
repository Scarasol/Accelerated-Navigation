import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../../.tmp/shared-topology-production-20261015");
const candidate = process.argv[2], topic = process.argv[3];
if (!/^[a-z0-9-]+$/.test(candidate ?? "") || !["air", "failure", "legal", "row-reader", "local-envelope"].includes(topic))
    throw Error("Explicit revision candidate and topic required");
const topology = "src/main/java/com/scarasol/acceleratednavigation/topology/";
function excerpt(file, start, end) {
    const source = readFileSync(resolve(root, candidate, "sources", file), "utf8").replaceAll("\r\n", "\n");
    const at = source.indexOf(start), until = source.indexOf(end, at);
    if (at < 0 || until < at) throw Error("Missing revision excerpt");
    return `${file}:${source.slice(0, at).split("\n").length}\n${source.slice(at, until)}\n`;
}
let prompt = `独立复审冻结${candidate}，无历史，只审附带完整控制流，不调用工具不追读。中文200字内ACCEPT_WITH_GATES/REQUEST_CHANGES，写“本轮必须修改项：无”或具体问题。首版GROUND,width<=1,heightCells>=2,同section,accuracy0；其他现有Base/Super。宏观只候选、真实PathFinder/partial/实际最终到达；持久化暂停，不批准性能或扩大范围。
`;
if (topic === "air") {
    prompt += `前片担心去门会丢FLUID和就地修改事实。本版明确门格FLUID及门上空碰撞流体支撑均抛专用UnsupportedInputException，消费者仅该异常回到现有宏观，不转整条详细执行。完整源码中cells()返回新解包数组；raw/halo不修改。withFacts保留offset/revision，重算转换facts的fingerprint。真实发布身份始终取原始Base lease的stamps，不用人工air事实给真实输入资格。
新Runtime1296、scratch3387及现有核心12通过；新控制typed异常、raw不变、lease/slot释放通过。当前仅审输入转换；失败类型交付另有独立包，勿重复推测。
`
        + excerpt(topology + "SharedRows.java", "    static BaseClusterTopology.BuildInput airInput(",
            "    boolean inherits(")
        + excerpt(topology + "BaseClusterTopology.java", "        BuildInput withFacts(", "        int referenceBytes()")
        + excerpt(topology + "BaseClusterTopology.java", "        byte[] cells()", "        PackedFacts withChanges(");
}
if (topic === "failure") {
    prompt += `新增控制复现旧failBase：先排base失败，再retire会让等待flight只收到Cancellation，专用UnsupportedInput被遮蔽而无法进入既定宏观回退。本版在retire前摘出对应flight等待者，锁外交付原失败，交付复核ownerOpen、interest、原事实stamps和TTL。lease.close使lease.current=false，故error交付用原事实stamp，而不检查已主动释放的lease。只交付异常，不可能生成成功Result。
slot.base是普通new CompletableFuture，首次完成生效；base回调看到flight已删除即返回，不双结算。所有调用持runtimeLock，sharedAfter/sharedComplete退锁执行；submitSharedWork的accepted/rejected会重新进入runtimeTransition持锁，已有接口复审通过。ownerOpen在beginStopping原子false；SectionStamp.current绑定原事实版本/load身份，与lease.released独立。
新Runtime1296通过，实际before-base阻塞：leader取消、同key follower保留typed Unsupported、过期订阅Timeout、兴趣false取消，原raw facts不变、slots/pins/flight归零。保留core-v3旧实现失败原件。其他输入/取消/TTL/关闭控制及scratch3387/核心12通过。
`
        + excerpt(topology + "RuntimeSharedTopology.java", "    private void launchBase(",
            "    private void run(Flight flight)")
        + excerpt(topology + "RuntimeSharedTopology.java", "    private void retire(",
            "    void close()");
}
if (topic === "legal") {
    prompt += `前片条件意见：“若0是已评估非法，则legal会错误继承base，需要改按evaluated统一”。真实编码为0未知、1非法、2合法；outgoing首先legal(cell)，所以已求出的行必有1/2合法性，空行也替换；evaluated只描述出边行是否完整生成，不能代替合法性是否已检查。查询可能只检查邻anchor合法性，尚未生成该格出边，故两状态独立有必要。
请依据以下实际设置/读取/合并，核对旧必修是否成立。不要假设0含非法；独立完整Base CSR参考、脏数组及旧快照控制已经通过。不开门door替换仍遵循此编码。
`
        + excerpt(topology + "LocalKernel.java", "    boolean legal(int cell)", "    boolean prism(")
        + excerpt(topology + "LocalKernel.java", "    Row outgoing(int cell)", "\n}")
        + excerpt(topology + "SharedRows.java", "    private boolean legal(", "    List<Integer> known(")
        + excerpt(topology + "SharedRows.java", "    SharedRows merge(", "    static boolean same(");
}
if (topic === "row-reader") {
    prompt += `原read分片超时，无结论，重送完整读取代码。内部调用域cell=0..4095，edge<count(cell)，由Query验证和BFS循环限制；delta只有非null精确Row，空Row显式替换。base是create(rows,packed)且其Block无inherited位；layer是empty(packed,base.rows)再merge，只有basis非null且basis.same(targets,masks)才写inherited位。pack同时校验offset<=65535及targets在0..4095。64格位图与CSR数组已在Runtime1296/scratch3387及独立完整Base参考控制验证。
下面完整字段/构造/空块/读方法是否有实际读取或所有权bug？仅需核对该方法范围，不把持久化或新压缩当必修。
`
        + excerpt(topology + "RowStorage.java", "    static final int SHIFT", "    RowStorage merge(");
}
if (topic === "local-envelope") {
    prompt += `前片意见：horizontal=1/dy>=0时preparePrism没补目标列头，可能穿入方块，heightCells>2只覆两格。实际outgoing在preparePrism之前legal(target)，legal调用anchor(cell,true)扫描整个body.heightCells并验证volumeOpen、fluid与底部groundOpen；prism调用anchor(cell,false)同样扫描完整body高度。掩码位代表“整个身体体积可用的anchor”，不是单个方块；yy/yy+1是两种中间anchor高度，非身体只有两格。preparePrism只填movementEnvelopeOpen实际读取的中间列/落差shaft，终点全身由legal负责。以下完整相关控制流是否足以撤回必修意见？不追读、缺接口只列待确认。
Runtime1296/scratch3387、完整Base CSR参考、step/drop/jump掩码及高身体控制通过；本包不把宏观边升为原版可达性。
`
        + excerpt(topology + "LocalKernel.java", "    boolean legal(int cell)", "    Map<String, Long> work()");
}
const round = `${candidate}-revision-${topic}-v1`;
const target = resolve(root, `deepseek-prompt-${round}.txt`);
if (existsSync(target)) throw Error("Preserve prior revision review");
if (Buffer.byteLength(prompt) > 6500) throw Error(`Oversize revision ${Buffer.byteLength(prompt)}`);
writeFileSync(target, prompt);
console.log(JSON.stringify({ round, bytes: Buffer.byteLength(prompt) }));
