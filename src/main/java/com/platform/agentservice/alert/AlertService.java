package com.platform.agentservice.alert;

import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.run.Run;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 운영 알림(P3d, D-P3d-3·4) — run이 사람 손을 기다리게 됐을 때 org 메일 허브로 메일을 넘긴다.
 *
 * <p><b>채널 분담</b>: 이슈 코멘트(⛔·🔁)는 이 서비스가 아니라 기존 호출부({@code RunService}·{@code ReviewService})가 그대로
 * 남긴다 — 코멘트는 run 페르소나 명의로 이슈에 붙는 작업 기록이고 호출부마다 명의·문구 맥락이 다르다. 이 서비스는 이슈를
 * 보지 않는 사람(운영자)에게 닿는 메일만 맡는다. 그래서 한 사건에 코멘트 1건 + 메일 1통이 나가고 중복이 없다.
 *
 * <p><b>fail-soft</b>: 모든 메서드는 던지지 않는다. 호출 시점에 run 상태는 이미 커밋됐고, 알림 실패로 run 처리 흐름(에스컬레이션
 * 훅·fix 제출)이 끊기면 알림보다 큰 사고가 된다. 수신자·토큰 미설정, org 메일 꺼짐, 호출 실패는 전부 로그로 끝난다.
 */
@Slf4j
@Service
public class AlertService {

    static final String SOURCE = "agent-service";
    private static final int REASON_SUMMARY_MAX = 120;

    private final OrgClient orgClient;
    private final AlertProperties properties;

    public AlertService(OrgClient orgClient, AlertProperties properties) {
        this.orgClient = orgClient;
        this.properties = properties;
    }

    /** run이 BLOCKED로 멈췄다(사고형·계보 결함·반려 한도). */
    public void notifyBlocked(Run run, String reason, AlertKind kind) {
        try {
            sendBlocked(run, reason, kind);
        } catch (Exception e) {
            log.warn("run={} 알림 메일 조립 실패(run 처리는 계속됩니다): {}", idOf(run), e.getMessage());
        }
    }

    /** 반려 누적이 알림 임계에 닿았다 — 한도 안이라 fix run은 이미 떠 있다. */
    public void notifyRejectThreshold(Run reviewRun, int rejectCount, int rejectMax, Long fixRunId) {
        try {
            sendRejectThreshold(reviewRun, rejectCount, rejectMax, fixRunId);
        } catch (Exception e) {
            log.warn("run={} 반려 임계 알림 메일 조립 실패(run 처리는 계속됩니다): {}", idOf(reviewRun), e.getMessage());
        }
    }

    // ---- 내부 ----

    private void sendBlocked(Run run, String reason, AlertKind kind) {
        String subject = "[AI팀] run " + run.getId() + " 중단 — " + run.getIssueKey() + " (" + kind.label + ")";
        String body = header(run, kind)
                + "사유: " + nullToDash(reason) + "\n\n"
                + "재개 방법:\n"
                + "  - 원인을 확인·조치한 뒤 POST /api/agent/runs/" + run.getId() + "/resume (ADMIN) — 같은 계보로 이어서 실행\n"
                + "  - 또는 ALM AI 사무실의 run 상세 화면에서 재개\n"
                + "  - 이어갈 필요가 없으면 POST /api/agent/runs/" + run.getId() + "/cancel\n";
        send(run, subject, body);
    }

    private void sendRejectThreshold(Run reviewRun, int rejectCount, int rejectMax, Long fixRunId) {
        String subject = "[AI팀] run " + reviewRun.getId() + " 반려 " + rejectCount + "/" + rejectMax + " — "
                + reviewRun.getIssueKey() + " (" + AlertKind.REJECT_THRESHOLD.label + ")";
        String body = header(reviewRun, AlertKind.REJECT_THRESHOLD)
                + "반려 누적: " + rejectCount + "회 (자동 수정 한도 " + rejectMax + "회)\n"
                + "진행: 수정 run " + nullToDash(fixRunId) + "이 리뷰 지적을 반영 중입니다. 한도를 넘는 다음 반려는 사람 확인(BLOCKED)으로 넘어갑니다.\n\n"
                + "개입하려면: 수정 run을 POST /api/agent/runs/{id}/cancel로 멈추거나, 이슈 코멘트로 방향을 지시하세요.\n";
        send(reviewRun, subject, body);
    }

    private static Object idOf(Run run) {
        return run == null ? null : run.getId();
    }

    private String header(Run run, AlertKind kind) {
        return (kind.stopped ? "AI팀 run이 멈춰 사람 확인을 기다립니다.\n\n" : "AI팀 run의 리뷰 반려가 알림 임계에 닿았습니다.\n\n")
                + "run: " + run.getId() + " (" + run.getType() + ", 시도 순번 " + run.getAttempt() + ", 반려 누적 " + run.getRejectCount() + ")\n"
                + "이슈: " + run.getIssueKey() + "\n"
                + "프로젝트 id: " + run.getProjectId() + "\n"
                + "페르소나 id: " + run.getPersonaId() + "\n"
                + "트리거: " + run.getTrigger() + "\n"
                + "알림 종류: " + kind.label + "\n";
    }

    private void send(Run run, String subject, String body) {
        try {
            List<String> to = properties.recipients();
            if (to.isEmpty()) {
                log.info("run={} 알림 메일 생략 — 수신자 미설정(platform.agent.alerts.mail-to): {}", run.getId(), subject);
                return;
            }
            String token = properties.token();
            if (token.isEmpty()) {
                // 토큰 없이 부르면 org가 어차피 403이다 — 왕복을 버리지 않고, 설정 누락이 눈에 띄게 warn으로 남긴다.
                log.warn("run={} 알림 메일 생략 — org 내부 토큰 미설정(ORG_INTERNAL_TOKEN): {}", run.getId(), subject);
                return;
            }
            OrgClient.InternalMailResponse response =
                    orgClient.enqueueInternalMail(to, truncate(subject, 500), body, SOURCE, token);
            if (response.disabled()) {
                log.info("run={} 알림 메일 미발송 — org 메일 허브가 꺼져 있습니다: {}", run.getId(), subject);
            } else {
                log.info("run={} 알림 메일 큐 적재: to={} subject={}", run.getId(), to, subject);
            }
        } catch (Exception e) {
            log.warn("run={} 알림 메일 요청 실패(run 처리는 계속됩니다): {} — {}", run.getId(), subject, e.getMessage());
        }
    }

    /** 차단 사유·코멘트에 넣을 원인 요지 — 한 줄로 접고 자른다(스택 트레이스·워커 출력 꼬리가 통째로 올 수 있다). */
    public static String summarize(String reason) {
        if (reason == null || reason.isBlank()) {
            return "원인 미상";
        }
        String oneLine = reason.replaceAll("\\s+", " ").trim();
        return truncate(oneLine, REASON_SUMMARY_MAX);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
