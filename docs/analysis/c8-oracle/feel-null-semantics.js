/* WO-C8-54 — null/missing/unknown/incomparable semantics on the live Zeebe 8.5.0 oracle.
 *
 * Two questions the F1 matrix could not answer (G-Q, WO-C8-54 item 3):
 *
 *   (A) CONDITIONS — what does Zeebe 8.5 do when a gateway condition evaluates to
 *       null (missing variable, unknown function, incomparable types, explicit null)?
 *       Taken default flow? Incident? Both with and without a <default> flow.
 *       (Routing must not change silently — the engine must not turn a null
 *       condition into "true".)
 *
 *   (B) PROD CORPUS — the FEEL expressions that actually occur in the production
 *       definitions (docs/internal). For every
 *       expression that can evaluate to null we record the live 8.5 value, so the
 *       "ВЛИЯНИЕ НА ПРОД" section of the report rests on a measurement, not on reading.
 *
 * Writes scenarios/feel-null-semantics/result-8.5.json.
 *
 * Usage: node feel-null-semantics.js
 */
'use strict';
const path = require('path');
const fs = require('fs');
const {
  ROOT, buildClient, unary, topology, start, cancelPi, complete, sleep, parseVars,
} = require('./lib/oracle');

const OUT_DIR = path.join(ROOT, 'scenarios', 'feel-null-semantics');
const OUT_PATH = path.join(OUT_DIR, 'result-8.5.json');

const esc = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;');

/* ── (A) condition probes ─────────────────────────────────────────────────── */

const CONDITION_PROBES = [
  { id: 'cond-missing-var', expr: 'nope', why: 'variable absent from the context' },
  { id: 'cond-missing-compare', expr: 'nope = "x"', why: 'comparison against a missing variable' },
  { id: 'cond-not-comparable', expr: '1 < "2"', why: 'incomparable types' },
  { id: 'cond-unknown-function', expr: 'round(2.5)', why: 'unknown function' },
  { id: 'cond-explicit-null', expr: 'null', why: 'explicit null literal' },
  { id: 'cond-true', expr: 'true', why: 'control: a true condition takes its own flow' },
];

function conditionBpmn(pid, expr, withDefault) {
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + `id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">`;
  const body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
    + `<bpmn:exclusiveGateway id="gw"${withDefault ? ' default="flowB"' : ''}>`
    + '<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>flowA</bpmn:outgoing>'
    + (withDefault ? '<bpmn:outgoing>flowB</bpmn:outgoing>' : '')
    + '</bpmn:exclusiveGateway>'
    + '<bpmn:serviceTask id="taskA" name="taskA">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="feelCondA"/></bpmn:extensionElements>'
    + '<bpmn:incoming>flowA</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>'
    + (withDefault
      ? '<bpmn:serviceTask id="taskB" name="taskB">'
        + '<bpmn:extensionElements><zeebe:taskDefinition type="feelCondB"/></bpmn:extensionElements>'
        + '<bpmn:incoming>flowB</bpmn:incoming><bpmn:outgoing>f4</bpmn:outgoing></bpmn:serviceTask>'
      : '')
    + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming>'
    + (withDefault ? '<bpmn:incoming>f4</bpmn:incoming>' : '')
    + '</bpmn:endEvent>'
    + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>'
    + '<bpmn:sequenceFlow id="flowA" sourceRef="gw" targetRef="taskA">'
    // Zeebe 8.5 REQUIRES the '=' prefix on a condition expression
    // ("Expected expression but found static value ... An expression must start with '='").
    + `<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=${esc(expr)}</bpmn:conditionExpression>`
    + '</bpmn:sequenceFlow>'
    + (withDefault
      ? '<bpmn:sequenceFlow id="flowB" sourceRef="gw" targetRef="taskB"/>' : '')
    + '<bpmn:sequenceFlow id="f3" sourceRef="taskA" targetRef="end"/>'
    + (withDefault ? '<bpmn:sequenceFlow id="f4" sourceRef="taskB" targetRef="end"/>' : '');
  return defs + `<bpmn:process id="${pid}" name="${pid}" isExecutable="true">${body}</bpmn:process></bpmn:definitions>`;
}

/* ── (B) prod-corpus io-mapping probes ────────────────────────────────────── */

/* The expressions of docs/internal that can
 * evaluate to null (missing variable / unknown function / null path). The context
 * is EMPTY on purpose — that is the "the variable is not there" scenario, the only
 * one where null semantics bite. */
