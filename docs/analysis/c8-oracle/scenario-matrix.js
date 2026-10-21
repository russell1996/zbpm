#!/usr/bin/env node
/* WO-OPS-27 — дифференциальный прогон матрицы сценариев против ЖИВОГО Zeebe 8.5.0.
 *
 * Тот же набор схем, что и на нашем движке (манифест из
 * zorrobpm-engine/target/scenario-matrix/manifest.json — его пишет генератор
 * BpmnScenarioGenerator), но против настоящего брокера Zeebe 8.5.0.
 *
 * Наблюдение — то же самое, что и у нас: «наблюдательная» service task в конце
 * процесса; payload её job'а = переменные её скоупа (в Zeebe это все переменные
 * процесса, видимые в корне). Наблюдение снимается ДО завершения job'а.
 *
 * Порядок завершения инстантов задаётся сценарием: инстанс опознаётся по
 * переменной inputElement (`item`) в payload'е его job'а.
 *
 * Использование:
 *   node scenario-matrix.js [--manifest <path>] [--out <path>]
 *
 * По умолчанию:
 *   manifest: ../../zorrobpm-engine/target/scenario-matrix/manifest.json
 *   out:      ../../zorrobpm-engine/target/scenario-matrix/zeebe.json
 *
 * ДИАЛЕКТ ДЛЯ ОРАКУЛА (важно, см. toZeebeDialect). Наш генератор печатает user task
 * в НАТИВНОМ диалекте Zeebe 8.5: `<zeebe:userTask/>` + `<zeebe:assignmentDefinition/>`.
 * Измерено живьём (docs/analysis/c8-oracle/user-task-probe.js, Zeebe 8.5.0):
 *   - нативный user task (с `<zeebe:userTask/>`) job-worker API НЕ отдаёт —
 *     у него нет активируемого job'а, он живёт в User Task API;
 *   - user task БЕЗ `<zeebe:userTask/>` — обычный job типа
 *     `io.camunda.zeebe:userTask` (НЕ `io.camunda:user-task` от 8.4), и после
 *     CompleteJob инстанс идёт дальше.
 * То есть «Zeebe 8.5 не исполняет user task» — НЕВЕРНО; неверно то, что нативную
 * форму не доводит job-worker харнесс. Поэтому для оси user task оракул
 * разворачивается в job-worker диалекте: user task остаётся ТЕМ ЖЕ элементом
 * (тот же id, тот же порядок в MI, те же переменные), меняется только способ
 * его завершения. Сравниваются терминальный статус, переменные и «что осталось
 * активным» — то есть семантика MI-агрегации, а не фичи user task.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = path.join(__dirname);
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GATEWAY_HOST = process.env.C8_ORACLE_GW || 'localhost:36500';

/**
 * Диалект оракула: убирает нативные маркеры user task 8.5.
 * Всё остальное (структура, id, MI-характеристики, переменные) — байт в байт.
 */
function toZeebeDialect(bpmn) {
  return bpmn
    .replace(/\s*<zeebe:userTask\s*\/>/g, '')
    .replace(/\s*<zeebe:assignmentDefinition[^>]*\/>/g, '')
    // опустевший контейнер расширений убираем — пустой extensionElements
    // Zeebe не любит (проверено пробой: деплой с пустым контейнером падает).
    .replace(/\s*<bpmn:extensionElements>\s*<\/bpmn:extensionElements>/g, '');
}

/** Окно активации job'ов (ms) — сколько сервер держит открытым поток активации. */
const ACTIVATE_WINDOW_MS = 1000;
/**
 * Сколько job остаётся залоченным после активации (ms).
 * Держим БОЛЬШОЙ срок: активированные job'ы мы завершаем в заданном порядке
 * (порядок завершения — ось сценария), и между активациями они не должны
 * «протухать». Короткий lock приводил к тому, что polling перезалочивал чужие
 * job'ы и они не успевали завершиться (инстанст висел).
 */
const JOB_LOCK_MS = 20000;
/** Сколько раз ждать появления нужного job'а. */
const MAX_TRIES = 15;
/**
 * Сколько раундов ждать job'а в СЦЕНАРИИ ДЕЙСТВИЙ (срез F2, WO-OPS-28).
 * Меньше, чем у среза F1: там job'ы появляются пачками и с задержкой MI-спавна,
 * здесь каждый шаг — синхронная цепочка «завершили задачу → движок пошёл дальше»,
 * поэтому двух-трёх раундов хватает; ждать 15 раундов на «ветвь не взята»
 * незачем (это НЕ ошибка, а наблюдаемый факт).
 */
