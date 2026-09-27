package com.platform.agentservice.config;

import com.platform.agentservice.authz.GrpcPermissionClient;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** org PermissionService gRPC 채널 — alm·wiki와 같은 env(ORG_GRPC_HOST·ORG_GRPC_PORT·ORG_GRPC_DEADLINE_SECONDS). */
@Configuration
public class PermissionConfig {

    @Bean(destroyMethod = "shutdown")
    @Qualifier("orgChannel")
    ManagedChannel orgChannel(@Value("${platform.org-grpc.host}") String host,
                              @Value("${platform.org-grpc.port}") int port) {
        // alm 실측(2026-09-05): 첫 요청이 연결 수립에 데드라인을 넘기지 않게 시작 시 연결을 선점하고 keepalive로 유휴 끊김을 막는다.
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        channel.getState(true);
        return channel;
    }

    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(@Qualifier("orgChannel") ManagedChannel channel,
                                      @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel).withWaitForReady(), deadlineSeconds);
    }
}
