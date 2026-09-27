package com.platform.agentservice.chat;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.budget.BudgetService;
import com.platform.agentservice.budget.LedgerScope;
import com.platform.agentservice.budget.UsageLedger;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.chat.dto.ChatRequest;
import com.platform.agentservice.chat.dto.ChatResponse;
import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.credential.ResolvedCredential;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunService;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 사무실 1:1 수다(P3g ②, AGP-65). 인증 사용자 누구나(권한 판정 없음 — 스펙 §4). 판정 순서: 입력(400) → 페르소나(404) →
 * 설치 옵션(503) → 킬 스위치·월 상한(409) → LLM 키(503) → 레이트 리밋(429) → Anthropic 호출(실패 503).
 * 레이트 리밋을 맨 뒤에 두는 것은 실제로 LLM을 부르는 시도만 창을 쓰게 하려는 것이다.
 *
 * <p>LLM 호출은 트랜잭션 밖이고, 성공하면 원장(PLATFORM + 프로젝트 문맥이 있으면 PROJECT)·감사·대화 기록(USER·PERSONA SAY)을 한
 * 트랜잭션으로 남긴다. 실패한 턴은 기록하지 않는다(다음 문맥이 사용자/페르소나 교대를 유지하게).
 *
 * <p><b>본문 비노출</b>: 사용자 메시지·답변은 대화 기록 테이블에만 저장한다 — 로그·감사 summary·예외 문구에는 메타(페르소나·세션·토큰·
 * 키 출처)만. 키 원문은 {@link ResolvedCredential} 밖으로 나가지 않는다.
 */
@Slf4j
@Service
public class ChatService {

    static final int MESSAGE_MAX = 500;
    private static final Pattern SESSION_ID = Pattern.compile("c-[0-9A-Za-z-]{8,38}");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final PersonaRepository personaRepository;
    private final RunRepository runRepository;
    private final CredentialResolver credentialResolver;
    private final BudgetService budgetService;
    private final UsageLedgerRepository ledgerRepository;
    private final AuditService auditService;
    private final DialogService dialogService;
    private final ChatRateLimiter rateLimiter;
    private final AnthropicChatClient chatClient;
    private final ChatProperties properties;
    private final TransactionTemplate tx;

