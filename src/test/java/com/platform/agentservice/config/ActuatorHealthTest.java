package com.platform.agentservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이 관리자 대시보드가 인증 없이 프로브하는 두 경로. 노출은 health·info뿐이다.
 * MCP 체인과 같은 함정(JWT 리소스서버가 붙은 체인은 Authorization 값을 무조건 디코드한다)을
 * 피했는지도 함께 본다 — PAT을 단 프로브도 401이 되면 안 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuatorHealthTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details: always — 게이트웨이가 components.db를 읽는다
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void healthSurvivesNonJwtAuthorizationHeader() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer agp_not_a_jwt"))
                .andExpect(status().isOk());
    }

    @Test
    void infoIsPublic() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
