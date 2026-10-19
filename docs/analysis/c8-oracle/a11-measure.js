#!/usr/bin/env node
/* WO-AUDIT-11 — Camunda 8.5 live measurement for the F-01 / F-09 findings.
 *
 * WHY a separate runner (not compare.js): compare.js models "complete the worker
 * jobs, then read the final dump job's variables". The two audit findings are
 * about INCIDENTS and about a BOUNDARY firing — there is no happy-path dump job
 * to read, and the decisive signal lives in the broker's incident counters.
 *
 * Observable on Zeebe 8.5 over gRPC:
 *   - which job types were created (=> which element the flow reached),
 *   - the broker's incident counters (=> an incident was raised / not raised).
 * NOT observable without Operate: element-instance states (ERROR vs COMPLETED)
 * and the incident message — recorded honestly as such in the report.
 *
 * Usage: node a11-measure.js <scenario-id> [--matrix]
 *   <scenario-id>  directory under scenarios/ (a11-errend-unhandled | a11-fork-scope-boundary)
 *   --matrix       also append the matrix line to docs/analysis/c8-oracle/MATRIX.md
 */
'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');
const O = require('./lib/oracle');

const SCEN_DIR = path.join(__dirname, 'scenarios');
const ACTUATOR = process.env.C8_ORACLE_ACTUATOR || 'http://localhost:39600';

/** Fetch a prometheus metric value by exact metric name (sum over label sets). */
async function promValue(metric) {
  const body = await new Promise((resolve, reject) => {
    http.get(`${ACTUATOR}/actuator/prometheus`, (res) => {
      let data = '';
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve(data));
    }).on('error', reject);
  });
  let total = 0;
  let found = false;
  for (const line of body.split('\n')) {
    if (!line.startsWith(metric)) continue;
    const value = Number(line.slice(line.lastIndexOf(' ') + 1));
    if (Number.isNaN(value)) continue;
    found = true;
    total += value;
  }
  return { found, value: total };
}

async function incidentCounters() {
  const created = await promValue('zeebe_incident_events_total{action="created"');
  const pending = await promValue('zeebe_pending_incidents_total');
  return {
    incidentsCreated: created.value,
    incidentsPending: pending.value,
  };
}

async function main() {
  const scenarioId = process.argv[2];
  const withMatrix = process.argv.includes('--matrix');
  if (!scenarioId) {
    console.error('usage: node a11-measure.js <scenario-id> [--matrix]');
    process.exit(2);
  }
  const scenDir = path.join(SCEN_DIR, scenarioId);
  const sc = JSON.parse(fs.readFileSync(path.join(scenDir, 'scenario.json'), 'utf8'));
  const bpmnPath = path.join(scenDir, sc.bpmn || 'process.bpmn');

  const client = O.buildClient();
  const before = await incidentCounters();
  console.log(`SCENARIO ${scenarioId}: ${sc.title}`);
  console.log(`INCIDENTS before: created=${before.incidentsCreated} pending=${before.incidentsPending}`);

  await O.deploy(client, bpmnPath);
  const piKey = await O.start(client, sc.processId, sc.vars || {});
  await O.sleep(sc.waitMs || 4000);

  const observedJobs = {};
  for (const type of sc.observeJobTypes || []) {
    const jobs = await O.activate(client, type, 10);
    observedJobs[type] = jobs
      .filter((j) => String(j.processInstanceKey) === String(piKey))
      .map((j) => ({ key: j.key, elementId: j.elementId, vars: O.parseVars(j) }));
    console.log(`JOB ${type}: ${observedJobs[type].length} activatable job(s) for pi ${piKey}`);
  }

  const after = await incidentCounters();
  console.log(`INCIDENTS after: created=${after.incidentsCreated} pending=${after.incidentsPending}`);

  const incidentDelta = after.incidentsCreated - before.incidentsCreated;
  const pendingDelta = after.incidentsPending - before.incidentsPending;
  const result = {
    id: scenarioId,
    title: sc.title,
    measuredOn: 'Zeebe 8.5.0 (live oracle, camunda/zeebe:8.5.0)',
    processInstanceKey: piKey,
    incidents: { before, after, createdDelta: incidentDelta, pendingDelta },
    observedJobs,
    expectation: { expectIncident: sc.expectIncident },
    verdict: sc.expectIncident
      ? (incidentDelta > 0 ? 'incident raised (as expected)' : 'NO incident raised')
      : (incidentDelta === 0 ? 'no incident (as expected)' : `UNEXPECTED incident raised (${incidentDelta})`),
    matrix: sc.matrix,
  };
  fs.writeFileSync(
    path.join(scenDir, 'result-8.5.json'),
    `${JSON.stringify(result, null, 2)}\n`,
  );

  const liveCol = [
    incidentDelta > 0 ? `incident raised (+${incidentDelta}, pending ${pendingDelta >= 0 ? '+' : ''}${pendingDelta})` : 'no incident',
    ...Object.entries(observedJobs).map(([t, j]) => `${t}=${j.length ? j.map((x) => x.elementId).join(',') : 'none'}`),
  ].join(', ');
  const line = `## ${scenarioId} — ${sc.title}\n\n\`${sc.matrix.docs89} | ${liveCol} | (pending) | measured-8.5 (ours pending)\``;
  console.log(`MATRIX:   ${sc.matrix.docs89} | ${liveCol} | (pending) | measured-8.5 (ours pending)`);
  if (withMatrix) {
    fs.appendFileSync(path.join(__dirname, 'MATRIX.md'), `\n${line}\n`);
    console.log('MATRIX.md updated');
  }
  await O.cancelPi(client, piKey).catch(() => {});
}

main().catch((e) => {
  console.error('MEASURE FAILED:', e);
  process.exit(1);
});
