# syntax=docker/dockerfile:1.7
# magicjar-batch Dockerfile
# 멀티 스테이지 X — gradle 호스트 빌드 후 jar COPY (CLAUDE.md 운영 함정 §1).

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source=https://github.com/sp-daewoon/magicJar
LABEL org.opencontainers.image.title=magicjar-batch
LABEL org.opencontainers.image.licenses=MIT

# 비루트 (ADR 0008)
RUN addgroup -S magicjar && adduser -S -G magicjar magicjar
USER magicjar
WORKDIR /app

# 호스트 산출물 COPY — :batch:bootJar 가 build/libs/batch-0.0.1-SNAPSHOT.jar 생성
COPY --chown=magicjar:magicjar build/libs/batch-0.0.1-SNAPSHOT.jar /app/app.jar

# JVM 옵션 — KST 타임존 의무
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul -XX:+UseG1GC -XX:MaxRAMPercentage=75"

EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=20 \
    CMD wget -qO- http://localhost:8081/actuator/health 2>/dev/null | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
