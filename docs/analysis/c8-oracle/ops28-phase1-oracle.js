#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 1 — дифференциальный прогон корпуса «Задачи и данные» против ЖИВОГО Zeebe 8.5.0.
 *
 * Читает общий корпус zorrobpm-engine/src/test/resources/ops28-phase1-cases.json и те же BPMN-файлы,
 * что читает наш JUnit-харнесс (docs/analysis/c8-oracle/ops28-phase1-gen.js — единственный генератор),
 * и измеряет поведение Zeebe 8.5.0: payload задания, итоговые переменные scope, парковку/ошибку.
 *
 * Результат: docs/analysis/c8-oracle/scenarios/ops28-phase1/result-8.5.json
 * Это ИЗМЕРЕНИЕ, не набор утверждений: никаких ожиданий «от себя» (G-Q).
 *
 * Запуск: node docs/analysis/c8-oracle/ops28-phase1-oracle.js [caseIdPrefix]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const O = require('./lib/oracle');

const ROOT = path.join(__dirname, '..', '..', '..');
const CASES_PATH = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'ops28-phase1-cases.json');
const OUT_DIR = path.join(__dirname, 'scenarios', 'ops28-phase1');
const OUT_PATH = path.join(OUT_DIR, 'result-8.5.json');

/**
 * Образ оракула. Метка версии в измерении ВЫВОДИТСЯ из тега этого образа, поэтому «метка
 * инструмента» и «образ, который на самом деле поднимали» разъехаться не могут (находка
 * независимого verifier'а: раньше версия была просто строкой в измерении).
 *
 * ВНИМАНИЕ: это зафиксированный тег образа, а НЕ зонд версии у брокера. Отдельного version-зонда
 * (напр. gRPC Topology → brokerVersion) инструмент не делает — см. §2.2 отчёта WO-OPS-28 phase1.
 */
const ORACLE_IMAGE = 'camunda/zeebe:8.5.0';
const ORACLE_LABEL = 'zeebe-' + ORACLE_IMAGE.slice(ORACLE_IMAGE.lastIndexOf(':') + 1);

const sleep = O.sleep;

function bpmnPath(rel) {
  return path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', rel);
}

/** Кейс по ВНЕШНЕЙ фикстуре (P-15): тот же файл, что читает наш JUnit-харнесс. */
function fixturePath(rel) {
  return path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'files', rel);
}

/** Activate a job type; a non-existent type makes Zeebe answer with an error — that is data, not a crash. */
async function tryActivate(client, type) {
  try {
    return await O.activate(client, type);
  } catch (e) {
    return [];
  }
}

async function publishMessage(client, name, key, vars) {
  try {
    const res = await O.unary(client, 'PublishMessage', {
      name,
      correlationKey: key == null ? '' : String(key),
      variables: JSON.stringify(vars || {}),
      tenantId: '',
    });
    return { ok: true, correlated: (res && res.correlated) || 0 };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e) };
  }
}

/** Find the job of `element` for `pi` by trying the expected job type first, then the element id.
 *  `anyPi` — шаг относится к ДОЧЕРНЕМУ инстансу (call activity): у него свой piKey. */
async function findJob(client, pi, step) {
  const candidates = [...new Set([step.jobType, step.element].filter(Boolean))];
  for (const type of candidates) {
    for (let attempt = 0; attempt < 8; attempt++) {
      const jobs = await tryActivate(client, type);
      let hit = null;
      if (step.anyPi) {
        hit = jobs.find((j) => j.elementId === step.element);
      } else {
        const mine = jobs.filter((j) => String(j.processInstanceKey) === String(pi));
        hit = mine.find((j) => j.elementId === step.element);
        if (!hit && mine.length === 1) hit = mine[0];
      }
      if (hit) return hit;
      if (jobs.length === 0) await sleep(400);
      else await sleep(200);
    }
  }
  return null;
}

