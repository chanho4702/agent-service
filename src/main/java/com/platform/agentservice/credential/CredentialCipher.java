package com.platform.agentservice.credential;

import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * LLM 키 암복호(D-P3h-1) — AES-256-GCM, 행별 랜덤 IV 12바이트, 태그 128비트.
 *
 * <p><b>AAD에 (scope, scopeId, provider)를 묶는 이유</b>: DB 쓰기 권한만 가진 공격자(또는 잘못된 운영 스크립트)가 A 프로젝트 행의
 * 암호문·IV를 B 프로젝트 행이나 전역 행으로 복사하면, AAD 없이는 그대로 복호화돼 B 프로젝트 run이 A의 키(= A의 과금)로 돈다.
 * AAD가 행의 좌표와 묶여 있으면 옮겨진 암호문은 태그 검증에서 떨어진다 — 해석에서 제외될 뿐 다른 층 키로 둔갑하지 않는다.
 *
 * <p>마스터 키가 없거나 형식이 틀려도 기동은 막지 않는다 — 키 설정은 선택 기능이고, 없으면 저장만 503(fail-closed)이며
 * 해석은 기존 env 경로로 떨어진다. 키 값은 어떤 로그·예외에도 싣지 않는다.
 */
@Slf4j
@Component
public class CredentialCipher {

    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String AAD_PREFIX = "agent-credential:v1";
    static final String UNAVAILABLE_MESSAGE =
            "자격증명 마스터 키가 설정되지 않았거나 형식이 잘못돼 키를 저장할 수 없습니다 — 운영자에게 AGENT_CREDENTIAL_MASTER_KEY(base64 32바이트) 설정을 요청하세요";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public record Sealed(byte[] ciphertext, byte[] iv) {}

    public CredentialCipher(CredentialProperties properties) {
        this.key = parse(properties.masterKey());
    }

    private static SecretKeySpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("AGENT_CREDENTIAL_MASTER_KEY 미설정 — LLM 키 저장 API는 503, 워커는 env 인증 경로만 씁니다");
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("AGENT_CREDENTIAL_MASTER_KEY 형식 오류(base64 아님) — LLM 키 저장 API는 503");
            return null;
        }
        if (bytes.length != 32) {
            log.warn("AGENT_CREDENTIAL_MASTER_KEY 길이 오류(디코딩 {}바이트, 32바이트 필요) — LLM 키 저장 API는 503", bytes.length);
            return null;
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean isAvailable() {
        return key != null;
    }

    public Sealed encrypt(String plaintext, CredentialScope scope, String scopeId, LlmProvider provider) {
        requireAvailable();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(scope, scopeId, provider));
            return new Sealed(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), iv);
        } catch (GeneralSecurityException e) {
            // 원인 메시지에 평문이 섞일 일은 없지만 암호 계층 내부 문구를 사용자에게 흘리지 않는다.
            throw new IllegalStateException("자격증명 암호화 실패(" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 태그 불일치(키 교체·행 바꿔치기)·마스터 키 없음이면 {@link CredentialDecryptException}. */
    public String decrypt(byte[] ciphertext, byte[] iv, CredentialScope scope, String scopeId, LlmProvider provider) {
        if (key == null) {
            throw new CredentialDecryptException("마스터 키 없음");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(scope, scopeId, provider));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CredentialDecryptException(e.getClass().getSimpleName());
        }
    }

    public void requireAvailable() {
        if (key == null) {
            throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
        }
    }

    static byte[] aad(CredentialScope scope, String scopeId, LlmProvider provider) {
        return (AAD_PREFIX + '|' + scope.name() + '|' + (scopeId == null ? "" : scopeId) + '|' + provider.name())
                .getBytes(StandardCharsets.UTF_8);
    }

    public static class CredentialDecryptException extends RuntimeException {
        CredentialDecryptException(String reason) {
            super("자격증명 복호화 실패: " + reason);
        }
    }
}
