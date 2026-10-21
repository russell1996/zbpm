#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 1 — пришивка ожиданий ЖИВОГО Zeebe 8.5.0 к корпусу.
 *
 * Берёт измерение оракула (scenarios/ops28-phase1/result-8.5.json) и добавляет в корпус
 * блок `expect` — так же, как это сделано в Ф1 (WO-OPS-30, `expect`-блоки в корпусе).
 * Ожидания БЕРУТСЯ ИЗ ЖИВОГО ОРАКУЛА (G-Q: не из нашей реализации), ни одно значение
 * не придумывается.
 *
 * Для расхождений (их список ниже — это §7 отчёта) ожидание Zeebe НЕ навязывается нашему
 * движку: там закрепляется НАШЕ измеренное поведение (characterization) + ссылка на баг-WO.
 * Иначе тест был бы зелёным только потому, что расхождение не проверялось.
 *
 * Запуск: node docs/analysis/c8-oracle/ops28-phase1-expect.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const CASES = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'ops28-phase1-cases.json');
const ZEEBE = path.join(__dirname, 'scenarios', 'ops28-phase1', 'result-8.5.json');
const OURS = path.join(ROOT, 'zorrobpm-engine', 'target', 'ops28-phase1-observed.json');

/** Расхождения Фазы 1: кейс → (баг-WO, почему ожидание Zeebe не навязывается). */
const DIVERGENCES = {
  'scr-inline-script': ['Ф1-3', 'Zeebe 8.5 не знает BPMN-инлайн <script> (наша расширенная форма)'],
  'brt-feel-expression': ['Ф1-3', 'Zeebe 8.5 требует у BRT zeebe:calledDecision; FEEL-форма (zeebe:script) — наша'],
  'snd-message-throw': ['Ф1-3', 'Zeebe 8.5 не знает BPMN-атрибут messageRef у send task (наша форма)'],
  'snd-publish-message': ['Ф1-5', 'Zeebe 8.5.0 падает с внутренним NPE (jobWorkerProps is null) — эталона нет'],
  'snd-intermediate-throw': ['Ф1-3', 'Zeebe 8.5 требует zeebe:publishMessage у throw-события; наша форма без него'],
  'rcv-message-no-key': ['Ф1-1', 'НАШ ДЕФЕКТ: бесключевая публикация будит ключевую подписку'],
};

/** Наш тип переменной → значение в JSON-виде Zeebe. */
function oursValue(entry) {
  if (!entry || typeof entry !== 'object') return entry;
  const type = String(entry.type);
  const raw = entry.value;
  switch (type) {
    case 'LONG': case 'DOUBLE': return Number(raw);
    case 'BOOLEAN': return raw === 'true';
    case 'JSON': try { return JSON.parse(raw); } catch (e) { return raw; }
    default: return raw;
  }
}

const corpus = JSON.parse(fs.readFileSync(CASES, 'utf8'));
const zeebe = JSON.parse(fs.readFileSync(ZEEBE, 'utf8'));
const ours = fs.existsSync(OURS) ? JSON.parse(fs.readFileSync(OURS, 'utf8')) : {};

let changed = 0;
for (const c of corpus.cases) {
  const z = zeebe[c.id] || {};
  const o = ours[c.id] || {};
  const live = {
    outcome: z.outcome || 'unknown',
    dump: z.dump ? z.dump.variables : null,
    // customHeaders у Zeebe приходят строкой JSON — разбираем, чтобы сравнивать карту с картой.
    taskHeaders: z.work && z.work.customHeaders ? JSON.parse(z.work.customHeaders || '{}') : null,
  };
  const mine = {
    outcome: o.outcome || 'unknown',
    dump: o['dump.root']
      ? Object.fromEntries(Object.entries(o['dump.root']).map(([k, v]) => [k, oursValue(v)]))
      : null,
    taskHeaders: (o['work.outbox'] || {}).taskHeaders || null,
  };
  c.expect = { live, ours: mine };
  const div = DIVERGENCES[c.id];
  if (div) {
    c.divergence = { bugWo: div[0], reason: div[1], liveMatchesOurs: false };
  } else {
    c.divergence = null;
  }
  changed++;
}

fs.writeFileSync(CASES, JSON.stringify(corpus, null, 2) + '\n', 'utf8');
console.log(`WROTE expect blocks for ${changed} cases -> ${CASES}`);
const div = corpus.cases.filter((c) => c.divergence);
console.log(`divergences pinned as characterization: ${div.map((c) => c.id).join(', ')}`);
