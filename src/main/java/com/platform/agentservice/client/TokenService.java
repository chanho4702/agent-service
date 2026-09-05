package com.platform.agentservice.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 페르소나 서비스 토큰 캐시. 만료 100초 전에 갱신한다 — 캐시 엔트리 자체를
 * {@code (expiresInSeconds - 100)}초 뒤에 만료시켜서, 그 시점 이후 첫 호출이 자동으로
 * 재발급을 트리거하게 한다(별도 스케줄러 없이 지연(lazy) 갱신).
 */
@Component
public class TokenService {

    private static final long REFRESH_MARGIN_SECONDS = 100;

    private final AuthTokenClient authTokenClient;
    private final Cache<Long, CacheEntry> cache;

    public TokenService(AuthTokenClient authTokenClient) {
        this.authTokenClient = authTokenClient;
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<Long, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(Long key, CacheEntry value, long currentTime) {
                        long ttl = Math.max(0, value.expiresInSeconds() - REFRESH_MARGIN_SECONDS);
                        return TimeUnit.SECONDS.toNanos(ttl);
                    }

                    @Override
                    public long expireAfterUpdate(Long key, CacheEntry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(Long key, CacheEntry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    private record CacheEntry(String accessToken, long expiresInSeconds) {}

    /** {@code "Bearer <토큰>"} 형태로 반환 — 그대로 Authorization 헤더 값으로 쓸 수 있다. */
    public String bearerFor(long personaMemberId) {
        CacheEntry entry = cache.get(personaMemberId, id -> {
            TokenResponse response = authTokenClient.mint(id);
            return new CacheEntry(response.accessToken(), response.expiresInSeconds());
        });
        return "Bearer " + entry.accessToken();
    }
}
