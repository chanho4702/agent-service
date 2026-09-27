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

    /** AGP-62 — 해석 5단계: override > 페르소나 기본 > 프로젝트 맵 > 전역 기본 > null. 빈 값은 모든 단계에서 미지정. */
    @Test
    void resolve_model_walks_override_persona_project_global_then_null() {
        SchedulerProperties full = properties("claude-global", Map.of("agp", "claude-project"));

        assertThat(full.resolveModel(" claude-user ", "claude-persona", "AGP")).isEqualTo("claude-user");
        assertThat(full.resolveModel("  ", " claude-persona ", "AGP")).isEqualTo("claude-persona");
        assertThat(full.resolveModel(null, "", "AGP")).isEqualTo("claude-project");
        assertThat(full.resolveModel(null, null, "WEB")).isEqualTo("claude-global");
        assertThat(properties(" ", Map.of()).resolveModel(null, null, "AGP")).isNull();
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

    @Test
    void max_pick_pages_defaults_to_five_when_unset_or_non_positive() {
        // AGP-52: 바인딩에서 값이 빠져 0이 들어와도 "순회 안 함"이 되면 page-0 기아가 되살아난다.
        assertThat(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3).maxPickPages()).isEqualTo(5);
        assertThat(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3, null, null, 0).maxPickPages()).isEqualTo(5);
        assertThat(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3, null, null, -1).maxPickPages()).isEqualTo(5);
        assertThat(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3, null, null, 8).maxPickPages()).isEqualTo(8);
    }
}
