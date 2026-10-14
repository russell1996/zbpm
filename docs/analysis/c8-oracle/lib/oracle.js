/* Self-contained raw-gRPC Zeebe oracle client (WO-OPS-30 F0).
 *
 * Why raw gRPC instead of @camunda8/sdk's typed client: the 8.5.9 SDK's lossless
 * JSON parser crashes on job.variables containing arrays with null
 * (e.g. a pre-created outputCollection [null,null,null]):
 *   "An unsafe number value was received for results ... Cannot read properties of null".
 * Raw string variables are also more faithful for an oracle log (no re-serialization drift).
 *
 * Self-contained: @grpc/* and the gateway proto are resolved from THIS directory's
 * node_modules (installed via `npm ci` from the local package-lock.json). No absolute
 * paths to temp/scratch directories — the old runner pointed at a /tmp scratchpad that no
 * longer exists, which broke it.
 *
 * The gateway host defaults to localhost:36500 (the stack in stack/docker-compose.yml)
 * and can be overridden with C8_ORACLE_GW=<host:port>.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

// __dirname = docs/analysis/c8-oracle/lib ; node_modules sits one level up (docs/analysis/c8-oracle).
const ROOT = path.join(__dirname, '..');
const PROTO = path.join(ROOT, 'node_modules', '@camunda8/sdk', 'dist', 'proto', 'zeebe.proto');
const GATEWAY_HOST = process.env.C8_ORACLE_GW || 'localhost:36500';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Build an insecure Gateway client for the given host:port (defaults to C8_ORACLE_GW/localhost:36500). */
function buildClient(host = GATEWAY_HOST) {
  const def = protoLoader.loadSync(PROTO, {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true,
  });
  return new (grpc.loadPackageDefinition(def).gateway_protocol.Gateway)(
    host,
    grpc.credentials.createInsecure(),
  );
}

/** Single unary gRPC call, promise-wrapped. */
function unary(client, method, req) {
  return new Promise((resolve, reject) => {
    client[method](req, (err, resp) => (err ? reject(err) : resolve(resp)));
  });
}

/** Topology — used as a connectivity/health probe (rejects if the gateway is down). */
async function topology(client) {
  return unary(client, 'Topology', {});
}

/**
 * Activate jobs of one type (server-streaming call), collecting them into an array.
 * `worker` is fixed to 'c8-oracle'; a 30s activation timeout is enough for these scenarios.
 */
function activate(client, type, max = 100) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'c8-oracle',
      timeout: '30000',
      maxJobsToActivate: max,
      requestTimeout: '3000',
    });
    const jobs = [];
    call.on('data', (resp) => {
      for (const j of resp.jobs || []) jobs.push(j);
    });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

/** Activate several types at once (convenience for the compare.js driver). */
async function activateMany(client, types, max = 100) {
  const out = [];
  for (const t of types) out.push(...(await activate(client, t, max)));
  return out;
}

/** Deploy a BPMN file (absolute path) to the broker; logs the resulting process ids/versions. */
async function deploy(client, absPath) {
  const name = path.basename(absPath);
  const content = fs.readFileSync(absPath);
  const res = await unary(client, 'DeployResource', {
    resources: [{ name, content }],
    tenantId: '',
  });
  const procs = (res.deployments || [])
    .map((d) => `${d.process?.bpmnProcessId} v${d.process?.version} key=${d.process?.processDefinitionKey}`)
    .join(', ');
  console.log(`DEPLOY ${name} -> ${procs}`);
  return res;
}

/** Start a process instance with JSON variables (default `{}`); returns the piKey as a string. */
async function start(client, bpmnProcessId, variables = {}) {
  const res = await unary(client, 'CreateProcessInstance', {
    bpmnProcessId,
    version: -1,
    variables: JSON.stringify(variables || {}),
  });
  console.log(`START ${bpmnProcessId} -> piKey=${res.processInstanceKey}`);
  return String(res.processInstanceKey);
}

/** Cancel a process instance. */
async function cancelPi(client, piKey) {
  await unary(client, 'CancelProcessInstance', { processInstanceKey: String(piKey) });
  console.log(`CANCEL piKey=${piKey}`);
}

/**
 * Poll a job type until at least `minCount` jobs (optionally for one pi) are active,
 * retrying up to `tries` times with 500ms gaps. Returns whatever is present on the last try.
 */
async function poll(client, type, minCount, tries = 14, piKey) {
  for (let i = 0; i < tries; i++) {
    let jobs = await activate(client, type);
    if (piKey) jobs = jobs.filter((j) => String(j.processInstanceKey) === String(piKey));
    if (jobs.length >= minCount) return jobs;
    await sleep(500);
  }
  let jobs = await activate(client, type);
  if (piKey) jobs = jobs.filter((j) => String(j.processInstanceKey) === String(piKey));
  return jobs;
}

/** Complete a job with JSON variables; logs the call. */
async function complete(client, job, variables) {
  await unary(client, 'CompleteJob', {
    jobKey: job.key,
    variables: JSON.stringify(variables || {}),
  });
  console.log(`COMPLETE key=${job.key} type=${job.type} vars=${JSON.stringify(variables || {})}`);
}

/** Human-readable dump of a job batch (type/elementId/pi/vars) for oracle logs. */
function show(tag, jobs) {
  console.log(`--- ${tag}: ${jobs.length} job(s) ---`);
  for (const j of jobs) {
    console.log(
      `key=${j.key} type=${j.type} elementId=${j.elementId} piKey=${j.processInstanceKey} vars=${j.variables}`,
    );
  }
}

/** Best-effort extraction of the `"item":"..."` value from a raw job-variables string. */
function itemOf(j) {
  const m = /"item":"([^"]*)"/.exec(j.variables || '');
  return m ? m[1] : '?';
}

/** Parse a raw job-variables JSON string safely (returns {} on garbage). */
function parseVars(j) {
  try {
    return JSON.parse(j.variables || '{}');
  } catch (e) {
    return {};
  }
}

module.exports = {
  ROOT,
  PROTO,
  GATEWAY_HOST,
  sleep,
  buildClient,
  unary,
  topology,
  activate,
  activateMany,
  deploy,
  start,
  cancelPi,
  poll,
  complete,
  show,
  itemOf,
  parseVars,
};
