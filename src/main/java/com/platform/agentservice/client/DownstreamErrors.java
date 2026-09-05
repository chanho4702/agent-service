package com.platform.agentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 다운스트림(auth-server/org-service) 호출 실패를 플랫폼 오류 계약(common-starter)으로
 * 매핑한다. 403은 인가 거부(ForbiddenException), 그 외 4xx는 상태 충돌(ConflictException),
 * 5xx·연결 실패는 가용성 장애(ServiceUnavailableException)로 묶는다 — 프론트가 "내가
 * 잘못 보냈다"와 "서버가 지금 안 된다"를 구분하게 한다. {@code {"error":...}} 계약은
 * common-starter의 {@code PlatformApiExceptionHandler}가 지킨다.
 */
final class DownstreamErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DownstreamErrors() {}

    static RuntimeException map(RestClientException e, String context) {
        if (e instanceof HttpStatusCodeException status) {
            String message = extractMessage(status, context);
            if (status.getStatusCode().value() == 403) {
                return new ForbiddenException(message);
            }
            if (status.getStatusCode().is5xxServerError()) {
                return new ServiceUnavailableException(context + " 실패: 다운스트림 오류(" + status.getStatusCode() + ")");
            }
            return new ConflictException(message);
        }
        return new ServiceUnavailableException(context + " 실패: 연결할 수 없습니다");
    }

    private static String extractMessage(HttpStatusCodeException e, String fallbackContext) {
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
