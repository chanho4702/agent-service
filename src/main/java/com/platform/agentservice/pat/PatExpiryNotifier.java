package com.platform.agentservice.pat;

import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 사람용 PAT 만료 임박 알림(D-P4-3b) — 하루 한 번({@code MaintenanceSchedulingConfig}) 7일 안에 만료되는 토큰을 찾아 발급자별로 메일
 * 한 통을 보내고 {@code expiry_warned_at}을 남긴다(같은 토큰에 매일 다시 보내지 않는다). run 토큰은 대상이 아니다(run 종료 시 철회).
 * 메일을 넘기지 못했으면(수신자·토큰 미설정·호출 실패) 표식하지 않아 다음 날 다시 시도한다.
 */
@Slf4j
@Service
public class PatExpiryNotifier {

    private final PatTokenRepository patTokenRepository;
    private final PersonaRepository personaRepository;
    private final AlertService alertService;
    private final Clock clock;

    @Autowired
    public PatExpiryNotifier(PatTokenRepository patTokenRepository, PersonaRepository personaRepository,
                             AlertService alertService) {
        this(patTokenRepository, personaRepository, alertService, Clock.systemUTC());
    }

    PatExpiryNotifier(PatTokenRepository patTokenRepository, PersonaRepository personaRepository,
                      AlertService alertService, Clock clock) {
        this.patTokenRepository = patTokenRepository;
        this.personaRepository = personaRepository;
        this.alertService = alertService;
        this.clock = clock;
    }

    /** @return 경고 표식을 남긴 토큰 수 */
    @Transactional
    public int warnExpiringSoon() {
        Instant now = clock.instant();
        List<PatToken> expiring = patTokenRepository
                .findByRevokedAtIsNullAndExpiryWarnedAtIsNullAndExpiresAtGreaterThanEqualAndExpiresAtLessThan(
                        now, now.plus(PatService.EXPIRY_WARN_DAYS, ChronoUnit.DAYS))
                .stream().filter(t -> !t.isRunToken()).toList();
        if (expiring.isEmpty()) {
            return 0;
        }
        Map<Long, String> slugs = personaRepository.findAll().stream()
                .collect(Collectors.toMap(Persona::getId, Persona::getSlug, (a, b) -> a));
        // 발급자(memberId + 메일) 단위로 묶는다 — 한 사람에게 토큰마다 메일이 가지 않게.
        Map<String, List<PatToken>> byOwner = expiring.stream().collect(Collectors.groupingBy(
                t -> t.getOwnerMemberId() + "|" + Objects.toString(t.getOwnerEmail(), ""), LinkedHashMap::new, Collectors.toList()));
        int warned = 0;
        for (List<PatToken> tokens : byOwner.values()) {
            PatToken first = tokens.get(0);
            List<AlertService.ExpiringToken> items = tokens.stream()
                    .map(t -> new AlertService.ExpiringToken(t.getId(), t.getLabel(), slugs.get(t.getPersonaId()), t.getExpiresAt()))
                    .toList();
            if (alertService.notifyTokensExpiring(first.getOwnerEmail(), first.getOwnerMemberId(), items)) {
                tokens.forEach(t -> t.markExpiryWarned(now));
                warned += tokens.size();
            }
        }
        log.info("PAT 만료 임박 점검 — 대상 {}건, 경고 표식 {}건", expiring.size(), warned);
        return warned;
    }
}
