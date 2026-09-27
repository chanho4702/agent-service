package com.platform.agentservice.client;

import com.platform.common.error.ServiceUnavailableException;

/**
 * 다운스트림이 이 서비스가 실어 보낸 자격증명을 401로 거부했다(AGP-25). 호출자(사람·워커)가 고칠 수 있는
 * 입력 오류가 아니라 서비스 간 인증 결함(서비스 토큰 만료·JWKS/issuer 불일치·내부 시크릿 불일치)이라 503으로
 * 내보낸다 — 예전처럼 409로 뭉개면 "내가 잘못 보냈다"로 읽혀 원인 추적이 끊긴다. MCP 도구 오류 문구는
 * "일시 장애·재시도"와도 구분한다(재시도로는 안 풀린다 — {@code Audited}).
 */
public class DownstreamAuthException extends ServiceUnavailableException {
    public DownstreamAuthException(String message) {
        super(message);
    }
}
