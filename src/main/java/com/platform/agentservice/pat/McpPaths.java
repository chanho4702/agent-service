package com.platform.agentservice.pat;

/**
 * MCP 엔드포인트 경로 술어의 단일 정본(AGP-28). {@code SecurityConfig}의 체인 매처와
 * {@link PatAuthFilter}의 방어 검사가 같은 값을 봐야 한다 — 한쪽만 바뀌면 PAT 체인이 경로를 놓쳐
 * JWT 체인으로 떨어지거나(401), 필터가 정상 요청을 fail-closed로 막는다. yml의
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}는 상수를 참조할 수 없어
 * {@code McpPathsConsistencyTest}가 둘을 묶어 둔다.
 */
public final class McpPaths {

    public static final String MCP_PATH = "/api/agent/mcp";
    public static final String MCP_SUBPATHS = MCP_PATH + "/**";
    private static final String MCP_PATH_PREFIX = MCP_PATH + "/";

    private McpPaths() {}

    /** {@link #MCP_PATH} 자체이거나 그 하위 경로 — 체인 매처 {@code (MCP_PATH, MCP_SUBPATHS)}와 같은 범위다. */
    public static boolean isMcpPath(String uri) {
        return uri != null && (uri.equals(MCP_PATH) || uri.startsWith(MCP_PATH_PREFIX));
    }
}
