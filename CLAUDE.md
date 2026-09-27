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

# 2) 페르소나 생성 — role은 PLANNER|DESIGNER|FRONTEND|BACKEND|OPS|REVIEWER|MANAGER 중 하나(MANAGER는 §5.10),
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

PAT 관리: `GET /api/agent/tokens`(관리자만 — AGP-21, 라벨·페르소나·사용 시각도 운영 정보라 일반 사용자에게 닫았다. 해시는
어느 응답에도 노출 안 함) · `DELETE /api/agent/tokens/{id}`(관리자만). 페르소나 목록: `GET /api/agent/personas`(인증된 사용자
누구나 — JWT 필요, 슬러그/역할/emoji/active만 노출).

페르소나 활성/비활성(AGP-29): `PATCH /api/agent/personas/{id}/active`(관리자만, body `{"active": true|false}` → 200
`PersonaResponse`, 없는 id 404, `active` 누락 400). 비활성 페르소나의 **사람용 PAT은 다음 요청부터 401**(`PatService.validate`
— 기존 "유효하지 않은 토큰" 계약 그대로), 새 PAT·새 run 토큰 발급은 409(기존), 디스패처는 기본 페르소나가 비활성이면 픽업을
건너뛰고(warn), USER run 생성은 400이다. **진행 중인 run은 끊지 않는다** — run 토큰(`PatToken.isRunToken()`: 발급자=시스템
센티널 0 + label `run:`)은 비활성이어도 run이 끝나 철회될 때까지 통과한다. 즉시 멈춰야 하면 킬 스위치(§5.2)·run 취소(§5.5)를
쓴다. 비활성화는 토큰을 철회하지 않으므로 다시 활성화하면 기존 PAT이 살아난다(영구 차단은 토큰 철회). 재부트스트랩
(`POST /api/agent/personas` 같은 slug)은 active를 건드리지 않는다.

PAT 거부 로그(AGP-23): 401의 사유(해시 불일치·철회·만료·페르소나 없음·비활성)를 `PatService`가 warn으로 남긴다 — 토큰 원문은
싣지 않고 해시 불일치는 앞 8자(`agp_` + 4자), 나머지는 토큰 id만. 서비스 토큰 발급(`/internal/service-tokens`) 실패도
`AuthTokenClient`가 memberId·HTTP 상태만 warn으로 남긴다(시크릿 미설정 포함).

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
   `claim_issue(issueKey, status?)`(AGP-27): `status`를 생략하면 그 이슈 프로젝트의 유효 스킴(`GET /api/alm/projects/{id}/settings`
   — `get_project_context`와 같은 경로)에서 카테고리 의미 `kind=active`인 상태를 고른다 — 기본 스킴 id `inprogress`가 그중에
   있으면 그것, 없으면 `order`가 가장 앞선 것. 스킴 조회 실패·active 상태 없음이면 `inprogress`로 폴백한다(그 상태가 스킴에
   없으면 alm 400 → 오류 텍스트, 기존과 같은 실패 모양). 전이 규칙은 보지 않는다(판정은 alm PUT). 스킴에 없는 `status`를
   넘기면 alm 400이 그대로 전파된다. 디스패처 자동 claim(`RunService.buildJob`)도 같은 해석을 쓴다
   (`IssueClaimSupport.resolveInProgressStatus` — 예전 `CLAIM_STATUS="inprogress"` 고정 제거). 한계: 진행 중 상태가 여럿인
   스킴에서 "착수" 상태는 스킴이 말해 주지 않아 순서로 정한다.
3. 결정·설계·작업 내용은 위키에 `create_page`/`append_to_page`로 페르소나 명의 작업 보고서를
   남기고, `add_comment`로 이슈에 보고서를 링크한다 — **보고서 없는 완료는 금지**.
4. PR은 `link_pr`로 이슈에 연결한다.
5. 완료 시 순서: 작업 보고서 링크 코멘트 → `update_issue_status(done 계열)`.
   단, agent-service가 띄운 워커 run(스케줄러·USER run)에서 리뷰 상시화가 켜져 있으면
   (`REVIEW_ENABLED` 기본 true) 작업 워커는 done 전환을 하지 않는다 — `report_result(DONE)`까지만
   하고, done 전환은 검증 run을 통과시킨 리뷰어 페르소나의 몫이다(§5.8). 사람이 PAT로 직접 도구를
   호출하는 경우는 이 규약 그대로다.
6. 모든 기록은 호출에 쓰인 PAT의 페르소나 명의로 남는다(작성자=페르소나 memberId) —
   `tool_call_audit` 테이블에 도구·상태·persona_id가 매 호출마다 적재된다.
7. 이슈 제목·설명·우선순위를 고칠 때는 `update_issue`(AGP-37)를 쓴다 — DB 직접 수정 금지.
   넘긴 필드만 바뀌고(null·생략=그대로, 셋 다 없으면 오류) 나머지는 현재 값으로 되쓴다.
   이슈를 수정하는 도구(`claim_issue`/`update_issue_status`/`update_issue`)와 디스패처 자동 claim은
   모두 `IssueClaimSupport.update`(GET → `IssueUpdateRequest.preserving(현재)` 위에 변경분만 덮기 →
   PUT, 409면 재조회·재머지 1회 재시도)를 거친다 — alm PUT이 full-replace라 필드 매핑을 도구마다 따로
   두면 담당자(`assigneeId` null=해제)가 조용히 지워진다. `details`는 항상 null(확장 필드 보존),
   `mentionedUserIds`도 null(되쓰기마다 멘션 알림 재발송 방지).
8. `create_issue`/`update_issue`의 `description`이 일반 텍스트면(첫 글자가 `<`가 아니면) `& < >`를 이스케이프한 뒤 `<p>`로
   감싼다(AGP-26, `IssueTools.toTipTapHtml`) — `List<String>`·"a < b" 같은 본문이 태그로 먹히지 않는다. `<`로 시작하면 HTML로
   보고 그대로 보낸다.
9. **입력 인코딩 방어(AGP-38)**: 모든 도구 인자(중첩 목록·객체 포함)에 깨진 서로게이트·제어문자(개행·탭·CR 제외)·U+FFFD(비UTF-8
   디코딩 흔적)가 있으면 도구를 실행하지 않고 `오류: 입력 인코딩 거부 — 파라미터 '<경로>': <사유>`로 거부한다. 단일 지점은
   `ToolInputGuard`(서버에 주입되는 `List<SyncToolSpecification>` 빈의 핸들러를 감싸는 BeanPostProcessor — 새 도구도 자동
   적용). 거부도 감사 행(ERROR, summary `입력 거부: <파라미터>` — 값은 안 싣는다)을 남긴다. 정상 한글·이모지는 통과.
10. **도구 오류 문구(AGP-25)**: 다운스트림 404는 `오류: <다운스트림 메시지>`(문구 형식 불변 — 예외 타입만 NotFound),
    401은 `다운스트림 인증 결함 — 권한 없음이 아님, 운영자 확인 필요(재시도로 해결되지 않음): …`, 5xx·연결 실패는 기존
    `권한 서비스/다운스트림 일시 장애 — …잠시 후 재시도하세요: …`. 전체 매핑은 아래 "다운스트림 오류 매핑" 참고.

**다운스트림 오류 매핑(`DownstreamErrors`, AGP-25 — alm/wiki/org/auth 호출 공통)**

| 다운스트림 | 예외 | REST 응답 | 비고 |
|---|---|---|---|
| 401 | `DownstreamAuthException`(`ServiceUnavailableException` 하위) | 503 | 이 서비스가 보낸 자격증명 거부 = 서비스 간 인증 결함. 예전엔 409 |
| 403 | `ForbiddenException` | 403 | 다운스트림 메시지 그대로(불변) |
| 404 | `NotFoundException` | 404 | 다운스트림 메시지 그대로. 예전엔 409 |
| 409(낙관적 락) | `VersionConflictException`(alm·wiki update만) → 재시도 1회 | 409 | 불변 |
| 그 밖 4xx(400 스킴 위반 등) | `ConflictException` | 409 | 불변 |
| 5xx·연결 실패 | `ServiceUnavailableException` | 503 | 불변 |

예외: 서비스 토큰 발급(`AuthTokenClient.mint`)의 404는 호출자가 찾던 대상(이슈 등)이 없다는 뜻이 아니라서 404가 아니라
503으로 바꿔 던진다. best-effort 경로(커밋 링크 파서·서버 코멘트·알림)는 `Exception` 전체를 삼키므로 예외 타입이 바뀌어도
계속 삼킨다.

