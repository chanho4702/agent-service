package com.platform.agentservice.authz;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.Action;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** alm {@code GrpcPermissionClient}와 같은 캐시·데드라인·장애 구분 정책. 다른 점은 장애 결과를 캐시하지 않는 것 하나 — 아래 {@link #checkAdmin}. */
@Slf4j
public class GrpcPermissionClient implements PermissionClient {

    private record CacheKey(long userId, ResourceType type, String resourceId) {}

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    // alm과 같은 30초 — grant 회수·계정 정지가 최대 30초 늦게 반영되는 대가로 매 요청 gRPC 왕복을 피한다.
    private final Cache<CacheKey, PermissionDecision> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();
    private final long deadlineSeconds;

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub, long deadlineSeconds) {
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
    }

    /**
     * alm과 같은 장애 구분: 가용성 장애(UNAVAILABLE·DEADLINE_EXCEEDED)는 503 — "권한 없음"이 아니라 "지금 판정 불가"다.
     * 그 밖의 gRPC 오류는 거부(fail-closed). 어느 쪽이든 <b>캐시하지 않는다</b> — 장애 결과를 30초 붙들면 org가 살아난 뒤에도
     * 관리자가 계속 막힌다(alm은 fail-closed 거부를 캐시한다 — 여기서는 의도적으로 다르다). 정상 판정만 캐시한다.
     */
    @Override
    public PermissionDecision checkAdmin(long userId, ResourceType type, String resourceId) {
        CacheKey key = new CacheKey(userId, type, resourceId == null ? "" : resourceId);
        PermissionDecision cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        try {
            CheckPermissionResponse response = stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                    .checkPermission(CheckPermissionRequest.newBuilder()
                            .setUserId(userId)
                            .setResourceType(type)
                            .setResourceId(key.resourceId())
                            .setAction(Action.ADMIN)
                            .build());
            PermissionDecision decision = response.getAllowed()
                    ? PermissionDecision.allow()
                    : PermissionDecision.deny(response.getDeniedReason());
            cache.put(key, decision);
            return decision;
        } catch (Exception e) {
            if (isUnavailable(e)) {
                log.error("권한 서비스 불가 — 503 전파: user={} resource={}/{}", userId, type, key.resourceId(), e);
                throw new ServiceUnavailableException("권한 서비스에 연결할 수 없어 거부했습니다 — 잠시 후 다시 시도하세요", e);
            }
            log.warn("권한 조회 실패 — fail-closed 거부: user={} resource={}/{}", userId, type, key.resourceId(), e);
            return PermissionDecision.orgFailure();
        }
    }

    private static boolean isUnavailable(Throwable e) {
        if (e instanceof StatusRuntimeException status) {
            Status.Code code = status.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }
}
