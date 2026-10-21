#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 5 («сквозные») — сравнение «наш прогон ↔ живой Zeebe 8.5.0» + PG ↔ H2.
 *
 * Входы:
 *   zorrobpm-engine/target/scenario-matrix/zeebe-cross.json   — живой Zeebe 8.5.0
 *   zorrobpm-engine/target/scenario-matrix/ours-cross-h2.json   — наш движок на H2
 *   zorrobpm-engine/target/scenario-matrix/ours-cross-pg.json   — наш движок на PostgreSQL 16
 *
 * Выходы:
 *   docs/analysis/bpmn-scenario-matrix/phase5-divergences.md    — таблица (человекочитаемая)
 *   docs/analysis/bpmn-scenario-matrix/phase5-divergences.json  — та же таблица машинно
 *   zorrobpm-engine/src/test/resources/scenario-matrix/cross-expectations.json
 *     — прибитые факты: в секцию zeebe кладётся ЖИВОЙ факт оракула, в ours/pg —
 *       наши факты; рядом — вердикт по каждому сценарию.
 *
 * ПРАВИЛО: скрипт НЕ переприбивает нашу часть молча — он только ДОПИСЫВАЕТ в
 * файл прибитых фактов секцию zeebe (факт оракула живьём) и вердикт. Наша часть
 * (ours/pg) — прибита тестами; если она разошлась с оракулом, это расхождение
 * видно в вердикте, а не «съедено» переприбитием.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname);
const ART = path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'target', 'scenario-matrix');
const OUT_DIR = path.join(ROOT, '..', 'bpmn-scenario-matrix');
const PINNED = path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'src', 'test', 'resources',
  'scenario-matrix', 'cross-expectations.json');

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function normVars(vars) {
  const out = {};
  for (const [k, v] of Object.entries(vars || {})) {
    out[k] = v === null || v === undefined ? null : String(v);
  }
  return out;
}

function oursById(file) {
  const rows = readJson(file).outcomes || [];
  const map = {};
  for (const row of rows) map[row.scenarioId] = row;
  return map;
}

function zeebeById(file) {
  const rows = readJson(file).outcomes || [];
  const map = {};
  for (const row of rows) map[row.id] = row;
  return map;
}

function varsEqual(a, b) {
  const na = normVars(a);
  const nb = normVars(b);
  const keys = new Set([...Object.keys(na), ...Object.keys(nb)]);
  for (const k of keys) {
    if (String(na[k]) !== String(nb[k])) return false;
  }
  return true;
}

const zeebe = zeebeById(path.join(ART, 'zeebe-cross.json'));
const h2 = oursById(path.join(ART, 'ours-cross-h2.json'));
const pg = oursById(path.join(ART, 'ours-cross-pg.json'));

const ids = [...new Set([...Object.keys(zeebe), ...Object.keys(h2), ...Object.keys(pg)])].sort();
const rows = [];
let matches = 0;
let divergences = 0;

