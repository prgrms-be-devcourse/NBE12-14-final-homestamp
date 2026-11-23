FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle/ ./gradle/
COPY src/main/ ./src/main/

RUN ./gradlew bootJar --no-daemon -x test
RUN set -eu; \
    set -- build/libs/*.jar; \
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then \
      echo 'Expected exactly one bootJar output' >&2; \
      exit 1; \
    fi; \
    case "$1" in \
      *-plain.jar) echo 'Expected an executable bootJar' >&2; exit 1 ;; \
    esac; \
    cp "$1" /workspace/app.jar

FROM eclipse-temurin:25-jre-noble
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home app
WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/app.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-Duser.timezone=UTC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