전체 도구 22종(P1 18종 + P2a run 보고 3종 + AGP-37 `update_issue`, 아래 §5 참고): `whoami`,
`list_projects`, `get_project_context`, `search_issues`, `get_issue`, `create_issue`, `claim_issue`,
`update_issue`, `add_comment`, `log_work`, `link_pr`, `update_issue_status`, `list_spaces`, `find_pages`,
`get_page`, `create_page`, `update_page`, `append_to_page`, `ping`, `report_progress`, `request_gate`,
`report_result`.

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
4. 워커 인증: 같은 사용자 구독을 재사용하거나(검증됨 — `%USERPROFILE%\.claude` 자격증명 파일)
   `CLAUDE_CODE_OAUTH_TOKEN` / `ANTHROPIC_API_KEY`를 이 서비스의 프로세스 env로 둔다.
   **env 화이트리스트 커튼(AGP-48)**: `ProcessCommandExecutor`는 자식 프로세스(워커 `claude -p`·
   `git clone`·커밋 수확 git — 모든 자식이 이 한 지점을 지난다)에 부모 env를 상속하지 않는다.
   `environment().clear()` 후 다음만 넣는다.
   - 구동 최소 세트(`BASE_ALLOWED_KEYS`, 대소문자 무관 매칭) — Windows 키: `PATH` `PATHEXT`
     `SYSTEMROOT` `SYSTEMDRIVE` `COMSPEC` `WINDIR` `TEMP` `TMP` `USERPROFILE` `HOMEDRIVE` `HOMEPATH`
     `APPDATA` `LOCALAPPDATA` `PROGRAMDATA` `USERNAME` `USERDOMAIN` `COMPUTERNAME`
     `NUMBER_OF_PROCESSORS` `PROCESSOR_ARCHITECTURE` `JAVA_HOME` + POSIX 키(Linux 워커 호스트용,
     최종 리뷰 M1): `HOME` `LANG` `LC_ALL` `LC_CTYPE` `TMPDIR` `USER` `LOGNAME` `SHELL` — HOME이
     없으면 git이 `~/.gitconfig`(user.name/email 등)를 못 읽어 워커 커밋이 실패하고, LANG이 없으면
     C 로케일로 한글 경로가 이스케이프된다. 전부 경로·로케일 값이라 비밀 차단 목적과 충돌 없음.
     2026-09-26 Windows 세트로 `git clone`·`claude --version`·헤드리스 `claude -p`(구독 인증)·
     워커 `Bash(git *)` 도구·`gradlew`가 도는 것을 실측했다(Linux 세트는 P2b 컨테이너화 때 실측 예정).
   - 인증 2종은 커튼 목록에 없다 — `WorkerLauncher.workerEnv()`가 **워커 호출에만** extraEnv로
     싣는다(`git clone`에는 안 간다). 이것이 인증 키가 워커에 닿는 유일한 경로다.
   - 이스케이프 해치 `WORKER_EXTRA_ENV_KEYS`(`platform.agent.worker.extra-env-keys`, 쉼표 목록,
     기본 빈): 온프렘 변형용 — 프록시(`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`), 사내 CA
     (`NODE_EXTRA_CA_CERTS`/`GIT_SSL_CAINFO`), Git Bash 위치(`CLAUDE_CODE_GIT_BASH_PATH`) 등.
     이름만 적고 값은 부모 env에서 가져온다. 자격증명 키를 넣으면 워커에 노출된다.
   - 호출부가 명시한 extraEnv는 커튼 뒤에 덧씌운다(명시값이 이긴다).
   - git 자격증명이 env로 오는 구성(예: `GH_TOKEN`으로 사내 리포 clone)은 커튼 뒤에서 clone이
     깨진다. 현 구성은 공개 리포 + 로컬 git credential manager(자격증명이 env가 아님)라 무해하다 —
     바꿔야 하면 해치로 명시적으로 연다.
   - **한계**: 이것은 env 누출 방어일 뿐 **프로세스 격리가 아니다** — 워커는 같은 사용자 계정으로
     돌아 파일시스템(`%USERPROFILE%` 아래 다른 자격증명 파일 포함)과 네트워크를 그대로 공유한다.
     컨테이너 격리는 P2b 몫이다.

자동화 정책: 라벨 `auto` + 상태 `todo`인 ALM 이슈만 픽업 대상이고, 한 틱에 하나만 새로
픽업한다(단순화, 브리핑 지시). 검색 첫 페이지가 활성 run 보유·예산 거부·프로젝트 한도 초과 이슈로 가득 차도
뒤 후보가 굶지 않게 후보를 찾거나 마지막 페이지(`page·size·total`로 판정)에 닿을 때까지 다음
페이지로 넘어간다 — 한 틱 상한 `SCHEDULER_MAX_PICK_PAGES`(`scheduler.max-pick-pages`, 기본 5,
0 이하는 5로 본다)(AGP-52). 동시성은 `SCHEDULER_MAX_GLOBAL`(기본 2, 전역 QUEUED+RUNNING)
· `SCHEDULER_MAX_PER_PROJECT`(기본 1, 프로젝트별) — 픽업 단계에서만 본다(이미 QUEUED인 run을
드레인하는 건 다시 게이트하지 않음).

**실패 정책(P3d, 2026-09-27 사용자 결정 AGP-55 — P2a "재시도 3회 → BLOCKED"를 대체)** — **사고형(인프라) 실패는
재시도하지 않고 즉시 중단한다.** 프로세스 비정상 종료·시간 초과·워커 self-FAILED(`report_result(FAILED)`)·준비 단계 실패
(리포 매핑 없음·claim 실패 등)·실행 인프라 예외·리뷰 무판정 종료·회의/매니저 무보고 종료는 전부 continuation 없이 곧바로
`BLOCKED`("사고형 실패 — 즉시 중단: <원인 요지>") + 이슈 ⛔ 코멘트 + 메일 알림(§5.11) + 자동 에스컬레이션 훅(§5.9, 설정 시)이다
(`RunService.blockIncident`). 같은 조건에서 다시 돌리면 대개 같은 이유로 또 죽으면서 예산만 태운다는 판단이다. 복구는 사람
재개(§5.4)뿐이다. 리뷰 반려는 사고가 아니라 판정이라 별도 예산(§5.8 반려 예산)으로 센다. `attempt`는 이제 계보 순번일 뿐
예산이 아니다. BLOCKED는 픽업 후보에서 완전히 제외된다(`RunService.ACTIVE_STATUSES`에 BLOCKED 포함 — 없으면 같은 이슈를
attempt=1부터 무한 재픽업하는 회귀가 난다, task-7 E2E 실측).

**폐기 설정**: `SCHEDULER_RETRY_MAX_ATTEMPTS`(`scheduler.retry-max-attempts`)는 기존 배포 env가 기동을 깨지 않게 바인딩만
남아 있고 **코드는 읽지 않는다**(값이 무엇이든 무시). 반려 한도는 `REVIEW_REJECT_MAX`로 옮겨 갔다.

**모델 정책(P2c)** — run의 `--model`은 다음 순서로 정해진다(`SchedulerProperties.modelFor`):
USER run 요청의 `model` > 프로젝트별 맵 `platform.agent.scheduler.project-models.<KEY>`(대소문자
무관 조회) > 전역 기본 `SCHEDULER_DEFAULT_MODEL`(`scheduler.default-model`) > 비움(`--model`
생략, 워커 기본 모델). 스케줄러 픽업 run은 요청 단계가 없으므로 맵부터 본다. 프로젝트별 맵은
위 `repos`와 같은 relaxed binding 함정(env var로 넣으면 키가 소문자로 접힘)이 있어 조회가 대소문자
무관이긴 하지만, 주입은 프로그램 인자(`--platform.agent.scheduler.project-models.AGP=...`)를
권장한다. REVIEW run은 `REVIEW_MODEL`(`review.model`) > 프로젝트 맵 > 전역 기본 순이며 부모 TASK
(USER가 지정한 모델 포함)의 모델을 이어받지 않는다. 반려-fix run은 원 TASK run의 모델을 그대로
승계한다. 게이트 승인·재개 continuation도 직전 run의 모델을 승계한다.

### 5.2 예산·킬 스위치

