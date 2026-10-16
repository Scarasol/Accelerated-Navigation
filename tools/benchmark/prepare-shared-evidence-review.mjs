import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";

const root = resolve(import.meta.dirname, "../../.tmp/shared-topology-production-20261015");
const candidate = process.argv[2], topic = process.argv[3];
if (!/^[a-z0-9-]+$/.test(candidate ?? "") || !["native", "controls", "integration"].includes(topic))
    throw Error("Explicit frozen candidate and evidence topic required");
const json = path => JSON.parse(readFileSync(resolve(root, path), "utf8").replace(/^\uFEFF/, ""));
const hash = path => createHash("sha256").update(readFileSync(resolve(root, path))).digest("hex").toUpperCase();
const frozen = json(`${candidate}/identities.json`);
const bindings = frozen.verificationRuns ?? {
    native: "native-v6", lifecycle: "lifecycle-v2", core: "core-v2", reviewCandidate: candidate,
};
const nativePath = `${bindings.native}/shared-production.json`;
const native = json(nativePath);
const lifecycle = json(`${bindings.lifecycle}/result.json`);
const reader = json(`analysis-${bindings.native}.json`);
const closeout = json(`closeout-${candidate}-bounded-v3.json`);
let prompt = `独立证据审查，无历史，只审所附机制和数据，不调用工具不追读。中文200字内ACCEPT_WITH_GATES/REQUEST_CHANGES，并明确“本轮必须修改项：无”或具体问题。冻结 ${candidate}，生产${closeout.productionSha256}。
首版仅GROUND,width<=1,heightCells>=2,同section,accuracy=0；其他走现有Base/Super。宏观锚点仅候选，每次从实际实体位置调用真实PathFinder；partial保留。首次共享固定搜索总上限12、恢复8，已有非零额度不扩大，宏观刷新≤1。最终成功须真实canReach且实际accuracy成立。持久化暂停；本包不批准性能、全实体或上层全面共享。
`;
if (topic === "native") {
    prompt += `当前实际发行态原始结果通过${native.checks.length}检查，读取器通过${reader.checks}核对。原结果sha=${hash(nativePath)}，host退出0无超时，raw.jvmPid=${native.jvmPid}且等于启动pid。加载生产和探针jar与冻结完全同hash，进程已退出。
独立读取器逐场景核对预期terminal、scope路由、实际最终floor(position)与固定目标、自然轨迹存在/finite/tick、真实native调用数、flights/captures=0、全部控制断言、生产/测试mod身份。不是只比较摘要。
探针通过正式AutomaticNavigationBridge.move移动；初始化放置一次后只有原版导航运动，不传送修正结果。每场景检测whole native移动调用差额==0；替换旧请求CANCELLED，输入变化触发新真实搜索。native-stop-resume受控调用实际bridge及原版stop，4 tick后确认EXHAUSTED、无额外搜索、active清理，再发正常请求并实际到达；不称自然物理stuck实验。
原v1/v2因spawn-animals=false移除Pig整轮未通过，保留；使用允许动物的隔离配置后v3/v4通过，v5增加停止控制，v6增加API范围外typed rejection。没有删除失败样本。
场景(name/state/actual position/shared requests/native calls)：
`;
    for (const row of native.cases)
        prompt += `${row.name}/${row.state}/${row.position.map(n => n.toFixed(3)).join(",")}/${row.sharedRequests}/${row.nativeCalls}\n`;
    prompt += `最新包完整生产生命周期${lifecycle.checks.length}检查，四条冻结原参数路线自然到达、关闭records/active/foreign/pending=0。路径SUCCEEDED时canReach被生产segmentReachable门禁，accuracy=1的原参数路线保持；不能用此扩大共享accuracy范围。
${lifecycle.checks.filter(row => row.name.startsWith("full-route-natural-arrival")).map(row => row.value).join("\n")}
`;
}
if (topic === "controls") {
    const runtime = json(`${bindings.core}/runtime.json`), scratch = json(`${bindings.core}/scratch.json`);
    const referencePath = "src/test/java/com/scarasol/acceleratednavigation/topology/SharedTopologyReference.java";
    const reference = readFileSync(resolve(root, candidate, "sources", referencePath), "utf8");
    prompt += `最新源码class下Runtime ${runtime.checks.length}、scratch ${scratch.checks.length}检查通过，两个独立JVM正常退出。故障注入InternalError/ThreadDeath的worker栈是预定退出控制，结果passed=true；不把栈当正常运行无异常。
runtime raw=${hash(`${bindings.core}/runtime.json`)}；scratch raw=${hash(`${bindings.core}/scratch.json`)}。
Runtime控制包括同key128订阅合并/leader取消、两实际worker不同geometry合并、中心/halo/load identity失效、TTL、锁外交付、取消/替换、在途关闭、容量拒绝及释放。scratch控制比较allocate/reuse的candidate/state/cells/rows/legal，脏数组、重入、异常finally释放、跨线程拒绝、真实worker与退出清理、旧Outcome在后续查询后不变。
生产worker exclusive scratch由实际任务拥有，重入临时数组；ThreadLocal线程退出finally移除。已发布rows/legal/evaluated不变；Results包含不可变cells与stamps，current动态检查ownerOpen和事实身份。estimated retained只计行载荷，不等于完整堆上限；本轮未用它宣称全内存峰值或性能。
独立参考遍历使用生产完整Base的component/CSR+capability；不同于被测SharedRows查询。引用源码：
${reference}
closeout检查冻结源码与当前源码、253 main class和原test class、两个实际服务器加载jar、原世界输入、实际终态/目标/路径、PID已退出，${closeout.checks}项通过。class/jar身份只证明绑定，不替代上述功能控制。四条原参数路线与共享12场景各单独登记。
`;
}
if (topic === "integration") {
    const reviewCandidate = bindings.reviewCandidate;
    const packets = json(`review-packets-${reviewCandidate}-bounded-v3.json`);
    const progress = json(`review-progress-${reviewCandidate}-bounded-v3.json`);
    if (progress.length !== packets.length) throw Error("All static packets must finish before integration");
    const followups = {
        "api-publication-2": "publication-thread",
        "route-1": "publication-thread",
        "route-2": "routing-contracts",
        "segments-1": "routing-contracts",
        "settlement-2": "capture-recovery",
        "request-2": "capture-recovery",
        "basis-1": "runtime-adapters",
    };
    const revised = candidate !== reviewCandidate ? {
        "basis-1": "failure", "air-1": "air", "air-2": "air",
        "basis-rows-1": "legal", "row-read-1": "row-reader", "local-edges-1": "local-envelope",
    } : {};
    const clear = round => {
        const exit = json(`deepseek-exit-${round}.json`);
        const answer = readFileSync(resolve(root, `deepseek-answer-${round}.txt`), "utf8").replaceAll("**", "");
        return exit.exitCode === 0 && !exit.timedOut && exit.settingsModified === false
            && /ACCEPT_WITH_GATES/.test(answer)
            && /本轮必须修改项\s*[:：]\s*无|本轮无需修改/.test(answer);
    };
    const resolutions = [];
    for (const packet of packets) {
        const key = `${packet.unit}-${packet.part}`;
        const resolution = revised[key] ? `${candidate}-revision-${revised[key]}-v1`
            : clear(packet.round) ? packet.round
            : followups[key] ? `${reviewCandidate}-followup-${followups[key]}-v1` : null;
        if (!resolution || !clear(resolution)) throw Error(`Unresolved ${packet.round}`);
        resolutions.push({ packet: key, resolution });
    }
    for (const evidence of ["native", "controls"])
        if (!clear(`${candidate}-evidence-${evidence}-v1`)) throw Error(`Evidence ${evidence} not accepted`);
    if (candidate !== reviewCandidate) {
        const prior = json(`${reviewCandidate}/identities.json`);
        const oldHashes = new Map(prior.identities.map(row => [row.path, row.sha256]));
        const changed = frozen.identities.filter(row => row.path.startsWith("src/")
            && oldHashes.get(row.path) !== row.sha256).map(row => row.path);
        const allowed = [
            "src/main/java/com/scarasol/acceleratednavigation/topology/RuntimeSharedTopology.java",
            "src/main/java/com/scarasol/acceleratednavigation/topology/SharedRows.java",
            "src/test/java/com/scarasol/acceleratednavigation/topology/SharedRuntimeProbe.java",
        ];
        if (changed.some(path => !allowed.includes(path))) throw Error("Unreviewed source transition");
        prompt += `当前${candidate}相对${reviewCandidate}只有两个生产源变化：SharedRows拒绝门格流体；RuntimeSharedTopology.failBase保留错误类型并复核兴趣/TTL/原事实stamps。SharedRuntimeProbe增加对应拒绝、leader取消/follower、TTL/兴趣及释放控制。其余冻结生产源完全同hash；复用其有效静态分片共识。新air/failure/legal/row-reader四包已分别有效接受，必修无；未把旧候选审核当成自动批准新源码。core-v3发现异常被取消遮蔽的失败保持。
`;
    }
    const runtime = json(`${bindings.core}/runtime.json`), scratch = json(`${bindings.core}/scratch.json`);
    prompt += `整体接入终审：34静态分片全部完成，必须意见已逐项补完整控制流复审。未通过标题或退出0偷换通过。有效共识绑定当前冻结源码，外审只静态核对所附材料，没有独立重演全部实验。原失败、超时、意见与补证均保留。
补证共识：并发keySet且新增仅server、close先copyOf；生产API完成排server TickTask，Work回调server；searchLimit固定总上限，searches独立累加，0仅未初始化；queue执行前live guard；REACHABLE包含真实partial，终局真实canReach+actual accuracy；capture先failure结算返回且正常lease来自private new BaseGraphView；recover上限前++recoveryCount；提交accepted/rejected在runtimeTransition锁内、future完成锁外，普通CompletableFuture重复complete先到生效。
审查覆盖路由/详细段/当前性/结算/清理、共享捕获/准入/基础/发布/关闭、air与halo保真、门依赖/局部查询/合并、分块CSR和worker scratch。native与controls两独立证据包均ACCEPT_WITH_GATES、必修改无。
本轮${native.checks.length}共享实际检查+${lifecycle.checks.length}完整生命周期检查；10实际到达、1铁门正确负候选、1取消；Runtime${runtime.checks.length}/scratch${scratch.checks.length}/现有核心12通过。读取器${reader.checks}；身份/原参数/退出收尾${closeout.checks}，原件hash保持。prod=${closeout.productionSha256}，probe=${closeout.probeSha256}。settings始终071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858，实际deepseek/deepseek-v4.1-flash，原生dsh无头各包独立。
门禁：最大64 Slot/1024 flight/capture、有界估计行载荷；无idle eviction，不宣称长期扩区访问或完整内存峰值已认证。补充另测全拥有对象/旧快照/容量/现有Base-Super冷阶段/表示消融；不把补充、持久化和全面上层共享倒置成已有首版前置。
动态预算接线核查成立，接入前da55e12已push 1.20.1；4..20ms候选预算的正式验收仍未结清，本轮无改预算逻辑/用户世界/落盘v5。共享改动保留工作区，不部署。本轮核心是否可登记有限范围生产接入共识，随后按用户顺序验证补充？只评价已有证据，列真实必修项或明确无。
`;
    writeFileSync(resolve(root, `review-resolutions-${candidate}.json`), JSON.stringify(resolutions, null, 2) + "\n");
}
const round = `${candidate}-${topic === "integration" ? "integration" : "evidence-" + topic}-v1`;
const target = resolve(root, `deepseek-prompt-${round}.txt`);
if (existsSync(target)) throw Error("Preserve prior evidence review");
if (Buffer.byteLength(prompt) > 6500) throw Error("Oversize evidence review");
writeFileSync(target, prompt);
console.log(JSON.stringify({ round, bytes: Buffer.byteLength(prompt),
    sources: frozen.identities.length, nativeRaw: reader.rawSha256 }));
