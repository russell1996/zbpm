#!/usr/bin/env node
/* WO-OPS-30 F2c — СООБЩЕНИЯ (+ script/DMN) против живого Zeebe 8.5 (оракул).
 *
 * Один список кейсов на обе стороны (G-Q):
 * zorrobpm-engine/src/test/resources/message-cases.json — ЕДИНСТВЕННЫЙ источник правды
 * для BPMN и для шагов. Оракул и наш движок исполняют ОДНИ И ТЕ ЖЕ сценарии.
 *
 *   node messages.js --sync        # перегенерировать scenarios/f2v-<id> из списка кейсов
 *   node messages.js                # прогнать все кейсы против живого Zeebe 8.5
 *   node messages.js f2v-msg-start  # один кейс
 *   node messages.js --ours <file>  # подставить колонку 'ours' из нашего прогона
 *
 * Шаги сценария: publish (PublishMessage) / observeJob / completeJob / observeState.
 * Наблюдение «есть ли задание» на живой стороне — активация задания нужного типа;
 * на нашей стороне — тело задания в outbox. Сравниваются по (шаг, элемент, outcome,
 * тип задания, переменные) — порядок ключей в JSON не считается расхождением.
 *
 * Побайтовая сверка: перед прогоном каждый process.bpmn в scenarios/f2v-<id> сверяется
 * с каноническим файлом из zorrobpm-engine/src/test/resources/message/ — если они
 * разошлись, прогон падает (иначе «оба движка» measuring разные модели).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const O = require('./lib/oracle');

const ROOT = path.join(__dirname, '..', '..', '..');
const CASES_FILE = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'message-cases.json');
const BPMN_DIR = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'message');
const SCEN_DIR = path.join(__dirname, 'scenarios');
const DEFAULT_OURS = path.join(ROOT, 'zorrobpm-engine', 'target', 'message-observed.json');

function loadCases() {
  const raw = JSON.parse(fs.readFileSync(CASES_FILE, 'utf8'));
  return raw.cases || [];
}

/** element id -> job type (zeebe:taskDefinition type) из BPMN. */
function elementTypes(bpmnXml) {
  const map = new Map();
  // В Camunda 8.5 бросок сообщения — тоже задание (zeebe:taskDefinition на
  // intermediateThrowEvent), поэтому тип ищем у ЛЮБОГО элемента. Разбор идёт по
  // открывающим тегам: тело элемента — до его собственного закрывающего тега
  // (жадный regex по </bpmn:X> склеивал бы соседние элементы).
  const openRe = /<bpmn:([a-zA-Z]+)\b([^>]*?)(\/?)>/g;
  let m;
  while ((m = openRe.exec(bpmnXml)) !== null) {
    const tag = m[1];
    const attrs = m[2];
    const selfClosing = m[3] === '/';
    const idMatch = /\bid="([^"]+)"/.exec(attrs);
    if (!idMatch) continue;
    const bodyStart = m.index + m[0].length;
    let body = '';
    if (!selfClosing) {
      const closeIdx = bpmnXml.indexOf(`</bpmn:${tag}>`, bodyStart);
      body = closeIdx >= 0 ? bpmnXml.slice(bodyStart, closeIdx) : '';
    }
    const t = /<zeebe:taskDefinition[^>]*\btype="([^"]+)"/.exec(body);
    if (t) map.set(idMatch[1], t[1]);
  }
  return map;
}

function readBpmn(c) {
  const canonical = path.join(BPMN_DIR, path.basename(c.bpmn));
  if (!fs.existsSync(canonical)) throw new Error(`missing canonical BPMN ${canonical}`);
  return { canonical, xml: fs.readFileSync(canonical, 'utf8') };
}

/** Копия BPMN в сценарий + scenario.json (формат _template, расширенный шагами). */
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
      start: c.start || 'none',
      publishBeforeDeploy: !!c.publishBeforeDeploy,
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