for (const id of ids) {
  const z = zeebe[id];
  const o = h2[id];
  const p = pg[id];
  const problems = [];

  if (!z) problems.push('нет факта оракула (Zeebe 8.5 не отработал сценарий)');
  if (!o) problems.push('нет нашего факта на H2');
  if (!p) problems.push('нет нашего факта на PostgreSQL');

  if (z && o) {
    const zStatus = z.status === 'COMPLETED' ? 'COMPLETED' : z.status;
    if (zStatus !== o.status) {
      problems.push(`статус: Zeebe=${z.status} ours=${o.status}`);
    }
    if (!varsEqual(z.rootVars, o.vars)) {
      problems.push(`переменные: Zeebe=${JSON.stringify(normVars(z.rootVars))} ours=${JSON.stringify(normVars(o.vars))}`);
    }
    const zeebeLeftovers = (z.leftovers || []).length;
    const oursLeaks = (o.leaks || []).length;
    if (zeebeLeftovers !== oursLeaks) {
      problems.push(`осталось активным: Zeebe=${zeebeLeftovers} ours(утечки)=${oursLeaks}`);
    }
    // Ось «миграция версии»: ЧЕЙ job' появился после деплоя второй версии.
    if (z.continuedWith && o.extra && o.extra.continuedWith
        && z.continuedWith !== o.extra.continuedWith) {
      problems.push(`продолжение после деплоя новой версии: Zeebe=${z.continuedWith} ours=${o.extra.continuedWith}`);
    }
  }

  if (o && p) {
    if (o.status !== p.status) {
      problems.push(`PG vs H2: статус H2=${o.status} PG=${p.status}`);
    }
    if (!varsEqual(o.vars, p.vars)) {
      problems.push(`PG vs H2: переменные H2=${JSON.stringify(normVars(o.vars))} PG=${JSON.stringify(normVars(p.vars))}`);
    }
    const oExtra = JSON.stringify(o.extra || {});
    const pExtra = JSON.stringify(p.extra || {});
    if (oExtra !== pExtra) {
      problems.push(`PG vs H2: axis-факты разошлись H2=${oExtra} PG=${pExtra}`);
    }
  }

  const verdict = problems.length === 0 ? 'match' : 'ours-differs';
  if (verdict === 'match') matches++; else divergences++;
  rows.push({
    id,
    axis: (o && o.axis) || (z && z.content) || '',
    zeebe: z ? {
      status: z.status,
      vars: normVars(z.rootVars),
      leftovers: (z.leftovers || []).length,
      continuedWith: z.continuedWith || '',
      deployedVersions: z.deployedVersions || '',
    } : null,
    ours: o ? {
      status: o.status,
      vars: normVars(o.vars),
      leaks: (o.leaks || []).length,
      incidents: (o.incidents || []).length,
      extra: o.extra || {},
    } : null,
    pg: p ? {
      status: p.status,
      vars: normVars(p.vars),
      leaks: (p.leaks || []).length,
      incidents: (p.incidents || []).length,
      extra: p.extra || {},
    } : null,
    problems,
    verdict,
  });
}

