#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 4 («события») — сравнение нашего прогона с живым Zeebe 8.5.0.
 *
 * Входы:
 *   ours-events/*.json   — наблюдения нашего движка (zorrobpm-engine/target/scenario-matrix/ours-events/)
 *   zeebe-events.json    — наблюдения Zeebe 8.5.0 (zorrobpm-engine/target/scenario-matrix/)
 *
 * Выходы:
 *   docs/analysis/bpmn-scenario-matrix/phase4-divergences.md     — таблица (человеку)
 *   docs/analysis/bpmn-scenario-matrix/phase4-divergences.json   — то же машинно
 *   zorrobpm-engine/src/test/resources/scenario-matrix/event-expectations.json
 *        — «прибитые» факты (наши + живого Zeebe): их CI сверяет БЕЗ живого Zeebe
 *
 * Смысл «прибитых» фактов: расхождение НЕ чинится в этой кампании (баги — отдельными
 * задачами), но оно не должно исчезнуть молча. Поэтому в эталоне хранятся ОБЕ стороны
 * (что сделал Zeebe 8.5 и что сделали мы) и вычисленный вердикт; наш прогон в CI
 * сверяется с прибитой НАШЕЙ стороной — любое изменение поведения движка роняет сборку.
 *
 * Использование:
 *   node scenario-matrix-event-diff.js [--ours <dir>] [--zeebe <path>]
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');

function argOf(args, name, dflt) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? path.resolve(args[i + 1]) : dflt;
}

/** Значение в общем виде: JSON-строка разбирается, остальное — как есть. */
function normalize(raw) {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== 'string') return raw;
  const t = raw.trim();
  if (t === '') return raw;
  if (t.startsWith('[') || t.startsWith('{')) {
    try { return JSON.parse(t); } catch (e) { return raw; }
  }
  return raw;
}

function normalizeVars(vars) {
  const out = {};
  for (const k of Object.keys(vars || {}).sort()) out[k] = normalize(vars[k]);
  return out;
}

/** Нарушения инварианта утечек — без id инстанса (он случайный). */
function normalizeLeaks(leaks) {
  return (leaks || []).map((l) => String(l).replace(/instance=[0-9a-f-]{36}\s*/, ''));
}

function same(a, b) {
  return String(a) === String(b);
}

const args = process.argv.slice(2);
const oursDir = argOf(args, '--ours', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'ours-events'));
const zeebePath = argOf(args, '--zeebe', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-events.json'));
const outDir = path.join(ROOT, 'docs', 'analysis', 'bpmn-scenario-matrix');
const expectPath = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'scenario-matrix', 'event-expectations.json');

if (!fs.existsSync(oursDir)) {
  console.error(`missing ours dir: ${oursDir} — run the engine event slice first`);
  process.exit(2);
}
if (!fs.existsSync(zeebePath)) {
  console.error(`missing oracle output: ${zeebePath} — run scenario-matrix-events.js against the live Zeebe first`);
  process.exit(2);
}

const ours = [];
for (const file of fs.readdirSync(oursDir).sort()) {
  if (!file.endsWith('.json')) continue;
  const parsed = JSON.parse(fs.readFileSync(path.join(oursDir, file), 'utf8'));
  for (const o of parsed.outcomes || []) ours.push(o);
}
const zeebeDoc = JSON.parse(fs.readFileSync(zeebePath, 'utf8'));
const zeebe = zeebeDoc.outcomes || [];
const zeebeById = new Map(zeebe.map((z) => [z.id, z]));

/** Волатильные ключи сценария (число срабатываний цикла, число инстансов на сигнал). */
function volatileOf(o) {
  return o.volatileKeys || [];
}

/**
 * Расходится ли ЧИСЛО инстанцирований элементов (ours elementCounts vs zeebe jobCounts)?
 *
 * <p>Сравниваются только элементы, которые есть на ОБЕИХ сторонах (у нас в elementCounts
 * попадают ещё и потоки — у Zeebe job'ов на них нет). Это ровно тот вопрос, который стоит
 * за расхождением ENG-3: сколько раз сработала граница — один раз на тело MI или по
 * разу на инстанс MI.
 */
function countDiffers(entry) {
  const ours = entry.ours.elementCounts || {};
  const zeebe = entry.zeebe.jobCounts || {};
  for (const el of Object.keys(ours)) {
    if (!(el in zeebe)) continue;
    if (Number(ours[el]) !== Number(zeebe[el])) return true;
  }
  return false;
}

