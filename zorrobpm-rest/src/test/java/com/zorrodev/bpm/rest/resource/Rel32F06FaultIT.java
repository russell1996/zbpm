package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Rel32F06FaultIT {

    @Autowired private MockMvc mockMvc;
    @MockitoSpyBean private TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin","admin");
    }
    private String login(String u,String p) throws Exception {
        LoginDTO dto=new LoginDTO(); dto.setUsername(u); dto.setPassword(p);
        MvcResult r=mockMvc.perform(post("/auth/login").content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
    private void deploy(String key) throws Exception {
        String bpmn=new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn"))).replace("id=\"form-process\"","id=\""+key+"\"");
        AddProcessDefinitionDTO dto=new AddProcessDefinitionDTO(); dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions").header("Authorization","Bearer "+adminToken).content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated());
    }

    @Test
    void f06_commitFailure_doesNotReturn200_andRetrySucceeds() throws Exception {
        String procKey="rel32-f06-"+UUID.randomUUID().toString().substring(0,4);
        deploy(procKey);
        String body="{\"processDefinitionKey\":\""+procKey+"\",\"variables\":[]}";
        String key=UUID.randomUUID().toString();
        doThrow(new RuntimeException("simulated commit failure")).doCallRealMethod().when(transactionTemplate).execute(any());
        MvcResult first=null;
        try {
            first=mockMvc.perform(post("/process-instances").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andReturn();
        } catch (Exception e) {
            // commit failure propagated - client did NOT get 200
        }
        if(first!=null) assertThat(first.getResponse().getStatus()).isNotEqualTo(201);
        MvcResult second=mockMvc.perform(post("/process-instances").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        assertThat(second.getResponse().getContentAsString()).contains("\"id\"");
    }
}
