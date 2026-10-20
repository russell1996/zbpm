#!/usr/bin/env node
/* WO-OPS-27 — сравнение нашего прогона с живым Zeebe 8.5.0.
 *
 * Входы:
 *   ours.json    — наблюдения нашего движка  (zorrobpm-engine/target/scenario-matrix/ours.json)
 *   zeebe.json   — наблюдения Zeebe 8.5.0    (…/zeebe.json)
 *
 * Выходы:
 *   docs/analysis/bpmn-scenario-matrix/divergences.md    — таблица расхождений (человеку)
 *   docs/analysis/bpmn-scenario-matrix/divergences.json  — то же машинно
 *   zorrobpm-engine/src/test/resources/scenario-matrix/zeebe-expectations.json
 *        — «прибитые» факты оракула: их CI сверяет с нашим прогоном БЕЗ живого Zeebe
 *
 * Нормализация: наш движок хранит значения переменных сырым текстом ("[\"a\"]"),
 * Zeebe отдаёт разобранный JSON. Поэтому обе стороны приводятся к общему виду:
 * значение, которое разбирается как JSON, разбирается; иначе — строка.
 *
 * Использование:
 *   node scenario-matrix-diff.js [--ours <path>] [--zeebe <path>]
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

function normalizeVars(vars) {
  const out = {};
  for (const k of Object.keys(vars || {}).sort()) out[k] = normalize(vars[k]);
  return out;
}

function same(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
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

const args = process.argv.slice(2);
const oursPath = argOf(args, '--ours', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'ours.json'));
const zeebePath = argOf(args, '--zeebe', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe.json'));
const outDir = path.join(ROOT, 'docs', 'analysis', 'bpmn-scenario-matrix');
const expectPath = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'scenario-matrix', 'zeebe-expectations.json');

for (const p of [oursPath, zeebePath]) {
  if (!fs.existsSync(p)) {
    console.error(`missing input: ${p}`);
    process.exit(2);
  }
}

const ours = JSON.parse(fs.readFileSync(oursPath, 'utf8')).outcomes || [];
const zeebe = JSON.parse(fs.readFileSync(zeebePath, 'utf8')).outcomes || [];
const zeebeById = new Map(zeebe.map((z) => [z.id, z]));

const rows = [];
const expectations = {};
let match = 0;
let oracleUnsupported = 0;
const divergences = [];

for (const o of ours) {
  const z = zeebeById.get(o.id);
  const ourVars = normalizeVars(o.rootVars);
  const entry = {
    id: o.id,
    container: o.container,
    content: o.content,
    cardinality: o.cardinality,
    order: o.order,
    ours: { status: o.status, vars: ourVars, leaks: o.leaks || [], incidents: o.incidents || [] },
    zeebe: z ? { status: z.status, vars: normalizeVars(z.rootVars), error: z.error || '' } : null,
    verdict: '',
    detail: '',
  };

  if (!z) {
    entry.verdict = 'no-oracle-run';
    entry.detail = 'сценарий не прогонялся против оракула';
  } else if (z.status !== 'COMPLETED') {
    // Оракул 8.5 не исполнил сценарий — сравнивать нечего. Это НЕ дефект нашего
    // движка: факт поведения 8.5 зафиксирован (docs!=8.5 по G-Q).
    entry.verdict = 'oracle-unsupported';
    entry.detail = `Zeebe 8.5.0 не исполнил сценарий (${z.status}${z.error ? ': ' + z.error : ''})`;
    oracleUnsupported++;
    expectations[o.id] = { oracle: 'UNSUPPORTED-8.5', status: z.status, vars: null, note: entry.detail };
  } else {
    const d = diffKeys(ourVars, normalizeVars(z.rootVars));
    if (d.length === 0 && o.status === 'COMPLETED' && (o.leaks || []).length === 0) {
      entry.verdict = 'match';
      match++;
      expectations[o.id] = { oracle: 'SUPPORTED-8.5', status: z.status, vars: normalizeVars(z.rootVars) };
    } else {
      entry.verdict = 'ours-differs';
      entry.detail = d.length ? d.join('; ') : `ours status=${o.status} leaks=${JSON.stringify(o.leaks || [])}`;
      divergences.push(entry);
      expectations[o.id] = { oracle: 'SUPPORTED-8.5', status: z.status, vars: normalizeVars(z.rootVars), knownDivergence: entry.detail };
    }
  }
  rows.push(entry);
}

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(path.dirname(expectPath), { recursive: true });

const md = [];
md.push('# WO-OPS-27 — матрица сценариев: наш движок vs Zeebe 8.5.0 (живой оракул)');
md.push('');
md.push(`- Оракул: живой Zeebe 8.5.0 (gateway ${JSON.parse(fs.readFileSync(zeebePath, 'utf8')).gateway || '8.5.0'}), стенд \`zbpm-cmp-zeebe\`.`);
md.push(`- Наш движок: тот же набор схем (манифест \`zorrobpm-engine/target/scenario-matrix/manifest.json\`).`);
md.push(`- Сценариев: ${rows.length}; совпали: ${match}; оракул не исполнил (docs≠8.5): ${oracleUnsupported}; расхождений: ${divergences.length}.`);
md.push('');
md.push('| # | Сценарий | Zeebe 8.5 live | ours | Вердикт |');
md.push('|---|---|---|---|---|');
rows.forEach((r, i) => {
  const z = r.zeebe ? `${r.zeebe.status} ${JSON.stringify(r.zeebe.vars)}` : '—';
  const ours = `${r.ours.status} ${JSON.stringify(r.ours.vars)}`;
  md.push(`| ${i + 1} | \`${r.id}\` | ${z} | ${ours} | ${r.verdict}${r.detail ? ' — ' + r.detail : ''} |`);
});
md.push('');
if (divergences.length === 0) {
  md.push('## Расхождений нет');
  md.push('');
  md.push('Ни по одному сценарию среза наблюдаемое состояние (переменные корневого скоупа)');
  md.push('и терминальный статус не разошлись с живым Zeebe 8.5.0.');
} else {
  md.push('## Расхождения');
  md.push('');
  for (const d of divergences) {
    md.push(`### \`${d.id}\``);
    md.push('');
    md.push(`- Zeebe 8.5: \`${JSON.stringify(d.zeebe.vars)}\` (status ${d.zeebe.status})`);
    md.push(`- ours: \`${JSON.stringify(d.ours.vars)}\` (status ${d.ours.status})`);
    md.push(`- расхождение: ${d.detail}`);
    md.push('');
  }
}
md.push('');
md.push('## Как воспроизвести');
md.push('');
md.push('```bash');
md.push('# 1) наш движок (генерирует манифест и наши наблюдения)');
md.push('mvn -pl zorrobpm-engine verify -Dit.test=ScenarioMatrixIntegrationTests');
md.push('# 2) живой Zeebe 8.5.0');
md.push('cd docs/analysis/c8-oracle && node scenario-matrix.js');
md.push('# 3) сравнение + прибитые факты оракула');
md.push('cd docs/analysis/c8-oracle && node scenario-matrix-diff.js');
md.push('```');

fs.writeFileSync(path.join(outDir, 'divergences.md'), md.join('\n') + '\n');
fs.writeFileSync(path.join(outDir, 'divergences.json'), JSON.stringify({
  generatedAt: new Date().toISOString(),
  oracle: 'zeebe-8.5.0-live',
  total: rows.length,
  match,
  oracleUnsupported,
  divergences: divergences.length,
  rows,
}, null, 2) + '\n');
fs.writeFileSync(expectPath, JSON.stringify({
  oracle: 'zeebe-8.5.0-live',
  note: 'Факты живого Zeebe 8.5.0, снятые прогоном docs/analysis/c8-oracle/scenario-matrix.js. '
    + 'Сверяются нашим прогоном в ScenarioMatrixIntegrationTests — БЕЗ живого Zeebe в CI.',
  scenarios: expectations,
}, null, 2) + '\n');

console.log(`scenarios=${rows.length} match=${match} oracleUnsupported=${oracleUnsupported} divergences=${divergences.length}`);
for (const d of divergences) console.log(`DIVERGENCE ${d.id}: ${d.detail}`);
console.log(`WROTE ${path.join(outDir, 'divergences.md')}`);
console.log(`WROTE ${expectPath}`);
