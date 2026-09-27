package com.platform.agentservice.runner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /api/agent/runners/runs/{id}/result} — {@code WorkerExecution}이 만든 {@code WorkerResult}를 그대로 옮긴 모양 + 러너가
 * 자기 워크스페이스에서 {@code CommitLinkParser}로 뽑은 커밋 링크. 서버는 러너 디스크를 볼 수 없으므로 커밋 파싱은 러너 몫이다.
 *
 * <p>폭: {@code sessionId}는 {@code run.session_id VARCHAR(80)}, {@code workspacePath}는 {@code run.workspace_path VARCHAR(400)}과 같다.
 * {@code rawTail}은 서버가 뒤 2000자로, {@code model}은 60자로 자른다. 숫자·불리언은 박싱 타입이다 — Jackson 3는 누락된 원시 타입을
 * 거부하므로(FAIL_ON_NULL_FOR_PRIMITIVES 기본 on) 선택 필드는 생략 가능하게 둔다({@code exitCode}만 필수).
 */
public record RunnerResultRequest(
        @NotNull(message = "exitCode는 필수입니다") Integer exitCode,
        Boolean timedOut,
        String resultText,
        @Size(max = 80, message = "sessionId는 80자 이하여야 합니다") String sessionId,
        @PositiveOrZero(message = "costUsd는 0 이상이어야 합니다") BigDecimal costUsd,
        @PositiveOrZero(message = "inputTokens는 0 이상이어야 합니다") Long inputTokens,
        @PositiveOrZero(message = "outputTokens는 0 이상이어야 합니다") Long outputTokens,
        String model,
        String rawTail,
        @Size(max = 400, message = "workspacePath는 400자 이하여야 합니다") String workspacePath,
        @Valid @Size(max = 100, message = "commits는 100건 이하여야 합니다") List<CommitReport> commits
) {

    /** {@code CommitLinkParser.CommitLink}와 같은 모양. {@code url}이 null이면 서버가 링크 등록을 건너뛴다(서버 파서와 같은 규칙). */
    public record CommitReport(
            @Size(max = 40) String issueKey,
            @Size(max = 64) String sha,
            @Size(max = 500) String subject,
            @Size(max = 500) String url) {
    }
}