const CORPUS_PROBES = [
  { id: 'corpus-default-silenceFacts', expr: 'default(silenceFacts, [])', from: 'output.source — approvalprocess' },
  { id: 'corpus-silenceFacts', expr: 'silenceFacts', from: 'output.source — outgoingLetterPreparationProcess' },
  { id: 'corpus-activity-employeeId', expr: 'activity.employeeId', from: 'assignee/input.source — testApprovalRouteProcess' },
  { id: 'corpus-activity-uuid', expr: 'activity.uuid', from: 'input.source — testApprovalRouteProcess' },
  { id: 'corpus-el-id', expr: 'el.id', from: 'assignee — miCollProbe/miFromJsonProbe' },
  { id: 'corpus-comment', expr: 'comment', from: 'input.source — testApprovalRouteProcess' },
  { id: 'corpus-deadlineDate', expr: 'deadlineDate', from: 'input.source — incomingCorrespondenceProcess' },
  { id: 'corpus-restartReason', expr: 'restartReason', from: 'input.source — approvalRouteProcess' },
  { id: 'corpus-sourceVersion-plus', expr: 'sourceVersion + 1', from: 'input.source — testApprovalRouteProcess' },
  { id: 'corpus-attachments', expr: 'attachments', from: 'input.source — incomingCorrespondenceProcess' },
  { id: 'corpus-content', expr: 'content', from: 'input.source — incomingCorrespondenceProcess' },
  { id: 'corpus-executors', expr: 'executors', from: 'input.source — incomingCorrespondenceProcess' },
  { id: 'corpus-stageList', expr: 'stageList', from: 'input.source — outgoingLetterPreparationProcess' },
  { id: 'corpus-stages', expr: 'stages', from: 'input.source — testApprovalRouteProcess' },
  { id: 'corpus-stageApprovers', expr: 'stageApprovers', from: 'mi.inputCollection — approvalprocess' },
  { id: 'corpus-remarkAuthors', expr: 'remarkAuthors', from: 'mi.inputCollection — approvalprocess' },
  { id: 'corpus-openActivities', expr: 'openActivities', from: 'mi.inputCollection — testApprovalRouteProcess' },
  { id: 'corpus-activityUuids', expr: 'activityUuids', from: 'mi.inputCollection — approvalRouteProcess' },
  { id: 'corpus-primaryExecutorIds', expr: 'primaryExecutorIds', from: 'mi.inputCollection — multiInstanceTestProcess' },
  { id: 'corpus-itemsStr', expr: 'itemsStr', from: 'mi.inputCollection — miCollProbe' },
  { id: 'corpus-itemsJson', expr: 'itemsJson', from: 'mi.inputCollection — miCollProbe' },
  { id: 'corpus-fromJson', expr: 'fromJson(itemsStr)', from: 'mi.inputCollection — miFromJsonProbe' },
  { id: 'corpus-for-activities', expr: 'for a in activities return a.employeeId', from: 'mi.inputCollection — probeFeelProcess' },
  { id: 'corpus-ctx-literal-missing', expr: '{correspondentName:correspondentName}', from: 'input.source — incomingCorrespondenceProcess' },
  { id: 'corpus-ctx-put-missing', expr: 'put({a: 1}, "b", missingVar)', from: 'control: context built-in with a missing value' },
  { id: 'corpus-size-missing', expr: 'size(stageApprovers)', from: 'condition operand — approvalprocess' },
  { id: 'corpus-some-missing', expr: 'some d in decisions satisfies d.decision = "REJECTED"', from: 'condition/completionCondition — testApprovalRouteProcess' },
  { id: 'corpus-split-index', expr: 'split(string(now()), "@")[1]', from: 'operand of the input.source date expression — approvalRouteProcess' },
  { id: 'corpus-http-status', expr: 'http.status', from: 'output.source — connCamunda/connNative (connector result)' },
  { id: 'corpus-activity-list', expr: 'activities', from: 'output.source — approvalRouteProcess' },
  { id: 'corpus-approved', expr: 'approved', from: 'output.source — approvalprocess' },
  { id: 'corpus-escalated', expr: 'escalated', from: 'output.source — approvalprocess' },
  { id: 'corpus-allApproved', expr: 'allApproved', from: 'output.source/condition — approvalRouteProcess' },
  { id: 'corpus-hasOpen', expr: 'hasOpen', from: 'output.source/condition — approvalRouteProcess' },
  { id: 'corpus-hasRejected', expr: 'hasRejected', from: 'output.source/condition — approvalRouteProcess' },
  { id: 'corpus-pauseResult', expr: 'pauseResult', from: 'output.source/condition — approvalRouteProcess' },
  { id: 'corpus-approvalResult', expr: 'approvalResult', from: 'output.source — outgoingLetterPreparationProcess' },
  { id: 'corpus-stageModeId', expr: 'stageModeId', from: 'output.source — approvalRouteProcess' },
  { id: 'corpus-probeId', expr: 'probeId', from: 'output.source — probeFeelProcess' },
  { id: 'corpus-resourceUuid', expr: 'resourceUuid', from: 'output.source — testApprovalRouteProcess' },
  { id: 'corpus-routeStageUuid', expr: 'routeStageUuid', from: 'output.source — approvalRouteProcess' },
  { id: 'corpus-taskUuid', expr: 'taskUuid', from: 'output.source — testApprovalRouteProcess' },
];

