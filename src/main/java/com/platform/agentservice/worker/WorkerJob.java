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
 */
public record WorkerJob(String repoUrl, String issueTitle, String issueBody, List<String> recentComments) {
}
