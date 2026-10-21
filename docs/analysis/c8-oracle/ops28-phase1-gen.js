#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 1 — генератор сценариев «Задачи и данные».
 *
 * ЕДИНСТВЕННЫЙ источник правды: из этого файла генерируются
 *   1) BPMN-фикстуры  zorrobpm-engine/src/test/resources/ops28-phase1/<id>.bpmn
 *   2) корпус кейсов  zorrobpm-engine/src/test/resources/ops28-phase1-cases.json
 *   3) DMN-фикстура    zorrobpm-engine/src/test/resources/ops28-phase1/discount.dmn
 *
 * Оба измерителя читают ОДНИ И ТЕ ЖЕ байты:
 *   - оракул Zeebe 8.5:  docs/analysis/c8-oracle/ops28-phase1-oracle.js
 *   - наш движок:         zorrobpm-engine/src/test/java/.../Phase1TaskDataConformanceIntegrationTests
 * (два независимых генератора BPMN разошлись бы — так уже было в Ф1, поэтому здесь
 *  генератор один, а обе стороны читают сгенерированные файлы.)
 *
 * Форма кейса (общий для обеих сторон):
 *   id        — идентификатор кейса
 *   element   — элемент BPMN, который кейс проверяет
 *   docs      — что говорит документация Camunda 8.9 (первая колонка матрицы)
 *   vars      — переменные старта
 *   drive[]   — шаги «что завершить/опубликовать», по порядку:
 *                 kind=service — service task завершается переменными vars
 *                 kind=user    — user task завершается переменными vars
 *                 kind=message — публикуется сообщение (message/key/vars)
 *                 kind=auto    — элемент исполняется сам (script/BRT/send-message)
 *   dump      — элемент-наблюдатель (service task), его payload = итоговое состояние scope
 *
 * Запуск:  node docs/analysis/c8-oracle/ops28-phase1-gen.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const OUT_DIR = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'ops28-phase1');
const OUT_CASES = path.join(ROOT, 'zorrobpm-engine', 'src', 'test', 'resources', 'ops28-phase1-cases.json');

const esc = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;');

/* ------------------------------------------------------------------ builders */

function defs(pid, body, extraDefs) {
  return '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + 'id="Definitions_' + pid + '" targetNamespace="http://bpmn.io/schema/bpmn">'
    + (extraDefs || '')
    + '<bpmn:process id="' + pid + '" name="' + pid + '" isExecutable="true">'
    + body
    + '</bpmn:process></bpmn:definitions>';
}

const start = (id, outgoing) => `<bpmn:startEvent id="${id || 'start'}"><bpmn:outgoing>${outgoing || 'f1'}</bpmn:outgoing></bpmn:startEvent>`;
const end = (id, incoming) => `<bpmn:endEvent id="${id || 'end'}"><bpmn:incoming>${incoming || 'f3'}</bpmn:incoming></bpmn:endEvent>`;
const flow = (id, src, tgt) => `<bpmn:sequenceFlow id="${id}" sourceRef="${src}" targetRef="${tgt}"/>`;

function svc(id, jobType, incoming, outgoing, ext) {
  const extension = `<zeebe:taskDefinition type="${esc(jobType)}"/>` + (ext || '');
  return `<bpmn:serviceTask id="${id}" name="${id}">`
    + `<bpmn:extensionElements>${extension}</bpmn:extensionElements>`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:serviceTask>';
}

function usr(id, incoming, outgoing, ext) {
  return `<bpmn:userTask id="${id}" name="${id}">`
    + (ext ? `<bpmn:extensionElements>${ext}</bpmn:extensionElements>` : '')
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:userTask>';
}

function script(id, incoming, outgoing, ext) {
  return `<bpmn:scriptTask id="${id}" name="${id}">`
    + `<bpmn:extensionElements>${ext}</bpmn:extensionElements>`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:scriptTask>';
}

function inlineScript(id, incoming, outgoing, body, format) {
  return `<bpmn:scriptTask id="${id}" name="${id}" scriptFormat="${esc(format || 'FEEL')}">`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + `<bpmn:script>${esc(body)}</bpmn:script>`
    + '</bpmn:scriptTask>';
}

