/* WO-C8-55 — the ioMapping source/target semantics that Zeebe 8.5 applies where OUR
 * engine differs (F1 campaign, root 3 = "source without '='", root 4 = "target with a dot").
 *
 * Three questions this probe answers on the LIVE Zeebe 8.5.0 oracle — none of them may be
 * answered from the docs (G-Q: behaviour = live 8.5, design = 8.9 docs):
 *
 *   (A) SOURCE WITHOUT '=' — a static value, or a FEEL expression? The F1 matrix measured
 *       two spellings ('hello' and 'present'); this probe adds the spellings the PRODUCTION
 *       definitions actually use (a bare upper-case word 'PATCH', a word with a space, a
 *       number, a JSON text) and the TYPE of the resulting value (string vs number).
 *
 *   (B) TARGET WITH A DOT — how deep does the nesting go, and what happens when the parent
 *       key already exists (as a string, as a context)? The prod corpus has targets like
 *       'http.url' (the HTTP connector contract), so the merge/overwrite question decides
 *       whether the nested-target parity is even implementable for us.
 *
 *   (C) THE MI FIELDS — inputCollection / inputElement / outputElement have their OWN
 *       semantics in Zeebe; the 'static value' rule of (A) must NOT be transferred there
 *       without a measurement. The prod corpus has 'mi.outputElement' values WITHOUT '='
 *       ('probeId', '{"decision": decision, "comment": comment}') — so this is not academic.
 *       (C2) WHICH FORM MUST THE PRODUCER WRITE INSTEAD? The refused spelling has to be
 *       replaced by something that deploys AND carries the value: a bare reference
 *       ('=probeId'), a context ('={"v": probeId}') and a literal ('="probeId"') behave
 *       DIFFERENTLY on live 8.5 — measured, not guessed.
 *
 * Writes scenarios/feel-iomapping-static-nested/result-8.5.json. No assumptions about the
 * expected values: this is a measurement, not an assertion suite.
 *
 * Usage: node feel-iomapping-static-nested.js
 *
 * Corpus: 29 probes (15 io-mapping + 14 multi-instance). Measured 2026-10-20 on the live
 * Zeebe 8.5.0 oracle — the MI half of the corpus was extended on that day (WO-C8-55,
 * maintainer's question "what exactly must the consumer write instead of the refused form?").
 */
'use strict';
const path = require('path');
const fs = require('fs');
const {
  ROOT, buildClient, unary, topology, start, complete, sleep, parseVars,
} = require('./lib/oracle');

const OUT_DIR = path.join(ROOT, 'scenarios', 'feel-iomapping-static-nested');
const OUT_PATH = path.join(OUT_DIR, 'result-8.5.json');

const esc = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;');

/* ── (A)/(B): ioMapping source/target probes ──────────────────────────────── */

const IO_PROBES = [
  { id: 'src-noeq-literal', source: 'hello', target: 'r', vars: {},
    why: 'a bare word — static value or FEEL?' },
  { id: 'src-noeq-var', source: 'present', target: 'r', vars: { present: 'V' },
    why: 'a bare word that IS a variable name — static value or variable?' },
  { id: 'src-noeq-upper', source: 'PATCH', target: 'r', vars: {},
    why: 'the spelling the prod definitions use for an HTTP method (testApprovalRouteProcess v5/v6)' },
  { id: 'src-noeq-space', source: 'hello world', target: 'r', vars: {},
    why: 'a value with a space — deployable as a static value?' },
  { id: 'src-noeq-number', source: '42', target: 'r', vars: {},
    why: 'a bare number — static STRING or a number value?' },
  { id: 'src-noeq-json', source: '{"a":1}', target: 'r', vars: {},
    why: 'a JSON text without \'=\' — static string or parsed object?' },
  { id: 'tgt-nested', source: '=1', target: 'a.b', vars: {},
    why: 'a target with a dot — nested context (F1 matrix row 61)' },
  { id: 'tgt-nested-deep', source: '=1', target: 'a.b.c', vars: {},
    why: 'a target with two dots — how deep?' },
  { id: 'tgt-nested-parent-string', source: '=1', target: 'a.b', vars: { a: 'S' },
    why: 'the parent key already exists as a STRING — overwrite, merge or fail?' },
  { id: 'tgt-nested-parent-ctx', source: '=1', target: 'a.b', vars: { a: { x: 1 } },
    why: 'the parent key already exists as a CONTEXT — merge or replace?' },
  { id: 'tgt-nested-sibling', source: '=1', target: 'a.b', vars: {},
    second: { source: '=2', target: 'a.c' },
    why: 'two mappings into the SAME parent — do both survive?' },
  { id: 'src-noeq-output', source: 'hello', target: 'r', vars: {}, output: true,
    why: 'an OUTPUT source without \'=\' — static value too?' },
  { id: 'src-noeq-output-var', source: 'present', target: 'r', vars: { present: 'V' }, output: true,
    why: 'an OUTPUT source without \'=\' that names a variable — literal text or the value?' },
  { id: 'src-noeq-output-nested', source: 'hello', target: 'a.b', vars: {}, output: true,
    why: 'an OUTPUT source without \'=\' into a dotted target — the two rules together' },
  { id: 'src-noeq-spaces', source: '  hello  ', target: 'r', vars: {},
    why: 'a static value with surrounding SPACES — trimmed or verbatim?' },
];

