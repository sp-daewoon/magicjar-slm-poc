/**
 * errorStatus — axios error → HTTP status 추출 helper (B-1 Dashboard accountType toggle)
 *
 * TanStack Query 의 `error: Error | null` 에서 axios response status 를 안전하게 꺼낸다.
 * 412 응답(자격증명 부재) 분기에서 사용 — MOCK/REAL 키 미등록 시 graceful 메시지.
 */

interface AxiosLikeError {
  response?: {
    status?: number
  }
}

/** error 객체에서 HTTP status 추출. axios 가 아니거나 null 이면 null. */
export function extractStatus(error: unknown): number | null {
  if (error === null || error === undefined) return null
  if (typeof error !== 'object') return null
  const maybeAxios = error as AxiosLikeError
  return maybeAxios.response?.status ?? null
}
