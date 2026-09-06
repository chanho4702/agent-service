package com.platform.agentservice.budget;

import com.platform.agentservice.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BudgetController} — 인가 계약(킬 스위치 변경=ROLE_ADMIN, 조회 둘 다 인증 사용자
 * 누구나)과 응답 shape만 본다(PatControllerTest와 같은 패턴). 캡 판정 로직 자체는
 * {@link BudgetServiceTest}가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean BudgetService budgetService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authenticated_user_can_read_budget_status() throws Exception {
        given(budgetService.snapshot()).willReturn(
                new BudgetService.BudgetSnapshot(new BigDecimal("100"), new BigDecimal("42.50"), false));

        mvc.perform(get("/api/agent/budget").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyCapUsd").value(100))
                .andExpect(jsonPath("$.platformMonthToDateUsd").value(42.50))
                .andExpect(jsonPath("$.killSwitch").value(false));
    }

    @Test
    void authenticated_user_can_read_kill_switch_status() throws Exception {
        given(budgetService.killSwitchOn()).willReturn(true);

        mvc.perform(get("/api/agent/kill-switch").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.on").value(true));
    }

    @Test
    void admin_can_turn_kill_switch_on() throws Exception {
        mvc.perform(post("/api/agent/kill-switch").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}"))
                .andExpect(status().isNoContent());

        verify(budgetService).setKillSwitch(true);
    }

    @Test
    void admin_can_turn_kill_switch_off() throws Exception {
        mvc.perform(post("/api/agent/kill-switch").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"on\":false}"))
                .andExpect(status().isNoContent());

        verify(budgetService).setKillSwitch(false);
    }

    @Test
    void non_admin_cannot_change_kill_switch() throws Exception {
        mvc.perform(post("/api/agent/kill-switch").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}"))
                .andExpect(status().isForbidden());
    }
}
