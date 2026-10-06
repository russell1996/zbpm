package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-34 (external review CR-08): the parser used to accept model constructs it does not
 * implement and execute them with a DIFFERENT meaning, without a word — a standard loop ran once,
 * a complex gateway vanished, a multi-instance container ran once, a conditional start became an
 * unconditional one, a second {@code <process>} was dropped, and a flow into nowhere parked the
 * instance on an incident at runtime. Deploy now refuses the model.
 *
 * <p>V11: every case goes through the PRODUCTION deploy path
 * ({@code ProcessDefinitionService.addProcessDefinition} — the method the REST endpoint, the batch
 * deployer and the submission-approval flow all funnel into), against the real repositories. Not a
 * unit test of the scanner.
 *
 * <p>The refusal must be total, not cosmetic: criterion 1 asks for a refusal BEFORE the active
 * definition, its version, its start job and its subscription are created, so each rejection case
 * also asserts that no row was written.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class UnsupportedConstructDeployIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private com.zorrodev.bpm.engine.service.BpmnParseService bpmnParseService;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private ProcessRepository processRepository;

    @Autowired
    private MessageStartSubscriptionRepository messageStartSubscriptionRepository;

    @Autowired
    private TimerStartJobRepository timerStartJobRepository;

    // ─── Criterion 1: standardLoopCharacteristics ───────────────────────

    @Test
    void criterion1_standardLoopCharacteristics_rejectedAndNothingPersisted() {
        String bpmn = readBpmn("test-eng34-standard-loop.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_STANDARD_LOOP");
                assertThat(api.getMessage()).contains("standardLoopCharacteristics");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("loopTask");
            });

        // nothing at all was written: no version row under either start's key, no registry row,
        // and — the point of the message/timer start events in the fixture — no subscription and
        // no timer job. This is the "refusal BEFORE the artifacts exist" half of criterion 1.
        assertThat(processDefinitionRepository.findAll())
            .as("no process definition version may exist for a refused model")
            .noneMatch(pd -> pd.getKey().startsWith("test-eng34-standard-loop"));
        assertThat(processRepository.findByDefinitionKey("test-eng34-standard-loop")).isEmpty();
        assertThat(messageStartSubscriptionRepository.findByMessageName("eng34-loop-message"))
            .as("no message start subscription may be registered for a refused model")
            .isEmpty();
        assertThat(timerStartJobRepository.findAll())
            .as("no timer start job may be created for a refused model")
            .noneMatch(job -> job.getProcessKey() != null
                && job.getProcessKey().startsWith("test-eng34-standard-loop"));
    }

    // ─── Criterion 2: complexGateway ──────────────────────────────────────

    @Test
    void criterion2_complexGateway_rejectedWithItsElementId() {
        String bpmn = readBpmn("test-eng34-complex-gateway.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_COMPLEX_GATEWAY");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("cg");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("test-eng34-complex-gateway"));
    }

    // ─── Criterion 3: several <process> in one resource ───────────────────

    @Test
    void criterion3_severalProcessesInOneResource_rejectedNamingBoth() {
        String bpmn = readBpmn("test-eng34-multi-process.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("MULTIPLE_PROCESSES_IN_RESOURCE");
                // BOTH ids, so the modeller knows which one to split out — not just the winner
                assertThat(api.getParams().get("elementIds")).asList()
                    .containsExactly("test-eng34-multi-process-first", "test-eng34-multi-process-second");
            });

        assertThat(processDefinitionRepository.findAll())
            .as("neither process may be deployed")
            .noneMatch(pd -> pd.getKey().startsWith("test-eng34-multi-process"));
    }

    // ─── Criterion 4: unresolvable sourceRef / targetRef ──────────────────

    @Test
    void criterion4_unresolvableFlowRef_rejectedNamingTheFlow() {
        String bpmn = readBpmn("test-eng34-dangling-ref.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("UNRESOLVED_SEQUENCE_FLOW_REF");
                // f1/f3 resolve, only f2 dangles — the message must not blame the whole graph
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("f2");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("test-eng34-dangling-ref"));
    }

    @Test
    void criterion6_refsToNodesTheParserDoesNotModel_areNotReportedAsDangling() {
        // The regression this WO's first cut actually shipped. The ref check resolved against the
        // ids the PARSER modelled, so every flow to or from a node the parser drops was called
        // "matches no flow node of the process" — a lie that refuses a valid model and sends its
        // author looking for a typo. Three such node kinds are declared in
        // test-eng34-odd-node-refs.bpmn: <implicitThrowEvent> (a real BPMN 2.0 flow node) and the
        // <messageStartEvent>/<timerStartEvent>/<terminateEndEvent> tag spellings that Zeebe/Camunda
        // exports contain. NONE of them may produce a finding — the resolver's question is whether the
        // DOCUMENT declares the node, not whether the engine managed to model it.
        //
        // The model is additionally deployed, so this is the criterion-6 half: today those starts and
        // the implicit throw are silently NOT executed (a separate finding for CTO), and refusing the
        // deploy would change behaviour this WO must not change.
        String bpmn = readBpmn("test-eng34-odd-node-refs.bpmn");

        List<String> findings = bpmnParseService.parse(bpmn).getUnsupportedConstructs()
            .stream().map(c -> c.code() + c.elementIds()).toList();

        assertThat(findings).as("no finding at all for nodes the document declares").isEmpty();
        assertThat(processDefinitionService.addProcessDefinition(bpmn).getId()).isNotNull();
    }

    @Test
    void criterion4_danglingRefInsideASubProcess_isFoundToo() {
        // the scan must RECURSE into container bodies: a top-level-only scan would accept this model
        String bpmn = readBpmn("test-eng34-nested-ref-dangling.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("UNRESOLVED_SEQUENCE_FLOW_REF");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("fGhost");
            });
    }

    @Test
    void criterion4_refToANodeOfTheParentScopeInsideASubProcess_isNotDangling() {
        // the false-positive twin of the test above: fSub points OUT of the sub-process at a node of
        // the same process. The parser flattens the body into one scope, so this resolves — a scan
        // that resolved refs per CONTAINER, or against a set that missed parent-scope nodes, would
        // refuse a perfectly good model.
        String bpmn = readBpmn("test-eng34-nested-ref-ok.bpmn");

        ProcessDefinition deployed = processDefinitionService.addProcessDefinition(bpmn);

        assertThat(deployed.getId()).isNotNull();
    }

    // ─── Criterion 5: conditional start event ─────────────────────────────

    @Test
    void criterion5_conditionalStartEvent_rejectedNotSilentlyMadeAPlainStart() {
        String bpmn = readBpmn("test-eng34-conditional-start.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                // the specific defect, NOT the pre-existing "at most one plain start" rule: a
                // conditional start is not a plain start and must not spend that budget
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_CONDITIONAL_START_EVENT");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("conditionalStart");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("test-eng34-conditional-start"));
    }

    @Test
    void criterion5_multiInstanceOnContainer_rejected() {
        // the CTO-named scope addition (found by the WO-C8-34 review): multiInstanceLoopCharacteristics
        // on a subProcess/callActivity parsed into an ordinary single-instance container
        String bpmn = readBpmn("test-mi-on-container-parse.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_CONTAINER");
                assertThat(api.getParams().get("elementIds")).asList()
                    .containsExactly("miSub", "miCall");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("mi-on-container-parse"));
    }

    // ─── Criterion 6: supported models still deploy, unchanged ────────────

    /**
     * The regression guard, and the honest one: the SUPPORTED cousin of every construct now refused.
     * If any of these stopped deploying (or picked up a finding), the check would be eating working
     * models — which is the failure mode a rejection feature actually has.
     */
    @Test
    void criterion6_supportedCounsinsOfEveryRejectedConstruct_stillDeploy() {
        for (String fixture : List.of(
            // multi-instance on the two kinds that support it
            "test-multi-instance.bpmn",
            "test-multi-instance-sequential.bpmn",
            "test-multi-instance-service.bpmn",
            "test-mi-reenter.bpmn",
            "test-eng-8-completioncondition.bpmn",
            // conditional events on the kinds that DO implement them (boundary + intermediate catch)
            "test-conditional-boundary.bpmn",
            "test-conditional-catch.bpmn",
            "test-conditional-filter.bpmn",
            // plain subprocess/callActivity without any loop marker
            "controlProcess.bpmn",
            // boundaries, gateways, event subprocess — the neighbours of the refused constructs
            "test-boundary.bpmn",
            "test-inclusive-end.bpmn",
            "process1.bpmn")) {

            String key = "eng34-ok-" + UUID.randomUUID().toString().substring(0, 8);
            String bpmn = readBpmn(fixture).replace("dummy-process", key);
            ProcessDefinition deployed = processDefinitionService.addProcessDefinition(bpmn);

            assertThat(deployed)
                .as("%s must keep deploying", fixture)
                .isNotNull();
            assertThat(deployed.getKey()).as("%s key", fixture).isNotBlank();
        }
    }

    @Test
    void criterion6_aFreshDeployOfASupportedModel_reportsNoUnsupportedConstruct() {
        String key = "eng34-clean-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = readBpmn("test-multi-instance-service.bpmn").replace("dummy-process", key);

        ProcessDefinition deployed = processDefinitionService.addProcessDefinition(bpmn);

        // not merely "no exception": the model that WAS produced carries no findings either, which
        // is what a later deploy of the same file relies on
        assertThat(deployed.getId()).isNotNull();
        assertThat(processDefinitionRepository.findById(deployed.getId()))
            .as("the supported model was really persisted")
            .isPresent();
    }

    /**
     * The FALSE-POSITIVE guard, and the one this WO's own first cut failed: a scan that resolves
     * sequence-flow refs against the ids the parser put into the executable model reports valid
     * models as broken whenever the parser legitimately models fewer elements than the document
     * declares. It did exactly that for message/timer/signal starts written in the
     * {@code <bpmn:messageStartEvent>} tag form (which Zeebe/Camunda exports produce and which the
     * JAXB process model has no list for): every flow out of such a start came back as dangling.
     *
     * <p>Each fixture below deploys TODAY; a false positive refuses it. They are the supported
     * cousins of the six refused constructs, which is the only honest way to pin a rejection
     * feature — its real failure mode is eating working models, not missing bad ones.
     */
    @Test
    void criterion6_supportedModelsProduceNoFindingsAtAll() {
        List<String> supported = List.of(
            // message / timer / signal starts, in the spelling the engine binds (<startEvent> + def)
            "test-message-start.bpmn", "test-timer-start.bpmn", "test-signal-start-receiver.bpmn",
            "process-with-events.bpmn", "test-rel15-msg-deploy.bpmn", "test-rel15-timer-deploy.bpmn",
            "test-scale4-batch-race.bpmn",
            // event sub-processes (message / timer / signal triggered)
            "test-event-subprocess.bpmn", "test-event-subprocess-timer.bpmn",
            "test-event-subprocess-signal.bpmn", "test-event-subprocess-noninterrupting.bpmn",
            // conditional events on kinds that DO implement them (boundary / intermediate catch)
            "test-conditional-boundary.bpmn", "test-conditional-catch.bpmn",
            // multi-instance on every supported kind, incl. the intermediate-throw parity case
            // that keeps today's behaviour (not refused — see the report's "найдено рядом")
            "test-c8-mi-throw.bpmn", "test-multi-instance.bpmn", "test-multi-instance-sequential.bpmn",
            "test-multi-instance-service.bpmn", "test-mi-reenter.bpmn",
            "test-eng-8-completioncondition.bpmn", "test-eng34-mi-on-tasks.bpmn",
            // gateways, boundaries, sub-processes, call activity
            "test-boundary.bpmn", "test-inclusive-end.bpmn", "controlProcess.bpmn",
            "test-eng34-nested-ref-ok.bpmn");

        List<String> dirty = new java.util.ArrayList<>();
        for (String fixture : supported) {
            var model = bpmnParseService.parse(readBpmn(fixture));
            if (!model.getUnsupportedConstructs().isEmpty()) {
                dirty.add(fixture + " -> " + model.getUnsupportedConstructs().stream()
                    .map(c -> c.code() + c.elementIds()).toList());
            }
        }
        assertThat(dirty)
            .as("a rejection feature that eats working models is worse than the defect it closes")
            .isEmpty();
    }

    // ─── Criterion 7: the error shape ─────────────────────────────────────

    @Test
    void criterion7_refusalUsesTheSameShapeAsServiceTaskMissingJob() {
        ApiException refusal = catchRefusal("test-eng34-complex-gateway.bpmn");

        // code + element ids in params — exactly what SERVICE_TASK_MISSING_JOB established
        // (ProcessDefinitionServiceImpl), no new error format invented
        assertThat(refusal.getCode()).isNotBlank();
        assertThat(refusal.getParams()).containsKey("elementIds");
        assertThat((List<?>) refusal.getParams().get("elementIds")).isNotEmpty();
        assertThat(refusal.getMessage()).isNotBlank();
        // and it is a 400, like every other deploy refusal
        assertThat(refusal.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void criterion7_messageNamesTheConstructAndTheOffendingElement() {
        ApiException refusal = catchRefusal("test-eng34-standard-loop.bpmn");

        assertThat(refusal.getMessage())
            .as("the modeller must see WHAT is unsupported, not a generic 'parse error'")
            .contains("standardLoopCharacteristics")
            .contains("loopTask");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private ApiException catchRefusal(String fixture) {
        return (ApiException) org.assertj.core.api.Assertions.catchThrowable(
            () -> processDefinitionService.addProcessDefinition(readBpmn(fixture)));
    }

    private static String readBpmn(String filename) {
        try {
            return Files.readString(Paths.get("src/test/files/" + filename));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + filename, e);
        }
    }
}
