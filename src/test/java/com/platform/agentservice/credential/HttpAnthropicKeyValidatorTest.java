package com.platform.agentservice.credential;

import com.platform.common.error.ServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 검증 호출(GET /v1/models) — 성공·거부(400)·판정 불가(503). 실제 Anthropic 대신 로컬 HTTP 서버. */
class HttpAnthropicKeyValidatorTest {

    static final String KEY = "sk-ant-api03-validate-me-0042";

    HttpServer server;
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> seenKey = new AtomicReference<>();
    final AtomicReference<String> seenVersion = new AtomicReference<>();
    final AtomicReference<String> seenPath = new AtomicReference<>();

    HttpAnthropicKeyValidator validatorAgainstServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            seenVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            seenPath.set(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        return new HttpAnthropicKeyValidator(RestClient.builder(),
                new CredentialProperties(null, "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void ok_is_valid_and_the_call_carries_key_and_version_headers() throws IOException {
        HttpAnthropicKeyValidator v = validatorAgainstServer();

        assertThatCode(() -> v.validate(KEY)).doesNotThrowAnyException();
        assertThat(seenKey.get()).isEqualTo(KEY);
        assertThat(seenVersion.get()).isEqualTo(HttpAnthropicKeyValidator.ANTHROPIC_VERSION);
        assertThat(seenPath.get()).isEqualTo("/v1/models?limit=1");
    }

    @Test
    void rate_limited_counts_as_authenticated() throws IOException {
        HttpAnthropicKeyValidator v = validatorAgainstServer();
        status.set(429);

        assertThatCode(() -> v.validate(KEY)).doesNotThrowAnyException();
    }

    @Test
    void unauthorized_is_a_400_rejection_without_the_key() throws IOException {
        HttpAnthropicKeyValidator v = validatorAgainstServer();
        for (int code : new int[]{401, 403}) {
            status.set(code);
            assertThatThrownBy(() -> v.validate(KEY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(AnthropicKeyValidator.INVALID_MESSAGE)
                    .hasMessageNotContaining(KEY);
        }
    }

    @Test
    void server_error_is_503() throws IOException {
        HttpAnthropicKeyValidator v = validatorAgainstServer();
        status.set(503);

        assertThatThrownBy(() -> v.validate(KEY)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void unreachable_is_503_without_the_key() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HttpAnthropicKeyValidator v = new HttpAnthropicKeyValidator(RestClient.builder(),
                new CredentialProperties(null, "http://127.0.0.1:" + closedPort));

        assertThatThrownBy(() -> v.validate(KEY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(AnthropicKeyValidator.UNREACHABLE_MESSAGE);
    }
}
