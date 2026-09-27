-- P3g(AGP-65): AI 사무실 1:1 대화 기록 + 수다 비용 원장.
-- 대화 기록은 호출자 본인 것만 읽는다(관리자 포함 타인 불가 — 사적 대화). 소유자 = JWT sub(user_member_id).
CREATE TABLE dialog_entry (
    id              BIGSERIAL PRIMARY KEY,
    user_member_id  BIGINT        NOT NULL,
    persona_id      BIGINT        NOT NULL REFERENCES persona(id),
    session_id      VARCHAR(40),                          -- 수다 세션(c-<uuid>) — 서버가 적재한 SAY 턴만. 프론트 POST 행은 NULL
    speaker         VARCHAR(10)   NOT NULL,               -- USER | PERSONA
    kind            VARCHAR(10)   NOT NULL,               -- SAY | STATUS | DIRECTIVE | ASSIGN
    text            VARCHAR(2000) NOT NULL,
    issue_key       VARCHAR(40),
    run_id          BIGINT,                               -- FK 없음 — 프론트가 보낸 참조값(표시용)
    comment_id      BIGINT,                               -- alm 코멘트 id(서비스 경계, FK 없음)
    created_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_dialog_owner ON dialog_entry (user_member_id, persona_id, id DESC);
-- 보존 90일 정리(쓰기 시점 전역 삭제)가 전체 스캔이 되지 않게.
CREATE INDEX idx_dialog_created ON dialog_entry (created_at);

-- 수다 비용은 run 없이 적재한다(PLATFORM 스코프 + 프로젝트 문맥이 있으면 PROJECT) — run_id를 비울 수 있게 한다.
-- 페르소나별 비용 집계(사무실)는 run 조인이라 이 행들을 세지 않는다(수다는 페르소나 작업 비용이 아니다).
ALTER TABLE usage_ledger ALTER COLUMN run_id DROP NOT NULL;
