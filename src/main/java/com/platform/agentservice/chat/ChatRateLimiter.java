package com.platform.agentservice.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자당 수다 횟수 제한(P3g) — 인메모리 슬라이딩 창. <b>한계</b>: 인스턴스별로 따로 센다(여러 인스턴스를 띄우면 사용자 한 명이
 * 인스턴스 수 × 한도까지 보낼 수 있다)·재기동하면 초기화된다. 현재 agent-service는 단일 인스턴스 배포라 수용했다 — 확장 시 Redis로.
 * 판정을 통과한 시도 하나가 창의 한 칸을 쓴다(LLM 호출이 실패해도 돌려주지 않는다 — 실패 재시도 폭주도 같이 막는다).
 */
@Component
public class ChatRateLimiter {

    private final ChatProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimiter(ChatProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ChatRateLimiter(ChatProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 허용이면 한 칸을 쓰고 true, 창이 찼으면 false. */
    public boolean tryAcquire(long userId) {
        Instant now = clock.instant();
        Instant floor = now.minus(properties.rateWindow());
        Deque<Instant> window = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(floor)) {
                window.pollFirst();
            }
            if (window.size() >= properties.rateLimit()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