function corpusBpmn(pid, expr) {
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + `id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">`;
  const body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
    + '<bpmn:serviceTask id="work" name="work">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="feelCorpusWork"/>'
    + `<zeebe:ioMapping><zeebe:input source="=${esc(expr)}" target="r"/></zeebe:ioMapping>`
    + '</bpmn:extensionElements>'
    + '<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>'
    + '<bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>'
    + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="work"/>'
    + '<bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="end"/>';
  return defs + `<bpmn:process id="${pid}" name="${pid}" isExecutable="true">${body}</bpmn:process></bpmn:definitions>`;
}

/* ── (C) multi-instance inputCollection with a missing collection ──────────── */

/* G-Q item 3 names inputCollection explicitly: a missing collection must not
 * silently change routing. Observable through the MI body jobs (an MI service
 * task creates one job per collection element). */
const MI_COLLECTION_PROBES = [
  { id: 'mi-collection-missing', expr: 'nope', why: 'collection variable absent' },
  { id: 'mi-collection-null', expr: 'null', why: 'explicit null collection' },
  { id: 'mi-collection-empty', expr: '[]', why: 'control: empty collection' },
  { id: 'mi-collection-size-missing', expr: 'size(nope)', why: 'size of a missing collection' },
];

function miCollectionBpmn(pid, expr) {
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + `id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">`;
  const body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
    + '<bpmn:serviceTask id="miWork" name="miWork">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="feelMiWork"/></bpmn:extensionElements>'
    + '<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>'
    + '<bpmn:multiInstanceLoopCharacteristics>'
    + '<bpmn:extensionElements><zeebe:loopCharacteristics '
    + `inputCollection="=${esc(expr)}" inputElement="item" `
    + 'outputCollection="results" outputElement="=r"/>'
    + '</bpmn:extensionElements>'
    + '</bpmn:multiInstanceLoopCharacteristics>'
    + '</bpmn:serviceTask>'
    + '<bpmn:serviceTask id="dump" name="dump">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="feelDump"/></bpmn:extensionElements>'
    + '<bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>'
    + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
    + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="miWork"/>'
    + '<bpmn:sequenceFlow id="f2" sourceRef="miWork" targetRef="dump"/>'
    + '<bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end"/>';
  return defs + `<bpmn:process id="${pid}" name="${pid}" isExecutable="true">${body}</bpmn:process></bpmn:definitions>`;
}

async function runMiCollectionProbe(client, probe) {
  const result = { id: probe.id, expr: probe.expr, why: probe.why };
  const pid = 'c854_' + probe.id.replace(/[^a-zA-Z0-9]/g, '_');
  try {
    await deploy(client, pid, miCollectionBpmn(pid, probe.expr));
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }
  let piKey;
  try {
    piKey = await start(client, pid, {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }
  result.processInstanceKey = piKey;
  try {
    const jobs = await pollT(client, 'feelMiWork', piKey);
    result.miJobCount = jobs.length;
    if (!jobs.length) {
      result.outcome = 'noMiJob';
    } else {
      result.outcome = 'completed';
      for (const job of jobs) {
        await complete(client, job, {});
      }
    }
    // Did the flow CONTINUE past the MI body? (routing check — G-Q item 3:
    // a missing collection must not silently change the routing)
    const dumps = await pollT(client, 'feelDump', piKey);
    result.continuedToNextTask = dumps.length > 0;
    if (dumps.length) {
      result.nextTaskVariables = parseVars(dumps[0]);
      await complete(client, dumps[0], {});
    }
  } finally {
    try {
      await cancelPi(client, piKey);
    } catch (e) { /* best effort */ }
  }
  return result;
}

/* ── driver ───────────────────────────────────────────────────────────────── */

function activateT(client, type, max, requestTimeoutMs) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'c854-oracle',
      timeout: '30000',
      maxJobsToActivate: max,
      requestTimeout: String(requestTimeoutMs),
    });
    const jobs = [];
    call.on('data', (resp) => {
      for (const j of resp.jobs || []) jobs.push(j);
    });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

