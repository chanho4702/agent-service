package com.platform.agentservice.pat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGP-28 — MCP 경로 정본은 {@link McpPaths} 하나다. yml의 MCP 서버 엔드포인트는 상수를 참조할 수 없어
 * 여기서 묶는다 — 엔드포인트만 옮기면 PAT 체인이 새 경로를 놓쳐 JWT 체인이 401로 막는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpPathsConsistencyTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    String mcpEndpoint;

    @Test
    void mcp_server_endpoint_matches_security_path_constant() {
        assertThat(mcpEndpoint).isEqualTo(McpPaths.MCP_PATH);
    }

    @Test
    void predicate_covers_path_and_subpaths_only() {
        assertThat(McpPaths.isMcpPath("/api/agent/mcp")).isTrue();
        assertThat(McpPaths.isMcpPath("/api/agent/mcp/x")).isTrue();
        assertThat(McpPaths.isMcpPath("/api/agent/mcpx")).isFalse();
        assertThat(McpPaths.isMcpPath("/api/agent/%6dcp")).isFalse();
        assertThat(McpPaths.isMcpPath(null)).isFalse();
    }
}