`BUDGET_MONTHLY_USD`(기본 100, 캘린더 월 UTC 누적 — 플랫폼 전체·프로젝트별에 P2a는 같은 값)
· `BUDGET_PER_RUN_USD`(기본 5 — 넘어도 run을 되돌리지 않고 이슈에 경고 코멘트만 남긴다).
`GET /api/agent/budget`(인증된 누구나)로 현재 월 사용량·캡·킬스위치 상태 조회.
`GET /api/agent/kill-switch`(누구나) / `POST /api/agent/kill-switch`(ADMIN, body
`{"on": true|false}`)로 즉시 전체 차단·해제. **킬 스위치는 인메모리다 — 재기동하면 이 값과
무관하게 항상 꺼진 상태(off)로 시작한다**. BLOCKED 알림(메일)은 §5.11 — 예산·킬 스위치 자체의 알림은 아직 없다.

`BudgetGuard`의 실구현은 `BudgetService`다 — `BudgetGuardConfig`의 permissive 기본값은 `@ConditionalOnMissingBean`으로
밀려나는데, 이것이 자동구성이 아닌 일반 설정이라 빈 등록 순서에 기댄다. `BudgetGuardWiringTest`(AGP-50)가 실 컨텍스트에서
BudgetGuard 빈이 `BudgetService` 하나뿐이고 `RunService`가 그것을 쓰는지 고정한다 — 깨지면 킬 스위치·캡이 조용히 "항상 허용"이 된다.

킬 스위치/예산 캡(`BudgetGuard.allow`)은 `RunService.execute` 진입점 자체에서 확인한다(최종
리뷰 I3) — 새 이슈 픽업뿐 아니라 QUEUED continuation 드레인, 게이트 승인, BLOCKED/FAILED
사람 재개까지 **전부** 이 지점을 거치므로 어느 경로로 실행이 트리거되든 예외 없이 차단된다.
거부된 run은 실패 처리하지 않고 QUEUED로 그대로 둔다 — 스위치를 끄거나 캡이 회복되면 다음
드레인 틱이 실패로 치지 않고 다시 집어간다. 단 **스케줄러 off 환경에서는 킬스위치/캡 해제 후
자동 재개가 없다**(드레인 부재) — 그 run을 cancel한 뒤 재요청하거나 스케줄러를 켠다.

### 5.3 게이트 승인 흐름

워커가 `request_gate(runId, kind, request)`를 부르면 그 run이 `WAITING_APPROVAL`로 전환되고
`Gate` 행이 생긴다(kind: `MERGE`|`ESCALATION`|`PLAN`). `GET /api/agent/gates?pending=true`
(누구나 — 생략/`false`면 요청순 최신 50건)로 대기열을 본 뒤 `POST /api/agent/gates/{id}/approve`
| `reject`(ADMIN)로 결정한다. 승인은 원 run 행을 되돌리지 않고 **continuation run(attempt+1)을
새로 만들어** 큐잉·비동기 실행하고, 원 run은 CANCELLED로 닫는다(D9 — WAITING_APPROVAL에 그대로
두면 다음 디스패처 픽업의 "활성 run 중복" 가드와 동시성 집계에 계속 걸린다). 거절은 원 run을
CANCELLED로 닫고 재개하지 않는다.

### 5.4 BLOCKED·FAILED 사람 재개

사고형 즉시 중단(§5.1)·반려 한도 소진(§5.8)·반려 계보 결함으로 `BLOCKED`가 된 run은 게이트와는 별개 경로로 사람이 재개한다
(알림 메일 본문에도 이 경로가 적힌다, §5.11):
`POST /api/agent/runs/{id}/resume`(ADMIN) — `Gate` 행 없이 continuation run(attempt+1)을 만들어
재실행한다(§5.3 승인과 동일 패턴, 다만 사람이 먼저 승인을 요청받은 게 아니라 시스템이 스스로
멈춘 것이므로 게이트 엔티티가 없다). `FAILED`도 재개 대상이다(최종 리뷰 I1) — 정상 경로에서는
워커가 스스로 `report_result(FAILED)`로 종결해도 `RunService`가 곧바로 BLOCKED로
옮기므로 FAILED에 오래 머물지 않지만, 그 처리 자체가 예외로 실패하는 잔여 케이스에서는 FAILED에
멈출 수 있다 — 그때도 이 경로로 재개한다. 대상 run이 BLOCKED·FAILED 둘 다 아니면 409. 재개 continuation은 모델·지시문·
계보 워크스페이스와 함께 **반려 누적(`reject_count`)을 그대로 승계**한다 — 재개가 반려 예산을 초기화하지 않는다(반려 한도로
멈춘 REVIEW를 재개하면 같은 워크스페이스에서 리뷰를 다시 돌리고, 또 반려되면 곧바로 다시 BLOCKED다). 사고형 실패로 멈춘
run은 원인을 고친 뒤 재개한다 — 고치지 않고 재개하면 같은 이유로 다시 BLOCKED + 알림이 온다.

`run.error`는 덮어쓰지 않고 누적된다(AGP-53) — 실패 원인 → BLOCKED 사유 → 재개/취소 노트가
`--- [UTC 타임스탬프] ---` 구분선으로 원인이 앞, 최신이 뒤에 쌓인다. 빈 노트(`cancel()`)는 기존
기록을 지우지 않는다. 8000자 상한을 넘으면 앞 6000자(최초 원인)를 보존하고 남은 자리에 최신 항목의
앞부분을 담아 `…(truncated)`로 표시한다(중간 이력만 잘린다).

### 5.5 run 감독 REST

`GET /api/agent/runs?status=`(인증된 누구나, `status` 생략 시 전체) ·
`POST /api/agent/runs/{id}/cancel`(ADMIN — DB 상태만 CANCELLED로 옮긴다. 실행 중인 워커 OS
프로세스를 강제로 죽이지는 않는다, Windows 프로세스 트리 관리는 P2a 범위 밖).

**USER run 생성(P2c, AGP-42)** — `POST /api/agent/runs`(ADMIN — 예산을 소모하는 행위라
cancel/resume과 같은 권한). 라벨 `auto`·상태 `todo` 조건과 무관하게 사람이 이슈 하나를 지정해
run을 띄운다.

```json
{"issueKey": "AGP-42", "instruction": "선택, 4000자 이하", "model": "선택, 60자 이하", "personaSlug": "선택"}
```

- 응답: 201 + `RunSummaryResponse`(id·issueKey·status=QUEUED·personaId·attempt·model·startedAt·endedAt·
  type·trigger·parentRunId — 목록 API와 같은 요약. 뒤 셋은 P3a에서 추가). 저장되는 run은 TASK·trigger=USER다.
- 페르소나: 지정 슬러그 > `SCHEDULER_PERSONA`(기본 슬러그). 못 찾으면 404 — 스케줄러처럼 조용히
  건너뛰지 않는다. 비활성 페르소나는 400("비활성 페르소나입니다: <slug>", AGP-29 — 큐에 넣어도 run 토큰 발급이 막혀
  사고형 BLOCKED로 끝나므로 요청 시점에 거부).
- 이슈는 그 페르소나 bearer로 ALM에서 확인하고 run에는 ALM이 돌려준 정본 키를 쓴다. 없는 이슈는
  **404**(alm 메시지 그대로 — AGP-25로 409에서 바뀜, 아래 §3 "다운스트림 오류 매핑").
- 같은 이슈에 활성 run(QUEUED/RUNNING/WAITING_APPROVAL/BLOCKED)이 있으면 409.
- `instruction`은 앞뒤 공백을 자른 뒤 저장되고, 워커 프롬프트에 `<사용자-지시>` 경계 섹션으로
  실린다(§5.6). 게이트 승인·재개·반려-fix continuation과 REVIEW run까지 승계된다 — 리뷰어도
  변경이 지시를 따르는지 확인한다(지시 위반은 반려 사유).
- 실행 제출은 컨트롤러가 `RunService.execute`(`@Async`) 프록시로 한다. 워커 스레드풀이 포화돼
  제출이 거부돼도 run은 이미 QUEUED로 커밋됐으므로 201을 돌려준다 — 다음 드레인 틱이 집어간다
  (스케줄러가 꺼져 있으면 드레인도 없으므로 QUEUED에 머문다).
- 킬 스위치·예산 캡은 §5.2대로 `execute` 진입점에서 걸린다.

### 5.6 워커 계약 요약

- 헤드리스 `claude -p`를 **비-bare로** 실행한다: `--permission-mode dontAsk
  --permission-prompts none --allowedTools <허용목록> --strict-mcp-config --mcp-config <파일>
  --output-format json --max-turns N [--model M]`.
