-- P4b(AGP-67) 실행 중 지시 — 사람이 RUNNING run에 남기는 지시. 그 run의 run 토큰으로 오는 다음 MCP 도구 결과 끝에 붙어 전달된다.
CREATE TABLE run_directive (
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT        NOT NULL REFERENCES run(id),
    text              VARCHAR(2000) NOT NULL,
    author_member_id  BIGINT        NOT NULL,               -- 지시한 사람(JWT sub) — org 멤버 id, FK 없음(서비스 경계)
    created_at        timestamptz   NOT NULL DEFAULT now(),
    delivered_at      timestamptz                           -- 도구 결과에 실린 시각. NULL = 미전달
);
-- 전달(도구 호출마다 미전달 조회)·사무실 미전달 개수 — 미전달 행만 본다.
CREATE INDEX idx_run_directive_pending ON run_directive (run_id) WHERE delivered_at IS NULL;
-- 목록(run별 전체 이력).
CREATE INDEX idx_run_directive_run ON run_directive (run_id, id);

-- P4b(AGP-60) run 종결 워크로그: "워커가 이 run에서 이미 log_work를 불렀나"를 run 종결마다 본다 — 감사 표 전체 스캔을 피한다.
CREATE INDEX idx_audit_run ON tool_call_audit (run_id, tool) WHERE run_id IS NOT NULL;

COMMENT ON COLUMN run_directive.delivered_at IS '도구 결과에 실려 워커에게 전달된 시각(원자적 조건부 UPDATE로 한 번만). NULL = 미전달';
