import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const workspace=resolve(import.meta.dirname,"../.."),root=resolve(workspace,".tmp/shared-topology-production-20261015");
const stage=process.argv[2];
if(!["before-final","complete"].includes(stage))throw Error("Explicit closeout stage required");
const output=resolve(root,`supplement-closeout-${stage}-v1.json`);
if(existsSync(output))throw Error("Preserve closeout");
const read=file=>JSON.parse(readFileSync(resolve(root,file),"utf8").replace(/^\uFEFF/,""));
const hash=bytes=>createHash("sha256").update(bytes).digest("hex").toUpperCase();
let checks=0;
const identities=[];
function check(value,label){checks++;if(!value)throw Error(label);}
function identity(path,sha) {
    const bytes=readFileSync(path),actual=hash(bytes);check(actual===sha,"identity "+path);
    identities.push({path,sha256:actual,bytes:bytes.length});
}
const production=read("candidate-v5/identities.json");
for(const row of production.identities.filter(r=>r.path.startsWith("src/main/")))
    identity(resolve(workspace,row.path),row.sha256);
for(const row of production.mainClasses)
    identity(resolve(workspace,"build/classes/java/main",row.path),row.sha256);
for(const artifact of production.artifacts)
    identity(resolve(root,"candidate-v5",artifact.path),artifact.sha256);
const closeout=read("closeout-candidate-v5-bounded-v3.json");
check(closeout.passed&&closeout.checks===5542,"original current-byte lifecycle closeout");
const owned=read("analysis-supplement-capacity-v1-owned.json"),cold=read("analysis-cold-matrix-v3.json");
check(owned.passed&&cold.passed,"independent owned/cold readers");
const budgets=read("cold-budget-qualification-v1.json");
check(budgets.passed&&budgets.checks===54&&budgets.observations.length===18,"actual budget qualification");
identity(resolve(root,"supplement-capacity-v1/supplement.json"),owned.rawSha256);
for(const row of cold.observations) {
    identity(resolve(root,row.run,"cold-topology.json"),row.rawSha256);
    const exit=read(`${row.run}/exit.json`);
    check(row.pid===exit.pid&&exit.exitCode===0&&!exit.timedOut,"actual cold exit");
    check(execFileSync("powershell.exe",["-NoProfile","-Command",
        `if (Get-Process -Id ${Number(exit.pid)} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
        {encoding:"utf8"}).trim()==="dead","cold JVM no longer running");
}
const instrument=read("cold-instrument-v3/identities.json");
identity(resolve(root,"cold-instrument-v3/probe.jar"),instrument.probeSha256);
for(const row of instrument.identities) {
    identity(resolve(root,"cold-instrument-v3/sources",row.path),row.sha256);
    identity(resolve(workspace,row.path),row.sha256);
}
const rounds=["candidate-v4-integration-v1",
    ...["capacity-consumer","capacity-producers","capacity-evidence","owned-mechanism","owned-evidence","capacity-closeout"]
        .map(topic=>`supplement-${topic}-v1`),
    "supplement-cold-intervals-v2","supplement-cold-cold-identity-v2",
    "supplement-cold-evidence-v1","supplement-cold-directions-v2","supplement-cold-identity-followup-v1"];
if(stage==="complete")rounds.push("supplement-integration-v1");
const settings="071DB56517E8F97CCDE780095F71E5B264A1C7BC41694499CF81AF7C2ACA1858";
const reviews=[];
for(const round of rounds) {
    const exit=read(`deepseek-exit-${round}.json`),answer=readFileSync(resolve(root,`deepseek-answer-${round}.txt`));
    check(exit.exitCode===0&&!exit.timedOut&&exit.processExited===true,"normal actual review exit");
    check(exit.provider==="deepseek"&&exit.model==="deepseek-v4.1-flash","authorized actual provider/model");
    check(!exit.settingsModified&&exit.settingsSha256Before===settings&&exit.settingsSha256After===settings,"settings unchanged");
    check(exit.mustFixesClear&&answer.toString().includes("ACCEPT_WITH_GATES"),"effective bounded acceptance");
    check(exit.promptBytes<=6500,"independent bounded packet");
    identity(resolve(root,`deepseek-prompt-${round}.txt`),exit.promptSha256);
    identity(resolve(root,`deepseek-transmitted-${round}.txt`),exit.promptSha256);
    reviews.push({round,exitCode:exit.exitCode,promptBytes:exit.promptBytes,answerSha256:hash(answer)});
}
const release=read("current-release-v1/equivalence.json");
check(release.passed&&release.entries.length===273,"actual current release payload equivalence");
check(release.entries.every(row=>row.candidateSha256===row.currentSha256||row.entry==="META-INF/MANIFEST.MF"),
    "all compiled classes/resources/dependencies identical by content");
identity(resolve(root,"current-release-v1/production.jar"),release.productionSha256);
identity(resolve(workspace,"build/libs/accelerated_navigation-0.1.0-all.jar"),release.productionSha256);
identity(resolve(root,"current-release-v1/probe.jar"),release.probeSha256);
const currentNative=read("analysis-native-v9.json"),currentLaunch=read("native-v9/launch-environment.json");
check(currentNative.passed&&currentNative.nativeChecks===143,"current package real native checks");
const mods=new Map(currentLaunch.mods.map(row=>[row.name,row.sha256]));
check(mods.get("accelerated_navigation.jar")===release.productionSha256&&mods.get("production-probe.jar")===release.probeSha256,
    "current package actual loaded jars");
const currentExit=read("native-v9/exit.json");
check(currentExit.exitCode===0&&!currentExit.timedOut&&currentExit.pid===currentNative.nativeJvmPid,"current native exit");
check(execFileSync("powershell.exe",["-NoProfile","-Command",
    `if (Get-Process -Id ${Number(currentExit.pid)} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
    {encoding:"utf8"}).trim()==="dead","current native JVM stopped");
const result={schema:"SHARED_SUPPLEMENT_CLOSEOUT_V1",stage,passed:true,checks,
    productionSha256:production.artifacts[0].sha256,coldProbeSha256:instrument.probeSha256,
    currentReleaseSha256:release.productionSha256,currentProbeSha256:release.probeSha256,
    currentNativeChecks:currentNative.nativeChecks,currentNativeReaderChecks:currentNative.checks,
    remoteBaseline:"da55e121c8775d32da74f843aaa27023d839ded0",ownedChecks:owned.checks,
    coldChecks:cold.checks,coldRuns:cold.observations.length,identities,reviews,
    unresolvedMandatoryFixes:[],superseded:[
        {round:"supplement-capacity-plan-v1",reason:"conditions resolved by six implementation/evidence/closeout packets; plan alone was not approval"},
        {round:"supplement-cold-intervals-v1",reason:"unsent prompt retained, corrected step-between dependency wait in v2"},
        {round:"supplement-cold-directions-v1",reason:"unsent prompt retained, corrected step-between dependency wait in v2"},
        {round:"supplement-cold-cold-identity-v1",reason:"unsent prompt retained, policy range corrected to permit zero grants in v2"}],
    failedColdRunsRetained:["cold-diagnostic-v1-baseline-super","cold-diagnostic-v2-baseline-super"],
    persistenceApproved:false,performanceApproved:false,fullProcessPeakApproved:false,
    upperGraphSharingImplemented:false,sharedChangesPushed:false};
writeFileSync(output,JSON.stringify(result,null,2)+"\n");
console.log(JSON.stringify({stage,checks,reviews:reviews.length,coldRuns:result.coldRuns,passed:true}));
