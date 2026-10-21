#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 5 («сквозные») — дифференциальный прогон среза F5 против ЖИВОГО Zeebe 8.5.0.
 *
 * Тот же набор схем, что и на нашем движке (манифест
 * zorrobpm-engine/target/scenario-matrix/manifest-cross.json — его пишет
 * CrossCuttingScenarioIntegrationTests), но против настоящего брокера Zeebe 8.5.0.
 *
 * ТРИ ОСИ, и что каждая измеряет на ОРАКУЛЕ:
 *
 *   1. f5-restart-midprocess — «рестарт движка посреди процесса». У Zeebe нет
 *      понятия «рестарт движка»: состояние живёт в самом брокере (партиция), и
 *      перезапуск брокера состояние НЕ теряет. Поэтому на оракуле измеряется то же
 *      наблюдаемое — процесс доходит до конца с теми же переменными. Параметр
 *      «durability» у Zeebe обеспечивает брокер (replication/partition log), у нас —
 *      БД; сравнивается ИТОГ, а не механизм.
 *
 *   2. f5-parallel-race — параллельные завершения. Завершаются ВСЕ job'ы ветвей
 *      ОДНИМ ЗАЛПОМ (Promise.all — по-настоящему параллельно с точки зрения
 *      клиента), после чего проверяется, что джойн дал РОВНО ОДИН следующий job.
 *      Наш движок в той же схеме завершает ветви четырьмя РЕАЛЬНЫМИ потоками с
 *      барьером.
 *
 *   3. f5-version-v1 (+ парная v2) — миграция версии определения при живом
 *      инстансе: старт на v1 → деплой v2 ТОГО ЖЕ ключа → завершение задачи →
 *      смотрим, ЧЕЙ job' появился (stV1 = своя версия, stV2 = новая). Это прямое
 *      измерение поведения Zeebe 8.5.0, а не цитата из документации.
 *
 * Использование:
 *   node scenario-matrix-cross.js [--manifest <path>] [--out <path>]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = path.join(__dirname);
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GATEWAY_HOST = process.env.C8_ORACLE_GW || 'localhost:36500';

const ACTIVATE_WINDOW_MS = 1000;
const JOB_LOCK_MS = 20000;
const MAX_TRIES = 12;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function buildClient(host = GATEWAY_HOST) {
  const def = protoLoader.loadSync(PROTO, {
    keepCase: true, longs: String, enums: String, defaults: true, oneofs: true,
  });
  return new (grpc.loadPackageDefinition(def).gateway_protocol.Gateway)(
    host, grpc.credentials.createInsecure(),
  );
}

function unary(client, method, req) {
  return new Promise((resolve, reject) => {
    client[method](req, (err, resp) => (err ? reject(err) : resolve(resp)));
  });
}

async function topology(client) {
  return unary(client, 'Topology', {});
}

function activate(client, type, max = 100) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'ops28f5',
      timeout: String(JOB_LOCK_MS),
      maxJobsToActivate: max,
      requestTimeout: String(ACTIVATE_WINDOW_MS),
    });
    const jobs = [];
    call.on('data', (resp) => { for (const j of resp.jobs || []) jobs.push(j); });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

async function deployContent(client, name, content) {
  const res = await unary(client, 'DeployResource', {
    resources: [{ name, content: Buffer.from(content, 'utf8') }],
    tenantId: '',
  });
  const proc = (res.deployments || [])[0] && (res.deployments || [])[0].process;
  if (!proc) throw new Error(`deploy of ${name} returned no process`);
  return { bpmnProcessId: proc.bpmnProcessId, version: Number(proc.version) };
}

async function start(client, bpmnProcessId, version, variables) {
  const res = await unary(client, 'CreateProcessInstance', {
    bpmnProcessId,
    version,
    variables: JSON.stringify(variables || {}),
  });
  return String(res.processInstanceKey);
}

async function complete(client, job, variables) {
  await unary(client, 'CompleteJob', {
    jobKey: job.key,
    variables: JSON.stringify(variables || {}),
  });
}

async function cancelPi(client, piKey) {
  await unary(client, 'CancelProcessInstance', { processInstanceKey: String(piKey) }).catch(() => {});
}

function parseVars(job) {
  try { return JSON.parse(job.variables || '{}'); } catch (e) { return {}; }
}

const forPi = (jobs, pi) => jobs.filter((j) => String(j.processInstanceKey) === String(pi));

/** Активирует job'ы всех типов сценария и держит их (job -> job). */
async function holdJobs(client, pi, types) {
  const held = new Map();
  const batches = await Promise.all(types.map((t) => activate(client, t)));
  for (const batch of batches) {
    for (const j of forPi(batch, pi)) held.set(j.key, j);
  }
  return held;
}

/**
 * Ведение среза F5 по списку действий.
 *
 * @param scenario сценарий манифеста
 * @param pi       ключ инстанса
 * @param held     уже активированные job'ы (Map key -> job)
 * @returns {Promise<{held: Map, nextElementIds: string[], error: string}>}
 */
