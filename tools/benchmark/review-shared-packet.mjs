import { readFileSync, writeFileSync, openSync, closeSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { resolve } from "node:path";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const round=process.argv[2];
if(!/^[a-z0-9-]+$/.test(round??""))throw Error("Explicit safe packet name required");
const path=kind=>resolve(root,`deepseek-${kind}-${round}`);
if(existsSync(path("exit")+".json"))throw Error("Preserve prior review");
const settingsPath="/home/administrator/.dsh/settings.yaml";
const settings=readFileSync(settingsPath,"utf8");
const defaults=settings.match(/^agent-default-model:\s*\n((?:[ \t]+.*\n?)*)/m)?.[1]??"";
const provider=defaults.match(/^\s*provider:\s*([^\s#]+)/m)?.[1];
const model=defaults.match(/^\s*model:\s*([^\s#]+)/m)?.[1];
if(provider!=="deepseek"||model!=="deepseek-v4.1-flash")throw Error("Unexpected actual provider/model");
const hash=data=>createHash("sha256").update(data).digest("hex").toUpperCase();
const prompt=readFileSync(path("prompt")+".txt","utf8");
if(Buffer.byteLength(prompt)>6500)throw Error("Packet exceeds 6500 bytes");
writeFileSync(path("transmitted")+".txt",prompt);
const stdout=openSync(path("answer")+".txt","wx"),stderr=openSync(path("process")+".txt","wx");
const started=Date.now();
const child=spawn("/usr/bin/dsh",["--profile","headless",prompt],{
    cwd:root,detached:true,stdio:["ignore",stdout,stderr],
});
console.log(JSON.stringify({round,provider,model,pid:child.pid,promptBytes:Buffer.byteLength(prompt)}));
let timedOut=false,forceTimer;
function terminate(signal) {
    try { process.kill(-child.pid,signal); } catch(error) { if(error.code!=="ESRCH")throw error; }
}
const timer=setTimeout(()=>{
    timedOut=true;terminate("SIGTERM");
    forceTimer=setTimeout(()=>terminate("SIGKILL"),8000);
},240000);
child.on("error",error=>console.error(error.message));
child.on("close",(exitCode,signal)=>{
    clearTimeout(timer);clearTimeout(forceTimer);closeSync(stdout);closeSync(stderr);
    const answer=readFileSync(path("answer")+".txt","utf8"),clean=answer.replaceAll("**","");
    const before=hash(settings),after=hash(readFileSync(settingsPath));
    const result={round,provider,model,exitCode,signal,timedOut,processExited:true,
        elapsedSeconds:(Date.now()-started)/1000,promptBytes:Buffer.byteLength(prompt),promptSha256:hash(prompt),
        settingsSha256Before:before,settingsSha256After:after,settingsModified:before!==after,
        conclusionPresent:/ACCEPT_WITH_GATES|REQUEST_CHANGES/.test(answer),
        mustFixesClear:/本轮必须修改项\s*[:：]\s*无/.test(clean),
        mode:"NATIVE_DSH_HEADLESS_CURRENT_SCOPED_REVIEW",
        includesCurrentPacketSummaries:round.includes("integration"),
    };
    writeFileSync(path("exit")+".json",JSON.stringify(result,null,2)+"\n");
    console.log(JSON.stringify(result));
    process.exitCode=exitCode===0&&!timedOut&&result.conclusionPresent?0:1;
});
