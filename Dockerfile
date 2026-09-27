# 런타임 전용. jar는 CI(또는 로컬)의 `gradlew bootJar` 산출물(build/libs/app.jar)을 복사한다.
# 서비스 전용 이미지다 — 워커(claude·git·node)를 싣지 않는다. 서버 실행은 비밀 없는 별도 러너 컨테이너(AGP-69) 몫(CLAUDE.md §12).
FROM eclipse-temurin:24-jre
RUN groupadd --system --gid 10001 agent \
 && useradd --system --uid 10001 --gid agent --no-create-home --shell /usr/sbin/nologin agent
WORKDIR /app
COPY build/libs/app.jar app.jar
USER agent
EXPOSE 9160
ENTRYPOINT ["java", "-jar", "app.jar"]
