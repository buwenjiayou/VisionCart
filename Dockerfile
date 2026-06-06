FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY backend backend
RUN chmod +x ./gradlew && ./gradlew :backend:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system visioncart \
    && useradd --system --gid visioncart --home-dir /app --shell /usr/sbin/nologin visioncart
COPY --from=build /workspace/backend/build/libs/backend-0.1.0.jar /app/backend.jar
RUN mkdir -p /app/data && chown -R visioncart:visioncart /app
USER visioncart
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend.jar", "--spring.profiles.active=prod"]
