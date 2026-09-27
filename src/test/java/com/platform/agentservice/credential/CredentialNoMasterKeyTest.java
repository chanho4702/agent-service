package com.platform.agentservice.credential;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 마스터 키 미설정 — 저장은 503(fail-closed, 검증 호출 전), 조회는 정상 동작. */
@SpringBootTest(properties = "platform.agent.credentials.master-key=")
@ActiveProfiles("test")
class CredentialNoMasterKeyTest {

    @Autowired WebApplicationContext context;
    @Autowired CredentialRepository credentialRepository;
    @MockitoBean PermissionClient permissionClient;
    @MockitoBean AnthropicKeyValidator validator;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        credentialRepository.deleteAll();
    }

    @Test
    void put_is_503_before_validation_and_get_still_works() throws Exception {
        var admin = authentication(TestAuth.admin(1L, "Admin"));

        mvc.perform(put("/api/agent/credentials/platform").contentType(MediaType.APPLICATION_JSON)
                        .content(CredentialControllerTest.body(CredentialControllerTest.KEY, true)).with(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(CredentialCipher.UNAVAILABLE_MESSAGE));
        mvc.perform(put("/api/agent/credentials/projects/7").contentType(MediaType.APPLICATION_JSON)
                        .content(CredentialControllerTest.body(CredentialControllerTest.KEY)).with(admin))
                .andExpect(status().isServiceUnavailable());

        verify(validator, never()).validate(any());
        assertThat(credentialRepository.findAll()).isEmpty();
        mvc.perform(get("/api/agent/credentials/platform").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.set").value(false));
        mvc.perform(get("/api/agent/credentials/projects/7").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.set").value(false));
    }
}
