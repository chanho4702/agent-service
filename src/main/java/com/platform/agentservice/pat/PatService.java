package com.platform.agentservice.pat;

import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PAT(개인 접근 토큰) 발급·조회·철회 + {@code /api/agent/mcp} 필터가 쓰는 검증.
 *
 * <p>평문 토큰은 {@link #issue}의 반환값에만 존재한다 — 저장은 SHA-256 해시(64 hex)로만
 * 하므로 이후로는 절대 재조회할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatService {

    private static final String TOKEN_PREFIX = "agp_";
    private static final String RUNNER_TOKEN_PREFIX = "agr_";
    /** lastUsedAt은 호출마다 쓰지 않고 이 창을 넘겼을 때만 갱신한다(쓰기 폭주 방지). */
    private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

    private final PatTokenRepository patTokenRepository;
    private final PersonaRepository personaRepository;

    /** 사람용 PAT 만료 기본(D-P4-3b) — 요청이 기간을 주지 않으면 90일. */
    public static final int DEFAULT_EXPIRY_DAYS = 90;
    public static final int MAX_EXPIRY_DAYS = 365;
    /** 만료 임박 경고 창 — 이 안으로 들어온 토큰은 목록에 {@code expiringSoon}, 발급자에게 메일 1회. */
    public static final int EXPIRY_WARN_DAYS = 7;
    static final String NO_EXPIRY_GLOBAL_ONLY = "무기한 토큰은 전역 관리자만 발급할 수 있습니다";

    /**
     * 원시 발급 — {@code expiresInDays}가 null이면 만료 없음. run 토큰({@code RunTokenService} — run 종료 시 명시적으로 철회)과
     * 서비스 내부 경로 전용이다. 사람이 부르는 {@code POST /api/agent/tokens}는 {@link #issueForHuman}(만료 정책)을 거친다.
     */
    @Transactional
    public PatCreatedResponse issue(PatCreateRequest request, long ownerMemberId) {
        Instant expiresAt = request.expiresInDays() == null
                ? null
                : Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS);
        return save(request, ownerMemberId, null, expiresAt);
    }

    /**
     * 사람용 PAT 발급(D-P4-3b) — 만료 기본 {@value #DEFAULT_EXPIRY_DAYS}일, 요청은 1~{@value #MAX_EXPIRY_DAYS}일. 무기한은 전역
     * 관리자가 {@code noExpiry=true}로 명시할 때만(아니면 403), 기간과 함께 주면 400. 발급자 메일(JWT email 클레임)을 남겨 만료 임박
     * 알림을 그 사람에게 보낸다.
     */
    @Transactional
    public PatCreatedResponse issueForHuman(PatCreateRequest request, long ownerMemberId, String ownerEmail,
                                            boolean globalAdmin) {
        boolean noExpiry = Boolean.TRUE.equals(request.noExpiry());
        Integer days = request.expiresInDays();
        if (noExpiry && days != null) {
            throw new IllegalArgumentException("noExpiry와 expiresInDays는 함께 줄 수 없습니다");
        }
        if (days != null && (days < 1 || days > MAX_EXPIRY_DAYS)) {
            throw new IllegalArgumentException("expiresInDays는 1~" + MAX_EXPIRY_DAYS + " 사이여야 합니다");
        }
        if (noExpiry && !globalAdmin) {
            throw new ForbiddenException(NO_EXPIRY_GLOBAL_ONLY);
        }
        Instant expiresAt = noExpiry ? null
                : Instant.now().plus(days == null ? DEFAULT_EXPIRY_DAYS : days, ChronoUnit.DAYS);
        return save(request, ownerMemberId, ownerEmail, expiresAt);
    }

    private PatCreatedResponse save(PatCreateRequest request, long ownerMemberId, String ownerEmail, Instant expiresAt) {
        Persona persona = personaRepository.findBySlug(request.personaSlug())
                .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + request.personaSlug()));
        if (!persona.isActive()) {
            throw new ConflictException("비활성 페르소나에는 토큰을 발급할 수 없습니다: " + request.personaSlug());
        }

        String rawToken = TokenHashing.generate(TOKEN_PREFIX);
        PatToken saved = patTokenRepository.save(PatToken.of(TokenHashing.sha256Hex(rawToken), request.label(),
                ownerMemberId, persona.getId(), expiresAt, normalizedEmail(ownerEmail)));

        return new PatCreatedResponse(rawToken, saved.getId(), saved.getLabel(), persona.getSlug(), saved.getExpiresAt());
    }

    private static String normalizedEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 200 || !email.contains("@")) {
            return null;
        }
        return email.trim();
    }

    /** projectId가 있으면 그 프로젝트 소속 페르소나의 토큰만(P3f — 공용 페르소나 토큰은 빠진다). 권한 판정은 컨트롤러 몫. */
    @Transactional(readOnly = true)
    public List<PatSummaryResponse> list(Long projectId) {
        Instant now = Instant.now();
        Map<Long, Persona> personas = personaRepository.findAll().stream()
                .collect(Collectors.toMap(Persona::getId, Function.identity()));
        return patTokenRepository.findAll().stream()
                .filter(t -> projectId == null || Optional.ofNullable(personas.get(t.getPersonaId()))
                        .map(p -> projectId.equals(p.getProjectId())).orElse(false))
                .map(t -> new PatSummaryResponse(
                        t.getId(),
                        t.getLabel(),
                        Optional.ofNullable(personas.get(t.getPersonaId())).map(Persona::getSlug).orElse(null),
                        t.getCreatedAt(),
                        t.getExpiresAt(),
                        t.getLastUsedAt(),
                        t.isRevoked(),
                        t.isRunToken() ? PatSummaryResponse.KIND_RUN : PatSummaryResponse.KIND_HUMAN,
                        !t.isRunToken() && t.getExpiresAt() == null,
                        !t.isRevoked() && t.getExpiresAt() != null && !t.isExpired(now)
                                && t.getExpiresAt().isBefore(now.plus(EXPIRY_WARN_DAYS, ChronoUnit.DAYS))))
                .toList();
    }

    /** 멱등 — 없는 id나 이미 철회된 토큰이어도 조용히 끝난다(컨트롤러는 항상 204). */
    @Transactional
    public void revoke(long id) {
        patTokenRepository.findById(id).ifPresent(t -> t.revoke(Instant.now()));
    }

    /**
     * PatAuthFilter 전용. 유효(미철회·미만료·존재·페르소나 활성)하면 lastUsedAt을 스로틀 갱신하고
     * {@link PatPrincipal}을 반환한다. 그 외에는 {@link Optional#empty()} — 필터가 401로 끝낸다.
     *
     * <p>거부 사유는 warn으로 남긴다(AGP-23 — 401만 보고는 만료·철회·오타·비활성을 구분할 수 없다).
     * 토큰 원문은 절대 싣지 않는다: 해시 불일치는 앞 8자(접두 + 4자)만, 나머지는 토큰 id만 쓴다.
     *
     * <p><b>비활성 페르소나(AGP-29)</b>: 사람용 PAT은 거부한다 — 이게 없으면 비활성화가 장식이다.
     * run 토큰({@link PatToken#isRunToken()})은 예외로 통과시킨다: 비활성화는 "새 인증"만 막고, 이미
     * 도는 워커는 자연 종료되게 둔다(즉시 멈춰야 하면 킬 스위치·run 취소가 그 수단이다). 새 run 토큰은
     * {@link #issue}가 비활성 페르소나에 발급을 거부하므로 새 run은 시작되지 않는다.
     */
    @Transactional
    public Optional<PatPrincipal> validate(String rawToken) {
        if (rawToken != null && rawToken.startsWith(RUNNER_TOKEN_PREFIX)) {
            // 러너 토큰은 MCP·도구 경로에 쓸 수 없다(D-P4-3b) — 필터가 먼저 끊지만 이 메서드를 다른 곳에서 불러도 같게.
            log.warn("PAT 거부 — 러너 토큰(agr_)은 직원 토큰 경로에 쓸 수 없습니다");
            return Optional.empty();
        }
        Optional<PatToken> found = patTokenRepository.findByTokenHash(sha256Hex(rawToken));
        if (found.isEmpty()) {
            log.warn("PAT 거부 — 일치하는 토큰 없음(해시 불일치): prefix={}", safePrefix(rawToken));
            return Optional.empty();
        }
        PatToken token = found.get();
        if (token.isRevoked()) {
            log.warn("PAT 거부 — 철회된 토큰: id={}", token.getId());
            return Optional.empty();
        }
        if (token.isExpired(Instant.now())) {
            log.warn("PAT 거부 — 만료된 토큰: id={} expiresAt={}", token.getId(), token.getExpiresAt());
            return Optional.empty();
        }
        Optional<Persona> persona = personaRepository.findById(token.getPersonaId());
        if (persona.isEmpty()) {
            log.warn("PAT 거부 — 페르소나 없음: id={} personaId={}", token.getId(), token.getPersonaId());
            return Optional.empty();
        }
        if (!persona.get().isActive() && !token.isRunToken()) {
            log.warn("PAT 거부 — 비활성 페르소나: id={} persona={}", token.getId(), persona.get().getSlug());
            return Optional.empty();
        }
        touchIfStale(token);
        return Optional.of(new PatPrincipal(token.getOwnerMemberId(), token.getPersonaId(), persona.get().getMemberId(),
                token.isRunToken(), token.runIdFromLabel()));
    }

    private static String safePrefix(String rawToken) {
        if (rawToken == null) {
            return "";
        }
        return rawToken.length() <= 8 ? "(짧음)" : rawToken.substring(0, 8) + "…";
    }

    private void touchIfStale(PatToken token) {
        Instant now = Instant.now();
        Instant lastUsed = token.getLastUsedAt();
        if (lastUsed == null || Duration.between(lastUsed, now).compareTo(LAST_USED_THROTTLE) > 0) {
            token.touch(now);
        }
    }

    static String sha256Hex(String value) {
        return TokenHashing.sha256Hex(value);
    }
}
