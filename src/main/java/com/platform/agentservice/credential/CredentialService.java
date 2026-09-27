package com.platform.agentservice.credential;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.credential.dto.CredentialPutRequest;
import com.platform.agentservice.credential.dto.CredentialStatusResponse;
import com.platform.agentservice.credential.dto.ProjectCredentialResponse;
import com.platform.common.error.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * LLM 키 2층 관리(D-P3h-2). 권한 판정은 컨트롤러의 {@code @agentAuthz}가 끝낸 뒤라 여기선 하지 않는다.
 *
 * <p><b>원문 비노출 원칙</b>: 키 원문은 이 클래스에서 검증·암호화에만 쓰이고 응답(힌트 4자만)·감사 summary(층·힌트만)·
 * 예외 메시지(고정 문구) 어디에도 실리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CredentialService {

    static final int MIN_KEY_LENGTH = 8;
    static final int MAX_KEY_LENGTH = 512;
    private static final int HINT_LENGTH = 4;

    private final CredentialRepository repository;
    private final CredentialCipher cipher;
    private final CredentialResolver resolver;
    private final AnthropicKeyValidator validator;
    private final AuditService auditService;
    private final Clock clock = Clock.systemUTC();

    /** 원문 끝 4자 — 짧은 키는 그대로 두면 전체가 노출되므로 길이 하한({@link #MIN_KEY_LENGTH})이 먼저 걸린다. */
    public static String hintOf(String apiKey) {
        return apiKey.length() <= HINT_LENGTH ? apiKey : apiKey.substring(apiKey.length() - HINT_LENGTH);
    }

    // ---- 조회 ----

    /** 마스터 키가 없어도 동작한다 — 저장된 메타데이터만 보여 준다. */
    @Transactional(readOnly = true)
    public CredentialStatusResponse platform() {
        return repository.findPlatform(LlmProvider.ANTHROPIC).map(CredentialStatusResponse::of).orElse(CredentialStatusResponse.UNSET);
    }

    @Transactional(readOnly = true)
    public ProjectCredentialResponse project(long projectId) {
        CredentialStatusResponse project = repository.findProject(String.valueOf(projectId), LlmProvider.ANTHROPIC)
                .map(CredentialStatusResponse::of).orElse(CredentialStatusResponse.UNSET);
        ResolvedCredential effective = resolver.resolve(projectId);
        return new ProjectCredentialResponse(project,
                new ProjectCredentialResponse.Effective(effective.source().name(), effective.keyHint()));
    }

    // ---- 저장·삭제 ----

    public CredentialStatusResponse putPlatform(CredentialPutRequest request, AgentCaller caller) {
        return put(CredentialScope.PLATFORM, null, request, caller);
    }

    public CredentialStatusResponse putProject(long projectId, CredentialPutRequest request, AgentCaller caller) {
        return put(CredentialScope.PROJECT, String.valueOf(projectId), request, caller);
    }

    public void deletePlatform(AgentCaller caller) {
        delete(CredentialScope.PLATFORM, null, caller);
    }

    public void deleteProject(long projectId, AgentCaller caller) {
        delete(CredentialScope.PROJECT, String.valueOf(projectId), caller);
    }

    private CredentialStatusResponse put(CredentialScope scope, String scopeId, CredentialPutRequest request, AgentCaller caller) {
        // 마스터 키부터 본다 — 검증 호출(외부 API)까지 해 놓고 저장 단계에서 503이 나면 헛수고다.
        cipher.requireAvailable();
        if (request == null) {
            throw new IllegalArgumentException("본문이 필요합니다");
        }
        LlmProvider provider = LlmProvider.parse(request.provider());
        String apiKey = normalize(request.apiKey());
        String hint = hintOf(apiKey);
        String where = where(scope, scopeId);

        if (request.validateRequested()) {
            try {
                validator.validate(apiKey);
            } catch (RuntimeException e) {
                auditService.record(null, caller.userId(), "credential.put", where + " provider=" + provider + " keyHint=" + hint
                        + " 검증 실패", AuditStatus.ERROR);
                throw e;
            }
        }

        CredentialCipher.Sealed sealed = cipher.encrypt(apiKey, scope, scopeId, provider);
        Credential saved;
        try {
            saved = upsert(scope, scopeId, provider, sealed, hint, caller.userId());
        } catch (DataIntegrityViolationException e) {
            // 같은 층에 동시 첫 저장이 겹쳐 유니크 제약에 걸린 경우 — 이긴 쪽 값이 남아 있으니 다시 시도하면 교체된다.
            throw new ConflictException("같은 키 설정이 동시에 저장됐습니다 — 다시 시도하세요");
        }
        auditService.record(null, caller.userId(), "credential.put", where + " provider=" + provider + " keyHint=" + hint
                + (request.validateRequested() ? " 검증됨" : ""), AuditStatus.OK);
        return CredentialStatusResponse.of(saved);
    }

    /** 외부 검증 호출을 트랜잭션 밖에 두려고 메서드 전체를 묶지 않는다 — 기존 행 교체가 겹치면 나중 저장이 이긴다(키 교체의 의미상 수용). */
    private Credential upsert(CredentialScope scope, String scopeId, LlmProvider provider, CredentialCipher.Sealed sealed,
                                String hint, long userId) {
        Credential credential = find(scope, scopeId, provider).orElseGet(() -> Credential.create(scope, scopeId, provider));
        credential.replaceSecret(sealed, hint, userId, Instant.now(clock));
        return repository.saveAndFlush(credential);
    }

    /** 없는 키 삭제도 204 — 멱등. 감사는 실제로 지운 경우만 남긴다. */
    private void delete(CredentialScope scope, String scopeId, AgentCaller caller) {
        Optional<Credential> existing = find(scope, scopeId, LlmProvider.ANTHROPIC);
        if (existing.isEmpty()) {
            return;
        }
        repository.delete(existing.get());
        auditService.record(null, caller.userId(), "credential.delete", where(scope, scopeId) + " provider=ANTHROPIC keyHint="
                + existing.get().getKeyHint(), AuditStatus.OK);
    }

    private Optional<Credential> find(CredentialScope scope, String scopeId, LlmProvider provider) {
        return scope == CredentialScope.PLATFORM ? repository.findPlatform(provider) : repository.findProject(scopeId, provider);
    }

    private static String where(CredentialScope scope, String scopeId) {
        return scope == CredentialScope.PLATFORM ? "scope=PLATFORM" : "scope=PROJECT projectId=" + scopeId;
    }

    /** 오류 문구에 입력값을 절대 넣지 않는다 — 잘못 붙여 넣은 키도 키다. */
    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("apiKey가 필요합니다");
        }
        String key = raw.strip();
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("apiKey 길이가 올바르지 않습니다(" + MIN_KEY_LENGTH + "~" + MAX_KEY_LENGTH + "자)");
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || ch > 0x7E) {
                // 워커 env 값으로 들어가므로 공백·제어·비ASCII는 붙여 넣기 실수로 보고 거부한다.
                throw new IllegalArgumentException("apiKey 형식이 올바르지 않습니다(공백·제어문자·비ASCII 문자 불가)");
            }
        }
        return key;
    }
}
