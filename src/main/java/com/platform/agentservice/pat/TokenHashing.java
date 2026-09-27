package com.platform.agentservice.pat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 베어러 토큰 생성·해시 공통(사람용 PAT {@code agp_}·러너 {@code agr_}). 원문은 발급 응답에만 존재하고 저장은 SHA-256 hex(64자)로만
 * 한다 — 이후 재조회 불가.
 */
public final class TokenHashing {

    private static final int TOKEN_RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHashing() {}

    /** {@code prefix} + URL-safe base64(32바이트 난수). */
    public static String generate(String prefix) {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }

    /** 로그·화면 표시용 앞 8자(접두 + 4자) — 원문 전체를 싣지 않는다. */
    public static String displayPrefix(String rawToken) {
        if (rawToken == null) {
            return "";
        }
        return rawToken.length() <= 8 ? "(짧음)" : rawToken.substring(0, 8);
    }
}
