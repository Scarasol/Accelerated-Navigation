import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../../.tmp/shared-topology-production-20261015");
const candidate = process.argv[2];
const topic = process.argv[3] ?? "publication-thread";
if (!/^[a-z0-9-]+$/.test(candidate ?? "")) throw Error("Explicit candidate required");
const file = "src/main/java/com/scarasol/acceleratednavigation/topology/TopologyService.java";
const source = readFileSync(resolve(root, candidate, "sources", file), "utf8").replaceAll("\r\n", "\n");
function between(start, end) {
    const at = source.indexOf(start), until = source.indexOf(end, at);
    if (at < 0 || until < at) throw Error("Missing frozen excerpt");
    return `${file}:${source.slice(0, at).split("\n").length}\n${source.slice(at, until)}\n`;
}
let prompt = `独立复审，只有附带代码，不调用工具、不追读。冻结 ${candidate}。输出中文200字内，ACCEPT_WITH_GATES或REQUEST_CHANGES，并明确本轮必须修改项。
此前分片漏带声明，意见为：result.whenComplete锁外删除导致CME/size上限竞态；消费者whenComplete在queue前读Work可能在worker线程。请依下列完整生产API核对是否成立，缺少其他实现时只列待确认接口，勿推测为bug。
事实：requireServerThread后才能新增请求；集合是ConcurrentHashMap.newKeySet，其他线程仅删除；close在server线程遍历List.copyOf后取消。Work调用API且注册whenComplete也在server线程。API同步failedFuture在调用server线程执行注册回调；异步结果完成在queueServer的server TickTask中。scheduler.account只计费，Work后续queue是server调度，不承担此API切线程职责。
以下是冻结源码完整方法及实际字段/关闭摘录。持久化、性能和支持范围不在本包，不修改文件。
private final Set<CompletableFuture<SharedSectionCandidate>> sharedRequests =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
关闭实际语句：
for (var request : List.copyOf(sharedRequests)) request.cancel(false);
sharedRequests.clear();
`
    + between("    public CompletableFuture<SharedSectionCandidate> requestSharedSection(",
        "    public Map<String, Long> sharedTopologyMetrics()")
    + between("    private void queueServer(Runnable action)", "    private boolean resultCurrent(");
