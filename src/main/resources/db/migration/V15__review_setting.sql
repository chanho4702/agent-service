-- P4b(AGP-59) 리뷰어 지정은 설정(DB), env REVIEW_PERSONA는 폴백. 해석 순서는 ReviewerResolver 한 곳:
-- 프로젝트 설정 > 전역 설정 > env > 자동(활성 REVIEWER, 프로젝트 소속 우선·id 최소) > 없음(fail-closed).
CREATE TABLE review_setting (
    id          BIGSERIAL PRIMARY KEY,
    scope       VARCHAR(10)  NOT NULL,              -- PLATFORM | PROJECT
    scope_id    BIGINT,                             -- PROJECT = alm 프로젝트 id(FK 없음 — 서비스 경계), PLATFORM = NULL
    persona_id  BIGINT       NOT NULL,              -- 리뷰어 페르소나(FK 없음 — 페르소나는 비활성화만 되고 지워지지 않는다)
    updated_by  BIGINT       NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_review_setting_scope CHECK ((scope = 'PLATFORM' AND scope_id IS NULL) OR (scope = 'PROJECT' AND scope_id IS NOT NULL))
);
-- 범위당 한 행. PLATFORM(scope_id NULL)은 일반 UNIQUE로 막히지 않아 부분 유니크 인덱스로 나눈다(V9 credential과 같은 이유).
CREATE UNIQUE INDEX uq_review_setting_project ON review_setting (scope_id) WHERE scope = 'PROJECT';
CREATE UNIQUE INDEX uq_review_setting_platform ON review_setting (scope) WHERE scope = 'PLATFORM';
