package com.platform.agentservice.schema;

import com.platform.agentservice.pat.PatToken;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4(AGP-24) — 요청 DTO의 {@code @Size(max)}와 엔티티 {@code @Column(length)}가 어긋나지 않게 묶어 둔다.
 *
 * <p>진실 소스는 Flyway V1의 컬럼폭(persona.slug 40 · name 80 · emoji 16 · pat_token.label 120)이다.
 * 엔티티 {@code length}는 그 폭을 코드에 옮겨 적은 것이고, DTO {@code @Size}는 그 폭을 요청 경계에서
 * 미리 끊어 500(DataIntegrityViolation) 대신 400으로 돌려주기 위한 것이다 — 셋 중 하나만 바꾸면 이 테스트가 깨진다.
 * (Flyway 스키마 자체와 엔티티의 정합은 {@link FlywaySchemaValidationTest}가 본다.)
 *
 * <p>레코드 컴포넌트가 아니라 필드에서 읽는다 — Bean Validation 제약은 {@code RECORD_COMPONENT} 타깃이 없어
 * 컴파일러가 필드/접근자/생성자 파라미터로만 전파한다.
 */
class RequestDtoColumnWidthTest {

    static Stream<Arguments> pairs() {
        return Stream.of(
                Arguments.of(PersonaCreateRequest.class, "name", Persona.class, "name", 80),
                Arguments.of(PersonaCreateRequest.class, "emoji", Persona.class, "emoji", 16),
                Arguments.of(PatCreateRequest.class, "label", PatToken.class, "label", 120));
    }

    @ParameterizedTest(name = "{0}.{1} @Size(max) == {2}.{3} @Column(length) == {4}")
    @MethodSource("pairs")
    void dto_size_max_matches_entity_column_length(Class<? extends Record> dto, String dtoField,
                                                   Class<?> entity, String entityField, int expected) throws Exception {
        Size size = dto.getDeclaredField(dtoField).getAnnotation(Size.class);
        Column column = entity.getDeclaredField(entityField).getAnnotation(Column.class);

        assertThat(size).as("%s.%s에 @Size가 있어야 한다", dto.getSimpleName(), dtoField).isNotNull();
        assertThat(column).as("%s.%s에 @Column이 있어야 한다", entity.getSimpleName(), entityField).isNotNull();
        assertThat(size.max()).isEqualTo(expected);
        assertThat(column.length()).isEqualTo(expected);
    }

    /** slug는 @Pattern이 길이까지 묶는다(2~40) — 상한이 persona.slug VARCHAR(40)과 같아야 한다. */
    @Test
    void persona_slug_pattern_upper_bound_matches_column_length() throws Exception {
        Pattern pattern = PersonaCreateRequest.class.getDeclaredField("slug").getAnnotation(Pattern.class);
        Column column = Persona.class.getDeclaredField("slug").getAnnotation(Column.class);

        assertThat(pattern.regexp()).endsWith("{2,40}");
        assertThat(column.length()).isEqualTo(40);
    }

}