function brt(id, incoming, outgoing, ext) {
  return `<bpmn:businessRuleTask id="${id}" name="${id}">`
    + `<bpmn:extensionElements>${ext}</bpmn:extensionElements>`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:businessRuleTask>';
}

function send(id, incoming, outgoing, messageRef, ext) {
  // В BPMN 2.0 messageRef у sendTask — АТРИБУТ (не дочерний элемент).
  return `<bpmn:sendTask id="${id}" name="${id}"${messageRef ? ` messageRef="${esc(messageRef)}"` : ''}>`
    + (ext ? `<bpmn:extensionElements>${ext}</bpmn:extensionElements>` : '')
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:sendTask>';
}

function recv(id, incoming, outgoing, messageRef, ext) {
  // В BPMN 2.0 messageRef у receiveTask — АТРИБУТ (не дочерний элемент).
  return `<bpmn:receiveTask id="${id}" name="${id}"${messageRef ? ` messageRef="${esc(messageRef)}"` : ''}>`
    + (ext ? `<bpmn:extensionElements>${ext}</bpmn:extensionElements>` : '')
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:receiveTask>';
}

function intermediateThrow(id, incoming, outgoing, messageRef) {
  // Порядок детей по BPMN 2.0 XSD: incoming/outgoing, затем eventDefinition (в конце).
  return `<bpmn:intermediateThrowEvent id="${id}" name="${id}">`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + `<bpmn:messageEventDefinition id="${id}Def" messageRef="${esc(messageRef)}"/>`
    + '</bpmn:intermediateThrowEvent>';
}

function callActivity(id, incoming, outgoing, ext) {
  return `<bpmn:callActivity id="${id}" name="${id}">`
    + `<bpmn:extensionElements>${ext}</bpmn:extensionElements>`
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + '</bpmn:callActivity>';
}

function subProcess(id, incoming, outgoing, ext, inner) {
  return `<bpmn:subProcess id="${id}" name="${id}">`
    + (ext ? `<bpmn:extensionElements>${ext}</bpmn:extensionElements>` : '')
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + inner
    + '</bpmn:subProcess>';
}

const ioMapping = (inputs, outputs) => {
  const parts = [];
  for (const m of inputs || []) parts.push(`<zeebe:input source="${esc(m.source)}" target="${esc(m.target)}"/>`);
  for (const m of outputs || []) parts.push(`<zeebe:output source="${esc(m.source)}" target="${esc(m.target)}"/>`);
  return parts.length ? `<zeebe:ioMapping>${parts.join('')}</zeebe:ioMapping>` : '';
};

const messageDef = (id, name, correlationKey) =>
  // Zeebe 8.5 требует у каждого <bpmn:message> РОВНО один zeebe:subscription
  // (пустой = без correlation key). Наш парсер читает оттуда correlationKey.
  `<bpmn:message id="${id}" name="${name}">`
  + `<bpmn:extensionElements><zeebe:subscription correlationKey="${esc(correlationKey)}"/></bpmn:extensionElements>`
  + '</bpmn:message>';

/* -------------------------------------------------------------- case helpers */

/** Стандартная цепочка: start → <элемент под тестом> → dump → end. */
function chain(elementXml, dumpJobType) {
  return start()
    + elementXml
    + svc('dump', dumpJobType, 'f2', 'f3')
    + end()
    + flow('f1', 'start', elementXml.match(/id="([^"]+)"/)[1])
    + flow('f2', elementXml.match(/id="([^"]+)"/)[1], 'dump')
    + flow('f3', 'dump', 'end');
}

/* ------------------------------------------------------------------- кейсы */

const cases = [];
const bpmn = {};

function addCase(c) {
  cases.push(c);
  bpmn[c.id] = c.bpmn;
}

const P = (id) => 'p1_' + id.replace(/-/g, '_');

/* --- 1..5 serviceTask ------------------------------------------------------ */

addCase({
  id: 'svc-completion-vars',
  element: 'serviceTask',
  title: 'переменные завершения service task попадают в scope процесса',
  docs: '8.9 variables: variables completed with a job are merged into the containing scope (the process scope at root level)',
  vars: { a: 1 },
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_completion_vars_work', vars: { c: 'C' } }],
  dump: 'dump',
  dumpJobType: 'p1_svc_completion_vars_dump',
  bpmn: defs(P('svc_completion_vars'),
    chain(svc('work', 'p1_svc_completion_vars_work', 'f1', 'f2'), 'p1_svc_completion_vars_dump')),
});