const MAX_TRIES_ACTIONS = 5;

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

/** Активация job'ов типа type; поток держится ACTIVATE_WINDOW_MS. */
function activate(client, type, max = 100) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'ops27',
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
  // bpmnProcessId — строгий id процесса из BPMN (нужен в CreateProcessInstance);
  // processDefinitionKey — числовой ключ Zeebe, его в CREATE передавать нельзя.
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

/**
 * Публикация сообщения (срез F2, WO-OPS-28): будит подписку, в т.ч. ветвь
 * event-based gateway. Correlation key пустой — корреляция по имени.
 *
 * Отказ публикации (подписки нет — например, проигравшая подписка
 * event-based gateway уже снята) — НЕ ошибка сценария, а наблюдаемый факт:
 * возвращаем false, сценарий продолжается.
 */
async function publishMessage(client, name, variables, correlationKey) {
  try {
    await unary(client, 'PublishMessage', {
      name,
      correlationKey: correlationKey || '',
      timeToLive: 0,
      variables: JSON.stringify(variables || {}),
    });
    return true;
  } catch (e) {
    return false;
  }
}

function parseVars(job) {
  try { return JSON.parse(job.variables || '{}'); } catch (e) { return {}; }
}

/** Значение inputElement из payload'а job'а (внутри — строка "item":"a"). */
function itemOf(job) {
  const v = parseVars(job);
  if (v && typeof v.item === 'string') return v.item;
  return null;
}

const forPi = (jobs, pi) => jobs.filter((j) => String(j.processInstanceKey) === String(pi));

/**
 * Завершает инстансы в заданном порядке. Для каждого item ждём его job'ы
 * (job мог быть залочен предыдущей активацией — поэтому ждём и пробуем снова),
 * завершаем их и идём к следующему item.
 */
async function drive(client, pi, scenario) {
  const types = scenario.jobTypes || ['work'];
  const dumpType = scenario.dumpJobType || 'dump';
  const steps = scenario.steps || 1;
  // Активированные, но ещё не завершённые job'ы (key -> job). Их завершаем
  // ПОРЯДКОМ СЦЕНАРИЯ: сначала инстанс «a», потом «b» и т.д.
  const held = new Map();
  for (const item of scenario.order || []) {
    let done = 0;
    for (let tryNo = 0; tryNo < MAX_TRIES && done < steps; tryNo++) {
      let mine = [...held.values()].filter((j) => itemOf(j) === item);
      if (mine.length === 0) {
        for (const t of types) {
          for (const j of forPi(await activate(client, t), pi)) held.set(j.key, j);
        }
        mine = [...held.values()].filter((j) => itemOf(j) === item);
      }
      if (mine.length > 0) {
        for (const j of mine) {
          await complete(client, j, {});
          held.delete(j.key);
          done++;
        }
        continue;
      }
      // Job'а этого инстанса нет: либо он ещё не создан (последовательный MI
      // создаёт следующий инстанс асинхронно), либо MI закрылась досрочно.
      // Признак «делать больше нечего» — появилась наблюдательная задача.
      if (forPi(await activate(client, dumpType), pi).length > 0) return;
      await sleep(200);
    }
  }
  return held;
}

/**
 * Ведение сценария по СПИСКУ ДЕЙСТВИЙ (срез F2, WO-OPS-28 «потоки»).
 *
 * Тот же список действий выполняет и наш раннер (ScenarioMatrixRunner) —
 * один и тот же сценарий на обоих движках, иначе сравнение было бы нечестным.
 *
 * Действия:
 *   - {kind: 'publish',  target: '<имя сообщения>', vars: '{...}'} — публикация;
 *   - {kind: 'complete', target: '<id элемента[,id элемента…]>', vars: '{...}'}
 *     — завершение задачи с указанным id (если такой задачи нет — действие
 *     пропускается: это признак того, что ветвь не взята, а не ошибка).
 */
