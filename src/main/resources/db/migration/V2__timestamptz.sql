-- I6: V1의 timestamp(타임존 없음) 컬럼을 플랫폼 표준인 timestamptz로 전환한다.
-- V1은 이미 dev agentdb에 데이터가 있어 되돌려 고치지 않는다(체크섬 보존) — 새 마이그레이션으로만 간다.
-- 엔티티는 이미 java.time.Instant를 쓰고 있어 애플리케이션 코드 변경은 없다.
ALTER TABLE persona
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE pat_token
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN last_used_at TYPE timestamptz USING last_used_at AT TIME ZONE 'UTC',
    ALTER COLUMN revoked_at TYPE timestamptz USING revoked_at AT TIME ZONE 'UTC';

ALTER TABLE tool_call_audit
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