async function pollT(client, type, piKey, tries = 6, requestTimeoutMs = 1000) {
  for (let i = 0; i < tries; i++) {
    const jobs = (await activateT(client, type, 100, requestTimeoutMs))
      .filter((j) => String(j.processInstanceKey) === String(piKey));
    if (jobs.length) return jobs;
    await sleep(200);
  }
  return [];
}

async function deploy(client, pid, xml) {
  await unary(client, 'DeployResource', {
    resources: [{ name: pid + '.bpmn', content: Buffer.from(xml) }],
    tenantId: '',
  });
}

async function runConditionProbe(client, probe, withDefault) {
  const result = { id: `${probe.id}${withDefault ? '-with-default' : '-no-default'}`, expr: probe.expr, why: probe.why, withDefault };
  const pid = 'c854_' + result.id.replace(/[^a-zA-Z0-9]/g, '_');
  try {
    await deploy(client, pid, conditionBpmn(pid, probe.expr, withDefault));
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }
  let piKey;
  try {
    piKey = await start(client, pid, {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }
  result.processInstanceKey = piKey;
  try {
    const a = await pollT(client, 'feelCondA', piKey);
    const b = withDefault ? await pollT(client, 'feelCondB', piKey) : [];
    if (a.length) {
      result.taken = 'flowA (condition true)';
      await complete(client, a[0], {});
    } else if (b.length) {
      result.taken = 'flowB (default flow)';
      await complete(client, b[0], {});
    } else {
      result.taken = 'none — no job activated (incident / stuck)';
    }
    result.outcome = 'completed';
  } finally {
    try {
      await cancelPi(client, piKey);
    } catch (e) { /* best effort */ }
  }
  return result;
}

async function runCorpusProbe(client, probe) {
  const result = { id: probe.id, expr: probe.expr, from: probe.from };
  const pid = 'c854_' + probe.id.replace(/[^a-zA-Z0-9]/g, '_');
  try {
    await deploy(client, pid, corpusBpmn(pid, probe.expr));
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }
  let piKey;
  try {
    piKey = await start(client, pid, {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }
  result.processInstanceKey = piKey;
  try {
    const jobs = await pollT(client, 'feelCorpusWork', piKey);
    if (!jobs.length) {
      result.outcome = 'noWorkJob';
      result.value = null;
    } else {
      result.outcome = 'completed';
      result.value = parseVars(jobs[0]).r;
      result.variablesRaw = jobs[0].variables;
      await complete(client, jobs[0], {});
    }
  } finally {
    try {
      await cancelPi(client, piKey);
    } catch (e) { /* best effort */ }
  }
  return result;
}

async function main() {
  const client = buildClient();
  const topo = await topology(client);
  console.log(`CONNECTED brokers=${(topo.brokers || []).length} gw=${topo.gatewayVersion}`);
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const conditions = [];
  for (const probe of CONDITION_PROBES) {
    for (const withDefault of [true, false]) {
      const r = await runConditionProbe(client, probe, withDefault);
      conditions.push(r);
      console.log(`COND ${r.id} expr='${r.expr}' -> ${r.outcome} taken=${r.taken || r.error}`);
    }
  }

  const corpus = [];
  for (const probe of CORPUS_PROBES) {
    const r = await runCorpusProbe(client, probe);
    corpus.push(r);
    console.log(`CORPUS ${r.id} expr='${r.expr}' -> ${r.outcome} value=${JSON.stringify(r.value)} raw=${r.variablesRaw || r.error || ''}`);
  }

  const miCollections = [];
  for (const probe of MI_COLLECTION_PROBES) {
    const r = await runMiCollectionProbe(client, probe);
    miCollections.push(r);
    console.log(`MICOLL ${r.id} expr='${r.expr}' -> ${r.outcome} miJobs=${r.miJobCount}`);
  }

  const out = {
    generatedAt: new Date().toISOString(),
    oracle: 'Zeebe ' + topo.gatewayVersion,
    conditions,
    corpus,
    miCollections,
  };
  fs.writeFileSync(OUT_PATH, JSON.stringify(out, null, 2) + '\n');
  console.log(`WROTE ${OUT_PATH} (conditions=${conditions.length}, corpus=${corpus.length}, miCollections=${miCollections.length})`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
