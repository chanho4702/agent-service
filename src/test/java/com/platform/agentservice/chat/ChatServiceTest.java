package com.platform.agentservice.chat;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.budget.BudgetService;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.chat.dto.ChatRequest;
import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.RunRepository;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 설치 옵션 CHAT_ENABLED=false → 503, 키·예산·레이트 리밋·LLM에 닿지 않는다. */
class ChatServiceTest {

    @Test
    void disabled_install_option_is_503_before_budget_keys_rate_limit_and_llm() {
        PersonaRepository personas = mock(PersonaRepository.class);
        given(personas.findById(3L)).willReturn(Optional.of(Persona.of(9L, "jiho", PersonaRole.BACKEND, "지호", null, null)));
        CredentialResolver resolver = mock(CredentialResolver.class);
        BudgetService budget = mock(BudgetService.class);
        ChatRateLimiter limiter = mock(ChatRateLimiter.class);
        AnthropicChatClient client = mock(AnthropicChatClient.class);
        ChatProperties off = new ChatProperties(false, "m", 400, 30, Duration.ofMinutes(10), BigDecimal.ONE,
                BigDecimal.ONE, 10, 90, 500);
        ChatService service = new ChatService(personas, mock(RunRepository.class), resolver, budget,
                mock(UsageLedgerRepository.class), mock(AuditService.class), mock(DialogService.class), limiter, client,
                off, mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> service.chat(1L, 3L, new ChatRequest("안녕", null, null)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("꺼져");
        verifyNoInteractions(resolver, budget, client);
        verify(limiter, never()).tryAcquire(anyLong());
    }
}
