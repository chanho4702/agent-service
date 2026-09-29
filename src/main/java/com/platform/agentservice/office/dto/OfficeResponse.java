package com.platform.agentservice.office.dto;

import com.platform.agentservice.budget.dto.BudgetStatusResponse;
import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.GateKind;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.run.dto.RunSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** {@code GET /api/agent/office} — AI 사무실 한 화면 분량을 한 번의 폴링으로 내려준다(P3a D-P3a-1). */
public record OfficeResponse(
        List<OfficePersona> personas,
        List<RunSummaryResponse> recentRuns,
        long pendingGateCount,
        List<PendingGate> pendingGates,
        BudgetStatusResponse budget,
        Instant generatedAt,
        List<BoardPost> boardPosts,
        ActiveMeeting activeMeeting,
        Features features,
        boolean reviewReady
) {

    /**
     * {@code reviewReady}(P4b D-P4b-1): TASK가 DONE이면 검증 run을 띄울 리뷰어가 있다 — 리뷰가 꺼져 있으면(작업자가 직접 done) true,
     * 아니면 유효 리뷰어가 NONE이 아닐 때(projectId가 있으면 그 프로젝트 기준, 없으면 전역 기준). false면 화면이 "리뷰어 없음 — done
     * 불가" 경고를 띄운다. 이 생성자는 플래그 없는 기존 호출부·테스트용(true).
     */
    public OfficeResponse(List<OfficePersona> personas, List<RunSummaryResponse> recentRuns, long pendingGateCount,
                          List<PendingGate> pendingGates, BudgetStatusResponse budget, Instant generatedAt,
                          List<BoardPost> boardPosts, ActiveMeeting activeMeeting, Features features) {
        this(personas, recentRuns, pendingGateCount, pendingGates, budget, generatedAt, boardPosts, activeMeeting, features,
                true);
    }

    /**
     * 기능 플래그(P3g ①). {@code chat} = 사무실 수다가 동작 가능(설치 옵션 {@code CHAT_ENABLED} on + LLM 키가 해석됨 — projectId가
     * 있으면 프로젝트 층부터, 없으면 전역·env). 프론트는 필드가 없으면 false로 본다(구 백엔드 호환).
     */
    public record Features(boolean chat) {
    }

    /**
     * 회의실 연출(P3e) — 회의 계열(MANAGER 포함) RUNNING run 중 최신 1건, 없으면 null. QUEUED·WAITING_APPROVAL·BLOCKED는
     * 회의실에 앉아 있는 상태가 아니라서 뺀다. {@code hostPersonaId}는 run 소유 페르소나(진행자)로
     * {@code attendeePersonaIds[0]}과 같지만 순서 의존 없이 쓰도록 명시 필드로 둔다. 참석자는 저장 순서 그대로(진행자 먼저).
     */
    public record ActiveMeeting(
            long runId,
            RunType type,
            RunStatus status,
            String issueKey,
            long projectId,
            long hostPersonaId,
            List<Long> attendeePersonaIds,
            Instant startedAt
    ) {
    }

    /**
     * 사무실 게시판(P3b D-P3b-7) — 회의록이 보고된 완료 회의 run. 제목은 위키를 조회하지 않고 프론트가 type 라벨 + 시각으로
     * 그린다(권한 없는 스페이스 제목 노출도 피한다). issueKey는 안건 이슈 없는 회의면 {@code PROJECT-<projectId>}다.
     *
     * <p>{@code spaceId}는 위키 링크({@code /spaces/:spaceId/pages/:pageId})용이며, run별로 저장하지 않고 조회 시점의
     * {@code platform.agent.meetings.space-id} 설정값을 쓴다 — 회의록은 전부 그 스페이스에 쓰이기 때문이다. 한계: 운영 중
     * 스페이스 설정을 바꾸면 이전 스페이스에 쓰인 과거 게시물의 링크가 깨질 수 있다(게시물은 최근 5건뿐이라 수용).
     * 설정이 비어 있으면 null이다.
     */
    public record BoardPost(
            long runId,
            RunType type,
            String issueKey,
            long projectId,
            long pageId,
            Long spaceId,
            Instant endedAt
    ) {
    }

    /**
     * currentRun·lastActivity는 없으면 null — 유휴 페르소나, 5분 넘게 조용한 페르소나. {@code avatarConfig}(AGP-62)는 저장된 JSON 문자열,
     * 미설정이면 null. 스킬·기본 모델은 싣지 않는다 — 사무실은 관리 권한을 보지 않는 표면이다. {@code presence}(AGP-63)는
     * currentRun이 없고 최근 5분 안에 EXTERNAL 감사가 있으면 {@code EXTERNAL}, 그 밖 null.
     */
    public record OfficePersona(
            long id,
            String slug,
            String name,
            String emoji,
            PersonaRole role,
            boolean active,
            CurrentRun currentRun,
            AuditEntry lastActivity,
            BigDecimal todayCostUsd,
            String avatarConfig,
            PersonaPresence presence
    ) {
        /** presence가 없는 항목(기존 호출부·테스트). */
        public OfficePersona(long id, String slug, String name, String emoji, PersonaRole role, boolean active,
                             CurrentRun currentRun, AuditEntry lastActivity, BigDecimal todayCostUsd, String avatarConfig) {
            this(id, slug, name, emoji, role, active, currentRun, lastActivity, todayCostUsd, avatarConfig, null);
        }

        /** 아바타 설정이 없는 항목(기존 호출부·테스트). */
        public OfficePersona(long id, String slug, String name, String emoji, PersonaRole role, boolean active,
                             CurrentRun currentRun, AuditEntry lastActivity, BigDecimal todayCostUsd) {
            this(id, slug, name, emoji, role, active, currentRun, lastActivity, todayCostUsd, null, null);
        }
    }

    /**
     * {@code executionSite}(P4a D-P4-4) — LOCAL이면 책상 모니터에 "집" 표지. {@code awaitingRunner}는 QUEUED인데 지금 집어갈 러너가
     * 없음("러너 대기" 라벨) — LOCAL은 범위 안 LOCAL 러너(고정 run은 그 러너)가 전부 오프라인, SERVER는 인프로세스 실행이 꺼져 있고
     * PLATFORM 러너가 오프라인일 때.
     */
    public record CurrentRun(
            long id,
            RunStatus status,
            String issueKey,
            RunType type,
            RunTrigger trigger,
            int attempt,
            String model,
            Instant startedAt,
            ExecutionSite executionSite,
            boolean awaitingRunner
    ) {
        /** 실행 위치 필드 없는 생성(기존 테스트) — SERVER, 대기 아님. */
        public CurrentRun(long id, RunStatus status, String issueKey, RunType type, RunTrigger trigger, int attempt,
                          String model, Instant startedAt) {
            this(id, status, issueKey, type, trigger, attempt, model, startedAt, ExecutionSite.SERVER, false);
        }
    }

    /**
     * requestSummary는 게이트 요청문 앞부분만 — 전문은 {@code GET /api/agent/gates}에 있다. 감사 summary와 달리
     * {@code AuditSummaryRedactor}를 거치지 않는 것은 의도다: 같은 요청문을 인증 사용자 누구나 이미
     * {@code GET /api/agent/gates}로 전문 조회할 수 있어, 여기서 가려도 노출 수준이 달라지지 않는다.
     */
    public record PendingGate(
            long id,
            long runId,
            String issueKey,
            long personaId,
            GateKind kind,
            String requestSummary,
            Instant requestedAt
    ) {
    }
}
