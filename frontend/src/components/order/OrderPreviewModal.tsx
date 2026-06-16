/**
 * OrderPreviewModal — 수동 주문 미리보기 + 이중 confirm 모달 (T-MAN Phase 2 — ADR 0082)
 *
 * UI 8 layer 안전망 매트릭스 (spec §3-7):
 *   1. 종목코드 6자리 LOCK — NewOrderPage 입력 검증
 *   2. 수량 >= 1 — NewOrderPage 입력 검증
 *   3. LIMIT 시 가격 > 0 — NewOrderPage 입력 검증
 *   4. 3중 게이트 상태 표시 (mode badge + ALLOW_REAL + consent)
 *   5. KillSwitch 상태 표시 (ON 시 LOCK)
 *   6. ★ 이중 confirm (본 모달 — 확인 checkbox + [발행] 버튼)
 *   7. ★ 60초 cooldown 카운터 (NewOrderPage가 props로 cooldown 잔여초 전달)
 *   8. ★ REAL 모드 강조 — "이 주문은 실 자본 변동을 발생시킵니다" 빨간 강조
 *
 * AI 호출 0 — 룰 5 (W3 LLM provider import 0건).
 */
import { useEffect, useState } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import type { OrderSide, OrderPriceType, TradingMode } from '../../api/order'

export interface OrderPreviewModalProps {
  open: boolean
  /** 종목코드 6자리 */
  code: string
  /** 종목명 (자동완성에서 선택된 이름, 미선택 시 코드 동일) */
  codeName: string
  side: OrderSide
  priceType: OrderPriceType
  quantity: number
  /** LIMIT 시 가격 문자열 (BigDecimal 정밀도) */
  limitPrice: string | null
  /** 거래 모드 — 3중 게이트 합산 결과 */
  mode: TradingMode
  /** ALLOW_REAL 허용 여부 (3중 게이트 2번째) */
  allowReal: boolean
  /** real_trading_consent_at not null 여부 (3중 게이트 3번째) */
  hasConsent: boolean
  /** KillSwitch ON 여부 (4 layer 5) */
  killSwitchOn: boolean
  /** 60s cooldown 잔여초 (0 이상 — 0이면 발행 가능) */
  cooldownRemainSec: number
  /** [발행] 클릭 — 부모(NewOrderPage)가 mutation 실행 */
  onConfirm: () => void
  /** [취소] 클릭 또는 모달 외 클릭 */
  onCancel: () => void
  /** mutation pending — 발행 버튼 disable */
  isPending?: boolean
}

function formatPrice(price: string | null): string {
  if (!price) return '시장가'
  const n = parseFloat(price)
  if (Number.isNaN(n)) return '시장가'
  return n.toLocaleString('ko-KR') + '원'
}

function estimateTotal(priceType: OrderPriceType, limitPrice: string | null, quantity: number): string {
  if (priceType === 'MARKET' || !limitPrice) return '— (시장가)'
  const n = parseFloat(limitPrice)
  if (Number.isNaN(n)) return '—'
  return (n * quantity).toLocaleString('ko-KR') + '원'
}

