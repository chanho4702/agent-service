package com.platform.agentservice.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록된 MCP 도구 이름 집합의 스냅샷(S10 Task 10). {@code @McpTool}이 붙은 메서드는
 * spring-ai-mcp-annotations의 {@code ServerAnnotatedMethodBeanPostProcessor}가 스캔해
 * {@code McpServerAnnotationScannerAutoConfiguration$ServerMcpAnnotatedBeans}에 모으고,
 * {@code McpServerSpecificationFactoryAutoConfiguration$SyncServerSpecificationConfiguration
 * #toolSpecs(...)}가 그로부터 {@code List<McpServerFeatures.SyncToolSpecification>} 빈을
 * 만든다(jar 리버스 엔지니어링으로 확인, S10) — 이 빈이 바로
 * {@code McpServerAutoConfiguration#mcpSyncServer(...)}에 주입되는 도구 목록이므로, 컨텍스트에
 * 등록된 이 빈을 직접 오토와이어링하는 것이 "실제 MCP 서버가 노출할 도구 이름"을 확인하는
 * 가장 직접적인 방법이다. (참고: 일반 {@code @Tool}/{@code ToolCallback} 경로용
 * {@code ToolCallbackConverterAutoConfiguration}은 이 서비스가 쓰는 {@code @McpTool} 애노테이션
 * 스캔 경로와는 별개라 여기서는 빈이 없다 — 실측으로 확인.) 정확히 이 21개와 일치해야 한다 —
 * 도구가 조용히 사라지거나(빈 등록 실패) 이름에 오타가 생기는 회귀를 잡는다. (18→21은 P2a T2가
 * run 도구 3종 report_progress/request_gate/report_result를 추가하면서 늘었다.)
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolRegistrySnapshotTest {

    @Autowired
    private List<McpServerFeatures.SyncToolSpecification> toolSpecs;

    @Test
    void registeredToolNames_matchExactSet() {
        // 이름 집합 비교 전에 개수부터 확인한다 — 이름이 중복 등록돼도(예: 두 빈이 같은
        // @McpTool(name=...)을 쓰는 실수) Set으로 모으면 조용히 뭉개지므로, 리스트 크기가
        // 18인지 먼저 잡아야 그런 회귀를 놓치지 않는다(리뷰 반영, S10).
        assertThat(toolSpecs).hasSize(21);

        Set<String> names = toolSpecs.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "ping",
                "whoami",
                "list_projects",
                "get_project_context",
                "search_issues",
                "get_issue",
                "create_issue",
                "claim_issue",
                "update_issue_status",
                "add_comment",
                "log_work",
                "link_pr",
                "list_spaces",
                "get_page",
                "find_pages",
                "create_page",
                "update_page",
                "append_to_page",
                "report_progress",
                "request_gate",
                "report_result");
    }
}