addCase({
  id: 'svc-no-completion-vars',
  element: 'serviceTask',
  title: 'service task без переменных завершения не добавляет переменных',
  docs: '8.9 variables: completing a job without variables leaves the scope unchanged',
  vars: { a: 1 },
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_no_completion_vars_work', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_svc_no_completion_vars_dump',
  bpmn: defs(P('svc_no_completion_vars'),
    chain(svc('work', 'p1_svc_no_completion_vars_work', 'f1', 'f2'), 'p1_svc_no_completion_vars_dump')),
});

addCase({
  id: 'svc-job-vars-scope',
  element: 'serviceTask',
  title: 'payload задания service task = переменные видимого scope',
  docs: '8.9 jobs: an activated job carries the variables of the element scope (all visible variables)',
  vars: { a: 1, s: 'text' },
  observeWork: true,
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_job_vars_scope_work', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_svc_job_vars_scope_dump',
  bpmn: defs(P('svc_job_vars_scope'),
    chain(svc('work', 'p1_svc_job_vars_scope_work', 'f1', 'f2'), 'p1_svc_job_vars_scope_dump')),
});

addCase({
  id: 'svc-io-input-local',
  element: 'serviceTask',
  title: 'ioMapping input создаёт ЛОКАЛЬНУЮ переменную элемента (в root не попадает)',
  docs: '8.9 variables: input mappings create variables in the element scope; they are not propagated to the parent scope',
  vars: { a: 1 },
  observeWork: true,
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_io_input_local_work', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_svc_io_input_local_dump',
  bpmn: defs(P('svc_io_input_local'),
    chain(svc('work', 'p1_svc_io_input_local_work', 'f1', 'f2',
      ioMapping([{ source: '=a', target: 'x' }], null)), 'p1_svc_io_input_local_dump')),
});

addCase({
  id: 'svc-io-output-root',
  element: 'serviceTask',
  title: 'ioMapping output продвигает переменную в родительский scope; сырые переменные завершения — нет',
  docs: '8.9 variables: output mappings write to the parent scope; raw completion variables of a task with output mappings stay element-local',
  vars: {},
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_io_output_root_work', vars: { c: 'C' } }],
  dump: 'dump',
  dumpJobType: 'p1_svc_io_output_root_dump',
  bpmn: defs(P('svc_io_output_root'),
    chain(svc('work', 'p1_svc_io_output_root_work', 'f1', 'f2',
      ioMapping(null, [{ source: '=c', target: 'o' }])), 'p1_svc_io_output_root_dump')),
});

/* --- 6..8 userTask ---------------------------------------------------------- */

addCase({
  id: 'usr-completion-vars',
  element: 'userTask',
  title: 'переменные завершения user task попадают в scope процесса',
  docs: '8.9 user task: variables completed with the task are merged into the process scope',
  vars: { a: 1 },
  drive: [{ element: 'ut', kind: 'user', jobType: 'io.camunda.zeebe:userTask', vars: { d: 'D' } }],
  dump: 'dump',
  dumpJobType: 'p1_usr_completion_vars_dump',
  bpmn: defs(P('usr_completion_vars'),
    chain(usr('ut', 'f1', 'f2'), 'p1_usr_completion_vars_dump')),
});

