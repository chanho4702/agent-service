package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditOrigin;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.directive.RunDirective;
import com.platform.agentservice.directive.RunDirectiveService;
import com.platform.agentservice.pat.PatPrincipal;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AGP-67 — 실행 중 지시는 run 토큰 호출의 도구 결과 끝에만 붙는다(오류·입력 거부 결과 포함). 사람용 PAT에는 절대 안 붙는다. */
class DirectiveDeliveryTest {

    private final RunDirectiveService directiveService = mock(RunDirectiveService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final DirectiveDelivery delivery = new DirectiveDelivery(directiveService, new Audited(auditService));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(PatPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static RunDirective directive(long runId, String text) {
        return RunDirective.of(runId, text, 7L);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(result.content().size() - 1)).text();
    }

    @Test
    void run_token_result_gets_directives_appended_oldest_first_and_a_textless_audit() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 812L));
        when(directiveService.claimUndelivered(812L)).thenReturn(List.of(directive(812L, "테스트부터 고쳐"), directive(812L, "PR은 작게")));

        McpSchema.CallToolResult result = delivery.deliver(McpSchema.CallToolResult.builder().addTextContent("코멘트 등록 완료 (id=3)").build());

        assertThat(text(result)).isEqualTo("코멘트 등록 완료 (id=3)\n\n[사람 지시 — 지금 반영하라]\n- 테스트부터 고쳐\n- PR은 작게");
        assertThat(result.content()).hasSize(1);
        verify(auditService).record(eq(5L), eq(0L), eq("directive.deliver"), eq("directive delivered run=812 count=2"),
                eq(AuditStatus.OK), eq(AuditOrigin.WORKER), eq(812L));
    }

    @Test
    void error_results_carry_directives_and_keep_is_error() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 812L));
        when(directiveService.claimUndelivered(812L)).thenReturn(List.of(directive(812L, "멈추고 보고해")));

        McpSchema.CallToolResult result = delivery.deliver(
                McpSchema.CallToolResult.builder().addTextContent("오류: 없음").isError(true).build());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).endsWith("[사람 지시 — 지금 반영하라]\n- 멈추고 보고해");
    }

    @Test
    void human_pat_never_receives_directives_and_does_not_touch_the_queue() {
        authenticate(new PatPrincipal(100L, 5L, 42L));
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder().addTextContent("ok").build();

        assertThat(delivery.deliver(original)).isSameAs(original);
        // 사람이 label을 run:으로 지어도 run 토큰이 아니면(runToken=false) 같다.
        authenticate(new PatPrincipal(100L, 5L, 42L, false, 812L));
        assertThat(delivery.deliver(original)).isSameAs(original);
        SecurityContextHolder.clearContext();
        assertThat(delivery.deliver(original)).isSameAs(original);

        verifyNoInteractions(directiveService, auditService);
    }

    @Test
    void nothing_pending_returns_the_same_result_without_audit() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 812L));
        when(directiveService.claimUndelivered(812L)).thenReturn(List.of());
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder().addTextContent("ok").build();

        assertThat(delivery.deliver(original)).isSameAs(original);
        verifyNoInteractions(auditService);
    }

    @Test
    void claim_failure_leaves_the_tool_result_intact() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 812L));
        when(directiveService.claimUndelivered(anyLong())).thenThrow(new IllegalStateException("db down"));
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder().addTextContent("ok").build();

        assertThat(delivery.deliver(original)).isSameAs(original);
        verifyNoInteractions(auditService);
    }

    @Test
    void result_without_text_content_gets_a_new_text_item() {
        McpSchema.CallToolResult appended = DirectiveDelivery.append(McpSchema.CallToolResult.builder().content(List.of()).build(),
                "\n\n[사람 지시 — 지금 반영하라]\n- x");

        assertThat(appended.content()).singleElement().extracting(c -> ((McpSchema.TextContent) c).text())
                .isEqualTo("[사람 지시 — 지금 반영하라]\n- x");
    }

    /** 단일 지점: ToolInputGuard가 감싼 모든 도구(입력 거부 결과 포함)가 전달을 거친다. */
    @Test
    void tool_input_guard_routes_every_result_including_rejections_through_delivery() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 812L));
        when(directiveService.claimUndelivered(812L))
                .thenReturn(List.of(directive(812L, "첫 지시")))
                .thenReturn(List.of(directive(812L, "둘째 지시")));
        ToolInputGuard guard = new ToolInputGuard(provider(new Audited(auditService)), provider(delivery));
        McpSchema.Tool tool = McpSchema.Tool.builder().name("add_comment").description("d").inputSchema(
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null)).build();
        McpServerFeatures.SyncToolSpecification guarded = guard.guard(new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("실행됨").build()));

        McpSchema.CallToolResult ok = guarded.callHandler().apply(null,
                new McpSchema.CallToolRequest("add_comment", Map.of("body", "정상")));
        McpSchema.CallToolResult rejected = guarded.callHandler().apply(null,
                new McpSchema.CallToolRequest("add_comment", Map.of("body", "x\u0000y")));

        assertThat(text(ok)).isEqualTo("실행됨\n\n[사람 지시 — 지금 반영하라]\n- 첫 지시");
        assertThat(text(rejected)).startsWith("오류: 입력 인코딩 거부").endsWith("[사람 지시 — 지금 반영하라]\n- 둘째 지시");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
