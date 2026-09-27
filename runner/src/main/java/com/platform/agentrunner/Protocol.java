package com.platform.agentrunner;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platform.agentservice.worker.WorkSpec;

import java.math.BigDecimal;
import java.util.List;

/**
 * 러너 프로토콜 모양(서버 {@code runner.dto.*}와 같은 필드 — CLAUDE.md §13). 서버가 필드를 더해도 러너가 깨지지 않게 모르는 필드는
 * 무시한다({@link RunnerApi}의 매퍼 설정).
 */
final class Protocol {

    private Protocol() {
    }

    record HeartbeatRequest(String version, String os, Integer maxConcurrency, List<Long> runIds) {
    }

    record HeartbeatResponse(long runnerId, String kind, String serverTime, List<Long> stopRunIds, long offlineAfterSeconds) {
        List<Long> stopRunIdsOrEmpty() {
            return stopRunIds == null ? List.of() : stopRunIds;
        }
    }

    /**
     * claim 200 응답. {@code runToken}은 mcp-config 파일에만, {@code llmApiKey}(PLATFORM 러너만)는 워커 env {@code ANTHROPIC_API_KEY}에만
     * 들어간다. {@code toString}은 둘 다·프롬프트를 싣지 않는다.
     */
    record ClaimResponse(long runId, String issueKey, Long projectId, Integer attempt, String executionSite, WorkSpec spec,
                         String runToken, String llmApiKey, String credentialScope, Harness harness, Budget budget) {

        @Override
        public String toString() {
            return "ClaimResponse{runId=" + runId + ", issueKey=" + issueKey + ", executionSite=" + executionSite
                    + ", credentialScope=" + credentialScope + ", llmApiKey=" + (llmApiKey == null ? "none" : "set") + "}";
        }
    }

    record Harness(String sha256, String path) {
    }

    record Budget(BigDecimal perRunUsdCap, BigDecimal monthlyUsdCap) {
    }

    /** {@code POST /runs/{id}/result} 본문 — {@code WorkerResult} + 러너가 파싱한 커밋 링크. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResultReport(int exitCode, boolean timedOut, String resultText, String sessionId, BigDecimal costUsd,
                        long inputTokens, long outputTokens, String model, String rawTail, String workspacePath,
                        List<CommitReport> commits) {
    }

    record CommitReport(String issueKey, String sha, String subject, String url) {
    }

    record ResultResponse(boolean accepted, boolean duplicate, String runStatus) {
    }
}
