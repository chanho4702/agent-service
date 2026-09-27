package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Map;

/**
 * 디스패처 스케줄러 설정(P2a T4) — {@code platform.agent.scheduler.*}. {@link #enabled()}
 * 기본값은 반드시 false다(킬스위치) — 무인 루프가 실수로 켜진 채 배포되지 않게 한다.
 * <p><b>{@link #retryMaxAttempts()}는 폐기됐다(P3d, 2026-09-27 AGP-55)</b> — 사고형 실패는 재시도 없이 즉시 BLOCKED이고
 * 리뷰 반려 예산은 {@link ReviewProperties#rejectMax()}가 따로 센다. 기존 배포의 {@code SCHEDULER_RETRY_MAX_ATTEMPTS}가
 * 기동을 깨지 않도록 바인딩만 남겨 두며, 코드는 이 값을 읽지 않는다.
 *
 * <p>{@link #defaultModel()}·{@link #projectModels()}는 자동화 run의 모델 정책이다(P2c, D-P2c-5).
 * {@link #projectModels()}는 {@code WorkerProperties.repos()}와 같은 이유(env var 주입 시 Spring
 * relaxed binding이 키를 소문자로 접는다, F3)로 직접 {@code .get()}하지 말고 {@link #modelFor}로 조회한다.
 */
@ConfigurationProperties(prefix = "platform.agent.scheduler")
public record SchedulerProperties(
        boolean enabled,
        long intervalMs,
        int maxConcurrentGlobal,
        int maxConcurrentPerProject,
        String defaultPersonaSlug,
        int retryMaxAttempts,
        String defaultModel,
        Map<String, String> projectModels,
        int maxPickPages
) {

    /** 새 이슈 픽업이 한 틱에 순회하는 검색 페이지 상한의 기본값(AGP-52) — 걸러지는 이슈가 쌓여도 ALM 호출이 폭주하지 않게 묶는다. */
    public static final int DEFAULT_MAX_PICK_PAGES = 5;

    /** 생성자가 여럿이라 바인딩 대상을 명시해야 한다 — 없으면 Spring Boot가 어느 쪽으로 바인딩할지 정하지 못한다. */
    @ConstructorBinding
    public SchedulerProperties {
        // 미설정(0)이나 음수를 "순회 안 함"으로 받으면 page-0 기아(AGP-52)가 조용히 되살아난다.
        if (maxPickPages <= 0) {
            maxPickPages = DEFAULT_MAX_PICK_PAGES;
        }
    }

    /** 픽업 페이지 상한을 따로 정하지 않는 설정(기존 호출부·테스트) — 기본 상한을 쓴다. */
    public SchedulerProperties(boolean enabled, long intervalMs, int maxConcurrentGlobal, int maxConcurrentPerProject,
                               String defaultPersonaSlug, int retryMaxAttempts, String defaultModel,
                               Map<String, String> projectModels) {
        this(enabled, intervalMs, maxConcurrentGlobal, maxConcurrentPerProject, defaultPersonaSlug, retryMaxAttempts,
                defaultModel, projectModels, DEFAULT_MAX_PICK_PAGES);
    }

    /** 모델 정책이 없는 설정(기존 호출부·테스트) — 워커 기본 모델을 쓴다. */
    public SchedulerProperties(boolean enabled, long intervalMs, int maxConcurrentGlobal, int maxConcurrentPerProject,
                               String defaultPersonaSlug, int retryMaxAttempts) {
        this(enabled, intervalMs, maxConcurrentGlobal, maxConcurrentPerProject, defaultPersonaSlug, retryMaxAttempts,
                null, null, DEFAULT_MAX_PICK_PAGES);
    }

    /**
     * 프로젝트별 맵(대소문자 무관) → 전역 기본 → {@code null}(워커 기본) 순으로 해석한다. 빈 문자열은
     * 미지정으로 본다 — {@code ${ENV:}} 형태의 빈 기본값이 {@code --model ""}로 새지 않게 한다.
     */
    public String modelFor(String projectKey) {
        if (projectKey != null && projectModels != null) {
            for (Map.Entry<String, String> entry : projectModels.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(projectKey)
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        return defaultModel != null && !defaultModel.isBlank() ? defaultModel : null;
    }
}
