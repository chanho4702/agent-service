package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.directive.RunDirective;
import com.platform.agentservice.directive.RunDirectiveService;
import com.platform.agentservice.pat.PatPrincipal;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 중 지시 전달(AGP-67) — {@link ToolInputGuard}가 모든 도구 결과(오류 결과·입력 거부 포함)를 여기로 넘긴다. 호출을 인증한 토큰이
 * run 토큰({@link PatPrincipal#runToken()})이면 그 run의 미전달 지시를 원자적으로 가져가 결과 텍스트 끝에 붙인다. 사람용 PAT(EXTERNAL)은
 * run이 없으니 절대 받지 않는다.
 *
 * <p>best-effort: 가져오기가 실패하면 원래 결과를 그대로 돌려준다(도구 결과를 지시 때문에 망치지 않는다 — 지시는 미전달로 남아 다음
 * 호출에 다시 시도된다). 감사는 {@code directive.deliver} 한 행, summary에 본문을 싣지 않는다.
 */
@Slf4j
@Component
public class DirectiveDelivery {

    static final String HEADER = "[사람 지시 — 지금 반영하라]";
    static final String AUDIT_TOOL = "directive.deliver";

    private final RunDirectiveService directiveService;
    private final Audited audited;

    public DirectiveDelivery(RunDirectiveService directiveService, Audited audited) {
        this.directiveService = directiveService;
        this.audited = audited;
    }

    public McpSchema.CallToolResult deliver(McpSchema.CallToolResult result) {
        PatPrincipal actor = currentActor();
        if (actor == null || !actor.runToken() || actor.runId() == null) {
            return result;
        }
        long runId = actor.runId();
        List<RunDirective> directives;
        try {
            directives = directiveService.claimUndelivered(runId);
        } catch (Exception e) {
            log.warn("run={} 실행 중 지시 전달 실패 — 미전달로 남겨 다음 도구 호출에 다시 시도합니다: {}", runId, e.getMessage());
            return result;
        }
        if (directives.isEmpty()) {
            return result;
        }
        try {
            audited.note(AUDIT_TOOL, "directive delivered run=" + runId + " count=" + directives.size(), AuditStatus.OK);
        } catch (Exception e) {
            log.warn("run={} 지시 전달 감사 기록 실패(전달은 진행): {}", runId, e.getMessage());
        }
        return append(result, suffix(directives));
    }

    /** 오래된 것 먼저, 한 줄에 하나({@code - <본문>}). */
    static String suffix(List<RunDirective> directives) {
        StringBuilder sb = new StringBuilder("\n\n").append(HEADER);
        for (RunDirective d : directives) {
            sb.append("\n- ").append(d.getText());
        }
        return sb.toString();
    }

    /** 마지막 텍스트 항목 끝에 붙인다. 텍스트 항목이 없으면 새 항목으로. isError·structuredContent·meta는 그대로. */
    static McpSchema.CallToolResult append(McpSchema.CallToolResult result, String suffix) {
        List<McpSchema.Content> content = new ArrayList<>(result.content() == null ? List.of() : result.content());
        int last = -1;
        for (int i = content.size() - 1; i >= 0; i--) {
            if (content.get(i) instanceof McpSchema.TextContent) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            content.add(McpSchema.TextContent.builder(suffix.strip()).build());
        } else {
            McpSchema.TextContent text = (McpSchema.TextContent) content.get(last);
            content.set(last, McpSchema.TextContent.builder(text.text() + suffix)
                    .annotations(text.annotations()).meta(text.meta()).build());
        }
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder().content(content)
                .isError(result.isError()).meta(result.meta());
        if (result.structuredContent() != null) {
            builder.structuredContent(result.structuredContent());
        }
        return builder.build();
    }

    private static PatPrincipal currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PatPrincipal principal ? principal : null;
    }
}