- 일반 TASK run(사람 재개 포함)은 run마다 새 워크스페이스(`AGENT_WORK_DIR/run-{id}`)를 만들어
  `git clone` 후, 하네스(`.claude/` 번들 + 루트 `CLAUDE.md`/`AGENTS.md`)를 그 워크스페이스에
  실체화한다(`HarnessMaterializer`) — 워커가 플랫폼 협업 규약을 그대로 보고 작업하게 하기 위함. 번들 안의
  `settings.local.json`(운영자 개인 권한 허용 목록·env·MCP 설정)은 어느 깊이에 있든 복사하지 않는다(AGP-51) — 워커가
  운영자 로컬 권한으로 돌거나 워커 커밋에 섞여 나가는 것을 막는다. 공유 설정은 `settings.json`에 둔다.
- **예외 — 계보 run(P2c)**: REVIEW run, 반려-fix run, 그리고 이 둘의 재개는 원 TASK run의
  워크스페이스를 **승계**한다(`Run.isWorkspaceLineage()` — REVIEW 전부 + `parentRunId`가 있는
  TASK). 워커 커밋은 푸시되지 않고 그 워크스페이스에만 있으므로(`CommitLinkParser`가
  `origin/main..HEAD`를 본다) 새로 clone하면 검증·수정 대상이 사라진다. 그래서 clone·하네스
  실체화를 생략하고, 이를 위해 `run.workspace_path`에 실제 경로를 영속화한다(P2a까지는 "pending"
  고정이었다). 승계 워크스페이스가 비었거나("pending") 디렉터리가 사라졌으면 **clone으로 대신하지
  않고 실패**시킨다 — 새 clone 위에서는 diff가 비어 리뷰어가 빈 변경을 통과시킬 수 있기 때문이다
  (사고형 즉시 BLOCKED로 사람에게 넘어간다).
