import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { createHash } from "node:crypto";

const workspace=resolve(import.meta.dirname,"../.."),name=process.argv[2];
if(!/^[a-z0-9-]+$/.test(name??""))throw Error("Safe new cold instrument freeze required");
const root=resolve(workspace,".tmp/shared-topology-production-20261015",name);
if(existsSync(root))throw Error("Preserve freeze");
mkdirSync(root,{recursive:true});
const hash=bytes=>createHash("sha256").update(bytes).digest("hex").toUpperCase();
const files=["ColdTopologyProbe","ColdTopologyTelemetry","ProductionNavigationProbe","PerformanceScheduleTelemetry",
    "PerformanceTickTelemetry"].map(n=>`src/test/java/an/production/${n}.java`);
files.push(...["BaseStages","SuperStages","RuntimeStages","TaskStages","MacroStages","Publication","NativeStages"]
    .map(n=>`src/test/java/an/production/mixin/Cold${n}Mixin.java`),
    "src/test/resources/an_cold.mixins.json","tools/benchmark/production-probe.gradle","tools/benchmark/run-production-checks.ps1",
    "tools/benchmark/shared-server.properties","tools/benchmark/analyze-cold-topology.mjs");
const identities=[];
for(const path of files){
    const target=resolve(root,"sources",path);mkdirSync(dirname(target),{recursive:true});
    copyFileSync(resolve(workspace,path),target);
    const bytes=readFileSync(target);identities.push({path,sha256:hash(bytes),bytes:bytes.length});
}
copyFileSync(resolve(workspace,"build/reobfColdTopologyProbeJar/output.jar"),resolve(root,"probe.jar"));
const result={schema:"COLD_TOPOLOGY_INSTRUMENT_FREEZE_V1",name,frozenAt:new Date().toISOString(),
    probeSha256:hash(readFileSync(resolve(root,"probe.jar"))),identities,
    productionCandidate:"candidate-v5",baseline:"baseline-da55e12",diagnosisOnly:true};
writeFileSync(resolve(root,"identities.json"),JSON.stringify(result,null,2)+"\n");
console.log(JSON.stringify({name,probeSha256:result.probeSha256,files:identities.length}));