    public ChatService(PersonaRepository personaRepository, RunRepository runRepository, CredentialResolver credentialResolver,
                       BudgetService budgetService, UsageLedgerRepository ledgerRepository, AuditService auditService,
                       DialogService dialogService, ChatRateLimiter rateLimiter, AnthropicChatClient chatClient,
                       ChatProperties properties, PlatformTransactionManager transactionManager) {
        this.personaRepository = personaRepository;
        this.runRepository = runRepository;
        this.credentialResolver = credentialResolver;
        this.budgetService = budgetService;
        this.ledgerRepository = ledgerRepository;
        this.auditService = auditService;
        this.dialogService = dialogService;
        this.rateLimiter = rateLimiter;
        this.chatClient = chatClient;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public ChatResponse chat(long userId, long personaId, ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("본문이 필요합니다");
        }
        String message = DialogService.checkText(request.message(), "message", MESSAGE_MAX);
        String sessionId = sessionIdOf(request.sessionId());
        Long projectId = request.projectId();

        Persona persona = personaRepository.findById(personaId)
                .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + personaId));
        if (!properties.enabled()) {
            throw new ServiceUnavailableException("사무실 대화 기능이 꺼져 있습니다");
        }
        if (budgetService.killSwitchOn()) {
            throw new ConflictException("킬 스위치가 켜져 있어 지금은 대화할 수 없습니다");
        }
        if (!budgetService.withinCap(projectId)) {
            throw new ConflictException("이번 달 AI 예산 상한에 도달해 대화할 수 없습니다");
        }
        ResolvedCredential credential = credentialResolver.resolve(projectId);
        if (!credential.present()) {
            throw new ServiceUnavailableException("LLM 키가 설정되지 않아 대화할 수 없습니다 — 관리자에게 키 설정을 요청하세요");
        }
        if (!rateLimiter.tryAcquire(userId)) {
            throw new ChatRateLimitedException("대화가 너무 잦습니다 — " + properties.rateWindow().toMinutes()
                    + "분에 " + properties.rateLimit() + "번까지예요. 잠시 후 다시 말을 걸어 주세요");
        }

        Run currentRun = runRepository.findFirstByPersonaIdAndStatusInOrderByIdDesc(personaId, RunService.ACTIVE_STATUSES)
                .orElse(null);
        List<DialogEntry> history = dialogService.recentSessionTurns(userId, personaId, sessionId, properties.contextTurns());
        String system = ChatPromptBuilder.system(persona, currentRun);
        List<AnthropicChatClient.Message> messages = ChatPromptBuilder.messages(history, message);

        AnthropicChatClient.Completion completion;
        try {
            completion = chatClient.complete(credential.apiKey(), properties.model(), properties.maxTokens(), system, messages);
        } catch (RuntimeException e) {
            auditService.record(null, userId, "chat.say", meta(personaId, sessionId, credential) + " 응답 실패", AuditStatus.ERROR);
            throw e;
        }
        ChatReplyParser.Parsed parsed = ChatReplyParser.parse(completion.text());
        BigDecimal cost = costOf(completion);

        tx.executeWithoutResult(status -> {
            String scope = credential.source().name();
            ledgerRepository.save(UsageLedger.withoutRun(LedgerScope.PLATFORM, "platform", cost,
                    completion.inputTokens(), completion.outputTokens(), properties.model(), scope));
            if (projectId != null) {
                ledgerRepository.save(UsageLedger.withoutRun(LedgerScope.PROJECT, String.valueOf(projectId), cost,
                        completion.inputTokens(), completion.outputTokens(), properties.model(), scope));
            }
            auditService.record(null, userId, "chat.say", meta(personaId, sessionId, credential)
                    + " in=" + completion.inputTokens() + " out=" + completion.outputTokens()
                    + (parsed.reply().isEmpty() ? " 빈 응답" : ""), parsed.reply().isEmpty() ? AuditStatus.ERROR : AuditStatus.OK);
            if (!parsed.reply().isEmpty()) {
                dialogService.recordChatTurn(userId, personaId, sessionId, message, parsed.reply());
            }
        });
        log.info("사무실 수다 user={} persona={} session={} in={} out={} costUsd={} 키 출처={} mood={} suggest={}",
                userId, personaId, sessionId, completion.inputTokens(), completion.outputTokens(), cost,
                credential.source(), parsed.mood(), parsed.suggest());
        if (parsed.reply().isEmpty()) {
            throw new ServiceUnavailableException("지금은 대화할 수 없습니다 — AI 응답이 비어 있었습니다. 다시 말을 걸어 주세요");
        }
        return new ChatResponse(sessionId, parsed.reply(), parsed.mood(), parsed.suggest());
    }

    /**
     * 감사 행은 {@code persona_id}를 비운다 — 채우면 사무실 말풍선(lastActivity)·페르소나 활동 목록에 "누가 수다를 떨었다"가 모두에게
     * 보인다(사적 대화). 페르소나는 summary 메타로만 남긴다.
     */
    private static String meta(long personaId, String sessionId, ResolvedCredential credential) {
        return "personaId=" + personaId + " session=" + sessionId + " credential=" + credential.source();
    }

    private BigDecimal costOf(AnthropicChatClient.Completion c) {
        BigDecimal in = BigDecimal.valueOf(c.inputTokens()).multiply(properties.inputUsdPerMtok());
        BigDecimal out = BigDecimal.valueOf(c.outputTokens()).multiply(properties.outputUsdPerMtok());
        return in.add(out).divide(MILLION, 4, RoundingMode.UP);
    }

    private static String sessionIdOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "c-" + UUID.randomUUID();
        }
        String id = raw.strip();
        if (!SESSION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("sessionId 형식이 올바르지 않습니다");
        }
        return id;
    }
}