export function OrderPreviewModal(props: OrderPreviewModalProps) {
  const {
    open,
    code,
    codeName,
    side,
    priceType,
    quantity,
    limitPrice,
    mode,
    allowReal,
    hasConsent,
    killSwitchOn,
    cooldownRemainSec,
    onConfirm,
    onCancel,
    isPending = false,
  } = props

  const [checkboxConfirmed, setCheckboxConfirmed] = useState(false)

  // open 토글 시 체크박스 리셋
  useEffect(() => {
    if (!open) {
      setCheckboxConfirmed(false)
    }
  }, [open])

  // ESC 닫기 + body scroll lock (공용 훅 — open=false면 비활성)
  useModalBehavior({ open, onClose: onCancel })

  if (!open) return null

  const sideLabel = side === 'BUY' ? '매수' : '매도'
  const sideColor = side === 'BUY' ? 'text-red-400' : 'text-blue-400'
  const isReal = mode === 'REAL'

  // 3중 게이트 통과 여부 — REAL 시 ALLOW_REAL + 동의 둘 다 필수
  const realGatePassed = !isReal || (allowReal && hasConsent)
  const cooldownBlocked = cooldownRemainSec > 0
  const canSubmit =
    checkboxConfirmed &&
    !isPending &&
    !killSwitchOn &&
    realGatePassed &&
    !cooldownBlocked

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="order-preview-title"
      onClick={onCancel}
    >
      <div
        className={`w-full max-w-md rounded-2xl border ${
          isReal ? 'border-red-700 bg-red-950/30' : 'border-gray-800 bg-gray-900'
        } p-6 shadow-2xl`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <h2 id="order-preview-title" className="text-lg font-bold text-white">
            주문 미리보기
          </h2>
          {isReal ? (
            <span className="rounded-lg border border-red-700 bg-red-950 px-2 py-0.5 text-xs font-bold text-red-300">
              REAL 거래
            </span>
          ) : (
            <span className="rounded-lg border border-gray-700 bg-gray-800 px-2 py-0.5 text-xs text-gray-400">
              MOCK
            </span>
          )}
        </div>

        {/* REAL 모드 강조 경고 (layer 8) */}
        {isReal && (
          <div className="mb-4 rounded-xl border border-red-700 bg-red-950/50 px-4 py-3">
            <p className="text-sm font-bold text-red-300">
              이 주문은 실 자본 변동을 발생시킵니다
            </p>
            <p className="mt-1 text-xs text-red-400">
              룰 안전망(T-RISK Guard chain) 우회 — 사용자 책임
            </p>
          </div>
        )}

        {/* 주문 정보 */}
        <dl className="mb-4 space-y-2 rounded-xl border border-gray-800 bg-gray-900/60 p-4 text-sm">
          <div className="flex justify-between">
            <dt className="text-gray-400">종목</dt>
            <dd className="font-mono text-white">
              {codeName} <span className="text-gray-500">({code})</span>
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-400">방향</dt>
            <dd className={`font-semibold ${sideColor}`}>{sideLabel}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-400">가격 유형</dt>
            <dd className="text-white">{priceType === 'MARKET' ? '시장가' : '지정가'}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-gray-400">수량</dt>
            <dd className="text-white">{quantity.toLocaleString('ko-KR')}주</dd>
          </div>
          {priceType === 'LIMIT' && (
            <div className="flex justify-between">
              <dt className="text-gray-400">지정가</dt>
              <dd className="text-white">{formatPrice(limitPrice)}</dd>
            </div>
          )}
          <div className="flex justify-between border-t border-gray-800 pt-2">
            <dt className="text-gray-400">예상 총액</dt>
            <dd className="text-white">{estimateTotal(priceType, limitPrice, quantity)}</dd>
          </div>
        </dl>

        {/* 3중 게이트 + KillSwitch 상태 (layer 4·5) */}
        <div className="mb-4 rounded-xl border border-gray-800 bg-gray-950 p-3 text-xs">
          <p className="mb-2 font-semibold text-gray-300">안전 게이트 상태</p>
          <ul className="space-y-1">
            <li className="flex items-center justify-between">
              <span className="text-gray-500">거래 모드 (MAGICJAR_MODE)</span>
              <span className={isReal ? 'text-red-400' : 'text-cyan-400'}>{mode}</span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-gray-500">실거래 허용 (ALLOW_REAL)</span>
              <span className={allowReal ? 'text-green-400' : 'text-gray-500'}>
                {allowReal ? '허용' : '비활성'}
              </span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-gray-500">실거래 동의 (consent)</span>
              <span className={hasConsent ? 'text-green-400' : 'text-gray-500'}>
                {hasConsent ? '체결' : '미체결'}
              </span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-gray-500">KillSwitch</span>
              <span className={killSwitchOn ? 'text-red-400' : 'text-green-400'}>
                {killSwitchOn ? 'ON (차단)' : 'OFF'}
              </span>
            </li>
          </ul>
          {isReal && !realGatePassed && (
            <p className="mt-2 text-xs text-red-400">
              ⚠ REAL 게이트 미통과 — ALLOW_REAL 또는 동의 미체결
            </p>
          )}
        </div>

        {/* 60s cooldown 표시 (layer 7) */}
        {cooldownBlocked && (
          <div className="mb-4 rounded-xl border border-yellow-700 bg-yellow-950/30 p-3 text-xs">
            <p className="text-yellow-300">
              ⏱ 동일 종목 60초 cooldown — 잔여 {cooldownRemainSec}초
            </p>
            <p className="mt-1 text-yellow-500">
              backend OrderIdempotencyGuard(Redis SETNX 60s)가 권위 — UI 우회 시도 차단
            </p>
          </div>
        )}

        {/* KillSwitch LOCK 표시 (layer 5) */}
        {killSwitchOn && (
          <div className="mb-4 rounded-xl border border-red-700 bg-red-950/40 p-3 text-xs">
            <p className="font-bold text-red-300">⛔ KillSwitch ON — 모든 주문 차단됨</p>
          </div>
        )}

        {/* 이중 confirm 체크박스 (layer 6) */}
        <label className="mb-4 flex cursor-pointer items-start gap-2 rounded-xl border border-gray-800 bg-gray-950 p-3">
          <input
            type="checkbox"
            checked={checkboxConfirmed}
            onChange={(e) => setCheckboxConfirmed(e.target.checked)}
            className="mt-0.5 h-4 w-4 cursor-pointer accent-blue-500"
            aria-label="주문 내용 확인"
          />
          <span className="text-xs text-gray-300">
            주문 내용을 확인했으며,{' '}
            <span className={isReal ? 'font-bold text-red-300' : 'text-cyan-300'}>
              {isReal ? '실 자본 변동' : 'MOCK 가상 주문'}
            </span>
            {'을(를) 발행함에 동의합니다.'}
          </span>
        </label>

        {/* 버튼 */}
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={isPending}
            className="flex-1 rounded-xl border border-gray-700 bg-gray-900 px-4 py-2.5 text-sm font-medium text-gray-300 transition-colors hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={!canSubmit}
            className={[
              'flex-1 rounded-xl px-4 py-2.5 text-sm font-bold text-white transition-colors',
              canSubmit
                ? isReal
                  ? 'bg-red-600 hover:bg-red-500 focus:ring-2 focus:ring-red-400'
                  : 'bg-blue-600 hover:bg-blue-500 focus:ring-2 focus:ring-blue-400'
                : 'cursor-not-allowed bg-gray-800 text-gray-500',
            ].join(' ')}
            aria-label={isReal ? '정말 실거래 주문을 발행합니다' : 'MOCK 주문 발행'}
          >
            {isPending
              ? '발행 중...'
              : isReal
                ? '정말 발사하시겠습니까?'
                : '발행'}
          </button>
        </div>
      </div>
    </div>
  )
}
