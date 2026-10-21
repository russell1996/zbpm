#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 4 («события») — прогон сценариев среза F4 против ЖИВОГО Zeebe 8.5.0.
 *
 * Тот же набор схем, что и на нашем движке (манифест
 * zorrobpm-engine/target/scenario-matrix/manifest-events.json — его пишет генератор
 * EventScenarioGenerator), но против настоящего брокера Zeebe 8.5.0.
 *
 * Сценарий — это СПИСОК ДЕЙСТВИЙ (тот же, что выполняет наш раннер
 * EventScenarioRunner), поэтому обе стороны меряют одно и то же:
 *   complete        — завершить job'ы элемента (по elementId job'а);
 *   broadcastSignal — бросить сигнал (BroadcastSignal);
 *   wait            — подождать (нужно таймерам/подпискам);
 *   fireTimers      — на оракуле ничего не делает: таймеры в Zeebe срабатывают сами;
 *   throwError      — бросить BPMN-ошибку из job'а (ThrowError);
 *   resolveIncident — НЕ поддерживается шлюзом 8.5 (нет чтения инцидентов) — фиксируется;
 *   cancelInstance  — отменить инстанс (CancelProcessInstance);
 *   observe         — снять наблюдение (payload job'а точки наблюдения), НЕ завершая его.
 *
 * Наблюдение снимается в тот же момент, что и у нас: job' точки наблюдения (её payload =
 * переменные её скоупа = корневые переменные процесса).
 *
 * Использование:
 *   node scenario-matrix-events.js [--manifest <path>] [--out <path>]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = path.join(__dirname);
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GATEWAY_HOST = process.env.C8_ORACLE_GW || '127.0.0.1:36500';
/**
 * Идентификатор прогона — входит в ИМЯ ресурса при деплое.
 *
 * <p>Zeebe не пересоздаёт версию, если содержание ресурса не изменилось: повторный
 * деплой той же модели даёт ту же версию и НЕ перевзводит стартовый таймер. Поэтому
 * каждый прогон деплоит свою версию (имя уникально) — иначе сценарий «старт по
 * таймеру» во втором прогоне не сработал бы вовсе (поймано живым прогоном: job'ов 0).
 * На СОДЕРЖАНИЕ модели это не влияет: обе стороны меряют одну и ту же BPMN-схему.
 */
const RUN_ID = process.env.C8_ORACLE_RUN_ID || String(Date.now());

/** Окно активации job'ов (ms) — сколько сервер держит открытым поток активации. */
const ACTIVATE_WINDOW_MS = 1000;
/** Сколько job остаётся залоченным после активации (ms). */
const JOB_LOCK_MS = 20000;

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
      worker: 'ops28-f4',
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
  const proc = (res.deployments || [])[0] && (res.deployments[0].process);
  if (!proc) throw new Error(`deploy of ${name} returned no process`);
  // processDefinitionKey — ключ ВЕРСИИ в Zeebe: по нему деплой сносится в конце
  // прогона (Zeebe 8.5 удаляет ресурс по КЛЮЧУ, по имени — только с 8.6+;
  // имя поля в ответе на деплой — processDefinitionKey, не processKey).
  return { bpmnProcessId: proc.bpmnProcessId, version: Number(proc.version),
    processKey: Number(proc.processDefinitionKey) };
}

/**
 * Уборка за собой: отменить ВСЕ инстансы сценария и снести его деплой.
 *
 * <p>Без этого стенд копит мусор: сигнальный start event остаётся подписанным (каждая
 * версия — отдельная подписка), а циклический стартовый таймер продолжает создавать
 * инстансы после прогона. На общем стенде это недопустимо.
 */
