-- P2a: 에이전트 실행 상태 백본 — Run·Gate·UsageLedger (스펙 D9·§10.5).
-- V1과 달리 처음부터 timestamptz(플랫폼 표준, V2 정합) — 새로 추가하는 테이블에 timestamp를 쓰지 않는다.
CREATE TABLE run (
    id             BIGSERIAL PRIMARY KEY,
    type           VARCHAR(20)  NOT NULL,                     -- TASK (P2a는 TASK만)
    issue_key      VARCHAR(40)  NOT NULL,
    project_id     BIGINT       NOT NULL,
    persona_id     BIGINT       NOT NULL REFERENCES persona(id),
    trigger        VARCHAR(20)  NOT NULL,                     -- SCHEDULER | USER
    status         VARCHAR(20)  NOT NULL,                     -- QUEUED|RUNNING|WAITING_APPROVAL|BLOCKED|DONE|FAILED|CANCELLED
    harness_ref    VARCHAR(200),
    workspace_path VARCHAR(400),
    session_id     VARCHAR(80),
    pat_id         BIGINT,
    attempt        INT          NOT NULL DEFAULT 1,
    error          TEXT,
    started_at     timestamptz,
    ended_at       timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_run_status ON run (status);
CREATE INDEX idx_run_issue ON run (issue_key);

CREATE TABLE gate (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT       NOT NULL REFERENCES run(id),
    kind          VARCHAR(20)  NOT NULL,                      -- MERGE | ESCALATION | PLAN
    request       TEXT         NOT NULL,
    decided_by    BIGINT,
    decision      VARCHAR(10),                                -- APPROVE | REJECT
    requested_at  timestamptz  NOT NULL DEFAULT now(),
    decided_at    timestamptz
);

CREATE TABLE usage_ledger (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT        NOT NULL REFERENCES run(id),
    scope          VARCHAR(20)   NOT NULL,                     -- PROJECT | PLATFORM
    scope_id       VARCHAR(40)   NOT NULL,
    cost_usd       NUMERIC(10,4) NOT NULL,
    input_tokens   BIGINT        NOT NULL DEFAULT 0,
    output_tokens  BIGINT        NOT NULL DEFAULT 0,
    model          VARCHAR(60),
    created_at     timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_scope_time ON usage_ledger (scope, scope_id, created_at DESC);