addCase({
  id: 'usr-job-vars-scope',
  element: 'userTask',
  title: 'payload задания user task = переменные видимого scope',
  docs: '8.9 user task: the user task job carries the variables of the element scope',
  vars: { a: 1, s: 'text' },
  observeWork: true,
  drive: [{ element: 'ut', kind: 'user', jobType: 'io.camunda.zeebe:userTask', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_usr_job_vars_scope_dump',
  bpmn: defs(P('usr_job_vars_scope'),
    chain(usr('ut', 'f1', 'f2'), 'p1_usr_job_vars_scope_dump')),
});

addCase({
  id: 'usr-io-mapping-local',
  element: 'userTask',
  title: 'ioMapping input на user task: локальная переменная элемента не выходит в root',
  docs: '8.9 variables: input mappings create variables in the element scope; a user task scope is destroyed on completion',
  vars: { a: 1 },
  drive: [{ element: 'ut', kind: 'user', jobType: 'io.camunda.zeebe:userTask', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_usr_io_mapping_local_dump',
  bpmn: defs(P('usr_io_mapping_local'),
    chain(usr('ut', 'f1', 'f2', ioMapping([{ source: '=a', target: 'x' }], null)), 'p1_usr_io_mapping_local_dump')),
});

/* --- 9..11 scriptTask ------------------------------------------------------ */

addCase({
  id: 'scr-feel-expression',
  element: 'scriptTask',
  title: 'zeebe:script (FEEL) пишет resultVariable в scope процесса',
  docs: '8.9 script task: a script task evaluates a FEEL expression and stores the result in the result variable',
  vars: { a: 2, b: 3 },
  drive: [{ element: 'scr', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_scr_feel_expression_dump',
  bpmn: defs(P('scr_feel_expression'),
    chain(script('scr', 'f1', 'f2',
      '<zeebe:script expression="=a + b" resultVariable="sum"/>'), 'p1_scr_feel_expression_dump')),
});

addCase({
  id: 'scr-inline-script',
  element: 'scriptTask',
  title: 'инлайн-<script> (scriptFormat) — поддержка неодинакова',
  docs: '8.9 script task: the script task is defined by zeebe:script with a FEEL expression; inline <script> bodies are not part of the Camunda 8 model',
  vars: { a: 2, b: 3 },
  drive: [{ element: 'scr', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_scr_inline_script_dump',
  bpmn: defs(P('scr_inline_script'),
    chain(inlineScript('scr', 'f1', 'f2', 'a + b', 'FEEL'), 'p1_scr_inline_script_dump')),
});

addCase({
  id: 'scr-runtime-error',
  element: 'scriptTask',
  title: 'ошибка FEEL-выражения в script task: инцидент и парковка инстанса',
  docs: '8.9 incidents: a failing job raises an incident and the process instance waits there',
  vars: {},
  drive: [{ element: 'scr', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_scr_runtime_error_dump',
  bpmn: defs(P('scr_runtime_error'),
    chain(script('scr', 'f1', 'f2',
      '<zeebe:script expression="=1 / 0" resultVariable="sum"/>'), 'p1_scr_runtime_error_dump')),
});

addCase({
  id: 'scr-runtime-error-type',
  element: 'scriptTask',
  title: 'ошибка типа в FEEL-выражении script task: инцидент и парковка инстанса',
  docs: '8.9 incidents: a failing job raises an incident and the process instance waits there',
  vars: {},
  drive: [{ element: 'scr', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_scr_runtime_error_type_dump',
  bpmn: defs(P('scr_runtime_error_type'),
    chain(script('scr', 'f1', 'f2',
      '<zeebe:script expression="=&quot;a&quot; + 1" resultVariable="sum"/>'), 'p1_scr_runtime_error_type_dump')),
});

/* --- 12..14 businessRuleTask ------------------------------------------------ */

addCase({
  id: 'brt-dmn',
  element: 'businessRuleTask',
  title: 'zeebe:calledDecision (DMN) пишет resultVariable в scope процесса',
  docs: '8.9 business rule task: the decision result is stored in the result variable',
  vars: { category: 'gold' },
  dmn: 'ops28-phase1/discount.dmn',
  drive: [{ element: 'brt', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_brt_dmn_dump',
  bpmn: defs(P('brt_dmn'),
    chain(brt('brt', 'f1', 'f2',
      '<zeebe:calledDecision decisionId="discount" resultVariable="discount"/>'), 'p1_brt_dmn_dump')),
});

addCase({
  id: 'brt-feel-expression',
  element: 'businessRuleTask',
  title: 'business rule task с FEEL-выражением (zeebe:script) — поддержка неодинакова',
  docs: '8.9 business rule task: the task is bound to a DMN decision (zeebe:calledDecision); a bare FEEL expression (zeebe:script) is not part of the Camunda 8 model',
  vars: { a: 2 },
  drive: [{ element: 'brt', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_brt_feel_expression_dump',
  bpmn: defs(P('brt_feel_expression'),
    chain(brt('brt', 'f1', 'f2',
      '<zeebe:script expression="=a * 10" resultVariable="doubled"/>'), 'p1_brt_feel_expression_dump')),
});

addCase({
  id: 'brt-missing-decision',
  element: 'businessRuleTask',
  title: 'ссылка на несуществующее DMN-решение: инцидент, инстанс паркуется',
  docs: '8.9 incidents: a failing business rule task raises an incident',
  vars: {},
  drive: [{ element: 'brt', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_brt_missing_decision_dump',
  bpmn: defs(P('brt_missing_decision'),
    chain(brt('brt', 'f1', 'f2',
      '<zeebe:calledDecision decisionId="noSuchDecision" resultVariable="r"/>'), 'p1_brt_missing_decision_dump')),
});

/* --- 15..16 sendTask -------------------------------------------------------- */

addCase({
  id: 'snd-message-throw',
  element: 'sendTask',
  title: 'send task в форме BPMN messageRef бросает сообщение живому получателю',
  docs: '8.9 send task: a send task throws a message; the message variables are merged into the receiving scope. Camunda 8.5 expresses this with zeebe:publishMessage, not with the BPMN messageRef attribute',
  vars: { a: 1 },
  drive: [{ element: 'snd', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_snd_message_throw_dump',
  bpmn: defs(P('snd_message_throw'),
    start()
    + send('snd', 'f1', 'f2', 'msgNote')
    + recv('rcv', 'f2', 'f3', 'msgNote')
    + svc('dump', 'p1_snd_message_throw_dump', 'f3', 'f4')
    + end('end', 'f4')
    + flow('f1', 'start', 'snd')
    + flow('f2', 'snd', 'rcv')
    + flow('f3', 'rcv', 'dump')
    + flow('f4', 'dump', 'end'),
    messageDef('msgNote', 'note', '="k1"')),
});

addCase({
  id: 'snd-publish-message',
  element: 'sendTask',
  title: 'send task в форме Camunda 8.5 (zeebe:publishMessage) бросает сообщение живому получателю',
  docs: '8.9 send task: zeebe:publishMessage publishes a message (correlationKey — имя переменной); the message variables are merged into the receiving scope',
  vars: { a: 1, k: 'k1' },
  drive: [{ element: 'snd', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_snd_publish_message_dump',
  bpmn: defs(P('snd_publish_message'),
    start()
    + send('snd', 'f1', 'f2', 'msgNote',
      '<zeebe:publishMessage messageName="note" correlationKey="k"/>')
    + recv('rcv', 'f2', 'f3', 'msgNote')
    + svc('dump', 'p1_snd_publish_message_dump', 'f3', 'f4')
    + end('end', 'f4')
    + flow('f1', 'start', 'snd')
    + flow('f2', 'snd', 'rcv')
    + flow('f3', 'rcv', 'dump')
    + flow('f4', 'dump', 'end'),
    messageDef('msgNote', 'note', '="k1"')),
});

addCase({
  id: 'snd-job-worker',
  element: 'sendTask',
  title: 'send task с zeebe:taskDefinition — это job-задача воркеру',
  docs: '8.9 send task: a send task with a task definition is a job worker; without it the task throws a message',
  vars: { a: 1 },
  drive: [{ element: 'snd', kind: 'service', jobType: 'p1_snd_job_worker_snd', vars: { s: 'S' } }],
  dump: 'dump',
  dumpJobType: 'p1_snd_job_worker_dump',
  bpmn: defs(P('snd_job_worker'),
    chain(send('snd', 'f1', 'f2', null,
      '<zeebe:taskDefinition type="p1_snd_job_worker_snd"/>'), 'p1_snd_job_worker_dump')),
});

/* --- 17..19 receiveTask ---------------------------------------------------- */

addCase({
  id: 'rcv-message-catch',
  element: 'receiveTask',
  title: 'receive task ждёт сообщение; опубликованные переменные попадают в scope',
  docs: '8.9 receive task: a receive task waits for a message; the message variables are merged into the scope',
  vars: { a: 1 },
  drive: [{ element: 'rcv', kind: 'message', message: 'note', key: 'k1', vars: { m: 'M' } }],
  dump: 'dump',
  dumpJobType: 'p1_rcv_message_catch_dump',
  bpmn: defs(P('rcv_message_catch'),
    chain(recv('rcv', 'f1', 'f2', 'msgNote'), 'p1_rcv_message_catch_dump'),
    messageDef('msgNote', 'note', '="k1"')),
});

addCase({
  id: 'rcv-correlation-key-match',
  element: 'receiveTask',
  title: 'correlationKey подписки совпал — сообщение коррелирует',
  docs: '8.9 messages: a subscription with a correlation key only receives a message whose correlation key matches',
  vars: { orderId: 'A' },
  messageDefs: [messageDef('msgOrder', 'orderUpdate', '=orderId')],
  drive: [{ element: 'rcv', kind: 'message', message: 'orderUpdate', key: 'A', vars: { m: 'M' } }],
  dump: 'dump',
  dumpJobType: 'p1_rcv_correlation_key_match_dump',
  bpmn: defs(P('rcv_correlation_key_match'),
    chain(recv('rcv', 'f1', 'f2', 'msgOrder'), 'p1_rcv_correlation_key_match_dump'),
    messageDef('msgOrder', 'orderUpdate', '=orderId')),
});

addCase({
  id: 'rcv-correlation-key-miss',
  element: 'receiveTask',
  title: 'correlationKey подписки не совпал — сообщение не коррелирует, инстанс ждёт',
  docs: '8.9 messages: a message with a non-matching correlation key is not correlated with the subscription',
  vars: { orderId: 'A' },
  messageDefs: [messageDef('msgOrder', 'orderUpdate', '=orderId')],
  drive: [{ element: 'rcv', kind: 'message', message: 'orderUpdate', key: 'B', vars: { m: 'M' } }],
  dump: 'dump',
  dumpJobType: 'p1_rcv_correlation_key_miss_dump',
  bpmn: defs(P('rcv_correlation_key_miss'),
    chain(recv('rcv', 'f1', 'f2', 'msgOrder'), 'p1_rcv_correlation_key_miss_dump'),
    messageDef('msgOrder', 'orderUpdate', '=orderId')),
});

addCase({
  id: 'rcv-message-no-key',
  element: 'receiveTask',
  title: 'сообщение без correlation key не коррелирует с подпиской, у которой ключ задан',
  docs: '8.9 messages: a message is correlated by its correlation key; a message published without a key does not match a keyed subscription',
  vars: { orderId: 'A' },
  drive: [{ element: 'rcv', kind: 'message', message: 'orderUpdate', key: null, vars: { m: 'M' } }],
  dump: 'dump',
  dumpJobType: 'p1_rcv_message_no_key_dump',
  bpmn: defs(P('rcv_message_no_key'),
    chain(recv('rcv', 'f1', 'f2', 'msgOrder'), 'p1_rcv_message_no_key_dump'),
    messageDef('msgOrder', 'orderUpdate', '="k1"')),
});

addCase({
  id: 'snd-intermediate-throw',
  element: 'intermediateThrowEvent',
  title: 'бросок сообщения через intermediate throw event — форма, рабочая в Camunda 8.5',
  docs: '8.9 message events: an intermediate message throw event publishes a message; the message variables are merged into the receiving scope',
  vars: { a: 1 },
  drive: [{ element: 'thr', kind: 'auto' }],
  dump: 'dump',
  dumpJobType: 'p1_snd_intermediate_throw_dump',
  bpmn: defs(P('snd_intermediate_throw'),
    start()
    + intermediateThrow('thr', 'f1', 'f2', 'msgNote')
    + recv('rcv', 'f2', 'f3', 'msgNote')
    + svc('dump', 'p1_snd_intermediate_throw_dump', 'f3', 'f4')
    + end('end', 'f4')
    + flow('f1', 'start', 'thr')
    + flow('f2', 'thr', 'rcv')
    + flow('f3', 'rcv', 'dump')
    + flow('f4', 'dump', 'end'),
    messageDef('msgNote', 'note', '="k1"')),
});

/* --- 20..23 scope переменных (call activity / subprocess) ------------------- */

const CHILD_BODY = (jobType) => start('cStart', 'cf1')
  + svc('cWork', jobType, 'cf1', 'cf2')
  + end('cEnd', 'cf2')
  + flow('cf1', 'cStart', 'cWork')
  + flow('cf2', 'cWork', 'cEnd');

addCase({
  id: 'scope-callactivity-propagate',
  element: 'callActivity',
  title: 'call activity: переменные дочернего инстанса пропагаются в родителя (по умолчанию)',
  docs: '8.9 call activity: by default all variables of the called process are propagated back to the caller',
  vars: { a: 1 },
  drive: [{ element: 'call', kind: 'auto' }, { element: 'cWork', kind: 'service', anyPi: true, jobType: 'p1_child_propagate_work', vars: { c: 'C' } }],
  dump: 'dump',
  dumpJobType: 'p1_scope_callactivity_propagate_dump',
  child: { id: 'p1_child_propagate', body: CHILD_BODY('p1_child_propagate_work') },
  bpmn: defs(P('scope_callactivity_propagate'),
    chain(callActivity('call', 'f1', 'f2',
      '<zeebe:calledElement processId="p1_child_propagate"/>'), 'p1_scope_callactivity_propagate_dump')),
});

addCase({
  id: 'scope-callactivity-nopropagate',
  element: 'callActivity',
  title: 'call activity: propagateAllChildVariables=false — переменные дочернего инстанса не пропагают',
  docs: '8.9 call activity: propagateAllChildVariables=false disables the propagation of the child variables',
  vars: { a: 1 },
  drive: [{ element: 'call', kind: 'auto' }, { element: 'cWork', kind: 'service', anyPi: true, jobType: 'p1_child_nopropagate_work', vars: { c: 'C' } }],
  dump: 'dump',
  dumpJobType: 'p1_scope_callactivity_nopropagate_dump',
  child: { id: 'p1_child_nopropagate', body: CHILD_BODY('p1_child_nopropagate_work') },
  bpmn: defs(P('scope_callactivity_nopropagate'),
    chain(callActivity('call', 'f1', 'f2',
      '<zeebe:calledElement processId="p1_child_nopropagate" propagateAllChildVariables="false"/>'),
    'p1_scope_callactivity_nopropagate_dump')),
});

addCase({
  id: 'scope-callactivity-input-mapping',
  element: 'callActivity',
  title: 'call activity: propagateAllParentVariables=false + ioMapping input — ребёнок видит только смэпленное',
  docs: '8.9 call activity: with propagateAllParentVariables=false only the input-mapped variables are passed to the called process',
  vars: { a: 1, b: 2 },
  drive: [{ element: 'call', kind: 'auto' }, { element: 'cWork', kind: 'service', anyPi: true, jobType: 'p1_child_inputmap_work', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_scope_callactivity_input_mapping_dump',
  observeWork: true,
  observeElement: 'cWork',
  child: { id: 'p1_child_inputmap', body: CHILD_BODY('p1_child_inputmap_work') },
  bpmn: defs(P('scope_callactivity_input_mapping'),
    chain(callActivity('call', 'f1', 'f2',
      '<zeebe:calledElement processId="p1_child_inputmap" propagateAllParentVariables="false"/>'
      + ioMapping([{ source: '=a', target: 'x' }], null)),
    'p1_scope_callactivity_input_mapping_dump')),
});

addCase({
  id: 'scope-subprocess-local',
  element: 'subProcess',
  title: 'подпроцесс: переменная, созданная внутри, не выходит в родительский scope',
  docs: '8.9 subprocess: the subprocess has its own scope; variables created inside are removed when it ends unless mapped out',
  vars: { a: 1 },
  drive: [{ element: 'sp', kind: 'auto' }, { element: 'spWork', kind: 'service', jobType: 'p1_scope_subprocess_local_work', vars: { inner: 'I' } }],
  dump: 'dump',
  dumpJobType: 'p1_scope_subprocess_local_dump',
  bpmn: defs(P('scope_subprocess_local'),
    start()
    + subProcess('sp', 'f1', 'f2', null,
      start('spStart', 'sf1')
      + svc('spWork', 'p1_scope_subprocess_local_work', 'sf1', 'sf2')
      + end('spEnd')
      + flow('sf1', 'spStart', 'spWork')
      + flow('sf2', 'spWork', 'spEnd'))
    + svc('dump', 'p1_scope_subprocess_local_dump', 'f2', 'f3')
    + end()
    + flow('f1', 'start', 'sp')
    + flow('f2', 'sp', 'dump')
    + flow('f3', 'dump', 'end')),
});

/* --- 24 taskHeaders -------------------------------------------------------- */

addCase({
  id: 'svc-task-headers',
  element: 'serviceTask',
  title: 'zeebe:taskHeaders — заголовки задания (поддержка зависит от версии)',
  docs: '8.9 jobs: custom task headers are carried by the job (Camunda 8.6+ feature; 8.5 support measured)',
  vars: { a: 1 },
  observeWork: true,
  drive: [{ element: 'work', kind: 'service', jobType: 'p1_svc_task_headers_work', vars: {} }],
  dump: 'dump',
  dumpJobType: 'p1_svc_task_headers_dump',
  bpmn: defs(P('svc_task_headers'),
    chain(svc('work', 'p1_svc_task_headers_work', 'f1', 'f2',
      '<zeebe:taskHeaders><zeebe:header key="h1" value="v1"/></zeebe:taskHeaders>'),
    'p1_svc_task_headers_dump')),
});

/* ------------------------------------------------------------------ запись */

const corpus = {
  campaign: 'WO-OPS-28 ФАЗА 1 — «Задачи и данные»: service/user/script/business-rule/send/receive, ioMapping, FEEL, переменные скоупа, correlation message',
  oracle: 'Zeebe 8.5.0 (живой оракул, docs/analysis/c8-oracle/ops28-phase1-oracle.js)',
  generatedBy: 'docs/analysis/c8-oracle/ops28-phase1-gen.js',
  cases: cases.map((c) => ({
    id: c.id,
    element: c.element,
    title: c.title,
    docs: c.docs,
    processId: P(c.id.replace(/-/g, '_')),
    bpmn: `ops28-phase1/${c.id}.bpmn`,
    vars: c.vars || {},
    drive: c.drive || [],
    dump: c.dump || 'dump',
    dumpJobType: c.dumpJobType,
    observeWork: !!c.observeWork,
    ...(c.observeElement ? { observeElement: c.observeElement } : {}),
    ...(c.dmn ? { dmn: c.dmn } : {}),
    ...(c.child ? { child: { id: c.child.id, bpmn: `ops28-phase1/${c.child.id}.bpmn` } } : {}),
  })),
};

/* Дочерние процессы (call activity) — те же файлы, читаемые обеими сторонами. */
for (const c of cases) {
  if (!c.child) continue;
  bpmn[c.child.id] = defs(c.child.id, c.child.body);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
for (const [id, xml] of Object.entries(bpmn)) {
  fs.writeFileSync(path.join(OUT_DIR, `${id}.bpmn`), xml, 'utf8');
}
fs.writeFileSync(OUT_CASES, JSON.stringify(corpus, null, 2) + '\n', 'utf8');

/* DMN-фикстура (для business rule task) — та же, что в тестах движка, но лежит
 * рядом с корпусом, чтобы обе стороны читали ОДИН файл. */
const DMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_discount" name="discount" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
  <decision id="discount" name="Discount">
    <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
      <input id="Input_1" label="Category">
        <inputExpression id="InputExpression_1" typeRef="string" expressionLanguage="feel">
          <text>category</text>
        </inputExpression>
      </input>
      <output id="Output_1" label="Discount" name="discount" typeRef="number" />
      <rule id="Rule_gold">
        <inputEntry id="In_gold"><text>"gold"</text></inputEntry>
        <outputEntry id="Out_gold"><text>20</text></outputEntry>
      </rule>
      <rule id="Rule_silver">
        <inputEntry id="In_silver"><text>"silver"</text></inputEntry>
        <outputEntry id="Out_silver"><text>10</text></outputEntry>
      </rule>
      <rule id="Rule_default">
        <inputEntry id="In_default"><text></text></inputEntry>
        <outputEntry id="Out_default"><text>0</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
`;
fs.writeFileSync(path.join(OUT_DIR, 'discount.dmn'), DMN, 'utf8');

console.log(`WROTE ${cases.length} BPMN files -> ${OUT_DIR}`);
console.log(`WROTE corpus -> ${OUT_CASES}`);
