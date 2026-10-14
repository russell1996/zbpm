# INVENTORY — workers & handlers on disk (WO-OPS-30 F0, item (г))

What the conformance campaign (G-Q) must keep conformant to Camunda 8. Every entry is on disk
at the referenced `file:line` (verified 2026-10-15 against this tree). Phases in the last column
map to the WO phase that will compare each item.

> **script task / business rule (DMN): ЕСТЬ.** Both exist as synchronous in-engine handlers
> (`SyncTaskHandler.ScriptTask` / `SyncTaskHandler.BusinessRuleTask`) — NOT "не найдено".
> Detail in section 3.

## 1. External workers — `job-handler-starter` protocol

The protocol contract is the `JobHandler` interface; the starter wires a RabbitMQ listener per
job type and publishes completions. This is what F2a compares to Zeebe's job protocol
(completion merge, fail/retries/backoff, `throwError`, redelivery, DLQ).

| item | location |
|---|---|
| `JobHandler` interface (the worker protocol contract) | `zorrobpm-job-handler-spring-boot-starter/src/main/java/com/zorrodev/bpm/handler/JobHandler.java:8` — `getJob()` `:10`, `handleJob(JobDetailModel)` `:12` |
| `HandlerAutoConfiguration` (per-type listener wiring, queue `zorrobpm.jobs.<type>`) | `.../handler/boot/HandlerAutoConfiguration.java:29` |
| `JobCompletionListener` (receive job → dispatch → publish completion) | `.../handler/boot/JobCompletionListener.java:51` |
| `ConfirmedCompletionSender` (reliable completion publish, C8-36) | `.../handler/boot/ConfirmedCompletionSender.java:29` |
| `CompletionRedeliveryBackoff` (retries/backoff on NACK/timeout) | `.../handler/boot/CompletionRedeliveryBackoff.java:61` |
| `CompletionPoisonRetryListener` (poison / DLQ, REL-64) | `.../handler/boot/CompletionPoisonRetryListener.java:47` |
| `CompletionTopology` / `CompletionPublishDeniedException` | `.../handler/boot/CompletionTopology.java` / `.../CompletionPublishDeniedException.java` |

Concrete `JobHandler` implementations (both in `zorrobpm-http-connector`):

| worker | job type | location |
|---|---|---|
| `CamundaHttpJsonWorker` (Camunda REST-connector format) | `io.camunda:http-json:1` | `zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/CamundaHttpJsonWorker.java:86` (JOB_TYPE `:98`) |
| `HttpConnectorWorker` (our native HTTP) | `zorrobpm:http` | `.../httpconnector/HttpConnectorWorker.java:64` (JOB_TYPE `:66`) |

→ **F2b** compares these two against the Connectors bundle's HTTP/REST connector (live 8.5.9).

## 2. Engine built-in (synchronous) handlers — `ElementHandler`

These run inside the engine (no external worker). F2c (messages) + F1 (io-mapping) cover them.

| handler | element | location |
|---|---|---|
| `ServiceTaskHandler` | `serviceTask` (dispatch/timeout/incident) | `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/ServiceTaskHandler.java:23` |
| `SendTaskHandler` | `sendTask` | `.../engine/handler/SendTaskHandler.java:23` |
| `MessageCatchHandler` | message catch (receiveTask / message start & boundary) | `.../engine/handler/MessageCatchHandler.java:27` |
| `MessageThrowHandler` | message throw (intermediate message catch-event source) | `.../engine/handler/MessageThrowHandler.java:27` |

## 3. Script task / business rule (DMN) — **Есть (both exist)**

Both are synchronous in-engine handlers nested in `SyncTaskHandler`:

| handler | element | what it does | location |
|---|---|---|---|
| `SyncTaskHandler.ScriptTask` | `scriptTask` | evaluates a **FEEL** script synchronously as the token flows through | `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SyncTaskHandler.java:27` |
| `SyncTaskHandler.BusinessRuleTask` | `businessRuleTask` | evaluates a **DMN** decision via `DmnService` (`zeebe:calledDecision`) or inline FEEL | `.../engine/handler/SyncTaskHandler.java:157` |

Supporting services / models:

| item | location |
|---|---|
| `DmnService` (interface) / `DmnServiceImpl` (DMN decision evaluation) | `.../engine/service/DmnService.java:11` / `.../engine/service/impl/DmnServiceImpl.java:47` |
| `ScriptService` (FEEL script evaluation) | `.../engine/service/impl/ScriptServiceImpl.java` (referenced by `SyncTaskHandler.ScriptTask`) |
| BPMN models | `.../engine/bpmn/xml/BpmnScriptTaskModel.java`, `.../engine/bpmn/xml/BpmnBusinessRuleTaskModel.java`, `.../engine/bpmn/model/ScriptTaskExtensionModel.java`, `.../engine/bpmn/model/BusinessRuleExtensionModel.java` |

→ **F2c** (+ F1 FEEL) covers these. The WO item "Есть ли script/DMN — если нет, пиши «не найдено»"
resolves to: **both present**, so the campaign must keep them conformant.

## 4. Consumer workers (Go) — OUTSIDE this repository

The Go consumer workers are not in this repo. Per the WO (item п.4), only **contract tests from our
side** apply: what the engine guarantees to a worker (input variables per the mapping, `JSON`/`STRING`
types, result merge, error → incident). These become F2c contract tests, not a code inventory.

## 5. G-Q files the campaign will touch (F1→F2)

The files a parity matrix row / conformance test will read or mutate:

- **F1 (FEEL + io-mapping):** `zorrobpm-engine/.../engine/handler/ElementSupport.java` (FEEL + io-mapping
  evaluation), `.../engine/service/impl/DmnServiceImpl.java`, `.../engine/service/impl/ScriptServiceImpl.java`.
- **F2a (job protocol vs Zeebe 8.5):** everything in section 1 (the `job-handler-starter` + workers).
- **F2b (HTTP/REST connector vs Connectors 8.5):** `CamundaHttpJsonWorker.java`, `HttpConnectorWorker.java`.
- **F2c (messages + script/DMN + consumer contracts):** section 2 handlers + section 3
  (`SyncTaskHandler`, `DmnServiceImpl`) + the engine-side guarantees to external workers.
