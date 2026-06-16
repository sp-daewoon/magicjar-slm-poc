/**
 * KIS WS trId 코드 → 한국어 라벨 매핑.
 *
 * /admin/realtime 실시간 구독 상태 화면 전반(슬롯 게이지·구독 테이블·체결통보 배너)에서
 * trId 코드만 노출되어 "뭔지 모르겠다"는 최고 관리자 불만 해소.
 *
 * 표시 규약: `체결가 · H0STCNT0` 형태로 라벨 + 코드 병기.
 * 미지 trId 는 코드 그대로 라벨로 사용한다.
 *
 * 출처: KIS OpenAPI 실시간 시세 trId (docs/operations/kis-api-reference.md).
 */

/** trId → 한국어 라벨 (LOCK — 사용자 결재 명칭) */
export const KIS_TR_ID_LABEL: Record<string, string> = {
  H0UNCNT0: '체결가(통합)',
  H0UNASP0: '호가(통합)',
  H0UNMKO0: '장운영/VI(통합)',
  H0STCNT0: '체결가',
  H0NXCNT0: 'NXT 체결가',
  H0STOUP0: '시간외 단일가',
  H0STCNI0: '체결통보(실전)',
  H0STCNI9: '체결통보(모의)',
}

/** 등록된 trId 라벨인지 여부. 미지 trId 코드 중복 표기를 막는 UI 분기용. */
export function trIdHasLabel(trId: string): boolean {
  return Object.prototype.hasOwnProperty.call(KIS_TR_ID_LABEL, trId)
}

/**
 * trId → 한국어 라벨. 미지 trId 면 코드 그대로 반환.
 *
 * @example trIdLabel('H0STCNT0') // '체결가'
 * @example trIdLabel('H0XXXXX0') // 'H0XXXXX0'
 */
export function trIdLabel(trId: string): string {
  return KIS_TR_ID_LABEL[trId] ?? trId
}

/**
 * `체결가 · H0STCNT0` 형태의 라벨+코드 병기 텍스트.
 * 미지 trId 면 코드만 반환(중복 방지).
 *
 * @example trIdDisplay('H0STCNT0') // '체결가 · H0STCNT0'
 * @example trIdDisplay('H0XXXXX0') // 'H0XXXXX0'
 */
export function trIdDisplay(trId: string): string {
  const label = KIS_TR_ID_LABEL[trId]
  return label ? `${label} · ${trId}` : trId
}
