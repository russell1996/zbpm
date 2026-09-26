package com.zorrodev.bpm.engine.bpmn.model;

import com.zorrodev.bpm.engine.service.impl.BpmnParseServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-16 criterion #1: {@code getJobTypes()} is what deployment/startup announce to the
 * messaging layer, so a job type it misses keeps the old lazy behaviour (no queue until the first
 * message). The element-type-agnostic rule matters: a {@code zeebe:taskDefinition} turns a script
 * task or a send task into a job worker too, so filtering by SERVICE_TASK would silently drop them.
 */
class BpmnProcessDefinitionModelJobTypesTest {

    private static BpmnElementModel element(String id, BpmnElementType type, String job) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(type);
        if (job != null) {
            BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
            ServiceTaskExtensionModel serviceTask = new ServiceTaskExtensionModel();
            serviceTask.setJob(job);
            extensions.setServiceTaskExtension(serviceTask);
            e.setExtensions(extensions);
        }
        return e;
    }

    @Test
    void collectsJobsFromServiceScriptAndSendTasks() {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(element("svc", BpmnElementType.SERVICE_TASK, "billing"));
        model.addElement(element("script", BpmnElementType.SCRIPT_TASK, "scoring"));
        model.addElement(element("send", BpmnElementType.SEND_TASK, "notify"));

        assertThat(model.getJobTypes()).containsExactlyInAnyOrder("billing", "scoring", "notify");
    }

    @Test
    void deduplicatesRepeatedJobType() {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(element("svc1", BpmnElementType.SERVICE_TASK, "billing"));
        model.addElement(element("svc2", BpmnElementType.SERVICE_TASK, "billing"));

        assertThat(model.getJobTypes()).containsExactly("billing");
    }

    @Test
    void ignoresElementsWithoutJob() {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(element("start", BpmnElementType.START_EVENT, null));           // no extensions at all
        model.addElement(element("user", BpmnElementType.USER_TASK, null));
        BpmnElementModel emptyExtensions = new BpmnElementModel();
        emptyExtensions.setId("gw");
        emptyExtensions.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
        emptyExtensions.setExtensions(new BpmnElementExtensionModel());                  // extensions, no serviceTask
        model.addElement(emptyExtensions);
        model.addElement(element("blank", BpmnElementType.SERVICE_TASK, "   "));         // blank job name

        assertThat(model.getJobTypes()).isEmpty();
    }

    @Test
    void emptyModelYieldsEmptySet() {
        assertThat(new BpmnProcessDefinitionModel().getJobTypes()).isEmpty();
    }

    /** End-to-end through the real parser, so the extraction is not only tested against hand-built models. */
    @Test
    void collectsJobTypeFromRealParsedDefinition() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/process2.bpmn"));
        BpmnProcessDefinitionModel model = new BpmnParseServiceImpl().parse(bpmn);

        assertThat(model.getJobTypes()).contains("job1");
    }
}
