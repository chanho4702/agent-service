package com.platform.agentservice.review;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.review.dto.ReviewSettingResponse;
import com.platform.agentservice.run.ReviewProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 리뷰어 해석 단일 지점(D-P4b-1, AGP-59) — TASK DONE 뒤 검증 run을 맡을 페르소나. 순서:
 * <b>프로젝트 설정 &gt; 전역 설정 &gt; env {@code REVIEW_PERSONA} &gt; 자동 &gt; 없음</b>.
 *
 * <p><b>명시 지정이 깨졌으면 없음이다</b> — 설정(또는 env)이 가리키는 페르소나가 비활성·롤 변경·범위 밖이면 다음 단계로 내려가지 않고
 * NONE(사유 포함)으로 끝난다. 사람이 고른 리뷰어를 조용히 다른 페르소나로 바꾸면 "누가 승인했는가"가 흐려지고, NONE은 사무실
 * {@code reviewReady=false} 경고와 이슈 ⚠️ 코멘트로 드러난다. 자동 선택은 명시 지정이 하나도 없을 때만 탄다.
 *
 * <p>설정 지정은 활성·REVIEWER 롤·범위 호환(프로젝트 소속 페르소나는 그 프로젝트에서만, 공용은 어디서나)을 해석 때도 다시 본다 —
 * 저장 뒤 페르소나가 바뀔 수 있어서다. env는 P2c 의미 그대로(존재·활성만 — 롤을 보지 않는다).
 * 리뷰어가 작업자와 같은지는 run을 아는 {@code ReviewService}가 본다(자동 선택만 작업자를 후보에서 뺀다).
 */
@Service
public class ReviewerResolver {

    static final String INVALID_REVIEWER = "리뷰어로 지정할 수 없는 페르소나입니다 — 활성 REVIEWER 롤이고 이 범위에서 쓸 수 있는 "
            + "페르소나(프로젝트 소속은 그 프로젝트에서만, 공용은 어디서나)만 지정할 수 있습니다";
    static final String PERSONA_ID_REQUIRED = "personaId가 필요합니다";
    static final String NOT_CONFIGURED = "리뷰어 페르소나가 설정되지 않았습니다(AI 팀 설정의 리뷰어 지정 또는 "
            + "platform.agent.review.persona-slug) — 자동으로 고를 활성 REVIEWER 페르소나도 없습니다";

    /** {@code persona}가 null이면 source=NONE이고 {@code problem}이 그 사유다. */
    public record Resolution(Persona persona, ReviewerSource source, String problem) {

        static Resolution none(String problem) {
            return new Resolution(null, ReviewerSource.NONE, problem);
        }
    }

    private final ReviewSettingRepository settings;
    private final PersonaRepository personas;
    private final ReviewProperties properties;

    @Autowired
    public ReviewerResolver(ReviewSettingRepository settings, PersonaRepository personas, ReviewProperties properties) {
        this.settings = settings;
        this.personas = personas;
        this.properties = properties;
    }

    /** 설정 테이블 없이 env·자동만 보는 해석기 — DB 설정이 없던 P2c 시그니처의 단위 테스트용. */
    public static ReviewerResolver envOnly(PersonaRepository personas, ReviewProperties properties) {
        return new ReviewerResolver(null, personas, properties);
    }

    @Transactional(readOnly = true)
    public Resolution resolve(Long projectId) {
        return resolve(projectId, null);
    }

    /** {@code excludeFromAuto}(작업자 페르소나)는 자동 선택 후보에서만 뺀다 — 명시 지정이 작업자면 호출자가 fail-closed로 막는다. */
    @Transactional(readOnly = true)
    public Resolution resolve(Long projectId, Long excludeFromAuto) {
        if (projectId != null) {
            Optional<ReviewSetting> project = projectSetting(projectId);
            if (project.isPresent()) {
                return explicit(project.get(), ReviewerSource.PROJECT, projectId);
            }
        }
        Optional<ReviewSetting> platform = platformSetting();
        if (platform.isPresent()) {
            return explicit(platform.get(), ReviewerSource.PLATFORM, projectId);
        }
        String slug = properties.personaSlug();
        if (slug != null && !slug.isBlank()) {
            return personas.findBySlug(slug.trim()).filter(Persona::isActive)
                    .map(p -> new Resolution(p, ReviewerSource.ENV, null))
                    .orElseGet(() -> Resolution.none("리뷰어 페르소나를 찾을 수 없거나 비활성입니다: " + slug.trim()));
        }
        return auto(projectId, excludeFromAuto)
                .map(p -> new Resolution(p, ReviewerSource.AUTO, null))
                .orElseGet(() -> Resolution.none(NOT_CONFIGURED));
    }

    /** 사무실 경고 플래그 — 리뷰가 꺼져 있으면 검증 없이 작업자가 done하므로 준비된 것으로 본다. */
    @Transactional(readOnly = true)
    public boolean reviewReady(Long projectId) {
        return !properties.enabled() || resolve(projectId).persona() != null;
    }

