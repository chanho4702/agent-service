# 런타임 전용. jar는 CI(또는 로컬)의 `gradlew bootJar` 산출물(build/libs/app.jar)을 복사한다.
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY build/libs/app.jar app.jar
EXPOSE 9160
ENTRYPOINT ["java", "-jar", "app.jar"]
