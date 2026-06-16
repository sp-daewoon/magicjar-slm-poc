/**
 * PositionsPage — /positions
 *
 * 보유 포지션 페이지 — KIS 실시간 보유 종목 (GET /api/account/positions).
 *
 * ## hotfix 2026-05-21 (사용자 dogfooding — 빈 화면)
 * 변경 전: usePositions (DB position 테이블) → 사용자 실제 보유와 무관 → 항상 빈 결과.
 * 변경 후: useAccountPositions (KIS inquire-balance output1) → Dashboard HoldingsTable 과 동일 출처.
 *
 * 사용자 보고: "http://localhost:3000/positions 화면 진입하면 아무것도 안 나옴"
 * 검증 evidence (2026-05-20):
 *   curl /api/account/positions?userId=74683223&accountType=MOCK
 *   → SK하이닉스 1주·한국석유 5주·삼성전자 1주 (3건)
 *
 * 필터: source(MOCK/REAL/전체) → accountType(REAL/MOCK) 단순화.
 *   "전체"는 KIS 호출 2회 + 합산 필요 → 본 hotfix 범위 밖. AccountTypeToggle 로 1-tap 전환.
 *
 * 룰 5: LLM provider import 0건
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { accountApi, type AccountHoldingDto, type AccountType } from '../../api/account'
import type { OrderSide } from '../../api/order'
import { useAccountPositions } from '../../api/hooks/useAccountPositions'
import { usePreferredAccountType } from '../../api/hooks/usePreferredAccountType'
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'
import { useStompAccountBalance } from '../../api/hooks/useStompAccountBalance'
import { AccountHoldingCard } from '../../components/risk/AccountHoldingCard'
import { PositionOrderModal } from '../../components/risk/PositionOrderModal'
import { extractStatus } from '../Dashboard/errorStatus'

const ACCOUNT_OPTIONS: { value: AccountType; label: string }[] = [
  { value: 'REAL', label: '실전' },
  { value: 'MOCK', label: '모의' },
]

const POSITION_ORDER_COOLDOWN_SECONDS = 60

interface OrderSelection {
  holding: AccountHoldingDto
  side: OrderSide
  initialQuantity: number
}

function positionOrderCooldownKey(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): string {
  return `position_order_cooldown:${userId}:${accountType}:${code}:${side}`
}

function getPositionOrderCooldownRemainSec(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): number {
  try {
    const raw = sessionStorage.getItem(positionOrderCooldownKey(userId, accountType, code, side))
    if (!raw) return 0
    const expiryEpoch = parseInt(raw, 10)
    if (Number.isNaN(expiryEpoch)) return 0
    const remain = expiryEpoch - Math.floor(Date.now() / 1000)
    return remain > 0 ? remain : 0
  } catch {
    return 0
  }
}

function setPositionOrderCooldown(
  userId: number,
  accountType: AccountType,
  code: string,
  side: OrderSide,
): void {
  try {
    const expiry = Math.floor(Date.now() / 1000) + POSITION_ORDER_COOLDOWN_SECONDS
    sessionStorage.setItem(positionOrderCooldownKey(userId, accountType, code, side), String(expiry))
  } catch {
    // sessionStorage 차단 환경에서는 UI 재시도 쿨다운만 생략된다.
  }
}

export function PositionsPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const queryClient = useQueryClient()
  const { accountType, setAccountType, isReady: accountTypeReady } = usePreferredAccountType()
  const { data: runtimeFlags, isLoading: isRuntimeFlagsLoading } = useRuntimeFlags(userId)
  const { data, isLoading, isError, error, isFetching } = useAccountPositions({
    userId,
    accountType,
    enabled: accountTypeReady,
  })
  const [orderSelection, setOrderSelection] = useState<OrderSelection | null>(null)
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null)
  const [forceRefreshing, setForceRefreshing] = useState(false)
  const [, setCooldownTick] = useState(0)
  const postOrderRefreshTimersRef = useRef<number[]>([])

  const holdings = data ?? []
  const isCredentialMissing = extractStatus(error) === 412
  const orderMode = runtimeFlags?.mode ?? null
  const killSwitchOn = runtimeFlags?.killSwitchEnabled ?? false
  const canTradeSelectedAccount = orderMode !== null && orderMode === accountType && !killSwitchOn
  const tradeDisabledReason = useMemo(() => {
    if (isRuntimeFlagsLoading || orderMode === null) return '주문 런타임 모드를 확인 중입니다.'
    if (killSwitchOn) return 'KillSwitch ON 상태라 포지션 주문이 차단됩니다.'
    if (orderMode !== accountType) {
      return `현재 주문 모드가 ${orderMode}라 ${accountType} 포지션 주문을 막았습니다.`
    }
    return null
  }, [accountType, isRuntimeFlagsLoading, killSwitchOn, orderMode])

  const forceRefreshPositions = useCallback(
    async (notice = 'KIS 잔고를 직접 새로고침 중입니다.') => {
      if (!userId) return
      setRefreshNotice(notice)
      setForceRefreshing(true)
      try {
        const fresh = await accountApi.getPositions(userId, accountType, { refresh: true })
        queryClient.setQueryData(['account', 'positions', userId, accountType], fresh)
        void queryClient.invalidateQueries({
          queryKey: ['account', 'summary', userId, accountType],
          exact: true,
        })
      } catch {
        setRefreshNotice('KIS 잔고 새로고침에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      } finally {
        setForceRefreshing(false)
      }
    },
    [accountType, queryClient, userId],
  )

  useStompAccountBalance({
    userId,
    enabled: userId !== null && userId !== undefined,
    onBalanceChanged: (payload) => {
      if (payload.accountType !== accountType) return
      void forceRefreshPositions('체결 이벤트를 받아 KIS 보유 수량을 다시 확인 중입니다.')
    },
  })

  useEffect(() => {
    const timer = setInterval(() => setCooldownTick((tick) => tick + 1), 1000)
    return () => clearInterval(timer)
  }, [])

  useEffect(
    () => () => {
      for (const timer of postOrderRefreshTimersRef.current) {
        window.clearTimeout(timer)
      }
    },
    [],
  )

  function schedulePostOrderRefresh() {
    for (const timer of postOrderRefreshTimersRef.current) {
      window.clearTimeout(timer)
    }
    postOrderRefreshTimersRef.current = []
    void forceRefreshPositions('주문이 접수되었습니다. KIS 체결/잔고 반영을 확인 중입니다.')
    postOrderRefreshTimersRef.current = [1_500, 4_000, 8_000, 12_000].map((delay) =>
      window.setTimeout(() => {
        void forceRefreshPositions('주문 체결 반영을 다시 확인 중입니다.')
      }, delay),
    )
  }

  function handleOpenBuyMore(holding: AccountHoldingDto) {
    setOrderSelection({
      holding,
      side: 'BUY',
      initialQuantity: 1,
    })
  }

  function handleOpenPartialSell(holding: AccountHoldingDto) {
    setOrderSelection({
      holding,
      side: 'SELL',
      initialQuantity: Math.min(1, holding.quantity),
    })
  }

  function handleOpenClosePosition(holding: AccountHoldingDto) {
    setOrderSelection({
      holding,
      side: 'SELL',
      initialQuantity: holding.quantity,
    })
  }

  function handleOrderSubmitted(holding: AccountHoldingDto, side: OrderSide) {
    setPositionOrderCooldown(userId!, accountType, holding.code, side)
    setCooldownTick((tick) => tick + 1)
    schedulePostOrderRefresh()
  }

  // userId 미등록 시 셋업 안내
  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">보유 포지션</h1>
        <p className="text-sm text-gray-400">KIS 계좌 등록을 완료하면 보유 포지션을 확인할 수 있습니다.</p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  if (!accountTypeReady) {
    return (
      <div className="mx-auto max-w-5xl px-4 py-8">
        <div className="h-32 animate-pulse rounded-xl border border-gray-800 bg-gray-900/60" />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">보유 포지션</h1>
          <p className="mt-2 text-sm text-gray-500">
            KIS 실시간 보유 종목입니다. (현재 {accountType === 'REAL' ? '실전' : '모의'}계좌)
          </p>
          {tradeDisabledReason && (
            <p className="mt-1 text-xs text-yellow-500">{tradeDisabledReason}</p>
          )}
        </div>
        {/* 새로고침 버튼 */}
        <button
          onClick={() => void forceRefreshPositions('KIS 잔고를 직접 새로고침 중입니다.')}
          disabled={isFetching || forceRefreshing}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
          aria-label="포지션 목록 새로고침"
        >
          {isFetching || forceRefreshing ? '갱신 중...' : '새로고침'}
        </button>
      </div>

      {refreshNotice && (
        <div className="mb-4 rounded-lg border border-blue-900 bg-blue-950/30 px-4 py-3 text-xs text-blue-200">
          {refreshNotice}
          {!forceRefreshing && ' 최신 KIS 응답 기준으로 화면을 갱신했습니다.'}
        </div>
      )}

      {/* 계좌 유형 필터 (REAL / MOCK) */}
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <span className="text-xs text-gray-500">계좌</span>
        <div className="flex gap-2">
          {ACCOUNT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setAccountType(opt.value)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
                accountType === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 text-gray-400 hover:bg-gray-800 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 포지션 목록 */}
      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-36 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : isError && isCredentialMissing ? (
        <div className="rounded-xl border border-yellow-800 bg-yellow-900/20 p-6 text-center text-sm text-yellow-300">
          {accountType === 'MOCK' ? '모의' : '실전'}계좌 KIS 키가 등록되지 않았습니다.
          <p className="mt-1 text-xs text-yellow-500">
            우측 상단 설정(⚙️)에서 {accountType === 'MOCK' ? '모의(VPS)' : '실전(PROD)'} 키를 등록한 뒤 다시 시도해 주세요.
          </p>
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-6 text-center text-sm text-red-400">
          포지션 목록을 불러오지 못했습니다.
          <button
            onClick={() => void forceRefreshPositions('KIS 잔고를 직접 다시 확인 중입니다.')}
            className="ml-2 text-red-300 underline hover:text-red-200"
          >
            다시 시도
          </button>
        </div>
      ) : holdings.length === 0 ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-16 text-center">
          <p className="text-gray-500">보유 포지션이 없습니다.</p>
          <p className="mt-1 text-xs text-gray-600">
            매수 주문이 체결되면 종목이 여기에 표시됩니다.
          </p>
        </div>
      ) : (
        <>
          <div className="mb-3 text-xs text-gray-500">
            총 {holdings.length}개 종목
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {holdings.map((h) => (
              <AccountHoldingCard
                key={h.code}
                holding={h}
                canTrade={canTradeSelectedAccount}
                tradeDisabledReason={tradeDisabledReason}
                buyCooldownRemainSec={getPositionOrderCooldownRemainSec(userId, accountType, h.code, 'BUY')}
                sellCooldownRemainSec={getPositionOrderCooldownRemainSec(userId, accountType, h.code, 'SELL')}
                onBuyMore={handleOpenBuyMore}
                onSellPartial={handleOpenPartialSell}
                onClosePosition={handleOpenClosePosition}
              />
            ))}
          </div>
        </>
      )}

      <PositionOrderModal
        holding={orderSelection?.holding ?? null}
        side={orderSelection?.side ?? null}
        userId={userId}
        accountType={accountType}
        orderMode={orderMode}
        allowReal={runtimeFlags?.allowReal ?? false}
        hasConsent={runtimeFlags?.userConsented ?? false}
        killSwitchOn={killSwitchOn}
        cooldownRemainSec={
          orderSelection
            ? getPositionOrderCooldownRemainSec(
                userId,
                accountType,
                orderSelection.holding.code,
                orderSelection.side,
              )
            : 0
        }
        initialQuantity={orderSelection?.initialQuantity ?? 1}
        onClose={() => setOrderSelection(null)}
        onSubmitted={handleOrderSubmitted}
      />
    </div>
  )
}
