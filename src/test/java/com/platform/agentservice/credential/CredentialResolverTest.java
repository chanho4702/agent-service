package com.platform.agentservice.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** 해석 순서 4단계(D-P3h-3)와 깨진 행 제외. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CredentialResolverTest {

    static final String PROJECT_KEY = "sk-ant-project-key-7777";
    static final String PLATFORM_KEY = "sk-ant-platform-key-8888";
    static final String ENV_KEY = "sk-ant-env-key-9999";

    @Mock CredentialRepository repository;

    CredentialCipher cipher = CredentialCipherTest.cipher(CredentialCipherTest.MASTER);

    @BeforeEach
    void noRowsByDefault() {
        lenient().when(repository.findProject(any(), any())).thenReturn(Optional.empty());
        lenient().when(repository.findPlatform(any())).thenReturn(Optional.empty());
    }

    Credential row(CredentialCipher by, CredentialScope scope, String scopeId, String plaintext) {
        Credential c = Credential.create(scope, scopeId, LlmProvider.ANTHROPIC);
        c.replaceSecret(by.encrypt(plaintext, scope, scopeId, LlmProvider.ANTHROPIC), CredentialService.hintOf(plaintext), 1L, Instant.now());
        return c;
    }

    CredentialResolver resolver(String envKey) {
        Map<String, String> env = envKey == null ? Map.of() : Map.of(CredentialResolver.ENV_KEY, envKey);
        return new CredentialResolver(repository, cipher, env::get);
    }

    void givenProject(Credential c) {
        lenient().when(repository.findProject("7", LlmProvider.ANTHROPIC)).thenReturn(Optional.of(c));
    }

    void givenPlatform(Credential c) {
        lenient().when(repository.findPlatform(LlmProvider.ANTHROPIC)).thenReturn(Optional.of(c));
    }

    @Test
    void project_key_wins_over_everything() {
        givenProject(row(cipher, CredentialScope.PROJECT, "7", PROJECT_KEY));
        givenPlatform(row(cipher, CredentialScope.PLATFORM, null, PLATFORM_KEY));

        ResolvedCredential r = resolver(ENV_KEY).resolve(7L);

        assertThat(r.source()).isEqualTo(CredentialSource.PROJECT);
        assertThat(r.apiKey()).isEqualTo(PROJECT_KEY);
        assertThat(r.keyHint()).isEqualTo("7777");
    }

    @Test
    void platform_key_when_project_has_none() {
        givenPlatform(row(cipher, CredentialScope.PLATFORM, null, PLATFORM_KEY));

        ResolvedCredential r = resolver(ENV_KEY).resolve(7L);

        assertThat(r.source()).isEqualTo(CredentialSource.PLATFORM);
        assertThat(r.apiKey()).isEqualTo(PLATFORM_KEY);
    }

    @Test
    void env_key_when_nothing_is_stored() {
        ResolvedCredential r = resolver(ENV_KEY).resolve(7L);

        assertThat(r.source()).isEqualTo(CredentialSource.ENV);
        assertThat(r.apiKey()).isEqualTo(ENV_KEY);
        assertThat(r.stored()).isFalse();
    }

    @Test
    void none_when_nothing_anywhere() {
        ResolvedCredential r = resolver(null).resolve(7L);

        assertThat(r.source()).isEqualTo(CredentialSource.NONE);
        assertThat(r.present()).isFalse();
        assertThat(r.keyHint()).isNull();
    }

    @Test
    void null_project_skips_the_project_layer() {
        givenPlatform(row(cipher, CredentialScope.PLATFORM, null, PLATFORM_KEY));

        assertThat(resolver(null).resolve(null).source()).isEqualTo(CredentialSource.PLATFORM);
    }

    @Test
    void undecryptable_project_row_falls_through_with_a_warning(CapturedOutput output) {
        CredentialCipher rotated = CredentialCipherTest.cipher(CredentialCipherTest.OTHER_MASTER);
        givenProject(row(rotated, CredentialScope.PROJECT, "7", PROJECT_KEY));
        givenPlatform(row(cipher, CredentialScope.PLATFORM, null, PLATFORM_KEY));

        ResolvedCredential r = resolver(null).resolve(7L);

        assertThat(r.source()).isEqualTo(CredentialSource.PLATFORM);
        assertThat(output.getAll()).contains("LLM 키 복호화 실패").doesNotContain(PROJECT_KEY);
    }

    @Test
    void row_swapped_from_another_project_is_not_used() {
        // 프로젝트 8의 암호문을 프로젝트 7 행에 옮겨 둔 상황 — AAD 불일치로 제외, 8의 키로 둔갑하지 않는다.
        Credential foreign = row(cipher, CredentialScope.PROJECT, "8", "sk-ant-other-project-0008");
        Credential swapped = row(cipher, CredentialScope.PROJECT, "7", "placeholder-key-0000");
        swapped.replaceSecret(new CredentialCipher.Sealed(foreign.getCiphertext(), foreign.getIv()), "0008", 1L, Instant.now());
        givenProject(swapped);

        assertThat(resolver(null).resolve(7L).source()).isEqualTo(CredentialSource.NONE);
    }

    @Test
    void to_string_never_prints_the_key() {
        assertThat(new ResolvedCredential(PROJECT_KEY, CredentialSource.PROJECT).toString())
                .doesNotContain(PROJECT_KEY).contains("PROJECT").contains("7777");
    }
}
