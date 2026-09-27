-- P3h(AGP-66): LLM 키 2층(전역 PLATFORM · 프로젝트 PROJECT). 원문은 저장하지 않는다 — AES-256-GCM 암호문 + 행별 IV만.
-- 마스터 키(AGENT_CREDENTIAL_MASTER_KEY)는 DB 밖(env)에 있어 DB 덤프만으로는 키를 복원할 수 없다.
CREATE TABLE credential (
    id          BIGSERIAL PRIMARY KEY,
    scope       VARCHAR(10)  NOT NULL,              -- PLATFORM | PROJECT
    scope_id    VARCHAR(40),                        -- PROJECT = alm 프로젝트 id(FK 없음 — 서비스 경계), PLATFORM = NULL
    provider    VARCHAR(20)  NOT NULL,              -- ANTHROPIC
    ciphertext  BYTEA        NOT NULL,              -- GCM 암호문 + 태그
    iv          BYTEA        NOT NULL,              -- 행별 랜덤 12바이트
    key_hint    VARCHAR(8)   NOT NULL,              -- 원문 끝 4자(표시용)
    updated_by  BIGINT       NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_credential_scope UNIQUE (scope, scope_id, provider),
    CONSTRAINT ck_credential_scope_id CHECK ((scope = 'PLATFORM' AND scope_id IS NULL) OR (scope = 'PROJECT' AND scope_id IS NOT NULL))
);
-- 위 UNIQUE는 NULL을 서로 다른 값으로 보므로 PLATFORM(scope_id NULL) 행 중복을 막지 못한다 — 부분 유니크 인덱스로 보완한다
-- (NULLS NOT DISTINCT는 PG15+ 전용이라 운영 PG 버전에 기대지 않는다).
CREATE UNIQUE INDEX uq_credential_platform ON credential (scope, provider) WHERE scope_id IS NULL;

-- 어떤 층 키로 돈 run인지(PROJECT|PLATFORM|ENV|NONE) — 과금 분리 추적용 메타. 원문·힌트는 싣지 않는다.
ALTER TABLE usage_ledger ADD COLUMN credential_scope VARCHAR(10);
