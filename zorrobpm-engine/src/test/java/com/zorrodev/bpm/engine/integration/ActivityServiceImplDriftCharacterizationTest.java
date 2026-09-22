package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
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

/**
 * WO-DEBT-8a: drift-characterization for the {@code ActivityServiceImpl} delta since
 * {@code 05083df0} (WO-AUD-25 close, "неснижаемый фасад").
 *
 * Every other new chunk from that range is already covered by its own WO's tests
 * (full chunk→test table in {@code governance/reports/WO-DEBT-8a.md}) — only the
 * WO-API-1 4-arg {@code startProcessInstance(..., claimedInitiator)} overload had
 * no engine-level test (just REST-level {@code OnBehalfOfIntegrationTest}), so it
 * is characterized HERE.
 *
 * FIXES BEHAVIOR AS-IS — does NOT change production code.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ActivityServiceImplDriftCharacterizationTest {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityService activityService;
    @Autowired private QueryService queryService;
    @Autowired private ProcessInstanceRepository processInstanceRepository;

    private UUID deployDummy(String key) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/dummy-process.bpmn"))
            .replace("dummy-process", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    @Transactional
    void startProcessInstance_claimedInitiator_recordedOnInstance() throws Exception {
        // WO-API-1 (API-7): claimedInitiator едет в том же INSERT create — инстанс
        // стартует как обычно, initiator записан на созданной строке.
        UUID pdId = deployDummy(uniq("drift-init"));

        UUID pi = activityService.startProcessInstance(null, pdId, List.of(), "emp99");

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(processInstanceRepository.findById(pi)).isPresent();
        assertThat(processInstanceRepository.findById(pi).get().getInitiator()).isEqualTo("emp99");
    }

    @Test
    @Transactional
    void startProcessInstance_nullInitiator_matchesLegacyOverload() throws Exception {
        // WO-API-1: null = старый путь побайтово — 3-arg перегрузка и 4-arg с null
        // дают одинаковый результат (initiator не записан, инстанс отработал).
        UUID pdId = deployDummy(uniq("drift-null"));

        UUID piLegacy = activityService.startProcessInstance(null, pdId, List.of());
        UUID piNull = activityService.startProcessInstance(null, pdId, List.of(), null);

        assertThat(queryService.getProcessInstance(piLegacy).getCompletedAt()).isNotNull();
        assertThat(queryService.getProcessInstance(piNull).getCompletedAt()).isNotNull();
        assertThat(processInstanceRepository.findById(piLegacy).get().getInitiator()).isNull();
        assertThat(processInstanceRepository.findById(piNull).get().getInitiator()).isNull();
    }
}
