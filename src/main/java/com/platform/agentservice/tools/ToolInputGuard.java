package com.platform.agentservice.tools;

import com.platform.common.error.ForbiddenException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 입력 인코딩 방어(AGP-38) — 모든 도구 호출이 지나는 단일 지점이다. {@code @McpTool} 스캔이 만든
 * {@code List<SyncToolSpecification>} 빈(서버에 주입되는 도구 목록 그 자체)의 각 핸들러를 감싸, 인자에
 * 깨진 서로게이트·제어문자(개행·탭·CR 제외)·치환 문자(U+FFFD)가 있으면 도구를 실행하지 않고 plain-text 오류로
 * 거부한다. 도구마다 검사를 흩뿌리지 않기 위해서다 — 새 도구도 자동으로 덮인다.
 *
 * <p>비UTF-8 바이트는 여기까지 바이트로 오지 않는다: 전송 계층이 본문을 UTF-8로 디코드하면서 깨진 바이트를
 * U+FFFD로 바꾸거나 JSON 파싱 오류로 먼저 끊는다. 그래서 U+FFFD가 비UTF-8 입력의 유일한 흔적이다(정상 문서에
 * 이 문자가 들어올 일은 사실상 없어 오탐 비용보다 깨진 본문이 ALM·위키에 영구히 박히는 비용이 크다).
 * 깨진 서로게이트는 JSON {@code \ud800} 이스케이프로 들어온다 — PostgreSQL이 저장을 거부하거나 화면에서 깨진다.
 *
 * <p>거부도 감사 행(ERROR)을 남긴다({@link Audited#run}) — summary에는 파라미터 이름만 싣고 값은 싣지 않는다.
 * {@link Audited}는 JPA 리포지토리를 끌고 오므로 BeanPostProcessor 초기화 시점에 당기지 않게 지연 조회한다.
 */
@Component
public class ToolInputGuard implements BeanPostProcessor {

    private final ObjectProvider<Audited> audited;

    public ToolInputGuard(ObjectProvider<Audited> audited) {
        this.audited = audited;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return list.stream()
                    .map(McpServerFeatures.SyncToolSpecification.class::cast)
                    .map(this::guard)
                    .toList();
        }
        return bean;
    }

    McpServerFeatures.SyncToolSpecification guard(McpServerFeatures.SyncToolSpecification spec) {
        String toolName = spec.tool().name();
        return new McpServerFeatures.SyncToolSpecification(spec.tool(), (exchange, request) -> {
            Violation violation = findViolation(request.arguments());
            if (violation == null) {
                return spec.callHandler().apply(exchange, request);
            }
            return McpSchema.CallToolResult.builder().addTextContent(reject(toolName, violation)).build();
        });
    }

    private String reject(String toolName, Violation violation) {
        try {
            return audited.getObject().run(toolName, "입력 거부: " + violation.param(), () -> {
                throw new IllegalArgumentException(violation.message());
            });
        } catch (ForbiddenException e) {
            // PAT principal이 없는 경로(체인 오배선) — 감사 없이 거부 문구만 돌려준다.
            return "오류: " + violation.message();
        }
    }

    record Violation(String param, String message) {
    }

    /** 첫 위반 하나만 돌려준다 — 없으면 null. 중첩 목록·객체 인자는 경로(예: {@code labels[1]})로 가리킨다. */
    static Violation findViolation(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Violation v = inspect(entry.getKey(), entry.getValue());
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Violation inspect(String path, Object value) {
        if (value instanceof CharSequence text) {
            String reason = invalidReason(text);
            return reason == null ? null
                    : new Violation(path, "입력 인코딩 거부 — 파라미터 '" + path + "': " + reason);
        }
        if (value instanceof Collection<?> items) {
            int i = 0;
            for (Object item : items) {
                Violation v = inspect(path + "[" + i++ + "]", item);
                if (v != null) {
                    return v;
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Violation v = inspect(path + "." + entry.getKey(), entry.getValue());
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    static String invalidReason(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return "깨진 서로게이트 " + codePoint(c) + " (" + (i + 1) + "번째 문자)";
            }
            if (Character.isLowSurrogate(c)) {
                return "깨진 서로게이트 " + codePoint(c) + " (" + (i + 1) + "번째 문자)";
            }
            if (c == '�') {
                return "인코딩이 깨진 문자 U+FFFD (" + (i + 1) + "번째 문자) — UTF-8이 아닌 입력으로 보입니다";
            }
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                return "제어문자 " + codePoint(c) + " (" + (i + 1) + "번째 문자)";
            }
        }
        return null;
    }

    private static String codePoint(char c) {
        return String.format("U+%04X", (int) c);
    }
}
