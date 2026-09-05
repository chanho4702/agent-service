package com.platform.agentservice.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class FlywaySchemaValidationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("eureka.client.enabled", () -> "false");
        r.add("platform.jwt.issuer", () -> "http://localhost:9000");
        r.add("platform.jwt.audience", () -> "platform-api");
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:9000/.well-known/jwks.json");
    }

    @Test
    void migrations_match_entities() {
        // 컨텍스트 부팅 = Flyway 실행 + validate 통과
    }
}
