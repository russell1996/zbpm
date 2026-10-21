#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 2 («потоки») — сравнение нашего прогона с живым Zeebe 8.5.0.
 *
 * Входы:
 *   ours-flows/*.json   — наблюдения нашего движка (zorrobpm-engine/target/scenario-matrix/ours-flows/)
 *   zeebe-flows.json    — наблюдения Zeebe 8.5.0 (docs/analysis/c8-oracle/scenario-matrix.js)
 *
 * Выходы:
 *   docs/analysis/bpmn-scenario-matrix/phase2-divergences.md     — таблица (человеку)
 *   docs/analysis/bpmn-scenario-matrix/phase2-divergences.json   — то же машинно
 *   zorrobpm-engine/src/test/resources/scenario-matrix/flow-expectations.json
 *        — «прибитые» факты (наши + живого Zeebe): их CI сверяет БЕЗ живого Zeebe
 *
 * Смысл «прибитых» фактов: расхождение НЕ чинится в этой кампании (баги — отдельными
 * задачами), но оно не должно исчезнуть молча. Поэтому в эталоне хранятся ОБЕ
 * стороны (что сделал Zeebe 8.5 и что сделали мы) и вычисленный вердикт; наш
 * прогон в CI сверяется с прибитой НАШЕЙ стороной — любое изменение поведения
 * движка роняет сборку и заставляет переснять эталон (то есть пересмотреть
 * расхождение, а не закрыть его случайно).
 *
 * Использование:
 *   node scenario-matrix-flow-diff.js [--ours <dir>] [--zeebe <path>]
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
 * Нарушения инварианта утечек — без id инстанса: он случайный (новый инстанс
 * на каждый прогон), в прибитом факте ему не место.
 */
function normalizeLeaks(leaks) {
  return (leaks || []).map((l) => String(l).replace(/instance=[0-9a-f-]{36}\s*/, ''));
}

function normalizeVars(vars) {
  const out = {};
  for (const k of Object.keys(vars || {}).sort()) out[k] = normalize(vars[k]);
  return out;
}

/**
 * Исходы публикаций в общем виде: имя сообщения → «correlated» | «rejected».
 *
 * Наша сторона: список «msgA:correlated=1» (сколько подписок разбудила
 * публикация; 0 = никто не слушал). Сторона оракула: «msgA:correlated» /
 * «msgB:rejected» (публикация отвергнута — подписчика нет).
 */
