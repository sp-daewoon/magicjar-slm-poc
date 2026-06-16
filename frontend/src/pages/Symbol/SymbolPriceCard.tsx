/**
 * SymbolPriceCard — 현재가·전일대비·등락률·high·low·open·volume + stale 배지
 *
 * spec §6-2 정합:
 * - stale=true → 노란색 "장 마감" 배지 (ADR 0014 T3)
 * - source='closed' → "장 마감" 표시
 * - 상승(change>0) → 빨간색, 하락(change<0) → 파란색 (한국 주식 관례)
 * - 숫자 포맷: 천 단위 콤마 (ko-KR) + 원화 표시
 *
 * 결함 4 수정: getPriceBadge() 헬퍼로 장 시간 외 polling → "장 마감"(회색) 표시.
 *   - asOf가 30초 이내 → "실시간"(녹색)
 *   - polling + 장 중 → "지연 데이터"(노랑)
 *   - polling/closed + 장외 → "장 마감"(회색)
 *
 * dogfooding hotfix: STOMP TickPayload의 changeRate·priceChange·changeSign 신규 필드 우선 사용.
 *   - STOMP tick changeRate/priceChange가 non-null이면 현재가와 동일 source로 동기 표시.
 *   - null이면 REST SymbolDto.changeRate / SymbolDto.change fallback.
 *   - changeSign 코드로 방향 부호 렌더링 (한국식 색상·부호 정합).
 */
import type { SymbolDto, TickPayload } from '../../api/symbol'
import { getPriceBadge } from '../../lib/marketHours'

interface SymbolPriceCardProps {
  symbol: SymbolDto
  realtimeTick?: TickPayload | null
}

function formatPrice(value: string | number): string {
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '-'
  return num.toLocaleString('ko-KR')
}

