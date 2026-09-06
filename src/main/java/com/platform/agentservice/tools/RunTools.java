package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.run.GateKind;
import com.platform.agentservice.run.RunToolService;
import com.platform.agentservice.run.RunToolService.GateRequestResult;
import com.platform.agentservice.run.RunToolService.RunResultOutcome;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * run(에이전트 실행 한 건) 보고 도구 3종(P2a T2). 워커(헤드리스 Claude Code)는 디스패처가
 * run마다 발급한 임시 PAT({@link com.platform.agentservice.run.RunTokenService})로
 * {@code /api/agent/mcp}에 인증하므로, {@link ToolActor#current()}가 곧 그 run이 배정된
 * 페르소나가 된다. 소유·상태 검증({@code run.personaId == 호출자.personaId}, 상태==RUNNING)은
 * {@link RunToolService}에 위임하고, 이 클래스는 ALM 이슈 코멘트 작성 + {@link Audited} 감사만
 * 담당한다(도구를 얇게 유지, T2 디스패치 지시).
 *
 * <p><b>코멘트는 best-effort(fix round 1 컨트롤러 판정)</b>: {@code request_gate}/
 * {@code report_result}는 run 상태 전이(Gate 생성·WAITING_APPROVAL·DONE/FAILED/BLOCKED)가
 * 커밋된 이후에만 ALM 코멘트를 시도한다 — 커밋된 상태가 진실의 원천이고, 코멘트는 사람에게 보내는
 * 부가 알림일 뿐이다. 코멘트가 실패해도 도구는 실패로 보고하지 않는다(실패로 보고하면 워커가
 * 재시도하다 이미 전이된 run에서 {@code ConflictException}을 맞아 상태가 어긋난다) — 대신 성공
 * 텍스트에 경고 문구를 덧붙이고, {@link Audited#note}로 {@code <tool>.comment} 이름의 별도 ERROR
 * 감사 행을 남겨 누락을 추적 가능하게 한다. {@code report_progress}는 상태 변경이 없으므로 코멘트
 * 실패가 곧 도구 실패다 — 이 예외는 그대로 둔다.
 */
@Component
public class RunTools {

    private final RunToolService runToolService;
    private final AlmClient almClient;
    private final TokenService tokenService;
    private final Audited audited;

    public RunTools(RunToolService runToolService, AlmClient almClient, TokenService tokenService, Audited audited) {
        this.runToolService = runToolService;
        this.almClient = almClient;
        this.tokenService = tokenService;
        this.audited = audited;
    }

    @McpTool(name = "report_progress", description = "진행 중인(RUNNING) run의 진행상황을 이슈 코멘트로 남긴다.")
    public String reportProgress(
            @McpToolParam(description = "run id", required = true) long runId,
            @McpToolParam(description = "진행 메시지", required = true) String message) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("report_progress", "run=" + runId + " " + message, () -> {
            String issueKey = runToolService.requireRunningIssueKey(runId, actor.personaId());
            addComment(issueKey, "🤖 진행: " + message, actor);
            return "진행상황 기록 완료";
        });
    }

    @McpTool(name = "request_gate", description = "run을 승인 대기(WAITING_APPROVAL)로 전환하고 사람 승인을 요청한다(kind: MERGE|ESCALATION|PLAN).")
    public String requestGate(
            @McpToolParam(description = "run id", required = true) long runId,
            @McpToolParam(description = "게이트 종류: MERGE|ESCALATION|PLAN", required = true) String kind,
            @McpToolParam(description = "승인 요청 내용", required = true) String request) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("request_gate", "run=" + runId + " kind=" + kind, () -> {
            GateKind gateKind = parseGateKind(kind);
            GateRequestResult result = runToolService.requestGate(runId, actor.personaId(), gateKind, request);
            String warning = commentBestEffort(
                    "request_gate", result.issueKey(), "⏸ 승인 대기(" + gateKind + "): " + request, actor);
            return "게이트 등록됨(gate id=" + result.gateId() + ") — 작업 상태를 이슈·위키에 저장했으면 이제 종료하라. "
                    + "승인 후 새 run이 기록을 읽고 이어받는다." + warning;
        });
    }

    @McpTool(name = "report_result", description = "run을 종결한다(status: DONE|FAILED|BLOCKED).")
    public String reportResult(
            @McpToolParam(description = "run id", required = true) long runId,
            @McpToolParam(description = "종결 상태: DONE|FAILED|BLOCKED", required = true) String status,
            @McpToolParam(description = "요약/사유", required = true) String summary) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("report_result", "run=" + runId + " status=" + status, () -> {
            String normalized = status == null ? "" : status.toUpperCase();
            RunResultOutcome outcome = applyOutcome(runId, actor.personaId(), normalized, summary);
            String warning = commentBestEffort("report_result", outcome.issueKey(), commentFor(normalized, summary), actor);
            return "run 종결 기록 완료: " + normalized + warning;
        });
    }

    private RunResultOutcome applyOutcome(long runId, long callerPersonaId, String status, String summary) {
        return switch (status) {
            case "DONE" -> runToolService.completeRun(runId, callerPersonaId);
            case "FAILED" -> runToolService.failRun(runId, callerPersonaId, summary);
            case "BLOCKED" -> runToolService.blockRun(runId, callerPersonaId, summary);
            default -> throw new IllegalArgumentException("알 수 없는 종결 상태(DONE|FAILED|BLOCKED만 허용): " + status);
        };
    }

    private String commentFor(String status, String summary) {
        return switch (status) {
            case "DONE" -> "✅ 완료: " + summary;
            case "FAILED" -> "❌ 실패: " + summary;
            case "BLOCKED" -> "⛔ 차단: " + summary;
            default -> throw new IllegalArgumentException("알 수 없는 종결 상태: " + status);
        };
    }

    private GateKind parseGateKind(String kind) {
        try {
            return GateKind.valueOf(kind == null ? "" : kind.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 게이트 종류(MERGE|ESCALATION|PLAN만 허용): " + kind);
        }
    }

    private void addComment(String issueKey, String body, PatPrincipal actor) {
        String bearer = tokenService.bearerFor(actor.personaMemberId());
        IssueResponse issue = almClient.getByKey(issueKey, bearer);
        almClient.addComment(issue.id(), body, bearer);
    }

    /**
     * {@code request_gate}/{@code report_result} 전용: run 상태 전이가 이미 커밋된 뒤
     * 시도하는 ALM 코멘트다. 실패해도 도구 전체를 실패로 만들지 않는다(상태가 진실의 원천) —
     * {@code <tool>.comment} 이름으로 별도 ERROR 감사 행만 남기고, 성공 텍스트에 덧붙일 경고
     * 문구를 반환한다(성공 시 빈 문자열).
     */
    private String commentBestEffort(String tool, String issueKey, String body, PatPrincipal actor) {
        try {
            addComment(issueKey, body, actor);
            return "";
        } catch (Exception e) {
            audited.note(tool + ".comment", issueKey + ": " + e.getMessage(), AuditStatus.ERROR);
            return "\n(경고: 이슈 코멘트 기록 실패 — 상태는 저장됨, 사람 알림 누락 가능)";
        }
    }
}