/** Подробности расхождения чисел (для отчёта). */
function countDetail(entry) {
  const ours = entry.ours.elementCounts || {};
  const zeebe = entry.zeebe.jobCounts || {};
  const parts = [];
  for (const el of Object.keys(ours)) {
    if (!(el in zeebe)) continue;
    if (Number(ours[el]) !== Number(zeebe[el])) {
      parts.push(`${el}: ours=${ours[el]} zeebe=${zeebe[el]}`);
    }
  }
  return parts.join('; ');
}

const rows = [];
const expectations = {};
let match = 0;
let differs = 0;
let rejected = 0;

for (const o of ours) {
  const z = zeebeById.get(o.id);
  const obs = o.observation || {};
  const oursVars = normalizeVars(obs.vars);
  const zObs = z ? (z.observation || {}) : {};
  const zVars = normalizeVars(zObs.vars);
  const entry = {
    id: o.id,
    family: o.family,
    element: o.element,
    ours: {
      status: o.status,
      dumpReached: !!obs.dumpReached,
      vars: oursVars,
      liveElements: obs.liveElements || {},
      elementCounts: obs.elementCounts || {},
      instanceCount: obs.instanceCount,
      leaks: normalizeLeaks(o.leaks || []),
      leakCheck: ['COMPLETED', 'CANCELLED'].includes(o.status)
        ? 'применим' : 'н/д — инстанс не завершён (инвариант неприменим)',
      incidents: o.incidents || [],
      error: o.error || '',
    },
    zeebe: z ? {
      status: z.status,
      dumpReached: !!zObs.dumpReached,
      vars: zVars,
      leftoverJobs: zObs.leftoverJobs || {},
      jobCounts: zObs.jobCounts || {},
      unsupportedActions: z.unsupportedActions || [],
      error: z.error || '',
    } : null,
    verdict: '',
    detail: '',
  };

  if (!z) {
    entry.verdict = 'no-oracle-run';
    entry.detail = 'сценарий не прогонялся против оракула';
    differs += 1;
    rows.push(entry);
    continue;
  }

  // Оракул НЕ ПРИНЯЛ МОДЕЛЬ — ограничение версии Zeebe 8.5, а не расхождение рантайма.
  const rejectedByOracle = z.status === 'FAILED' && /INVALID_ARGUMENT/.test(z.error || '');
  if (rejectedByOracle) {
    entry.verdict = 'oracle-deploy-rejected';
    entry.detail = 'Zeebe 8.5 не принимает модель на деплое: '
      + String(z.error || '').split('\n').map((l) => l.trim()).filter(Boolean).slice(1, 4).join(' / ');
    rejected += 1;
  } else if (entry.ours.dumpReached !== entry.zeebe.dumpReached) {
    entry.verdict = 'ours differs';
    entry.detail = `точка наблюдения: ours=${entry.ours.dumpReached} zeebe=${entry.zeebe.dumpReached}`;
    differs += 1;
  } else if (JSON.stringify(entry.ours.vars) !== JSON.stringify(entry.zeebe.vars)) {
    entry.verdict = 'ours differs';
    entry.detail = `переменные в точке наблюдения: ours=${JSON.stringify(entry.ours.vars)} zeebe=${JSON.stringify(entry.zeebe.vars)}`;
    differs += 1;
  } else if (!volatileOf(o).includes('elementCounts') && countDiffers(entry)) {
    entry.verdict = 'ours differs';
    entry.detail = `сколько раз инстанцирован элемент: ${countDetail(entry)}`;
    differs += 1;
  } else if (entry.ours.leaks.length > 0) {
    entry.verdict = 'ours differs';
    entry.detail = `утечка по инварианту G-P: ${entry.ours.leaks.join('; ')}`;
    differs += 1;
  } else {
    entry.verdict = 'match';
    entry.detail = '';
    match += 1;
  }

  // Число срабатываний циклического таймера — величина от времени окна, не от поведения.
  const volatileKeys = volatileOf(o);
  if (volatileKeys.includes('elementCounts')) {
    entry.verdict = entry.verdict === 'match' ? 'match' : entry.verdict;
    entry.detail = entry.detail
      ? entry.detail
      : `факт срабатывания совпал; число срабатываний цикла (ours elementCounts=${JSON.stringify(entry.ours.elementCounts)} / zeebe jobCounts=${JSON.stringify(entry.zeebe.jobCounts)}) зависит от окна ожидания и не сравнивается`;
  }

  rows.push(entry);

  // Прибитый факт: наша сторона без волатильных ключей.
  const pinnedOurs = { status: entry.ours.status, dumpReached: entry.ours.dumpReached, vars: entry.ours.vars };
  if (!volatileKeys.includes('liveElements')) pinnedOurs.liveElements = entry.ours.liveElements;
  if (!volatileKeys.includes('elementCounts')) pinnedOurs.elementCounts = entry.ours.elementCounts;
  if (!volatileKeys.includes('instanceCount')) pinnedOurs.instanceCount = entry.ours.instanceCount;
  pinnedOurs.leaks = entry.ours.leaks;
  pinnedOurs.incidents = entry.ours.incidents;
  expectations[o.id] = {
    zeebe: entry.zeebe ? {
      status: entry.zeebe.status,
      dumpReached: entry.zeebe.dumpReached,
      vars: entry.zeebe.vars,
      leftoverJobs: entry.zeebe.leftoverJobs,
      jobCounts: entry.zeebe.jobCounts,
    } : null,
    ours: pinnedOurs,
    verdict: entry.verdict,
    detail: entry.detail,
  };
}

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'phase4-divergences.json'),
  JSON.stringify({
    workOrder: 'WO-OPS-28 ФАЗА 4',
    slice: 'события (start/end/intermediate/boundary/timer/incident/cancel)',
    oracle: zeebeDoc.gateway ? `Zeebe ${zeebeDoc.gateway}` : 'zeebe-8.5.0',
    generatedAt: new Date().toISOString(),
    summary: { match, differs, rejected, total: rows.length },
    rows,
  }, null, 2) + '\n');