async function cleanupScenario(client, scenario, deployed) {
  const cancelAll = async () => {
    const pis = new Set();
    for (const type of scenario.jobTypes || []) {
      for (const job of await activate(client, type)) {
        pis.add(String(job.processInstanceKey));
      }
    }
    for (const pi of pis) {
      await unary(client, 'CancelProcessInstance', { processInstanceKey: pi }).catch(() => {});
    }
    return pis.size;
  };
  await cancelAll();
  if (!deployed || !deployed.processKey) return;
  // Отмена инстанса в Zeebe АСИНХРОННА: сразу после неё брокер ещё видит инстанс
  // живым, и DeleteResource отвергается («there are still running instances»).
  // Поэтому ждём, пока job'ы перестанут выдаваться, и только потом сносим деплой.
  for (let i = 0; i < 20; i++) {
    let left = 0;
    for (const type of scenario.jobTypes || []) {
      left += (await activate(client, type)).length;
    }
    if (left === 0) break;
    await cancelAll();
    await sleep(500);
  }
  await sleep(1000);
  // Удаление ресурса в Zeebe отвергается, если у процесса есть ЖИВЫЕ инстансы, а их
  // отмена видна брокеру не мгновенно: повторяем попытку. Без этого версия остаётся
  // подписанной (сигнальный start event) и следующий прогон стартует ЛИШНИЕ инстансы.
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      await unary(client, 'DeleteResource', { resourceKey: deployed.processKey });
      return;
    } catch (e) {
      if (attempt === 9) {
        console.log(`WARN delete resourceKey=${deployed.processKey} failed: `
          + String(e.details || e.message).slice(0, 120));
      }
      await cancelAll();
      await sleep(1500);
    }
  }
}

async function start(client, bpmnProcessId, version, variables) {
  const res = await unary(client, 'CreateProcessInstance', {
    bpmnProcessId,
    version,
    variables: JSON.stringify(variables || {}),
  });
  return String(res.processInstanceKey);
}

async function completeJob(client, job, variables) {
  await unary(client, 'CompleteJob', {
    jobKey: job.key,
    variables: JSON.stringify(variables || {}),
  });
}

async function throwError(client, job, errorCode) {
  await unary(client, 'ThrowError', {
    jobKey: job.key,
    errorCode: errorCode || '',
    errorMessage: 'ops28-f4',
    variables: '{}',
  });
}

async function broadcastSignal(client, signalName, variables) {
  return unary(client, 'BroadcastSignal', {
    signalName,
    variables: JSON.stringify(variables || {}),
    tenantId: '',
  });
}

/**
 * «Успокаивает» сигнальную подписку: ждёт, пока бросок сигнала перестанет создавать
 * инстансы (т.е. пока не останется подписанных версий процесса).
 */
async function quiesceSignal(client, scenario) {
  const dumpType = (scenario.elementTypes || {})[scenario.dumpElementId];
  for (let round = 0; round < 10; round++) {
    await broadcastSignal(client, scenario.startSignal, {});
    await sleep(700);
    const jobs = dumpType ? await activate(client, dumpType) : [];
    if (jobs.length === 0) {
      return;
    }
    for (const job of jobs) {
      await unary(client, 'CancelProcessInstance', { processInstanceKey: String(job.processInstanceKey) })
        .catch(() => {});
    }
    await sleep(400);
  }
}

async function cancelPi(client, piKey) {
  if (!piKey) return;
  await unary(client, 'CancelProcessInstance', { processInstanceKey: String(piKey) }).catch(() => {});
}

function parseVars(job) {
  try { return JSON.parse(job.variables || '{}'); } catch (e) { return {}; }
}

const forPi = (jobs, pi) => (pi ? jobs.filter((j) => String(j.processInstanceKey) === String(pi)) : jobs);

/**
 * Прогон одного сценария против живого Zeebe.
 *
 * @returns {Promise<object>} наблюдение: те же ключи, что у нашего раннера
 *          (dumpReached / vars) + остаток job'ов (аналог живых элементов).
 */
