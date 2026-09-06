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
}
