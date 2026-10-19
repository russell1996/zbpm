#!/usr/bin/env node
/* WO-C8-51 part B oracle probe — MI inputCollection value classes on live Zeebe 8.5.
 *
 * For each case in scenario.json: start the MI service-task process with the case's start
 * vars, count distinct `c851work` jobs created for that instance within a window, complete
 * them, then check whether the instance reaches the post-MI `c851dump` element.
 *
 *   array            -> N instances, dump reached (the token moves on)
 *   any non-list     -> 0 instances, dump NOT reached -> instance stuck on an incident
 *                       (the pre-fix bug: an OBJECT silently spawned N null-item instances)
 *
 * compare.js is not used: it requires a dump job, i.e. it cannot express "expected incident".
 *
 * Usage: node probe.js            (gateway: localhost:36500; override C8_ORACLE_GW)
 */
'use strict';
const fs = require('fs');
const path = require('path');
const O = require('../../lib/oracle');

const SC = JSON.parse(fs.readFileSync(path.join(__dirname, 'scenario.json'), 'utf8'));
const BPMN = path.join(__dirname, 'process.bpmn');
const WINDOW_MS = 8000;

/** Reject if the oracle call takes longer than ms (a wedged broker must not hang the probe). */
const withDeadline = (p, ms, what) =>
  Promise.race([
    p,
    new Promise((_, rej) => setTimeout(() => rej(new Error(`DEADLINE ${ms}ms on ${what}`)), ms)),
  ]);

async function runCase(client, c) {
  const pi = await withDeadline(O.start(client, SC.processId, c.vars), 15000, 'start');
  const seen = new Map();
  let reachedDump = false;
  try {
    const deadline = Date.now() + WINDOW_MS;
    while (Date.now() < deadline) {
      const jobs = (await withDeadline(O.activate(client, SC.jobType, 100), 15000, 'activate'))
        .filter((j) => String(j.processInstanceKey) === String(pi));
      for (const j of jobs) if (!seen.has(j.key)) seen.set(j.key, j.variables);
      await O.sleep(400);
    }
    for (const key of seen.keys()) {
      await withDeadline(O.complete(client, { key }, {}).catch(() => {}), 15000, 'complete');
    }
    for (let i = 0; i < 8 && !reachedDump; i++) {
      const dump = (await withDeadline(O.activate(client, SC.dumpType, 100), 15000, 'activate dump'))
        .filter((j) => String(j.processInstanceKey) === String(pi));
      if (dump.length) {
        reachedDump = true;
        for (const j of dump) await withDeadline(O.complete(client, j, {}).catch(() => {}), 15000, 'complete dump');
      } else {
        await O.sleep(500);
      }
    }
  } finally {
    await withDeadline(O.cancelPi(client, pi).catch(() => {}), 15000, 'cancel');
  }
  const itemValues = [...seen.values()].map((v) => {
    try {
      return JSON.parse(v || '{}')['item'];
    } catch (e) {
      return `<unparseable:${e.message}>`;
    }
  });
  return { case: c.case, instances: seen.size, itemValues, reachedDump };
}

(async () => {
  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);
  await O.deploy(client, BPMN);

  const rows = [];
  let failed = 0;
  for (const c of SC.cases) {
    try {
      const row = await runCase(client, c);
      rows.push(row);
      console.log(
        `CASE ${row.case.padEnd(16)} instances=${row.instances} reachedDump=${row.reachedDump} itemValues=${JSON.stringify(row.itemValues)}`,
      );
    } catch (e) {
      failed++;
      rows.push({ case: c.case, error: e.message });
      console.log(`CASE ${c.case.padEnd(16)} ERROR ${e.message}`);
    }
  }
  console.log('RESULT-JSON ' + JSON.stringify(rows));
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error('PROBE FAILED: ' + (e.stack || e.message));
  process.exit(1);
});
