#!/usr/bin/env node
/**
 * WO-OPS-30 Ф4 — сверка реестра корней с матрицами фаз.
 *
 * Проверяет, что:
 *  1. каждая строка матрицы с вердиктом `ours differs` покрыта корнем реестра (одним или
 *     несколькими — кейс может нести два корня: например `f2b-response-headers` = R12 «by design,
 *     безопасность» + R13 «поведение HTTP-клиента»; покрытие корнем ОБЯЗАТЕЛЬНО, ровно один —
 *     нет);
 *  2. каждый корень реестра привязан хотя бы к одному кейсу (нет «висячих» корней);
 *  3. идентификаторы корней глобально уникальны (across all phases) — иначе нарезка
 *     «по одному баг-WO на корень» склеит разные корни;
 *  4. количество строк в сводной таблице реестра совпадает с фактическим числом корней.
 *
 * Запуск:  node docs/analysis/check-divergence-register.js
 * Выход:   0 — всё сходится; 1 — расхождение (список в stdout).
 *
 * Почему не в `ci/`: `ci/wo-report-gate.sh` и прочие maintainer-инструменты править нельзя
 * (CONTRIBUTING.md, G-C-adjacent правило про гейт), а этот скрипт — проверка артефакта
 * измерения, а не часть гейта.
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const REGISTER = path.join(ROOT, 'docs', 'analysis', 'conformance-divergence-register.md');

/** Матрица фазы → префикс глобальных ID корней этой фазы. */
const PHASES = [
  { file: 'docs/analysis/feel-iomapping-conformance-matrix.md', prefix: 'FEEL' },
  { file: 'docs/analysis/job-protocol-conformance-matrix.md', prefix: 'JOB' },
  { file: 'docs/analysis/http-connector-conformance-matrix.md', prefix: 'CONN' },
  { file: 'docs/analysis/message-conformance-matrix.md', prefix: 'MSG' },
];

/** Разбор строки реестра: `| \`ID\` | ... |` → { id, cells }. */
function parseRegister(text) {
  const rows = [];
  for (const line of text.split('\n')) {
    const m = line.match(/^\|\s*`([A-Z]+-R\d+)`\s*\|(.*)$/);
    if (!m) continue;
    const cells = m[2].split('|').map((c) => c.trim());
    rows.push({ id: m[1], cells, line });
  }
  return rows;
}

/** Кейсы строки матрицы, у которых вердикт `ours differs`. */
function differingCases(matrixText) {
  const out = [];
  for (const line of matrixText.split('\n')) {
    if (!line.startsWith('|')) continue;
    // колонка кейса — вторая (после №), а вердикт — последняя непустая
    const cells = line.split('|').map((c) => c.trim());
    if (cells.length < 6) continue;
    const verdict = cells[cells.length - 2] || cells[cells.length - 1];
    if (!/ours differs/i.test(verdict)) continue;
    const idM = cells[2].match(/`([^`]+)`/);
    if (!idM) continue;
    out.push({ caseId: idM[1], line });
  }
  return out;
}

function main() {
  const problems = [];

  const regText = fs.readFileSync(REGISTER, 'utf8');
  const regRows = parseRegister(regText);

  // (3) глобальная уникальность ID
  const seen = new Map();
  for (const r of regRows) {
    seen.set(r.id, (seen.get(r.id) || 0) + 1);
  }
  for (const [id, n] of seen) {
    if (n > 1) problems.push(`ID корня не уникален: ${id} встречается ${n} раз(а) в реестре`);
  }

  // (1)+(2) покрытие: каждый differing-кейс → ровно один корень; каждый корень → кейсы
  const rootsByCase = new Map(); // caseId -> [rootId]
  const casesByRoot = new Map(); // rootId -> [caseId]
  for (const r of regRows) {
    // m[2] — текст ПОСЛЕ колонки с ID, поэтому: [0]=Корень [1]=Кейсы [2]=живое [3]=ours ...
    const caseCell = r.cells[1] || '';
    const ids = caseCell.match(/`([^`]+)`/g) || [];
    const caseIds = ids.map((x) => x.replace(/`/g, ''));
    for (const c of caseIds) {
      if (!rootsByCase.has(c)) rootsByCase.set(c, []);
      rootsByCase.get(c).push(r.id);
      if (!casesByRoot.has(r.id)) casesByRoot.set(r.id, []);
      casesByRoot.get(r.id).push(c);
    }
  }

  let differingTotal = 0;
  for (const ph of PHASES) {
    const mPath = path.join(ROOT, ph.file);
    if (!fs.existsSync(mPath)) {
      problems.push(`матрица не найдена: ${ph.file}`);
      continue;
    }
    const mText = fs.readFileSync(mPath, 'utf8');
    const diffs = differingCases(mText);
    differingTotal += diffs.length;
    for (const d of diffs) {
      const roots = rootsByCase.get(d.caseId) || [];
      if (roots.length === 0) {
        problems.push(`кейс ${d.caseId} (${ph.prefix}) помечен ours differs, но НЕ покрыт ни одним корнем реестра`);
      } else if (roots.length > 2) {
        // два корня на кейс — бывает (R12 by design + R13 клиент); три и больше — признак
        // того, что кейс «нарос» разнородными расхождениями и его надо разобрать.
        problems.push(`кейс ${d.caseId} (${ph.prefix}) покрыт ${roots.length} корнями: ${roots.join(', ')}`);
      }
    }
  }

  // (4) сводная таблица реестра vs факт
  const totalRow = regText.match(/^\|\s*\*\*Итого\*\*\s*\|\s*\|\s*\*\*(\d+)\*\*\s*\|/m);
  if (!totalRow) {
    problems.push('в реестре нет строки «Итого» со счётчиком корней');
  } else if (Number(totalRow[1]) !== regRows.length) {
    problems.push(`«Итого» в реестре говорит ${totalRow[1]} корней, в таблице ${regRows.length}`);
  }

  // корни без кейсов (висячие) — только если у корня вообще нет колонки с кейсами
  for (const [root, cases] of casesByRoot) {
    if (cases.length === 0) problems.push(`корень ${root} не привязан ни к одному кейсу`);
  }

  console.log(`Реестр: ${regRows.length} корней; матрицы: ${differingTotal} строк 'ours differs'`);
  if (problems.length === 0) {
    console.log('OK: каждый `ours`-кейс покрыт корнем реестра, ID уникальны, сводка сходится.');
    return 0;
  }
  console.error(`FAIL: расхождений ${problems.length}:`);
  for (const p of problems) console.error('  - ' + p);
  return 1;
}

process.exit(main());
