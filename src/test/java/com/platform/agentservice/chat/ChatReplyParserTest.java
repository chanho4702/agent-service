package com.platform.agentservice.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatReplyParserTest {

    @Test
    void meta_line_is_split_off() {
        ChatReplyParser.Parsed p = ChatReplyParser.parse("좋아요!\n@@meta {\"mood\":\"happy\",\"suggest\":null}");
        assertThat(p.reply()).isEqualTo("좋아요!");
        assertThat(p.mood()).isEqualTo(ChatMood.HAPPY);
        assertThat(p.suggest()).isNull();
    }

    @Test
    void bare_json_last_line_is_accepted() {
        ChatReplyParser.Parsed p = ChatReplyParser.parse("음…\n{\"mood\":\"TROUBLED\",\"suggest\":\"DIRECTIVE\"}");
        assertThat(p.reply()).isEqualTo("음…");
        assertThat(p.mood()).isEqualTo(ChatMood.TROUBLED);
        assertThat(p.suggest()).isEqualTo("DIRECTIVE");
    }

    @Test
    void unknown_values_and_broken_json_fall_back_to_null() {
        assertThat(ChatReplyParser.parse("a\n@@meta {\"mood\":\"ANGRY\",\"suggest\":\"RUN\"}"))
                .satisfies(p -> {
                    assertThat(p.reply()).isEqualTo("a");
                    assertThat(p.mood()).isNull();
                    assertThat(p.suggest()).isNull();
                });
        assertThat(ChatReplyParser.parse("b\n@@meta not json")).satisfies(p -> {
            assertThat(p.reply()).isEqualTo("b");
            assertThat(p.mood()).isNull();
        });
        assertThat(ChatReplyParser.parse(null).reply()).isEmpty();
    }

    @Test
    void reply_is_capped_at_600_without_splitting_a_surrogate_pair() {
        String longText = "가".repeat(598) + "😀" + "나나나";
        String reply = ChatReplyParser.parse(longText).reply();
        assertThat(reply.length()).isLessThanOrEqualTo(600);
        assertThat(reply).endsWith("…");
        assertThat(Character.isHighSurrogate(reply.charAt(reply.length() - 2))).isFalse();
    }
}
