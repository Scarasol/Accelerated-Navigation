import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import crypto from "node:crypto";

const [beforeFile, afterFile, sceneList, output] = process.argv.slice(2);
assert(beforeFile && afterFile && sceneList && output && process.argv.length === 6,
  "Usage: node compare-controls.mjs BEFORE_JSON AFTER_JSON SCENES NEW_OUTPUT_JSON");
const names = sceneList.split(",");
assert(names.length && names.every(Boolean) && new Set(names).size === names.length);
const read = file => JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
const hash = file => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const before = read(beforeFile), after = read(afterFile);
assert(before.valid && after.valid && before.diagnosticOnly && after.diagnosticOnly);
const fields = ["last", "window", "pendingWindow", "shared", "range", "requestedRange",
  "accuracy", "requestedAccuracy", "regionOffset", "visitedMultiplier", "refreshes",
  "committedIdentityRetained", "start", "targets", "unavailable", "canReach", "nodes"];
function inputs(trial) {
  return trial.actualSearches.map(search => Object.fromEntries(fields.map(key => {
    assert(Object.hasOwn(search, key), `Missing search input ${key} in ${trial.name}`);
    return [key, search[key]];
  })));
}
function messages(file, trial) {
  const log = path.join(path.dirname(file), "logs", "debug.log");
  const ids = new Set(trial.actualSearches.map(search => search.queryId));
  const selected = fs.readFileSync(log, "utf8").split(/\r?\n/).flatMap(line => {
    const match = line.match(/Navigation ([0-9a-f-]{36}) .*/);
    return match && ids.has(match[1]) ? [match[0]] : [];
  });
  assert(selected.length, `Missing navigation messages for ${trial.name} in ${log}`);
  return {file: log, sha256: hash(log), lines: selected.length,
    utf8Bytes: selected.reduce((bytes, line) => bytes + Buffer.byteLength(line + "\n"), 0)};
}
const trials = names.map(name => {
  const select = data => {
    const matches = data.trials.filter(trial => trial.name === name);
    assert.equal(matches.length, 1, `Missing or duplicate scene ${name}`);
    return matches[0];
  };
  const left = select(before), right = select(after);
  assert.equal(left.state, right.state, `${name}: terminal state`);
  assert.equal(left.arrived, right.arrived, `${name}: physical arrival`);
  assert.deepEqual(inputs(left), inputs(right), `${name}: search inputs and raw nodes`);
  assert.equal(left.windowPreparations, right.windowPreparations, `${name}: window count`);
  const leftLog = messages(beforeFile, left), rightLog = messages(afterFile, right);
  return {name, semanticMatch: true, searches: left.actualSearches.length,
    windowPreparations: right.windowPreparations,
    beforeFirstPathMs: left.firstPathMs, afterFirstPathMs: right.firstPathMs,
    beforeMessages: leftLog, afterMessages: rightLog,
    navigationMessageByteChange: rightLog.utf8Bytes / leftLog.utf8Bytes - 1};
});
const result = {valid: true, diagnosticOnly: true, fields, trials,
  before: {file: beforeFile, sha256: hash(beforeFile), productionSha256: before.productionSha256},
  after: {file: afterFile, sha256: hash(afterFile), productionSha256: after.productionSha256},
  logScope: "Selected search query IDs; UTF-8 message text plus newline, excluding logger prefixes. Diagnostic bytes, not total allocation or performance."};
fs.writeFileSync(output, JSON.stringify(result, null, 2) + "\n", {flag: "wx"});
console.log(JSON.stringify({valid: true, diagnosticOnly: true, trials}));
