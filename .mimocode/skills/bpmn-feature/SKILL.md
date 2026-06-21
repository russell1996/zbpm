---
description: "Structured workflow for implementing a new BPMN element in ZorroBPM engine"
---

# BPMN Feature Implementation

Standardized multi-phase workflow for adding a new BPMN element (event, task, gateway, subprocess) to the ZorroBPM engine. Derived from 5+ completed feature cycles.

## Phase 0: Analysis (read-only)

1. Read `docs/camunda8-compatibility.md` for the element's Camunda 8 status
2. Read existing XML models in `zorrobpm-engine/.../bpmn/xml/` for similar elements
3. Read existing engine models in `zorrobpm-engine/.../bpmn/model/` for the execution counterpart
4. Read the BPMN 2.0 spec section for the element (if available)
5. Identify: XML attributes → model fields → parser logic → engine behavior → test cases

## Phase 1: XML Model

Add/modify XML model classes in `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/xml/`:
- Create `Bpmn<Element>Model.java` if new element type (extend `BpmnBaseElementModel` for flow nodes)
- **Binding is JAXB, not Jackson.** Follow the existing pattern:
  - `@XmlAccessorType(XmlAccessType.FIELD)` on the class, Lombok `@Getter`/`@Setter`
  - `@XmlAttribute` for attributes, `@XmlElement(name = "...", namespace = "...")` for child elements
  - BPMN namespace: `http://www.omg.org/spec/BPMN/20100524/MODEL`
  - Zeebe extensions live under `extensionElements` (namespace `http://camunda.org/schema/zeebe/1.0`);
    mirror `ExtensionElements` / `ServiceTaskExtensionModel` / `ZeebeLoopCharacteristicsModel`

## Phase 2: Engine Model

Add/modify engine model in `zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/model/`:
- Add fields to `BpmnProcessDefinitionModel` or create new model class
- Update `BpmnParseServiceImpl` to parse the new XML into engine model

## Phase 3: Parser Integration

Update `BpmnParseServiceImpl.java`:
- Register new element in the parse switch/map
- Handle definition-level vs instance-level parsing
- For event definitions: register in definition-level registry (the `T0` pattern)

## Phase 4: Engine Behavior

Update execution logic in `ActivityServiceImpl.java` or relevant handler:
- Token traversal for the new element (handlers are registered in the `BpmnElementType -> handler` map)
- Scope propagation (if element creates/enters a scope). Use **scoped variables** when an element needs
  per-activity locals: `dbService.getVariables(pi, scopeId)` merges root + local (local shadows);
  `scope_id == null` is the instance root. IO input-mappings write locals; output writes root.
- Event subscription handling (if applicable) — message/signal/timer subscriptions carry marker columns
  (e.g. `event_subprocess_id`, `boundary_element_id`) on the subscription/timer-job rows

## Phase 5: Tests

Integration tests live in **`zorrobpm-engine/src/test/`**, not `zorrobpm-test/` (that module only holds
shared test helpers):
- **Integration test**: `zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/integration/<Element>IntegrationTests.java`
  (note the **plural** `Tests` suffix — Failsafe runs `*IntegrationTests`)
- **Fixture**: a `.bpmn` (and `.dmn` if needed) under `zorrobpm-engine/src/test/files/`, loaded with
  `Files.readString(Paths.get("src/test/files/<name>.bpmn"))`
- Drive execution through the public services and assert on repositories. **JPA staleness caveat:** bulk
  `@Modifying` status updates leave the persistence cache stale mid-flight — assert statuses after the
  instance completes, or track activity ids, as the existing tests do.
- Parser-level unit tests go in `BpmnParseServiceImplTest` / `DBServiceImplTest` where applicable

## Phase 6: Verify

Run `mvn-build` command to confirm all tests pass.

## Phase 7: Documentation

Update `docs/camunda8-compatibility.md`:
- Mark element as supported
- Note any Camunda 8 incompatibilities

Update `README.md` support matrix if applicable.

## Key Files Reference

| Purpose | Path |
|---------|------|
| XML models | `zorrobpm-engine/.../bpmn/xml/Bpmn*Model.java` |
| Engine models | `zorrobpm-engine/.../bpmn/model/BpmnProcessDefinitionModel.java` |
| Parser | `zorrobpm-engine/.../service/impl/BpmnParseServiceImpl.java` |
| Token engine | `zorrobpm-engine/.../service/impl/ActivityServiceImpl.java` |
| Runtime | `zorrobpm-engine/.../service/impl/RuntimeServiceImpl.java` |
| DB / variables | `zorrobpm-engine/.../service/impl/DBServiceImpl.java` |
| Integration tests | `zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/integration/` |
| Test fixtures | `zorrobpm-engine/src/test/files/*.bpmn` |
| DB migrations | `zorrobpm-engine/src/main/resources/db/changelog/changesets/` (Liquibase, `includeAll`) |
| Camunda compat | `docs/camunda8-compatibility.md`, `docs/CAMUNDA8_COMPATIBILITY_PLAN.md` |

## FEEL note

Two FEEL entry points with different return types — pick deliberately:
- `ScriptService` (javax.script `FeelScriptEngineFactory`) returns **Scala** collections for FEEL lists
- `FeelEngineApi` (`FeelEngineBuilder.forJava().build()`) returns **Java** types

Numbers come back as `BigDecimal`. The custom DMN engine evaluates over `FeelEngineApi`.

## Stopping Condition

All tests pass (`BUILD SUCCESS` via `mvn-build`), `docs/camunda8-compatibility.md` + `README.md` updated,
feature branch ready for the `git-merge` command (merge `--no-ff` to master, push only on explicit ask).
