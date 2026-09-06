package com.platform.agentservice.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 워커 런처 설정(P2a T3) — {@code platform.agent.worker.*}. 전부 env로 오버라이드 가능하다
 * (application.yml 기본값은 로컬 dev 전용). {@link #repos()}는 프로젝트키→git URL 매핑으로,
 * 이슈의 라벨/프로젝트에서 어느 리포를 clone할지 T4(디스패처)가 여기서 조회한다 — 이 태스크는
 * 매핑 자체만 들고 있고 조회 로직은 갖지 않는다(호출자가 {@code WorkerJob.repoUrl()}로 이미
 * 해석해서 넘겨준다).
 *
 * <p><b>F3 fix(task-7 E2E 실측)</b>: env var로 이 맵을 주입하면
 * ({@code PLATFORM_AGENT_WORKER_REPOS_AGP=...}) Spring Boot relaxed binding이 키를
 * 소문자로 접어버린다({@code repos.get("agp")}, {@code "AGP"} 아님) — ALM 프로젝트 키는
 * 항상 대문자라 이 경로로 주입하면 매번 "리포 매핑 없음"이 재현된다(실측, 12회 반복
 * 실패). {@link #repos()}를 직접 {@code .get()}하지 말고 반드시 {@link #repoFor(String)}로
 * 조회한다 — 대소문자 무관 매칭이라 YAML(대문자 키 보존)·프로그램 인자(대문자 보존)·env
 * var(소문자로 접힘) 세 경로 어느 쪽으로 주입해도 안전하다.
 */
@ConfigurationProperties(prefix = "platform.agent.worker")
public record WorkerProperties(
        String workDir,
        String harnessBundlePath,
        List<String> harnessRootFiles,
        String claudeBin,
        int maxTurns,
        int timeoutMinutes,
        String allowedTools,
        String mcpUrl,
        Map<String, String> repos
) {

    /** {@link #repos()}를 프로젝트 키로 대소문자 무관하게 조회한다(F3) — 없으면 {@code null}. */
    public String repoFor(String projectKey) {
        if (projectKey == null || repos == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : repos.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(projectKey)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
