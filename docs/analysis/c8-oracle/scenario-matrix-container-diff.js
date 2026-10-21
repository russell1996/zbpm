#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 3 («контейнеры») — сравнение нашего прогона с живым Zeebe 8.5.0.
 *
 * Входы:
 *   ours-containers/*.json   — наблюдения нашего движка
 *                             (zorrobpm-engine/target/scenario-matrix/ours-containers/)
 *   zeebe-containers.json    — наблюдения Zeebe 8.5.0
 *                             (zorrobpm-engine/target/scenario-matrix/zeebe-containers.json)
 *
 * Выходы:
 *   docs/analysis/bpmn-scenario-matrix/phase3-divergences.md     — таблица (человеку)
 *   docs/analysis/bpmn-scenario-matrix/phase3-divergences.json   — то же машинно
 *   zorrobpm-engine/src/test/resources/scenario-matrix/container-expectations.json
 *        — «прибитые» факты (наши + живого Zeebe): их CI сверяет БЕЗ живого Zeebe
 *
 * Смысл «прибитых» фактов: расхождение НЕ чинится в этой кампании (баги —
 * отдельными задачами), но оно не должно исчезнуть молча. В эталоне хранятся ОБЕ
 * стороны (что сделал Zeebe 8.5 и что сделали мы) и вычисленный вердикт; наш
 * прогон в CI сверяется с прибитой НАШЕЙ стороной — любое изменение поведения
 * движка роняет сборку и заставляет переснять эталон (то есть пересмотреть
 * расхождение, а не закрыть его случайно).
 *
 * Использование:
 *   node scenario-matrix-container-diff.js [--ours <dir>] [--zeebe <path>]
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');

function argOf(args, name, dflt) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? path.resolve(args[i + 1]) : dflt;
}

/** Приводит значение к общему виду: JSON-строка разбирается, остальное — как есть. */
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

/**
 * Краткая, УСТОЙЧИВАЯ сводка текста отказа оракула.
 *
 * Полный текст отказа Zeebe перечисляет дефекты модели в НЕдетерминированном
 * порядке (между прогонами строки идут иначе) — прибивать его дословно нельзя:
 * эталон «дышал» бы при каждой пересъёмке, и пересъёмка перестала бы быть
 * проверяемым шагом. Поэтому в эталон идёт СВОДКА (отсортированный список
 * отвергнутых элементов), а полный текст остаётся в артефакте прогона
 * (`target/scenario-matrix/zeebe-containers.json`) и в отчёте.
 */
function errorSummary(raw) {
  const text = String(raw || '');
  if (!text.includes('\n')) {
    return text;
  }
  const elements = new Set();
  const re = /- Element: ([A-Za-z0-9_.-]+)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    elements.add(m[1]);
  }
  const reasons = new Set();
  const re2 = /- ERROR: ([^\n]+)/g;
  while ((m = re2.exec(text)) !== null) {
    reasons.add(m[1].trim());
  }
  const head = text.split('\n')[0];
  return `${head} | отвергнутые элементы: ${[...elements].sort().join(', ')}`
    + ` | причины: ${[...reasons].sort().join(' / ')}`;
}

/** Нарушения инварианта утечек — без id инстанса (он случайный). */
function normalizeLeaks(leaks) {
  return (leaks || []).map((l) => String(l).replace(/instance=[0-9a-f-]{36}\s*/, '')).sort();
}

function normalizeVars(vars) {
  const out = {};
  for (const k of Object.keys(vars || {}).sort()) out[k] = normalize(vars[k]);
  return out;
}

/** Сравнение значений — ПО СТРОКЕ (наш движок хранит значение сырым текстом). */
function same(a, b) {
  return String(a) === String(b);
}

/** Сравнение списков шагов (порядок значим: это порядок вождения). */
function sameSteps(a, b) {
  const x = (a || []).map(String);
  const y = (b || []).map(String);
  return x.length === y.length && x.every((v, i) => v === y[i]);
}