function formatVolume(value: number): string {
  if (!value) return '-'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}백만`
  if (value >= 1_000) return `${(value / 1_000).toFixed(0)}천`
  return value.toLocaleString('ko-KR')
}

function formatChangeRate(value: string): string {
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  const sign = num >= 0 ? '+' : ''
  return `${sign}${num.toFixed(2)}%`
}

function formatChange(value: string): string {
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  const sign = num >= 0 ? '+' : ''
  return `${sign}${num.toLocaleString('ko-KR')}`
}

function normalizeSignedValue(value: string, changeSign: string | null | undefined): string {
  const num = parseFloat(value)
  if (isNaN(num)) return value
  if (changeSign === '1' || changeSign === '2') return Math.abs(num).toString()
  if (changeSign === '3') return '0'
  if (changeSign === '4' || changeSign === '5') return (-Math.abs(num)).toString()
  return value
}

/**
 * changeSign 코드 → 방향 부호 문자열 (한국 주식 KIS PRDY_VRSS_SIGN 정합)
 * "1"=상한·"2"=상승·"3"=보합·"4"=하한·"5"=하락
 */
export function changeSignToArrow(sign: string | null | undefined): string {
  switch (sign) {
    case '1':
    case '2':
      return '▲'
    case '3':
      return '·'
    case '4':
    case '5':
      return '▼'
    default:
      return ''
  }
}

/**
 * changeSign 코드 → 한국식 색상 클래스
 * 상승(1·2)=빨강, 보합(3)=회색, 하락(4·5)=파랑
 */
export function changeSignToColorClass(sign: string | null | undefined): string {
  switch (sign) {
    case '1':
    case '2':
      return 'text-red-400'
    case '3':
      return 'text-gray-300'
    case '4':
    case '5':
      return 'text-blue-400'
    default:
      return 'text-gray-300'
  }
}

/** 가격 변동에 따른 색상 클래스 (한국: 상승=빨강, 하락=파랑) — changeSign 없을 때 fallback */
function getChangeColorClass(changeStr: string): string {
  const change = parseFloat(changeStr)
  if (isNaN(change) || change === 0) return 'text-gray-300'
  return change > 0 ? 'text-red-400' : 'text-blue-400'
}

export function SymbolPriceCard({ symbol, realtimeTick }: SymbolPriceCardProps) {
  // STOMP tick이 있으면 실시간 가격 사용, 없으면 REST 응답 사용
  const currentPrice = realtimeTick?.price ?? symbol.currentPrice
  const source = realtimeTick?.source ?? symbol.source
  const asOf = realtimeTick?.ts ?? symbol.asOf

  // dogfooding hotfix: STOMP changeRate·priceChange·changeSign 우선 사용 (단일 source 동기화)
  // - STOMP tick의 값이 non-null이면 현재가와 동일 tick에서 온 값 → 싱크 보장
  // - null이면 REST SymbolDto의 초기 조회값으로 fallback
  const displayChangeSign = realtimeTick?.changeSign ?? null
  const displayChange = normalizeSignedValue(realtimeTick?.priceChange ?? symbol.change, displayChangeSign)
  const displayChangeRate = normalizeSignedValue(realtimeTick?.changeRate ?? symbol.changeRate, displayChangeSign)

  // 색상 결정: changeSign 코드 우선 → 없으면 change 숫자값 기반 fallback
  const changeColorClass = displayChangeSign
    ? changeSignToColorClass(displayChangeSign)
    : getChangeColorClass(displayChange)

  // 결함 4: 장 시간 + asOf 기반 배지 판정
  const badge = getPriceBadge(source, asOf)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      {/* 현재가 헤더 */}
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <div className="text-xs text-gray-500 mb-1">현재가</div>
          <div className="flex items-baseline gap-2">
            <span className="text-2xl font-bold text-white tabular-nums">
              {formatPrice(currentPrice)}
            </span>
            <span className="text-sm text-gray-500">원</span>
          </div>
        </div>

        {/* 등락 정보 — STOMP source 우선, null 시 REST fallback */}
        <div className={`text-right ${changeColorClass}`}>
          <div className="text-base font-semibold tabular-nums">
            {displayChangeSign && (
              <span className="mr-0.5 text-sm" aria-hidden="true">
                {changeSignToArrow(displayChangeSign)}
              </span>
            )}
            {formatChange(displayChange)}
          </div>
          <div className="text-sm tabular-nums">
            {formatChangeRate(displayChangeRate)}
          </div>
        </div>
      </div>

      {/* stale / source 배지 (결함 4: 장 시간 기반 분기) */}
      <div className="mb-3">
        {badge === 'live' && (
          <span className="inline-flex items-center gap-1 rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400" aria-hidden="true" />
            실시간
          </span>
        )}
        {badge === 'delayed' && (
          <span className="inline-flex items-center gap-1 rounded-full bg-yellow-900/40 px-2 py-0.5 text-xs text-yellow-400">
            <span className="h-1.5 w-1.5 rounded-full bg-yellow-400" aria-hidden="true" />
            지연 데이터
          </span>
        )}
        {badge === 'closed' && (
          <span className="inline-flex items-center gap-1 rounded-full bg-gray-800/60 px-2 py-0.5 text-xs text-gray-400">
            <span className="h-1.5 w-1.5 rounded-full bg-gray-500" aria-hidden="true" />
            장 마감
          </span>
        )}
      </div>

      {/* 보조 지표: 고·저·시·거래량 */}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 border-t border-gray-800 pt-3">
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">고가</dt>
          <dd className="text-xs font-mono text-red-300 tabular-nums">{formatPrice(symbol.high)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">저가</dt>
          <dd className="text-xs font-mono text-blue-300 tabular-nums">{formatPrice(symbol.low)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">시가</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatPrice(symbol.open)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">전일종가</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatPrice(symbol.previousClose)}</dd>
        </div>
        <div className="flex justify-between col-span-2">
          <dt className="text-xs text-gray-500">거래량</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatVolume(symbol.volume)}</dd>
        </div>
      </dl>
    </div>
  )
}
