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
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(nullable = false) private Instant updatedAt;

    public static Persona of(Long memberId, String slug, PersonaRole role, String name, String emoji, String voicePrompt) {
        Persona p = new Persona();
        p.memberId = memberId; p.slug = slug; p.role = role; p.name = name;
        p.emoji = emoji; p.voicePrompt = voicePrompt;
        return p;
    }

    /** 재부트스트랩(같은 slug 재호출) 시 표시용 필드만 갱신한다 — memberId/slug/role은 불변이다. */
    public void refresh(String name, String emoji, String voicePrompt) {
        this.name = name;
        this.emoji = emoji;
        this.voicePrompt = voicePrompt;
    }
}
