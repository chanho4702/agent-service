# 플랫폼 러너 이미지(AGP-69 T3 — CLAUDE.md §12·§13). 서버 사이트 run을 agent-service 컨테이너 밖에서 돌리는 비밀 없는 실행기다.
# 빌드 컨텍스트는 리포 루트, jar는 CI(또는 로컬)의 `gradlew build` 산출물(runner/build/libs/agent-runner.jar)을 복사한다:
#   docker build -f runner/Dockerfile -t agent-runner .
#
# 입력은 RUNNER_SERVER(nginx 경유 플랫폼 주소)·RUNNER_TOKEN(PLATFORM 러너 토큰 = agent-service의 AGENT_PLATFORM_RUNNER_TOKEN과 같은 값) 둘뿐이다.
# 이미지에는 어떤 플랫폼 비밀도 없다 — LLM 키는 claim마다 서버가 주고 워커 프로세스 env에만 들어간다. 하네스는 서버 harness 엔드포인트에서
# 받으므로 마운트하지 않는다. 구독 세션(~/.claude) 마운트 금지(§12).

# claude CLI를 받는 단계 — 런타임에는 node 바이너리와 전역 node_modules만 옮긴다(npm 캐시·빌드 잔여물 제외).
FROM node:24-bookworm-slim AS node
# 버전 고정: latest를 받으면 같은 커밋의 이미지가 빌드 시점마다 다른 워커 CLI를 싣는다(출력 JSON shape가 바뀌면 파싱이 조용히 깨진다).
# 갱신 절차는 CLAUDE.md §12 "CLI 버전 고정·갱신 절차".
ARG CLAUDE_CODE_VERSION=2.1.283
# npm 레지스트리 일시 오류로 CI가 떨어지지 않게 fetch 재시도를 넓힌다.
RUN npm config set fetch-retries 5 \
 && npm config set fetch-retry-mintimeout 20000 \
 && npm config set fetch-retry-maxtimeout 120000 \
 && npm install -g --no-fund --no-audit "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force \
 && claude --version

# JDK인 이유: 워커가 대상 리포에서 `./gradlew build`(javac)를 돈다 — JRE면 Java 리포 작업·테스트가 전부 불가능하다. 러너 jar도 이 JDK로 돈다.
FROM eclipse-temurin:24-jdk
# tini: 러너(PID 1이 아니게)가 SIGTERM을 받아 종료 유예를 돌고, 워커가 남긴 고아 프로세스(gradle·node)를 거둔다.
RUN apt-get -o Acquire::Retries=5 update \
 && apt-get -o Acquire::Retries=5 install -y --no-install-recommends git ca-certificates tini \
 && rm -rf /var/lib/apt/lists/*

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && ln -s ../lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack \
 && ln -s ../lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe /usr/local/bin/claude \
 && corepack enable pnpm \
 && node --version && git --version && claude --version

# 워커 커밋 신원 기본값 — 컨테이너에는 ~/.gitconfig가 없어 커밋이 "Please tell me who you are"로 실패한다.
# system 층이라 env 커튼(HOME만 통과)과 무관하게 읽히고, 대상 리포·워크스페이스 설정이 있으면 그쪽이 이긴다.
RUN git config --system user.name "agent-service worker" \
 && git config --system user.email "agent-worker@platform.local"

# non-root: 워커는 이슈 내용(외부 입력)으로 프롬프트가 구성되는 LLM이 임의 명령을 도는 프로세스다. 전역 node_modules는 root 소유라
# 워커가 CLI 자체를 바꾸지 못한다. /work는 compose 명명 볼륨 자리(워크스페이스·하네스 캐시·미전송 결과) — 빈 볼륨의 첫 마운트 때 이
# 디렉터리의 소유자가 볼륨에 복사된다. REVIEW·반려-fix가 같은 러너의 워크스페이스를 이어 쓰므로 재생성에도 남아야 한다.
RUN groupadd --system --gid 10001 agent \
 && useradd --system --uid 10001 --gid agent --create-home --home-dir /home/agent --shell /bin/bash agent \
 && mkdir -p /work \
 && chown agent:agent /work

WORKDIR /app
COPY runner/build/libs/agent-runner.jar agent-runner.jar
USER agent
# RUNNER_LOG_FORMAT=json: 플랫폼 로그 규약(stdout JSON → Alloy → Loki).
# DISABLE_AUTOUPDATER: CLI 자동 갱신이 고정 버전을 흔들지 않게 — env 커튼이 워커에 넘기려면 RUNNER_EXTRA_ENV_KEYS에 이 이름이 있어야 한다.
ENV HOME=/home/agent \
    RUNNER_WORK_DIR=/work \
    RUNNER_LOG_FORMAT=json \
    CLAUDE_BIN=/usr/local/bin/claude \
    DISABLE_AUTOUPDATER=1 \
    RUNNER_EXTRA_ENV_KEYS=DISABLE_AUTOUPDATER
VOLUME ["/work"]
# 러너는 포트를 열지 않는다 — 마지막 heartbeat 성공 시각 파일(runner.alive)이 3분 안이면 healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD find /work/runner.alive -mmin -3 | grep -q . || exit 1
ENTRYPOINT ["tini", "--", "java", "-XX:MaxRAMPercentage=25", "-jar", "/app/agent-runner.jar"]
