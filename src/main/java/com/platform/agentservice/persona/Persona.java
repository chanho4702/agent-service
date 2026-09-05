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
    @Column(nullable = false, unique = true) private String slug;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PersonaRole role;
    @Column(nullable = false) private String name;
    private String emoji;
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
}
