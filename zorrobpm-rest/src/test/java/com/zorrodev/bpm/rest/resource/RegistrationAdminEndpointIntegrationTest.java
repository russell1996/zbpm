package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REG-5 criterion 1,7 (rest): SUPER_ADMIN-only queue over real HTTP.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationAdminEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private StubMailSender mailSender;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String adminToken() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"admin\"}";
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String userToken(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"User\",\"email\":\"" + email + "\"}";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        String mailBody = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s\"]+)").matcher(mailBody);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UUID createUserViaAdmin(String username, String role) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"User\",\"email\":\"" + username + "@x.com\",\"role\":\"" + role + "\"}";
        String admin = adminToken();
        MvcResult r = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void nonSuperAdmin_forbiddenOnAllThree() throws Exception {
        String userName = "regadm-user-" + tag();
        createUserViaAdmin(userName, "USER");
        String userTok = userToken(userName, "MyStr0ng!P@ssw0rd");

        mockMvc.perform(get("/admin/registrations")
                        .header("Authorization", "Bearer " + userTok))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/registrations/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", "Bearer " + userTok))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/registrations/" + UUID.randomUUID() + "/reject")
                        .header("Authorization", "Bearer " + userTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());

        // 401 without token
        mockMvc.perform(get("/admin/registrations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/registrations/" + UUID.randomUUID() + "/approve"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/registrations/" + UUID.randomUUID() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminApprove_happy() throws Exception {
        String email = "regadm-happy-" + tag() + "@x.com";
        String token = registerAndGetToken("regadm-happy-" + tag(), email);
        // verify email → PENDING_APPROVAL
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UiUserEntity user = userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow();
        String admin = adminToken();
        mailSender.clear();

        mockMvc.perform(post("/admin/registrations/" + user.getId() + "/approve")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        UiUserEntity approved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(approved.isActive()).isTrue();
        assertThat(approved.getRegistrationStatus()).isEqualTo("ACTIVE");
        assertThat(mailSender.getSent().stream().anyMatch(m -> email.equalsIgnoreCase(m.to()))).isTrue();

        // login now works
        String loginBody = "{\"username\":\"" + approved.getUsername() + "\",\"password\":\"MyStr0ng!P@ssw0rd\"}";
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        userRepository.deleteById(approved.getId());
    }

    @Test
    void listPending_onlyPendingApproval() throws Exception {
        String admin = adminToken();
        // pending verification
        String emailPv = "list-pv-" + tag() + "@x.com";
        String bodyPv = "{\"username\":\"list-pv-" + tag() + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"User\",\"email\":\"" + emailPv + "\"}";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyPv.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        UiUserEntity pv = userRepository.findAll().stream().filter(u -> emailPv.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow();

        // pending approval
        String emailPa = "list-pa-" + tag() + "@x.com";
        String tokPa = registerAndGetToken("list-pa-" + tag(), emailPa);
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + tokPa + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        UiUserEntity pa = userRepository.findAll().stream().filter(u -> emailPa.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow();

        MvcResult list = mockMvc.perform(get("/admin/registrations")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        String json = list.getResponse().getContentAsString();
        assertThat(json).contains(pa.getId().toString());
        assertThat(json).doesNotContain(pv.getId().toString());

        userRepository.deleteById(pv.getId());
        userRepository.deleteById(pa.getId());
    }
}