/** Побайтовая сверка сценария с канонической моделью. */
function assertScenarioMatchesCanonical(c) {
  const { canonical, xml } = readBpmn(c);
  const scenFile = path.join(SCEN_DIR, c.id, 'process.bpmn');
  if (!fs.existsSync(scenFile)) {
    throw new Error(`scenario ${c.id}: ${scenFile} missing — run 'node messages.js --sync'`);
  }
  const scenXml = fs.readFileSync(scenFile, 'utf8');
  if (scenXml !== xml) {
    throw new Error(`scenario ${c.id}: process.bpmn differs from ${path.relative(ROOT, canonical)} — re-run --sync`);
  }
  return xml;
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
    const observations = [];
    let pi = null;

    // Шаг публикации ДО деплоя (кейс про буфер): подписки ещё нет.
    if (c.publishBeforeDeploy) {
      for (const step of c.steps || []) {
        if (step.action !== 'publish') continue;
        try {
          const res = await O.publishMessage(client, step.name, step.correlationKey, step.timeToLive, step.variables);
          observations.push({ step: 'publish', outcome: 'published', key: String(res.key), publishedBeforeDeploy: true });
        } catch (e) {
          observations.push({ step: 'publish', outcome: 'error', error: String(e.message || e) });
        }
      }
    }

    // Деплой. Отказ деплоя — самостоятельное наблюдение (так, например, 8.5 не знает
    // zeebe:calledDecision): дальше сценарий не идёт.
    let deployed = true;
    try {
      await O.deploy(client, path.join(scenDir, 'process.bpmn'));
      observations.push({ step: 'deploy', outcome: 'deployOk' });
    } catch (e) {
      deployed = false;
      observations.push({ step: 'deploy', outcome: 'deployError', error: String(e.message || e) });
    }

    if (deployed) {
      if (c.publishBeforeStart) {
        // Публикация ДО старта инстанса: подписки ещё нет — проверяем буфер.
        for (const step of c.steps || []) {
          if (step.action !== 'publish') continue;
          try {
            const res = await O.publishMessage(client, step.name, step.correlationKey, step.timeToLive, step.variables);
            observations.push({ step: 'publish', outcome: 'published', key: String(res.key), publishedBeforeStart: true });
          } catch (e) {
            observations.push({ step: 'publish', outcome: 'error', error: String(e.message || e) });
          }
        }
      }
      if ((c.start || 'none') !== 'message') {
        pi = await O.start(client, c.processId, c.vars || {});
      } else {
        // Инстанс создаёт публикация сообщения. Перед шагами сливаем ОСТАТКИ
        // заданий этого типа (инстансы прошлых прогонов на общем стенде) — иначе
        // наблюдение может достать чужое задание с тем же caseTag.
        for (const step of c.steps || []) {
          if (step.action !== 'observeJob') continue;
          const type = types.get(step.element);
          if (!type) continue;
          const stale = await O.activate(client, type, 10, 1000);
          for (const j of stale) {
            await O.complete(client, j, {}).catch(() => {});
          }
          if (stale.length) {
            console.log(`DRAIN stale job(s) of type ${type}: ${stale.length}`);
          }
        }
      }
      for (const step of c.steps || []) {
        if (c.publishBeforeDeploy && step.action === 'publish') continue; // уже опубликовано до деплоя
        if (c.publishBeforeStart && step.action === 'publish') continue; // уже опубликовано до старта
        try {
          if (step.action === 'publish') {
            const res = await O.publishMessage(client, step.name, step.correlationKey, step.timeToLive, step.variables);
            observations.push({ step: 'publish', outcome: 'published', key: String(res.key) });
          } else if (step.action === 'observeJob' || step.action === 'completeJob') {
            const type = types.get(step.element);
            if (!type) throw new Error(`unknown element '${step.element}'`);
            // Короткий таймаут активации для НАБЛЮДЕНИЯ: задание, которое мы
            // активировали и не завершили, не должно лежать заблокированным —
            // иначе следующее наблюдание того же элемента увидит «noJob» и
            // выдаст ложный вывод (замеренный факт: блокировка активации).
            const activationTimeoutMs = step.timeoutMs
              || (step.action === 'observeJob' ? 1500 : 5000);
            const jobs = await O.activate(client, type, 10, activationTimeoutMs);
            // Инстанс, созданный публикацией сообщения, на живой стороне заранее
            // неизвестен — тогда задание фильтруется по МЕТКЕ кейса (caseTag в
            // переменных сообщения): иначе можно взять задание чужого инстанса
            // (оставшееся на стенде) и записать в матрицу чужое наблюдение.
            const mine = pi
              ? jobs.filter((j) => String(j.processInstanceKey) === String(pi))
              : jobs.filter((j) => caseTagOf(j.variables) === c.id);
            if (mine.length === 0) {
              observations.push({ step: step.action, element: step.element, jobType: type, outcome: 'noJob' });
              continue;
            }
            const job = mine[0];
            if (!pi) pi = String(job.processInstanceKey);
            if (step.action === 'observeJob') {
              observations.push({
                step: step.action,
                element: step.element,
                jobType: job.type,
                variables: job.variables,
                outcome: 'jobObserved',
              });
            } else {
              await O.complete(client, job, step.vars || {});
              observations.push({
                step: step.action,
                element: step.element,
                jobType: job.type,
                vars: step.vars || {},
                outcome: 'completed',
              });
            }
          } else if (step.action === 'observeState') {
            // У Gateway API 8.5 нет чтения состояния инстанса (это Operate, его в стенде нет).
            observations.push({
              step: step.action,
              outcome: 'notReadable',
              note: 'no instance-state API on the Zeebe 8.5 Gateway (Operate is not part of this stand)',
            });
          } else {
            observations.push({ step: step.action, element: step.element, outcome: 'unknownStep' });
          }
        } catch (e) {
          observations.push({ step: step.action, element: step.element, outcome: 'error', error: String(e.message || e) });
        }
      }
    }

    if (pi) {
      await O.cancelPi(client, pi).catch(() => {});
    }

    const liveSummary = summarize(observations);
    const ours = oursById[c.id] || null;
    const oursSummary = ours ? summarize(ours.observations || []) : '(pending)';
    const docs89 = c.docs89 || '';
    const verdict = ours ? verdictOf(observations, ours) : 'measured-8.5 (ours pending)';
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
    fs.writeFileSync(path.join(scenDir, 'result-8.5.json'), JSON.stringify(out, null, 2) + '\n');
    console.log(`\n### ${c.id} — ${c.title}`);
    console.log(`LIVE:  ${liveSummary}`);
    console.log(`OURS:  ${oursSummary}`);
    console.log(`MATRIX: ${matrixLine}`);
    lines.push(matrixLine);
  }
})().catch((e) => {
  console.error(`MESSAGES FAILED: ${e.stack || e.message}`);
  process.exit(1);
});

