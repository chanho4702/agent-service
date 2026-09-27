package com.platform.agentservice.credential;

import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-256-GCM 암복호(D-P3h-1) — 왕복·IV 랜덤성·AAD 바꿔치기 거부·마스터 키 형식. */
class CredentialCipherTest {

    static final String MASTER = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    static final String OTHER_MASTER = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII));
    static final String KEY = "sk-ant-api03-roundtrip-secret-0001";

    static CredentialCipher cipher(String master) {
        return new CredentialCipher(new CredentialProperties(master, "https://api.anthropic.com"));
    }

    @Test
    void round_trip_restores_the_plaintext_and_ciphertext_does_not_contain_it() {
        CredentialCipher c = cipher(MASTER);
        CredentialCipher.Sealed sealed = c.encrypt(KEY, CredentialScope.PROJECT, "7", LlmProvider.ANTHROPIC);

        assertThat(sealed.iv()).hasSize(CredentialCipher.IV_BYTES);
        assertThat(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
        assertThat(c.decrypt(sealed.ciphertext(), sealed.iv(), CredentialScope.PROJECT, "7", LlmProvider.ANTHROPIC)).isEqualTo(KEY);
    }

    @Test
    void every_encryption_uses_a_fresh_random_iv() {
        CredentialCipher c = cipher(MASTER);
        CredentialCipher.Sealed a = c.encrypt(KEY, CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC);
        CredentialCipher.Sealed b = c.encrypt(KEY, CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC);

        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void ciphertext_moved_to_another_row_fails_the_tag_check() {
        CredentialCipher c = cipher(MASTER);
        CredentialCipher.Sealed sealed = c.encrypt(KEY, CredentialScope.PROJECT, "7", LlmProvider.ANTHROPIC);

        // 다른 프로젝트 행으로 복사
        assertThatThrownBy(() -> c.decrypt(sealed.ciphertext(), sealed.iv(), CredentialScope.PROJECT, "8", LlmProvider.ANTHROPIC))
                .isInstanceOf(CredentialCipher.CredentialDecryptException.class);
        // 전역 행으로 승격
        assertThatThrownBy(() -> c.decrypt(sealed.ciphertext(), sealed.iv(), CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC))
                .isInstanceOf(CredentialCipher.CredentialDecryptException.class);
    }

    @Test
    void rotated_master_key_cannot_decrypt_and_the_error_carries_no_plaintext() {
        CredentialCipher.Sealed sealed = cipher(MASTER).encrypt(KEY, CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC);

        assertThatThrownBy(() -> cipher(OTHER_MASTER).decrypt(sealed.ciphertext(), sealed.iv(), CredentialScope.PLATFORM, null,
                LlmProvider.ANTHROPIC))
                .isInstanceOf(CredentialCipher.CredentialDecryptException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void missing_or_malformed_master_key_makes_the_cipher_unavailable_and_encrypt_503() {
        String wrongLength = Base64.getEncoder().encodeToString(new byte[16]);
        for (String master : new String[]{null, "", "   ", "not base64 !!", wrongLength}) {
            CredentialCipher c = cipher(master);
            assertThat(c.isAvailable()).as("master=%s", master).isFalse();
            assertThatThrownBy(() -> c.encrypt(KEY, CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC))
                    .isInstanceOf(ServiceUnavailableException.class);
            assertThatThrownBy(() -> c.decrypt(new byte[16], new byte[12], CredentialScope.PLATFORM, null, LlmProvider.ANTHROPIC))
                    .isInstanceOf(CredentialCipher.CredentialDecryptException.class);
        }
    }

    @Test
    void properties_to_string_hides_the_master_key() {
        assertThat(new CredentialProperties(MASTER, "u").toString()).doesNotContain(MASTER).contains("(set)");
    }
}