async function runCase(client, c) {
  const obs = {
    id: c.id,
    element: c.element,
    title: c.title,
    docs: c.docs,
    oracle: ORACLE_LABEL,
  };

  try {
    if (c.dmn) {
      await O.deploy(client, bpmnPath(c.dmn));
    }
    await O.deploy(client, c.bpmnFile ? fixturePath(c.bpmnFile) : bpmnPath(c.bpmn));
    if (c.child) {
      await O.deploy(client, bpmnPath(c.child.bpmn));
    }
  } catch (e) {
    obs.outcome = 'deployError';
    obs.error = String((e && e.message) || e).split('\n').slice(0, 6).join(' | ');
    return obs;
  }

  let pi;
  try {
    pi = await O.start(client, c.processId, c.vars || {});
  } catch (e) {
    obs.outcome = 'startError';
    obs.error = String((e && e.message) || e).split('\n').slice(0, 6).join(' | ');
    return obs;
  }
  obs.processInstanceKey = pi;

  for (const step of c.drive || []) {
    if (step.kind === 'message') {
      obs.publish = { name: step.message, key: step.key == null ? null : step.key };
      const res = await publishMessage(client, step.message, step.key, step.vars);
      obs.publish.correlated = res.correlated;
      if (res.error) obs.publish.error = res.error;
      await sleep(500);
      continue;
    }
    if (step.kind === 'auto') {
      await sleep(600);
      continue;
    }
    if (step.kind === 'listener') {
      // «Существует ли задание слушателя?» — оракул активирует job типа listenerType
      // для нашего инстанса. Есть → завершаем его (как воркер) и продолжаем; нет →
      // элемент пошёл без слушателя (наша сторона теряет его молча — ср. P-5 инвентаря).
      const type = step.jobType;
      const jobs = await tryActivate(client, type);
      const mine = jobs.filter((j) => String(j.processInstanceKey) === String(pi));
      obs.listener = obs.listener || {};
      if (mine.length === 0) {
        obs.listener[type] = 'absent';
        await sleep(300);
        continue;
      }
      obs.listener[type] = 'present';
      await O.complete(client, mine[0], step.vars || {});
      await sleep(250);
      continue;
    }
    const job = await findJob(client, pi, step);
    if (!job) {
      obs.outcome = 'jobNotActivated';
      obs.failedAt = step.element;
      obs.dump = null;
      await O.cancelPi(client, pi).catch(() => {});
      return obs;
    }
    const observeElement = c.observeElement || (c.drive[0] && c.drive[0].element);
    if (c.observeWork && !obs.work && step.element === observeElement) {
      obs.work = {
        jobType: job.type,
        elementId: job.elementId,
        variables: O.parseVars(job),
        raw: job.variables,
        customHeaders: job.customHeaders || '',
        retries: job.retries,
      };
    }
    await O.complete(client, job, step.vars || {});
    await sleep(250);
  }

  // Наблюдатель: payload задания-nаблюдателя = итоговое состояние scope.
  // Кейс без наблюдателя (P-15: процесс без start-события) — ждать нечего.
  let dumpJob = null;
  if (c.dumpJobType) {
    for (let attempt = 0; attempt < 16 && !dumpJob; attempt++) {
      const jobs = await tryActivate(client, c.dumpJobType);
      dumpJob = jobs.find((j) => String(j.processInstanceKey) === String(pi)) || null;
      if (!dumpJob) await sleep(400);
    }
  }
  if (!dumpJob) {
    obs.outcome = 'stuck';
    obs.dump = null;
    await O.cancelPi(client, pi).catch(() => {});
    return obs;
  }
  obs.dump = {
    jobType: dumpJob.type,
    elementId: dumpJob.elementId,
    variables: O.parseVars(dumpJob),
    raw: dumpJob.variables,
  };
  obs.outcome = 'completed';
  await O.complete(client, dumpJob, {});
  return obs;
}

(async () => {
  const prefix = process.argv[2] || '';
  const corpus = JSON.parse(fs.readFileSync(CASES_PATH, 'utf8'));
  const cases = corpus.cases.filter((c) => !prefix || c.id.startsWith(prefix));
  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion} cases=${cases.length}`);

  // МЕРЖИМ с уже измеренными кейсами: частичный прогон (по префиксу) не должен
  // затирать остальные измерения.
  let results = {};
  if (fs.existsSync(OUT_PATH)) {
    try { results = JSON.parse(fs.readFileSync(OUT_PATH, 'utf8')); } catch (e) { results = {}; }
  }
  for (const c of cases) {
    const obs = await runCase(client, c);
    results[c.id] = obs;
    console.log(`--- ${c.id}: ${obs.outcome}`);
    if (obs.error) console.log(`    error: ${obs.error}`);
    if (obs.work) console.log(`    work(${obs.work.jobType}): ${JSON.stringify(obs.work.variables)}`);
    if (obs.dump) console.log(`    dump: ${JSON.stringify(obs.dump.variables)}`);
    if (obs.publish) console.log(`    publish: ${JSON.stringify(obs.publish)}`);
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(OUT_PATH, JSON.stringify(results, null, 2) + '\n', 'utf8');
  }
  console.log(`WROTE ${OUT_PATH}`);
})().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
