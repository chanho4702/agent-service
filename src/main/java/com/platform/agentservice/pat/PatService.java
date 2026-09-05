package com.platform.agentservice.pat;

import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
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
     * PatAuthFilter 전용. 유효(미철회·미만료·존재)하면 lastUsedAt을 스로틀 갱신하고
     * {@link PatPrincipal}을 반환한다. 그 외에는 {@link Optional#empty()} — 필터가 401로 끝낸다.
     */
    @Transactional
    public Optional<PatPrincipal> validate(String rawToken) {
        return patTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .filter(t -> !t.isRevoked())
                .filter(t -> !t.isExpired(Instant.now()))
                .flatMap(this::toPrincipal);
    }

    private Optional<PatPrincipal> toPrincipal(PatToken token) {
        return personaRepository.findById(token.getPersonaId()).map(persona -> {
            touchIfStale(token);
            return new PatPrincipal(token.getOwnerMemberId(), token.getPersonaId(), persona.getMemberId());
        });
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
