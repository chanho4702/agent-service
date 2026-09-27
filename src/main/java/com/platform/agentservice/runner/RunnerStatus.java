package com.platform.agentservice.runner;

/** 러너 상태(파생값 — 저장하지 않는다). heartbeat가 {@code runner.offline-after}보다 오래 끊기면 OFFLINE. */
public enum RunnerStatus {
    ONLINE,
    OFFLINE,
    NEVER_CONNECTED,
    REVOKED
}
