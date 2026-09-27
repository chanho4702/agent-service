package com.platform.agentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * I1: 다운스트림 RestClient가 무한 대기하지 않고 설정된 타임아웃 안에서 끊어지는지 증명한다.
 * 실제 배포 값(연결 2초/읽기 10초)으로 테스트를 돌리면 스위트가 느려지므로, 여기서는
 * {@link ClientsConfig#timeoutRequestFactory(Duration, Duration)}에 짧은 타임아웃을 넣어
 * 같은 메커니즘(JdkClientHttpRequestFactory + 명시적 read timeout)이 실제로 동작을
 * 강제한다는 것만 확인한다.
 *
 * <p>AGP-30: 예전 판정은 "경과 시간이 N초 미만"이라 부하가 걸린 전체 스위트(컨텍스트 여러 개 기동 중)에서
 * 간헐적으로 넘쳤다. 이제는 시간 대신 <b>예외 원인</b>으로 판정한다 — read timeout이 걸렸다면 원인이
 * {@link HttpTimeoutException}이다(타임아웃이 없었다면 가짜 서버가 끝까지 붙잡고 있어 hang → 상한 30초의
 * {@code assertTimeoutPreemptively}가 끊는다). 연결 타임아웃은 네트워크 환경마다 TEST-NET 주소가 즉시 거부되기도
 * 해서 행동 검증 대신 {@link java.net.http.HttpClient#connectTimeout()} 설정값으로 결정적으로 본다.
 */
class ClientsConfigTest {

    private static final Duration HANG_GUARD = Duration.ofSeconds(30);

    @Test
    void production_timeouts_are_the_agreed_values() {
        assertThat(ClientsConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
        assertThat(ClientsConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void read_timeout_is_enforced_when_server_accepts_but_never_responds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            // 연결은 받아주되 응답 바이트를 절대 쓰지 않고, 테스트가 끝날 때까지 소켓을 붙잡는다 —
            // read timeout 말고는 이 호출을 끝낼 길이 없다.
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    release.await();
                } catch (Exception ignored) {
                    // 테스트 종료 시 소켓 닫힘으로 인한 예외는 무시
                }
            });

            JdkClientHttpRequestFactory requestFactory =
                    ClientsConfig.timeoutRequestFactory(Duration.ofSeconds(2), Duration.ofMillis(500));
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(requestFactory)
                    .build();

            assertTimeoutPreemptively(HANG_GUARD, () ->
                    assertThatThrownBy(() -> restClient.get().uri("/never-responds").retrieve().body(String.class))
                            .isInstanceOf(ResourceAccessException.class)
                            .hasRootCauseInstanceOf(HttpTimeoutException.class)
                            .rootCause().isNotInstanceOf(HttpConnectTimeoutException.class));

            release.countDown();
            acceptor.join(TimeUnit.SECONDS.toMillis(5));
        } finally {
            release.countDown();
        }
    }

    @Test
    void connect_timeout_is_configured_on_the_http_client() {
        assertThat(ClientsConfig.timeoutHttpClient(Duration.ofMillis(500)).connectTimeout())
                .isEqualTo(Optional.of(Duration.ofMillis(500)));
        assertThat(ClientsConfig.timeoutHttpClient(ClientsConfig.CONNECT_TIMEOUT).connectTimeout())
                .isEqualTo(Optional.of(Duration.ofSeconds(2)));
    }

    @Test
    void connect_attempt_to_a_non_routable_address_never_hangs() {
        // TEST-NET-1(RFC 5737) — 환경에 따라 connect timeout 또는 즉시 거부로 끝난다. 어느 쪽이든 OS 기본
        // 연결 타임아웃(수십 초~분)까지 매달리지 않는다는 것만 본다 — 시간 경계가 아니라 hang 여부다.
        JdkClientHttpRequestFactory requestFactory =
                ClientsConfig.timeoutRequestFactory(Duration.ofMillis(500), Duration.ofSeconds(10));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://192.0.2.1")
                .requestFactory(requestFactory)
                .build();

        assertTimeoutPreemptively(HANG_GUARD, () ->
                assertThatThrownBy(() -> restClient.get().uri("/unreachable").retrieve().body(String.class))
                        .isInstanceOf(ResourceAccessException.class));
    }
}
