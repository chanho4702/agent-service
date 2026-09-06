package com.platform.agentservice.worker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkerProperties#repoFor} — F3(fix round, task-7 E2E 실측): env var로 주입한 맵의
 * 키가 Spring relaxed binding에 의해 소문자로 접혀도({@code repos.get("agp")}) ALM 프로젝트
 * 키(항상 대문자, {@code "AGP"})로 조회할 수 있어야 한다.
 */
class WorkerPropertiesTest {

    private WorkerProperties propertiesWithRepos(Map<String, String> repos) {
        return new WorkerProperties("C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read,Edit,Write", "http://localhost/api/agent/mcp", repos);
    }

    @Test
    void repoFor_matches_lowercase_key_when_lookup_key_is_uppercase() {
        // env var 주입(PLATFORM_AGENT_WORKER_REPOS_AGP=...) 시 relaxed binding이 실제로
        // 만드는 모양 그대로 재현 — 소문자 키.
        WorkerProperties properties = propertiesWithRepos(Map.of("agp", "https://example.com/agp.git"));

        assertThat(properties.repoFor("AGP")).isEqualTo("https://example.com/agp.git");
    }

    @Test
    void repoFor_matches_uppercase_key_when_lookup_key_is_uppercase() {
        // YAML/프로그램 인자로 주입하면 대소문자가 그대로 보존된다 — 이 경로도 계속 동작해야 한다.
        WorkerProperties properties = propertiesWithRepos(Map.of("AGP", "https://example.com/agp.git"));

        assertThat(properties.repoFor("AGP")).isEqualTo("https://example.com/agp.git");
    }

    @Test
    void repoFor_returns_null_when_no_key_matches() {
        WorkerProperties properties = propertiesWithRepos(Map.of("other", "https://example.com/other.git"));

        assertThat(properties.repoFor("AGP")).isNull();
    }

    @Test
    void repoFor_returns_null_for_empty_or_null_repos() {
        assertThat(propertiesWithRepos(Map.of()).repoFor("AGP")).isNull();
        assertThat(propertiesWithRepos(null).repoFor("AGP")).isNull();
    }
}
