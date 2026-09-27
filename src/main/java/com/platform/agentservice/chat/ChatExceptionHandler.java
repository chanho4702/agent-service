package com.platform.agentservice.chat;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 429만 여기서 — 나머지 오류 계약({@code {"error": 메시지}})은 common-starter {@code PlatformApiExceptionHandler}가 지킨다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatExceptionHandler {

    @ExceptionHandler(ChatRateLimitedException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> rateLimited(ChatRateLimitedException e) {
        return Map.of("error", e.getMessage());
    }
}
