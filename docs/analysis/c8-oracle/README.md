# Camunda 8 oracle toolkit (WO-OPS-30)

Self-contained tooling to compare **our** ZorroBPM engine against real **Camunda 8** on the
conformance campaign (WO-OPS-30, rule **G-Q**). Two sources, two roles:

- **Behavior (the oracle) — live 8.5:** `camunda/zeebe:8.5.0` + `camunda/connectors-bundle:8.5.9`.
  What 8.5 *actually does*, measured by running scenarios against a live broker.
- **Design (the spec) — 8.9 docs:** docs.camunda.io. What the *current* docs say a feature should do.

Every matrix row is three columns — `8.9 docs | Zeebe 8.5 live | ours` — with a verdict
(`match` / `docs!=8.5` / `ours differs`). **F0** (this commit set) builds the foundation: a
self-contained oracle runner, the docker stand, a unified scenario format + comparison harness,
and an inventory of the workers/handlers we must keep conformant. F1+ fill the matrices.

## Layout

```
docs/analysis/c8-oracle/
  package.json, package-lock.json   deps: @camunda8/sdk@8.5.9 (proto), @grpc/grpc-js, @grpc/proto-loader
  lib/oracle.js                     shared raw-gRPC Zeebe client + helpers (deploy/start/activate/complete/...)
  run-oracle.js                     the original s1..s9 MI-sub-process scenarios (preserved, self-contained)
  compare.js                        unified scenario runner -> result-8.5.json + matrix line
  scenarios/
    _template/                      example scenario (process.bpmn + scenario.json)
    <id>/process.bpmn + scenario.json
  stack/
    docker-compose.yml              zeebe 8.5.0 + connectors-bundle 8.5.9 (containers zbpm-cmp-*)
    up.sh / down.sh                 safe up/down — only ever touch zbpm-cmp-* containers
  INVENTORY-workers.md              the workers/handlers on disk we must keep conformant (file:line)
  bpmn/, logs/                      the original C8-41 fixtures and oracle logs (s1..s9)
```

`node_modules/` and `scenarios/**/result-*.json` are gitignored (regeneratable).

## Prerequisites

- **Node.js** (tested on v24) + npm.
- **Docker** (for the stand). The Zeebe image `camunda/zeebe:8.5.0` must be pullable.
- A reachable Zeebe gateway at `localhost:36500` (default). Override with `C8_ORACLE_GW=<host:port>`.

## 1. Install the oracle deps (one-time)

```bash
cd docs/analysis/c8-oracle
npm ci                 # installs exactly package-lock.json (node_modules is gitignored)
```

> Why raw gRPC instead of `@camunda8/sdk`'s typed client: the 8.5.9 SDK's lossless JSON parser
> crashes on `job.variables` containing arrays with `null` (e.g. a pre-created `outputCollection
> [null,null,null]`). The raw string variables are also more faithful for an oracle log. We keep
> `@camunda8/sdk` only for its `dist/proto/zeebe.proto` (the gateway contract).

## 2. Bring up the stand

```bash
bash stack/up.sh
```

`up.sh` handles both topologies and only ever touches the `zbpm-cmp-*` containers (never
`raxon-*`, `wo*`, `zbpm-*-pgci*`, `zorrobpm-*`, ...):

- **Fresh stand-up** — starts `zbpm-cmp-zeebe` (8.5.0, partitions=1, gateway `127.0.0.1:36500`,
  actuator `127.0.0.1:39600`) and `zbpm-cmp-connectors` (8.5.9); connectors reach zeebe by compose
  service name `zeebe:26500`.
- **Reuse an existing zeebe** (e.g. one the maintainer started for the campaign) — it is **reused, not
  recreated**. Its published ports are bound to `127.0.0.1`, so the connectors reach it via its
  bridge IP on its own network (`C8_ORACLE_GATEWAY=<ip>:26500` + `docker network connect`).

Verify:

```bash
curl -s localhost:39600/actuator/health            # -> {"status":"UP",...}
docker logs zbpm-cmp-connectors 2>&1 | grep -c 'Connection refused'   # expect 0 (connectors connected)
```

> **Known benign log noise (connectors).** The `connectors-bundle` logs a repeating
> `ProcessDefinitionImporter ... UnsupportedOperationException: Unable to determine authentication`
> (plus a one-time `DefaultNoopAuthentication: Unable to determine authentication`) because its
> optional inbound / process-definition-import feature tries to use Camunda *Cloud* auth, which we
> deliberately leave off on this self-managed, plaintext stand. It does **not** affect job workers:
> `io.camunda:http-json:1` and the other bundled workers register and run (0 `Connection refused`).
> This is the bundle's default behavior with no cloud credentials, not a stand defect.

Tear down (only `zbpm-cmp-*`; `--keep-zeebe` leaves a shared zeebe up):

