package com.platform.agentservice.persona;

import java.util.regex.Pattern;

/**
 * 페르소나 스킬(AGP-62) 공용 규칙 — 요약 한 줄과 워크스페이스 스킬 디렉터리 이름. 워커 실체화(SKILL.md)·회의 참석자 소개·수다
 * 시스템 프롬프트가 같은 요약을 쓰도록 한 곳에 둔다.
 */
public final class PersonaSkills {

    /** 앱 상한(마크다운) — 워커 워크스페이스 파일이자 프롬프트 재료라 TEXT 컬럼이어도 묶는다. */
    public static final int MAX_LENGTH = 8000;
    public static final int SUMMARY_MAX = 120;
    /** 생성 스킬 디렉터리 접두사 — {@code .claude/skills/persona-<slug>}. */
    public static final String DIR_PREFIX = "persona-";
    /**
     * 경로 조각이 되는 slug 검증. 생성 API의 slug 규칙({@code PersonaCreateRequest})과 같지만, 그 규칙이 생기기 전 행이나 DB 직접
     * 수정분이 경로 탈출({@code ../})로 새지 않게 실체화 시점에 다시 본다.
     */
    private static final Pattern SAFE_SLUG = Pattern.compile("[a-z0-9-]{2,40}");
    private static final Pattern LEADING_MARKS = Pattern.compile("^[#>*+\\-\\s]+");

    private PersonaSkills() {
    }

    public static boolean hasSkills(String skills) {
        return skills != null && !skills.isBlank();
    }

    /** 스킬 디렉터리 이름({@code persona-<slug>}), slug가 경로 조각으로 안전하지 않으면 null. */
    public static String dirName(String slug) {
        return slug != null && SAFE_SLUG.matcher(slug).matches() ? DIR_PREFIX + slug : null;
    }

    /**
     * 첫 비어 있지 않은 줄에서 마크다운 머리 기호(#·-·*·>)를 떼고 {@value #SUMMARY_MAX}자로 자른 한 줄. 스킬이 없거나 기호뿐이면 null.
     */
    public static String summary(String skills) {
        if (!hasSkills(skills)) {
            return null;
        }
        for (String line : skills.split("\\R")) {
            String text = LEADING_MARKS.matcher(line).replaceFirst("").strip();
            if (!text.isEmpty()) {
                return cut(text);
            }
        }
        return null;
    }

    private static String cut(String text) {
        if (text.length() <= SUMMARY_MAX) {
            return text;
        }
        int end = SUMMARY_MAX;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
