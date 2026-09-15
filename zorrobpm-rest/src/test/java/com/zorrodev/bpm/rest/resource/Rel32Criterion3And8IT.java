package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-32 HOLD: missing criterion 3 (fail/resolve both directions, claim/cancel different->422)
 * All via real Prod path — real service-task / incident endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Rel32Criterion3And8IT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO(); dto.setUsername(u); dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private void deployUserProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn"))).replace("id=\"form-process\"","id=\""+key+"\"");
        AddProcessDefinitionDTO dto=new AddProcessDefinitionDTO(); dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions").header("Authorization","Bearer "+adminToken).content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated());
    }

    private UUID deployServiceProcess(String key) throws Exception {
        String bpmn="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            +"<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" id=\"Defs\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
            +"  <bpmn:process id=\""+key+"\" name=\""+key+"\" isExecutable=\"true\">\n"
            +"    <bpmn:startEvent id=\"startEvent\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>\n"
            +"    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"startEvent\" targetRef=\"svc\"/>\n"
            +"    <bpmn:serviceTask id=\"svc\" name=\"svc\"><bpmn:extensionElements><zeebe:taskDefinition type=\"testsvc\" retries=\"3\"/></bpmn:extensionElements><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>\n"
            +"    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"svc\" targetRef=\"endEvent\"/>\n"
            +"    <bpmn:endEvent id=\"endEvent\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>\n"
            +"  </bpmn:process>\n</bpmn:definitions>";
        AddProcessDefinitionDTO dto=new AddProcessDefinitionDTO(); dto.setBpmn(bpmn);
        MvcResult r=mockMvc.perform(post("/process-definitions").header("Authorization","Bearer "+adminToken).content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID startAndGetServiceTask(String key) throws Exception {
        UUID pdId=deployServiceProcess(key);
        String body="{\"processDefinitionId\":\""+pdId+"\",\"variables\":[]}";
        MvcResult started=mockMvc.perform(post("/process-instances").header("Authorization","Bearer "+adminToken).content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        UUID piId=UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
        MvcResult tasks=mockMvc.perform(get("/service-tasks?processInstanceId="+piId).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn();
        String data=mapper.readTree(tasks.getResponse().getContentAsString()).get("data").toString();
        assertThat(data).as("service task must exist, got "+tasks.getResponse().getContentAsString()).contains("\"id\"");
        return UUID.fromString(mapper.readTree(tasks.getResponse().getContentAsString()).get("data").get(0).get("id").asText());
    }

    private UUID failToCreateIncident(UUID serviceTaskId) throws Exception {
        String body="{\"message\":\"boom\",\"retries\":0}";
        mockMvc.perform(post("/service-tasks/"+serviceTaskId+"/fail").header("Authorization","Bearer "+adminToken).content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        // fetch incident for that service task's process
        MvcResult incidents=mockMvc.perform(get("/incidents").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn();
        String incStr=incidents.getResponse().getContentAsString();
        var arr=mapper.readTree(incStr).get("data");
        for(var n:arr){
            if(serviceTaskId.toString().equals(n.get("activityId").asText())) return UUID.fromString(n.get("id").asText());
        }
        throw new AssertionError("no incident for "+serviceTaskId+" in "+incStr);
    }

    // ===== fail =====
    @Test
    void fail_sameBody_replays() throws Exception {
        UUID svcId=startAndGetServiceTask("rel32-fs-"+UUID.randomUUID().toString().substring(0,4));
        String key=UUID.randomUUID().toString();
        String body="{\"message\":\"e1\",\"retries\":1}";
        MvcResult first=mockMvc.perform(post("/service-tasks/"+svcId+"/fail").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        MvcResult second=mockMvc.perform(post("/service-tasks/"+svcId+"/fail").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void fail_differentBody_422() throws Exception {
        UUID svcId=startAndGetServiceTask("rel32-fd-"+UUID.randomUUID().toString().substring(0,4));
        String key=UUID.randomUUID().toString();
        String body1="{\"message\":\"e1\",\"retries\":1}";
        String body2="{\"message\":\"e2\",\"retries\":0}";
        mockMvc.perform(post("/service-tasks/"+svcId+"/fail").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body1).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        mockMvc.perform(post("/service-tasks/"+svcId+"/fail").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body2).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableEntity());
    }

    // ===== resolve =====
    @Test
    void resolve_sameBody_replays() throws Exception {
        UUID svcId=startAndGetServiceTask("rel32-rs-"+UUID.randomUUID().toString().substring(0,4));
        UUID incId=failToCreateIncident(svcId);
        String key=UUID.randomUUID().toString();
        String body="{\"variables\":[]}";
        MvcResult first=mockMvc.perform(post("/incidents/"+incId+"/resolve").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andReturn();
        int s1=first.getResponse().getStatus();
        String b1=first.getResponse().getContentAsString();
        MvcResult second=mockMvc.perform(post("/incidents/"+incId+"/resolve").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(s1);
        assertThat(second.getResponse().getContentAsString()).isEqualTo(b1);
    }

    @Test
    void resolve_differentBody_422() throws Exception {
        UUID svcId=startAndGetServiceTask("rel32-rd-"+UUID.randomUUID().toString().substring(0,4));
        UUID incId=failToCreateIncident(svcId);
        String key=UUID.randomUUID().toString();
        String body1="{\"variables\":[]}";
        String body2="{\"variables\":[{\"name\":\"x\",\"value\":\"1\",\"type\":\"STRING\"}]}";
        mockMvc.perform(post("/incidents/"+incId+"/resolve").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body1).contentType(MediaType.APPLICATION_JSON)).andReturn();
        mockMvc.perform(post("/incidents/"+incId+"/resolve").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body2).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableEntity());
    }

    // ===== claim / cancel different -> 422 =====
    @Test
    void claim_differentBody_422() throws Exception {
        String procKey="rel32-cld-"+UUID.randomUUID().toString().substring(0,4);
        deployUserProcess(procKey);
        String startBody="{\"processDefinitionKey\":\""+procKey+"\",\"variables\":[]}";
        MvcResult started=mockMvc.perform(post("/process-instances").header("Authorization","Bearer "+adminToken).content(startBody).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        UUID piId=UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
        MvcResult tasks=mockMvc.perform(get("/user-tasks?processInstanceId="+piId).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn();
        UUID taskId=UUID.fromString(mapper.readTree(tasks.getResponse().getContentAsString()).get("data").get(0).get("id").asText());
        String key=UUID.randomUUID().toString();
        mockMvc.perform(post("/user-tasks/"+taskId+"/claim").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key)).andExpect(status().isOk());
        mockMvc.perform(post("/user-tasks/"+taskId+"/claim").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content("{\"different\":1}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancel_differentBody_422() throws Exception {
        String procKey="rel32-cad-"+UUID.randomUUID().toString().substring(0,4);
        String bpmn=new String(Files.readAllBytes(Paths.get("src/test/files/test-cancel-process.bpmn"))).replace("id=\"test-cancel-process\"","id=\""+procKey+"\"");
        AddProcessDefinitionDTO dto=new AddProcessDefinitionDTO(); dto.setBpmn(bpmn);
        MvcResult dep=mockMvc.perform(post("/process-definitions").header("Authorization","Bearer "+adminToken).content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        UUID pdId=UUID.fromString(mapper.readTree(dep.getResponse().getContentAsString()).get("id").asText());
        MvcResult started=mockMvc.perform(post("/process-instances").header("Authorization","Bearer "+adminToken).content("{\"processDefinitionId\":\""+pdId+"\",\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        String piId=mapper.readTree(started.getResponse().getContentAsString()).get("id").asText();
        String key=UUID.randomUUID().toString();
        mockMvc.perform(post("/process-instances/"+piId+"/cancel").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key)).andExpect(status().isAccepted());
        mockMvc.perform(post("/process-instances/"+piId+"/cancel").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content("{\"different\":1}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableEntity());
    }
}
