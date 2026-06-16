# syntax=docker/dockerfile:1.7
# magicjar-api Dockerfile
# 멀티 스테이지 X — gradle 호스트 빌드 후 jar COPY (CLAUDE.md 운영 함정 §1).
# scripts/build-and-deploy.sh가 정석 경로 (gradle :api:bootJar → docker build).

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source=https://github.com/sp-daewoon/magicJar
LABEL org.opencontainers.image.title=magicjar-api
LABEL org.opencontainers.image.licenses=MIT

# 비루트 (ADR 0008)
RUN addgroup -S magicjar && adduser -S -G magicjar magicjar
USER magicjar
WORKDIR /app

# 호스트 산출물 COPY — :api:bootJar 가 build/libs/api-0.0.1-SNAPSHOT.jar 생성
# archiveClassifier="" 설정으로 plain.jar 미생성 (build.gradle.kts 정합)
COPY --chown=magicjar:magicjar build/libs/api-0.0.1-SNAPSHOT.jar /app/app.jar

# JVM 옵션 — KST 타임존 의무 (KIS·DB·log 정합)
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul -XX:+UseG1GC -XX:MaxRAMPercentage=75"

EXPOSE 8080

# 헬스체크 (ADR 0008) — /actuator/health
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=20 \
    CMD wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
