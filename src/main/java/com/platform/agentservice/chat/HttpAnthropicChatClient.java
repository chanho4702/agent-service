package com.platform.agentservice.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.credential.CredentialProperties;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST {AGENT_ANTHROPIC_API_URL}/v1/messages}를 직접 부른다. Spring AI 자동구성(고정 키 하나)을 쓰지 않는 이유: 요청마다
 * 해석된 키가 다르다(프로젝트 층·전역 층 — D-P3h-3). 키 검증({@code HttpAnthropicKeyValidator})과 같은 HTTP 계층·URL 설정을 쓴다.
 *
 * <p>로그·예외에는 HTTP 상태와 예외 종류만 남긴다 — 요청(키·시스템 프롬프트·대화 본문)과 응답 본문은 싣지 않는다.
 */
@Slf4j
@Component
public class HttpAnthropicChatClient implements AnthropicChatClient {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    static final String UNAVAILABLE = "지금은 대화할 수 없습니다 — AI 응답을 받지 못했습니다. 잠시 후 다시 시도하세요";
    static final String KEY_REJECTED = "지금은 대화할 수 없습니다 — LLM 키가 거부됐습니다. 관리자에게 키 설정을 확인해 달라고 하세요";

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpAnthropicChatClient(RestClient.Builder builder, CredentialProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.clone().baseUrl(properties.anthropicApiUrl()).requestFactory(factory).build();
    }

    @Override
    public Completion complete(String apiKey, String model, int maxTokens, String system, List<Message> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("수다 요청 직렬화 실패", e);
        }

        Response response;
        try {
            response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(), read(res.getBody())));
        } catch (RestClientException e) {
            log.warn("Anthropic 수다 호출 실패(연결 불가): {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        if (response.status == 401 || response.status == 403) {
            log.warn("Anthropic 수다 호출 — 키 거부: HTTP {}", response.status);
            throw new ServiceUnavailableException(KEY_REJECTED);
        }
        if (response.status < 200 || response.status >= 300) {
            log.warn("Anthropic 수다 호출 실패: HTTP {}", response.status);
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        return parse(response.body);
    }

    private Completion parse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            JsonNode usage = root.path("usage");
            return new Completion(text.toString(), usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
        } catch (IOException e) {
            log.warn("Anthropic 수다 응답 해석 실패: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
    }

    private static String read(java.io.InputStream in) throws IOException {
        return in == null ? "" : StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }

    private record Response(int status, String body) {
    }
}