function diffKeys(a, b) {
  const keys = new Set([...Object.keys(a || {}), ...Object.keys(b || {})]);
  const out = [];
  for (const k of [...keys].sort()) {
    if (!same((a || {})[k], (b || {})[k])) {
      out.push(`${k}: ours=${JSON.stringify((a || {})[k])} zeebe=${JSON.stringify((b || {})[k])}`);
    }
  }
  return out;
}

/**
 * Статусы двух сторон приводятся к общему виду:
 *   COMPLETED      — дошли до точки наблюдения и завершились
 *   NO_DUMP        — не дошли до точки наблюдения (поток прерван/завис)
 *   DEPLOY_REJECTED — модель отвергнута при деплое
 *   FAILED         — прогон сломался (ошибка)
 */
function flowStatus(ours, zeebe) {
  const map = {
    COMPLETED: 'COMPLETED',
    NO_DUMP: 'NO_DUMP',
    NO_DUMP_JOB: 'NO_DUMP',
    DEPLOY_REJECTED: 'DEPLOY_REJECTED',
    FAILED: 'FAILED',
    NOT_COMPLETED: 'NOT_COMPLETED',
    LEFTOVER_JOBS: 'COMPLETED',
  };
  return {
    ours: map[ours] || ours,
    zeebe: map[zeebe] || zeebe,
  };
}

const args = process.argv.slice(2);
const oursDir = argOf(args, '--ours',
  path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'ours-containers'));
const zeebePath = argOf(args, '--zeebe',
  path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-containers.json'));
const outDir = path.join(ROOT, 'docs', 'analysis', 'bpmn-scenario-matrix');
const expectPath = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources',
  'scenario-matrix', 'container-expectations.json');

if (!fs.existsSync(oursDir)) {
  console.error(`missing ours dir: ${oursDir} — run the engine container slice first`);
  process.exit(2);
}
if (!fs.existsSync(zeebePath)) {
  console.error(`missing oracle output: ${zeebePath} — run scenario-matrix-container.js against the live Zeebe first`);
  process.exit(2);
}

// Наши наблюдения: по файлам семейств.
const ours = [];
for (const file of fs.readdirSync(oursDir).sort()) {
  if (!file.endsWith('.json')) continue;
  const parsed = JSON.parse(fs.readFileSync(path.join(oursDir, file), 'utf8'));
  for (const o of parsed.outcomes || []) ours.push(o);
}
const oracle = JSON.parse(fs.readFileSync(zeebePath, 'utf8'));
const zeebeById = new Map((oracle.outcomes || []).map((o) => [o.id, o]));

