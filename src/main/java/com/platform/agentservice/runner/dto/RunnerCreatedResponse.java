package com.platform.agentservice.runner.dto;

import com.platform.agentservice.runner.RunnerKind;

import java.time.Instant;

/** 발급 응답 — {@code token}(agr_)은 이 응답에만 담긴다(해시만 저장, 재조회 불가). */
public record RunnerCreatedResponse(long id, RunnerKind kind, String name, Long projectId, String token,
                                    String tokenPrefix, Instant createdAt) {

    @Override
    public String toString() {
        return "RunnerCreatedResponse{id=" + id + ", kind=" + kind + ", name=" + name + ", projectId=" + projectId
                + ", tokenPrefix=" + tokenPrefix + "}";
    }
}
