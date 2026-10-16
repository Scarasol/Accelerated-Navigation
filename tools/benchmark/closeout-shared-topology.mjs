import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const workspace = resolve(import.meta.dirname, "../..");
const root = resolve(workspace, ".tmp/shared-topology-production-20261015");
const candidate = process.argv[2];
const campaign = process.argv[3];
if (!/^[a-z0-9-]+$/.test(candidate ?? "") || !/^[a-z0-9-]+$/.test(campaign ?? ""))
    throw Error("Explicit frozen candidate and campaign required");
const hash = data => createHash("sha256").update(data).digest("hex").toUpperCase();
const read = path => JSON.parse(readFileSync(resolve(root, path), "utf8").replace(/^\uFEFF/, ""));
const identities = [];
let checks = 0;
function check(value, label) {
    checks++;
    if (!value) throw Error(label);
}
function identity(path, expected) {
    const data = readFileSync(path);
    const actual = hash(data);
    check(actual === expected.toUpperCase(), `identity: ${path}`);
    identities.push({ path, bytes: data.length, sha256: actual });
}
const frozen = read(`${candidate}/identities.json`);
const bindings = frozen.verificationRuns ?? {
    native: "native-v6", lifecycle: "lifecycle-v2", core: "core-v2", reviewCandidate: candidate,
};
for (const row of frozen.identities) {
    identity(resolve(root, candidate, "sources", row.path), row.sha256);
    // Reports may gain an audit closeout after the source freeze.
    if (row.path.startsWith("src/")) identity(resolve(workspace, row.path), row.sha256);
}
for (const [name, directory] of [["mainClasses", "main"], ["testClasses", "test"]])
    for (const row of frozen[name])
        identity(resolve(workspace, "build/classes/java", directory, row.path), row.sha256);
for (const artifact of frozen.artifacts)
    identity(resolve(root, candidate, artifact.path), artifact.sha256);
const production = frozen.artifacts.find(a => a.path === "release/production.jar").sha256;
const probe = frozen.artifacts.find(a => a.path === "release/probe.jar").sha256;
const runs = [];
for (const [run, file] of [[bindings.native, "shared-production.json"], [bindings.lifecycle, "result.json"]]) {
    const exit = read(`${run}/exit.json`);
    const launch = read(`${run}/launch-environment.json`);
    const result = read(`${run}/${file}`);
    check(exit.exitCode === 0 && !exit.timedOut, `${run} exit`);
    check(result.failed === false, `${run} result`);
    for (const row of result.checks) check(row.passed === true, `${run} ${row.name}`);
    if (run === bindings.lifecycle) {
        const shutdown = read(`${run}/shutdown.json`);
        check(shutdown.passed && shutdown.serviceRejected && shutdown.schedulerRejected,
            "lifecycle shutdown rejects new work");
        check(shutdown.navigation.records === 0 && shutdown.navigation.active === 0
            && shutdown.navigation.foreignPaths === 0 && shutdown.scheduler.pendingRequests === 0,
            "lifecycle navigation state zero");
        const stages = read(`${run}/stages.json`);
        check(result.routes.length === 4 && stages.length === 4, "four original parameter routes");
        for (let index = 0; index < 4; index++) {
            const route = result.routes[index], stage = stages[index];
            check(route.route === stage.name, "original route order");
            const control = result.checks.find(row => row.name === `full-route-natural-arrival-${index}`);
            const match = String(control?.value).match(
                /^SUCCEEDED BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\} target=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\} native=false$/);
            check(match != null, "real route terminal and segmented execution");
            const feet = route.arrival.map(Math.floor), actual = match.slice(1, 4).map(Number);
            const target = match.slice(4, 7).map(Number);
            check(feet.every((value, axis) => value === actual[axis]), "actual route feet");
            check(feet.reduce((sum, value, axis) => sum + Math.abs(value - target[axis]), 0) <= 1,
                "original route actual accuracy");
            check(target[0] === stage.goal[0] && target[2] === stage.goal[2], "original route goal");
            check(result.segments.some(row => row.route === index && row.nodes.length > 0),
                "actual route native nodes");
        }
        check(result.ticks.filter(row => row.phase === 7).every(row => row.navigation.nativeWholeCalls === 0)
            && result.segments.every(row => !row.nativeBackend), "routes have no whole native fallback");
        for (const input of read(`${run}/world-inputs.json`)) identity(input.source, input.sha256);
    }
    const jars = new Map(launch.mods.map(m => [m.name, m.sha256]));
    check(jars.get("accelerated_navigation.jar") === production, `${run} actual production jar`);
    check(jars.get("production-probe.jar") === probe, `${run} actual probe jar`);
    identity(resolve(root, run, "mods/accelerated_navigation.jar"), production);
    identity(resolve(root, run, "mods/production-probe.jar"), probe);
    const pidText = execFileSync("powershell.exe", ["-NoProfile", "-Command",
        `if (Get-Process -Id ${Number(exit.pid)} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
        { encoding: "utf8" }).trim();
    check(pidText === "dead", `${run} process exited`);
    if (result.jvmPid != null) check(result.jvmPid === exit.pid, `${run} actual JVM PID`);
    runs.push({ run, rawSha256: hash(readFileSync(resolve(root, run, file))),
        checks: result.checks.length, pid: exit.pid, exit, routes: result.routes });
}
const reviews = read(`review-progress-${bindings.reviewCandidate}-${campaign}.json`);
for (const file of [`${bindings.core}/runtime.json`, `${bindings.core}/scratch.json`]) {
    const data = read(file);
    check(data.passed === true, `${file} latest controls`);
    for (const row of data.checks) check(row.passed === true, `${file} ${row.name}`);
    const pidText = execFileSync("powershell.exe", ["-NoProfile", "-Command",
        `if (Get-Process -Id ${Number(data.jvmPid)} -ErrorAction SilentlyContinue) { 'alive' } else { 'dead' }`],
        { encoding: "utf8" }).trim();
    check(pidText === "dead", `${file} process exited`);
    identities.push({ path: resolve(root, file), sha256: hash(readFileSync(resolve(root, file))) });
}
for (const row of reviews) {
    identity(resolve(root, `deepseek-prompt-${row.round}.txt`), row.promptSha256);
    identity(resolve(root, `deepseek-transmitted-${row.round}.txt`), row.promptSha256);
    check(row.provider === "deepseek" && row.model === "deepseek-v4.1-flash", `${row.round} model`);
    check(row.settingsModified === false, `${row.round} settings`);
}
const summary = { schema: "SHARED_PRODUCTION_IDENTITIES_CLOSEOUT_V1",
    candidate, campaign, bindings, passed: true, checks, productionSha256: production,
    probeSha256: probe, runs, identities, reviews: reviews.map(row => ({
        round: row.round, exitCode: row.exitCode, timedOut: row.timedOut,
        mustFixesClear: row.mustFixesClear, conclusionPresent: row.conclusionPresent,
    })), auditApproved: false, persistenceApproved: false, performanceApproved: false };
writeFileSync(resolve(root, `closeout-${candidate}-${campaign}.json`), JSON.stringify(summary, null, 2) + "\n");
console.log(JSON.stringify({ candidate, campaign, passed: true, checks, runs: runs.length,
    reviews: reviews.length, productionSha256: production }));