const md = [];
md.push('# WO-OPS-28 ФАЗА 5 («сквозные») — наш движок vs живой Zeebe 8.5.0');
md.push('');
md.push('- Оракул: живой Zeebe 8.5.0 (gateway 8.5.0), прогон `docs/analysis/c8-oracle/scenario-matrix-cross.js`.');
md.push('- Наш движок: те же схемы и тот же список действий (манифест `zorrobpm-engine/target/scenario-matrix/manifest-cross.json`).');
md.push(`- Сценариев: ${rows.length}; совпали: ${matches}; расхождений: ${divergences}.`);
md.push('- Наблюдение: переменные корневого скоупа в «наблюдательной» задаче + «что осталось активным» (у нас — нарушения инварианта утечек G-P, у оракула — оставшиеся активируемыми job\'ы).');
md.push('- PG vs H2: те же сценарии на PostgreSQL 16 — сверяются с той же прибитой частью, расхождение было бы отдельной строкой.');
md.push('');
md.push('| # | Сценарий | Ось | Zeebe 8.5 live | ours (H2) | ours (PG) | Вердикт |');
md.push('|---|---|---|---|---|---|---|');
rows.forEach((r, i) => {
  const z = r.zeebe ? `${r.zeebe.status} ${JSON.stringify(r.zeebe.vars)} / leftovers=${r.zeebe.leftovers}${r.zeebe.continuedWith ? ' / продолжение=' + r.zeebe.continuedWith : ''}${r.zeebe.deployedVersions ? ' / версии=' + r.zeebe.deployedVersions : ''}` : '—';
  const o = r.ours ? `${r.ours.status} ${JSON.stringify(r.ours.vars)} / leaks=${r.ours.leaks} / incidents=${r.ours.incidents}` : '—';
  const p = r.pg ? `${r.pg.status} ${JSON.stringify(r.pg.vars)} / leaks=${r.pg.leaks} / incidents=${r.pg.incidents}` : '—';
  md.push(`| ${i + 1} | \`${r.id}\` | ${r.axis} | ${z} | ${o} | ${p} | ${r.verdict}${r.problems.length ? ' — ' + r.problems.join('; ') : ''} |`);
});
md.push('');
if (divergences === 0) {
  md.push('## Расхождений нет');
  md.push('');
  md.push('Ни по одному сценарию среза «сквозные» наблюдаемое состояние, терминальный статус');
  md.push('и остаток не разошлись ни с живым Zeebe 8.5.0, ни между H2 и PostgreSQL 16.');
} else {
  md.push('## Расхождения');
  md.push('');
  for (const r of rows.filter((x) => x.verdict !== 'match')) {
    md.push(`- \`${r.id}\` (${r.axis}): ${r.problems.join('; ')}`);
  }
}
md.push('');
md.push('## Что означает каждый сценарий на оракуле');
md.push('');
md.push('- `f5-restart-midprocess` — у Zeebe нет понятия «рестарт движка»: состояние живёт в партиции брокера,');
md.push('  и перезапуск брокера его не теряет. Поэтому сверяется ИТОГ прогона (процесс дошёл до конца с теми же');
md.push('  переменными), а не механизм: «durable state» у нас обеспечивает БД, у Zeebe — репликация партиции.');
md.push('- `f5-parallel-race` — все job\'ы ветвей форка завершаются ОДНИМ ЗАЛПОМ (`Promise.all`), то есть');
md.push('  действительно параллельно с точки зрения клиента; у нас те же четыре завершения делают четыре');
md.push('  РЕАЛЬНЫХ потока с барьером. Сверяется, что джойн дал ровно один следующий job.');
md.push('- `f5-version-v1` — старт на v1 → деплой v2 ТОГО ЖЕ ключа → завершение задачи. ЖИВОЙ факт Zeebe 8.5.0:');
md.push('  инстанс продолжился СВОЕЙ версией (`stV1`), а не новой (`stV2`) — версия фиксируется на старте.');
md.push('  Это измерение живьём, а не цитата из документации. `f5-version-v2` — контроль: инстанс, стартовавший');
md.push('  на v2, продолжается на v2.');

fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, 'phase5-divergences.md'), md.join('\n') + '\n');
fs.writeFileSync(path.join(OUT_DIR, 'phase5-divergences.json'),
  JSON.stringify({ engine: 'zorro', scenarios: rows }, null, 2) + '\n');
console.log(`WROTE ${path.join(OUT_DIR, 'phase5-divergences.md')}`);
console.log(`WROTE ${path.join(OUT_DIR, 'phase5-divergences.json')}`);

// ── Прибитые факты: дописываем секцию zeebe (живой факт) и вердикт ──────────
const pinned = readJson(PINNED);
for (const r of rows) {
  const fact = pinned.scenarios[r.id];
  if (!fact) continue;
  if (r.zeebe) {
    fact.zeebe = {
      status: r.zeebe.status,
      vars: r.zeebe.vars,
      leftovers: r.zeebe.leftovers,
      continuedWith: r.zeebe.continuedWith,
      deployedVersions: r.zeebe.deployedVersions,
    };
  }
  fact.verdict = r.verdict;
  if (r.problems.length) {
    fact.problems = r.problems;
  } else {
    delete fact.problems;
  }
}
fs.writeFileSync(PINNED, JSON.stringify(pinned, null, 2) + '\n');
console.log(`WROTE ${PINNED}`);
console.log(`scenarios=${rows.length} match=${matches} divergences=${divergences}`);
for (const r of rows.filter((x) => x.verdict !== 'match')) {
  console.log(`DIVERGENCE ${r.id}: ${r.problems.join('; ')}`);
}