const scenarios = [];
const problems = [];
for (const o of ours) {
  const z = zeebeById.get(o.id);
  if (!z) {
    problems.push(`${o.id}: нет наблюдения живого Zeebe — прогони scenario-matrix-container.js`);
    continue;
  }
  const st = flowStatus(o.status, z.status);
  const oursVars = normalizeVars(o.rootVars);
  const zeebeVars = normalizeVars(z.rootVars);
  const varDiff = diffKeys(oursVars, zeebeVars);
  const oursLeaks = normalizeLeaks(o.leaks);
  const zeebeLeftovers = (z.leftovers || []).map((l) => `${l.type}:${l.elementId}`).sort();

  // ПОРЯДОК ПРАВИЛ — ЕДИНЫЙ с пересчётом вердикта в CI
  // (ContainerScenarioMatrixIntegrationTests.recomputeVerdict): отказ оракула на деплое →
  // отказ у нас на деплое → расхождение статуса → расхождение переменных →
  // расхождение остатка (утечки) → расхождение достигнутых шагов.
  // Порядок важен: если он разойдётся между инструментом и CI, легитимная пересъёмка
  // эталона будет ронять сборку (и единственным путём к зелёному станет правка вердикта
  // руками — ровно то, что запрещено).
  let verdict = 'match';
  const details = [];
  if (st.zeebe === 'DEPLOY_REJECTED') {
    verdict = 'oracle-deploy-rejected';
    details.push('Zeebe 8.5 отверг модель на деплое — сравнивать нечего (вывод о паритете приёма модели).');
  } else if (st.ours === 'DEPLOY_REJECTED') {
    verdict = 'ours-deploy-rejected';
    details.push('мы отвергли модель на деплое, а Zeebe 8.5 её исполнил (вывод о паритете приёма модели).');
  } else if (st.ours !== st.zeebe) {
    verdict = 'ours-differs';
    details.push(`поток разошёлся: ours=${st.ours} zeebe=${st.zeebe}`);
  } else if (varDiff.length > 0) {
    verdict = 'ours-differs';
    details.push(...varDiff);
  } else if ((oursLeaks.length > 0) !== (zeebeLeftovers.length > 0)) {
    verdict = 'ours-leaks';
    details.push(oursLeaks.length > 0
      ? `у нас остаток в завершённом инстансе: ${JSON.stringify(oursLeaks)}; у Zeebe остатка нет`
      : `у Zeebe остались job'ы: ${JSON.stringify(zeebeLeftovers)}; у нас остатка нет`);
  } else if (!sameSteps(o.reachedSteps || [], z.reachedSteps || [])) {
    verdict = 'ours-differs';
    details.push(`достигнутые шаги разошлись: ours=${JSON.stringify(o.reachedSteps || [])}`
      + ` zeebe=${JSON.stringify(z.reachedSteps || [])}`);
  }
  if (oursLeaks.length > 0) {
    details.push(`LEAK-INVARIANT (G-P): ${JSON.stringify(oursLeaks)}`);
  }
  scenarios.push({
    id: o.id,
    container: o.container,
    content: o.content,
    cardinality: o.cardinality,
    oracle: st.zeebe === 'DEPLOY_REJECTED' ? 'REJECTED-8.5' : 'SUPPORTED-8.5',
    zeebe: {
      status: z.status,
      vars: z.rootVars || {},
      leftovers: zeebeLeftovers,
      reachedSteps: z.reachedSteps || [],
      probedTypes: z.probedTypes || [],
      error: errorSummary(z.error),
    },
    ours: {
      status: o.status,
      vars: o.rootVars || {},
      leaks: oursLeaks,
      incidents: o.incidents || [],
      reachedSteps: o.reachedSteps || [],
      error: errorSummary(o.error),
    },
    verdict,
    detail: details.join('; '),
  });
}

scenarios.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));

// ---- прибитые факты ----
const pinned = {
  oracle: 'zeebe-8.5.0-live',
  note: 'Факты живого Zeebe 8.5.0 и наши, снятые прогоном '
    + 'docs/analysis/c8-oracle/scenario-matrix-container.js и срезом F3 нашего движка '
    + '(ContainerScenarioMatrixIntegrationTests). Расхождения НЕ чинятся в кампании: '
    + 'они закреплены здесь, чтобы их нельзя было закрыть молча.',
  scenarios: {},
};
for (const s of scenarios) {
  pinned.scenarios[s.id] = {
    oracle: s.oracle,
    zeebe: s.zeebe,
    ours: s.ours,
    verdict: s.verdict,
    detail: s.detail,
  };
}
fs.mkdirSync(path.dirname(expectPath), { recursive: true });
fs.writeFileSync(expectPath, JSON.stringify(pinned, null, 2) + '\n');
console.log(`WROTE ${expectPath}`);

// ---- таблица (человеку) ----
const byVerdict = (v) => scenarios.filter((s) => s.verdict === v).length;
const md = [];
md.push('# WO-OPS-28 ФАЗА 3 («контейнеры») — расхождения с живым Zeebe 8.5.0');
md.push('');
md.push(`- **Дата:** ${new Date().toISOString().slice(0, 10)}`);
md.push(`- **Оракул:** живой Zeebe 8.5.0 (${oracle.gateway || '8.5.0'}), стенд \`zbpm-cmp-zeebe\`, gRPC 127.0.0.1:36500`);
md.push(`- **Сценариев:** ${scenarios.length} · совпали: ${byVerdict('match')} · расходятся: ${byVerdict('ours-differs')}`
  + ` · утечка у нас: ${byVerdict('ours-leaks')}`
  + ` · отказ оракула на деплое: ${byVerdict('oracle-deploy-rejected')}`
  + ` · отказ у нас на деплое: ${byVerdict('ours-deploy-rejected')}`);