```bash
bash stack/down.sh            # remove zbpm-cmp-connectors + zbpm-cmp-zeebe
bash stack/down.sh --keep-zeebe
```

## 3. Run the original s1..s9 MI-sub-process scenarios (preserved)

```bash
node run-oracle.js s4         # one scenario
node run-oracle.js all        # s1 s2a s2b s3 s4 s5 s6 s7 s8a s8b s9
node run-oracle.js cleanup    # cancel leftover instances
```

These are the C8-41 MI-sub-process recon scenarios; they now run from the local `node_modules`
(no more `/tmp/.../scratchpad` absolute paths).

## 4. Run a unified scenario -> result JSON + matrix line

A scenario is a directory `scenarios/<id>/` with `process.bpmn` + `scenario.json`
(see `scenarios/_template/`). The canonical shape is **a service task + a final dump job**:
compare.js completes the service task with `completeVars` (the "worker result") and captures the
dump job's variables as the observed root state.

```bash
node compare.js _template            # run, write scenarios/_template/result-8.5.json, print the matrix line
node compare.js _template --matrix   # also append the matrix line to MATRIX.md
```

Example output:

```
OBSERVED: {"result":42,"greeting":"camunda"}
CHECKS:   result=OK; greeting=OK
MATRIX:   <8.9 docs> | greeting="camunda", result=42 | (pending) | measured-8.5 (ours pending)
```

The `ours` column (column 3) is filled manually / in F1+ when we run the same scenario through
our engine; F0 rows carry the verdict `measured-8.5 (ours pending)`.

### `scenario.json` fields

| field | meaning |
|---|---|
| `processId` | BPMN process id to start |
| `vars` | start variables (JSON) for `CreateProcessInstance` |
| `completeTypes` | job types compare.js completes while driving toward the dump job |
| `completeVars` | per-type completion variables — the "worker result" (`{ "<type>": { ... } }`) |
| `dumpType` | the final job type whose variables are captured as the observed root state |
| `expected` | documented 8.5 behavior; compared (by key) against the observed dump vars |
| `matrix.docs89` | what the 8.9 docs say (column 1) |
| `matrix.ours` | what our engine does (column 3); empty = pending |
| `maxRounds` | safety cap for the drive loop (default 40) |

## 4b. Run the F2a job-protocol scenarios (WO-OPS-30 Ф2а)

`job-protocol.js` drives the **job protocol** (completion merge, fail/retries/backoff,
`throwError`, activation timeout/redelivery, `zeebe:taskDefinition` retries, `zeebe:taskHeaders`,
dispatch by type, no worker) against the live broker. Its scenario format is the one above,
extended with a `steps` array — a step is either a worker action (`complete` / `fail` /
`throwError`) or an observation (`observeJob` / `observeJobImmediately` / `activateAndAbandon` /
`observeIncident`).

```bash
node job-protocol.js --sync     # regenerate scenarios/f2a-*/ from the canonical case list
node job-protocol.js             # run every F2a case against the live broker
node job-protocol.js f2a-merge-root
```

**One source of truth for both engines.** The canonical case list is
`zorrobpm-engine/src/test/resources/job-protocol-cases.json` (BPMN + steps + expectations);
`--sync` writes the `scenarios/f2a-<id>/` copies from it, and every run **byte-compares**
`scenarios/f2a-<id>/process.bpmn` against
`zorrobpm-engine/src/test/resources/job-protocol/<id>.bpmn` — a mismatch aborts the run, so the
two engines can never be measured against different models.

The matrix itself: `docs/analysis/job-protocol-conformance-matrix.md`
(8.9 docs | Zeebe 8.5 live | ours | verdict).

## F0 finding — Zeebe 8.5 variable scoping (measured live)

Measured on live Zeebe 8.5.0 (see report `docs/internal`):

- **Completion variables** (from `CompleteJob`) are written to the containing scope (root) and
  **are** visible to subsequent root elements — this is the reliable way to land a FEEL-computed
  result where a dump job can capture it.
- **Input-mapping targets are local to the element scope**: they DO appear in the activated job's
  raw `variables`, and they do NOT overwrite a same-named variable in the parent/root scope. A
  later root task sees the start/root value, not the input-mapped shadow.
- **Output-mapping targets are written to the element's parent scope** (for a root-level task,
  the root scope), so they DO reach a subsequent root task. Output mappings are evaluated in the
  element scope, including input-mapped locals; sibling outputs are visible to later output
  mappings in the same element. Completion variables are also merged into the containing scope.

F1 corrected the original F0 wording: the F0 claim mixed input-mapping locality with
output-mapping propagation. The measured F1 cases `input-rewrite`, `output-local`,
`output-completion`, `output-other-output`, `sp-container-input`, `sp-container-output` and
`sp-output-scope` pin the two directions separately. See
`docs/analysis/feel-iomapping-conformance-matrix.md`.
