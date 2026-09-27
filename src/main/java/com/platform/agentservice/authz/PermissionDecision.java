package com.platform.agentservice.authz;

/**
 * org-service CheckPermission 판정 결과. {@code deniedReason}은 org 값(PENDING·SUSPENDED·DEACTIVATED·NO_GRANT·
 * INSUFFICIENT_ROLE) 그대로이거나, 판정 자체를 못 한 경우 {@link #ORG_FAILURE}다.
 *
 * <p>alm은 org 불능을 503으로 올리지만 여기서는 거부로 닫는다(D-P3f-2) — 관리 행위만 막히고 전역 관리자는 JWT만으로
 * 통과하므로 운영이 멈추지 않는다. 대신 사유를 구분해 두어 사용자에게 "권한 없음"이라고 거짓말하지 않는다.
 */
public record PermissionDecision(boolean allowed, String deniedReason) {

    /** 판정 실패(org 장애·데드라인·그 밖의 gRPC 오류) — org가 준 값이 아니라 이 서비스가 붙인 표식이다. */
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
            case ORG_FAILURE -> "권한 서비스에 연결할 수 없어 거부했습니다 — 잠시 후 다시 시도하세요";
            case "PENDING" -> "승인 대기 중인 계정입니다";
            case "SUSPENDED" -> "정지된 계정입니다";
            case "DEACTIVATED" -> "비활성된 계정입니다";
            default -> null;
        };
    }
}
