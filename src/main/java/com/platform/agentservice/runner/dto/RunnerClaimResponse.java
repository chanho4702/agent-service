package com.platform.agentservice.runner.dto;

import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.worker.WorkSpec;

import java.math.BigDecimal;

/**
 * {@code POST /api/agent/runners/claim} 200 응답 — 러너가 {@code WorkerExecution}으로 서버와 같은 절차를 재현하는 데 필요한 전부.
 *
 * <ul>
 *   <li>{@code spec}: 실행 명세(프롬프트 완성본·모델·도구 허용 목록·최대 턴·시간 제한·MCP 주소·승계 워크스페이스·페르소나 스킬).</li>
 *   <li>{@code runToken}: 이 run 전용 임시 PAT(agp_, label {@code run:<id>}) — mcp-config에만 넣는다. 결과 보고·생존 판정 실패·취소 시 서버가
 *       철회한다.</li>
 *   <li>{@code llmApiKey}: PLATFORM 러너에만 — 해석된 키(프로젝트 &gt; 전역 &gt; 서비스 env). 워커 env {@code ANTHROPIC_API_KEY} 하나로만
 *       싣는다. LOCAL 러너에는 항상 null(그 PC의 구독·자기 키 — 과금 분리, D-P4-5).</li>
 *   <li>{@code credentialScope}: 과금 출처 표시(PROJECT|PLATFORM|ENV|LOCAL) — 원장에 이 값이 남는다.</li>
 *   <li>{@code harness}: 하네스 번들 zip의 SHA-256과 내려받을 경로({@code GET /api/agent/runners/harness}) — 러너는 해시로 캐시한다.</li>
 *   <li>{@code budget}: run 1건 경고 상한·월 상한(USD) — 참고값. 상한 판정·경고 코멘트는 서버가 결과 보고 때 한다.</li>
 * </ul>
 *
 * <p>{@code toString}은 토큰·키·프롬프트를 싣지 않는다(로그 한 줄로 새는 경로를 구조로 막는다).
 */
public record RunnerClaimResponse(
        long runId,
        String issueKey,
        long projectId,
        int attempt,
        ExecutionSite executionSite,
        WorkSpec spec,
        String runToken,
        String llmApiKey,
        String credentialScope,
        Harness harness,
        Budget budget
) {

    public record Harness(String sha256, String path) {
    }

    public record Budget(BigDecimal perRunUsdCap, BigDecimal monthlyUsdCap) {
    }

    @Override
    public String toString() {
        return "RunnerClaimResponse{runId=" + runId + ", issueKey=" + issueKey + ", executionSite=" + executionSite
                + ", credentialScope=" + credentialScope + ", llmApiKey=" + (llmApiKey == null ? "none" : "set") + "}";
    }
}
