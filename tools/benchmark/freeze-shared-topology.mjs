import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync, readdirSync } from "node:fs";
import { resolve, dirname, relative } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const workspace = resolve(import.meta.dirname,"../..");
const run = process.argv[2];
if (!/^[a-z0-9-]+$/.test(run ?? "")) throw Error("Fresh safe freeze name required");
const verificationRuns = {
    native: process.argv[3] ?? "native-v6", lifecycle: process.argv[4] ?? "lifecycle-v2",
    core: process.argv[5] ?? "core-v2", reviewCandidate: process.argv[6] ?? run,
};
if (Object.values(verificationRuns).some(value => !/^[a-z0-9-]+$/.test(value)))
    throw Error("Unsafe verification binding");
const root = resolve(workspace,".tmp/shared-topology-production-20261015",run);
if (existsSync(root)) throw Error("Preserve existing frozen candidate");
mkdirSync(root,{recursive:true});
const main = "src/main/java/com/scarasol/acceleratednavigation/";
const test = "src/test/java/com/scarasol/acceleratednavigation/topology/";
const files = [
    ...["BaseClusterTopology","TopologyService","TopologyWorkerRuntime","TopologyTaskExecutor",
        "SharedRows","RuntimeSharedTopology","LocalKernel","RowStorage","QueryScratch","SharedSectionCandidate"]
        .map(name => `${main}topology/${name}.java`),
    ...["SegmentedNavigationService","NativeNavigationSearch","NativeQueryProof","NavigationRequestPath",
        "NavigationCapabilities","AutomaticNavigationBridge"].map(name => `${main}navigation/${name}.java`),
    `${main}scheduler/NavigationScheduler.java`,
    `${main}mixin/ServerNavigationBudgetMixin.java`,
    ...["Fixtures","SharedRuntimeProbe","ScratchControls","OptimizationControls","SharedTopologyReference","SizeAgent"]
        .map(name => `${test}${name}.java`),
    "src/test/java/an/production/SharedTopologyProductionProbe.java",
    "src/test/java/an/production/ProductionNavigationProbe.java",
    "src/test/java/an/production/SharedRejectionControls.java",
    "src/test/java/an/production/mixin/SharedSubmissionRejectionMixin.java",
    "src/test/java/an/production/mixin/SharedUnavailableRejectionMixin.java",
    "src/test/resources/an_performance.mixins.json",
    ...["SharedOwnershipAgent","SharedOwnedSize","SharedTopologySupplementProbe"]
        .map(name => `${test}${name}.java`),
    "docs/designs/2026-10-13-native-detail-navigation-contract-zh.md",
    "docs/designs/2026-10-15-shared-topology-production-scope-zh.md",
    "docs/reports/2026-10-15-shared-topology-production-integration-zh.md",
    "tools/benchmark/run-production-checks.ps1",
    "tools/benchmark/shared-server.properties",
    "tools/benchmark/shared-topology-checks.gradle",
    "build.gradle",
];
const hash = data => createHash("sha256").update(data).digest("hex").toUpperCase();
const identities = [];
for (const path of files) {
    const source = resolve(workspace,path), target = resolve(root,"sources",path);
    mkdirSync(dirname(target),{recursive:true}); copyFileSync(source,target);
    const data = readFileSync(target);
    identities.push({path,bytes:data.length,sha256:hash(data)});
}
const artifacts = [];
for (const [source,name] of [
    ["build/libs/accelerated_navigation-0.1.0-all.jar","production.jar"],
    ["build/reobfProductionProbeJar/output.jar","probe.jar"],
]) {
    const target = resolve(root,"release",name);
    mkdirSync(dirname(target),{recursive:true}); copyFileSync(resolve(workspace,source),target);
    const data = readFileSync(target);artifacts.push({path:`release/${name}`,bytes:data.length,sha256:hash(data)});
}
function classes(directory) {
    const records = [];
    function visit(path) {
        for (const entry of readdirSync(path,{withFileTypes:true})) {
            const child = resolve(path,entry.name);
            if(entry.isDirectory())visit(child);
            else if(entry.name.endsWith(".class")) {
                const data=readFileSync(child);
                records.push({path:relative(directory,child).replaceAll("\\","/"),bytes:data.length,sha256:hash(data)});
            }
        }
    }
    visit(directory);return records;
}
const result = {
    schema:"SHARED_PRODUCTION_FROZEN_CANDIDATE_V1",run,verificationRuns,
    baselineCommit:execFileSync("git",["rev-parse","HEAD"],{cwd:workspace,encoding:"utf8"}).trim(),
    frozenAt:new Date().toISOString(),identities,artifacts,
    mainClasses:classes(resolve(workspace,"build/classes/java/main")),
    testClasses:classes(resolve(workspace,"build/classes/java/test")),
    persistenceIncluded:false,productionApproved:false,
};
writeFileSync(resolve(root,"identities.json"),JSON.stringify(result,null,2)+"\n");
console.log(JSON.stringify({run,sources:identities.length,mainClasses:result.mainClasses.length,artifacts}));
