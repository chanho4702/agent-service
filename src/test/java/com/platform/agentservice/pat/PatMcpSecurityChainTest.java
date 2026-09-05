package com.platform.agentservice.pat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PatAuthFilter가 실제 전체 보안 필터 체인(springSecurity() 적용, 전체 애플리케이션 컨텍스트)
 * 안에서 살아남는지 증명한다. {@link PatAuthFilterTest}는 필터를 단독으로 호출하므로, 같은
 * 체인에 oauth2ResourceServer(jwt)가 함께 있을 때 BearerTokenAuthenticationFilter가
 * "Authorization: Bearer <아무 문자열>"을 경로 무관하게 JWT로 디코드 시도하다 실패해
 * SecurityContext를 지우고 401로 체인을 끊어버리는 상호작용은 잡아내지 못한다 — 이 테스트가
 * 그 상호작용을 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PatMcpSecurityChainTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PatService patService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void valid_pat_is_not_401d_by_the_real_security_chain_on_mcp_subpath() throws Exception {
        given(patService.validate("agp_good")).willReturn(Optional.of(new PatPrincipal(1L, 2L, 3L)));

        var result = mvc.perform(post("/api/agent/mcp/anything").header("Authorization", "Bearer agp_good"))
                .andReturn();

        // 이 서브패스에는 실제 MCP 핸들러가 없으니 404는 정상 — 증명 대상은 "PAT 인증
        // 레이어가 유효한 토큰을 401로 끊지 않는다"는 것 하나다.
        assertThat(result.getResponse().getStatus())
                .as("유효한 PAT가 보안 레이어에서 401로 끊기면 안 된다")
                .isNotEqualTo(401);
    }

    @Test
    void invalid_pat_returns_401_through_the_real_security_chain() throws Exception {
        given(patService.validate("agp_bad")).willReturn(Optional.empty());

        mvc.perform(post("/api/agent/mcp/anything").header("Authorization", "Bearer agp_bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void valid_pat_reaches_the_actual_mcp_endpoint_path() throws Exception {
        given(patService.validate("agp_good")).willReturn(Optional.of(new PatPrincipal(1L, 2L, 3L)));

        var result = mvc.perform(post("/api/agent/mcp").header("Authorization", "Bearer agp_good"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("유효한 PAT로 실제 MCP 엔드포인트 경로(하위경로 아님)에 접근할 때도 401이면 안 된다")
                .isNotEqualTo(401);
    }
}
