package com.platform.agentservice.pat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /api/agent/mcp만 담당하는 필터의 401 차단(체인 미진행 포함)과 인증 성공 경로,
 * 그리고 /api/agent/tokens(일반 JWT 경로)는 건드리지 않는다는 것을 증명한다.
 */
class PatAuthFilterTest {

    private final PatService patService = mock(PatService.class);
    private final PatAuthFilter filter = new PatAuthFilter(patService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void non_mcp_path_passes_through_untouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(patService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missing_authorization_header_returns_401_and_blocks_chain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("유효하지 않은 토큰");
        verifyNoInteractions(chain);
        verifyNoInteractions(patService);
    }

    @Test
    void non_bearer_authorization_header_returns_401_and_blocks_chain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void invalid_expired_or_revoked_token_returns_401_and_blocks_chain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp");
        request.addHeader("Authorization", "Bearer agp_bad");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(patService.validate("agp_bad")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("유효하지 않은 토큰");
        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void valid_token_populates_security_context_and_continues_chain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp");
        request.addHeader("Authorization", "Bearer agp_good");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        PatPrincipal principal = new PatPrincipal(1L, 2L, 3L);
        when(patService.validate("agp_good")).thenReturn(Optional.of(principal));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_AGENT");
    }

    @Test
    void mcp_subpaths_are_also_covered_by_the_filter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp/tool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
}
