package com.platform.agentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 다운스트림 REST 클라이언트 4종. base-url은 {@code platform.agent.*}(dev 프로필에서
 * +10000 오버라이드, docker 프로필에서 서비스 DNS)에서 온다. 빈 이름이 곧 {@code @Qualifier}
 * 값이다 — alm/wiki는 이 태스크에서 아직 쓰이지 않지만(후속 태스크가 소비) base-url 설정
 * 방식을 한 곳에 모아 두려고 여기서 함께 정의한다.
 */
@Configuration
public class ClientsConfig {

    @Bean
    RestClient authRestClient(RestClient.Builder builder,
                               @Value("${platform.agent.auth-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    RestClient orgRestClient(RestClient.Builder builder,
                              @Value("${platform.agent.org-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    RestClient almRestClient(RestClient.Builder builder,
                              @Value("${platform.agent.alm-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    RestClient wikiRestClient(RestClient.Builder builder,
                               @Value("${platform.agent.wiki-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
