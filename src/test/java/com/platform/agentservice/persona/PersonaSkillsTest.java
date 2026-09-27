package com.platform.agentservice.persona;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaSkillsTest {

    @Test
    void summary_is_first_non_blank_line_without_markdown_marks() {
        assertThat(PersonaSkills.summary("\n\n## 백엔드 장인\n- 테스트 먼저")).isEqualTo("백엔드 장인");
        assertThat(PersonaSkills.summary("  - > 인용된 항목  ")).isEqualTo("인용된 항목");
        assertThat(PersonaSkills.summary("###\n---\n본문")).isEqualTo("본문");
        assertThat(PersonaSkills.summary(null)).isNull();
        assertThat(PersonaSkills.summary(" \n# \n")).isNull();
    }

    @Test
    void summary_is_cut_to_120_chars_without_splitting_a_surrogate_pair() {
        assertThat(PersonaSkills.summary("가".repeat(200))).isEqualTo("가".repeat(120) + "…");
        String emojiAtBoundary = "a".repeat(119) + "😀" + "tail";
        assertThat(PersonaSkills.summary(emojiAtBoundary)).isEqualTo("a".repeat(119) + "…");
    }

    @Test
    void dir_name_only_for_path_safe_slugs() {
        assertThat(PersonaSkills.dirName("jiho-2")).isEqualTo("persona-jiho-2");
        assertThat(PersonaSkills.dirName("../x")).isNull();
        assertThat(PersonaSkills.dirName("Jiho")).isNull();
        assertThat(PersonaSkills.dirName(null)).isNull();
    }
}