    // ---- 설정 API ----

    @Transactional(readOnly = true)
    public ReviewSettingResponse platform() {
        return response(platformSetting(), null);
    }

    @Transactional(readOnly = true)
    public ReviewSettingResponse project(long projectId) {
        return response(projectSetting(projectId), projectId);
    }

    @Transactional
    public ReviewSettingResponse putPlatform(Long personaId, long actorId) {
        Persona persona = requireAssignable(personaId, null);
        Instant now = Instant.now();
        platformSetting().ifPresentOrElse(
                existing -> existing.change(persona.getId(), actorId, now),
                () -> settings.save(ReviewSetting.of(ReviewScope.PLATFORM, null, persona.getId(), actorId, now)));
        settings.flush();
        return platform();
    }

    @Transactional
    public ReviewSettingResponse putProject(long projectId, Long personaId, long actorId) {
        Persona persona = requireAssignable(personaId, projectId);
        Instant now = Instant.now();
        projectSetting(projectId).ifPresentOrElse(
                existing -> existing.change(persona.getId(), actorId, now),
                () -> settings.save(ReviewSetting.of(ReviewScope.PROJECT, projectId, persona.getId(), actorId, now)));
        settings.flush();
        return project(projectId);
    }

    /** 멱등 — 없어도 성공. 이미 떠 있는 REVIEW run의 리뷰어는 바꾸지 않는다. */
    @Transactional
    public void deletePlatform() {
        platformSetting().ifPresent(settings::delete);
    }

    @Transactional
    public void deleteProject(long projectId) {
        projectSetting(projectId).ifPresent(settings::delete);
    }

    // ---- 내부 ----

    /** 활성 · REVIEWER 롤 · 범위 호환. {@code projectId}가 null(전역 범위)이면 공용 페르소나만 맞는다. */
    static boolean assignableFor(Persona persona, Long projectId) {
        return persona.isActive() && persona.getRole() == PersonaRole.REVIEWER
                && (persona.getProjectId() == null || Objects.equals(persona.getProjectId(), projectId));
    }

    /** 없는 id도 같은 400 — 지정 가능 여부와 존재 여부를 구분해 주지 않는다. */
    private Persona requireAssignable(Long personaId, Long projectId) {
        if (personaId == null) {
            throw new IllegalArgumentException(PERSONA_ID_REQUIRED);
        }
        return personas.findById(personaId).filter(p -> assignableFor(p, projectId))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_REVIEWER));
    }

    private Resolution explicit(ReviewSetting setting, ReviewerSource source, Long projectId) {
        return personas.findById(setting.getPersonaId()).filter(p -> assignableFor(p, projectId))
                .map(p -> new Resolution(p, source, null))
                .orElseGet(() -> Resolution.none((source == ReviewerSource.PROJECT ? "프로젝트" : "전역")
                        + " 리뷰어 지정(페르소나 id=" + setting.getPersonaId()
                        + ")이 비활성이거나 REVIEWER가 아니거나 이 범위에서 쓸 수 없습니다 — AI 팀 설정에서 리뷰어를 다시 지정하세요"));
    }

    /** 그 프로젝트 소속 활성 REVIEWER가 있으면 id 최소, 없으면 공용 활성 REVIEWER id 최소. 전역 범위는 공용만. */
    private Optional<Persona> auto(Long projectId, Long exclude) {
        var candidates = personas.findByRoleAndActiveTrueOrderByIdAsc(PersonaRole.REVIEWER).stream()
                .filter(p -> !p.getId().equals(exclude))
                .toList();
        if (projectId != null) {
            Optional<Persona> own = candidates.stream().filter(p -> projectId.equals(p.getProjectId())).findFirst();
            if (own.isPresent()) {
                return own;
            }
        }
        return candidates.stream().filter(p -> p.getProjectId() == null).findFirst();
    }

    private ReviewSettingResponse response(Optional<ReviewSetting> stored, Long projectId) {
        ReviewSettingResponse.Setting setting = stored.map(s -> {
            Persona p = personas.findById(s.getPersonaId()).orElse(null);
            return new ReviewSettingResponse.Setting(s.getPersonaId(), p == null ? null : p.getSlug(),
                    p == null ? null : p.getName());
        }).orElse(null);
        Resolution r = resolve(projectId);
        Persona p = r.persona();
        return new ReviewSettingResponse(setting, new ReviewSettingResponse.Effective(
                p == null ? null : p.getId(), p == null ? null : p.getSlug(), p == null ? null : p.getName(), r.source()));
    }

    private Optional<ReviewSetting> platformSetting() {
        return settings == null ? Optional.empty() : settings.findByScopeAndScopeIdIsNull(ReviewScope.PLATFORM);
    }

    private Optional<ReviewSetting> projectSetting(long projectId) {
        return settings == null ? Optional.empty() : settings.findByScopeAndScopeId(ReviewScope.PROJECT, projectId);
    }
}
