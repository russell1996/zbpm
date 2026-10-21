#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 1 — сборка матрицы «элемент × сценарий × Zeebe 8.5 × ZorroBPM × утечки».
 *
 * Читает ДВА измерения — одно и то же поведение, снятое двумя сторонами:
 *   Zeebe 8.5.0 : docs/analysis/c8-oracle/scenarios/ops28-phase1/result-8.5.json
 *   ours        : zorrobpm-engine/target/ops28-phase1-observed.json
 * и печатает markdown-таблицу (колонки: элемент, сценарий, Zeebe, ours, утечки, вердикт).
 *
 * Никаких «ожиданий от себя»: вердикт выводится ИЗ СРАВНЕНИЯ двух измерений.
 *
 * Запуск: node docs/analysis/c8-oracle/ops28-phase1-compare.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const ZEEBE = path.join(__dirname, 'scenarios', 'ops28-phase1', 'result-8.5.json');
const OURS = path.join(ROOT, 'zorrobpm-engine', 'target', 'ops28-phase1-observed.json');
const CORPUS = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'ops28-phase1-cases.json');

/** Наш тип → значение в том же JSON-виде, что у Zeebe. */
function oursValue(entry) {
  if (!entry || typeof entry !== 'object') return entry;
  const type = String(entry.type);
  const raw = entry.value;
  switch (type) {
    case 'LONG': return Number(raw);
    case 'DOUBLE': return Number(raw);
    case 'BOOLEAN': return raw === 'true';
    case 'JSON': try { return JSON.parse(raw); } catch (e) { return raw; }
    default: return raw;
  }
}

/** Наша карта переменных → канонический вид «name=value» (как у Zeebe). */
function oursCanon(map) {
  if (!map) return null;
  return Object.keys(map).sort().map((k) => `${k}=${JSON.stringify(oursValue(map[k]))}`).join(', ') || '(empty)';
}

/** Zeebe: payload задания = плоская карта name→json. */
function zeebeCanon(map) {
  if (!map) return null;
  return Object.keys(map).sort().map((k) => `${k}=${JSON.stringify(map[k])}`).join(', ') || '(empty)';
}

function outcomeOf(side) {
  if (!side) return '—';
  if (side.outcome === 'completed') return 'completed';
  if (side.outcome === 'deployError') return 'deployError';
  if (side.outcome === 'stuck') return 'stuck';
  return side.outcome || '—';
}

function shortError(text) {
  if (!text) return '';
  const first = String(text).split('\n')[0];
  return first.length > 120 ? first.slice(0, 117) + '…' : first;
}

function leaksOf(ours) {
  if (!ours) return '—';
  const parts = [];
  if (ours.leaksChecked === false) {
    parts.push('G-P: н/п (инстанс не создан)');
  } else if (ours.leaks && ours.leaks.length) {
    parts.push(`G-P: ${ours.leaks.length} НАРУШЕНИЙ`);
  } else {
    parts.push('G-P: 0');
  }
  const inc = (ours.incidents || []).length;
  parts.push(`инцидентов: ${inc}`);
  return parts.join(' · ');
}

function verdictOf(zeebe, ours) {
  const z = outcomeOf(zeebe);
  const o = outcomeOf(ours);
  if (z === 'completed' && o === 'completed') {
    const zc = zeebeCanon(zeebe.dump && zeebe.dump.variables);
    const oc = oursCanon(ours['dump.root']);
    if (zc === oc) return 'match';
    return 'ours differs';
  }
  if (z === 'stuck' && o === 'stuck') return 'match (парковка)';
  if (z === 'deployError' && o === 'deployError') return 'match (оба отвергают форму)';
  if (z === 'deployError') return 'ours differs (Zeebe отвергает на деплое)';
  if (o === 'deployError') return 'ours differs (ours отвергает на деплое)';
  return 'ours differs';
}

const corpus = JSON.parse(fs.readFileSync(CORPUS, 'utf8'));
const zeebe = JSON.parse(fs.readFileSync(ZEEBE, 'utf8'));
const ours = JSON.parse(fs.readFileSync(OURS, 'utf8'));

