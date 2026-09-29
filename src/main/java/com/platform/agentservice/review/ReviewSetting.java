package com.platform.agentservice.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 리뷰어 지정(V15 {@code review_setting}) — 행이 없으면 다음 해석 단계로 내려간다({@link ReviewerResolver}). */
@Entity
@Table(name = "review_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSetting {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ReviewScope scope;
    private Long scopeId;
    @Column(nullable = false) private Long personaId;
    @Column(nullable = false) private Long updatedBy;
    @Column(nullable = false) private Instant updatedAt;

    public static ReviewSetting of(ReviewScope scope, Long scopeId, long personaId, long updatedBy, Instant now) {
        ReviewSetting s = new ReviewSetting();
        s.scope = scope;
        s.scopeId = scopeId;
        s.personaId = personaId;
        s.updatedBy = updatedBy;
        s.updatedAt = now;
        return s;
    }

    public void change(long personaId, long updatedBy, Instant now) {
        this.personaId = personaId;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }
}
