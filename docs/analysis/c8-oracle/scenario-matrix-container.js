#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 3 («контейнеры») — дифференциальный прогон среза F3 против ЖИВОГО Zeebe 8.5.0.
 *
 * Вход:  zorrobpm-engine/target/scenario-matrix/manifest-containers.json
 *        (его пишет ContainerScenarioMatrixIntegrationTests — те же схемы, что
 *         и на нашем движке: один XML на обе стороны).
 * Выход: zorrobpm-engine/target/scenario-matrix/zeebe-containers.json
 *
 * Отличие от среза F1 (scenario-matrix.js): водит не по MI-инстансам, а по
 * СПИСКУ ШАГОВ сценария (тот же список выполняет и наш движок — сравнение
 * остаётся честным). Шаг = {elementId, jobType, vars, item}:
 *   - активировать job'ы типа jobType;
 *   - если задан item — завершить ТОЛЬКО тот инстанс MI-цикла, у которого
 *     переменная item равна этому значению (нужно для completionCondition);
 *   - завершить с переменными шага.
 *
 * Типы job'ов УНИКАЛЬНЫ на сценарий (в протоколе Zeebe 8.5 у job'а нет ссылки
 * на родительский инстанс, поэтому дочерний процесс call activity от родителя
 * не отличить — уникальный тип снимает риск перехвата чужого job'а).
 *
 * Использование:
 *   node scenario-matrix-container.js [--manifest <path>] [--out <path>]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = path.join(__dirname);
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GATEWAY_HOST = process.env.C8_ORACLE_GW || 'localhost:36500';

/** Окно активации job'ов (ms). */
const ACTIVATE_WINDOW_MS = 1000;
/**
 * Сколько job остаётся залоченным после активации (ms).
 * Короткий срок: job'ы завершаются сразу после активации, а короткий лок
 * нужен, чтобы «что осталось активным» можно было перемерить честно —
 * иначе залоченный нами job не вернётся в активацию и остаток не виден.
 */
const JOB_LOCK_MS = 3000;
/** Сколько раз ждать появления нужного job'а. */
const MAX_TRIES = 25;
/** Пауза перед замером остатка (ждём истечения локов). */
const LOCK_SETTLE_MS = 3500;

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

async function activate(client, type, max = 100) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'ops28f3',
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
  const proc = (res.deployments || [])[0] && res.deployments[0].process;
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

/** Значение MI-цикла item из payload'а job'а. */
function itemOf(job) {
  const v = parseVars(job);
  if (v && typeof v.item === 'string') return v.item;
  return null;
}

/**
 * Ведение по списку шагов.
 *
 * «Что осталось активным» меряется ОТДЕЛЬНО (см. leftovers): после истечения
 * локов свежая активация возвращает ровно те job'ы, которые ещё живы. Поэтому
 * здесь ничего не «держим» — иначе завершённый позже job' числился бы
 * остатком (ложный остаток).
 */
async function drive(client, scenario) {
  const reached = [];
  for (const step of scenario.steps || []) {
    let done = false;
    for (let tryNo = 0; tryNo < MAX_TRIES && !done; tryNo++) {
      const activated = await activate(client, step.jobType);
      if (process.env.F3_DEBUG) {
        console.log(`    [debug] step ${step.jobType} activated=${activated.length}`
          + ` elements=${JSON.stringify(activated.map((j) => j.elementId))}`);
      }
      let targets = activated;
      if (step.item) {
        targets = activated.filter((j) => itemOf(j) === step.item);
        if (targets.length === 0) {
          // Нет инстанса с таким item: либо он ещё не создан, либо MI закрылась
          // досрочно (completionCondition). Ждём появления наблюдательной задачи.
          if ((await activate(client, scenario.dumpJobType)).length > 0) return;
          await sleep(200);
          continue;
        }
      }
      for (const j of targets) {
        await complete(client, j, JSON.parse(step.vars || '{}'));
        if (!reached.includes(step.elementId)) reached.push(step.elementId);
        if (process.env.F3_DEBUG) console.log(`    [debug] completed ${j.elementId} key=${j.key}`);
      }
      done = true;
    }
  }
  return reached;
}

/** Наблюдательная задача: снимаем payload (состояние) и завершаем её. */
async function observe(client, scenario) {
  const dumpType = scenario.dumpJobType;
  for (let tryNo = 0; tryNo < 15; tryNo++) {
    const jobs = await activate(client, dumpType);
    if (jobs.length > 0) {
      const observed = parseVars(jobs[0]);
      await complete(client, jobs[0], {});
      return { observed, found: true };
    }
    await sleep(200);
  }
  return { observed: null, found: false };
}

