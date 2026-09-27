package com.platform.agentservice.client;

import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void mint_unauthorized_is_logged_and_surfaces_as_auth_defect(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://auth-server/internal/service-tokens"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "top-secret-value");

        assertThatThrownBy(() -> authTokenClient.mint(9001L)).isInstanceOf(DownstreamAuthException.class);

        assertThat(output).contains("서비스 토큰 발급 실패: memberId=9001 원인=HTTP 401");
        assertThat(output).doesNotContain("top-secret-value");
        server.verify();
    }

    @Test
    void mint_not_found_is_not_reported_as_caller_404() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://auth-server/internal/service-tokens"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "s");

        assertThatThrownBy(() -> authTokenClient.mint(9001L))
                .isInstanceOf(ServiceUnavailableException.class)
                .isNotInstanceOf(com.platform.common.error.NotFoundException.class);
        server.verify();
    }
}