async function driveActions(client, pi, scenario) {
  const types = scenario.jobTypes || ['work'];
  const held = new Map();
  const rejected = [];
  for (const action of scenario.actions || []) {
    if (action.kind === 'publish') {
      let vars = {};
      try {
        vars = JSON.parse(action.vars || '{}');
      } catch (e) {
        vars = {};
      }
      const ok = await publishMessage(client, action.target, vars, action.correlationKey);
      if (!ok) rejected.push(action.target);
      continue;
    }
    const ids = String(action.target || '').split(',').map((s) => s.trim()).filter(Boolean);
    let done = false;
    for (let tryNo = 0; tryNo < MAX_TRIES_ACTIONS && !done; tryNo++) {
      let mine = [...held.values()].filter((j) => ids.includes(j.elementId));
      if (mine.length === 0) {
        // Активируем ВСЕ типы параллельно: один раунд = ~1 с, а не по секунде
        // на тип (типов у сценария несколько).
        const batches = await Promise.all(types.map((t) => activate(client, t)));
        for (const batch of batches) {
          for (const j of forPi(batch, pi)) held.set(j.key, j);
        }
        mine = [...held.values()].filter((j) => ids.includes(j.elementId));
      }
      if (mine.length > 0) {
        let vars = {};
        try {
          vars = JSON.parse(action.vars || '{}');
        } catch (e) {
          vars = {};
        }
        for (const j of mine) {
          await complete(client, j, vars);
          held.delete(j.key);
        }
        done = true;
      } else {
        await sleep(200);
      }
    }
  }
  return { held, rejected };
}

/** Наблюдательная задача: снимаем payload (состояние) и завершаем её. */
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

/** Что осталось активным после завершения наблюдательной задачи. */
async function leftovers(client, pi, types) {
  const left = [];
  for (const t of types) {
    for (const j of forPi(await activate(client, t), pi)) left.push({ type: t, elementId: j.elementId });
  }
  return left;
}

(async () => {
  const args = process.argv.slice(2);
  const argOf = (name, dflt) => {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
  };
  const manifestPath = path.resolve(argOf('--manifest',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'manifest.json')));
  const outPath = path.resolve(argOf('--out',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe.json')));

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
      cardinality: scenario.cardinality,
      order: (scenario.order || []).join(','),
      status: 'UNKNOWN',
      rootVars: {},
      leftovers: [],
      error: '',
    };
    let pi = null;
    try {
      const deployed = await deployContent(client, `${scenario.id}.bpmn`, toZeebeDialect(scenario.bpmn));
      const vars = JSON.parse(scenario.vars || '{}');
      pi = await start(client, deployed.bpmnProcessId, deployed.version, vars);
      // Срез F2 (WO-OPS-28 «потоки») ведётся СПИСКОМ ДЕЙСТВИЙ — тем же, что
      // выполняет наш раннер; срез F1 (WO-OPS-27) — по MI-инстансам.
      const hasActions = Array.isArray(scenario.actions) && scenario.actions.length > 0;
      const driven = hasActions
        ? await driveActions(client, pi, scenario)
        : { held: await drive(client, pi, scenario), rejected: [] };
      const held = driven.held;
      row.rejectedPublishes = driven.rejected;
      // Исходы публикаций в одном формате с нашей стороной: «<имя>:correlated» /
      // «<имя>:rejected» (у нас — по счётчику разбуженных подписок).
      row.publishes = (scenario.actions || [])
        .filter((a) => a.kind === 'publish')
        .map((a) => `${a.target}:${driven.rejected.includes(a.target) ? 'rejected' : 'correlated'}`);
      const obs = await observe(client, pi, scenario.dumpJobType || 'dump');
      if (!obs.found) {
        row.status = 'NO_DUMP_JOB';
        row.error = 'dump job never appeared';
      } else {
        row.rootVars = obs.observed || {};
        row.status = 'COMPLETED';
      }
      const types = (scenario.jobTypes || []).concat([scenario.dumpJobType || 'dump']);
      row.leftovers = (held && held.size ? [...held.values()].map((j) => ({ type: j.type, elementId: j.elementId })) : [])
        .concat(await leftovers(client, pi, types));
      if (row.leftovers.length > 0) row.status = 'LEFTOVER_JOBS';
      await cancelPi(client, pi);
    } catch (e) {
      row.status = 'FAILED';
      row.error = (e && e.message) ? e.message : String(e);
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
  console.error(`SCENARIO MATRIX FAILED: ${(e && e.stack) || e}`);
  process.exit(1);
});