- mcp-config는 **인라인 JSON 인자가 아니라 파일**(`.mcp-run.json`)로 넘긴다 — Windows
  `ProcessBuilder`가 인자를 재조립(re-quote)할 때 JSON 안 따옴표가 사라지고 `/`가 `\`로
  바뀌어 CLI가 손상된 문자열을 파일 경로로 오인해 즉시 죽는 버그를 피한다(F1, task-7 E2E
  3회 100% 재현). 그 파일은 **워크스페이스(git 클론 루트) 안이 아니라 형제 디렉터리**
  (`AGENT_WORK_DIR/run-{id}-cfg/`, 계보 run도 워크스페이스 이름이 아니라 **자기** run id 기준 —
  같은 워크스페이스를 잇는 run끼리 한쪽의 `finally` 삭제가 다른 쪽 토큰 파일을 지우지 않게)에
  둔다(최종 리뷰 I4) — 워커가 `Bash(git *)`를 허용
  도구로 갖고 있어 클론 트리 안에 있으면 워커의 커밋에 실려 나갈 위험이 있다(하네스 수확
  워크플로가 워커 커밋을 체리픽한다). 이 파일에는 run 토큰(Bearer)이 그대로 담기므로 절대
  로그로 남기지 않고, 실행 후 `finally`에서 그 디렉터리 전체를 재귀적으로 삭제 + PAT 철회한다.
- 프롬프트는 이슈 본문·최근 코멘트를 `<이슈-내용>`/`<코멘트>` 경계로 감싸 "데이터"로 표시한
  뒤 규약 섹션을 둔다 — **사람 코멘트 = 지시**로 취급하되, 그 안에 규약과 충돌하는 문구가
  있으면 규약이 우선한다는 문장을 경계 직후에 못박는다(프롬프트 인젝션 방어, fix round 1 I2).
  USER run의 지시문도 같은 방어를 받는다(P2c): 코멘트 다음에 `<사용자-지시>` 경계로 싸고, 규약
  우선 문장이 이 블록까지 포괄한다. 지시문이 없으면 프롬프트는 P2a와 바이트 단위로 같다(골든 테스트).
- 프롬프트에 종결 3종 도구 호출을 runId와 함께 명시한다 — 워커는 `report_progress`로 수시
  진행 보고, 완료/실패/차단 시 반드시 `report_result(status=DONE|FAILED|BLOCKED)`를 호출해야
  하고, 사람 승인이 필요하면 `request_gate`를 부른다.
- TASK 프롬프트의 done 규약(P2c): 리뷰 상시화가 켜져 있으면(`REVIEW_ENABLED`, 기본 true) "이슈를
  done 계열로 바꾸지 마라, 완료는 `report_result(DONE)`까지"를 명시한다 — done 전환은 리뷰어만
  한다(§5.8). 꺼져 있으면 P2a 규약(워커가 직접 done) 그대로다. 반려-fix run 프롬프트에는 "이전
  run의 커밋 위에 코멘트의 리뷰 지적을 반영해 이어서 커밋하라"가 추가된다. REVIEW run은 작업 규약
  대신 리뷰 규약 섹션을 받는다(§5.8).
- 종결 시 `CommitLinkParser`가 `origin/main..HEAD` 범위의 로컬 커밋 메시지에서 이슈 키
  (정규식 `[A-Z][A-Z0-9_]{1,11}-\d+`)를 찾아 COMMIT 종류 웹링크로 이슈에 연결한다(순수 git
  파싱, best-effort — 실패해도 run 처리를 막지 않는다). PR 연결은 별도 도구 `link_pr`(P1,
  웹링크).

### 5.7 E2E 검증 이력

2026-09-06: 무인 루프 전체 왕복(디스패처 픽업 → 워커 실행 → `report_result` → 커밋/PR 수확)을
실제로 완주 — run 16건이 13분·$3.93으로 `DONE`, 산출물이 `main`에 병합(`b3a7f68`). 상세는
각 태스크 보고서(`.superpowers/sdd/2026-09-06-agent-service-p2a/task-*-report.md`)를 참고.

2026-09-27: **회의 run(§5.9) 실기 E2E 완주** — 호스트 프로세스(새 코드)+socat 브리지 4개(도커
프로드 클러스터의 auth/alm/wiki/org 직결, no-ports 우회)로 `MEETING_RETRO_CRON` 1회 발화 →
RETRO run 17이 3분 12초·$1.93(claude-fable-5-1)에 완주: 회의록 페이지 53(템플릿 준수, 1인
회의·빈 회고 자료를 스스로 문제로 등재), 개선 이슈 3건 자동 생성(AGP-58~60), 관련 이슈 링크
코멘트, `report_result(pageId=53)`. V4~V6 마이그레이션이 프로드 agentdb에 이때 적용됨.
검증 안 된 것: 게시판 REST 실렌더(office API는 JWT 필요 — 단위 테스트로만), 리뷰 루프
(TASK→REVIEW) 실기 — 리뷰어 페르소나 부트스트랩 후(AGP-59).

### 5.8 리뷰 상시화 루프 (P2c, 2026-09-26, AGP-44 — "검증 없는 확정 없음")

P2a까지는 워커가 `report_result(DONE)` 후 스스로 이슈를 done으로 바꿨다 — 아무도 검증하지 않은
확정이었다. P2c는 TASK run이 DONE이 되면 **다른 페르소나(리뷰어)의 REVIEW run**이 뒤따르고,
done 전환은 리뷰 통과 시 리뷰어만 하도록 바꿨다. 구현은 `ReviewService` + `RunService.applyOutcome`
+ `WorkerLauncher`(REVIEW 프롬프트·워크스페이스 승계).

**운영 경고 — 배포 전에 반드시 읽을 것.** `REVIEW_ENABLED` 기본값이 **true**다. 이 상태에서
`REVIEW_PERSONA`를 설정하지 않고 배포하면 리뷰를 띄울 수 없으므로 **모든 TASK run(스케줄러 무인 run과
USER run 모두)이 미확정으로 멈춘다**(run은 DONE, 이슈는 inprogress + 경고 코멘트). 배포 전에:

1. §1(MCP 접속 가이드)의 페르소나 생성 절차로 **`role=REVIEWER`** 페르소나를 만든다(대상 프로젝트
   EDITOR 이상 grant — 코멘트·상태 전환·위키 보고서를 써야 한다). 작업 페르소나(`SCHEDULER_PERSONA`
   등)와 다른 슬러그여야 한다.
2. `REVIEW_PERSONA=<그 슬러그>`를 설정한다. `REVIEW_MODEL`은 선택(비우면 §5.1 모델 정책).
3. 리뷰를 끄려면 `REVIEW_ENABLED=false` — P2a 동작(작업 워커가 직접 done) 복원이며, 명시적으로
   고를 때만 쓴다.

**흐름**

1. **훅 위치**: TASK run이 DONE으로 종결되면 `applyOutcome`(워커의 `report_result` 자기 종결 경로와
   프로세스 정상 종료 경로의 합류점)에서 `ReviewService.onRunDone`을 부른다. `report_result` 도구
   시점에 걸지 않는 이유: 그때는 워커 프로세스가 아직 살아 있어 같은 워크스페이스를 두 프로세스가
   쓰게 되고, 워크스페이스 실경로도 프로세스 종료 후에야 기록돼("pending") 리뷰가 항상 fail-closed로
   떨어진다.
2. **REVIEW run 생성**: 리뷰어 페르소나 명의 REVIEW run(attempt=1, trigger는 부모 것, `parentRunId`=
   원 TASK)을 만들어 즉시 제출하고, 이슈에 "검증 run 시작" 코멘트를 남긴다. 원 TASK 워크스페이스를
   재사용한다(§5.6 계보 run). REVIEW run은 **이슈를 claim하지 않는다** — 담당자는 작업자로 남는다.
   스케줄러 run뿐 아니라 USER run(§5.5)의 TASK DONE에도 똑같이 걸린다.
3. **리뷰어 프롬프트**: `git diff origin/main..HEAD`(작업자의 로컬 커밋)를 검토하고, 코드를 고치거나
   커밋하지 말고 판정만 하라는 리뷰 규약을 받는다. 판정 채널은 `report_result` status 하나다.
   - **통과**: 리뷰어가 `add_comment`(승인 사유) → `update_issue_status(done)` → `report_result(DONE)`.
     REVIEW의 DONE은 새 REVIEW를 낳지 않는다(무한루프 가드 — `onRunDone`은 TASK만 처리하고,
     `Run.queuedReview`도 DONE TASK만 부모로 받는다).
   - **반려**: 리뷰어가 `add_comment`(파일·위치·고칠 내용) → `report_result(FAILED)`. 이슈 상태는
     건드리지 않는다. REVIEW의 FAILED는 인프라 실패가 아니라 판정이므로 사고형 중단이 아니라
     `onReviewRejected`(반려 예산 판단)로 넘긴다.
   - **판정 없이 정상 종료**(`report_result` 없이 프로세스만 성공)는 통과가 아니라 **사고형 실패**로 본다 —
     즉시 BLOCKED + 알림(§5.1). 시간 초과·비정상 종료 같은 REVIEW의 인프라 실패도 같다. 사람이 재개하면
     continuation이 같은 워크스페이스·계보를 승계한다.
   - 리뷰어가 `report_result(BLOCKED)`를 부르면 REVIEW run은 BLOCKED로 사람 재개를 기다린다.
4. **반려-fix**: 원 페르소나 명의 TASK run을 만든다(`Run.fixContinuation` — 워크스페이스·모델·
   지시문 승계, `parentRunId`=반려한 REVIEW run). attempt는 원 TASK의 순번 +1(계보 순번일 뿐 예산 아님),
   `reject_count`는 원 TASK +1(아래 반려 예산). 리뷰 지적 코멘트는 기존 최근 코멘트(10건) 경로로 fix
   프롬프트에 들어간다. fix run이 다시 DONE이 되면 그 DONE에 대해 새 REVIEW가 뜬다(fix도 TASK이므로) — 그
   REVIEW의 "원 TASK"는 직전 fix라 카운터가 계보를 따라 1→2→3…으로 쌓인다.
5. **한도 초과·이어갈 수 없음**: 반려 누적이 한도를 넘거나("반려 한도 N회 소진 — 사람 확인 필요", 알림 종류
   `REJECT_LIMIT`), 원 TASK run을 찾을 수 없거나, 원 워크스페이스 경로가 없으면(알림 종류 `LINEAGE_BROKEN`)
   반려한 REVIEW run을 **BLOCKED로 승격**하고 이슈에 ⛔ 사유 코멘트 + 메일 알림(§5.11)을 남긴다(이슈는
   inprogress). 자동 에스컬레이션 훅은 한도 소진 분기만 탄다. 사람이 확인 후 `POST /api/agent/runs/{id}/resume`
   (§5.4)으로 재개한다 — 이때 만들어지는 continuation은 REVIEW의 연장이라 **fix가 아니라 같은 워크스페이스에서
   리뷰를 다시 돌린다**(사람이 워크스페이스를 직접 손본 뒤 재검증하는 용도). 반려 누적은 승계되므로 재검증이 또
   반려되면 곧바로 다시 BLOCKED다.

**반려 예산(P3d, D-P3d-2 — 인프라 재시도와 분리)** — `run.reject_count`(V7, 기본 0)가 한 작업 계보의 반려 횟수를
센다. 반려-fix는 원 TASK 값 +1을, 게이트 승인·재개 continuation과 REVIEW run은 직전 값을 그대로 싣는다(REVIEW 쪽 값은
감독·알림 표시용이고 판정은 부모 TASK 값으로 한다). 반려 시 `newCount = 원 TASK.reject_count + 1`:

| 조건 | 결과 |
|---|---|
| `newCount > REVIEW_REJECT_MAX` | fix 없이 REVIEW run BLOCKED("반려 한도 N회 소진 — 사람 확인 필요") + ⛔ 코멘트 + 메일(`REJECT_LIMIT`) + 자동 에스컬레이션 훅 |
| `newCount ≥ 알림 임계` (한도 이내) | fix run 생성·제출 + "🔁 리뷰 반려 — 수정 run N 시작(반려 k/max) … ⚠️ 반려 알림 임계 도달" 코멘트 + 메일(`REJECT_THRESHOLD`) — 진행은 계속 |
| 그 밖 | fix run 생성·제출 + "🔁 리뷰 반려 — 수정 run N 시작(반려 k/max)" 코멘트 |

| 키 | env | 기본 | 의미 |
|---|---|---|---|
| `review.reject-max` | `REVIEW_REJECT_MAX` | `2` | 자동 수정(반려-fix) 수용 한도. `0`이면 첫 반려부터 사람 확인. 음수는 0으로 본다 |
| `review.reject-alert-at` | `REVIEW_REJECT_ALERT_AT` | 빈 값(= `reject-max`) | 알림 임계. 1 미만도 `reject-max`로 본다. `reject-max`보다 크면 그 전에 BLOCKED로 끝나 임계 알림은 영영 안 온다 — 기동 시 warn 로그(한도 소진 알림만 동작) |

기본값(2/2)이면: 1차 반려 → fix(1/2), 2차 반려 → fix(2/2) + 임계 알림, 3차 반려 → BLOCKED + 한도 소진 알림.
P2c~P3c의 "attempt가 `SCHEDULER_RETRY_MAX_ATTEMPTS`를 넘으면 반려 BLOCKED" 결합은 제거됐다 — 반려 판단은 이 카운터만 본다.

**fail-closed(리뷰를 띄울 수 없는 경우)** — 아래는 REVIEW run을 만들지 않고, TASK run은 DONE으로
두되 이슈를 inprogress에 남긴 채 "⚠️ 검증 run을 만들 수 없음 — 사람이 확인하세요" 코멘트를 단다:
`REVIEW_PERSONA` 미설정, 슬러그의 페르소나 미존재·비활성, 리뷰어가 작업자와 같은 페르소나(자기
승인은 검증이 아니다), 원 TASK의 워크스페이스 경로가 비었거나 "pending". REVIEW run이 만들어진
뒤 실행 시점에 승계 워크스페이스 디렉터리가 사라졌으면 fresh clone으로 폴백하지 않고 실패시킨다
(§5.6 — 빈 diff 통과 방지). 후처리 자체가 예외로 새면 로그만 남기고 삼킨다(TASK는 DONE, 이슈는
미확정 — 사람 눈에 띄는 쪽으로 실패한다).

**한계(알고 쓸 것)**

- 리뷰어의 "코드 수정·커밋 금지"는 **프롬프트 수준 규약일 뿐**이다 — REVIEW run의 워커도 TASK와
  같은 `allowedTools`(`Edit`/`Write`/`Bash(git *)` 포함)로 실행되므로 도구 권한으로 막혀 있지 않다.
  마찬가지로 TASK 워커의 "done 전환 금지"도 프롬프트 규약이며, `update_issue_status` 도구 자체는
  TASK run 토큰으로도 호출 가능하다.
- 리뷰 판정은 리뷰어가 스스로 부르는 도구 호출(코멘트·상태 전환·`report_result`)의 조합이다 — 서버는
  `report_result` status만 보고 흐름을 분기하며, 통과 시 리뷰어가 실제로 done 전환을 했는지는
  검사하지 않는다.
- 워크스페이스는 로컬 디스크에만 있다(푸시 없음). `AGENT_WORK_DIR`를 비우거나 다른 호스트로 옮기면
  진행 중인 계보(REVIEW·반려-fix)는 실패 → BLOCKED로 끝난다.
- 실제 무인 루프에서의 리뷰 왕복 E2E는 이 문서 작성 시점(T3 커밋 `0c2837a`) 기준 단위 테스트
  (`ReviewServiceTest`·`RunServiceTest`·`WorkerLauncherTest`·`RunLineageTest`)로만 검증됐다 — 도그푸딩 실측 결과는 이후 추가한다.

### 5.9 회의 run (P3b, 2026-09-27, AGP-15 — 스펙 §6)

회의는 별도 엔진이 아니라 같은 워커 파이프라인의 run 타입 3종이다(`RunType.MEETING`(착수/계획)·`RETRO`(회고)·
`ESCALATION`). 워커(`claude -p`)가 회의 프롬프트로 참석 페르소나를 롤플레이하고, 산출물은 위키 회의록 페이지다.
구현은 `MeetingService`(소집·참석 규칙·워커 입력·자동 트리거) + `WorkerLauncher.buildMeetingPrompt` + `MeetingController`.
게이트·예산·킬 스위치는 TASK와 똑같이 `RunService.execute` 진입점에서 걸린다.

**설정(`platform.agent.meetings.*`)**

| 키 | env | 기본 | 의미 |
|---|---|---|---|
| `space-id` | `MEETING_SPACE_ID` | 빈 값 | 회의록 위키 스페이스. 비면 소집 API 400, 회고 cron·자동 에스컬레이션은 건너뜀. 진행자 페르소나에 이 스페이스 EDITOR grant 필요 |
| `retro-cron` | `MEETING_RETRO_CRON` | 빈 값(off) | Spring cron 6필드, **Asia/Seoul**. `SCHEDULER_ENABLED=false`여도 이것만으로 동작 |
| `manager-cron` | `MEETING_MANAGER_CRON` | 빈 값(off) | 매니저 순찰 주기(P3c) — 형식·조건은 retro-cron과 같다. §5.10 |
| `auto-escalation` | `MEETING_AUTO_ESCALATION` | `false` | BLOCKED 승격 시 ESCALATION run 자동 생성(비용 때문에 기본 off) |
| `auto-issue` | `MEETING_AUTO_ISSUE` | `true` | 결정→이슈를 워커가 `create_issue`로 직접. false면 PLAN 게이트 경유 |

**소집 API — `POST /api/agent/meetings`(ADMIN)**

```json
{"type": "MEETING|RETRO|ESCALATION|MANAGER", "projectId": 1, "agendaIssueKey": "선택, 40자", "agenda": "선택, 4000자", "personaSlugs": ["선택, 20명 이하"]}
```

- 201 응답: `{"run": RunSummaryResponse, "attendees": [{"personaId","slug","name","role","emoji"}]}` — attendees[0]이 진행자.
- 참석자: `personaSlugs`가 있으면 그 순서(첫 번째 = 진행자, 없는 슬러그 404·비활성 400). 없으면 **활성** 페르소나 중
  계획=PLANNER·DESIGNER·FRONTEND·BACKEND, 회고=전원, 에스컬레이션=그 이슈에서 run을 돌린 페르소나(회의 run 제외)+REVIEWER
  (관련 run이 없으면 PLANNER+REVIEWER)를 롤 순서(PLANNER→DESIGNER→FRONTEND→BACKEND→OPS→REVIEWER)·id 순으로 세운다.
  진행자가 run 소유 페르소나다(run 토큰·기록 명의). 참석자 id는 `run.attendee_persona_ids`에 저장돼 continuation까지 승계된다.
- `agendaIssueKey`가 있으면 진행자 bearer로 ALM에서 확인(없는 이슈 404 — AGP-25 이전엔 409)하고 다른 프로젝트 이슈면 400,
  run.issueKey는 ALM 정본 키. 없으면 **`PROJECT-<projectId>`** 대표 키(`Run.projectIssueKey`) — 이 run은
  `hasAgendaIssue()=false`라 서버 코멘트·`report_progress`/`report_result`/`request_gate` 코멘트를 전부 건너뛴다.
  알려진 판정 한계(수용): ALM 프로젝트 키를 문자 그대로 `PROJECT`로 만든 설치에서 이슈 번호가 projectId와 우연히 같은
  안건(`PROJECT-<projectId>`)을 지정하면 합성 키로 오판해 그 회의의 이슈 코멘트가 생략된다 — 프로젝트 키 `PROJECT`를 피하라.
- `projectId`는 ALM `getProject`로 확인하고(없는 프로젝트 404 — AGP-25 이전엔 409) 그 키로 모델 정책(§5.1)을 푼다. MEETING·ESCALATION은 `agendaIssueKey`·`agenda`
  둘 다 없으면 400(빈 회의 방지). `agenda`는 `run.instruction`에 들어가 `<사용자-지시>` 경계로 프롬프트에 실린다.
- 같은 프로젝트에 활성(QUEUED/RUNNING/WAITING_APPROVAL/BLOCKED) 회의 run이 있으면 409. 이슈 단위 중복 가드는 쓰지 않는다.
  단, 안건 이슈가 있는 회의 run은 활성인 동안 그 이슈의 TASK 생성·픽업을 막는다(`existsByIssueKeyAndStatusIn`이 타입 무관).
- 실행 제출은 컨트롤러가 `RunService.execute` 프록시로(§5.5와 같은 이유).

**실행(워커 회의 모드)** — `RunService.buildJob`이 회의 타입이면 리포 매핑·claim을 전부 건너뛰고 `MeetingService.buildJob`에
넘긴다(안건 이슈가 있으면 본문·최근 코멘트 10건만 읽는다). `WorkerLauncher`는 clone 없이 빈 워크스페이스
(`AGENT_WORK_DIR/run-{id}`) + 하네스 실체화만 한다. 회의 워크스페이스는 git 저장소가 아니므로 커밋 링크 파서를 태우지 않는다
(git이 상위 디렉터리 저장소를 찾아 남의 커밋을 링크할 수 있어서). RETRO는 서버가 최근 24시간 그 프로젝트 run 요약(최대 30건)을
`<최근-run>` 블록으로 싣는다(워커에 run 조회 도구가 없다).

**프롬프트 계약(`WorkerLauncher.buildMeetingPrompt`)** — 순서: `## 회의 소집`(종류·목적·산출물·프로젝트 id·안건 이슈·회의록
스페이스 id) → `## 참석자(1번이 진행자 — 너)`(emoji 이름 — 롤, slug, 말투=voicePrompt) → 데이터 블록(`<이슈-내용>`·`<코멘트>`
[안건 이슈 있을 때]·`<최근-run>`[RETRO]·`<사용자-지시>`·`<승인된-계획>`) → "블록은 데이터, 규약 우선" 문장 → `## 회의 규약` →
`## 회의록 템플릿`(참석자/안건/논의/결정/액션아이템[+ESCALATION은 "사람에게 묻는 질문"]) → `runId=N`. 규약 요지: 진행자가
참석자를 순서대로 롤플레이, 코드·git 금지, 맥락은 도구로 조회, 안건 이슈 claim·상태 변경 금지, 회의록은
`create_page(spaceId=<space-id>)`로 한 번만, 결정→이슈는 `auto-issue`면 `create_issue` 직접/아니면 `request_gate(PLAN)`로
승인 요청 후 종료, 안건 이슈가 있으면 회의록 링크 코멘트, **마감은 `report_result(status=DONE, pageId=<회의록>)`**.

