package com.platform.agentservice.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
public class PingTools {

    @McpTool(name = "ping", description = "연결 확인용. 서비스 이름과 시각을 돌려준다.")
    public String ping() {
        return "pong from agent-service at " + java.time.Instant.now();
    }
}
