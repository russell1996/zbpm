#!/usr/bin/env node
/* WO-OPS-30 F2a — протокол job против живого Zeebe 8.5 (оракул).
 *
 * Один список кейсов на обе стороны (G-Q): zorrobpm-engine/src/test/resources/job-protocol-cases.json
 * — это ЕДИНСТВЕННЫЙ источник правды для BPMN и для шагов. Оракул и наш движок
 * исполняют ОДНИ И ТЕ ЖЕ сценарии; расхождения видны в матрице.
 *
 *   node job-protocol.js --sync        # перегенерировать scenarios/f2a-<id> из списка кейсов
 *   node job-protocol.js                # прогнать все кейсы против живого Zeebe 8.5
 *   node job-protocol.js f2a-merge-root # один кейс
 *   node job-protocol.js --ours <file>  # подставить колонку 'ours' из нашего прогона
 *
 * Формат сценария — как в scenarios/_template (process.bpmn + scenario.json),
 * расширенный шагами ('steps'): шаг описывает ДЕЙСТВИЕ воркера (complete / fail /
 * throwError) или НАБЛЮДЕНИЕ (observeJob / observeIncident / activateAndAbandon).
 *
 * Побайтовая сверка: перед прогоном каждый process.bpmn в scenarios/f2a-<id> сверяется
 * с каноническим файлом из zorrobpm-engine/src/test/resources/job-protocol/ — если они
 * разошлись, прогон падает (иначе «оба движка» measuring разные модели).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const O = require('./lib/oracle');

const ROOT = path.join(__dirname, '..', '..', '..');
const CASES_FILE = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'job-protocol-cases.json');
const BPMN_DIR = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'job-protocol');
const SCEN_DIR = path.join(__dirname, 'scenarios');
const DEFAULT_OURS = path.join(ROOT, 'zorrobpm-engine', 'target', 'job-protocol-observed.json');

function loadCases() {
  const raw = JSON.parse(fs.readFileSync(CASES_FILE, 'utf8'));
  return raw.cases || [];
}

/** element id -> job type (zeebe:taskDefinition type) из BPMN. */
function elementTypes(bpmnXml) {
  const map = new Map();
  const re = /<bpmn:(serviceTask|scriptTask|sendTask|endEvent|intermediateThrowEvent)\b[^>]*id="([^"]+)"[\s\S]*?<\/bpmn:\1>/g;
  let m;
  while ((m = re.exec(bpmnXml)) !== null) {
    const block = m[0];
    const t = /<zeebe:taskDefinition[^>]*\btype="([^"]+)"/.exec(block);
    if (t) map.set(m[2], t[1]);
  }
  return map;
}

function readBpmn(c) {
  const canonical = path.join(BPMN_DIR, path.basename(c.bpmn));
  if (!fs.existsSync(canonical)) throw new Error(`missing canonical BPMN ${canonical}`);
  return { canonical, xml: fs.readFileSync(canonical, 'utf8') };
}

/** Копия BPMN в сценарий + scenario.json в формате _template (расширенный шагами). */
function syncScenarios(cases) {
  let written = 0;
  for (const c of cases) {
    const { canonical, xml } = readBpmn(c);
    const dir = path.join(SCEN_DIR, c.id);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'process.bpmn'), xml);
    const scenario = {
      id: c.id,
      title: c.title,
      bpmn: 'process.bpmn',
      processId: c.processId,
      vars: c.vars || {},
      steps: c.steps || [],
      expected: c.expect || {},
      matrix: { docs89: c.docs89 || '', ours: '' },
      _source: path.relative(ROOT, canonical),
    };
    fs.writeFileSync(path.join(dir, 'scenario.json'), JSON.stringify(scenario, null, 2) + '\n');
    written++;
  }
  console.log(`SYNC: ${written} scenario(s) written to ${path.relative(ROOT, SCEN_DIR)}/`);
}

/** Побайтовая сверка сценария с канонической моделью — иначе «оба движка» меряют разное. */
function assertScenarioMatchesCanonical(c) {
  const { canonical, xml } = readBpmn(c);
  const scenFile = path.join(SCEN_DIR, c.id, 'process.bpmn');
  if (!fs.existsSync(scenFile)) {
    throw new Error(`scenario ${c.id}: ${scenFile} missing — run 'node job-protocol.js --sync'`);
  }
  const scenXml = fs.readFileSync(scenFile, 'utf8');
  if (scenXml !== xml) {
    throw new Error(`scenario ${c.id}: process.bpmn differs from ${path.relative(ROOT, canonical)} — re-run --sync`);
  }
  return xml;
}

