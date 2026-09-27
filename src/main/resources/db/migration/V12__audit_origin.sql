-- AGP-63: 실행 출처 구분 — 내부 워커 run(run 토큰) · 외부 MCP(사람용 PAT) · 서버 내부 기록. 기존 행은 NULL(미상).
ALTER TABLE tool_call_audit ADD COLUMN origin VARCHAR(10) NULL;
ALTER TABLE tool_call_audit ADD COLUMN run_id BIGINT NULL;

COMMENT ON COLUMN tool_call_audit.origin IS 'WORKER(run 토큰) | EXTERNAL(사람용 PAT) | SYSTEM(PAT 없는 서버 내부 기록) — NULL은 V12 이전 행(미상)';
COMMENT ON COLUMN tool_call_audit.run_id IS 'WORKER 호출의 run id(run 토큰 label run:<id>에서 해석, FK 없음 — 표시용)';
