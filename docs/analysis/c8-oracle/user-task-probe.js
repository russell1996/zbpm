#!/usr/bin/env node
/* WO-OPS-27 — проба: что Zeebe 8.5.0 ДЕЛАЕТ с <bpmn:userTask>.
 *
 * Переснимает факт, который в отчёте был сформулирован неверно.
 * Ключевой момент: ActivateJobs в 8.5.0 требует ТОЧНЫЙ тип job'а (пустой тип
 * отвергается: "Expected to activate job batch with type"), поэтому «не нашлось
 * job'а» в пробе со списком угаданных типов НЕ означает «Zeebe не исполняет
 * user task». Ниже перебираются все правдоподобные типы в одном прогоне, пока
 * инстанс жив.
 *
 * Варианты схемы: native (<zeebe:userTask/>), plain (без расширений),
 * taskDefinition с типами "user-task" / "io.camunda:user-task".
 *
 * Использование:
 *   node user-task-probe.js [--gw localhost:37500]
 */
'use strict';
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = __dirname;
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GW = (() => {
  const i = process.argv.indexOf('--gw');
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : (process.env.C8_ORACLE_GW || 'localhost:37500');
})();

const def = protoLoader.loadSync(PROTO, { keepCase: true, longs: String, enums: String, defaults: true, oneofs: true });
const client = new (grpc.loadPackageDefinition(def).gateway_protocol.Gateway)(GW, grpc.credentials.createInsecure());

const unary = (m, req) => new Promise((res, rej) => client[m](req, (e, r) => (e ? rej(e) : res(r))));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Активация job'ов ТОЧНОГО типа (пустой тип 8.5.0 не принимает). */
function activate(type, worker) {
  return new Promise((resolve) => {
    const jobs = []; let err = null;
    const s = client.ActivateJobs({ type, worker, timeout: 5000, maxJobsToActivate: 20, requestTimeout: 3000 });
    s.on('data', (r) => { for (const j of r.jobs || []) jobs.push(j); });
    s.on('error', (e) => { err = e; resolve({ jobs, err }); });
    s.on('end', () => resolve({ jobs, err }));
  });
}

const UT_VARIANTS = {
  native: '      <bpmn:userTask id="ut" name="ut">\n        <bpmn:extensionElements>\n          <zeebe:userTask />\n        </bpmn:extensionElements>\n      </bpmn:userTask>\n',
  plain: '      <bpmn:userTask id="ut" name="ut" />\n',
  td_user_task: '      <bpmn:userTask id="ut" name="ut">\n        <bpmn:extensionElements>\n          <zeebe:taskDefinition type="user-task" />\n        </bpmn:extensionElements>\n      </bpmn:userTask>\n',
  td_io_camunda: '      <bpmn:userTask id="ut" name="ut">\n        <bpmn:extensionElements>\n          <zeebe:taskDefinition type="io.camunda:user-task" />\n        </bpmn:extensionElements>\n      </bpmn:userTask>\n',
};

/** Типы, которые проба перебирает. */
const CANDIDATE_TYPES = [
  'io.camunda.zeebe:userTask', 'io.camunda.zeebe:user-task', 'io.camunda:user-task',
  'user-task', 'userTask', 'camunda:user-task', 'zeebe:userTask',
];

function bpmn(variant, id) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="defs_${id}" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="${id}" name="${id}" isExecutable="true">
    <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="ut" />
${UT_VARIANTS[variant]}    <bpmn:sequenceFlow id="f2" sourceRef="ut" targetRef="dump" />
    <bpmn:serviceTask id="dump" name="dump">
      <bpmn:extensionElements><zeebe:taskDefinition type="dump" /></bpmn:extensionElements>
      <bpmn:incoming>f2</bpmn:incoming>
    </bpmn:serviceTask>
    <bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end" />
    <bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
  </bpmn:process>
</bpmn:definitions>`;
}

(async () => {
  const topo = await unary('Topology', {});
  console.log(`GW=${GW} brokers=${topo.brokers.length} gw=${topo.gatewayVersion}`);
  console.log('(ActivateJobs с пустым типом в 8.5.0 отвергается — "Expected to activate job batch with type")');

  for (const variant of Object.keys(UT_VARIANTS)) {
    const id = `probe_${variant}`;
    const res = await unary('DeployResource', {
      resources: [{ name: `${id}.bpmn`, content: Buffer.from(bpmn(variant, id), 'utf8') }],
      tenantId: '',
    });
    const proc = (res.deployments || [])[0] && res.deployments[0].process;
    const pi = await unary('CreateProcessInstance', {
      bpmnProcessId: proc.bpmnProcessId, version: Number(proc.version), variables: JSON.stringify({ x: 1 }),
    });
    await sleep(2000);

    let found = null;
    for (const t of CANDIDATE_TYPES) {
      const r = await activate(t, `probe-${variant}`);
      if (r.jobs.length) {
        found = { type: t, job: r.jobs.find((j) => j.elementId === 'ut') || r.jobs[0] };
        break;
      }
    }
    if (!found) {
      console.log(`VARIANT ${variant}: pi=${pi.processInstanceKey} → ни один тип не дал job'а (${CANDIDATE_TYPES.length} типов перебрано)`);
    } else {
      console.log(`VARIANT ${variant}: pi=${pi.processInstanceKey} → job типа "${found.type}" (elementId=${found.job.elementId})`);
      await unary('CompleteJob', { jobKey: found.job.key, variables: JSON.stringify({ done: true }) });
      await sleep(2000);
      const dump = await activate('dump', `probe-${variant}-after`);
      const dumpJob = dump.jobs.find((j) => j.elementId === 'dump');
      console.log(`  после CompleteJob: следующий элемент создан = ${!!dumpJob}${dumpJob ? ' (dump-job активирован)' : ''}`);
    }
    await unary('CancelProcessInstance', { processInstanceKey: String(pi.processInstanceKey) }).catch(() => {});
  }
  process.exit(0);
})().catch((e) => { console.error('PROBE FAILED:', (e && e.stack) || e); process.exit(1); });
