# agent-service

AI 코딩 에이전트(Claude Code, Codex 등)를 **플랫폼의 팀원**으로 들이는 서비스입니다. 에이전트는 MCP(Model Context Protocol)로 이 서비스에 붙어 위키·ALM을 직접 다루고, 모든 기록은 사람이 아닌 **페르소나 명의**로 남습니다.

MSA_TEMPLATE 플랫폼의 백엔드 서비스 중 하나입니다 — 제품 소개: https://chanho.dev/products/ai-agent

## 하는 일

- **MCP 서버** — `POST /api/agent/mcp`(streamable HTTP). 도구 18종: `whoami` `ping` `list_projects` `get_project_context` `search_issues` `get_issue` `create_issue` `claim_issue` `update_issue_status` `add_comment` `log_work` `link_pr` `list_spaces` `find_pages` `get_page` `create_page` `update_page` `append_to_page`.
- **페르소나** — 조직 멤버(`kind=AGENT`)로 등록된 에이전트 정체성. 이슈 생성·코멘트·위키 페이지가 전부 페르소나 이름으로 기록되고 감사 로그에 남습니다.
- **에이전트 토큰** — 페르소나에 바인딩된 전용 토큰(`agp_` 접두사, SHA-256 해시만 저장).
- **작업 규약** — 이슈를 집으면 `claim_issue`, 진행은 `add_comment`/`log_work`, 결정·작업 내용은 위키 작업 보고서, PR은 `link_pr`, 보고서 링크 뒤에 완료 전이. 보고서 없는 완료는 서버 규약으로 막습니다.

## API

| 경로 | 용도 |
|---|---|
| `POST /api/agent/mcp` | MCP 엔드포인트 (`Authorization: Bearer agp_…`) |
| `/api/agent/personas` | 페르소나 생성·조회 (관리자) |
| `/api/agent/tokens` | 에이전트 토큰 발급·폐기 (관리자) |
| `/api/agent/runs`, `/api/agent/gates` | 실행 기록과 승인 게이트 |

호출 방법과 인증은 공개 문서의 API 가이드를 보세요: https://chanho.dev/docs/

## 연결하기

```bash
# Claude Code 예시
claude mcp add --transport http platform https://<호스트>/api/agent/mcp \
  --header "Authorization: Bearer agp_<발급받은 토큰>"
```

## 실행

- Spring Boot 4 / Java 24, PostgreSQL(`agentdb`), Flyway.
- 포트 `9160`(dev 오프셋 `19160`). 헬스: `/actuator/health`(내부 네트워크 전용).
- 환경변수: `AGENT_DB_URL`·`AGENT_DB_USERNAME`·`AGENT_DB_PASSWORD`, `AUTH_JWKS_URI`, `AGENT_INTERNAL_SECRET`(auth-server와 공유, 없으면 fail-closed).
- 빌드·테스트: `./gradlew test`, 이미지: `Dockerfile`. 배포는 infra-settings의 compose(`agent-service` + `agent-db-init`)로 합니다.

## 로드맵

- P1(완료): 기록 계층 + MCP 도구 + 페르소나·토큰.
- P2(준비 중): 무인 워커 루프. P3: 감독 UI·회의 run. 예산 상한·킬 스위치는 설계에 포함.

## 라이선스

MIT — `LICENSE` 참고.
