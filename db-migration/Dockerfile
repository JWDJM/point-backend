# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG MODULE
RUN ./gradlew :${MODULE}:bootJar --no-daemon -x test

# --- runtime (non-root) --- 웹 서버 없음: Flyway 마이그레이션 후 종료 (성공 0 / 실패 1)
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
WORKDIR /app
ARG MODULE
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
