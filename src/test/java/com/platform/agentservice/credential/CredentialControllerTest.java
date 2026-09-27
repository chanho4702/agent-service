package com.platform.agentservice.credential;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.audit.ToolCallAudit;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 키 API(D-P3h-2) — 응답 shape이 프론트 계약이고, 원문은 응답·로그·감사 summary·저장 바이트 어디에도 없어야 한다(반증 단언).
 */
@SpringBootTest(properties = "platform.agent.credentials.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CredentialControllerTest {

    static final String KEY = "sk-ant-api03-TOPSECRET-value-abcd1234";
    static final String KEY2 = "sk-ant-api03-SECONDSECRET-value-wxyz";

    @Autowired WebApplicationContext context;
    @Autowired CredentialRepository credentialRepository;
    @Autowired ToolCallAuditRepository auditRepository;
    @MockitoBean PermissionClient permissionClient;
    @MockitoBean AnthropicKeyValidator validator;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        credentialRepository.deleteAll();
        auditRepository.deleteAll();
        given(permissionClient.checkAdmin(anyLong(), any(), any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    static String body(String key) {
        return "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + key + "\"}";
    }

    static String body(String key, boolean validate) {
        return "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + key + "\",\"validate\":" + validate + "}";
    }

    private String hostEnvScope() {
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null || env.isBlank() ? "NONE" : "ENV";
    }

    private void assertNoPlaintextAnywhere(CapturedOutput output, String... keys) {
        for (String key : keys) {
            assertThat(output.getAll()).as("로그").doesNotContain(key);
            for (ToolCallAudit audit : auditRepository.findAll()) {
                assertThat(audit.getSummary()).as("감사 summary").doesNotContain(key);
            }
            for (Credential c : credentialRepository.findAll()) {
                assertThat(new String(c.getCiphertext(), StandardCharsets.ISO_8859_1)).as("저장 바이트").doesNotContain(key);
            }
        }
    }

    // ---- 전역 층 ----

    @Test
    void global_admin_puts_and_reads_the_platform_key_with_the_contract_shape(CapturedOutput output) throws Exception {
        MvcResult put = mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY))
                        .with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.keyHint").value("1234"))
                .andExpect(jsonPath("$.updatedBy").value(1))
                .andExpect(jsonPath("$.updatedAt").isString())
                .andReturn();
        assertThat(put.getResponse().getContentAsString()).doesNotContain(KEY);

        MvcResult got = mvc.perform(get("/api/agent/credentials/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.keyHint").value("1234"))
                .andReturn();
        assertThat(got.getResponse().getContentAsString()).doesNotContain(KEY);

        assertThat(auditRepository.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getTool()).isEqualTo("credential.put");
            assertThat(a.getSummary()).contains("scope=PLATFORM").contains("keyHint=1234");
            assertThat(a.getActorMemberId()).isEqualTo(1L);
        });
        verify(validator, never()).validate(any());
        assertNoPlaintextAnywhere(output, KEY);
    }

    @Test
    void unset_platform_reads_as_all_nulls() throws Exception {
        mvc.perform(get("/api/agent/credentials/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set").value(false))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.keyHint").doesNotExist());
    }

    @Test
    void replacing_the_platform_key_keeps_one_row_and_delete_is_idempotent_204() throws Exception {
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY))
                .with(authentication(TestAuth.admin(1L, "Admin")))).andExpect(status().isOk());
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY2))
                        .with(authentication(TestAuth.admin(3L, "Admin2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyHint").value("wxyz"))
                .andExpect(jsonPath("$.updatedBy").value(3));
        assertThat(credentialRepository.findAll()).hasSize(1);

        mvc.perform(delete("/api/agent/credentials/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/agent/credentials/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/credentials/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(jsonPath("$.set").value(false));
    }

    @Test
    void non_global_user_is_403_on_every_platform_endpoint_even_as_project_admin() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());

        mvc.perform(get("/api/agent/credentials/platform").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").isString());
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY))
                .with(authentication(TestAuth.user(2L, "Bob")))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/agent/credentials/platform").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
        assertThat(credentialRepository.findAll()).isEmpty();
    }

    @Test
    void unauthenticated_is_401() throws Exception {
        mvc.perform(get("/api/agent/credentials/platform")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/credentials/projects/7")).andExpect(status().isUnauthorized());
    }

    // ---- 프로젝트 층 ----

    @Test
    void project_admin_manages_own_project_key_but_not_another_projects() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());

        mvc.perform(put("/api/agent/credentials/projects/7").contentType(MediaType.APPLICATION_JSON).content(body(KEY))
                        .with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.keyHint").value("1234"))
                .andExpect(jsonPath("$.updatedBy").value(2));

        mvc.perform(put("/api/agent/credentials/projects/8").contentType(MediaType.APPLICATION_JSON).content(body(KEY2))
                        .with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/credentials/projects/8").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/agent/credentials/projects/8").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
        assertThat(credentialRepository.findAll()).hasSize(1);
    }

    @Test
    void project_read_shows_the_effective_layer_as_it_falls_through(CapturedOutput output) throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        var bob = authentication(TestAuth.user(2L, "Bob"));

        mvc.perform(get("/api/agent/credentials/projects/7").with(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.set").value(false))
                .andExpect(jsonPath("$.effective.scope").value(hostEnvScope()));

        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY2))
                .with(authentication(TestAuth.admin(1L, "Admin")))).andExpect(status().isOk());
        mvc.perform(get("/api/agent/credentials/projects/7").with(bob))
                .andExpect(jsonPath("$.project.set").value(false))
                .andExpect(jsonPath("$.effective.scope").value("PLATFORM"))
                .andExpect(jsonPath("$.effective.keyHint").value("wxyz"));

        mvc.perform(put("/api/agent/credentials/projects/7").contentType(MediaType.APPLICATION_JSON).content(body(KEY))
                .with(bob)).andExpect(status().isOk());
        MvcResult got = mvc.perform(get("/api/agent/credentials/projects/7").with(bob))
                .andExpect(jsonPath("$.project.set").value(true))
                .andExpect(jsonPath("$.project.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.project.keyHint").value("1234"))
                .andExpect(jsonPath("$.project.updatedBy").value(2))
                .andExpect(jsonPath("$.project.updatedAt").isString())
                .andExpect(jsonPath("$.effective.scope").value("PROJECT"))
                .andExpect(jsonPath("$.effective.keyHint").value("1234"))
                .andReturn();
        assertThat(got.getResponse().getContentAsString()).doesNotContain(KEY).doesNotContain(KEY2);

        mvc.perform(delete("/api/agent/credentials/projects/7").with(bob)).andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/credentials/projects/7").with(bob))
                .andExpect(jsonPath("$.project.set").value(false))
                .andExpect(jsonPath("$.effective.scope").value("PLATFORM"));
        assertNoPlaintextAnywhere(output, KEY, KEY2);
    }

    // ---- 검증·입력 ----

    @Test
    void validate_true_passes_the_key_to_the_validator_and_saves_on_success() throws Exception {
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY, true))
                .with(authentication(TestAuth.admin(1L, "Admin")))).andExpect(status().isOk());

        verify(validator).validate(KEY);
        assertThat(auditRepository.findAll()).singleElement().satisfies(a -> assertThat(a.getSummary()).contains("검증됨"));
    }

    @Test
    void rejected_key_is_400_without_the_plaintext_and_nothing_is_stored(CapturedOutput output) throws Exception {
        willThrow(new IllegalArgumentException(AnthropicKeyValidator.INVALID_MESSAGE)).given(validator).validate(KEY);

        MvcResult r = mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY, true))
                        .with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(AnthropicKeyValidator.INVALID_MESSAGE))
                .andReturn();

        assertThat(r.getResponse().getContentAsString()).doesNotContain(KEY);
        assertThat(credentialRepository.findAll()).isEmpty();
        assertThat(auditRepository.findAll()).singleElement().satisfies(a -> assertThat(a.getSummary()).contains("검증 실패"));
        assertNoPlaintextAnywhere(output, KEY);
    }

    @Test
    void unreachable_validation_is_503_and_nothing_is_stored() throws Exception {
        willThrow(new ServiceUnavailableException(AnthropicKeyValidator.UNREACHABLE_MESSAGE)).given(validator).validate(KEY);

        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON).content(body(KEY, true))
                        .with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(AnthropicKeyValidator.UNREACHABLE_MESSAGE));
        assertThat(credentialRepository.findAll()).isEmpty();
    }

    @Test
    void bad_input_is_400_and_error_messages_never_echo_the_input() throws Exception {
        var admin = authentication(TestAuth.admin(1L, "Admin"));
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"apiKey\":\"" + KEY + "\"}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"  \"}").with(admin))
                .andExpect(status().isBadRequest());
        MvcResult spaced = mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"sk-ant with-space-secret\"}").with(admin))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(spaced.getResponse().getContentAsString()).doesNotContain("with-space-secret");
        assertThat(credentialRepository.findAll()).isEmpty();
    }
}
