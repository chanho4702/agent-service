package com.platform.agentservice.chat;

import com.platform.agentservice.credential.CredentialResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * office {@code features.chat}(P3g ①) — 설치 옵션이 켜져 있고 LLM 키가 해석되는가. 키 층 규칙은 {@link CredentialResolver}
 * 하나를 쓴다(D-P3h-5 — 복제 금지). projectId가 없으면 전역·env 층만 본다.
 */
@Component
@RequiredArgsConstructor
public class ChatAvailability {

    private final ChatProperties properties;
    private final CredentialResolver resolver;

    public boolean available(Long projectId) {
        return properties.enabled() && resolver.resolve(projectId).present();
    }
}
