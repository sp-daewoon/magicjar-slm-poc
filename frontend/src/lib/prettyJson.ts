/**
 * prettyJson — detail 문자열을 사람이 읽기 좋게 정규화한다.
 *
 * 사용처: 시스템 이벤트 Detail 셀 hover 툴팁 / 클릭 상세 모달.
 *   - 입력이 유효한 JSON object/array 면 2-space indent pretty-print
 *   - JSON 이 아니면(빈 문자열·일반 텍스트) 원문 그대로 반환 (trim 만)
 *
 * 매매 결정·자본 흐름과 무관한 순수 표현 유틸. AI 호출 0.
 */

export interface PrettyJsonResult {
  /** 화면에 표시할 텍스트 (pretty 또는 원문) */
  text: string
  /** 입력이 유효한 JSON object/array 였는지 — 모노스페이스/구문 강조 여부 결정용 */
  isJson: boolean
}

/**
 * detail 문자열을 pretty 변환한다.
 *
 * @param raw 원본 detailJson 문자열 (null/undefined 안전)
 */
export function prettyJson(raw: string | null | undefined): PrettyJsonResult {
  const trimmed = (raw ?? '').trim()
  if (trimmed === '') {
    return { text: '', isJson: false }
  }

  // object / array 형태일 때만 JSON 파싱 시도 — "123"·"true" 같은 스칼라는 원문 유지
  const looksStructured =
    (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
    (trimmed.startsWith('[') && trimmed.endsWith(']'))
  if (!looksStructured) {
    return { text: trimmed, isJson: false }
  }

  try {
    const parsed = JSON.parse(trimmed)
    if (parsed === null || typeof parsed !== 'object') {
      return { text: trimmed, isJson: false }
    }
    return { text: JSON.stringify(parsed, null, 2), isJson: true }
  } catch {
    // 파싱 실패(잘린 JSON 등) → 원문 그대로 노출, 정보 손실 0
    return { text: trimmed, isJson: false }
  }
}
