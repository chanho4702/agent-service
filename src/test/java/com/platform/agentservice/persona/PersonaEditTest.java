package com.platform.agentservice.persona;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PATCH /api/agent/personas/{id}}(AGP-62) 본문 계약 — 실제 {@link PersonaService}·DB로 부분 갱신·지움·검증 400을 본다.
 * 권한 판정은 {@link PersonaControllerTest}가 본다(여기서는 전역 관리자로만 부른다).
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaEditTest {

    private static final AtomicLong SEQ = new AtomicLong(96000);

    @Autowired WebApplicationContext context;
    @Autowired PersonaRepository personaRepository;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;
    Persona persona;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        long n = SEQ.incrementAndGet();
        persona = personaRepository.save(Persona.of(n, "edit-" + n, PersonaRole.BACKEND, "지호", "🔧", "반말", 7L));
    }

    private ResultActions patchBody(String json) throws Exception {
        return mvc.perform(patch("/api/agent/personas/" + persona.getId()).with(authentication(TestAuth.admin(1L, "Admin")))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Persona reload() {
        return personaRepository.findById(persona.getId()).orElseThrow();
    }

    @Test
    void partial_update_changes_only_given_fields_and_returns_new_fields() throws Exception {
        patchBody("""
                {"defaultModel":" claude-opus-5-5[1m] ","skills":"## 백엔드\\n- 테스트 먼저","avatarConfig":"{\\"v\\": 1, \\"hairStyle\\": \\"bob\\", \\"skinTone\\": 3}"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("지호"))
                .andExpect(jsonPath("$.emoji").value("🔧"))
                .andExpect(jsonPath("$.defaultModel").value("claude-opus-5-5[1m]"))
                .andExpect(jsonPath("$.skills").value("## 백엔드\n- 테스트 먼저"))
                .andExpect(jsonPath("$.avatarConfig").value("{\"v\":1,\"hairStyle\":\"bob\",\"skinTone\":3}"))
                .andExpect(jsonPath("$.projectId").value(7));

        Persona saved = reload();
        assertThat(saved.getVoicePrompt()).isEqualTo("반말");
        assertThat(saved.getDefaultModel()).isEqualTo("claude-opus-5-5[1m]");

        // null(JSON null)도 "그대로"다.
        patchBody("{\"name\":\"지호2\",\"skills\":null,\"avatarConfig\":null}").andExpect(status().isOk());
        assertThat(reload().getName()).isEqualTo("지호2");
        assertThat(reload().getSkills()).isEqualTo("## 백엔드\n- 테스트 먼저");
        assertThat(reload().getAvatarConfig()).isNotNull();
    }

    @Test
    void empty_string_clears_optional_fields() throws Exception {
        patchBody("{\"defaultModel\":\"claude-x\",\"skills\":\"s\",\"avatarConfig\":{\"v\":1}}").andExpect(status().isOk());

        patchBody("{\"emoji\":\"\",\"voicePrompt\":\"  \",\"defaultModel\":\"\",\"skills\":\"\",\"avatarConfig\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emoji").doesNotExist())
                .andExpect(jsonPath("$.defaultModel").doesNotExist());

        Persona saved = reload();
        assertThat(saved.getEmoji()).isNull();
        assertThat(saved.getVoicePrompt()).isNull();
        assertThat(saved.getDefaultModel()).isNull();
        assertThat(saved.getSkills()).isNull();
        assertThat(saved.getAvatarConfig()).isNull();
    }

    @Test
    void name_cannot_be_cleared() throws Exception {
        patchBody("{\"name\":\"  \",\"skills\":\"바뀌면 안 됨\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("name은 비울 수 없습니다"));
        assertThat(reload().getName()).isEqualTo("지호");
        assertThat(reload().getSkills()).isNull(); // 한 필드가 400이면 아무것도 바뀌지 않는다
    }

    @Test
    void avatar_config_shape_is_validated() throws Exception {
        String[] rejected = {
                "\"{\\\"v\\\":1,\\\"hat\\\":\\\"red\\\"}\"",        // 화이트리스트 밖 키
                "{\"v\":1,\"hairStyle\":{\"id\":\"bob\"}}",           // 중첩 객체
                "{\"v\":1,\"accessory\":[\"glasses\"]}",              // 배열
                "{\"v\":1,\"accessory\":true}",                       // 불리언
                "{\"v\":1,\"accessory\":null}",                       // null 값
                "\"not json\"",                                       // 비JSON 문자열
                "\"[1,2]\"",                                          // 객체 아님
                "42",                                                 // 객체 아님
                "{\"v\":1,\"hairStyle\":\"" + "x".repeat(1100) + "\"}" // 1KB 초과
        };
        for (String value : rejected) {
            patchBody("{\"avatarConfig\":" + value + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("avatarConfig")));
        }
        assertThat(reload().getAvatarConfig()).isNull();

        // 오류 문구에 입력값(모르는 키 이름)을 싣지 않는다.
        patchBody("{\"avatarConfig\":{\"secretKeyName\":\"x\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("secretKeyName"))));
    }

    @Test
    void skills_over_limit_and_bad_model_and_bad_encoding_are_400() throws Exception {
        patchBody("{\"skills\":\"" + "가".repeat(PersonaSkills.MAX_LENGTH + 1) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("skills는 8000자 이하여야 합니다"));
        patchBody("{\"skills\":\"" + "가".repeat(PersonaSkills.MAX_LENGTH) + "\"}").andExpect(status().isOk());

        patchBody("{\"defaultModel\":\"claude opus\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("defaultModel")));
        patchBody("{\"defaultModel\":\"" + "m".repeat(61) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("defaultModel은 60자 이하여야 합니다"));

        patchBody("{\"skills\":\"ok\\u0007bell\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("skills 입력 인코딩 거부: 제어문자 U+0007")));
        patchBody("{\"voicePrompt\":\"broken \\ud800 surrogate\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("voicePrompt 입력 인코딩 거부")));
    }

    @Test
    void list_carries_the_new_fields() throws Exception {
        patchBody("{\"defaultModel\":\"claude-sonnet-5\",\"avatarConfig\":\"{\\\"v\\\":2}\"}").andExpect(status().isOk());

        mvc.perform(get("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + persona.getId() + ")].defaultModel").value("claude-sonnet-5"))
                .andExpect(jsonPath("$[?(@.id == " + persona.getId() + ")].avatarConfig").value("{\"v\":2}"));
    }
}