async function activateElement(client, pi, type, timeoutMs) {
  const jobs = await O.activate(client, type, 100, timeoutMs);
  return jobs.filter((j) => String(j.processInstanceKey) === String(pi));
}

(async () => {
  const args = process.argv.slice(2);
  if (args.includes('--sync')) {
    syncScenarios(loadCases());
    return;
  }
  const oursIdx = args.indexOf('--ours');
  const oursFile = oursIdx >= 0 ? args[oursIdx + 1] : DEFAULT_OURS;
  const ids = args.filter((a) => !a.startsWith('--') && a !== oursFile);
  const cases = loadCases().filter((c) => ids.length === 0 || ids.includes(c.id));

  let oursById = {};
  if (fs.existsSync(oursFile)) {
    const raw = JSON.parse(fs.readFileSync(oursFile, 'utf8'));
    oursById = raw.cases || raw;
    console.log(`OURS: ${Object.keys(oursById).length} observation(s) from ${path.relative(ROOT, oursFile)}`);
  } else {
    console.log(`OURS: no file at ${path.relative(ROOT, oursFile)} (column 3 stays pending)`);
  }

  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`CONNECTED brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);

  const lines = [];
  for (const c of cases) {
    const bpmnXml = assertScenarioMatchesCanonical(c);
    const types = elementTypes(bpmnXml);
    const scenDir = path.join(SCEN_DIR, c.id);
    await O.deploy(client, path.join(scenDir, 'process.bpmn'));
    const pi = await O.start(client, c.processId, c.vars || {});

    const activated = new Map(); // element -> job (already activated by this runner)
    const observations = [];

    const activateFor = async (element) => {
      if (activated.has(element)) return activated.get(element);
      const type = types.get(element);
      if (!type) throw new Error(`case ${c.id}: element '${element}' has no zeebe:taskDefinition type`);
      const jobs = await activateElement(client, pi, type, 30000);
      if (jobs.length === 0) return null;
      activated.set(element, jobs[0]);
      return jobs[0];
    };

    for (const step of c.steps || []) {
      if (step.oursOnly) {
        observations.push({ step: step.action, element: step.element, skipped: 'ours-only step' });
        continue;
      }
      try {
        if (step.action === 'observeJob' || step.action === 'observeJobImmediately') {
          const type = types.get(step.element);
          if (!type) throw new Error(`unknown element '${step.element}'`);
          // timeoutMs — таймаут АКТИВАЦИИ для этого наблюдения: короткий (2 с)
          // освобождает задание обратно в брокер, чтобы следующее наблюдение
          // могло его снова увидеть (кейс «воркера нет»).
          const activationTimeoutMs = step.timeoutMs || 30000;
          const jobs = await activateElement(client, pi, type, activationTimeoutMs);
          if (jobs.length === 0) {
            observations.push({
              step: step.action,
              element: step.element,
              jobType: type,
              outcome: step.action === 'observeJobImmediately' ? 'notActivatable' : 'noJob',
            });
            continue;
          }
          const job = jobs[0];
          activated.set(step.element, job);
          observations.push({
            step: step.action,
            element: step.element,
            jobType: job.type,
            retries: job.retries,
            deadline: job.deadline,
            customHeaders: job.customHeaders || {},
            worker: job.worker,
            variables: job.variables,
            activationTimeoutMs,
            outcome: 'jobObserved',
          });
        } else if (step.action === 'complete') {
          const job = await activateFor(step.element);
          if (!job) {
            observations.push({ step: step.action, element: step.element, outcome: 'noJobToComplete' });
            continue;
          }
          await O.complete(client, job, step.vars || {});
          activated.delete(step.element);
          observations.push({
            step: step.action,
            element: step.element,
            vars: step.vars || {},
            outcome: 'completed',
          });
        } else if (step.action === 'fail') {
          const job = await activateFor(step.element);
          if (!job) {
            observations.push({ step: step.action, element: step.element, outcome: 'noJobToFail' });
            continue;
          }
          await O.failJob(client, job, step.retries, step.message, step.retryBackOffMs, step.vars);
          activated.delete(step.element);
          observations.push({
            step: step.action,
            element: step.element,
            retries: step.retries,
            errorMessage: step.message,
            retryBackOffMs: step.retryBackOffMs || 0,
            vars: step.vars || {},
            outcome: 'failed',
          });
        } else if (step.action === 'throwError') {
          const job = await activateFor(step.element);
          if (!job) {
            observations.push({ step: step.action, element: step.element, outcome: 'noJobToThrow' });
            continue;
          }
          await O.throwError(client, job, step.code, step.message);
          activated.delete(step.element);
          observations.push({
            step: step.action,
            element: step.element,
            errorCode: step.code,
            errorMessage: step.message,
            outcome: 'thrown',
          });
        } else if (step.action === 'activateAndAbandon') {
          const type = types.get(step.element);
          const jobs = await activateElement(client, pi, type, step.timeoutMs || 2000);
          observations.push({
            step: step.action,
            element: step.element,
            jobType: type,
            timeoutMs: step.timeoutMs || 2000,
            activated: jobs.length,
            retries: jobs.length ? jobs[0].retries : null,
            outcome: jobs.length ? 'activatedAndAbandoned' : 'noJob',
          });
        } else if (step.action === 'wait') {
          await O.sleep(step.ms || 1000);
          observations.push({ step: step.action, ms: step.ms || 1000, outcome: 'waited' });
        } else if (step.action === 'observeIncident') {
          // The 8.5 Gateway API has no incident query (that lives in Operate, which is not
          // part of this stand) — the observable on the live side is "no further job".
          observations.push({
            step: step.action,
            element: step.element,
            outcome: 'notReadable',
            note: 'no incident API on the Zeebe 8.5 Gateway (Operate is not part of this stand); the observable is the absence of a further job',
          });
        } else {
          observations.push({ step: step.action, element: step.element, outcome: 'unknownStep' });
        }
      } catch (e) {
        observations.push({
          step: step.action,
          element: step.element,
          outcome: 'error',
          error: String(e.message || e),
        });
      }
    }

    // The instance must not be left behind on the stand.
    await O.cancelPi(client, pi).catch(() => {});

    const liveSummary = summarize(observations);
    const ours = oursById[c.id] || null;
    const oursSummary = ours ? summarize(ours.observations || []) : '(pending)';
    const docs89 = c.docs89 || '';
    const verdict = ours ? verdictOf(c, observations, ours) : 'measured-8.5 (ours pending)';
    const matrixLine = `${docs89} | ${liveSummary} | ${oursSummary} | ${verdict}`;

    const out = {
      scenario: c.id,
      title: c.title,
      oracle: `${topo.brokers.length} broker(s), gateway ${topo.gatewayVersion}`,
      processInstanceKey: pi,
      observations,
      matrix: { docs89, 'zeebe-8.5-live': liveSummary, ours: oursSummary, verdict },
      matrixLine,
      generatedAt: new Date().toISOString(),
    };
    fs.mkdirSync(scenDir, { recursive: true });
    const outFile = path.join(scenDir, 'result-8.5.json');
    fs.writeFileSync(outFile, JSON.stringify(out, null, 2) + '\n');
    console.log(`\n### ${c.id} — ${c.title}`);
    console.log(`LIVE:  ${liveSummary}`);
    console.log(`OURS:  ${oursSummary}`);
    console.log(`MATRIX: ${matrixLine}`);
    lines.push(matrixLine);
  }

  const matrixFile = path.join(__dirname, 'MATRIX.md');
  if (args.includes('--matrix')) {
    fs.appendFileSync(matrixFile, `\n## F2a (WO-OPS-30)\n\n${lines.map((l) => `- \`${l}\``).join('\n')}\n`);
    console.log(`\nAPPENDED: ${matrixFile}`);
  }
})().catch((e) => {
  console.error(`JOB-PROTOCOL FAILED: ${e.stack || e.message}`);
  process.exit(1);
});

/** Компактная сводка наблюдений для колонки матрицы. */
function summarize(observations) {
  return (observations || [])
    .map((o) => {
      const bits = [];
      if (o.jobType) bits.push(`type=${o.jobType}`);
      if (o.retries !== undefined && o.retries !== null) bits.push(`retries=${o.retries}`);
      if (o.workerVisibleRetries !== undefined) {
        bits.push(`workerVisibleRetries=${o.workerVisibleRetries === null ? 'NONE' : o.workerVisibleRetries}`);
      }
      if (o.varsAccepted === false) bits.push('varsAccepted=false');
      if (o.customHeaders && Object.keys(o.customHeaders).length) {
        bits.push(`headers=${JSON.stringify(o.customHeaders)}`);
      }
      if (o.taskHeaders && Object.keys(o.taskHeaders).length) {
        bits.push(`headers=${JSON.stringify(o.taskHeaders)}`);
      }
      if (o.variables !== undefined && o.variables !== null) {
        bits.push(`vars=${canonicalJson(o.variables)}`);
      }
      if (o.errorMessage) bits.push(`msg="${o.errorMessage}"`);
      if (o.errorCode) bits.push(`code=${o.errorCode}`);
      if (o.outcome) bits.push(o.outcome);
      if (o.error) bits.push(`ERROR=${o.error}`);
      return `${o.step}${o.element ? `(${o.element})` : ''}: ${bits.join(' ') || '—'}`;
    })
    .join(' | ');
}

/**
 * Механическое сравнение колонок. Это ПОМОЩНИК, а не вердикт: окончательный
 * вердикт в матрице ставит человек (он объясняет расхождение). Сравнение
 * переменных — по СОДЕРЖАНИЮ (JSON разбирается, порядок ключей не важен).
 */
function verdictOf(c, liveObs, ours) {
  const live = normalize(liveObs);
  const our = normalize(ours.observations || []);
  const liveMap = byStep(live);
  const ourMap = byStep(our);
  const diffs = [];
  const onlyOurs = [];
  const onlyLive = [];
  for (const [k, liveKey] of liveMap) {
    if (!ourMap.has(k)) {
      onlyLive.push(k);
      continue;
    }
    if (liveKey !== ourMap.get(k)) {
      diffs.push(` @ ${k}: live[${liveKey}] vs ours[${ourMap.get(k)}]`);
    }
  }
  for (const k of ourMap.keys()) {
    if (!liveMap.has(k)) {
      onlyOurs.push(k);
    }
  }
  if (diffs.length === 0) {
    const extra = onlyOurs.length || onlyLive.length
      ? ` (шаг только с одной стороны: ${[...onlyOurs, ...onlyLive].join(', ')})`
      : '';
    return 'match' + extra;
  }
  return 'ours differs' + diffs.join('');
}

/** Ключ шага: действие + элемент + порядковый номер (шаги сопоставляются по СМЫСЛУ,
 *  а не по индексу: у сторон разный набор шагов — live-only activateAndAbandon,
 *  ours-only watchdog). */
function byStep(observations) {
  const map = new Map();
  const counters = new Map();
  for (const o of observations) {
    const base = `${o.step}(${o.element || '-'})`;
    const n = (counters.get(base) || 0) + 1;
    counters.set(base, n);
    map.set(`${base}#${n}`, keyOf(o));
  }
  return map;
}

