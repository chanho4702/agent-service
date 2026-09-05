package com.platform.agentservice.client;

import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTokenClientTest {

    @Test
    void mint_with_blank_internal_secret_fails_closed_without_any_http_call() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        // 기대를 하나도 등록하지 않는다 — mint()가 실제로 요청을 내보내면
        // MockRestServiceServer가 "예기치 못한 요청"으로 이 테스트를 실패시킨다.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "");

        assertThatThrownBy(() -> authTokenClient.mint(9001L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("에이전트 토큰 발급이 설정되지 않았습니다");

        server.verify();
    }

    @Test
    void mint_with_whitespace_only_internal_secret_also_fails_closed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 생성자가 trim()하므로 공백만 있는 시크릿도 "미설정"과 동일하게 취급돼야 한다.
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "   ");

        assertThatThrownBy(() -> authTokenClient.mint(9001L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("에이전트 토큰 발급이 설정되지 않았습니다");

        server.verify();
    }
}
