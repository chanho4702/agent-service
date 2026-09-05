package com.platform.agentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 다운스트림 REST 클라이언트 4종. base-url은 {@code platform.agent.*}(dev 프로필에서
 * +10000 오버라이드, docker 프로필에서 서비스 DNS)에서 온다. 빈 이름이 곧 {@code @Qualifier}
 * 값이다 — alm/wiki는 이 태스크에서 아직 쓰이지 않지만(후속 태스크가 소비) base-url 설정
 * 방식을 한 곳에 모아 두려고 여기서 함께 정의한다.
 *
 * <p>네 빈 모두 타임아웃을 명시한다(연결 2초/읽기 10초) — 기본 {@code RestClient.Builder}는
 * 무한 대기라 다운스트림(auth/org/alm/wiki) 중 하나가 멈추면 이 서비스의 MCP 도구 호출
 * 스레드가 함께 걸린다. {@code spring.http.client(s).*} yml 프로퍼티로는 이 경로에 타임아웃이
 * 붙지 않는 사례가 있어(auth-server {@code SecurityConfig.tokenResponseClient()} 실측 —
 * Boot의 RestClient.Builder 자동구성을 거치지 않고 직접 만드는 클라이언트는 yml 설정이 아예
 * 안 먹는다) 이 서비스도 같은 패턴으로 spring-web 내장 {@link JdkClientHttpRequestFactory}를
 * 직접 구성해 명시적으로 타임아웃 있는 요청 팩토리를 붙인다.
 */
@Configuration
public class ClientsConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient authRestClient(RestClient.Builder builder,
                               @Value("${platform.agent.auth-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(timeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT)).build();
    }

    @Bean
    RestClient orgRestClient(RestClient.Builder builder,
                              @Value("${platform.agent.org-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(timeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT)).build();
    }

    @Bean
    RestClient almRestClient(RestClient.Builder builder,
                              @Value("${platform.agent.alm-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(timeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT)).build();
    }

    @Bean
    RestClient wikiRestClient(RestClient.Builder builder,
                               @Value("${platform.agent.wiki-base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(timeoutRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT)).build();
    }

    /** 패키지 전용 — 테스트가 실제 타임아웃 강제 동작을 검증할 수 있도록 노출한다. */
    static JdkClientHttpRequestFactory timeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }
}
