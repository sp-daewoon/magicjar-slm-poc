/**
 * useInstrument — 종목 마스터 detail TanStack Query hook
 *
 * 용도: SymbolDetailPage에서 instrument.active 등 마스터 메타 확인.
 * 가격(SymbolDto)은 별도 useSymbol — 둘은 데이터 소스가 다름:
 *   - SymbolDto: KIS inquire-price (실시간 가격) — 폐지 종목은 404/빈응답
 *   - InstrumentDetailDto: instrument 테이블 (마스터 메타) — 폐지 종목도 active=false로 조회 가능
 *
 * staleTime: 6h — 마스터 메타는 동기화 주기 단위로만 변함.
 */
import { useQuery } from '@tanstack/react-query'
import { instrumentApi } from '../instrument'

const INSTRUMENT_STALE_TIME_MS = 6 * 60 * 60 * 1000
const INSTRUMENT_GC_TIME_MS = 30 * 60 * 1000

export function useInstrument(code: string) {
  return useQuery({
    queryKey: ['instrument', code] as const,
    queryFn: () => instrumentApi.getByCode(code),
    staleTime: INSTRUMENT_STALE_TIME_MS,
    gcTime: INSTRUMENT_GC_TIME_MS,
    refetchOnWindowFocus: false,
    enabled: Boolean(code),
    retry: 1,
  })
}
