# syntax=docker/dockerfile:1.7
# 멀티 스테이지 빌드:
#   stage 1 (build)  — node:20-alpine + pnpm + Vite → dist/
#   stage 2 (serve)  — nginx:alpine → dist/ 정적 서빙
#
# ADR 0009 정합 (React 19 + TS + Vite + LWC v4).
# 로컬 빌드: docker compose -f docker/docker-compose.yml build frontend

# ─────────── Stage 1: 빌드 ───────────
FROM node:20-alpine AS build

WORKDIR /build

# corepack으로 pnpm 활성화 (package.json의 packageManager 필드 읽음)
RUN corepack enable

# lockfile만 먼저 복사 → 레이어 캐시 최적화
COPY package.json pnpm-lock.yaml ./

# frozen-lockfile: CI/CD 정합성 보장
RUN pnpm install --frozen-lockfile

# 소스 전체 복사 후 빌드
COPY . .
RUN pnpm build

# ─────────── Stage 2: 서빙 ───────────
FROM nginx:alpine

LABEL org.opencontainers.image.source=https://github.com/sp-daewoon/magicJar
LABEL org.opencontainers.image.title=magicjar-frontend
LABEL org.opencontainers.image.licenses=MIT

# 빌드 산출물 복사
COPY --from=build /build/dist /usr/share/nginx/html

# SPA history mode fallback 설정
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# nginx 헬스체크 — alpine nginx에 wget 미설치라 nginx 프로세스 + 로컬 응답을 wget-busybox로 검증
# busybox wget(alpine 기본)이 200 응답을 받으면 통과.
# /healthz 전용 endpoint — nginx.conf에서 access_log off 설정 (access log 노이즈 제거).
# interval 10s → 30s 완화 (사용자 의제 정합).
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=5 \
    CMD wget --spider -q http://127.0.0.1/healthz 2>/dev/null || exit 1