function normalizePublishes(oursList, zeebeList) {
  const out = {};
  for (const entry of oursList || []) {
    const m = String(entry).match(/^(.*):correlated=(\d+)$/);
    if (m) out[m[1]] = Number(m[2]) > 0 ? 'correlated' : 'rejected';
  }
  for (const entry of zeebeList || []) {
    const m = String(entry).match(/^(.*):(correlated|rejected)$/);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

/**
 * Сравнение значений — ПО СТРОКЕ, как в диффе среза F1 (scenario-matrix-diff.js):
 * наш движок хранит значение сырым текстом ("true"), Zeebe отдаёт разобранный
 * JSON (true). Это ОДНО И ТО ЖЕ значение, просто разная форма хранения:
 * String.valueOf(true) == String.valueOf("true") == "true".
 */
function same(a, b) {
  return String(a) === String(b);
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
const oursDir = argOf(args, '--ours', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'ours-flows'));
const zeebePath = argOf(args, '--zeebe', path.join(ROOT, 'zorrobpm-engine', 'target', 'scenario-matrix', 'zeebe-flows.json'));
const outDir = path.join(ROOT, 'docs', 'analysis', 'bpmn-scenario-matrix');
const expectPath = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'scenario-matrix', 'flow-expectations.json');

if (!fs.existsSync(oursDir)) {
  console.error(`missing ours dir: ${oursDir} — run the engine flow slice first`);
  process.exit(2);
}
if (!fs.existsSync(zeebePath)) {
  console.error(`missing oracle output: ${zeebePath} — run scenario-matrix.js against the live Zeebe first`);
  process.exit(2);
}

// Наши наблюдения: по файлам семейств.
const ours = [];
for (const file of fs.readdirSync(oursDir).sort()) {
  if (!file.endsWith('.json')) continue;
  const parsed = JSON.parse(fs.readFileSync(path.join(oursDir, file), 'utf8'));
  for (const o of parsed.outcomes || []) ours.push(o);
}
const zeebeDoc = JSON.parse(fs.readFileSync(zeebePath, 'utf8'));
const zeebe = zeebeDoc.outcomes || [];
const zeebeById = new Map(zeebe.map((z) => [z.id, z]));

/**
 * Ожидаемый исход сценария по его семейству.
 *
 * Для части сценариев «не дойти до наблюдательной задачи» — это НЕ дефект, а
 * замысел сценария (инцидент на шлюзе без default / join, ждущий вторую ветвь).
 * Такие сценарии сравниваются по САМОМУ факту «не дошёл», а не как расхождение.
 */
const EXPECTED_NOT_REACHED = new Set([
  'f2-exclusive-no-match',
  'f2-parallel-join-incomplete',
]);

const rows = [];
const expectations = {};
let match = 0;
const divergences = [];
// Отказы оракула на деплое — ОТДЕЛЬНЫЙ вывод (не «ours-differs»): версия
// оракула отвергает модель, мы её принимаем и исполняем. Это вопрос паритета
// к решению владельца, а не расхождение рантайма (WO-OPS-28 ФАЗА 2, раунд 2).
const deployRejected = [];

for (const o of ours) {
  const z = zeebeById.get(o.id);
  const ourVars = normalizeVars(o.rootVars);
  const entry = {
    id: o.id,
    family: o.content,
    ours: {
      status: o.status, vars: ourVars, leaks: normalizeLeaks(o.leaks || []), incidents: o.incidents || [],
      error: o.error || '', publishes: o.publishes || [],
    },
    zeebe: z ? {
      status: z.status, vars: normalizeVars(z.rootVars), leftovers: z.leftovers || [],
      rejectedPublishes: z.rejectedPublishes || [], publishes: z.publishes || [],
      error: z.error || '',
    } : null,
    verdict: '',
    detail: '',
  };

  if (!z) {
    entry.verdict = 'no-oracle-run';
    entry.detail = 'сценарий не прогонялся против оракула';
    divergences.push(entry);
    continue;
  }

  // Оракул НЕ ПРИНЯЛ МОДЕЛЬ (deploy rejected) — это ограничение версии Zeebe
  // 8.5, а не расхождение рантайма: отдельный вердикт, отдельная строка отчёта.
  const rejectedByOracle = !z || (z.status === 'FAILED'
    && /rejected with code 'INVALID_ARGUMENT'/.test(z.error || ''));
  if (rejectedByOracle) {
    entry.verdict = 'oracle-deploy-rejected';
    entry.detail = `Zeebe 8.5 отверг модель на деплое: ${(z.error || '').split('\n').slice(0, 4).join(' ')}`;
    rows.push(entry);
    deployRejected.push(entry);
    expectations[o.id] = {
      oracle: 'DEPLOY-REJECTED-BY-8.5',
      zeebe: { status: z.status, vars: {}, leftovers: [], publishes: [] },
      ours: {
        status: o.status, vars: ourVars, leaks: normalizeLeaks(o.leaks || []), incidents: o.incidents || [],
        error: o.error || '', publishes: o.publishes || [],
      },
      verdict: entry.verdict,
      detail: entry.detail,
    };
    continue;
  }

  const oursReached = o.status === 'COMPLETED';
  const zeebeReached = z.status === 'COMPLETED';
  const expectedNotReached = EXPECTED_NOT_REACHED.has(o.id);

  if (!oursReached && !zeebeReached) {
    // Оба не дошли до наблюдательной задачи.
    if (expectedNotReached) {
      entry.verdict = 'match';
      entry.detail = 'оба движка не дошли до точки наблюдения — ожидаемо для этого сценария';
      match++;
    } else {
      entry.verdict = 'ours-differs';
      entry.detail = `оба не дошли, но сценарий не задуман как «не дойти»: ours=${o.status}/${o.error} zeebe=${z.status}/${z.error}`;
      divergences.push(entry);
    }
  } else if (oursReached !== zeebeReached) {
    entry.verdict = 'ours-differs';
    entry.detail = `дошли до точки наблюдения по-разному: ours=${o.status} zeebe=${z.status}`
      + (o.error ? ` ours-error=${o.error}` : '') + (z.error ? ` zeebe-error=${z.error}` : '');
    divergences.push(entry);
  } else {
    // Оба дошли: сравниваем переменные и «что осталось активным».
    const d = diffKeys(ourVars, normalizeVars(z.rootVars));
    const oursLeftovers = (o.leaks || []).length;
    const zeebeLeftovers = (z.leftovers || []).length;
    const leftoverDiff = (oursLeftovers === 0) !== (zeebeLeftovers === 0);
    const ourPub = normalizePublishes(o.publishes || [], []);
    const zeebePub = normalizePublishes([], z.publishes || []);
    const pubDiff = diffKeys(ourPub, zeebePub);
    if (d.length === 0 && !leftoverDiff && pubDiff.length === 0) {
      entry.verdict = 'match';
      match++;
    } else {
      entry.verdict = 'ours-differs';
      const parts = [];
      if (d.length) parts.push(d.join('; '));
      if (leftoverDiff) parts.push(`leftovers: ours=${oursLeftovers} zeebe=${zeebeLeftovers}`);
      if (pubDiff.length) parts.push(`публикации: ${pubDiff.join('; ')}`);
      entry.detail = parts.join('; ');
      divergences.push(entry);
    }
  }

  // Отдельно фиксируем ИНЦИДЕНТЫ нашей стороны (у оракула их в этой модели нет —
  // инциденты Zeebe не видны через job-API), чтобы находка не потерялась.
  if ((o.incidents || []).length > 0) {
    entry.oursIncident = o.incidents;
  }

  rows.push(entry);
  expectations[o.id] = {
    oracle: z.status === 'COMPLETED' ? 'SUPPORTED-8.5' : 'DID-NOT-REACH-OBSERVATION',
    zeebe: {
      status: z.status,
      vars: normalizeVars(z.rootVars),
      leftovers: z.leftovers || [],
      rejectedPublishes: z.rejectedPublishes || [],
      publishes: z.publishes || [],
    },
    ours: {
      status: o.status,
      vars: ourVars,
      leaks: normalizeLeaks(o.leaks || []),
      incidents: o.incidents || [],
      error: o.error || '',
      publishes: o.publishes || [],
    },
    verdict: entry.verdict,
    detail: entry.detail,
  };
}

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(path.dirname(expectPath), { recursive: true });

const md = [];
md.push('# WO-OPS-28 ФАЗА 2 («потоки») — наш движок vs живой Zeebe 8.5.0');
md.push('');
md.push(`- Оракул: живой Zeebe 8.5.0 (gateway ${zeebeDoc.gateway || '8.5.0'}), контейнер \`${process.env.C8_ORACLE_CONTAINER || 'zbpm-o28c-zeebe'}\`, прогон \`docs/analysis/c8-oracle/scenario-matrix.js\`.`);
md.push('- Наш движок: те же схемы и тот же список действий (манифест `zorrobpm-engine/target/scenario-matrix/manifest-flows.json`).');
md.push(`- Сценариев: ${rows.length}; совпали: ${match}; расхождений: ${divergences.length}.`);
md.push('- Наблюдение: переменные корневого скоупа в «наблюдательной» задаче + «что осталось активным» (у нас — нарушения инварианта утечек G-P, у оракула — оставшиеся активируемыми job\'ы).');
md.push('');
md.push('| # | Сценарий | Zeebe 8.5 live (переменные / что осталось активным) | ours (переменные / утечки / инциденты) | Вердикт |');
md.push('|---|---|---|---|---|');
rows.forEach((r, i) => {
  const z = r.zeebe
    ? `${r.zeebe.status} ${JSON.stringify(r.zeebe.vars)} / leftovers=${JSON.stringify(r.zeebe.leftovers || [])}`
    : '—';
  const ours = `${r.ours.status} ${JSON.stringify(r.ours.vars)} / leaks=${JSON.stringify(r.ours.leaks || [])}`
    + (r.ours.incidents && r.ours.incidents.length ? ` / incidents=${JSON.stringify(r.ours.incidents)}` : '');
  md.push(`| ${i + 1} | \`${r.id}\` | ${z} | ${ours} | ${r.verdict}${r.detail ? ' — ' + r.detail : ''} |`);
});
md.push('');
if (divergences.length === 0) {
  md.push('## Расхождений нет');
  md.push('');
  md.push('Ни по одному сценарию среза «потоки» наблюдаемое состояние и терминальный');
  md.push('статус не разошлись с живым Zeebe 8.5.0.');
} else {
  md.push('## Расхождения');
  md.push('');
  for (const d of divergences) {
    md.push(`### \`${d.id}\``);
    md.push('');
    md.push(`- Zeebe 8.5: \`${JSON.stringify(d.zeebe && d.zeebe.vars)}\` (status ${d.zeebe && d.zeebe.status}, leftovers ${JSON.stringify(d.zeebe && d.zeebe.leftovers)})`);
    md.push(`- ours: \`${JSON.stringify(d.ours.vars)}\` (status ${d.ours.status}, leaks ${JSON.stringify(d.ours.leaks)}, incidents ${JSON.stringify(d.ours.incidents)})`);
    md.push(`- расхождение: ${d.detail}`);
    md.push('');
  }
}
md.push('');
if (deployRejected.length > 0) {
  md.push('## Отказы оракула на деплое — отдельный вывод (не «ours-differs»)');
  md.push('');
  md.push('Эти сценарии НЕ расхождение рантайма: живой Zeebe 8.5.0 отверг модель на деплое,');
  md.push('поэтому сравнивать нечего — оракул не исполнил модель. Наш движок такую модель');
  md.push('ПРИНИМАЕТ и исполняет. Это расхождение по ПАРИТЕТУ ПРИЁМА МОДЕЛИ (мы permissive,');
  md.push('оракул — нет), и оно НЕ чинится в кампании: решение (отвергать такую модель и у нас,');
  md.push('или принять расхождение как осознанное) — за владельцем продукта.');
  md.push('');
  for (const d of deployRejected) {
    md.push(`- \`${d.id}\` — ${d.detail}`);
    md.push(`  - ours: ${d.ours.status} ${JSON.stringify(d.ours.vars)} (модель принята и исполнена движком)`);
  }
  md.push('');
  md.push('**Вывод (к решению владельца / кандидат в баг-WO):** либо мы отвергаем модель с');
  md.push('джойном inclusive на деплое, как Zeebe 8.5, либо расхождение принимается осознанно.');
  md.push('В рамках кампании (WO-OPS-28) правки в деплой-валидации НЕ делаются — баги чинятся');
  md.push('отдельными задачами; здесь зафиксирован факт и предложение.');
} else {
  md.push('## Отказы оракула на деплое');
  md.push('');
  md.push('Ни один сценарий среза не был отвергнут оракулом на деплое.');
}
md.push('');
md.push('## Как воспроизвести');
md.push('');
md.push('```bash');
md.push('# 0) свой оракул Zeebe 8.5.0 (чужие контейнеры не трогаем)');
md.push('docker run -d --name zbpm-o28c-zeebe --restart unless-stopped \\');
md.push('  -p 127.0.0.1:40600:9600 -p 127.0.0.1:37500:26500 \\');
md.push('  -e ZEEBE_BROKER_CLUSTER_PARTITIONSCOUNT=1 \\');
md.push('  -e ZEEBE_BROKER_GATEWAY_NETWORK_HOST=0.0.0.0 -e ZEEBE_BROKER_GATEWAY_NETWORK_PORT=26500 \\');
md.push('  camunda/zeebe:8.5.0');
md.push('# 1) наш движок (генерирует манифест и наши наблюдения)');
md.push('mvn -pl zorrobpm-engine verify -Dit.test=FlowScenarioMatrixIntegrationTests');
md.push('# 2) живой Zeebe 8.5.0 (пути — от каталога docs/analysis/c8-oracle)');
md.push('cd docs/analysis/c8-oracle && C8_ORACLE_GW=localhost:37500 node scenario-matrix.js \\');
md.push('  --manifest ../../../zorrobpm-engine/target/scenario-matrix/manifest-flows.json \\');
md.push('  --out ../../../zorrobpm-engine/target/scenario-matrix/zeebe-flows.json');
md.push('# 3) сравнение + пересъёмка прибитых фактов');
md.push('cd docs/analysis/c8-oracle && node scenario-matrix-flow-diff.js');
md.push('```');
md.push('');
md.push('Оракул — СВОЙ контейнер Zeebe 8.5.0; чужие контейнеры (zbpm-cmp-zeebe, raxon-*,');
md.push('zbpm-pgci-*) не трогаются. Имя своего контейнера — в §«Оракул» выше.');

fs.writeFileSync(path.join(outDir, 'phase2-divergences.md'), md.join('\n') + '\n');
fs.writeFileSync(path.join(outDir, 'phase2-divergences.json'), JSON.stringify({
  generatedAt: new Date().toISOString(),
  oracle: 'zeebe-8.5.0-live',
  total: rows.length,
  match,
  divergences: divergences.length,
  deployRejected: deployRejected.length,
  deployRejectedIds: deployRejected.map((d) => d.id),
  rows,
}, null, 2) + '\n');
fs.writeFileSync(expectPath, JSON.stringify({
  oracle: 'zeebe-8.5.0-live',
  note: 'Факты живого Zeebe 8.5.0 и наши, снятые прогоном docs/analysis/c8-oracle/scenario-matrix.js '
    + 'и срезом F2 нашего движка (FlowScenarioMatrixIntegrationTests). Расхождения НЕ чинятся в кампании: '
    + 'они закреплены здесь, чтобы их нельзя было закрыть молча.',
  scenarios: expectations,
}, null, 2) + '\n');

console.log(`scenarios=${rows.length} match=${match} divergences=${divergences.length} deployRejected=${deployRejected.length}`);
for (const d of divergences) console.log(`DIVERGENCE ${d.id}: ${d.detail}`);
for (const d of deployRejected) console.log(`DEPLOY-REJECTED ${d.id}: ${d.detail}`);
console.log(`WROTE ${path.join(outDir, 'phase2-divergences.md')}`);
console.log(`WROTE ${expectPath}`);
