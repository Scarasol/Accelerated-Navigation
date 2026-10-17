import fs from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";
import { analyzeCampaign, loadConfig, toCsv, toMarkdown } from "./analysis.mjs";

const usage = `Usage:
  node tools/performance-analysis/analyze.mjs --config <campaign.json> --out <new-directory>

Paths inside campaign.json are relative to that configuration file.
Exit codes: 0 = complete and technically valid, 1 = incomplete/invalid data,
2 = configuration, usage or output error. Timeouts remain functional outcomes.
`;

try {
  const { values } = parseArgs({
    options: {
      config: { type: "string" },
      out: { type: "string" },
      help: { type: "boolean", short: "h" },
    },
  });
  if (values.help) {
    console.log(usage);
  } else {
    if (!values.config || !values.out) throw Error(usage);
    const config = loadConfig(values.config);
    const output = path.resolve(values.out);
    if (fs.existsSync(output)) throw Error(`Output already exists; choose a fresh directory: ${output}`);
    const summary = analyzeCampaign(config);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.mkdirSync(output);
    for (const [name, content] of [
      ["summary.json", JSON.stringify(summary, null, 2) + "\n"],
      ["trials.csv", toCsv(summary.rows)],
      ["cells.csv", toCsv(summary.cells.map(({ metrics, jvms, deadlines, conditionalMetrics, ...cell }) => ({
        ...cell,
        ...Object.fromEntries(Object.entries(metrics).flatMap(([name, stats]) =>
          Object.entries(stats).map(([stat, value]) => [`${name}_${stat}`, value]))),
      })))],
      ["summary.md", toMarkdown(summary)],
    ]) fs.writeFileSync(path.join(output, name), content, { flag: "wx" });
    console.log(`ANALYZED runs=${summary.runs.length}/${summary.expectedRuns.length}`
      + ` trials=${summary.rows.length}/${summary.coverage.expectedFormalTrials}`
      + ` complete=${summary.complete} timedOut=${summary.functional.timedOutTrials}`);
    for (const issue of summary.issues) console.error(`${issue.run ?? "campaign"}: ${issue.code}: ${issue.message}`);
    console.log(`Output: ${output}`);
    process.exitCode = summary.complete ? 0 : 1;
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 2;
}
