/**
 * useInstrumentUniverse — 전체 종목 universe 조회 훅.
 *
 * 섹터별 종목검색 화면에서 q·market·offset·limit 파라미터를 서버 페이징과 동기화한다.
 */
import { useQuery } from '@tanstack/react-query'
import {
  instrumentApi,
  type InstrumentUniverseParams,
  type InstrumentUniverseResponseDto,
} from '../instrument'

export function useInstrumentUniverse(params: InstrumentUniverseParams) {
  return useQuery<InstrumentUniverseResponseDto, Error>({
    queryKey: ['instrument-universe', params],
    queryFn: () => instrumentApi.list(params),
    staleTime: 60_000,
  })
}
