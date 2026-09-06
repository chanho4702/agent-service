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

전체 도구 21종(P1 18종 + P2a run 보고 3종, 아래 §5 참고): `whoami`, `list_projects`,
`get_project_context`, `search_issues`, `get_issue`, `create_issue`, `claim_issue`, `add_comment`,
`log_work`, `link_pr`, `update_issue_status`, `list_spaces`, `find_pages`, `get_page`, `create_page`,
`update_page`, `append_to_page`, `ping`, `report_progress`, `request_gate`, `report_result`.

### 4. 검증 이력

2026-09-06 dev 오프셋 클러스터(전 서비스 +10000)에서 실제 왕복 검증 완료 — 페르소나 `jiho`(memberId=3)
로 이슈 `AGP-1` 생성부터 `done` 전환까지 13회 도구 호출 전부 `tool_call_audit`에 `OK`로 기록,
이슈 assignee/reporter·코멘트 author·워크로그 author·위키 `updatedBy`가 모두 페르소나 memberId와
일치함을 REST로 교차 확인(최초 검증은 위 게이트웨이 버그 때문에 agent-service 직결로 수행).
같은 날 게이트웨이 수정(`gateway-server` a535be4) 후 게이트웨이 경유 `initialize`/`tools/list`
(18종) 재검증 완료. 상세: `.superpowers/sdd/2026-09-05-agent-service-p1/task-14-report.md`.

## 5. P2a: 무인 자동화 루프 운영 (2026-09-06 완료, E2E 검증)

P1(위 1~4절)은 사람이 매번 도구를 호출해 기록을 남기는 통로였다. P2a는 그 위에 **사람 개입
없이 이슈를 집어 워커(헤드리스 Claude Code)를 실행하고 결과를 되받는 루프**를 얹는다 —
디스패처(스케줄러)·워커 런처·게이트 승인·예산/킬 스위치가 그 구성 요소다. MCP 도구가
21종으로 는 것도 이 루프 때문이다: 워커가 자기 run의 진행·승인 요청·종결을 보고하는 3종
(`report_progress`/`request_gate`/`report_result`)이 새로 생겼다. 이 셋은 디스패처가 run마다
발급한 임시 PAT(run 토큰)으로만 호출 가능하고, runId 소유(호출자 페르소나==run의 페르소나)와
상태(RUNNING) 검증은 `RunToolService`가 한다.

### 5.1 스케줄러(디스패처) 켜기

기본은 **OFF**다(`platform.agent.scheduler.enabled` 기본값 `false` — 킬스위치, 재기동
필요). 무인 루프를 켜려면:

1. `SCHEDULER_ENABLED=true`.
2. 프로젝트키→git URL 매핑(`platform.agent.worker.repos`)을 채운다 — **env var로 넣지 말 것**:
   Spring relaxed binding이 맵 키를 소문자로 접어서(`PLATFORM_AGENT_WORKER_REPOS_AGP=...` →
   `repos.get("agp")`) 항상 대문자인 ALM 프로젝트 키와 어긋난다(F3, task-7 E2E 실측). 프로그램
   인자로 넣는다: `--platform.agent.worker.repos.AGP=https://github.com/....git`(대소문자
   보존). `WorkerProperties.repoFor(key)`가 대소문자 무관 조회라 실제로는 둘 다 동작하지만,
   안전한 쪽은 프로그램 인자다.
3. `AGENT_INTERNAL_SECRET` — auth-server와 반드시 같은 값(비면 페르소나/run 토큰 발급이
   fail-closed로 전부 막힌다).
4. 워커 인증: 같은 사용자 구독을 재사용하거나(검증됨) `CLAUDE_CODE_OAUTH_TOKEN` /
   `ANTHROPIC_API_KEY`를 이 서비스의 프로세스 env로 둔다 — `WorkerLauncher.workerEnv()`가
   부모 프로세스 env에서 이 둘만 골라 워커 프로세스로 전달한다(다른 자격증명은 흘리지 않음).

자동화 정책: 라벨 `auto` + 상태 `todo`인 ALM 이슈만 픽업 대상이고, 한 틱에 하나만 새로
픽업한다(단순화, 브리핑 지시). 동시성은 `SCHEDULER_MAX_GLOBAL`(기본 2, 전역 QUEUED+RUNNING)
· `SCHEDULER_MAX_PER_PROJECT`(기본 1, 프로젝트별) — 픽업 단계에서만 본다(이미 QUEUED인 run을
드레인하는 건 다시 게이트하지 않음). 재시도는 `SCHEDULER_RETRY_MAX_ATTEMPTS`(기본 3) — 실패
시 attempt+1 continuation을 만들어 재시도하고, 한도를 넘기면 `BLOCKED`로 승격해 픽업 후보에서
완전히 제외한다(`RunService.ACTIVE_STATUSES`에 BLOCKED 포함 — 없으면 같은 이슈를 attempt=1부터
무한 재픽업하는 회귀가 난다, task-7 E2E 실측).

### 5.2 예산·킬 스위치

