package com.platform.agentservice.persona;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.persona.dto.PersonaResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러의 인가 계약(ROLE_ADMIN)과 상태 코드(생성=201/기존 갱신=200)만 본다 —
 * auth-server·org-service 연동 자체는 {@link PersonaServiceTest}(MockRestServiceServer)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PersonaService personaService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void admin_creates_persona_returns_201() throws Exception {
        PersonaResponse response = new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", true);
        given(personaService.bootstrap(any(), any()))
                .willReturn(new PersonaService.BootstrapResult(response, true));

        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot","emoji":"🤖"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("qa-bot"))
                .andExpect(jsonPath("$.memberId").value(9001));
    }

    @Test
    void admin_refresh_of_existing_slug_returns_200() throws Exception {
        PersonaResponse response = new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot v2", "🤖", true);
        given(personaService.bootstrap(any(), any()))
                .willReturn(new PersonaService.BootstrapResult(response, false));

        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot v2"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("QA Bot v2"));
    }

    @Test
    void non_admin_is_forbidden() throws Exception {
        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticated_user_can_list_personas() throws Exception {
        given(personaService.list()).willReturn(java.util.List.of(
                new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", true)));

        mvc.perform(get("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("qa-bot"));
    }
}
