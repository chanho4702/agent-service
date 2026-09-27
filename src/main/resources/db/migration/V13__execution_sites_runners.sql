-- P4a(AGP-69) 실행 위치 2종 — 서버(SERVER)·로컬 러너(LOCAL) + 러너 도메인 + 사람용 PAT 위생(D-P4-3b).

-- 실행 위치는 run 속성(D-P4-1). 기존 행은 전부 SERVER(지금까지 모든 run이 서버 프로세스에서 돌았다).
ALTER TABLE run ADD COLUMN execution_site VARCHAR(10) NOT NULL DEFAULT 'SERVER';
-- QUEUED에서는 "이 러너만 집을 수 있음"(계보 run 고정 — 워크스페이스가 그 러너 디스크에 있다), RUNNING 이후에는 배정받은 러너.
ALTER TABLE run ADD COLUMN runner_id BIGINT NULL;
-- 러너 결과 보고 수신 시각 — 조건부 UPDATE로 한 번만 처리한다(중복 보고 멱등).
ALTER TABLE run ADD COLUMN runner_result_at timestamptz NULL;
-- claim 시점에 확정한 LLM 키 출처(PROJECT|PLATFORM|ENV|LOCAL) — 결과 보고 때 원장에 그대로 싣는다(보고 시점 재해석 금지).
ALTER TABLE run ADD COLUMN credential_scope VARCHAR(10) NULL;
ALTER TABLE run ADD CONSTRAINT ck_run_execution_site CHECK (execution_site IN ('SERVER', 'LOCAL'));
-- 러너 claim 후보 조회(QUEUED + 위치) · 생존 판정(RUNNING + 러너) — 둘 다 소수 행이라 부분 인덱스로 충분하다.
CREATE INDEX idx_run_claim ON run (execution_site, id) WHERE status = 'QUEUED';
CREATE INDEX idx_run_runner ON run (runner_id) WHERE runner_id IS NOT NULL;

COMMENT ON COLUMN run.execution_site IS 'SERVER(서버 — 인프로세스 또는 플랫폼 러너) | LOCAL(사용자 PC 로컬 러너)';
COMMENT ON COLUMN run.runner_id IS 'QUEUED=고정 러너(계보 run), RUNNING 이후=배정 러너. FK 없음(러너 철회 뒤에도 run 이력 보존)';

-- 러너(D-P4-3·D-P4-5). 토큰(agr_) 원문은 저장하지 않는다 — SHA-256 hex만. PLATFORM은 env AGENT_PLATFORM_RUNNER_TOKEN으로만 생긴다.
CREATE TABLE runner (
    id                 BIGSERIAL PRIMARY KEY,
    kind               VARCHAR(10)  NOT NULL,                 -- PLATFORM(SERVER run만) | LOCAL(LOCAL run만)
    name               VARCHAR(80)  NOT NULL,
    project_id         BIGINT,                                -- NULL = 플랫폼 전역(전역 관리자 발급 LOCAL, 또는 PLATFORM)
    issued_by          BIGINT       NOT NULL,                 -- 발급한 사람 memberId(PLATFORM은 시스템 센티널 0)
    token_hash         VARCHAR(64)  NOT NULL UNIQUE,
    token_prefix       VARCHAR(12)  NOT NULL,                 -- 표시용 앞 8자(agr_ + 4)
    created_at         timestamptz  NOT NULL DEFAULT now(),
    revoked_at         timestamptz,
    last_heartbeat_at  timestamptz,
    runner_version     VARCHAR(40),                           -- 러너 프로그램이 보고한 버전
    os                 VARCHAR(80),
    max_concurrency    INT          NOT NULL DEFAULT 1,
    CONSTRAINT ck_runner_kind CHECK (kind IN ('PLATFORM', 'LOCAL'))
);
CREATE INDEX idx_runner_project ON runner (project_id);

-- 프로젝트 실행 위치 설정(AI 팀 설정). 행이 없으면 전역 기본(platform.agent.execution.default-site)을 따른다.
CREATE TABLE project_execution_site (
    project_id  BIGINT       PRIMARY KEY,                     -- alm 프로젝트 id(FK 없음 — 서비스 경계)
    site        VARCHAR(10)  NOT NULL,
    updated_by  BIGINT       NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_execution_site CHECK (site IN ('SERVER', 'LOCAL'))
);

-- 사람용 PAT 위생(D-P4-3b). 만료 컬럼(expires_at)은 V1부터 있다 — 만료 임박 알림 1회 발송 표식과 발급자 메일 주소만 더한다.
-- 기존 토큰은 건드리지 않는다(owner_email NULL → 알림은 운영 수신자 AGENT_ALERT_MAIL_TO로 간다).
ALTER TABLE pat_token ADD COLUMN expiry_warned_at timestamptz NULL;
ALTER TABLE pat_token ADD COLUMN owner_email VARCHAR(200) NULL;