function ioBpmn(pid, probe) {
  const inputs = [{ source: probe.source, target: probe.target }]
    .concat(probe.second ? [probe.second] : [])
    .map((m) => `<zeebe:input source="${esc(m.source)}" target="${esc(m.target)}"/>`)
    .join('');
  const outputs = probe.output
    ? `<zeebe:output source="${esc(probe.source)}" target="${esc(probe.target)}"/>`
    : '';
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + `id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">`;
  const body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
    + '<bpmn:serviceTask id="work" name="work">'
    + `<bpmn:extensionElements><zeebe:taskDefinition type="probeWork"/>`
    + `<zeebe:ioMapping>${inputs}${outputs}</zeebe:ioMapping>`
    + '</bpmn:extensionElements>'
    + '<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>'
    + '<bpmn:serviceTask id="dump" name="dump">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="probeDump"/></bpmn:extensionElements>'
    + '<bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>'
    + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
    + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="work"/>'
    + '<bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="dump"/>'
    + '<bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end"/>';
  return defs + `<bpmn:process id="${pid}" name="${pid}" isExecutable="true">${body}</bpmn:process></bpmn:definitions>`;
}

/* ── (C): the multi-instance field probes ─────────────────────────────────── */

const MI_PROBES = [
  { id: 'mi-outputelement-noeq', outputElement: 'probeId', vars: { probeId: 'P', items: ['a', 'b'] },
    why: 'outputElement WITHOUT \'=\' and it names a variable — literal text or the variable\'s value?' },
  { id: 'mi-outputelement-json', outputElement: '{"decision": decision, "comment": comment}',
    vars: { decision: 'D', comment: 'C', items: ['a', 'b'] },
    why: 'outputElement as a JSON text WITHOUT \'=\' (the prod corpus spelling) — literal text or a context?' },
  { id: 'mi-outputelement-eq-json', outputElement: '={"decision": decision, "comment": comment}',
    vars: { decision: 'D', comment: 'C', items: ['a', 'b'] },
    why: 'the same JSON WITH \'=\' — control for the two spellings' },
  { id: 'mi-outputelement-eq-var', outputElement: '=probeId', vars: { probeId: 'P', items: ['a', 'b'] },
    why: 'outputElement as a FEEL expression — control' },
  { id: 'mi-inputcollection-noeq', inputCollection: 'items', vars: { items: ['a', 'b'] },
    why: 'inputCollection WITHOUT \'=\' — the variable\'s value or the literal text?' },
  { id: 'mi-inputcollection-eq', inputCollection: '=items', vars: { items: ['a', 'b'] },
    why: 'inputCollection WITH \'=\' — control' },
  { id: 'mi-inputelement-plain', inputElement: 'item', vars: { items: ['a', 'b'] },
    why: 'inputElement is the NAME of the element variable — control' },

  /* --- the "what exactly must the consumer write?" probes (maintainer, 2026-10-20) ---
   * The prod corpus writes `outputElement="probeId"` (a bare variable name) and
   * `outputElement='{"decision": decision, "comment": comment}'` (a JSON text). Both are
   * refused at deploy by live 8.5. The consumer therefore needs the EXACT form to write
   * instead — and the answer is not obvious: `=probeId` (a variable reference) and
   * `={"decision": decision, "comment": comment}` (a context) are two different FEEL
   * expressions, and the first measured result was `results: [null, null]`.
   * These probes separate the three questions that hide behind that:
   *   1) is the null a NAME artefact (`probeId`) or does EVERY bare reference behave so?
   *   2) can the value be read inside a CONTEXT expression (`{"v": probeId}`)?
   *   3) is the value visible in the MI scope at all, or only in the parent scope?
   */
  { id: 'mi-outputelement-eq-var-othername', outputElement: '=myVal',
    vars: { myVal: 'M', items: ['a', 'b'] },
    why: 'a bare variable reference under ANOTHER name — is the null name-specific?' },
  { id: 'mi-outputelement-eq-ctx-var', outputElement: '={"v": probeId}',
    vars: { probeId: 'P', items: ['a', 'b'] },
    why: 'the SAME variable read INSIDE a context — can the MI scope see it?' },
  { id: 'mi-outputelement-eq-ctx-var-othername', outputElement: '={"v": myVal}',
    vars: { myVal: 'M', items: ['a', 'b'] },
    why: 'the same under another name — control' },
  { id: 'mi-outputelement-eq-literal', outputElement: '="probeId"',
    vars: { probeId: 'P', items: ['a', 'b'] },
    why: 'the LITERAL spelling (the form maintainer warned about) — the text, not the value' },
  { id: 'mi-outputelement-eq-var-set-in-body', outputElement: '=probeId',
    vars: { items: ['a', 'b'] }, completeVars: { probeId: 'P' },
    why: 'the same reference, but the variable is written by the MI BODY itself — scope check' },
  { id: 'mi-outputelement-eq-item-var', outputElement: '=item',
    vars: { items: ['a', 'b'] },
    why: 'a bare reference to the INPUT ELEMENT variable — the per-instance value' },
  { id: 'mi-outputelement-eq-items-idx', outputElement: '=items[1]',
    vars: { items: ['a', 'b'] },
    why: 'a bare reference into the collection — is the collection visible in the MI scope?' },
];