async function runScenario(client, scenario) {
  const row = {
    id: scenario.id,
    family: scenario.family,
    element: scenario.element,
    status: 'UNKNOWN',
    observation: { dumpReached: false, vars: {}, leftoverJobs: {} },
    unsupportedActions: [],
    error: '',
  };
  let pi = null;
  let deployed = null;
  /** Активированные, но не завершённые job'ы (key -> job). */
  const held = new Map();
  /** Сколько job'ов каждого элемента оракул видел (аналог elementCounts у нас). */
  const jobCounts = {};
  /** Сколько разных инстансов оракул видел (аналог instanceCount у нас). */
  const instances = new Set();

  const noteJob = (job) => {
    if (pi === null) pi = String(job.processInstanceKey);
    instances.add(String(job.processInstanceKey));
    jobCounts[job.elementId] = (jobCounts[job.elementId] || 0) + 1;
  };

  /**
   * Активирует ТОЛЬКО нужный тип задания.
   *
   * Активировать «все типы сценария» нельзя: лишний job берётся в лок (20 с) и
   * прячет факт «job остался жив в движке» — а этот факт и есть предмет сравнения.
   */
  const activateType = async (type) => {
    if (!type) return [];
    const jobs = [];
    for (const job of await activate(client, type)) {
      noteJob(job);
      held.set(job.key, job);
      jobs.push(job);
    }
    return jobs;
  };

  /** Тип job'а элемента (из манифеста). */
  const typeOf = (elementId) => (scenario.elementTypes || {})[elementId];

  /** Ищет job по elementId (сначала среди удерживаемых). */
  const findJob = (elementId) => {
    for (const job of held.values()) {
      if (job.elementId === elementId) return job;
    }
    return null;
  };

  try {
    deployed = await deployContent(client, `${scenario.id}.${RUN_ID}.bpmn`, scenario.bpmn);
    const vars = JSON.parse(scenario.vars || '{}');

    if (scenario.startMode === 'PLAIN') {
      pi = await start(client, deployed.bpmnProcessId, deployed.version, vars);
    } else if (scenario.startMode === 'SIGNAL') {
      // Удаление деплоя в Zeebe АСИНХРОННО: подписка сигнального start event живёт
      // ещё какое-то время после DeleteResource. Пока она жива, ОДИН бросок сигнала
      // стартует инстанс на каждую подписанную версию (измерен live: 2 инстанса).
      // Поэтому перед замером стенд «успокаивается»: бросок → отмена → повтор, пока
      // бросок не перестанет создавать инстансы.
      await quiesceSignal(client, scenario);
      // Счётчик инстансов обнуляется: до этого момента он видел инстансы «успокоителя»
      // (их создавал он сам), а измерять надо инстансы ИЗМЕРЕНИЯ.
      instances.clear();
      await broadcastSignal(client, scenario.startSignal, vars);
    } else if (scenario.startMode === 'TIMER') {
      // Таймер старта в Zeebe срабатывает сам; ждём появления job'а точки наблюдения.
      for (let i = 0; i < 30 && pi === null; i++) {
        await activateType(typeOf(scenario.dumpElementId));
        if (held.size === 0) await sleep(300);
      }
    }

    for (const action of scenario.actions || []) {
      switch (action.kind) {
        case 'complete': {
          let job = findJob(action.target);
          if (!job) {
            await activateType(typeOf(action.target));
            job = findJob(action.target);
          }
          if (job) {
            await completeJob(client, job, JSON.parse(action.vars || '{}'));
            held.delete(job.key);
          }
          break;
        }
        case 'broadcastSignal': {
          await broadcastSignal(client, action.target, JSON.parse(action.vars || '{}'));
          break;
        }
        case 'wait': {
          await sleep(action.ms || 0);
          break;
        }
        case 'fireTimers': {
          // Таймеры в Zeebe срабатывают сами — действие не требует ничего.
          await sleep(100);
          break;
        }
        case 'throwError': {
          let job = findJob(action.target);
          if (!job) {
            await activateType(typeOf(action.target));
            job = findJob(action.target);
          }
          if (job) {
            await throwError(client, job, action.code);
            held.delete(job.key);
          }
          break;
        }
        case 'resolveIncident': {
          // Шлюз Zeebe 8.5 не отдаёт инциденты — разбор инцидента на стороне оракула
          // НЕ ИЗМЕРИМ (фиксируется как unsupported, а не выдумывается результат).
          row.unsupportedActions.push('resolveIncident');
          break;
        }
        case 'cancelInstance': {
          await cancelPi(client, pi);
          break;
        }
        case 'observe': {
          let job = findJob(scenario.dumpElementId);
          if (!job) {
            await activateType(typeOf(scenario.dumpElementId));
            job = findJob(scenario.dumpElementId);
          }
          if (job) {
            row.observation.dumpReached = true;
            row.observation.vars = parseVars(job);
          }
          break;
        }
        default:
          row.unsupportedActions.push(action.kind);
      }
    }

    // Остаток: ЧТО ЕЩЁ ЖИВО в движке после действий — свежая активация всех типов
    // сценария. Job'ы, которые мы уже забрали и НЕ завершили, лежат в локе и вернутся
    // только после истечения лока — их в остаток не включаем (иначе «остаток» отвечает
    // на вопрос «что мы не завершили», а не «что осталось в движке»).
    const leftovers = {};
    for (const type of scenario.jobTypes || []) {
      for (const job of await activate(client, type)) {
        noteJob(job);
        leftovers[job.elementId] = (leftovers[job.elementId] || 0) + 1;
      }
    }
    row.observation.leftoverJobs = leftovers;
    row.observation.jobCounts = jobCounts;
    row.observation.instanceCount = instances.size;
    row.status = Object.keys(leftovers).length === 0 ? 'COMPLETED' : 'LIVE_JOBS';
    await cleanupScenario(client, scenario, deployed);
  } catch (e) {
    row.status = 'FAILED';
    row.error = (e && e.message) ? e.message : String(e);
    await cleanupScenario(client, scenario, deployed);
  }
  return row;
}