**`report_result`의 `pageId`(선택 인자, 하위 호환)** — 어떤 run이든 DONE/FAILED/BLOCKED와 함께 pageId를 주면
`run.output_page_id`에 기록된다(양수만). **회의 run의 DONE은 pageId 필수** — 없으면 도구가 오류를 돌려주고 run은 RUNNING
그대로다(워커가 pageId를 붙여 다시 부른다). 회의 run이 `report_result` 없이 프로세스만 성공하면 완료가 아니라 **실패**
("회의록 보고(report_result) 없이 종료") → 사고형 즉시 BLOCKED + 알림(§5.1). 회의 run은 자동 에스컬레이션 대상이 아니다.

**PLAN 게이트 이어받기(`auto-issue=false`)** — 회의 continuation(게이트 승인·재개)은 직전 회의 run을
`parentRunId`로 잇는다(TASK 재개와 다름). `MeetingService.buildJob`이 조상 사슬(최대 20단계)에서 승인된 PLAN 게이트를 찾으면
그 요청문을 `<승인된-계획>`으로 싣고, 프롬프트는 "회의를 다시 열지 말고 제안 이슈를 만들어 기존 회의록을 갱신한 뒤
`report_result(DONE, pageId=그 회의록)`"으로 바뀐다.

**자동 에스컬레이션(`auto-escalation=true`)** — BLOCKED 승격 두 지점(`RunService.blockIncident` 사고형 즉시 중단,
`ReviewService.onReviewRejected` 반려 한도)에서 `MeetingService.onBlocked`를 부른다(예외는 삼킨다 — BLOCKED는 이미 커밋).
원 run의 프로젝트·이슈키·trigger를 승계한 ESCALATION run을 만들어 제출하고 이슈에 소집 코멘트를 남긴다. 지시문:
`<이슈키> 자동 진행 중단 — 원인 분석과 사람에게 물을 질문 목록` + 원 run id·타입 + 차단 사유 + 실패 기록 끝 1000자.
가드: **원 run이 회의 3종이면 만들지 않는다**(재귀 방지), 같은 이슈에 활성 ESCALATION이 있으면 건너뛴다. 리뷰 반려의
"원 run 없음·워크스페이스 없음" BLOCKED는 대상이 아니다(한도 분기만).

