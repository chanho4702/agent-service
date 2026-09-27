package com.platform.agentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 다운스트림(auth-server/org-service/alm/wiki) 호출 실패를 플랫폼 오류 계약(common-starter)으로
 * 매핑한다 — 프론트·워커가 "내가 잘못 보냈다", "대상이 없다", "서버가 지금 안 된다"를 구분하게 한다.
 * {@code {"error":...}} 계약은 common-starter의 {@code PlatformApiExceptionHandler}가 지킨다.
 *
 * <ul>
 *   <li>401 → {@link DownstreamAuthException}(503) — 이 서비스가 보낸 자격증명이 거부된 서비스 간 인증 결함(AGP-25)</li>
 *   <li>403 → {@link ForbiddenException}(403) — 인가 거부, 다운스트림 메시지 그대로</li>
 *   <li>404 → {@link NotFoundException}(404) — 대상 없음, 다운스트림 메시지 그대로(AGP-25 — 예전엔 409)</li>
 *   <li>5xx·연결 실패 → {@link ServiceUnavailableException}(503)</li>
 *   <li>그 밖의 4xx(400 스킴 위반·409 등) → {@link ConflictException}(409) — 기존 그대로</li>
 * </ul>
 */
final class DownstreamErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DownstreamErrors() {}

    static RuntimeException map(RestClientException e, String context) {
        if (e instanceof HttpStatusCodeException status) {
            int code = status.getStatusCode().value();
            if (code == 401) {
                return new DownstreamAuthException(context + " 실패: 다운스트림 인증 거부(401) — 서비스 토큰·내부 인증 설정을 확인하세요");
            }
            if (status.getStatusCode().is5xxServerError()) {
                return new ServiceUnavailableException(context + " 실패: 다운스트림 오류(" + status.getStatusCode() + ")");
            }
            String message = extractMessage(status, context);
            if (code == 403) {
                return new ForbiddenException(message);
            }
            if (code == 404) {
                return new NotFoundException(message);
            }
            return new ConflictException(message);
        }
        return new ServiceUnavailableException(context + " 실패: 연결할 수 없습니다");
    }

    /** AlmClient가 409(낙관적 락 충돌)를 별도 예외로 분리해 던질 때 메시지 추출에 재사용한다. */
    static String extractMessage(HttpStatusCodeException e, String fallbackContext) {
        try {
            Map<?, ?> body = MAPPER.readValue(e.getResponseBodyAsString(), Map.class);
            Object error = body.get("error");
            if (error != null) {
                return String.valueOf(error);
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니거나 error 필드가 없으면 기본 메시지로 대체
        }
        return fallbackContext + " 실패 (" + e.getStatusCode() + ")";
    }
}
