package com.platform.agentservice.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 운영 알림 설정(P3d, D-P3d-3) — {@code platform.agent.alerts.*}.
 *
 * <p>{@link #mailTo()}는 쉼표 목록이며 비면 메일을 보내지 않는다(로그만). {@link #orgInternalToken()}은 org 메일 허브
 * {@code /internal/org/**}의 공유 비밀로, wiki·alm과 같은 env({@code ORG_INTERNAL_TOKEN})를 쓴다 — 서비스마다 다른 이름을
 * 붙이면 설치 문서가 같은 값을 두 번 넣으라고 말해야 한다.
 */
@ConfigurationProperties(prefix = "platform.agent.alerts")
public record AlertProperties(String mailTo, String orgInternalToken) {

    public List<String> recipients() {
        if (mailTo == null || mailTo.isBlank()) {
            return List.of();
        }
        return Arrays.stream(mailTo.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public String token() {
        return orgInternalToken == null ? "" : orgInternalToken.trim();
    }
}