`BUDGET_MONTHLY_USD`(기본 100, 캘린더 월 UTC 누적 — 플랫폼 전체·프로젝트별에 P2a는 같은 값)
· `BUDGET_PER_RUN_USD`(기본 5 — 넘어도 run을 되돌리지 않고 이슈에 경고 코멘트만 남긴다).
`GET /api/agent/budget`(인증된 누구나)로 현재 월 사용량·캡·킬스위치 상태 조회.
`GET /api/agent/kill-switch`(누구나) / `POST /api/agent/kill-switch`(ADMIN, body
`{"on": true|false}`)로 즉시 전체 차단·해제. **킬 스위치는 인메모리다 — 재기동하면 이 값과
무관하게 항상 꺼진 상태(off)로 시작한다**, 알림 연동(경고 채널)은 P3.

### 5.3 게이트 승인 흐름

워커가 `request_gate(runId, kind, request)`를 부르면 그 run이 `WAITING_APPROVAL`로 전환되고
`Gate` 행이 생긴다(kind: `MERGE`|`ESCALATION`|`PLAN`). `GET /api/agent/gates?pending=true`
(누구나 — 생략/`false`면 요청순 최신 50건)로 대기열을 본 뒤 `POST /api/agent/gates/{id}/approve`
| `reject`(ADMIN)로 결정한다. 승인은 원 run 행을 되돌리지 않고 **continuation run(attempt+1)을
새로 만들어** 큐잉·비동기 실행하고, 원 run은 CANCELLED로 닫는다(D9 — WAITING_APPROVAL에 그대로
두면 다음 디스패처 픽업의 "활성 run 중복" 가드와 동시성 집계에 계속 걸린다). 거절은 원 run을
CANCELLED로 닫고 재개하지 않는다.

### 5.4 BLOCKED 사람 재개

재시도 한도를 소진해 `BLOCKED`가 된 run은 게이트와는 별개 경로로 사람이 재개한다:
`POST /api/agent/runs/{id}/resume`(ADMIN) — `Gate` 행 없이 continuation run(attempt+1)을 만들어
재실행한다(§5.3 승인과 동일 패턴, 다만 사람이 먼저 승인을 요청받은 게 아니라 시스템이 스스로
멈춘 것이므로 게이트 엔티티가 없다). 대상 run이 BLOCKED가 아니면 409.

### 5.5 run 감독 REST

`GET /api/agent/runs?status=`(인증된 누구나, `status` 생략 시 전체) ·
`POST /api/agent/runs/{id}/cancel`(ADMIN — DB 상태만 CANCELLED로 옮긴다. 실행 중인 워커 OS
프로세스를 강제로 죽이지는 않는다, Windows 프로세스 트리 관리는 P2a 범위 밖).

### 5.6 워커 계약 요약

- 헤드리스 `claude -p`를 **비-bare로** 실행한다: `--permission-mode dontAsk
  --permission-prompts none --allowedTools <허용목록> --strict-mcp-config --mcp-config <파일>
  --output-format json --max-turns N [--model M]`.
- run마다 새 워크스페이스(`AGENT_WORK_DIR/run-{id}`)를 만들어 `git clone` 후, 하네스
  (`.claude/` 번들 + 루트 `CLAUDE.md`/`AGENTS.md`)를 그 워크스페이스에 실체화한다
  (`HarnessMaterializer`) — 워커가 플랫폼 협업 규약을 그대로 보고 작업하게 하기 위함.
- mcp-config는 **인라인 JSON 인자가 아니라 워크스페이스 파일**(`.mcp-run.json`)로 넘긴다 —
  Windows `ProcessBuilder`가 인자를 재조립(re-quote)할 때 JSON 안 따옴표가 사라지고 `/`가
  `\`로 바뀌어 CLI가 손상된 문자열을 파일 경로로 오인해 즉시 죽는 버그를 피한다(F1, task-7
  E2E 3회 100% 재현). 이 파일에는 run 토큰(Bearer)이 그대로 담기므로 절대 로그로 남기지 않고,
  실행 후 `finally`에서 반드시 삭제 + PAT 철회한다.
- 프롬프트는 이슈 본문·최근 코멘트를 `<이슈-내용>`/`<코멘트>` 경계로 감싸 "데이터"로 표시한
  뒤 규약 섹션을 둔다 — **사람 코멘트 = 지시**로 취급하되, 그 안에 규약과 충돌하는 문구가
  있으면 규약이 우선한다는 문장을 경계 직후에 못박는다(프롬프트 인젝션 방어, fix round 1 I2).
- 프롬프트에 종결 3종 도구 호출을 runId와 함께 명시한다 — 워커는 `report_progress`로 수시
  진행 보고, 완료/실패/차단 시 반드시 `report_result(status=DONE|FAILED|BLOCKED)`를 호출해야
  하고, 사람 승인이 필요하면 `request_gate`를 부른다.
- 종결 시 `CommitLinkParser`가 `origin/main..HEAD` 범위의 로컬 커밋 메시지에서 이슈 키
  (정규식 `[A-Z][A-Z0-9_]{1,11}-\d+`)를 찾아 COMMIT 종류 웹링크로 이슈에 연결한다(순수 git
  파싱, best-effort — 실패해도 run 처리를 막지 않는다). PR 연결은 별도 도구 `link_pr`(P1,
  웹링크).

### 5.7 E2E 검증 이력

2026-09-06: 무인 루프 전체 왕복(디스패처 픽업 → 워커 실행 → `report_result` → 커밋/PR 수확)을
실제로 완주 — run 16건이 13분·$3.93으로 `DONE`, 산출물이 `main`에 병합(`b3a7f68`). 상세는
각 태스크 보고서(`.superpowers/sdd/2026-09-06-agent-service-p2a/task-*-report.md`)를 참고.