function keyOf(o) {
  // workerVisibleRetries НЕ входит в сравнение: это не поведение шага, а
  // отдельная колонка матрицы (воркеру виден ли бюджет попыток).
  // Заголовки: пустая карта (live customHeaders={}) и отсутствие заголовков
  // (ours taskHeaders=null) — одно и то же, приводим к общему виду.
  const headers = canonicalJson(o.customHeaders ?? o.taskHeaders ?? {});
  // Переменные — по СОДЕРЖАНИЮ (порядок ключей не важен: live отдаёт сырой
  // JSON в порядке вставки, ours — отсортированный).
  const vars = o.variables === undefined || o.variables === null ? '' : canonicalJson(o.variables);
  // Инцидент на live-8.5 не читается без Operate (в стенде нет Operate) —
  // обе стороны сводим к «не сравнимо», окончательный вердикт по инциденту
  // ставится по документации 8.9 и по отсутствию следующего задания.
  const outcome = o.step === 'observeIncident' ? 'incident?' : (o.outcome || '');
  return [
    o.element || '',
    outcome,
    o.retries === undefined || o.retries === null ? '' : o.retries,
    o.jobType || '',
    headers,
    vars,
  ].join('|');
}

function parseMaybeJson(raw) {
  if (raw === undefined || raw === null) {
    return undefined;
  }
  if (typeof raw !== 'string') {
    return raw;
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return raw;
  }
}

/** Канонический JSON (ключи отсортированы) — иначе live и ours разошлись бы
 *  только из-за порядка ключей, а это не расхождение поведения. */
function canonicalJson(raw) {
  const parsed = parseMaybeJson(raw);
  if (parsed === undefined) {
    return 'undefined';
  }
  if (parsed === null || typeof parsed !== 'object') {
    return JSON.stringify(parsed);
  }
  const sort = (v) => {
    if (Array.isArray(v)) return v.map(sort);
    if (v && typeof v === 'object') {
      const out = {};
      Object.keys(v).sort().forEach((k) => { out[k] = sort(v[k]); });
      return out;
    }
    return v;
  };
  return JSON.stringify(sort(parsed));
}

function normalize(observations) {
  return (observations || []).filter((o) => o && !o.skipped);
}
