import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { spawn } from "node:child_process";

const root=resolve(import.meta.dirname,"../../.tmp/shared-topology-production-20261015");
const candidate=process.argv[2],campaign=process.argv[3];
if(!/^[a-z0-9-]+$/.test(candidate??"")||!/^[a-z0-9-]+$/.test(campaign??""))
    throw Error("Explicit safe candidate and campaign required");
const packets=JSON.parse(readFileSync(resolve(root,`review-packets-${candidate}-${campaign}.json`),"utf8"));
const runner=resolve(import.meta.dirname,"review-shared-packet.mjs");
const results=[];
for(const packet of packets) {
    const exitPath=resolve(root,`deepseek-exit-${packet.round}.json`);
    if(!existsSync(exitPath)) {
        const code=await new Promise((done,reject)=>{
            const child=spawn(process.execPath,[runner,packet.round],{stdio:"inherit"});
            child.on("error",reject);child.on("close",done);
        });
        console.log(JSON.stringify({packet:packet.round,runnerExit:code}));
    }
    const result=JSON.parse(readFileSync(exitPath,"utf8"));
    results.push({...packet,...result});
    writeFileSync(resolve(root,`review-progress-${candidate}-${campaign}.json`),JSON.stringify(results,null,2)+"\n");
}
console.log(JSON.stringify({candidate,campaign,finished:results.length,
    conclusions:results.filter(r=>r.conclusionPresent&&!r.timedOut).length,
    clear:results.filter(r=>r.mustFixesClear&&!r.timedOut).length}));
