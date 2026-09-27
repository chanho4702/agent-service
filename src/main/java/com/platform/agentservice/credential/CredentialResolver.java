package com.platform.agentservice.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * run·대화가 쓸 LLM 키를 고른다(D-P3h-3): <b>프로젝트 키 &gt; 전역 키 &gt; 서비스 env {@code ANTHROPIC_API_KEY} &gt; 없음</b>.
 * 워커 런처(D-P3h-4)와 이후 자유 대화(P3g, D-P3h-5)가 같은 빈을 쓴다 — 층 규칙을 소비처마다 복제하지 않는다.
 *
 * <p>복호화에 실패한 행(마스터 키 교체·미설정, 행 바꿔치기)은 그 층이 없는 것으로 보고 다음 층으로 내려간다 + warn. 실패를
 * 예외로 올리면 키 하나가 깨졌다고 그 프로젝트의 모든 run이 멈추고, 반대로 조용히 삼키면 운영자가 과금 층이 바뀐 걸 모른다 —
 * 그래서 계속 진행하되 로그로 드러낸다(GET 프로젝트 API의 effective.scope에도 실제 층이 보인다).
 */
@Slf4j
@Component
public class CredentialResolver {

    static final String ENV_KEY = "ANTHROPIC_API_KEY";

    private final CredentialRepository repository;
    private final CredentialCipher cipher;
    private final UnaryOperator<String> env;

    @Autowired
    public CredentialResolver(CredentialRepository repository, CredentialCipher cipher) {
        this(repository, cipher, System::getenv);
    }

    CredentialResolver(CredentialRepository repository, CredentialCipher cipher, UnaryOperator<String> env) {
        this.repository = repository;
        this.cipher = cipher;
        this.env = env;
    }

    /** {@code projectId}가 null이면(프로젝트 축이 없는 호출) 프로젝트 층을 건너뛴다. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolve(Long projectId) {
        LlmProvider provider = LlmProvider.ANTHROPIC;
        if (projectId != null) {
            Optional<String> projectKey = repository.findProject(String.valueOf(projectId), provider).flatMap(this::decrypt);
            if (projectKey.isPresent()) {
                return new ResolvedCredential(projectKey.get(), CredentialSource.PROJECT);
            }
        }
        Optional<String> platformKey = repository.findPlatform(provider).flatMap(this::decrypt);
        if (platformKey.isPresent()) {
            return new ResolvedCredential(platformKey.get(), CredentialSource.PLATFORM);
        }
        String envKey = env.apply(ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            return new ResolvedCredential(envKey, CredentialSource.ENV);
        }
        return ResolvedCredential.NONE;
    }

    private Optional<String> decrypt(Credential c) {
        try {
            return Optional.of(cipher.decrypt(c.getCiphertext(), c.getIv(), c.getScope(), c.getScopeId(), c.getProvider()));
        } catch (CredentialCipher.CredentialDecryptException e) {
            log.warn("LLM 키 복호화 실패 — 이 층을 건너뜁니다(마스터 키 교체·미설정 또는 행 변조 의심): credential id={} scope={} scopeId={} ({})",
                    c.getId(), c.getScope(), c.getScopeId(), e.getMessage());
            return Optional.empty();
        }
    }
}