md.push('');
md.push('| # | Сценарий | Zeebe 8.5 live | ZorroBPM | Утечки (G-P) | Шаги (наши / Zeebe) | Вердикт |');
md.push('|---|---|---|---|---|---|---|');
scenarios.forEach((s, i) => {
  const z = s.zeebe.status === 'DEPLOY_REJECTED'
    ? `**ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ**`
    : `${s.zeebe.status} ${JSON.stringify(normalizeVars(s.zeebe.vars))}`;
  const o = s.ours.status === 'DEPLOY_REJECTED'
    ? `**ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** (${s.ours.error.split(':')[0]})`
    : `${s.ours.status} ${JSON.stringify(normalizeVars(s.ours.vars))}`;
  const leaks = s.ours.leaks.length > 0
    ? `**${s.ours.leaks.length} нарушение(й)**: ${s.ours.leaks.map((l) => l.replace(/^.*type=/, 'type=')).join('; ')}`
    : 'нет';
  const steps = `${JSON.stringify(s.ours.reachedSteps || [])} / ${JSON.stringify(s.zeebe.reachedSteps || [])}`;
  md.push(`| ${i + 1} | \`${s.id}\` | ${z} | ${o} | ${leaks} | ${steps} | **${s.verdict}** |`);
});
md.push('');
md.push('## Подробности расхождений');
md.push('');
const divergent = scenarios.filter((s) => s.verdict !== 'match');
if (divergent.length === 0) {
  md.push('Расхождений нет.');
} else {
  for (const s of divergent) {
    md.push(`### \`${s.id}\` — ${s.verdict}`);
    md.push('');
    md.push(`- **Zeebe 8.5:** статус ${s.zeebe.status}, переменные ${JSON.stringify(s.zeebe.vars)},`
      + ` остаток ${JSON.stringify(s.zeebe.leftovers)}`);
    md.push(`- **Мы:** статус ${s.ours.status}, переменные ${JSON.stringify(s.ours.vars)},`
      + ` нарушения инварианта ${JSON.stringify(s.ours.leaks)}, инциденты ${JSON.stringify(s.ours.incidents)}`);
    if (s.zeebe.error) md.push(`- **Ошибка оракула:** ${s.zeebe.error.split('\n')[0]}`);
    if (s.ours.error) md.push(`- **Ошибка у нас:** ${s.ours.error.split('\n')[0]}`);
    if (s.detail) md.push(`- **Вывод:** ${s.detail}`);
    md.push('');
  }
}
md.push('## Отказы оракула на деплое — отдельный вывод (не «ours-differs»)');
md.push('');
const rejected = scenarios.filter((s) => s.verdict === 'oracle-deploy-rejected');
if (rejected.length === 0) {
  md.push('Отказов оракула на деплое нет.');
} else {
  md.push('Zeebe 8.5 отверг модель на деплое — оракул её не исполнил, сравнивать нечего.');
  md.push('Это НЕ «ours-differs», а вывод о ПАРИТЕТЕ ПРИЁМА МОДЕЛИ (мы permissive, оракул — нет).');
  md.push('');
  for (const s of rejected) {
    md.push(`- \`${s.id}\`: ${s.zeebe.error.split('\n').filter((l) => l.trim()).slice(0, 4).join(' / ')}`);
  }
}
md.push('');

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'phase3-divergences.md'), md.join('\n'));
fs.writeFileSync(path.join(outDir, 'phase3-divergences.json'), JSON.stringify({
  oracle: 'zeebe-8.5.0-live',
  gateway: oracle.gateway || '',
  generatedAt: new Date().toISOString(),
  scenarios,
}, null, 2) + '\n');
console.log(`WROTE ${path.join(outDir, 'phase3-divergences.md')}`);
console.log(`WROTE ${path.join(outDir, 'phase3-divergences.json')}`);

for (const s of scenarios) {
  console.log(`${s.id}: ${s.verdict}`);
}
if (problems.length > 0) {
  console.error('PROBLEMS:');
  for (const p of problems) console.error(`  ${p}`);
  process.exit(1);
}