function miBpmn(pid, probe) {
  const inputCollection = probe.inputCollection || '=items';
  const inputElement = probe.inputElement || 'item';
  const outputCollection = probe.outputCollection || 'results';
  const outputElement = probe.outputElement || '=r';
  const defs = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
    + 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
    + 'xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" '
    + `id="Definitions_${pid}" targetNamespace="http://bpmn.io/schema/bpmn">`;
  const body = '<bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>'
    + '<bpmn:serviceTask id="miWork" name="miWork">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="probeMi"/></bpmn:extensionElements>'
    + '<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>'
    + '<bpmn:multiInstanceLoopCharacteristics isSequential="false">'
    + '<bpmn:extensionElements><zeebe:loopCharacteristics '
    + `inputCollection="${esc(inputCollection)}" inputElement="${esc(inputElement)}" `
    + `outputCollection="${esc(outputCollection)}" outputElement="${esc(outputElement)}"/>`
    + '</bpmn:extensionElements>'
    + '</bpmn:multiInstanceLoopCharacteristics>'
    + '</bpmn:serviceTask>'
    + '<bpmn:serviceTask id="dump" name="dump">'
    + '<bpmn:extensionElements><zeebe:taskDefinition type="probeDump"/></bpmn:extensionElements>'
    + '<bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>'
    + '<bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>'
    + '<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="miWork"/>'
    + '<bpmn:sequenceFlow id="f2" sourceRef="miWork" targetRef="dump"/>'
    + '<bpmn:sequenceFlow id="f3" sourceRef="dump" targetRef="end"/>';
  return defs + `<bpmn:process id="${pid}" name="${pid}" isExecutable="true">${body}</bpmn:process></bpmn:definitions>`;
}

/* ── the driver ───────────────────────────────────────────────────────────── */

function activateT(client, type, max, requestTimeoutMs) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'c855-oracle',
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

