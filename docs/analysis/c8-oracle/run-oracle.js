/* WO-C8-41 oracle runner — RAW gRPC against live Zeebe 8.5.0 (zbpm-cmp-zeebe).
 *
 * Why raw: @camunda8/sdk 8.5.9 lossless parser CRASHES on job.variables that
 * contain arrays with null (e.g. pre-created outputCollection [null,null,null]):
 * "An unsafe number value was received for results ... Cannot read properties
 * of null". Raw strings are also more faithful for an oracle log.
 *
 * Usage: node run-oracle.js s1|s2a|...|all
 * Env: none needed (plaintext localhost:36500).
 *
 * Output is the raw oracle log quoted in docs/analysis/c8-recon-mi-subprocess-2026-10-13.md.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('/tmp/claude-1000/-home-russell-crowe-projects-zorro/e3cad831-2892-481c-a93b-453baacb2ad3/scratchpad/zeebe-cmp/node_modules/@grpc/grpc-js');
const protoLoader = require('/tmp/claude-1000/-home-russell-crowe-projects-zorro/e3cad831-2892-481c-a93b-453baacb2ad3/scratchpad/zeebe-cmp/node_modules/@grpc/proto-loader');

const PROTO = '/tmp/claude-1000/-home-russell-crowe-projects-zorro/e3cad831-2892-481c-a93b-453baacb2ad3/scratchpad/zeebe-cmp/node_modules/@camunda8/sdk/dist/proto/zeebe.proto';
const def = protoLoader.loadSync(PROTO, { keepCase: true, longs: String, enums: String, defaults: true, oneofs: true });
const client = new (grpc.loadPackageDefinition(def).gateway_protocol.Gateway)('localhost:36500', grpc.credentials.createInsecure());
const BPMN = path.join(__dirname, 'bpmn');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function unary(method, req) {
  return new Promise((resolve, reject) => {
    client[method](req, (err, resp) => (err ? reject(err) : resolve(resp)));
  });
}

function activate(type, max = 100) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({ type, worker: 'c8-oracle', timeout: '30000', maxJobsToActivate: max, requestTimeout: '3000' });
    const jobs = [];
    call.on('data', (resp) => { for (const j of resp.jobs || []) jobs.push(j); });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

async function deploy(file) {
  const content = fs.readFileSync(path.join(BPMN, file));
  const res = await unary('DeployResource', { resources: [{ name: file, content }], tenantId: '' });
  const procs = (res.deployments || []).map((d) => `${d.process?.bpmnProcessId} v${d.process?.version} key=${d.process?.processDefinitionKey}`).join(', ');
  console.log(`DEPLOY ${file} -> ${procs}`);
}

async function start(bpmnProcessId) {
  const res = await unary('CreateProcessInstance', { bpmnProcessId, version: -1, variables: '{}' });
  console.log(`START ${bpmnProcessId} -> piKey=${res.processInstanceKey}`);
  return String(res.processInstanceKey);
}

async function cancelPi(piKey) {
  await unary('CancelProcessInstance', { processInstanceKey: piKey });
  console.log(`CANCEL piKey=${piKey}`);
}

async function poll(type, minCount, tries = 14, piKey) {
  for (let i = 0; i < tries; i++) {
    let jobs = await activate(type);
    if (piKey) jobs = jobs.filter((j) => String(j.processInstanceKey) === String(piKey));
    if (jobs.length >= minCount) return jobs;
    await sleep(500);
  }
  let jobs = await activate(type);
  if (piKey) jobs = jobs.filter((j) => String(j.processInstanceKey) === String(piKey));
  return jobs;
}

async function complete(job, variables) {
  await unary('CompleteJob', { jobKey: job.key, variables: JSON.stringify(variables || {}) });
  console.log(`COMPLETE key=${job.key} type=${job.type} vars=${JSON.stringify(variables || {})}`);
}

function show(tag, jobs) {
  console.log(`--- ${tag}: ${jobs.length} job(s) ---`);
  for (const j of jobs) {
    console.log(`key=${j.key} type=${j.type} elementId=${j.elementId} piKey=${j.processInstanceKey} vars=${j.variables}`);
  }
}

function itemOf(j) {
  const m = /"item":"([^"]*)"/.exec(j.variables || '');
  return m ? m[1] : '?';
}

const S = {
  async s1() {
    await deploy('s1-mi-sub-race.bpmn');
    const pi = await start('s1MiSub');
    const w = await poll('s1w', 3, 14, pi);
    await show('S1 w (expect 3: item? loopCounter? pre-created results/tu?)', w);
    w.sort((a, b) => (itemOf(b) < itemOf(a) ? -1 : 1));
    console.log(`S1 completion order: ${w.map(itemOf).join(',')}`);
    for (const j of w) await complete(j, { tu: `tu-${itemOf(j)}` });
    const w2 = await poll('s1w2', 3, 14, pi);
    await show('S1 w2 (what tu does each branch see?)', w2);
    for (const j of w2) await complete(j, {});
    const d = await poll('s1dump', 1, 14, pi);
    await show('S1 dump (results? tu? where?)', d);
    for (const j of d) await complete(j, {});
  },
  async s2a() {
    await deploy('s2a-mi-sub-outputmap.bpmn');
    const pi = await start('s2aMiSub');
    const w = await poll('s2aw', 3, 14, pi);
    await show('S2a w', w);
    w.sort((a, b) => (itemOf(b) < itemOf(a) ? -1 : 1));
    for (const j of w) await complete(j, { tu: `tu-${itemOf(j)}` });
    const w2 = await poll('s2aw2', 3, 14, pi);
    await show('S2a w2 (tu via output mapping tu->tu?)', w2);
    for (const j of w2) await complete(j, {});
    const d = await poll('s2adump', 1, 14, pi);
    await show('S2a dump', d);
    for (const j of d) await complete(j, {});
  },
  async s2b() {
    await deploy('s2b-mi-sub-outputmap-renamed.bpmn');
    const pi = await start('s2bMiSub');
    const w = await poll('s2bw', 3, 14, pi);
    await show('S2b w', w);
    w.sort((a, b) => (itemOf(b) < itemOf(a) ? -1 : 1));
    for (const j of w) await complete(j, { tu: `tu-${itemOf(j)}` });
    const w2 = await poll('s2bw2', 3, 14, pi);
    await show('S2b w2 (tuLocal via output mapping?)', w2);
    for (const j of w2) await complete(j, {});
    const d = await poll('s2bdump', 1, 14, pi);
    await show('S2b dump', d);
    for (const j of d) await complete(j, {});
  },
  async s3() {
    await deploy('s3-mi-sub-inputmap.bpmn');
    const pi = await start('s3MiSub');
    const w = await poll('s3w', 2, 14, pi);
    await show('S3 w (x from =item input? item? loopCounter?)', w);
    for (const j of w) await complete(j, {});
    const w2 = await poll('s3w2', 2, 14, pi);
    await show('S3 w2 (sees x? lc from =loopCounter input?)', w2);
    for (const j of w2) await complete(j, {});
    const d = await poll('s3dump', 1, 14, pi);
    await show('S3 dump (results + x/lc leakage?)', d);
    for (const j of d) await complete(j, {});
  },
  async s4() {
    await deploy('s4-mi-sub-empty.bpmn');
    const pi = await start('s4MiSub');
    await sleep(2000);
    const w = (await activate('s4w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S4 w (expect 0 jobs)', w);
    const d = await poll('s4dump', 1, 14, pi);
    await show('S4 dump (results == []?)', d);
    for (const j of d) await complete(j, {});
  },
  async s5() {
    await deploy('s5-mi-sub-unset-output.bpmn');
    const pi = await start('s5MiSub');
    const w = await poll('s5w', 2, 14, pi);
    await show('S5 w', w);
    for (const j of w) await complete(j, {});
    const d = await poll('s5dump', 1, 14, pi);
    await show('S5 dump (results == [null,null]?)', d);
    for (const j of d) await complete(j, {});
  },
  async s6() {
    await deploy('s6-mi-sub-sequential.bpmn');
    const pi = await start('s6MiSub');
    await sleep(1500);
    const first = (await activate('s6w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S6 first activation (expect exactly 1)', first);
    for (const j of first) await complete(j, {});
    await sleep(1500);
    const second = (await activate('s6w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S6 second activation (expect exactly 1, next item)', second);
    for (const j of second) await complete(j, {});
    await sleep(1500);
    const third = (await activate('s6w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S6 third activation (expect exactly 1)', third);
    for (const j of third) await complete(j, {});
    const d = await poll('s6dump', 1, 14, pi);
    await show('S6 dump (results order?)', d);
    for (const j of d) await complete(j, {});
  },
  async s7() {
    await deploy('s7-mi-sub-boundary.bpmn');
    const pi = await start('s7MiSub');
    await sleep(1000);
    const w = (await activate('s7w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S7 w before timer (expect 2, NOT completed)', w);
    console.log('S7 waiting 6s for PT3S boundary to fire...');
    await sleep(6000);
    const wAfter = (await activate('s7w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S7 w after timer (expect 0 — canceled?)', wAfter);
    const d = await poll('s7dump', 1, 14, pi);
    await show('S7 dump (path? results present?)', d);
    for (const j of d) await complete(j, {});
  },
  async s8a() {
    await deploy('s8a-mi-sub-nested.bpmn');
    const pi = await start('s8aNested');
    const w = await poll('s8aw', 4, 14, pi);
    await show('S8a w (expect 4: oitem visible? iitem? loopCounter?)', w);
    for (const j of w) await complete(j, {});
    const d = await poll('s8adump', 1, 14, pi);
    await show('S8a dump (outerResults/innerResults?)', d);
    for (const j of d) await complete(j, {});
  },
  async s8b() {
    await deploy('s8b-mi-task-in-mi-sub.bpmn');
    const pi = await start('s8bMiTaskInSub');
    const w = await poll('s8bw', 4, 14, pi);
    await show('S8b MI task jobs (expect 4: item visible? pitem?)', w);
    for (const j of w) await complete(j, {});
    const d = await poll('s8bdump', 1, 14, pi);
    await show('S8b dump (results/tresults?)', d);
    for (const j of d) await complete(j, {});
  },
  async s9() {
    try {
      await deploy('s9-mi-sub-completion-cond.bpmn');
    } catch (e) {
      console.log(`S9 DEPLOY FAILED: ${e.message}`);
      return;
    }
    const pi = await start('s9MiSub');
    await sleep(1500);
    const w = (await activate('s9w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S9 w initial (expect 3)', w);
    if (w.length > 0) {
      w.sort((a, b) => (itemOf(a) < itemOf(b) ? -1 : 1));
      await complete(w[0], {});
      console.log('S9 completed one instance, waiting 3s...');
      await sleep(3000);
      const rest = (await activate('s9w')).filter((j) => String(j.processInstanceKey) === String(pi));
      await show('S9 w after one completion (remaining canceled?)', rest);
      for (const j of rest) await complete(j, {});
    }
    // WO-C8-41 B-1: ожидание 35 с — пережить 30-секундные локи сиблингов
    // (иначе «0 живых» доказывает только локи, а не терминацию условием).
    console.log('S9 completed one instance, waiting 35s (outlive 30s sibling locks)...');
    await sleep(35000);
    const rest = (await activate('s9w')).filter((j) => String(j.processInstanceKey) === String(pi));
    await show('S9 w after one completion (S9-1: expect 0)', rest);
    for (const j of rest) await complete(j, {});
    await sleep(2000);
    const d = await poll('s9dump', 1, 8, pi);
    await show('S9 dump (S9-2: expect partial results)', d);
    for (const j of d) await complete(j, {});
  },
  async cleanup() {
    // Cancel leftover instances from the crashed SDK run (piKey ...251) etc.
    for (const t of ['s1w', 's1w2', 's2aw', 's2bw', 's3w', 's5w', 's6w', 's7w', 's8aw', 's8bw', 's9w']) {
      const jobs = await activate(t);
      for (const j of jobs) {
        try { await cancelPi(String(j.processInstanceKey)); } catch (e) { /* already gone */ }
      }
    }
    console.log('CLEANUP DONE');
  },
};

(async () => {
  const which = process.argv[2] || 'all';
  const order = ['s1', 's2a', 's2b', 's3', 's4', 's5', 's6', 's7', 's8a', 's8b', 's9'];
  const list = which === 'all' ? order : [which];
  try {
    const topo = await unary('Topology', {});
    console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);
    for (const name of list) {
      if (!S[name]) throw new Error(`unknown scenario ${name}`);
      console.log(`\n===== ${name.toUpperCase()} =====`);
      await S[name]();
    }
    console.log('\nORACLE DONE');
  } catch (e) {
    console.error(`ORACLE FAILED: ${e.stack || e.message}`);
    process.exitCode = 1;
  }
})();
