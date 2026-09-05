package com.platform.agentservice.client;

/** auth-server {@code POST /internal/service-tokens} 응답. */
public record TokenResponse(String accessToken, long expiresInSeconds) {}
