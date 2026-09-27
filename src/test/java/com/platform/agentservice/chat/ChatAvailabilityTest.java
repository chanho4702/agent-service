package com.platform.agentservice.chat;

import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.credential.CredentialSource;
import com.platform.agentservice.credential.ResolvedCredential;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** office features.chat(P3g ①) = CHAT_ENABLED && 키 해석됨 — 층 판정은 CredentialResolver에 projectId를 그대로 넘긴다. */
class ChatAvailabilityTest {

    static ChatProperties props(boolean enabled) {
        return new ChatProperties(enabled, "m", 400, 30, Duration.ofMinutes(10), BigDecimal.ONE, BigDecimal.valueOf(5), 10, 90, 500);
    }

    final CredentialResolver resolver = mock(CredentialResolver.class);

    @Test
    void enabled_and_key_found_is_true_for_each_layer() {
        for (CredentialSource source : new CredentialSource[]{CredentialSource.PROJECT, CredentialSource.PLATFORM, CredentialSource.ENV}) {
            given(resolver.resolve(7L)).willReturn(new ResolvedCredential("sk-ant-xxxxxxxx", source));
            assertThat(new ChatAvailability(props(true), resolver).available(7L)).as(source.name()).isTrue();
        }
    }

    @Test
    void no_key_is_false() {
        given(resolver.resolve(null)).willReturn(ResolvedCredential.NONE);
        assertThat(new ChatAvailability(props(true), resolver).available(null)).isFalse();
    }

    @Test
    void project_context_is_passed_through_to_the_resolver() {
        given(resolver.resolve(3L)).willReturn(new ResolvedCredential("sk-ant-project-3", CredentialSource.PROJECT));
        given(resolver.resolve(4L)).willReturn(ResolvedCredential.NONE);
        ChatAvailability availability = new ChatAvailability(props(true), resolver);

        assertThat(availability.available(3L)).isTrue();
        assertThat(availability.available(4L)).isFalse();
    }

    @Test
    void disabled_install_option_is_false_without_touching_keys() {
        assertThat(new ChatAvailability(props(false), resolver).available(7L)).isFalse();
        verifyNoInteractions(resolver);
    }
}
