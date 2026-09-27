package com.platform.agentservice.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실행 위치 설정(P4a) — {@code platform.agent.execution.*}.
 *
 * <p>{@link #defaultSite()}는 요청·프로젝트 설정이 없을 때의 전역 기본(D-P4-1, 기본 SERVER). {@link #inProcess()}는 SERVER run을
 * agent-service 프로세스 안에서 직접 띄울지(D-P4-5): true(기본)는 호스트에서 도는 로컬 dev 구성, compose 배포는 false —
 * 이때 SERVER run은 QUEUED로 남아 PLATFORM 러너 컨테이너가 claim해 간다(워커를 비밀이 든 서비스 컨테이너 안에서 돌리지 않는다).
 */
@ConfigurationProperties(prefix = "platform.agent.execution")
public record ExecutionProperties(String defaultSite, Boolean inProcess) {

    public ExecutionProperties {
        inProcess = inProcess == null ? Boolean.TRUE : inProcess;
    }

    /** 잘못된 값은 기동을 막지 않고 SERVER로 본다(설정 오타가 전체 run을 LOCAL 대기로 빠뜨리지 않게) — 해석기가 기동 시 warn한다. */
    public ExecutionSite defaultSiteOrServer() {
        try {
            ExecutionSite parsed = ExecutionSite.parseOrNull(defaultSite);
            return parsed == null ? ExecutionSite.SERVER : parsed;
        } catch (IllegalArgumentException e) {
            return ExecutionSite.SERVER;
        }
    }

    /** 설정값이 비어 있지 않은데 SERVER·LOCAL로 해석되지 않는가(기동 warn용). */
    public boolean defaultSiteInvalid() {
        try {
            ExecutionSite.parseOrNull(defaultSite);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public boolean inProcessEnabled() {
        return Boolean.TRUE.equals(inProcess);
    }
}
