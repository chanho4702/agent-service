package com.platform.agentservice.worker;

import java.util.List;

/**
 * {@link WorkerLauncher#launch}에 넘기는 이슈 컨텍스트 — 호출자(T4 디스패처)가 조립한다.
 * {@code repoUrl}은 이슈의 프로젝트/라벨을 {@code platform.agent.worker.repos} 매핑으로
 * 해석한 결과다(해석 로직 자체는 이 타입의 책임이 아니다). 이슈 키는 {@code Run.issueKey}에
 * 이미 있으므로 여기 중복해 담지 않는다.
 *
 * <p>{@code recentComments}는 사람이 이슈에 남긴 최근 코멘트 원문 목록이다 — 사람 코멘트는
 * 곧 지시이므로(스펙 §10.4-1) 워커 프롬프트에 반드시 포함된다.
 *
 * <p>{@code instruction}은 USER run의 사람 직접 지시문이다(AGP-42, D-P2c-6). 자동화 run에는 없다({@code null}).
 *
 * <p>{@code meeting}은 회의 run(P3b)에만 있다 — 회의 run은 clone하지 않으므로 {@code repoUrl}이 {@code null}이다.
 */
public record WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments,
                        String instruction, MeetingContext meeting) {

    /** 지시문이 없는 자동화 run용. */
    public WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments) {
        this(repoUrl, issueTitle, issueBody, recentComments, null, null);
    }

    public WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments,
                     String instruction) {
        this(repoUrl, issueTitle, issueBody, recentComments, instruction, null);
    }

    /**
     * 회의 프롬프트 재료(D-P3b-5). {@code recentRuns}는 회고 자료(RETRO만, 그 외 빈 목록), {@code approvedPlan}은
     * PLAN 게이트 승인 뒤 이어받은 run에서만 채워진다(승인된 요청문 — 있으면 회의를 다시 열지 않는다).
     */
    public record MeetingContext(long projectId, long spaceId, boolean autoIssue, List<Attendee> attendees,
                                 List<String> recentRuns, String approvedPlan) {
    }

    /** 참석 페르소나 — 첫 번째가 진행자. {@code voice}는 페르소나 말투(voicePrompt, 없으면 null). */
    public record Attendee(String slug, String name, String role, String emoji, String voice) {
    }
}