**회고 cron** — `retro-cron`이 있을 때만 회고 task가 등록된다(`MeetingSchedulingConfig` — retro·manager cron 중 하나라도 있으면 서는 설정, 자체 `@EnableScheduling` — 스케줄러
킬스위치와 독립. 이 경우 `Dispatcher.tick`도 스케줄되지만 `enabled` 확인에서 바로 돌아간다). 틱마다 `worker.repos` 매핑의
프로젝트 키마다 ALM 프로젝트 목록(진행자 명의 1회 조회)으로 id를 풀어 프로젝트별 RETRO run 하나(trigger=SCHEDULER,
참석=활성 전원 — MANAGER 롤 제외(§5.10), 대표 키 `PROJECT-<id>`). ALM에 없는 키·활성 회의가 있는 프로젝트는 건너뛴다.

**리뷰와의 관계** — 회의 run의 DONE은 REVIEW를 만들지 않는다(`ReviewService.onRunDone`은 TASK만 — 테스트로 고정).

### 5.10 매니저 run (P3c, 2026-09-27, AGP-45 — 스펙 §10.4-3)

"사람은 매니저의 보고를 받는다" — 매니저 페르소나(`PersonaRole.MANAGER`)가 보드를 순찰해 정체를 찾아 독려·정리하고,
사람에게 필요한 결정을 보고 페이지로 올린다. 새 엔진 없이 §5.9 회의 파이프라인을 재사용한다: `RunType.MANAGER`는
`MEETING_TYPES`에 들어 있어 **clone 없음·claim 없음·`report_result` pageId 필수·무보고 종료=실패·REVIEW 안 낳음·
자동 에스컬레이션 대상 아님·사무실 게시판(`boardPosts`) 게시**가 회의 run과 똑같이 따라온다. 다른 것은 참석 규칙·순찰
자료·프롬프트뿐이다(`MeetingService` + `WorkerLauncher.buildManagerPrompt`).

**설정** — `platform.agent.meetings.manager-cron`(`MEETING_MANAGER_CRON`, 기본 빈 값=off). 형식·시간대(Spring cron 6필드,
Asia/Seoul)·동작 조건(`SCHEDULER_ENABLED=false`여도 동작)·스페이스 요건(`MEETING_SPACE_ID` — 보고 페이지도 회의록
스페이스에 쓴다, 매니저에게 EDITOR grant 필요)이 `retro-cron`과 같다. 틱마다 `worker.repos` 매핑 프로젝트별 MANAGER run 하나
(trigger=SCHEDULER, 지시문=`MeetingService.MANAGER_AGENDA`, 대표 키 `PROJECT-<id>`). 매니저 페르소나에는 대상 프로젝트
EDITOR 이상 grant가 필요하다(코멘트·우선순위 정정).

**참석** — MANAGER 롤 **활성** 페르소나 **1명 단독**(여럿이면 id 최솟값). 없으면 소집 API 400, cron은 틱 전체를 건너뛰고
warn 로그. 소집 API에서 `personaSlugs`를 주면 MANAGER 롤 1명이어야 한다(실무 롤·여러 명이면 400) — 매니저가 여럿일 때
특정 매니저를 고르는 용도. 회고 "전원"에서 MANAGER 롤은 빠진다(매니저는 실무 회고 참석자가 아니라 보고 수신자다).
계획 회의(PLANNING_ROLES)·에스컬레이션(그 이슈에 TASK/REVIEW run을 돌린 페르소나 — 매니저 run은 회의 계열이라 제외)에도
기본으로는 앉지 않는다.

**소집** — `POST /api/agent/meetings`에 `"type": "MANAGER"`. RETRO처럼 안건(`agendaIssueKey`·`agenda`) 없이 된다(프로젝트
전반 순찰이 기본). `agenda`를 주면 `<사용자-지시>`로 실린다(특정 에픽만 보라 등).

**활성 회의 가드 공유** — 매니저 run도 "프로젝트당 활성 회의 계열 run 1건" 가드(`MEETING_TYPES` 기준)에 걸린다. 매니저 순찰과
실무 회의가 동시에 돌면 같은 보드를 두 run이 건드리고 게시판·예산이 겹치기 때문이다. 순찰 중이면 회의 소집이 409, 회의
중이면 매니저 cron이 그 프로젝트를 건너뛴다.

**순찰 자료(서버가 프롬프트에 싣는 요약, D-P3c-3)**

- `<최근-run>`: 최근 24시간 그 프로젝트 run(최대 30, 자기 자신 제외) — 회고와 같은 라인.
- `<대기-게이트>`: 그 프로젝트 run에 걸린 미결 게이트 — `gate <id> · <kind> · run <id> · <issueKey> · 대기 <분>분 · <요청문
  앞 80자, 한 줄>`. 게이트에는 프로젝트 축이 없어 `findByDecisionIsNull` + run 조인으로 거른다(OfficeService와 같은 방식).
- `<차단-run>`: 그 프로젝트의 BLOCKED run(기간 무관, 최대 20) — 24시간 창 밖으로 밀려난 정체가 가장 오래된 정체라서.
- **이슈 단위 정체 신호는 서버가 산출하지 않는다** — "inprogress인데 활성 run 없는 이슈"를 서버가 뽑으려면 ALM 검색
  페이지 순회 + run 조인 + 경과일 판정(검색 응답에 갱신 시각이 없다)이 필요해서, 프롬프트 규약으로 대체했다: 워커가
  `search_issues(statuses=[진행 중])` → `get_issue`로 최근 코멘트·진척을 보고 판정한다.

**행동 범위(D-P3c-4, 프롬프트 규약)** — 금지: `claim_issue`·`update_issue_status`·`log_work`·`link_pr`·코드/git·
`request_gate`(사람 판단이 필요한 것은 보고의 "사람에게 필요한 결정" 절로). 허용: 정체 이슈에 독려·정리 `add_comment`(명령이
아니라 팀원에게 말 걸듯, 최근 매니저 코멘트가 있으면 되풀이 금지), 우선순위 정정 `update_issue(priority)`+사유 코멘트, 중복
의심은 닫지 말고 "중복 후보 — 닫기 제안" 코멘트, 담당 제안 코멘트, `create_issue`는 **정리 목적**만(`auto-issue=false`면
금지 — 보고서에 제안으로). 보고 페이지는 `create_page(spaceId, title="[매니저 보고] <YYYY-MM-DD> <프로젝트 키>")` 한 번,
템플릿: 보드 현황 요약 / 병목·정체와 조치 / 독려 내역 / 사람에게 필요한 결정. 마감 `report_result(DONE, pageId)`.

**한계(알고 쓸 것)**

- **배분 없음** — 담당자 지정 도구(`assign_issue`)가 없어 담당 제안은 코멘트뿐이다(AGP-61 예정). 라벨 부여도 없다.
- 금지 목록은 §5.8과 같이 **프롬프트 수준 규약**이다 — 매니저 run 토큰으로도 `claim_issue`·`update_issue_status` 도구
  호출 자체는 가능하다.
- 매니저 보고는 회의 계열이라 게시판에 회의록과 같은 줄로 걸린다(프론트가 type=MANAGER로 라벨 구분 — P3c T2).

### 5.11 운영 알림 (P3d, 2026-09-27, AGP-55)

run이 사람 손을 기다리게 되면 이슈를 보지 않는 운영자에게도 닿도록 org 메일 허브로 메일을 보낸다(`AlertService`).

