#!/usr/bin/env node
/* WO-OPS-28 ФАЗА 2 — проба диалекта <bpmn:message> для Zeebe 8.5.
 *
 * Zeebe 8.5 требует у определения сообщения ровно один zeebe:subscription.
 * Проба перебирает варианты и печатает, какой принимает брокер.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const ROOT = path.join(__dirname);
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GW = process.env.C8_ORACLE_GW || 'localhost:37500';

function buildClient(host) {
  const def = protoLoader.loadSync(PROTO, {
    keepCase: true, longs: String, enums: String, defaults: true, oneofs: true,
  });
  return new (grpc.loadPackageDefinition(def).gateway_protocol.Gateway)(
    host, grpc.credentials.createInsecure(),
  );
}
function unary(client, method, req) {
  return new Promise((resolve, reject) => {
    client[method](req, (err, resp) => (err ? reject(err) : resolve(resp)));
  });
}

const VARIANTS = {
  'subscription без атрибутов': '<zeebe:subscription />',
  'subscription с пустым correlationKey': '<zeebe:subscription correlationKey="" />',
  'subscription с correlationKey=key': '<zeebe:subscription correlationKey="=key" />',
  'без extensionElements': null,
};

function bpmn(messageDef) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="defs_probe" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:message id="msgA" name="msgA">${messageDef ? `<bpmn:extensionElements>${messageDef}</bpmn:extensionElements>` : ''}</bpmn:message>
  <bpmn:process id="probe_msg" isExecutable="true">
    <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="catchA" />
    <bpmn:intermediateCatchEvent id="catchA">
      <bpmn:incoming>f1</bpmn:incoming>
      <bpmn:outgoing>f2</bpmn:outgoing>
      <bpmn:messageEventDefinition id="medA" messageRef="msgA" />
    </bpmn:intermediateCatchEvent>
    <bpmn:sequenceFlow id="f2" sourceRef="catchA" targetRef="end" />
    <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
  </bpmn:process>
</bpmn:definitions>
`;
}

(async () => {
  const client = buildClient(GW);
  for (const [name, def] of Object.entries(VARIANTS)) {
    try {
      const res = await unary(client, 'DeployResource', {
        resources: [{ name: `probe-${name.replace(/\s+/g, '-')}.bpmn`, content: Buffer.from(bpmn(def), 'utf8') }],
        tenantId: '',
      });
      const proc = (res.deployments || [])[0] && res.deployments[0].process;
      console.log(`OK    ${name} -> bpmnProcessId=${proc && proc.bpmnProcessId}`);
    } catch (e) {
      console.log(`FAIL  ${name} -> ${String(e.message).split('\n').slice(0, 3).join(' | ')}`);
    }
  }
})().catch((e) => { console.error(e); process.exit(1); });
