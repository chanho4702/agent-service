package com.platform.agentservice.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 아바타 설정(AGP-62) 형태 검증 — 시각 파츠 값 자체(어떤 머리 모양·색이 있는지)는 프론트 팔레트가 정본이라 서버는 형태만 본다:
 * JSON 객체, 최상위 키 화이트리스트, 값은 문자열·숫자만(중첩·배열·불리언·null 거부), 1KB 상한. 오류 문구에 입력값을 싣지 않는다.
 */
final class AvatarConfigValidator {

    static final int MAX_BYTES = 1024;
    static final Set<String> ALLOWED_KEYS = Set.of("v", "skinTone", "hairStyle", "hairColor", "shirtColor", "accessory");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AvatarConfigValidator() {
    }

    /**
     * 요청 값(JSON 문자열, 또는 요청 바인딩이 만든 Map)을 검증해 저장할 정규화 문자열(공백 없는 JSON)로 돌려준다. 호출자가
     * null·빈 문자열(지움)을 먼저 걸러낸다. 그 밖의 타입(숫자·배열·불리언)은 객체가 아니므로 거부한다.
     */
    static String normalize(Object input) {
        JsonNode node;
        if (input instanceof String text) {
            requireSize(text);
            try {
                node = MAPPER.readTree(text);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("avatarConfig는 JSON 객체 문자열이어야 합니다");
            }
        } else if (input instanceof Map<?, ?> map) {
            node = MAPPER.valueToTree(map);
        } else {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("avatarConfig는 JSON 객체여야 합니다");
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!ALLOWED_KEYS.contains(field.getKey())) {
                throw new IllegalArgumentException("avatarConfig에 허용되지 않은 키가 있습니다 — 허용: " + String.join(", ",
                        ALLOWED_KEYS.stream().sorted().toList()));
            }
            JsonNode value = field.getValue();
            if (!value.isTextual() && !value.isNumber()) {
                throw new IllegalArgumentException("avatarConfig 값은 문자열 또는 숫자여야 합니다: " + field.getKey());
            }
        }
        String normalized = node.toString();
        requireSize(normalized);
        return normalized;
    }

    private static void requireSize(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("avatarConfig는 " + MAX_BYTES + "바이트 이하여야 합니다");
        }
    }
}
