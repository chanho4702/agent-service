package com.platform.agentservice.run;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchedulerProperties#modelFor} — 모델 정책 해석 순서(D-P2c-5)와, env var 주입 시 소문자로
 * 접힌 맵 키로도 대문자 프로젝트 키를 찾는지(F3와 같은 함정)를 본다.
 */
class SchedulerPropertiesTest {

    private SchedulerProperties properties(String defaultModel, Map<String, String> projectModels) {
        return new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3, defaultModel, projectModels);
    }

    @Test
    void project_model_wins_over_global_default() {
        SchedulerProperties p = properties("claude-sonnet-5", Map.of("AGP", "claude-opus-5-5"));

        assertThat(p.modelFor("AGP")).isEqualTo("claude-opus-5-5");
    }

    @Test
    void project_lookup_matches_lowercase_key_folded_by_env_binding() {
        SchedulerProperties p = properties(null, Map.of("agp", "claude-opus-5-5"));

        assertThat(p.modelFor("AGP")).isEqualTo("claude-opus-5-5");
    }

    @Test
    void falls_back_to_global_default_when_project_has_no_entry() {
        SchedulerProperties p = properties("claude-sonnet-5", Map.of("OTHER", "claude-opus-5-5"));

        assertThat(p.modelFor("AGP")).isEqualTo("claude-sonnet-5");
    }

    @Test
    void returns_null_when_nothing_is_configured_so_the_worker_default_applies() {
        assertThat(properties(null, null).modelFor("AGP")).isNull();
        assertThat(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3).modelFor("AGP")).isNull();
    }

    @Test
    void blank_values_count_as_unset() {
        // ${SCHEDULER_DEFAULT_MODEL:} 같은 빈 기본값이 --model "" 로 새면 안 된다.
        assertThat(properties("", null).modelFor("AGP")).isNull();
        assertThat(properties("claude-sonnet-5", Map.of("AGP", " ")).modelFor("AGP")).isEqualTo("claude-sonnet-5");
    }

    @Test
    void null_project_key_uses_global_default() {
        assertThat(properties("claude-sonnet-5", Map.of("AGP", "claude-opus-5-5")).modelFor(null))
                .isEqualTo("claude-sonnet-5");
    }
}
