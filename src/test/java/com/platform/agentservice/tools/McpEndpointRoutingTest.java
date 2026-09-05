package com.platform.agentservice.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 로컬에 Postgres(agentdb)가 없어 bootRun으로 실서버를 띄운 curl 확인이 불가능했다(H2는
 * testRuntimeOnly라 main 실행 classpath에도 없음). 대신 시큐리티 필터를 뺀 MockMvc로
 * "/api/agent/mcp" 라우팅 자체가 살아있는지(404 아님) 직접 검증한다 — Task 2 브리핑의
 * 명시된 대체 경로.
 *
 * 참고: 이 서비스는 SecurityConfig에서 anyRequest().authenticated()라 시큐리티 필터가
 * 켜진 채로는 존재하지 않는 경로도 401을 먼저 돌려준다(디스패처의 핸들러 매핑까지
 * 가지도 않음) — 그래서 curl 401은 "시큐리티가 살아있다"만 증명하고 "엔드포인트가
 * 실제로 매핑됐다"는 증명하지 못한다. addFilters=false로 시큐리티를 걷어내야
 * 라우팅 자체를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class McpEndpointRoutingTest {

    @Autowired MockMvc mockMvc;

    @Test
    void mcpEndpointIsMapped_notFourOhFour() throws Exception {
        int status = mockMvc.perform(get("/api/agent/mcp"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(404);
    }
}
