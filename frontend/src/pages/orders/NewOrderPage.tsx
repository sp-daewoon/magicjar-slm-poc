/**
 * NewOrderPage — 사용자 수동 주문 발행 페이지 (T-MAN Phase 2 — ADR 0082 B-2-extended)
 *
 * route: /orders/new
 *
 * UI 8 layer 안전망 매트릭스 (spec §3-7):
 *   1. 종목코드 6자리 LOCK — 정규식 `^\d{6}$` 검증
 *   2. 수량 >= 1 — Number input min=1 + 추가 검증
 *   3. LIMIT 시 가격 > 0 — string parseFloat 후 > 0 검증
 *   4. 3중 게이트 상태 표시 (MAGICJAR_MODE · MAGICJAR_ALLOW_REAL · real_trading_consent_at)
 *   5. KillSwitch 상태 표시 + ON 시 LOCK
 *   6. 미리보기 → OrderPreviewModal 이중 confirm
 *   7. 60초 cooldown (동일 사용자 + 동일 종목, sessionStorage 영속화)
 *   8. REAL 모드 강조 — confirm 텍스트 빨간색 + "정말 발사하시겠습니까?" 텍스트
 *
 * 흐름:
 *   InstrumentSearchInput → side toggle → priceType toggle → quantity → limitPrice(LIMIT 시)
 *   → 게이트 상태 panel → [미리보기] 클릭
 *   → OrderPreviewModal (이중 confirm)
 *   → useManualOrderMutation POST /api/orders/manual
 *   → onSuccess: 토스트 + navigate('/orders')
 *
 * 60s cooldown 영속화:
 *   sessionStorage key: `manual_order_cooldown:${userId}:${code}`
 *   value: epochSeconds (cooldown 종료 시각)
 *   페이지 새로고침 후에도 잔여시간 표시 (backend Redis SETNX 60s 권위 + UI 동기화)
 *
 * AI 호출 0 — 룰 5 (W3 LLM provider import 0건).
 */
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'
import { OrderPreviewModal } from '../../components/order/OrderPreviewModal'
import { useManualOrderMutation } from '../../api/hooks/useManualOrderMutation'
import { useRealTradingConsent } from '../../api/hooks/useUpdateRealTradingConsent'
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'
import { useKillSwitchState } from '../../api/queries/useKillSwitchState'
import type { OrderSide, OrderPriceType, TradingMode, ManualOrderRequest } from '../../api/order'
import type { InstrumentSearchResultDto } from '../../api/instrument'

// ──────────────────────────────────────────────
// cooldown helpers (sessionStorage 영속화)
// ──────────────────────────────────────────────

const COOLDOWN_SECONDS = 60

// ADR 0089 Phase 2 — userId number (cooldown key 직렬화는 toString 자연 변환)
function cooldownKey(userId: number, code: string): string {
  return `manual_order_cooldown:${userId}:${code}`
}

function getCooldownRemainSec(userId: number, code: string): number {
  if (!code) return 0
  try {
    const raw = sessionStorage.getItem(cooldownKey(userId, code))
    if (!raw) return 0
    const expiryEpoch = parseInt(raw, 10)
    if (Number.isNaN(expiryEpoch)) return 0
    const remain = expiryEpoch - Math.floor(Date.now() / 1000)
    return remain > 0 ? remain : 0
  } catch {
    return 0
  }
}

function setCooldown(userId: number, code: string): void {
  if (!code) return
  try {
    const expiry = Math.floor(Date.now() / 1000) + COOLDOWN_SECONDS
    sessionStorage.setItem(cooldownKey(userId, code), String(expiry))
  } catch {
    // sessionStorage 차단 환경 — backend OrderIdempotencyGuard가 권위
  }
}

// ──────────────────────────────────────────────
// 페이지 본체
// ──────────────────────────────────────────────

