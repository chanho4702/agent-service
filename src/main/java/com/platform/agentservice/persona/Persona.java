package com.platform.agentservice.persona;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "persona")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Persona {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private Long memberId;
    // length는 V1 컬럼폭을 옮겨 적은 것 — 요청 DTO @Size와 짝이다(RequestDtoColumnWidthTest가 묶어 둔다).
    @Column(nullable = false, unique = true, length = 40) private String slug;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PersonaRole role;
    @Column(nullable = false, length = 80) private String name;
    @Column(length = 16) private String emoji;
    @Column(columnDefinition = "text") private String voicePrompt;
    @Column(nullable = false) private boolean active = true;
    /** 소속 ALM 프로젝트 — null이면 전사 공용(전역 관리자만 관리, D-P3f-3). 생성 후 불변이다. */
    private Long projectId;
    /** 기본 모델(AGP-62) — run.model과 같은 폭. 모델 해석 순서는 {@code SchedulerProperties.resolveModel}. */
    @Column(length = 60) private String defaultModel;
    /** 전문성 마크다운(AGP-62) — 워커 워크스페이스에 SKILL.md로 실체화된다({@code HarnessMaterializer}). */
    @Column(columnDefinition = "text") private String skills;
    /** 사무실 아바타 설정 JSON 문자열(AGP-62) — 형태 검증은 {@code AvatarConfigValidator}. */
    @Column(columnDefinition = "text") private String avatarConfig;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(nullable = false) private Instant updatedAt;

    public static Persona of(Long memberId, String slug, PersonaRole role, String name, String emoji, String voicePrompt) {
        return of(memberId, slug, role, name, emoji, voicePrompt, null);
    }

    public static Persona of(Long memberId, String slug, PersonaRole role, String name, String emoji, String voicePrompt,
                             Long projectId) {
        Persona p = new Persona();
        p.memberId = memberId; p.slug = slug; p.role = role; p.name = name;
        p.emoji = emoji; p.voicePrompt = voicePrompt; p.projectId = projectId;
        return p;
    }

    /** 재부트스트랩(같은 slug 재호출) 시 표시용 필드만 갱신한다 — memberId/slug/role은 불변이다. */
    public void refresh(String name, String emoji, String voicePrompt) {
        this.name = name;
        this.emoji = emoji;
        this.voicePrompt = voicePrompt;
    }

    /**
     * 관리자 편집(AGP-62, {@code PATCH /api/agent/personas/{id}}). 인자는 이미 검증·정규화된 값이다 — {@code null}이면 그 필드를
     * 건드리지 않고, {@link Edit#clear}면 지운다(name은 호출측이 지우기를 거부한다).
     */
    public void edit(Edit<String> name, Edit<String> emoji, Edit<String> voicePrompt, Edit<String> defaultModel,
                     Edit<String> skills, Edit<String> avatarConfig) {
        if (name != null) this.name = name.value();
        if (emoji != null) this.emoji = emoji.value();
        if (voicePrompt != null) this.voicePrompt = voicePrompt.value();
        if (defaultModel != null) this.defaultModel = defaultModel.value();
        if (skills != null) this.skills = skills.value();
        if (avatarConfig != null) this.avatarConfig = avatarConfig.value();
    }

    /** 부분 갱신의 한 필드 — 객체가 있으면 "이 값으로 바꾼다"(value가 null이면 지움), 객체 자체가 null이면 "그대로". */
    public record Edit<T>(T value) {
        public static <T> Edit<T> set(T value) {
            return new Edit<>(value);
        }

        public static <T> Edit<T> clear() {
            return new Edit<>(null);
        }
    }

    /** 관리자 활성/비활성 전환(AGP-29). 재부트스트랩({@link #refresh})은 이 값을 건드리지 않는다. */
    public void changeActive(boolean active) {
        this.active = active;
    }
}
