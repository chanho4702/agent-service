package com.platform.agentservice.client.dto;

import java.util.List;
import java.util.Map;

/**
 * alm-backend {@code GET /api/alm/projects/{id}/settings}({@code ResolvedSettingsResponse})에서
 * {@code get_project_context}가 쓰는 필드만 옮겨왔다(S10) — 최상위의 {@code source}/{@code scheme}
 * (스킴 메타데이터)은 필요 없다, 실제로 유효한 값은 전부 {@code body}에 있다. alm-backend
 * {@code SettingsBody}는 응답 시 이미 정규화돼 있다(상태/전이/필드는 null 대신 빈 목록,
 * 우선순위는 미지정 시 5종 전부, defaultPriority는 미지정 시 "medium", fields는 항상 13종) —
 * 여기서는 그 정규화된 값을 그대로 받기만 한다.
 */
public record ProjectSettingsResponse(SettingsBody body) {

    public record SettingsBody(
            List<StatusEntry> statuses,
            List<TransitionEntry> transitions,
            List<String> enabledTypes,
            List<String> enabledPriorities,
            String defaultPriority,
            List<FieldConfigEntry> fields,
            Map<String, List<FieldConfigEntry>> fieldsByType
    ) {
    }

    /** {@code name/category/order/kind/color/icon}은 스킴 편집 UI 전용이라 컨텍스트 요약에는 id만 쓴다. */
    public record StatusEntry(String id) {
    }

    public record TransitionEntry(String id, String name, List<String> from, String to) {
    }

    public record FieldConfigEntry(String id, boolean visible, boolean required) {
    }
}
