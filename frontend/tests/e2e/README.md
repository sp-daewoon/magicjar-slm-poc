# magicJar qa Visual — Playwright E2E + 시각 회귀

> **권위 spec**: `_workspace/specs/2026-05-21-qa-visual-uplift.md` (architect 작성, PR #646 머지)
> **권위 ADR**: `_workspace/arch/adr/0094-ops-stage-transition.md` §2-4 D-7

## 페르소나 — "품질 헌터" (Quality Hunter)

- 역할: Visual QA Engineer (10년차)
- 좌우명: "사용자가 보기 전에 내가 본다."
- 트리거: frontend 핵심 화면 변경 PR · dogfooding 결함 시나리오 재현 · 정기 주간 SLA Visual 검증

## P0 셋업 시나리오 매트릭스

| # | 시나리오 | 파일 | baseline 스크린샷 | 회귀 가드 evidence |
|---|---------|------|------------------|------------------|
| S-1 | Dashboard 진입 | `s1-dashboard.spec.ts` | `s1-dashboard-default.png` | PR #595 / PR #597 |
| S-2 | Orders 페이지 | `s2-orders.spec.ts` | `s2-orders-default.png` | PR #631 |
| S-3 | Positions 페이지 | `s3-positions.spec.ts` | `s3-positions-default.png` | PR #643 |
| S-4 | Settings 모달 | `s4-settings-modal.spec.ts` | `s4-settings-modal.png` | KIS 키 입력 흐름 |
| S-5 | 알람센터 | `s5-notification-center.spec.ts` | `s5-notification-center.png` | NotificationCenter |

## 사용법

```bash
# 1) docker compose 가동 (frontend 컨테이너 3000 포트 listen 의무)
scripts/build-and-deploy.sh   # 또는 docker compose -f docker/docker-compose.yml up -d

# 2) baseline 첫 생성 (또는 의도된 UI 변경 후 baseline 갱신)
cd frontend && pnpm qa:visual:update

# 3) 회귀 검증
pnpm qa:visual

# 4) HTML 보고서 열기 (실패 시 trace/video/screenshot diff 확인)
open playwright-report/index.html
```

## baseline PNG git tracking

- `frontend/tests/e2e/__screenshots__/` 하위 모든 PNG는 **git tracked** (사용자 메모리 `feedback_no_mock_data` 정합 — placeholder 0)
- baseline 갱신은 의도된 UI 변경 PR에서만 수행 + reviewer가 diff 인지 후 머지

## 운영 단계 확장 P1 backlog

- multi-viewport (mobile Pixel 5) 추가
- multi-browser (Firefox · WebKit) 추가
- S-6~S-10: backtest E2E · 시그널 자동 발사 · 매매일지 · AI 분석 (W4) · MCP 노출
- accessibility 검증 (axe-playwright 통합)
- pre-push hook 통합 (사용자 메모리 `feedback_no_github_actions` 정합 — local 단독)
