package com.platform.agentservice.pat;

import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

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
    private static final int TOKEN_RANDOM_BYTES = 32;
    /** lastUsedAt은 호출마다 쓰지 않고 이 창을 넘겼을 때만 갱신한다(쓰기 폭주 방지). */
    private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

    private final PatTokenRepository patTokenRepository;
    private final PersonaRepository personaRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public PatCreatedResponse issue(PatCreateRequest request, long ownerMemberId) {
        Persona persona = personaRepository.findBySlug(request.personaSlug())
                .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + request.personaSlug()));
        if (!persona.isActive()) {
            throw new ConflictException("비활성 페르소나에는 토큰을 발급할 수 없습니다: " + request.personaSlug());
        }

        String rawToken = generateToken();
        Instant expiresAt = request.expiresInDays() == null
                ? null
                : Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS);

        PatToken saved = patTokenRepository.save(
                PatToken.of(sha256Hex(rawToken), request.label(), ownerMemberId, persona.getId(), expiresAt));

        return new PatCreatedResponse(rawToken, saved.getId(), saved.getLabel(), persona.getSlug());
    }

    @Transactional(readOnly = true)
    public List<PatSummaryResponse> list() {
        return patTokenRepository.findAll().stream()
                .map(t -> new PatSummaryResponse(
                        t.getId(),
                        t.getLabel(),
                        personaSlugOf(t.getPersonaId()),
                        t.getCreatedAt(),
                        t.getExpiresAt(),
                        t.getLastUsedAt(),
                        t.isRevoked()))
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
        return Optional.of(new PatPrincipal(token.getOwnerMemberId(), token.getPersonaId(), persona.get().getMemberId()));
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

    private String personaSlugOf(Long personaId) {
        return personaRepository.findById(personaId).map(Persona::getSlug).orElse(null);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