/**
 * Все типы job'ов схемы сценария.
 *
 * <p>Важно: probe остатка должен смотреть ВСЕ типы задач схемы, а не только те,
 * по которым водились шаги. Иначе «остатка нет» означало бы «мы не проверяли
 * тот тип, который не водили» (для прерывающего event sub-process это именно
 * задача `after`: она не входит в список шагов, потому что в Zeebe поток
 * прерывается и её не бывает).
 */
function allJobTypes(scenario) {
  const types = new Set((scenario.steps || []).map((s) => s.jobType));
  types.add(scenario.dumpJobType);
  const bpmn = String(scenario.bpmn || '');
  const re = /<zeebe:taskDefinition[^>]*type="([^"]+)"/g;
  let m;
  while ((m = re.exec(bpmn)) !== null) types.add(m[1]);
  for (const c of scenario.childBpmn || []) {
    const re2 = /<zeebe:taskDefinition[^>]*type="([^"]+)"/g;
    let m2;
    while ((m2 = re2.exec(String(c))) !== null) types.add(m2[1]);
  }
  return [...types];
}

/** Что осталось активным после завершения наблюдательной задачи. */
async function leftovers(client, scenario) {
  const types = allJobTypes(scenario);
  const left = [];
  for (const t of types) {
    for (const j of await activate(client, t)) {
      left.push({ type: j.type, elementId: j.elementId });
    }
  }
  return { left, probedTypes: types };
}

(async () => {
  const args = process.argv.slice(2);
  const argOf = (name, dflt) => {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
  };
  const manifestPath = path.resolve(argOf('--manifest',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'manifest-containers.json')));
  const outPath = path.resolve(argOf('--out',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-containers.json')));

  if (!fs.existsSync(manifestPath)) {
    console.error(`no manifest at ${manifestPath} — run the engine container slice first`);
    process.exit(2);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const only = argOf('--only', null);
  const scenarios = (manifest.scenarios || [])
      .filter((s) => !only || s.id === only);

  const client = buildClient();
  const topo = await topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);

  const results = [];
  for (const scenario of scenarios) {
    const row = {
      id: scenario.id,
      container: scenario.container,
      content: scenario.content,
      cardinality: scenario.cardinality,
      status: 'UNKNOWN',
      rootVars: {},
      leftovers: [],
      reachedSteps: [],
      probedTypes: [],
      error: '',
    };
    let pi = null;
    try {
      // Дочерние ресурсы (call activity) — ОТДЕЛЬНЫЙ деплой, как и у нас.
      for (const child of scenario.childBpmn || []) {
        await deployContent(client, `${scenario.id}-child.bpmn`, child);
      }
      const deployed = await deployContent(client, `${scenario.id}.bpmn`, scenario.bpmn);
      const vars = JSON.parse(scenario.vars || '{}');
      pi = await start(client, deployed.bpmnProcessId, deployed.version, vars);
      row.reachedSteps = await drive(client, scenario);
      const obs = await observe(client, scenario);
      if (!obs.found) {
        row.status = 'NO_DUMP_JOB';
        row.error = 'dump job never appeared';
      } else {
        row.rootVars = obs.observed || {};
        row.status = 'COMPLETED';
      }
      // Замер остатка — после истечения локов (иначе залоченный нами job
      // не вернётся в активацию и остаток не виден).
      await sleep(LOCK_SETTLE_MS);
      const rest = await leftovers(client, scenario);
      row.leftovers = rest.left;
      row.probedTypes = rest.probedTypes;
      if (rest.left.length > 0) {
        row.status = row.status === 'COMPLETED' ? 'LEFTOVER_JOBS' : row.status;
      }
      await cancelPi(client, pi);
    } catch (e) {
      const message = (e && e.message) ? e.message : String(e);
      // Отказ при деплое — наблюдаемый факт, а не сбой прогона.
      const isDeployRefusal = /ERROR|Invalid|not supported|Unsupported|unsupported/i.test(message)
        && !pi;
      row.status = isDeployRefusal ? 'DEPLOY_REJECTED' : 'FAILED';
      row.error = message;
      if (pi) await cancelPi(client, pi);
    }
    results.push(row);
    console.log(`${row.id}: ${row.status} vars=${JSON.stringify(row.rootVars)}`
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
  console.error(`CONTAINER SCENARIO MATRIX FAILED: ${(e && e.stack) || e}`);
  process.exit(1);
});
