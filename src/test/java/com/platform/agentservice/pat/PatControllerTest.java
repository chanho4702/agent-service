package com.platform.agentservice.pat;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 계약(발급/철회=ROLE_ADMIN, 목록=인증만)과 응답 shape만 본다 — 실제 발급/검증 로직은
 * {@link PatServiceTest}가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PatControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PatService patService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void admin_creates_token_returns_201_with_plaintext_once() throws Exception {
        given(patService.issue(any(), eq(1L)))
                .willReturn(new PatCreatedResponse("agp_abcdef", 10L, "ci-token", "qa-bot"));

        String body = """
                {"label":"ci-token","personaSlug":"qa-bot"}
                """;

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("agp_abcdef"))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.label").value("ci-token"))
                .andExpect(jsonPath("$.personaSlug").value("qa-bot"));
    }

    /** M4 — label은 pat_token.label VARCHAR(120). 초과분은 DB까지 가지 않고 400으로 끊어야 한다. */
    @Test
    void label_over_column_width_returns_400_with_korean_error() throws Exception {
        String body = """
                {"label":"%s","personaSlug":"qa-bot"}
                """.formatted("a".repeat(121));

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("label은 120자 이하여야 합니다"));
    }

    /** 경계값 — 정확히 120자 label은 통과해야 한다. */
    @Test
    void label_at_exact_column_width_is_accepted() throws Exception {
        given(patService.issue(any(), eq(1L)))
                .willReturn(new PatCreatedResponse("agp_abcdef", 10L, "l", "qa-bot"));

        String body = """
                {"label":"%s","personaSlug":"qa-bot"}
                """.formatted("a".repeat(120));

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void non_admin_cannot_create_token() throws Exception {
        String body = """
                {"label":"ci-token","personaSlug":"qa-bot"}
                """;

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticated_user_can_list_tokens_without_hash() throws Exception {
        given(patService.list()).willReturn(List.of(
                new PatSummaryResponse(10L, "ci-token", "qa-bot", Instant.now(), null, null, false)));

        mvc.perform(get("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].personaSlug").value("qa-bot"))
                .andExpect(jsonPath("$[0].revoked").value(false));
    }

    @Test
    void admin_revokes_token_returns_204() throws Exception {
        mvc.perform(delete("/api/agent/tokens/10").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(patService).revoke(10L);
    }

    @Test
    void non_admin_cannot_revoke_token() throws Exception {
        mvc.perform(delete("/api/agent/tokens/10").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }
}
