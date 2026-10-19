/* WO-OPS-30 F1 — FEEL / ioMapping differential probe against the live Zeebe 8.5.0 oracle.
 *
 * Reads the shared case list from zorrobpm-engine/src/test/resources/feel-iomapping-cases.json,
 * generates one BPMN process per case, deploys it, starts one instance, and records what a
 * worker actually receives in job.variables:
 *   - input mappings: variables on the activated `feelWork` job;
 *   - output mappings: variables on the activated `feelDump` job (root) or `feelDumpIn`
 *     (inside a normal subprocess) plus the root dump after the subprocess ends;
 *   - multi-instance: every activated `feelMiWork` job and the final `feelDump`.
 *
 * Writes scenarios/feel-iomapping/result-8.5.json. No assumptions about expected values:
 * this is a measurement, not an assertion suite.
 *
 * Usage: node feel-iomapping.js [caseIdPrefix]
 */
'use strict';
const path = require('path');
const fs = require('fs');
const {
  ROOT, buildClient, unary, topology, deploy, start, cancelPi, complete, sleep, parseVars,
} = require('./lib/oracle');

const CASES_PATH = path.join(ROOT, '..', '..', '..', 'zorrobpm-engine', 'src', 'test', 'resources', 'feel-iomapping-cases.json');
const OUT_DIR = path.join(ROOT, 'scenarios', 'feel-iomapping');
const OUT_PATH = path.join(OUT_DIR, 'result-8.5.json');

const esc = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;');

function ioMapping(inputs, outputs) {
  const parts = [];
  for (const m of inputs || []) parts.push(`<zeebe:input source="${esc(m.source)}" target="${esc(m.target)}"/>`);
  for (const m of outputs || []) parts.push(`<zeebe:output source="${esc(m.source)}" target="${esc(m.target)}"/>`);
  return parts.length ? `<zeebe:ioMapping>${parts.join('')}</zeebe:ioMapping>` : '';
}

function extensions({ taskType, inputs, outputs }) {
  const parts = [];
  if (taskType) parts.push(`<zeebe:taskDefinition type="${esc(taskType)}"/>`);
  const io = ioMapping(inputs, outputs);
  if (io) parts.push(io);
  return parts.length ? `<bpmn:extensionElements>${parts.join('')}</bpmn:extensionElements>` : '';
}

function serviceTask(id, type, incoming, outgoing, inputs, outputs, extra) {
  return `<bpmn:serviceTask id="${id}" name="${id}">`
    + extensions({ taskType: type, inputs, outputs })
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + (extra || '')
    + '</bpmn:serviceTask>';
}

function loopCharacteristics(c) {
  return '<bpmn:multiInstanceLoopCharacteristics isSequential="' + (c.isSequential ? 'true' : 'false') + '">'
    + '<bpmn:extensionElements><zeebe:loopCharacteristics '
    + 'inputCollection="=items" inputElement="item" '
    + `outputCollection="${esc(c.outputCollection || 'results')}" `
    + `outputElement="${esc(c.outputElement || '=r')}"/>`
    + '</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>';
}

function subProcess(id, incoming, outgoing, ext, innerXml) {
  return `<bpmn:subProcess id="${id}" name="${id}">`
    + ext
    + `<bpmn:incoming>${incoming}</bpmn:incoming><bpmn:outgoing>${outgoing}</bpmn:outgoing>`
    + innerXml
    + '</bpmn:subProcess>';
}

function bpmnXml(c) {
  const pid = 'f1_' + c.id.replace(/[^a-zA-Z0-9]/g, '_');
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + 'id="Definitions_' + pid + '" targetNamespace="http://bpmn.io/schema/bpmn">';
  let body;

  if (c.wrap === 'subprocess') {
    const inner = '<bpmn:startEvent id="spStart"><bpmn:outgoing>s1</bpmn:outgoing></bpmn:startEvent>'
      + serviceTask('work', 'feelWork', 's1', 's2', c.input, c.output)
      + serviceTask('dumpIn', 'feelDumpIn', 's2', 's3', null, null)
      + '<bpmn:endEvent id="spEnd"><bpmn:incoming>s3</bpmn:incoming></bpmn:endEvent>'
      + '<bpmn:sequenceFlow id="s1" sourceRef="spStart" targetRef="work"/>'
      + '<bpmn:sequenceFlow id="s2" sourceRef="work" targetRef="dumpIn"/>'
      + '<bpmn:sequenceFlow id="s3" sourceRef="dumpIn" targetRef="spEnd"/>';
    body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
      + subProcess('sp', 'f1', 'f2',
        extensions({ inputs: c.containerInput, outputs: c.containerOutput }), inner)
      + serviceTask('rootDump', 'feelDump', 'f2', 'f3', null, null)
      + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
      + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="sp"/>'
      + '<bpmn:sequenceFlow id="f2" sourceRef="sp" targetRef="rootDump"/>'
      + '<bpmn:sequenceFlow id="f3" sourceRef="rootDump" targetRef="end"/>';
  } else if (c.wrap === 'mi') {
    body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
      + serviceTask('miWork', 'feelMiWork', 'f1', 'f2', null, null, loopCharacteristics(c))
      + serviceTask('dump', 'feelDump', 'f2', 'f3', null, null)
      + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
      + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="miWork"/>'
      + '<bpmn:sequenceFlow id="f2" sourceRef="miWork" targetRef="dump"/>'
      + '<bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end"/>';
  } else {
    body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
      + serviceTask('work', 'feelWork', 'f1', 'f2', c.input, c.output)
      + serviceTask('dump', 'feelDump', 'f2', 'f3', null, null)
      + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
      + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="work"/>'
      + '<bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="dump"/>'
      + '<bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end"/>';
  }

  return defs + '<bpmn:process id="' + pid + '" name="' + pid + '" isExecutable="true">'
    + body + '</bpmn:process></bpmn:definitions>';
}

