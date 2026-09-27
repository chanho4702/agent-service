package com.platform.agentservice.authz;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.Action;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 진짜 스텁을 in-process org 대역에 붙여 요청 모양·캐시·장애 처리(fail-closed, 비캐시)를 본다. */
class GrpcPermissionClientTest {

    private static final class FakeOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final List<CheckPermissionRequest> seen = new ArrayList<>();
        Status failWith;
        boolean allow = true;

        @Override
        public void checkPermission(CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
            seen.add(request);
            if (failWith != null) {
                observer.onError(failWith.asRuntimeException());
                return;
            }
            observer.onNext(CheckPermissionResponse.newBuilder().setAllowed(allow)
                    .setDeniedReason(allow ? "" : "NO_GRANT").build());
            observer.onCompleted();
        }
    }

    FakeOrg org;
    Server server;
    ManagedChannel channel;
    GrpcPermissionClient client;

    @BeforeEach
    void setUp() throws IOException {
        org = new FakeOrg();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(org).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel), 2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void ADMIN_action으로_묻고_정상_판정은_캐시한다() {
        assertThat(client.checkAdmin(2L, ResourceType.PROJECT, "7").allowed()).isTrue();
        assertThat(client.checkAdmin(2L, ResourceType.PROJECT, "7").allowed()).isTrue();

        assertThat(org.seen).hasSize(1);
        CheckPermissionRequest req = org.seen.get(0);
        assertThat(req.getUserId()).isEqualTo(2L);
        assertThat(req.getResourceType()).isEqualTo(ResourceType.PROJECT);
        assertThat(req.getResourceId()).isEqualTo("7");
        assertThat(req.getAction()).isEqualTo(Action.ADMIN);
    }

    @Test
    void 자원이_다르면_캐시가_섞이지_않는다() {
        client.checkAdmin(2L, ResourceType.PROJECT, "7");
        client.checkAdmin(2L, ResourceType.SPACE, "7");

        assertThat(org.seen).hasSize(2);
    }

    @Test
    void 거부는_사유를_싣는다() {
        org.allow = false;

        PermissionDecision decision = client.checkAdmin(2L, ResourceType.PROJECT, "7");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.deniedReason()).isEqualTo("NO_GRANT");
    }

    /** 가용성 장애는 503(alm 관례). 장애 결과를 캐시하면 org가 살아난 뒤에도 30초간 관리자가 막힌다. */
    @Test
    void org_가용성_장애는_503이고_캐시하지_않는다() {
        org.failWith = Status.UNAVAILABLE;
        assertThatThrownBy(() -> client.checkAdmin(2L, ResourceType.PROJECT, "7"))
                .isInstanceOf(ServiceUnavailableException.class);

        org.failWith = null;
        assertThat(client.checkAdmin(2L, ResourceType.PROJECT, "7").allowed()).isTrue();
        assertThat(org.seen).hasSize(2);
    }

    @Test
    void 데드라인_초과도_503이다() {
        org.failWith = Status.DEADLINE_EXCEEDED;

        assertThatThrownBy(() -> client.checkAdmin(2L, ResourceType.PROJECT, "7"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 가용성_외_gRPC_오류는_거부이고_캐시하지_않는다() {
        org.failWith = Status.INTERNAL;
        assertThat(client.checkAdmin(2L, ResourceType.PROJECT, "7").deniedReason()).isEqualTo(PermissionDecision.ORG_FAILURE);

        org.failWith = null;
        assertThat(client.checkAdmin(2L, ResourceType.PROJECT, "7").allowed()).isTrue();
    }
}
