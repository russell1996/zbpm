#!/usr/bin/env node
/* WO-OPS-30 F0 — unified scenario comparison runner.
 *
 * Runs ONE scenario (BPMN + start vars + documented expectation) against the LIVE Zeebe
 * 8.5.0 oracle and produces two artifacts:
 *   1. scenarios/<id>/result-8.5.json  — the observed Zeebe behavior (structured JSON).
 *   2. a matrix line:  <8.9 docs> | <Zeebe 8.5 live> | <ours> | <verdict>
 *      The "ours" column is filled manually / in a later phase (F1+); compare.js leaves it
 *      as given in scenario.json and emits a provisional verdict for the F0 stage.
 *
 * A scenario is a directory scenarios/<id>/ containing:
 *   process.bpmn   — the mini-process (input-mapped service task + a final dump job)
 *   scenario.json  — descriptor (see scenarios/_template/scenario.json)
 *
 * Usage:
 *   node compare.js <scenario-id> [--matrix]
 *     <scenario-id>   directory name under scenarios/ (e.g. _template)
 *     --matrix        also append the matrix line to docs/analysis/c8-oracle/MATRIX.md
 *
 * The gateway is localhost:36500 by default (see lib/oracle.js; override C8_ORACLE_GW).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const O = require('./lib/oracle');

const SCEN_DIR = path.join(__dirname, 'scenarios');

function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => deepEqual(a[k], b[k]));
}

/** Compare only the keys present in `expected` against `observed` (extra observed keys are fine). */
function checkExpected(observed, expected) {
  const checks = Object.keys(expected || {}).map((k) => ({
    key: k,
    expected: expected[k],
    actual: observed[k],
    ok: deepEqual(observed[k], expected[k]),
  }));
  return { pass: checks.every((c) => c.ok), checks };
}

/** Deterministic compact rendering of the observed root vars for the matrix column. */
function summarizeVars(varsObj) {
  const keys = Object.keys(varsObj).sort();
  return keys.map((k) => `${k}=${JSON.stringify(varsObj[k])}`).join(', ') || '(empty)';
}

/**
 * Deploy the scenario BPMN, start it with the given vars, drive the intermediate
 * `completeTypes` jobs to completion, then capture the final `dumpType` job's variables
 * (which carry the resulting root state). Returns the observed root variables.
 */
async function runScenario(client, scenDir, sc) {
  const bpmnPath = path.join(scenDir, sc.bpmn || 'process.bpmn');
  await O.deploy(client, bpmnPath);
  const pi = await O.start(client, sc.processId, sc.vars || {});

  const completeTypes = sc.completeTypes || [];
  const completeVars = sc.completeVars || {};
  const dumpType = sc.dumpType;
  const maxRounds = sc.maxRounds || 40;
  const forPi = (jobs) => jobs.filter((j) => String(j.processInstanceKey) === String(pi));

  let dumpJobs = [];
  for (let round = 0; round < maxRounds; round++) {
    dumpJobs = await O.poll(client, dumpType, 1, 2, pi);
    if (dumpJobs.length > 0) break;
    let completedThisRound = 0;
    for (const t of completeTypes) {
      const jobs = forPi(await O.activate(client, t));
      for (const j of jobs) {
        await O.complete(client, j, completeVars[t] || {});
        completedThisRound++;
      }
    }
    if (completedThisRound === 0) await O.sleep(500);
  }

  if (dumpJobs.length === 0) {
    await O.cancelPi(client, pi).catch(() => {}); // do not strand the instance
    throw new Error(`dump job of type '${dumpType}' never appeared (pi=${pi}); instance canceled`);
  }

  const observed = O.parseVars(dumpJobs[0]);
  await O.complete(client, dumpJobs[0], {});
  return {
    processInstanceKey: pi,
    dumpJob: { key: dumpJobs[0].key, elementId: dumpJobs[0].elementId },
    observed,
  };
}

(async () => {
  const args = process.argv.slice(2);
  const matrixFlag = args.includes('--matrix');
  const id = args.find((a) => !a.startsWith('--'));
  if (!id) {
    console.error('usage: node compare.js <scenario-id> [--matrix]   (scenario dir under scenarios/)');
    process.exit(2);
  }
  const scenDir = path.join(SCEN_DIR, id);
  const scenFile = path.join(scenDir, 'scenario.json');
  if (!fs.existsSync(scenFile)) {
    console.error(`no scenario at ${scenFile}`);
    process.exit(2);
  }
  const sc = JSON.parse(fs.readFileSync(scenFile, 'utf8'));

  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);

  const result = await runScenario(client, scenDir, sc);
  const { pass, checks } = checkExpected(result.observed, sc.expected || {});

  const observedSummary = summarizeVars(result.observed);
  const docs89 = (sc.matrix && sc.matrix.docs89) || '(fill: what 8.9 docs say)';
  const ours = (sc.matrix && sc.matrix.ours) || '(pending)';

  // F0 verdict: we have measured the 8.5 behavior; "ours" is not measured yet (F1+).
  let verdict;
  if (!pass) verdict = 'UNEXPECTED-8.5 (observed != documented expected)';
  else if (!sc.matrix || !sc.matrix.ours) verdict = 'measured-8.5 (ours pending)';
  else verdict = '8.5 measured; ours as given (manual parity check in F1+)';

  const matrixLine = `${docs89} | ${observedSummary} | ${ours} | ${verdict}`;

  const out = {
    scenario: sc.id || id,
    title: sc.title || '',
    oracle: `${topo.brokers.length} broker(s), gateway ${topo.gatewayVersion}`,
    processInstanceKey: result.processInstanceKey,
    dumpJob: result.dumpJob,
    observed: result.observed,
    expected: sc.expected || {},
    checks,
    pass,
    matrix: { docs89, 'zeebe-8.5-live': observedSummary, ours, verdict },
    matrixLine,
    generatedAt: new Date().toISOString(),
  };
  const outFile = path.join(scenDir, 'result-8.5.json');
  fs.writeFileSync(outFile, JSON.stringify(out, null, 2) + '\n');

  console.log(`\nOBSERVED: ${JSON.stringify(result.observed)}`);
  console.log(
    `CHECKS:   ${
      checks
        .map((c) =>
          c.ok
            ? `${c.key}=OK`
            : `${c.key}=MISMATCH exp=${JSON.stringify(c.expected)} act=${JSON.stringify(c.actual)}`,
        )
        .join('; ') || '(none)'
    }`,
  );
  console.log(`WROTE:    ${outFile}`);
  console.log(`MATRIX:   ${matrixLine}`);

  if (matrixFlag) {
    const matrixFile = path.join(__dirname, 'MATRIX.md');
    if (!fs.existsSync(matrixFile)) {
      fs.writeFileSync(
        matrixFile,
        '# Conformance matrix — 8.9 docs | Zeebe 8.5 live | ours | verdict\n\n' +
          'Generated/appended by `node compare.js <scenario-id> --matrix` (WO-OPS-30).\n' +
          'Verdict: `match` / `docs!=8.5` / `ours differs`; F0 rows are `measured-8.5 (ours pending)`.\n',
      );
    }
    const block = `\n## ${sc.id || id}${sc.title ? ` — ${sc.title}` : ''}\n\n\`${matrixLine}\`\n`;
    fs.appendFileSync(matrixFile, block);
    console.log(`APPENDED: ${matrixFile}`);
  }
})().catch((e) => {
  console.error(`COMPARE FAILED: ${e.stack || e.message}`);
  process.exit(1);
});
