package com.platform.agentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1: 다운스트림 RestClient가 무한 대기하지 않고 설정된 타임아웃 안에서 끊어지는지 증명한다.
 * 실제 배포 값(연결 2초/읽기 10초)으로 테스트를 돌리면 스위트가 느려지므로, 여기서는
 * {@link ClientsConfig#timeoutRequestFactory(Duration, Duration)}에 짧은 타임아웃을 넣어
 * 같은 메커니즘(JdkClientHttpRequestFactory + 명시적 read timeout)이 실제로 동작을
 * 강제한다는 것만 확인한다.
 */
class ClientsConfigTest {

    @Test
    void production_timeouts_are_the_agreed_values() {
        assertThat(ClientsConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
        assertThat(ClientsConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void read_timeout_is_enforced_when_server_accepts_but_never_responds() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            // 연결은 받아주되 응답 바이트를 절대 쓰지 않는 서버 — read timeout만 순수하게 검증한다.
            CompletableFuture<Void> acceptor = CompletableFuture.runAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    TimeUnit.SECONDS.sleep(5);
                } catch (IOException | InterruptedException ignored) {
                    // 테스트 종료 시 소켓 닫힘으로 인한 예외는 무시
                }
            });

            JdkClientHttpRequestFactory requestFactory =
                    ClientsConfig.timeoutRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(1));
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(requestFactory)
                    .build();

            // 별도 preemptive-timeout 스레드로 감싸지 않는다 — 가짜 서버가 최대 5초만 붙잡고
            // 있으므로 이 호출 자체가 자연스럽게 유계(bounded)다. read timeout(1초)이 실제로
            // 걸린다면 5초보다 훨씬 짧게 끝나야 한다는 것으로 강제 여부를 검증한다.
            Instant start = Instant.now();
            assertThatThrownBy(() -> restClient.get().uri("/never-responds").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(elapsed)
                    .as("read timeout(1초)이 걸리지 않으면 가짜 서버가 응답을 주는 5초까지 기다리게 된다")
                    .isLessThan(Duration.ofSeconds(3));

            acceptor.cancel(true);
        }
    }

    @Test
    void connect_timeout_is_enforced_against_a_non_routable_address() {
        // TEST-NET-1(RFC 5737) — 연결 시도가 절대 성공/거부되지 않고 그냥 뭉개져야 connect timeout만 순수 검증된다.
        JdkClientHttpRequestFactory requestFactory =
                ClientsConfig.timeoutRequestFactory(Duration.ofMillis(500), Duration.ofSeconds(10));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://192.0.2.1")
                .requestFactory(requestFactory)
                .build();

        Instant start = Instant.now();
        assertThatThrownBy(() -> restClient.get().uri("/unreachable").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("connect timeout(0.5초)이 걸리지 않으면 OS 기본 연결 타임아웃(수십 초)까지 기다리게 된다")
                .isLessThan(Duration.ofSeconds(10));
    }
}
