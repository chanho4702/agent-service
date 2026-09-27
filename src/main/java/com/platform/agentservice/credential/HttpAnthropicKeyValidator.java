package com.platform.agentservice.credential;

import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@code GET /v1/models?limit=1}로 키를 확인한다 — 토큰을 쓰지 않는 호출이라 검증 자체에 과금이 없다(messages 최소 호출보다 싸다).
 * 401·403은 키 거부, 429는 인증은 통과한 것이라 유효로 본다(검증 때문에 저장을 막을 이유가 아니다), 5xx·연결 실패는 판정 불가.
 */
@Slf4j
@Component
public class HttpAnthropicKeyValidator implements AnthropicKeyValidator {

    static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient client;

    public HttpAnthropicKeyValidator(RestClient.Builder builder, CredentialProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.clone().baseUrl(properties.anthropicApiUrl()).requestFactory(factory).build();
    }

    @Override
    public void validate(String apiKey) {
        int status;
        try {
            status = client.get()
                    .uri("/v1/models?limit=1")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RestClientException e) {
            // 예외 메시지에 요청 헤더가 실릴 일은 없지만, 사용자 응답은 고정 문구로 둔다.
            log.warn("Anthropic 키 검증 호출 실패(연결 불가): {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException(UNREACHABLE_MESSAGE);
        }
        if ((status >= 200 && status < 300) || status == 429) {
            return;
        }
        if (status >= 500) {
            log.warn("Anthropic 키 검증 판정 불가: HTTP {}", status);
            throw new ServiceUnavailableException(UNREACHABLE_MESSAGE);
        }
        log.info("Anthropic 키 검증 거부: HTTP {}", status);
        throw new IllegalArgumentException(INVALID_MESSAGE);
    }
}