(async () => {
  const args = process.argv.slice(2);
  const argOf = (name, dflt) => {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? path.resolve(args[i + 1]) : dflt;
  };
  const manifestPath = argOf('--manifest',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'manifest-events.json'));
  const outPath = argOf('--out',
    path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-events.json'));

  if (!fs.existsSync(manifestPath)) {
    console.error(`no manifest at ${manifestPath} — run the engine event slice first`);
    process.exit(2);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const scenarios = manifest.scenarios || [];

  const client = buildClient();
  const topo = await topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);

  const results = [];
  for (const scenario of scenarios) {
    const row = await runScenario(client, scenario);
    results.push(row);
    console.log(`${row.id}: ${row.status} dumpReached=${row.observation.dumpReached}`
      + ` vars=${JSON.stringify(row.observation.vars)}`
      + ` leftover=${JSON.stringify(row.observation.leftoverJobs)}`
      + (row.error ? ` error=${row.error}` : '')
      + (row.unsupportedActions.length ? ` unsupported=${row.unsupportedActions.join(',')}` : ''));
  }

  // Проверка чистоты стенда: после уборки ни один бросок сигнала не должен создавать
  // инстанс (иначе на стенде осталась подписанная версия — она бы искажала замер
  // «сколько инстансов стартует по одному сигналу»).
  await sleep(12000);
  const check = await activate(client, 'dump-f4-start-signal');
  console.log(`STAND-CHECK: instances started by a signal after cleanup = ${check.length} `
    + `(0 = стенд чист)`);
  for (const job of check) {
    await cancelPi(client, String(job.processInstanceKey));
  }

  fs.writeFileSync(outPath, JSON.stringify({
    engine: 'zeebe-8.5.0',
    gateway: topo.gatewayVersion,
    generatedAt: new Date().toISOString(),
    outcomes: results,
  }, null, 2) + '\n');
  console.log(`WROTE ${outPath}`);
})().catch((e) => {
  console.error(`EVENT SCENARIO MATRIX FAILED: ${(e && e.stack) || e}`);
  process.exit(1);
});