/** Компактная сводка наблюдений для колонки матрицы. */
function summarize(observations) {
  return (observations || [])
    .map((o) => {
      const bits = [];
      if (o.jobType) bits.push(`type=${o.jobType}`);
      if (o.variables !== undefined && o.variables !== null) bits.push(`vars=${canonicalJson(o.variables)}`);
      if (o.vars !== undefined && o.vars !== null) bits.push(`completedWith=${canonicalJson(o.vars)}`);
      if (o.outcome) bits.push(o.outcome);
      if (o.error) bits.push(`ERROR=${String(o.error).slice(0, 160).replace(/\n/g, ' ')}`);
      return `${o.step}${o.element ? `(${o.element})` : ''}: ${bits.join(' ') || '—'}`;
    })
    .join(' | ');
}

/**
 * Механическое сравнение колонок — ПОМОЩНИК, не вердикт: окончательный вердикт в
 * матрице ставит человек (он объясняет расхождение).
 */
function verdictOf(liveObs, ours) {
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

/** Ключ шага: действие + элемент + порядковый номер повторов. */
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
  // Состояние инстанса на живой стороне не читается — обе стороны сводим к «не сравнимо»,
  // окончательный вердикт по состоянию ставится по документации 8.9 и по наличию задания.
  if (o.step === 'observeState') {
    return `${o.element || ''}|state?|`;
  }
  const vars = o.variables === undefined || o.variables === null ? '' : canonicalJson(o.variables);
  // «Инстанта нет» и «задания нет» — одно и то же наблюдение с разных сторон:
  // на живой стороне инстанс может быть, а задания нет; у нас инстанта нет.
  const outcome = o.outcome === 'noInstance' ? 'noJob' : (o.outcome || '');
  return [o.element || '', outcome, o.jobType || '', vars].join('|');
}

/** Метка кейса в переменных задания (caseTag) — фильтр «своё задание». */
function caseTagOf(rawVars) {
  const parsed = parseMaybeJson(rawVars);
  if (!parsed || typeof parsed !== 'object') {
    return null;
  }
  const tag = parsed.caseTag;
  return tag === undefined || tag === null ? null : String(tag);
}

function parseMaybeJson(raw) {
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== 'string') return raw;
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
  if (parsed === undefined) return 'undefined';
  if (parsed === null || typeof parsed !== 'object') return JSON.stringify(parsed);
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
