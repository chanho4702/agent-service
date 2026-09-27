package com.platform.agentservice.authz;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

/** alm {@code GrpcPermissionClient}와 같은 캐시·데드라인 정책. 다른 점은 장애 처리 하나 — 아래 {@link #checkAdmin}. */
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
     * 판정 실패는 거부로 닫되(D-P3f-2) <b>캐시하지 않는다</b> — 장애 거부를 30초 붙들면 org가 살아난 뒤에도
     * 관리자가 계속 막힌다. org가 정상적으로 내린 판정만 캐시한다.
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
                log.error("권한 서비스 불가 — fail-closed 거부: user={} resource={}/{}", userId, type, key.resourceId(), e);
            } else {
                log.warn("권한 조회 실패 — fail-closed 거부: user={} resource={}/{}", userId, type, key.resourceId(), e);
            }
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