const md = [];
md.push('# Расхождения среза «события» (ФАЗА 4) — Zeebe 8.5 live × ZorroBPM');
md.push('');
md.push(`Источники: наш прогон — \`zorrobpm-engine/target/scenario-matrix/ours-events/\`, живой оракул — `);
md.push(`\`docs/analysis/c8-oracle/scenario-matrix-events.js\` (Zeebe ${zeebeDoc.gateway || '8.5.0'}).`);
md.push('');
md.push(`**Итог:** match — ${match}, ours differs — ${differs}, oracle-deploy-rejected — ${rejected}, всего — ${rows.length}.`);
md.push('');
md.push('| # | элемент | сценарий | Zeebe 8.5 live | ZorroBPM | утечки G-P | вердикт |');
md.push('|---|---|---|---|---|---|---|');
rows.forEach((r, i) => {
  const z = r.zeebe || {};
  const zeebeCell = `точка наблюдения: ${z.dumpReached ? 'достигнута' : 'НЕ достигнута'}; job'ы: ${JSON.stringify(z.jobCounts || {})}; остаток: ${JSON.stringify(z.leftoverJobs || {})}`;
  const oursCell = `статус: ${r.ours.status}; точка наблюдения: ${r.ours.dumpReached ? 'достигнута' : 'НЕ достигнута'}; элементы: ${JSON.stringify(r.ours.elementCounts || {})}; живы: ${JSON.stringify(r.ours.liveElements || {})}`;
  // Инвариант утечек применим ТОЛЬКО к завершённым инстансам (LeakInvariantChecker
  // берёт completed_at IS NOT NULL). Для нетерминальных сценариев колонка не «нет»,
  // а «н/д» — иначе таблица утверждает «утечек нет» там, где проверка не могла сработать.
  const terminal = ['COMPLETED', 'CANCELLED'].includes(r.ours.status);
  const leaks = !terminal
    ? 'н/д — инстанс не завершён (инвариант неприменим)'
    : (r.ours.leaks.length ? r.ours.leaks.join('; ') : 'нет');
  md.push(`| ${i + 1} | ${r.element} | \`${r.id}\` | ${zeebeCell} | ${oursCell} | ${leaks} | **${r.verdict}**${r.detail ? ' — ' + r.detail : ''} |`);
});
md.push('');
fs.writeFileSync(path.join(outDir, 'phase4-divergences.md'), md.join('\n') + '\n');

fs.writeFileSync(expectPath, JSON.stringify({
  meta: {
    workOrder: 'WO-OPS-28 ФАЗА 4',
    slice: 'события (start/end/intermediate/boundary/timer/incident/cancel)',
    oracle: `Zeebe ${zeebeDoc.gateway || '8.5.0'} (camunda/zeebe:8.5.0), стенд zbpm-cmp-zeebe, gRPC 127.0.0.1:36500`,
    howToRegenerate: 'node docs/analysis/c8-oracle/scenario-matrix-events.js && node docs/analysis/c8-oracle/scenario-matrix-event-diff.js',
    note: 'Файл перезаписывается diff-инструментом; руками не правится.',
  },
  scenarios: expectations,
}, null, 2) + '\n');

console.log(`match=${match} differs=${differs} rejected=${rejected} total=${rows.length}`);
console.log(`WROTE ${path.join(outDir, 'phase4-divergences.md')}`);
console.log(`WROTE ${expectPath}`);
for (const r of rows) {
  if (r.verdict !== 'match') {
    console.log(`  ${r.verdict}: ${r.id} — ${r.detail}`);
  }
}