async function pollT(client, type, piKey, tries = 8, requestTimeoutMs = 1000) {
  for (let i = 0; i < tries; i++) {
    const jobs = (await activateT(client, type, 100, requestTimeoutMs))
      .filter((j) => String(j.processInstanceKey) === String(piKey));
    if (jobs.length) return jobs;
    await sleep(250);
  }
  return [];
}

async function deployXml(client, pid, xml) {
  return unary(client, 'DeployResource', {
    resources: [{ name: pid + '.bpmn', content: Buffer.from(xml) }],
    tenantId: '',
  });
}

async function runIo(client, probe) {
  const result = { id: probe.id, why: probe.why, source: probe.source, target: probe.target, outcome: 'unknown' };
  const pid = 'c855_' + probe.id.replace(/[^a-zA-Z0-9]/g, '_');
  let piKey;
  try {
    await deployXml(client, pid, ioBpmn(pid, probe));
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }
  try {
    piKey = await start(client, pid, probe.vars || {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }
  result.processInstanceKey = String(piKey);
  const work = await pollT(client, 'probeWork', piKey);
  if (!work.length) {
    result.outcome = 'noWorkJob';
    return result;
  }
  result.work = { variablesRaw: work[0].variables, variables: parseVars(work[0]) };
  await complete(client, work[0], {});
  const dump = await pollT(client, 'probeDump', piKey);
  if (dump.length) {
    result.dump = { variablesRaw: dump[0].variables, variables: parseVars(dump[0]) };
    await complete(client, dump[0], {});
    result.outcome = 'completed';
  } else {
    result.outcome = 'noDumpJob';
  }
  return result;
}

async function runMi(client, probe) {
  const result = { id: probe.id, why: probe.why, outcome: 'unknown' };
  const pid = 'c855_' + probe.id.replace(/[^a-zA-Z0-9]/g, '_');
  let piKey;
  try {
    await deployXml(client, pid, miBpmn(pid, probe));
  } catch (e) {
    result.outcome = 'deployError';
    result.error = String(e.message || e);
    return result;
  }
  try {
    piKey = await start(client, pid, probe.vars || {});
  } catch (e) {
    result.outcome = 'startError';
    result.error = String(e.message || e);
    return result;
  }
  result.processInstanceKey = String(piKey);
  result.miJobs = [];
  let dumpJob = null;
  for (let round = 0; round < 20 && !dumpJob; round++) {
    const jobs = (await activateT(client, 'probeMi', 100, 800))
      .filter((j) => String(j.processInstanceKey) === String(piKey));
    if (jobs.length) {
      for (const job of jobs) {
        const vars = parseVars(job);
        result.miJobs.push({ variablesRaw: job.variables, variables: vars });
        await complete(client, job, probe.completeVars || {});
      }
      continue;
    }
    const dumps = (await activateT(client, 'probeDump', 10, 800))
      .filter((j) => String(j.processInstanceKey) === String(piKey));
    if (dumps.length) {
      dumpJob = dumps[0];
    } else {
      await sleep(300);
    }
  }
  if (!dumpJob) {
    result.outcome = result.miJobs.length ? 'noDumpJob' : 'noMiJob';
    return result;
  }
  result.dump = { variablesRaw: dumpJob.variables, variables: parseVars(dumpJob) };
  await complete(client, dumpJob, {});
  result.outcome = 'completed';
  return result;
}

async function main() {
  const client = buildClient();
  const topo = await topology(client);
  console.log('ORACLE:', JSON.stringify(topo).slice(0, 200));
  const results = [];
  for (const probe of IO_PROBES) {
    const r = await runIo(client, probe);
    results.push(r);
    console.log('\n### ' + probe.id + ' — ' + probe.why);
    console.log(JSON.stringify(r, null, 2));
  }
  for (const probe of MI_PROBES) {
    const r = await runMi(client, probe);
    results.push(r);
    console.log('\n### ' + probe.id + ' — ' + probe.why);
    console.log(JSON.stringify(r, null, 2));
  }
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(OUT_PATH, JSON.stringify(results, null, 2));
  console.log('\nWROTE ' + OUT_PATH);
  process.exit(0);
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});
