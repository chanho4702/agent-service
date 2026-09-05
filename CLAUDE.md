# agent-service 작업 규약

루트 `C:/MSA_TEMPLATE/CLAUDE.md`를 먼저 따른다.

## 이 서비스는 무엇인가

에이전트 기록 계층 — AI 에이전트가 ALM·Wiki에 **사람이 아닌 페르소나 명의**로 기록을 남기는 통로다.
이후 태스크에서 MCP 서버(Model Context Protocol)로 확장되어, 외부 AI 에이전트가 이 서비스를 거쳐
플랫폼 API(ALM/Wiki/Org)를 호출한다. 이 태스크(P1)는 표준 서비스 골격만 갖춘 상태이며 MCP 관련
코드는 아직 없다.

## 포트

- 운영: `9160` (REST)
- dev 오프셋(+10000): `19160`

## 페르소나 신원 체인

1. `auth-server`의 users 테이블에 `agent:<slug>` 형태의 계정을 만든다(사람 계정과 동일 스키마, provider만 다름).
2. `org-service`의 member가 이 계정을 JIT mirror하되 `kind=AGENT`로 표시된다(사람=`kind=USER`와 구분).
3. `agentdb`(이 서비스의 PostgreSQL)에 실제 페르소나 엔티티(이름·설명·소유 팀 등)가 저장된다.

세 계층 중 하나만 봐서는 "이 에이전트가 누구인지" 알 수 없다 — 항상 체인 전체를 따라간다.

## 인증

MCP 클라이언트는 PAT(Personal Access Token)로 인증한다(이후 태스크에서 구현). 사람 사용자와 동일한
JWT 리소스서버 검증(JWKS + issuer/audience, common-starter 제공)은 이미 이 골격에 붙어 있다.

## 빌드/테스트

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon
```

GitHub Packages(`com.platform:common-starter`) 인증은 `GITHUB_TOKEN` env 또는
`~/.gradle/gradle.properties`의 `gpr.token`.

## MCP 접속 가이드 (2026-09-06 E2E 도그푸딩 검증 완료, 09-06 게이트웨이 수정 후 재검증)

### 게이트웨이 경유 접속이 정식 경로다

`http://localhost:18000/api/agent/mcp`(dev) / `http://localhost:8000/api/agent/mcp`(운영)로
PAT(`agp_*`)를 그대로 태워 접속한다 — 별도 교환·변환 없이 agent-service가 PAT을 직접 검증한다.

한때 이 경로가 401로 막혀 있었다: `gateway-server`의 보안 체인에서 `/api/agent/mcp/**`를
`permitAll()`로 열어 두긴 했지만, **`permitAll()`은 인가(authorization) 단계만 건너뛴다** —
같은 체인에 `oauth2ResourceServer(jwt)`가 붙어 있으면 인가보다 먼저 도는
`BearerTokenAuthenticationFilter`가 경로와 무관하게 모든 `Authorization: Bearer` 값을 JWT로
디코드하려 시도한다. PAT(`agp_*`)는 JWT가 아니므로 이 디코드가 실패해 인가 단계에 닿기도 전에
401로 체인이 끊겼다(agent-service 자체의 `PatAuthFilter` 설계 때 겪었던 것과 동일한 함정 —
`SecurityConfig`가 그때도 `/api/agent/mcp`를 JWT 리소스서버가 아예 없는 별도 체인으로 분리해
피해 갔었다). `gateway-server` a535be4가 같은 방식으로 `/api/agent/mcp/**`를
`oauth2ResourceServer` 없는 별도 `SecurityWebFilterChain`(순서 1)으로 완전히 분리해 고쳤다 —
나머지 경로는 기존 JWT 체인(순서 2) 그대로. 2026-09-06 실측: 게이트웨이 경유 `initialize` →
`tools/list`(18종) 정상 확인.

### 1. 관리자로 페르소나·PAT 부트스트랩

관리자 JWT(AT)로 아래 순서를 1회 실행한다. (브라우저 로그인 후 auth-server의
`/api/auth/refresh`로 AT를 얻는다 — Keycloak은 ROPC를 지원하지 않으므로 반드시 브라우저 리다이렉트
로그인이 선행돼야 한다.)

