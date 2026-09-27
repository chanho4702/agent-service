package com.platform.agentservice.authz;

/**
 * org-service CheckPermission 판정 결과. {@code deniedReason}은 org 값(PENDING·SUSPENDED·DEACTIVATED·NO_GRANT·
 * INSUFFICIENT_ROLE) 그대로이거나, 판정 자체를 못 한 경우 {@link #ORG_FAILURE}다.
 *
 * <p>org 가용성 장애는 이 레코드가 아니라 {@code ServiceUnavailableException}(503)으로 온다(alm과 같다). {@link #ORG_FAILURE}는
 * 그 밖의 gRPC 오류 — 판정을 못 했으니 거부로 닫되(fail-closed) 사용자에게 "권한 없음"이라고 거짓말하지 않게 사유를 구분한다.
 */
public record PermissionDecision(boolean allowed, String deniedReason) {

    /** 판정 실패(가용성 외 gRPC 오류) — org가 준 값이 아니라 이 서비스가 붙인 표식이다. */
    public static final String ORG_FAILURE = "ORG_FAILURE";

    public PermissionDecision {
        deniedReason = deniedReason == null ? "" : deniedReason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(true, "");
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }

    public static PermissionDecision orgFailure() {
        return new PermissionDecision(false, ORG_FAILURE);
    }

    /** 권한이 모자란 것이 아닌 거부라면 그 사실을 말하는 문구, 아니면 null(호출측이 맥락 문구를 쓴다). */
    public String specialMessage() {
        return switch (deniedReason) {
            case ORG_FAILURE -> "권한 판정에 실패해 거부했습니다 — 잠시 후 다시 시도하세요";
            case "PENDING" -> "승인 대기 중인 계정입니다";
            case "SUSPENDED" -> "정지된 계정입니다";
            case "DEACTIVATED" -> "비활성된 계정입니다";
            default -> null;
        };
    }
}
