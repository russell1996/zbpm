package com.zorrodev.bpm.client.configuration;

import com.zorrodev.bpm.client.ProcessDefinitionClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * What the configured clients put on the wire: the base URL and, only when one is configured, the
 * API token as a bearer header.
 */
class ClientConfigurationTest {

    private static final String ENGINE = "http://engine.example";

    @Test
    void sendsTheConfiguredTokenAsABearerHeader() {
        RestClient.Builder builder = ClientConfiguration.configure(RestClient.builder(), ENGINE, "zbpa_secret");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID id = UUID.randomUUID();
        server.expect(requestTo(ENGINE + "/process-definitions/" + id + "/xml"))
            .andExpect(header("Authorization", "Bearer zbpa_secret"))
            .andExpect(header("Accept", "application/json"))
            .andRespond(withSuccess("<bpmn/>", MediaType.APPLICATION_JSON));

        String xml = client(builder).getProcessDefinitionXml(id);

        assertThat(xml).isEqualTo("<bpmn/>");
        server.verify();
    }

    @Test
    void sendsNoAuthorizationWhenTheTokenIsEmpty() {
        for (String token : new String[]{null, "", "   "}) {
            RestClient.Builder builder = ClientConfiguration.configure(RestClient.builder(), ENGINE, token);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            UUID id = UUID.randomUUID();
            server.expect(requestTo(ENGINE + "/process-definitions/" + id + "/xml"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("<bpmn/>", MediaType.APPLICATION_JSON));

            client(builder).getProcessDefinitionXml(id);

            server.verify();
        }
    }

    private static ProcessDefinitionClient client(RestClient.Builder builder) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
            .build()
            .createClient(ProcessDefinitionClient.class);
    }
}
