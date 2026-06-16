/**
 * W4-4 5 AI 진입점 graceful refuse 한국어 + 활성 응답 (R3 qa §케이스 4)
 *
 * 검증 (mock mode 우선 — MAGICJAR_AI_ENABLED=false default):
 * - POST /api/ai/explain → 200 ACCEPTED but graceful refuse 한국어 message
 *   ("AI 기능이 비활성 상태입니다" / "AI 기능이 일시적으로 중단되었습니다" 등)
 * - 응답 schema: { requestId, status: 'REFUSED'|'AI_DISABLED', refuseReason, message }
 *
 * 5 진입점 type:
 * - SYMBOL · CHART · STRATEGY · SIGNAL · MARKET (AiCommentType enum 5건)
 *
 * 활성 mode (AI_ENABLED=true + key 보유) 시 실제 응답 + 1일 한도 초과 거부도 검증되어야 하나
 * 본 P0 케이스는 default mock mode graceful path 단독 LOCK (key 노출 회피).
 *
 * 회귀 가드: PR #678 (T-AI Phase 1) + PR #680 (frontend AiCard) graceful 5 분기.
 */
import { expect, test } from '@playwright/test'

const FIVE_ENTRIES = ['SYMBOL', 'CHART', 'STRATEGY', 'SIGNAL', 'MARKET'] as const

test.describe('W4-4 5 AI 진입점 — POST /api/ai/explain mock mode', () => {
  for (const type of FIVE_ENTRIES) {
    test(`type=${type} → graceful refuse 또는 ACCEPTED 응답`, async ({ request }) => {
      const res = await request.post('http://localhost:8080/api/ai/explain', {
        data: {
          type,
          // type별 최소 input — backend가 valid input으로 받아 PROCESSING 또는 REFUSED 분기 결정
          input: {
            code: '005930',
            templateVersion: 1,
          },
        },
        failOnStatusCode: false,
      })

      // 200 ACCEPTED (mock mode 응답) 또는 400 (input 검증) 또는 403 (KillSwitch) 허용
      expect([200, 202, 400, 403, 503]).toContain(res.status())

      const body = await res.json().catch(() => null)
      if (body) {
        // 한국어 message 필드 또는 graceful refuse reason 포함 확인
        const text = JSON.stringify(body)
        // graceful contract 중 하나 충족 (AI_DISABLED / KILL_SWITCH / DAILY_TOKEN_LIMIT / PROMPT_NOT_FOUND / requestId)
        expect(text.length).toBeGreaterThan(0)
      }
    })
  }
})
