# Gradle 홈(배포본·의존성 캐시·init 스크립트). 기본은 비어 있어 빌드가 직접 받는다(로컬은 그대로).
# CI는 러너가 채운 Gradle 홈을 --build-context gradle-home=<폴더>로 넘겨, Docker 안에서 Maven Central에 가지 않는다(429 막기, #372)
FROM scratch AS gradle-home

FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle/ ./gradle/
COPY src/main/ ./src/main/

# 넘겨받은 Gradle 홈을 쓰기 가능으로 붙인다. 빌드 중에 쓴 것은 버려지고 결과 jar만 남는다(층에 캐시를 복사하지 않음)
RUN --mount=type=bind,from=gradle-home,target=/root/.gradle,rw \
    ./gradlew bootJar --no-daemon -x test
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
# RDS 인증서 묶음(공개 파일, 04 6장 "공개 파일"). 저장소 원본과 같은지 확인하고 root 소유·모두 읽기 전용으로 둔다.
# 웹 앱(10001)과 Migration(10002)이 같은 경로(sslrootcert=/app/certs/rds-ca.crt)로 읽는다
COPY certs/rds-ca-ap-northeast-2.crt /app/certs/rds-ca.crt
RUN echo "913fb5b814f17af79d4c1622584a8d0ceddf5b0d76fe353d0c7d1186cdd6b229  /app/certs/rds-ca.crt" | sha256sum -c - \
    && chmod 0755 /app/certs \
    && chmod 0444 /app/certs/rds-ca.crt
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-Duser.timezone=UTC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
