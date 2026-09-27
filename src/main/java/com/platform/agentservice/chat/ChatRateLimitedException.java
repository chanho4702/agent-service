package com.platform.agentservice.chat;

/** 사용자당 수다 한도 초과 → 429 {@code {"error"}}({@link ChatExceptionHandler}). common-starter에 429 예외가 없어 여기 둔다. */
public class ChatRateLimitedException extends RuntimeException {
    public ChatRateLimitedException(String message) {
        super(message);
    }
}
