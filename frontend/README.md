# magicJar Frontend

React 19 + TypeScript + Vite + Tailwind CSS + TradingView Lightweight Charts v4

## 개발 환경

```bash
# pnpm 설치 (없는 경우)
corepack enable && corepack prepare pnpm@latest --activate

# 의존성 설치
pnpm install

# 개발 서버 실행 (5173 포트)
pnpm dev

# 타입 체크
pnpm tsc --noEmit

# 프로덕션 빌드
pnpm build

# 빌드 결과 미리보기
pnpm preview
```

## 스택

| 역할 | 라이브러리 |
|------|-----------|
| UI 프레임워크 | React 19 |
| 언어 | TypeScript 5 |
| 번들러 | Vite 6 |
| CSS | Tailwind CSS 3 |
| 라우터 | react-router-dom 7 |
| 서버 상태 | TanStack Query 5 |
| 클라이언트 상태 | Zustand 5 |
| 차트 | TradingView Lightweight Charts v4 |

## 화면 구조

| URL | 컴포넌트 | 상태 |
|-----|---------|------|
| `/` | DashboardPlaceholder | stub (PR-6+ 구현 예정) |
| `/setup` | SetupWizardPlaceholder | stub (PR-6 T-ONBOARD 구현 예정) |

## Docker

```bash
# 멀티 스테이지 빌드 (node:20-alpine → nginx:alpine)
docker compose -f docker/docker-compose.yml build frontend

# 컨테이너 실행 (3000 포트)
docker compose up -d frontend
```
