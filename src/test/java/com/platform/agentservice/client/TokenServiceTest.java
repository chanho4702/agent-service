package com.platform.agentservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TokenServiceTest {

    @Test
    void bearerFor_mints_once_and_reuses_cache_on_second_call() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "internal-secret");
        TokenService tokenService = new TokenService(authTokenClient);

        // 딱 하나의 기대만 등록한다 — 두 번째 bearerFor가 실제로 또 mint를 시도하면
        // MockRestServiceServer가 예기치 못한 요청으로 실패시킨다.
        server.expect(requestTo("http://auth-server/internal/service-tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "internal-secret"))
                .andRespond(withSuccess("""
                        {"accessToken":"tok-1","expiresInSeconds":900}
                        """, MediaType.APPLICATION_JSON));

        String first = tokenService.bearerFor(9001L);
        String second = tokenService.bearerFor(9001L);

        assertThat(first).isEqualTo("Bearer tok-1");
        assertThat(second).isEqualTo("Bearer tok-1");
        server.verify();
    }

    @Test
    void bearerFor_mints_separately_per_persona() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-server");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthTokenClient authTokenClient = new AuthTokenClient(builder.build(), "internal-secret");
        TokenService tokenService = new TokenService(authTokenClient);

        server.expect(requestTo("http://auth-server/internal/service-tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"accessToken":"tok-a","expiresInSeconds":900}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth-server/internal/service-tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"accessToken":"tok-b","expiresInSeconds":900}
                        """, MediaType.APPLICATION_JSON));

        assertThat(tokenService.bearerFor(9001L)).isEqualTo("Bearer tok-a");
        assertThat(tokenService.bearerFor(9002L)).isEqualTo("Bearer tok-b");
        server.verify();
    }
}