const rows = [];
for (const c of corpus.cases) {
  const z = zeebe[c.id];
  const o = ours[c.id];
  const zOut = outcomeOf(z);
  const oOut = outcomeOf(o);
  let zCell;
  if (zOut === 'completed') {
    zCell = `completed · ${zeebeCanon(z.dump && z.dump.variables)}`;
  } else if (zOut === 'deployError' || zOut === 'startError') {
    zCell = `${zOut} · ${shortError(z.error)}`;
  } else {
    zCell = `${zOut}`;
  }
  let oCell;
  if (oOut === 'completed') {
    oCell = `completed · ${oursCanon(o['dump.root'])}`;
  } else if (oOut === 'deployOrStartError') {
    oCell = `ошибка деплоя/старта · ${shortError(o.error)}`;
  } else {
    oCell = `${oOut}${(o.incidents || []).length ? ' · инцидент: ' + shortError(o.incidents[0].message) : ''}`;
  }
  rows.push({
    id: c.id,
    element: c.element,
    title: c.title,
    docs: c.docs,
    zCell,
    oCell,
    leaks: leaksOf(o),
    verdict: verdictOf(z, o),
    workZ: z && z.work ? zeebeCanon(z.work.variables) : null,
    workO: o && o.work ? oursCanon(o.work) : null,
    headers: (z && z.work && z.work.customHeaders) || (o && o['work.outbox'] && o['work.outbox'].taskHeaders) || null,
    headersZ: z && z.work ? (z.work.customHeaders || '(нет)') : null,
    headersO: o && o['work.outbox'] ? JSON.stringify(o['work.outbox'].taskHeaders) : null,
    zOutcome: zOut,
    oOutcome: oOut,
  });
}

console.log('| # | Элемент | Сценарий | Zeebe 8.5 (живой) | ZorroBPM (наш) | Утечки/инциденты | Вердикт |');
console.log('|---|---|---|---|---|---|---|');
rows.forEach((r, i) => {
  console.log(`| ${i + 1} | \`${r.element}\` | \`${r.id}\` | ${r.zCell} | ${r.oCell} | ${r.leaks} | **${r.verdict}** |`);
});

console.log('\n\n### Payload элемента под тестом (work)');
console.log('| Сценарий | Zeebe 8.5 (payload задания) | ours (переменные, видимые элементу) |');
console.log('|---|---|---|');
for (const r of rows) {
  if (r.workZ === null && r.workO === null) continue;
  console.log(`| \`${r.id}\` | ${r.workZ === null ? '—' : r.workZ} | ${r.workO === null ? '—' : r.workO} |`);
}

console.log('\n\n### Тело задания (outbox, как его видит воркер) — заголовки и приоритет');
console.log('| Сценарий | Zeebe 8.5 (customHeaders) | ours (taskHeaders из outbox) |');
console.log('|---|---|---|');
for (const r of rows) {
  if (!r.headers) continue;
  console.log(`| \`${r.id}\` | ${r.headersZ === null ? '—' : r.headersZ} | ${r.headersO === null ? '—' : r.headersO} |`);
}

// ---- H2 vs PostgreSQL: тот же закоммиченный инструмент, то же сравнение ----
const PG = path.join(ROOT, 'zorrobpm-engine', 'target', 'ops28-phase1-observed-pg.json');
if (fs.existsSync(PG)) {
  const pg = JSON.parse(fs.readFileSync(PG, 'utf8'));
  const canonOurs = (map) => {
    if (!map) return null;
    return Object.keys(map).sort()
      .map((k) => `${k}=${JSON.stringify(oursValue(map[k]))}`).join(', ') || '(empty)';
  };
  let diffs = 0;
  const lines = [];
  for (const c of corpus.cases) {
    const a = ours[c.id] || {};
    const b = pg[c.id] || {};
    const same = a.outcome === b.outcome
      && canonOurs(a['dump.root']) === canonOurs(b['dump.root'])
      && JSON.stringify(a.leaks || []) === JSON.stringify(b.leaks || [])
      && JSON.stringify((a['work.outbox'] || {}).taskHeaders)
         === JSON.stringify((b['work.outbox'] || {}).taskHeaders);
    if (!same) {
      diffs++;
      lines.push(`  ${c.id}: outcome ${a.outcome}/${b.outcome}, root ${canonOurs(a['dump.root'])} / ${canonOurs(b['dump.root'])}`);
    }
  }
  console.log('\n\n### H2 vs PostgreSQL 16 (тот же корпус, тот же инструмент)');
  console.log('```');
  console.log(`cases compared: ${corpus.cases.length} | differences: ${diffs}`);
  console.log(`PG leaks total: ${Object.values(pg).reduce((s, v) => s + ((v.leaks || []).length), 0)}`);
  console.log(`H2 leaks total: ${Object.values(ours).reduce((s, v) => s + ((v.leaks || []).length), 0)}`);
  if (lines.length) {
    console.log('differences:');
    lines.forEach((l) => console.log(l));
  }
  console.log('```');
}

console.log('\n\n### JSON');
console.log(JSON.stringify(rows.map((r) => ({
  id: r.id, element: r.element, verdict: r.verdict, z: r.zOutcome, o: r.oOutcome,
})), null, 1));
