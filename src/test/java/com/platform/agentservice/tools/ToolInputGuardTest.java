package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditOrigin;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.pat.PatPrincipal;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.isNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AGP-38 — 도구 공통 입력 인코딩 방어: 정상 한글·이모지는 통과, 깨진 서로게이트·제어문자·U+FFFD는 거부. */
class ToolInputGuardTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void korean_emoji_newline_tab_and_cr_pass() {
        assertThat(ToolInputGuard.invalidReason("안녕하세요 🔧👩‍💻 결정:\n- 항목\t값\r\n끝")).isNull();
        assertThat(ToolInputGuard.findViolation(Map.of("body", "정상 본문 ✅", "labels", List.of("버그", "🐛")))).isNull();
    }

    @Test
    void lone_high_or_low_surrogate_is_rejected() {
        assertThat(ToolInputGuard.invalidReason("앞\uD83D뒤")).startsWith("깨진 서로게이트 U+D83D (2번째 문자)");
        assertThat(ToolInputGuard.invalidReason("끝\uD83D")).startsWith("깨진 서로게이트 U+D83D");
        assertThat(ToolInputGuard.invalidReason("\uDE00시작")).startsWith("깨진 서로게이트 U+DE00 (1번째 문자)");
    }

    @Test
    void control_characters_other_than_newline_tab_cr_are_rejected() {
        assertThat(ToolInputGuard.invalidReason("a\u0000b")).isEqualTo("제어문자 U+0000 (2번째 문자)");
        assertThat(ToolInputGuard.invalidReason("\u001B[31m")).startsWith("제어문자 U+001B");
        assertThat(ToolInputGuard.invalidReason("c1\u0085")).startsWith("제어문자 U+0085");
    }

    @Test
    void replacement_character_from_broken_utf8_decoding_is_rejected() {
        assertThat(ToolInputGuard.invalidReason("깨짐�")).contains("U+FFFD").contains("UTF-8이 아닌 입력");
    }

    @Test
    void nested_list_and_object_arguments_are_located_by_path() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("issueKey", "AGP-1");
        args.put("labels", List.of("ok", "bad\u0007"));
        ToolInputGuard.Violation v = ToolInputGuard.findViolation(args);

        assertThat(v.param()).isEqualTo("labels[1]");
        assertThat(v.message()).isEqualTo("입력 인코딩 거부 — 파라미터 'labels[1]': 제어문자 U+0007 (4번째 문자)");
        assertThat(ToolInputGuard.findViolation(Map.of("meta", Map.of("k", "\uD800"))).param()).isEqualTo("meta.k");
    }

    @Test
    void guard_rejects_without_calling_tool_and_records_error_audit_without_value() {
        AuditService auditService = mock(AuditService.class);
        ToolInputGuard guard = new ToolInputGuard(provider(new Audited(auditService)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new PatPrincipal(100L, 5L, 42L), null, List.of()));
        AtomicInteger calls = new AtomicInteger();

        McpServerFeatures.SyncToolSpecification guarded = guard.guard(spec("add_comment", calls));
        McpSchema.CallToolResult result = guarded.callHandler().apply(null,
                new McpSchema.CallToolRequest("add_comment", Map.of("issueKey", "AGP-1", "body", "x\u0000y")));

        assertThat(calls).hasValue(0);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .isEqualTo("오류: 입력 인코딩 거부 — 파라미터 'body': 제어문자 U+0000 (2번째 문자)");
        verify(auditService).record(eq(5L), eq(100L), eq("add_comment"), eq("입력 거부: body"), eq(AuditStatus.ERROR), eq(AuditOrigin.EXTERNAL), isNull());
    }

    @Test
    void guard_passes_valid_input_through_to_the_tool() {
        ToolInputGuard guard = new ToolInputGuard(provider(new Audited(mock(AuditService.class))));
        AtomicInteger calls = new AtomicInteger();

        McpSchema.CallToolResult result = guard.guard(spec("add_comment", calls)).callHandler().apply(null,
                new McpSchema.CallToolRequest("add_comment", Map.of("issueKey", "AGP-1", "body", "한글 본문 🙂")));

        assertThat(calls).hasValue(1);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("실행됨");
    }

    @Test
    void only_tool_specification_lists_are_wrapped() {
        ToolInputGuard guard = new ToolInputGuard(provider(new Audited(mock(AuditService.class))));
        List<String> other = List.of("a");

        assertThat(guard.postProcessAfterInitialization(other, "other")).isSameAs(other);
        Object wrapped = guard.postProcessAfterInitialization(List.of(spec("ping", new AtomicInteger())), "toolSpecs");
        assertThat(wrapped).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    private static McpServerFeatures.SyncToolSpecification spec(String name, AtomicInteger calls) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description(name).inputSchema(
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null)).build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            calls.incrementAndGet();
            return McpSchema.CallToolResult.builder().addTextContent("실행됨").build();
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Audited> provider(Audited audited) {
        ObjectProvider<Audited> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audited);
        return provider;
    }
}