if (topic === "routing-contracts") {
    const navigationFile = "src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java";
    const navigation = readFileSync(resolve(root, candidate, "sources", navigationFile), "utf8")
        .replaceAll("\r\n", "\n");
    function excerpt(start, end) {
        const at = navigation.indexOf(start), until = navigation.indexOf(end, at);
        if (at < 0 || until < at) throw Error("Missing routing excerpt");
        return `${navigationFile}:${navigation.slice(0, at).split("\n").length}\n${navigation.slice(at, until)}\n`;
    }
    prompt = `独立复审冻结 ${candidate}，只核对所附材料，不追读、不调用工具。中文200字内，ACCEPT_WITH_GATES/REQUEST_CHANGES并写“本轮必须修改项：无”或具体问题。
前片意见：searchLimit耗尽后变0重置扩额；findSegment延后回调可能停机访问旧Work；partial置REACHABLE可能伪成功。实际searchLimit是固定总上限，累计值另为searches；0仅未初始化值。全部赋值只有首次shared=12/8、首次legacy=Math.max(12,(edges.size()+1)*3)/Math.max(8,(edges.size()+1)*2)，均if(searchLimit==0)，无递减/清零代码。请求替换建立新Work，旧Work.finish退出records。候选索引在macroCandidate开头>=targets.size即UNAVAILABLE；shared回调队列之前只有server访问，已由完整API补证。
REACHABLE是“有真实Path待执行”，含原生partial；不是最终canReach/成功。真实canReach保存segmentReachable/nativeReachable。终局必须segmentReachable且actual accuracy到达；native-stop共享直接EXHAUSTED。真实搜索后nativeProof和共享current在每次实体执行前复查；旧宏观只可触发有界刷新，不能通过旧Path实际执行。
下附队列完整守卫与等待恢复入口、真实搜索写入及完整settle。是否有当前证据可成立的必修bug？缺额外接口时列待确认，不按猜测把partial改成过滤无路。
`
        + excerpt("    private boolean live(Work work)", "    private void initialize(Work work)")
        + excerpt("                if(work.waitingSearch!=null", "                if(!work.request.adopted&&tick-work.request.createdTick")
        + excerpt("        if(++work.searches>work.searchLimit)", "    private void nativeWhole(Work work)")
        + excerpt("    private void settle(Work work)", "    private void rescheduleStaleInput(Work work)");
}
if (topic === "capture-recovery") {
    function frozenExcerpt(path, start, end) {
        const full = readFileSync(resolve(root, candidate, "sources", path), "utf8").replaceAll("\r\n", "\n");
        const at = full.indexOf(start), until = full.indexOf(end, at);
        if (at < 0 || until < at) throw Error("Missing recovery excerpt");
        return `${path}:${full.slice(0, at).split("\n").length}\n${full.slice(at, until)}\n`;
    }
    const runtimeFile = "src/main/java/com/scarasol/acceleratednavigation/topology/RuntimeSharedTopology.java";
    const navigationFile = "src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java";
    prompt = `独立复审冻结 ${candidate}，无历史，只审附带完整相关控制流，不追读不调用工具。中文200字内：ACCEPT_WITH_GATES/REQUEST_CHANGES；必须明确“本轮必须修改项：无”或具体问题。
此前两截断片意见：capture异常/取消时lease=null仍close导致NPE且waiter挂起；recover中recoveryCount未增加导致无限重试。以下完整摘录核实它们：capture回调首先failure!=null结算返回；source失败向capture异常结算，成功捕获值来自private requestBaseGraph的new BaseGraphView，正常null不可达。leaseClose保护在catch。recover实际在上限检查之前++recoveryCount，searches计数另有总上限12；refresh最多一次，replan不清零计数。
锁约束：sharedTransition持runtimeLock，sharedComplete排锁外完成，sharedAfter锁外提交。捕获close自身只进行原子release和runtime重入转换。Work只在server线程。持久化/性能/上层共享不在此包，请勿凭未给代码猜测问题。
`
        + frozenExcerpt(runtimeFile, "        capture.whenComplete((lease, failure) -> {",
            "        return waiter.result;\n    }\n    private void cancelWaiter")
        + frozenExcerpt(navigationFile, "    private void recover(Work work)", "    private boolean entityDrifted(Work work)");
}
if (topic === "runtime-adapters") {
    const runtimeFile = "src/main/java/com/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime.java";
    const full = readFileSync(resolve(root, candidate, "sources", runtimeFile), "utf8").replaceAll("\r\n", "\n");
    function excerpt(start, end) {
        const at = full.indexOf(start), until = full.indexOf(end, at);
        if (at < 0 || until < at) throw Error("Missing adapter excerpt");
        return `${runtimeFile}:${full.slice(0, at).split("\n").length}\n${full.slice(at, until)}\n`;
    }
    prompt = `独立复审冻结 ${candidate}，只审以下接口实现，不调用工具不追读。中文200字内，ACCEPT_WITH_GATES/REQUEST_CHANGES，明确“本轮必须修改项：无”或具体问题。
前片意见：submitSharedWork rejected回调直接failBase或finish可能锁外改共享Map；failBase先以真实failure sharedComplete(slot.base)再retire以CancellationException complete同一future可能双完成或掩盖真因。实际slot.base是new CompletableFuture<SharedRows>()，无子类或自定义完成行为；JDK complete/completeExceptionally先到生效，后到返回false，回调异常由依赖stage持有，不抛回complete；共享future即此具体类型。
下面是生产适配与完整提交/完成helper。runtimeTransition在synchronized(runtimeLock)中运行command，嵌套直接command.get；退出锁后按入队顺序执行deferred。runRuntimeTransition即runtimeTransition(false, ...)。failBase/retire由此rejected/accepted或显式sharedTransition执行，均持runtimeLock。禁止把未附接口推测为必修竞态；有真实问题请指出实际控制流。
`
        + excerpt("    <T> T sharedTransition(", "    private static final Direction[] DIRECTIONS")
        + excerpt("    private void afterRuntimeLock(Runnable action)", "    private void reprioritizeTask(")
        + excerpt("    private <T> void finishFuture(", "    private <T> void forwardFuture(");
}
if (!["publication-thread", "routing-contracts", "capture-recovery", "runtime-adapters"].includes(topic))
    throw Error("Unknown followup topic");
const round = `${candidate}-followup-${topic}-v1`;
const target = resolve(root, `deepseek-prompt-${round}.txt`);
if (existsSync(target)) throw Error("Preserve prior followup");
if (Buffer.byteLength(prompt) > 6500) throw Error("Oversize followup");
writeFileSync(target, prompt);
console.log(JSON.stringify({ round, bytes: Buffer.byteLength(prompt) }));