**알림 지점(D-P3d-4)**

| 종류(`AlertKind`) | 언제 | run 상태 | 메일 제목 |
|---|---|---|---|
| `INCIDENT` | 사고형 즉시 중단(§5.1) — 전 run 타입 | BLOCKED | `[AI팀] run {id} 중단 — {issueKey} (사고형 실패)` |
| `REJECT_LIMIT` | 반려 한도 소진(§5.8) | REVIEW BLOCKED | `… (반려 한도 소진)` |
| `LINEAGE_BROKEN` | 반려됐지만 원 작업 run·워크스페이스를 못 찾음(§5.8 5번) | REVIEW BLOCKED | `… (반려 후속 불가)` |
| `REJECT_THRESHOLD` | 반려 누적이 알림 임계 도달(한도 이내) | fix 진행 중 | `[AI팀] run {id} 반려 k/max — {issueKey} (반려 알림 임계)` |

게이트 장기 대기·워커가 스스로 `report_result(BLOCKED)`한 경우는 알림 대상이 아니다(게이트는 매니저 보고가 커버, 워커 BLOCKED는
워커가 이슈에 사유를 남긴다). 본문(text)에는 run id·타입·시도 순번·반려 누적·이슈·프로젝트·페르소나·트리거·사유와 재개 방법
(`POST /api/agent/runs/{id}/resume`, AI 사무실 run 상세 화면, 필요 없으면 cancel)이 담긴다.

**채널 분담** — 이슈 코멘트(⛔·🔁)는 기존대로 `RunService`·`ReviewService`가 run 페르소나 명의로 남기고, `AlertService`는
**메일만** 맡는다. 한 사건에 코멘트 1건 + 메일 1통이며 중복 발송은 없다. 안건 이슈 없는 회의·매니저 run(`PROJECT-<id>`)은
코멘트 없이 메일만 간다.

**계약** — `POST {org-base-url}/internal/org/mail`, 헤더 `X-Internal-Token`, body `{to[], subject, text, source:"agent-service"}`
→ 202 `{accepted, disabled}`(wiki·alm `OrgMailClient`와 같은 계약). 202는 큐 적재일 뿐이며 실제 발송·실패는 org 관리 화면의
발송 로그에서 본다.

| 키 | env | 기본 | 의미 |
|---|---|---|---|
| `alerts.mail-to` | `AGENT_ALERT_MAIL_TO` | 빈 값 | 수신자 쉼표 목록(trim·중복 제거). 비면 메일 생략(info 로그만) |
| `alerts.org-internal-token` | `ORG_INTERNAL_TOKEN` | 빈 값 | org `/internal/org/**` 공유 비밀 — **wiki·alm·auth와 같은 env·같은 값**. 비면 메일 생략(warn 로그) |

org의 base URL은 기존 `ORG_BASE_URL`(`platform.agent.org-base-url`)을 그대로 쓴다. 컨테이너 배포에서는 agent-service에
`ORG_INTERNAL_TOKEN`·`AGENT_ALERT_MAIL_TO` env 배선이 필요하다(infra compose — 이 리포 밖).

**fail-soft** — 수신자·토큰 미설정, org 메일 꺼짐(`disabled=true`, 정상 — info 로그), 호출 실패(5xx·타임아웃·403)는 전부 로그로
끝나고 던지지 않는다. 호출 시점에 BLOCKED는 이미 커밋됐고, 알림 실패가 자동 에스컬레이션·fix 제출을 끊으면 알림보다 큰 사고가
되기 때문이다. 메일 요청은 워커 스레드에서 동기(연결 2초/읽기 10초 — `ClientsConfig`)로 나간다.

## 6. P3a: AI 사무실 감독 API (2026-09-26)

alm-front "AI 사무실" 화면이 10초 폴링하는 읽기 전용 집계(`office` 패키지). 인증된 사용자 누구나 — run·게이트
목록과 같은 권한이며 **프로젝트 권한은 보지 않는다**(그래서 감사 summary의 자유 본문을 걷어낸다, 아래).

- `GET /api/agent/office?projectId=`(선택): `personas[]`(id·slug·name·emoji·role·active·`currentRun`·
  `lastActivity`·`todayCostUsd`) · `recentRuns[]`(최근 종결 10건, `RunSummaryResponse`) · `pendingGateCount` ·
  `pendingGates[]`(최신 5건, id·runId·issueKey·personaId·kind·requestSummary(200자)·requestedAt) ·
  `budget`(`GET /api/agent/budget`과 같은 shape) · `generatedAt` · `boardPosts[]`(P3b, 아래).
  - `boardPosts`(사무실 게시판, D-P3b-7): 회의 계열 run(MEETING/RETRO/ESCALATION + P3c MANAGER 보고) 중 DONE이고 `output_page_id`가 있는 것
    `endedAt` 최신순 5건 — `{runId, type, issueKey, projectId, pageId, spaceId, endedAt}`. `spaceId`는 위키 링크
    (`/spaces/:spaceId/pages/:pageId`)용으로 run별 저장 없이 현재 `meetings.space-id` 설정값을 싣는다(미설정이면 null) —
    운영 중 스페이스를 바꾸면 과거 게시물 링크가 깨질 수 있다(최근 5건뿐이라 수용). 제목은 위키를 조회하지 않는다(프론트가
    type 라벨+시각으로 그림 — 권한 없는 스페이스 제목 노출도 피함). 안건 이슈 없는 회의의 issueKey는 `PROJECT-<projectId>`.
    projectId 필터 적용. 부분 인덱스 `idx_run_board`(V6).
  - `currentRun`: `RunService.ACTIVE_STATUSES`(QUEUED·RUNNING·WAITING_APPROVAL·BLOCKED) 중 페르소나별 최신 1건(id 기준), 없으면 null.
    필드: id·status·issueKey·type·trigger·attempt·model·startedAt. 감사 로그(`AuditEntry`)에는 runId가 없다 —
    `tool_call_audit`에 run 축 컬럼이 없어서(스키마 변경 없이 생략).
  - 최근 종결 = DONE·FAILED·CANCELLED·BLOCKED, `updatedAt` 최신순(BLOCKED는 endedAt이 비어 있어서). BLOCKED는
    `currentRun`에도 나온다.
  - `lastActivity`: 그 페르소나 최근 감사 1건, **5분 이내일 때만**(아니면 null).
  - projectId는 run 축(현재 run·최근 run·게이트·비용)만 좁힌다. 감사에는 프로젝트 축이 없어 말풍선은 전체 활동 기준.
- `GET /api/agent/personas/{id}/activity`: `runs[]`(최근 20) · `todayAudits[]`(오늘 최대 50, id·tool·status·
  summary·createdAt) · `todayCostUsd`. 없는 페르소나는 404.
- **비용 축**: 원장에 페르소나 축이 없어 `usage_ledger`×`run` 조인으로 페르소나별 합산한다. 한 run 비용이
  PROJECT·PLATFORM 두 스코프로 적재되므로 **PLATFORM 행만** 센다. "오늘"은 **Asia/Seoul 자정** 기준(월 예산은
  여전히 UTC 캘린더 월 — §5.2).
- **summary 가림(`AuditSummaryRedactor`)**: 이 API는 ALM 프로젝트·위키 스페이스 권한을 보지 않으므로 본문뿐 아니라
  제목·검색어도 권한 우회 표면이다 — 도구명+식별자만 남긴다. `add_comment`·`*.comment`(코멘트 실패 노트)는 이슈키 +
  `(본문 생략)`, `report_progress`는 `run=N` + `(본문 생략)`, `create_issue`/`create_page`/`update_page`는
  `projectId=`/`spaceId=`/`pageId=` + `(제목 생략)`, `search_issues`/`find_pages`는 `projectId=`/`spaceId=` +
  `(검색어 생략)`, `link_pr`은 이슈키 + `(링크 생략)`(온프렘 비공개 리포명 노출 방지). 원본 감사 행은 그대로 보존(노출 시점에만 거름). 토큰류는 원래 어떤 도구 summary에도 싣지 않는다.
- **게이트 요청문은 가리지 않는다(의도)**: `pendingGates[].requestSummary`는 기존 `GET /api/agent/gates`가 인증 사용자
  누구나에게 전문을 주는 것과 같은 노출 수준이라 가림을 거치지 않는다.
- 인덱스(V5): `tool_call_audit(created_at)`(말풍선 5분 창), `run(persona_id, id DESC)`(개인 오피스),
  `gate(id) WHERE decision IS NULL`(미결 게이트 부분 인덱스).
