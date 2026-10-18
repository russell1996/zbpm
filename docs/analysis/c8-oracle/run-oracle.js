/* WO-C8-41 / WO-OPS-30 oracle runner — raw gRPC against live Zeebe 8.5.0 (zbpm-cmp-zeebe).
 *
 * Self-contained: the gRPC client + gateway proto come from THIS directory's node_modules
 * (installed via `npm ci`) through lib/oracle.js — no absolute /tmp scratchpad paths.
 *
 * Usage:
 *   npm ci                 # one-time: install @camunda8/sdk@8.5.9 + @grpc/*
 *   node run-oracle.js s1|s2a|s2b|s3|s4|s5|s6|s7|s8a|s8b|s9|all|cleanup
 *
 * The Zeebe stack must be up (see README.md / stack/docker-compose.yml); the gateway is
 * reached at localhost:36500 by default (override with C8_ORACLE_GW=<host:port>).
 *
 * Output is the raw oracle log quoted in docs/analysis/c8-recon-mi-subprocess-2026-10-13.md.
 */
'use strict';
const path = require('path');
const O = require('./lib/oracle');

const client = O.buildClient();
const BPMN = path.join(__dirname, 'bpmn');

// Thin wrappers so the scenario bodies below read exactly like the original runner.
const deploy = (file) => O.deploy(client, path.join(BPMN, file));
const start = (id) => O.start(client, id);
const poll = (type, minCount, tries, piKey) => O.poll(client, type, minCount, tries, piKey);
const activate = (type) => O.activate(client, type);
const complete = (job, vars) => O.complete(client, job, vars);
const cancelPi = (piKey) => O.cancelPi(client, piKey);
const { show, itemOf, sleep } = O;

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
    // Cancel leftover instances from a crashed/partial run (stranded active jobs).
    for (const t of ['s1w', 's1w2', 's2aw', 's2bw', 's3w', 's5w', 's6w', 's7w', 's8aw', 's8bw', 's9w']) {
      const jobs = await activate(t);
      for (const j of jobs) {
        try {
          await cancelPi(String(j.processInstanceKey));
        } catch (e) {
          /* already gone */
        }
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
    const topo = await O.topology(client);
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