async function driveCross(client, pi, scenario, held) {
  const types = scenario.jobTypes || ['work'];
  const nextElementIds = [];
  for (const action of scenario.actions || []) {
    if (action.kind !== 'complete') continue;
    const ids = String(action.target || '').split(',').map((s) => s.trim()).filter(Boolean);
    let mine = [];
    for (let tryNo = 0; tryNo < MAX_TRIES && mine.length === 0; tryNo++) {
      mine = [...held.values()].filter((j) => ids.includes(j.elementId));
      if (mine.length === 0) {
        const more = await holdJobs(client, pi, types);
        for (const [k, v] of more) held.set(k, v);
        mine = [...held.values()].filter((j) => ids.includes(j.elementId));
      }
      if (mine.length === 0) await sleep(200);
    }
    if (mine.length === 0) continue;
    let vars = {};
    try { vars = JSON.parse(action.vars || '{}'); } catch (e) { vars = {}; }
    if (scenario.parallelActions && mine.length > 1) {
      // ОДНОВРЕМЕННОЕ завершение всех ветвей форка (тот же смысл, что четыре
      // реальных потока с барьером у нас).
      await Promise.all(mine.map((j) => complete(client, j, vars)));
      for (const j of mine) held.delete(j.key);
    } else {
      for (const j of mine) {
        await complete(client, j, vars);
        held.delete(j.key);
      }
    }
    // Какие job'ы появились ПОСЛЕ этого действия (для оси «миграция версии»:
    // чей job' — stV1 (своя версия) или stV2 (новая версия)).
    await sleep(300);
    const after = await holdJobs(client, pi, types);
    for (const [k, v] of after) {
      held.set(k, v);
      if (!nextElementIds.includes(v.elementId)) nextElementIds.push(v.elementId);
    }
  }
  return { held, nextElementIds };
}

/** Наблюдательная задача: снимаем payload и завершаем её. */
async function observe(client, pi, dumpType) {
  for (let tryNo = 0; tryNo < 10; tryNo++) {
    const jobs = forPi(await activate(client, dumpType), pi);
    if (jobs.length > 0) {
      const observed = parseVars(jobs[0]);
      await complete(client, jobs[0], {});
      return { observed, found: true };
    }
    await sleep(200);
  }
  return { observed: null, found: false };
}

(async () => {
  const args = process.argv.slice(2);
  const argOf = (name, dflt) => {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
  };
  const manifestPath = path.resolve(argOf('--manifest',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'manifest-cross.json')));
  const outPath = path.resolve(argOf('--out',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-cross.json')));

  if (!fs.existsSync(manifestPath)) {
    console.error(`no manifest at ${manifestPath} — run the engine matrix first`);
    process.exit(2);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const scenarios = manifest.scenarios || [];

  const client = buildClient();
  const topo = await topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);

  const results = [];
  for (const scenario of scenarios) {
    const row = {
      id: scenario.id,
      container: scenario.container,
      content: scenario.content,
      status: 'UNKNOWN',
      rootVars: {},
      leftovers: [],
      error: '',
      continuedWith: '',
      deployedVersions: '',
    };
    let pi = null;
    try {
      const deployed = await deployContent(client, `${scenario.id}.bpmn`, scenario.bpmn);
      row.deployedVersions = String(deployed.version);
      const vars = JSON.parse(scenario.vars || '{}');
      pi = await start(client, deployed.bpmnProcessId, deployed.version, vars);
      // Ось «миграция версии»: вторая версия ТОГО ЖЕ ключа деплоится, пока инстанс
      // версии 1 ещё жив.
      if (scenario.deployAfterStart) {
        const second = await deployContent(client, `${scenario.id}-v2.bpmn`, scenario.deployAfterStart);
        row.deployedVersions = `${deployed.version}->${second.version}`;
      }
      const held = await holdJobs(client, pi, scenario.jobTypes || ['work']);
      const driven = await driveCross(client, pi, scenario, held);
      row.continuedWith = driven.nextElementIds.join(',');
      const obs = await observe(client, pi, scenario.dumpJobType || 'dump');
      if (!obs.found) {
        row.status = 'NO_DUMP_JOB';
        row.error = 'dump job never appeared';
      } else {
        row.rootVars = obs.observed || {};
        row.status = 'COMPLETED';
      }
      const types = (scenario.jobTypes || []).concat([scenario.dumpJobType || 'dump']);
      for (const t of types) {
        for (const j of forPi(await activate(client, t), pi)) {
          row.leftovers.push({ type: t, elementId: j.elementId });
        }
      }
      if (row.leftovers.length > 0) row.status = 'LEFTOVER_JOBS';
      await cancelPi(client, pi);
    } catch (e) {
      row.status = 'FAILED';
      row.error = (e && e.message) ? e.message : String(e);
      if (pi) await cancelPi(client, pi);
    }
    results.push(row);
    console.log(`${row.id}: ${row.status} vars=${JSON.stringify(row.rootVars)}`
      + (row.continuedWith ? ` continuedWith=${row.continuedWith}` : '')
      + (row.deployedVersions ? ` versions=${row.deployedVersions}` : '')
      + (row.error ? ` error=${row.error}` : '')
      + (row.leftovers.length ? ` leftovers=${JSON.stringify(row.leftovers)}` : ''));
  }

  fs.writeFileSync(outPath, JSON.stringify({
    engine: 'zeebe-8.5.0',
    gateway: topo.gatewayVersion,
    generatedAt: new Date().toISOString(),
    outcomes: results,
  }, null, 2) + '\n');
  console.log(`WROTE ${outPath}`);
})().catch((e) => {
  console.error(`CROSS-CUTTING SCENARIO MATRIX FAILED: ${(e && e.stack) || e}`);
  process.exit(1);
});