function activateT(client, type, max, requestTimeoutMs) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'f1-oracle',
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

function raw(job) {
  return { elementId: job.elementId, variablesRaw: job.variables };
}

function rendered(template, vars) {
  const out = {};
  for (const [k, v] of Object.entries(template || {})) {
    out[k] = typeof v === 'string'
      ? v.replace(/\$\{([^}]+)\}/g, (m, name) => (vars[name] === undefined ? m : String(vars[name])))
      : v;
  }
  return out;
}

async function runCase(client, c) {
  const result = { id: c.id, title: c.title, outcome: 'unknown' };
  const xml = bpmnXml(c);
  const pid = 'f1_' + c.id.replace(/[^a-zA-Z0-9]/g, '_');

  try {
    await unary(client, 'DeployResource', {
      resources: [{ name: pid + '.bpmn', content: Buffer.from(xml) }],
      tenantId: '',
    });
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }

  let piKey;
  try {
    piKey = await start(client, pid, c.vars || {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }

  try {
    if (c.wrap === 'mi') {
      result.miJobs = [];
      let dumpJob = null;
      for (let round = 0; round < 20 && !dumpJob; round++) {
        const jobs = (await activateT(client, 'feelMiWork', 100, 800))
          .filter((j) => String(j.processInstanceKey) === String(piKey));
        if (jobs.length) {
          for (const job of jobs) {
            const vars = parseVars(job);
            result.miJobs.push({ ...raw(job), variables: vars });
            const completion = c.completionTemplate ? rendered(c.completionTemplate, vars) : (c.completionVars || {});
            await complete(client, job, completion);
          }
          continue;
        }
        const dumps = (await activateT(client, 'feelDump', 10, 800))
          .filter((j) => String(j.processInstanceKey) === String(piKey));
        if (dumps.length) {
          dumpJob = dumps[0];
        } else {
          await sleep(300);
        }
      }
      result.miJobs.sort((a, b) => {
        const ai = a.variables.loopCounter === undefined ? -1 : Number(a.variables.loopCounter);
        const bi = b.variables.loopCounter === undefined ? -1 : Number(b.variables.loopCounter);
        return ai - bi;
      });
      if (!dumpJob) {
        result.outcome = result.miJobs.length ? 'noDumpJob' : 'noMiJob';
        return result;
      }
      result.dump = { ...raw(dumpJob), variables: parseVars(dumpJob) };
      await complete(client, dumpJob, {});
      result.outcome = 'completed';
      return result;
    }

    const workJobs = await pollT(client, 'feelWork', piKey);
    if (!workJobs.length) {
      result.outcome = 'noWorkJob';
      return result;
    }
    const work = workJobs[0];
    result.work = { ...raw(work), variables: parseVars(work) };
    await complete(client, work, c.completionVars || {});

    if (c.wrap === 'subprocess') {
      const innerDumps = await pollT(client, 'feelDumpIn', piKey);
      if (!innerDumps.length) {
        result.outcome = 'noDumpInJob';
        return result;
      }
      result.dumpIn = { ...raw(innerDumps[0]), variables: parseVars(innerDumps[0]) };
      await complete(client, innerDumps[0], {});

      const rootDumps = await pollT(client, 'feelDump', piKey);
      if (!rootDumps.length) {
        result.outcome = 'noRootDumpJob';
        return result;
      }
      result.rootDump = { ...raw(rootDumps[0]), variables: parseVars(rootDumps[0]) };
      await complete(client, rootDumps[0], {});
      result.outcome = 'completed';
      return result;
    }

    const dumps = await pollT(client, 'feelDump', piKey);
    if (!dumps.length) {
      result.outcome = 'noDumpJob';
      return result;
    }
    result.dump = { ...raw(dumps[0]), variables: parseVars(dumps[0]) };
    await complete(client, dumps[0], {});
    result.outcome = 'completed';
    return result;
  } finally {
    if (result.outcome !== 'completed') {
      try {
        await cancelPi(client, piKey);
        result.cancelled = true;
      } catch (e) {
        result.cancelError = String(e.message || e);
      }
    }
  }
}

async function main() {
  const filter = process.argv[2] || '';
  const data = JSON.parse(fs.readFileSync(CASES_PATH, 'utf8'));
  const cases = data.cases.filter((c) => c.id.startsWith(filter));
  const client = buildClient();
  const topo = await topology(client);
  console.log(`CONNECTED brokers=${(topo.brokers || []).length} gw=${topo.gatewayVersion}`);
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const results = [];
  for (const c of cases) {
    process.stdout.write(`CASE ${c.id} ... `);
    const r = await runCase(client, c);
    results.push(r);
    console.log(`${r.outcome}  work=${JSON.stringify(r.work?.variables ?? null)}  dump=${JSON.stringify(r.dump?.variables ?? null)}${r.dumpIn ? '  dumpIn=' + JSON.stringify(r.dumpIn.variables) : ''}${r.rootDump ? '  rootDump=' + JSON.stringify(r.rootDump.variables) : ''}${r.miJobs ? '  mi=' + JSON.stringify(r.miJobs.map((j) => j.variables)) : ''}`);
  }

  const out = {
    generatedAt: new Date().toISOString(),
    oracle: 'Zeebe ' + topo.gatewayVersion,
    cases: results,
  };
  fs.writeFileSync(OUT_PATH, JSON.stringify(out, null, 2) + '\n');
  console.log(`WROTE ${OUT_PATH} (${results.length} cases)`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
