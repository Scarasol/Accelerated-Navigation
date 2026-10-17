import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

export function verdict(answer) {
  const plain=answer.replaceAll("**","");
  return {
    conclusion:plain.match(/^\s*(?:#+\s*)?(ACCEPT_WITH_GATES|ACCEPT_SCOPED|REQUEST_CHANGES|READY_FOR_INTEGRATION_VALIDATION)\b/m)?.[1]??null,
    mustFixesClear:/本轮必须修改项\s*[:：]\s*无/.test(plain)
  };
}

async function main() {
  const [packet,output]=process.argv.slice(2);
  if(!packet||!output||process.argv.length!==4)
    throw Error("Usage (WSL): node tools/review/deepseek-review.mjs PACKET NEW_OUTPUT_DIRECTORY");
  const prompt=fs.readFileSync(path.resolve(packet),"utf8");
  if(Buffer.byteLength(prompt)>8500)throw Error("Self-contained packet exceeds 8500 UTF-8 bytes");
  const settingsPath="/home/administrator/.dsh/settings.yaml";
  const settings=fs.readFileSync(settingsPath);
  const defaults=settings.toString("utf8").match(/^agent-default-model:\s*\n((?:[ \t]+.*\n?)*)/m)?.[1]??"";
  const provider=defaults.match(/^\s*provider:\s*([^\s#]+)/m)?.[1];
  const model=defaults.match(/^\s*model:\s*([^\s#]+)/m)?.[1];
  if(provider!=="deepseek"||model!=="deepseek-v4.1-flash")
    throw Error("Expected configured deepseek/deepseek-v4.1-flash; settings not modified");
  const root=path.resolve(output);
  fs.mkdirSync(root);
  const hash=bytes=>crypto.createHash("sha256").update(bytes).digest("hex");
  const file=name=>path.join(root,name);
  fs.writeFileSync(file("prompt.txt"),prompt,{flag:"wx"});
  fs.writeFileSync(file("transmitted.txt"),prompt,{flag:"wx"});
  const stdout=fs.openSync(file("answer.txt"),"wx"),stderr=fs.openSync(file("process.txt"),"wx");
  const started=Date.now();
  const child=spawn("/usr/bin/dsh",["--profile","headless",prompt],{
    cwd:root,detached:true,stdio:["ignore",stdout,stderr]
  });
  let timedOut=false,forceTimer,spawnError=null;
  function terminate(signal) {
    if(!child.pid)return;
    try{process.kill(-child.pid,signal);}catch(error){if(error.code!=="ESRCH")throw error;}
  }
  const timer=setTimeout(()=>{
    timedOut=true;terminate("SIGTERM");
    forceTimer=setTimeout(()=>terminate("SIGKILL"),8000);
  },900_000);
  console.log(JSON.stringify({provider,model,pid:child.pid,promptBytes:Buffer.byteLength(prompt),timeoutSeconds:900}));
  child.on("error",error=>{spawnError=error.message;});
  await new Promise(resolve=>child.on("close",(exitCode,signal)=>{
    clearTimeout(timer);clearTimeout(forceTimer);fs.closeSync(stdout);fs.closeSync(stderr);
    const answer=fs.readFileSync(file("answer.txt"),"utf8");
    const before=hash(settings),after=hash(fs.readFileSync(settingsPath));
    const result={schema:"DEEPSEEK_TEXT_REVIEW_V1",provider,model,exitCode,signal,spawnError,timedOut,
      processExited:true,startedAt:new Date(started).toISOString(),exitedAt:new Date().toISOString(),
      elapsedSeconds:(Date.now()-started)/1000,promptBytes:Buffer.byteLength(prompt),
      promptSha256:hash(prompt),answerSha256:hash(answer),
      settingsSha256Before:before,settingsSha256After:after,settingsModified:before!==after,...verdict(answer)};
    fs.writeFileSync(file("exit.json"),JSON.stringify(result,null,2)+"\n",{flag:"wx"});
    console.log(JSON.stringify(result));
    process.exitCode=exitCode===0&&!timedOut&&!spawnError&&before===after&&result.conclusion?0:1;
    resolve();
  }));
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))
  main().catch(error=>{console.error(error.message);process.exitCode=1;});