export function NewOrderPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const navigate = useNavigate()

  // form state
  const [code, setCode] = useState('')
  const [codeName, setCodeName] = useState('')
  const [side, setSide] = useState<OrderSide>('BUY')
  const [priceType, setPriceType] = useState<OrderPriceType>('MARKET')
  const [quantity, setQuantity] = useState<number>(1)
  const [limitPrice, setLimitPrice] = useState<string>('')
  const [previewOpen, setPreviewOpen] = useState(false)
  const [cooldownRemain, setCooldownRemain] = useState(0)

  // 3중 게이트 데이터
  const { data: consent } = useRealTradingConsent(userId)
  const { data: killSwitchState } = useKillSwitchState()
  const { data: runtimeFlags } = useRuntimeFlags(userId)

  // RuntimeFlags가 backend env/runtime gate의 권위다. 개별 consent/killSwitch query는 응답 지연 시 표시 보강용.
  const mode: TradingMode = runtimeFlags?.mode ?? 'MOCK'
  const hasConsent = runtimeFlags?.userConsented ?? !!consent?.realTradingConsentAt
  const allowReal = runtimeFlags?.allowReal ?? false
  const killSwitchOn = runtimeFlags?.killSwitchEnabled ?? !!killSwitchState?.enabled

  // mutation
  const manualOrderMutation = useManualOrderMutation()

  // cooldown 1초 단위 갱신 + 종목 변경 시 즉시 재계산
  useEffect(() => {
    if (!userId || !code) {
      setCooldownRemain(0)
      return
    }
    const update = () => setCooldownRemain(getCooldownRemainSec(userId, code))
    update()
    const timer = setInterval(update, 1000)
    return () => clearInterval(timer)
  }, [userId, code])

  // ── 검증 매트릭스 (layer 1·2·3) ─────────────────────────────
  const codeValid = /^\d{6}$/.test(code)
  const quantityValid = Number.isInteger(quantity) && quantity >= 1
  const limitPriceNumber = limitPrice ? parseFloat(limitPrice) : NaN
  const limitPriceValid =
    priceType === 'MARKET' ||
    (!Number.isNaN(limitPriceNumber) && limitPriceNumber > 0)

  const formValid = codeValid && quantityValid && limitPriceValid
  const cooldownBlocked = cooldownRemain > 0

  const canOpenPreview = useMemo(
    () => formValid && !cooldownBlocked && !killSwitchOn,
    [formValid, cooldownBlocked, killSwitchOn],
  )

  // userId 미등록 시 셋업 안내
  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">신규 주문</h1>
        <p className="text-sm text-gray-400">
          KIS 계좌 등록을 완료하면 수동 주문을 발행할 수 있습니다.
        </p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  // ── 핸들러 ────────────────────────────────────────────────
  function handleInstrumentSelect(result: InstrumentSearchResultDto) {
    setCode(result.code)
    setCodeName(result.name)
  }

  function handleOpenPreview() {
    if (!canOpenPreview) return
    setPreviewOpen(true)
  }

  function handleCancelPreview() {
    setPreviewOpen(false)
  }

  function handleConfirmSubmit() {
    if (!userId) return
    const request: ManualOrderRequest = {
      userId,
      code,
      side,
      priceType,
      quantity,
      limitPrice: priceType === 'LIMIT' ? limitPrice : null,
    }
    manualOrderMutation.mutate(request, {
      onSuccess: () => {
        // 60s cooldown sessionStorage 영속화 (Phase 4 frontend 책임 — spec §4-3 위험 매트릭스)
        setCooldown(userId, code)
        setPreviewOpen(false)
        // navigate('/orders')는 useManualOrderMutation 내부에서 처리
      },
      onError: () => {
        // 토스트는 useManualOrderMutation 내부에서 표시
        setPreviewOpen(false)
      },
    })
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-6">
      {/* 헤더 */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">신규 주문</h1>
          <p className="mt-1 text-sm text-gray-400">
            사용자 수동 주문 발행 (T-MAN — ADR 0082 B-2-extended)
          </p>
        </div>
        <button
          type="button"
          onClick={() => navigate('/orders')}
          className="rounded-lg border border-gray-700 bg-gray-900 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
        >
          ← 주문 내역
        </button>
      </div>

      {/* 룰 안전망 우회 경고 (W3 보강 트랙) */}
      <div className="mb-6 rounded-2xl border border-yellow-800 bg-yellow-950/30 px-4 py-3">
        <p className="text-sm font-semibold text-yellow-300">⚠ 수동 주문 = 룰 안전망 우회</p>
        <p className="mt-1 text-xs text-yellow-400">
          T-RISK Guard chain(POSITION_LIMIT·DAILY_LOSS·EXPOSURE_CAP)을 우회합니다. 책임은 사용자에 있으며,
          UI 8 layer 안전망 + 60s cooldown + 3중 게이트로 자본 위험을 1차 차단합니다.
        </p>
      </div>

      {/* 폼 */}
      <form
        onSubmit={(e) => {
          e.preventDefault()
          handleOpenPreview()
        }}
        className="space-y-5 rounded-2xl border border-gray-800 bg-gray-900/60 p-6"
      >
        {/* 1단: 종목 검색 (layer 1) */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-300">
            종목 <span className="text-red-400">*</span>
          </label>
          <InstrumentSearchInput value={codeName ? `${codeName} (${code})` : ''} onSelect={handleInstrumentSelect} />
          {code && !codeValid && (
            <p className="mt-1 text-xs text-red-400">종목코드는 6자리 숫자여야 합니다.</p>
          )}
          {code && codeValid && (
            <p className="mt-1 text-xs text-gray-500">
              선택된 종목: <span className="font-mono text-cyan-400">{code}</span>
            </p>
          )}
        </div>

        {/* 2단: 매수/매도 */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-300">
            매수/매도 <span className="text-red-400">*</span>
          </label>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setSide('BUY')}
              className={[
                'flex-1 rounded-xl px-4 py-2.5 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
                side === 'BUY'
                  ? 'bg-red-600 text-white focus:ring-red-400'
                  : 'border border-gray-700 bg-gray-900 text-red-400 hover:bg-gray-800',
              ].join(' ')}
              aria-pressed={side === 'BUY'}
            >
              매수 (BUY)
            </button>
            <button
              type="button"
              onClick={() => setSide('SELL')}
              className={[
                'flex-1 rounded-xl px-4 py-2.5 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
                side === 'SELL'
                  ? 'bg-blue-600 text-white focus:ring-blue-400'
                  : 'border border-gray-700 bg-gray-900 text-blue-400 hover:bg-gray-800',
              ].join(' ')}
              aria-pressed={side === 'SELL'}
            >
              매도 (SELL)
            </button>
          </div>
        </div>

        {/* 3단: 가격 유형 */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-300">
            가격 유형 <span className="text-red-400">*</span>
          </label>
          <div className="flex gap-2">
            <label className="flex flex-1 cursor-pointer items-center gap-2 rounded-xl border border-gray-700 bg-gray-900 px-4 py-2.5 text-sm text-gray-300 hover:bg-gray-800">
              <input
                type="radio"
                name="priceType"
                value="MARKET"
                checked={priceType === 'MARKET'}
                onChange={() => setPriceType('MARKET')}
                className="accent-blue-500"
              />
              시장가 (MARKET)
            </label>
            <label className="flex flex-1 cursor-pointer items-center gap-2 rounded-xl border border-gray-700 bg-gray-900 px-4 py-2.5 text-sm text-gray-300 hover:bg-gray-800">
              <input
                type="radio"
                name="priceType"
                value="LIMIT"
                checked={priceType === 'LIMIT'}
                onChange={() => setPriceType('LIMIT')}
                className="accent-blue-500"
              />
              지정가 (LIMIT)
            </label>
          </div>
        </div>

        {/* 4단: 수량 (layer 2) */}
        <div>
          <label htmlFor="quantity" className="mb-2 block text-sm font-medium text-gray-300">
            수량 <span className="text-red-400">*</span>
          </label>
          <input
            id="quantity"
            type="number"
            min={1}
            step={1}
            value={quantity}
            onChange={(e) => setQuantity(Math.max(1, parseInt(e.target.value || '1', 10)))}
            className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          {!quantityValid && (
            <p className="mt-1 text-xs text-red-400">수량은 1주 이상의 정수여야 합니다.</p>
          )}
        </div>

        {/* 5단: 지정가 (LIMIT 시만 — layer 3) */}
        {priceType === 'LIMIT' && (
          <div>
            <label htmlFor="limitPrice" className="mb-2 block text-sm font-medium text-gray-300">
              지정가 (원) <span className="text-red-400">*</span>
            </label>
            <input
              id="limitPrice"
              type="text"
              inputMode="decimal"
              value={limitPrice}
              onChange={(e) => setLimitPrice(e.target.value.trim())}
              placeholder="예: 70500"
              className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            {!limitPriceValid && limitPrice && (
              <p className="mt-1 text-xs text-red-400">지정가는 0보다 큰 숫자여야 합니다.</p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              사용자 메모리 정합: KIS HTS 호가창에서 현재가 확인 후 입력 (1차 MVP 호가 stream 0)
            </p>
          </div>
        )}

        {/* 6단: 3중 게이트 + KillSwitch 상태 panel (layer 4·5) */}
        <div className="rounded-xl border border-gray-800 bg-gray-950 p-4 text-xs">
          <p className="mb-2 font-semibold text-gray-300">안전 게이트 상태 (참고)</p>
          <ul className="space-y-1">
            <li className="flex items-center justify-between">
              <span className="text-gray-500">거래 모드</span>
              <span className={mode === 'REAL' ? 'text-red-400' : 'text-cyan-400'}>{mode}</span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-gray-500">실거래 동의 (consent)</span>
              <span className={hasConsent ? 'text-green-400' : 'text-gray-500'}>
                {hasConsent ? '체결' : '미체결 (MOCK 발행만 가능)'}
              </span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-gray-500">KillSwitch</span>
              <span className={killSwitchOn ? 'text-red-400' : 'text-green-400'}>
                {killSwitchOn ? 'ON (모든 주문 차단)' : 'OFF'}
              </span>
            </li>
          </ul>
          <p className="mt-2 text-xs text-gray-600">
            권위는 backend ManualOrderService 3중 게이트 fast precheck + consumer OrderRouter step 3·4 재검증.
          </p>
        </div>

        {/* 60s cooldown panel (layer 7) */}
        {cooldownBlocked && (
          <div className="rounded-xl border border-yellow-700 bg-yellow-950/30 p-3 text-xs">
            <p className="font-semibold text-yellow-300">
              ⏱ 동일 종목 60초 cooldown — 잔여 {cooldownRemain}초
            </p>
            <p className="mt-1 text-yellow-500">
              backend OrderIdempotencyGuard(Redis SETNX 60s)가 권위.
            </p>
          </div>
        )}

        {/* KillSwitch LOCK panel */}
        {killSwitchOn && (
          <div className="rounded-xl border border-red-700 bg-red-950/40 p-3 text-xs">
            <p className="font-bold text-red-300">⛔ KillSwitch ON — 모든 주문 발행 차단</p>
            <p className="mt-1 text-red-400">설정 화면에서 KillSwitch OFF 후 재시도</p>
          </div>
        )}

        {/* 미리보기 버튼 (layer 6 진입) */}
        <button
          type="submit"
          disabled={!canOpenPreview}
          className={[
            'w-full rounded-xl px-4 py-3 text-sm font-bold transition-colors focus:outline-none focus:ring-2',
            canOpenPreview
              ? 'bg-blue-600 text-white hover:bg-blue-500 focus:ring-blue-400'
              : 'cursor-not-allowed bg-gray-800 text-gray-500',
          ].join(' ')}
        >
          미리보기
        </button>
      </form>

      {/* 미리보기 모달 (이중 confirm — layer 6) */}
      <OrderPreviewModal
        open={previewOpen}
        code={code}
        codeName={codeName || code}
        side={side}
        priceType={priceType}
        quantity={quantity}
        limitPrice={priceType === 'LIMIT' ? limitPrice : null}
        mode={mode}
        allowReal={allowReal}
        hasConsent={hasConsent}
        killSwitchOn={killSwitchOn}
        cooldownRemainSec={cooldownRemain}
        onConfirm={handleConfirmSubmit}
        onCancel={handleCancelPreview}
        isPending={manualOrderMutation.isPending}
      />
    </div>
  )
}
