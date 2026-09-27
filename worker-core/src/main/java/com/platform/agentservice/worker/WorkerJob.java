package com.platform.agentservice.worker;

import com.platform.agentservice.persona.PersonaSkills;

import java.util.List;

/**
 * {@code WorkerLauncher#launch}에 넘기는 이슈 컨텍스트 — 호출자(T4 디스패처)가 조립한다.
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
                        String instruction, MeetingContext meeting, Expertise expertise) {

    public WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments,
                     String instruction, MeetingContext meeting) {
        this(repoUrl, issueTitle, issueBody, recentComments, instruction, meeting, null);
    }

    /** 지시문이 없는 자동화 run용. */
    public WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments) {
        this(repoUrl, issueTitle, issueBody, recentComments, null, null);
    }

    public WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments,
                     String instruction) {
        this(repoUrl, issueTitle, issueBody, recentComments, instruction, null);
    }

    /**
     * 회의 프롬프트 재료(D-P3b-5). {@code recentRuns}는 회고·매니저 순찰 자료(RETRO·MANAGER만, 그 외 빈 목록),
     * {@code approvedPlan}은 PLAN 게이트 승인 뒤 이어받은 run에서만 채워진다(승인된 요청문 — 있으면 회의를 다시 열지 않는다).
     * {@code pendingGates}·{@code blockedRuns}는 매니저 순찰 자료(P3c, D-P3c-3 — MANAGER만) — 워커에는 게이트·run을
     * 조회하는 도구가 없어서 서버가 요약 라인을 실어 준다.
     */
    public record MeetingContext(long projectId, long spaceId, boolean autoIssue, List<Attendee> attendees,
                                 List<String> recentRuns, String approvedPlan, List<String> pendingGates,
                                 List<String> blockedRuns) {

        /** 매니저 순찰 자료가 없는 회의 3종용. */
        public MeetingContext(long projectId, long spaceId, boolean autoIssue, List<Attendee> attendees,
                              List<String> recentRuns, String approvedPlan) {
            this(projectId, spaceId, autoIssue, attendees, recentRuns, approvedPlan, List.of(), List.of());
        }
    }

    public WorkerJob withExpertise(Expertise expertise) {
        return new WorkerJob(repoUrl, issueTitle, issueBody, recentComments, instruction, meeting, expertise);
    }

    /**
     * 참석 페르소나 — 첫 번째가 진행자. {@code voice}는 페르소나 말투(voicePrompt, 없으면 null), {@code skillSummary}는 스킬 첫 줄
     * 요약(AGP-62, {@code PersonaSkills.summary} — 없으면 null).
     */
    public record Attendee(String slug, String name, String role, String emoji, String voice, String skillSummary) {

        public Attendee(String slug, String name, String role, String emoji, String voice) {
            this(slug, name, role, emoji, voice, null);
        }
    }

    /**
     * run 소유 페르소나의 스킬(AGP-62) — {@link HarnessMaterializer}가 워크스페이스 {@code .claude/skills/persona-<slug>/SKILL.md}로
     * 실체화하고 프롬프트가 그 경로를 가리킨다. 스킬이 없는 페르소나는 {@code null}({@link #of}).
     */
    public record Expertise(String slug, String name, String role, String skills) {

        public static Expertise of(String slug, String name, String role, String skills) {
            return PersonaSkills.hasSkills(skills) ? new Expertise(slug, name, role, skills) : null;
        }

        /** 워크스페이스 기준 스킬 디렉터리({@code .claude/skills/persona-<slug>}), slug가 경로로 안전하지 않으면 null. */
        public String skillDir() {
            String dir = PersonaSkills.dirName(slug);
            return dir == null ? null : ".claude/skills/" + dir;
        }
    }
}
