package com.platform.agentservice.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpServerWiringTest {
    @Autowired PingTools pingTools;

    @Test
    void ping_returns_pong_with_service_name() {
        assertThat(pingTools.ping()).contains("agent-service");
    }
}
