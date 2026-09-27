package com.platform.agentservice.credential;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 암호화된 LLM 키 한 건(D-P3h-1). 원문 필드는 없다 — 평문은 {@link CredentialResolver}가 해석 순간에만 메모리로 복호화한다.
 * {@code toString}을 Lombok으로 만들지 않는 이유: 암호문·IV도 로그에 흘릴 이유가 없다.
 */
@Entity
@Table(name = "credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Credential {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private CredentialScope scope;
    @Column(length = 40) private String scopeId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private LlmProvider provider;
    @Column(nullable = false) private byte[] ciphertext;
    @Column(nullable = false) private byte[] iv;
    @Column(nullable = false, length = 8) private String keyHint;
    @Column(nullable = false) private Long updatedBy;
    @Column(nullable = false) private Instant updatedAt;

    public static Credential create(CredentialScope scope, String scopeId, LlmProvider provider) {
        Credential c = new Credential();
        c.scope = scope;
        c.scopeId = scopeId;
        c.provider = provider;
        return c;
    }

    public void replaceSecret(CredentialCipher.Sealed sealed, String keyHint, long updatedBy, Instant now) {
        this.ciphertext = sealed.ciphertext();
        this.iv = sealed.iv();
        this.keyHint = keyHint;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    @Override
    public String toString() {
        return "Credential{id=" + id + ", scope=" + scope + ", scopeId=" + scopeId + ", provider=" + provider + "}";
    }
}
