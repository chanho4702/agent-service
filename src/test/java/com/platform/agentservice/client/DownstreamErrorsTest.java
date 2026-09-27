package com.platform.agentservice.client;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** AGP-25 — 다운스트림 상태 코드 → 플랫폼 예외(→ HTTP 코드) 매핑 표. */
class DownstreamErrorsTest {

    @Test
    void not_found_maps_to_not_found_with_downstream_message() {
        RuntimeException mapped = DownstreamErrors.map(error(HttpStatus.NOT_FOUND, "{\"error\":\"이슈를 찾을 수 없습니다: AGP-9\"}"), "이슈 조회");

        assertThat(mapped).isInstanceOf(NotFoundException.class).hasMessage("이슈를 찾을 수 없습니다: AGP-9");
    }

    @Test
    void not_found_without_json_body_falls_back_to_context_message() {
        RuntimeException mapped = DownstreamErrors.map(error(HttpStatus.NOT_FOUND, "<html>nope</html>"), "문서 조회");

        assertThat(mapped).isInstanceOf(NotFoundException.class).hasMessage("문서 조회 실패 (404 NOT_FOUND)");
    }

    @Test
    void unauthorized_is_a_service_auth_defect_not_a_caller_conflict() {
        RuntimeException mapped = DownstreamErrors.map(error(HttpStatus.UNAUTHORIZED, "{\"error\":\"만료된 토큰\"}"), "이슈 조회");

        assertThat(mapped).isInstanceOf(DownstreamAuthException.class).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("이슈 조회 실패: 다운스트림 인증 거부(401) — 서비스 토큰·내부 인증 설정을 확인하세요");
    }

    @Test
    void forbidden_bad_request_server_error_and_io_keep_their_previous_mapping() {
        assertThat(DownstreamErrors.map(error(HttpStatus.FORBIDDEN, "{\"error\":\"권한 없음\"}"), "x"))
                .isInstanceOf(ForbiddenException.class).hasMessage("권한 없음");
        assertThat(DownstreamErrors.map(error(HttpStatus.BAD_REQUEST, "{\"error\":\"스킴에 없는 상태\"}"), "x"))
                .isInstanceOf(ConflictException.class).hasMessage("스킴에 없는 상태");
        assertThat(DownstreamErrors.map(error(HttpStatus.CONFLICT, "{\"error\":\"충돌\"}"), "x"))
                .isInstanceOf(ConflictException.class);
        assertThat(DownstreamErrors.map(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8), "x"))
                .isInstanceOf(ServiceUnavailableException.class).isNotInstanceOf(DownstreamAuthException.class);
        assertThat(DownstreamErrors.map(new ResourceAccessException("timeout"), "x"))
                .isInstanceOf(ServiceUnavailableException.class).hasMessage("x 실패: 연결할 수 없습니다");
    }

    private static HttpClientErrorException error(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
