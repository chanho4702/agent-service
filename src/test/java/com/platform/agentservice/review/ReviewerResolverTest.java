package com.platform.agentservice.review;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 리뷰어 해석 순서(D-P4b-1): 프로젝트 설정 &gt; 전역 설정 &gt; env &gt; 자동 &gt; 없음, 명시 지정이 깨지면 없음. */
@ExtendWith(MockitoExtension.class)
class ReviewerResolverTest {

    static final long PROJECT = 7L;

    @Mock ReviewSettingRepository settings;
    @Mock PersonaRepository personas;

    private final Persona sharedReviewer = persona(10L, "sora", PersonaRole.REVIEWER, null, true);
    private final Persona projectReviewer = persona(11L, "yuna", PersonaRole.REVIEWER, PROJECT, true);
    private final Persona otherProjectReviewer = persona(12L, "mina", PersonaRole.REVIEWER, 8L, true);
    private final Persona envReviewer = persona(13L, "envy", PersonaRole.BACKEND, null, true);

    private static Persona persona(long id, String slug, PersonaRole role, Long projectId, boolean active) {
        Persona p = Persona.of(100L + id, slug, role, slug + "-name", null, null, projectId);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "active", active);
        return p;
    }

    private ReviewerResolver resolver(String envSlug) {
        return resolver(envSlug, true);
    }

    private ReviewerResolver resolver(String envSlug, boolean enabled) {
        return new ReviewerResolver(settings, personas, new ReviewProperties(enabled, envSlug, null));
    }

    private void byId(Persona... ps) {
        for (Persona p : ps) {
            lenient().when(personas.findById(p.getId())).thenReturn(Optional.of(p));
        }
    }

    private void projectSetting(long personaId) {
        when(settings.findByScopeAndScopeId(ReviewScope.PROJECT, PROJECT)).thenReturn(Optional.of(
                ReviewSetting.of(ReviewScope.PROJECT, PROJECT, personaId, 1L, Instant.now())));
    }

    private void platformSetting(long personaId) {
        when(settings.findByScopeAndScopeIdIsNull(ReviewScope.PLATFORM)).thenReturn(Optional.of(
                ReviewSetting.of(ReviewScope.PLATFORM, null, personaId, 1L, Instant.now())));
    }

    private void reviewers(Persona... ps) {
        lenient().when(personas.findByRoleAndActiveTrueOrderByIdAsc(PersonaRole.REVIEWER)).thenReturn(List.of(ps));
    }

    @Test
    void project_setting_wins_over_platform_env_and_auto() {
        byId(projectReviewer, sharedReviewer);
        projectSetting(projectReviewer.getId());

        ReviewerResolver.Resolution r = resolver("envy").resolve(PROJECT);

        assertThat(r.persona()).isSameAs(projectReviewer);
        assertThat(r.source()).isEqualTo(ReviewerSource.PROJECT);
    }

    @Test
    void platform_setting_applies_when_the_project_has_none() {
        byId(sharedReviewer);
        platformSetting(sharedReviewer.getId());

        ReviewerResolver.Resolution r = resolver("envy").resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.PLATFORM);
        assertThat(r.persona()).isSameAs(sharedReviewer);
    }

    @Test
    void env_slug_is_the_fallback_below_db_settings_with_p2c_semantics_role_not_checked() {
        when(personas.findBySlug("envy")).thenReturn(Optional.of(envReviewer));

        ReviewerResolver.Resolution r = resolver(" envy ").resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.ENV);
        assertThat(r.persona()).isSameAs(envReviewer);
    }

    @Test
    void broken_env_slug_is_none_with_the_existing_message_not_auto() {
        reviewers(sharedReviewer);
        when(personas.findBySlug("ghost")).thenReturn(Optional.empty());

        ReviewerResolver.Resolution r = resolver("ghost").resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.NONE);
        assertThat(r.persona()).isNull();
        assertThat(r.problem()).isEqualTo("리뷰어 페르소나를 찾을 수 없거나 비활성입니다: ghost");
    }

    @Test
    void broken_explicit_setting_is_none_and_does_not_silently_fall_through() {
        Persona retired = persona(20L, "old", PersonaRole.REVIEWER, null, false);
        byId(retired, sharedReviewer);
        reviewers(sharedReviewer);
        projectSetting(retired.getId());

        ReviewerResolver.Resolution r = resolver(null).resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.NONE);
        assertThat(r.problem()).contains("프로젝트 리뷰어 지정(페르소나 id=20)").contains("다시 지정");
    }

    @Test
    void a_setting_pointing_at_another_projects_persona_is_unusable_at_resolve_time() {
        byId(otherProjectReviewer);
        platformSetting(otherProjectReviewer.getId());

        assertThat(resolver(null).resolve(PROJECT).source()).isEqualTo(ReviewerSource.NONE);
    }

    @Test
    void auto_prefers_a_project_reviewer_then_shared_lowest_id_and_skips_the_worker() {
        Persona sharedLow = persona(5L, "aaa", PersonaRole.REVIEWER, null, true);
        reviewers(sharedLow, sharedReviewer, projectReviewer, otherProjectReviewer);

        assertThat(resolver(null).resolve(PROJECT).persona()).isSameAs(projectReviewer);
        assertThat(resolver(null).resolve(PROJECT).source()).isEqualTo(ReviewerSource.AUTO);
        // 소속 리뷰어가 작업자면 후보에서 빠지고 공용 id 최소로 간다.
        assertThat(resolver(null).resolve(PROJECT, projectReviewer.getId()).persona()).isSameAs(sharedLow);
        // 프로젝트 축이 없는 전역 범위는 공용만.
        assertThat(resolver(null).resolve(null).persona()).isSameAs(sharedLow);
        // 다른 프로젝트 소속만 있으면 없음.
        reviewers(otherProjectReviewer);
        assertThat(resolver(null).resolve(PROJECT).source()).isEqualTo(ReviewerSource.NONE);
    }

    @Test
    void nothing_configured_and_no_reviewer_is_none_with_the_not_configured_reason() {
        reviewers();

        ReviewerResolver.Resolution r = resolver("").resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.NONE);
        assertThat(r.problem()).isEqualTo(ReviewerResolver.NOT_CONFIGURED);
    }

    @Test
    void review_ready_is_true_when_review_is_disabled_even_without_a_reviewer() {
        lenient().when(personas.findById(anyLong())).thenReturn(Optional.empty());
        reviewers();

        assertThat(resolver(null, false).reviewReady(PROJECT)).isTrue();
        assertThat(resolver(null, true).reviewReady(PROJECT)).isFalse();
        reviewers(sharedReviewer);
        assertThat(resolver(null, true).reviewReady(null)).isTrue();
    }

    @Test
    void env_only_resolver_ignores_the_settings_table() {
        reviewers(sharedReviewer);

        ReviewerResolver.Resolution r = ReviewerResolver.envOnly(personas, new ReviewProperties(true, null, null)).resolve(PROJECT);

        assertThat(r.source()).isEqualTo(ReviewerSource.AUTO);
        assertThat(r.persona()).isSameAs(sharedReviewer);
    }
}
