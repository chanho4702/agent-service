package com.platform.agentservice.chat;

import com.platform.agentservice.chat.dto.ChatRequest;
import com.platform.agentservice.chat.dto.ChatResponse;
import com.platform.agentservice.chat.dto.DialogAppendRequest;
import com.platform.agentservice.chat.dto.DialogListResponse;
import com.platform.agentservice.chat.dto.DialogSaveResponse;
import com.platform.common.error.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * AI 사무실 1:1 대화(P3g, AGP-65) — 인증 사용자 누구나({@code SecurityConfig}의 {@code anyRequest().authenticated()}), AI 팀 관리 권한
 * (§7)은 보지 않는다. 대화 기록의 소유자는 JWT sub 하나 — 경로·본문으로 다른 사용자를 지정하는 입력 자체가 없다.
 */
@RestController
@RequestMapping("/api/agent/personas/{id}")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final DialogService dialogService;

    @PostMapping("/chat")
    public ChatResponse chat(@PathVariable long id, @RequestBody(required = false) ChatRequest request,
                             Authentication authentication) {
        return chatService.chat(userId(authentication), id, request);
    }

    @GetMapping("/dialog")
    public DialogListResponse dialog(@PathVariable long id, @RequestParam(required = false) String before,
                                     @RequestParam(required = false) String limit, Authentication authentication) {
        return dialogService.list(userId(authentication), id, before, parseLimit(limit));
    }

    @PostMapping("/dialog")
    @ResponseStatus(HttpStatus.CREATED)
    public DialogSaveResponse append(@PathVariable long id, @RequestBody(required = false) DialogAppendRequest request,
                                     Authentication authentication) {
        return new DialogSaveResponse(dialogService.append(userId(authentication), id, request));
    }

    private static Integer parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit은 숫자여야 합니다");
        }
    }

    private static long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("로그인한 사용자만 대화할 수 있습니다");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ForbiddenException("로그인한 사용자만 대화할 수 있습니다");
        }
    }
}
