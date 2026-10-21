/* WO-OPS-30 F2b — the LIVE side of the HTTP-connector conformance measurement.
 *
 * Drives the real Camunda 8.5 stand (camunda/zeebe:8.5.0 + camunda/connectors-bundle:8.5.9):
 * deploys a BPMN whose service task has type io.camunda:http-json:1, lets the REAL
 * connector worker do the HTTP call against the shared mock, and observes what it did.
 *
 * The case list is the single source of truth (zorrobpm-http-connector/src/test/resources/
 * http-connector-cases.json) — the very same file our own JUnit conformance test reads.
 * The BPMN is GENERATED from that list, so the live engine and ours can never be measured
 * against different models (the F2a discipline).
 *
 * Usage:
 *   node http-connector.js                # run every case
 *   node http-connector.js f2b-get-200-json
 *   node http-connector.js --sync          # (re)write the per-case scenario BPMN files
 *   node http-connector.js --matrix        # also append the matrix lines to MATRIX.md
 *
 * Env:
 *   C8_ORACLE_GW      gateway host:port (default localhost:36500)
 *   F2B_MOCK_BASE     mock base URL used in the BPMN (default from the case list)
 */
'use strict';
const fs = require('fs');
const path = require('path');
const oracle = require('./lib/oracle');

const ROOT = path.join(__dirname, '..', '..', '..');
const CASE_FILE = path.join(ROOT, 'zorrobpm-http-connector', 'src', 'test', 'resources',
  'http-connector-cases.json');
const SCENARIO_DIR = path.join(__dirname, 'scenarios');

const caseList = JSON.parse(fs.readFileSync(CASE_FILE, 'utf8'));
const MOCK_BASE = process.env.F2B_MOCK_BASE || caseList.mockBase;
/**
 * Where the RUNNER reads the mock's request log from. The BPMN talks to the mock by its
 * docker name (zbpm-cmp-mock) — that name resolves only inside the oracle network, while
 * the runner runs on the host. The mock publishes the same port on 127.0.0.1 for that.
 */
const MOCK_OBSERVE = process.env.F2B_MOCK_OBSERVE || 'http://127.0.0.1:18099';

/** Job types the driver owns (the connector's own type is io.camunda:http-json:1). */
const DUMP_TYPE = 'f2b-dump';
const ERR_DUMP_TYPE = 'f2b-errdump';
const CONNECTOR_TYPE = 'io.camunda:http-json:1';

/** How long we wait for the connector to finish the job before calling it a failure. */
const AFTER_REQUEST_GRACE_MS = 8000;
const TOTAL_TIMEOUT_MS = 30000;

/* ---------------------------------------------------------------- helpers */

/**
 * FEEL string literal. The FEEL engine (Camunda/Scala FEEL) escapes an embedded
 * double quote with a BACKSLASH — doubling it ("") is not FEEL and the expression
 * fails to parse (measured: 'Expected ... found "\"temp\"\":25'').
 */
