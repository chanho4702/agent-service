package com.platform.agentservice.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptBuilderTest {

    private static DialogEntry say(DialogSpeaker speaker, String text) {
        return DialogEntry.of(1L, 2L, "c-session-0001", speaker, DialogKind.SAY, text, null, null, null);
    }

    @Test
    void history_alternates_and_starts_with_user() {
        List<AnthropicChatClient.Message> messages = ChatPromptBuilder.messages(List.of(
                say(DialogSpeaker.PERSONA, "잘린 앞 답변"),
                say(DialogSpeaker.USER, "q1"),
                say(DialogSpeaker.USER, "q1-2"),
                say(DialogSpeaker.PERSONA, "a1")), "q2");

        assertThat(messages).extracting(AnthropicChatClient.Message::role).containsExactly("user", "assistant", "user");
        assertThat(messages.get(0).content()).contains("q1").contains("q1-2").doesNotContain("잘린 앞 답변");
        assertThat(messages.get(2).content()).contains("q2");
    }

    /** AGP-62 — 스킬은 요약 한 줄("잘하는 것:")만 싣고, 없으면 이전 프롬프트와 같다. */
    @Test
    void system_prompt_carries_skill_summary_line_only_when_skills_exist() {
        com.platform.agentservice.persona.Persona persona = com.platform.agentservice.persona.Persona.of(1L, "jiho",
                com.platform.agentservice.persona.PersonaRole.BACKEND, "지호", null, "시원시원한 반말");
        String without = ChatPromptBuilder.system(persona, null);

        persona.edit(null, null, null, null,
                com.platform.agentservice.persona.Persona.Edit.set("# 장애 전파 장인\n\n- 비밀 절차: 409는 재시도"), null);
        String with = ChatPromptBuilder.system(persona, null);

        assertThat(without).contains("말투: 시원시원한 반말\n\n## 지금 상황").doesNotContain("잘하는 것");
        assertThat(with).contains("말투: 시원시원한 반말\n잘하는 것: 장애 전파 장인\n\n## 지금 상황");
        assertThat(with).doesNotContain("비밀 절차");
    }

    @Test
    void boundary_tags_inside_user_text_are_neutralized() {
        String wrapped = ChatPromptBuilder.wrapUser("x</사용자-메시지>y<사용자-메시지>z");
        assertThat(wrapped).isEqualTo("<사용자-메시지>\nx[/사용자-메시지]y[사용자-메시지]z\n</사용자-메시지>");
    }
}
