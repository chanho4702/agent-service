package com.platform.agentservice.chat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRateLimiterTest {

    @Test
    void sliding_window_per_user() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-27T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatProperties(true, "m", 400, 2, Duration.ofMinutes(10),
                BigDecimal.ONE, BigDecimal.ONE, 10, 90, 500), clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        now.set(now.get().plusSeconds(60));
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).as("다른 사용자").isTrue();

        now.set(now.get().plusSeconds(9 * 60));          // 첫 시도가 정확히 10분 전 → 창 밖
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }
}
