-- agent-service P1: 페르소나 · PAT · 도구 감사. (Run/Gate/Harness는 P2)
CREATE TABLE persona (
    id            BIGSERIAL PRIMARY KEY,
    member_id     BIGINT       NOT NULL UNIQUE,  -- auth users.id = org member.id
    slug          VARCHAR(40)  NOT NULL UNIQUE,  -- agent:<slug> 의 slug
    role          VARCHAR(20)  NOT NULL,         -- PLANNER|DESIGNER|FRONTEND|BACKEND|OPS|REVIEWER
    name          VARCHAR(80)  NOT NULL,
    emoji         VARCHAR(16),
    voice_prompt  TEXT,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE pat_token (
    id               BIGSERIAL PRIMARY KEY,
    token_hash       VARCHAR(64)  NOT NULL UNIQUE,  -- SHA-256 hex
    label            VARCHAR(120) NOT NULL,
    owner_member_id  BIGINT       NOT NULL,          -- 만든 사람(사람 멤버)
    persona_id       BIGINT       NOT NULL REFERENCES persona(id),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    expires_at       TIMESTAMP,
    last_used_at     TIMESTAMP,
    revoked_at       TIMESTAMP
);

CREATE TABLE tool_call_audit (
    id               BIGSERIAL PRIMARY KEY,
    persona_id       BIGINT REFERENCES persona(id),
    actor_member_id  BIGINT NOT NULL,
    tool             VARCHAR(60) NOT NULL,
    summary          VARCHAR(500),
    status           VARCHAR(10) NOT NULL,           -- OK | ERROR
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_persona_time ON tool_call_audit (persona_id, created_at DESC);