function feelString(value) {
  return '"' + String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

/** FEEL map literal: { "k": "v", ... } (keys are quoted, values are FEEL strings). */
function feelMap(obj) {
  const entries = Object.keys(obj).map((k) => `${feelString(k)}: ${feelString(obj[k])}`);
  return `{ ${entries.join(', ')} }`;
}

/** Variables that carry a NUMBER in the Camunda binding (timeouts). */
const NUMERIC_INPUTS = new Set(['connectionTimeoutInSeconds', 'readTimeoutInSeconds']);
/** Variables that carry a BOOLEAN in the Camunda binding. */
const BOOLEAN_INPUTS = new Set(['followRedirects', 'storeResponse', 'ignoreNullValues']);
/** Variables that carry a MAP in the Camunda binding (FEEL map). */
const MAP_INPUTS = new Set(['headers', 'queryParameters']);

function ioInput(name, value) {
  if (MAP_INPUTS.has(name)) {
    return `<zeebe:input source="= ${xmlEscape(feelMap(value))}" target="${xmlEscape(name)}" />`;
  }
  if (NUMERIC_INPUTS.has(name)) {
    return `<zeebe:input source="= ${xmlEscape(String(Number(value)))}" target="${xmlEscape(name)}" />`;
  }
  if (BOOLEAN_INPUTS.has(name)) {
    const b = String(value).toLowerCase() === 'true';
    return `<zeebe:input source="= ${b}" target="${xmlEscape(name)}" />`;
  }
  return `<zeebe:input source="= ${xmlEscape(feelString(value))}" target="${xmlEscape(name)}" />`;
}

function xmlEscape(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function processIdFor(caseId) {
  return 'f2b_' + caseId.replace(/-/g, '_');
}

/**
 * The {MOCK} placeholder in the case list is replaced by the base URL of the mock
 * this side talks to. The URL is the ONLY thing that differs between the two
 * sides in the campaign run (both resolve zbpm-cmp-mock by name), so the request
 * that arrives at the mock is byte-identical.
 */
function substMock(value) {
  if (typeof value === 'string') {
    return value.split('{MOCK}').join(MOCK_BASE);
  }
  if (Array.isArray(value)) {
    return value.map(substMock);
  }
  if (value && typeof value === 'object') {
    const out = {};
    Object.keys(value).forEach((k) => {
      out[k] = substMock(value[k]);
    });
    return out;
  }
  return value;
}

/** The BPMN for one case: service task (io.camunda:http-json:1) -> dump job, + error boundary. */
function buildBpmn(c) {
  const variables = substMock(c.job.variables || {});
  const inputs = Object.keys(variables)
    .sort()
    .map((name) => ioInput(name, variables[name]))
    .join('\n        ');
  const headers = c.job.taskHeaders || {};
  const headerXml = Object.keys(headers)
    .sort()
    .map((k) => `        <zeebe:header key="${xmlEscape(k)}" value="${xmlEscape(headers[k])}" />`)
    .join('\n');
  const pid = processIdFor(c.id);
  const ioMapping = inputs
    ? `      <zeebe:ioMapping>\n        ${inputs}\n      </zeebe:ioMapping>`
    : '';

  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                  id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="${pid}" isExecutable="true">
    <bpmn:startEvent id="start">
      <bpmn:outgoing>toCall</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:sequenceFlow id="toCall" sourceRef="start" targetRef="call" />
    <bpmn:serviceTask id="call" name="http-json">
      <bpmn:extensionElements>
        <zeebe:taskDefinition type="${xmlEscape(c.job.type || CONNECTOR_TYPE)}" retries="1" />
${headerXml ? `        <zeebe:taskHeaders>\n${headerXml}\n        </zeebe:taskHeaders>\n` : ''}${ioMapping}
      </bpmn:extensionElements>
      <bpmn:incoming>toCall</bpmn:incoming>
      <bpmn:outgoing>toDump</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:sequenceFlow id="toDump" sourceRef="call" targetRef="dump" />
    <bpmn:serviceTask id="dump">
      <bpmn:extensionElements>
        <zeebe:taskDefinition type="${DUMP_TYPE}" retries="3" />
      </bpmn:extensionElements>
      <bpmn:incoming>toDump</bpmn:incoming>
    </bpmn:serviceTask>
    <bpmn:boundaryEvent id="errBoundary" attachedToRef="call">
      <bpmn:outgoing>toErrDump</bpmn:outgoing>
      <bpmn:errorEventDefinition />
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="toErrDump" sourceRef="errBoundary" targetRef="errDump" />
    <bpmn:serviceTask id="errDump">
      <bpmn:extensionElements>
        <zeebe:taskDefinition type="${ERR_DUMP_TYPE}" retries="3" />
      </bpmn:extensionElements>
      <bpmn:incoming>toErrDump</bpmn:incoming>
    </bpmn:serviceTask>
  </bpmn:process>
</bpmn:definitions>
`;
}

/** The mock's view of the requests it received for one case id. */
async function mockRequests(mockBase, caseId) {
  try {
    const res = await fetch(`${mockBase}/__requests`);
    const all = await res.json();
    return all.filter((r) => r.case === caseId);
  } catch (e) {
    return [];
  }
}

async function mockReset(mockBase) {
  try {
    await fetch(`${mockBase}/__reset`, { method: 'POST' });
  } catch (e) {
    /* the mock may be down — the case then has no request observations */
  }
}

/* ------------------------------------------------------------ measurement */

/**
 * Run one case against the live stand.
 * Returns the observation: { outcome, request, response, requestsSeen, ... }.
 */
async function runCase(client, c, mockBase) {
  const bpmn = buildBpmn(c);
  const scenarioDir = path.join(SCENARIO_DIR, c.id);
  fs.mkdirSync(scenarioDir, { recursive: true });
  const bpmnPath = path.join(scenarioDir, 'process.bpmn');
  fs.writeFileSync(bpmnPath, bpmn);

  await mockReset(MOCK_OBSERVE);
  await oracle.deploy(client, bpmnPath);
  const piKey = await oracle.start(client, processIdFor(c.id), {});

  const started = Date.now();
  let requestSeenAt = null;
  let observation = null;

  for (;;) {
    /* 1) the connector completed the job -> the dump job exists */
    const dumps = await oracle.poll(client, DUMP_TYPE, 1, 1, piKey);
    if (dumps.length > 0) {
      const vars = oracle.parseVars(dumps[0]);
      observation = {
        outcome: 'completed',
        response: vars,
        requestsSeen: (await mockRequests(MOCK_OBSERVE, c.id)).length,
      };
      await oracle.complete(client, dumps[0], {});
      break;
    }
    /* 2) the connector threw a BPMN error -> the boundary caught it */
    const errDumps = await oracle.poll(client, ERR_DUMP_TYPE, 1, 1, piKey);
    if (errDumps.length > 0) {
      const vars = oracle.parseVars(errDumps[0]);
      observation = {
        outcome: 'bpmnError',
        errorVars: vars,
        requestsSeen: (await mockRequests(MOCK_OBSERVE, c.id)).length,
      };
      await oracle.complete(client, errDumps[0], {});
      break;
    }
    /* 3) did the connector make the HTTP call at all? */
    const seen = await mockRequests(MOCK_OBSERVE, c.id);
    if (seen.length > 0 && requestSeenAt === null) {
      requestSeenAt = Date.now();
    }
    if (requestSeenAt !== null && Date.now() - requestSeenAt > AFTER_REQUEST_GRACE_MS) {
      observation = {
        outcome: 'failed',
        requestsSeen: seen.length,
        request: seen[0],
      };
      break;
    }
    if (Date.now() - started > TOTAL_TIMEOUT_MS) {
      observation = {
        outcome: seen.length > 0 ? 'failed' : 'noHttpCall',
        requestsSeen: seen.length,
        request: seen[0],
        note: 'observation window exhausted',
      };
      break;
    }
    await oracle.sleep(400);
  }

  const requests = await mockRequests(MOCK_OBSERVE, c.id);
  observation.requests = requests.map((r) => ({
    method: r.method,
    path: r.path,
    query: r.query,
    headers: r.headers,
    body: r.body,
    ts: r.ts,
  }));
  if (!observation.request && requests.length > 0) {
    observation.request = requests[0];
  }

  /* Clean up: the instance may still be alive (the failing cases leave it there). */
  try {
    await oracle.cancelPi(client, piKey);
  } catch (e) {
    /* already finished */
  }
  return observation;
}

/* -------------------------------------------------------------- verdicts */

function checkRequest(expected, actual) {
  if (!expected) {
    return { checked: false, ok: true };
  }
  if (expected === false) {
    return { checked: true, ok: !actual, detail: 'no request expected' };
  }
  const problems = [];
  if (expected.method && (!actual || actual.method !== expected.method)) {
    problems.push(`method ${actual ? actual.method : 'none'} != ${expected.method}`);
  }
  if (expected.path && (!actual || actual.path !== expected.path)) {
    problems.push(`path ${actual ? actual.path : 'none'} != ${expected.path}`);
  }
  if (expected.query) {
    const q = (actual && actual.query) || {};
    for (const key of Object.keys(expected.query)) {
      if (String(q[key]) !== String(expected.query[key])) {
        problems.push(`query ${key}=${q[key]} != ${expected.query[key]}`);
      }
    }
  }
  if (expected.headers) {
    const h = (actual && actual.headers) || {};
    for (const key of Object.keys(expected.headers)) {
      if (String(h[key]) !== String(expected.headers[key])) {
        problems.push(`header ${key}=${h[key]} != ${expected.headers[key]}`);
      }
    }
  }
  if (expected.authorization) {
    const h = (actual && actual.headers) || {};
    if (h.authorization !== expected.authorization) {
      problems.push(`authorization ${h.authorization} != ${expected.authorization}`);
    }
  }
  if (expected.headerXApiKey) {
    const h = (actual && actual.headers) || {};
    const got = h['x-api-key'];
    if (got !== expected.headerXApiKey) {
      problems.push(`x-api-key ${got} != ${expected.headerXApiKey}`);
    }
  }
  if (expected.body !== undefined && expected.body !== null) {
    const b = actual ? actual.body : '';
    if (b !== expected.body) {
      problems.push(`body ${JSON.stringify(b)} != ${JSON.stringify(expected.body)}`);
    }
  }
  if (expected.bodyContains) {
    const b = actual ? actual.body : '';
    if (!b.includes(expected.bodyContains)) {
      problems.push(`body does not contain ${expected.bodyContains}`);
    }
  }
  return { checked: true, ok: problems.length === 0, detail: problems.join('; ') };
}

function checkExpectation(expectRoot, observation) {
  /* expectRoot = expect всего кейса: live-ожидания лежат в expect.live,
   * ожидания ЗАПРОСА — на уровне expect (общие для обеих сторон) либо
   * expect.requestLive (только живая сторона). Путать эти два уровня нельзя:
   * при неверном уровне проверки молча не выполняются вовсе. */
  const expect = expectRoot.live || {};
  const problems = [];
  if (expect.outcome && observation.outcome !== expect.outcome) {
    problems.push(`outcome ${observation.outcome} != ${expect.outcome}`);
  }
  if (expect.responseStatus !== undefined) {
    const vars = observation.response || {};
    const resp = vars.response || {};
    if (resp.status !== undefined && resp.status !== expect.responseStatus) {
      problems.push(`response.status ${resp.status} != ${expect.responseStatus}`);
    }
  }
  if (expect.responseBodyContains) {
    const vars = observation.response || {};
    const resp = vars.response || {};
    const body = typeof resp.body === 'string' ? resp.body : JSON.stringify(resp.body || '');
    if (!body.includes(expect.responseBodyContains)) {
      problems.push(`response body does not contain ${expect.responseBodyContains}`);
    }
  }
  if (expect.responseVarName) {
    const vars = observation.response || {};
    if (!(expect.responseVarName in vars)) {
      problems.push(`variable ${expect.responseVarName} missing`);
    }
  }
  if (expect.variableProbe) {
    const vars = observation.response || {};
    if (vars.probe !== expect.variableProbe) {
      problems.push(`variable probe=${vars.probe} != ${expect.variableProbe}`);
    }
  }
  if (expect.responseHeader) {
    const vars = observation.response || {};
    const resp = vars.response || {};
    const headers = resp.headers || {};
    const found = Object.keys(headers).some((k) => k.toLowerCase() === expect.responseHeader.toLowerCase());
    if (!found) {
      problems.push(`response header ${expect.responseHeader} missing`);
    }
  }
  /* Авторизация — проверяется по тому, что СЕРВЕР УВИДЕЛ (журнал мока), а не по догадке:
   * ожидание лежит на уровне expect.live, а не внутри expect.request. */
  if (expect.authorization) {
    const h = (observation.request && observation.request.headers) || {};
    if (h.authorization !== expect.authorization) {
      problems.push(`authorization ${h.authorization} != ${expect.authorization}`);
    }
  }
  if (expect.headerXApiKey) {
    const h = (observation.request && observation.request.headers) || {};
    if (h['x-api-key'] !== expect.headerXApiKey) {
      problems.push(`x-api-key ${h['x-api-key']} != ${expect.headerXApiKey}`);
    }
  }
  const requestExpectation = expectRoot.requestLive !== undefined
    ? expectRoot.requestLive
    : expectRoot.request;
  const req = checkRequest(requestExpectation, observation.request);
  if (req.checked && !req.ok) {
    problems.push(`request: ${req.detail}`);
  }
  return problems;
}

/* ------------------------------------------------------------------ main */

async function main() {
  const args = process.argv.slice(2);
  const only = args.filter((a) => !a.startsWith('--'));
  const client = oracle.buildClient();

  await oracle.topology(client); // connectivity probe — throws if the gateway is down

  const cases = caseList.cases.filter((c) => (only.length === 0 ? true : only.includes(c.id)));
  const results = [];

  for (const c of cases) {
    process.stdout.write(`\n=== ${c.id} — ${c.title}\n`);
    let observation;
    try {
      observation = await runCase(client, c, MOCK_BASE);
    } catch (e) {
      observation = { outcome: 'error', error: String(e && e.message ? e.message : e) };
    }
    const problems = observation.outcome === 'error'
      ? ['runner error']
      : checkExpectation(c.expect, observation);
    const verdict = observation.outcome === 'error' ? 'ERROR'
      : (problems.length === 0 ? 'match' : 'DIFFERS');
    console.log(`  observed: ${JSON.stringify({
      outcome: observation.outcome,
      requestsSeen: observation.requestsSeen,
      request: observation.request ? { method: observation.request.method, path: observation.request.path, query: observation.request.query, body: observation.request.body } : null,
      response: observation.response,
      errorVars: observation.errorVars,
    })}`);
    console.log(`  expected: ${JSON.stringify(c.expect.live)}`);
    console.log(`  verdict : ${verdict}${problems.length ? ' — ' + problems.join('; ') : ''}`);
    if (observation.error) {
      console.log(`  error   : ${observation.error}`);
    }

    const scenarioDir = path.join(SCENARIO_DIR, c.id);
    fs.mkdirSync(scenarioDir, { recursive: true });
    fs.writeFileSync(path.join(scenarioDir, 'result-8.5.json'), JSON.stringify({
      id: c.id,
      title: c.title,
      docs89: c.docs89,
      expectation: c.expect.live,
      observation,
      problems,
      verdict,
      measuredAt: new Date().toISOString(),
    }, null, 2));
    results.push({ id: c.id, verdict, problems, observation });
  }

  const diffs = results.filter((r) => r.verdict !== 'match');
  console.log(`\n--- SUMMARY: ${results.length} case(s), ${results.length - diffs.length} match, ${diffs.length} differ(s)`);
  for (const d of diffs) {
    console.log(`    ${d.id}: ${d.verdict} — ${d.problems.join('; ')}`);
  }
}

main().catch((e) => {
  console.error('F2b oracle failed:', e);
  process.exit(1);
});