```bash
ADMIN_AT="..."   # 관리자 로그인 후 발급받은 AT
GATEWAY=http://localhost:18000   # 또는 :8000(운영), :18000(dev)

# 1) 대상 프로젝트/스페이스 확보 (없으면 생성)
curl -s -X POST $GATEWAY/api/alm/projects \
  -H "Authorization: Bearer $ADMIN_AT" -H "Content-Type: application/json" \
  -d '{"key":"AGP","name":"agent-platform"}'

curl -s -X POST $GATEWAY/api/wiki/spaces \
  -H "Authorization: Bearer $ADMIN_AT" -H "Content-Type: application/json" \
  -d '{"key":"agp","name":"Agent Platform"}'

# 2) 페르소나 생성 — role은 PLANNER|DESIGNER|FRONTEND|BACKEND|OPS|REVIEWER 중 하나,
#    grants.resourceType은 GLOBAL|SPACE|PROJECT, grants.role은 VIEWER|COMMENTER|EDITOR|ADMIN.
curl -s -X POST $GATEWAY/api/agent/personas \
  -H "Authorization: Bearer $ADMIN_AT" -H "Content-Type: application/json; charset=utf-8" \
  -d '{"slug":"jiho","role":"BACKEND","name":"지호","emoji":"🔧",
       "grants":[{"resourceType":"PROJECT","resourceId":"<projectId>","role":"EDITOR"},
                 {"resourceType":"SPACE","resourceId":"<spaceId>","role":"EDITOR"}]}'

# 3) PAT 발급 — 응답의 token(agp_*)은 이때 한 번만 보인다, 반드시 저장.
curl -s -X POST $GATEWAY/api/agent/tokens \
  -H "Authorization: Bearer $ADMIN_AT" -H "Content-Type: application/json" \
  -d '{"label":"my-claude-code","personaSlug":"jiho"}'
```

PAT 관리: `GET /api/agent/tokens`(인증된 누구나, 해시는 노출 안 함) · `DELETE /api/agent/tokens/{id}`
(관리자만). 페르소나 목록: `GET /api/agent/personas`(인증된 사용자 누구나 — JWT 필요, 슬러그/역할/emoji만 노출).

### 2. Claude Code에 MCP 서버 등록

게이트웨이 단일 진입점을 쓴다(dev):

```bash
claude mcp add --transport http agent-platform http://localhost:18000/api/agent/mcp \
  --header "Authorization: Bearer agp_..."
```

운영은 `:8000`. 연결 확인: `ping`(pong 응답) → `whoami`(방금 만든 페르소나 이름) →
`list_projects`.

디버깅용 참고: agent-service는 `/api/agent/mcp`에 자체 `PatAuthFilter`를 갖고 있어
`http://localhost:19160/api/agent/mcp`(dev) / `:9160`(운영)로 **직접** 접속해도 동일하게
동작한다 — 게이트웨이 자체를 의심할 때(라우팅·보안 체인 변경 검증 등) 비교 대상으로 쓴다.

### 3. 도구 규약 (서버가 `initialize` 응답 `instructions`로도 내려준다)

1. 작업 시작 전 `get_project_context(projectId)`로 스킴(상태/타입/우선순위)과 멤버 명단을 먼저
   확인한다 — `create_issue`의 스킴 400을 예방한다.
2. 이슈를 집으면 `claim_issue` → 진행 코멘트는 `add_comment` → 시간은 `log_work`.
3. 결정·설계·작업 내용은 위키에 `create_page`/`append_to_page`로 페르소나 명의 작업 보고서를
   남기고, `add_comment`로 이슈에 보고서를 링크한다 — **보고서 없는 완료는 금지**.
4. PR은 `link_pr`로 이슈에 연결한다.
5. 완료 시 순서: 작업 보고서 링크 코멘트 → `update_issue_status(done 계열)`.
6. 모든 기록은 호출에 쓰인 PAT의 페르소나 명의로 남는다(작성자=페르소나 memberId) —
   `tool_call_audit` 테이블에 도구·상태·persona_id가 매 호출마다 적재된다.

전체 도구 18종: `whoami`, `list_projects`, `get_project_context`, `search_issues`, `get_issue`,
`create_issue`, `claim_issue`, `add_comment`, `log_work`, `link_pr`, `update_issue_status`,
`list_spaces`, `find_pages`, `get_page`, `create_page`, `update_page`, `append_to_page`, `ping`.

### 4. 검증 이력

2026-09-06 dev 오프셋 클러스터(전 서비스 +10000)에서 실제 왕복 검증 완료 — 페르소나 `jiho`(memberId=3)
로 이슈 `AGP-1` 생성부터 `done` 전환까지 13회 도구 호출 전부 `tool_call_audit`에 `OK`로 기록,
이슈 assignee/reporter·코멘트 author·워크로그 author·위키 `updatedBy`가 모두 페르소나 memberId와
일치함을 REST로 교차 확인(최초 검증은 위 게이트웨이 버그 때문에 agent-service 직결로 수행).
같은 날 게이트웨이 수정(`gateway-server` a535be4) 후 게이트웨이 경유 `initialize`/`tools/list`
(18종) 재검증 완료. 상세: `.superpowers/sdd/2026-09-05-agent-service-p1/task-14-report.md`.
