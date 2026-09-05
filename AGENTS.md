# AGENTS.md — agent-service

실제 서비스 규약은 `./CLAUDE.md`가 정본이다. 작업 전에 전체를 읽고 따른다.

이 리포에 적용되는 플랫폼 확정 결정(루트 `CLAUDE.md` "확정 설계 결정" 절 참고):

- 리소스 서버 공통 규약은 `common-starter`(현재 `0.15.0`) — JWT 검증(JWKS + issuer/audience),
  `roles`→`ROLE_*` 변환, `{"error": 메시지}` 오류 계약, 공용 예외(`com.platform.common.error.*`)를
  서비스에 다시 복제하지 않는다. `SecurityFilterChain`(경로 정책)만 이 서비스가 가진다.
- 로그는 stdout JSON만 출력한다(docker 프로필: `logging.structured.format.console: ecs`). Alloy →
  Loki → Grafana가 수집한다. 앱에서 Loki 직결(loki4j) 금지.
- 스키마 변경은 Flyway로만 한다. `spring.jpa.hibernate.ddl-auto: validate` — 엔티티가 스키마를
  만들게 하지 않는다.
- DB는 PostgreSQL 통일, 서비스 간 이벤트는 Redis Streams(Kafka 아님) — 단 이 서비스 P1에서는
  Redis 미사용.
