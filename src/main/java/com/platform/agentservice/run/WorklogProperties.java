package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * run 종결 워크로그(P4b D-P4b-2, AGP-60) — {@code platform.agent.worklog.*}. {@link #auto()} 기본 true: 작업 시간 기록을 워커
 * 프롬프트 의무에 맡기지 않고 서버가 남긴다({@link RunWorklogService}).
 */
@ConfigurationProperties(prefix = "platform.agent.worklog")
public record WorklogProperties(boolean auto) {

    @ConstructorBinding
    public WorklogProperties(@DefaultValue("true") boolean auto) {
        this.auto = auto;
    }
}
