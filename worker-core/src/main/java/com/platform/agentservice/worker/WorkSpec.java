package com.platform.agentservice.worker;

/**
 * 워커 한 번 실행에 필요한 전부(P4a AGP-69) — {@code WorkerLauncher#buildSpec}이 run·이슈 컨텍스트에서 만들고 {@link WorkerExecution}이
 * 그대로 실행한다. DB·Spring을 모르는 순수 값이라 서버 인프로세스 실행과 러너(로컬·플랫폼) 실행이 같은 명세를 쓴다 — 러너는 claim
 * 응답으로 이 값을 받는다.
 *
 * <p>{@code workspaceLineage}+{@code inheritedWorkspacePath}: REVIEW·반려-fix(와 그 재개)는 새로 clone하지 않고 부모 run의
 * 워크스페이스를 이어 쓴다(D-P2c-1) — 경로가 없거나 사라졌으면 clone으로 대신하지 않고 실패한다. {@code meeting}: 회의 계열은
 * clone 없이 빈 워크스페이스 + 하네스만(D-P3b-2). {@code prompt}는 서버가 완성해 보낸다(러너가 프롬프트 규약을 복제하지 않게).
 */
public record WorkSpec(
        long runId,
        String runType,
        boolean workspaceLineage,
        String inheritedWorkspacePath,
        boolean meeting,
        String repoUrl,
        String prompt,
        String model,
        int maxTurns,
        String allowedTools,
        int timeoutMinutes,
        String mcpUrl,
        WorkerJob.Expertise expertise
) {

    /** MCP 주소만 바꾼 사본 — 러너는 서버가 보낸 주소 대신 자기 {@code --server} 기준 주소를 쓴다(플랫폼 러너는 내부 nginx, PC 러너는 공개 주소). */
    public WorkSpec withMcpUrl(String url) {
        return new WorkSpec(runId, runType, workspaceLineage, inheritedWorkspacePath, meeting, repoUrl, prompt, model, maxTurns,
                allowedTools, timeoutMinutes, url, expertise);
    }

    /** 프롬프트는 이슈 본문·코멘트를 담는다 — 로그 한 줄로 통째 새지 않게 요약만. */
    @Override
    public String toString() {
        return "WorkSpec{runId=" + runId + ", runType=" + runType + ", lineage=" + workspaceLineage + ", meeting=" + meeting
                + ", model=" + model + "}";
    }
}
